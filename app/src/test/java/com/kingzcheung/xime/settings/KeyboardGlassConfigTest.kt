package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 液态玻璃配置的合并规则。
 *
 * 重点钉两条容易回归的语义：
 * 1. 不写 `alpha_dark` 时沿用 `alpha`（不是沿用默认值 100）
 * 2. 深浅两组互相独立，写一个不影响另一个
 */
class KeyboardGlassConfigTest {

    @Test
    fun `不写 alpha_dark 时深色沿用 alpha`() {
        val merged = KeysConfigHelper.mergeGlassConfigs(
            custom = KeyboardGlassPartial(background = GlassBackgroundPartial(alpha = 40)),
            builtIn = null,
        )
        assertEquals(40, merged.background.alpha)
        assertEquals(40, merged.background.alphaDark)
    }

    @Test
    fun `键帽同样沿用 alpha_dark`() {
        val merged = KeysConfigHelper.mergeGlassConfigs(
            custom = KeyboardGlassPartial(key = GlassKeyPartial(alpha = 55, rim = 0.5f)),
            builtIn = null,
        )
        assertEquals(55, merged.key.alpha)
        assertEquals(55, merged.key.alphaDark)
        assertEquals(0.5f, merged.key.rim, 0.001f)
    }

    @Test
    fun `深浅两组独立`() {
        val merged = KeysConfigHelper.mergeGlassConfigs(
            custom = KeyboardGlassPartial(
                background = GlassBackgroundPartial(alpha = 58, alphaDark = 74),
                key = GlassKeyPartial(alpha = 62, alphaDark = 70),
            ),
            builtIn = null,
        )
        assertEquals(58, merged.background.alphaFor(isDark = false))
        assertEquals(74, merged.background.alphaFor(isDark = true))
        assertEquals(62, merged.key.alphaFor(isDark = false))
        assertEquals(70, merged.key.alphaFor(isDark = true))
    }

    @Test
    fun `什么都不配时等于关闭玻璃`() {
        val merged = KeysConfigHelper.mergeGlassConfigs(custom = null, builtIn = null)
        assertEquals(100, merged.background.alpha)
        assertEquals(100, merged.background.alphaDark)
        assertEquals(0, merged.background.blurRadius)
        assertEquals(100, merged.key.alpha)
        assertEquals(0f, merged.key.rim, 0.001f)
    }

    @Test
    fun `custom 优先于 builtIn`() {
        val merged = KeysConfigHelper.mergeGlassConfigs(
            custom = KeyboardGlassPartial(background = GlassBackgroundPartial(alpha = 50)),
            builtIn = KeyboardGlassPartial(background = GlassBackgroundPartial(alpha = 90)),
        )
        assertEquals(50, merged.background.alpha)
    }

    @Test
    fun `顶部圆角默认为 0 且可配置`() {
        assertEquals(0, KeysConfigHelper.mergeGlassConfigs(null, null).background.cornerRadius)
        val merged = KeysConfigHelper.mergeGlassConfigs(
            custom = KeyboardGlassPartial(background = GlassBackgroundPartial(cornerRadius = 24)),
            builtIn = null,
        )
        assertEquals(24, merged.background.cornerRadius)
    }

    @Test
    fun `越界值被夹到合法区间`() {
        val merged = KeysConfigHelper.mergeGlassConfigs(
            custom = KeyboardGlassPartial(
                background = GlassBackgroundPartial(alpha = 0, blurRadius = 999),
                key = GlassKeyPartial(alpha = 500, rim = 9f),
            ),
            builtIn = null,
        )
        assertEquals(10, merged.background.alpha)
        assertEquals(150, merged.background.blurRadius)
        assertEquals(100, merged.key.alpha)
        assertEquals(1f, merged.key.rim, 0.001f)
        assertEquals(96, KeysConfigHelper.mergeGlassConfigs(
            custom = KeyboardGlassPartial(background = GlassBackgroundPartial(cornerRadius = 500)),
            builtIn = null,
        ).background.cornerRadius)
    }
}
