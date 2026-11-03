package com.kingzcheung.xime.settings

import androidx.annotation.StringRes
import com.kingzcheung.xime.R

/** 模糊拼音规则分组。 */
enum class FuzzyPinyinGroup(@StringRes val titleRes: Int) {
    INITIAL(R.string.s_fuzzy_shengmu),
    FINAL(R.string.s_fuzzy_yunmu),
    SPECIAL(R.string.s_fuzzy_teshu),
}

/**
 * 一个模糊拼音开关。
 *
 * [patterns] 是完整的 Rime speller/algebra 规则串（`fuzz/pattern/replacement/`）。
 * 同一开关的正反方向、零声母写法都在这里展开，避免写补丁时再拼字符串。
 */
data class FuzzyPinyinRule(
    val id: String,
    val group: FuzzyPinyinGroup,
    @StringRes val labelRes: Int,
    val patterns: List<String>,
)

/** 多个开关叠加后会形成传递匹配的已知组合。 */
data class FuzzyPinyinConflict(
    val ids: Set<String>,
    @StringRes val messageRes: Int,
)

/**
 * 模糊拼音规则目录。
 *
 * 规则顺序固定为：声母 → 韵母 → 特殊音节。生成补丁时按 [all] 的顺序输出，
 * 不跟随 UI 点击顺序，保证同一组开关始终得到同一份 YAML。
 */
object FuzzyPinyinRules {

    private fun rule(
        id: String,
        group: FuzzyPinyinGroup,
        @StringRes labelRes: Int,
        vararg patterns: String,
    ) = FuzzyPinyinRule(id, group, labelRes, patterns.toList())

