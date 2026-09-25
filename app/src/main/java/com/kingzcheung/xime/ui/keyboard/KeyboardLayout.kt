package com.kingzcheung.xime.ui.keyboard

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.twotone.EmojiEmotions
import androidx.compose.material.icons.twotone.KeyboardCapslock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.isTablet
import com.kingzcheung.xime.settings.DisplayMode
import com.kingzcheung.xime.settings.ButtonLayout
import com.kingzcheung.xime.settings.KeyAction
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.keyboard.GestureAction

/** 半角 → 全角标点映射，中文模式下键帽显示用。提交仍走半角由 Rime 处理。 */
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import kotlin.math.abs
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.keyboard.KeyboardDimensions
import com.kingzcheung.xime.util.PermissionHelper
import com.kingzcheung.xime.util.CharInfo
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.util.SubcharHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import com.kingzcheung.xime.viewmodel.ShiftMode
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.keyboardBackground

import androidx.compose.material.icons.twotone.KeyboardControlKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.TextUnit



@Composable
fun KeyboardLayout(
    onKeyPress: (String) -> Unit,
    viewModel: KeyboardViewModel,
    callbacks: KeyboardCallbacks,
    uiState: KeyboardUiState,
    isAsciiMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val isShifted by viewModel.isShifted.collectAsStateWithLifecycle()
    val shiftMode by viewModel.shiftMode.collectAsStateWithLifecycle()

    var visualIsShifted by remember { mutableStateOf(false) }
    LaunchedEffect(isShifted) {
        visualIsShifted = isShifted
    }
    var visualShiftMode by remember { mutableStateOf(ShiftMode.OFF) }
    LaunchedEffect(shiftMode) {
        visualShiftMode = shiftMode
    }

    val context = LocalContext.current
    val kbColors = KeysConfigHelper.getKeyboardColors()
    val longToColor: (Long) -> Color = { if (it > 0xFFFFFF) Color(it) else Color(0xFF000000 or it) }
    val keyboardBackgroundColor = KeyboardThemes.getKeyboardBackgroundColor(uiState.themeId, uiState.isDarkTheme)
    val themeScheme = KeyboardThemes.getThemeById(uiState.themeId)
    val themeSpecialKeyColor = KeyboardThemes.getSpecialKeyColor(uiState.themeId, uiState.isDarkTheme)
    val keyBackgroundColor = KeyboardThemes.getKeyBgColorOverride(uiState.themeId, uiState.isDarkTheme)
        ?: if (uiState.isDarkTheme) longToColor(kbColors.keyBgColorDark) else longToColor(kbColors.keyBgColor)
    val keyTextColor = KeyboardThemes.getKeyTextColorOverride(uiState.themeId, uiState.isDarkTheme)
        ?: if (uiState.isDarkTheme) longToColor(kbColors.keyTextColorDark) else longToColor(kbColors.keyTextColor)
    val specialKeyBackgroundColor = if (uiState.isDarkTheme) kbColors.specialKeyBgColorDark?.let { longToColor(it) }
        ?: themeSpecialKeyColor else kbColors.specialKeyBgColor?.let { longToColor(it) } ?: themeSpecialKeyColor
    val specialKeyTextColor = if (uiState.isDarkTheme) Color.White
        else KeyboardThemes.getSpecialKeyTextColor(uiState.themeId, false)
    val bubbleBgColor = if (uiState.isDarkTheme) themeScheme.specialKeyDark
        else themeScheme.specialKeyLight
    val kbShadow = KeysConfigHelper.getKeyboardShadow()
    val kbKey = KeysConfigHelper.getKeyboardKeyConfig()
    val shadowEnabled = kbShadow.enabled
    val shadowElevation = kbShadow.elevation.dp
    val shadowShapeRadius = kbShadow.shapeRadius.dp
    val schemaName = uiState.schemaName
    val enterKeyText = uiState.enterKeyText
    val isDarkTheme = uiState.isDarkTheme
    val isSttEnabled = uiState.isSttEnabled
    val isVoiceMode = uiState.isVoiceMode
    val isVoiceSticky = uiState.voiceSticky
    val keyRows = KeysConfigHelper.getKeyRows(isAsciiMode)
    val onKeyPressDown = callbacks.onKeyPressDown
    val onKeyRelease = callbacks.onKeyRelease
    val onVoiceModeChange = callbacks.onVoiceModeChange
    val onCommitText = callbacks.onCommitText
    val onGestureAction: (GestureAction, String) -> Unit = { action, value ->
        when (action) {
            GestureAction.SWITCH_ROUTE -> {
                val overlayRoute = when (value) {
                    "emoji" -> OverlayRoute.Emoji
                    "symbol" -> OverlayRoute.Symbol
                    else -> null
                }
                overlayRoute?.let { viewModel.showOverlay(it) }
            }
            GestureAction.TOGGLE_ASCII -> {
                FileLogger.i("XimeKeyboard", "earth key toggle_ascii tapped, ui ascii=${uiState.isAsciiMode}")
                viewModel.resetShift()
                callbacks.onKeyPress("ime_switch", uiState.isAsciiMode)
            }
            GestureAction.DELETE -> {
                callbacks.onKeyPress("delete", false)
            }
            GestureAction.TOGGLE_SYMBOLS -> {
                callbacks.onKeyPress("mode_change", false)
            }
            GestureAction.TOGGLE_SHIFT -> {
                viewModel.toggleShift()
            }
            GestureAction.VOICE -> {
                if (!PermissionHelper.hasRecordAudioPermission(context)) {
                    Toast.makeText(context, "需要麦克风权限才能使用语音输入", Toast.LENGTH_SHORT).show()
                    PermissionHelper.requestRecordAudioPermission(context)
                } else {
                    onVoiceModeChange?.invoke(true)
                }
            }
            else -> callbacks.onGestureAction?.invoke(action, value) ?: Unit
        }
    }
    val suppressCursorMove = LocalSuppressCursorMove.current
    var swipeUpHintsEnabled by remember {
        mutableStateOf(
            SettingsPreferences.isSwipeUpHintsEnabled(
                context
            )
        )
    }
    var swipeDownHintsEnabled by remember {
        mutableStateOf(
            SettingsPreferences.isSwipeDownHintsEnabled(
                context
            )
        )
    }
    var landscapeSplitKeyboardEnabled by remember {
        mutableStateOf(SettingsPreferences.isLandscapeSplitKeyboardEnabled(context))
    }
    val effectiveSwipeDownHintsEnabled = swipeDownHintsEnabled

    // 监听设置变化
    DisposableEffect(context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    SettingsPreferences.KEY_SWIPE_UP_HINTS_ENABLED ->
                        swipeUpHintsEnabled = SettingsPreferences.isSwipeUpHintsEnabled(context)

                    SettingsPreferences.KEY_SWIPE_DOWN_HINTS_ENABLED ->
                        swipeDownHintsEnabled = SettingsPreferences.isSwipeDownHintsEnabled(context)

                    SettingsPreferences.KEY_LANDSCAPE_SPLIT_KEYBOARD_ENABLED ->
                        landscapeSplitKeyboardEnabled =
                            SettingsPreferences.isLandscapeSplitKeyboardEnabled(context)
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(Unit) {
        SubcharHelper.init(context)
    }

    val swipeBubble = rememberSwipeBubbleController()
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }

    // 监听手势配置版本号，部署后强制刷新键帽显示
    val cfgVer by KeysConfigHelper.configVersion.collectAsState()

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

    val bubbleData = rememberSwipeBubbleDrawData(
        swipeState = swipeBubble.state,
        keyBounds = swipeBubble.keyBounds,
        keyBackgroundColor = bubbleBgColor,
        keyTextColor = keyTextColor,
        accentColor = specialKeyTextColor,
        keyWidth = if (swipeBubble.state.isSwiping || swipeBubble.state.isPressed) swipeBubble.keyBounds.width else 0f,
        keyboardWidth = keyboardBounds.width
    )

    val isLandscape = !uiState.isFloatingMode && LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val useLandscapeSplitKeyboard = isLandscape && landscapeSplitKeyboardEnabled
    // 手机横屏键盘矮，行距与分体紧凑档同源收紧；竖屏/平板保持常规行距
    val landscapeCompactSpacing = isLandscape && !isTablet()

    CompositionLocalProvider(
        LocalKeyCornerRadius provides kbKey.cornerRadius.dp,
        LocalSwipeHintCorner provides landscapeCompactSpacing,
        LocalKeyVisualPadding provides PaddingValues(
            horizontal = kbKey.spacingFor("qwerty").first?.dp ?: 2.dp,
            // 手机横屏：只认 qwerty 专属覆盖（keyboard.key.qwerty.spacing_y），回退 2.5dp；
            // 其余场景：全局 qwerty 行距，回退 4.25dp
            vertical = if (landscapeCompactSpacing) (kbKey.spacingOverrides["qwerty"]?.spacingY?.dp ?: 2.5.dp)
            else (kbKey.spacingFor("qwerty").second?.dp ?: 4.25.dp),
        ),
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
            .padding(bottom = if (uiState.isFloatingMode || isLandscape) {0.dp} else {0.dp})
    ) {
            if (useLandscapeSplitKeyboard) {
            LandscapeKeyboardContent(
                onKeyPress = onKeyPress,
                viewModel = viewModel,
                callbacks = callbacks,
                uiState = uiState,
                swipeUpHintsEnabled = swipeUpHintsEnabled,
                swipeDownHintsEnabled = effectiveSwipeDownHintsEnabled,
                isAsciiMode = isAsciiMode,
                keyRows = keyRows,
                configVersion = cfgVer,
                onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) },
            )
        } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            ) {

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.Top,
                ) {
                    val env = QwertyRowEnv(
                        isAsciiMode = isAsciiMode,
                        isShifted = visualIsShifted,
                        shiftMode = visualShiftMode,
                        keyBackgroundColor = keyBackgroundColor,
                        specialKeyBackgroundColor = specialKeyBackgroundColor,
                        keyTextColor = keyTextColor,
                        specialKeyTextColor = specialKeyTextColor,
                        keyboardBackgroundColor = keyboardBackgroundColor,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        onKeyPress = onKeyPress,
                        onKeyPressDown = onKeyPressDown,
                        onKeyRelease = onKeyRelease,
                        onCommitText = onCommitText,
                        onGestureAction = onGestureAction,
                        onVoiceModeChange = onVoiceModeChange,
                        isSttEnabled = isSttEnabled,
                        isVoiceMode = isVoiceMode,
                        isVoiceSticky = isVoiceSticky,
                        schemaName = schemaName,
                        enterKeyText = enterKeyText,
                        suppressCursorMove = suppressCursorMove,
                        processSwipeState = { state, bounds -> processSwipeState(state, bounds) },
                        swipeUpHintsEnabled = swipeUpHintsEnabled,
                        swipeDownHintsEnabled = effectiveSwipeDownHintsEnabled,
                        configVersion = cfgVer,
                    )
                    // 行数由 layout.rows 驱动（最多 5 行，含可选数字行）；语音模式用固定占位行
                    if (isVoiceMode && !isVoiceSticky) {
                        Box(modifier = Modifier.weight(1f)) {
                            DummyKeyboardRow(
                                keysCount = 10,
                                keyBackgroundColor = keyBackgroundColor.copy(alpha = 0.5f),
                                keyboardBackgroundColor = keyboardBackgroundColor
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp)
                        ) {
                            DummyKeyboardRow(
                                keysCount = 9,
                                keyBackgroundColor = keyBackgroundColor.copy(alpha = 0.5f),
                                keyboardBackgroundColor = keyboardBackgroundColor
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            DummyBottomRow(
                                keyBackgroundColor = keyBackgroundColor.copy(alpha = 0.5f),
                                specialKeyBackgroundColor = specialKeyBackgroundColor.copy(alpha = 0.5f),
                                keyboardBackgroundColor = keyboardBackgroundColor
                            )
                        }
                        DummyKeyButton(
                            backgroundColor = specialKeyBackgroundColor.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1.2f)
                        )
                        DummyKeyButton(
                            backgroundColor = specialKeyBackgroundColor.copy(alpha = 0.5f),
                            modifier = Modifier.weight(0.8f)
                        )
                    } else {
                        val rows = remember(keyRows) { normalizeQwertyRows(keyRows) }
                        rows.forEachIndexed { idx, ids ->
                            // 9 键纯字母行（如 asdf 行）两端缩进，视觉居中。
                            // 按首行键数取比例（9/10），任意行宽下第二行键宽都与首行一致——
                            // 固定 16dp 在竖屏刚好、横屏宽行下不够，导致第二行键比首行宽
                            val indent = if (ids.size == 9 && ids.none { it in KeysConfigHelper.FUNCTION_KEY_IDS }) {
                                val firstRowSize = rows.firstOrNull()?.size ?: 10
                                Modifier
                                    .fillMaxWidth((9f / firstRowSize).coerceIn(0f, 1f))
                                    .align(Alignment.CenterHorizontally)
                            } else Modifier
                            QwertyRow(
                                ids = ids,
                                env = env,
                                modifier = Modifier.weight(1f).then(indent),
                            )
                        }
                    }
                }

            }
        }

        // 语音模式中央麦克风图标
        if (isVoiceMode && !isVoiceSticky) {
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "语音输入",
                    tint = keyTextColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(64.dp)
                )
            }
        }
    }

    }
}

