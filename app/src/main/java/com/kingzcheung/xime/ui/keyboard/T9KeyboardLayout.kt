package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.i18n.tr

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.settings.DisplayMode
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.R
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.util.PermissionHelper
import com.kingzcheung.xime.util.SubcharHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 拼音九键（T9）键盘布局。
 *
 * 布局说明（三列结构，由 xime.yaml keyboard.t9.layout 的 left/rows/right 驱动，
 * 未配置时为以下内置默认布局，与历史行为一致；键 id 分派见 KeysConfigHelper.T9_FUNCTION_KEY_IDS）：
 * - 左侧候选区：占用右侧第 1/4/7 三个数字键的垂直高度，显示当前音节候选拼音。
 * - 右侧主键区：
 *   第1行：分词(1) | ABC(2) | DEF(3) | 退格
 *   第2行：GHI(4) | JKL(5) | MNO(6) | 重输
 *   第3行：PQRS(7) | TUV(8) | WXYZ(9) | 0
 *   第4行：符 | 123 | 空格(显示方案名) | 中/En(地球图标) | 发送
 */
@Composable
fun T9KeyboardLayout(
    onKeyPress: (String) -> Unit,
    callbacks: KeyboardCallbacks,
    uiState: KeyboardUiState,
    t9Controller: T9InputController,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    accentColor: Color = Color(0xFF1A73E8),
    /** 左栏选中拼音胶囊背景色；Unspecified 时按 accentColor 20% 透明派生。 */
    selectedCandidateBgColor: Color = Color.Unspecified,
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
    candidateState: State<CandidateState> = remember { mutableStateOf(CandidateState()) },
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
) {
    val controller = t9Controller
    val configuration = LocalConfiguration.current
    val isLandscape = !isFloatingMode && configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun handleDelete() {
        // onDeleted 在后台队列中处理（flush 不阻塞 UI），结果通过回调返回（Main 线程）。
        controller.onDeleted { result ->
            when (result) {
                T9InputController.DeleteResult.UNDO_COMMIT -> {
                    controller.clearRimeAndResend()
                }

                T9InputController.DeleteResult.NOT_CONSUMED -> {
                    onKeyPress("delete")
                }

                T9InputController.DeleteResult.DELETED, T9InputController.DeleteResult.UNDO_CHOICE -> {
                }
            }
        }
    }

    T9KeyboardSwipeOverlay(
        modifier = modifier,
        keyboardBackgroundColor = keyboardBackgroundColor,
        keyCornerRadius = keyCornerRadius,
        keyTextColor = keyTextColor,
        isLandscape = isLandscape,
        isFloatingMode = isFloatingMode,
        onKeyPress = onKeyPress,
        callbacks = callbacks,
        uiState = uiState,
        controller = controller,
        keyBackgroundColor = keyBackgroundColor,
        specialKeyBackgroundColor = specialKeyBackgroundColor,
        bubbleBackgroundColor = bubbleBackgroundColor,
        accentColor = accentColor,
        selectedCandidateBgColor = selectedCandidateBgColor,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        onKeyPressDown = onKeyPressDown,
        onDelete = ::handleDelete,
        specialKeyTextColor = specialKeyTextColor,
        candidateState = candidateState,
        keySpacingX = keySpacingX,
        keySpacingY = keySpacingY,
        onGestureAction = onGestureAction,
    )
}


// ─── 九键左栏（候选/符号列）几何：键盘态与候选展开页共用同一公式 ────────

/** 九键三列之间的 `Arrangement.spacedBy`（共 2 个间隙）。 */
internal const val T9_COLUMN_GAP_DP = 2f

/**
 * 九键左栏是否显示：竖屏一直有；手机横屏键矮，左栏让位给顶部候选栏；
 * 平板横屏键盘区足够高，与竖屏同款（见 [T9KeyboardSwipeOverlay] 的 compactLandscape）。
 */
internal fun t9HasLeftRail(isLandscape: Boolean, isTablet: Boolean): Boolean = !isLandscape || isTablet

/**
 * 九键左栏列宽（dp）：与键盘态左栏同一条公式，供候选展开页复刻左栏
 * （展开/收起时左栏不横移）。
 *
 * 键位区基数 = 页宽 − 两侧外围留白（[keyboardSidePaddingDp]）− 2 个列间隙；
 * 左栏权重 0.8 / 三列总权重 5。
 */
internal fun t9LeftRailWidthDp(pageWidthDp: Float, isTablet: Boolean): Float {
    val base = pageWidthDp - 2 * keyboardSidePaddingDp(isTablet) - 2 * T9_COLUMN_GAP_DP
    return (base * 0.8f / 5f).coerceAtLeast(32f)
}

