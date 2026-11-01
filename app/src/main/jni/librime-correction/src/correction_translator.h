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
//   跨行候选附加方向先验 cross_row_penalty（真实误触 81% 同行，纵向被信道高估）
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
  int min_code_length = 2;

  // ── 结构化信道核（默认路径）──
  // 增益 = log p(落点 | 意图键) − log p(落点 | 按下键)，两者用**同一个**各向异性高斯核：
  //   log p = −½[ (Δx−off_x)²/σx² + (Δy−off_y)²/σy² ]
  // 其中 Δ = 意图键心 − 按下键心（键宽/键高单位），off = 落点 − 按下键心。
  // 关键：**所有候选共用同一核**，方向差异只由 Δ 本身承担——横向邻键 Δx≈1.12、
  // 纵向 Δy≈1.19，谁"更近"就由几何自己说话。旧实现用 TSI 训练的 MLP 输出直接当
  // 证据，而 MLP 学到的各向异性（干净 tap 横向 sd 0.226 > 纵向 0.164）会把"横向
  // 偏移"解释成正常抖动、把"纵向偏移"解释成误触，于是横向纠错偏少、纵向偏多——
  // 这正是真机反馈的现象。σ 由端上自校准（MAD）给出，见 correction_set_channel_params。
  float sigma_x = 0.272f;        // 横向 σ（键宽单位）；默认 = TSI 实测 30.5px / 本机键宽 112px
  float sigma_y = 0.206f;        // 纵向 σ（键高单位）；默认 = TSI 实测 33.8px / 本机键高 164px
  // 方向先验（nats）：log P(误触方向) − log P(同行)。TSI 43,735 次真实 tap 实测
  // 同行 78% / 斜向 17% / 跨行 6% → log(0.17/0.78)=−1.52、log(0.06/0.78)=−2.56。
  // 这是**先验**而不是补丁：它和几何似然正交，改它不会扭曲落点证据。
  float dir_prior_h = 0.0f;
  float dir_prior_d = -1.52f;
  float dir_prior_v = -2.56f;
  // 方向性投影门：off 沿"到该邻键"方向的投影比例（1 = 正好落在邻键心，0 = 原键心）。
  // 取代旧的 |off| ≥ min_off ——旧的是各向同性欧氏模长，在 112×164 的键上对横向
  // 比对纵向苛刻 1.46 倍，本身就是"横低竖高"的来源之一。
  // **按原码的语言状态分三档**（真机实测：wubi86 的语言分是 log 绝对频次，
  // 词条 8~14、合法前缀 −6、空码 −30；"前缀码→邻码是词"的语言差高达 +14 nats，
  // 语言项在那里几乎是常数，能区分"真按错"和"还没打完"的只剩落点几何）：
  float min_frac_empty = 0.0f;    // 空码：候选栏本来就是空的，救回是纯增益 → 不设几何门
  // 空码/死路态的触发阈值：候选栏空白时"能显示一个词"本身就比"什么都没有"强，
  // 因此只要比"无词"更好就给（gain>0），不必再要 1 nats 的优势。
  float margin_empty = 0.0f;
  // 死路判定：码长 >= 该值且没有完整词条时，认为用户已经打完、只是这条码没词。
  // 仅对"词典 weight 退化"的四码型码表（freq 包生效的 wubi86 等）启用——
  // 这类码表在打完整码之前候选栏通常是空的。
  int dead_end_len = 4;
  float min_frac_prefix = 0.50f;  // 合法前缀：用户多半只是还没打完 → 只有越过半程才算数
  float min_frac_word = 0.20f;    // 完整词：靠语言项区分，几何要求适中

  // 叠用 TSI 训练的 ONNX 信道（作为似然的补充项，默认关闭；开启时与高斯核相加）
  bool use_mlp = false;

  // ── 旧参数（保留兼容；新配置不再使用）──
  float min_off = 0.0f;
  float cross_row_penalty = 0.0f;
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

  // 模型路径：channel_path 仅 use_mlp 时需要；freq_path 是插件自带的词频表（WFQ1）
  void SetModelPaths(const std::string& channel_path,
                     const std::string& freq_path = std::string());
  bool LoadModels();

  // 端上自校准的信道宽度（键宽/键高单位）。App 未校准时保持 TSI 实测默认值。
  void SetChannelParams(float sigma_x, float sigma_y);
  float sigma_x() const;
  float sigma_y() const;

  // 取与给定编码长度匹配的 tap 序列；不匹配返回 false（宁可不用先验，不用错位的）
  bool TapsFor(const std::string& code, std::vector<TapInfo>* out) const;

  static constexpr size_t kMaxTaps = 12;

 private:
  mutable std::mutex mutex_;
  bool enabled_ = false;
  std::vector<TapInfo> taps_;
  std::string channel_path_;
  std::string freq_path_;
  bool models_requested_ = false;
  float sigma_x_ = 0.0f;   // 0 = 未校准，用插件配置里的默认值
  float sigma_y_ = 0.0f;
  bool calibrated_ = false;
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
  the<Dictionary> dict_;  the<UserDictionary> user_dict_;
  std::string dict_name_;    // 方案主词典名（translator/dictionary）
  bool freq_applies_ = false;  // 词频包是否对本方案生效（词典同名才用）
  bool freq_logged_ = false;   // 词频判定只记一次日志
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
// 端上自校准的信道宽度（键宽/键高单位）；<=0 表示回退默认值
void correction_set_channel_params(float sigma_x, float sigma_y);
// 插件日志文件路径（Kotlin 侧送入；不依赖 logcat，见 correction_translator.cc）
void correction_set_log_file(const char* path);
}

#endif  // RIME_CORRECTION_TRANSLATOR_H_