/**
 * 调用一个已解析的按键动作。
 *
 * 分发约定：
 * - COMMIT → 直接上屏（[onCommitText] 优先，回退 [onKeyPress]）
 * - SEND_RIME / COMMAND → 走 [onKeyPress] 的字符串路由（UI 面板切换与 rime 通用键均在其内）
 * - 其余动作（编辑/删除/回车/空格/清空/面板切换等）→ [onGestureAction]（UI 拦截层 → 服务层）
 * - NONE / null → 不触发
 */
internal fun invokeKeyAction(
    action: KeyAction?,
    onKeyPress: (String) -> Unit,
    onCommitText: ((String) -> Unit)?,
    onGestureAction: ((GestureAction, String) -> Unit)?,
) {
    val type = action?.action ?: return
    val value = action.value.ifEmpty { action.label }
    when (type) {
        GestureAction.COMMIT -> (onCommitText ?: onKeyPress)(value)
        GestureAction.SEND_RIME, GestureAction.COMMAND -> onKeyPress(value)
        GestureAction.NONE -> Unit
        else -> onGestureAction?.invoke(type, value)
    }
}

/** 一行内的共享渲染参数（颜色/回调等），供行循环与功能键组件复用。 */
private class QwertyRowEnv(
    val isAsciiMode: Boolean,
    val isShifted: Boolean,
    val shiftMode: ShiftMode,
    val keyBackgroundColor: Color,
    val specialKeyBackgroundColor: Color,
    val keyTextColor: Color,
    val specialKeyTextColor: Color,
    val keyboardBackgroundColor: Color = Color.Transparent,
    val shadowEnabled: Boolean,
    val shadowElevation: Dp,
    val shadowShapeRadius: Dp,
    val onKeyPress: (String) -> Unit,
    val onKeyPressDown: ((String) -> Unit)?,
    val onKeyRelease: ((String) -> Unit)?,
    val onCommitText: ((String) -> Unit)?,
    val onGestureAction: ((GestureAction, String) -> Unit)?,
    val onVoiceModeChange: ((Boolean) -> Unit)?,
    val isSttEnabled: Boolean,
    val isVoiceMode: Boolean,
    val isVoiceSticky: Boolean,
    val schemaName: String,
    val enterKeyText: String,
    val suppressCursorMove: MutableState<Boolean>,
    val processSwipeState: (SwipeState, Rect) -> Unit,
    val swipeUpHintsEnabled: Boolean,
    val swipeDownHintsEnabled: Boolean,
    val configVersion: Int,
    /** 横屏紧凑模式（手机）：字母段用 [CompactKeyboardRowWithConfig] 并套用 [fontSize]/[swipeFontSize]；平板为 false，走完整模式组件。 */
    val landscape: Boolean = false,
    val fontSize: TextUnit = TextUnit.Unspecified,
    val swipeFontSize: TextUnit = 9.sp,
)

/** 功能键列宽：显式 `keys.<id>.width` 优先，否则内置默认。 */
private fun functionKeyWidth(id: String, isAsciiMode: Boolean): Float {
    KeysConfigHelper.getKeyGesture(id, isAsciiMode)?.width?.takeIf { it > 0f }?.let { return it }
    return when (id) {
        "shift", "delete" -> 1.4f
        "mode_change", "enter" -> 1.2f
        "earth", "comma" -> 0.8f
        "space" -> 3f
        else -> 1f
    }
}

/** 字母键列宽：显式 `keys.<id>.width` 优先，否则默认 1（等宽）。 */
private fun letterKeyWidth(id: String, isAsciiMode: Boolean): Float =
    KeysConfigHelper.getKeyGesture(id, isAsciiMode)?.width?.takeIf { it > 0f } ?: 1f

/** 键盘最大行数（支持 4 行标准布局，或 5 行带数字行）。 */
internal const val MAX_QWERTY_ROWS = 5

/** 标准 QWERTY 兜底行（3 字母行 + 控制行）。 */
internal val DEFAULT_QWERTY_ROWS: List<List<String>> = listOf(
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
    listOf("z", "x", "c", "v", "b", "n", "m"),
    listOf("mode_change", "comma", "space", "earth", "enter"),
)

/**
 * 规范化键盘行：按配置取行（最多 [MAX_QWERTY_ROWS] 行），缺失的行用内置默认补齐，
 * 因此少于 4 行时会补出默认控制行；多出的行（如可选数字行）原样保留。
 */
internal fun normalizeQwertyRows(rows: List<List<String>>): List<List<String>> {
    val out = ArrayList<List<String>>(MAX_QWERTY_ROWS)
    for (i in 0 until MAX_QWERTY_ROWS) {
        val row = rows.getOrNull(i) ?: DEFAULT_QWERTY_ROWS.getOrNull(i) ?: break
        if (row.isEmpty()) break
        out.add(row)
    }
    return out.ifEmpty { DEFAULT_QWERTY_ROWS }
}

/**
 * 横屏行：在 [normalizeQwertyRows] 基础上，若整份布局不含任何功能键（如合并键布局只有字母行），
 * 在最后一个字母行两端补 `shift` / `delete`。
 */
internal fun landscapeQwertyRows(rows: List<List<String>>): List<List<String>> {
    val normalized = normalizeQwertyRows(rows)
    if (normalized.size < 2) return normalized
    // 忽略末尾控制行：只要其余行都不含功能键（纯字母布局），就在最后一个字母行两端补 shift/delete
    val contentRows = normalized.dropLast(1)
    val hasFunction = contentRows.any { r -> r.any { it in KeysConfigHelper.FUNCTION_KEY_IDS } }
    if (hasFunction) return normalized
    val letterIdx = normalized.lastIndex - 1
    return normalized.mapIndexed { idx, r ->
        if (idx == letterIdx) listOf("shift") + r + listOf("delete") else r
    }
}

/**
 * 通用行渲染：按 id 逐个渲染，功能键走 [FunctionKeyCell]，连续字母段用
 * [KeyboardRowWithConfig] 渲染。布局行内容由 `layout.rows` 决定，键行为由 `keys.<id>` 决定。
 */
@Composable
private fun QwertyRow(
    ids: List<String>,
    env: QwertyRowEnv,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(),
    ) {
        var i = 0
        while (i < ids.size) {
            val id = ids[i]
            if (id in KeysConfigHelper.FUNCTION_KEY_IDS) {
                FunctionKeyCell(
                    id = id,
                    env = env,
                    modifier = Modifier
                        .weight(functionKeyWidth(id, env.isAsciiMode))
                        .fillMaxHeight(),
                )
                i++
            } else {
                var j = i
                while (j < ids.size && ids[j] !in KeysConfigHelper.FUNCTION_KEY_IDS) j++
                val segment = ids.subList(i, j)
                val segWeights = segment.map { letterKeyWidth(it, env.isAsciiMode) }
                val segModifier = Modifier
                    .weight(segWeights.sum())
                    .fillMaxHeight()
                val rowConfig = KeyboardRowConfig(
                    keyBackgroundColor = env.keyBackgroundColor,
                    keyTextColor = env.keyTextColor,
                    keyboardBackgroundColor = env.keyboardBackgroundColor,
                    fontSize = env.fontSize,
                    swipeFontSize = env.swipeFontSize,
                    shadowEnabled = env.shadowEnabled,
                    shadowElevation = env.shadowElevation,
                    shadowShapeRadius = env.shadowShapeRadius,
                )
                if (env.landscape) {
                    CompactKeyboardRowWithConfig(
                        keys = segment,
                        onKeyPress = env.onKeyPress,
                        config = rowConfig,
                        isShifted = env.isShifted,
                        isAsciiMode = env.isAsciiMode,
                        modifier = segModifier,
                        keyWidths = segWeights,
                        onSwipeStateChange = { state, bounds -> env.processSwipeState(state, bounds) },
                        onKeyPressDown = env.onKeyPressDown,
                        onKeyRelease = env.onKeyRelease,
                        swipeDownHintsEnabled = env.swipeDownHintsEnabled,
                        swipeUpHintsEnabled = env.swipeUpHintsEnabled,
                        onCommitText = env.onCommitText,
                        onGestureAction = env.onGestureAction,
                        configVersion = env.configVersion,
                    )
                } else {
                    KeyboardRowWithConfig(
                        keys = segment,
                        onKeyPress = env.onKeyPress,
                        config = rowConfig,
                        isShifted = env.isShifted,
                        isAsciiMode = env.isAsciiMode,
                        modifier = segModifier,
                        keyWidths = segWeights,
                        onSwipeStateChange = { state, bounds -> env.processSwipeState(state, bounds) },
                        onKeyPressDown = env.onKeyPressDown,
                        onKeyRelease = env.onKeyRelease,
                        swipeDownHintsEnabled = env.swipeDownHintsEnabled,
                        swipeUpHintsEnabled = env.swipeUpHintsEnabled,
                        onCommitText = env.onCommitText,
                        onGestureAction = env.onGestureAction,
                        configVersion = env.configVersion,
                    )
                }
                i = j
            }
        }
    }
}

/** 按 id 分派功能键组件。 */
@Composable
private fun FunctionKeyCell(id: String, env: QwertyRowEnv, modifier: Modifier) {
    when (id) {
        "shift" -> ShiftCell(env, modifier)
        "delete" -> DeleteCell(env, modifier)
        "mode_change" -> ModeChangeCell(env, modifier)
        "comma" -> CommaCell(env, modifier)
        "space" -> SpaceCell(env, modifier)
        "earth" -> EarthCell(env, modifier)
        "enter" -> EnterCell(env, modifier)
    }
}

