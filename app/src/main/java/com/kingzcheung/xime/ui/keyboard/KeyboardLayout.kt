package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.i18n.tr

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.twotone.EmojiEmotions
import androidx.compose.material.icons.twotone.KeyboardAlt
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
import com.kingzcheung.xime.correction.KeyTapLogger
import com.kingzcheung.xime.R
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.isTablet
import com.kingzcheung.xime.settings.DisplayMode
import com.kingzcheung.xime.settings.ButtonLayout
import com.kingzcheung.xime.settings.KeyAction
import com.kingzcheung.xime.settings.KeyBinding
import com.kingzcheung.xime.settings.KeyboardKeyConfig
import com.kingzcheung.xime.settings.KeyboardRow
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.RowAlign
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
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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


/**
 * shift 视觉延迟：按下 shift 后延迟 [delayMillis] 再点亮键面（快速连按不闪烁），松开立即熄灭。
 * 主键盘正常路径 delay=0（历史行为即时），分体路径 250ms（历史行为）。
 */
@Composable
private fun rememberVisualShiftState(isShifted: Boolean, shiftMode: ShiftMode, delayMillis: Long): Pair<Boolean, ShiftMode> {
    var visualIsShifted by remember { mutableStateOf(false) }
    LaunchedEffect(isShifted) {
        if (isShifted && delayMillis > 0) delay(delayMillis)
        visualIsShifted = isShifted
    }
    var visualShiftMode by remember { mutableStateOf(ShiftMode.OFF) }
    LaunchedEffect(shiftMode) {
        if (shiftMode == ShiftMode.SINGLE && delayMillis > 0) delay(delayMillis)
        visualShiftMode = shiftMode
    }
    return visualIsShifted to visualShiftMode
}

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
    val (visualIsShifted, visualShiftMode) = rememberVisualShiftState(isShifted, shiftMode, delayMillis = 0L)

    val context = LocalContext.current
    val palette = resolveKeyboardPalette(uiState.themeId, uiState.isDarkTheme)
    val keyboardBackgroundColor = palette.keyboardBackgroundColor
    val keyBackgroundColor = palette.keyBackgroundColor
    val keyTextColor = palette.keyTextColor
    val specialKeyBackgroundColor = palette.specialKeyBackgroundColor
    val specialKeyTextColor = palette.specialKeyTextColor
    val bubbleBgColor = palette.bubbleBgColor
    val kbKey = KeysConfigHelper.getKeyboardKeyConfig()
    val shadowParams = resolveKeyboardShadowParams()
    val shadowEnabled = shadowParams.enabled
    val shadowElevation = shadowParams.elevation
    val shadowShapeRadius = shadowParams.shapeRadius
    val schemaName = uiState.schemaName
    val enterKeyText = uiState.enterKeyText
    val isDarkTheme = uiState.isDarkTheme
    val isSttEnabled = uiState.isSttEnabled
    val isVoiceMode = uiState.isVoiceMode
    val isVoiceSticky = uiState.voiceSticky
    // 行布局：行声明（含行级 align/width）+ 参考列数 + 键盘级对齐策略
    val rowsLayout = KeysConfigHelper.getRowsLayout(isAsciiMode)
    val onKeyPressDown = callbacks.onKeyPressDown
    val onKeyRelease = callbacks.onKeyRelease
    val onVoiceModeChange = callbacks.onVoiceModeChange
    val onCommitText = callbacks.onCommitText
    val requestVoice = buildVoiceRequest(context, isSttEnabled, onVoiceModeChange)
    val onGestureAction = buildUiGestureHandler(viewModel, callbacks, uiState, requestVoice, onKeyPress)
    val suppressCursorMove = LocalSuppressCursorMove.current
    var landscapeSplitKeyboardEnabled by remember {
        mutableStateOf(SettingsPreferences.isLandscapeSplitKeyboardEnabled(context))
    }

    // 监听设置变化
    DisposableEffect(context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
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
    // 平板参数档：大键面配更大的圆角与键间距（对齐 Gboard 平板观感），手机不受影响；
    // 分体模式在平板上同样适用（分体只是行的排布方式，键面样式仍按设备档）
    val tabletParams = isTablet()

    // 圆角：yaml 按键盘覆盖优先，未覆盖时按设备档（平板大键面档）；与面板共用同一条规则
    val qwertyCornerRadius = keyCornerRadiusFor(kbKey, "qwerty", tabletParams)

    CompositionLocalProvider(
        LocalKeyCornerRadius provides qwertyCornerRadius,
        // 键面提示档与主键盘同一规则（退格的「清空」等提示的放大倍率与画法）
        LocalSwipeHintCorner provides swipeHintCornerFor(isLandscape, tabletParams),
        // 平板键面提示统一放大（字母上标/退格"清空"等所有键组件一致），手机为 1f
        LocalHintExtraScale provides hintExtraScaleFor(tabletParams),
        LocalKeyVisualPadding provides PaddingValues(
            horizontal = if (tabletParams) 4.dp else (kbKey.spacingFor("qwerty").first?.dp ?: 2.dp),
            // 手机横屏：只认 qwerty 专属覆盖（keyboard.key.qwerty.spacing_y），回退 2.5dp；
            // 其余场景：全局 qwerty 行距，回退 4.25dp；平板行距加大
            vertical = when {
                landscapeCompactSpacing -> (kbKey.spacingOverrides["qwerty"]?.spacingY?.dp ?: 2.5.dp)
                tabletParams -> 6.dp
                else -> (kbKey.spacingFor("qwerty").second?.dp ?: 4.25.dp)
            },
        ),
    ) {
    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                keyboardBounds = coordinates.boundsInRoot()
                // 按键坐标日志需要键盘矩形做跨设备归一化
                KeyTapLogger.keyboardBounds = keyboardBounds
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
                isAsciiMode = isAsciiMode,
                keyRows = rowsLayout.rows,
                configVersion = cfgVer,
                onSwipeStateChange = { state, bounds -> processSwipeState(state, bounds) },
            )
        } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        // 平板键盘两侧留边（Gboard 式不满宽），手机维持贴边紧凑
                        .padding(start = if (tabletParams) 16.dp else 4.dp, end = if (tabletParams) 16.dp else 4.dp, bottom = 8.dp),
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
                        val rows = remember(rowsLayout.rows) { normalizeQwertyRows(rowsLayout.rows) }
                        val columns = rowsLayout.columns ?: rows.firstOrNull()?.keys?.size ?: 10
                        rows.forEach { row ->
                            // 行宽由行声明或键盘级策略决定（[rowWidthFraction]）：显式 width 优先；
                            // uniform 按列对齐（行宽 = 键数/列数，键宽与首行一致、行居中）；
                            // stretch 或含功能键的行铺满均分。比例 < 1 时才需要居中缩进。
                            val fraction = rowWidthFraction(row, columns, rowsLayout.rowAlign)
                            val indent = if (fraction < 1f) {
                                Modifier
                                    .fillMaxWidth(fraction)
                                    .align(Alignment.CenterHorizontally)
                            } else Modifier
                            QwertyRow(
                                ids = row.keys,
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
                    contentDescription = tr(R.string.s_yuyinshuru),
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
 * - SEND_RIME → 走 [onKeyPress] 的字符串路由（UI 面板切换与 rime 通用键均在其内）
 * - COMMAND → [dispatchCommandAction]（命令名白名单 → 同一条按键路由；未登记的名字不上屏）
 * - 其余动作（编辑/删除/回车/空格/清空/面板切换等）→ [onGestureAction]（UI 拦截层 → 服务层）
 * - NONE / null → 不触发
 */
/**
 * 九键数字键点按是否被用户**显式**改绑。
 *
 * [parseKeyAction] 会把 `tap` 缺省的 action 兜底为 [GestureAction.SEND_RIME] 且 value 为空，
 * 因此「仅配置 label」的内置数字键（如 `"3": { tap: { label: "DEF" } }`）也会得到非 null 的
 * action。这类配置不属于显式改绑，必须保持内置九键数字输入——否则点按会把键面字母（"DEF"
 * 取首字符 'd'）送进 RIME，数字键退化为首字母简拼。
 */
internal fun t9DigitTapRebinds(tap: KeyAction?): Boolean =
    tap != null && tap.action != null &&
        (tap.action != GestureAction.SEND_RIME || tap.value.isNotEmpty())

/**
 * 语音转文本开关关闭时，xime.yaml 里配置的 `voice` 动作（如空格长按）不生效；其他动作不受影响。
 */
internal fun shouldSkipVoiceKeyAction(action: KeyAction?, isSttEnabled: Boolean): Boolean =
    action?.action == GestureAction.VOICE && !isSttEnabled

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
        GestureAction.SEND_RIME -> onKeyPress(value)
        GestureAction.COMMAND -> dispatchCommandAction(value, onKeyPress)
        GestureAction.NONE -> Unit
        else -> onGestureAction?.invoke(type, value)
    }
}

