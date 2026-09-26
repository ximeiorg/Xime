package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.keyboard.MainType
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.PanelType
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import com.kingzcheung.xime.plugin.core.api.PluginResultItem
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState
import com.kingzcheung.xime.ui.keyboard.transition
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutAction
import com.kingzcheung.xime.ui.keyboard.KeyboardViewState
import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import com.kingzcheung.xime.ui.keyboard.initialKeyboardLayoutState
import com.kingzcheung.xime.util.FileLogger

enum class ShiftMode { OFF, SINGLE, CAPS }

/**
 * 菜单栏中动态展示的一个方案开关（来自 schema 的 `switches`）。
 * [name] 非空为布尔开关，[options] 非空为多选一开关；[currentIndex] 为当前状态在 [states] 中的下标。
 * [abbrev] 为自定义缩写（可能每个状态一个），用于菜单栏标题展示。
 */
data class SchemaSwitchUiState(
    val name: String = "",
    val options: List<String> = emptyList(),
    val states: List<String> = emptyList(),
    val abbrev: List<String> = emptyList(),
    val currentIndex: Int = 0,
)

data class KeyboardUiState(
    val isAsciiMode: Boolean = false,
    val schemaName: String = "",
    val currentSchemaId: String = "",
    val schemas: List<SchemaInfo> = emptyList(),
    val schemaSwitches: List<SchemaSwitchUiState> = emptyList(),
    val enterKeyText: String = "发送",
    val isDarkTheme: Boolean = false,
    val darkMode: Int = 2,
    val themeId: String = "ocean_blue",
    val keyboardHeightDp: Int = 0,
    val keyboardBottomPaddingDp: Int = 0,
    val isDeploying: Boolean = false,
    val deploymentMessage: String = "",
    val clipboardItems: List<ClipboardItem> = emptyList(),
    val quickSendItems: List<ClipboardItem> = emptyList(),
    val recentClipboardItems: List<ClipboardItem> = emptyList(),
    val isVoiceMode: Boolean = false,
    val voiceSticky: Boolean = false,
    val voiceBottomActive: Boolean = false,
    val voiceLeftActive: Boolean = false,
    val voiceRightActive: Boolean = false,
    val voicePluginName: String = "",
    val voiceRecognitionState: RecognitionState = RecognitionState.IDLE,
    val voiceRecognizedText: String = "",
    val isSttEnabled: Boolean = true,
    val toolbarButtons: List<String> = ToolbarButton.DEFAULT_VISIBLE.map { it.id },
    /** 已启用插件声明的工具栏按钮（候选池），渲染时与内置按钮合并。 */
    val toolbarPluginButtons: List<ToolbarButtonItem.Plugin> = emptyList(),
    val isCalculatorMode: Boolean = false,
    val inputSessionId: Long = 0L,
    val isFloatingMode: Boolean = false,
    val isHandwritingMode: Boolean = false,
    val floatingOffsetX: Int = 0,
    val floatingOffsetY: Int = 0,
    val floatingMinOffsetY: Int = 0,
    val t9ResetSignal: Long = 0L,
    val swipeCancelEpoch: Long = 0L,
    val t9RightCandidateSelectedCount: Long = 0L,
    val t9SelectedCandidatePinyin: String = "",
    val showQuickSendForm: Boolean = false,
    val quickSendFormFocused: Boolean = false,
    val quickSendEditingItemId: Long? = null,
    val quickSendEditingItemText: String = "",
    val quickSendEditingItemCode: String = "",
    val toolPanelVisible: Boolean = false,
    val toolPanelInputFocused: Boolean = false,
    val toolPanelPluginId: String = "",
    val toolPanelTitle: String = "",
    val toolPanelPrefillText: String = "",
    val toolPanelItems: List<PluginResultItem> = emptyList(),
    val toolPanelLoading: Boolean = false,
    val toolPanelRequestEpoch: Long = 0,
    val toolPanelDisplay: String? = null,
    val toolPanelUiNodes: List<com.kingzcheung.xime.plugin.core.config.UiNode>? = null,
    val clipboardSyncEnabled: Boolean = false,
)

class KeyboardViewModel(application: Application) : AndroidViewModel(application) {

    val clipboardManager = ClipboardManager.getInstance(application)

    private val _isShifted = MutableStateFlow(false)
    val isShifted: StateFlow<Boolean> = _isShifted.asStateFlow()