@Composable
private fun ShiftCell(env: QwertyRowEnv, modifier: Modifier) {
    val shiftBinding = KeysConfigHelper.getKeyGesture("shift", env.isAsciiMode)
    val shiftTap = shiftBinding?.tap
    val shiftDoubleTap = shiftBinding?.doubleTap
    val onTap: (() -> Unit)? = if (shiftTap != null && shiftTap.action != null && shiftTap.action != GestureAction.TOGGLE_SHIFT) {
        { invokeKeyAction(shiftTap, env.onKeyPress, env.onCommitText, env.onGestureAction) }
    } else null
    val onDoubleTap: (() -> Unit)? = if (shiftDoubleTap != null && shiftDoubleTap.action != null && shiftDoubleTap.action != GestureAction.TOGGLE_SHIFT) {
        { invokeKeyAction(shiftDoubleTap, env.onKeyPress, env.onCommitText, env.onGestureAction) }
    } else null
    val shiftUp = shiftBinding?.swipeUp
    val shiftDown = shiftBinding?.swipeDown
    val shiftLeft = shiftBinding?.swipeLeft
    val shiftRight = shiftBinding?.swipeRight
    val onSwipe: ((String) -> Unit)? =
        if (shiftUp != null || shiftDown != null || shiftLeft != null || shiftRight != null) {
            { dir ->
                val a = when (dir) {
                    "up" -> shiftUp
                    "down" -> shiftDown
                    "left" -> shiftLeft
                    "right" -> shiftRight
                    else -> null
                }
                if (a != null) {
                    if (dir == "left" || dir == "right") env.suppressCursorMove.value = true
                    invokeKeyAction(a, env.onKeyPress, env.onCommitText, env.onGestureAction)
                }
            }
        } else null
    ShiftCapsKeyButton(
        shiftMode = env.shiftMode,
        onKeyPress = env.onKeyPress,
        onKeyPressDown = env.onKeyPressDown,
        backgroundColor = env.specialKeyBackgroundColor,
        iconColor = env.specialKeyTextColor,
        modifier = modifier,
        shadowEnabled = env.shadowEnabled,
        shadowElevation = env.shadowElevation,
        shadowShapeRadius = env.shadowShapeRadius,
        onTap = onTap,
        onDoubleTap = onDoubleTap,
        onSwipe = onSwipe,
    )
}

@Composable
private fun SpaceCell(env: QwertyRowEnv, modifier: Modifier) {
    val spaceBinding = KeysConfigHelper.getKeyGesture("space", env.isAsciiMode)
    val spaceTap = spaceBinding?.tap
    val spaceLong = spaceBinding?.longPress?.values?.firstOrNull()
    // 仅在显式配置为非默认空格时接管 tap；长按一旦配置则替换内置（语音/重复空格）
    val onTap: (() -> Unit)? = if (spaceTap != null && spaceTap.action != GestureAction.SPACE) {
        { invokeKeyAction(spaceTap, env.onKeyPress, env.onCommitText, env.onGestureAction) }
    } else null
    val onLongPress: (() -> Unit)? = spaceLong?.let {
        { invokeKeyAction(it, env.onKeyPress, env.onCommitText, env.onGestureAction) }
    }
    val spaceUp = spaceBinding?.swipeUp
    val spaceDown = spaceBinding?.swipeDown
    val spaceLeft = spaceBinding?.swipeLeft
    val spaceRight = spaceBinding?.swipeRight
    val onSwipe: ((String) -> Unit)? =
        if (spaceUp != null || spaceDown != null || spaceLeft != null || spaceRight != null) {
            { dir ->
                val a = when (dir) {
                    "up" -> spaceUp
                    "down" -> spaceDown
                    "left" -> spaceLeft
                    "right" -> spaceRight
                    else -> null
                }
                if (a != null) {
                    if (dir == "left" || dir == "right") env.suppressCursorMove.value = true
                    invokeKeyAction(a, env.onKeyPress, env.onCommitText, env.onGestureAction)
                }
            }
        } else null
    SpaceKey(
        schemaName = env.schemaName,
        isAsciiMode = env.isAsciiMode,
        isSttEnabled = env.isSttEnabled,
        isVoiceMode = env.isVoiceMode,
        voiceSticky = env.isVoiceSticky,
        keyBackgroundColor = env.keyBackgroundColor,
        keyTextColor = env.keyTextColor,
        shadowEnabled = env.shadowEnabled,
        shadowElevation = env.shadowElevation,
        shadowShapeRadius = env.shadowShapeRadius,
        modifier = modifier,
        onKeyPress = env.onKeyPress,
        onKeyPressDown = env.onKeyPressDown,
        onKeyRelease = env.onKeyRelease,
        onVoiceModeChange = env.onVoiceModeChange,
        onTap = onTap,
        onLongPress = onLongPress,
        onSwipe = onSwipe,
    )
}

@Composable
private fun EnterCell(env: QwertyRowEnv, modifier: Modifier) {
    val enterBinding = KeysConfigHelper.getKeyGesture("enter", env.isAsciiMode)
    val enterAction = enterBinding?.tap ?: KeyAction(GestureAction.ENTER)
    val enterKeyValue = enterAction.value.ifEmpty { "enter" }
    KeyButton(
        text = enterAction.label.ifEmpty { env.enterKeyText },
        onClick = { invokeKeyAction(enterAction, env.onKeyPress, env.onCommitText, env.onGestureAction) },
        backgroundColor = env.specialKeyBackgroundColor,
        textColor = env.specialKeyTextColor,
        modifier = modifier,
        onPress = { env.onKeyPressDown?.invoke(enterKeyValue) },
        onRelease = { env.onKeyRelease?.invoke(enterKeyValue) },
        shadowEnabled = env.shadowEnabled,
        shadowElevation = env.shadowElevation,
        shadowShapeRadius = env.shadowShapeRadius,
    )
}

@Composable
private fun DeleteCell(env: QwertyRowEnv, modifier: Modifier) {
    val delBinding = KeysConfigHelper.getKeyGesture("delete", env.isAsciiMode)
    val delTap = delBinding?.tap ?: KeyAction(GestureAction.DELETE)
    val delLong = delBinding?.longPress?.values?.firstOrNull()
        ?: KeyAction(GestureAction.DELETE, repeat = true)
    val delUp = delBinding?.swipeUp ?: KeyAction(GestureAction.CLEAR_ALL, label = "上滑清空")
    val delDown = delBinding?.swipeDown ?: KeyAction(GestureAction.UNDO_CLEAR, label = "下滑撤回")
    val delLeft = delBinding?.swipeLeft ?: KeyAction(GestureAction.COMMAND, "clear_composition")
    val delRight = delBinding?.swipeRight ?: KeyAction(GestureAction.COMMAND, "clear_composition")
    SwipeableIconKeyButton(
        icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
        onClick = { invokeKeyAction(delTap, env.onKeyPress, env.onCommitText, env.onGestureAction) },
        backgroundColor = env.specialKeyBackgroundColor,
        iconColor = env.specialKeyTextColor,
        modifier = modifier,
        swipeText = delRight.label.ifEmpty { "清空" },
        onSwipe = { invokeKeyAction(delRight, env.onKeyPress, env.onCommitText, env.onGestureAction) },
        onLongClick = { invokeKeyAction(delLong, env.onKeyPress, env.onCommitText, env.onGestureAction) },
        onPress = { env.onKeyPressDown?.invoke("delete") },
        onRelease = { env.onKeyRelease?.invoke("delete") },
        swipeUpLabel = delUp.label.ifEmpty { null },
        swipeDownLabel = delDown.label.ifEmpty { null },
        onSwipeUp = { invokeKeyAction(delUp, env.onKeyPress, env.onCommitText, env.onGestureAction) },
        onSwipeDown = { invokeKeyAction(delDown, env.onKeyPress, env.onCommitText, env.onGestureAction) },
        onSwipeLeft = { invokeKeyAction(delLeft, env.onKeyPress, env.onCommitText, env.onGestureAction) },
        onSwipeRight = { invokeKeyAction(delRight, env.onKeyPress, env.onCommitText, env.onGestureAction) },
        onSwipeStateChange = { state, bounds -> env.processSwipeState(state, bounds) },
        shadowEnabled = env.shadowEnabled,
        shadowElevation = env.shadowElevation,
        shadowShapeRadius = env.shadowShapeRadius,
    )
}

@Composable
private fun ModeChangeCell(env: QwertyRowEnv, modifier: Modifier) {
    val mcBinding = KeysConfigHelper.getKeyGesture("mode_change", env.isAsciiMode)
    val mcTap = mcBinding?.tap
    val mcLongPressValues = mcBinding?.longPress?.values
    val mcLongPressItems = mcLongPressValues?.map { it.label }?.filter { it.isNotEmpty() }?.ifEmpty { null }
        ?: listOf("number", "common_symbol")
    SwipeableKeyButton(
        text = mcTap?.label?.takeIf { it.isNotEmpty() } ?: "?123",
        onClick = {
            if (mcTap != null) invokeKeyAction(mcTap, env.onKeyPress, env.onCommitText, env.onGestureAction)
            else env.onKeyPress("mode_change")
        },
        backgroundColor = env.specialKeyBackgroundColor,
        textColor = env.specialKeyTextColor,
        modifier = modifier,
        onPress = { env.onKeyPressDown?.invoke(mcTap?.value?.takeIf { it.isNotEmpty() } ?: "mode_change") },
        onRelease = { env.onKeyRelease?.invoke(mcTap?.value?.takeIf { it.isNotEmpty() } ?: "mode_change") },
        onLongPressSelect = { label ->
            val configured = mcLongPressValues?.firstOrNull { it.label == label }
            if (configured != null) {
                invokeKeyAction(configured, env.onKeyPress, env.onCommitText, env.onGestureAction)
            } else {
                env.onKeyPress(if (label == "number") "mode_change_number" else "mode_change_common_symbol")
            }
        },
        longPressItems = mcLongPressItems,
        longPressDrawableIds = listOf(
            com.kingzcheung.xime.R.drawable.t9,
            com.kingzcheung.xime.R.drawable.t26
        ),
        onSwipeStateChange = { state, bounds -> env.processSwipeState(state, bounds) },
        shadowEnabled = env.shadowEnabled,
        shadowElevation = env.shadowElevation,
        shadowShapeRadius = env.shadowShapeRadius,
    )
}

