// 邻键误触纠错：几何邻键表 + 码表词频评分（纯 C++，零外部依赖）。
//
// 与 jiucuo 的离线评估同口径：
//   · 邻键 = 键心归一化距离 <= 1.35 键宽/键高（含上下与斜向；librime 自带 keyboard_map
//     只有同行左右，会漏掉 q→a 这类最常见的上下误触）
//   · 码表评分：精确命中 → log(频次占比)；合法前缀 → PREFIX；非法 → INVALID
#ifndef RIME_CORRECTION_GEOMETRY_H_
#define RIME_CORRECTION_GEOMETRY_H_

#include <array>
#include <string>
#include <unordered_map>
#include <vector>

namespace rime {
namespace correction {

// 键心（归一化：x/键盘宽，y/按键区高）与键尺寸，取实测键盘几何（jiucuo/data/layout.json）
struct KeyGeom {
  float x, y;
};

// 26 个字母键的键心（a..z），实测自 1272x826@3.5 键盘
extern const std::array<KeyGeom, 26> kKeyCenters;
constexpr float kKeyW = 0.0881f;
constexpr float kKeyH = 0.2195f;
constexpr float kNeighborRadius = 1.35f;  // 邻键半径（键宽/高为单位）
constexpr float kRowPitch = 0.2604f;      // 行距（按键区高归一化，同 layout.json spacing.y）

// 同行判定（行距的一半为界）
bool SameRow(int a, int b);

// 键心位移（键宽/键高单位）：from → to。结构化信道核与方向先验都用它。
struct KeyDelta {
  float dx, dy;
};
KeyDelta DeltaOf(int from, int to);

// 方向类别：误触方向先验的索引（见 correction_translator.h 的 dir_prior_*）
enum class TapDir { kSame = 0, kHoriz = 1, kVert = 2, kDiag = 3 };
TapDir DirOf(int pressed, int intended);

// 词频表（插件自带，二进制 WFQ1）：code → ln(频次)。
// 真机 wubi86 词典的 weight 列只有 {10,20}，语言分几乎是常数（Δ ≤ 0.69 nats），
// 压不过几何的 −2~−12 nats，纠错会永远不出词；这份表是语言项真正的信息来源。
class FreqTable {
 public:
  static FreqTable& Instance();
  // 加载词频包；返回是否成功。包内含词典名，用 Name() 判断是否适用本方案。
  bool Load(const std::string& path);
  bool loaded() const { return loaded_; }
  // 词频包声明的词典名（如 "wubi86"）；不同名词典的码含义不同，绝不能混用。
  const std::string& Name() const { return name_; }
  // 完整词条的 ln(频次)；不是词返回 0（对数域的"中性"）
  float Freq(const std::string& code) const;
  // 前缀质量：所有以本码为前缀的词的频次之和，取 ln。
  // 这是"用户还没打完"的语言证据；没有任何词以它为前缀时返回 0 = 死路。
  float Mass(const std::string& code) const;
  // 语言分：是词用词频，否则用前缀质量（死路为 0）
  float LangScore(const std::string& code) const;

 private:
  FreqTable() = default;
  std::unordered_map<std::string, float> freq_;
  std::unordered_map<std::string, float> mass_;
  std::string name_;
  bool loaded_ = false;
  bool tried_ = false;
};

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