    private val _shiftMode = MutableStateFlow(ShiftMode.OFF)
    val shiftMode: StateFlow<ShiftMode> = _shiftMode.asStateFlow()

    // 首帧布局：从持久化方案同步推导，不依赖引擎异步就绪。
    // 冷启动/服务重建时若用固定默认值，弹出键盘会先渲染全键盘、
    // 引擎就绪更新方案后才切到九键/笔画，表现为布局闪烁
    private val initialKbState = initialKeyboardLayoutState(
        isAsciiMode = false,
        schemaId = SettingsPreferences.getCurrentSchema(application),
    )

    private val _keyboardState = MutableStateFlow<KeyboardLayoutState>(initialKbState)
    val keyboardState: StateFlow<KeyboardLayoutState> = _keyboardState.asStateFlow()

    /** 最近一次停留的主键盘布局（中文/英文全键盘、九键、笔画）；
     *  数字面板据此判断进入来源，决定「返回/符号」键的位置自适应 */
    private val _lastMainLayout = MutableStateFlow<KeyboardLayoutState>(initialKbState)
    val lastMainLayout: StateFlow<KeyboardLayoutState> = _lastMainLayout.asStateFlow()

    private val _page = MutableStateFlow<KeyboardPage>(KeyboardPage.Main(MainType.FULL))
    val page: StateFlow<KeyboardPage> = _page.asStateFlow()

    /** 候选展开页：候选栏的在位展开态（非 Overlay——顶部保持真实候选栏，实时跟随编码/删除）。 */
    private val _candidatePageExpanded = MutableStateFlow(false)
    val candidatePageExpanded: StateFlow<Boolean> = _candidatePageExpanded.asStateFlow()

    /** 打开/收起候选展开页。 */
    fun setCandidatePageExpanded(expanded: Boolean) {
        _candidatePageExpanded.value = expanded
        if (!expanded) {
            // 筛选开关只在展开页左栏：收起后候选栏若仍被过滤将无处可关，自动复位
            _singleCharFilter.value = false
        }
    }

    // ── 展开页翻页联动 ──
    // 硬件键盘 DPAD 上/下经此事件流驱动展开页滚动一屏

    /** 硬件键盘翻页事件：+1 向下滚一屏、-1 向上滚一屏 */
    private val _expandedPageScrollEvents = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val expandedPageScrollEvents: SharedFlow<Int> = _expandedPageScrollEvents.asSharedFlow()

    /** 请求展开页滚动一屏（硬件键盘 DPAD 上/下） */
    fun requestExpandedPageScroll(direction: Int) {
        _expandedPageScrollEvents.tryEmit(direction)
    }

    /** "只看单字"过滤（展开页左栏底部切换按钮） */
    private val _singleCharFilter = MutableStateFlow(false)
    val singleCharFilter: StateFlow<Boolean> = _singleCharFilter.asStateFlow()

    fun toggleSingleCharFilter() {
        _singleCharFilter.value = !_singleCharFilter.value
    }


    /** 是否从 handwriting 进入英文键盘，用于 ASCII 切回时恢复 handwriting */
    var handwritingShouldReturn: Boolean = false

    /** 进入面板前保存的 keyboardState，用于 exitPanel 恢复 */
    private var _savedKbStateBeforePanel: KeyboardLayoutState? = null

    /** 键盘 ascii 状态机（顶层统一管理各键盘上下文的 ascii 记忆） */
    val asciiStateMachine = KeyboardAsciiStateMachine()

    /** 统一视图状态（替换 keyboardState + page 双轴） */
    private val _viewState = MutableStateFlow<KeyboardViewState>(
        when (initialKbState) {
            KeyboardLayoutState.T9Pinyin -> KeyboardViewState.T9PinyinFull
            KeyboardLayoutState.Stroke -> KeyboardViewState.StrokeFull
            KeyboardLayoutState.English -> KeyboardViewState.EnglishFull
            else -> KeyboardViewState.ChineseFull
        }
    )
    val viewState: StateFlow<KeyboardViewState> = _viewState.asStateFlow()