@Composable
private fun CommaCell(env: QwertyRowEnv, modifier: Modifier) {
    val k2KeyGesture = KeysConfigHelper.getKeyGesture("comma", env.isAsciiMode)
    val k2TapAction = k2KeyGesture?.tap?.action
    val k2TapValue = k2KeyGesture?.tap?.value?.takeIf { it.isNotEmpty() }
        ?: k2KeyGesture?.tap?.label?.takeIf { it.isNotEmpty() }
        ?: if (env.isAsciiMode) "," else "，"
    val k2TapLabel = k2KeyGesture?.tap?.label?.takeIf { it.isNotEmpty() } ?: k2TapValue
    val k2SwipeUpRaw = k2KeyGesture?.swipeUp
    val k2SwipeUpLabel = if (env.isAsciiMode)
        (k2SwipeUpRaw?.value?.takeIf { it.isNotEmpty() } ?: "")
        else (k2SwipeUpRaw?.label?.takeIf { it.isNotEmpty() } ?: k2SwipeUpRaw?.value?.takeIf { it.isNotEmpty() } ?: "")
    val k2SwipeUpValue = k2SwipeUpRaw?.value?.takeIf { it.isNotEmpty() }
        ?: k2SwipeUpRaw?.label?.takeIf { it.isNotEmpty() }
    val k2SwipeUpDisplay = k2SwipeUpRaw?.display ?: DisplayMode.BOTH
    val k2SwipeUpBubble = k2SwipeUpRaw?.bubble ?: true
    val k2SwipeUpKeyLabel = if (k2SwipeUpDisplay == DisplayMode.BUBBLE) "" else k2SwipeUpLabel
    val k2SwipeUpText = if (k2SwipeUpBubble) k2SwipeUpLabel else null
    val k2SwipeUpCommitValue = k2SwipeUpValue
    val k2SwipeDownRaw = k2KeyGesture?.swipeDown
    val k2SwipeDownLabel = k2SwipeDownRaw?.label?.takeIf { it.isNotEmpty() }
    val k2SwipeDownAction = k2SwipeDownRaw?.action
    val k2SwipeDownValue = k2SwipeDownRaw?.value
    val k2SwipeDownDisplay = k2SwipeDownRaw?.display ?: DisplayMode.BOTH
    val k2SwipeDownBubble = k2SwipeDownRaw?.bubble ?: true
    val k2SwipeDownKeyLabel = if (k2SwipeDownDisplay == DisplayMode.BUBBLE) "" else k2SwipeDownLabel
    val k2SwipeDownText = if (k2SwipeDownBubble) k2SwipeDownLabel else null
    val k2LongPressConfig = k2KeyGesture?.longPress
    val k2LongPressDisplay = k2LongPressConfig?.display ?: DisplayMode.KEY
    val k2LongPressLabels = if (k2LongPressDisplay == DisplayMode.BUBBLE) {
        k2LongPressConfig?.values?.map { it.label }?.filter { it.isNotEmpty() }?.ifEmpty { null }
    } else null
    val k2LongPressGestureMap = if (k2LongPressDisplay == DisplayMode.BUBBLE) {
        k2LongPressConfig?.values?.associateBy { it.label }
    } else null
    val k2OnClick: () -> Unit = remember(k2TapAction, k2TapValue, env.onKeyPress, env.onGestureAction) {
        {
            if (k2TapAction != null && k2TapAction != GestureAction.COMMIT && k2TapAction != GestureAction.SEND_RIME) {
                env.onGestureAction?.invoke(k2TapAction, k2TapValue)
            } else {
                env.onKeyPress(k2TapValue)
            }
        }
    }
    val k2OnSwipeDown: ((String) -> Unit)? = if (k2SwipeDownAction != null && k2SwipeDownLabel != null) {
        remember(k2SwipeDownAction, k2SwipeDownValue, k2SwipeDownLabel, env.onKeyPress, env.onGestureAction, env.onCommitText) {
            val label = k2SwipeDownLabel
            { _: String ->
                if (k2SwipeDownAction == GestureAction.COMMIT) {
                    (env.onCommitText ?: env.onKeyPress)(k2SwipeDownValue?.ifEmpty { label } ?: label)
                } else {
                    env.onGestureAction?.invoke(k2SwipeDownAction, k2SwipeDownValue?.ifEmpty { label } ?: label)
                }
                Unit
            }
        }
    } else null
    val k2OnLongPressSelect: ((String) -> Unit)? = remember(k2LongPressGestureMap, env.onGestureAction, env.onCommitText, env.onKeyPress) {
        { selectedLabel: String ->
            val gesture = k2LongPressGestureMap?.get(selectedLabel)
            if (gesture != null && gesture.action != GestureAction.COMMIT) {
                env.onGestureAction?.invoke(gesture.action!!, gesture.value.ifEmpty { selectedLabel })
            } else {
                (env.onCommitText ?: env.onKeyPress)(selectedLabel)
            }
            Unit
        }
    }
    if (k2TapAction == GestureAction.TOGGLE_ASCII) {
        IconKeyButton(
            icon = rememberVectorPainter(Icons.Default.Language),
            onClick = k2OnClick,
            backgroundColor = env.keyBackgroundColor,
            iconColor = env.keyTextColor,
            modifier = modifier,
            onPress = { env.onKeyPressDown?.invoke(k2TapValue) },
            onRelease = { env.onKeyRelease?.invoke(k2TapValue) },
            shadowEnabled = env.shadowEnabled,
            shadowElevation = env.shadowElevation,
            shadowShapeRadius = env.shadowShapeRadius,
        )
    } else {
        SwipeableKeyButton(
            layoutMode = KeysConfigHelper.getButtonLayout(env.isAsciiMode),
            text = k2TapLabel,
            onClick = k2OnClick,
            backgroundColor = env.keyBackgroundColor,
            textColor = env.keyTextColor,
            modifier = modifier,
            swipeText = k2SwipeUpText,
            swipeDownText = k2SwipeDownText,
            swipeUpKeyLabel = k2SwipeUpKeyLabel,
            swipeDownKeyLabel = k2SwipeDownKeyLabel,
            onSwipe = if (k2SwipeUpCommitValue != null) { { env.onKeyPress(k2SwipeUpCommitValue) } } else null,
            onSwipeDown = k2OnSwipeDown,
            onSwipeStateChange = { state, bounds -> env.processSwipeState(state, bounds) },
            onPress = { env.onKeyPressDown?.invoke(k2TapValue) },
            onRelease = { env.onKeyRelease?.invoke(k2TapValue) },
            onLongPressSelect = k2OnLongPressSelect,
            longPressItems = k2LongPressLabels,
            shadowEnabled = env.shadowEnabled,
            shadowElevation = env.shadowElevation,
            shadowShapeRadius = env.shadowShapeRadius,
        )
    }
}

@Composable
private fun EarthCell(env: QwertyRowEnv, modifier: Modifier) {
    val k4KeyGesture = KeysConfigHelper.getKeyGesture("earth", env.isAsciiMode)
    val k4TapAction = k4KeyGesture?.tap?.action
    val isEarthDefaultTap = k4KeyGesture?.tap == null
    val k4TapValue = k4KeyGesture?.tap?.value?.takeIf { it.isNotEmpty() } ?: "ime_switch"
    val k4TapLabel = k4KeyGesture?.tap?.label?.takeIf { it.isNotEmpty() } ?: "中"
    val k4Icon: Painter? = k4KeyGesture?.tap?.icon?.takeIf { it.isNotEmpty() }?.let { iconName ->
        val iv = when (iconName) {
            "language", "globe" -> Icons.Default.Language
            else -> null
        }
        iv?.let { rememberVectorPainter(it) }
    } ?: if (isEarthDefaultTap) rememberVectorPainter(Icons.Default.Language) else null
    val k4SwipeUpRaw = k4KeyGesture?.swipeUp
    val k4SwipeUpLabel = if (env.isAsciiMode)
        (k4SwipeUpRaw?.value?.takeIf { it.isNotEmpty() } ?: "")
        else (k4SwipeUpRaw?.label?.takeIf { it.isNotEmpty() } ?: k4SwipeUpRaw?.value?.takeIf { it.isNotEmpty() } ?: "")
    val k4SwipeUpValue = k4SwipeUpRaw?.value?.takeIf { it.isNotEmpty() }
        ?: k4SwipeUpRaw?.label?.takeIf { it.isNotEmpty() }
    val k4SwipeUpAction = k4SwipeUpRaw?.action
    val k4SwipeUpDisplay = k4SwipeUpRaw?.display ?: DisplayMode.BOTH
    val k4SwipeUpBubble = k4SwipeUpRaw?.bubble ?: true
    val k4SwipeUpKeyLabel = if (k4SwipeUpDisplay == DisplayMode.BUBBLE) "" else k4SwipeUpLabel.takeIf { it.isNotEmpty() }
    val k4SwipeUpText = if (k4SwipeUpBubble) k4SwipeUpLabel.takeIf { it.isNotEmpty() } else null
    val k4SwipeDownRaw = k4KeyGesture?.swipeDown
    val k4SwipeDownLabel = k4SwipeDownRaw?.label?.takeIf { it.isNotEmpty() }
    val k4SwipeDownAction = k4SwipeDownRaw?.action
    val k4SwipeDownValue = k4SwipeDownRaw?.value
    val k4SwipeDownDisplay = k4SwipeDownRaw?.display ?: DisplayMode.BOTH
    val k4SwipeDownBubble = k4SwipeDownRaw?.bubble ?: true
    val k4SwipeDownKeyLabel = if (k4SwipeDownDisplay == DisplayMode.BUBBLE) "" else k4SwipeDownLabel
    val k4SwipeDownText = if (k4SwipeDownBubble) k4SwipeDownLabel else null
    val k4LongPressConfig = k4KeyGesture?.longPress
    val k4LongPressDisplay = k4LongPressConfig?.display ?: DisplayMode.KEY
    val k4LongPressLabels = if (k4LongPressDisplay == DisplayMode.BUBBLE) {
        k4LongPressConfig?.values?.map { it.label }?.filter { it.isNotEmpty() }?.ifEmpty { null }
    } else null
    val k4LongPressGestureMap = if (k4LongPressDisplay == DisplayMode.BUBBLE) {
        k4LongPressConfig?.values?.associateBy { it.label }
    } else null
    val k4OnClick: () -> Unit = remember(k4TapAction, k4TapValue, env.onKeyPress, env.onGestureAction) {
        {
            if (k4TapAction != null && k4TapAction != GestureAction.COMMIT && k4TapAction != GestureAction.SEND_RIME) {
                env.onGestureAction?.invoke(k4TapAction, k4TapValue)
            } else {
                env.onKeyPress(k4TapValue)
            }
        }
    }
    val k4OnSwipe: ((String) -> Unit)? = if (k4SwipeUpValue != null && k4SwipeUpAction != GestureAction.NONE) {
        remember(k4SwipeUpValue, env.onKeyPress) { { env.onKeyPress(k4SwipeUpValue) } }
    } else null
    val k4OnSwipeDown: ((String) -> Unit)? = if (k4SwipeDownAction != null && k4SwipeDownLabel != null) {
        remember(k4SwipeDownAction, k4SwipeDownValue, k4SwipeDownLabel, env.onKeyPress, env.onGestureAction, env.onCommitText) {
            val label = k4SwipeDownLabel
            { _: String ->
                if (k4SwipeDownAction == GestureAction.COMMIT) {
                    (env.onCommitText ?: env.onKeyPress)(k4SwipeDownValue?.ifEmpty { label } ?: label)
                } else {
                    env.onGestureAction?.invoke(k4SwipeDownAction, k4SwipeDownValue?.ifEmpty { label } ?: label)
                }
                Unit
            }
        }
    } else null
    val k4OnLongPressSelect: ((String) -> Unit)? = remember(k4LongPressGestureMap, env.onGestureAction, env.onCommitText, env.onKeyPress) {
        { selectedLabel: String ->
            val gesture = k4LongPressGestureMap?.get(selectedLabel)
            if (gesture != null && gesture.action != GestureAction.COMMIT) {
                env.onGestureAction?.invoke(gesture.action!!, gesture.value.ifEmpty { selectedLabel })
            } else {
                (env.onCommitText ?: env.onKeyPress)(selectedLabel)
            }
            Unit
        }
    }
    if ((k4TapAction == GestureAction.TOGGLE_ASCII && k4Icon != null || isEarthDefaultTap) && k4LongPressLabels == null) {
        IconKeyButton(
            icon = k4Icon ?: rememberVectorPainter(Icons.Default.Language),
            onClick = k4OnClick,
            backgroundColor = env.keyBackgroundColor,
            iconColor = env.keyTextColor,
            modifier = modifier,
            onPress = { env.onKeyPressDown?.invoke(k4TapValue) },
            onRelease = { env.onKeyRelease?.invoke(k4TapValue) },
            shadowEnabled = env.shadowEnabled,
            shadowElevation = env.shadowElevation,
            shadowShapeRadius = env.shadowShapeRadius,
        )
    } else {
        SwipeableKeyButton(
            layoutMode = KeysConfigHelper.getButtonLayout(env.isAsciiMode),
            text = k4TapLabel,
            onClick = k4OnClick,
            backgroundColor = env.keyBackgroundColor,
            textColor = env.keyTextColor,
            modifier = modifier,
            icon = k4Icon,
            swipeText = k4SwipeUpText,
            swipeDownText = k4SwipeDownText,
            swipeUpKeyLabel = k4SwipeUpKeyLabel,
            swipeDownKeyLabel = k4SwipeDownKeyLabel,
            onSwipe = k4OnSwipe,
            onSwipeDown = k4OnSwipeDown,
            onSwipeStateChange = { state, bounds -> env.processSwipeState(state, bounds) },
            onPress = { env.onKeyPressDown?.invoke(k4TapValue) },
            onRelease = { env.onKeyRelease?.invoke(k4TapValue) },
            onLongPressSelect = k4OnLongPressSelect,
            longPressItems = k4LongPressLabels,
            shadowEnabled = env.shadowEnabled,
            shadowElevation = env.shadowElevation,
            shadowShapeRadius = env.shadowShapeRadius,
        )
    }
}

