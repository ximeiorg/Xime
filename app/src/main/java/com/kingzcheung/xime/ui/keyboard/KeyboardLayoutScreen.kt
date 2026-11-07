package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.handwriting.HandwritingCandidate
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.isTablet
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.resolveSolidColor
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel

@Composable
fun KeyboardLayoutScreen(
    keyboardState: KeyboardLayoutState,
    uiState: KeyboardUiState,
    viewModel: KeyboardViewModel,
    callbacks: KeyboardCallbacks,
    onKeyPress: (String) -> Unit,
    modifier: Modifier = Modifier,
    isHandwritingLookup: Boolean = false,
    onHandwritingCandidates: ((List<HandwritingCandidate>) -> Unit)? = null,
    onHandwritingButtonFeedback: ((String) -> Unit)? = null,
    handwritingClearSignal: Int = 0,
    onHandwritingLookupExit: (() -> Unit)? = null,
    t9Controller: T9InputController? = null,
    candidateState: State<CandidateState> = remember { mutableStateOf(CandidateState()) },
) {
    // 键盘切换日志：必须放 LaunchedEffect。原实现是「组合期读写同一个 remember 状态」
    // （if (keyboardState != lastLoggedKb) { lastLoggedKb = keyboardState; ... }）——
    // 读让本函数订阅了该状态，写又在组合期发生 ⇒ 每次 keyboardState 变化都把自己标脏，
    // 整棵键盘树（面板 40+ 键）要多重组一遍，这就是 ?123 切换的「延迟感」来源之一。
    LaunchedEffect(keyboardState, uiState.isAsciiMode) {
        FileLogger.d("XimeKeyboard", "render keyboard: $keyboardState, ui ascii=${uiState.isAsciiMode}")
    }
    val kbColors = KeysConfigHelper.getKeyboardColors()
    val longToColor: (Long) -> Color = { if (it > 0xFFFFFF) Color(it) else Color(0xFF000000 or it) }
    val themeScheme = KeyboardThemes.getThemeById(uiState.themeId)
    val themeBgColor =
        themeScheme.keyboardBackground?.let { resolveSolidColor(it, uiState.isDarkTheme) }
    val keyboardBgColor = themeBgColor ?: KeyboardThemes.getKeyboardBackgroundColor(
        uiState.themeId,
        uiState.isDarkTheme
    )
    val themeSpecialKeyColor =
        KeyboardThemes.getSpecialKeyColor(uiState.themeId, uiState.isDarkTheme)
    val keyBgColor = KeyboardThemes.getKeyBgColorOverride(uiState.themeId, uiState.isDarkTheme)
        ?: if (uiState.isDarkTheme) longToColor(kbColors.keyBgColorDark) else longToColor(kbColors.keyBgColor)
    val keyTextColor = KeyboardThemes.getKeyTextColorOverride(uiState.themeId, uiState.isDarkTheme)
        ?: if (uiState.isDarkTheme) longToColor(kbColors.keyTextColorDark) else longToColor(kbColors.keyTextColor)
    val specialKeyBgColor =
        if (uiState.isDarkTheme) kbColors.specialKeyBgColorDark?.let { longToColor(it) }
            ?: themeSpecialKeyColor else kbColors.specialKeyBgColor?.let { longToColor(it) }
            ?: themeSpecialKeyColor
    val specialKeyTextColor = KeyboardThemes.getSpecialKeyTextColorForBackground(
        specialKeyBgColor, keyTextColor
    )
    val kbShadow = KeysConfigHelper.getKeyboardShadow()
    val kbKey = KeysConfigHelper.getKeyboardKeyConfig()
    val accentColor = KeyboardThemes.getAccentColor(uiState.themeId, uiState.isDarkTheme)
    val candidateSelectedBgColor =
        KeyboardThemes.getCandidateSelectedBgColor(uiState.themeId, uiState.isDarkTheme)
    // 按键级背景使用的主题色 token（accent/primary/special/key/keyboard）统一在这里解析一次
    val keyStyleColors = remember(accentColor, specialKeyBgColor, keyBgColor, keyboardBgColor) {
        KeyStyleColors(
            accent = accentColor,
            special = specialKeyBgColor,
            key = keyBgColor,
            keyboard = keyboardBgColor,
        )
    }

    // 代码布局（九键 / 笔画 / 手写）的键面圆角与阴影圆角同样走「yaml 按键盘覆盖 → 设备档」，
    // 与 26 键 / 面板共用同一条解析：平板统一 14dp（此前这三处漏了设备档，仍是全局 8dp/硬编码 6dp）。
    val tablet = isTablet()
    val t9CornerRadius = keyCornerRadiusFor(kbKey, "t9", tablet)
    val strokeCornerRadius = keyCornerRadiusFor(kbKey, "stroke", tablet)
    val handwritingCornerRadius = keyCornerRadiusFor(kbKey, "handwriting", tablet)


    val onGestureAction: (GestureAction, String) -> Unit = { action, value ->
        when (action) {
            // 面板切换映射的唯一实现（三处 UI 手势入口共用，含 clipboard）
            GestureAction.SWITCH_ROUTE -> switchRouteOverlay(value)?.let { viewModel.showOverlay(it) }

            // command 的值统一走按键路由（tap/上滑与手势分发同一条解析；命令名白名单见 dispatchCommandAction）
            GestureAction.COMMAND -> dispatchCommandAction(value, onKeyPress)

            GestureAction.TOGGLE_ASCII -> {
                FileLogger.i("XimeKeyboard", "earth key toggle_ascii tapped, ui ascii=${uiState.isAsciiMode}")
                viewModel.resetShift()
                callbacks.onKeyPress("ime_switch", uiState.isAsciiMode)
            }

            GestureAction.TOGGLE_SHIFT -> {
                viewModel.toggleShift()
            }

            else -> callbacks.onGestureAction?.invoke(action, value) ?: Unit
        }
    }

// 系统手势（如三指截图）截走触摸流时，Compose 手势检测器不会被 ACTION_CANCEL 取消，
    // 键内部的长按/按下协程会继续执行并继续上报，导致气泡/按下状态残留。
    // 这里用 key() 让整个活动键盘在 cancel（epoch 变化）时 remount：
    // 所有键的 remember 状态重置、所有进行中手势协程被 Compose 取消，彻底清干净。
    key(keyboardState) {
        key(uiState.swipeCancelEpoch) {
            CompositionLocalProvider(LocalKeyStyleColors provides keyStyleColors) {
            when (keyboardState) {
            is KeyboardLayoutState.Chinese -> {
                if (isHandwritingLookup) {
                    HandwritingLookupKeyboard(
                        keyTextColor = keyTextColor,
                        specialKeyBgColor = specialKeyBgColor,
                        specialKeyTextColor = specialKeyTextColor,
                        keyboardBgColor = keyboardBgColor,
                        shadowEnabled = kbShadow.enabled,
                        shadowElevation = kbShadow.elevation.dp,
                        shadowShapeRadius = maxOf(kbShadow.shapeRadius.dp, handwritingCornerRadius),
                        keyCornerRadius = handwritingCornerRadius,
                        onKeyPress = onKeyPress,
                        onButtonFeedback = onHandwritingButtonFeedback,
                        onCandidates = onHandwritingCandidates,
                        onExit = { onHandwritingLookupExit?.invoke() },
                        clearSignal = handwritingClearSignal,
                        uiState = uiState,
                        modifier = modifier,
                    )
                } else {
                    KeyboardLayout(
                        onKeyPress = onKeyPress,
                        viewModel = viewModel,
                        callbacks = callbacks,
                        uiState = uiState,
                        isAsciiMode = false,
                        modifier = modifier,
                    )
                }
            }

            is KeyboardLayoutState.English -> {
                if (isHandwritingLookup) {
                    HandwritingLookupKeyboard(
                        keyTextColor = keyTextColor,
                        specialKeyBgColor = specialKeyBgColor,
                        specialKeyTextColor = specialKeyTextColor,
                        keyboardBgColor = keyboardBgColor,
                        shadowEnabled = kbShadow.enabled,
                        shadowElevation = kbShadow.elevation.dp,
                        shadowShapeRadius = maxOf(kbShadow.shapeRadius.dp, handwritingCornerRadius),
                        keyCornerRadius = handwritingCornerRadius,
                        onKeyPress = onKeyPress,
                        onButtonFeedback = onHandwritingButtonFeedback,
                        onCandidates = onHandwritingCandidates,
                        onExit = { onHandwritingLookupExit?.invoke() },
                        clearSignal = handwritingClearSignal,
                        uiState = uiState,
                        modifier = modifier,
                    )
                } else {
                    KeyboardLayout(
                        onKeyPress = onKeyPress,
                        viewModel = viewModel,
                        callbacks = callbacks,
                        uiState = uiState,
                        isAsciiMode = true,
                        modifier = modifier,
                    )
                }
            }

            is KeyboardLayoutState.Number -> {
                // 从全键盘（?123 在左下角）进入数字键盘时，返回键放到左下角与进入位置对齐；
                // 九键/笔画/手写进入时保持「符号键在最左下角」的九键习惯
                val lastMainLayout by viewModel.lastMainLayout.collectAsStateWithLifecycle()
                PanelKeyboardLayout(
                    section = "number",
                    onKeyPress = onKeyPress,
                    viewModel = viewModel,
                    callbacks = callbacks,
                    uiState = uiState,
                    isAsciiMode = uiState.isAsciiMode,
                    keyBackgroundColor = keyBgColor,
                    keyTextColor = keyTextColor,
                    specialKeyBackgroundColor = specialKeyBgColor,
                    bubbleBackgroundColor = themeSpecialKeyColor,
                    keyboardBackgroundColor = keyboardBgColor,
                    shadowEnabled = kbShadow.enabled,
                    shadowElevation = kbShadow.elevation.dp,
                    shadowShapeRadius = maxOf(kbShadow.shapeRadius.dp, keyCornerRadiusFor(kbKey, "number", isTablet())),
                    keyCornerRadius = keyCornerRadiusFor(kbKey, "number", isTablet()),
                    keySpacingX = kbKey.spacingFor("number").first?.dp,
                    keySpacingY = kbKey.spacingFor("number").second?.dp,
                    modifier = modifier,
                    onKeyPressDown = callbacks.onKeyPressDown,
                    isFloatingMode = uiState.isFloatingMode,
                    specialKeyTextColor = specialKeyTextColor,
                    backKeyOnLeft = lastMainLayout is KeyboardLayoutState.Chinese ||
                        lastMainLayout is KeyboardLayoutState.English,
                )
            }

            is KeyboardLayoutState.CommonSymbol -> {
                PanelKeyboardLayout(
                    section = "common_symbols",
                    onKeyPress = onKeyPress,
                    viewModel = viewModel,
                    callbacks = callbacks,
                    uiState = uiState,
                    isAsciiMode = uiState.isAsciiMode,
                    keyBackgroundColor = keyBgColor,
                    keyTextColor = keyTextColor,
                    specialKeyBackgroundColor = specialKeyBgColor,
                    bubbleBackgroundColor = themeSpecialKeyColor,
                    keyboardBackgroundColor = keyboardBgColor,
                    shadowEnabled = kbShadow.enabled,
                    shadowElevation = kbShadow.elevation.dp,
                    shadowShapeRadius = maxOf(kbShadow.shapeRadius.dp, keyCornerRadiusFor(kbKey, "symbol", isTablet())),
                    keyCornerRadius = keyCornerRadiusFor(kbKey, "symbol", isTablet()),
                    keySpacingX = kbKey.spacingFor("symbol").first?.dp,
                    keySpacingY = kbKey.spacingFor("symbol").second?.dp,
                    modifier = modifier,
                    onKeyPressDown = callbacks.onKeyPressDown,
                    isFloatingMode = uiState.isFloatingMode,
                    specialKeyTextColor = specialKeyTextColor,
                )
            }

            is KeyboardLayoutState.Stroke -> {
                StrokeKeyboardLayout(
                    onKeyPress = onKeyPress,
                    keyBackgroundColor = keyBgColor,
                    keyTextColor = keyTextColor,
                    specialKeyBackgroundColor = specialKeyBgColor,
                    bubbleBackgroundColor = themeSpecialKeyColor,
                    keyboardBackgroundColor = keyboardBgColor,
                    shadowEnabled = kbShadow.enabled,
                    shadowElevation = kbShadow.elevation.dp,
                    shadowShapeRadius = maxOf(kbShadow.shapeRadius.dp, strokeCornerRadius),
                    keyCornerRadius = strokeCornerRadius,
                    keySpacingX = kbKey.spacingFor("stroke").first?.dp ?: if (tablet) 4.dp else null,
                    keySpacingY = kbKey.spacingFor("stroke").second?.dp ?: if (tablet) 6.dp else null,
                    modifier = modifier,
                    onKeyPressDown = callbacks.onKeyPressDown,
                    isFloatingMode = uiState.isFloatingMode,
                    specialKeyTextColor = specialKeyTextColor,
                    onGestureAction = onGestureAction,
                    schemaName = uiState.schemaName,
                    isSttEnabled = uiState.isSttEnabled,
                    voiceSticky = uiState.voiceSticky,
                    onVoiceModeChange = callbacks.onVoiceModeChange,
                )
            }

            is KeyboardLayoutState.T9Pinyin -> {
                if (isHandwritingLookup) {
                    HandwritingLookupKeyboard(
                        keyTextColor = keyTextColor,
                        specialKeyBgColor = specialKeyBgColor,
                        specialKeyTextColor = specialKeyTextColor,
                        keyboardBgColor = keyboardBgColor,
                        shadowEnabled = kbShadow.enabled,
                        shadowElevation = kbShadow.elevation.dp,
                        shadowShapeRadius = maxOf(kbShadow.shapeRadius.dp, handwritingCornerRadius),
                        keyCornerRadius = handwritingCornerRadius,
                        onKeyPress = onKeyPress,
                        onButtonFeedback = onHandwritingButtonFeedback,
                        onCandidates = onHandwritingCandidates,
                        onExit = { onHandwritingLookupExit?.invoke() },
                        clearSignal = handwritingClearSignal,
                        uiState = uiState,
                        modifier = modifier,
                    )
                } else if (t9Controller != null) {
                    T9KeyboardLayout(
                        onKeyPress = onKeyPress,
                        callbacks = callbacks,
                        uiState = uiState,
                        t9Controller = t9Controller,
                        candidateState = candidateState,
                        keyBackgroundColor = keyBgColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBgColor,
                        bubbleBackgroundColor = themeSpecialKeyColor,
                        accentColor = accentColor,
                        selectedCandidateBgColor = candidateSelectedBgColor,
                        keyboardBackgroundColor = keyboardBgColor,
                        shadowEnabled = kbShadow.enabled,
                        shadowElevation = kbShadow.elevation.dp,
                        shadowShapeRadius = maxOf(kbShadow.shapeRadius.dp, t9CornerRadius),
                        keyCornerRadius = t9CornerRadius,
                        keySpacingX = kbKey.spacingFor("t9").first?.dp ?: if (tablet) 4.dp else null,
                        keySpacingY = kbKey.spacingFor("t9").second?.dp ?: if (tablet) 6.dp else null,
                        modifier = modifier,
                        onKeyPressDown = callbacks.onKeyPressDown,
                        isFloatingMode = uiState.isFloatingMode,
                        specialKeyTextColor = specialKeyTextColor,
                        onGestureAction = onGestureAction,
                    )
                }
            }

            is KeyboardLayoutState.Symbol -> {
                // 符号键盘已改为路由，此处不应到达
            }
        }
        }
        }
    }
}