    /**
     * 单⼀状态转移入口 — 替代所有散落的 LaunchedEffect、setKeyboardState、switchMain 等。
     */
    /**
     * 按 action 携带（或显式传入）的 schemaId 同步键布局缓存。
     * dispatch 的各分支都会经由 initialKeyboardLayoutState 依据 schemaId 推导布局，
     * 合并键布局（pinyin_14jian 等）的行数据/手势缓存需在此之前切换到位。
     */
    private fun applySchemaLayout(action: KeyboardDispatchAction, schemaId: String) {
        val sid = schemaId.ifEmpty {
            when (action) {
                is KeyboardDispatchAction.AsciiModeChanged -> action.schemaId
                is KeyboardDispatchAction.InputSessionStarted -> action.schemaId
                else -> ""
            }
        }
        if (sid.isNotEmpty()) {
            KeysConfigHelper.setActiveKeyboardSchema(sid)
        }
    }

    fun dispatch(
        action: KeyboardDispatchAction,
        isAsciiMode: Boolean = false,
        schemaId: String = "",
    ) {
        // 布局随方案切换：合并键方案（pinyin_14jian 等）切换行布局与手势缓存，
        // 必须在状态更新前同步执行，保证重组时读到新布局
        applySchemaLayout(action, schemaId)
        val current = _viewState.value
        val (newState, newPage, newKbState) = when (action) {
            is KeyboardDispatchAction.ToggleChineseEnglish -> {
                when (current) {
                    KeyboardViewState.ChineseFull -> Triple(KeyboardViewState.EnglishFull, KeyboardPage.Main(MainType.FULL), KeyboardLayoutState.English)
                    KeyboardViewState.EnglishFull -> Triple(KeyboardViewState.ChineseFull, KeyboardPage.Main(MainType.FULL), KeyboardLayoutState.Chinese)
                    is KeyboardViewState.NumberPanel -> {
                        Triple(KeyboardViewState.ChineseFull, KeyboardPage.Main(current.returnTo), initialKeyboardLayoutState(isAsciiMode, schemaId))
                    }
                    is KeyboardViewState.CommonSymbolPanel -> {
                        Triple(KeyboardViewState.ChineseFull, KeyboardPage.Main(current.returnTo), initialKeyboardLayoutState(isAsciiMode, schemaId))
                    }
                    else -> Triple(current, _page.value, _keyboardState.value)
                }
            }
            is KeyboardDispatchAction.ModeChange -> {
                val target = if (action.targetIsNumber) KeyboardLayoutAction.SwitchToNumber
                    else KeyboardLayoutAction.SwitchToCommonSymbol
                val newKb = initialKeyboardLayoutState(isAsciiMode, schemaId).transition(target, isAsciiMode, schemaId)
                val newVs: KeyboardViewState = when (newKb) {
                    KeyboardLayoutState.Number -> KeyboardViewState.NumberPanel(MainType.FULL)
                    KeyboardLayoutState.CommonSymbol -> KeyboardViewState.CommonSymbolPanel(MainType.FULL)
                    else -> when (newKb) {
                        is KeyboardLayoutState.Chinese -> KeyboardViewState.ChineseFull
                        is KeyboardLayoutState.English -> KeyboardViewState.EnglishFull
                        is KeyboardLayoutState.T9Pinyin -> KeyboardViewState.T9PinyinFull
                        is KeyboardLayoutState.Stroke -> KeyboardViewState.StrokeFull
                        else -> current
                    }
                }
                val newPage = if (newKb is KeyboardLayoutState.Number || newKb is KeyboardLayoutState.CommonSymbol)
                    KeyboardPage.Panel(
                        if (newKb is KeyboardLayoutState.Number) PanelType.NUMBER else PanelType.COMMON_SYMBOL,
                        MainType.FULL
                    )
                else KeyboardPage.Main(MainType.FULL)
                Triple(newVs, newPage, newKb)
            }
            is KeyboardDispatchAction.ShowNumber -> {
                val returnTo = when (current) {
                    is KeyboardViewState.NumberPanel -> current.returnTo
                    is KeyboardViewState.CommonSymbolPanel -> current.returnTo
                    else -> MainType.FULL
                }
                Triple(KeyboardViewState.NumberPanel(returnTo), KeyboardPage.Panel(PanelType.NUMBER, returnTo), KeyboardLayoutState.Number)
            }
            is KeyboardDispatchAction.ShowCommonSymbol -> {
                val returnTo = when (current) {
                    is KeyboardViewState.NumberPanel -> current.returnTo
                    is KeyboardViewState.CommonSymbolPanel -> current.returnTo
                    else -> MainType.FULL
                }
                Triple(KeyboardViewState.CommonSymbolPanel(returnTo), KeyboardPage.Panel(PanelType.COMMON_SYMBOL, returnTo), KeyboardLayoutState.CommonSymbol)
            }
            is KeyboardDispatchAction.ExitPanel -> {
                when (current) {
                    is KeyboardViewState.NumberPanel, is KeyboardViewState.CommonSymbolPanel -> {
                        val returnTo = (current as? KeyboardViewState.NumberPanel)?.returnTo
                            ?: (current as KeyboardViewState.CommonSymbolPanel).returnTo
                        val (vs, kb) = when (returnTo) {
                            MainType.FULL -> {
                                val kb = initialKeyboardLayoutState(isAsciiMode, schemaId)
                                val vs = when (kb) {
                                    is KeyboardLayoutState.Chinese -> KeyboardViewState.ChineseFull
                                    is KeyboardLayoutState.English -> KeyboardViewState.EnglishFull
                                    is KeyboardLayoutState.T9Pinyin -> KeyboardViewState.T9PinyinFull
                                    is KeyboardLayoutState.Stroke -> KeyboardViewState.StrokeFull
                                    else -> KeyboardViewState.ChineseFull
                                }
                                vs to kb
                            }
                            MainType.HANDWRITING -> KeyboardViewState.Handwriting to KeyboardLayoutState.Chinese
                            MainType.STROKE -> KeyboardViewState.StrokeFull to KeyboardLayoutState.Stroke
                            MainType.VOICE -> KeyboardViewState.Voice to KeyboardLayoutState.English
                        }
                        Triple(vs, KeyboardPage.Main(returnTo), kb)
                    }
                    else -> Triple(current, _page.value, _keyboardState.value)
                }
            }
            is KeyboardDispatchAction.SwitchToT9Pinyin -> {
                Triple(KeyboardViewState.T9PinyinFull, KeyboardPage.Main(MainType.FULL), KeyboardLayoutState.T9Pinyin)
            }
            is KeyboardDispatchAction.SwitchToStroke -> {
                Triple(KeyboardViewState.StrokeFull, KeyboardPage.Main(MainType.FULL), KeyboardLayoutState.Stroke)
            }
            is KeyboardDispatchAction.SwitchToHandwriting -> {
                Triple(KeyboardViewState.Handwriting, KeyboardPage.Main(MainType.HANDWRITING), KeyboardLayoutState.Chinese)
            }
            is KeyboardDispatchAction.AsciiModeChanged -> {
                if (current is KeyboardViewState.Overlay) {
                    FileLogger.d("XimeKeyboard", "AsciiModeChanged skipped: current=$current (overlay)")
                    Triple(current, _page.value, _keyboardState.value)
                } else if (current is KeyboardViewState.NumberPanel || current is KeyboardViewState.CommonSymbolPanel) {
                    FileLogger.d("XimeKeyboard", "AsciiModeChanged skipped: current=$current (panel)")
                    Triple(current, _page.value, _keyboardState.value)
                } else if (!action.isAsciiMode && isHandwritingSchema(action.schemaId)) {
                    Triple(KeyboardViewState.Handwriting, KeyboardPage.Main(MainType.HANDWRITING), KeyboardLayoutState.Chinese)
                } else {
                    val kb = initialKeyboardLayoutState(action.isAsciiMode, action.schemaId)
                    val vs = when (kb) {
                        is KeyboardLayoutState.Chinese -> KeyboardViewState.ChineseFull
                        is KeyboardLayoutState.English -> KeyboardViewState.EnglishFull
                        is KeyboardLayoutState.T9Pinyin -> KeyboardViewState.T9PinyinFull
                        is KeyboardLayoutState.Stroke -> KeyboardViewState.StrokeFull
                        else -> KeyboardViewState.ChineseFull
                    }
                    FileLogger.d("XimeKeyboard", "AsciiModeChanged dispatch: current=$current, ascii=${action.isAsciiMode}, schemaId=${action.schemaId}, -> $vs / $kb")
                    Triple(vs, KeyboardPage.Main(MainType.FULL), kb)
                }
            }
            is KeyboardDispatchAction.InputSessionStarted -> {
                val currentPage = _page.value
                // 面板（数字/符号）不应被新会话事件重置，否则输入一个数字后键盘会切回全键盘
                if (current is KeyboardViewState.NumberPanel || current is KeyboardViewState.CommonSymbolPanel) {
                    Triple(current, _page.value, _keyboardState.value)
                } else if (currentPage is KeyboardPage.Main && currentPage.type == MainType.HANDWRITING
                    && (action.schemaId.isEmpty() || isHandwritingSchema(action.schemaId))
                ) {
                    // 仅在当前确为手写方案（或方案未知）时保持手写页；事件携带其他
                    // schemaId 说明引擎已切换，手写页是残留状态，需走下方重置切回
                    Triple(_viewState.value, currentPage, _keyboardState.value)
                } else {
                    val kb = initialKeyboardLayoutState(action.isAsciiMode, action.schemaId)
                    val vs = when (kb) {
                        is KeyboardLayoutState.Chinese -> KeyboardViewState.ChineseFull
                        is KeyboardLayoutState.English -> KeyboardViewState.EnglishFull
                        is KeyboardLayoutState.T9Pinyin -> KeyboardViewState.T9PinyinFull
                        is KeyboardLayoutState.Stroke -> KeyboardViewState.StrokeFull
                        else -> KeyboardViewState.ChineseFull
                    }
                    Triple(vs, KeyboardPage.Main(MainType.FULL), kb)
                }
            }
            is KeyboardDispatchAction.ShowOverlay -> {
                Triple(KeyboardViewState.Overlay(action.route, action.backStack, current), KeyboardPage.Overlay(action.route, action.backStack, _page.value), _keyboardState.value)
            }
            is KeyboardDispatchAction.CloseOverlay -> {
                val behind = (current as? KeyboardViewState.Overlay)?.behind ?: current
                val behindPage = (_page.value as? KeyboardPage.Overlay)?.behind ?: _page.value
                Triple(behind, behindPage, _keyboardState.value)
            }
            is KeyboardDispatchAction.PushOverlay -> {
                val ov = current as? KeyboardViewState.Overlay ?: return
                val ovPage = _page.value as? KeyboardPage.Overlay ?: return
                Triple(
                    KeyboardViewState.Overlay(action.route, ov.backStack + ov.route, ov.behind),
                    KeyboardPage.Overlay(action.route, ovPage.backStack + ovPage.route, ovPage.behind),
                    _keyboardState.value
                )
            }
            is KeyboardDispatchAction.PopOverlay -> {
                val ov = current as? KeyboardViewState.Overlay ?: return
                val ovPage = _page.value as? KeyboardPage.Overlay ?: return
                if (ov.backStack.isEmpty()) return
                val prevRoute = ov.backStack.last()
                Triple(
                    KeyboardViewState.Overlay(prevRoute, ov.backStack.dropLast(1), ov.behind),
                    KeyboardPage.Overlay(prevRoute, ovPage.backStack.dropLast(1), ovPage.behind),
                    _keyboardState.value
                )
            }
        }
        _viewState.value = newState
        _page.value = newPage
        if (newKbState is KeyboardLayoutState.English) {
            _isShifted.value = false
            _shiftMode.value = ShiftMode.OFF
        }
        _keyboardState.value = newKbState
        _syncViewState()
    }