/**
 * 在按下后跟踪指针，返回首次越阈值的滑动方向（"up"/"down"/"left"/"right"）；未滑动返回 null。
 * 检测到滑动即消费事件，避免父容器滚动。
 */
private suspend fun AwaitPointerEventScope.awaitSwipeDirection(
    down: PointerInputChange,
    longPressTriggered: () -> Boolean,
): String? {
    val threshold = 40.dp.toPx()
    var dragX = 0f
    var dragY = 0f
    var direction: String? = null
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: return direction
        if (direction != null) change.consume()
        if (!change.pressed) return direction
        if (direction == null && !longPressTriggered()) {
            val delta = change.position - change.previousPosition
            dragX += delta.x
            dragY += delta.y
            val horizontal = abs(dragX) > abs(dragY)
            if (horizontal && abs(dragX) > threshold) {
                direction = if (dragX < 0) "left" else "right"
            } else if (!horizontal && abs(dragY) > threshold) {
                direction = if (dragY < 0) "up" else "down"
            }
            if (direction != null) change.consume()
        }
    }
}

@Composable
private fun DummyKeyboardRow(
    keysCount: Int,
    keyBackgroundColor: Color,
    keyboardBackgroundColor: Color = Color.Transparent,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        repeat(keysCount) {
            DummyKeyButton(
                backgroundColor = keyBackgroundColor,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DummyBottomRow(
    keyBackgroundColor: Color,
    specialKeyBackgroundColor: Color,
    keyboardBackgroundColor: Color = Color.Transparent
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        DummyKeyButton(
            backgroundColor = specialKeyBackgroundColor,
            modifier = Modifier.weight(1.2f)
        )
        Row(
            modifier = Modifier
                .weight(7f)
                .fillMaxHeight(),
        ) {
            repeat(7) {
                DummyKeyButton(
                    backgroundColor = keyBackgroundColor,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        DummyKeyButton(
            backgroundColor = specialKeyBackgroundColor,
            modifier = Modifier.weight(1.2f)
        )
    }
}

@Composable
private fun DummyKeyButton(
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
            .background(backgroundColor)
    )
}

@Composable
fun KeyboardRowWithConfig(
    keys: List<String>,
    onKeyPress: (String) -> Unit,
    config: KeyboardRowConfig,
    isShifted: Boolean,
    isAsciiMode: Boolean = false,
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    onKeyPressDown: ((String) -> Unit)? = null,
    onKeyRelease: ((String) -> Unit)? = null,
    swipeDownHintsEnabled: Boolean = true,
    swipeUpHintsEnabled: Boolean = true,
    onCommitText: ((String) -> Unit)? = null,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
    configVersion: Int = 0,
    /** 每个键的列宽（与 keys 一一对应）；缺省或长度不足时按 1 等宽。 */
    keyWidths: List<Float>? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        keys.forEachIndexed { index, key ->
            val rawSwipeUpLabel = KeysConfigHelper.getSwipeUpLabel(key, isAsciiMode)
            val swipeUpAction = KeysConfigHelper.getSwipeUpAction(key, isAsciiMode)
            val swipeUpDisplay = KeysConfigHelper.getSwipeUpDisplay(key, isAsciiMode)
            val swipeUpBubble = KeysConfigHelper.getSwipeUpBubble(key, isAsciiMode)
            // 键面提示位置由 display 决定（BUBBLE 用空串压制回退）；运行时气泡由 bubble 决定
            val swipeUpKeyLabel = if (swipeUpHintsEnabled) {
                if (swipeUpDisplay == DisplayMode.BUBBLE) "" else rawSwipeUpLabel
            } else null
            val swipeUpText = if (swipeUpHintsEnabled && swipeUpBubble) rawSwipeUpLabel else null
            val swipeUpCommitValue = KeysConfigHelper.getSwipeUpCommitValue(key, isAsciiMode)
            val swipeDownRaw = KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.swipeDown
            val swipeDownLabel = swipeDownRaw?.label?.takeIf { it.isNotEmpty() }
            val swipeDownAction = swipeDownRaw?.action
            val swipeDownValue = swipeDownRaw?.value
            val swipeDownDisplay = swipeDownRaw?.display ?: DisplayMode.BOTH
            val swipeDownBubble = swipeDownRaw?.bubble ?: true
            val swipeDownKeyLabel = if (swipeDownHintsEnabled) {
                if (swipeDownDisplay == DisplayMode.BUBBLE) "" else swipeDownLabel
            } else null
            val swipeDownText = if (swipeDownHintsEnabled && swipeDownBubble) swipeDownLabel else null

            // 长按选项
            val longPressConfig = KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.longPress
            val longPressDisplay = longPressConfig?.display ?: DisplayMode.KEY
            val longPressLabels = if (longPressDisplay == DisplayMode.BUBBLE) {
                longPressConfig?.values?.map { it.label }?.filter { it.isNotEmpty() }
                    ?.ifEmpty { null }
            } else null
            val longPressGestureMap = if (longPressDisplay == DisplayMode.BUBBLE) {
                longPressConfig?.values?.associateBy { it.label }
            } else null

            // 键帽显示文本
            val rawCommitValue = KeysConfigHelper.getKeyCommitValue(key, isAsciiMode)
            val commitValue = if (isShifted) {
                rawCommitValue.uppercase()
            } else {
                rawCommitValue
            }
            val displayText = if (isAsciiMode) {
                commitValue
            } else {
                KeysConfigHelper.getKeyDisplayLabel(key, isAsciiMode)
            }

            val onClick = remember(key, commitValue, onKeyPress) { { onKeyPress(commitValue) } }
            val onPress: (() -> Unit)? = remember(key, onKeyPressDown) { { onKeyPressDown?.invoke(key); Unit } }
            val onRelease: (() -> Unit)? = remember(key, onKeyRelease) { { onKeyRelease?.invoke(key); Unit } }
            // 左/右滑：命中即执行动作（并在按钮内自动抑制父层光标手势）
            val onSwipeLeft: (() -> Unit)? = KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.swipeLeft
                ?.takeIf { it.action != null }?.let { a ->
                    {
                        if (a.action == GestureAction.COMMIT) {
                            (onCommitText ?: onKeyPress)(a.value.ifEmpty { a.label })
                        } else {
                            onGestureAction?.invoke(a.action!!, a.value.ifEmpty { a.label })
                        }
                    }
                }
            val onSwipeRight: (() -> Unit)? = KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.swipeRight
                ?.takeIf { it.action != null }?.let { a ->
                    {
                        if (a.action == GestureAction.COMMIT) {
                            (onCommitText ?: onKeyPress)(a.value.ifEmpty { a.label })
                        } else {
                            onGestureAction?.invoke(a.action!!, a.value.ifEmpty { a.label })
                        }
                    }
                }
            val onSwipeDown: ((String) -> Unit)? = if (swipeDownAction != null && swipeDownHintsEnabled && swipeDownLabel != null) {
                remember(key, onKeyPress, onGestureAction, onCommitText, swipeDownAction, swipeDownValue, swipeDownLabel) {
                    val label = swipeDownLabel
                    { _: String ->
                        if (swipeDownAction == GestureAction.COMMIT) {
                            (onCommitText ?: onKeyPress)(swipeDownValue?.ifEmpty { label } ?: label)
                        } else {
                            onGestureAction?.invoke(
                                swipeDownAction,
                                swipeDownValue?.ifEmpty { label } ?: label)
                        }
                        Unit
                    }
                }
            } else null
            val onLongPressSelect: ((String) -> Unit)? = remember(key, longPressGestureMap, onGestureAction, onCommitText, onKeyPress) { { selectedLabel: String ->
                val gesture = longPressGestureMap?.get(selectedLabel)
                if (gesture != null && gesture.action != GestureAction.COMMIT) {
                    onGestureAction?.invoke(
                        gesture.action!!,
                        gesture.value.ifEmpty { selectedLabel })
                } else {
                    (onCommitText ?: onKeyPress)(selectedLabel)
                }
                Unit
            } }

            SwipeableKeyButton(
                layoutMode = KeysConfigHelper.getButtonLayout(isAsciiMode),
                text = displayText,
                onClick = onClick,
                backgroundColor = config.keyBackgroundColor,
                textColor = config.keyTextColor,
                modifier = Modifier.weight(keyWidths?.getOrNull(index) ?: 1f),
                swipeText = swipeUpText,
                swipeDownText = swipeDownText,
                swipeUpKeyLabel = swipeUpKeyLabel,
                swipeDownKeyLabel = swipeDownKeyLabel,
                onSwipe = if (swipeUpCommitValue != null && swipeUpAction != GestureAction.NONE) { { onKeyPress(swipeUpCommitValue) } } else null,
                onSwipeDown = onSwipeDown,
                onSwipeLeft = onSwipeLeft,
                onSwipeRight = onSwipeRight,
                onSwipeStateChange = onSwipeStateChange,
                onPress = onPress,
                onRelease = onRelease,
                onLongPressSelect = onLongPressSelect,
                longPressItems = longPressLabels,
                fontSize = config.fontSize,
                swipeFontSize = config.swipeFontSize,
                shadowEnabled = config.shadowEnabled,
                shadowElevation = config.shadowElevation,
                shadowShapeRadius = config.shadowShapeRadius,
            )
        }
    }
}

@Composable
private fun ShiftCapsKeyButton(
    shiftMode: ShiftMode,
    onKeyPress: (String) -> Unit,
    onKeyPressDown: ((String) -> Unit)?,
    backgroundColor: Color,
    iconColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    /** 配置的 tap/双击动作；为 null 时走内置大小写状态机（shift_single / shift_caps）。 */
    onTap: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
    /** 滑动手势：参数为方向 "up"/"down"/"left"/"right"；为 null 时不检测滑动。 */
    onSwipe: ((String) -> Unit)? = null,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val density = LocalDensity.current

    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (shadowEnabled) {
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
    val keyCornerRadius = LocalKeyCornerRadius.current
    val keyClipShape = remember(keyCornerRadius) { RoundedCornerShape(keyCornerRadius) }

    fun darkenColor(color: Color, factor: Float = 0.15f): Color {
        return Color(
            red = (color.red * (1 - factor)).coerceIn(0f, 1f),
            green = (color.green * (1 - factor)).coerceIn(0f, 1f),
            blue = (color.blue * (1 - factor)).coerceIn(0f, 1f),
            alpha = color.alpha)
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    onKeyPressDown?.invoke("shift")
                    if (currentOnSwipe != null) {
                        val dir = awaitSwipeDirection(down) { false }
                        if (dir != null) {
                            currentOnSwipe?.invoke(dir)
                            isPressed = false
                            return@awaitEachGesture
                        }
                    }
                    if (currentOnTap != null) currentOnTap?.invoke() else onKeyPress("shift_single")

                    val firstUp = waitForUpOrCancellation()
                    if (firstUp != null) {
                        val secondDown = withTimeoutOrNull(
                            viewConfiguration.doubleTapTimeoutMillis
                        ) {
                            awaitFirstDown(requireUnconsumed = false)
                        }
                        if (secondDown != null) {
                            if (currentOnDoubleTap != null) currentOnDoubleTap?.invoke() else onKeyPress("shift_caps")
                            waitForUpOrCancellation()
                        }
                    }
                    isPressed = false
                }
            }
            .padding(LocalKeyVisualPadding.current)
            .then(shadowModifier)
            .clip(keyClipShape)
            .background(
                if (isPressed) darkenColor(backgroundColor, 0.1f)
                else if (shiftMode == ShiftMode.CAPS) darkenColor(backgroundColor, 0.2f)
                else if (shiftMode == ShiftMode.SINGLE) darkenColor(backgroundColor, 0.1f)
                else backgroundColor
            ),
        contentAlignment = Alignment.Center
    ) {
        val painter = when (shiftMode) {
            ShiftMode.OFF -> rememberVectorPainter(Icons.TwoTone.KeyboardControlKey)
            else -> rememberVectorPainter(Icons.TwoTone.KeyboardCapslock)
        }
        Icon(
            painter = painter,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(20.dp)
        )

        if (shiftMode == ShiftMode.CAPS) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(iconColor)
            )
        }
    }
}

