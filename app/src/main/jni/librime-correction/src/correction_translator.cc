#include "correction_translator.h"

#include <android/log.h>

#include <algorithm>
#include <cstdio>
#include <cmath>
#include <unordered_map>
#include <unordered_set>

#include <rime/algo/syllabifier.h>
#include <rime/candidate.h>
#include <rime/segmentation.h>
#include <rime/config.h>
#include <rime/engine.h>
#include <rime/schema.h>
#include <rime/service.h>

#include "correction_channel.h"
#include "correction_geometry.h"

#define LOG_TAG "CorrectionTranslator"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace rime {

namespace {

inline int LetterIndex(char c) {
  if (c >= 'a' && c <= 'z')
    return c - 'a';
  return -1;
}

// 日志出口：直接追加到 Kotlin 指定的文件（部分 ROM 会静音应用 logcat，
// 而插件内部日志对排障是必需的）。路径由 Kotlin 经 correction_set_log_file 送入。
std::string g_log_path;
std::mutex g_log_mutex;

void EmitLog(const std::string& msg) {
  LOGI("%s", msg.c_str());
  if (g_log_path.empty())
    return;
  std::lock_guard<std::mutex> lock(g_log_mutex);
  if (FILE* f = fopen(g_log_path.c_str(), "a")) {
    fprintf(f, "[CorrectionPlugin] %s\n", msg.c_str());
    fclose(f);
  }
}

// ── 完整码词典查询（拼音多音节/五笔统一走音节图）──
// 为什么不用 Dictionary::LookupWords(exact)：script（拼音）词典的 prism 键只有
// 单音节形态（音节及简拼派生），"baidu" 这类多音节串 GetValue 永远落空——只有
// 单音节码（"bai"）查得到。正确路径与 ScriptTranslator 同款：Syllabifier 切出
// SyllableGraph，再 LookupAll 取「起点 0、终点=全长」的完整匹配桶。
// 五笔整码本身就是一个 syllable（无 algebra 时词码整体入 syllabary），
// 同一路径天然覆盖，行为与旧 LookupWords 等价。
//
// 两个输出的语义分工：
//  · lang_weight：margin 判定用的语言项，三值（+真实词频增强）——
//      完整词条：词典有真实 weight（log 域 > -30，如拼音）→ 用真值；
//                无 weight 列的词典（wubi86）→ 0.0（无词间区分度，但方向正确）
//      合法前缀（图可切分、无完整词条）：-6（介于词与非法之间）
//      非法码（图切不动）：-30（空码语义）
//  · has_entry/best_quality_weight：候选落位参照（exp 后与引擎正常候选 quality
//    同域；无 weight 词典的正常词 quality ≈ 0，纠错取 reference-0.5 排其身后）。
// 出词词条永远来自当前方案自己的词典/用户词典，绝不跨码表。
enum class CodeState { kEmpty = 0, kPrefix = 1, kWord = 2 };

struct ExactLookup {
  float lang_weight = -30.0f;         // ≈ ln(该码的语言概率质量)
  bool has_entry = false;             // 有完整词条（词典或用户词典）
  double best_quality_weight = 0.0;   // 落位用：词条 weight 最大值（用户词含 +0.5）
  std::vector<an<DictEntry>> entries;  // 出词用词条（用户词在前，共至多 max_entries 个）
  CodeState state = CodeState::kEmpty;
};

ExactLookup LookupExactCode(Dictionary* dict, UserDictionary* user_dict,
                            const std::string& code,
                            const hash_set<std::string>* blacklist,
                            size_t max_entries,
                            bool use_user_dict,
                            bool use_freq) {
  ExactLookup r;
  if (code.empty() || !dict || !dict->loaded())
    return r;
  float lang = -30.0f;
  CodeState state = CodeState::kEmpty;
  // 用户词典：userdb 键即完整输入串，直接查（用户词优先出、优先计分；
  // 其 weight = log(上屏次数) ≥ 0，天然有区分度）
  if (use_user_dict && user_dict && user_dict->loaded()) {
    UserDictEntryIterator uter;
    user_dict->LookupWords(&uter, code, false);
    for (; !uter.exhausted() && r.entries.size() < max_entries; uter.Next()) {
      const an<DictEntry>& e = uter.Peek();
      if (!e || e->text.empty())
        continue;
      lang = std::max(lang, static_cast<float>(e->weight) + 0.5f);  // 用户词加成与 table_translator 同口径
      r.best_quality_weight = std::max(r.best_quality_weight, e->weight + 0.5);
      r.has_entry = true;
      r.entries.push_back(e);
    }
  }
  // 静态词典：音节图 + 完整匹配桶（多音节唯一正确路径）
  Syllabifier syllabifier;
  SyllableGraph graph;
  if (syllabifier.BuildSyllableGraph(code, *dict->prism(), &graph) >=
      static_cast<int>(code.size())) {
    // 图可切分 = 用户可能只是还没打完（后续由词频质量覆盖成 kPrefix / kEmpty）
    state = CodeState::kPrefix;
    lang = std::max(lang, 0.0f);
    auto buckets = dict->LookupAll(graph, {0}, blacklist);
    const auto start_it = buckets.find(0);
    if (start_it != buckets.end() && start_it->second) {
      const auto full = start_it->second->find(code.size());
      if (full != start_it->second->end()) {
        DictEntryIterator& iter = full->second;
        for (; !iter.exhausted() && r.entries.size() < max_entries; iter.Next()) {
          const an<DictEntry>& e = iter.Peek();
          if (!e || e->text.empty())
            continue;
          // DictEntryIterator 的 e->weight = 表层词频 − log(1e8) + credibility
          // （dictionary.cc Peek()），不是 log 词频——必须还原偏移再当语言分，
          // 否则拼音词条（8.8→-9.6）会被压到合法前缀（-6）之下、纠错增益恒负。
          // 无 weight 列的词典编译后表层 weight = log(DBL_EPSILON) ≈ -708（比
          // 空码还低，是哨兵值而非频次）——按"无频次证据"取 0，避免词比非词分还低
          const double raw_weight = e->weight + 18.420680743952367 /* log(1e8) */;
          const float w = raw_weight > -30.0 ? static_cast<float>(raw_weight) : 0.0f;
          lang = std::max(lang, w);
          r.best_quality_weight = std::max(r.best_quality_weight, e->weight);
          r.has_entry = true;
          state = CodeState::kWord;
          r.entries.push_back(e);
        }
      }
    }
  }
  if (use_freq) {
    // 真机 wubi86 词典的 weight 列实测只有 {10,20}（语言分近似常数），必须叠加
    // 插件自带的真实词频，否则词与词之间没有区分度，纠错无从判断哪个更该出。
    // 词频包按词典名隔离：只对同名词典生效，第三方方案用自己的词典权重。
    auto& ft = correction::FreqTable::Instance();
    if (state == CodeState::kWord) {
      lang = std::max(lang, ft.Freq(code));
    } else {
      // 还没有完整词条：语言分 = 所有以本码为前缀的词的频次质量（ln）。
      // >0 表示"确实有词这么开头"（用户多半还没打完）；
      // ==0 表示词库里没有任何词以它开头 —— 这条码是死路，按空码处理。
      const float m = ft.Mass(code);
      if (m > 0.0f) {
        state = CodeState::kPrefix;
        lang = m;
      } else {
        state = CodeState::kEmpty;
        lang = -30.0f;
      }
    }
  }
  r.lang_weight = lang;
  r.state = state;
  return r;
}

}  // namespace