    fun toggleShift() {
        _isShifted.update { !it }
    }

    fun setShifted(shifted: Boolean) {
        _isShifted.value = shifted
    }

    fun singleTapShift() {
        when (_shiftMode.value) {
            ShiftMode.OFF -> {
                _isShifted.value = true
                _shiftMode.value = ShiftMode.SINGLE
            }
            ShiftMode.SINGLE, ShiftMode.CAPS -> {
                _isShifted.value = false
                _shiftMode.value = ShiftMode.OFF
            }
        }
    }

    fun doubleTapShift() {
        when (_shiftMode.value) {
            ShiftMode.CAPS -> {
                _isShifted.value = false
                _shiftMode.value = ShiftMode.OFF
            }
            else -> {
                _isShifted.value = true
                _shiftMode.value = ShiftMode.CAPS
            }
        }
    }

    fun onCharacterTyped() {
        if (_shiftMode.value == ShiftMode.SINGLE) {
            _isShifted.value = false
            _shiftMode.value = ShiftMode.OFF
        }
    }

    fun setKeyboardState(state: KeyboardLayoutState) {
        val prevKb = _keyboardState.value
        if (state is KeyboardLayoutState.English) {
            _isShifted.value = false
            _shiftMode.value = ShiftMode.OFF
        } else {
            handwritingShouldReturn = false
        }
        _keyboardState.value = state
        _syncViewState()
        if (prevKb != state) {
            FileLogger.d("XimeKeyboard", "setKeyboardState: $prevKb -> $state, vs=${_viewState.value}, page=${_page.value}")
        }
    }
    
