// 独立 C++ 前向实现（无第三方依赖）。权重布局见文件头注释与 scripts/export_cpp.py。
#include "corrector.h"

#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace {

struct Layer {
  int lin;  // 该层输入维度（l0=hidden，l>0=2*hidden）
  std::vector<float> wih_f, whh_f, bih_f, bhh_f;  // forward
  std::vector<float> wih_b, whh_b, bih_b, bhh_b;  // reverse
};

struct Impl {
  int in_dim = 0, hidden = 0, n_layers = 0, vocab = 0, bidir = 1;
  std::vector<float> proj_w, proj_b;  // [hidden*in_dim], [hidden]
  std::vector<Layer> layers;
  std::vector<float> head_w, head_b;  // [vocab*2hidden], [vocab]
};

inline float sigmoid(float x) { return 1.0f / (1.0f + std::exp(-x)); }

// 单向 LSTM：in [W, lin] -> out [W, H]（reverse=1 时按时间倒序）
void lstm_dir(const float* in, int W, int lin, int H,
              const float* wih, const float* whh,
              const float* bih, const float* bhh, int reverse, float* out) {
  std::vector<float> h(H, 0.0f), c(H, 0.0f), gates(4 * H);
  for (int step = 0; step < W; ++step) {
    const int t = reverse ? (W - 1 - step) : step;
    const float* xt = in + (size_t)t * lin;
    for (int k = 0; k < 4 * H; ++k) {
      float s = bih[k] + bhh[k];
      const float* a = wih + (size_t)k * lin;
      for (int j = 0; j < lin; ++j) s += a[j] * xt[j];
      const float* b = whh + (size_t)k * H;
      for (int j = 0; j < H; ++j) s += b[j] * h[j];
      gates[k] = s;
    }
    for (int j = 0; j < H; ++j) {
      const float i = sigmoid(gates[j]);
      const float f = sigmoid(gates[H + j]);
      const float g = std::tanh(gates[2 * H + j]);
      const float o = sigmoid(gates[3 * H + j]);
      c[j] = f * c[j] + i * g;
      h[j] = o * std::tanh(c[j]);
      out[(size_t)t * H + j] = h[j];
    }
  }
}

bool read_f32(const uint8_t*& p, const uint8_t* end, size_t n, std::vector<float>& out) {
  if (p + n * 4 > end) return false;
  out.resize(n);
  std::memcpy(out.data(), p, n * 4);
  p += n * 4;
  return true;
}

}  // namespace

struct Corrector {
  Impl impl;
};

Corrector* corrector_create(const void* data, size_t size) {
  const uint8_t* p = static_cast<const uint8_t*>(data);
  const uint8_t* end = p + size;
  if (size < 28 || std::memcmp(p, "JCC1", 4) != 0) return nullptr;
  p += 4;
  uint32_t hdr[6];
  std::memcpy(hdr, p, sizeof(hdr));
  p += sizeof(hdr);
  Impl impl;
  uint32_t version = hdr[0];
  impl.in_dim = (int)hdr[1];
  impl.hidden = (int)hdr[2];
  impl.n_layers = (int)hdr[3];
  impl.vocab = (int)hdr[4];
  impl.bidir = (int)hdr[5];
  if (version != 1 || impl.bidir != 1) return nullptr;
  const int H = impl.hidden;
  if (!read_f32(p, end, (size_t)H * impl.in_dim, impl.proj_w)) return nullptr;
  if (!read_f32(p, end, (size_t)H, impl.proj_b)) return nullptr;
  impl.layers.resize(impl.n_layers);
  for (int l = 0; l < impl.n_layers; ++l) {
    Layer& L = impl.layers[l];
    L.lin = (l == 0) ? H : 2 * H;
    const size_t wi = (size_t)4 * H * L.lin, wh = (size_t)4 * H * H, bb = (size_t)4 * H;
    if (!read_f32(p, end, wi, L.wih_f) || !read_f32(p, end, wh, L.whh_f) ||
        !read_f32(p, end, bb, L.bih_f) || !read_f32(p, end, bb, L.bhh_f) ||
        !read_f32(p, end, wi, L.wih_b) || !read_f32(p, end, wh, L.whh_b) ||
        !read_f32(p, end, bb, L.bih_b) || !read_f32(p, end, bb, L.bhh_b))
      return nullptr;
  }
  if (!read_f32(p, end, (size_t)impl.vocab * 2 * H, impl.head_w)) return nullptr;
  if (!read_f32(p, end, (size_t)impl.vocab, impl.head_b)) return nullptr;

  Corrector* c = new Corrector();
  c->impl = std::move(impl);
  return c;
}

