#include "correction_geometry.h"

#include <cmath>

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

}  // namespace correction
}  // namespace rime