    private fun _syncViewState() {
        val kb = _keyboardState.value
        val p = _page.value
        // 主键盘布局真正显示时刷新「最近主布局」来源记录（数字面板返回/符号键位置自适应依赖）。
        // 必须放在 _syncViewState：切九键/笔画等走 dispatch 直接赋值，不经过 setKeyboardState
        if (p is KeyboardPage.Main && p.type == com.kingzcheung.xime.keyboard.MainType.FULL) {
            when (kb) {
                is KeyboardLayoutState.Chinese,
                is KeyboardLayoutState.English,
                is KeyboardLayoutState.T9Pinyin,
                is KeyboardLayoutState.Stroke -> _lastMainLayout.value = kb
                else -> {}
            }
        }
        val vs: KeyboardViewState = when (p) {
            is KeyboardPage.Overlay -> KeyboardViewState.Overlay(p.route, p.backStack, _viewState.value.let { if (it is KeyboardViewState.Overlay) it.behind else it })
            is KeyboardPage.Panel -> when (p.type) {
                com.kingzcheung.xime.keyboard.PanelType.NUMBER -> KeyboardViewState.NumberPanel(p.returnTo)
                com.kingzcheung.xime.keyboard.PanelType.COMMON_SYMBOL -> KeyboardViewState.CommonSymbolPanel(p.returnTo)
            }
            is KeyboardPage.Main -> when (p.type) {
                com.kingzcheung.xime.keyboard.MainType.FULL -> when (kb) {
                    is KeyboardLayoutState.Chinese -> KeyboardViewState.ChineseFull
                    is KeyboardLayoutState.English -> KeyboardViewState.EnglishFull
                    is KeyboardLayoutState.T9Pinyin -> KeyboardViewState.T9PinyinFull
                    is KeyboardLayoutState.Stroke -> KeyboardViewState.StrokeFull
                    is KeyboardLayoutState.Number -> KeyboardViewState.NumberPanel(com.kingzcheung.xime.keyboard.MainType.FULL)
                    is KeyboardLayoutState.CommonSymbol -> KeyboardViewState.CommonSymbolPanel(com.kingzcheung.xime.keyboard.MainType.FULL)
                    else -> KeyboardViewState.ChineseFull
                }
                com.kingzcheung.xime.keyboard.MainType.HANDWRITING -> KeyboardViewState.Handwriting
                com.kingzcheung.xime.keyboard.MainType.STROKE -> KeyboardViewState.StrokeFull
                com.kingzcheung.xime.keyboard.MainType.VOICE -> KeyboardViewState.Voice
            }
        }
        _viewState.value = vs
    }

