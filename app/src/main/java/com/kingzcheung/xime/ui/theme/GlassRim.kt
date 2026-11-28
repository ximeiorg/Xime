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
 * 玻璃键帽的一圈边：上下两条边迎光，左右两条边留空。
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
    // 光源在正上方：顶边最亮，底边是桌面回弹的余光——明显弱于顶边，但要留得住（约 2.3:1），
    // 压太狠就整条看不见了。左右两侧只在与顶/底相接的转角处淡淡过渡：边框走到左右两边
    // 中段时颜色已经归零。早先用过左上→右下的斜向渐变，宽键（空格）上的高光会沿着宽度
    // 衰减，看着像半边亮。
    // 线宽与不透明度都要克制：这是照在半透明面上的光，不是给键帽描白边。
    val edge = Brush.verticalGradient(
        0.00f to Color.White.copy(alpha = 0.40f * strength),
        0.16f to Color.White.copy(alpha = 0.06f * strength),
        0.50f to Color.Transparent,
        0.85f to Color.White.copy(alpha = 0.03f * strength),
        1.00f to Color.White.copy(alpha = 0.17f * strength),
    )
    return border(BorderStroke(0.85.dp, edge), shape)
}