// ─── 滑动气泡覆盖层（隔离 swipeState 作用域，避免全键盘重组） ────────

@Composable
private fun T9KeyboardSwipeOverlay(
    modifier: Modifier,
    keyboardBackgroundColor: Color,
    keyCornerRadius: Dp,
    keyTextColor: Color,
    isLandscape: Boolean,
    isFloatingMode: Boolean,
    onKeyPress: (String) -> Unit,
    callbacks: KeyboardCallbacks,
    uiState: KeyboardUiState,
    controller: T9InputController,
    keyBackgroundColor: Color,
    specialKeyBackgroundColor: Color,
    bubbleBackgroundColor: Color = keyBackgroundColor,
    accentColor: Color,
    selectedCandidateBgColor: Color = Color.Unspecified,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    onKeyPressDown: ((String) -> Unit)?,
    onDelete: () -> Unit,
    specialKeyTextColor: Color = Color.White,
    candidateState: State<CandidateState> = remember { mutableStateOf(CandidateState()) },
    keySpacingX: Dp? = null,
    keySpacingY: Dp? = null,
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
) {
    val swipeBubble = rememberSwipeBubbleController()
    var keyboardBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    // 设备档：平板键盘区高度约为手机的 1.6 倍（键盘高 = 屏高 × 比例），所以
    // ① 横屏紧凑档（去掉左栏 + 收小字号）只在手机横屏生效，平板横屏用竖屏同款布局；
    // ② 外围留白/提示档按设备档（`panelOuterPadding` 与 26 键、面板共用同一条）。
    val tablet = isTablet()
    val compactLandscape = isLandscape && !tablet

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

    // 外围留白与设备档（面板/26 键同源）：手机竖屏 4/4/8、手机横屏 50/2、平板 16/16/8
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
            .padding(bottom = if (isFloatingMode || isLandscape) 0.dp else 0.dp)) {
        if (compactLandscape) {
            // 手机横屏：去除原左侧候选大面板（候选已在顶部候选栏展示），
            // 九键布局撑满键盘区域（与全键盘横屏同款 50dp 边距）
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
                    T9KeyboardContent(
                        onKeyPress = onKeyPress,
                        callbacks = callbacks,
                        uiState = uiState,
                        controller = controller,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBackgroundColor,
                        accentColor = accentColor,
                        selectedCandidateBgColor = selectedCandidateBgColor,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        onKeyPressDown = onKeyPressDown,
                        onSwipeStateChange = ::processSwipeState,
                        onDelete = onDelete,
                        compactMode = true,
                        candidateState = candidateState,
                        onGestureAction = onGestureAction,
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(outerPadding),
                ) {
                    T9KeyboardContent(
                        onKeyPress = onKeyPress,
                        callbacks = callbacks,
                        uiState = uiState,
                        controller = controller,
                        keyBackgroundColor = keyBackgroundColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBackgroundColor,
                        accentColor = accentColor,
                        selectedCandidateBgColor = selectedCandidateBgColor,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                        onKeyPressDown = onKeyPressDown,
                        onSwipeStateChange = ::processSwipeState,
                        onDelete = onDelete,
                        compactMode = false,
                        candidateState = candidateState,
                        onGestureAction = onGestureAction,
                    )
                }
            }
        }
    }
    }
}

// ─── 九键键盘三列主体（竖屏整宽 / 横屏右栏复用） ──────────────────────

