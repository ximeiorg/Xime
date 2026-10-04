#include "correction_geometry.h"

#include <algorithm>
#include <cmath>
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

// ---------------------------------------------------------------- CodeTable

CodeTable& CodeTable::Instance() {
  static CodeTable table;
  return table;
}

size_t CodeTable::LowerBound(const std::string& code) const {
  size_t lo = 0, hi = codes_.size();
  while (lo < hi) {
    size_t mid = (lo + hi) / 2;
    if (codes_[mid] < code)
      lo = mid + 1;
    else
      hi = mid;
  }
  return lo;
}

bool CodeTable::LoadFromMemory(const void* data, size_t size) {
  if (loaded_)
    return true;
  const uint8_t* p = static_cast<const uint8_t*>(data);
  const uint8_t* end = p + size;
  if (size < 8 || std::memcmp(p, "JWC2", 4) != 0)
    return false;
  p += 4;
  uint32_t count = 0;
  std::memcpy(&count, p, 4);
  p += 4;
  codes_.clear();
  texts_.clear();
  logw_.clear();
  codes_.reserve(count);
  texts_.reserve(count);
  logw_.reserve(count);
  for (uint32_t i = 0; i < count; ++i) {
    if (p + 1 > end)
      return false;
    uint8_t clen = *p++;
    if (p + clen > end)
      return false;
    codes_.emplace_back(reinterpret_cast<const char*>(p), clen);
    p += clen;
    if (p + 1 > end)
      return false;
    uint8_t tlen = *p++;
    if (p + tlen > end)
      return false;
    texts_.emplace_back(reinterpret_cast<const char*>(p), tlen);
    p += tlen;
    if (p + 4 > end)
      return false;
    float w = 0.0f;
    std::memcpy(&w, p, 4);
    p += 4;
    logw_.push_back(w);
  }
  loaded_ = true;
  return true;
}

float CodeTable::Score(const std::string& code) const {
  if (!loaded_ || code.empty())
    return kInvalidScore;
  const size_t i = LowerBound(code);
  if (i >= codes_.size())
    return kInvalidScore;
  if (codes_[i] == code)
    return logw_[i];
  if (codes_[i].compare(0, code.size(), code) == 0)
    return kPrefixScore;
  return kInvalidScore;
}

const std::string& CodeTable::TopText(const std::string& code) const {
  static const std::string kEmpty;
  if (!loaded_ || code.empty())
    return kEmpty;
  const size_t i = LowerBound(code);
  if (i < codes_.size() && codes_[i] == code)
    return texts_[i];
  return kEmpty;
}

}  // namespace correction
}  // namespace rime
