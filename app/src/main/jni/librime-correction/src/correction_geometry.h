// 邻键误触纠错：几何邻键表（纯 C++，零外部依赖）。
//
// 邻键 = 键心归一化距离 <= 1.35 键宽/键高（含上下与斜向；librime 自带 keyboard_map
// 只有同行左右，会漏掉 q→a 这类最常见的上下误触）。
// 语言侧证据（词频/词条）不在此处：改用当前 schema 自己的 rime 词典（见
// correction_translator 的 dict_score），五笔查五笔词典、拼音查拼音词典。
#ifndef RIME_CORRECTION_GEOMETRY_H_
#define RIME_CORRECTION_GEOMETRY_H_

#include <array>
#include <string>
#include <vector>

namespace rime {
namespace correction {

// 键心（归一化：x/键盘宽，y/键盘高）与键尺寸，取实测键盘几何（jiucuo/data/layout.json）
struct KeyGeom {
  float x, y;
};

// 26 个字母键的键心（a..z），实测自 1272x826@3.5 键盘
extern const std::array<KeyGeom, 26> kKeyCenters;
constexpr float kKeyW = 0.0881f;
constexpr float kKeyH = 0.2195f;
constexpr float kNeighborRadius = 1.35f;  // 邻键半径（键宽/高为单位）

class NeighborTable {
 public:
  static const NeighborTable& Instance();

  // 字母 index（0..25）→ 邻键 index 列表
  const std::vector<int>& Neighbors(int letter) const { return neighbors_[letter]; }

  // 距离（键宽/高归一化），用于几何信道打分
  float Distance(int a, int b) const;

 private:
  NeighborTable();
  std::array<std::vector<int>, 26> neighbors_;
};

}  // namespace correction
}  // namespace rime

#endif  // RIME_CORRECTION_GEOMETRY_H_
