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
struct ExactLookup {
  float best_weight = -30.0f;  // 完整匹配最优词条的 weight（log 词频域）；无 → -30（空码语义）
  std::vector<an<DictEntry>> entries;  // 出词用词条（用户词在前，共至多 max_entries 个）
};

ExactLookup LookupExactCode(Dictionary* dict, UserDictionary* user_dict,
                            const std::string& code,
                            const hash_set<std::string>* blacklist,
                            size_t max_entries,
                            bool use_user_dict = true) {
  ExactLookup r;
  if (code.empty() || !dict || !dict->loaded())
    return r;
  // 用户词典：userdb 键即完整输入串，直接查（用户词优先出、优先计分）
  if (use_user_dict && user_dict && user_dict->loaded()) {
    UserDictEntryIterator uter;
    user_dict->LookupWords(&uter, code, false);
    for (; !uter.exhausted() && r.entries.size() < max_entries; uter.Next()) {
      const an<DictEntry>& e = uter.Peek();
      if (!e || e->text.empty())
        continue;
      if (r.best_weight == -30.0f)
        r.best_weight = static_cast<float>(e->weight) + 0.5f;  // 与 table_translator 的用户词加成同口径
      r.entries.push_back(e);
    }
  }
  // 静态词典：音节图 + 完整匹配桶
  Syllabifier syllabifier;
  SyllableGraph graph;
  if (syllabifier.BuildSyllableGraph(code, *dict->prism(), &graph) <
      static_cast<int>(code.size()))
    return r;  // 无法完整切分（该方案下非法码）：无静态语言证据
  auto buckets = dict->LookupAll(graph, {0}, blacklist);
  const auto start_it = buckets.find(0);
  if (start_it == buckets.end() || !start_it->second)
    return r;
  const auto full = start_it->second->find(code.size());
  if (full == start_it->second->end())
    return r;  // 有切分但无「全长」词条（纯前缀）：按无完整词处理
  DictEntryIterator& iter = full->second;
  bool first = true;
  for (; !iter.exhausted() && r.entries.size() < max_entries; iter.Next()) {
    const an<DictEntry>& e = iter.Peek();
    if (!e || e->text.empty())
      continue;
    if (first) {
      // 静态词条与用户词同现时取更优权重
      const float w = static_cast<float>(e->weight);
      r.best_weight = (r.best_weight == -30.0f) ? w : std::max(r.best_weight, w);
      first = false;
    }
    r.entries.push_back(e);
  }
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

void CorrectionPriors::SetModelPaths(const std::string& channel_path) {
  std::lock_guard<std::mutex> lock(mutex_);
  channel_path_ = channel_path;
  models_requested_ = true;
}

bool CorrectionPriors::LoadModels() {
  std::string channel_path;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!models_requested_)
      return false;
    channel_path = channel_path_;
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
  // 组合编码可能与 tap 序列不完全一致（T9/拼音反查等），只在尾部完全匹配时采用
  const size_t n = code.size();
  const size_t off = taps_.size() - n;
  for (size_t i = 0; i < n; ++i) {
    if (taps_[off + i].letter != code[i])
      return false;
  }
  out->assign(taps_.end() - n, taps_.end());
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
  if (Config* config = engine_->schema()->config()) {
    // 配置键注入在 correction/ 命名空间（见 nativeEnsureCorrectedSchemaPatch）；
    // 不能用 name_space_（= 组件名 correction_translator，无 @ 后缀），否则读不到、
    // enable 保持默认 false，Query 在第一道门就静默返回。
    const std::string cfg_ns = "correction";
    config->GetBool(cfg_ns + "/enable", &config_.enable);
    config->GetString(cfg_ns + "/mode", &config_.mode);
    double lambda = config_.lambda, margin = config_.margin, min_off = config_.min_off;
    config->GetDouble(cfg_ns + "/lambda", &lambda);
    config->GetDouble(cfg_ns + "/margin", &margin);
    config->GetDouble(cfg_ns + "/min_off", &min_off);
    config_.lambda = static_cast<float>(lambda);
    config_.margin = static_cast<float>(margin);
    config_.min_off = static_cast<float>(min_off);
    config->GetInt(cfg_ns + "/max_candidates", &config_.max_candidates);
    config->GetInt(cfg_ns + "/min_code_length", &config_.min_code_length);
  }
  LOGI("translator created: enable=%d lambda=%.2f margin=%.2f max=%d",
       static_cast<int>(config_.enable), config_.lambda, config_.margin,
       config_.max_candidates);
}