@SuppressLint("SuspiciousIndentation")
@Composable
private fun T9KeyboardContent(
    onKeyPress: (String) -> Unit,
    callbacks: KeyboardCallbacks,
    uiState: KeyboardUiState,
    controller: T9InputController,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    accentColor: Color,
    selectedCandidateBgColor: Color = Color.Unspecified,
    shadowEnabled: Boolean,
    shadowElevation: Dp,
    shadowShapeRadius: Dp,
    onKeyPressDown: ((String) -> Unit)?,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)?,
    onDelete: () -> Unit,
    compactMode: Boolean = false,
    candidateState: State<CandidateState> = remember { mutableStateOf(CandidateState()) },
    onGestureAction: ((GestureAction, String) -> Unit)? = null,
) {
    val t9DigitFontSize = if (compactMode) 13.sp else 16.sp
    val ctrlFontSize = if (compactMode) 11.sp else androidx.compose.ui.unit.TextUnit.Unspecified
    val candidateFontSize = if (compactMode) 11.sp else 13.sp
    val specialKeyTextColor = KeyboardThemes.getSpecialKeyTextColorForBackground(
        specialKeyBackgroundColor, keyTextColor
    )

    // 数字键滑动手势（keyboard.t9.keys，热重载经 configVersion 感知）：
    // 上滑默认直接上屏数字（T9 模式 onKeyPress(数字) 会进拼音数字码组合，须走 onCommitText），
    // 下滑默认绑定快捷编辑动作。提示恒显，是否绘制由按键配置的 display/bubble 决定；
    // 手势触发只看回调绑定，与提示无关。上滑键面提示尊重 display: bubble（仅气泡不印键面）。
    val configVersion by KeysConfigHelper.configVersion.collectAsState()
    val hintsActive = !compactMode
    val commitDirect: (String) -> Unit =
        { text -> callbacks.onCommitText?.invoke(text) ?: onKeyPress(text) }

    fun swipesFor(id: String): T9KeySwipes {
        val gesture = KeysConfigHelper.getT9KeyGesture(id) ?: return T9KeySwipes()
        val upHint = gesture.swipeUp?.let { it.label.ifEmpty { it.value } }
        val downHint = gesture.swipeDown?.let { it.label.ifEmpty { it.value } }
        val leftHint = gesture.swipeLeft?.let { it.label.ifEmpty { it.value } }
        val rightHint = gesture.swipeRight?.let { it.label.ifEmpty { it.value } }
        // display 只管静态键面提示位置（bubble 不画键面，用空串压制回退）；
        // 运行时气泡由 bubble 独立控制。手势回调与二者无关。
        val swipeUpKeyLabel = when {
            !hintsActive -> null
            gesture.swipeUp?.display == DisplayMode.BUBBLE -> ""
            else -> upHint
        }
        val swipeDownKeyLabel = when {
            !hintsActive -> null
            gesture.swipeDown?.display == DisplayMode.BUBBLE -> ""
            else -> downHint
        }
        val swipeLeftKeyLabel = when {
            !hintsActive -> null
            gesture.swipeLeft?.display == DisplayMode.BUBBLE -> ""
            else -> leftHint
        }
        val swipeRightKeyLabel = when {
            !hintsActive -> null
            gesture.swipeRight?.display == DisplayMode.BUBBLE -> ""
            else -> rightHint
        }
        return T9KeySwipes(
            onSwipeUp = swipeHandlerFor(gesture.swipeUp, commitDirect, onGestureAction),
            onSwipeDown = swipeHandlerFor(gesture.swipeDown, commitDirect, onGestureAction),
            onSwipeLeft = swipeHandlerFor(gesture.swipeLeft, commitDirect, onGestureAction),
            onSwipeRight = swipeHandlerFor(gesture.swipeRight, commitDirect, onGestureAction),
            swipeUpText = if (hintsActive && (gesture.swipeUp?.bubble ?: true)) upHint else null,
            swipeDownText = if (hintsActive && (gesture.swipeDown?.bubble ?: true)) downHint else null,
            swipeUpKeyLabel = swipeUpKeyLabel,
            swipeDownKeyLabel = swipeDownKeyLabel,
            swipeLeftKeyLabel = swipeLeftKeyLabel,
            swipeRightKeyLabel = swipeRightKeyLabel,
        )
    }

    val density = LocalDensity.current
    val candidateShadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, keyBackgroundColor) {
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

    // 布局由 xime.yaml keyboard.t9.layout 驱动（configVersion 感知热重载）；
    // 未配置时为内置默认，与历史硬编码布局一致（三列：左列/主区/右列）。
    val t9Layout = remember(configVersion) { KeysConfigHelper.getT9Layout() }

    /** 布局项相对权重：keys.<id>.width 优先，否则功能键默认（候选面板 3、空格 1.8、回车 2，其余 1）。 */
    fun keyWeight(id: String): Float {
        KeysConfigHelper.getT9KeyGesture(id)?.width?.takeIf { it > 0f }?.let { return it }
        return when (id) {
            "candidates" -> 3f
            "space" -> 1.8f
            "enter" -> 2f
            else -> 1f
        }
    }

    /** 数字键键面字母：keys.<id>.tap.label 可覆盖，未配置回退内置默认（无默认时显示数字本身）。 */
    fun digitLetters(id: String): String =
        KeysConfigHelper.getT9KeyLabel(id) ?: defaultT9DigitLetters()[id] ?: id

    /** 数字键长按候选：keys.<id>.long_press 可覆盖（显示 label 缺省 value），未配置回退内置默认。 */
    fun digitLongPress(id: String): List<String>? =
        KeysConfigHelper.getT9KeyLongPressLabels(id) ?: DEFAULT_T9_DIGIT_LONG_PRESS[id]

    /** 左侧拼音候选面板（candidates 占位）：候选态显示音节选择，空闲态显示 side_symbols。 */
    @Composable
    fun CandidatesPanel(modifier: Modifier) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(LocalKeyVisualPadding.current)
                .then(candidateShadowModifier)
                .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
                .background(keyBackgroundColor)
        ) {
            val showCandidates = controller.leftPanelState != T9InputController.LeftPanelState.IDLE
            val currentFirstOptions = controller.firstOptions
            // 空闲态符号列表来自 xime.yaml keyboard.t9.side_symbols（可自定义，>4 滚动）
            val configVersion by KeysConfigHelper.configVersion.collectAsState()
            val t9SideSymbols = remember(configVersion) { KeysConfigHelper.getT9SideSymbols() }

            @Composable
            fun CandidateRow(option: T9InputController.SyllableOption, rowModifier: Modifier) {
                val isSelected = controller.leftPanelState == T9InputController.LeftPanelState.SELECTION &&
                        controller.selectedOption == option &&
                        controller.isSelectedOptionInCurrentCandidates()
                CandidateItem(
                    text = option.pinyin,
                    onClick = { controller.onChoiceSelected(option) },
                    onPress = { onKeyPressDown?.invoke(option.pinyin) },
                    textColor = keyTextColor,
                    backgroundColor = keyBackgroundColor,
                    accentColor = accentColor,
                    selectedBackgroundColor = selectedCandidateBgColor,
                    fontSize = candidateFontSize,
                    isSelected = isSelected,
                    modifier = rowModifier
                )
            }

            @Composable
            fun SymbolRow(item: String, rowModifier: Modifier) {
                CandidateItem(
                    text = item,
                    onClick = { onKeyPress(item) },
                    onPress = { onKeyPressDown?.invoke(item) },
                    textColor = keyTextColor,
                    backgroundColor = keyBackgroundColor,
                    accentColor = accentColor,
                    selectedBackgroundColor = selectedCandidateBgColor,
                    fontSize = candidateFontSize,
                    isSelected = false,
                    modifier = rowModifier
                )
            }

            if (showCandidates) {
                if (currentFirstOptions.size <= 4) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        currentFirstOptions.forEach { option ->
                            CandidateRow(option, Modifier.fillMaxWidth().weight(1f))
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        itemsIndexed(currentFirstOptions) { _, option ->
                            CandidateRow(
                                option,
                                Modifier.fillMaxWidth().height(if (compactMode) 26.dp else 32.dp)
                            )
                        }
                    }
                }
            } else {
                // 符号栏固定 4 等分（与数字键盘左侧键柱观感一致），不随数量压缩；
                // 超过 4 个时保持 4 等分高度上下滚动（issue #1131）。
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val slotHeight = maxHeight / 4
                    if (t9SideSymbols.size <= 4) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            t9SideSymbols.forEach { item ->
                                SymbolRow(item, Modifier.fillMaxWidth().height(slotHeight))
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            itemsIndexed(t9SideSymbols) { _, item ->
                                SymbolRow(item, Modifier.fillMaxWidth().height(slotHeight))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 九键数字输入键：点按默认走九键数字输入（`keys.<id>.tap.action` 可改绑）；键面字母/长按候选/手势可配。
     */
    @Composable
    fun DigitKey(digit: String, modifier: Modifier) {
        val binding = KeysConfigHelper.getT9KeyGesture(digit)
        val tap = binding?.tap
        // 只有显式配置了动作才改绑点按，否则保持内置的九键数字输入（维护 T9 组合/候选语义）。
        // 注意不能用 `tap.action != null` 判断：label-only 的内置配置会被解析器兜底为
        // SEND_RIME（value 空），那种情况仍须走 onDigitPressed。
        val tapRebinds = t9DigitTapRebinds(tap)
        val onDigitClick: () -> Unit = {
            if (tapRebinds) {
                invokeKeyAction(tap, onKeyPress, callbacks.onCommitText, onGestureAction)
            } else {
                controller.onDigitPressed(digit)
            }
        }
        val longPressConfig = binding?.longPress
        val longPressActionMap = longPressConfig?.let { KeysConfigHelper.longPressActionMap(it) }
        val longPress = longPressConfig?.let { KeysConfigHelper.longPressDisplayItems(it) }
            ?: digitLongPress(digit)
        if (longPress.isNullOrEmpty()) {
            // 无长按候选（默认 "1" 分词键）：无长按弹出、按下态不进滑动气泡层（历史路径）
            NineKeyButton(
                digit = digit,
                letters = digitLetters(digit),
                onClick = onDigitClick,
                backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                modifier = modifier,
                onPress = { onKeyPressDown?.invoke(digit) },
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                fontSize = t9DigitFontSize,
                swipes = swipesFor(digit),
            )
        } else {
            T9DigitKey(
                swipes = swipesFor(digit),
                digit = digit, letters = digitLetters(digit), longPressItems = longPress,
                onClick = onDigitClick,
                onLongPressSelect = { selected ->
                    // 与 26 键同语义：配置了动作就按动作分派；未配置/无动作仍走内置「清空 + 上屏所选」
                    val gesture = longPressActionMap?.get(selected)
                    controller.clearAll()
                    if (gesture != null && gesture.action != null) {
                        invokeKeyAction(gesture, onKeyPress, callbacks.onCommitText, onGestureAction)
                    } else {
                        onKeyPress(gesture?.value?.takeIf { it.isNotEmpty() } ?: selected)
                    }
                },
                onSwipeStateChange = { state, bounds ->
                    if (!(state.isPressed && !state.isLongPress && state.pressedText != null))
                        onSwipeStateChange?.invoke(state, bounds)
                },
                backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                modifier = modifier,
                onPress = { onKeyPressDown?.invoke(digit) },
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                fontSize = t9DigitFontSize,
            )
        }
    }

    /** 自定义键：数字 id 走九键输入键；其余按 keys.<id> 行为渲染（tap 定义键面与动作，手势可配；无配置提交键名本身）。 */
    @Composable
    fun CustomKey(id: String, modifier: Modifier) {
        if (id.length == 1 && id[0] in '0'..'9') {
            DigitKey(id, modifier)
            return
        }
        val binding = KeysConfigHelper.getT9KeyGesture(id)
        val tap = binding?.tap
        val configLabel = tap?.label?.takeIf { it.isNotEmpty() }
        // 键名兜底显示：含小写字母时显示大写键名（与全键盘键名兜底一致）
        val label = configLabel ?: id.takeIf { it.any { ch -> ch in 'a'..'z' } }?.uppercase() ?: id
        val swipes = swipesFor(id)
        // 长按候选：与 26 键同一套解析（label 回退 value；非 commit 动作走统一分派）
        val longPressConfig = binding?.longPress
        val longPressItems = longPressConfig?.let { KeysConfigHelper.longPressDisplayItems(it) }
        val longPressActionMap = longPressConfig?.let { KeysConfigHelper.longPressActionMap(it) }
        SwipeableKeyButton(
            text = label,
            // tap.bubble: false → 不弹按压气泡（默认 true，行为与改动前一致）
            pressText = label.takeIf { tap?.bubble ?: true },
            onClick = {
                if (tap != null && tap.action != null) {
                    invokeKeyAction(tap, onKeyPress, callbacks.onCommitText, onGestureAction)
                } else {
                    onKeyPress(id)
                }
            },
            backgroundColor = keyBackgroundColor,
            textColor = keyTextColor,
            modifier = modifier,
            onPress = { onKeyPressDown?.invoke(id) },
            swipeText = swipes.swipeUpText,
            swipeDownText = swipes.swipeDownText,
            swipeUpKeyLabel = swipes.swipeUpKeyLabel,
            swipeDownKeyLabel = swipes.swipeDownKeyLabel,
            swipeLeftKeyLabel = swipes.swipeLeftKeyLabel,
            swipeRightKeyLabel = swipes.swipeRightKeyLabel,
            onSwipe = swipes.onSwipeUp?.let { handler -> { _: String -> handler() } },
            onSwipeDown = swipes.onSwipeDown?.let { handler -> { _: String -> handler() } },
            onSwipeLeft = swipes.onSwipeLeft,
            onSwipeRight = swipes.onSwipeRight,
            onLongPressSelect = { selected ->
                dispatchLongPressSelection(selected, longPressActionMap, onKeyPress, callbacks.onCommitText, onGestureAction)
            },
            longPressItems = longPressItems,
            shadowEnabled = shadowEnabled,
            shadowElevation = shadowElevation,
            shadowShapeRadius = shadowShapeRadius,
        )
    }

    /** 布局键位渲染分发：功能键走内置组件（行为内置），数字走九键输入键，其余为自定义键。 */
    @Composable
    fun KeyCell(id: String, modifier: Modifier) {
        when (id) {
            "candidates" -> CandidatesPanel(modifier)
            "symbol" -> KeyButton(
                text = stringResource(R.string.keyboard_symbol),
                onClick = { onKeyPress("symbol") },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = modifier,
                onPress = { onKeyPressDown?.invoke("symbol") },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                fontSize = ctrlFontSize,
            )
            "number" -> KeyButton(
                text = "123",
                onClick = { onKeyPress("number") },
                backgroundColor = keyBackgroundColor,
                textColor = keyTextColor,
                modifier = modifier,
                onPress = { onKeyPressDown?.invoke("mode_change") },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
            "space" -> {
                // 空格上滑（keyboard.t9.keys.space.swipe_up，内置配置为直接输入 "0"）：
                // commitDirect 直接上屏（与数字键上滑同路径，不经 T9 数字组合）
                val spaceSwipe = KeysConfigHelper.getT9KeyGesture("space")?.swipeUp
                T9SpaceKey(
                    schemaName = uiState.schemaName, isSttEnabled = uiState.isSttEnabled,
                    voiceSticky = uiState.voiceSticky,
                    onKeyPress = onKeyPress, onKeyPressDown = onKeyPressDown,
                    onVoiceModeChange = callbacks.onVoiceModeChange,
                    backgroundColor = keyBackgroundColor, textColor = keyTextColor,
                    modifier = modifier,
                    shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
                    onSwipeUp = swipeHandlerFor(spaceSwipe, commitDirect, callbacks.onGestureAction),
                    swipeUpBadge = if (hintsActive && spaceSwipe?.display != DisplayMode.BUBBLE)
                        spaceSwipe?.label?.ifEmpty { spaceSwipe?.value } else null,
                )
            }
            "earth" -> IconKeyButton(
                icon = rememberVectorPainter(Icons.Default.Language),
                onClick = { onKeyPress("ime_switch") },
                backgroundColor = keyBackgroundColor, iconColor = keyTextColor,
                modifier = modifier,
                a11yDescription = tr(R.string.s_zhongyingqiehuan),
                onPress = { onKeyPressDown?.invoke("ime_switch") },
                shadowEnabled = shadowEnabled, shadowElevation = shadowElevation, shadowShapeRadius = shadowShapeRadius,
            )
            "delete" -> SwipeableIconKeyButton(
                icon = rememberVectorPainter(Icons.AutoMirrored.Filled.Backspace),
                onClick = { onDelete() },
                onLongClick = { onDelete() },
                backgroundColor = specialKeyBackgroundColor,
                iconColor = specialKeyTextColor,
                modifier = modifier,
                a11yDescription = stringResource(R.string.keyboard_delete),
                swipeText = if (compactMode) null else stringResource(R.string.keyboard_clear),
                onSwipe = { onKeyPress("clear_composition") },
                onPress = { onKeyPressDown?.invoke("delete") },
                swipeUpLabel = if (compactMode) null else tr(R.string.s_shanghuaqingkong),
                swipeDownLabel = if (compactMode) null else tr(R.string.s_xiahuachehui),
                onSwipeUp = {
                    controller.clearAll()
                    onKeyPress("clear_all")
                },
                onSwipeDown = { onKeyPress("undo_clear") },
                onSwipeLeft = { onKeyPress("clear_composition") },
                onSwipeStateChange = { state, bounds ->
                    onSwipeStateChange?.invoke(state, bounds)
                },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
            "clear" -> ResetKey(
                onClick = {
                    controller.clearAll()
                    // 重输 = 只清输入态（预编辑/候选/左栏），不动已上屏文本（对标主流输入法 2026-08-11）：
                    //   · 输入态点重输 → 清除输入态内容
                    //   · 空闲态点重输 → 无反应（clear_composition 无可清内容）
                    onKeyPress("clear_composition")
                },
                onPress = { onKeyPressDown?.invoke("clear") },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = modifier,
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                compactMode = compactMode,
            )
            "enter" -> KeyButton(
                text = uiState.enterKeyText,
                onClick = { onKeyPress("enter") },
                backgroundColor = specialKeyBackgroundColor,
                textColor = specialKeyTextColor,
                modifier = modifier,
                onPress = { onKeyPressDown?.invoke("enter") },
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
            )
            else -> CustomKey(id, modifier)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(T9_COLUMN_GAP_DP.dp)
    ) {
        // ── 第1列：左列（keyboard.t9.layout.left，candidates 为候选/符号面板占位） ──
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.8f),
            verticalArrangement = Arrangement.spacedBy(if (compactMode) 2.dp else 4.dp)
        ) {
            t9Layout.left.forEach { id ->
                KeyCell(id, Modifier.weight(keyWeight(id)))
            }
        }

        // ── 第2列：主区（keyboard.t9.layout.rows，每行一个键 id 列表） ──
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(3.4f),
        ) {
            t9Layout.rows.forEach { rowIds ->
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    rowIds.forEach { id ->
                        KeyCell(id, Modifier.weight(keyWeight(id)))
                    }
                }
            }
        }

        // ── 第3列：右列（keyboard.t9.layout.right，功能键列） ──
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.8f),
        ) {
            t9Layout.right.forEach { id ->
                KeyCell(id, Modifier.weight(keyWeight(id)))
            }
        }
    }
}

