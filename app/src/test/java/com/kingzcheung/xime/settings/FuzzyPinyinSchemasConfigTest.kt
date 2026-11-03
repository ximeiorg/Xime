package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 模糊拼音方案绑定（xime.yaml 顶层 fuzzy_pinyin.schemas）解析回归。
 *
 * 方案类型只认显式声明：Rime 方案没有类型字段，靠 id/名称/代数猜「是不是全拼」
 * 会把双拼、形码误判成拼音，因此内置默认列表只包含真正的全拼方案。
 */
class FuzzyPinyinSchemasConfigTest {

    @Test
    fun `解析顶层 fuzzy_pinyin 段`() {
        val yaml = """
            fuzzy_pinyin:
              schemas: [pinyin_simp, rime_ice]
        """.trimIndent()
        assertEquals(
            setOf("pinyin_simp", "rime_ice"),
            KeysConfigHelper.parseTopLevelSchemasYamlText(yaml, "fuzzy_pinyin")
        )
    }

    @Test
    fun `custom 追加第三方全拼方案`() {
        val yaml = """
            fuzzy_pinyin:
              schemas: [wanxiang]
        """.trimIndent()
        assertEquals(
            setOf("wanxiang"),
            KeysConfigHelper.parseTopLevelSchemasYamlText(yaml, "fuzzy_pinyin")
        )
    }

    @Test
    fun `与 correction 段共存互不干扰`() {
        val yaml = """
            correction:
              schemas: [wubi86, pinyin_simp]
            fuzzy_pinyin:
              schemas: [pinyin_simp]
        """.trimIndent()
        assertEquals(
            setOf("pinyin_simp"),
            KeysConfigHelper.parseTopLevelSchemasYamlText(yaml, "fuzzy_pinyin")
        )
    }

    @Test
    fun `缺段或非列表返回空集`() {
        assertEquals(
            emptySet<String>(),
            KeysConfigHelper.parseTopLevelSchemasYamlText("style:\n  dark_mode: 2", "fuzzy_pinyin")
        )
        assertEquals(
            emptySet<String>(),
            KeysConfigHelper.parseTopLevelSchemasYamlText("fuzzy_pinyin: [pinyin_simp]", "fuzzy_pinyin")
        )
        assertEquals(
            emptySet<String>(),
            KeysConfigHelper.parseTopLevelSchemasYamlText(":::not yaml:::", "fuzzy_pinyin")
        )
    }

    @Test
    fun `内置 xime yaml 不把九键和形码当全拼`() {
        val declared = KeysConfigHelper.parseTopLevelSchemasYamlText(
            repoFile("src/main/assets/xime.yaml").readText(),
            "fuzzy_pinyin",
        )
        assertTrue("内置全拼 pinyin_simp 必须声明", "pinyin_simp" in declared)
        // 双拼/九键/形码不得进入默认列表（内置没有双拼，九键与形码有）。
        assertFalse("t9_pinyin 不得被当作全拼", "t9_pinyin" in declared)
        assertFalse("wubi86 不得被当作全拼", "wubi86" in declared)
        assertFalse("wubi86_pinyin 不得被当作全拼", "wubi86_pinyin" in declared)
    }

    /** 定位仓库内文件（单测 workingDir 可能是模块目录或仓库根目录，逐级向上查找）。 */
    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it }
            File(dir, "app/$rel").takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("file not found: $rel")
    }
}