an<Translation> CorrectionTranslator::Query(const string& input,
                                            const Segment& segment) {
  if (!config_.enable || !segment.HasAnyTagIn(tags()))
    return nullptr;
  auto& priors = CorrectionPriors::Instance();
  if (!priors.enabled())
    return nullptr;

  // 只处理纯字母编码（拼音反查/数字/T9 一律跳过）
  std::string code = input;
  if (static_cast<int>(code.size()) < config_.min_code_length)
    return nullptr;
  for (char c : code) {
    if (LetterIndex(c) < 0)
      return nullptr;
  }

  priors.LoadModels();  // 幂等：首次查询时加载信道模型

  std::vector<TapInfo> taps;
  if (!priors.TapsFor(code, &taps))
    return nullptr;  // 无对齐先验：不猜（宁可不纠，也不误改）

  const int L = static_cast<int>(code.size());
  auto& channel = correction::ChannelModel::Instance();
  auto& neighbors = correction::NeighborTable::Instance();
  if (!channel.loaded()) {
    static bool warned = false;
    if (!warned) {
      warned = true;
      EmitLog("models not ready: channel missing");
    }
    return nullptr;
  }

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

  // 2) 信道：每个位置只对"原码字母 + 该位置邻键"求概率（其余位置对总增益贡献相同，可忽略）
  //    对每个候选，逐位置取 p(候选字母 | 按下键, off)
  std::vector<float> feats;
  feats.reserve(static_cast<size_t>(L) * correction::ChannelModel::kFeatDim);
  for (int i = 0; i < L; ++i) {
    const int pi = LetterIndex(taps[i].letter);
    feats.push_back(taps[i].has_offset ? taps[i].off_x : 0.0f);
    feats.push_back(taps[i].has_offset ? taps[i].off_y : 0.0f);
    for (int k = 0; k < 26; ++k)
      feats.push_back(k == pi ? 1.0f : 0.0f);
  }
  std::vector<float> probs(static_cast<size_t>(L) * correction::ChannelModel::kVocab, 0.0f);
  if (!channel.Predict(feats.data(), L, probs.data()))
    return nullptr;

  const bool check_off = config_.min_off > 0.0f;
  auto geo_logp = [&](int pos, char ch) -> float {
    const int ci = LetterIndex(ch);
    if (ci < 0)
      return -30.0f;
    const float p = probs[static_cast<size_t>(pos) * correction::ChannelModel::kVocab + ci];
    return std::log(std::max(p, 1e-9f));
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
                                           !IsUserDictDisabledFor(c)))
               .first;
    }
    return it->second;  // 值返回：unordered_map rehash 会使引用失效
  };

  auto score = [&](const std::string& c) -> float {
    float s = 0.0f;
    for (int i = 0; i < L; ++i)
      s += geo_logp(i, c[i]);
    s += config_.lambda * lookup(c).best_weight;
    return s;
  };

  const float base = score(code);

  // 3) 触发判定：增益 ≥ margin，且（可选）偏移幅度足够（按得准就不纠）
  std::vector<std::pair<float, const std::string*>> hits;
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
    if (check_off && taps[diff_pos].has_offset) {
      const float mag = std::sqrt(taps[diff_pos].off_x * taps[diff_pos].off_x +
                                  taps[diff_pos].off_y * taps[diff_pos].off_y);
      if (mag < config_.min_off)
        continue;
    }
    const float gain = score(v) - base;
    if (gain >= config_.margin)
      hits.emplace_back(gain, &cands[k]);
  }
  if (hits.empty()) {
    static int miss_count = 0;
    if (++miss_count % 20 == 1) {
      EmitLog("miss input=" + code + " (采样: 无候选达 margin=" +
                          std::to_string(config_.margin) + ")");
    }
    return nullptr;
  }
  std::sort(hits.begin(), hits.end(),
            [](const auto& a, const auto& b) { return a.first > b.first; });
  const float best_gain = hits.empty() ? 0.0f : hits.front().first;

  // 4) 出词：修正码 → 同一份字典 / 用户词典（语言侧完全交给 Rime）
  // 纠错本质是猜测（无法确认用户真的按错了）：quality 必须落在「原码最高正常候选」
  // 之下——候选栏只取引擎当前页，若按极低 quality 排到全部正常候选之后会跌出首页、
  // 永远不可见。故与 table_translator 同口径（exp(weight)，用户词 +0.5，本方案未配
  // initial_quality）查原码的参照质量，把纠错压在其下方一点：引擎序紧随首选之后
  // （二选起、仍在首页内），又不抢占首选。
  double reference = 0.0;
  {
    const ExactLookup base_lookup = lookup(code);
    if (base_lookup.best_weight > -30.0f)
      reference = std::exp(base_lookup.best_weight);
  }
  // 无正常候选（空码救回）：纠错即全部候选，序值无所谓，取 -1000 表达"垫底"语义。
  const double correction_quality =
      reference > 0.0 ? std::max(reference * 0.9 - 0.5, 0.001) : -1000.0;
  auto translation = New<FifoTranslation>();
  std::unordered_set<std::string> seen;
  int produced = 0;
  for (const auto& [gain, code_ptr] : hits) {
    if (produced >= config_.max_candidates)
      break;
    const std::string& corrected = *code_ptr;
    const ExactLookup lk = lookup(corrected);
    int taken = 0;
    for (const auto& e : lk.entries) {
      if (!e || e->text.empty() || !seen.insert(e->text).second)
        continue;
      auto cand = New<SimpleCandidate>("correction", segment.start,
                                       segment.end, e->text, "纠错");
      cand->set_quality(correction_quality);
      translation->Append(cand);
      ++taken;
      ++produced;
    }
    // 词典与用户词典都无完整词条时不出词：纠错的词必须来自当前方案自己的语言
    // 证据，不做任何跨方案/跨码表的兜底（词典没有的码，纠错也不该替用户猜词）。
  }
  if (produced == 0)
    return nullptr;
  {
    std::string msg = "HIT input=" + code + " gain=" + std::to_string(static_cast<int>(best_gain)) +
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

void correction_set_model_paths(const char* channel_path, const char* code_table_path) {
  // code_table_path 已退役（语言侧证据改用 schema 自己的词典）；参数保留为 ABI 兼容。
  (void)code_table_path;
  rime::CorrectionPriors::Instance().SetModelPaths(
      channel_path ? channel_path : "");
}

void correction_set_log_file(const char* path) {
  std::lock_guard<std::mutex> lock(rime::g_log_mutex);
  rime::g_log_path = path ? path : "";
}

}  // extern "C"