// ─── 九键数字键默认键面（keyboard.t9.keys.<id> 可覆盖） ─────────────────

/** 九键数字键内置键面字母（与历史硬编码一致；keys.<id>.tap.label 可覆盖）。 */
private fun defaultT9DigitLetters(): Map<String, String> = mapOf(
    "1" to tr(R.string.s_fenci), "2" to "ABC", "3" to "DEF",
    "4" to "GHI", "5" to "JKL", "6" to "MNO",
    "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
)

/** 九键数字键内置长按候选字母（keys.<id>.long_press 可覆盖；"1" 无长按为历史行为）。 */
private val DEFAULT_T9_DIGIT_LONG_PRESS: Map<String, List<String>> = mapOf(
    "2" to listOf("A", "B", "C"),
    "3" to listOf("D", "E", "F"),
    "4" to listOf("G", "H", "I"),
    "5" to listOf("J", "K", "L"),
    "6" to listOf("M", "N", "O"),
    "7" to listOf("P", "Q", "R", "S"),
    "8" to listOf("T", "U", "V"),
    "9" to listOf("W", "X", "Y", "Z"),
)

// ─── 九键数字键（可长按） ──────────────────────────────────────────────

/** 九键数字键的滑动配置：回调 + 提示文本（均受 keyboard.t9.keys 配置与提示开关控制）。 */
private data class T9KeySwipes(
    val onSwipeUp: (() -> Unit)? = null,
    val onSwipeDown: (() -> Unit)? = null,
    /** 左滑动作（keyboard.t9.keys.<id>.swipe_left，未配置为 null：横向滑动仍移动光标） */
    val onSwipeLeft: (() -> Unit)? = null,
    /** 右滑动作（keyboard.t9.keys.<id>.swipe_right，未配置为 null：横向滑动仍移动光标） */
    val onSwipeRight: (() -> Unit)? = null,
    /** 上滑滑动气泡文本（display: key 时不传） */
    val swipeUpText: String? = null,
    /** 下滑滑动气泡文本（display: key 时不传） */
    val swipeDownText: String? = null,
    /** 上滑键面提示（空串 = 显式不印键面，bubble 模式用；null = 不显示） */
    val swipeUpKeyLabel: String? = null,
    val swipeDownKeyLabel: String? = null,
    /** 左滑键面提示（空串 = 显式不印键面，bubble 模式用；null = 不显示） */
    val swipeLeftKeyLabel: String? = null,
    /** 右滑键面提示（空串 = 显式不印键面，bubble 模式用；null = 不显示） */
    val swipeRightKeyLabel: String? = null,
)