/**
 * 可上屏键（字母/数字等）的手势动作分发。
 *
 * - 未配置动作、COMMIT、SEND_RIME → 走 [onKeyPress]（沿用输入法组合/候选语义；调用方传入
 *   已按 shift 处理过的 [fallbackValue]，避免绕过大小写）；
 * - NONE → 视为未绑定，不触发；
 * - 其余动作（复制/粘贴/光标移动/面板切换等）→ [invokeKeyAction]。
 */
internal fun dispatchKeyActionOrPress(
    action: KeyAction?,
    fallbackValue: String?,
    onKeyPress: (String) -> Unit,
    onCommitText: ((String) -> Unit)?,
    onGestureAction: ((GestureAction, String) -> Unit)?,
) {
    when (action?.action) {
        null, GestureAction.COMMIT, GestureAction.SEND_RIME -> fallbackValue?.let(onKeyPress)
        GestureAction.NONE -> Unit
        else -> invokeKeyAction(action, onKeyPress, onCommitText, onGestureAction)
    }
}

/**
 * 长按气泡选中项的处理（26 键字母区标准/紧凑、逗号键、中英键共用）。
 *
 * - 未配置动作（action 为 null）或 COMMIT → 上屏：`value` 优先，其次气泡显示文本（label）；
 * - 其余动作 → 手势分发（`none` 交给分发的空实现吸收，不会上屏）。
 *
 * 说明：原实现直接上屏气泡显示文本，`{ label: "，", value: "。" }` 会打出"，"而不是"。"；
 * 且 `action: null` 会走到 `action!!` 崩溃，这里一并按"未配置动作"处理。
 */
internal fun dispatchLongPressSelection(
    selected: String,
    actionMap: Map<String, KeyAction>?,
    onKeyPress: (String) -> Unit,
    onCommitText: ((String) -> Unit)?,
    onGestureAction: ((GestureAction, String) -> Unit)?,
) {
    val gesture = actionMap?.get(selected)
    val action = gesture?.action
    if (gesture != null && action != null && action != GestureAction.COMMIT) {
        onGestureAction?.invoke(action, gesture.value.ifEmpty { selected })
    } else {
        (onCommitText ?: onKeyPress)(gesture?.value?.takeIf { it.isNotEmpty() } ?: selected)
    }
}

/**
 * 上滑手势处理器（26 键字母区标准/紧凑两套渲染共用）。
 *
 * - 未配置 swipe_up 且无旧默认表取值 → 返回 null（不检测上滑）；
 * - `action: none` → 生成空处理器「吸收」上滑（不上屏），与下滑/左右滑一致，
 *   避免竖向拖拽被落点点击判定误打出一个字符；
 * - 其余交给 [dispatchKeyActionOrPress]（COMMIT/未配置 → 原有上滑上屏；其它动作 → 手势分发）。
 */
internal fun swipeUpHandlerFor(
    swipeUp: KeyAction?,
    commitValue: String?,
    onKeyPress: (String) -> Unit,
    onCommitText: ((String) -> Unit)?,
    onGestureAction: ((GestureAction, String) -> Unit)?,
): ((String) -> Unit)? {
    if (swipeUp == null && commitValue == null) return null
    // 参数为按键组件回传的上滑提示文本（本处理器用不到，忽略）
    return { _: String -> dispatchKeyActionOrPress(swipeUp, commitValue, onKeyPress, onCommitText, onGestureAction) }
}

/**
 * 功能键（enter / 面板键 / 光标键 / caps 等）的通用手势装配。
 *
 * 语义与字母键槽位一致：COMMIT → 上屏，其余（含 none 吸收）走 [invokeKeyAction]；
 * 左右滑自动抑制父层光标手势；未配置的方向为 null（该方向不接管）。
 */
internal class FunctionKeyGestures(
    val onUp: (() -> Unit)?,
    val onDown: (() -> Unit)?,
    val onLeft: (() -> Unit)?,
    val onRight: (() -> Unit)?,
)

/** `SwipeableKeyButton` 的上滑/下滑回调带一个（被忽略的）提示文本参数。 */
internal fun (() -> Unit)?.withSwipeTextArg(): ((String) -> Unit)? = this?.let { h -> { _: String -> h() } }

internal fun functionKeyGestures(binding: KeyBinding?, env: QwertyRowEnv): FunctionKeyGestures =
    functionKeyGestures(
        swipeUp = binding?.swipeUp,
        swipeDown = binding?.swipeDown,
        swipeLeft = binding?.swipeLeft,
        swipeRight = binding?.swipeRight,
        onKeyPress = env.onKeyPress,
        onCommitText = env.onCommitText,
        onGestureAction = env.onGestureAction,
        onHorizontalSwipe = { env.suppressCursorMove.value = true },
    )

/** [functionKeyGestures] 的无 Compose 依赖重载（便于单测）。 */
internal fun functionKeyGestures(
    swipeUp: KeyAction?,
    swipeDown: KeyAction?,
    swipeLeft: KeyAction?,
    swipeRight: KeyAction?,
    onKeyPress: (String) -> Unit,
    onCommitText: ((String) -> Unit)?,
    onGestureAction: ((GestureAction, String) -> Unit)?,
    onHorizontalSwipe: () -> Unit,
): FunctionKeyGestures {
    fun slot(a: KeyAction?) = a?.takeIf { it.action != null }
    fun invoke(a: KeyAction): () -> Unit = { invokeKeyAction(a, onKeyPress, onCommitText, onGestureAction) }
    return FunctionKeyGestures(
        onUp = slot(swipeUp)?.let { invoke(it) },
        onDown = slot(swipeDown)?.let { invoke(it) },
        onLeft = slot(swipeLeft)?.let { a -> { onHorizontalSwipe(); invoke(a)() } },
        onRight = slot(swipeRight)?.let { a -> { onHorizontalSwipe(); invoke(a)() } },
    )
}

/** 一行内的共享渲染参数（颜色/回调等），供行循环与功能键组件复用；主键盘与面板渲染共用。 */
internal class QwertyRowEnv(
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
    val configVersion: Int,
    /** 横屏紧凑模式（手机）：字母段套用 [fontSize]/[swipeFontSize] 并切换横屏按键组件；平板为 false，走完整模式组件。 */
    val landscape: Boolean = false,
    val fontSize: TextUnit = TextUnit.Unspecified,
    val swipeFontSize: TextUnit = 9.sp,
    /** 面板文本功能键字号（返回/123/符号/确定/空格）；null = KeyButton 默认字号。主键盘不使用。 */
    val ctrlFontSize: TextUnit? = null,
    /** 手势/宽度配置来源段名（主键盘 = "qwerty"；面板 = number / common_symbols…）。 */
    val section: String = "qwerty",
)

