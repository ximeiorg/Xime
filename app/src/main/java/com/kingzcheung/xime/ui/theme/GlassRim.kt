package com.kingzcheung.xime.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.KeysConfigHelper

/**
 * 玻璃下键帽的不透明度（已按深浅主题解析好：key.alpha / key.alpha_dark）。
 *
 * 由键盘根节点下发；键帽绘制路径拿不到 UI 状态，只能靠它。
 */
val LocalGlassKeyAlpha = compositionLocalOf { 1f }

/**
 * 玻璃键帽的一圈边：左上一条亮线，右下一条暗线。
 *
 * 半透明面本身只是着色，没有这条边就和塑料片一样平——玻璃"立起来"全靠它。
 * 纯绘制，不采样背景，所以任何 Android 版本都能用；折射那套才需要采样。
 *
 * @param enabled 只有键帽真的半透明（背景模糊生效）时才画，正常主题下保持原样
 */
@androidx.compose.runtime.Composable
fun Modifier.glassRim(shape: Shape): Modifier {
    // 按「玻璃是否启用」判断，而不是按键帽颜色是否半透明：
    // accent 键的颜色本身不透明（只是被兜底压了 alpha），按颜色判断会漏掉它们
    if (LocalGlassKeyAlpha.current >= 0.99f) return this
    val strength = KeysConfigHelper.getKeyboardGlass().key.rim.coerceIn(0f, 1f)
    if (strength <= 0f) return this
    // 只有迎光的两条边（左上）有高光，背光的两条边（右下）完全不上色。
    // 早先加过右下暗线，压在半透明键帽上会显成一道脏边，这里索性留空。
    val edge = Brush.linearGradient(
        0.00f to Color.White.copy(alpha = 0.38f * strength),
        0.30f to Color.White.copy(alpha = 0.16f * strength),
        0.52f to Color.Transparent,
        1.00f to Color.Transparent,
    )
    return border(BorderStroke(0.9.dp, edge), shape)
}
