package com.kingzcheung.xime.service

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.kingzcheung.xime.ui.keyboard.LocalStretchFactor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ui.keyboard.KeyboardResizeOverlay
import com.kingzcheung.xime.ui.keyboard.HardwareKeyboardCandidateBar
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.asCoroutineDispatcher
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kingzcheung.xime.MainActivity
import com.kingzcheung.xime.association.AssociationManager
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import com.kingzcheung.xime.association.AssociationService
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.clipboard.sync.ClipboardSyncBridge
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.ToolPlugin
import com.kingzcheung.xime.plugin.core.api.ToolResult
import com.kingzcheung.xime.plugin.core.js.PluginEvent
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.speech.AsrBackendFactory
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.rime.buildT9DisplayState
import com.kingzcheung.xime.rime.resolveRimeCandidateIndex

import com.kingzcheung.xime.settings.SchemaConfigHelper
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.keyboardBackground
import kotlin.math.roundToInt
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.util.PreeditMergeHelper
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.keyboard.ActionExecutor
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import com.kingzcheung.xime.plugin.core.api.PluginResultItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import android.os.Bundle
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.core.view.updateLayoutParams
import java.io.File
import java.io.FileInputStream

object QuickSendFormEditTextHolder {
    var editText: android.widget.EditText? = null
}

/** 快捷发送表单的触发编码输入框 holder（与内容输入框独立）。 */
object QuickSendFormCodeEditTextHolder {
    var editText: android.widget.EditText? = null
}

/** 通用工具面板输入框 holder（与快捷发送独立，避免互相覆盖）。 */
object ToolPanelEditTextHolder {
    /** 当前聚焦的输入框（软/硬按键路由目标；主输入框与控件行字段共用，谁聚焦指向谁）。 */
    var editText: android.widget.EditText? = null

    /** 主输入框（预填与生成取词的锚点；控件行字段聚焦时与 editText 不同）。 */
    var main: android.widget.EditText? = null

    /** 用户是否手动编辑过主输入框：true 后宿主不再回填 prefill（防覆盖用户正在输入的内容）。 */
    var userEdited: Boolean = false

    /** 宿主程序化写入输入框期间的抑制标志：写入不标记 userEdited。 */
    var applyingProgrammatically: Boolean = false
}

/**
 * T9 九键 partial commit 段：右选部分提交时累积的（文本, 拼音）对。
 * 文本用于 preedit 拼接与上屏，拼音用于用户词典调频/回滚，两者同源同步维护。
 */
data class T9PartialSegment(val text: String, val pinyin: String)

class XimeInputMethodService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, ActionExecutor {

    companion object {
        internal const val TAG = "XimeInputMethodService"
        private const val DARK_MODE_LIGHT = 0
        private const val DARK_MODE_DARK = 1
        private const val DARK_MODE_SYSTEM = 2
        private const val HARDWARE_CANDIDATE_BAR_HEIGHT = 72
        internal const val SAFE_TEXT_LIMIT = 262144

        /** 面板 loading 延迟显示阈值：此时间内完成的动作不显示进度条（面板高度也不变，防闪烁）。 */
        private const val TOOL_PANEL_LOADING_SHOW_DELAY_MS = 250L
    }

    /** release 构建不输出调试日志，减少 logcat 写入开销。 */
    private fun debugLog(msg: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, msg)
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    internal val rimeEngine = RimeEngine.getInstance()
    
    internal lateinit var clipboardManager: ClipboardManager

    internal var clipboardSyncBridge: ClipboardSyncBridge? = null
    