/**
 * 九键数字键，复用 [SwipeableKeyButton] 的长按弹出逻辑。
 * - 主体显示字母（ABC），右上角叠加数字浮标。
 * - 点按走 [onClick]，长按走 [onLongPressSelect]，上/下滑走 [onSwipeUp]/[onSwipeDown]。
 */
@Composable
private fun T9DigitKey(
    digit: String,
    letters: String,
    longPressItems: List<String>,
    onClick: () -> Unit,
    onLongPressSelect: ((String) -> Unit)?,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    swipes: T9KeySwipes = T9KeySwipes(),
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPressSelect by rememberUpdatedState(onLongPressSelect)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentSwipes by rememberUpdatedState(swipes)

    SwipeableKeyButton(
        text = letters,
        onClick = { currentOnClick() },
        backgroundColor = backgroundColor,
        textColor = textColor,
        fontSize = fontSize,
        modifier = modifier,
        onPress = { currentOnPress?.invoke() },
        longPressItems = longPressItems,
        onLongPressSelect = { letter -> currentOnLongPressSelect?.invoke(letter) },
        onSwipeStateChange = onSwipeStateChange,
        badgeText = digit,
        a11yDescription = tr(R.string.s_shuzi_zimu, digit, letters),
        swipeText = currentSwipes.swipeUpText,
        swipeDownText = currentSwipes.swipeDownText,
        swipeUpKeyLabel = currentSwipes.swipeUpKeyLabel,
        swipeDownKeyLabel = currentSwipes.swipeDownKeyLabel,
        swipeLeftKeyLabel = currentSwipes.swipeLeftKeyLabel,
        swipeRightKeyLabel = currentSwipes.swipeRightKeyLabel,
        onSwipe = currentSwipes.onSwipeUp?.let { handler -> { _: String -> handler() } },
        onSwipeDown = currentSwipes.onSwipeDown?.let { handler -> { _: String -> handler() } },
        onSwipeLeft = currentSwipes.onSwipeLeft,
        onSwipeRight = currentSwipes.onSwipeRight,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
    )
}