/** 功能键列宽：显式 `keys.<id>.width` 优先，否则内置默认。 */
private fun functionKeyWidth(id: String, isAsciiMode: Boolean): Float {
    KeysConfigHelper.getKeyGesture(id, isAsciiMode)?.width?.takeIf { it > 0f }?.let { return it }
    return when (id) {
        "shift", "delete", "shift_left", "shift_right", "caps" -> 1.4f
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

/** 平板键面圆角下限：键圆角与阴影圆角在平板上共同对齐此值，两处解析（主键盘/分体）共享。 */
internal val TABLET_KEY_CORNER_RADIUS = 14.dp

/** 平板键面提示（数字上标、退格的「清空」等）的额外放大倍率；主键盘与面板共用同一档。 */
internal const val TABLET_HINT_EXTRA_SCALE = 1.35f

/** 键面提示是否画到键角（横屏矮键 / 平板大键）；主键盘与面板共用，避免两处各判一套。 */
internal fun swipeHintCornerFor(isLandscape: Boolean, isTablet: Boolean): Boolean = isLandscape || isTablet

/** 键面提示放大倍率：平板大键放大加深，其余 1f。 */
internal fun hintExtraScaleFor(isTablet: Boolean): Float = if (isTablet) TABLET_HINT_EXTRA_SCALE else 1f

/**
 * 按键圆角（dp）：xime.yaml 可按键盘覆盖（`keyboard.key.<键盘名>.corner_radius`；平板档
 * `<键盘名>_tablet` 在加载时已并到同名基础项），未覆盖时按**设备档**给默认——平板用大键面档
 * （不小于 [TABLET_KEY_CORNER_RADIUS]），手机用全局 `keyboard.key.corner_radius`。
 *
 * 平板 26 键与各面板（数字/常用符号）共用这一条，保证同一设备上各处键圆角一致；
 * 想单独调某个键盘就写 `<键盘名>: { corner_radius: N }`。
 */
/**
 * overlay 面板（全量符号 / 表情）的键圆角：这两块没有 xime.yaml 段，所以按全局
 * `keyboard.key.corner_radius` + 设备档（平板大键面档），与 26 键/面板同一观感。
 * 全量符号面板另用 [keyCornerRadiusFor] 传 "symbol"，让 `key.symbol.corner_radius` 也能管到它。
 */
internal fun overlayKeyCornerRadius(kbKey: KeyboardKeyConfig, isTablet: Boolean): Dp =
    if (isTablet) maxOf(kbKey.cornerRadius.dp, TABLET_KEY_CORNER_RADIUS) else kbKey.cornerRadius.dp

internal fun keyCornerRadiusFor(kbKey: KeyboardKeyConfig, keyboard: String, isTablet: Boolean): Dp =
    kbKey.spacingOverrides[keyboard]?.cornerRadius?.dp
        ?: if (isTablet) maxOf(kbKey.cornerRadius.dp, TABLET_KEY_CORNER_RADIUS) else kbKey.cornerRadius.dp

/** 主题调色板：主键盘正常路径、分体路径与面板渲染共用同一解析，防止多处取值漂移。 */
internal data class KeyboardPalette(
    val keyboardBackgroundColor: Color,
    val keyBackgroundColor: Color,
    val keyTextColor: Color,
    val specialKeyBackgroundColor: Color,
    val specialKeyTextColor: Color,
    val bubbleBgColor: Color,
)

internal fun resolveKeyboardPalette(themeId: String, isDarkTheme: Boolean): KeyboardPalette {
    val kbColors = KeysConfigHelper.getKeyboardColors()
    val longToColor: (Long) -> Color = { if (it > 0xFFFFFF) Color(it) else Color(0xFF000000 or it) }
    val themeScheme = KeyboardThemes.getThemeById(themeId)
    val themeSpecialKeyColor = KeyboardThemes.getSpecialKeyColor(themeId, isDarkTheme)
    val keyBackgroundColor = KeyboardThemes.getKeyBgColorOverride(themeId, isDarkTheme)
        ?: if (isDarkTheme) longToColor(kbColors.keyBgColorDark) else longToColor(kbColors.keyBgColor)
    val keyTextColor = KeyboardThemes.getKeyTextColorOverride(themeId, isDarkTheme)
        ?: if (isDarkTheme) longToColor(kbColors.keyTextColorDark) else longToColor(kbColors.keyTextColor)
    val specialKeyBackgroundColor = if (isDarkTheme) kbColors.specialKeyBgColorDark?.let { longToColor(it) }
        ?: themeSpecialKeyColor else kbColors.specialKeyBgColor?.let { longToColor(it) } ?: themeSpecialKeyColor
    val specialKeyTextColor = KeyboardThemes.getSpecialKeyTextColorForBackground(
        specialKeyBackgroundColor, keyTextColor
    )
    return KeyboardPalette(
        keyboardBackgroundColor = KeyboardThemes.getKeyboardBackgroundColor(themeId, isDarkTheme),
        keyBackgroundColor = keyBackgroundColor,
        keyTextColor = keyTextColor,
        specialKeyBackgroundColor = specialKeyBackgroundColor,
        specialKeyTextColor = specialKeyTextColor,
        bubbleBgColor = if (isDarkTheme) themeScheme.specialKeyDark else themeScheme.specialKeyLight,
    )
}

/** 阴影参数（含平板圆角下限）：主键盘与分体共用，避免「分体路径漏改平板下限」回归。 */
internal data class KeyboardShadowParams(
    val enabled: Boolean,
    val elevation: Dp,
    val shapeRadius: Dp,
)

@Composable
internal fun resolveKeyboardShadowParams(): KeyboardShadowParams {
    val kbShadow = KeysConfigHelper.getKeyboardShadow()
    return KeyboardShadowParams(
        enabled = kbShadow.enabled,
        elevation = kbShadow.elevation.dp,
        // 平板下阴影圆角不小于键圆角下限，否则键四角外露更方的阴影角（视觉"脏边"）
        shapeRadius = if (isTablet()) maxOf(kbShadow.shapeRadius.dp, TABLET_KEY_CORNER_RADIUS) else kbShadow.shapeRadius.dp,
    )
}

/**
 * UI 手势动作统一映射（主键盘正常路径、分体路径与面板渲染共用）。
 * [requestVoice] 传 null 时不拦截 VOICE，透传 callbacks（面板路径历史行为）；
 * 主键盘与分体传 [buildVoiceRequest] 产物（含 STT/权限门禁）。
 */
internal fun buildUiGestureHandler(
    viewModel: KeyboardViewModel,
    callbacks: KeyboardCallbacks,
    uiState: KeyboardUiState,
    requestVoice: (() -> Unit)?,
    onKeyPress: (String) -> Unit,
): (GestureAction, String) -> Unit = { action, value ->
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
        GestureAction.DELETE -> callbacks.onKeyPress("delete", false)
        GestureAction.TOGGLE_SYMBOLS -> callbacks.onKeyPress("mode_change", false)
        GestureAction.TOGGLE_SHIFT -> viewModel.toggleShift()
        GestureAction.VOICE -> requestVoice?.invoke()
            ?: callbacks.onGestureAction?.invoke(action, value) ?: Unit
        else -> callbacks.onGestureAction?.invoke(action, value) ?: Unit
    }
}

/** 语音入口动作（主键盘/分体共用）：STT 关闭不响应；无麦克风权限先请求，已授予则进入语音模式。 */
internal fun buildVoiceRequest(
    context: android.content.Context,
    isSttEnabled: Boolean,
    onVoiceModeChange: ((Boolean) -> Unit)?,
): () -> Unit = {
    if (isSttEnabled) {
        if (!PermissionHelper.hasRecordAudioPermission(context)) {
            Toast.makeText(context, tr(R.string.s_xuyaomaikefengquanxiancainengs_3b6e4), Toast.LENGTH_SHORT).show()
            PermissionHelper.requestRecordAudioPermission(context)
        } else {
            onVoiceModeChange?.invoke(true)
        }
    }
}

/** 标准 QWERTY 兜底行（3 字母行 + 控制行）。 */
internal val DEFAULT_QWERTY_ROWS: List<KeyboardRow> = listOf(
    KeyboardRow(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")),
    KeyboardRow(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")),
    KeyboardRow(listOf("z", "x", "c", "v", "b", "n", "m")),
    KeyboardRow(listOf("mode_change", "comma", "space", "earth", "enter")),
)

/**
 * 规范化键盘行：按配置取行（最多 [MAX_QWERTY_ROWS] 行），缺失的行用内置默认补齐，
 * 因此少于 4 行时会补出默认控制行；多出的行（如可选数字行）原样保留。
 */
internal fun normalizeQwertyRows(rows: List<KeyboardRow>): List<KeyboardRow> {
    val out = ArrayList<KeyboardRow>(MAX_QWERTY_ROWS)
    for (i in 0 until MAX_QWERTY_ROWS) {
        val row = rows.getOrNull(i) ?: DEFAULT_QWERTY_ROWS.getOrNull(i) ?: break
        if (row.keys.isEmpty()) break
        out.add(row)
    }
    return out.ifEmpty { DEFAULT_QWERTY_ROWS }
}

/**
 * 横屏行：在 [normalizeQwertyRows] 基础上，若整份布局不含任何功能键（如合并键布局只有字母行），
 * 在最后一个字母行两端补 `shift` / `delete`。
 */
internal fun landscapeQwertyRows(rows: List<KeyboardRow>): List<KeyboardRow> {
    val normalized = normalizeQwertyRows(rows)
    if (normalized.size < 2) return normalized
    // 忽略末尾控制行：只要其余行都不含功能键（纯字母布局），就在最后一个字母行两端补 shift/delete
    val contentRows = normalized.dropLast(1)
    val hasFunction = contentRows.any { r -> r.keys.any { it in KeysConfigHelper.FUNCTION_KEY_IDS } }
    if (hasFunction) return normalized
    val letterIdx = normalized.lastIndex - 1
    return normalized.mapIndexed { idx, r ->
        if (idx == letterIdx) r.copy(keys = listOf("shift") + r.keys + listOf("delete")) else r
    }
}

/**
 * 行的宽度占比（0..1）：
 * 1. 行级 `width` 显式优先；
 * 2. 含功能键的行铺满（功能键按权重分配，行不能缩进）；
 * 3. 否则按行级/键盘级 align：`uniform` = 键数/列数（键宽与列对齐、行居中），`stretch` = 铺满。
 * 判定纯函数化便于单测。
 */
internal fun rowWidthFraction(row: KeyboardRow, columns: Int, defaultAlign: RowAlign): Float {
    row.width?.let { return it.coerceIn(0f, 1f) }
    if (row.keys.any { it in KeysConfigHelper.FUNCTION_KEY_IDS }) return 1f
    return when (row.align ?: defaultAlign) {
        RowAlign.STRETCH -> 1f
        RowAlign.UNIFORM ->
            if (columns > 0) (row.keys.size.toFloat() / columns).coerceIn(0f, 1f) else 1f
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
                    onCommitText = env.onCommitText,
                    onGestureAction = env.onGestureAction,
                    configVersion = env.configVersion,
                    compactButton = env.landscape,
                )
                i = j
            }
        }
    }
}