// ---------------------------------------------------------------- 侧信道

CorrectionPriors& CorrectionPriors::Instance() {
  static CorrectionPriors priors;
  return priors;
}

void CorrectionPriors::SetTaps(const std::vector<TapInfo>& taps) {
  std::lock_guard<std::mutex> lock(mutex_);
  taps_ = taps;
  if (taps_.size() > kMaxTaps)
    taps_.erase(taps_.begin(), taps_.end() - kMaxTaps);
}

void CorrectionPriors::Clear() {
  std::lock_guard<std::mutex> lock(mutex_);
  taps_.clear();
}

void CorrectionPriors::SetModelPaths(const std::string& channel_path,
                                     const std::string& freq_path) {
  std::lock_guard<std::mutex> lock(mutex_);
  channel_path_ = channel_path;
  freq_path_ = freq_path;
  models_requested_ = true;
}

void CorrectionPriors::SetChannelParams(float sigma_x, float sigma_y) {
  std::lock_guard<std::mutex> lock(mutex_);
  sigma_x_ = sigma_x;
  sigma_y_ = sigma_y;
  calibrated_ = sigma_x > 0.0f && sigma_y > 0.0f;
}

float CorrectionPriors::sigma_x() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return calibrated_ ? sigma_x_ : 0.0f;
}

float CorrectionPriors::sigma_y() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return calibrated_ ? sigma_y_ : 0.0f;
}