    val all: List<FuzzyPinyinRule> = listOf(
        // ── 声母 ──
        rule(
            "z_zh", FuzzyPinyinGroup.INITIAL, R.string.s_fuzzy_rule_z_zh,
            "fuzz/^z([^h].*)$/zh$1/",
            "fuzz/^zh(.+)$/z$1/",
        ),
        rule(
            "c_ch", FuzzyPinyinGroup.INITIAL, R.string.s_fuzzy_rule_c_ch,
            "fuzz/^c([^h].*)$/ch$1/",
            "fuzz/^ch(.+)$/c$1/",
        ),
        rule(
            "s_sh", FuzzyPinyinGroup.INITIAL, R.string.s_fuzzy_rule_s_sh,
            "fuzz/^s([^h].*)$/sh$1/",
            "fuzz/^sh(.+)$/s$1/",
        ),
        rule(
            "g_k", FuzzyPinyinGroup.INITIAL, R.string.s_fuzzy_rule_g_k,
            "fuzz/^g(.+)$/k$1/",
            "fuzz/^k(.+)$/g$1/",
        ),
        rule(
            "h_f", FuzzyPinyinGroup.INITIAL, R.string.s_fuzzy_rule_h_f,
            "fuzz/^h(.+)$/f$1/",
            "fuzz/^f(.+)$/h$1/",
        ),
        rule(
            "n_l", FuzzyPinyinGroup.INITIAL, R.string.s_fuzzy_rule_n_l,
            "fuzz/^n(.+)$/l$1/",
            "fuzz/^l(.+)$/n$1/",
        ),
        rule(
            "r_l", FuzzyPinyinGroup.INITIAL, R.string.s_fuzzy_rule_r_l,
            "fuzz/^r(.+)$/l$1/",
            "fuzz/^l(.+)$/r$1/",
        ),

        // ── 韵母 ──
        rule(
            "an_ang", FuzzyPinyinGroup.FINAL, R.string.s_fuzzy_rule_an_ang,
            "fuzz/^([^iuyw]*)an$/$1ang/",
            "fuzz/^([^iuyw]*)ang$/$1an/",
        ),
        rule(
            "en_eng", FuzzyPinyinGroup.FINAL, R.string.s_fuzzy_rule_en_eng,
            "fuzz/^([^iu]*)en$/$1eng/",
            "fuzz/^([^iu]*)eng$/$1en/",
        ),
        rule(
            "in_ing", FuzzyPinyinGroup.FINAL, R.string.s_fuzzy_rule_in_ing,
            "fuzz/in$/ing/",
            "fuzz/ing$/in/",
        ),
        rule(
            "an_ai", FuzzyPinyinGroup.FINAL, R.string.s_fuzzy_rule_an_ai,
            "fuzz/^([^iuyw]*)an$/$1ai/",
            "fuzz/^([^iuyw]*)ai$/$1an/",
        ),
        rule(
            "eng_ong", FuzzyPinyinGroup.FINAL, R.string.s_fuzzy_rule_eng_ong,
            "fuzz/^([^iy]*)eng$/$1ong/",
            "fuzz/^([^iy]*)ong$/$1eng/",
        ),
        rule(
            "ian_iang", FuzzyPinyinGroup.FINAL, R.string.s_fuzzy_rule_ian_iang,
            "fuzz/ian$/iang/",
            "fuzz/iang$/ian/",
            "fuzz/^yan$/yang/",
            "fuzz/^yang$/yan/",
        ),
        rule(
            "on_ong", FuzzyPinyinGroup.FINAL, R.string.s_fuzzy_rule_on_ong,
            "fuzz/^([^iy]*)on$/$1ong/",
            "fuzz/^([^iy]*)ong$/$1on/",
        ),
        rule(
            "uan_uang", FuzzyPinyinGroup.FINAL, R.string.s_fuzzy_rule_uan_uang,
            "fuzz/^([^jqxy]*)uan$/$1uang/",
            "fuzz/^([^jqxy]*)uang$/$1uan/",
            "fuzz/^wan$/wang/",
            "fuzz/^wang$/wan/",
        ),
        rule(
            "un_ong", FuzzyPinyinGroup.FINAL, R.string.s_fuzzy_rule_un_ong,
            "fuzz/^([^jqxy]*)un$/$1ong/",
            "fuzz/^([^jqxy]*)ong$/$1un/",
        ),
        rule(
            "un_iong", FuzzyPinyinGroup.FINAL, R.string.s_fuzzy_rule_un_iong,
            "fuzz/^([jqx])un$/$1iong/",
            "fuzz/^([jqx])iong$/$1un/",
            "fuzz/^yun$/yong/",
            "fuzz/^yong$/yun/",
        ),

        // ── 特殊音节 ──
        rule(
            "hui_fei", FuzzyPinyinGroup.SPECIAL, R.string.s_fuzzy_rule_hui_fei,
            "fuzz/^hui$/fei/",
            "fuzz/^fei$/hui/",
        ),
        rule(
            "huang_wang", FuzzyPinyinGroup.SPECIAL, R.string.s_fuzzy_rule_huang_wang,
            "fuzz/^huang$/wang/",
            "fuzz/^wang$/huang/",
        ),
    )

    private val byId: Map<String, FuzzyPinyinRule> = all.associateBy { it.id }

    val conflicts: List<FuzzyPinyinConflict> = listOf(
        FuzzyPinyinConflict(setOf("n_l", "r_l"), R.string.s_fuzzy_conflict_n_l_r_l),
        FuzzyPinyinConflict(setOf("en_eng", "eng_ong"), R.string.s_fuzzy_conflict_en_eng_ong),
        FuzzyPinyinConflict(setOf("on_ong", "un_ong"), R.string.s_fuzzy_conflict_un_on_ong),
        FuzzyPinyinConflict(setOf("uan_uang", "huang_wang"), R.string.s_fuzzy_conflict_uan_huang),
    )

    /** 过滤未知/已删除的规则 id，避免旧 prefs 带坏补丁生成。 */
    fun sanitize(ids: Collection<String>): Set<String> = ids.filter { it in byId }.toSet()

    fun rulesFor(ids: Collection<String>): List<FuzzyPinyinRule> = all.filter { it.id in ids }

    /** 按固定顺序展开规则串。 */
    fun patternsFor(ids: Collection<String>): List<String> =
        all.asSequence()
            .filter { it.id in ids }
            .flatMap { it.patterns.asSequence() }
            .toList()

    fun activeConflicts(ids: Collection<String>): List<FuzzyPinyinConflict> =
        conflicts.filter { ids.containsAll(it.ids) }
}
