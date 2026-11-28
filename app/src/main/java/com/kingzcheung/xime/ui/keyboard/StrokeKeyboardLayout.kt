package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.i18n.tr

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.settings.DisplayMode
import com.kingzcheung.xime.settings.KeyAction
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.swipeHandlerFor
import com.kingzcheung.xime.ui.isTablet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.R
import com.kingzcheung.xime.util.PermissionHelper
import com.kingzcheung.xime.util.SubcharHelper

/**
 * 笔画键盘布局 — 参照 [T9KeyboardLayout] 结构。
 *
 * 布局说明：
 * - 左侧候选区：。？ ！ ~ 4 个常用标点，纵向排列。
 * - 中间主键区：
 *   第1行：一(h) | 丨(s) | 丿(p)
 *   第2行：丶(n) | 乛(z) | *
 *   第3行：分词 | ， | 英
 *   第4行：符号 | 123 | 空格 | .
 * - 右侧功能键区：退格 | 重输 | 确定
 */
@Composable
fun StrokeKeyboardLayout(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    keyboardBackgroundColor: Color = Color.Transparent,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    keyCornerRadius: Dp = 8.dp,
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    isFloatingMode: Boolean = false,
    specialKeyTextColor: Color = Color.White,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
    schemaName: String = "",
    isSttEnabled: Boolean = false,
    voiceSticky: Boolean = false,
    onVoiceModeChange: ((Boolean) -> Unit)? = null,
) {
    StrokeKeyboardSwipeOverlay(
        modifier = modifier,
        keyboardBackgroundColor = keyboardBackgroundColor,
        keyCornerRadius = keyCornerRadius,
        keyTextColor = keyTextColor,
        isFloatingMode = isFloatingMode,
        onKeyPress = onKeyPress,
        keyBackgroundColor = keyBackgroundColor,
        specialKeyBackgroundColor = specialKeyBackgroundColor,
        bubbleBackgroundColor = bubbleBackgroundColor,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        onKeyPressDown = onKeyPressDown,
        specialKeyTextColor = specialKeyTextColor,
        keySpacingX = keySpacingX,
        keySpacingY = keySpacingY,
        onGestureAction = onGestureAction,
        schemaName = schemaName,
        isSttEnabled = isSttEnabled,
        voiceSticky = voiceSticky,
        onVoiceModeChange = onVoiceModeChange,
    )
}

// ─── 滑动气泡覆盖层 ────────────────────────────────────────────────

