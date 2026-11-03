package com.kingzcheung.xime.ui.settings


import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.twotone.KeyboardControlKey
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.LocalKeyCornerRadius
import com.kingzcheung.xime.ui.keyboard.crispShadowColor
import com.kingzcheung.xime.ui.theme.KeyboardColorScheme
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.keyboardBackground

private val QWERTY_ROW0 = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
private val QWERTY_ROW1 = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
private val QWERTY_ROW2 = listOf("Z", "X", "C", "V", "B", "N", "M")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemePreviewSheet(
    theme: KeyboardColorScheme,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = theme.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            ThemePreviewPager(theme = theme)

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text(tr(R.string.s_quxiao), fontSize = 16.sp)
                }
                Button(
                    onClick = onApply,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text(tr(R.string.s_yingyong), fontSize = 16.sp)
                }
            }
        }
    }
}

private data class PreviewPage(val label: String, val isDark: Boolean)

private val PREVIEW_PAGES = listOf(
    PreviewPage(tr(R.string.s_qianse), false),
    PreviewPage(tr(R.string.s_shense), true),
)

@Composable
private fun ThemePreviewPager(
    theme: KeyboardColorScheme,
) {
    val pagerState = rememberPagerState(pageCount = { PREVIEW_PAGES.size })

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        ) { page ->
            val preview = PREVIEW_PAGES[page]
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                ThemeKeyboardPreview(
                    theme = theme,
                    isDark = preview.isDark,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 2.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        PageIndicator(
            pageCount = PREVIEW_PAGES.size,
            currentPage = pagerState.currentPage,
            activeColor = MaterialTheme.colorScheme.primary,
            inactiveColor = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    activeColor: Color,
    inactiveColor: Color,
    dotSize: Dp = 8.dp,
    spacing: Dp = 6.dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .padding(horizontal = spacing / 2)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(if (index == currentPage) activeColor else inactiveColor),
            )
        }
    }
}