/**
 * 横屏分体键盘行拆分：前/后各取 ceil(n/2) 个按键，奇数行的中间键两侧重复，
 * 与历史内置 QWERTY 拆分一致（asdfghjkl → asdfg / ghjkl，zxcvbnm → zxcv / vbnm）。
 * 行内容来自 xime.yaml / xime.custom.yaml 的 layout.rows，自定义布局（如 Colemak）同样生效。
 */
internal fun splitRowForLandscape(row: List<String>): Pair<List<String>, List<String>> {
    if (row.isEmpty()) return emptyList<String>() to emptyList()
    val half = (row.size + 1) / 2
    return row.take(half) to row.takeLast(half)
}

/**
 * 横屏分体键盘内容 — 横屏且用户开启分体布局时渲染。
 * 将键盘拆分为左右两个面板，紧贴屏幕左右边缘，中间留空方便双手持机拇指操作。
 * 样式按设备分两档：手机沿用原紧凑样式（12sp 小字号 + 2dp 行距 + 阶梯缩进，适配横屏矮键盘），
 * 平板与完整模式同视觉（复用 KeyboardRowWithConfig，仅保留分体容器结构）。
 */
@Composable
private fun LandscapeKeyboardContent(
    onKeyPress: (String) -> Unit,
    viewModel: KeyboardViewModel,
    callbacks: KeyboardCallbacks,
    uiState: KeyboardUiState,
    swipeUpHintsEnabled: Boolean,
    swipeDownHintsEnabled: Boolean,
    isAsciiMode: Boolean,
    keyRows: List<List<String>>,
    configVersion: Int = 0,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
) {
    val isShifted by viewModel.isShifted.collectAsStateWithLifecycle()
    val shiftMode by viewModel.shiftMode.collectAsStateWithLifecycle()

    var visualIsShifted by remember { mutableStateOf(false) }
    LaunchedEffect(isShifted) {
        if (isShifted) {
            delay(250L)
            visualIsShifted = isShifted
        } else {
            visualIsShifted = isShifted
        }
    }
    var visualShiftMode by remember { mutableStateOf(ShiftMode.OFF) }
    LaunchedEffect(shiftMode) {
        if (shiftMode == ShiftMode.SINGLE) {
            delay(250L)
            visualShiftMode = shiftMode
        } else {
            visualShiftMode = shiftMode
        }
    }

    val suppressCursorMove = LocalSuppressCursorMove.current
    // 手机保留原紧凑样式（横屏键盘矮，小字号 + 小行距不拥挤）；平板走完整模式视觉
    val compactStyle = !isTablet()
    val staggerStep = 10.dp
    val landscapeFontSize = if (compactStyle) 12.sp else TextUnit.Unspecified
    val landscapeSwipeFontSize = if (compactStyle) 7.sp else 9.sp

    val kbColors = KeysConfigHelper.getKeyboardColors()
    val longToColor: (Long) -> Color = { if (it > 0xFFFFFF) Color(it) else Color(0xFF000000 or it) }
    val keyboardBackgroundColor = KeyboardThemes.getKeyboardBackgroundColor(uiState.themeId, uiState.isDarkTheme)
    val themeScheme = KeyboardThemes.getThemeById(uiState.themeId)
    val themeSpecialKeyColor = KeyboardThemes.getSpecialKeyColor(uiState.themeId, uiState.isDarkTheme)
    val keyBackgroundColor = KeyboardThemes.getKeyBgColorOverride(uiState.themeId, uiState.isDarkTheme)
        ?: if (uiState.isDarkTheme) longToColor(kbColors.keyBgColorDark) else longToColor(kbColors.keyBgColor)
    val keyTextColor = KeyboardThemes.getKeyTextColorOverride(uiState.themeId, uiState.isDarkTheme)
        ?: if (uiState.isDarkTheme) longToColor(kbColors.keyTextColorDark) else longToColor(kbColors.keyTextColor)
    val specialKeyBackgroundColor = if (uiState.isDarkTheme) kbColors.specialKeyBgColorDark?.let { longToColor(it) }
        ?: themeSpecialKeyColor else kbColors.specialKeyBgColor?.let { longToColor(it) } ?: themeSpecialKeyColor
    val specialKeyTextColor = if (uiState.isDarkTheme) Color.White
        else KeyboardThemes.getSpecialKeyTextColor(uiState.themeId, false)
    val bubbleBgColor = if (uiState.isDarkTheme) themeScheme.specialKeyDark
        else themeScheme.specialKeyLight
    val kbShadow = KeysConfigHelper.getKeyboardShadow()
    val shadowEnabled = kbShadow.enabled
    val shadowElevation = kbShadow.elevation.dp
    val shadowShapeRadius = kbShadow.shapeRadius.dp
    // 分体行拆分：行内容与竖屏同源（getKeyRows，含 xime.custom.yaml 自定义布局），
    // 未配置时回退内置 QWERTY（与竖屏 getOrElse 兜底一致）
    // 横屏与竖屏同源 layout.rows（最多 5 行）；纯字母布局在最后一个字母行两端补 shift/delete
    val landscapeRows = landscapeQwertyRows(keyRows)
    val schemaName = uiState.schemaName
    val enterKeyText = uiState.enterKeyText
    val onKeyPressDown = callbacks.onKeyPressDown
    val onKeyRelease = callbacks.onKeyRelease
    val onCommitText = callbacks.onCommitText
    val onGestureAction: (GestureAction, String) -> Unit = { action, value ->
        when (action) {
            GestureAction.SWITCH_ROUTE -> {
                val overlayRoute = when (value) {
                    "emoji" -> OverlayRoute.Emoji
                    "symbol" -> OverlayRoute.Symbol
                    else -> null
                }
                overlayRoute?.let { viewModel.showOverlay(it) }
            }
            GestureAction.TOGGLE_ASCII -> {
                viewModel.resetShift()
                callbacks.onKeyPress("ime_switch", uiState.isAsciiMode)
            }
            GestureAction.DELETE -> callbacks.onKeyPress("delete", false)
            GestureAction.TOGGLE_SYMBOLS -> callbacks.onKeyPress("mode_change", false)
            GestureAction.TOGGLE_SHIFT -> viewModel.toggleShift()
            else -> callbacks.onGestureAction?.invoke(action, value) ?: Unit
        }
    }
    val env = QwertyRowEnv(
        isAsciiMode = isAsciiMode,
        isShifted = visualIsShifted,
        shiftMode = visualShiftMode,
        keyBackgroundColor = keyBackgroundColor,
        specialKeyBackgroundColor = specialKeyBackgroundColor,
        keyTextColor = keyTextColor,
        specialKeyTextColor = specialKeyTextColor,
        keyboardBackgroundColor = keyboardBackgroundColor,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        onKeyPress = onKeyPress,
        onKeyPressDown = onKeyPressDown,
        onKeyRelease = onKeyRelease,
        onCommitText = onCommitText,
        onGestureAction = onGestureAction,
        onVoiceModeChange = callbacks.onVoiceModeChange,
        isSttEnabled = uiState.isSttEnabled,
        isVoiceMode = uiState.isVoiceMode,
        isVoiceSticky = uiState.voiceSticky,
        schemaName = schemaName,
        enterKeyText = enterKeyText,
        suppressCursorMove = suppressCursorMove,
        processSwipeState = { state, bounds -> onSwipeStateChange?.invoke(state, bounds) },
        swipeUpHintsEnabled = swipeUpHintsEnabled,
        swipeDownHintsEnabled = swipeDownHintsEnabled,
        configVersion = configVersion,
        landscape = compactStyle,
        fontSize = landscapeFontSize,
        swipeFontSize = landscapeSwipeFontSize,
    )

    // 行距由外层 provider 统一提供（手机横屏回退 2dp / 平板 4.25dp），此处不再覆盖。
    // 侧边距统一 4dp + 面板内距 4dp = 8dp 靠边，与候选栏一致；
    // 挖孔/导航栏避让由服务层边衬区 padding 统一处理，不再叠加手机专用的 50dp 拇指区缩进。
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 2.dp, horizontal = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.42f)
                .padding(start = 4.dp),
        ) {
            landscapeRows.forEachIndexed { idx, row ->
                val (left, _) = splitRowForLandscape(row)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (compactStyle && idx < landscapeRows.lastIndex) staggerStep * idx else 0.dp)
                ) {
                    QwertyRow(ids = left, env = env)
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.16f))

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.42f)
                .padding(end = 4.dp),
        ) {
            landscapeRows.forEachIndexed { idx, row ->
                val (_, right) = splitRowForLandscape(row)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = if (compactStyle && idx < landscapeRows.lastIndex) staggerStep * idx else 0.dp)
                ) {
                    QwertyRow(ids = right, env = env)
                }
            }
        }
    }
}


/**
 * 横屏紧凑版按键 — 主字符和上滑字符垂直堆叠居中
 */