@Composable
private fun StrokeKeyboardSwipeOverlay(
    modifier: Modifier,
    keyboardBackgroundColor: Color,
    keyCornerRadius: Dp,
    keyTextColor: Color,
    isFloatingMode: Boolean,
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    specialKeyBackgroundColor: Color,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
onKeyPressDown: ((String) -> Unit)?,
    specialKeyTextColor: Color,
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
    schemaName: String = "",
    isSttEnabled: Boolean = false,
    voiceSticky: Boolean = false,
    onVoiceModeChange: ((Boolean) -> Unit)? = null,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = !isFloatingMode && configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    // 设备档：与九键同款——手机横屏键矮走紧凑档（去掉左侧符号栏、字号收小），
    // 平板键盘区足够高，横竖屏都用完整布局 + 平板留白（见 [panelOuterPadding]）。
    val tablet = isTablet()
    val compactLandscape = isLandscape && !tablet

    val swipeBubble = rememberSwipeBubbleController()
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)

    val bubbleData = rememberSwipeBubbleDrawData(
        swipeState = swipeBubble.state,
        keyBounds = swipeBubble.keyBounds,
        keyBackgroundColor = bubbleBackgroundColor,
        keyTextColor = keyTextColor,
        accentColor = specialKeyTextColor,
        keyWidth = if (swipeBubble.state.isSwiping || swipeBubble.state.isPressed) swipeBubble.keyBounds.width else 0f,
        keyboardWidth = keyboardBounds.width
    )

    fun processSwipeState(state: SwipeState, bounds: Rect) {
        val newState = if (state.isSwipeDown && state.swipeText != null) {
            state.copy(charInfos = SubcharHelper.parseSwipeDownText(state.swipeText))
        } else {
            state
        }
        swipeBubble.update(
            newState,
            Rect(
                left = bounds.left - keyboardBounds.left,
                top = bounds.top - keyboardBounds.top,
                right = bounds.right - keyboardBounds.left,
                bottom = bounds.bottom - keyboardBounds.top
            )
        )
    }

    // 外围留白与设备档（面板/26 键/九键同源）：手机竖屏 4/4/8、手机横屏 50/2、平板 16/16/8
    val outerPadding = panelOuterPadding(isLandscape = isLandscape, isTablet = tablet)

    CompositionLocalProvider(
        LocalKeyCornerRadius provides keyCornerRadius,
        // 键面提示与主键盘同一档：手机横屏/平板画到键角，平板放大加深
        LocalSwipeHintCorner provides swipeHintCornerFor(isLandscape, tablet),
        LocalHintExtraScale provides hintExtraScaleFor(tablet),
    ) {
    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
            }
            .drawWithContent {
                drawContent()
                bubbleData?.let { drawSwipeBubble(it) }
            }
            .padding(bottom = if (isFloatingMode || isLandscape) 0.dp else 0.dp),
    ) {
        if (compactLandscape) {
            // 手机横屏：去除原左侧符号面板（符号走符号键盘），
            // 笔画布局撑满键盘区域（与全键盘横屏同款 50dp 边距）
            CompositionLocalProvider(
                LocalKeyVisualPadding provides PaddingValues(
                    horizontal = keySpacingX ?: 2.dp,
                    vertical = keySpacingY ?: 2.dp,
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(outerPadding),
                ) {
                    StrokeKeyboardContent(
                        onKeyPress = onKeyPress,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBackgroundColor,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        onKeyPressDown = onKeyPressDown,
                        onSwipeStateChange = ::processSwipeState,
                        specialKeyTextColor = specialKeyTextColor,
                        compactMode = true,
                        onGestureAction = onGestureAction,
                        schemaName = schemaName,
                        isSttEnabled = isSttEnabled,
                        voiceSticky = voiceSticky,
                        onVoiceModeChange = onVoiceModeChange,
                    )
                }
            }
        } else {
            CompositionLocalProvider(
                LocalKeyVisualPadding provides PaddingValues(
                    horizontal = keySpacingX ?: 2.dp,
                    vertical = keySpacingY ?: 2.dp,
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(outerPadding),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    StrokeKeyboardContent(
                        onKeyPress = onKeyPress,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBackgroundColor,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        onKeyPressDown = onKeyPressDown,
                        onSwipeStateChange = ::processSwipeState,
                        specialKeyTextColor = specialKeyTextColor,
                        compactMode = false,
                        onGestureAction = onGestureAction,
                        schemaName = schemaName,
                        isSttEnabled = isSttEnabled,
                        voiceSticky = voiceSticky,
                        onVoiceModeChange = onVoiceModeChange,
                    )
                }
            }
        }
    }
    }
}

// ─── 笔画键盘三列主体 ──────────────────────────────────────────────

private data class StrokeKeyDef(
    val mainLabel: String,
    val swipeDigit: String,
    val commit: String,
)

/** 笔画键的滑动配置：回调 + 提示文本（keyboard.stroke.keys 配置，无配置时回退内置默认）。 */
private data class StrokeKeySwipes(
    val onSwipeUp: (() -> Unit)? = null,
    val onSwipeDown: (() -> Unit)? = null,
    val swipeUpText: String? = null,
    val swipeDownText: String? = null,
    /** 上滑键面提示（空串 = 显式不印键面，bubble 模式用；null = 回退 swipeText） */
    val swipeUpKeyLabel: String? = null,
    val swipeDownKeyLabel: String? = null,
)

private val strokeKeys = listOf(
    StrokeKeyDef("一", "1", "h"),
    StrokeKeyDef("丨", "2", "s"),
    StrokeKeyDef("丿", "3", "p"),
    StrokeKeyDef("丶", "4", "n"),
    StrokeKeyDef("乛", "5", "z"),
)

