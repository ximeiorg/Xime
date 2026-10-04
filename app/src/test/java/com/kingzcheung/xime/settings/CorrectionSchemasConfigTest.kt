package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 纠错方案绑定（xime.yaml 顶层 correction.schemas）解析回归：
 * 内置列表、custom 追加列表、以及非列表/缺段等异常形态的容错。
 * 绑定语义与 keyboard.<section>.schemas 一致：custom 与内置取并集（只增不减）。
 */
class CorrectionSchemasConfigTest {

    @Test
    fun `内置 correction 段解析`() {
        val yaml = """
            correction:
              schemas: [wubi86, wubi86_pinyin]
        """.trimIndent()
        assertEquals(
            setOf("wubi86", "wubi86_pinyin"),
            KeysConfigHelper.parseTopLevelSchemasYamlText(yaml, "correction")
        )
    }

    @Test
    fun `custom 追加第三方方案`() {
        val yaml = """
            correction:
              schemas: [wubi98, wubixx]
        """.trimIndent()
        assertEquals(
            setOf("wubi98", "wubixx"),
            KeysConfigHelper.parseTopLevelSchemasYamlText(yaml, "correction")
        )
    }

    @Test
    fun `与 keyboard 段共存互不干扰`() {
        val yaml = """
            keyboard:
              t9:
                schemas: [t9_pinyin]
            correction:
              schemas: [wubi86]
        """.trimIndent()
        assertEquals(
            setOf("wubi86"),
            KeysConfigHelper.parseTopLevelSchemasYamlText(yaml, "correction")
        )
    }

    @Test
    fun `缺 correction 段返回空集`() {
        val yaml = """
            style:
              dark_mode: 2
        """.trimIndent()
        assertEquals(
            emptySet<String>(),
            KeysConfigHelper.parseTopLevelSchemasYamlText(yaml, "correction")
        )
    }

    @Test
    fun `correction 段缺 schemas 或非列表返回空集`() {
        assertEquals(
            emptySet<String>(),
            KeysConfigHelper.parseTopLevelSchemasYamlText("correction:\n  enable: true", "correction")
        )
        assertEquals(
            emptySet<String>(),
            KeysConfigHelper.parseTopLevelSchemasYamlText("correction: [wubi86]", "correction")
        )
    }

    @Test
    fun `乱文本不抛异常`() {
        assertEquals(
            emptySet<String>(),
            KeysConfigHelper.parseTopLevelSchemasYamlText(":::not yaml:::", "correction")
        )
    }
}