/** 按 id 分派功能键组件。 */
/**
 * 渲染层已实现组件的功能键 id。
 *
 * 必须与 [KeysConfigHelper.FUNCTION_KEY_IDS] 保持一致：只进配置集合、没有组件的 id 会渲染成
 * 「占宽度但看不见、也不响应」的死键。两者一致性由单测保证（`FunctionKeyRenderContractTest`）。
 */
internal val RENDERED_FUNCTION_KEY_IDS: Set<String> = setOf(
    "shift", "delete", "mode_change", "comma", "space", "earth", "enter", "symbol", "emoji", "voice",
    "cursor_left", "cursor_right", "tab", "caps", "shift_left", "shift_right",
    "back", "back_right", "number", "open_symbols", "space_bar", "space_key",
)

@Composable
internal fun FunctionKeyCell(id: String, env: QwertyRowEnv, modifier: Modifier) {
    when (id) {
        "shift" -> ShiftCell(env, modifier)
        "caps" -> CapsCell(env, modifier)
        "shift_left" -> ShiftCell(env, modifier, id = "shift_left")
        "shift_right" -> ShiftCell(env, modifier, id = "shift_right")
        "delete" -> DeleteCell(env, modifier)
        "mode_change" -> ModeChangeCell(env, modifier)
        "comma" -> CommaCell(env, modifier)
        "space" -> SpaceCell(env, modifier)
        "earth" -> EarthCell(env, modifier)
        "enter" -> EnterCell(env, modifier, env.ctrlFontSize)
        "symbol" -> PanelActionCell(
            "symbol", GestureAction.TOGGLE_SYMBOLS, "", rememberVectorPainter(Icons.TwoTone.KeyboardAlt), env, modifier,
        )
        "emoji" -> PanelActionCell(
            "emoji", GestureAction.SWITCH_ROUTE, "emoji", rememberVectorPainter(Icons.Filled.EmojiEmotions), env, modifier,
        )
        "voice" -> PanelActionCell(
            "voice", GestureAction.VOICE, "", rememberVectorPainter(Icons.Filled.Mic), env, modifier,
        )
        "tab" -> PanelActionCell(
            "tab", GestureAction.TAB, "", rememberVectorPainter(Icons.AutoMirrored.Filled.KeyboardTab), env, modifier,
        )
        "cursor_left" -> CursorMoveCell(-1, env, modifier)
        "cursor_right" -> CursorMoveCell(1, env, modifier)
        // 面板专用功能键（数字/常用符号键盘；行为内置，与旧硬编码面板一致）
        "back" -> PanelBackCell(env, modifier)
        "back_right" -> PanelBackCell(env, modifier, id = "back_right")
        "number" -> PanelTextCell("number", "123", "number", special = true, env, modifier)
        "open_symbols" -> PanelTextCell("open_symbols", stringResource(R.string.keyboard_symbol), "symbol", special = true, env, modifier)
        "space_bar" -> PanelTextCell("space_bar", stringResource(R.string.keyboard_space), "space", special = true, env, modifier)
        "space_key" -> PanelTextCell("space_key", stringResource(R.string.keyboard_space), "space", special = false, env, modifier)
        else -> FileLogger.w("XimeKeyboard", "功能键 id \"$id\" 没有对应组件，该键不会显示：请改用受支持的 id")
    }
}

/**
 * 面板 / 语音类功能键（`symbol` / `emoji` / `voice`）。
 *
 * 默认动作分别是切换符号面板、打开表情面板（`switch_route: emoji`）、进入语音输入；
 * 可用 `keys.<id>.tap` 覆盖动作（`action: null` 视为沿用默认）与图标（`icon: "@mic"` 等）。
 */
@Composable
private fun PanelActionCell(
    id: String,
    defaultAction: GestureAction,
    defaultValue: String,
    defaultIcon: Painter,
    env: QwertyRowEnv,
    modifier: Modifier,
) {
    val binding = KeysConfigHelper.getGestureFor(env.section, id, env.isAsciiMode)
    val tap = binding?.tap
    val action = tap?.action ?: defaultAction
    val value = tap?.value?.takeIf { it.isNotEmpty() } ?: defaultValue
    val icon = tap?.icon?.takeIf { it.isNotEmpty() }?.let { panelKeyIcon(it) } ?: defaultIcon
    val gestures = functionKeyGestures(binding, env)
    val longPressConfig = binding?.longPress
    val longPressItems = longPressConfig?.let { KeysConfigHelper.longPressDisplayItems(it) }?.ifEmpty { null }
    val longPressMap = longPressConfig?.let { KeysConfigHelper.longPressActionMap(it) }
    SwipeableIconKeyButton(
        icon = icon,
        onClick = {
            invokeKeyAction(
                KeyAction(action = action, value = value),
                env.onKeyPress, env.onCommitText, env.onGestureAction,
            )
        },
        backgroundColor = env.specialKeyBackgroundColor,
        iconColor = env.specialKeyTextColor,
        modifier = modifier,
        onSwipe = gestures.onUp,
        onSwipeDown = gestures.onDown,
        onSwipeLeft = gestures.onLeft,
        onSwipeRight = gestures.onRight,
        longPressItems = longPressItems,
        onLongPressSelect = longPressItems?.let {
            { label ->
                dispatchLongPressSelection(
                    label, longPressMap, env.onKeyPress, env.onCommitText, env.onGestureAction,
                )
            }
        },
        a11yDescription = when (id) {
            "symbol" -> tr(R.string.s_fuhao)
            "emoji" -> tr(R.string.s_biaoqing)
            "voice" -> tr(R.string.s_yuyinshuru)
            "tab" -> tr(R.string.s_tab_jian)
            else -> null
        },
        onPress = { env.onKeyPressDown?.invoke(value) },
        onRelease = { env.onKeyRelease?.invoke(value) },
        shadowEnabled = env.shadowEnabled,
        shadowElevation = env.shadowElevation,
        shadowShapeRadius = env.shadowShapeRadius,
    )
}

/**
 * 光标移动键（◀ / ▶，平板布局用）。
 *
 * 默认动作分别是光标左移/右移（组合态移编码编辑光标、空闲态移编辑器光标）；
 * 可用 `keys.<id>.tap` 覆盖动作与图标（`icon: "@mic"` 等），与 [PanelActionCell] 同规则。
 */
@Composable
private fun CursorMoveCell(direction: Int, env: QwertyRowEnv, modifier: Modifier) {
    val id = if (direction < 0) "cursor_left" else "cursor_right"
    val binding = KeysConfigHelper.getKeyGesture(id, env.isAsciiMode)
    val tap = binding?.tap
    val action = tap?.action
        ?: if (direction < 0) GestureAction.CURSOR_LEFT else GestureAction.CURSOR_RIGHT
    val icon = tap?.icon?.takeIf { it.isNotEmpty() }?.let { panelKeyIcon(it) }
        ?: rememberVectorPainter(
            if (direction < 0) Icons.AutoMirrored.Filled.KeyboardArrowLeft
            else Icons.AutoMirrored.Filled.KeyboardArrowRight
        )
    val gestures = functionKeyGestures(binding, env)
    val longPressConfig = binding?.longPress
    val longPressItems = longPressConfig?.let { KeysConfigHelper.longPressDisplayItems(it) }?.ifEmpty { null }
    val longPressMap = longPressConfig?.let { KeysConfigHelper.longPressActionMap(it) }
    SwipeableIconKeyButton(
        icon = icon,
        onClick = {
            invokeKeyAction(
                KeyAction(action = action, value = tap?.value ?: ""),
                env.onKeyPress, env.onCommitText, env.onGestureAction,
            )
        },
        backgroundColor = env.specialKeyBackgroundColor,
        iconColor = env.specialKeyTextColor,
        modifier = modifier,
        a11yDescription = if (direction < 0) tr(R.string.s_guangbiaozuoyi) else tr(R.string.s_guangbiaoyouyi),
        onSwipe = gestures.onUp,
        onSwipeDown = gestures.onDown,
        onSwipeLeft = gestures.onLeft,
        onSwipeRight = gestures.onRight,
        longPressItems = longPressItems,
        onLongPressSelect = longPressItems?.let {
            { label ->
                dispatchLongPressSelection(
                    label, longPressMap, env.onKeyPress, env.onCommitText, env.onGestureAction,
                )
            }
        },
        onPress = { env.onKeyPressDown?.invoke(id) },
        onRelease = { env.onKeyRelease?.invoke(id) },
        shadowEnabled = env.shadowEnabled,
        shadowElevation = env.shadowElevation,
        shadowShapeRadius = env.shadowShapeRadius,
    )
}