// ─── 子组件 ───────────────────────────────────────────────────────────

@Composable
private fun CandidateItem(
    text: String,
    onClick: () -> Unit,
    onPress: (() -> Unit)?,
    textColor: Color,
    backgroundColor: Color = Color.Transparent,
    accentColor: Color = Color(0xFF1A73E8),
    selectedBackgroundColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    isSelected: Boolean = false,
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnPress by rememberUpdatedState(onPress)
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
        if (isSelected) {
            val selectedBg = if (selectedBackgroundColor == Color.Unspecified) {
                accentColor.copy(alpha = 0.2f)
            } else {
                selectedBackgroundColor
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(selectedBg)
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            ) {
                Text(
                    text = text,
                    color = accentColor,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    fontFamily = AppFonts.candidateFontFamily
                )
            }
        } else {
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
}

/** 数字 1 分词键，与 [T9DigitKey] 统一使用 [SwipeableKeyButton] 保持角标位置一致 */
@Composable
private fun NineKeyButton(
    digit: String,
    letters: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPress: (() -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    swipes: T9KeySwipes = T9KeySwipes(),
) {
    SwipeableKeyButton(
        text = letters,
        onClick = onClick,
        backgroundColor = backgroundColor,
        textColor = textColor,
        modifier = modifier,
        onPress = onPress,
        badgeText = digit,
        a11yDescription = tr(R.string.s_shuzi_zimu, digit, letters),
        swipeText = swipes.swipeUpText,
        swipeDownText = swipes.swipeDownText,
        swipeUpKeyLabel = swipes.swipeUpKeyLabel,
        swipeDownKeyLabel = swipes.swipeDownKeyLabel,
        swipeLeftKeyLabel = swipes.swipeLeftKeyLabel,
        swipeRightKeyLabel = swipes.swipeRightKeyLabel,
        onSwipe = swipes.onSwipeUp?.let { handler -> { _: String -> handler() } },
        onSwipeDown = swipes.onSwipeDown?.let { handler -> { _: String -> handler() } },
        onSwipeLeft = swipes.onSwipeLeft,
        onSwipeRight = swipes.onSwipeRight,
        shadowEnabled = shadowEnabled,
        shadowElevation = shadowElevation,
        shadowShapeRadius = shadowShapeRadius,
        fontSize = fontSize,
    )
}

/** 重输键 —— 文本在上、图标在下，参考功能键风格 */
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .then(shadowModifier)
            .clip(shape)
            .background(if (isPressed) backgroundColor.copy(alpha = 0.7f) else backgroundColor)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    isPressed = true
                    currentOnPress?.invoke()
                    tryAwaitRelease()
                    isPressed = false
                }, onTap = { currentOnClick() })
            }, contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = tr(R.string.s_zhongshu),
            tint = textColor,
            modifier = Modifier.size(if (compactMode) 16.dp else 20.dp)
        )

        if (!compactMode) {
            Text(
                text = tr(R.string.s_zhongshu),
                color = textColor.copy(alpha = 0.5f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.offset(y = (-14).dp)
            )
        }
    }
}

