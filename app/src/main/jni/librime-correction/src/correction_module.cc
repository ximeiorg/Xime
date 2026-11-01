#include <rime/common.h>
#include <rime/registry.h>

#include <string>
#include <vector>

#include "correction_translator.h"

using namespace rime;

namespace {

static void rime_correction_initialize() {
  LOG(INFO) << "registering components from module 'correction'.";
  Registry& r = Registry::instance();
  r.Register("correction_translator", new CorrectionTranslatorComponent);
}

static void rime_correction_finalize() {}

}  // namespace

// 与 T9 插件同款：组件注册名与注入配置在单一地点维护，Kotlin 侧经 JNI 查询，
// 部署时自动把 translator 注入目标 schema（用户无需手改 schema 文件）。
std::vector<std::string> GetCorrectionSchemaPatches() {
  return {
      // 放在 table_translator 之前：纠错候选先于原码候选入队，再经 uniquifier 按
      // quality 排序，保证纠错项永远排在真候选之后（只追加，不抢位）。
      "correction_translator|engine/translators/@before 0|correction_translator",
      "correction_translator|correction/enable|true",
      "correction_translator|correction/mode|append",
      "correction_translator|correction/lambda|1.0",
      "correction_translator|correction/margin|1.0",
      "correction_translator|correction/patch_version|2",
      "correction_translator|correction/max_candidates|3",
      "correction_translator|correction/min_code_length|2",
      // 结构化信道核：σ 由端上自校准覆盖，这里的默认值 = TSI 实测 px 换算到
      // 本机键尺寸（键 112x164px → σx 30.5/112、σy 33.8/164）。
      "correction_translator|correction/sigma_x|0.272",
      "correction_translator|correction/sigma_y|0.206",
      // 方向先验（TSI 实测 同行 78% / 斜向 17% / 跨行 6% 的对数比）
      "correction_translator|correction/dir_prior_h|0.0",
      "correction_translator|correction/dir_prior_d|-1.52",
      "correction_translator|correction/dir_prior_v|-2.56",
      // 方向性投影门：落点至少要走到"到该邻键"方向的 1/4 才算证据
      "correction_translator|correction/min_frac_empty|0.12",
      "correction_translator|correction/min_frac_prefix|0.50",
      "correction_translator|correction/min_frac_word|0.20",
  };
}

RIME_REGISTER_MODULE(correction)
