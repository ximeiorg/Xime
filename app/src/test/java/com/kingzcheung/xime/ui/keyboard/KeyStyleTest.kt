package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.graphics.Color
import com.kingzcheung.xime.settings.KeyBinding
import com.kingzcheung.xime.settings.KeyColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyStyleTest {

    private val lightColors = KeyStyleColors(
        accent = Color(0xFF8F73E2),
        special = Color(0xFFE8DEF8),
        key = Color(0xFFFFFFFF),
        keyboard = Color(0xFFFAF8FC),
    )
    private val darkColors = KeyStyleColors(
        accent = Color(0xFFD0BCFF),
        special = Color(0xFF6750A4),
        key = Color(0xFF4A4A4A),
        keyboard = Color(0xFF2B2930),
    )

    @Test
    fun `主题色记号按当前主题解析`() {
        assertEquals(
            lightColors.accent,
            KeyBinding(background = KeyColor.Token("accent")).toKeyStyle(false, lightColors).background,
        )
        assertEquals(
            darkColors.accent,
            KeyBinding(background = KeyColor.Token("primary")).toKeyStyle(true, darkColors).background,
        )
        assertEquals(
            lightColors.special,
            KeyBinding(background = KeyColor.Token("special")).toKeyStyle(false, lightColors).background,
        )
        assertEquals(
            lightColors.key,
            KeyBinding(background = KeyColor.Token("key")).toKeyStyle(false, lightColors).background,
        )
        assertEquals(
            lightColors.keyboard,
            KeyBinding(background = KeyColor.Token("keyboard")).toKeyStyle(false, lightColors).background,
        )
    }

    @Test
    fun `暗色优先取 background_dark`() {
        val binding = KeyBinding(
            background = KeyColor.Token("accent"),
            backgroundDark = KeyColor.Token("special"),
        )
        assertEquals(lightColors.accent, binding.toKeyStyle(false, lightColors).background)
        assertEquals(darkColors.special, binding.toKeyStyle(true, darkColors).background)
    }

    @Test
    fun `固定颜色支持透明度`() {
        val style = KeyBinding(background = KeyColor.Hex(0x80FFFFFF)).toKeyStyle(false, lightColors)
        assertEquals(Color(0x80FFFFFF), style.background)
    }

    @Test
    fun `未知记号与未提供主题色时回退默认`() {
        assertNull(
            KeyBinding(background = KeyColor.Token("bogus")).toKeyStyle(false, lightColors).background
        )
        assertNull(
            KeyBinding(background = KeyColor.Token("accent")).toKeyStyle(false, KeyStyleColors()).background
        )
    }

    @Test
    fun `背景改色后自动选择对比文字色`() {
        val lightBg = KeyBinding(background = KeyColor.Hex(0xFFFFEB3B)).toKeyStyle(false, lightColors)
        val darkBg = KeyBinding(background = KeyColor.Hex(0xFF1F1F1F)).toKeyStyle(false, lightColors)
        assertEquals(Color(0xFF202124), lightBg.textColor)
        assertEquals(Color(0xFFE8EAED), darkBg.textColor)
    }
}
