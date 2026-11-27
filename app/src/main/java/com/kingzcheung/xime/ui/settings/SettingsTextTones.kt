package com.kingzcheung.xime.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * 设置页「次要文本」统一用色。
 *
 * 原先直接用 `onSurfaceVariant`：浅色下勉强够淡，深色下它（L≈80）与主文本（L≈90）几乎同亮，
 * 满屏说明文字和标题糊成一片，主次分不出来。这里改成在「主文本色」与「表面色」之间按固定
 * 比例插值——深浅两套主题各取一个比例，保证比主文本弱一档，又不至于看不清。
 *
 * 只用在本页的说明/副标题类文本上；标题、开关值、错误提示各自仍用自己的语义色。
 */
@Composable
fun settingsSecondaryColor(): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return lerp(scheme.onSurface, scheme.surface, if (dark) 0.30f else 0.45f)
}