bool CorrectionPriors::LoadModels() {
  std::string channel_path, freq_path;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!models_requested_)
      return false;
    channel_path = channel_path_;
    freq_path = freq_path_;
  }
  // 词频表是默认路径的必需品（语言项的唯一真实来源），与 use_mlp 无关
  if (!freq_path.empty()) {
    auto& freq = correction::FreqTable::Instance();
    if (!freq.loaded() && !freq.Load(freq_path))
      EmitLog("freq table load failed: " + freq_path);
  }
  auto& channel = correction::ChannelModel::Instance();
  if (!channel.loaded() && !channel_path.empty())
    channel.Load(channel_path);
  return channel.loaded();
}

bool CorrectionPriors::TapsFor(const std::string& code,
                               std::vector<TapInfo>* out) const {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!out || code.empty() || taps_.size() < code.size())
    return false;
  // 组合编码可能与 tap 序列不完全一致（上屏后残留、反查、T9 等）。
  // 旧策略是"任一位置不符就整条放弃"——真机上这会让纠错在整段输入里彻底哑火
  // （一次错位后缓冲区再也追不上编码，日志里 taps-unaligned 成片出现）。
  // 现在改为按位置对齐、逐个位置判定：字母一致才用它的落点，不一致则该位置
  // 标记为"无几何证据"（off=0、has_offset=false），仍允许语言项驱动救回。
  // 至少一半位置对得上才采用，避免彻底错位时拿别人的落点乱打分。
  const size_t n = code.size();
  const size_t off = taps_.size() - n;
  size_t matched = 0;
  for (size_t i = 0; i < n; ++i)
    if (taps_[off + i].letter == code[i])
      ++matched;
  if (matched * 2 < n)
    return false;
  out->assign(taps_.end() - n, taps_.end());
  for (size_t i = 0; i < n; ++i) {
    if ((*out)[i].letter != code[i])
      (*out)[i].has_offset = false;
  }
  return true;
}

// ---------------------------------------------------------------- translator