/** T9 空格键 */
@Composable
private fun T9SpaceKey(
    schemaName: String,
    isSttEnabled: Boolean,
    voiceSticky: Boolean = false,
    onKeyPress: (String) -> Unit,
    onKeyPressDown: ((String) -> Unit)?,
    onVoiceModeChange: ((Boolean) -> Unit)?,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    /** 上滑动作（keyboard.t9.keys.space.swipe_up，无配置为 null 不响应滑动）。 */
    onSwipeUp: (() -> Unit)? = null,
    /** 上滑键面角标（display: key 时显示，通常为滑动目标字符如 "0"）。 */
    swipeUpBadge: String? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val currentOnKeyPress by rememberUpdatedState(onKeyPress)
    val currentOnVoiceModeChange by rememberUpdatedState(onVoiceModeChange)
    val currentOnSwipeUp by rememberUpdatedState(onSwipeUp)
    val density = LocalDensity.current
    val spaceShadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
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

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .pointerInput(voiceSticky, isSttEnabled) {
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
                        // 语音转文本开关关闭时长按不触发语音
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
                // 语音常驻态（轻触结束语音）不响应上滑。仅在已触发后消费事件，不干扰横向光标手势。
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
            }, contentAlignment = Alignment.Center
    ) {
        // 背景层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(spaceShadowModifier)
                .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
                .background(backgroundColor)
        )
        if (voiceSticky) {
            Text(
                text = tr(R.string.s_qingchujieshuyuyin),
                color = textColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp)
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
                modifier = Modifier.padding(horizontal = 8.dp)
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
                    text = stringResource(R.string.keyboard_space),
                    color = textColor.copy(alpha = 0.3f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = 2.dp)
                )
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
}