void corrector_free(Corrector* c) { delete c; }

int corrector_forward(const Corrector* c, const float* x, int W, float* out) {
  if (!c || !x || !out || W <= 0) return 1;
  const Impl& m = c->impl;
  const int H = m.hidden;
  // proj + relu: [W, in_dim] -> [W, H]
  std::vector<float> cur((size_t)W * H);
  for (int t = 0; t < W; ++t) {
    const float* xt = x + (size_t)t * m.in_dim;
    for (int j = 0; j < H; ++j) {
      float s = m.proj_b[j];
      const float* a = m.proj_w.data() + (size_t)j * m.in_dim;
      for (int k = 0; k < m.in_dim; ++k) s += a[k] * xt[k];
      cur[(size_t)t * H + j] = s > 0.0f ? s : 0.0f;
    }
  }
  // 各层 BiLSTM
  for (int l = 0; l < m.n_layers; ++l) {
    const Layer& L = m.layers[l];
    const int lin = L.lin;
    std::vector<float> fw((size_t)W * H), bw((size_t)W * H);
    lstm_dir(cur.data(), W, lin, H, L.wih_f.data(), L.whh_f.data(),
             L.bih_f.data(), L.bhh_f.data(), 0, fw.data());
    lstm_dir(cur.data(), W, lin, H, L.wih_b.data(), L.whh_b.data(),
             L.bih_b.data(), L.bhh_b.data(), 1, bw.data());
    // concat(fw, bw) -> [W, 2H]
    cur.assign((size_t)W * 2 * H, 0.0f);
    for (int t = 0; t < W; ++t) {
      std::memcpy(cur.data() + (size_t)t * 2 * H, fw.data() + (size_t)t * H, H * sizeof(float));
      std::memcpy(cur.data() + (size_t)t * 2 * H + H, bw.data() + (size_t)t * H, H * sizeof(float));
    }
  }
  // head
  const int Hin = 2 * H;
  for (int t = 0; t < W; ++t) {
    const float* ht = cur.data() + (size_t)t * Hin;
    for (int v = 0; v < m.vocab; ++v) {
      float s = m.head_b[v];
      const float* a = m.head_w.data() + (size_t)v * Hin;
      for (int j = 0; j < Hin; ++j) s += a[j] * ht[j];
      out[(size_t)t * m.vocab + v] = s;
    }
  }
  return 0;
}

void corrector_softmax_rows(float* out, int W, int vocab) {
  for (int t = 0; t < W; ++t) {
    float* r = out + (size_t)t * vocab;
    float mx = r[0];
    for (int v = 1; v < vocab; ++v) mx = r[v] > mx ? r[v] : mx;
    float sum = 0.0f;
    for (int v = 0; v < vocab; ++v) {
      r[v] = std::exp(r[v] - mx);
      sum += r[v];
    }
    for (int v = 0; v < vocab; ++v) r[v] /= sum;
  }
}

int corrector_in_dim(const Corrector* c) { return c ? c->impl.in_dim : 0; }
int corrector_vocab(const Corrector* c) { return c ? c->impl.vocab : 0; }
int corrector_hidden(const Corrector* c) { return c ? c->impl.hidden : 0; }
int corrector_layers(const Corrector* c) { return c ? c->impl.n_layers : 0; }