CorrectionTranslator::CorrectionTranslator(const Ticket& ticket)
    : Translator(ticket), TranslatorOptions(ticket) {
  if (!engine_)
    return;

  // 复用 table_translator 的 dictionary/user_dict 组件：同一份词典与用户词典
  // （配置键与五笔 table_translator 相同，故无需额外配置）
  if (auto dictionary = Dictionary::Require("dictionary")) {
    dict_.reset(dictionary->Create(ticket));
    if (dict_)
      dict_->Load();
  }
  if (auto user_dictionary = UserDictionary::Require("user_dictionary")) {
    user_dict_.reset(user_dictionary->Create(ticket));
    if (user_dict_) {
      user_dict_->Load();
      if (dict_)
        user_dict_->Attach(dict_->primary_table(), dict_->prism());
    }
  }
  // 方案的主词典名（translator/dictionary）：词频包是按词典名分发的，
  // 只有同名词典才允许用——不同方案的码含义不同，混用等于投毒。
  if (Config* config = engine_->schema()->config())
    config->GetString("translator/dictionary", &dict_name_);

  if (Config* config = engine_->schema()->config()) {
    // 配置键注入在 correction/ 命名空间（见 nativeEnsureCorrectedSchemaPatch）；
    // 不能用 name_space_（= 组件名 correction_translator，无 @ 后缀），否则读不到、
    // enable 保持默认 false，Query 在第一道门就静默返回。
    const std::string cfg_ns = "correction";
    config->GetBool(cfg_ns + "/enable", &config_.enable);
    config->GetString(cfg_ns + "/mode", &config_.mode);
    double lambda = config_.lambda, margin = config_.margin;
    config->GetDouble(cfg_ns + "/lambda", &lambda);
    config->GetDouble(cfg_ns + "/margin", &margin);
    config_.lambda = static_cast<float>(lambda);
    config_.margin = static_cast<float>(margin);
    // 结构化信道核参数
    double sx = config_.sigma_x, sy = config_.sigma_y;
    double f_empty = config_.min_frac_empty, f_prefix = config_.min_frac_prefix;
    double f_word = config_.min_frac_word;
    double p_h = config_.dir_prior_h, p_d = config_.dir_prior_d, p_v = config_.dir_prior_v;
    config->GetDouble(cfg_ns + "/sigma_x", &sx);
    config->GetDouble(cfg_ns + "/sigma_y", &sy);
    config->GetDouble(cfg_ns + "/min_frac_empty", &f_empty);
    double margin_empty = config_.margin_empty;
    config->GetDouble(cfg_ns + "/margin_empty", &margin_empty);
    config_.margin_empty = static_cast<float>(margin_empty);
    config->GetInt(cfg_ns + "/dead_end_len", &config_.dead_end_len);
    config->GetDouble(cfg_ns + "/min_frac_prefix", &f_prefix);
    config->GetDouble(cfg_ns + "/min_frac_word", &f_word);
    config->GetDouble(cfg_ns + "/dir_prior_h", &p_h);
    config->GetDouble(cfg_ns + "/dir_prior_d", &p_d);
    config->GetDouble(cfg_ns + "/dir_prior_v", &p_v);
    config_.sigma_x = static_cast<float>(sx);
    config_.sigma_y = static_cast<float>(sy);
    config_.min_frac_empty = static_cast<float>(f_empty);
    config_.min_frac_prefix = static_cast<float>(f_prefix);
    config_.min_frac_word = static_cast<float>(f_word);
    config_.dir_prior_h = static_cast<float>(p_h);
    config_.dir_prior_d = static_cast<float>(p_d);
    config_.dir_prior_v = static_cast<float>(p_v);
    config->GetBool(cfg_ns + "/use_mlp", &config_.use_mlp);

    // 补丁版本：老机器上 custom.yaml 里可能还留着上一代参数（margin 2.0、
    // cross_row_penalty −0.7…），这些会和新的方向先验叠加、把模型带偏。
    // 版本不符时一切调参键一律忽略，只用插件内置默认值——参数的正确性不依赖
    // "用户是否重新部署过方案"。
    int patch_version = 0;
    config->GetInt(cfg_ns + "/patch_version", &patch_version);
    if (patch_version < 2) {
      config_.margin = CorrectionConfig{}.margin;
      config_.lambda = CorrectionConfig{}.lambda;
      config_.sigma_x = CorrectionConfig{}.sigma_x;
      config_.sigma_y = CorrectionConfig{}.sigma_y;
      config_.min_frac_empty = CorrectionConfig{}.min_frac_empty;
      config_.margin_empty = CorrectionConfig{}.margin_empty;
      config_.dead_end_len = CorrectionConfig{}.dead_end_len;
      config_.min_frac_prefix = CorrectionConfig{}.min_frac_prefix;
      config_.min_frac_word = CorrectionConfig{}.min_frac_word;
      config_.dir_prior_h = CorrectionConfig{}.dir_prior_h;
      config_.dir_prior_d = CorrectionConfig{}.dir_prior_d;
      config_.dir_prior_v = CorrectionConfig{}.dir_prior_v;
      config_.use_mlp = false;
    }
    // 旧参数一律不再读取（cross_row_penalty 会与新方向先验叠加，必须废弃）
    config_.min_off = 0.0f;
    config_.cross_row_penalty = 0.0f;
    config->GetInt(cfg_ns + "/max_candidates", &config_.max_candidates);
    config->GetInt(cfg_ns + "/min_code_length", &config_.min_code_length);
  }
  LOGI("translator created: enable=%d lambda=%.2f margin=%.2f max=%d mlp=%d",
       static_cast<int>(config_.enable), config_.lambda, config_.margin,
       config_.max_candidates, static_cast<int>(config_.use_mlp));
  // 排障（定位拼音下 Query 不被调用的问题；logcat 在部分 ROM 静音，写文件留痕）
  {
    std::string tag_list;
    for (const auto& t : tags()) tag_list += t + ",";
    EmitLog("constructed ns=" + name_space_ + " tags=" + tag_list +
            " dict_loaded=" + (dict_ && dict_->loaded() ? "1" : "0") +
            " prism=" + (dict_ && dict_->prism() ? "1" : "0") +
            " userdict=" + (user_dict_ && user_dict_->loaded() ? "1" : "0") +
            " dict=" + dict_name_ +
            " kernel=sx=" + std::to_string(config_.sigma_x) +
            ",sy=" + std::to_string(config_.sigma_y) +
            ",frac=" + std::to_string(config_.min_frac_empty) + "/" +
            std::to_string(config_.min_frac_prefix) + "/" +
            std::to_string(config_.min_frac_word) +
            ",prior=" + std::to_string(config_.dir_prior_h) + "/" +
            std::to_string(config_.dir_prior_d) + "/" +
            std::to_string(config_.dir_prior_v));
  }
}

