package com.kingzcheung.xime.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 语义状态色。
 *
 * M3 的 color roles 里没有 success / warning（`primary` 跟主题色走，绿不绿取决于取色），
 * 所以「在线=绿、附近=橙」单独给一组固定色，取值与桌面端（`libximecore` 的
 * `ThemeColors.success` / `tertiary`）保持一致；深浅色各一份，按当前 surface 明暗选用。
 */
object SemanticColors {
    val OnlineLight = Color(0xFF059669)
    val OnlineDark = Color(0xFF34D399)
    val NearbyLight = Color(0xFFB45E1E)
    val NearbyDark = Color(0xFFF2B87D)
}

/** 按当前主题底色在「浅色版 / 深色版」之间选一个，避免写死一种在另一套配色下看不清。 */
@Composable
fun semanticColor(light: Color, dark: Color): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) dark else light