/** 功能键图标名 → 内置图标（与 `label: "@xxx"` 的图标名约定一致）。 */
@Composable
private fun panelKeyIcon(name: String): Painter? = when (name) {
    "language", "globe" -> rememberVectorPainter(Icons.Filled.Language)
    "emoji", "mood", "emoji_emotions" -> rememberVectorPainter(Icons.Filled.EmojiEmotions)
    "mic", "voice" -> rememberVectorPainter(Icons.Filled.Mic)
    "symbol", "symbols", "keyboard" -> rememberVectorPainter(Icons.TwoTone.KeyboardAlt)
    else -> null
}

@Composable
private fun ShiftCell(env: QwertyRowEnv, modifier: Modifier, id: String = "shift") {
    // 按 env.section 取：主键盘为 "qwerty"（与旧入口等价），面板段则读本段配置——
    // 否则面板里的 caps/space 会读到 26 键的覆盖（平板 26 键的 caps 只有 width 无动作，会变成哑键）
    val shiftBinding = KeysConfigHelper.getGestureFor(env.section, id, env.isAsciiMode)
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

/**
 * 大写锁定键（⇪，平板布局行 2 左端用）。
 *
 * 单击切换大写锁定（CAPS ↔ OFF，复用 shift_caps 路由语义），CAPS 态键面高亮；
 * 可用 `keys.caps.tap` 覆盖动作。
 */
@Composable
private fun CapsCell(env: QwertyRowEnv, modifier: Modifier) {
    val capsBinding = KeysConfigHelper.getGestureFor(env.section, "caps", env.isAsciiMode)
    val capsTap = capsBinding?.tap
    val capsGestures = functionKeyGestures(capsBinding, env)
    val capsLongPressConfig = capsBinding?.longPress
    val capsLongPressItems = capsLongPressConfig?.let { KeysConfigHelper.longPressDisplayItems(it) }?.ifEmpty { null }
    val capsLongPressMap = capsLongPressConfig?.let { KeysConfigHelper.longPressActionMap(it) }
    val isCaps = env.shiftMode == ShiftMode.CAPS
    SwipeableIconKeyButton(
        icon = rememberVectorPainter(Icons.TwoTone.KeyboardCapslock),
        onClick = {
            if (capsTap != null) {
                invokeKeyAction(capsTap, env.onKeyPress, env.onCommitText, env.onGestureAction)
            } else {
                env.onKeyPress("shift_caps")
            }
        },
        backgroundColor = env.specialKeyBackgroundColor,
        iconColor = env.specialKeyTextColor,
        modifier = modifier,
        isHighlighted = isCaps,
        a11yDescription = tr(R.string.s_daxiesuoding),
        a11yState = if (isCaps) tr(R.string.s_yisuoding) else tr(R.string.s_weisuoding),
        onSwipe = capsGestures.onUp,
        onSwipeDown = capsGestures.onDown,
        onSwipeLeft = capsGestures.onLeft,
        onSwipeRight = capsGestures.onRight,
        longPressItems = capsLongPressItems,
        onLongPressSelect = capsLongPressItems?.let {
            { label ->
                dispatchLongPressSelection(
                    label, capsLongPressMap, env.onKeyPress, env.onCommitText, env.onGestureAction,
                )
            }
        },
        onPress = { env.onKeyPressDown?.invoke("shift_caps") },
        onRelease = { env.onKeyRelease?.invoke("shift_caps") },
        shadowEnabled = env.shadowEnabled,
        shadowElevation = env.shadowElevation,
        shadowShapeRadius = env.shadowShapeRadius,
    )
}

@Composable
private fun SpaceCell(env: QwertyRowEnv, modifier: Modifier) {
    val spaceBinding = KeysConfigHelper.getGestureFor(env.section, "space", env.isAsciiMode)
    val spaceTap = spaceBinding?.tap
    val spaceLong = spaceBinding?.longPress?.values?.firstOrNull()
    // 仅在显式配置为非默认空格时接管 tap；长按一旦配置则替换内置（语音/重复空格）
    val onTap: (() -> Unit)? = if (spaceTap != null && spaceTap.action != GestureAction.SPACE) {
        { invokeKeyAction(spaceTap, env.onKeyPress, env.onCommitText, env.onGestureAction) }
    } else null
    val spaceLongPressConfig = spaceBinding?.longPress
    val spaceLongPressItems = spaceLongPressConfig?.let { KeysConfigHelper.longPressDisplayItems(it) }?.ifEmpty { null }
    val spaceLongPressMap = spaceLongPressConfig?.let { KeysConfigHelper.longPressActionMap(it) }
    val onLongPress: (() -> Unit)? = if (spaceLongPressItems == null) {
        spaceLong?.let { action ->
            {
                // 长按动作由 xime.yaml 配置（默认 action: voice）；语音转文本开关关闭时不触发语音
                if (!shouldSkipVoiceKeyAction(action, env.isSttEnabled)) {
                    invokeKeyAction(action, env.onKeyPress, env.onCommitText, env.onGestureAction)
                }
            }
        }
    } else null
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
        longPressItems = spaceLongPressItems,
        onLongPressSelect = spaceLongPressItems?.let {
            { label ->
                dispatchLongPressSelection(
                    label, spaceLongPressMap, env.onKeyPress, env.onCommitText, env.onGestureAction,
                )
            }
        },
        onSwipeStateChange = { s, b -> env.processSwipeState(s, b) },
    )
}

@Composable
private fun EnterCell(env: QwertyRowEnv, modifier: Modifier, fontSize: TextUnit? = null) {
    val enterBinding = KeysConfigHelper.getGestureFor(env.section, "enter", env.isAsciiMode)
    val enterAction = enterBinding?.tap ?: KeyAction(GestureAction.ENTER)
    val enterKeyValue = enterAction.value.ifEmpty { "enter" }
    val enterGestures = functionKeyGestures(enterBinding, env)
    val enterLongPressConfig = enterBinding?.longPress
    val enterLongPressItems = enterLongPressConfig?.let { KeysConfigHelper.longPressDisplayItems(it) }?.ifEmpty { null }
    val enterLongPressMap = enterLongPressConfig?.let { KeysConfigHelper.longPressActionMap(it) }
    SwipeableKeyButton(
        text = enterAction.label.ifEmpty { env.enterKeyText },
        onClick = { invokeKeyAction(enterAction, env.onKeyPress, env.onCommitText, env.onGestureAction) },
        backgroundColor = env.specialKeyBackgroundColor,
        textColor = env.specialKeyTextColor,
        modifier = modifier,
        onSwipe = enterGestures.onUp.withSwipeTextArg(),
        onSwipeDown = enterGestures.onDown.withSwipeTextArg(),
        onSwipeLeft = enterGestures.onLeft,
        onSwipeRight = enterGestures.onRight,
        longPressItems = enterLongPressItems,
        onLongPressSelect = enterLongPressItems?.let {
            { label ->
                dispatchLongPressSelection(
                    label, enterLongPressMap, env.onKeyPress, env.onCommitText, env.onGestureAction,
                )
            }
        },
        onPress = { env.onKeyPressDown?.invoke(enterKeyValue) },
        onRelease = { env.onKeyRelease?.invoke(enterKeyValue) },
        shadowEnabled = env.shadowEnabled,
        shadowElevation = env.shadowElevation,
        shadowShapeRadius = env.shadowShapeRadius,
        fontSize = fontSize ?: androidx.compose.ui.unit.TextUnit.Unspecified,
    )
}