    fun resetShift() {
        _isShifted.value = false
        _shiftMode.value = ShiftMode.OFF
    }

    // ── Page Navigation ──

    /** Level 1: 切换主键盘类型 */
    fun switchMain(type: MainType) {
        _page.value = KeyboardPage.Main(type)
        if (type == MainType.FULL) {
            _keyboardState.value = KeyboardLayoutState.Chinese
        }
        _syncViewState()
    }

    private var savedKbStateBeforeVoice: KeyboardLayoutState? = null

    fun enterVoice() {
        savedKbStateBeforeVoice = _keyboardState.value
        _page.value = KeyboardPage.Main(MainType.VOICE)
        _syncViewState()
    }

    fun exitVoice() {
        val saved = savedKbStateBeforeVoice ?: return
        _page.value = KeyboardPage.Main(MainType.FULL)
        _keyboardState.value = saved
        savedKbStateBeforeVoice = null
        _syncViewState()
    }

    /** Level 2: 进入面板（编号/符号等），可从任意页面进入 */
    fun enterPanel(type: PanelType) {
        val current = _page.value
        val mainType = when (current) {
            is KeyboardPage.Main -> current.type
            is KeyboardPage.Panel -> current.returnTo
            is KeyboardPage.Overlay -> {
                val behind = current.behind
                if (behind is KeyboardPage.Main) behind.type
                else MainType.FULL
            }
        }
        if (_savedKbStateBeforePanel == null) {
            _savedKbStateBeforePanel = _keyboardState.value
        }
        _page.value = KeyboardPage.Panel(type, mainType)
        _keyboardState.value = when (type) {
            PanelType.NUMBER -> KeyboardLayoutState.Number
            PanelType.COMMON_SYMBOL -> KeyboardLayoutState.CommonSymbol
        }
        _syncViewState()
    }

