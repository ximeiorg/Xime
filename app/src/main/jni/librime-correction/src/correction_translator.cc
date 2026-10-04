#include "correction_translator.h"

#include <android/log.h>

#include <algorithm>
#include <cstdio>
#include <cmath>
#include <unordered_set>

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

bool ReadWholeFile(const std::string& path, std::string* out) {
  FILE* f = fopen(path.c_str(), "rb");
  if (!f)
    return false;
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  if (n <= 0) {
    fclose(f);
    return false;
  }
  out->resize(static_cast<size_t>(n));
  size_t got = fread(&(*out)[0], 1, static_cast<size_t>(n), f);
  fclose(f);
  out->resize(got);
  return got > 0;
}

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
                                     const std::string& code_table_path) {
  std::lock_guard<std::mutex> lock(mutex_);
  channel_path_ = channel_path;
  code_table_path_ = code_table_path;
  models_requested_ = true;
}

bool CorrectionPriors::LoadModels() {
  std::string channel_path, table_path;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!models_requested_)
      return false;
    channel_path = channel_path_;
    table_path = code_table_path_;
  }
  auto& channel = correction::ChannelModel::Instance();
  auto& table = correction::CodeTable::Instance();
  if (!channel.loaded() && !channel_path.empty())
    channel.Load(channel_path);
  if (!table.loaded() && !table_path.empty()) {
    std::string blob;
    if (ReadWholeFile(table_path, &blob))
      table.LoadFromMemory(blob.data(), blob.size());
  }
  return channel.loaded() && table.loaded();
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

  priors.LoadModels();  // 幂等：首次查询时加载信道与码表

  std::vector<TapInfo> taps;
  if (!priors.TapsFor(code, &taps))
    return nullptr;  // 无对齐先验：不猜（宁可不纠，也不误改）

  const int L = static_cast<int>(code.size());
  auto& channel = correction::ChannelModel::Instance();
  auto& table = correction::CodeTable::Instance();
  auto& neighbors = correction::NeighborTable::Instance();
  if (!channel.loaded() || !table.loaded()) {
    static bool warned = false;
    if (!warned) {
      warned = true;
      EmitLog(std::string("models not ready: channel=") +
                          (channel.loaded() ? "ok" : "missing") +
                          " table=" + (table.loaded() ? "ok" : "missing"));
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

  auto score = [&](const std::string& c) -> float {
    float s = 0.0f;
    for (int i = 0; i < L; ++i)
      s += geo_logp(i, c[i]);
    s += config_.lambda * table.Score(c);
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
  if (dict_ && dict_->loaded()) {
    DictEntryIterator ref;
    dict_->LookupWords(&ref, code, false, 0, &blacklist());
    if (!ref.exhausted()) {
      if (const auto& e = ref.Peek())
        reference = std::exp(e->weight);
    }
  }
  if (user_dict_ && user_dict_->loaded()) {
    UserDictEntryIterator uref;
    user_dict_->LookupWords(&uref, code, false);
    if (!uref.exhausted()) {
      if (const auto& e = uref.Peek())
        reference = std::max(reference, std::exp(e->weight) + 0.5);
    }
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
    DictEntryIterator iter;
    Dictionary* pydict = dict_.get();
    if (pydict && pydict->loaded())
      pydict->LookupWords(&iter, corrected, false, 0, &blacklist());
    UserDictEntryIterator uter;
    UserDictionary* pyuser = user_dict_.get();
    const bool enable_user_dict = pyuser && pyuser->loaded() &&
                                  !IsUserDictDisabledFor(corrected);
    if (enable_user_dict)
      pyuser->LookupWords(&uter, corrected, false);
    int taken = 0;
    for (; !iter.exhausted() && taken < 2; iter.Next()) {
      const an<DictEntry>& e = iter.Peek();
      if (!e || e->text.empty() || !seen.insert(e->text).second)
        continue;
      auto cand = New<SimpleCandidate>("correction", segment.start,
                                       segment.end, e->text, "纠错");
      cand->set_quality(correction_quality);
      translation->Append(cand);
      ++taken;
      ++produced;
    }
    for (; !uter.exhausted() && taken < 2; uter.Next()) {
      const an<DictEntry>& e = uter.Peek();
      if (!e || e->text.empty() || !seen.insert(e->text).second)
        continue;
      auto cand = New<SimpleCandidate>("correction", segment.start,
                                       segment.end, e->text, "纠错");
      cand->set_quality(correction_quality);
      translation->Append(cand);
      ++taken;
      ++produced;
    }
    // 字典无词条时的兜底：码表最高频词条
    if (taken == 0) {
      const std::string& t = table.TopText(corrected);
      if (!t.empty() && seen.insert(t).second) {
        auto cand = New<SimpleCandidate>("correction", segment.start,
                                         segment.end, t, "纠错");
        cand->set_quality(correction_quality);
        translation->Append(cand);
        ++produced;
      }
    }
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
  rime::CorrectionPriors::Instance().SetModelPaths(
      channel_path ? channel_path : "", code_table_path ? code_table_path : "");
}

void correction_set_log_file(const char* path) {
  std::lock_guard<std::mutex> lock(rime::g_log_mutex);
  rime::g_log_path = path ? path : "";
}

}  // extern "C"
