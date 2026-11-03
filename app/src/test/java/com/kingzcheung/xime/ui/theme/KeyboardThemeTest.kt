package com.kingzcheung.xime.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KeyboardThemeTest {

    @Test
    fun `亮背景返回皮肤按键文字色`() {
        // 樱花粉黛深色模式修复后的场景：浅粉功能键背景 + 深酒红按键文字
        val onLight = Color(0xFF7D3A52)
        assertEquals(
            onLight,
            KeyboardThemes.getSpecialKeyTextColorForBackground(Color(0xFFFAD2E0), onLight)
        )
    }

    @Test
    fun `暗背景返回柔和亮白而非纯白`() {
        // 沉稳石墨深色功能键背景
        assertEquals(
            Color(0xFFE8EAED),
            KeyboardThemes.getSpecialKeyTextColorForBackground(Color(0xFF424242), Color(0xFF7D3A52))
        )
        // 设计约束：功能键文字不用纯白（高对比在暗光下刺眼）
        assertNotEquals(
            Color.White,
            KeyboardThemes.getSpecialKeyTextColorForBackground(Color(0xFF424242), Color.White)
        )
    }

    @Test
    fun `候选选中背景未配置时按强调色20%派生`() {
        val accent = KeyboardThemes.getAccentColor("lavender_purple", false)
        assertEquals(
            accent.copy(alpha = 0.2f),
            KeyboardThemes.getCandidateSelectedBgColor("lavender_purple", false)
        )
    }

    @Test
    fun `陶土橙背景走暗色分支`() {
        // 落日橙光深色功能键 0xD97757，亮度低于阈值应配柔白文字
        assertEquals(
            Color(0xFFE8EAED),
            KeyboardThemes.getSpecialKeyTextColorForBackground(Color(0xFFD97757), Color(0xFF7D3A52))
        )
    }
}