@Composable
private fun DeleteCell(env: QwertyRowEnv, modifier: Modifier) {
    val delBinding = KeysConfigHelper.getGestureFor(env.section, "delete", env.isAsciiMode)
    val delTap = delBinding?.tap ?: KeyAction(GestureAction.DELETE)
    val delLong = delBinding?.longPress?.values?.firstOrNull()
        ?: KeyAction(GestureAction.DELETE)
    val delUp = delBinding?.swipeUp ?: KeyAction(GestureAction.CLEAR_ALL, label = tr(R.string.s_shanghuaqingkong))
    val delDown = delBinding?.swipeDown ?: KeyAction(GestureAction.UNDO_CLEAR, label = tr(R.string.s_xiahuachehui))
    val delLeft = delBinding?.swipeLeft ?: KeyAction(GestureAction.COMMAND, "clear_composition")
    val delRight = delBinding?.swipeRight ?: KeyAction(GestureAction.COMMAND, "clear_composition")
    SwipeableIconKeyButton(
        icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
        onClick = { invokeKeyAction(delTap, env.onKeyPress, env.onCommitText, env.onGestureAction) },
        backgroundColor = env.specialKeyBackgroundColor,
        iconColor = env.specialKeyTextColor,
        modifier = modifier,
        a11yDescription = tr(R.string.s_tuige),
        swipeText = delRight.label.ifEmpty { tr(R.string.s_qingkong) },
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
    val mcBinding = KeysConfigHelper.getGestureFor(env.section, "mode_change", env.isAsciiMode)
    val mcGestures = functionKeyGestures(mcBinding, env)
    val mcTap = mcBinding?.tap
    val mcLongPressValues = mcBinding?.longPress?.values
    val mcLongPressItems = mcLongPressValues?.map { it.label }?.filter { it.isNotEmpty() }?.ifEmpty { null }
        ?: listOf("number", "common_symbol")
    SwipeableKeyButton(
        text = mcTap?.label?.takeIf { it.isNotEmpty() } ?: "?123",
        // tap.bubble: false → 不弹按压气泡（默认 true，行为与改动前一致）
        pressText = (mcTap?.label?.takeIf { it.isNotEmpty() } ?: "?123").takeIf { mcTap?.bubble ?: true },
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
        onSwipe = mcGestures.onUp.withSwipeTextArg(),
        onSwipeDown = mcGestures.onDown.withSwipeTextArg(),
        onSwipeLeft = mcGestures.onLeft,
        onSwipeRight = mcGestures.onRight,
        onSwipeStateChange = { state, bounds -> env.processSwipeState(state, bounds) },
        shadowEnabled = env.shadowEnabled,
        shadowElevation = env.shadowElevation,
        shadowShapeRadius = env.shadowShapeRadius,
    )
}

@Composable
private fun CommaCell(env: QwertyRowEnv, modifier: Modifier) {
    val k2KeyGesture = KeysConfigHelper.getGestureFor(env.section, "comma", env.isAsciiMode)
    val k2Gestures = functionKeyGestures(k2KeyGesture, env)
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
        k2LongPressConfig?.let { KeysConfigHelper.longPressDisplayItems(it) }?.ifEmpty { null }
    } else null
    val k2LongPressGestureMap = if (k2LongPressDisplay == DisplayMode.BUBBLE) {
        k2LongPressConfig?.let { KeysConfigHelper.longPressActionMap(it) }
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
    val k2OnSwipeDown: ((String) -> Unit)? = if (k2SwipeDownAction != null) {
        remember(k2SwipeDownAction, k2SwipeDownValue, k2SwipeDownLabel, env.onKeyPress, env.onGestureAction, env.onCommitText) {
            // 与字母区一致：下滑只写 action（无 label）也能触发；值取 value、其次 label；
            // action:none 同样生成处理器以「吸收」下滑，避免竖向拖拽被判成点击而打出字符。
            { _: String ->
                val text = k2SwipeDownValue?.takeIf { it.isNotEmpty() } ?: k2SwipeDownLabel.orEmpty()
                if (k2SwipeDownAction == GestureAction.COMMIT) {
                    (env.onCommitText ?: env.onKeyPress)(text)
                } else {
                    env.onGestureAction?.invoke(k2SwipeDownAction, text)
                }
                Unit
            }
        }
    } else null
    val k2OnLongPressSelect: ((String) -> Unit)? = remember(k2LongPressGestureMap, env.onGestureAction, env.onCommitText, env.onKeyPress) {
        { selectedLabel: String ->
            dispatchLongPressSelection(
                selectedLabel, k2LongPressGestureMap, env.onKeyPress, env.onCommitText, env.onGestureAction)
        }
    }
    if (k2TapAction == GestureAction.TOGGLE_ASCII) {
        // 图标形态：用可手势的图标键，避免「配了滑动但走 IconKeyButton 被丢弃」
        SwipeableIconKeyButton(
            icon = rememberVectorPainter(Icons.Default.Language),
            onClick = k2OnClick,
            backgroundColor = env.keyBackgroundColor,
            iconColor = env.keyTextColor,
            modifier = modifier,
            a11yDescription = tr(R.string.s_zhongyingqiehuan),
            a11yState = if (env.isAsciiMode) tr(R.string.s_dangqianyingwen) else tr(R.string.s_dangqianzhongwen),
            onSwipe = k2Gestures.onUp,
            onSwipeDown = k2Gestures.onDown,
            onSwipeLeft = k2Gestures.onLeft,
            onSwipeRight = k2Gestures.onRight,
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
            // tap.bubble: false → 不弹按压气泡（默认 true，行为与改动前一致）
            pressText = k2TapLabel.takeIf { k2KeyGesture?.tap?.bubble ?: true },
            onClick = k2OnClick,
            backgroundColor = env.keyBackgroundColor,
            textColor = env.keyTextColor,
            modifier = modifier,
            swipeText = k2SwipeUpText,
            swipeDownText = k2SwipeDownText,
            swipeUpKeyLabel = k2SwipeUpKeyLabel,
            swipeDownKeyLabel = k2SwipeDownKeyLabel,
            // 上滑与字母键同一条分发：尊重配置的 action（COMMIT 沿用按键路由，其余走手势分发）
            onSwipe = swipeUpHandlerFor(
                k2SwipeUpRaw, k2SwipeUpCommitValue, env.onKeyPress, env.onCommitText, env.onGestureAction,
            ),
            onSwipeDown = k2OnSwipeDown,
            onSwipeLeft = k2Gestures.onLeft,
            onSwipeRight = k2Gestures.onRight,
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
    val k4KeyGesture = KeysConfigHelper.getGestureFor(env.section, "earth", env.isAsciiMode)
    val k4Gestures = functionKeyGestures(k4KeyGesture, env)
    val k4TapAction = k4KeyGesture?.tap?.action
    val isEarthDefaultTap = k4KeyGesture?.tap == null
    val k4TapValue = k4KeyGesture?.tap?.value?.takeIf { it.isNotEmpty() } ?: "ime_switch"
    val k4TapLabel = k4KeyGesture?.tap?.label?.takeIf { it.isNotEmpty() } ?: tr(R.string.s_zhong)
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
        k4LongPressConfig?.let { KeysConfigHelper.longPressDisplayItems(it) }?.ifEmpty { null }
    } else null
    val k4LongPressGestureMap = if (k4LongPressDisplay == DisplayMode.BUBBLE) {
        k4LongPressConfig?.let { KeysConfigHelper.longPressActionMap(it) }
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
    // 上滑与字母键同一条分发：尊重配置的 action（COMMIT 沿用按键路由，其余走手势分发）
    val k4OnSwipe: ((String) -> Unit)? = swipeUpHandlerFor(
        k4SwipeUpRaw, k4SwipeUpValue, env.onKeyPress, env.onCommitText, env.onGestureAction,
    )
    val k4OnSwipeDown: ((String) -> Unit)? = if (k4SwipeDownAction != null) {
        remember(k4SwipeDownAction, k4SwipeDownValue, k4SwipeDownLabel, env.onKeyPress, env.onGestureAction, env.onCommitText) {
            // 与字母区一致：下滑只写 action（无 label）也能触发；值取 value、其次 label；
            // action:none 同样生成处理器以「吸收」下滑，避免竖向拖拽被判成点击而打出字符。
            { _: String ->
                val text = k4SwipeDownValue?.takeIf { it.isNotEmpty() } ?: k4SwipeDownLabel.orEmpty()
                if (k4SwipeDownAction == GestureAction.COMMIT) {
                    (env.onCommitText ?: env.onKeyPress)(text)
                } else {
                    env.onGestureAction?.invoke(k4SwipeDownAction, text)
                }
                Unit
            }
        }
    } else null
    val k4OnLongPressSelect: ((String) -> Unit)? = remember(k4LongPressGestureMap, env.onGestureAction, env.onCommitText, env.onKeyPress) {
        { selectedLabel: String ->
            dispatchLongPressSelection(
                selectedLabel, k4LongPressGestureMap, env.onKeyPress, env.onCommitText, env.onGestureAction)
        }
    }
    if ((k4TapAction == GestureAction.TOGGLE_ASCII && k4Icon != null || isEarthDefaultTap) && k4LongPressLabels == null) {
        // 图标形态：用可手势的图标键，避免「配了滑动但走 IconKeyButton 被丢弃」
        SwipeableIconKeyButton(
            icon = k4Icon ?: rememberVectorPainter(Icons.Default.Language),
            onClick = k4OnClick,
            backgroundColor = env.keyBackgroundColor,
            iconColor = env.keyTextColor,
            modifier = modifier,
            a11yDescription = tr(R.string.s_zhongyingqiehuan),
            a11yState = if (env.isAsciiMode) tr(R.string.s_dangqianyingwen) else tr(R.string.s_dangqianzhongwen),
            onSwipe = k4Gestures.onUp,
            onSwipeDown = k4Gestures.onDown,
            onSwipeLeft = k4Gestures.onLeft,
            onSwipeRight = k4Gestures.onRight,
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
            // tap.bubble: false → 不弹按压气泡（默认 true，行为与改动前一致）
            pressText = k4TapLabel.takeIf { k4KeyGesture?.tap?.bubble ?: true },
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
            onSwipeLeft = k4Gestures.onLeft,
            onSwipeRight = k4Gestures.onRight,
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
    onCommitText: ((String) -> Unit)? = null,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
    configVersion: Int = 0,
    /** 每个键的列宽（与 keys 一一对应）；缺省或长度不足时按 1 等宽。 */
    keyWidths: List<Float>? = null,
    /** 手机横屏紧凑档：改用 [SwipeableKeyButtonLandscape]（主字符与上滑提示垂直堆叠、手势阈值收紧）。 */
    compactButton: Boolean = false,
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
            val swipeUpKeyLabel = if (swipeUpDisplay == DisplayMode.BUBBLE) "" else rawSwipeUpLabel
            val swipeUpText = if (swipeUpBubble) rawSwipeUpLabel else null
            val swipeUpCommitValue = KeysConfigHelper.getSwipeUpCommitValue(key, isAsciiMode)
            val swipeDownRaw = KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.swipeDown
            val swipeDownLabel = swipeDownRaw?.label?.takeIf { it.isNotEmpty() }
            val swipeDownAction = swipeDownRaw?.action
            val swipeDownValue = swipeDownRaw?.value
            val swipeDownDisplay = swipeDownRaw?.display ?: DisplayMode.BOTH
            val swipeDownBubble = swipeDownRaw?.bubble ?: true
            val swipeDownKeyLabel = if (swipeDownDisplay == DisplayMode.BUBBLE) "" else swipeDownLabel
            val swipeDownText = if (swipeDownBubble) swipeDownLabel else null

            // 长按选项
            val longPressConfig = KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.longPress
            val longPressDisplay = longPressConfig?.display ?: DisplayMode.KEY
            val longPressLabels = if (longPressDisplay == DisplayMode.BUBBLE) {
                longPressConfig?.let { KeysConfigHelper.longPressDisplayItems(it) }
                    ?.ifEmpty { null }
            } else null
            val longPressGestureMap = if (longPressDisplay == DisplayMode.BUBBLE) {
                longPressConfig?.let { KeysConfigHelper.longPressActionMap(it) }
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

            // tap 动作接线：非 COMMIT/SEND_RIME 的动作（复制/粘贴/面板切换等）走手势分发，
            // 其余沿用原有上屏路径（shift 大写与输入法组合语义不变）
            val tapAction = KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.tap
            val onClick: () -> Unit = remember(key, commitValue, tapAction, onKeyPress, onCommitText, onGestureAction) {
                { dispatchKeyActionOrPress(tapAction, commitValue, onKeyPress, onCommitText, onGestureAction) }
            }
            // 上滑动作接线（action: none 视为未绑定；COMMIT/未配置沿用旧上滑上屏）
            val onSwipeUp: ((String) -> Unit)? = remember(
                key, swipeUpAction, swipeUpCommitValue, onKeyPress, onCommitText, onGestureAction
            ) {
                swipeUpHandlerFor(
                    KeysConfigHelper.getKeyGesture(key, isAsciiMode)?.swipeUp,
                    swipeUpCommitValue, onKeyPress, onCommitText, onGestureAction,
                )
            }
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
            // 下滑手势与提示文本解耦：只写 action（无 label）也能触发。
            // 注意 action:none 也要生成处理器：它由动作分发空实现「吸收」下滑，
            // 否则竖向拖拽会走落点点击判定（shouldClick 只看横向位移）而误打出一个字母。
            // 传给动作的值优先 value、其次 label，都没有时为空串（仅对无需值的动作有意义）。
            val onSwipeDown: ((String) -> Unit)? = if (swipeDownAction != null) {
                remember(key, onKeyPress, onGestureAction, onCommitText, swipeDownAction, swipeDownValue, swipeDownLabel) {
                    { _: String ->
                        val text = swipeDownValue?.takeIf { it.isNotEmpty() } ?: swipeDownLabel.orEmpty()
                        if (swipeDownAction == GestureAction.COMMIT) {
                            (onCommitText ?: onKeyPress)(text)
                        } else {
                            onGestureAction?.invoke(swipeDownAction, text)
                        }
                        Unit
                    }
                }
            } else null
            val onLongPressSelect: ((String) -> Unit)? = remember(key, longPressGestureMap, onGestureAction, onCommitText, onKeyPress) { { selectedLabel: String ->
                dispatchLongPressSelection(
                    selectedLabel, longPressGestureMap, onKeyPress, onCommitText, onGestureAction)
            } }

            if (compactButton) {
                SwipeableKeyButtonLandscape(
                    text = displayText,
                    // tap.bubble: false → 不弹按压气泡（默认 true，行为与改动前一致）
                    pressText = displayText.takeIf { tapAction?.bubble ?: true },
                    onClick = onClick,
                    backgroundColor = config.keyBackgroundColor,
                    textColor = config.keyTextColor,
                    modifier = Modifier.weight(keyWidths?.getOrNull(index) ?: 1f),
                    swipeText = swipeUpText,
                    swipeDownText = swipeDownText,
                    swipeUpKeyLabel = swipeUpKeyLabel,
                    swipeDownKeyLabel = swipeDownKeyLabel,
                    onSwipe = onSwipeUp,
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
            } else {
            SwipeableKeyButton(
                layoutMode = KeysConfigHelper.getButtonLayout(isAsciiMode),
                text = displayText,
                // tap.bubble: false → 不弹按压气泡（默认 true，行为与改动前一致）
                pressText = displayText.takeIf { tapAction?.bubble ?: true },
                onClick = onClick,
                backgroundColor = config.keyBackgroundColor,
                textColor = config.keyTextColor,
                modifier = Modifier.weight(keyWidths?.getOrNull(index) ?: 1f),
                swipeText = swipeUpText,
                swipeDownText = swipeDownText,
                swipeUpKeyLabel = swipeUpKeyLabel,
                swipeDownKeyLabel = swipeDownKeyLabel,
                onSwipe = onSwipeUp,
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
            )
            .keySemantics(
                tr(R.string.s_daxiaoxieqiehuan),
                when (shiftMode) {
                    ShiftMode.CAPS -> tr(R.string.s_daxiesuoding)
                    ShiftMode.SINGLE -> tr(R.string.s_daxie)
                    ShiftMode.OFF -> tr(R.string.s_xiaoxie)
                },
            ) {
                if (currentOnTap != null) currentOnTap?.invoke() else onKeyPress("shift_single")
            },
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
    isAsciiMode: Boolean,
    keyRows: List<KeyboardRow>,
    configVersion: Int = 0,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
) {
    val isShifted by viewModel.isShifted.collectAsStateWithLifecycle()
    val shiftMode by viewModel.shiftMode.collectAsStateWithLifecycle()
    val (visualIsShifted, visualShiftMode) = rememberVisualShiftState(isShifted, shiftMode, delayMillis = 250L)

    val suppressCursorMove = LocalSuppressCursorMove.current
    // 手机保留原紧凑样式（横屏键盘矮，小字号 + 小行距不拥挤）；平板走完整模式视觉
    val compactStyle = !isTablet()
    val staggerStep = 10.dp
    val landscapeFontSize = if (compactStyle) 12.sp else TextUnit.Unspecified
    val landscapeSwipeFontSize = if (compactStyle) 7.sp else 9.sp

    val palette = resolveKeyboardPalette(uiState.themeId, uiState.isDarkTheme)
    val keyboardBackgroundColor = palette.keyboardBackgroundColor
    val keyBackgroundColor = palette.keyBackgroundColor
    val keyTextColor = palette.keyTextColor
    val specialKeyBackgroundColor = palette.specialKeyBackgroundColor
    val specialKeyTextColor = palette.specialKeyTextColor
    val shadowParams = resolveKeyboardShadowParams()
    val shadowEnabled = shadowParams.enabled
    val shadowElevation = shadowParams.elevation
    val shadowShapeRadius = shadowParams.shapeRadius
    // 分体行拆分：行内容与竖屏同源（getKeyRows，含 xime.custom.yaml 自定义布局），
    // 未配置时回退内置 QWERTY（与竖屏 getOrElse 兜底一致）
    // 横屏与竖屏同源 layout.rows（最多 5 行）；纯字母布局在最后一个字母行两端补 shift/delete
    val landscapeRows = landscapeQwertyRows(keyRows)
    val schemaName = uiState.schemaName
    val enterKeyText = uiState.enterKeyText
    val onKeyPressDown = callbacks.onKeyPressDown
    val onKeyRelease = callbacks.onKeyRelease
    val onCommitText = callbacks.onCommitText
    // 分体路径与主键盘同源语音入口（空格长按/语音键）：此前 requestVoice=null 透传服务层，
    // 而 KeyActionContext 未挂 ui，voice 动作静默失效
    val requestVoice = buildVoiceRequest(LocalContext.current, uiState.isSttEnabled, callbacks.onVoiceModeChange)
    val onGestureAction = buildUiGestureHandler(viewModel, callbacks, uiState, requestVoice, onKeyPress)
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
        configVersion = configVersion,
        landscape = compactStyle,
        fontSize = landscapeFontSize,
        swipeFontSize = landscapeSwipeFontSize,
    )

    // 行距由外层 provider 统一提供（手机横屏回退 2dp / 平板 4.25dp），此处不再覆盖。
    // 侧边距统一 4dp + 面板内距 4dp = 8dp 靠边，与候选栏一致；平板分体两侧留边 16dp（同主键盘）
    // 挖孔/导航栏避让由服务层边衬区 padding 统一处理，不再叠加手机专用的 50dp 拇指区缩进。
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 2.dp, horizontal = if (isTablet()) 16.dp else 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.42f)
                .padding(start = 4.dp),
        ) {
            landscapeRows.forEachIndexed { idx, row ->
                val (left, _) = splitRowForLandscape(row.keys)
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
                val (_, right) = splitRowForLandscape(row.keys)
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
    /** 长按气泡项；非空时接管长按（气泡滑动选择），[onLongPress] 与 [onSwipe] 不再生效。 */
    longPressItems: List<String>? = null,
    longPressDrawableIds: List<Int> = emptyList(),
    onLongPressSelect: ((String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
) {
    val currentOnKeyPress by rememberUpdatedState(onKeyPress)
    val currentOnKeyPressDown by rememberUpdatedState(onKeyPressDown)
    val currentOnKeyRelease by rememberUpdatedState(onKeyRelease)
    val currentOnLongPressSelect by rememberUpdatedState(onLongPressSelect)
    val currentLongPressItems by rememberUpdatedState(longPressItems)
    val currentLongPressDrawableIds by rememberUpdatedState(longPressDrawableIds)
    val view = LocalView.current
    var buttonBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
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
            .pointerInput(isSttEnabled, voiceSticky, currentLongPressItems == null) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    if (voiceSticky) {
                        // 常驻语音模式：轻触空格即结束语音
                        waitForUpOrCancellation()
                        currentOnVoiceModeChange?.invoke(false)
                        return@awaitEachGesture
                    }

                    currentOnKeyPressDown?.invoke("space")

                    val items = currentLongPressItems
                    if (items != null) {
                        // 长按气泡滑动选择（配置了 long_press 多项时接管长按；该键不再检测滑动）
                        var triggered = false
                        var moved = false
                        var selectedIdx = 0
                        val downX = down.position.x
                        val downY = down.position.y
                        val longPressJob = scope.launch {
                            delay(400)
                            triggered = true
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            onSwipeStateChange?.invoke(
                                SwipeState(
                                    isPressed = true,
                                    isLongPress = true,
                                    longPressItems = items,
                                    selectedLongPressIndex = 0,
                                    longPressDrawableIds = currentLongPressDrawableIds,
                                ),
                                buttonBounds,
                            )
                        }
                        val cancelThresholdPx = with(density) { 5.dp.toPx() }
                        var completed = false
                        try {
                            var lastReportedIdx = -1
                            while (!completed) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                // 抬手只认本指针（别的手指抬起、ACTION_CANCEL 都按结束处理）
                                val isRelease = change.changedToUpIgnoreConsumed()
                                if (!triggered) {
                                    // 5dp 只用于取消长按，不参与点击判定（该分支不检测滑动）
                                    if (!moved && (kotlin.math.abs(change.position.x - downX) > cancelThresholdPx ||
                                            kotlin.math.abs(change.position.y - downY) > cancelThresholdPx)
                                    ) {
                                        moved = true
                                        longPressJob.cancel()
                                    }
                                    if (isRelease) {
                                        completed = true
                                        if (currentOnTap != null) currentOnTap?.invoke()
                                        else currentOnKeyPress("space")
                                    }
                                    continue
                                }
                                val itemWidth = buttonBounds.width / items.size
                                selectedIdx = (((change.position.x - downX) / itemWidth) +
                                    if (items.size > 1) 0.5f else 0f)
                                    .toInt().coerceIn(0, items.size - 1)
                                if (!isRelease && selectedIdx != lastReportedIdx) {
                                    lastReportedIdx = selectedIdx
                                    onSwipeStateChange?.invoke(
                                        SwipeState(
                                            isPressed = true,
                                            isLongPress = true,
                                            longPressItems = items,
                                            selectedLongPressIndex = selectedIdx,
                                            longPressDrawableIds = currentLongPressDrawableIds,
                                        ),
                                        buttonBounds,
                                    )
                                }
                                change.consume()
                                if (isRelease) {
                                    completed = true
                                    items.getOrNull(selectedIdx)?.let { currentOnLongPressSelect?.invoke(it) }
                                }
                            }
                        } finally {
                            longPressJob.cancel()
                            onSwipeStateChange?.invoke(SwipeState(), buttonBounds)
                            // 没等到抬手就结束（触摸流取消/协程被重挂）时兜底上屏空格
                            if (!completed && !triggered) {
                                if (currentOnTap != null) currentOnTap?.invoke()
                                else currentOnKeyPress("space")
                            }
                        }
                        currentOnKeyRelease?.invoke("space")
                        return@awaitEachGesture
                    }

                    var longPressTriggered = false
                    val longPressJob = scope.launch {
                        delay(400)
                        longPressTriggered = true

                        if (currentOnLongPress != null) {
                            currentOnLongPress?.invoke()
                        } else if (isSttEnabled) {
                            // 语音转文本开关关闭时长按不触发语音
                            if (!PermissionHelper.hasRecordAudioPermission(context)) {
                                Toast.makeText(context, tr(R.string.s_xuyaomaikefengquanxiancainengs_3b6e4), Toast.LENGTH_SHORT).show()
                                PermissionHelper.requestRecordAudioPermission(context)
                            } else {
                                currentOnVoiceModeChange?.invoke(true)
                            }
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
            .background(keyBackgroundColor)
            .keySemantics(
                when {
                    voiceSticky -> tr(R.string.s_qingchujieshuyuyin)
                    isVoiceMode -> tr(R.string.s_yuyinshuru)
                    else -> tr(R.string.s_kongge)
                },
                null,
            ) {
                if (currentOnTap != null) currentOnTap?.invoke() else currentOnKeyPress("space")
            }
            .onGloballyPositioned { buttonBounds = it.boundsInRoot() },
        contentAlignment = Alignment.Center
    ) {
        when {
            voiceSticky -> {
                Text(
                    text = tr(R.string.s_qingchujieshuyuyin),
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
                    contentDescription = tr(R.string.s_yuyinshuru),
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
                        contentDescription = tr(R.string.s_yuyinshuru),
                        tint = keyTextColor.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp).align(Alignment.BottomStart).padding(start = 6.dp, bottom = 2.dp)
                    )
                } else {
                    Text(
                        text = tr(R.string.s_kongge),
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