    internal lateinit var keyboardContainer: VoiceKeyboardContainer
    
    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal val keyProcessingDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "key-process").also { it.isDaemon = true }
    }.asCoroutineDispatcher()
    
    internal val keyJobs = Channel<Job>(Channel.UNLIMITED)
    internal val uiEventChannel = Channel<suspend () -> Unit>(Channel.CONFLATED)

    /**
     * 长按退格合并锁/状态。
     *
     * 长按退格以约 80ms 的固定频率重复派发，而 rime 退格（JNI + 输入重组）耗时可能
     * 超过 80ms。若每次重复都排队，keyJobs 会堆积，候选栏 UI 更新变成"迟到的跳帧"
     * 突发式刷新（一闪一闪）。这里把高频重复的退格合并为单个 job：处理完一次后
     * 立即消费累积的 [pendingDeleteCount]，删除速率被 rime 吞吐自然限制，
     * UI 更新平滑，抬手后也不会洪水式多删。
     */
    internal val deleteCoalesceLock = Any()
    internal var deleteJobActive = false
    internal var pendingDeleteCount = 0

    init {
        serviceScope.launch {
            keyJobs.consumeEach { job ->
                job.join()
            }
        }
        serviceScope.launch(Dispatchers.Main) {
            uiEventChannel.consumeEach { work -> work() }
        }
    }
    
    internal val mainHandler = Handler(Looper.getMainLooper())
    
    internal val uiState = mutableStateOf(InputUIState())
    internal val candidateState = mutableStateOf(CandidateState())
    private val clipboardItemsState = mutableStateOf<List<com.kingzcheung.xime.clipboard.ClipboardItem>>(emptyList())
    private val voiceAmplitudeState = mutableFloatStateOf(0f)
    private val voiceSpectrumState = mutableStateOf(FloatArray(16))
    private val quickSendItemsState = mutableStateOf<List<com.kingzcheung.xime.clipboard.ClipboardItem>>(emptyList())
    internal val recentClipboardItemsState = mutableStateOf<List<com.kingzcheung.xime.clipboard.ClipboardItem>>(emptyList())


    private val bottomInsetPxState = mutableStateOf(0)
    private var hasHardwareKeyboard = false
    /** 当前输入框是否受限（密码/终端/NO_SUGGESTIONS，见 EditorInfoClassifier）。
     *  主线程写（onStartInput）、key-processing 线程读（英文联想短路），volatile 保证可见性。 */
    @Volatile
    private var editorRestricted: Boolean = false
    /** 秘密输入框（密码/TYPE_NULL）：英文联想与回删替换的统一禁用线 */
    private var editorSecret: Boolean = false
    private var floatingWinX = 100
    private var floatingWinY = 300
    
    internal var isTrackingVoiceButtons = false
    internal var voiceRecordingStarted = false
    private var pendingVoiceAction: (() -> Unit)? = null
    internal var composeViewRef: View? = null
    internal var lastClearedText: String = ""
    /** 累积的 partial commit 段列表（多段选词场景下逐段追加，文本+拼音同源，供调频/回滚） */
    internal val t9PartialSegments = mutableListOf<T9PartialSegment>()
    /** 键盘回调引用，用于在 RIME selectCandidate 前同步通知 T9 控制器 */
    internal var keyboardCallbacks: KeyboardCallbacks? = null
    internal var isChineseMode = true
    internal var currentEffectiveKeyboardHeight: Int = 0
    internal var currentFloatingCardHeightDp: Int = 0
    internal var previousSchemaId: String = ""
    
    internal val calculatorEngine = com.kingzcheung.xime.calculator.CalculatorEngine()

    private val _viewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = _viewModelStore

    internal val keyboardViewModel: KeyboardViewModel by lazy {
        ViewModelProvider(
            _viewModelStore,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(applicationContext as android.app.Application)
        ).get(KeyboardViewModel::class.java)
    }

    /** 候选展开页自动收起：候选与联想均空（编码删空）时收起在位展开页，不留空页。
     *  在状态生产端调用（而非依赖 UI 重组观察）——候选变化时键盘区作用域被设计为跳过重组。 */
    internal fun maybeCollapseCandidatePage() {
        if (!keyboardViewModel.candidatePageExpanded.value) return
        val cs = candidateState.value
        if (cs.candidates.isEmpty() && cs.associationCandidates.isEmpty()) {
            keyboardViewModel.setCandidatePageExpanded(false)
        }
    }

    /** 刷新展开页的跨页全量候选；非展开态清空以省内存。编码变化与展开动作时调用 */
    internal fun refreshExpandedCandidates() {
        if (!keyboardViewModel.candidatePageExpanded.value) {
            if (candidateState.value.expandedCandidates.isNotEmpty()) {
                candidateState.value = candidateState.value.copy(expandedCandidates = emptyList())
            }
            return
        }
        val perfT0 = android.os.SystemClock.elapsedRealtime()
        val all = rimeEngine.getAllCandidates().toList()
        candidateState.value = candidateState.value.copy(expandedCandidates = all)
        android.util.Log.d(
            "CandidatePerf",
            "refreshExpandedCandidates: count=${all.size} cost=${android.os.SystemClock.elapsedRealtime() - perfT0}ms"
        )
    }

    /** 硬件键盘在展开态按 DPAD_DOWN/UP：展开页滚动一屏（经事件流驱动 UI） */
    private fun expandedPageScroll(direction: Int) {
        keyboardViewModel.requestExpandedPageScroll(direction)
    }
    
    internal val predictionManager = PredictionManager(
        context = this,
        serviceScope = serviceScope,
        onPredictionResult = { candidates ->
            candidateState.value = candidateState.value.copy(
                associationCandidates = candidates
            )
        },
    )
    
    internal val voiceRecognitionHandler = VoiceRecognitionHandler(
        context = this,
        onStateChanged = { newState -> uiState.value = newState },
        getState = { uiState.value },
        getInputConnection = { currentInputConnection },
        onVoiceComplete = {
            val action = pendingVoiceAction
            pendingVoiceAction = null
            action?.invoke()

            restoreAfterVoiceFinish()
        },
        onAmplitudeChanged = { amplitude ->
            voiceAmplitudeState.floatValue = amplitude
        },
        onSpectrumChanged = { spectrum ->
            voiceSpectrumState.value = spectrum
        },
        onComposingWritten = { markInputBoxComposing() }
    )

    /**
     * 结束语音会话的统一出口：停止预启动并进入收尾——等待引擎最终结果后提交，
     * 超时回退提交部分结果（见 VoiceRecognitionHandler.finishRecognition）。
     * 键盘状态在收尾完成时经 onVoiceComplete → [restoreAfterVoiceFinish] 恢复，
     * 期间语音面板保持"正在识别..."显示，避免用户感知到结果被截断。
     * 幂等：收尾已在进行中时重复调用自动跳过。
     */
    internal fun endVoiceSession() {
        voiceRecognitionHandler.cancelPreStart()
        voiceRecognitionHandler.finishRecognition()
    }

    /** 语音会话真正完成（最终结果已提交/超时兜底/出错）后恢复键盘状态。幂等。 */
    internal fun restoreAfterVoiceFinish() {
        // 兜底：会话结束的任何路径都确保录音已请求停止（幂等，正常松手路径
        // recordingThread 已置空时直接返回）。UI 状态一旦清零，松手停止链就失效，
        // 这里漏一次 stop 麦克风/引擎连接就会一直后台占用。
        voiceRecognitionHandler.stopRecognition()
        keyboardViewModel.exitVoice()
        isTrackingVoiceButtons = false
        voiceRecordingStarted = false
        voiceAmplitudeState.floatValue = 0f
        uiState.value = uiState.value.copy(
            isVoiceMode = false,
            voiceSticky = false,
            voiceButtonState = VoiceButtonState(),
            voiceRecognitionState = RecognitionState.IDLE,
            voiceRecognizedText = "",
            voiceAmplitude = 0f
        )
    }
    
    private var sharedPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var clipboardCollectorJob: kotlinx.coroutines.Job? = null
    
    internal val feedbackManager = FeedbackManager(this)

    internal val keyRouter = ImeKeyRouter(this)

    internal val sessionController = ImeSessionController(this)

    internal val schemaController = ImeSchemaController(this)

    /** ascii（中/西文）模式唯一控制入口：切换/会话决策/归位均收敛于此 */
    internal val asciiModeController = AsciiModeController(this)

    internal val textCommit = ImeTextCommit(this)
    
    private val inlineSuggestionManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        InlineSuggestionManager(this)
    } else null
    
    private fun loadDarkModePreference() {
        val isLandscape = resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        val isFloatingMode = SettingsPreferences.isFloatingMode(this, isLandscape)
        val loadedX = SettingsPreferences.getFloatingOffsetX(this, isLandscape)
        val loadedY = SettingsPreferences.getFloatingOffsetY(this, isLandscape)
        val screenW = resources.configuration.screenWidthDp
        val screenH = resources.configuration.screenHeightDp
        val portraitWidth = minOf(screenW, screenH)
        val cardWidth = (portraitWidth * 0.85f).roundToInt()
        val halfMargin = maxOf(0, (screenW - cardWidth) / 2)
        val kbH = SettingsPreferences.getKeyboardHeightDp(this, isLandscape)
        val cappedKbH = kbH.coerceAtMost((screenH * 8) / 10)
        val cardH = (cappedKbH * 0.85f).roundToInt() + 18
        val navBarDp = tryGetNavBarHeightDp(this, window.window)
        val minY = if (isFloatingMode) navBarDp else 0
        val effectiveH = if (isFloatingMode) screenH - tryGetStatusBarHeightDp(this, window.window) else screenH
        val maxY = maxOf(minY, effectiveH - cardH - 20)
        val clampedX = loadedX.coerceIn(-halfMargin, halfMargin)
        val clampedY = loadedY.coerceIn(minY, maxY)
        if (clampedX != loadedX || clampedY != loadedY) {
            SettingsPreferences.setFloatingOffsetX(this, clampedX, isLandscape)
            SettingsPreferences.setFloatingOffsetY(this, clampedY, isLandscape)
        }
        uiState.value = uiState.value.copy(
            darkMode = SettingsPreferences.getDarkMode(this),
            themeId = SettingsPreferences.getKeyboardTheme(this),
            isSttEnabled = SettingsPreferences.isSttEnabled(this@XimeInputMethodService),
            keyboardHeightDp = SettingsPreferences.getKeyboardHeightDp(this, isLandscape),
            keyboardBottomPaddingDp = SettingsPreferences.getKeyboardBottomPaddingDp(this),
            toolbarButtons = SettingsPreferences.getToolbarButtons(this),
            isFloatingMode = isFloatingMode,
            floatingOffsetX = clampedX,
            floatingOffsetY = clampedY,
        )
    }
    
    private fun registerSharedPrefsListener() {
        val prefs = SettingsPreferences.getPrefsPublic(this)
        sharedPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "dark_mode", "keyboard_theme", "show_bottom_buttons", "keyboard_height_dp", "keyboard_bottom_padding_dp" -> {
                    loadDarkModePreference()
                    applyWindowBackground()
                }
                "floating_mode", "floating_mode_landscape" -> {
                    loadDarkModePreference()
                    applyWindowBackground()
                }
                "stt_enabled" -> {
                    uiState.value = uiState.value.copy(isSttEnabled = SettingsPreferences.isSttEnabled(this@XimeInputMethodService))
                }
                SettingsPreferences.KEY_STT_KEEP_ENGINE_ALIVE -> {
                    // 开启时立即预热模型常驻待命（走 AsrSupport.warmup 注册常驻后端）；
                    // 关闭时不主动销毁，:asr 服务端按同步到的设置恢复空闲回收，
                    // 且下一会话开始时 OfflineAsrBackend 会重新同步设置
                    if (SettingsPreferences.isSttKeepEngineAlive(this@XimeInputMethodService)) {
                        Thread { AsrBackendFactory.warmup(this@XimeInputMethodService) }.start()
                    }
                }
                SettingsPreferences.KEY_SMART_PREDICTION_ENABLED -> onPredictionSettingChanged()
                SettingsPreferences.KEY_CLIPBOARD_SYNC_ENABLED -> updateClipboardSync()
                SettingsPreferences.KEY_CLIPBOARD_SYNC_PLUGIN_ID -> {
                    stopClipboardSync()
                    updateClipboardSync()
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener)
    }

    /** 设置驱动：智能联想开启时加载联想模型，关闭时卸载。 */
    private fun onPredictionSettingChanged() {
        val enabled = SettingsPreferences.isSmartPredictionEnabled(this)
        if (enabled) {
            serviceScope.launch(Dispatchers.IO) {
                com.kingzcheung.xime.association.AssociationManager.initialize(this@XimeInputMethodService)
            }
        } else {
            com.kingzcheung.xime.association.AssociationManager.release()
        }
    }
    
    private fun saveDarkModePreference(mode: Int) {
        SettingsPreferences.setDarkMode(this, mode)
        uiState.value = uiState.value.copy(darkMode = mode)
    }
    
    fun toggleDarkMode() {
        val currentMode = uiState.value.darkMode
        val newMode = when (currentMode) {
            DARK_MODE_LIGHT -> DARK_MODE_DARK
            DARK_MODE_DARK -> DARK_MODE_LIGHT
            else -> { // DARK_MODE_SYSTEM: 切换到当前系统主题的反面
                if (isDarkTheme()) DARK_MODE_LIGHT else DARK_MODE_DARK
            }
        }
        saveDarkModePreference(newMode)
    }
    
    fun isDarkTheme(): Boolean {
        return when (uiState.value.darkMode) {
            DARK_MODE_DARK -> true
            DARK_MODE_SYSTEM -> {
                val nightModeFlags = resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            else -> false
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 允许 IME 窗口绘制到摄像头挖孔/刘海区域（横屏时背景覆盖全屏）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.window?.attributes?.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        savedStateRegistryController.performRestore(null)
        window.window?.decorView?.setViewTreeLifecycleOwner(this)
        window.window?.decorView?.setViewTreeSavedStateRegistryOwner(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        
        
        FileLogger.init(this)
        FileLogger.i(TAG, "XimeInputMethodService created")
        FileLogger.i(
            TAG,
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
                "screen=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}@${resources.displayMetrics.density}"
        )
        
        feedbackManager.initialize()
        
        loadDarkModePreference()
        registerSharedPrefsListener()
        
        initRimeEngine()
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                initClipboardManager()
                initAssociationEngine()
                initSpeechRecognition()

                withContext(Dispatchers.Main) {
                    FileLogger.i(TAG, "Service initialization completed")
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Initialization failed: ${e.message}")
            }
        }
    }
    
    private fun initSpeechRecognition() {
        voiceRecognitionHandler.initialize()
    }
    
    private fun initAssociationEngine() {
        predictionManager.initialize()
    }
    
    
    private fun getPredictionFromPlugin(contextText: String) {
        predictionManager.getPrediction(contextText)
    }
    
    private fun initRimeEngine() {
        Log.d(TAG, "initRimeEngine: Starting initialization...")
        
        // 必须在任何异步操作之前同步加载键盘按键配置，
        // 否则 KeyboardLayout 组合时 swipeUp/swipeDown 配置可能尚未就绪，
        // 导致按键上的符号不显示、上滑/下滑手势不触发。
        runBlocking(Dispatchers.IO) {
            KeysConfigHelper.loadConfig(this@XimeInputMethodService)
        }
        
        RimeEngine.setDeploymentCallback { isDeploying, message ->
            serviceScope.launch(Dispatchers.Main) {
                uiState.value = uiState.value.copy(
                    isDeploying = isDeploying,
                    deploymentMessage = message
                )
            }
        }
        
        val initJob = serviceScope.launch(Dispatchers.IO) {
            try {
                notifyDeploymentStatus(true, "正在初始化...")
                
                val (userDataDir, sharedDataDir) = RimeConfigHelper.initializeRimeDataAsync(this@XimeInputMethodService)
                
                notifyDeploymentStatus(true, "正在加载输入法引擎...")
                rimeEngine.initialize(userDataDir, sharedDataDir)

                // 检查词库是否已部署（deploymentDone 标记 + 部署 hash 一致）
                val deploymentDone = SettingsPreferences.isDeploymentDone(this@XimeInputMethodService)
                val needsDeployment = !deploymentDone || !RimeConfigHelper.isDeploymentComplete(this@XimeInputMethodService)

                if (needsDeployment) {
                    // 统一部署入口（进程内互斥，hash 一致时内部跳过）。
                    // 与 XimeApplication 预初始化共享，避免两者并发触发两次全量编译。
                    notifyDeploymentStatus(true, "正在编译词库...")
                    if (RimeConfigHelper.ensureDeployment(this@XimeInputMethodService)) {
                        rimeEngine.updateLastBuildTime()
                    } else {
                        FileLogger.e(TAG, "initRimeEngine: ensureDeployment failed, deployment may not have completed")
                    }
                } else {
                    Log.d(TAG, "initRimeEngine: Already deployed, creating session directly")
                }

                // 创建 session（已部署时跳过 maintenance 直接创建）
                val sessionReady = rimeEngine.ensureSession(180_000L)
                if (sessionReady) {
                    Log.d(TAG, "initRimeEngine: Session ready")
                    // 确保部署成功后才标记完成，避免首次部署超时后误标记
                    if (needsDeployment) {
                        SettingsPreferences.setDeploymentDone(this@XimeInputMethodService, true)
                        RimeConfigHelper.storeDeploymentHash(this@XimeInputMethodService)
                    }
                } else {
                    FileLogger.w(TAG, "initRimeEngine: Session not ready after 60s, continuing in background")
                }
                notifyDeploymentStatus(false, "")

                withContext(Dispatchers.Main) {
                    val savedSchema = SettingsPreferences.getCurrentSchema(this@XimeInputMethodService)
                    val availableSchemas = rimeEngine.getAvailableSchemas()
                    val currentSchema = rimeEngine.getCurrentSchema()
                    Log.d(TAG, "initRimeEngine: currentSchema=$currentSchema, savedSchema=$savedSchema, availableSchemas=${availableSchemas.joinToString()}")
                    
                    when {
                        isHandwritingSchema(savedSchema) -> {
                            // 手写方案：不要调 rimeEngine.switchSchema（Rime 没有手写引擎），
                            // 也不要覆盖 savedSchema（由 onStartInput 恢复 UI）
                            Log.d(TAG, "initRimeEngine: savedSchema is handwriting, keeping current Rime schema")
                            // UI 布局恢复：冷启动时第一次 onStartInput 先于引擎初始化完成，
                            // RimeEngine.isInitialized()=false 会跳过 handwriting UI 恢复，
                            // 此处必须补切，否则第一次弹出键盘停留在默认全键盘
                            val hwDir = com.kingzcheung.xime.model.ModelStorage.getModelDir(
                                this@XimeInputMethodService, "ochwpro"
                            )
                            com.kingzcheung.xime.model.ModelStorage.migrateLegacyForModel(
                                this@XimeInputMethodService, "ochwpro"
                            )
                            val modelOk = java.io.File(hwDir, "ochwpro.onnx").exists() &&
                                java.io.File(hwDir, "char_index.json").exists()
                            if (modelOk) {
                                val page = keyboardViewModel.page.value
                                val alreadyHandwriting = page is com.kingzcheung.xime.keyboard.KeyboardPage.Main &&
                                    page.type == com.kingzcheung.xime.keyboard.MainType.HANDWRITING
                                if (!alreadyHandwriting) {
                                    keyboardViewModel.switchMain(com.kingzcheung.xime.keyboard.MainType.HANDWRITING)
                                }
                                // 手写模型按"用键盘时加载"管理：不在此预载，
                                // HandwritingKeyboardLayout 创建时（LaunchedEffect）负责加载
                            } else {
                                FileLogger.w(TAG, "initRimeEngine: handwriting model missing, keep full keyboard")
                                android.widget.Toast.makeText(
                                    this@XimeInputMethodService,
                                    "请先下载手写模型",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        savedSchema in availableSchemas -> {
                            // 即使 savedSchema == currentSchema 也要调用 switchSchema，
                            // 因为 nativeCreateSession 后 schema 的 processor/translator 等
                            // 可能未完全初始化，switchSchema 会触发完整的初始化流程
                            Log.d(TAG, "initRimeEngine: Switching to saved schema: $savedSchema")
                            schemaController.applyPageSizeSetting(savedSchema)
                            rimeEngine.switchSchema(savedSchema)
                        }
                        SchemaManager.isSchemaCompiled(this@XimeInputMethodService, savedSchema) -> {
                            Log.d(TAG, "initRimeEngine: Schema compiled but not in get_schema_list, switching anyway")
                            schemaController.applyPageSizeSetting(savedSchema)
                            rimeEngine.switchSchema(savedSchema)
                        }
                        availableSchemas.isNotEmpty() -> {
                            // savedSchema 不可用且未编译，退而求其次用第一个可用方案
                            val fallbackSchema = availableSchemas.first()
                            Log.d(TAG, "initRimeEngine: savedSchema '$savedSchema' not available, falling back to '$fallbackSchema'")
                            schemaController.applyPageSizeSetting(fallbackSchema)
                            rimeEngine.switchSchema(fallbackSchema)
                            SettingsPreferences.setCurrentSchema(this@XimeInputMethodService, fallbackSchema)
                        }
                    }
                    
                    sessionController.updateSchemaName()
                    // onStartInput 在部署进行中会跳过 schema 切换与选项恢复，
                    // 部署完成后这里补齐 UI 状态，保证键盘可用
                    sessionController.restorePersistedSchemaOptions()
                    updateUI()
                    Log.d(TAG, "initRimeEngine: Rime engine initialized successfully")
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "initRimeEngine: Failed to initialize Rime engine", e)
                notifyDeploymentStatus(false, "初始化失败")
            }
        }
        
        // Watchdog: force-clear loading state after 190s
        // withTimeout cannot cancel native JNI calls; if rimeEngine.initialize() hangs
        // in librime, the IO coroutine would block forever. This watchdog ensures the
        // user is never permanently stuck on the loading screen.
        // 首次编译最多等 120s + ensureSession 60s + 10s 缓冲
        serviceScope.launch(Dispatchers.Main) {
            delay(190_000L)
            if (uiState.value.isDeploying) {
                FileLogger.w(TAG, "initRimeEngine: Watchdog triggered - native init appears stuck, forcing loading state cleared")
                uiState.value = uiState.value.copy(
                    isDeploying = false,
                    deploymentMessage = "初始化超时，请重启输入法"
                )
            }
        }
    }
    
    internal fun notifyDeploymentStatus(isDeploying: Boolean, message: String) {
        serviceScope.launch(Dispatchers.Main) {
            uiState.value = uiState.value.copy(
                isDeploying = isDeploying,
                deploymentMessage = message
            )
        }
    }
    
    private fun initClipboardManager() {
        Log.d(TAG, "initClipboardManager: Starting initialization...")
        try {
            clipboardManager = ClipboardManager.getInstance(this)
            clipboardItemsState.value = clipboardManager.clipboardItems.value
            quickSendItemsState.value = clipboardManager.quickSendItems.value

            serviceScope.launch {
                clipboardManager.clipboardItems.collect { items ->
                    clipboardItemsState.value = items
                }
            }
            serviceScope.launch {
                clipboardManager.quickSendItems.collect { items ->
                    quickSendItemsState.value = items
                    // 快捷发送列表变更 → 插件事件（仅投递给 manifest 声明
                    // capabilities.events 含 quick_send_changed 的插件）
                    PluginManager.dispatchEvent(
                        com.kingzcheung.xime.plugin.core.js.PluginEvent(
                            com.kingzcheung.xime.plugin.core.js.PluginEvent.TYPE_QUICK_SEND_CHANGED,
                            mapOf(com.kingzcheung.xime.plugin.core.js.PluginEvent.FIELD_COUNT to items.size)
                        )
                    )
                }
            }
            startClipboardSyncIfEnabled()
            serviceScope.launch {
                PluginManager.pluginInstancesFlow.collect {
                    updateClipboardSync()
                    refreshToolbarPluginButtons()
                    closeToolPanelIfPluginGone()
                }
            }
            Log.d(TAG, "initClipboardManager: Clipboard manager initialized successfully")
        } catch (e: Exception) {
            FileLogger.e(TAG, "initClipboardManager: Failed to initialize clipboard manager", e)
        }
    }

    private fun startClipboardSyncIfEnabled() {
        if (clipboardSyncBridge != null) return
        try {
            if (!SettingsPreferences.isClipboardSyncEnabled(this)) {
                Log.d(TAG, "Clipboard sync disabled in settings")
                return
            }
            val enabled = ExtensionManager.getEnabledClipboardSyncPlugins(this)
            if (enabled.isEmpty()) return
            val preferredId = SettingsPreferences.getClipboardSyncPluginId(this)
            val selected = enabled.firstOrNull { it.first == preferredId } ?: enabled.first()
            // 能力声明校验：未声明同步协议的插件不启动（manifest.capabilities.clipboard_sync.protocols）
            val protocols = ExtensionManager.getAllInstalledPlugins()
                .firstOrNull { it.id == selected.first }
                ?.capabilities?.clipboardSync?.protocols
            if (protocols.isNullOrEmpty()) {
                FileLogger.w(TAG, "Clipboard sync plugin ${selected.first} 未声明同步协议，拒绝启动")
                return
            }
            val plugin = selected.second
            clipboardSyncBridge = ClipboardSyncBridge(
                clipboardManager,
                plugin,
                pluginId = selected.first
            )
            clipboardSyncBridge?.start()
            uiState.value = uiState.value.copy(clipboardSyncEnabled = true)
            Log.d(TAG, "Clipboard sync started: ${selected.first}")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to start clipboard sync", e)
        }
    }

    private fun stopClipboardSync() {
        clipboardSyncBridge?.release()
        clipboardSyncBridge = null
        uiState.value = uiState.value.copy(clipboardSyncEnabled = false)
    }

    /** 剪贴板同步设置或插件状态变化时调用，按条件动态启停。 */
    private fun updateClipboardSync() {
        if (!::clipboardManager.isInitialized) return
        if (clipboardSyncBridge == null) {
            startClipboardSyncIfEnabled()
            return
        }
        if (
            !SettingsPreferences.isClipboardSyncEnabled(this) ||
            ExtensionManager.getEnabledClipboardSyncPlugins(this).isEmpty()
        ) {
            stopClipboardSync()
            return
        }
        // 当前 bridge 使用的插件与偏好选中的插件不一致时，重启切换到偏好插件
        val enabled = ExtensionManager.getEnabledClipboardSyncPlugins(this)
        val preferredId = SettingsPreferences.getClipboardSyncPluginId(this)
        val shouldUse = (if (preferredId.isNotEmpty()) {
            enabled.firstOrNull { it.first == preferredId }
        } else null) ?: enabled.first()
        if (shouldUse.first != clipboardSyncBridge?.pluginId) {
            stopClipboardSync()
            startClipboardSyncIfEnabled()
        }
    }

    /**
     * 刷新已启用插件声明的工具栏按钮到 uiState（插件启用/加载/卸载变化时调用）。
     * 两层显示控制的第一层：只有启用插件的按钮进入候选池；toolbar_buttons 偏好决定最终显示。
     */
    private fun refreshToolbarPluginButtons() {
        serviceScope.launch(Dispatchers.IO) {
            val buttons = ExtensionManager.getAllInstalledPlugins()
                .filter { SettingsPreferences.isPluginEnabled(this@XimeInputMethodService, it.id) }
                .flatMap { info ->
                    info.toolbarButtons.map { btn ->
                        ToolbarButtonItem.Plugin(
                            id = btn.id,
                            label = btn.label.ifBlank { btn.id },
                            icon = ExtensionManager.extractToolbarButtonIcon(
                                this@XimeInputMethodService, info.id, info, btn.icon
                            ) ?: ExtensionManager.extractPluginManifestIcon(this@XimeInputMethodService, info),
                            pluginId = info.id,
                            action = btn.action,
                        )
                    }
                }
            withContext(Dispatchers.Main) {
                uiState.value = uiState.value.copy(toolbarPluginButtons = buttons)
            }
        }
    }

    /** 面板打开时记录的输入框选区起止（上屏前恢复以替换原选区）。 */
    private var toolPanelSelection: Pair<Int, Int>? = null

    /** 面板生成轮询任务（流式生成期间持续刷新候选）。 */
    private var toolPanelPollJob: Job? = null

    /** 面板 loading 延迟显示任务：快速动作（互换语言等）不闪进度条、面板高度不跳动。 */
    private var toolPanelLoadingJob: Job? = null

    /**
     * 延迟显示面板 loading 指示（标题栏小圈）：阈值内完成的快速动作（本地互换、快速插件）
     * 全程不显示，避免指示器闪烁；慢动作（网络类 onAction / 生成）超时后照常反馈。
     * 面板重开（epoch 递增）后过期自动作废。
     */
    private fun scheduleToolPanelLoading(epoch: Long) {
        toolPanelLoadingJob?.cancel()
        toolPanelLoadingJob = serviceScope.launch {
            delay(TOOL_PANEL_LOADING_SHOW_DELAY_MS)
            if (uiState.value.toolPanelRequestEpoch == epoch && uiState.value.toolPanelVisible) {
                uiState.value = uiState.value.copy(toolPanelLoading = true)
            }
        }
    }

    /**
     * 插件工具栏按钮 action=open_panel 的宿主入口：打开该插件的通用面板。
     * 记录选区、按优先级收集上下文预填、向插件取初始面板状态并渲染。
     */
    internal fun openToolPanel(pluginId: String) {
        if (uiState.value.isFloatingMode ||
            resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        ) {
            Toast.makeText(this, "工具面板不支持在悬浮/横屏模式下使用", Toast.LENGTH_SHORT).show()
            return
        }
        if (uiState.value.showQuickSendForm) {
            uiState.value = uiState.value.copy(
                showQuickSendForm = false,
                quickSendFormFocused = false,
                quickSendCodeFocused = false,
                quickSendEditingItemId = null,
                quickSendEditingItemText = "",
                quickSendEditingItemCode = "",
            )
            QuickSendFormEditTextHolder.editText = null
            QuickSendFormCodeEditTextHolder.editText = null
        }
        // 打开面板前清理宿主输入框残留输入态（未上屏的拼音/英文），
        // 避免旧组合混入面板输入、或面板关闭后覆盖宿主输入框中段文字。
        val pending = candidateState.value
        if (pending.isComposing || pending.inputText.isNotEmpty() || pending.pendingEnglishText.isNotEmpty()) {
            rimeEngine.clearComposition()
            endComposingInputBox()
            candidateState.value = candidateState.value.copy(
                candidates = emptyList(),
                candidateComments = emptyList(),
                associationCandidates = emptyList(),
                pendingEnglishText = "",
                inputText = "",
                candidateActions = emptyList(),
                preeditText = "",
                isComposing = false,
                isShowingRecentClipboard = false,
                hasNextPage = false,
                hasPrevPage = false
            )
        }
        toolPanelSelection = readCurrentSelection()
        // 每次打开都是新会话：清空用户编辑标记，允许宿主回填一次 prefill
        ToolPanelEditTextHolder.userEdited = false
        val contextText = collectToolPanelContext()
        val pluginName = ExtensionManager.getAllInstalledPlugins()
            .firstOrNull { it.id == pluginId }?.name ?: pluginId
        val display = ExtensionManager.getAllInstalledPlugins()
            .firstOrNull { it.id == pluginId }?.capabilities?.tool?.display
        val epoch = uiState.value.toolPanelRequestEpoch + 1
        uiState.value = uiState.value.copy(
            toolPanelVisible = true,
            toolPanelInputFocused = display != ToolResult.PASSIVE,
            toolPanelPluginId = pluginId,
            toolPanelTitle = pluginName,
            toolPanelPrefillText = contextText,
            toolPanelItems = emptyList(),
            toolPanelDisplay = display?.name,
            toolPanelUiNodes = null,
            toolPanelLoading = false,
            toolPanelRequestEpoch = epoch,
            enterKeyText = if (display == ToolResult.PASSIVE) "发送" else "生成",
        )
        scheduleToolPanelLoading(epoch)
        serviceScope.launch(Dispatchers.IO) {
            // runCatching：插件异常（超时/中毒/网络失败）时不卡 loading，面板恢复可交互
            val state = runCatching {
                (ExtensionManager.getPluginById(pluginId) as? ToolPlugin)
                    ?.getPanelState(contextText)
            }.getOrNull()
            // inputText 契约：插件未返回 = 沿用上下文（适配器已解析）；
            // 返回空串 = 插件明确要求空输入框（如翻译插件拒绝剪贴板预填）
            val prefill = state?.inputText ?: contextText
            withContext(Dispatchers.Main) {
                if (uiState.value.toolPanelRequestEpoch != epoch || !uiState.value.toolPanelVisible) {
                    return@withContext
                }
                toolPanelLoadingJob?.cancel()
                uiState.value = uiState.value.copy(
                    toolPanelPrefillText = prefill,
                    toolPanelItems = state?.items ?: emptyList(),
                    toolPanelUiNodes = state?.ui,
                    toolPanelLoading = false,
                )
            }
        }
        if (display == ToolResult.PASSIVE) {
            // 纯展示面板与表情/符号同级：Overlay 全屏覆盖键盘，不撑高候选栏上方区域
            keyboardViewModel.showOverlay(OverlayRoute.ToolPanel)
        }
    }

    /**
     * 面板 action（InfoPanel 按钮 / direct 控件行按钮，如互换语言）：通知插件后单次重拉
     * getPanelState 刷新 ui 节点（action 改变数据后面板立即反映）。
     * 先置 loading 再执行：同步生成（插件 onPanelAction 阻塞返回）期间面板显示加载态。
     * 上下文与打开面板/生成时同口径（最近一次 prefill），避免 ai-reply 这类
     * "inputText 变化即重置缓存"的插件在按钮动作后被空上下文清状态。
     */
    internal fun dispatchToolPanelAction(actionId: String) {
        val pluginId = uiState.value.toolPanelPluginId
        val epoch = uiState.value.toolPanelRequestEpoch
        val contextText = uiState.value.toolPanelPrefillText
        // 延迟显示 loading：本地快动作（如互换语言）不闪指示器
        scheduleToolPanelLoading(epoch)
        serviceScope.launch(Dispatchers.IO) {
            val plugin = ExtensionManager.getPluginById(pluginId) as? ToolPlugin ?: return@launch
            // runCatching：插件异常（超时/中毒）时不卡 loading，面板恢复可交互
            val state = runCatching {
                plugin.onPanelInput("", contextText)
                plugin.onPanelAction(actionId)
                plugin.getPanelState(contextText)
            }.getOrNull()
            toolPanelLoadingJob?.cancel()
            withContext(Dispatchers.Main) {
                if (uiState.value.toolPanelRequestEpoch == epoch && uiState.value.toolPanelVisible) {
                    uiState.value = uiState.value.copy(
                        toolPanelUiNodes = state?.ui,
                        toolPanelItems = state?.items ?: uiState.value.toolPanelItems,
                        toolPanelLoading = state?.loading ?: false,
                    )
                }
            }
        }
    }

    /**
     * 控件行字段变更（文本输入/下拉选择）：实时通知插件（key = ui 节点 key）。
     * 插件自行保存状态，宿主不代存；UI 同步返回，插件侧由 JsScriptRuntime 全量捕获异常。
     */
    internal fun onToolPanelFieldInput(key: String, value: String) {
        val pluginId = uiState.value.toolPanelPluginId
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                (ExtensionManager.getPluginById(pluginId) as? ToolPlugin)?.onPanelInput(key, value)
            }
        }
    }

    internal fun closeToolPanel() {        toolPanelSelection = null
        stopToolPanelPoll()
        // 面板相关 Overlay 页面（ToolPanel 纯展示页）开着时联动关闭
        val page = keyboardViewModel.page.value
        if (page is com.kingzcheung.xime.keyboard.KeyboardPage.Overlay &&
            page.route == OverlayRoute.ToolPanel
        ) {
            keyboardViewModel.closeOverlay()
        }
        uiState.value = uiState.value.copy(
            toolPanelVisible = false,
            toolPanelInputFocused = false,
            toolPanelPluginId = "",
            toolPanelTitle = "",
            toolPanelPrefillText = "",
            toolPanelItems = emptyList(),
            toolPanelDisplay = null,
            toolPanelUiNodes = null,
            toolPanelLoading = false,
            enterKeyText = "发送",
        )
        ToolPanelEditTextHolder.editText = null
        ToolPanelEditTextHolder.main = null
        ToolPanelEditTextHolder.userEdited = false
        toolPanelLoadingJob?.cancel()
    }

    /**
     * 面板候选条目上屏：有选区时先恢复选区再提交（替换原选区），无选区时光标处追加。
     * 直接经 InputConnection 提交，不走 commitText 重定向（避免注入回面板输入框）。
     */
    internal fun commitToolPanelItem(text: String) {
        val pluginId = uiState.value.toolPanelPluginId
        val itemId = uiState.value.toolPanelItems.firstOrNull { it.text == text }?.id ?: text
        serviceScope.launch(Dispatchers.IO) {
            (ExtensionManager.getPluginById(pluginId) as? ToolPlugin)?.onPanelItemClick(itemId)
        }
        val ic = currentInputConnection ?: return
        toolPanelSelection?.let { (start, end) ->
            runCatching { ic.setSelection(start, end) }
        }
        ic.commitText(text, 1)
        if (isChineseMode) {
            predictionManager.appendCommittedText(text)
            predictionManager.recordInput(text)
        }
        closeToolPanel()
    }

    /**
     * 面板触发生成：通知插件输入变化并触发 generate，异步轮询取回最新面板状态。
     * 同步生成（智能回复）阻塞返回后一次取回；流式生成（帮写）期间插件 loading=true，
     * 宿主持续轮询刷新，直到 loading 结束或面板关闭。
     * 代际号防旧结果回填：新请求/重开会递增 epoch，旧结果回来时检测到已过期则丢弃。
     */
    internal fun triggerToolPanelGenerate() {
        val pluginId = uiState.value.toolPanelPluginId
        // passive 纯展示面板：无生成语义，enter 不触发（数据由事件驱动 + InfoPanel 点击 action 刷新）
        if (uiState.value.toolPanelDisplay == ToolResult.PASSIVE.name) {
            return
        }
        // 使用前授权检测：插件有未授权网络域名时先引导授权，不发起请求
        if (!com.kingzcheung.xime.plugin.PluginNetworkAuthHelper.ensureAuthorized(this, pluginId)) {
            return
        }
        // 生成取词固定读主输入框（控件行字段聚焦时按键路由指向字段本身，不影响生成上下文）
        val inputText = ToolPanelEditTextHolder.main?.text?.toString() ?: ""
        // 捕获当前代际号（openToolPanel 时递增）。轮询期间持续对比：
        // 面板被重新打开（epoch 递增）即视为过期，丢弃本轮结果。
        val epoch = uiState.value.toolPanelRequestEpoch
        val epochStartTime = System.currentTimeMillis()
        toolPanelPollJob?.cancel()
        toolPanelPollJob = serviceScope.launch(Dispatchers.IO) {
            val plugin = ExtensionManager.getPluginById(pluginId) as? ToolPlugin
            plugin?.onPanelInput("", inputText)
            plugin?.onPanelAction("generate")
            while (uiState.value.toolPanelVisible) {
                val state = plugin?.getPanelState(inputText) ?: break
                val items = state.items
                val loading = state.loading
                withContext(Dispatchers.Main) {
                    if (uiState.value.toolPanelRequestEpoch == epoch) {
                        // 轮询接管 loading（插件报告），取消尚未触发的延迟显示
                        toolPanelLoadingJob?.cancel()
                        uiState.value = uiState.value.copy(
                            toolPanelItems = items,
                            toolPanelLoading = loading,
                        )
                    }
                }
                if (!loading) break
                delay(200)
            }
            // 结果交互：生成结束后按结果显示方式决策——
            //   display=DIRECT → 直接上屏替换选区并关闭面板（AI 翻译/帮写等单结果场景，无需点击）
            //   display=PASSIVE → 面板本身全屏展示 items（InfoPanel 点选上屏），无需分派
            //   display=null（未声明）→ 直接上屏（旧行为为按数量兜底开页面，页面已并入 passive 面板）
            //   空结果 → 保持面板（用户可重新生成）
            withContext(Dispatchers.Main) {
                if (uiState.value.toolPanelVisible) {
                    val items = uiState.value.toolPanelItems
                    when {
                        items.isEmpty() -> {
                            // 空结果：静默失败。原因已记录到 PluginErrorLog
                            // （插件中心错误弹窗 / 设置→日志查看器可查看），不打断用户。
                            com.kingzcheung.xime.plugin.core.security.PluginErrorLog
                                .getLastError(pluginId)
                                ?.let { lastError ->
                                    FileLogger.w(
                                        TAG,
                                        "tool panel generate empty result, plugin error: ${
                                            com.kingzcheung.xime.plugin.core.security.PluginErrorLog
                                                .userMessage(lastError)
                                        } ${lastError.message}"
                                    )
                                }
                        }
                        else -> commitToolPanelItem(items[0].text)
                    }
                }
            }
        }
    }

    private fun stopToolPanelPoll() {
        toolPanelPollJob?.cancel()
        toolPanelPollJob = null
    }

    /**
     * 面板所属插件被禁用/卸载时自动关闭面板（工具栏按钮候选池同步移除，
     * toolbar_buttons 偏好中残留 id 匹配不到自然不显示，无需清理偏好）。
     */
    private fun closeToolPanelIfPluginGone() {
        val state = uiState.value
        if (!state.toolPanelVisible) return
        val pluginId = state.toolPanelPluginId
        if (pluginId.isBlank()) return
        val stillEnabled = ExtensionManager.getAllInstalledPlugins().any {
            it.id == pluginId && SettingsPreferences.isPluginEnabled(this, it.id)
        }
        if (!stillEnabled) {
            closeToolPanel()
        }
    }

    /** 读取当前输入框选区（起止不等时返回，供上屏替换原选区）。 */
    private fun readCurrentSelection(): Pair<Int, Int>? {
        val ic = currentInputConnection ?: return null
        return runCatching {
            val req = android.view.inputmethod.ExtractedTextRequest()
            val extracted = ic.getExtractedText(req, 0)
            if (extracted != null && extracted.selectionStart >= 0 && extracted.selectionEnd > extracted.selectionStart) {
                Pair(extracted.selectionStart, extracted.selectionEnd)
            } else null
        }.getOrNull()
    }

    /** 收集面板上下文：仅选中文本；未选中时返回空串（不预填输入框全文/剪贴板，待用户自行输入）。 */
    /**
     * 工具面板上下文收集（选区 > 输入框选区 > 剪贴板）：
     * 对方消息通常来自聊天 App 复制而非输入框选区，剪贴板兜底是 AI 回复等
     * 插件拿到上下文的关键路径（插件契约见 plugins/ai-reply/main.ts）。
     */
    private fun collectToolPanelContext(): String {
        val ic = currentInputConnection
        if (ic != null) {
            runCatching {
                val sel = ic.getSelectedText(0)?.toString()
                if (!sel.isNullOrBlank()) return sel
            }
            runCatching {
                val req = android.view.inputmethod.ExtractedTextRequest()
                val extracted = ic.getExtractedText(req, 0)
                if (extracted != null && extracted.selectionStart >= 0 && extracted.selectionEnd > extracted.selectionStart) {
                    val t = extracted.text?.toString()
                    if (t != null) {
                        val s = extracted.selectionStart.coerceIn(0, t.length)
                        val e = extracted.selectionEnd.coerceIn(s, t.length)
                        if (e > s) return t.substring(s, e)
                    }
                }
            }
        }
        // 剪贴板兜底：无选区/无输入连接时取系统剪贴板
        runCatching {
            if (::clipboardManager.isInitialized) {
                clipboardManager.getCurrentClipboardText()?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return ""
    }

    private fun ensureClipboardManagerInitialized() {
        if (!::clipboardManager.isInitialized) {
            Log.d(TAG, "ensureClipboardManagerInitialized: Initializing clipboard manager synchronously")
            try {
                clipboardManager = ClipboardManager.getInstance(this)
                clipboardItemsState.value = clipboardManager.clipboardItems.value
                quickSendItemsState.value = clipboardManager.quickSendItems.value
                Log.d(TAG, "ensureClipboardManagerInitialized: Clipboard manager initialized")
            } catch (e: Exception) {
                FileLogger.e(TAG, "ensureClipboardManagerInitialized: Failed to initialize clipboard manager", e)
            }
        }
    }

    override fun onCreateInputView(): View {
        keyboardContainer = VoiceKeyboardContainer(
            context = this,
            uiStateProvider = { uiState.value },
            onUiStateChanged = { newState -> uiState.value = newState },
            onPerformVibration = { view -> feedbackManager.hapticFeedback(view) },
            onPerformUndo = { pendingVoiceAction = { textCommit.performUndo() } },
            onPerformSearch = { pendingVoiceAction = { textCommit.performSearch() } },
            onStopRecognition = {
                endVoiceSession()
            },
            isRecording = { voiceRecordingStarted },
            setRecording = { voiceRecordingStarted = it },
            onVoiceDismiss = {
                val action = pendingVoiceAction
                pendingVoiceAction = null
                action?.invoke()
                endVoiceSession()
            },
            onTouchCancel = {
                uiState.value = uiState.value.copy(
                    swipeCancelEpoch = uiState.value.swipeCancelEpoch + 1
                )
            }
        )
        // 编码气泡绘制在候选栏上方（栏外，drawBehind 负坐标）：insets 重构后容器
        // 物理高度 = Compose 内容总高（gravity BOTTOM），候选栏紧贴容器顶边，
        // 气泡落在容器顶边之外——关闭容器裁剪，气泡才能画进 inputArea 的空白区。
        // 仅影响越界绘制裁剪，不触碰容器高度/insets 计算路径。
        keyboardContainer.clipChildren = false

        bottomInsetPxState.value = getActiveBottomInsetPx(window.window)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            keyboardContainer.setOnApplyWindowInsetsListener { v, insets ->
                val px = extractBottomInset(insets)
                if (px != bottomInsetPxState.value) {
                    bottomInsetPxState.value = px
                }
                v.onApplyWindowInsets(insets)
            }
        }
        
        val composeView = ComposeView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            composeViewRef = this
            setContent {
                val cand = candidateState.value
                val state = uiState.value
                val page by keyboardViewModel.page.collectAsState(com.kingzcheung.xime.keyboard.KeyboardPage.Main(com.kingzcheung.xime.keyboard.MainType.FULL))
                val isHandwritingMode = (page as? com.kingzcheung.xime.keyboard.KeyboardPage.Main)?.type == com.kingzcheung.xime.keyboard.MainType.HANDWRITING
                val isDarkTheme = isDarkTheme()
                val screenHeightDp = resources.configuration.screenHeightDp
                val physicalScreenDp = (resources.displayMetrics.heightPixels / resources.displayMetrics.density).roundToInt()
                val statusBarHeightDp = tryGetStatusBarHeightDp(this@XimeInputMethodService, window.window)
                val navBarHeightDp = tryGetNavBarHeightDp(this@XimeInputMethodService, window.window)
                val visibleNavBarHeightDp = tryGetVisibleNavBarHeightDp(this@XimeInputMethodService, window.window)
                // 用物理屏幕高度减去状态栏，保证不同 Android 版本一致
                val effectiveScreenH = if (state.isFloatingMode) physicalScreenDp - statusBarHeightDp else screenHeightDp
                val windowVisibleHeightDp = effectiveScreenH
                val navBarAlreadyExcluded = (physicalScreenDp - screenHeightDp) >= (navBarHeightDp + statusBarHeightDp - 3)
                val floatingMinY = if (navBarAlreadyExcluded) 0 else visibleNavBarHeightDp

                val screenWidthDp = resources.configuration.screenWidthDp
                val screenIsLandscape = screenWidthDp > screenHeightDp
                val portraitScreenHeightDp = if (screenIsLandscape) screenWidthDp else screenHeightDp
                val isLandscape = !state.isFloatingMode && screenIsLandscape
                val orientationHeight = if (state.isFloatingMode) {
                    val prefs = SettingsPreferences.getPrefsPublic(this@XimeInputMethodService)
                    val storedPortrait = prefs.getInt("keyboard_height_dp", -1)
                    if (storedPortrait > 0) storedPortrait else {
                        val storedLandscape = prefs.getInt("keyboard_height_dp_landscape", -1)
                        if (storedLandscape > 0) storedLandscape else portraitScreenHeightDp * SettingsPreferences.DEFAULT_KEYBOARD_HEIGHT_PERCENT / 100
                    }
                } else {
                    SettingsPreferences.getKeyboardHeightDp(this@XimeInputMethodService, screenIsLandscape)
                }
                val displayHeight = orientationHeight.coerceAtMost((if (state.isFloatingMode) portraitScreenHeightDp else screenHeightDp) * 8 / 10)
                val keyboardHeight = if (state.showKeyboardResize) {
                    if (screenIsLandscape) (screenHeightDp * 7) / 10 else displayHeight.coerceAtLeast(screenHeightDp / 2)
                } else if (isHandwritingMode) {
                    screenHeightDp / 2
                } else {
                    displayHeight
                }
                val floatScale = if (state.isFloatingMode) 0.85f else 1f
                val effectiveKeyboardHeight = (keyboardHeight * floatScale).toInt()
                val floatingDragBarHeight = if (state.isFloatingMode) 18 else 0
                val floatingCardContentHeight = effectiveKeyboardHeight + floatingDragBarHeight
                
                val density = LocalDensity.current
                // 统一使用 View 层多类型检测的 insets，避免 Compose
                // navigationBars 恒为手势条高度导致与系统栏（三键导航）差异被抹平。
                val activeBottomPx = bottomInsetPxState.value
                val rawDp = if (activeBottomPx > 0) {
                    with(density) { activeBottomPx.toDp().value.toInt() }
                } else 0
                // 底部留白整体缩减量（dp）：让键盘比系统导航栏实际高度再低一点，
                // 键盘背景已 edge-to-edge 延伸到系统栏后，留白可小于系统栏高度。
                // 横屏手势区 inset 更大（约 32dp vs 竖屏 16dp），固定减 8 会留下过厚的
                // 底条（24dp，为竖屏 3 倍）；横屏多减一档到 16dp，仍足以盖住手势条。
                val bottomInsetShrinkDp = if (isLandscape) 16 else 8
                // 标准（三键）导航栏 inset 明显大于手势条，额外多减一点，
                // 让标准模式高度更接近抬高模式，但保留可辨识的差异。
                val extraShrinkDp = if (rawDp >= 120) 8 else 0
                val bottomSpaceDp = if (rawDp > 0) (rawDp - bottomInsetShrinkDp - extraShrinkDp).coerceAtLeast(0) else 0
                // 兜底仅用于彻底检测不到任何底部 inset 的场景（全屏沉浸），
                // 不再把已有差异（标准 44dp / 手势 16dp）强行垫平。
                val minBottomDp = 18
                val activeBottomDp = if (bottomSpaceDp == 0) minBottomDp else bottomSpaceDp
                val navBarDp = activeBottomDp.dp
                val hasNavBar = navBarDp > 0.dp

                // 快捷发送 / 工具面板为"键盘上方的撑高面板"：显示时键盘总高增加面板高度（面板在键盘上方，
                // 不遮键盘按键），同时容器物理高度同步变大（updateHeight）→ IME insets 由系统确定性重算，
                // 关闭后容器还原，彻底避免 insets 残留与白色区域。
                // Overlay 页面（menubar/剪贴板/emoji 等）全屏覆盖键盘内容区：激活期间撑高面板
                // 不参与计算，否则 Overlay 页面会带上表单/工具面板的额外高度（容器整体被撑高）。
                val isOverlayPage = page is com.kingzcheung.xime.keyboard.KeyboardPage.Overlay
                val quickSendFormExtra = if (state.showQuickSendForm && !isOverlayPage) 200 else 0
                // 需与 ToolPanel 渲染高度一致（toolPanelHeightDp：内容自适应 + 上限），
                // 否则容器比面板多/少一截，键盘被拉高。
                // PASSIVE 纯展示面板走 Overlay 全屏覆盖（键盘窗口内容区），不撑高。
                val toolPanelExtra = if (state.toolPanelVisible &&
                    state.toolPanelDisplay != "PASSIVE" &&
                    !isOverlayPage
                ) {
                    com.kingzcheung.xime.ui.keyboard.toolPanelHeightDp(
                        hasControls = !state.toolPanelUiNodes.isNullOrEmpty(),
                    )
                } else 0
                val overlayPanelExtra = quickSendFormExtra + toolPanelExtra

                XimeTheme(darkTheme = isDarkTheme, themeId = state.themeId) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Sync FrameLayout height with Compose content height
                        val contentHeight = if (state.showKeyboardResize) state.resizePreviewHeightDp else floatingCardContentHeight + overlayPanelExtra
                        val totalDp = if (state.isCompact || state.isFloatingMode) effectiveScreenH
                            else contentHeight + state.keyboardBottomPaddingDp + activeBottomDp
                        SideEffect {
                            // 容器物理高度 = Compose 内容总高（含底部留白），全模式统一。
                            // 容器高度变化 → View 层 relayout → traversal → onComputeInsets
                            // 自动以新高度重算并上报（ViewRootImpl 每次 traversal 都 dispatch
                            // OnComputeInternalInsetsListener，值变化即 setInsets），
                            // 无需 +1dp hack 强制造型变化。
                            keyboardContainer.updateHeight(totalDp)
                            currentEffectiveKeyboardHeight = if (state.isFloatingMode) keyboardHeight + floatingDragBarHeight + 50 + state.keyboardBottomPaddingDp
                                else if (state.isCompact) HARDWARE_CANDIDATE_BAR_HEIGHT
                                else effectiveKeyboardHeight + overlayPanelExtra
                        }
                        val kbColors = KeysConfigHelper.getKeyboardColors()
                        val longToColor: (Long) -> androidx.compose.ui.graphics.Color = { if (it == 0L)  { androidx.compose.ui.graphics.Color(0xE61E1E1E) } else if (it > 0xFFFFFF) { androidx.compose.ui.graphics.Color(it) } else { androidx.compose.ui.graphics.Color(0xFF000000 or it) } }
                        val isDark = isDarkTheme
                        val cardBg = if (isDark) longToColor(com.kingzcheung.xime.settings.KeyboardColorsConfig.FALLBACK_BG_DARK) else longToColor(com.kingzcheung.xime.settings.KeyboardColorsConfig.FALLBACK_BG_LIGHT)
                        val candidateTextCol = com.kingzcheung.xime.ui.theme.KeyboardThemes.getCandidateTextColorOverride(state.themeId, isDark)
                            ?: if (isDark) longToColor(kbColors.candidateTextColorDark) else longToColor(kbColors.candidateTextColor)
                        val accentCol = com.kingzcheung.xime.ui.theme.KeyboardThemes.getAccentColor(state.themeId, isDark)
                        val selectedTextCol = com.kingzcheung.xime.ui.theme.KeyboardThemes.getCandidateSelectedTextColor(state.themeId, isDark)
                        val keyboardBgColor = cardBg
                        val rootTheme = com.kingzcheung.xime.ui.theme.KeyboardThemes.getThemeById(state.themeId)
                        if (state.isCompact && (cand.candidates.isNotEmpty() || cand.isShowingRecentClipboard || cand.inputText.isNotEmpty())) {
                            HardwareKeyboardCandidateBar(
                                inputText = cand.inputText,
                                preeditText = cand.preeditText,
                                candidates = cand.candidates,
                                hasNextPage = cand.hasNextPage,
                                hasPrevPage = cand.hasPrevPage,
                                cursorX = state.cursorX,
                                cursorY = state.cursorY,
                                cursorVisible = state.cursorVisible,
                                highlightIndex = highlightIndex.intValue,
                                cardBackgroundColor = cardBg,
                                candidateTextColor = candidateTextCol,
                                activeColor = accentCol,
                                selectedTextColor = selectedTextCol,
                            )
                        } else if (state.isCompact) {
                            Box(modifier = Modifier.fillMaxSize())
                        } else {
                        // 非浮动：背景与键盘内容同区域，贴底覆盖键盘内容高度 + 底部导航栏留白，
                        // 键盘内容通过 offset 上移 activeBottomDp 留出导航栏空间（对齐参考实现 bottomPaddingSpace）。
                        // 浮动模式：卡片由 KeyboardView 内部 FloatingKeyboardContainer 自绘背景与定位，此处不做背景/偏移。
                        if (!state.isFloatingMode) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(if (state.showKeyboardResize) (state.resizePreviewHeightDp + state.keyboardBottomPaddingDp + activeBottomDp).dp else (floatingCardContentHeight + state.keyboardBottomPaddingDp + overlayPanelExtra + activeBottomDp).dp)
                                    .align(androidx.compose.ui.Alignment.BottomCenter)
                                    .keyboardBackground(rootTheme.keyboardBackground, isDark, keyboardBgColor)
                            )
                        }
                        Box(
                            modifier = Modifier

                                .fillMaxWidth()
                                .height(if (state.showKeyboardResize) (state.resizePreviewHeightDp + state.keyboardBottomPaddingDp).dp else (floatingCardContentHeight + state.keyboardBottomPaddingDp + overlayPanelExtra).dp)
                                .align(androidx.compose.ui.Alignment.BottomCenter)
                                .then(if (state.isFloatingMode) Modifier else Modifier.offset(y = (-activeBottomDp).dp))
                        ) {
                        CompositionLocalProvider(LocalStretchFactor provides state.stretchFactor) {
                            // 注意：kbState 只承载键盘按键/布局状态，不承载候选数据。
                            // 候选数据单独通过 candidateState 传给 KeyboardView。
                            // remember 的 key 均为候选无关依赖：候选变化时 kbState 实例保持不变，
                            // KeyboardView（按键区）跳过重组，只有读取 candidateState 的候选栏重组，
                            // 避免长按退格时高频候选更新触发整个键盘重组导致候选栏闪烁。
                            val kbState = remember(
                                state,
                                isDarkTheme,
                                effectiveKeyboardHeight,
                                floatingMinY,
                            isHandwritingMode,
                            clipboardItemsState.value,
                            quickSendItemsState.value,
                            recentClipboardItemsState.value,
                            calculatorEngine.isActive(),
                            ) {
                                KeyboardUiState(
                                    isAsciiMode = state.isAsciiMode,
                                    schemaName = state.schemaName,
                                    currentSchemaId = state.currentSchemaId,
                                    schemas = state.schemas,
                                    schemaSwitches = state.schemaSwitches,
                                    enterKeyText = state.enterKeyText,
                                    isDarkTheme = isDarkTheme,
                                    darkMode = state.darkMode,
                                    themeId = state.themeId,
                                    keyboardHeightDp = effectiveKeyboardHeight,
                                    keyboardBottomPaddingDp = state.keyboardBottomPaddingDp,
                                    clipboardItems = clipboardItemsState.value,
                                    quickSendItems = quickSendItemsState.value,
                                    recentClipboardItems = recentClipboardItemsState.value,
                                    isVoiceMode = state.isVoiceMode,
                                    voiceSticky = state.voiceSticky,
                                    voiceBottomActive = state.voiceButtonState.bottomActive,
                                    voiceLeftActive = state.voiceButtonState.leftActive,
                                    voiceRightActive = state.voiceButtonState.rightActive,
                                    voicePluginName = state.voicePluginName,
                                    voiceRecognitionState = state.voiceRecognitionState,
                                    voiceRecognizedText = state.voiceRecognizedText,
                                    isSttEnabled = state.isSttEnabled,
                                    toolbarButtons = state.toolbarButtons,
                                    toolbarPluginButtons = state.toolbarPluginButtons,
                                    isCalculatorMode = calculatorEngine.isActive(),
                                    inputSessionId = state.inputSessionId,
                                    isFloatingMode = state.isFloatingMode,
                                    isHandwritingMode = isHandwritingMode,
                                    floatingOffsetX = state.floatingOffsetX,
                                    floatingOffsetY = state.floatingOffsetY,
                                    floatingMinOffsetY = floatingMinY,
                                    t9ResetSignal = state.t9ResetSignal,
                                    swipeCancelEpoch = state.swipeCancelEpoch,
                                    t9RightCandidateSelectedCount = state.t9RightCandidateSelectedCount,
                                    t9SelectedCandidatePinyin = state.t9SelectedCandidatePinyin,
                                    showQuickSendForm = state.showQuickSendForm,
                                    quickSendFormFocused = state.quickSendFormFocused,
                                    quickSendEditingItemId = state.quickSendEditingItemId,
                                    quickSendEditingItemText = state.quickSendEditingItemText,
                                    quickSendEditingItemCode = state.quickSendEditingItemCode,
                                    toolPanelVisible = state.toolPanelVisible,
                                    toolPanelInputFocused = state.toolPanelInputFocused,
                                    toolPanelPluginId = state.toolPanelPluginId,
                                    toolPanelTitle = state.toolPanelTitle,
                                    toolPanelPrefillText = state.toolPanelPrefillText,
                                    toolPanelItems = state.toolPanelItems,
                                    toolPanelLoading = state.toolPanelLoading,
                                    toolPanelRequestEpoch = state.toolPanelRequestEpoch,
                                    toolPanelDisplay = state.toolPanelDisplay,
                                    toolPanelUiNodes = state.toolPanelUiNodes,
                                    clipboardSyncEnabled = state.clipboardSyncEnabled,
                                )
                            }
                            val callbacks = rememberImeKeyboardCallbacks(this@XimeInputMethodService, floatingMinY, state, effectiveScreenH)
                            keyboardCallbacks = callbacks
                            val hapticView = LocalView.current
                            KeyboardView(
                                viewModel = keyboardViewModel,
                                state = kbState,
                                candidateState = candidateState,
                                voiceAmplitudeState = this@XimeInputMethodService.voiceAmplitudeState,
                                voiceSpectrumState = this@XimeInputMethodService.voiceSpectrumState,
                                callbacks = callbacks,
                                inlineSuggestions = inlineSuggestionManager?.suggestions.orEmpty(),
                                // 非按键交互（符号/表情面板、菜单栏、候选栏按钮）的振动，
                                // 语义与按键按下反馈完全一致（模式/时长/振幅走同一配置）
                                onHapticFeedback = { feedbackManager.hapticFeedback(hapticView) },
                                onCardPositioned = { _: Int, top: Int, _: Int, bottom: Int ->
                                    val cardHeightPx = bottom - top
                                    if (cardHeightPx > 0) {
                                        currentEffectiveKeyboardHeight = (cardHeightPx / density.density).roundToInt()
                                    }
                                },
                            )
                           }
                           if (state.showKeyboardResize) {
                              KeyboardResizeOverlay(
                                     initialHeightDp = state.resizePreviewHeightDp,
                                     defaultHeightDp = SettingsPreferences.getDefaultKeyboardHeightDp(this@XimeInputMethodService, isLandscape),
                                     currentBottomPaddingDp = state.keyboardBottomPaddingDp,
                                     onHeightChange = { newHeight ->
                                       uiState.value = uiState.value.copy(
                                           resizePreviewHeightDp = newHeight
                                       )
                                   },
                                  onBottomPaddingChange = { newPadding ->
                                       uiState.value = uiState.value.copy(
                                           keyboardBottomPaddingDp = newPadding
                                       )
                                   },
                                  onReset = { defaultHeight ->
                                       uiState.value = uiState.value.copy(
                                           resizePreviewHeightDp = defaultHeight,
                                           keyboardBottomPaddingDp = 0,
                                           stretchFactor = 1f
                                       )
                                   },
                                  onConfirm = { newHeight, newPadding ->
                                       schemaController.setKeyboardHeight(newHeight)
                                       SettingsPreferences.setKeyboardBottomPaddingDp(this@XimeInputMethodService, newPadding)
                                       uiState.value = uiState.value.copy(
                                           showKeyboardResize = false,
                                           keyboardHeightDp = newHeight,
                                           keyboardBottomPaddingDp = newPadding,
                                       )
                                    },
                                    onCancel = {
                                        val restoreHeight = SettingsPreferences.getKeyboardHeightDp(this@XimeInputMethodService, isLandscape)
                                        val restorePadding = SettingsPreferences.getKeyboardBottomPaddingDp(this@XimeInputMethodService)
                                        uiState.value = uiState.value.copy(
                                            showKeyboardResize = false,
                                            keyboardHeightDp = restoreHeight,
                                            keyboardBottomPaddingDp = restorePadding,
                                        )
                                    },
                                    modifier = Modifier
                                       .fillMaxSize()
                              )
                          }
                           }
                            if (!state.isFloatingMode && navBarDp > 0.dp) {
                                Spacer(modifier = Modifier.fillMaxWidth().height(navBarDp))
                            }
                       }
                      }
                 }
             }
         }
        
        keyboardContainer.addView(composeView)

        applyWindowBackground()

        return keyboardContainer
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        win.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        try {
            window.window?.decorView
                ?.findViewById<FrameLayout>(android.R.id.inputArea)
                ?.let { area ->
                    area.updateLayoutParams<android.view.ViewGroup.LayoutParams> {
                        height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    // 栏外编码气泡越出容器顶边后进入 inputArea 空白区，
                    // inputArea 默认 clipChildren=true 会把越界部分裁掉。
                    area.clipChildren = false
                }
            // 容器在 inputArea 内底部对齐（gravity BOTTOM）：
            // 容器物理高度 = Compose 内容总高，小于全屏窗口时若默认 top-left
            // 对齐会让键盘跑到屏顶。高度由 SideEffect 的 updateHeight 动态设置。
            view.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                gravity = android.view.Gravity.BOTTOM
            }
            val state = uiState.value
            if (state.isFloatingMode || state.isCompact) {
                view.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                    height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                }
            } else {
                // 首显前预设容器高度：容器初始 MATCH_PARENT（贴底 → 顶部 y=0）会让窗口
                // 第一次 traversal 的 onComputeInsets 报告"键盘占满全屏"，而 SideEffect 的
                // updateHeight 下一帧才生效；部分应用按首次 inset 布局后不再响应修正，
                // 输入框被顶到屏顶、与键盘间留大片空白（重进时容器已带正确高度故不复现）。
                // 按偏好高度预设首帧真实几何，之后仍由 SideEffect 统一维护。
                val isLandscape =
                    resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
                val displayHeight = SettingsPreferences.getKeyboardHeightDp(this, isLandscape)
                    .coerceAtMost((resources.configuration.screenHeightDp * 8) / 10)
                val density = resources.displayMetrics.density
                val rawDp = if (bottomInsetPxState.value > 0)
                    (bottomInsetPxState.value / density).toInt() else 0
                val extraShrink = if (rawDp >= 120) 8 else 0
                val bottomSpace = if (rawDp > 0) (rawDp - 8 - extraShrink).coerceAtLeast(0) else 0
                val activeBottomDp = if (bottomSpace == 0) 18 else bottomSpace
                view.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                    height = ((displayHeight + state.keyboardBottomPaddingDp + activeBottomDp) * density).toInt()
                }
            }
        } catch (_: Exception) {}
    }
    
    // ── ActionExecutor 实现 ──

    override fun performEditorMenuAction(actionId: Int) {
        when (actionId) {
            android.R.id.undo -> {
                // performContextMenuAction 对 undo 支持不一致，改用 Ctrl+Z 键盘快捷键
                val now = SystemClock.uptimeMillis()
                currentInputConnection?.sendKeyEvent(
                    KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON)
                )
                currentInputConnection?.sendKeyEvent(
                    KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON)
                )
            }
            else -> currentInputConnection?.performContextMenuAction(actionId)
        }
    }

    /** 读取当前方案为指定物理修饰键配置的 ascii_composer 动作。 */
    private fun hardwareSwitchAction(keyCode: Int): String? {
        val keyName = when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT -> "Shift_L"
            KeyEvent.KEYCODE_SHIFT_RIGHT -> "Shift_R"
            KeyEvent.KEYCODE_CTRL_LEFT -> "Control_L"
            KeyEvent.KEYCODE_CTRL_RIGHT -> "Control_R"
            else -> return null
        }
        return rimeEngine.getCurrentSchemaSwitchKeyAction(keyName)
            ?.lowercase()
            ?.trim()
    }

    private fun isHardwareSwitchKeyConfigured(keyCode: Int): Boolean {
        return hardwareSwitchAction(keyCode) in setOf(
            "commit_code",
            "commit_text",
            "inline_ascii",
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val e = event ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> {
                // Rime 方案未将该修饰键配置为 ascii_composer 切换键时，保持系统默认行为。
                if (!isHardwareSwitchKeyConfigured(keyCode)) {
                    return super.onKeyDown(keyCode, event)
                }
                when (keyCode) {
                    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                        if (pendingHardwareCtrlToggle) {
                            pendingHardwareCtrlToggle = false
                            hardwareModifierCombo = true
                        } else {
                            pendingHardwareShiftToggle = true
                            hardwareModifierCombo = false
                        }
                    }
                    else -> {
                        if (pendingHardwareShiftToggle) {
                            pendingHardwareShiftToggle = false
                            hardwareModifierCombo = true
                        } else {
                            pendingHardwareCtrlToggle = true
                            hardwareModifierCombo = false
                        }
                    }
                }
                return true
            }
        }
        // Shift/Ctrl 参与了组合按键时不把其释放误判为独立切换；具体是否切换由当前方案配置决定。
        if (pendingHardwareShiftToggle || pendingHardwareCtrlToggle) {
            pendingHardwareShiftToggle = false
            pendingHardwareCtrlToggle = false
            hardwareModifierCombo = true
        }
        // Ctrl/Alt/Meta 组合键交还系统/编辑器（其是否用于切换由当前 Rime 方案决定）。
        if (e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) {
            return super.onKeyDown(keyCode, event)
        }
        if (hasHardwareKeyboard && !e.isShiftPressed && candidateState.value.candidates.isNotEmpty()) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (keyboardViewModel.candidatePageExpanded.value) {
                        expandedPageScroll(1); highlightIndex.intValue = 0; return true
                    }
                    if (candidateState.value.hasNextPage) { keyRouter.pageDown(); highlightIndex.intValue = 0; return true }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (keyboardViewModel.candidatePageExpanded.value) {
                        expandedPageScroll(-1); highlightIndex.intValue = 0; return true
                    }
                    if (candidateState.value.hasPrevPage) { keyRouter.pageUp(); highlightIndex.intValue = 0; return true }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val maxIdx = candidateState.value.candidates.size - 1
                    highlightIndex.intValue = (highlightIndex.intValue + 1).coerceAtMost(maxIdx)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    highlightIndex.intValue = (highlightIndex.intValue - 1).coerceAtLeast(0)
                    return true
                }
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER -> {
                    if (candidateState.value.candidates.isNotEmpty()) {
                        keyRouter.selectCandidate(highlightIndex.intValue)
                        highlightIndex.intValue = 0
                        return true
                    }
                }
                KeyEvent.KEYCODE_1 -> { keyRouter.selectCandidate(0); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_2 -> { keyRouter.selectCandidate(1); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_3 -> { keyRouter.selectCandidate(2); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_4 -> { keyRouter.selectCandidate(3); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_5 -> { keyRouter.selectCandidate(4); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_6 -> { keyRouter.selectCandidate(5); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_7 -> { keyRouter.selectCandidate(6); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_8 -> { keyRouter.selectCandidate(7); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_9 -> { keyRouter.selectCandidate(8); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_0 -> { keyRouter.selectCandidate(9); highlightIndex.intValue = 0; return true }
            }
        }
        val isShifted = e.isShiftPressed
        val unicodeChar = e.getUnicodeChar(e.metaState)
        val key = keyCodeToKey(keyCode, isShifted, unicodeChar)
        if (key != null) {
            keyRouter.handleKeyPress(key, isShifted)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                if (!pendingHardwareShiftToggle && !hardwareModifierCombo) {
                    return super.onKeyUp(keyCode, event)
                }
                val keyName = if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT) "Shift_L" else "Shift_R"
                if (pendingHardwareShiftToggle && !hardwareModifierCombo) {
                    keyRouter.handleHardwareSwitchKey(keyName)
                }
                pendingHardwareShiftToggle = false
                hardwareModifierCombo = false
                return true
            }
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> {
                if (!pendingHardwareCtrlToggle && !hardwareModifierCombo) {
                    return super.onKeyUp(keyCode, event)
                }
                val keyName = if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT) "Control_L" else "Control_R"
                if (pendingHardwareCtrlToggle && !hardwareModifierCombo) {
                    keyRouter.handleHardwareSwitchKey(keyName)
                }
                pendingHardwareCtrlToggle = false
                hardwareModifierCombo = false
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun sendKeyEvent(keyCode: Int) {
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    override fun executeCommand(name: String) {
        when (name) {
            "clear_composition" -> {
                keyRouter.postRimeJob {
                    rimeEngine.clearComposition()
                    withContext(Dispatchers.Main) {
                        mainHandler.post { updateUI() }
                    }
                }
            }
            "show_ime_picker" -> {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                @Suppress("DEPRECATION")
                imm.showInputMethodPicker()
            }
            else -> FileLogger.w(TAG, "Unknown command: $name")
        }
    }

    override fun repeatLastInput() {
        val lastText = predictionManager.lastCommittedText
        if (lastText.isNotEmpty()) {
            currentInputConnection?.commitText(lastText, 1)
        }
    }

    override fun dispatchKey(key: String) {
        // 与物理键盘 onKeyDown 同一入口：handleKeyPress 内部自行调度到 key-processing 线程
        keyRouter.handleKeyPress(key, false)
    }

    // ── 原有方法 ──

    
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        loadDarkModePreference()

        // 敏感输入框（密码等）判定 + composing 去重标志重置（详见 PluginEventDispatcher）
        pluginEvents.onStartInput(attribute)

        // 受限输入框判定（密码/终端/NO_SUGGESTIONS）：供英文联想等补全功能短路
        editorRestricted = EditorInfoClassifier.isRestrictedEditor(attribute)
        // 秘密输入框判定（密码/终端，不含 NO_SUGGESTIONS）：英文联想/回删替换的禁用线
        editorSecret = EditorInfoClassifier.isSecretEditor(attribute)

        // 输入 target 变化：旧编辑框的 composing 区域不再可达，复位标记。
        // 防御 stale 标记导致 endComposingInputBox 对新编辑框执行 setComposingText("")
        // （无 composing 时会在光标处插入空串，选中文字时等于删除选区）。
        if (!restarting) {
            inputBoxComposingActive = false
        }

        predictionManager.clearCommittedText()
        // 新输入会话清空 partial commit 累积：外部 UI（如设置页输入框"清除"按钮仅清 Compose
        // state）会触发 restartInput → 此处重建 T9，若残留累积会被 buildT9DisplayState 拼进
        // preedit 回灌输入框（2026-08-07 日志实证：清除后 testText 从 '' 回灌为 '几乎'）。
        t9PartialSegments.clear()
        debugLog("onStartInput: cleared lastCommittedText")

        // 跨进程同步文件日志开关（开关在主进程设置页切换）
        FileLogger.setVerboseLoggingEnabled(
            SettingsPreferences.isVerboseLoggingEnabled(this)
        )
        
        if (RimeEngine.isInitialized()) {
            // 部署/全量编译进行中：不执行 schema 切换（switchSchema 会等待 rimeLock，
            // 60MB 词库编译可达 30s+，主线程等待会导致 ANR）。部署完成后
            // initRimeEngine 的流程会自动切换到正确方案，这里只做 UI 状态恢复。
            if (!rimeEngine.isMaintaining()) {
                val savedSchema = SettingsPreferences.getCurrentSchema(this)
                val currentSchema = rimeEngine.getCurrentSchema()
                val availableSchemas = rimeEngine.getAvailableSchemas()
                debugLog("onStartInput: saved=$savedSchema, current=$currentSchema, available=${availableSchemas.joinToString()}")
                
                val actualSchema: String
                when {
                    isHandwritingSchema(savedSchema) -> {
                        debugLog("onStartInput: saved schema is handwriting, checking model files")
                        val hwDir = com.kingzcheung.xime.model.ModelStorage.getModelDir(this, "ochwpro")
                        com.kingzcheung.xime.model.ModelStorage.migrateLegacyForModel(this, "ochwpro")
                        val modelFile = java.io.File(hwDir, "ochwpro.onnx")
                        val charIndexFile = java.io.File(hwDir, "char_index.json")
                        if (!modelFile.exists() || !charIndexFile.exists()) {
                            FileLogger.w(TAG, "Handwriting model not found, falling back to first available schema")
                            android.widget.Toast.makeText(
                                this, "请先下载手写模型", android.widget.Toast.LENGTH_LONG
                            ).show()
                            val fallbackSchema = if (availableSchemas.isNotEmpty()) {
                                availableSchemas.first()
                            } else {
                                savedSchema
                            }
                            schemaController.applyPageSizeSetting(fallbackSchema)
                            rimeEngine.switchSchema(fallbackSchema)
                            SettingsPreferences.setCurrentSchema(this, fallbackSchema)
                            actualSchema = fallbackSchema
                        } else {
                            debugLog("onStartInput: saved schema is handwriting, keeping handwriting mode")
                            keyboardViewModel.switchMain(com.kingzcheung.xime.keyboard.MainType.HANDWRITING)
                            // 手写模型按"用键盘时加载"管理：不在此加载/重载，
                            // 布局创建（LaunchedEffect）与落笔时的 predict 自愈兜底
                            actualSchema = savedSchema
                        }
                    }
                    savedSchema in availableSchemas -> {
                        if (savedSchema != currentSchema) {
                            debugLog("onStartInput: Switching to saved schema: $savedSchema")
                            schemaController.applyPageSizeSetting(savedSchema)
                            rimeEngine.switchSchema(savedSchema)
                        } else {
                            // 即使 schema 相同也重新 switch 一下，确保 processor 完全初始化
                            debugLog("onStartInput: Schema already matches, re-switching to init processors")
                            schemaController.applyPageSizeSetting(savedSchema)
                            rimeEngine.switchSchema(savedSchema)
                        }
                        actualSchema = savedSchema
                    }
                    SchemaManager.isSchemaCompiled(this@XimeInputMethodService, savedSchema) -> {
                        debugLog("onStartInput: Schema compiled but not in get_schema_list, switching anyway")
                        schemaController.applyPageSizeSetting(savedSchema)
                        rimeEngine.switchSchema(savedSchema)
                        actualSchema = savedSchema
                    }
                    availableSchemas.isNotEmpty() -> {
                        val fallbackSchema = availableSchemas.first()
                        debugLog("onStartInput: savedSchema '$savedSchema' not available, falling back to '$fallbackSchema'")
                        schemaController.applyPageSizeSetting(fallbackSchema)
                        rimeEngine.switchSchema(fallbackSchema)
                        SettingsPreferences.setCurrentSchema(this, fallbackSchema)
                        actualSchema = fallbackSchema
                    }
                    else -> actualSchema = savedSchema
                }
                sessionController.updateSchemaName()
                
                // 从 user.yaml 恢复方案选项（中/西、简/繁等，含 ascii_mode）
                sessionController.restorePersistedSchemaOptions()
                updateUI()
            } else {
                debugLog("onStartInput: deployment in progress, skipping schema switch")
            }
        }

        uiState.value = uiState.value.copy(
            inputSessionId = System.nanoTime(),
            isSttEnabled = SettingsPreferences.isSttEnabled(this@XimeInputMethodService),
        )

        // 重置键盘布局到初始状态，避免切换应用后仍残留之前的布局（如英文、数字、符号）。
        // 必须携带当前 schemaId，否则 T9/笔画等专用布局会被错误重置为默认全键盘。
        // restarting=true 表示同一输入会话内的状态刷新（应用 restartInput），此时不应
        // 重置布局，否则数字/符号面板会在输入中被切回全键盘。
        if (RimeEngine.isInitialized() && !restarting) {
            // ascii 确定性决策：默认中文（英文态不跨收起存活），密码框临时英文。
            // 不读引擎当前值——上次会话收起时的落点不再决定本次初始状态
            val startAscii = asciiModeController.applyStartDecision(attribute)
            FileLogger.i(TAG, "onStartInput: reset keyboard, startAscii=$startAscii")
            uiState.value = uiState.value.copy(isAsciiMode = startAscii)
            // currentSchemaId 为空（如引擎重建后 updateSchemaName 尚未完成）时，
            // 用持久化方案兜底，避免布局退化为 26 键全键盘
            val schemaId = uiState.value.currentSchemaId
                .ifBlank { SettingsPreferences.getCurrentSchema(this) }
            // 纯数字输入框（号码/验证码等）自动进入数字面板，可由设置关闭
            val forceNumberPanel = EditorInfoClassifier.isNumberEditor(attribute) &&
                SettingsPreferences.isAutoNumberKeyboardEnabled(this)
            debugLog(
                "onStartInput: editor inputType=0x${Integer.toHexString(attribute?.inputType ?: 0)}, " +
                    "restricted=$editorRestricted, forceNumberPanel=$forceNumberPanel"
            )
            keyboardViewModel.resetKeyboard(startAscii, schemaId, forceNumberPanel)
        } else {
            val rimeAscii = if (RimeEngine.isInitialized()) rimeEngine.isAsciiMode() else "n/a"
            FileLogger.i(TAG, "onStartInput: skip keyboard reset, restarting=$restarting, rimeAscii=$rimeAscii, ui=${uiState.value.isAsciiMode}")
        }

        // 先重置候选状态到初始值，避免前一 session 的残留状态影响新输入
        candidateState.value = CandidateState()

        // 获取最近30秒的剪切板内容
        ensureClipboardManagerInitialized()
        try {
            recentClipboardItemsState.value = clipboardManager.getRecentItems(30)
            // 将最近剪切板内容显示在候选栏
            candidateState.value = candidateState.value.copy(
                candidates = recentClipboardItemsState.value.map { it.text },
                candidateComments = emptyList(),
                isShowingRecentClipboard = true
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to get recent clipboard items", e)
        }

        // 监听clipboardItems变化，更新候选栏
        clipboardCollectorJob?.cancel()
        clipboardCollectorJob = serviceScope.launch {
            clipboardManager.clipboardItems.collect { _ ->
                val items = clipboardManager.getRecentItems(30)
                recentClipboardItemsState.value = items
                if (items.isNotEmpty()) {
                    // 清空Rime联想词等
                    rimeEngine.clearComposition()
                    candidateState.value = candidateState.value.copy(
                        candidates = items.map { it.text.take(8) + if (it.text.length > 8) "..." else "" },
                        candidateComments = emptyList(),
                        inputText = "",
                        isComposing = false,
                        associationCandidates = emptyList(),
                        isShowingRecentClipboard = true
                    )
                } else if (candidateState.value.isShowingRecentClipboard) {
                    // 如果没有recent items，清空候选栏
                    candidateState.value = candidateState.value.copy(
                        candidates = emptyList(),
                        candidateComments = emptyList(),
                        isShowingRecentClipboard = false,
                        candidateActions = emptyList()
                    )
                }
            }
        }

        attribute?.let { updateEnterKeyText(it) }
    }
    
    private val highlightIndex = mutableIntStateOf(0)
    /** 单独按下 Shift/Ctrl 时切换中英文；与其他键组成快捷键时不触发切换。 */
    private var pendingHardwareShiftToggle = false
    private var pendingHardwareCtrlToggle = false
    private var hardwareModifierCombo = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        info?.let { updateEnterKeyText(it) }
        hasHardwareKeyboard = resources.configuration.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS
        applyCompactMode()
        applyWindowBackground()
        if (hasHardwareKeyboard) {
            currentInputConnection?.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_MONITOR or InputConnection.CURSOR_UPDATE_IMMEDIATE
            )
        }
    }

    private var anchorCoords = floatArrayOf(0f, 0f, 0f, 0f)

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        if (!hasHardwareKeyboard) return
        try {
            val bounds = info.getCharacterBounds(0)
            if (bounds != null) {
                anchorCoords[0] = bounds.left
                anchorCoords[1] = bounds.bottom
                anchorCoords[2] = bounds.left
                anchorCoords[3] = bounds.top
            } else {
                anchorCoords[0] = info.insertionMarkerHorizontal
                anchorCoords[1] = info.insertionMarkerBottom
                anchorCoords[2] = info.insertionMarkerHorizontal
                anchorCoords[3] = info.insertionMarkerTop
            }
            if (anchorCoords.any(Float::isNaN)) return
            info.matrix.mapPoints(anchorCoords)
            val screenY = anchorCoords[1].toInt().coerceIn(0, resources.displayMetrics.heightPixels)
            val screenX = anchorCoords[0].toInt().coerceIn(0, resources.displayMetrics.widthPixels)
            uiState.value = uiState.value.copy(
                cursorX = screenX,
                cursorY = screenY,
                cursorVisible = true,
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "onUpdateCursorAnchorInfo failed", e)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    override fun onEvaluateInputViewShown(): Boolean {
        return true
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        return true
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        hasHardwareKeyboard = newConfig.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS
        super.onConfigurationChanged(newConfig)
        if (newConfig.screenWidthDp > newConfig.screenHeightDp) {
            closeToolPanel()
        }
        applyCompactMode()
        loadDarkModePreference()
        applyWindowBackground()
        if (hasHardwareKeyboard) {
            currentInputConnection?.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_MONITOR or InputConnection.CURSOR_UPDATE_IMMEDIATE
            )
        }
    }

    internal fun applyWindowBackground() {
        val state = uiState.value
        val isDark = isDarkTheme()
        try {
            val theme = com.kingzcheung.xime.ui.theme.KeyboardThemes.getThemeById(state.themeId)
            // 图片背景无法映射到 window 层，用主题主色作为导航栏/窗口兜底色；
            // solid / gradient 用解析出的键盘背景兜底色。
            val bgColor = if (theme.keyboardBackground?.type == "image") {
                com.kingzcheung.xime.ui.theme.KeyboardThemes.getPrimaryColor(state.themeId, isDark)
            } else {
                com.kingzcheung.xime.ui.theme.KeyboardThemes.getKeyboardBackgroundColor(state.themeId, isDark)
            }
            val argb = (bgColor.alpha * 255).toInt() shl 24 or
                (bgColor.red * 255).toInt() shl 16 or
                (bgColor.green * 255).toInt() shl 8 or
                (bgColor.blue * 255).toInt()
            window.window?.let { win ->
                if (state.isCompact) {
                    // 硬件键盘候选栏模式：窗口透明
                    win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    win.setDimAmount(0f)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        win.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
                    }
                } else if (state.isFloatingMode) {
                    win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    win.setDimAmount(0f)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        win.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
                    }
                } else {
                    // 非浮动模式：参考成熟输入法 FULL 方案的背景/高度布局。
                    // 1) edge-to-edge：窗口绘制到系统导航栏后面，键盘背景（渐变/图片）可延伸到底部；
                    // 2) 窗口背景透明：键盘内容由 Compose 绘制，键盘上方露出应用内容而不是白色/主题色块；
                    // 3) 导航栏透明 + 关闭强制对比度：底部导航栏区域由键盘背景覆盖，不会露出系统白色。
                    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(win, false)
                    win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    win.setDimAmount(0f)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        win.isNavigationBarContrastEnforced = false
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        win.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    win.decorView?.let { decor ->
                        val controller = androidx.core.view.WindowInsetsControllerCompat(win, decor)
                        controller.isAppearanceLightNavigationBars = !isDark
                    }
                }
                // setDecorFitsSystemWindows(false) 后必须重新分发 insets，
                // 否则 onApplyWindowInsets 不会触发、底部导航栏高度检测不到。
                win.decorView?.requestApplyInsets()
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "applyWindowBackground failed", e)
        }
    }

    private fun applyCompactMode() {
        val current = uiState.value
        val isCompact = hasHardwareKeyboard
        FileLogger.i(
            TAG,
            "applyCompactMode: keyboardCfg=${keyboardConfigName(resources.configuration.keyboard)}, " +
                "hasHardwareKeyboard=$hasHardwareKeyboard, isCompact=$isCompact (was ${current.isCompact})"
        )
        if (current.isCompact != isCompact) {
            uiState.value = current.copy(isCompact = isCompact)
            if (isCompact) {
                window.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
        }
    }

    private fun keyboardConfigName(value: Int): String = when (value) {
        android.content.res.Configuration.KEYBOARD_UNDEFINED -> "UNDEFINED"
        android.content.res.Configuration.KEYBOARD_NOKEYS -> "NOKEYS"
        android.content.res.Configuration.KEYBOARD_QWERTY -> "QWERTY"
        android.content.res.Configuration.KEYBOARD_12KEY -> "12KEY"
        else -> "UNKNOWN($value)"
    }

    private fun moveFloatingWindow(dx: Int, dy: Int) {
        window.window?.let { win ->
            val lp = win.attributes
            if (lp.gravity != (android.view.Gravity.TOP or android.view.Gravity.START)) {
                lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
            lp.x = (lp.x + dx).coerceAtLeast(0)
            lp.y = (lp.y + dy).coerceAtLeast(0)
            win.attributes = lp
        }
    }

    private fun updateEnterKeyText(editorInfo: EditorInfo) {
        val imeOptions = editorInfo.imeOptions
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val enterText = when {
            noEnterAction -> "换行"
            action == EditorInfo.IME_ACTION_GO -> "前往"
            action == EditorInfo.IME_ACTION_SEARCH -> "搜索"
            action == EditorInfo.IME_ACTION_SEND -> "发送"
            action == EditorInfo.IME_ACTION_NEXT -> "下一项"
            action == EditorInfo.IME_ACTION_DONE -> "完成"
            else -> "换行"
        }
        uiState.value = uiState.value.copy(enterKeyText = enterText)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        inlineSuggestionManager?.clear()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        // 会话结束：引擎 ascii 归位默认中文，英文态不跨会话残留
        asciiModeController.restoreDefaultChoice()
        clearInputState()
        recentClipboardItemsState.value = emptyList()
    }
    
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // 手写模型轻量，按"用键盘时加载、键盘收起即卸载"管理：输入会话结束
        // （收起键盘/焦点离开）即释放，:inference 侧同步卸载模型；未初始化时
        // release() 幂等空操作。下次落笔由 predict 自愈或布局重建重载。
        com.kingzcheung.xime.handwriting.HandwritingEngine.release()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        clearInputState()
        recentClipboardItemsState.value = emptyList()
        // 键盘隐藏时重置快捷发送表单临时态：表单开启状态下直接隐藏键盘（未走 onClose）
        // 会把 showQuickSendForm 残留到下次弹窗，候选栏上方渲染出残留表单背景造成遮挡；
        // 回车键文案也需一并还原（打开表单时被改为"确定"）。
        if (uiState.value.showQuickSendForm || uiState.value.quickSendFormFocused) {
            uiState.value = uiState.value.copy(
                showQuickSendForm = false,
                quickSendFormFocused = false,
                quickSendCodeFocused = false,
                quickSendEditingItemId = null,
                quickSendEditingItemText = "",
                quickSendEditingItemCode = "",
                enterKeyText = "发送",
            )
            QuickSendFormEditTextHolder.editText = null
            QuickSendFormCodeEditTextHolder.editText = null
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // 键盘弹出时对比系统取色与缓存的动态主题色，壁纸取色变化则重建主题并热更新 UI。
        // 每次弹出只做两次资源读取对比，取色未变时零成本。
        KeyboardThemes.refreshDynamicSchemes(this)
        clipboardSyncBridge?.pullOnce()
    }
    
    private fun clearInputState() {
        closeToolPanel()
        // 输入会话结束：关闭残留的面板页面（表情/符号等 overlay），
        // 避免下次键盘弹出时在候选栏上方渲染上次的面板背景
        var page = keyboardViewModel.page.value
        while (page is com.kingzcheung.xime.keyboard.KeyboardPage.Overlay) {
            keyboardViewModel.closeOverlay()
            page = keyboardViewModel.page.value
        }
        calculatorEngine.clear()
        rimeEngine.clearComposition()
        t9PartialSegments.clear()
        // 输入法隐藏/结束输入：静默停止语音会话，丢弃未识别文本，避免迟到结果写入新输入框
        if (uiState.value.isVoiceMode || voiceRecordingStarted) {
            voiceRecognitionHandler.abandonSession()
            voiceRecognitionHandler.stopRecognition()
            voiceRecognitionHandler.cancelPreStart()
            isTrackingVoiceButtons = false
            voiceRecordingStarted = false
            voiceAmplitudeState.floatValue = 0f
            uiState.value = uiState.value.copy(
                isVoiceMode = false,
                voiceSticky = false,
                voiceButtonState = VoiceButtonState(),
                voiceRecognitionState = RecognitionState.IDLE,
                voiceRecognizedText = "",
                voiceAmplitude = 0f
            )
            keyboardViewModel.exitVoice()
        }
        uiState.value = uiState.value.copy(
            t9ResetSignal = uiState.value.t9ResetSignal + 1,
            t9RightCandidateSelectedCount = 0,
            t9SelectedCandidatePinyin = ""
        )
        candidateState.value = candidateState.value.copy(
            candidates = emptyList(),
            candidateComments = emptyList(),
            inputText = "",
            isComposing = false,
            isShowingRecentClipboard = false,
            associationCandidates = emptyList(),
            pendingEnglishText = "",
            hasNextPage = false,
            hasPrevPage = false,
            englishReplaceSupported = true,
            candidateActions = emptyList()
        )
        endComposingInputBox()
    }

    /**
     * 输入框是否存在 IME 写入的 composing 区域（拼音编码回显 / 语音识别临时文本）。
     * Android 无法查询宿主编辑器的 composing 状态，由写入点主动标记。
     */
    private var inputBoxComposingActive = false

    /** 标记刚向输入框写入了 composing 文本（showInputBoxComposition / 语音 partial）。 */
    internal fun markInputBoxComposing() {
        inputBoxComposingActive = true
    }

    /**
     * 清理输入框中的 composing 区域（未上屏的拼音编码 / 语音临时文本）。
     *
     * 无论输入位置设置（输入框/候选栏）都执行。
     * 注意：composing 区域不存在时 [InputConnection.setComposingText] 并非空操作——
     * 它会在光标处"插入"空串，光标处有选中文字时等于删除整个选区
     * （收起键盘/焦点切换误删选中文字的根因）。因此仅在标记过 composing 时才清空，
     * 否则只调用 finishComposingText（无 composing 时是无害空操作，仅兜底清理残留 span）。
     */
    internal fun endComposingInputBox() {
        currentInputConnection?.let {
            if (inputBoxComposingActive) {
                it.setComposingText("", 0)
                it.finishComposingText()
            } else {
                it.finishComposingText()
            }
        }
        inputBoxComposingActive = false
    }

    /**
     * 当前输入框是否受限（密码/终端/NO_SUGGESTIONS，见 EditorInfoClassifier）：
     * 英文联想等补全类功能应短路。
     */
    internal fun isEditorRestricted(): Boolean = editorRestricted

    internal fun isSecretEditor(): Boolean = editorSecret

    /**
     * 当前宿主是否支持英文候选的"回删替换"机制。
     *
     * 英文直接上屏模式下，选中候选词需要 deleteSurroundingText 回删已上屏编码再提交候选词；
     * 终端等受限宿主对该能力（含文本探测接口）通常不支持，探针返回 null。
     * 此类宿主直接不提供英文联想候选。
     */
    internal fun supportsEnglishCandidateReplace(): Boolean {
        val ic = currentInputConnection ?: return false
        return runCatching { ic.getTextBeforeCursor(1, 0) != null }.getOrDefault(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        sharedPrefsListener?.let {
            SettingsPreferences.getPrefsPublic(this).unregisterOnSharedPreferenceChangeListener(it)
        }
        RimeEngine.setDeploymentCallback { _, _ -> }
        stopClipboardSync()
        if (::clipboardManager.isInitialized) {
            clipboardManager.release()
        }
        _viewModelStore.clear()
        feedbackManager.release()
        rimeEngine.destroy()
        AssociationManager.release()
        voiceRecognitionHandler.release()
        com.kingzcheung.xime.handwriting.HandwritingEngine.release()
        ExtensionManager.release()
        com.kingzcheung.xime.association.NativeOnnxEngine.releaseSharedEnv()
        serviceScope.cancel()
        keyProcessingDispatcher.close()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
    
    internal fun hideKeyboard() {
        clearInputState()
        requestHideSelf(0)
    }
    
    internal fun updateUI() {
        val composition = rimeEngine.getComposition()
        // 候选词变换（hotPath 插件能力）：仅 key-processing 线程同步等插件（至多 15ms），
        // 主线程调用点（联想上屏/光标移动/剪贴板点选后的刷新）一律跳过——主线程永不等待插件；
        // 这些调用点组合态已清空（input 为空），正常不触发，Looper 判定仅为防御
        val transformed = if (composition.input.isNotEmpty() &&
            android.os.Looper.myLooper() != android.os.Looper.getMainLooper()
        ) {
            candidateTransform.transform(
                inputText = composition.input,
                preedit = composition.preedit,
                engineCandidates = composition.candidates.toList(),
                asciiMode = composition.isAsciiMode,
            )
        } else {
            null
        }
        if (transformed != null) {
            sessionController.applyComposition(
                composition.copy(candidates = transformed.candidates.toTypedArray()),
                transformed.actions
            )
        } else {
            sessionController.applyComposition(composition)
        }
    }

    /**
     * 用户开始输入时清除候选栏中的 inline suggestions，让位于正常输入候选。
     */
    internal fun dismissInlineSuggestions() {
        inlineSuggestionManager?.clear()
    }


    

    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (inlineSuggestionManager == null) return null
        updateInlineSuggestionTheme()
        val result = inlineSuggestionManager.onCreateInlineSuggestionsRequest(uiExtras)
        return result
    }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return inlineSuggestionManager?.onInlineSuggestionsResponse(response) ?: false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun updateInlineSuggestionTheme() {
        val state = uiState.value
        val isDark = when (state.darkMode) {
            1 -> true
            2 -> (resources.configuration.uiMode.and(
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            )) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> false
        }
        val t = com.kingzcheung.xime.ui.theme.KeyboardThemes
        inlineSuggestionManager?.apply {
            val c = t.getCandidateTextColor(state.themeId, isDark)
            candidateTextColorArgb = (c.alpha * 255).toInt() shl 24 or
                (c.red * 255).toInt() shl 16 or
                (c.green * 255).toInt() shl 8 or
                (c.blue * 255).toInt()
            val label = c.copy(alpha = 0.6f)
            labelTextColorArgb = (label.alpha * 255).toInt() shl 24 or
                (label.red * 255).toInt() shl 16 or
                (label.green * 255).toInt() shl 8 or
                (label.blue * 255).toInt()
            isDarkTheme = isDark
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        val state = uiState.value
        if (state.isCompact) {
            try {
                val decor = window.window?.decorView
                if (decor != null) {
                    val navBarBg = decor.findViewById<View>(android.R.id.navigationBarBackground)
                    val navBarH = navBarBg?.height ?: 0
                    val h = (decor.height - navBarH).coerceAtLeast(0)
                    outInsets.contentTopInsets = h
                    outInsets.visibleTopInsets = h
                    outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
                    return
                }
            } catch (_: Exception) { }
            super.onComputeInsets(outInsets)
        } else if (state.isFloatingMode) {
            outInsets.apply {
                contentTopInsets = resources.displayMetrics.heightPixels
                visibleTopInsets = resources.displayMetrics.heightPixels
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                val decor = window.window?.decorView ?: return
                if (currentEffectiveKeyboardHeight <= 0) {
                    val isLandscape = resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
                    val kbH = SettingsPreferences.getKeyboardHeightDp(this@XimeInputMethodService, isLandscape)
                        .coerceAtMost((resources.configuration.screenHeightDp * 8) / 10)
                    currentEffectiveKeyboardHeight = kbH + 18 + 50 + state.keyboardBottomPaddingDp
                }
                val density = resources.displayMetrics.density
                val inputViewWidthPx = decor.width
                val statusBarHeightDp = tryGetStatusBarHeightDp(this@XimeInputMethodService, window.window)
                val physicalHeightPx = resources.displayMetrics.heightPixels
                val inputViewHeightPx = (physicalHeightPx - (statusBarHeightDp * density).toInt()).coerceAtLeast(1)
                val cardWidthPx = (inputViewWidthPx * 0.85f).toInt()
                val leftPaddingPx = ((inputViewWidthPx - cardWidthPx) / 2f).toInt()
                val offsetXPx = (state.floatingOffsetX * density).toInt()
                val cardHeightPx = (currentEffectiveKeyboardHeight * density).toInt()
                val offsetYPx = (state.floatingOffsetY * density).toInt()
                touchableRegion.set(
                    leftPaddingPx + offsetXPx,
                    inputViewHeightPx - cardHeightPx - offsetYPx,
                    leftPaddingPx + offsetXPx + cardWidthPx,
                    inputViewHeightPx - offsetYPx
                )
            }
        } else {
            // 非浮动模式：窗口全屏，容器物理高度 = Compose 内容总高（含底部留白），
            // 容器在窗口内底部对齐（gravity BOTTOM）。
            // contentTopInsets 直接用容器顶部在窗口中的 y 同步计算：
            // 容器高度变化（面板撑高/收起）→ View relayout → traversal → 本方法
            // 自动以新几何重算并上报，无需 hack；窗口全屏时 super 会误判键盘占满
            // 全屏导致布局下沉，故必须显式报告容器顶部。
            if (::keyboardContainer.isInitialized && keyboardContainer.height > 0) {
                val loc = IntArray(2)
                keyboardContainer.getLocationInWindow(loc)
                val topPx = loc[1].coerceAtLeast(0)
                outInsets.contentTopInsets = topPx
                outInsets.visibleTopInsets = topPx
                outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            } else {
                super.onComputeInsets(outInsets)
            }
        }
    }

    /** 插件下行事件投递器（input_changed / text_committed，敏感输入豁免）。 */
    internal val pluginEvents = PluginEventDispatcher(this)

    /** 候选词变换协调器（插件 candidate_transform 能力，hotPath：key-processing 线程同步调用）。 */
    internal val candidateTransform = CandidateTransformCoordinator(this)

    override fun commitText(text: String) {
        commitTextAndPredict(text, isPaste = false)
    }

    /**
     * 粘贴性质上屏（键盘剪贴板点选/编辑面板提交）：与 [commitText] 相同的上屏与
     * 联想行为，但 text_committed 事件带 is_paste 标记——事件语义是"文本上屏"
     * （照常投递给所有订阅插件），是否把粘贴计入打字量由插件自行决定
     * （typing-stats 过滤，用户反馈"一天一万多字"的主要来源即长文本粘贴）。
     */
    internal fun commitPastedText(text: String) {
        commitTextAndPredict(text, isPaste = true)
    }

    private fun commitTextAndPredict(text: String, isPaste: Boolean) {
        commitTextSilently(text, isPaste)
        if (isChineseMode) {
            mainHandler.post {
                if (!uiState.value.isAsciiMode) {
                    getPredictionFromPlugin(predictionManager.lastCommittedText)
                }
            }
        }
    }

    /**
     * 快捷发送表单内退格：按焦点路由到文本框/触发编码框，删除光标前字符或选区。
     * 与原行为对齐：表单显示即处理（未聚焦时删文本框），焦点在编码框时删编码框。
     * 需在主线程调用；返回 false 表示表单未显示（调用方继续常规退格流程）。
     */
    internal fun deleteInQuickSendForm(): Boolean {
        if (!uiState.value.showQuickSendForm) return false
        val et = if (uiState.value.quickSendFormFocused && uiState.value.quickSendCodeFocused)
            QuickSendFormCodeEditTextHolder.editText
        else QuickSendFormEditTextHolder.editText
        et?.let { box ->
            val start = box.selectionStart.coerceAtLeast(0)
            val end = box.selectionEnd.coerceAtLeast(start)
            if (end > start) {
                box.text?.delete(start, end)
                try { box.setSelection(start) } catch (_: Exception) {}
            } else if (start > 0) {
                box.text?.delete(start - 1, start)
                try { box.setSelection(start - 1) } catch (_: Exception) {}
            }
        }
        return true
    }

    /**
     * 静默上屏：与 [commitText] 相同的落盘路径（内部编辑器重定向、InputConnection、
     * text_committed 事件、联想上下文/输入统计），但不触发联想推理。
     * 手写叠写自动上屏/替换使用——笔画替换频率高，逐次推理既浪费又会闪烁候选栏。
     * [isPaste] 标记粘贴性质上屏，透传到 text_committed payload（见 commitPastedText）。
     * 需在主线程调用。
     */
    internal fun commitTextSilently(text: String, isPaste: Boolean = false) {
        if (uiState.value.quickSendFormFocused) {
            // 焦点在触发编码输入框时路由到编码框，否则路由到快捷发送文本框
            val codeFocused = uiState.value.quickSendCodeFocused
            mainHandler.post {
                val et = if (codeFocused) QuickSendFormCodeEditTextHolder.editText
                else QuickSendFormEditTextHolder.editText
                et?.let { box ->
                    val start = box.selectionStart.coerceAtLeast(0)
                    val textLen = text.length
                    box.text?.replace(start, box.selectionEnd.coerceAtLeast(start), text)
                    try { box.setSelection(start + textLen) } catch (_: Exception) {}
                }
            }
            return
        }
        if (uiState.value.toolPanelInputFocused) {
            mainHandler.post {
                ToolPanelEditTextHolder.editText?.let { et ->
                    val start = et.selectionStart.coerceAtLeast(0)
                    val textLen = text.length
                    et.text?.replace(start, et.selectionEnd.coerceAtLeast(start), text)
                    try { et.setSelection(start + textLen) } catch (_: Exception) {}
                }
            }
            return
        }
        currentInputConnection?.commitText(text, 1)

        // text_committed 事件：真实上屏才累计/投递（内部编辑器分支已在上方 return；
        // 敏感输入框（密码）不计不投；粘贴性质上屏带 is_paste 标记，见 commitPastedText；
        // 详见 PluginEventDispatcher）
        pluginEvents.onTextCommitted(text, isPaste)

        if (isChineseMode) {
            predictionManager.appendCommittedText(text)
            predictionManager.recordInput(text)
        }
    }

    /**
     * 手写活动区固化后触发一轮联想推理（基于已上屏文本）。
     * 空格/标点上屏走全量 commitText 自带推理，无需调用此方法。
     */
    internal fun finalizeHandwritingPrediction() {
        if (!isChineseMode) return
        mainHandler.post {
            if (!uiState.value.isAsciiMode) {
                getPredictionFromPlugin(predictionManager.lastCommittedText)
            }
        }
    }

    /**
     * 删除光标前 count 个字符。
     * 焦点在输入法内部编辑器（快捷发送/工具面板）时作用于对应 EditText，
     * 否则作用于宿主 InputConnection。需在主线程调用。
     */
    internal fun deleteBeforeCursor(count: Int) {
        val quickSendFocused = uiState.value.quickSendFormFocused
        if (quickSendFocused || uiState.value.toolPanelInputFocused) {
            val et = when {
                quickSendFocused && uiState.value.quickSendCodeFocused ->
                    QuickSendFormCodeEditTextHolder.editText
                quickSendFocused -> QuickSendFormEditTextHolder.editText
                else -> ToolPanelEditTextHolder.editText
            }
            et?.let { box ->
                val end = box.selectionStart.coerceAtLeast(0)
                val start = (end - count).coerceAtLeast(0)
                box.text?.replace(start, end, "")
            }
            return
        }
        currentInputConnection?.deleteSurroundingText(count, 0)
    }

    /**
     * 光标前文本与 expected 相同时替换为 replacement，返回是否替换成功。
     * 焦点在输入法内部编辑器时作用于对应 EditText，否则宿主 InputConnection。
     * 不匹配时不做任何操作（调用方决定降级策略）。需在主线程调用。
     */
    internal fun replaceBeforeCursor(expected: String, replacement: String): Boolean {
        val quickSendFocused = uiState.value.quickSendFormFocused
        if (quickSendFocused || uiState.value.toolPanelInputFocused) {
            val et = when {
                quickSendFocused && uiState.value.quickSendCodeFocused ->
                    QuickSendFormCodeEditTextHolder.editText
                quickSendFocused -> QuickSendFormEditTextHolder.editText
                else -> ToolPanelEditTextHolder.editText
            } ?: return false
            val selStart = et.selectionStart.coerceAtLeast(0)
            val start = (selStart - expected.length).coerceAtLeast(0)
            val before = et.text?.substring(start, selStart)
            if (before != expected) return false
            et.text?.replace(start, selStart, replacement)
            try { et.setSelection(start + replacement.length) } catch (_: Exception) {}
            return true
        }
        val ic = currentInputConnection ?: return false
        val before = runCatching {
            ic.getTextBeforeCursor(expected.length, 0)?.toString()
        }.getOrNull()
        if (before != expected) return false
        var replaced = false
        ic.beginBatchEdit()
        try {
            replaced = ic.deleteSurroundingText(expected.length, 0)
            ic.commitText(replacement, 1)
        } finally {
            ic.endBatchEdit()
        }
        return replaced
    }

    
    
}