@Composable
private fun StrokeKeyboardContent(
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    onKeyPressDown: ((String) -> Unit)?,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)?,
    specialKeyTextColor: Color,
    compactMode: Boolean = false,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
    schemaName: String = "",
    isSttEnabled: Boolean = false,
    voiceSticky: Boolean = false,
    onVoiceModeChange: ((Boolean) -> Unit)? = null,
) {
    val ctrlFontSize = if (compactMode) 11.sp else androidx.compose.ui.unit.TextUnit.Unspecified
    val strokeFontSize = if (compactMode) 13.sp else 16.sp
    val symbolFontSize = if (compactMode) 11.sp else 13.sp
    val specialCtxTextColor = if (compactMode) specialKeyTextColor
        else (if (keyTextColor == Color(0xFFE8EAED)) Color.White
              else Color(0xFF1A73E8))

    val suppressCursorMove = LocalSuppressCursorMove.current

    // 笔画键滑动手势（keyboard.stroke.keys，热重载经 configVersion 感知）：
    // 有配置走配置（可覆盖上滑/新增下滑动作），无配置回退内置默认（上滑提交对应数字）。
    // COMMIT 沿用 onKeyPress（保持笔画模式数字的按键路由语义）。
    // 提示恒显，是否绘制由按键配置的 display/bubble 决定；手势触发只看回调绑定，与提示无关。
    // 上滑键面提示尊重 display: bubble（仅气泡不印键面）。
    val configVersion by KeysConfigHelper.configVersion.collectAsState()
    val hintsActive = !compactMode
    // 左侧快捷符号列来自 xime.yaml keyboard.stroke.side_symbols（可自定义，>4 滚动显示）
    val strokeSideSymbols = remember(configVersion) { KeysConfigHelper.getStrokeSideSymbols() }

    // 符号面板统一阴影（与九键左栏候选面板同款样式）
    val density = LocalDensity.current
    val symbolPanelDrawShadow = keyShadowEnabled(shadowEnabled)
    val symbolPanelShadowModifier = remember(symbolPanelDrawShadow, shadowElevation, shadowShapeRadius, density, keyBackgroundColor) {
        if (symbolPanelDrawShadow) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(keyBackgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }

    fun swipesFor(id: String, fallbackDigit: String): StrokeKeySwipes {
        val gesture = KeysConfigHelper.getStrokeKeyGesture(id)
        if (gesture == null || (gesture.swipeUp == null && gesture.swipeDown == null)) {
            // 回退行为与内置默认（display: "key"）一致：仅键面提示，无滑动气泡
            return StrokeKeySwipes(
                onSwipeUp = { onKeyPress(fallbackDigit) },
                swipeUpKeyLabel = if (hintsActive) fallbackDigit else null,
            )
        }
        fun hint(def: KeyAction?): String? =
            def?.let { it.label.ifEmpty { it.value } }
        // display 只管静态键面提示位置（bubble 不画键面，用空串压制回退）；
        // 运行时气泡由 bubble 独立控制。
        val swipeUpKeyLabel = when {
            !hintsActive -> null
            gesture.swipeUp?.display == DisplayMode.BUBBLE -> ""
            else -> hint(gesture.swipeUp)
        }
        val swipeDownKeyLabel = when {
            !hintsActive -> null
            gesture.swipeDown?.display == DisplayMode.BUBBLE -> ""
            else -> hint(gesture.swipeDown)
        }
        return StrokeKeySwipes(
            onSwipeUp = swipeHandlerFor(gesture.swipeUp, onKeyPress, onGestureAction),
            onSwipeDown = swipeHandlerFor(gesture.swipeDown, onKeyPress, onGestureAction),
            swipeUpText = if (hintsActive && (gesture.swipeUp?.bubble ?: true)) hint(gesture.swipeUp) else null,
            swipeDownText = if (hintsActive && (gesture.swipeDown?.bubble ?: true)) hint(gesture.swipeDown) else null,
            swipeUpKeyLabel = swipeUpKeyLabel,
            swipeDownKeyLabel = swipeDownKeyLabel,
        )
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(if (compactMode) 2.dp else 4.dp)
    ) {
        // ── 第1列：左侧符号区（面板样式与九键左栏对齐：统一圆角/阴影/内边距） ──
        Column(
            modifier = Modifier.fillMaxHeight().weight(0.8f),
            verticalArrangement = Arrangement.spacedBy(if (compactMode) 2.dp else 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(3f)
                    .padding(LocalKeyVisualPadding.current)
                    .then(symbolPanelShadowModifier)
                    .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
                    .background(glassFill(keyBackgroundColor))
            ) {
                if (strokeSideSymbols.size <= 4) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        strokeSideSymbols.forEach { symbol ->
                            StrokeSymbolItem(
                                text = symbol,
                                onClick = { onKeyPress(symbol) },
                                onPress = { onKeyPressDown?.invoke(symbol) },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                fontSize = symbolFontSize,
                                modifier = Modifier.fillMaxWidth().weight(1f)
                            )
                        }
                    }
                } else {
                    // 超过 4 个滚动显示（对齐九键 side_symbols 体验），条目定高、由面板圆角统一裁切
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        itemsIndexed(strokeSideSymbols) { _, symbol ->
                            StrokeSymbolItem(
                                text = symbol,
                                onClick = { onKeyPress(symbol) },
                                onPress = { onKeyPressDown?.invoke(symbol) },
                                backgroundColor = keyBackgroundColor,
                                textColor = keyTextColor,
                                fontSize = symbolFontSize,
                                modifier = Modifier.fillMaxWidth().height(if (compactMode) 26.dp else 32.dp)
                            )
                        }
                    }
                }
            }
            StrokeSymbolButton(
                text = tr(R.string.s_fuhao),
                onClick = { onKeyPress("symbol") },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = Modifier.fillMaxWidth().weight(1f),
                onPress = { onKeyPressDown?.invoke("symbol") },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                fontSize = ctrlFontSize,
            )
        }

        // ── 第2列：笔画键区 ──
        Column(
            modifier = Modifier.fillMaxHeight().weight(3.2f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                strokeKeys.take(3).forEach { key ->
                    StrokeKeyItem(
                        mainLabel = key.mainLabel,
                        swipeDigit = key.swipeDigit,
                        onClick = { onKeyPress(key.commit) },
                        onPress = { onKeyPressDown?.invoke(key.commit) },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        strokeFontSize = strokeFontSize,
                        compactMode = compactMode,
                        onSwipeStateChange = onSwipeStateChange,
                        swipes = swipesFor(key.mainLabel, key.swipeDigit),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                strokeKeys.drop(3).forEach { key ->
                    StrokeKeyItem(
                        mainLabel = key.mainLabel,
                        swipeDigit = key.swipeDigit,
                        onClick = { onKeyPress(key.commit) },
                        onPress = { onKeyPressDown?.invoke(key.commit) },
                        backgroundColor = keyBackgroundColor,
                        textColor = keyTextColor,
                        modifier = Modifier.weight(1f),
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        strokeFontSize = strokeFontSize,
                        compactMode = compactMode,
                        onSwipeStateChange = onSwipeStateChange,
                        swipes = swipesFor(key.mainLabel, key.swipeDigit),
                    )
                }
                StrokeDigitKey(
                    digit = "*", swipeDigit = "6",
                    onClick = { onKeyPress("*") },
                    onPress = { onKeyPressDown?.invoke("*") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    fontSize = strokeFontSize,
                    onSwipeStateChange = onSwipeStateChange,
                    swipes = swipesFor("*", "6"),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                StrokeDigitKey(
                    digit = tr(R.string.s_fenci), swipeDigit = "7",
                    onClick = { onKeyPress("word_separator") },
                    onPress = { onKeyPressDown?.invoke("word_separator") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    fontSize = strokeFontSize,
                    onSwipeStateChange = onSwipeStateChange,
                    swipes = swipesFor(tr(R.string.s_fenci), "7"),
                )
                StrokeDigitKey(
                    digit = "，", swipeDigit = "8",
                    onClick = { onKeyPress("，") },
                    onPress = { onKeyPressDown?.invoke("，") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    fontSize = strokeFontSize,
                    onSwipeStateChange = onSwipeStateChange,
                    swipes = swipesFor("，", "8"),
                )
                StrokeDigitKey(
                    digit = tr(R.string.s_ying), swipeDigit = "9",
                    onClick = { onKeyPress("ime_switch") },
                    onPress = { onKeyPressDown?.invoke("ime_switch") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    fontSize = strokeFontSize,
                    onSwipeStateChange = onSwipeStateChange,
                    swipes = swipesFor(tr(R.string.s_ying), "9"),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                StrokeSymbolButton(
                    text = "123",
                    onClick = { onKeyPress("number") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke("number") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    fontSize = ctrlFontSize,
                )
                // 空格上滑（keyboard.stroke.keys.space.swipe_up，内置配置为直接输入 "0"）：
                // 与笔画键上滑同路径（直接上屏，不经 rime 组合）
                val spaceSwipe = KeysConfigHelper.getStrokeKeyGesture("space")?.swipeUp
                StrokeSpaceButton(
                    onKeyPress = onKeyPress,
                    onKeyPressDown = onKeyPressDown,
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1.8f),
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    onSwipeUp = swipeHandlerFor(spaceSwipe, onKeyPress, onGestureAction),
                    swipeUpBadge = if (hintsActive && spaceSwipe?.display != DisplayMode.BUBBLE)
                        spaceSwipe?.label?.ifEmpty { spaceSwipe?.value } else null,
                    schemaName = schemaName,
                    isSttEnabled = isSttEnabled,
                    voiceSticky = voiceSticky,
                    onVoiceModeChange = onVoiceModeChange,
                )
                StrokeSymbolButton(
                    text = ".",
                    onClick = { onKeyPress(".") },
                    backgroundColor = keyBackgroundColor,
                    textColor = keyTextColor,
                    modifier = Modifier.weight(1f),
                    onPress = { onKeyPressDown?.invoke(".") },
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    shadowShapeRadius = shadowShapeRadius,
                    fontSize = ctrlFontSize,
                )
            }
        }

        // ── 第3列：功能键区 ──
        Column(
            modifier = Modifier.fillMaxHeight().weight(0.9f),
        ) {
            SwipeableIconKeyButton(
                icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                onClick = { onKeyPress("delete") },
                onLongClick = { onKeyPress("delete") },
                backgroundColor = specialKeyBackgroundColor,
                iconColor = specialKeyTextColor,
                modifier = Modifier.weight(1f),
                a11yDescription = tr(R.string.s_tuige),
                swipeText = if (compactMode) null else tr(R.string.s_qingkong),
                onSwipe = { onKeyPress("clear_composition") },
                onPress = { onKeyPressDown?.invoke("delete") },
                swipeUpLabel = if (compactMode) null else tr(R.string.s_shanghuaqingkong),
                swipeDownLabel = if (compactMode) null else tr(R.string.s_xiahuachehui),
                onSwipeUp = { onKeyPress("clear_all") },
                onSwipeDown = { onKeyPress("undo_clear") },
                onSwipeLeft = {
                    suppressCursorMove.value = true
                    onKeyPress("clear_composition")
                },
                onSwipeStateChange = onSwipeStateChange,
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
            ResetKey(
                onClick = { onKeyPress("clear_composition") },
                onPress = { onKeyPressDown?.invoke("clear") },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = Modifier.weight(1f),
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                compactMode = compactMode,
            )
            StrokeSymbolButton(
                text = tr(R.string.s_queding),
                onClick = { onKeyPress("enter") },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = Modifier.weight(2f),
                onPress = { onKeyPressDown?.invoke("enter") },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                fontSize = androidx.compose.ui.unit.TextUnit.Unspecified,
            )
        }
    }
}

// ─── 子组件 ───────────────────────────────────────────────────────

@Composable
private fun StrokeSymbolItem(
    text: String,
    onClick: () -> Unit,
    onPress: (() -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    // 面板样式与九键左栏（CandidateItem）一致：条目自身透明、按压时垫底层加深，
    // 圆角/阴影由外层面板统一负责
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isPressed) backgroundColor.copy(alpha = 0.7f) else Color.Transparent)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    currentOnPress?.invoke()
                    tryAwaitRelease()
                    isPressed = false
                }, onTap = { currentOnClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            fontFamily = AppFonts.candidateFontFamily
        )
    }
}

@Composable
private fun StrokeKeyItem(
    mainLabel: String,
    swipeDigit: String,
    onClick: () -> Unit,
    onPress: (() -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    strokeFontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    compactMode: Boolean = false,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    swipes: StrokeKeySwipes = StrokeKeySwipes(),
) {
    SwipeableKeyButton(
        text = mainLabel,
        // tap.bubble: false → 不弹按压气泡（默认 true，行为与改动前一致）；
        // 笔画键的配置取自 keyboard.stroke.keys（键 id = 笔画键面字符）
        pressText = mainLabel.takeIf { KeysConfigHelper.getStrokeKeyGesture(mainLabel)?.tap?.bubble ?: true },
        onClick = onClick,
        backgroundColor = backgroundColor,
        textColor = textColor,
        fontSize = strokeFontSize,
        modifier = modifier,
        onPress = onPress,
        onSwipe = if (swipes.onSwipeUp != null) {
            // 笔画键盘只接线了上/下滑：横向滑动不得落到上滑处理器（此前忽略方向 → 左/右滑会误输入上滑内容）
            { dir -> if (dir != "left" && dir != "right") swipes.onSwipeUp?.invoke() }
        } else null,
        onSwipeDown = swipes.onSwipeDown?.let { handler -> { _: String -> handler() } },
        onSwipeStateChange = onSwipeStateChange,
        badgeText = swipeDigit,
        swipeText = swipes.swipeUpText,
        swipeDownText = swipes.swipeDownText,
        swipeUpKeyLabel = swipes.swipeUpKeyLabel,
        swipeDownKeyLabel = swipes.swipeDownKeyLabel,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        longPressItems = null,
    )
}

@Composable
private fun StrokeDigitKey(
    digit: String,
    swipeDigit: String,
    onClick: () -> Unit,
    onPress: (() -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    swipes: StrokeKeySwipes = StrokeKeySwipes(),
) {
    SwipeableKeyButton(
        text = digit,
        // tap.bubble: false → 不弹按压气泡（默认 true）。与 StrokeKeyItem 同口径：
        // 配置取自 keyboard.stroke.keys（分词/，/英/* 均在其中），内置已配 bubble: false
        // （与九键拼音键盘的无气泡交互对齐）。不显式传 pressText 时默认等于 text，必弹气泡。
        pressText = digit.takeIf { KeysConfigHelper.getStrokeKeyGesture(digit)?.tap?.bubble ?: true },
        onClick = onClick,
        backgroundColor = backgroundColor,
        textColor = textColor,
        modifier = modifier,
        onPress = onPress,
        onSwipe = if (swipes.onSwipeUp != null) {
            // 笔画键盘只接线了上/下滑：横向滑动不得落到上滑处理器（此前忽略方向 → 左/右滑会误输入上滑内容）
            { dir -> if (dir != "left" && dir != "right") swipes.onSwipeUp?.invoke() }
        } else null,
        onSwipeDown = swipes.onSwipeDown?.let { handler -> { _: String -> handler() } },
        onSwipeStateChange = onSwipeStateChange,
        badgeText = swipeDigit,
        swipeText = swipes.swipeUpText,
        swipeDownText = swipes.swipeDownText,
        swipeUpKeyLabel = swipes.swipeUpKeyLabel,
        swipeDownKeyLabel = swipes.swipeDownKeyLabel,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
    )
}

@Composable
private fun StrokeSymbolButton(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
) {
    KeyButton(
        text = text,
        onClick = onClick,
        backgroundColor = backgroundColor,
        textColor = textColor,
        modifier = modifier,
        onPress = onPress,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        fontSize = fontSize,
    )
}

@Composable
private fun ResetKey(
    onClick: () -> Unit,
    onPress: (() -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    compactMode: Boolean = false,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    val density = LocalDensity.current
    val shape = RoundedCornerShape(shadowShapeRadius)
    val drawShadow = keyShadowEnabled(shadowEnabled)
    val shadowModifier = remember(drawShadow, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (drawShadow) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .then(shadowModifier)
            .clip(shape)
            .background(glassFillPressed(backgroundColor, isPressed))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    currentOnPress?.invoke()
                    tryAwaitRelease()
                    isPressed = false
                }, onTap = { currentOnClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "重输",
            tint = textColor,
            modifier = Modifier.size(if (compactMode) 16.dp else 20.dp)
        )
        if (!compactMode) {
Text(
            text = "重输",
            color = textColor.copy(alpha = 0.5f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.offset(y = (-14).dp),
            fontFamily = AppFonts.keyFontFamily
        )
        }
    }
}

@Composable
private fun StrokeSpaceButton(
    onKeyPress: (String) -> Unit,
    onKeyPressDown: ((String) -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    /** 上滑动作（keyboard.stroke.keys.space.swipe_up，无配置为 null 不响应滑动）。 */
    onSwipeUp: (() -> Unit)? = null,
    /** 上滑键面角标（display: key 时显示，通常为滑动目标字符如 "0"）。 */
    swipeUpBadge: String? = null,
    schemaName: String = "",
    isSttEnabled: Boolean = false,
    voiceSticky: Boolean = false,
    onVoiceModeChange: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val currentOnKeyPress by rememberUpdatedState(onKeyPress)
    val currentOnVoiceModeChange by rememberUpdatedState(onVoiceModeChange)
    val currentOnSwipeUp by rememberUpdatedState(onSwipeUp)
    val drawShadow = keyShadowEnabled(shadowEnabled)
    val shadowModifier = remember(drawShadow, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (drawShadow) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .pointerInput(voiceSticky, isSttEnabled) {
                // 与九键空格一致：常驻语音轻触结束（不发空格），长按进入按住说话
                detectTapGestures(
                    onPress = {
                        if (voiceSticky) {
                            tryAwaitRelease()
                        } else {
                            onKeyPressDown?.invoke("space")
                            tryAwaitRelease()
                        }
                    },
                    onTap = {
                        if (voiceSticky) {
                            currentOnVoiceModeChange?.invoke(false)
                        } else {
                            currentOnKeyPress("space")
                        }
                    },
                    onLongPress = {
                        if (voiceSticky) return@detectTapGestures
                        if (!isSttEnabled) return@detectTapGestures
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        if (!PermissionHelper.hasRecordAudioPermission(context)) {
                            Toast.makeText(context, tr(R.string.s_xuyaomaikefengquanxiancainengs_3b6e4), Toast.LENGTH_SHORT).show()
                            PermissionHelper.requestRecordAudioPermission(context)
                        } else {
                            currentOnVoiceModeChange?.invoke(true)
                        }
                    }
                )
            }
            .pointerInput(voiceSticky, onSwipeUp) {
                // 空格上滑（如直接输入数字 0）：纵向拖动超阈值触发一次。
                // 与 detectTapGestures 共存：拖动时 tap 自动取消，点击/长按语音不受影响；
                // 常驻语音态不响应上滑。仅在已触发后消费事件，不干扰横向光标手势。
                if (onSwipeUp == null || voiceSticky) return@pointerInput
                var totalY = 0f
                var triggered = false
                val thresholdPx = 50.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = { totalY = 0f; triggered = false },
                    onVerticalDrag = { change, dragAmount ->
                        totalY += dragAmount
                        if (!triggered && totalY < -thresholdPx) {
                            triggered = true
                            currentOnSwipeUp?.invoke()
                        }
                        if (triggered) change.consume()
                    },
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(shadowModifier)
                .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
                .background(glassFill(backgroundColor))
        )
        if (voiceSticky) {
            Text(
                text = tr(R.string.s_qingchujieshuyuyin),
                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                fontFamily = AppFonts.keyFontFamily
            )
        } else {
            Text(
                text = schemaName,
                color = textColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
                fontFamily = AppFonts.keyFontFamily
            )
            if (isSttEnabled) {
                Icon(
                    painter = painterResource(R.drawable.voice),
                    contentDescription = tr(R.string.s_yuyinshuru),
                    tint = textColor.copy(alpha = 0.3f),
                    modifier = Modifier
                        .size(18.dp)
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = 2.dp)
                )
            } else {
                Text(
                    text = tr(R.string.s_kongge),
                    color = textColor.copy(alpha = 0.3f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = 2.dp),
                    fontFamily = AppFonts.keyFontFamily
                )
            }
        }
        // 上滑手势键面角标（如 "0"）：与数字键上滑数字提示（swipeUpKeyLabel）同位同样式
        if (swipeUpBadge != null) {
            Text(
                text = swipeUpBadge,
                color = textColor.copy(alpha = 0.6f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                lineHeight = 1.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
            )
        }
    }
}