@Composable
fun SwipeableKeyButtonLandscape(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    swipeText: String? = null,
    swipeDownText: String? = null,
    swipeUpKeyLabel: String? = null,
    swipeDownKeyLabel: String? = null,
    onSwipe: ((String) -> Unit)? = null,
    onSwipeDown: ((String) -> Unit)? = null,
    /** 左/右滑动作；配置任一后该键横向滑动即接管，自动放弃父层光标手势。 */
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    onLongPressSelect: ((String) -> Unit)? = null,
    longPressItems: List<String>? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    swipeFontSize: androidx.compose.ui.unit.TextUnit = 8.sp,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
) {
    var isPressed by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var hasTriggeredSwipeUp by remember { mutableStateOf(false) }
    var hasTriggeredSwipeDown by remember { mutableStateOf(false) }
    var hasTriggeredSwipeLeft by remember { mutableStateOf(false) }
    var hasTriggeredSwipeRight by remember { mutableStateOf(false) }
    var isSwiping by remember { mutableStateOf(false) }
    var isSwipeDown by remember { mutableStateOf(false) }
    var buttonBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    var dragActivated by remember { mutableStateOf(false) }

    val currentText by rememberUpdatedState(text)
    val currentSwipeText by rememberUpdatedState(swipeText)
    val currentSwipeDownText by rememberUpdatedState(swipeDownText)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val currentOnSwipeDown by rememberUpdatedState(onSwipeDown)
    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentOnSwipeRight by rememberUpdatedState(onSwipeRight)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentOnLongPressSelect by rememberUpdatedState(onLongPressSelect)
    val currentLongPressItems by rememberUpdatedState(longPressItems)
    val currentOnSwipeStateChange by rememberUpdatedState(onSwipeStateChange)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val keyLabelFontFamily = AppFonts.keyLabelFontFamily
    val keyFontFamily = AppFonts.keyFontFamily

    val density = LocalDensity.current
    val swipeUpThreshold = with(density) { (-15).dp.toPx() }
    val swipeDownThreshold = with(density) { 15.dp.toPx() }
    val swipeLeftThreshold = with(density) { (-30).dp.toPx() }
    val swipeRightThreshold = with(density) { 30.dp.toPx() }
    // 横向接管阈值：低于父层光标手势激活阈值（60dp），使配置了左右滑的键优先接管横向滑动
    val horizontalSwipeSuppressThreshold = with(density) { 18.dp.toPx() }
    val suppressCursorMove = LocalSuppressCursorMove.current
    val bubbleShowThresholdUp = swipeUpThreshold * 0.3f
    val bubbleShowThresholdDown = swipeDownThreshold * 0.3f

    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (shadowEnabled) {
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
    val keyCornerRadius = LocalKeyCornerRadius.current
    val keyClipShape = remember(keyCornerRadius) { RoundedCornerShape(keyCornerRadius) }

    fun darkenColor(color: Color, factor: Float = 0.15f): Color {
        return Color(
            red = (color.red * (1 - factor)).coerceIn(0f, 1f),
            green = (color.green * (1 - factor)).coerceIn(0f, 1f),
            blue = (color.blue * (1 - factor)).coerceIn(0f, 1f),
            alpha = color.alpha
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .pointerInput(currentText, currentLongPressItems.isNullOrEmpty(), currentOnLongPressSelect != null) {
                if (currentLongPressItems.isNullOrEmpty() || currentOnLongPressSelect == null) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            currentOnSwipeStateChange?.invoke(SwipeState(isPressed = true, pressedText = currentText), buttonBounds)
                            currentOnPress?.invoke()
                            tryAwaitRelease()
                            isPressed = false
                            currentOnRelease?.invoke()
                            currentOnSwipeStateChange?.invoke(SwipeState(), buttonBounds)
                        },
                        onTap = {
                            if (!dragActivated && !hasTriggeredSwipeUp && !hasTriggeredSwipeDown) currentOnClick()
                        }
                    )
                } else {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isPressed = true
                        var localLongPressTriggered = false
                        var selectedIdx = 0
                        val downX = down.position.x
                        val items = currentLongPressItems ?: return@awaitEachGesture

                        currentOnSwipeStateChange?.invoke(SwipeState(isPressed = true, pressedText = currentText), buttonBounds)
                        currentOnPress?.invoke()

                        val longPressJob = scope.launch {
                            delay(400L)
                            localLongPressTriggered = true
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            currentOnSwipeStateChange?.invoke(
                                SwipeState(
                                    isPressed = true,
                                    isLongPress = true,
                                    longPressItems = items,
                                    selectedLongPressIndex = 0
                                ),
                                buttonBounds
                            )
                        }

                        val cancelThresholdPx = with(density) { 5.dp.toPx() }
                        val downY = down.position.y
                        var swipeDetected = false

                        try {
                            var lastReportedIdx = -1
                            var completed = false
                            while (!completed) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break

                                if (change.isConsumed) continue

                                if (!localLongPressTriggered) {
                                    val deltaX = change.position.x - downX
                                    val deltaY = change.position.y - downY
                                    if (kotlin.math.abs(deltaX) > cancelThresholdPx || kotlin.math.abs(deltaY) > cancelThresholdPx) {
                                        swipeDetected = true
                                        longPressJob.cancel()
                                    }
                                }

                                if (localLongPressTriggered) {
                                    val deltaX = change.position.x - downX
                                    val itemWidth = buttonBounds.width / items.size
                                    selectedIdx = ((deltaX / itemWidth) + if (items.size > 1) 0.5f else 0f).toInt()
                                        .coerceIn(0, items.size - 1)

                                    if (selectedIdx != lastReportedIdx) {
                                        lastReportedIdx = selectedIdx
                                        currentOnSwipeStateChange?.invoke(
                                            SwipeState(
                                                isPressed = true,
                                                isLongPress = true,
                                                longPressItems = items,
                                                selectedLongPressIndex = selectedIdx
                                            ),
                                            buttonBounds
                                        )
                                    }
                                    change.consume()
                                }

                                if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Release) {
                                    completed = true
                                    if (localLongPressTriggered) {
                                        val selected = items.getOrNull(selectedIdx)
                                        if (selected != null) {
                                            currentOnLongPressSelect?.invoke(selected)
                                        }
                                    } else if (!dragActivated) {
                                        // 不能用 swipeDetected 抑制点击：swipeDetected 由 5dp 位移触发，
                                        // 而 dragActivated 由 touch slop（更大）触发。5dp~touchSlop 区间
                                        // 若被 swipeDetected 吞掉点击且 drag 未激活，会造成快速打字漏键。
                                        // 5dp 位移只用于取消长按（longPressJob）。
                                        currentOnClick()
                                    }
                                }
                            }
                        } finally {
                            longPressJob.cancel()
                            isPressed = false
                            currentOnRelease?.invoke()
                            currentOnSwipeStateChange?.invoke(SwipeState(), buttonBounds)
                        }
                    }
                }
            }
            .then(
                if (swipeText != null || swipeDownText != null ||
                    onSwipeLeft != null || onSwipeRight != null
                ) {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                dragActivated = true
                                isPressed = true
                                dragOffsetY = 0f
                                dragOffsetX = 0f
                                hasTriggeredSwipeUp = false
                                hasTriggeredSwipeDown = false
                                hasTriggeredSwipeLeft = false
                                hasTriggeredSwipeRight = false
                                isSwiping = false
                                isSwipeDown = false
                                currentOnSwipeStateChange?.invoke(SwipeState(isPressed = true, pressedText = currentText), buttonBounds)
                            },
                            onDragEnd = {
                                if (!hasTriggeredSwipeUp && !hasTriggeredSwipeDown &&
                                    !hasTriggeredSwipeLeft && !hasTriggeredSwipeRight &&
                                    dragOffsetY > swipeUpThreshold && dragOffsetY < swipeDownThreshold) {
                                    onClick()
                                }
                                dragActivated = false
                                isPressed = false
                                dragOffsetY = 0f
                                dragOffsetX = 0f
                                hasTriggeredSwipeUp = false
                                hasTriggeredSwipeDown = false
                                hasTriggeredSwipeLeft = false
                                hasTriggeredSwipeRight = false
                                isSwiping = false
                                isSwipeDown = false
                                currentOnSwipeStateChange?.invoke(SwipeState(), buttonBounds)
                            },
                            onDragCancel = {
                                dragActivated = false
                                isPressed = false
                                dragOffsetY = 0f
                                dragOffsetX = 0f
                                hasTriggeredSwipeUp = false
                                hasTriggeredSwipeDown = false
                                hasTriggeredSwipeLeft = false
                                hasTriggeredSwipeRight = false
                                isSwiping = false
                                isSwipeDown = false
                                currentOnSwipeStateChange?.invoke(SwipeState(), buttonBounds)
                            },
                            onDrag = { _: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: Offset ->
                                dragOffsetX += dragAmount.x
                                dragOffsetY += dragAmount.y

                                val swipeTextValue = currentSwipeText
                                val swipeDownTextValue = currentSwipeDownText
                                val onSwipeAction = currentOnSwipe
                                val onSwipeDownAction = currentOnSwipeDown
                                val onSwipeLeftAction = currentOnSwipeLeft
                                val onSwipeRightAction = currentOnSwipeRight
                                val onSwipeStateChangeAction = currentOnSwipeStateChange

                                // 配置了左/右滑的键：横向滑动即接管，抑制父层光标手势
                                if (shouldSuppressCursorMove(
                                        onSwipeLeftAction != null || onSwipeRightAction != null,
                                        dragOffsetX, dragOffsetY, horizontalSwipeSuppressThreshold
                                    )
                                ) {
                                    suppressCursorMove.value = true
                                }
                                if (dragOffsetX < swipeLeftThreshold && !hasTriggeredSwipeLeft && onSwipeLeftAction != null) {
                                    hasTriggeredSwipeLeft = true
                                    onSwipeLeftAction()
                                }
                                if (dragOffsetX > swipeRightThreshold && !hasTriggeredSwipeRight && onSwipeRightAction != null) {
                                    hasTriggeredSwipeRight = true
                                    onSwipeRightAction()
                                }

                                if (dragOffsetY < 0) {
                                    val shouldShowBubble = swipeTextValue != null && dragOffsetY < bubbleShowThresholdUp
                                    if (shouldShowBubble != isSwiping) {
                                        isSwiping = shouldShowBubble
                                        isSwipeDown = false
                                        onSwipeStateChangeAction?.invoke(
                                            SwipeState(isSwiping = shouldShowBubble, swipeText = swipeTextValue, isSwipeDown = false),
                                            buttonBounds
                                        )
                                    }
                                } else if (dragOffsetY > 0) {
                                    val shouldShowBubble = swipeDownTextValue != null && dragOffsetY > bubbleShowThresholdDown
                                    if (shouldShowBubble != isSwipeDown) {
                                        isSwipeDown = shouldShowBubble
                                        isSwiping = shouldShowBubble
                                        onSwipeStateChangeAction?.invoke(
                                            SwipeState(isSwiping = shouldShowBubble, swipeText = swipeDownTextValue, isSwipeDown = true),
                                            buttonBounds
                                        )
                                    }
                                }

                                if (dragOffsetY < 0 && !hasTriggeredSwipeUp && swipeTextValue != null && onSwipeAction != null) {
                                    if (dragOffsetY < swipeUpThreshold) {
                                        hasTriggeredSwipeUp = true
                                        onSwipeAction(swipeTextValue)
                                    }
                                } else if (dragOffsetY > 0 && !hasTriggeredSwipeDown && swipeDownTextValue != null && onSwipeDownAction != null) {
                                    if (dragOffsetY > swipeDownThreshold) {
                                        hasTriggeredSwipeDown = true
                                        onSwipeDownAction(swipeDownTextValue)
                                    }
                                }
                            }
                        )
                    }
                } else Modifier
            )
            .onGloballyPositioned { coordinates ->
                buttonBounds = coordinates.boundsInRoot()
            }
            .padding(LocalKeyVisualPadding.current)
            .then(shadowModifier)
            .clip(keyClipShape)
            .background(if (isPressed) darkenColor(backgroundColor) else backgroundColor),
        contentAlignment = Alignment.TopStart
    ) {
        val contentScale = adaptiveKeyContentScale(
            keyHeightDp = maxHeight.value,
            referenceHeightDp = 44f,
        )
        val hintScale = adaptiveHintScale(contentScale)
        val effectiveFontSize = (
            if (fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) fontSize.value else 14f
        ) * contentScale
        val effectiveSwipeFontSize = swipeFontSize.value * hintScale

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = textColor,
                fontSize = effectiveFontSize.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                lineHeight = TextUnit.Unspecified,
                fontFamily = keyFontFamily
            )
        }

        val keyLabel = swipeUpKeyLabel ?: swipeText
        if (keyLabel != null) {
            Text(
                text = keyLabel,
                color = textColor.copy(alpha = 0.5f),
                fontSize = effectiveSwipeFontSize.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.End,
                maxLines = 1,
                lineHeight = (8f * hintScale).sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 4.dp)
            )
        }
        if (swipeDownKeyLabel != null) {
            Text(
                text = swipeDownKeyLabel,
                color = textColor.copy(alpha = 0.5f),
                fontSize = effectiveSwipeFontSize.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = keyLabelFontFamily,
                textAlign = TextAlign.Start,
                maxLines = 1,
                lineHeight = (8f * hintScale).sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 4.dp, bottom = 2.dp)
            )
        }
    }
}

