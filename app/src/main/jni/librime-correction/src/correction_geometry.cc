#include "correction_geometry.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>

namespace rime {
namespace correction {

// 实测键心（a..z）
const std::array<KeyGeom, 26> kKeyCenters = {{
    {0.1038f, 0.3701f},  // a
    {0.5991f, 0.6299f},  // b
    {0.3994f, 0.6299f},  // c
    {0.3007f, 0.3701f},  // d
    {0.2563f, 0.1098f},  // e
    {0.3998f, 0.3701f},  // f
    {0.4988f, 0.3701f},  // g
    {0.5979f, 0.3701f},  // h
    {0.7449f, 0.1098f},  // i
    {0.6969f, 0.3701f},  // j
    {0.7960f, 0.3701f},  // k
    {0.8950f, 0.3701f},  // l
    {0.7987f, 0.6299f},  // m
    {0.6989f, 0.6299f},  // n
    {0.8424f, 0.1098f},  // o
    {0.9399f, 0.1098f},  // p
    {0.0597f, 0.1098f},  // q
    {0.3546f, 0.1098f},  // r
    {0.2020f, 0.3701f},  // s
    {0.4524f, 0.1098f},  // t
    {0.6474f, 0.1098f},  // u
    {0.4992f, 0.6299f},  // v
    {0.1580f, 0.1098f},  // w
    {0.2995f, 0.6299f},  // x
    {0.5499f, 0.1098f},  // y
    {0.1997f, 0.6299f},  // z
}};

const NeighborTable& NeighborTable::Instance() {
  static NeighborTable table;
  return table;
}

NeighborTable::NeighborTable() {
  for (int i = 0; i < 26; ++i) {
    for (int j = 0; j < 26; ++j) {
      if (i == j)
        continue;
      if (Distance(i, j) <= kNeighborRadius)
        neighbors_[i].push_back(j);
    }
  }
}

float NeighborTable::Distance(int a, int b) const {
  const float dx = (kKeyCenters[a].x - kKeyCenters[b].x) / kKeyW;
  const float dy = (kKeyCenters[a].y - kKeyCenters[b].y) / kKeyH;
  return std::sqrt(dx * dx + dy * dy);
}

FreqTable& FreqTable::Instance() {
  static FreqTable table;
  return table;
}

bool FreqTable::Load(const std::string& path) {
  if (loaded_ || tried_)
    return loaded_;   // 只尝试一次：失败后不再每次 Query 重试（否则日志被刷爆）
  tried_ = true;
  FILE* f = fopen(path.c_str(), "rb");
  if (!f)
    return false;
  char magic[4] = {0};
  uint32_t n = 0;
  if (fread(magic, 1, 4, f) != 4 ||
      (std::memcmp(magic, "WFQ3", 4) != 0 && std::memcmp(magic, "WFQ2", 4) != 0)) {
    fclose(f);
    return false;
  }
  uint8_t name_len = 0;
  if (fread(&name_len, 1, 1, f) != 1) {
    fclose(f);
    return false;
  }
  const bool has_mass = std::memcmp(magic, "WFQ3", 4) == 0;
  name_.resize(name_len);
  if (name_len > 0 && fread(&name_[0], 1, name_len, f) != name_len) {
    fclose(f);
    return false;
  }
  if (fread(&n, 4, 1, f) != 1) {
    fclose(f);
    return false;
  }
  std::string code;
  auto read_block = [&](std::unordered_map<std::string, float>* dst, uint32_t count) {
    dst->reserve(static_cast<size_t>(count) * 2);
    for (uint32_t i = 0; i < count; ++i) {
      uint8_t len = 0;
      if (fread(&len, 1, 1, f) != 1)
        return false;
      code.resize(len);
      if (len > 0 && fread(&code[0], 1, len, f) != len)
        return false;
      float lf = 0.0f;
      if (fread(&lf, 4, 1, f) != 1)
        return false;
      (*dst)[code] = lf;
    }
    return true;
  };
  bool ok = read_block(&freq_, n);
  if (ok && has_mass) {
    uint32_t nm = 0;
    ok = fread(&nm, 4, 1, f) == 1 && read_block(&mass_, nm);
  }
  fclose(f);
  loaded_ = !freq_.empty();
  return loaded_;
}

float FreqTable::Freq(const std::string& code) const {
  auto it = freq_.find(code);
  return it == freq_.end() ? 0.0f : it->second;
}

float FreqTable::Mass(const std::string& code) const {
  auto it = mass_.find(code);
  return it == mass_.end() ? 0.0f : it->second;
}

float FreqTable::LangScore(const std::string& code) const {
  auto it = freq_.find(code);
  if (it != freq_.end())
    return it->second;
  return Mass(code);
}

bool SameRow(int a, int b) {
  return std::fabs(kKeyCenters[a].y - kKeyCenters[b].y) < 0.5f * kRowPitch;
}

KeyDelta DeltaOf(int from, int to) {
  return KeyDelta{(kKeyCenters[to].x - kKeyCenters[from].x) / kKeyW,
                  (kKeyCenters[to].y - kKeyCenters[from].y) / kKeyH};
}

TapDir DirOf(int pressed, int intended) {
  const KeyDelta d = DeltaOf(pressed, intended);
  if (std::fabs(d.dy) * kKeyH < 0.5f * kRowPitch)
    return TapDir::kHoriz;
  if (std::fabs(d.dx) < 0.35f)
    return TapDir::kVert;
  return TapDir::kDiag;
}

}  // namespace correction
}  // namespace rime