@Composable
private fun ThemeKeyboardPreview(
    theme: KeyboardColorScheme,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (isDark) theme.keyboardBgDark else theme.keyboardBgLight
    val specialKeyColor = if (isDark) theme.specialKeyDark else theme.specialKeyLight
    val textColor = if (isDark) theme.keyTextColorDark else theme.keyTextColorLight

    val kbColors = KeysConfigHelper.getKeyboardColors()
    val longToColor: (Long) -> Color = { if (it > 0xFFFFFF) Color(it) else Color(0xFF000000 or it) }
    val keyColor = KeyboardThemes.getKeyBgColorOverride(theme.id, isDark)
        ?: if (isDark) longToColor(kbColors.keyBgColorDark) else longToColor(kbColors.keyBgColor)
    val candidateTextColor = KeyboardThemes.getCandidateTextColorOverride(theme.id, isDark)
        ?: if (isDark) longToColor(kbColors.candidateTextColorDark)
        else longToColor(kbColors.candidateTextColor)
    val selectedTextColor = KeyboardThemes.getCandidateSelectedTextColorOverride(theme.id, isDark)
        ?: if (isDark) theme.candidateSelectedTextColorDark else theme.candidateSelectedTextColorLight
    val selectedBgColor = KeyboardThemes.getCandidateSelectedBgColor(theme.id, isDark)
    // 与实机键盘同一条取色逻辑（亮度自适应），保证预览所见即所得
    val specialTextColor = KeyboardThemes.getSpecialKeyTextColorForBackground(
        specialKeyColor, textColor
    )
    val context = LocalContext.current
    val candidateTextSize = SettingsPreferences.getCandidateTextSize(context)

    val cornerRadius = KeysConfigHelper.getKeyboardKeyConfig().cornerRadius.dp

    CompositionLocalProvider(
        LocalKeyCornerRadius provides cornerRadius,
    ) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .keyboardBackground(theme.keyboardBackground, isDark, bgColor),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            ) {
                CandidateBarPreview(candidateTextColor, selectedTextColor, selectedBgColor, candidateTextSize)

                Column(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    // Row 1: QWERTYUIOP
                    Box(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            QWERTY_ROW0.forEach { key ->
                                PreviewKey(
                                    key,
                                    keyColor,
                                    textColor,
                                    1f,
                                    extraModifier = Modifier.padding(0.5.dp, 2.dp)
                                )
                            }
                        }
                    }

                    // Row 2: ASDFGHJKL
                    Box(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            QWERTY_ROW1.forEach { key ->
                                PreviewKey(
                                    key,
                                    keyColor,
                                    textColor,
                                    1f,
                                    extraModifier = Modifier.padding(0.5.dp, 2.dp)
                                )
                            }
                        }
                    }

                    // Row 3: Shift ZXCVBNM Backspace
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PreviewKey(
                            label = "",
                            icon = rememberVectorPainter(Icons.TwoTone.KeyboardControlKey),
                            color = specialKeyColor, textColor = specialTextColor, weight = 1.4f,
                            extraModifier = Modifier.padding(0.5.dp, 2.dp)
                        )

                        Row(
                            modifier = Modifier
                                .weight(7f)
                                .fillMaxHeight(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            QWERTY_ROW2.forEach { key ->
                                PreviewKey(
                                    key,
                                    keyColor,
                                    textColor,
                                    1f,
                                    extraModifier = Modifier.padding(0.5.dp, 2.dp)
                                )
                            }
                        }

                        PreviewKey(
                            label = "",
                            icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                            color = specialKeyColor, textColor = specialTextColor, weight = 1.4f,
                            extraModifier = Modifier.padding(0.5.dp, 2.dp)
                        )
                    }

                    // Row 4: ?123 , 空格 中/En 确定
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PreviewKey("?123", specialKeyColor, specialTextColor, 1.2f,extraModifier = Modifier.padding(0.5.dp, 2.dp))
                        PreviewKey("，", keyColor, textColor, 0.8f,extraModifier = Modifier.padding(0.5.dp, 2.dp))
                        PreviewKey(tr(R.string.s_kongge), keyColor, textColor, 3f, fontSize = 9.sp,extraModifier = Modifier.padding(0.5.dp, 2.dp))
                        PreviewKey(tr(R.string.s_zhong_en), specialKeyColor, specialTextColor, 0.8f, fontSize = 8.sp,extraModifier = Modifier.padding(0.5.dp, 2.dp))
                        PreviewKey(tr(R.string.s_queding), specialKeyColor, specialTextColor, 1.2f,extraModifier = Modifier.padding(0.5.dp, 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateBarPreview(
    textColor: Color,
    selectedTextColor: Color,
    selectedBgColor: Color,
    textSize: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PreviewCandidate(
                text = tr(R.string.s_xima),
                isSelected = true,
                textColor = textColor,
                selectedTextColor = selectedTextColor,
                selectedBgColor = selectedBgColor,
                textSize = textSize
            )
            Spacer(modifier = Modifier.width(4.dp))
            PreviewCandidate(
                text = tr(R.string.s_shurufa),
                isSelected = false,
                textColor = textColor,
                selectedTextColor = selectedTextColor,
                selectedBgColor = selectedBgColor,
                textSize = textSize
            )
        }
    }
}

@Composable
private fun PreviewCandidate(
    text: String,
    isSelected: Boolean,
    textColor: Color,
    selectedTextColor: Color,
    selectedBgColor: Color,
    textSize: Int,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (isSelected) selectedBgColor else Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = if (isSelected) selectedTextColor else textColor,
            fontSize = textSize.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun RowScope.PreviewKey(
    label: String,
    color: Color,
    textColor: Color,
    weight: Float,
    icon: Painter? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    extraModifier: Modifier = Modifier,
) {
    val shadow = KeysConfigHelper.getKeyboardShadow()
    val density = LocalDensity.current
    val shadowModifier = remember(shadow, density, color) {
        if (shadow.enabled) {
            val offsetPx = with(density) { shadow.elevation.dp.toPx() }
            val cornerPx = with(density) { shadow.shapeRadius.dp.toPx() }
            Modifier.drawBehind {
                drawRoundRect(
                    color = crispShadowColor(color),
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx),
                )
            }
        } else Modifier
    }

    Box(
        modifier = extraModifier
            .weight(weight)
            .fillMaxHeight()
            .fillMaxWidth()
            .then(shadowModifier)
            .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(
                text = label,
                fontSize = fontSize,
                color = textColor,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}
