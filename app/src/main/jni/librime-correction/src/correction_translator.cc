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
struct ExactLookup {
  float lang_weight = -30.0f;
  bool has_entry = false;             // 有完整词条（词典或用户词典）
  double best_quality_weight = 0.0;   // 落位用：词条 weight 最大值（用户词含 +0.5）
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
  float lang = -30.0f;
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
    lang = std::max(lang, -6.0f);  // 图可切分：合法前缀（即使无完整词条也优于非法）
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
          r.entries.push_back(e);
        }
      }
    }
  }
  r.lang_weight = lang;
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
  // 排障（定位拼音下 Query 不被调用的问题；logcat 在部分 ROM 静音，写文件留痕）
  {
    std::string tag_list;
    for (const auto& t : tags()) tag_list += t + ",";
    EmitLog("constructed ns=" + name_space_ + " tags=" + tag_list +
            " dict_loaded=" + (dict_ && dict_->loaded() ? "1" : "0") +
            " prism=" + (dict_ && dict_->prism() ? "1" : "0") +
            " userdict=" + (user_dict_ && user_dict_->loaded() ? "1" : "0"));
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

  priors.LoadModels();  // 幂等：首次查询时加载信道模型

  std::vector<TapInfo> taps;
  if (!priors.TapsFor(code, &taps))
    return skip("taps-unaligned");  // 无对齐先验：不猜（宁可不纠，也不误改）

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
    s += config_.lambda * lookup(c).lang_weight;
    return s;
  };

  const float base = score(code);

  // 3) 触发判定：增益 ≥ margin，且（可选）偏移幅度足够（按得准就不纠）
  std::vector<std::pair<float, const std::string*>> hits;
  const std::string* top_v = nullptr;  // 排障：全量最优候选（无论是否达 margin）
  float top_gain = -1e9f;
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
    if (gain > top_gain) {
      top_gain = gain;
      top_v = &cands[k];
    }
    if (gain >= config_.margin)
      hits.emplace_back(gain, &cands[k]);
  }
  if (hits.empty()) {
    // 排障：全量打分细节（top 候选的增益/语言分/信道概率；定位后移除）
    std::string msg = "miss input=" + code +
                      " base_lang=" + std::to_string(lookup(code).lang_weight) +
                      " ncands=" + std::to_string(cands.size());
    if (top_v) {
      int dp = -1;
      for (int i = 0; i < L; ++i) {
        if ((*top_v)[i] != code[i]) {
          dp = i;
          break;
        }
      }
      msg += " top=" + *top_v + " gain=" + std::to_string(top_gain) +
             " top_lang=" + std::to_string(lookup(*top_v).lang_weight) +
             " top_entries=" + std::to_string(lookup(*top_v).entries.size());
      if (dp >= 0) {
        const size_t base_idx =
            static_cast<size_t>(dp) * correction::ChannelModel::kVocab;
        msg += " diff@" + std::to_string(dp) +
               " p_obs=" + std::to_string(probs[base_idx + LetterIndex(code[dp])]) +
               " p_top=" + std::to_string(probs[base_idx + LetterIndex((*top_v)[dp])]);
      }
    }
    EmitLog(msg);
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
  for (const auto& [gain, code_ptr] : hits) {
    if (produced >= config_.max_candidates)
      break;
    const std::string& corrected = *code_ptr;
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
  // 语言分与出词均来自 schema 自己的词典（wubi86 词典已补 weight 列），
  // 无独立码表；code_table_path 参数仅为 ABI 兼容保留。
  (void)code_table_path;
  rime::CorrectionPriors::Instance().SetModelPaths(
      channel_path ? channel_path : "");
}

void correction_set_log_file(const char* path) {
  std::lock_guard<std::mutex> lock(rime::g_log_mutex);
  rime::g_log_path = path ? path : "";
}

}  // extern "C"
