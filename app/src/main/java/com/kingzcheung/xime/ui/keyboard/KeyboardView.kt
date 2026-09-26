package com.kingzcheung.xime.ui.keyboard

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kingzcheung.xime.handwriting.HandwritingCandidate
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.keyboard.MainType
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.PanelType
import com.kingzcheung.xime.keyboard.ToolbarAction
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import com.kingzcheung.xime.keyboard.resolveToolbarButtonItem
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.service.ExpandedCandidatePager
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.menubar.ClipboardView
import com.kingzcheung.xime.ui.menubar.SchemaListView
import com.kingzcheung.xime.ui.menubar.ToolbarCustomizeView
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.keyboardBackground
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.util.PermissionHelper
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

val LocalStretchFactor = compositionLocalOf { 1f }
val LocalSuppressCursorMove = compositionLocalOf { mutableStateOf(false) }

@Composable
fun KeyboardView(
    viewModel: KeyboardViewModel,
    state: KeyboardUiState,
    callbacks: KeyboardCallbacks,
    modifier: Modifier = Modifier,
    inlineSuggestions: List<*> = listOf<Any>(),
    onCardPositioned: (left: Int, top: Int, right: Int, bottom: Int) -> Unit = { _: Int, _: Int, _: Int, _: Int -> },
    candidateState: State<CandidateState> = remember { mutableStateOf(CandidateState()) },
    voiceAmplitudeState: State<Float> = remember { mutableFloatStateOf(0f) },
    voiceSpectrumState: State<FloatArray> = remember { mutableStateOf(FloatArray(16)) },
    /**
     * 非按键交互（符号/表情面板、菜单栏、候选栏按钮等）的振动钩子。
     * 按键本身的反馈走 onKeyPressDown 回调（服务层 FeedbackManager），
     * 此处只覆盖没有经过按键回调链路的点击交互，宿主按按键振动同款语义实现。
     */
    onHapticFeedback: (() -> Unit)? = null,
) {
    val isShifted by viewModel.isShifted.collectAsStateWithLifecycle()
    val keyboardState by viewModel.keyboardState.collectAsStateWithLifecycle()
    val page by viewModel.page.collectAsStateWithLifecycle()
    val candidatePageExpanded by viewModel.candidatePageExpanded.collectAsStateWithLifecycle()
    val singleCharFilter by viewModel.singleCharFilter.collectAsStateWithLifecycle()

    // 候选展开页自动收起：编码删空（无候选也无联想）时不留空页。
    // 展开态是候选栏的在位扩展（非 Overlay），删除实时更新页内候选，删空即回到键盘。
    LaunchedEffect(
        candidatePageExpanded,
        candidateState.value.candidates.size,
        candidateState.value.associationCandidates.size
    ) {
        if (candidatePageExpanded &&
            candidateState.value.candidates.isEmpty() &&
            candidateState.value.associationCandidates.isEmpty()
        ) {
            viewModel.setCandidatePageExpanded(false)
        }
    }
    val viewState by viewModel.viewState.collectAsStateWithLifecycle()
    val isLandscape = if (state.isFloatingMode) false
        else LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    SideEffect {
        val isHandwriting = page is KeyboardPage.Main && (page as KeyboardPage.Main).type == MainType.HANDWRITING
        val active = isHandwriting || (
            (keyboardState is KeyboardLayoutState.Chinese || keyboardState is KeyboardLayoutState.Stroke || keyboardState is KeyboardLayoutState.T9Pinyin)
            && page is KeyboardPage.Main && (page as KeyboardPage.Main).type == MainType.FULL
        )
        callbacks.onKeyboardModeChange?.invoke(active)
    }

    val t9Controller = remember {
        T9InputController(
            onCompositionRefresh = { composition, snapshots ->
                callbacks.onT9RefreshComposition?.invoke(composition, snapshots)
            },
            onRightCommitUndone = callbacks.onT9RightCommitUndone,
            candidateTransform = callbacks.onTCandidateTransform,
        )
    }

    LaunchedEffect(state.inputSessionId) {
        t9Controller.reset()
        FileLogger.i("XimeKeyboard", "InputSessionStarted: isAsciiMode=${state.isAsciiMode}, schemaId=${state.currentSchemaId}, kb=$keyboardState, vs=$viewState, page=$page")
        viewModel.asciiStateMachine.reset()
        viewModel.dispatch(
            KeyboardDispatchAction.InputSessionStarted(state.isAsciiMode, state.currentSchemaId)
        )
    }

    LaunchedEffect(state.isAsciiMode, state.currentSchemaId) {
        FileLogger.i("XimeKeyboard", "AsciiModeChanged: isAsciiMode=${state.isAsciiMode}, schemaId=${state.currentSchemaId}, kb=$keyboardState, vs=$viewState, page=$page")
        viewModel.dispatch(
            KeyboardDispatchAction.AsciiModeChanged(state.isAsciiMode, state.currentSchemaId)
        )
    }

    // 键盘 ascii 同步点（attach/detach）：键盘上下文切换时，
    // 先保存离开键盘的记忆，再按进入键盘的记忆同步引擎。
    var lastAsciiContext by remember { mutableStateOf<AsciiKeyboardContext?>(null) }
    LaunchedEffect(viewState) {
        val prevContext = lastAsciiContext
        val curContext = viewState.asciiContext()
        lastAsciiContext = curContext
        if (prevContext == null || prevContext == curContext) return@LaunchedEffect
        viewModel.asciiStateMachine.saveMemory(prevContext, state.isAsciiMode)
        val target = viewModel.asciiStateMachine.targetFor(curContext, state.isAsciiMode)
        if (target != null) {
            FileLogger.i("XimeKeyboard", "ascii sync: ${prevContext.name}(${state.isAsciiMode}) -> ${curContext.name}($target)")
            // 面板上下文同步走 ime_switch_panel（PANEL_SYNC）：切引擎但不写 user.yaml，
            // 临时态不污染用户显式中英选择
            callbacks.onKeyPress("ime_switch_panel", false)
        }
    }

    SideEffect {
        callbacks.onT9RightCandidateWillBeSelected = { pinyin, text, textLength ->
            // 返回 C++ T9RightCommitHandler 的 full_commit 权威标志，
            // 不依赖 RIME 引擎 input（full_commit 后引擎 input 可能残留，判断会失真）
            if (pinyin.isNullOrBlank()) {
                t9Controller.onRightCandidateSelectedByDirectCommit()
            } else {
                t9Controller.onRightCandidateSelected(pinyin, text, textLength)
            }
        }
        callbacks.onT9ForceSendToRime = {
            t9Controller.forceSendToRime()
        }
        callbacks.onFilterT9Candidates = { candidates, comments ->
            Pair(candidates, comments)  // no-op: t9_processor handles filtering
        }
    }

    LaunchedEffect(state.t9ResetSignal) {
        t9Controller.reset()
    }

    LaunchedEffect(keyboardState) {
        FileLogger.i("XimeKeyboard", "keyboardState switched: $keyboardState, vs=$viewState, page=$page, ascii=${state.isAsciiMode}")
    }

    val kbColors = KeysConfigHelper.getKeyboardColors()
    val kbShadow = KeysConfigHelper.getKeyboardShadow()
    val kbKey = KeysConfigHelper.getKeyboardKeyConfig()
    val longToColor: (Long) -> androidx.compose.ui.graphics.Color = { if (it > 0xFFFFFF) androidx.compose.ui.graphics.Color(it) else androidx.compose.ui.graphics.Color(0xFF000000 or it) }
    val keyboardBgColor = KeyboardThemes.getKeyboardBackgroundColor(state.themeId, state.isDarkTheme)
    val keyBgColor = KeyboardThemes.getKeyBgColorOverride(state.themeId, state.isDarkTheme)
        ?: if (state.isDarkTheme) longToColor(kbColors.keyBgColorDark)
        else longToColor(kbColors.keyBgColor)
    val keyTextColor = KeyboardThemes.getKeyTextColorOverride(state.themeId, state.isDarkTheme)
        ?: if (state.isDarkTheme) longToColor(kbColors.keyTextColorDark)
        else longToColor(kbColors.keyTextColor)
    val accentColor = KeyboardThemes.getAccentColor(state.themeId, state.isDarkTheme)
    val themeScheme = KeyboardThemes.getThemeById(state.themeId)
    val themeSpecialKeyColor = KeyboardThemes.getSpecialKeyColor(state.themeId, state.isDarkTheme)
    val specialKeyBgColor = if (state.isDarkTheme) kbColors.specialKeyBgColorDark?.let { longToColor(it) }
        ?: themeSpecialKeyColor
        else kbColors.specialKeyBgColor?.let { longToColor(it) } ?: themeSpecialKeyColor
    val specialKeyTextColor = if (state.isDarkTheme) androidx.compose.ui.graphics.Color.White
        else KeyboardThemes.getSpecialKeyTextColor(state.themeId, false)
    val candidateTextColor = KeyboardThemes.getCandidateTextColorOverride(state.themeId, state.isDarkTheme)
        ?: if (state.isDarkTheme) longToColor(kbColors.candidateTextColorDark)
        else longToColor(kbColors.candidateTextColor)
    val candidateSelectedTextColor = KeyboardThemes.getCandidateSelectedTextColorOverride(state.themeId, state.isDarkTheme)
        ?: KeyboardThemes.getCandidateSelectedTextColor(state.themeId, state.isDarkTheme)
    val dividerColor = if (state.isDarkTheme) androidx.compose.ui.graphics.Color(0xFF3C4043) else androidx.compose.ui.graphics.Color(0xFFDADCE0)

    val clipboardTab = (page as? KeyboardPage.Overlay)?.let {
        (it.route as? OverlayRoute.Clipboard)?.tab
    } ?: 0
    val screenW = LocalConfiguration.current.screenWidthDp
    val screenH = LocalConfiguration.current.screenHeightDp
    val portraitScreenWidth = minOf(screenW, screenH)
    val cardWidthDp = (portraitScreenWidth * 0.85f).roundToInt()
    val floatScaleFactor = if (state.isFloatingMode) cardWidthDp.toFloat() / screenW.toFloat() else 0.85f
    val floatFontScale = if (state.isFloatingMode) cardWidthDp.toFloat() / portraitScreenWidth.toFloat() else 1f

    val contentModifier = if (state.isFloatingMode) {
        modifier.keyboardBackground(themeScheme.keyboardBackground, state.isDarkTheme, keyboardBgColor)
    } else {
        // 非浮动模式：渐变背景由 XimeInputMethodService 外层 Box 统一绘制（含导航栏区，
        // 保证延伸到屏幕底部时渐变连续），此处不再叠加第二层背景。
        modifier
    }
    FloatingKeyboardContainer(
        isFloatingMode = state.isFloatingMode,
        scaleFactor = floatScaleFactor,
        fontScaleFactor = floatFontScale,
        offsetX = state.floatingOffsetX,
        offsetY = state.floatingOffsetY,
        minOffsetY = state.floatingMinOffsetY,
        backgroundColor = keyboardBgColor,
        onDrag = { dx, dy -> callbacks.onFloatingKeyboardDrag?.invoke(dx, dy) },
        onDragEnd = { callbacks.onFloatingKeyboardDragEnd?.invoke() },
        onCardPositioned = onCardPositioned,
    ) {
    Box(modifier = contentModifier) {
        Box {
        // 长按候选删除自造词：确认覆盖层状态（键盘视图内渲染，不弹独立
        // 窗口——焦点型弹窗会抢焦点导致 IME 被系统收起）
        // 长按删除待确认项：词文本 + 确认后执行（候选栏/展开页共用同一确认覆盖层）
        var deletePending by remember { mutableStateOf<DeletePendingWord?>(null) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            var handwritingCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
            var handwritingComments by remember { mutableStateOf<List<String>>(emptyList()) }
            var handwritingClearSignal by remember { mutableIntStateOf(0) }
            var isHandwritingLookup by remember { mutableStateOf(false) }
            // 手写叠写状态真源：tailText=屏上手写会话尾部文本（含固化+活动字）；
            // activeLen=其中活动部分长度（可被识别结果替换/固化/撤销）；
            // lastSegLen=最后一次识别的段长（停顿定型不清零）——点选替换据此定位
            // "最后上屏的字"（停顿后再点候选仍可替换，而非追加）
            var handwritingTail by remember { mutableStateOf("") }
            var handwritingActiveLen by remember { mutableStateOf(0) }
            var handwritingLastSegLen by remember { mutableStateOf(0) }

            // 数据源（展开与否即切换点）：展开态下候选栏与展开页同吃全量列表
            // （expandedCandidates，展开时服务层重新拉取）；非展开态候选栏保持
            // 引擎当前页（"每页候选词数"）
            val expandedDataMode = candidatePageExpanded &&
                candidateState.value.expandedCandidates.isNotEmpty()

            val isHandwritingPage = page is KeyboardPage.Main && (page as KeyboardPage.Main).type == MainType.HANDWRITING
            val showHandwritingCandidates = (isHandwritingPage || isHandwritingLookup) && handwritingCandidates.isNotEmpty()

            val cs = candidateState.value
            // 展开态候选栏数据源：全量列表（筛选态取单字子列表，仍是同源关系）。
            // 每项携带全量原索引：筛选态过滤掉词组后位置索引 ≠ 全局索引，
            // 点选/长按必须经 first 换算回全局索引，与展开页 globalIndices 同口径
            val railExpanded = if (expandedDataMode) {
                if (singleCharFilter) {
                    cs.expandedCandidates.withIndex()
                        .mapNotNull { (i, c) -> if (c.text.length == 1) i to c else null }
                } else {
                    cs.expandedCandidates.withIndex().map { it.index to it.value }
                }
            } else {
                emptyList()
            }
            val candidateBarState = remember(
                cs.candidates, cs.candidateComments, cs.inputText, cs.preeditText, cs.isComposing,
                cs.associationCandidates, cs.pendingEnglishText, cs.isShowingRecentClipboard, cs.hasNextPage,
                state.isCalculatorMode, handwritingCandidates, handwritingComments, showHandwritingCandidates,
                railExpanded, cs.preeditCaretPos,
            ) {
                if (showHandwritingCandidates) {
                    CandidateBarState.AssociationOnly(
                        candidates = handwritingCandidates,
                        comments = handwritingComments,
                        highlightIndex = 0,
                    )
                } else {
                    CandidateBarState.from(
                        candidates = if (railExpanded.isNotEmpty()) railExpanded.map { it.second.text } else cs.candidates,
                        candidateComments = if (railExpanded.isNotEmpty()) railExpanded.map { it.second.comment } else cs.candidateComments,
                        inputText = cs.inputText,
                        preeditText = cs.preeditText,
                        isComposing = cs.isComposing,
                        associationCandidates = if (cs.pendingEnglishText.isNotEmpty() && cs.englishReplaceSupported) {
                            listOf(cs.pendingEnglishText) + cs.associationCandidates
                        } else {
                            cs.associationCandidates
                        },
                        isShowingRecentClipboard = cs.isShowingRecentClipboard,
                        hasNextPage = cs.hasNextPage,
                        isCalculatorActive = state.isCalculatorMode,
                        preeditCaretPos = cs.preeditCaretPos,
                    )
                }
            }

            if (state.showQuickSendForm) {
                QuickSendFormArea(
                    backgroundColor = Color.Transparent,
                    textColor = keyTextColor,
                    accentColor = accentColor,
                    isFocused = state.quickSendFormFocused,
                    initialText = state.quickSendEditingItemText,
                    initialCode = state.quickSendEditingItemCode,
                    cardBgColor = keyBgColor,
                    editingItemId = state.quickSendEditingItemId,
                    onClose = { text: String, code: String ->
                        android.util.Log.d("QuickSendForm", "onClose: textLen=${text.length}, editingId=${state.quickSendEditingItemId}, showForm=${state.showQuickSendForm}")
                        if (text.isNotBlank()) {
                            val editingId = state.quickSendEditingItemId
                            if (editingId != null) {
                                viewModel.updateQuickSendItem(editingId, text, code)
                            } else {
                                viewModel.addQuickSendText(text, code)
                            }
                        }
                        callbacks.onHideQuickSendForm?.invoke()
                    },
                    onFocusChange = { focused: Boolean ->
                        callbacks.onQuickSendFormFocusChange?.invoke(focused)
                    },
                    onCodeFocusChange = { focused: Boolean ->
                        callbacks.onQuickSendCodeFocusChange?.invoke(focused)
                    },
                )
            }

            // ACTIVE 输入面板在候选栏上方（需要 EditText 输入，保留候选栏可见）；
            // PASSIVE 纯展示面板走 Overlay 全屏（KeyboardView 底部 Overlay 分支渲染 InfoPanel）
            if (state.toolPanelVisible && state.toolPanelDisplay != "PASSIVE") {
                ToolPanel(
                    title = state.toolPanelTitle,
                    isFocused = state.toolPanelInputFocused,
                    isLoading = state.toolPanelLoading,
                    initialText = state.toolPanelPrefillText,
                    controls = state.toolPanelUiNodes,
                    backgroundColor = Color.Transparent,
                    textColor = keyTextColor,
                    accentColor = accentColor,
                    cardBgColor = keyBgColor,
                    onClose = { callbacks.onToolPanelClose?.invoke() },
                    onFocusChange = { focused -> callbacks.onToolPanelFocusChange?.invoke(focused) },
                    onFieldInput = { key, value -> callbacks.onToolPanelFieldInput?.invoke(key, value) },
                    onPanelAction = { actionId -> callbacks.onToolPanelAction?.invoke(actionId) },
                )
            }

            CandidateBar(
                state = candidateBarState,
                page = page,
                candidatePageExpanded = candidatePageExpanded,
                isFloatingMode = state.isFloatingMode,
                isVoiceSticky = state.voiceSticky,
                voiceAmplitude = voiceAmplitudeState.value,
                voiceSpectrum = voiceSpectrumState.value,
                voiceRecognitionState = state.voiceRecognitionState,
                voicePluginName = state.voicePluginName,
                toolbarActions = state.toolbarButtons.mapNotNull { id ->
                    val item = resolveToolbarButtonItem(id, state.toolbarPluginButtons) ?: return@mapNotNull null
                    if (item is ToolbarButtonItem.Builtin && item.button == ToolbarButton.HANDWRITING_LOOKUP) {
                        if (!com.kingzcheung.xime.handwriting.HandwritingEngine.hasModel(LocalContext.current)) return@mapNotNull null
                    }
                    val toolbarContext = LocalContext.current
                    val onClick: () -> Unit = when (item) {
                        is ToolbarButtonItem.Builtin -> when (item.button) {
                            ToolbarButton.EMOJI -> ({ viewModel.showOverlay(OverlayRoute.Emoji) })
                            ToolbarButton.CLIPBOARD -> ({ viewModel.showOverlay(OverlayRoute.Clipboard(0)) })
                            ToolbarButton.SCHEMA -> ({ viewModel.showOverlay(OverlayRoute.SchemaList, listOf(OverlayRoute.Menu)) })
                            ToolbarButton.QUICK_PHRASE -> ({ viewModel.showOverlay(OverlayRoute.Clipboard(1)) })
                            ToolbarButton.SYMBOL -> ({ viewModel.showOverlay(OverlayRoute.Symbol) })
                            ToolbarButton.SELECT_ALL -> ({ callbacks.onToolbarEditingAction?.invoke("select_all") })
                            ToolbarButton.COPY -> ({ callbacks.onToolbarEditingAction?.invoke("copy") })
                            ToolbarButton.PASTE -> ({ callbacks.onToolbarEditingAction?.invoke("paste") })
                            ToolbarButton.HOME -> ({ callbacks.onToolbarEditingAction?.invoke("home") })
                            ToolbarButton.END -> ({ callbacks.onToolbarEditingAction?.invoke("end") })
                            ToolbarButton.FLOAT -> ({ callbacks.onFloatingModeChange?.invoke(!state.isFloatingMode) })
                            ToolbarButton.HANDWRITING_LOOKUP -> ({ isHandwritingLookup = !isHandwritingLookup })
                            ToolbarButton.EDIT -> ({ viewModel.showOverlay(OverlayRoute.Edit) })
                            ToolbarButton.VOICE -> ({
                                if (PermissionHelper.hasRecordAudioPermission(toolbarContext)) {
                                    callbacks.onVoiceStickyToggle?.invoke()
                                } else {
                                    android.widget.Toast.makeText(toolbarContext, "需要麦克风权限才能使用语音输入", android.widget.Toast.LENGTH_SHORT).show()
                                    PermissionHelper.requestRecordAudioPermission(toolbarContext)
                                }
                            })
                        }
                        is ToolbarButtonItem.Plugin -> ({
                            if (item.action == "open_panel") {
                                callbacks.onOpenToolPanel?.invoke(item.pluginId)
                            }
                        })
                    }
                    ToolbarAction(item) {
                        onHapticFeedback?.invoke()
                        onClick()
                    }
                },
                visuals = CandidateBarVisuals(
                    backgroundColor = Color.Transparent,
                    textColor = candidateTextColor,
                    dividerColor = dividerColor,
                    accentColor = accentColor,
                    selectedTextColor = candidateSelectedTextColor,
                    isDarkTheme = state.isDarkTheme
                ),
                callbacks = CandidateBarCallbacks(
                    onCandidateSelect = { index ->
                        if (showHandwritingCandidates && index in handwritingCandidates.indices) {
                            // 手写候选点选绕过了服务层 selectCandidate（其入口统一有按键反馈），
                            // 这里补齐同款反馈，保证各键盘点选手感一致
                            callbacks.onKeyPressDown?.invoke("standard")
                            if (isHandwritingLookup) {
                                // 手写查词：直接上屏（原有行为）
                                val ch = handwritingCandidates[index]
                                callbacks.onCommitText?.invoke(ch)
                                handwritingCandidates = emptyList()
                                handwritingComments = emptyList()
                                handwritingClearSignal++
                            } else {
                                // 兜底路径（AssociationOnly 正常走 onAssociationSelect）：
                                // 与点选替换同语义；点选即结束选择期，候选栏清空
                                val ch = handwritingCandidates[index]
                                if (index > 0 && handwritingLastSegLen > 0) {
                                    val newTail = handwritingTail.dropLast(handwritingLastSegLen) + ch
                                    val ok = callbacks.onHandwritingAutoCommit?.invoke(newTail, handwritingTail) ?: false
                                    if (ok || handwritingTail.isEmpty()) handwritingTail = newTail
                                    handwritingLastSegLen = ch.length
                                }
                                handwritingActiveLen = 0
                                callbacks.onHandwritingFinalize?.invoke()
                                handwritingCandidates = emptyList()
                                handwritingComments = emptyList()
                                handwritingClearSignal++
                            }
                        } else if (expandedDataMode) {
                            // 展开态候选栏数据源=全量列表（筛选态为其单字子列表）：
                            // 位置索引经 railExpanded 换算全局索引再走全局链路
                            val globalIndex = railExpanded.getOrNull(index)?.first
                            if (globalIndex != null) {
                                callbacks.onGlobalCandidateSelect?.invoke(globalIndex)
                                viewModel.setCandidatePageExpanded(false)
                            }
                        } else {
                            callbacks.onCandidateSelect(index)
                        }
                    },
                    onCandidateLongPress = { index ->
                        val railEntry = if (expandedDataMode) railExpanded.getOrNull(index) else null
                        val word = railEntry?.second?.text
                            ?: candidateState.value.candidates.getOrNull(index)
                        if (!word.isNullOrEmpty()) {
                            deletePending = DeletePendingWord(word) {
                                if (railEntry != null) {
                                    callbacks.onGlobalCandidateDelete?.invoke(railEntry.first)
                                } else {
                                    callbacks.onCandidateDelete?.invoke(index)
                                }
                            }
                        }
                    },
                    onClearAssociation = {
                        onHapticFeedback?.invoke()
                        if (showHandwritingCandidates) {
                            handwritingCandidates = emptyList()
                            handwritingComments = emptyList()
                            handwritingClearSignal++
                        } else {
                            callbacks.onClearAssociation?.invoke()
                        }
                    },
                    onLogoClick = { viewModel.showOverlay(OverlayRoute.Menu) },
                    onBack = {
                        onHapticFeedback?.invoke()
                        if (showHandwritingCandidates) {
                            handwritingCandidates = emptyList()
                            handwritingComments = emptyList()
                            handwritingClearSignal++
                        } else {
                            when (page) {
                                is KeyboardPage.Overlay -> {
                                    if ((page as KeyboardPage.Overlay).backStack.isEmpty())
                                        viewModel.closeOverlay()
                                    else viewModel.popOverlay()
                                }
                                is KeyboardPage.Panel -> viewModel.exitPanel()
                                is KeyboardPage.Main -> viewModel.setCandidatePageExpanded(false)
                            }
                        }
                    },
                    onHideKeyboard = {
                        onHapticFeedback?.invoke()
                        callbacks.onHideKeyboard?.invoke()
                        viewModel.resetKeyboard(state.isAsciiMode, state.currentSchemaId)
                    },
                    onShowMoreCandidates = {
                        onHapticFeedback?.invoke()
                        viewModel.setCandidatePageExpanded(true)
                        // 拉取跨页全量候选（展开页数据源，含首次展开）
                        callbacks.onRequestExpandedCandidates?.invoke()
                    },
                    onInputTextClick = {
                        if (candidateState.value.inputText.isNotEmpty()) {
                            callbacks.onClipboardSelect?.invoke(candidateState.value.inputText)
                        }
                    },
                    onAssociationSelect = { index ->
                        if (showHandwritingCandidates && index in handwritingCandidates.indices) {
                            // 手写候选点选绕过了服务层 onAssociationSelect（其入口统一有按键反馈），
                            // 这里补齐同款反馈，保证各键盘点选手感一致
                            callbacks.onKeyPressDown?.invoke("standard")
                            if (isHandwritingLookup) {
                                // 手写查词：查词结果直接上屏（原有行为，与叠写状态无关）
                                val ch = handwritingCandidates[index]
                                callbacks.onCommitText?.invoke(ch)
                                handwritingCandidates = emptyList()
                                handwritingComments = emptyList()
                                handwritingClearSignal++
                            } else if (index == 0) {
                                // 首选已自动上屏：点选固化当前字并触发联想。
                                // 点选即结束选择期：候选栏清空（下一轮书写重新填充）
                                handwritingActiveLen = 0
                                callbacks.onHandwritingFinalize?.invoke()
                                handwritingCandidates = emptyList()
                                handwritingComments = emptyList()
                                handwritingClearSignal++
                            } else {
                                // 替换最后上屏的字为点选候选（停顿定型后仍可替换）。
                                // 点选即结束选择期：候选栏清空，笔画清空
                                val ch = handwritingCandidates[index]
                                val newTail = handwritingTail.dropLast(handwritingLastSegLen) + ch
                                val ok = callbacks.onHandwritingAutoCommit?.invoke(newTail, handwritingTail) ?: false
                                if (ok || handwritingTail.isEmpty()) handwritingTail = newTail
                                handwritingActiveLen = 0
                                handwritingLastSegLen = ch.length
                                callbacks.onHandwritingFinalize?.invoke()
                                handwritingCandidates = emptyList()
                                handwritingComments = emptyList()
                                handwritingClearSignal++
                            }
                        } else {
                            callbacks.onAssociationSelect?.invoke(index)
                        }
                    },
                ),
                inlineSuggestions = inlineSuggestions,
            )

            if (candidatePageExpanded) {
                // 候选展开页：候选栏的在位展开态（顶部即真实候选栏，实时跟随编码/删除变化）。
                // 数据源为服务层的跨页全量候选（expandedCandidates），行分组惰性渲染
                // （LazyColumn 只画可见行），可上下滑动 + 翻页键滚动一屏，不驱动 rime 翻页。
                val allExpanded = candidateState.value.expandedCandidates
                // 左栏符号复刻当前键盘左栏：九键/笔画各自的可自定义 side_symbols
                // （configVersion 参与重组，保证设置改动后刷新）；其余布局用默认快捷符号
                val configVersion by KeysConfigHelper.configVersion.collectAsStateWithLifecycle()
                val customRailSymbols = remember(configVersion, keyboardState) {
                    when (keyboardState) {
                        is KeyboardLayoutState.T9Pinyin -> KeysConfigHelper.getT9SideSymbols()
                        is KeyboardLayoutState.Stroke -> KeysConfigHelper.getStrokeSideSymbols()
                        else -> null
                    }
                }
                // 九键左栏复刻：输入/选择态显示音节拼音候选（与键盘左栏同源，
                // 点击切换音节后服务层重拉全量候选刷新本页），空闲态回落 side_symbols；
                // 左栏宽度与九键键盘左栏视觉同宽：九键竖屏根容器有左右各 4dp 边距
                // （padding start/end 4dp），Row 内 spacedBy(2dp)×2，weight 基数 =
                // 屏宽-8-4；左栏列 = 基数×0.8/5，面板再带 LocalKeyVisualPadding
                // 水平缩进（keySpacingX ?: 2dp）——展开页左栏为全宽背景，同额扣除
                // （横屏九键无左栏不缩放）
                val isT9Layout = keyboardState is KeyboardLayoutState.T9Pinyin
                val t9RailWidthDp = if (isT9Layout && !isLandscape) {
                    val railInset = kbKey.spacingFor("t9").first ?: 2f
                    ((LocalConfiguration.current.screenWidthDp - 12) * 0.8f / 5f -
                        railInset * 2f + 0.5f).toInt().coerceAtLeast(32)
                } else 0
                // 左栏垂直缩进与九键左栏面板同源（keySpacingY ?: 2dp），展开/收起
                // 切换时左栏顶部不跳位；其余布局 6dp = 原 Row 垂直边距
                val t9RailInsetDp = if (isT9Layout && !isLandscape)
                    (kbKey.spacingFor("t9").second ?: 2f).toInt() else 6
                val railPinyinOptions =
                    if (isT9Layout) t9Controller.firstOptions.map { it.pinyin } else emptyList()
                val railSelectedPinyinIndex =
                    if (isT9Layout && t9Controller.leftPanelState ==
                        T9InputController.LeftPanelState.SELECTION
                    ) t9Controller.firstOptions.indexOf(t9Controller.selectedOption) else -1
                // 展开页展示全量候选；行分组按字符当量估算，仅作展示分组
                val rowWidthUnits = with(LocalDensity.current) {
                    ExpandedCandidatePager.rowWidthUnits(
                        screenWidthPx = LocalConfiguration.current.screenWidthDp.dp.toPx(),
                        density = density,
                        scaledDensity = density * fontScale
                    )
                }
                val candidateRows = remember(
                    allExpanded, singleCharFilter, rowWidthUnits
                ) {
                    ExpandedCandidatePager.flowRows(
                        ExpandedCandidatePager.filterIndices(allExpanded, singleCharFilter),
                        allExpanded,
                        rowWidthUnits
                    ).map { row ->
                        row.map { gi ->
                            CandidateEntry(allExpanded[gi].text, allExpanded[gi].comment, gi)
                        }
                    }
                }
                CandidatePage(
                    state = CandidatePageState(
                        candidateRows = candidateRows,
                        associationCandidates = candidateState.value.associationCandidates.toList(),
                        backgroundColor = keyboardBgColor,
                        textColor = candidateTextColor,
                        keyBackgroundColor = keyBgColor,
                        bottomPaddingDp = state.keyboardBottomPaddingDp,
                        singleCharFilter = singleCharFilter,
                        railSymbols = customRailSymbols.orEmpty(),
                        leftRailWidthDp = t9RailWidthDp,
                        leftRailInsetDp = t9RailInsetDp,
                        railPinyinOptions = railPinyinOptions,
                        railSelectedPinyinIndex = railSelectedPinyinIndex,
                        railAccentColor = accentColor,
                    ),
                    callbacks = CandidatePageCallbacks(
                        onCandidateSelect = { entry ->
                            // entry.globalIndex 即跨页全局索引（引擎侧 select_candidate）
                            callbacks.onGlobalCandidateSelect?.invoke(entry.globalIndex)
                            viewModel.setCandidatePageExpanded(false)
                        },
                        onCandidateLongPress = { entry ->
                            // 长按删除自造词：与候选栏共用确认弹窗
                            val word = candidateState.value.expandedCandidates
                                .getOrNull(entry.globalIndex)?.text
                            if (!word.isNullOrEmpty()) {
                                onHapticFeedback?.invoke()
                                deletePending = DeletePendingWord(word) {
                                    callbacks.onGlobalCandidateDelete?.invoke(entry.globalIndex)
                                }
                            }
                        },
                        onToggleSingleCharFilter = {
                            onHapticFeedback?.invoke()
                            viewModel.toggleSingleCharFilter()
                        },
                        onAssociationSelect = { index ->
                            callbacks.onAssociationSelect?.invoke(index)
                            viewModel.setCandidatePageExpanded(false)
                        },
                        onRailPinyinSelect = { index ->
                            onHapticFeedback?.invoke()
                            if (isT9Layout) {
                                // 与九键键盘左栏同语义：切换音节选项，引擎重组后
                                // 服务层重拉全量候选，展开页内容随之刷新
                                t9Controller.firstOptions.getOrNull(index)?.let {
                                    t9Controller.onChoiceSelected(it)
                                }
                            }
                        },
                        onCommitText = { text ->
                            onHapticFeedback?.invoke()
                            if (customRailSymbols != null) {
                                // 九键/笔画左栏符号：与键盘本体左栏同路径（onKeyPress，
                                // 由 rime punctuator 决定顶屏/上屏行为）
                                callbacks.onKeyPress(text, false)
                            } else {
                                callbacks.onCommitText?.invoke(text)
                            }
                        },
                        onDelete = {
                            onHapticFeedback?.invoke()
                            if (isT9Layout) {
                                // 与九键键盘本体退格键（T9KeyboardLayout.handleDelete）
                                // 完全同路径：T9 撤销模型（删未分配数字/撤销左栏选择/
                                // 撤销上屏），buffer→rime 经 FlushRimeInput 同步；
                                // 引擎不消费时才转服务层普通删除。此前直接走
                                // onKeyPress("delete") 缺 flush，T9 buffer 已回退但
                                // rime composition 不刷新，表现为连按多次才删掉
                                t9Controller.onDeleted { result ->
                                    when (result) {
                                        T9InputController.DeleteResult.UNDO_COMMIT ->
                                            t9Controller.clearRimeAndResend()
                                        T9InputController.DeleteResult.NOT_CONSUMED ->
                                            callbacks.onKeyPress("delete", false)
                                        else -> {}
                                    }
                                }
                            } else {
                                callbacks.onKeyPress("delete", false)
                            }
                        },
                        onEnter = {
                            onHapticFeedback?.invoke()
                            callbacks.onKeyPress("enter", false)
                        },
                    ),
                    pageScrollEvents = viewModel.expandedPageScrollEvents,
                    onHapticFeedback = onHapticFeedback,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            } else {
            val isMainKeyboard = page is KeyboardPage.Main
            if (isMainKeyboard) {
                val mainType = (page as KeyboardPage.Main).type
                when (mainType) {
                    MainType.FULL -> {
                        val currentOnCursorMove = rememberUpdatedState(callbacks.onCursorMove)
                        val suppressCursorMove = remember { mutableStateOf(false) }
                        val cursorMod = if (callbacks.onCursorMove != null) {
                            Modifier.pointerInput(Unit) {
                                val stepThresholdPx = 25.dp.toPx()
                                val activationThresholdPx = 60.dp.toPx()
                                awaitEachGesture {
                                    suppressCursorMove.value = false
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var isCursorGesture = false
                                    var lastSteps = 0
                                    var activationAnchorX = down.position.x

                                    do {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        val dx = change.position.x - down.position.x
                                        val dy = change.position.y - down.position.y

                                        if (!change.pressed) {
                                            if (isCursorGesture) {
                                                event.changes.forEach { it.consume() }
                                            }
                                            break
                                        }
                                        if (suppressCursorMove.value) break
                                        if (abs(dx) > abs(dy) * 4f) {

                                            if (!isCursorGesture && abs(dx) > activationThresholdPx) {
                                                isCursorGesture = true
                                                activationAnchorX = change.position.x
                                            }

                                            if (isCursorGesture) {
                                                event.changes.forEach { it.consume() }
                                                val dxFromAnchor = change.position.x - activationAnchorX
                                                val steps = (dxFromAnchor / stepThresholdPx).toInt()
                                                if (steps != lastSteps) {
                                                    val delta = steps - lastSteps
                                                    currentOnCursorMove.value?.invoke(delta)
                                                    lastSteps = steps
                                                }
                                            }
                                        }
                                    } while (true)
                                }
                            }
                        } else {
                            Modifier
                        }

                        val context = LocalContext.current

                        var modeChangeTarget: KeyboardLayoutAction by remember {
                            mutableStateOf(
                                if (SettingsPreferences.getModeChangeTargetIsNumber(context))
                                    KeyboardLayoutAction.SwitchToNumber
                                else
                                    KeyboardLayoutAction.SwitchToCommonSymbol
                            )
                        }

                        val fullScreenOnKeyPress: (String) -> Unit = { key ->
                            when (key) {
                                "shift" -> viewModel.toggleShift()
                                "shift_single" -> viewModel.singleTapShift()
                                "shift_caps" -> viewModel.doubleTapShift()
                                "mode_change" -> {
                                    callbacks.onCommitCandidateBeforeModeChange?.invoke()
                                    viewModel.setKeyboardState(keyboardState.transition(
                                        modeChangeTarget, state.isAsciiMode
                                    ))
                                    callbacks.onKeyPress("clear_composition", false)
                                }
                                "mode_change_symbol" -> viewModel.showOverlay(OverlayRoute.Symbol)
                                "mode_change_number" -> {
                                    callbacks.onCommitCandidateBeforeModeChange?.invoke()
                                    modeChangeTarget = KeyboardLayoutAction.SwitchToNumber
                                    SettingsPreferences.setModeChangeTargetIsNumber(context, true)
                                    viewModel.setKeyboardState(KeyboardLayoutState.Number)
                                }
                                "mode_change_common_symbol" -> {
                                    callbacks.onCommitCandidateBeforeModeChange?.invoke()
                                    modeChangeTarget = KeyboardLayoutAction.SwitchToCommonSymbol
                                    SettingsPreferences.setModeChangeTargetIsNumber(context, false)
                                    viewModel.setKeyboardState(keyboardState.transition(
                                        KeyboardLayoutAction.SwitchToCommonSymbol, state.isAsciiMode
                                    ))
                                }
                                "emoji" -> viewModel.showOverlay(OverlayRoute.Emoji)
                                else -> {
                                    callbacks.onKeyPress(key, isShifted)
                                    viewModel.onCharacterTyped()
                                }
                            }
                        }
                        val numberOnKeyPress: (String) -> Unit = { key ->
                            when (key) {
                                "abc" -> {
                                    callbacks.onKeyPress("abc", false)
                                    if (page is KeyboardPage.Panel) {
                                        viewModel.exitPanel()
                                    } else {
                                        val mainTarget = viewModel.asciiStateMachine.targetFor(
                                            AsciiKeyboardContext.MAIN, state.isAsciiMode
                                        ) ?: state.isAsciiMode
                                        viewModel.setKeyboardState(
                                            initialKeyboardLayoutState(mainTarget, state.currentSchemaId)
                                        )
                                    }
                                }
                                "symbol" -> {
                                    viewModel.showOverlay(OverlayRoute.Symbol)
                                }
                                "emoji" -> {
                                    viewModel.showOverlay(OverlayRoute.Emoji)
                                }
                                else -> callbacks.onKeyPress(key, false)
                            }
                        }
                        val symbolOnKeyPress: (String) -> Unit = { key ->
                            when (key) {
                                "abc" -> {
                                    if (page is KeyboardPage.Panel) {
                                        viewModel.exitPanel()
                                    } else {
                                        val mainTarget = viewModel.asciiStateMachine.targetFor(
                                            AsciiKeyboardContext.MAIN, state.isAsciiMode
                                        ) ?: state.isAsciiMode
                                        viewModel.setKeyboardState(
                                            initialKeyboardLayoutState(mainTarget, state.currentSchemaId)
                                        )
                                    }
                                }
                                "?123" -> {
                                    callbacks.onCommitCandidateBeforeModeChange?.invoke()
                                    viewModel.setKeyboardState(keyboardState.transition(
                                        KeyboardLayoutAction.SwitchToNumber, state.isAsciiMode
                                    ))
                                    callbacks.onKeyPress("clear_composition", false)
                                }
                                else -> callbacks.onKeyPress(key, false)
                            }
                        }
                        val commonSymbolOnKeyPress: (String) -> Unit = { key ->
                            when (key) {
                                "abc" -> {
                                    // 返回主键盘：面板内 ascii 模式不应影响主键盘布局，
                                    // 用主键盘记忆（或恢复进入面板前状态），避免"先英文后切回中文"闪变。
                                    if (page is KeyboardPage.Panel) {
                                        viewModel.exitPanel()
                                    } else {
                                        val mainTarget = viewModel.asciiStateMachine.targetFor(
                                            AsciiKeyboardContext.MAIN, state.isAsciiMode
                                        ) ?: state.isAsciiMode
                                        viewModel.setKeyboardState(
                                            initialKeyboardLayoutState(mainTarget, state.currentSchemaId)
                                        )
                                    }
                                }
                                "number" -> {
                                    viewModel.setKeyboardState(keyboardState.transition(
                                        KeyboardLayoutAction.SwitchToNumber, state.isAsciiMode
                                    ))
                                }
                                "symbol" -> {
                                    viewModel.showOverlay(OverlayRoute.Symbol)
                                }
                                "emoji" -> {
                                    viewModel.showOverlay(OverlayRoute.Emoji)
                                }
                                else -> callbacks.onKeyPress(key, false)
                            }
                        }
                        val strokeOnKeyPress: (String) -> Unit = { key ->
                            when (key) {
                                "abc" -> viewModel.setKeyboardState(keyboardState.transition(
                                    KeyboardLayoutAction.SwitchToFull, state.isAsciiMode
                                ))
                                "number" -> viewModel.setKeyboardState(keyboardState.transition(
                                    KeyboardLayoutAction.SwitchToNumber, state.isAsciiMode
                                ))
                                "symbol" -> viewModel.showOverlay(OverlayRoute.Symbol)
                                "emoji" -> viewModel.showOverlay(OverlayRoute.Emoji)
                                else -> callbacks.onKeyPress(key, false)
                            }
                        }
                        val t9OnKeyPress: (String) -> Unit = { key ->
                            when (key) {
                                "abc" -> viewModel.setKeyboardState(
                                    initialKeyboardLayoutState(state.isAsciiMode, state.currentSchemaId)
                                )
                                "number" -> {
                                    callbacks.onT9SwitchAway?.invoke()
                                    viewModel.setKeyboardState(keyboardState.transition(
                                        KeyboardLayoutAction.SwitchToNumber, state.isAsciiMode
                                    ))
                                }
                                "symbol" -> viewModel.showOverlay(OverlayRoute.Symbol)
                                "emoji" -> viewModel.showOverlay(OverlayRoute.Emoji)
                                "ime_switch" -> {
                                    callbacks.onT9SwitchAway?.invoke()
                                    callbacks.onKeyPress(key, false)
                                }
                                else -> callbacks.onKeyPress(key, false)
                            }
                        }
                        val currentOnKeyPress = when (keyboardState) {
                            is KeyboardLayoutState.Chinese,
                            is KeyboardLayoutState.English -> fullScreenOnKeyPress
                            is KeyboardLayoutState.Number -> numberOnKeyPress
                            is KeyboardLayoutState.CommonSymbol -> commonSymbolOnKeyPress
                            is KeyboardLayoutState.Stroke -> strokeOnKeyPress
                            is KeyboardLayoutState.T9Pinyin -> t9OnKeyPress
                            is KeyboardLayoutState.Symbol -> symbolOnKeyPress
                        }
                        CompositionLocalProvider(
                            LocalSuppressCursorMove provides suppressCursorMove,
                        ) {
                            KeyboardLayoutScreen(
                                keyboardState = keyboardState,
                                uiState = state,
                                candidateState = candidateState,
                                viewModel = viewModel,
                                callbacks = callbacks,
                                onKeyPress = currentOnKeyPress,
                                modifier = Modifier.weight(1f).then(cursorMod),
                                isHandwritingLookup = isHandwritingLookup,
                                onHandwritingCandidates = { candidates ->
                                    val chars = candidates.map { it.char }
                                    handwritingCandidates = chars
                                    handwritingComments = chars.map { RimeEngine.getInstance().lookupText(it) }
                                },
                                onHandwritingButtonFeedback = { key -> callbacks.onKeyPressDown?.invoke(key) },
                                handwritingClearSignal = handwritingClearSignal,
                                onHandwritingLookupExit = { isHandwritingLookup = false },
                                t9Controller = t9Controller,
                            )
                            if (state.keyboardBottomPaddingDp > 0) {
                                Spacer(modifier = Modifier.height(state.keyboardBottomPaddingDp.dp))
                            }
                        }
                    }

                    MainType.HANDWRITING -> {
                        HandwritingKeyboardLayout(
                            onKeyPress = { key ->
                                when (key) {
                                    "delete" -> {
                                        when {
                                            handwritingActiveLen > 0 -> {
                                                // 撤销当前字：删屏上活动区文本，清笔画重写
                                                callbacks.onDeleteText?.invoke(handwritingActiveLen)
                                                handwritingTail = handwritingTail.dropLast(handwritingActiveLen)
                                                handwritingActiveLen = 0
                                                handwritingLastSegLen = 0
                                                handwritingCandidates = emptyList()
                                                handwritingComments = emptyList()
                                                handwritingClearSignal++
                                            }
                                            handwritingTail.isNotEmpty() -> {
                                                // 无活动字：退格删固化尾字，tail 乐观修剪（下次替换校验兜底）。
                                                // 候选栏同步失效（tail 已变，点选替换无意义）
                                                callbacks.onDeleteText?.invoke(1)
                                                handwritingTail = handwritingTail.dropLast(1)
                                                handwritingLastSegLen = 0
                                                handwritingCandidates = emptyList()
                                                handwritingComments = emptyList()
                                            }
                                            else -> callbacks.onKeyPress("delete", false)
                                        }
                                    }
                                    "symbol" -> viewModel.enterPanel(PanelType.COMMON_SYMBOL)
                                    "number" -> viewModel.enterPanel(PanelType.NUMBER)
                                    "ime_switch" -> {
                                        // 离开手写页即卸载手写模型（回到手写页时布局重建重载）
                                        com.kingzcheung.xime.handwriting.HandwritingEngine.release()
                                        viewModel.switchMain(MainType.FULL)
                                        callbacks.onKeyPress("ime_switch", false)
                                    }
                                    "space" -> {
                                        // 当前字已自动上屏：空格=固化 + 上屏空格（全量 commitText 触发联想）。
                                        // 选择期结束：清候选栏
                                        handwritingActiveLen = 0
                                        handwritingLastSegLen = 0
                                        handwritingCandidates = emptyList()
                                        handwritingComments = emptyList()
                                        callbacks.onCommitText?.invoke(" ")
                                    }
                                    "enter" -> {
                                        // 换行定稿：选择期结束
                                        handwritingActiveLen = 0
                                        handwritingLastSegLen = 0
                                        handwritingCandidates = emptyList()
                                        handwritingComments = emptyList()
                                        callbacks.onKeyPress("enter", false)
                                    }
                                    else -> {
                                        // 标点等直接上屏：固化活动字 + 选择期结束
                                        handwritingActiveLen = 0
                                        handwritingLastSegLen = 0
                                        handwritingCandidates = emptyList()
                                        handwritingComments = emptyList()
                                        callbacks.onCommitText?.invoke(key)
                                    }
                                }
                            },
                            onRecognition = { segments ->
                                val segText = segments.mapNotNull { seg ->
                                    seg.candidates.firstOrNull()?.char
                                }.joinToString("")
                                if (segText.isNotEmpty()) {
                                    // 替换式上屏：活动区整体重写为最新识别结果（边写边上屏）。
                                    // 校验失败（光标漂移）时重置尾部状态，后续识别以追加模式重建
                                    val newTail = handwritingTail.dropLast(handwritingActiveLen) + segText
                                    val ok = callbacks.onHandwritingAutoCommit?.invoke(newTail, handwritingTail) ?: false
                                    handwritingTail = if (ok || handwritingTail.isEmpty()) newTail else ""
                                    handwritingActiveLen = segText.length
                                    handwritingLastSegLen = segText.length
                                    handwritingCandidates = segments.last().candidates.map { it.char }
                                    handwritingComments = emptyList()
                                }
                            },
                            onSegmentSettled = { ch ->
                                // 叠写满上限：最早段固化（屏上不动，退出活动区）
                                handwritingActiveLen = (handwritingActiveLen - ch.length).coerceAtLeast(0)
                            },
                            onUndoActive = {
                                if (handwritingActiveLen > 0) {
                                    callbacks.onDeleteText?.invoke(handwritingActiveLen)
                                    handwritingTail = handwritingTail.dropLast(handwritingActiveLen)
                                    handwritingActiveLen = 0
                                    handwritingCandidates = emptyList()
                                    handwritingComments = emptyList()
                                    handwritingClearSignal++
                                }
                            },
                            onButtonFeedback = { key ->
                                callbacks.onKeyPressDown?.invoke(key)
                            },
                            clearSignal = handwritingClearSignal,
                            keyBackgroundColor = keyBgColor,
                            keyTextColor = keyTextColor,
                            specialKeyBackgroundColor = specialKeyBgColor,
                            specialKeyTextColor = specialKeyTextColor,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.keyboardBottomPaddingDp > 0) {
                            Spacer(modifier = Modifier.height(state.keyboardBottomPaddingDp.dp))
                        }
                    }

                    MainType.STROKE -> {
                        // Stroke is handled via keyboardState within FULL for now
                    }

                    MainType.VOICE -> {
                        VoiceKeyboardLayout(
                            keyBackgroundColor = keyBgColor,
                            keyTextColor = keyTextColor,
                            specialKeyBackgroundColor = specialKeyBgColor,
                            keyboardBackgroundColor = keyboardBgColor,
                            modifier = Modifier.weight(1f),
                            isDarkTheme = state.isDarkTheme,
                            themeId = state.themeId,
                            bottomActive = state.voiceBottomActive,
                            leftActive = state.voiceLeftActive,
                            rightActive = state.voiceRightActive,
                            pluginName = state.voicePluginName,
                            recognitionState = state.voiceRecognitionState,
                            recognizedText = state.voiceRecognizedText,
                            amplitude = voiceAmplitudeState.value,
                            spectrum = voiceSpectrumState.value
                        )
                    }
                }
            }

            val isPanelKeyboard = page is KeyboardPage.Panel
            if (isPanelKeyboard) {
                val panelType = (page as KeyboardPage.Panel).type
                when (panelType) {
                    PanelType.NUMBER -> NumberKeyboardLayout(
                        onKeyPress = { key ->
                            when (key) {
                                "abc" -> viewModel.exitPanel()
                                "symbol" -> {
                                    viewModel.showOverlay(OverlayRoute.Symbol)
                                }
                                "emoji" -> {
                                    viewModel.showOverlay(OverlayRoute.Emoji)
                                }
                                else -> callbacks.onKeyPress(key, false)
                            }
                        },
                        keyBackgroundColor = keyBgColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBgColor,
                        bubbleBackgroundColor = themeSpecialKeyColor,
                        keyboardBackgroundColor = keyboardBgColor,
                        shadowEnabled = kbShadow.enabled,
                        shadowElevation = kbShadow.elevation.dp,
                        shadowShapeRadius = kbShadow.shapeRadius.dp,
                        keyCornerRadius = kbKey.cornerRadius.dp,
                        keySpacingX = kbKey.spacingFor("number").first?.dp,
                        keySpacingY = kbKey.spacingFor("number").second?.dp,
                        onKeyPressDown = callbacks.onKeyPressDown,
                        isFloatingMode = state.isFloatingMode,
                        specialKeyTextColor = specialKeyTextColor,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )

                    PanelType.COMMON_SYMBOL -> CommonSymbolKeyboardLayout(
                        onKeyPress = { key ->
                            when (key) {
                                "abc" -> {
                                    viewModel.exitPanel()
                                }
                                "number" -> {
                                    viewModel.enterPanel(PanelType.NUMBER)
                                }
                                "symbol" -> {
                                    viewModel.showOverlay(OverlayRoute.Symbol)
                                }
                                "emoji" -> {
                                    viewModel.showOverlay(OverlayRoute.Emoji)
                                }
                                else -> callbacks.onKeyPress(key, false)
                            }
                        },
                        isAsciiMode = state.isAsciiMode,
                        initialAsciiMode = viewModel.asciiStateMachine.targetFor(
                            AsciiKeyboardContext.SYMBOL_PANEL, state.isAsciiMode
                        ) ?: state.isAsciiMode,
                        keyBackgroundColor = keyBgColor,
                        keyTextColor = keyTextColor,
                        specialKeyBackgroundColor = specialKeyBgColor,
                        bubbleBackgroundColor = themeSpecialKeyColor,
                        keyboardBackgroundColor = keyboardBgColor,
                        shadowEnabled = kbShadow.enabled,
                        shadowElevation = kbShadow.elevation.dp,
                        shadowShapeRadius = kbShadow.shapeRadius.dp,
                        keyCornerRadius = kbKey.cornerRadius.dp,
                        keySpacingX = kbKey.spacingFor("symbol").first?.dp,
                        keySpacingY = kbKey.spacingFor("symbol").second?.dp,
                        onKeyPressDown = callbacks.onKeyPressDown,
                        isFloatingMode = state.isFloatingMode,
                        specialKeyTextColor = specialKeyTextColor,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )

                }
            }
            } // candidatePageExpanded else

            val configuration = LocalConfiguration.current
            val isLandscapeBottom = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }

        if (state.isDeploying) {
            val isError = state.deploymentMessage.contains("超时") || state.deploymentMessage.contains("失败")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(keyboardBgColor.copy(alpha = 0.9f))
                    .clickable(enabled = isError && callbacks.onDismissDeploying != null) {
                        callbacks.onDismissDeploying?.invoke()
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.deploymentMessage.ifEmpty { "正在初始�?.." },
                        color = keyTextColor,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                    )
                    if (isError) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "点击关闭",
                            color = keyTextColor.copy(alpha = 0.5f),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = "???",
                            color = keyTextColor.copy(alpha = 0.7f),
                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // 长按候选删除自造词：键盘视图内确认覆盖层（同窗口不夺焦点，
        // 避免焦点型弹窗导致 IME 被系统收起）；确认后按来源分发——
        // 候选栏走 delete_candidate_on_current_page，展开页走全局索引删除。
        deletePending?.let { pending ->
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { deletePending = null },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.widthIn(max = 300.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                        Text(
                            text = "删除自造词",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "将「${pending.word}」从用户词典中移除？",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = {
                                onHapticFeedback?.invoke()
                                deletePending = null
                            }) {
                                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = {
                                onHapticFeedback?.invoke()
                                deletePending = null
                                pending.onConfirm()
                            }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        if (page is KeyboardPage.Overlay) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
            ) {
            when (val p = page) {
                is KeyboardPage.Overlay -> when (p.route) {
                    is OverlayRoute.Menu -> MenuBar(
                        state = MenuBarState(
                            isVisible = true,
                            isDarkTheme = state.isDarkTheme,
                            darkMode = state.darkMode,
                            backgroundColor = keyboardBgColor,
                            keyBgColor = keyBgColor,
                            keyTextColor = keyTextColor,
                            isFloatingMode = state.isFloatingMode,
                            schemaSwitches = state.schemaSwitches,
                        ),
                        callbacks = MenuBarCallbacks(
                            onDismiss = { onHapticFeedback?.invoke(); viewModel.closeOverlay() },
                            onClipboard = { onHapticFeedback?.invoke(); viewModel.showOverlay(OverlayRoute.Clipboard(0)); callbacks.onClipboard?.invoke() },
                            onQuickSend = { onHapticFeedback?.invoke(); viewModel.showOverlay(OverlayRoute.Clipboard(1)); callbacks.onQuickSend?.invoke() },
                            onKeyboardResize = { onHapticFeedback?.invoke(); callbacks.onKeyboardResize?.invoke(); viewModel.closeOverlay() },
                            onEmoji = { onHapticFeedback?.invoke(); viewModel.showOverlay(OverlayRoute.Emoji) },
                            onReloadConfig = { onHapticFeedback?.invoke(); callbacks.onReloadConfig?.invoke(); viewModel.closeOverlay() },
                            onSettings = { onHapticFeedback?.invoke(); callbacks.onSettings?.invoke(); viewModel.closeOverlay() },
                            onSchemaList = { onHapticFeedback?.invoke(); viewModel.pushOverlay(OverlayRoute.SchemaList) },
                            onToggleDarkMode = { onHapticFeedback?.invoke(); callbacks.onToggleDarkMode?.invoke() },
                            onToolbarCustomize = { onHapticFeedback?.invoke(); viewModel.showOverlay(OverlayRoute.ToolbarCustomize) },
                            onFloatingModeToggle = { onHapticFeedback?.invoke(); callbacks.onFloatingModeChange?.invoke(!state.isFloatingMode); viewModel.closeOverlay() },
                            onToggleSchemaSwitch = { sw -> onHapticFeedback?.invoke(); callbacks.onToggleSchemaSwitch?.invoke(sw); viewModel.closeOverlay() },
                        ),
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    )
                    is OverlayRoute.SchemaList -> SchemaListView(
                        schemas = state.schemas,
                        currentSchemaId = state.currentSchemaId,
                        backgroundColor = keyboardBgColor,
                        accentColor = accentColor,
                        keyTextColor = keyTextColor,
                        keyBgColor = keyBgColor,
                        onSelectSchema = { schemaId ->
                            callbacks.onSwitchSchema?.invoke(schemaId)
                            viewModel.closeOverlay()
                        },
                        onBack = { viewModel.popOverlay() },
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    )
                    is OverlayRoute.Clipboard -> ClipboardView(
                        clipboardItems = state.clipboardItems,
                        quickSendItems = state.quickSendItems,
                        selectedTab = p.route.tab,
                        backgroundColor = keyboardBgColor,
                        keyTextColor = keyTextColor,
                        keyBgColor = keyBgColor,
                        viewModel = viewModel,
                        onSelectItem = { text ->
                            callbacks.onClipboardSelect?.invoke(text)
                            viewModel.closeOverlay()
                        },
                        onSplitWords = { text, _ -> viewModel.pushOverlay(OverlayRoute.SplitWords(text)) },
                        onBack = { viewModel.closeOverlay() },
                        onClipboardTabChange = { viewModel.pushOverlay(OverlayRoute.Clipboard(it)) },
                        bottomPaddingDp = state.keyboardBottomPaddingDp,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        onQuickSendAddClick = {
                            viewModel.closeOverlay()
                            callbacks.onShowQuickSendForm?.invoke()
                        },
                        onQuickSendEditItem = { id, text, code ->
                            viewModel.closeOverlay()
                            callbacks.onQuickSendEditItem?.invoke(id, text, code)
                        },
                        onPullRemote = callbacks.onClipboardPullRemote,
                        pullRemoteAvailable = state.clipboardSyncEnabled,
                    )
                    is OverlayRoute.ToolbarCustomize -> ToolbarCustomizeView(
                        toolbarButtons = state.toolbarButtons,
                        pluginButtons = state.toolbarPluginButtons,
                        keyTextColor = keyTextColor,
                        backgroundColor = keyboardBgColor,
                        accentColor = accentColor,
                        keyBgColor = keyBgColor,
                        onUpdateToolbarButtons = callbacks.onUpdateToolbarButtons,
                        onDismiss = { viewModel.closeOverlay() },
                        bottomPaddingDp = state.keyboardBottomPaddingDp,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    )
                    is OverlayRoute.Edit -> {
                        val editAction: (String) -> Unit = { action ->
                            when (action) {
                                "delete" -> callbacks.onKeyPress("delete", false)
                                "enter" -> callbacks.onKeyPress("enter", false)
                                else -> callbacks.onToolbarEditingAction?.invoke(action)
                            }
                        }
                        EditKeyboardLayout(
                            onAction = editAction,
                            onBack = { viewModel.closeOverlay() },
                            backgroundColor = keyboardBgColor,
                            textColor = keyTextColor,
                            accentColor = accentColor,
                            keyBgColor = keyBgColor,
                            bottomPaddingDp = state.keyboardBottomPaddingDp,
                            keyCornerRadius = kbKey.cornerRadius.dp,
                            shadowEnabled = kbShadow.enabled,
                            shadowElevation = kbShadow.elevation.dp,
                            shadowShapeRadius = kbShadow.shapeRadius.dp,
                            modifier = Modifier.fillMaxWidth().fillMaxHeight()
                        )
                    }
                    is OverlayRoute.Emoji -> EmojiKeyboardLayout(
                        onEmojiSelect = { emoji ->
                            onHapticFeedback?.invoke()
                            if (emoji == "delete") {
                                callbacks.onKeyPress("delete", false)
                            } else {
                                callbacks.onCommitText?.invoke(emoji)
                            }
                        },
                        onImageEmojiSelect = callbacks.onCommitImage,
                        onBack = { viewModel.closeOverlay() },
                        backgroundColor = keyboardBgColor,
                        textColor = keyTextColor,
                        accentColor = accentColor,
                        bottomPaddingDp = state.keyboardBottomPaddingDp,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        onHapticFeedback = onHapticFeedback,
                    )
                    is OverlayRoute.Symbol -> SymbolKeyboardLayout(
                        onSelect = { symbol ->
                            onHapticFeedback?.invoke()
                            if (symbol == "delete") {
                                callbacks.onKeyPress("delete", false)
                            } else {
                                callbacks.onCommitText?.invoke(symbol)
                            }
                        },
                        onBack = { viewModel.closeOverlay() },
                        backgroundColor = keyboardBgColor,
                        textColor = keyTextColor,
                        accentColor = accentColor,
                        keyBgColor = keyBgColor,
                        bottomPaddingDp = state.keyboardBottomPaddingDp,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        onHapticFeedback = onHapticFeedback,
                    )
                    is OverlayRoute.SplitWords -> SplitWordsView(
                        text = p.route.text,
                        backgroundColor = keyboardBgColor,
                        viewModel = viewModel,
                        onBack = { viewModel.popOverlay() },
                        onNavigateToQuickSend = { viewModel.pushOverlay(OverlayRoute.Clipboard(1)) },
                        onSelectChar = { char -> callbacks.onCommitText?.invoke(char) },
                        onDeleteText = { count -> callbacks.onDeleteText?.invoke(count) },
                        bottomPaddingDp = state.keyboardBottomPaddingDp,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    )
                    is OverlayRoute.ToolPanel -> InfoPanel(
                        title = state.toolPanelTitle,
                        nodes = state.toolPanelUiNodes ?: emptyList(),
                        items = state.toolPanelItems,
                        isLoading = state.toolPanelLoading,
                        backgroundColor = keyboardBgColor,
                        textColor = keyTextColor,
                        accentColor = accentColor,
                        itemBgColor = keyBgColor,
                        bottomPaddingDp = state.keyboardBottomPaddingDp,
                        onClose = { callbacks.onToolPanelClose?.invoke() },
                        onAction = { actionId -> callbacks.onToolPanelAction?.invoke(actionId) },
                        onItemClick = { item -> callbacks.onToolPanelItemClick?.invoke(item) },
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    )
                }
                else -> {}
            }
        }
        }
    }
}
}
}



/** 长按删除待确认项：词文本 + 用户确认后执行的删除动作（候选栏/展开页共用）。 */
private data class DeletePendingWord(
    val word: String,
    val onConfirm: () -> Unit,
)