    /** Level 2: 退出面板，回到主键盘 */
    fun exitPanel() {
        val current = _page.value
        if (current is KeyboardPage.Panel) {
            _page.value = KeyboardPage.Main(current.returnTo)
            val saved = _savedKbStateBeforePanel
            _savedKbStateBeforePanel = null
            _keyboardState.value = when (current.returnTo) {
                com.kingzcheung.xime.keyboard.MainType.FULL -> saved ?: KeyboardLayoutState.Chinese
                else -> KeyboardLayoutState.Chinese
            }
            _syncViewState()
        }
    }

    /** Level 3: 打开覆盖页面，可从任意页面进入 */
    fun showOverlay(route: OverlayRoute, initialBackStack: List<OverlayRoute> = emptyList()) {
        val current = _page.value
        val behind = if (current is KeyboardPage.Overlay) current.behind else current
        _page.value = KeyboardPage.Overlay(route, initialBackStack, behind)
        _syncViewState()
    }

    /** Level 3: 在覆盖页面内推入子页 */
    fun pushOverlay(route: OverlayRoute) {
        val current = _page.value
        if (current is KeyboardPage.Overlay) {
            _page.value = current.copy(
                route = route,
                backStack = current.backStack + current.route
            )
            _syncViewState()
        }
    }

    /** Level 3: 在覆盖页面内回退 */
    fun popOverlay() {
        val current = _page.value
        if (current is KeyboardPage.Overlay && current.backStack.isNotEmpty()) {
            val prev = current.backStack.last()
            _page.value = current.copy(
                route = prev,
                backStack = current.backStack.dropLast(1)
            )
            _syncViewState()
        }
    }

    /** Level 3: 关闭覆盖页面，回到背后页面 */
    fun closeOverlay() {
        val current = _page.value
        if (current is KeyboardPage.Overlay) {
            _page.value = current.behind
            _syncViewState()
        }
    }

    fun resetKeyboard(isAsciiMode: Boolean, schemaId: String = "", forceNumberPanel: Boolean = false) {
        _isShifted.value = false
        _shiftMode.value = ShiftMode.OFF
        _candidatePageExpanded.value = false
        KeysConfigHelper.setActiveKeyboardSchema(schemaId)
        if (forceNumberPanel) {
            // 数字输入框自动进入数字面板：记录默认主布局，供面板"abc"返回键恢复
            // （exitPanel 对 FULL returnTo 优先用 _savedKbStateBeforePanel）
            _keyboardState.value = KeyboardLayoutState.Number
            _savedKbStateBeforePanel = initialKeyboardLayoutState(isAsciiMode, schemaId)
            _page.value = KeyboardPage.Panel(PanelType.NUMBER, MainType.FULL)
        } else {
            _keyboardState.value = initialKeyboardLayoutState(isAsciiMode, schemaId)
            if (_page.value !is KeyboardPage.Main) {
                _page.value = KeyboardPage.Main(MainType.FULL)
            }
        }
        // 必须重新推导 viewState：否则 _viewState 停留在旧的面板/覆盖值，
        // 与 UI 实际渲染的 keyboardState 脱节，后续 AsciiModeChanged 会被误判为 panel/overlay 而跳过。
        _syncViewState()
    }

    // Clipboard operations

    fun removeClipboardItem(id: Long) {
        clipboardManager.removeItem(id)
    }

    fun removeClipboardItems(ids: List<Long>) {
        clipboardManager.removeItems(ids)
    }

    fun splitClipboardItem(id: Long) {
        clipboardManager.splitItem(id)
    }

    fun clearClipboard() {
        clipboardManager.clearClipboard()
    }

    fun addToQuickSend(id: Long) {
        clipboardManager.addToQuickSend(id)
    }

    fun addQuickSendText(text: String, code: String = "") {
        clipboardManager.addQuickSendItem(text, code)
    }

    fun updateQuickSendItem(id: Long, text: String, code: String = "") {
        clipboardManager.updateQuickSendItem(id, text, code)
    }

    fun removeQuickSendItem(id: Long) {
        clipboardManager.removeFromQuickSend(id)
    }

    fun togglePinQuickSend(id: Long) {
        clipboardManager.togglePinQuickSend(id)
    }
}