an<Translation> CorrectionTranslator::Query(const string& input,
                                            const Segment& segment) {
  // 排障插桩：同一输入串只记一次早退原因（防刷屏；定位后移除）
  static std::string last_skip;
  auto skip = [&](const std::string& why) -> an<Translation> {
    const std::string key = input + "|" + why;
    if (last_skip != key) {
      last_skip = key;
      EmitLog("skip input=" + input + " why=" + why);
    }
    return nullptr;
  };
  if (!config_.enable)
    return skip("disabled");
  if (!segment.HasAnyTagIn(tags()))
    return skip("tags");
  auto& priors = CorrectionPriors::Instance();
  if (!priors.enabled())
    return skip("priors-off");

  // 只处理纯字母编码（拼音反查/数字/T9 一律跳过）
  std::string code = input;
  if (static_cast<int>(code.size()) < config_.min_code_length)
    return nullptr;
  for (char c : code) {
    if (LetterIndex(c) < 0)
      return skip("non-letter:" + std::string(1, c));
  }

  // 词频表是默认路径的必需品；ONNX 信道仅在 use_mlp 时加载
  priors.LoadModels();
  // 词频包只在词典同名时生效（第三方方案/拼音方案用自己的词典权重）
  freq_applies_ = correction::FreqTable::Instance().loaded() &&
                  correction::FreqTable::Instance().Name() == dict_name_;
  if (!freq_logged_) {
    freq_logged_ = true;
    auto& ft = correction::FreqTable::Instance();
    EmitLog(std::string("freq pack: dict=") + (dict_name_.empty() ? "?" : dict_name_) +
            " pack=" + (ft.loaded() ? ft.Name() : std::string("none")) +
            " applies=" + (freq_applies_ ? "1" : "0"));
  }

  std::vector<TapInfo> taps;
  if (!priors.TapsFor(code, &taps))
    return skip("taps-unaligned");  // 无对齐先验：不猜（宁可不纠，也不误改）

  const int L = static_cast<int>(code.size());
  auto& neighbors = correction::NeighborTable::Instance();

  // 信道宽度：端上自校准（MAD，见 CorrectionPriors.SetChannelParams）优先，
  // 否则用配置默认值（TSI 实测 px 换算到本机键尺寸）。
  float sx = priors.sigma_x() > 0.0f ? priors.sigma_x() : config_.sigma_x;
  float sy = priors.sigma_y() > 0.0f ? priors.sigma_y() : config_.sigma_y;
  if (!(sx > 0.02f)) sx = 0.272f;
  if (!(sy > 0.02f)) sy = 0.206f;
  const float inv_sxx = 1.0f / (sx * sx);
  const float inv_syy = 1.0f / (sy * sy);

  // 1) 枚举候选：原码 + 每个位置的几何邻键单字母替换
  std::vector<std::string> cands;
  cands.reserve(1 + L * 4);
  cands.push_back(code);
  for (int i = 0; i < L; ++i) {
    const int li = LetterIndex(code[i]);
    if (li < 0)
      return nullptr;
    for (int nb : neighbors.Neighbors(li)) {
      std::string v = code;
      v[i] = static_cast<char>('a' + nb);
      cands.push_back(std::move(v));
    }
  }
  if (cands.size() <= 1)
    return nullptr;

  auto& channel = correction::ChannelModel::Instance();
  std::vector<float> probs;
  if (config_.use_mlp) {
    if (!channel.loaded()) {
      static bool warned = false;
      if (!warned) {
        warned = true;
        EmitLog("use_mlp=1 but channel model missing");
      }
      return nullptr;
    }
    std::vector<float> feats;
    feats.reserve(static_cast<size_t>(L) * correction::ChannelModel::kFeatDim);
    for (int i = 0; i < L; ++i) {
      const int pi = LetterIndex(taps[i].letter);
      feats.push_back(taps[i].has_offset ? taps[i].off_x : 0.0f);
      feats.push_back(taps[i].has_offset ? taps[i].off_y : 0.0f);
      for (int k = 0; k < 26; ++k)
        feats.push_back(k == pi ? 1.0f : 0.0f);
    }
    probs.assign(static_cast<size_t>(L) * correction::ChannelModel::kVocab, 0.0f);
    if (!channel.Predict(feats.data(), L, probs.data()))
      return nullptr;
  }

  // 位置几何分（nats）。两种似然共用同一接口，增益=候选分−原码分：
  //  · 结构化核（默认）：log p(落点|意图键) 的**同一各向异性高斯核**，候选之间只差
  //    「落点相对该键心的残差」——方向差异全部由键心位移承担，不存在"横/竖被
  //    差别对待"的自由度；
  //  · use_mlp：TSI 训练的 26 类校准分布（旧路径，保留做对照）。
  auto geo_at = [&](int pos, char ch) -> float {
    const int ci = LetterIndex(ch);
    const int pi = LetterIndex(code[pos]);
    if (ci < 0 || pi < 0)
      return -1e9f;
    if (config_.use_mlp)
      return std::log(std::max(
          probs[static_cast<size_t>(pos) * correction::ChannelModel::kVocab + ci],
          1e-9f));
    const float ox = taps[pos].has_offset ? taps[pos].off_x : 0.0f;
    const float oy = taps[pos].has_offset ? taps[pos].off_y : 0.0f;
    const correction::KeyDelta d = correction::DeltaOf(pi, ci);
    const float rx = d.dx - ox;
    const float ry = d.dy - oy;
    return -0.5f * (rx * rx * inv_sxx + ry * ry * inv_syy);
  };

  // 语言分：当前方案自己的词典/用户词典中该完整码的匹配结果（音节图完整匹配，
  // 拼音多音节/五笔统一）。五笔查五笔词典、拼音查拼音词典，绝不跨码制；
  // 查无词条返回 -30（空码语义：该码在该方案下无语言证据）。
  // memo：每个候选码只查一次词典（打分与出词共用；查询含音节图构建，避免重复）。
  std::unordered_map<std::string, ExactLookup> lookup_memo;
  auto lookup = [&](const std::string& c) -> ExactLookup {
    auto it = lookup_memo.find(c);
    if (it == lookup_memo.end()) {
      it = lookup_memo
               .emplace(c, LookupExactCode(dict_.get(), user_dict_.get(), c,
                                           &blacklist(), 2,
                                           !IsUserDictDisabledFor(c),
                                           freq_applies_))
               .first;
    }
    return it->second;  // 值返回：unordered_map rehash 会使引用失效
  };

  auto geo_sum = [&](const std::string& c) -> float {
    float s = 0.0f;
    for (int i = 0; i < L; ++i)
      s += geo_at(i, c[i]);
    return s;
  };

  // 前缀态判定（原码可切分、但还没有完整词条）：用更严的 proj 阈值，见 min_frac_prefix
  const ExactLookup base_lk = lookup(code);
  const bool base_prefix_only = base_lk.state == CodeState::kPrefix;
  // 死路态：这条码根本出不了词，候选栏是空的——
  //   · 空码（连前缀都不是），或
  //   · 已经打满长度（>= dead_end_len）却没有完整词条（打完了，但这条码没词）。
  // 此时纠错是**纯增益**：不抢候选位、不干扰已存在的结果，只把空栏填上。
  // 只对四码型码表启用（dead_end_len=4；拼音等变长码表由注入方写 0 关闭），
  // 避免把"拼音还在打第四五个字母"这种正常中间态当成死路。
  const bool dead_end =
      !base_lk.has_entry &&
      (base_lk.state == CodeState::kEmpty ||
       (config_.dead_end_len > 0 && L >= config_.dead_end_len));
  const float margin_eff = dead_end ? config_.margin_empty : config_.margin;
  const float base_geo = geo_sum(code);
  const float base = base_geo + config_.lambda * base_lk.lang_weight;

  auto dir_prior_of = [&](int from, int to) -> float {
    switch (correction::DirOf(from, to)) {
      case correction::TapDir::kHoriz:
        return config_.dir_prior_h;
      case correction::TapDir::kVert:
        return config_.dir_prior_v;
      case correction::TapDir::kDiag:
        return config_.dir_prior_d;
      default:
        return 0.0f;
    }
  };
  const char* dir_name[] = {"S", "H", "V", "D"};

  // 2) 触发判定：gain ≥ margin，且落点沿"到该邻键"方向走到位（三档 min_frac_*）
  struct Probe { float gain; float geo; float proj; int dir; };
  struct Hit {
    float gain;
    std::string code;
    float geo;
    float proj;
    int dir;
  };
  std::vector<Hit> hits;
  std::string top_code;   // 排障：全量最优候选（无论是否达 margin）
  Probe top{};
  top.gain = -1e9f;
  for (size_t k = 1; k < cands.size(); ++k) {
    const std::string& v = cands[k];
    int diff_pos = -1;
    for (int i = 0; i < L; ++i) {
      if (v[i] != code[i]) {
        diff_pos = i;
        break;
      }
    }
    if (diff_pos < 0)
      continue;
    const int oi = LetterIndex(code[diff_pos]);
    const int ni = LetterIndex(v[diff_pos]);
    if (oi < 0 || ni < 0)
      continue;
    const correction::KeyDelta d = correction::DeltaOf(oi, ni);
    // 方向性投影门：proj = 落点沿 d 的投影比例（1 = 正好落在邻键心，0 = 在原键心）。
    // 用 Mahalanobis 内积（同一 σ 归一化），横/竖/斜因此在同一尺度上可比——
    // 旧实现用各向同性 |off|，在 112×164 的键上对横向比对纵向苛刻 1.46 倍，
    // 是"横向纠错偏少、纵向偏多"的第一层来源（真机日志里 69% 的 miss 被它整批滤掉）。
    // 前缀态（原码可切分但还不是完整词）用更严的阈值：用户此刻多半只是还没打完，
    // 邻近前缀常常也拼得出词，只有落点确实朝那个键走了一小半才值得打扰。
    float proj = 1.0f;
    if (taps[diff_pos].has_offset) {
      const float ax = d.dx * inv_sxx;
      const float ay = d.dy * inv_syy;
      const float denom = d.dx * ax + d.dy * ay;
      proj = denom > 1e-6f
                 ? (taps[diff_pos].off_x * ax + taps[diff_pos].off_y * ay) / denom
                 : 0.0f;
      // 三档：空码（连前缀都不是）→ 激进；合法前缀（多半还没打完）→ 保守；
      // 完整词 → 中间，靠语言项区分。
      const float thr = dead_end
                            ? config_.min_frac_empty
                            : (base_prefix_only ? config_.min_frac_prefix
                                                : config_.min_frac_word);
      if (proj < thr)
        continue;
    }
    const float geo_gain = geo_at(diff_pos, v[diff_pos]) - geo_at(diff_pos, code[diff_pos]);
    float gain = geo_gain +
                 config_.lambda * (lookup(v).lang_weight - base_lk.lang_weight) +
                 dir_prior_of(oi, ni);
    if (config_.cross_row_penalty != 0.0f && !correction::SameRow(oi, ni))
      gain += config_.cross_row_penalty;  // 旧参数（默认 0，仅显式配置时叠加）
    // 旧的前缀门（要求 geo>0）已废弃：几何项对"按偏但仍在原键格内"的常态本来就是负值
    // （真机日志里 geo 几乎全负），要求它为正等于在前缀态永久关闭纠错。
    // 前缀态的门现在交给上面的三档 proj 阈值（min_frac_empty/prefix/word）。
    const int dir = static_cast<int>(correction::DirOf(oi, ni));
    if (gain > top.gain) {
      top = Probe{gain, geo_gain, proj, dir};
      top_code = v;
    }
    if (gain >= margin_eff)
      hits.push_back(Hit{gain, v, geo_gain, proj, dir});
  }
  if (hits.empty()) {
    // 排障：全量打分细节（top 候选的方向/投影/几何-语言分项；定位后移除）
    std::string msg = "miss input=" + code +
                      " base_lang=" + std::to_string(lookup(code).lang_weight) +
                      " ncands=" + std::to_string(cands.size());
    if (dead_end)
      msg += " dead=1";
    if (!top_code.empty()) {
      int dp = -1;
      for (int i = 0; i < L; ++i) {
        if (top_code[i] != code[i]) {
          dp = i;
          break;
        }
      }
      msg += " top=" + top_code + " gain=" + std::to_string(top.gain) +
             " top_lang=" + std::to_string(lookup(top_code).lang_weight) +
             " top_entries=" + std::to_string(lookup(top_code).entries.size()) +
             " dir=" + dir_name[top.dir] +
             " proj=" + std::to_string(top.proj) +
             " geo=" + std::to_string(top.geo);
      if (dp >= 0) {
        const float ox = taps[dp].has_offset ? taps[dp].off_x : 0.0f;
        const float oy = taps[dp].has_offset ? taps[dp].off_y : 0.0f;
        msg += " off=" + std::to_string(ox) + "/" + std::to_string(oy);
      }
    }
    EmitLog(msg);
    return nullptr;
  }
  std::sort(hits.begin(), hits.end(),
            [](const auto& a, const auto& b) { return a.gain > b.gain; });

  // 4) 出词：修正码 → 同一份字典 / 用户词典（语言侧完全交给 Rime）
  // 纠错本质是猜测（无法确认用户真的按错了）：quality 必须落在「原码最高正常候选」
  // 之下——候选栏只取引擎当前页，若按极低 quality 排到全部正常候选之后会跌出首页、
  // 永远不可见。故与 table_translator 同口径（exp(weight)，用户词 +0.5，本方案未配
  // initial_quality）查原码的参照质量，把纠错压在其下方一点：引擎序紧随首选之后
  // （二选起、仍在首页内），又不抢占首选。
  // 出词：修正码 → 同一份字典 / 用户词典（语言侧完全交给 Rime）。
  // 每个纠错候选的 quality = exp(该修正词条 weight) − 0.5：
  //   · 与 table/script translator 的正常候选 quality 同域（exp(weight)），自然
  //     融入引擎排序，恰好排在同词正常候选之后；
  //   · 空码救回（原码无完整词条）时按修正词自身词频落位——高频词纠错自然居前
  //     甚至首选（引擎此时只有简拼/前缀候选，拼音一长串，固定垫底值会被埋没）；
  //   · 无 weight 词典（wubi86）词条 weight 为哨兵值 → exp 下溢 0 → −0.5，
  //     恰排在正常词（quality≈0）之后。
  auto translation = New<FifoTranslation>();
  std::unordered_set<std::string> seen;
  int produced = 0;
  for (const auto& hit : hits) {
    if (produced >= config_.max_candidates)
      break;
    const std::string& corrected = hit.code;
    const ExactLookup lk = lookup(corrected);
    const double cand_quality =
        std::max(std::exp(lk.best_quality_weight), 1e-9) - 0.5;
    for (const auto& e : lk.entries) {
      if (!e || e->text.empty() || !seen.insert(e->text).second)
        continue;
      auto cand = New<SimpleCandidate>("correction", segment.start,
                                       segment.end, e->text, "纠错");
      cand->set_quality(cand_quality);
      translation->Append(cand);
      ++produced;
    }
    // 词典与用户词典都无完整词条时不出词：纠错的词必须来自当前方案自己的语言
    // 证据，不做任何跨方案/跨码表的兜底（词典没有的码，纠错也不该替用户猜词）。
  }
  if (produced == 0)
    return nullptr;
  {
    // 带出被接受的修正码/方向/投影，便于用真机日志回归（离线脚本 scripts/log_replay_direction.py）
    std::string msg = std::string("HIT ") + (dead_end ? "dead " : "") + "input=" + code +
                      " best=" + hits.front().code +
                      " gain=" + std::to_string(hits.front().gain) +
                      " dir=" + dir_name[hits.front().dir] +
                      " proj=" + std::to_string(hits.front().proj) +
                      " geo=" + std::to_string(hits.front().geo) +
                      " produced=" + std::to_string(produced);
    EmitLog(msg);
  }
  return translation;
}

