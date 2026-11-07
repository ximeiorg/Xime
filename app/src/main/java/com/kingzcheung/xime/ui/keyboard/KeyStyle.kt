package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.KeyBinding
import com.kingzcheung.xime.settings.KeysConfigHelper

/**
 * 单个按键的外观覆盖（`keyboard.<section>.keys.<id>` 的 corner_radius / background）。
 * 全部为 null 表示未配置，渲染完全沿用键盘级/主题默认。
 */
internal data class KeyStyleOverride(
    val cornerRadius: Dp? = null,
    val background: Color? = null,
    val textColor: Color? = null,
) {
    val isEmpty: Boolean get() = cornerRadius == null && background == null
}

internal fun keyStyleOverride(
    section: String,
    id: String,
    isAsciiMode: Boolean,
    isDarkTheme: Boolean,
): KeyStyleOverride =
    KeysConfigHelper.getGestureFor(section, id, isAsciiMode)?.toKeyStyle(isDarkTheme) ?: KeyStyleOverride()

internal fun KeyBinding.toKeyStyle(isDarkTheme: Boolean): KeyStyleOverride {
    val bg = (if (isDarkTheme) backgroundDark ?: background else background)?.let(::keyColor)
    return KeyStyleOverride(
        cornerRadius = cornerRadius?.takeIf { it >= 0 }?.dp,
        background = bg,
        textColor = bg?.let(::contrastTextColor),
    )
}

/** 0xRRGGBB 补足不透明通道；0xAARRGGBB 原样使用。 */
private fun keyColor(hex: Long): Color =
    if (hex > 0xFFFFFF) Color(hex) else Color(0xFF000000 or hex)

/** 按键背景改色后按亮度选前景，避免深底深字/浅底浅字。 */
private fun contrastTextColor(background: Color): Color =
    if (background.luminance() > 0.5f) Color(0xFF202124) else Color(0xFFE8EAED)

/** 把按键级背景/前景覆盖应用到共享渲染环境；圆角由 CompositionLocal 单独提供。 */
internal fun QwertyRowEnv.withKeyStyle(style: KeyStyleOverride): QwertyRowEnv {
    if (style.isEmpty) return this
    return copy(
        keyBackgroundColor = style.background ?: keyBackgroundColor,
        specialKeyBackgroundColor = style.background ?: specialKeyBackgroundColor,
        keyTextColor = style.textColor ?: keyTextColor,
        specialKeyTextColor = style.textColor ?: specialKeyTextColor,
        // 阴影与键面用同一圆角，避免按键级圆角和默认阴影半径不一致时出现「双层不同圆角」
        shadowShapeRadius = style.cornerRadius ?: shadowShapeRadius,
    )
}
