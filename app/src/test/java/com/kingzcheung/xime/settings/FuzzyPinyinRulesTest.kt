package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyPinyinRulesTest {

    private fun rule(id: String): FuzzyPinyinRule =
        FuzzyPinyinRules.all.first { it.id == id }

    private fun apply(rule: FuzzyPinyinRule, input: String): Set<String> {
        var current = setOf(input)
        for (definition in rule.patterns) {
            val body = definition.removePrefix("fuzz/")
            val parts = body.split("/", limit = 3)
            val regex = Regex(parts[0])
            val replacement = parts[1]
            current = current + current.map { regex.replace(it, replacement) }
        }
        return current
    }

    @Test
    fun `catalog has 19 switches in stable order`() {
        assertEquals(19, FuzzyPinyinRules.all.size)
        assertEquals(
            listOf(
                "z_zh", "c_ch", "s_sh", "g_k", "h_f", "n_l", "r_l",
                "an_ang", "en_eng", "in_ing", "an_ai", "eng_ong", "ian_iang",
                "on_ong", "uan_uang", "un_ong", "un_iong",
                "hui_fei", "huang_wang",
            ),
            FuzzyPinyinRules.all.map { it.id },
        )
        assertEquals(44, FuzzyPinyinRules.all.sumOf { it.patterns.size })
    }

    @Test
    fun `every rule is bidirectional for its representative pair`() {
        val pairs = mapOf(
            "z_zh" to ("zang" to "zhang"),
            "c_ch" to ("cang" to "chang"),
            "s_sh" to ("sang" to "shang"),
            "g_k" to ("ge" to "ke"),
            "h_f" to ("hu" to "fu"),
            "n_l" to ("na" to "la"),
            "r_l" to ("ran" to "lan"),
            "an_ang" to ("fan" to "fang"),
            "en_eng" to ("fen" to "feng"),
            "in_ing" to ("xin" to "xing"),
            "an_ai" to ("ban" to "bai"),
            "eng_ong" to ("zheng" to "zhong"),
            "ian_iang" to ("jian" to "jiang"),
            "on_ong" to ("dong" to "don"),
            "uan_uang" to ("zhuan" to "zhuang"),
            "un_ong" to ("dun" to "dong"),
            "un_iong" to ("jun" to "jiong"),
            "hui_fei" to ("hui" to "fei"),
            "huang_wang" to ("huang" to "wang"),
        )
        pairs.forEach { (id, pair) ->
            val (a, b) = pair
            assertTrue("$id: $a should match $b", apply(rule(id), a).contains(b))
            assertTrue("$id: $b should match $a", apply(rule(id), b).contains(a))
        }
    }

    @Test
    fun `zero initial yan yang and wan wang are covered`() {
        assertTrue(apply(rule("ian_iang"), "yan").contains("yang"))
        assertTrue(apply(rule("ian_iang"), "yang").contains("yan"))
        assertTrue(apply(rule("uan_uang"), "wan").contains("wang"))
        assertTrue(apply(rule("uan_uang"), "wang").contains("wan"))
    }

    @Test
    fun `an ang does not leak into ian uan or zero initial forms`() {
        val rule = rule("an_ang")
        assertFalse(apply(rule, "tian").contains("tiang"))
        assertFalse(apply(rule, "tuan").contains("tuang"))
        assertFalse(apply(rule, "yan").contains("yang"))
        assertFalse(apply(rule, "wan").contains("wang"))
    }

    @Test
    fun `an ai does not leak into uai or wai`() {
        val rule = rule("an_ai")
        assertFalse(apply(rule, "guai").contains("guan"))
        assertFalse(apply(rule, "wai").contains("wan"))
    }

    @Test
    fun `un split by initial does not create invalid cross matches`() {
        assertFalse(apply(rule("un_ong"), "jun").contains("jong"))
        assertFalse(apply(rule("un_ong"), "yun").contains("yong"))
        assertFalse(apply(rule("un_iong"), "dun").contains("diong"))
        assertTrue(apply(rule("un_iong"), "yun").contains("yong"))
    }

    @Test
    fun `ong rules exclude iong and yong`() {
        assertFalse(apply(rule("on_ong"), "jiong").contains("jion"))
        assertFalse(apply(rule("on_ong"), "yong").contains("yon"))
        assertFalse(apply(rule("eng_ong"), "jiong").contains("jieng"))
        assertFalse(apply(rule("eng_ong"), "yong").contains("yeng"))
    }

    @Test
    fun `patternsFor keeps catalog order and drops unknown ids`() {
        val patterns = FuzzyPinyinRules.patternsFor(setOf("z_zh", "n_l", "unknown"))
        assertEquals(
            listOf(
                "fuzz/^z([^h].*)$/zh$1/",
                "fuzz/^zh(.+)$/z$1/",
                "fuzz/^n(.+)$/l$1/",
                "fuzz/^l(.+)$/n$1/",
            ),
            patterns,
        )
        assertEquals(setOf("z_zh"), FuzzyPinyinRules.sanitize(setOf("z_zh", "unknown")))
    }

    @Test
    fun `known overlapping combinations are reported`() {
        val conflicts = FuzzyPinyinRules.activeConflicts(setOf("n_l", "r_l", "an_ang"))
        assertEquals(1, conflicts.size)
        assertEquals(setOf("n_l", "r_l"), conflicts.first().ids)
        assertTrue(FuzzyPinyinRules.activeConflicts(setOf("n_l")).isEmpty())
    }
}