CorrectionTranslator::~CorrectionTranslator() = default;

CorrectionTranslator* CorrectionTranslatorComponent::Create(
    const Ticket& ticket) {
  return new CorrectionTranslator(ticket);
}

}  // namespace rime

// ---------------------------------------------------------------- JNI 侧信道

extern "C" {

void correction_set_taps(const char* letters, const float* off_x,
                         const float* off_y, int n) {
  if (!letters || !off_x || !off_y || n <= 0) {
    rime::CorrectionPriors::Instance().Clear();
    return;
  }
  std::vector<rime::TapInfo> taps;
  taps.reserve(n);
  for (int i = 0; i < n; ++i) {
    rime::TapInfo t;
    t.letter = letters[i];
    t.has_offset = !std::isnan(off_x[i]) && !std::isnan(off_y[i]);
    t.off_x = t.has_offset ? off_x[i] : 0.0f;
    t.off_y = t.has_offset ? off_y[i] : 0.0f;
    taps.push_back(t);
  }
  rime::CorrectionPriors::Instance().SetTaps(taps);
}

void correction_set_enabled(int enabled) {
  rime::CorrectionPriors::Instance().SetEnabled(enabled != 0);
}

void correction_clear_taps() {
  rime::CorrectionPriors::Instance().Clear();
}

void correction_set_model_paths(const char* channel_path, const char* freq_path) {
  // 出词仍来自 schema 自己的词典/用户词典；freq_path 只补语言项（真实词频）。
  rime::CorrectionPriors::Instance().SetModelPaths(
      channel_path ? channel_path : "", freq_path ? freq_path : "");
}

void correction_set_log_file(const char* path) {
  std::lock_guard<std::mutex> lock(rime::g_log_mutex);
  rime::g_log_path = path ? path : "";
}

// 端上自校准的信道宽度（键宽/键高单位）。传 0 回退插件配置默认值。
void correction_set_channel_params(float sigma_x, float sigma_y) {
  rime::CorrectionPriors::Instance().SetChannelParams(sigma_x, sigma_y);
}

}  // extern "C"
