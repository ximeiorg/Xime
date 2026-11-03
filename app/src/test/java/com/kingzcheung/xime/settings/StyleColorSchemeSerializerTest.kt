package com.kingzcheung.xime.settings

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StyleColorSchemeSerializerTest {

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    @Test
    fun `标量形式 color_scheme 可以解析`() {
        val config = yaml.decodeFromString(
            StyleConfig.serializer(),
            "color_scheme: mu_shan_zi\n"
        )
        assertNotNull(config.colorScheme)
        assertEquals("mu_shan_zi", config.colorScheme?.light)
        assertNull(config.colorScheme?.dark)
    }

    @Test
    fun `对象形式 color_scheme 仍然可以解析`() {
        val config = yaml.decodeFromString(
            StyleConfig.serializer(),
            "color_scheme:\n  light: zine_light\n  dark: zine_dark\n"
        )
        assertNotNull(config.colorScheme)
        assertEquals("zine_light", config.colorScheme?.light)
        assertEquals("zine_dark", config.colorScheme?.dark)
    }

    @Test
    fun `对象形式缺 dark 时 light 保留`() {
        val config = yaml.decodeFromString(
            StyleConfig.serializer(),
            "color_scheme:\n  light: lavender_purple\n"
        )
        assertEquals("lavender_purple", config.colorScheme?.light)
        assertNull(config.colorScheme?.dark)
    }

    @Test
    fun `color_scheme 可指定 dynamic 动态配色`() {
        val config = yaml.decodeFromString(
            StyleConfig.serializer(),
            "color_scheme: dynamic\n"
        )
        assertEquals("dynamic", config.colorScheme?.light)
    }

    @Test
    fun `color_schemes 中 dynamic_color 标记可以解析`() {
        val config = yaml.decodeFromString(
            XimeConfig.serializer(),
            "color_schemes:\n  dynamic:\n    name: \"动态配色\"\n    dynamic_color: true\n"
        )
        val entry = config.colorSchemes?.get("dynamic")
        assertNotNull(entry)
        assertEquals(true, entry?.dynamicColor)
    }

    @Test
    fun `color_schemes 缺省 dynamic_color 为 false`() {
        val config = yaml.decodeFromString(
            XimeConfig.serializer(),
            "color_schemes:\n  lavender_purple:\n    name: x\n"
        )
        assertEquals(false, config.colorSchemes?.get("lavender_purple")?.dynamicColor)
    }

    @Test
    fun `mergeStyle custom 只覆盖显式字段`() {
        val default = StyleConfig(
            colorScheme = ColorSchemeModeConfig(light = "lavender_purple", dark = "slate_gray"),
            darkMode = 2,
        )
        val custom = StyleConfig(colorScheme = ColorSchemeModeConfig(light = "dynamic"))
        val merged = KeysConfigHelper.mergeStyleForTest(default, custom)
        assertEquals("dynamic", merged?.colorScheme?.light)
        assertEquals("slate_gray", merged?.colorScheme?.dark)
        assertEquals(2, merged?.darkMode)
    }

    @Test
    fun `color_schemes 的 special_key_bg_color_dark 可以解析`() {
        val config = yaml.decodeFromString(
            XimeConfig.serializer(),
            "color_schemes:\n" +
                "  sunset_orange:\n" +
                "    name: \"落日橙光\"\n" +
                "    primary_color: 0xE65100\n" +
                "    special_key_bg_color_dark: 0xD97757\n"
        )
        val entry = config.colorSchemes?.get("sunset_orange")
        assertNotNull(entry)
        assertEquals(0xD97757L, entry?.specialKeyBgColorDark)
        assertNull(entry?.specialKeyBgColor)
    }

    @Test
    fun `mergeColorSchemes custom 只覆盖显式字段且保留 dark 功能键色`() {
        val default = mapOf(
            "sunset_orange" to ColorSchemeEntry(
                name = "落日橙光",
                primaryColor = 0xE65100,
                specialKeyBgColorDark = 0xD97757,
            )
        )
        val custom = mapOf(
            "sunset_orange" to ColorSchemeEntry(
                name = "落日橙光（改）",
                primaryColor = 0xE65100,
            )
        )
        val merged = KeysConfigHelper.mergeColorSchemesForTest(default, custom)
        val entry = merged?.get("sunset_orange")
        assertNotNull(entry)
        assertEquals("落日橙光（改）", entry?.name)
        // custom 未配置 special_key_bg_color_dark 时保留内置值
        assertEquals(0xD97757L, entry?.specialKeyBgColorDark)
    }

    @Test
    fun `color_schemes 的 candidate_selected_bg_color 可以解析`() {
        val config = yaml.decodeFromString(
            XimeConfig.serializer(),
            "color_schemes:\n" +
                "  ocean_blue:\n" +
                "    name: \"海洋蔚蓝\"\n" +
                "    primary_color: 0x1A73E8\n" +
                "    candidate_selected_bg_color: 0x331A73E8\n" +
                "    candidate_selected_bg_color_dark: 0x55FFFFFF\n"
        )
        val entry = config.colorSchemes?.get("ocean_blue")
        assertNotNull(entry)
        assertEquals(0x331A73E8L, entry?.candidateSelectedBgColor)
        assertEquals(0x55FFFFFFL, entry?.candidateSelectedBgColorDark)
    }

    @Test
    fun `mergeColorSchemes 保留未覆盖的候选选中背景色`() {
        val default = mapOf(
            "ocean_blue" to ColorSchemeEntry(
                name = "海洋蔚蓝",
                candidateSelectedBgColor = 0x331A73E8,
                candidateSelectedBgColorDark = 0x55FFFFFF,
            )
        )
        val custom = mapOf(
            "ocean_blue" to ColorSchemeEntry(name = "海洋蔚蓝（改）")
        )
        val merged = KeysConfigHelper.mergeColorSchemesForTest(default, custom)
        val entry = merged?.get("ocean_blue")
        assertNotNull(entry)
        assertEquals(0x331A73E8L, entry?.candidateSelectedBgColor)
        assertEquals(0x55FFFFFFL, entry?.candidateSelectedBgColorDark)
    }

    @Test
    fun `mergeColorSchemes 显式纯黑主色不被当作未配置`() {
        val default = mapOf("mono" to ColorSchemeEntry(name = "墨黑", primaryColor = 0xE65100))
        val custom = mapOf("mono" to ColorSchemeEntry(primaryColor = 0x000000))
        val merged = KeysConfigHelper.mergeColorSchemesForTest(default, custom)
        // 0x000000 是显式取值，必须覆盖内置主色，不能回退
        assertEquals(0L, merged?.get("mono")?.primaryColor ?: -1L)
    }
}