/**
 * 横屏紧凑版键盘行 — 使用 [SwipeableKeyButtonLandscape] 替代 [SwipeableKeyButton]
 */
@Composable
fun CompactKeyboardRowWithConfig(
    keys: List<String>,
    onKeyPress: (String) -> Unit,
    config: KeyboardRowConfig,
    isShifted: Boolean,
    isAsciiMode: Boolean = false,
    modifier: Modifier = Modifier,
    onKeyPressDown: ((String) -> Unit)? = null,
    onKeyRelease: ((String) -> Unit)? = null,
    swipeDownHintsEnabled: Boolean = true,
    swipeUpHintsEnabled: Boolean = true,
    onCommitText: ((String) -> Unit)? = null,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    configVersion: Int = 0,
    /** 每个键的列宽（与 keys 一一对应）；缺省或长度不足时按 1 等宽。 */
    keyWidths: List<Float>? = null,
) {
    Row(
        modifier = modifier
            .fillMaxSize(),
    ) {
        keys.forEachIndexed { index, key ->
            val rawSwipeUpLabel = KeysConfigHelper.getSwipeUpLabel(key, isAsciiMode)
            val swipeUpAction = KeysConfigHelper.getSwipeUpAction(key, isAsciiMode)
            val swipeUpDisplay = KeysConfigHelper.getSwipeUpDisplay(key, isAsciiMode)
            val swipeUpBubble = KeysConfigHelper.getSwipeUpBubble(key, isAsciiMode)
            // 键面提示位置由 display 决定（BUBBLE 用空串压制回退）；运行时气泡由 bubble 决定
            val swipeUpKeyLabel = if (swipeUpHintsEnabled) {
                if (swipeUpDisplay == DisplayMode.BUBBLE) "" else rawSwipeUpLabel
            } else null
            val swipeUpText = if (swipeUpHintsEnabled && swipeUpBubble) rawSwipeUpLabel else null
            val swipeUpCommitValue = KeysConfigHelper.getSwipeUpCommitValue(key, isAsciiMode)
            val swipeDownRaw = KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.swipeDown
            val swipeDownLabel = swipeDownRaw?.label?.takeIf { it.isNotEmpty() }
            val swipeDownAction = swipeDownRaw?.action
            val swipeDownValue = swipeDownRaw?.value
            val swipeDownDisplay = swipeDownRaw?.display ?: DisplayMode.BOTH
            val swipeDownBubble = swipeDownRaw?.bubble ?: true
            val swipeDownKeyLabel = if (swipeDownHintsEnabled) {
                if (swipeDownDisplay == DisplayMode.BUBBLE) "" else swipeDownLabel
            } else null
            val swipeDownText = if (swipeDownHintsEnabled && swipeDownBubble) swipeDownLabel else null

            val longPressConfig = KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.longPress
            val longPressDisplay = longPressConfig?.display ?: DisplayMode.KEY
            val longPressLabels = if (longPressDisplay == DisplayMode.BUBBLE) {
                longPressConfig?.values?.map { it.label }?.filter { it.isNotEmpty() }
                    ?.ifEmpty { null }
            } else null
            val longPressGestureMap = if (longPressDisplay == DisplayMode.BUBBLE) {
                longPressConfig?.values?.associateBy { it.label }
            } else null

            val rawCommitValue = KeysConfigHelper.getKeyCommitValue(key, isAsciiMode)
            val commitValue = if (isShifted) {
                rawCommitValue.uppercase()
            } else {
                rawCommitValue
            }
            val compactDisplayText = if (isAsciiMode) commitValue else KeysConfigHelper.getKeyDisplayLabel(key, isAsciiMode)
            val compactOnClick = remember(key, commitValue, onKeyPress) { { onKeyPress(commitValue) } }
            val compactOnPress: (() -> Unit)? = remember(key, onKeyPressDown) { { onKeyPressDown?.invoke(key); Unit } }
            val compactOnRelease: (() -> Unit)? = remember(key, onKeyRelease) { { onKeyRelease?.invoke(key); Unit } }
            // 左/右滑：命中即执行动作（并在按钮内自动抑制父层光标手势）
            val compactOnSwipeLeft: (() -> Unit)? = KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.swipeLeft
                ?.takeIf { it.action != null }?.let { a ->
                    {
                        if (a.action == GestureAction.COMMIT) {
                            (onCommitText ?: onKeyPress)(a.value.ifEmpty { a.label })
                        } else {
                            onGestureAction?.invoke(a.action!!, a.value.ifEmpty { a.label })
                        }
                    }
                }
            val compactOnSwipeRight: (() -> Unit)? = KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.swipeRight
                ?.takeIf { it.action != null }?.let { a ->
                    {
                        if (a.action == GestureAction.COMMIT) {
                            (onCommitText ?: onKeyPress)(a.value.ifEmpty { a.label })
                        } else {
                            onGestureAction?.invoke(a.action!!, a.value.ifEmpty { a.label })
                        }
                    }
                }
            val compactOnSwipeDown: ((String) -> Unit)? = if (swipeDownAction != null && swipeDownHintsEnabled && swipeDownLabel != null) {
                remember(key, onKeyPress, onGestureAction, onCommitText, swipeDownAction, swipeDownValue, swipeDownLabel) {
                    val label = swipeDownLabel
                    { _: String ->
                        if (swipeDownAction == GestureAction.COMMIT) {
                            (onCommitText ?: onKeyPress)(swipeDownValue?.ifEmpty { label } ?: label)
                        } else {
                            onGestureAction?.invoke(
                                swipeDownAction,
                                swipeDownValue?.ifEmpty { label } ?: label!!)
                        }
                        Unit
                    }
                }
            } else null
            val compactOnLongPressSelect: ((String) -> Unit)? = remember(key, longPressGestureMap, onGestureAction, onCommitText, onKeyPress) { { selectedLabel: String ->
                val gesture = longPressGestureMap?.get(selectedLabel)
                if (gesture != null && gesture.action != GestureAction.COMMIT) {
                    onGestureAction?.invoke(
                        gesture.action!!,
                        gesture.value.ifEmpty { selectedLabel })
                } else {
                    (onCommitText ?: onKeyPress)(selectedLabel)
                }
                Unit
            } }

            SwipeableKeyButtonLandscape(
                text = compactDisplayText,
                onClick = compactOnClick,
                backgroundColor = config.keyBackgroundColor,
                textColor = config.keyTextColor,
                modifier = Modifier.weight(keyWidths?.getOrNull(index) ?: 1f),
                swipeText = swipeUpText,
                swipeDownText = swipeDownText,
                swipeUpKeyLabel = swipeUpKeyLabel,
                swipeDownKeyLabel = swipeDownKeyLabel,
                onSwipe = if (swipeUpCommitValue != null && swipeUpAction != GestureAction.NONE) { { onKeyPress(swipeUpCommitValue) } } else null,
                onSwipeDown = compactOnSwipeDown,
                onSwipeLeft = compactOnSwipeLeft,
                onSwipeRight = compactOnSwipeRight,
                onSwipeStateChange = onSwipeStateChange,
                onPress = compactOnPress,
                onRelease = compactOnRelease,
                onLongPressSelect = compactOnLongPressSelect,
                longPressItems = longPressLabels,
                fontSize = config.fontSize,
                swipeFontSize = config.swipeFontSize,
                shadowEnabled = config.shadowEnabled,
                shadowElevation = config.shadowElevation,
                shadowShapeRadius = config.shadowShapeRadius,
            )
        }
    }
}
/** QWERTY 空格键 */
@Composable
private fun SpaceKey(
    schemaName: String,
    isAsciiMode: Boolean,
    isSttEnabled: Boolean,
    isVoiceMode: Boolean,
    voiceSticky: Boolean,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    modifier: Modifier = Modifier,
    onKeyPress: (String) -> Unit,
    onKeyPressDown: ((String) -> Unit)?,
    onKeyRelease: ((String) -> Unit)?,
    onVoiceModeChange: ((Boolean) -> Unit)?,
    /** 配置的 tap/长按动作；为 null 时走内置默认（tap=空格、长按=进入语音）。 */
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    /** 滑动手势：参数为方向 "up"/"down"/"left"/"right"；为 null 时不检测滑动。 */
    onSwipe: ((String) -> Unit)? = null,
) {
    val currentOnKeyPress by rememberUpdatedState(onKeyPress)
    val currentOnKeyPressDown by rememberUpdatedState(onKeyPressDown)
    val currentOnKeyRelease by rememberUpdatedState(onKeyRelease)
    val currentOnVoiceModeChange by rememberUpdatedState(onVoiceModeChange)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val density = LocalDensity.current
    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, keyBackgroundColor) {
        if (shadowEnabled) {
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
    val keyFontFamily = AppFonts.keyFontFamily

    Box(
        modifier = modifier
            .fillMaxHeight()
            .pointerInput(isSttEnabled, voiceSticky) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    if (voiceSticky) {
                        // 常驻语音模式：轻触空格即结束语音
                        waitForUpOrCancellation()
                        currentOnVoiceModeChange?.invoke(false)
                        return@awaitEachGesture
                    }

                    currentOnKeyPressDown?.invoke("space")

                    var longPressTriggered = false
                    val longPressJob = scope.launch {
                        delay(400)
                        longPressTriggered = true

                        if (currentOnLongPress != null) {
                            currentOnLongPress?.invoke()
                        } else if (!PermissionHelper.hasRecordAudioPermission(context)) {
                            Toast.makeText(context, "需要麦克风权限才能使用语音输入", Toast.LENGTH_SHORT).show()
                            PermissionHelper.requestRecordAudioPermission(context)
                        } else {
                            currentOnVoiceModeChange?.invoke(true)
                        }
                    }

                    val dir: String?
                    if (currentOnSwipe != null) {
                        dir = awaitSwipeDirection(down) { longPressTriggered }
                    } else {
                        waitForUpOrCancellation()
                        dir = null
                    }
                    longPressJob.cancel()
                    currentOnKeyRelease?.invoke("space")

                    if (dir != null && currentOnSwipe != null) {
                        currentOnSwipe?.invoke(dir)
                    } else if (!longPressTriggered) {
                        if (currentOnTap != null) currentOnTap?.invoke() else currentOnKeyPress("space")
                    }
                }
            }
            .padding(LocalKeyVisualPadding.current)
            .fillMaxWidth()
            .fillMaxHeight()
            .then(shadowModifier)
            .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
            .background(keyBackgroundColor),
        contentAlignment = Alignment.Center
    ) {
        when {
            voiceSticky -> {
                Text(
                    text = "轻触结束语音",
                    color = keyTextColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
            isVoiceMode -> {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "语音输入",
                    tint = keyTextColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            else -> {
                Text(
                    text = if (isAsciiMode) "English" else schemaName,
                    color = keyTextColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    fontFamily = keyFontFamily
                )

                if (isSttEnabled) {
                    Icon(
                        painter = painterResource(com.kingzcheung.xime.R.drawable.voice),
                        contentDescription = "语音输入",
                        tint = keyTextColor.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp).align(Alignment.BottomStart).padding(start = 6.dp, bottom = 2.dp)
                    )
                } else {
                    Text(
                        text = "空格",
                        color = keyTextColor.copy(alpha = 0.3f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 2.dp),
                        fontFamily = keyFontFamily
                    )
                }
            }
        }
    }
}
