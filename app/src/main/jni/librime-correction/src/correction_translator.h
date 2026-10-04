// 邻键误触纠错插件（librime translator）。
//
// 与既有实现的区别（推倒重来的原因）：
//   · 旧实现：Kotlin 侧维护码表 + 造候选 + 管显示索引（与 Rime 平行，抓不到权重/用户词典）
//   · 本插件：作为 librime 的一个 translator 参与合成与排序 —— 自己查同一份字典/用户词典
//     拿语言项，候选以 type="correction" + quality 参与排序，且【只追加、绝不替换输入】
//
// 打分（与 jiucuo/scripts/channel_decode_p0.py 的离线评估同口径）：
//   score(code) = Σ_i log p_geo(意图字母 | 按下键_i, off_i) + λ · dict_score(code)
//   候选生成 = 原码 + 每个位置的"几何邻键"单字母替换
//   触发条件 = best 相对原码的增益 ≥ margin（干净输入下信道优势大 → 结构性不触发）
//
// 几何先验（每键 off / 按下键）由 Kotlin 侧经 correction_set_taps 侧信道送入；
// 未开启或无先验时插件直接返回空，对既有输入零影响。
#ifndef RIME_CORRECTION_TRANSLATOR_H_
#define RIME_CORRECTION_TRANSLATOR_H_

#include <array>
#include <mutex>
#include <string>
#include <vector>

#include <rime/gear/translator_commons.h>
#include <rime/translation.h>
#include <rime/translator.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/user_dictionary.h>

namespace rime {

// 一次字母按键的几何信息（Kotlin 侧 KeyButton 采集）
struct TapInfo {
  char letter;      // 按下的字母（小写）
  float off_x;      // (触点x - 按下键心x) / 键宽
  float off_y;      // (触点y - 按下键心y) / 键高
  bool has_offset;  // 是否有真实触点坐标
};

// 插件配置（schema 的 correction/ 命名空间）
struct CorrectionConfig {
  bool enable = false;
  std::string mode = "append";   // append：只追加纠错候选（v1）
  float lambda = 1.0f;           // 语言项权重
  float margin = 2.0f;           // 触发所需最小增益（nats）
  int max_candidates = 3;        // 最多给出几个纠错候选
  float min_off = 0.0f;          // 偏移幅度下限（0 = 不限制）
  int min_code_length = 2;
};

// ---- 侧信道：Kotlin → 插件（线程安全，单例） ----
class CorrectionPriors {
 public:
  static CorrectionPriors& Instance();

  void SetEnabled(bool enabled) { enabled_ = enabled; }
  bool enabled() const { return enabled_; }

  // 与 Rime 当前组合编码对齐的按键序列（右对齐截断到最近 max_taps 个）
  void SetTaps(const std::vector<TapInfo>& taps);
  void Clear();

  // 模型/码表文件路径（由 Kotlin 侧在初始化时送入）
  void SetModelPaths(const std::string& channel_path, const std::string& code_table_path);
  bool LoadModels();

  // 取与给定编码长度匹配的 tap 序列；不匹配返回 false（宁可不用先验，不用错位的）
  bool TapsFor(const std::string& code, std::vector<TapInfo>* out) const;

  static constexpr size_t kMaxTaps = 12;

 private:
  mutable std::mutex mutex_;
  bool enabled_ = false;
  std::vector<TapInfo> taps_;
  std::string channel_path_;
  std::string code_table_path_;
  bool models_requested_ = false;
};

// schema 补丁（Kotlin 侧经 JNI 使用；与 T9 插件的 GetT9SchemaPatches 同款）
std::vector<std::string> GetCorrectionSchemaPatches();

class CorrectionTranslator : public Translator, public TranslatorOptions {
 public:
  explicit CorrectionTranslator(const Ticket& ticket);
  ~CorrectionTranslator() override;

  an<Translation> Query(const string& input, const Segment& segment) override;

 private:
  CorrectionConfig config_;
  // 与 table_translator 用同一套组件加载同一份词典 / 用户词典（配置里指定 dictionary:）
  the<Dictionary> dict_;
  the<UserDictionary> user_dict_;
};

class CorrectionTranslatorComponent : public CorrectionTranslator::Component {
 public:
  CorrectionTranslatorComponent() = default;
  CorrectionTranslator* Create(const Ticket& ticket) override;
};

}  // namespace rime

// ---- JNI 侧信道接口（供 librime_jni 调用） ----
extern "C" {
// 设置几何先验：letters/off_x/off_y 等长；off 可为 NaN（无真实坐标）
void correction_set_taps(const char* letters, const float* off_x,
                         const float* off_y, int n);
void correction_set_enabled(int enabled);
void correction_clear_taps();
void correction_set_model_paths(const char* channel_path, const char* code_table_path);
// 插件日志文件路径（Kotlin 侧送入；不依赖 logcat，见 correction_translator.cc）
void correction_set_log_file(const char* path);
}

#endif  // RIME_CORRECTION_TRANSLATOR_H_
