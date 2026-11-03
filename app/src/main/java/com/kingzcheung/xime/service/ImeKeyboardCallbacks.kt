package com.kingzcheung.xime.service

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.kingzcheung.xime.keyboard.KeyActionContext
import com.kingzcheung.xime.keyboard.KeyActionRegistry
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.rime.RimeProcessResult
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.FloatingCardGeometry
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.ui.keyboard.KeyboardResizeBounds
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.util.FileLogger
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 构建键盘回调集合（KeyboardCallbacks）。
 *
 * 所有回调直接操作服务层状态与方法；service 内部成员对同模块可见（internal）。
 * 仅当 [floatingMinY] 变化时重建；回调体内一律读 [XimeInputMethodService.uiState]
 * 现值——此前闭包捕获组合期的 state/effectiveScreenH，remember 只以 floatingMinY
 * 为 key，回调会拿着首组过期值工作（如悬浮拖动用了过期的屏幕高与模式开关）。
 */
@Composable
internal fun rememberImeKeyboardCallbacks(
    service: XimeInputMethodService,
    floatingMinY: Int,
): KeyboardCallbacks {
    val view = LocalView.current
    return remember(floatingMinY) {
        KeyboardCallbacks(
            onKeyPress = { key, isShifted ->
                service.keyRouter.handleKeyPress(key, isShifted)
            },
            onKeyPressDown = { key ->
                // 删除键按下：开一次删除会话快照（下滑撤回删除用），必须在首次退格
                // 派发之前（同一主线程回调内同步完成），否则首个被删字符漏记。
                // 会话结算见 XimeInputMethodService.finishDeleteSession：由撤回请求或
                // 其它按键触发，不在抬手上结算（抬手时退格 job 可能还在队列里没落盘）。
                if (key == "delete") service.beginDeleteSession()
                service.feedbackManager.performKeyPressDownEffect(key, view)
            },
            onKeyRelease = { key ->
                service.feedbackManager.hapticFeedback(view, keyUp = true)
            },
            onCandidateSelect = { index ->
                service.keyRouter.selectCandidate(index)
            },
            onCandidateDelete = { index ->
                service.keyRouter.deleteCandidate(index)
            },
            onAssociationSelect = { index ->
                service.feedbackManager.performKeyPressEffect(view = view)
                val cs = service.candidateState.value
                val adjustedCandidates = if (cs.pendingEnglishText.isNotEmpty() && cs.englishReplaceSupported) {
                    listOf(cs.pendingEnglishText) + cs.associationCandidates
                } else {
                    cs.associationCandidates
                }
                if (index >= 0 && index < adjustedCandidates.size) {
                    val text = adjustedCandidates[index]
                    val pendingEnglish = cs.pendingEnglishText
                    if (pendingEnglish.isNotEmpty()) {
                        // 英文直接上屏模式：编码已逐字落盘，选中候选词时需回删屏上编码再提交候选词。
                        // 第 0 项即当前已键入文本本身（上屏确认），无需替换。
                        if (index == 0 && text == pendingEnglish) {
                            service.candidateState.value = service.candidateState.value.copy(
                                pendingEnglishText = "",
                                associationCandidates = emptyList()
                            )
                        } else {
                            // 内部编辑器（快捷发送/工具面板）与宿主 InputConnection 统一走
                            // service 层重定向，避免编码回删/替换作用到错误的屏上文本。
                            var replaced = service.replaceBeforeCursor(pendingEnglish, text)
                            if (!replaced) {
                                // 光标位置与编码不对应（用户移动过光标）：放弃替换，
                                // 候选词降级为直接追加上屏，避免误删用户文本。
                                service.commitText(text)
                            }
                            FileLogger.d(
                                XimeInputMethodService.TAG,
                                "english candidate replace: replaced=$replaced, expected=${pendingEnglish.length} chars"
                            )
                            service.candidateState.value = service.candidateState.value.copy(
                                pendingEnglishText = "",
                                associationCandidates = emptyList()
                            )
                        }
                    } else {
                        // 单次联想模式（仅中文模式——英文 commitText 不触发推理，
                        // 否则抑制标志会悬挂并吞掉下次切回中文后的首轮推理）：
                        // 联想候选上屏前先置抑制标志——commitText 触发的下一轮自动推理
                        // 被跳过并清空联想候选（只推理一次）。
                        // 连续联想模式：不抑制，commitText 自动推理新联想（一直上屏一直推理）。
                        if (!service.uiState.value.isAsciiMode && SettingsPreferences.isSingleAssociationMode(service)) {
                            service.predictionManager.suppressNextPredictionOnce()
                        }
                        service.commitText(text)
                        service.updateUI()
                    }
                }
            },
            onClearAssociation = {
                // 清空英文联想候选，并结束英文输入态：pendingEnglish 清空后，
                // 后续刷新不再触发联想异步回填（否则"清了又冒出来"，见
                // ImeSessionController/updateUIWithResult 的在途回填校验）。
                service.candidateState.value = service.candidateState.value.copy(
                    associationCandidates = emptyList(),
                    pendingEnglishText = ""
                )
            },
            onToggleDarkMode = { service.toggleDarkMode() },
            onClipboard = {},
            onClipboardSelect = { text -> service.textCommit.selectClipboardItem(text) },
            onQuickSendSelect = { text -> service.textCommit.selectQuickSendItem(text) },
            onClipboardImageSelect = { item -> service.textCommit.selectClipboardImage(item) },
            onClipboardPullRemote = { service.clipboardSyncBridge?.pullOnce() },
            onCommitText = { text -> service.textCommit.commitClipboardText(text) },
            onDeleteText = { count -> service.textCommit.deleteClipboardChars(count) },
            onHandwritingAutoCommit = { newTail, expectedTail ->
                if (expectedTail.isEmpty()) {
                    service.commitTextSilently(newTail)
                    true
                } else {
                    // 光标前文本与手写尾部不对应（用户移动过光标/退格过）时不上屏，
                    // 调用方重置手写尾部状态后以追加模式重建，避免误删/重复上屏
                    service.replaceBeforeCursor(expectedTail, newTail)
                }
            },
            onHandwritingFinalize = { service.finalizeHandwritingPrediction() },
            onQuickSend = {},
            onKeyboardResize = {
                if (service.isHostConstrained()) {
                    // 独立 IME 面板：调节页按屏幕尺寸给的边界远大于面板，确定/取消按钮会
                    // 落到可见区外关不掉，直接拒绝进入
                    android.widget.Toast.makeText(
                        service,
                        tr(R.string.s_dangqianxianshiquyubuzhichijia_20f47),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val config = service.resources.configuration
                    val isLandscape = config.screenWidthDp > config.screenHeightDp
                    // 按当前屏宽收紧已存边距（横竖屏切换后旧值可能超出上限）
                    val maxMargin = KeyboardResizeBounds.maxHorizontalMarginDp(config.screenWidthDp)
                    service.uiState.value = service.uiState.value.copy(
                        showKeyboardResize = true,
                        resizePreviewHeightDp = SettingsPreferences.getKeyboardHeightDp(service, isLandscape),
                        resizePreviewMarginStartDp = SettingsPreferences.getKeyboardMarginStartDp(service)
                            .coerceIn(0, maxMargin),
                        resizePreviewMarginEndDp = SettingsPreferences.getKeyboardMarginEndDp(service)
                            .coerceIn(0, maxMargin),
                    )
                }
            },
            onReloadConfig = { service.schemaController.reloadConfig() },
            onSettings = { service.schemaController.openSettings() },
            onSwitchSchema = { schemaId -> service.schemaController.switchSchema(schemaId) },
            onToggleSchemaSwitch = { sw -> service.sessionController.toggleSchemaSwitch(sw) },
            onHideKeyboard = { service.hideKeyboard() },
            onSwitchKeyboard = {
                val imm = service.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                @Suppress("DEPRECATION")
                imm.showInputMethodPicker()
            },
            onToolbarEditingAction = { action -> service.schemaController.handleToolbarEditingAction(action) },
            onCommitImage = { imagePath ->
                val success = service.textCommit.commitImage(imagePath)
                if (!success) {
                    android.widget.Toast.makeText(
                        service,
                        tr(R.string.s_fasongshibai_yifuzhidaojiantieban),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    service.clipboardManager.copyImageToSystemClipboard(imagePath)
                }
            },
            onVoiceModeChange = { enabled ->
                if (!enabled) {
                    // 长按抬手/常驻语音轻触空格或点按候选栏频谱：结束语音会话
                    // （提交当前已识别文本并停止识别）。
                    // 常驻语音下补一次震动反馈——轻触空格没有任何按键事件，
                    // 不补就完全没有触感（与进入语音时的 performVibration 对称）。
                    if (service.uiState.value.voiceSticky) {
                        service.feedbackManager.performVibration()
                    }
                    service.endVoiceSession()
                } else if (SettingsPreferences.isSttEnabled(service) && !service.uiState.value.isVoiceMode) {
                    service.uiState.value = service.uiState.value.copy(
                        isVoiceMode = true,
                        voiceSticky = false,
                        voiceButtonState = VoiceButtonState(bottomActive = true),
                        voiceRecognizedText = ""
                    )
                    service.keyboardViewModel.enterVoice()
                    service.feedbackManager.performVibration()
                    service.isTrackingVoiceButtons = true
                    service.keyboardContainer.enableVoiceButtonTracking()
                    service.voiceRecordingStarted = true
                    // 立即提前启动麦克风录音（等 150ms 的话模型常驻时加载极快，
                    // preStarted 可能还没创建好就被 startRecording 跳过，导致开头丢失）
                    service.voiceRecognitionHandler.startDelayedPreStart(0)
                    service.voiceRecognitionHandler.startRecognition()
                }
            },
            onVoiceStickyToggle = {
                val state = service.uiState.value
                if (state.isVoiceMode && state.voiceSticky) {
                    // 常驻语音中：点按空格/再次点击工具栏即结束
                    service.endVoiceSession()
                } else if (SettingsPreferences.isSttEnabled(service) && !state.isVoiceMode) {
                    // 进入常驻语音：保持正常键盘布局，候选栏显示频谱，空格键轻触结束
                    service.uiState.value = service.uiState.value.copy(
                        isVoiceMode = true,
                        voiceSticky = true,
                        voiceButtonState = VoiceButtonState(bottomActive = true),
                        voiceRecognizedText = ""
                    )
                    service.voiceRecordingStarted = true
                    service.voiceRecognitionHandler.startDelayedPreStart(0)
                    service.voiceRecognitionHandler.startRecognition()
                }
            },
            onPageDown = { service.keyRouter.pageDown() },
            onPageUp = { service.keyRouter.pageUp() },
            onGlobalCandidateSelect = { globalIndex ->
                service.keyRouter.selectCandidateGlobal(globalIndex)
            },
            onGlobalCandidateDelete = { globalIndex ->
                service.keyRouter.deleteCandidateGlobal(globalIndex)
            },
            onRequestExpandedCandidates = { service.refreshExpandedCandidates() },
            onCursorMove = { direction ->
                if (direction != 0) {
                    // 组合态移编码编辑光标 / 空闲态移编辑器光标，与光标键动作同一路径
                    service.moveCursor(direction)
                }
            },
            onGestureAction = { action, value ->
                KeyActionRegistry.execute(action, KeyActionContext(service), value)
            },
            onUpdateToolbarButtons = { buttons ->
                SettingsPreferences.setToolbarButtons(service, buttons)
                service.uiState.value = service.uiState.value.copy(toolbarButtons = buttons)
            },
            onOpenToolPanel = { pluginId -> service.openToolPanel(pluginId) },
            onToolPanelClose = { service.closeToolPanel() },
            onToolPanelItemClick = { item -> service.commitToolPanelItem(item.text) },
            onToolPanelAction = { actionId -> service.dispatchToolPanelAction(actionId) },
            onToolPanelFieldInput = { key, value -> service.onToolPanelFieldInput(key, value) },
            onToolPanelFocusChange = { focused ->
                service.uiState.value = service.uiState.value.copy(
                    toolPanelInputFocused = focused,
                )
            },
            onKeyboardModeChange = { chineseMode ->
                if (service.isChineseMode != chineseMode) {
                    service.isChineseMode = chineseMode
                    if (!chineseMode) {
                        service.candidateState.value = service.candidateState.value.copy(associationCandidates = emptyList())
                    }
                }
            },
            onDismissDeploying = { service.notifyDeploymentStatus(false, "") },
            onFloatingModeChange = { enabled -> service.schemaController.toggleFloatingMode(enabled, floatingMinY) },
            onFloatingKeyboardDrag = { dx, dy ->
                val s = service.uiState.value
                val config = service.resources.configuration
                val screenW = config.screenWidthDp
                val portraitWidth = minOf(screenW, config.screenHeightDp)
                val halfMargin = FloatingCardGeometry.halfMarginDp(screenW, portraitWidth)
                // dx/dy 为原始屏幕位移（+x 右、+y 下）：x 直接累加，y 与 offsetY
                // 方向相反（offsetY = 卡片离底边的距离，上拖 dy<0 → 卡片上移）
                val newX = (s.floatingOffsetX + dx).roundToInt().coerceIn(-halfMargin, halfMargin)
                // 卡高以实测（onCardPositioned）为唯一真源，首帧实测前走几何兜底
                val cardHeightDp = if (service.currentFloatingCardHeightDp > 0) {
                    service.currentFloatingCardHeightDp
                } else {
                    FloatingCardGeometry.fallbackCardHeightDp(
                        SettingsPreferences.getKeyboardHeightDp(service, false)
                            .coerceAtMost((config.screenHeightDp * 8) / 10),
                        s.keyboardBottomPaddingDp,
                    )
                }
                // 悬浮卡片恒为竖屏形态：有效屏高 = 物理高 - 状态栏（与 setContent 一致）
                val screenH = if (s.isFloatingMode) {
                    val metrics = service.resources.displayMetrics
                    (metrics.heightPixels / metrics.density).roundToInt() -
                        tryGetStatusBarHeightDp(service, service.window.window)
                } else {
                    config.screenHeightDp
                }
                val rawY = (s.floatingOffsetY - dy).roundToInt()
                val newY = FloatingCardGeometry.clampOffsetY(
                    rawY,
                    minY = floatingMinY,
                    screenHeightDp = screenH,
                    cardHeightDp = cardHeightDp,
                )
                // 试图越过最低点（继续下拖）= 进入"松手切换非悬浮"提示态：边沿触发
                // 震动一次（不逐帧），同时点亮底部光效；拖离底部即退出提示态
                val atExitHint = rawY <= floatingMinY
                if (atExitHint != service.floatingExitHintState.value) {
                    service.floatingExitHintState.value = atExitHint
                    if (atExitHint) {
                        service.feedbackManager.performVibration()
                    }
                }
                service.uiState.value = s.copy(
                    floatingOffsetX = newX,
                    floatingOffsetY = newY,
                )
            },
            onFloatingKeyboardDragEnd = {
                if (service.floatingExitHintState.value) {
                    // 底部松手：切换为非悬浮（停靠），位置语义与菜单开关同一条出口
                    service.floatingExitHintState.value = false
                    service.schemaController.toggleFloatingMode(false)
                } else {
                    val s = service.uiState.value
                    val isLandscape = service.resources.configuration.screenWidthDp > service.resources.configuration.screenHeightDp
                    SettingsPreferences.setFloatingOffsetX(service, s.floatingOffsetX, isLandscape)
                    SettingsPreferences.setFloatingOffsetY(service, s.floatingOffsetY, isLandscape)
                }
            },
            onT9ReplaceFullPinyin = { pinyin ->
                service.serviceScope.launch(service.keyProcessingDispatcher) {
                    when {
                        pinyin == T9InputController.CLEAR_COMPOSITION_ONLY -> {
                            service.rimeEngine.clearComposition()
                        }
                        pinyin == T9InputController.CLEAR_ALL -> {
                            service.t9PartialSegments.clear()
                            service.rimeEngine.setInput("")
                            service.rimeEngine.clearComposition()
                        }
                        pinyin.isEmpty() -> {
                            service.rimeEngine.clearComposition()
                        }
                        else -> {
                            service.rimeEngine.setInput(pinyin)
                        }
                    }
                    val composition = service.rimeEngine.getComposition()
                    withContext(Dispatchers.Main) {
                        service.mainHandler.post { service.sessionController.applyComposition(composition) }
                    }
                }
            },
            onT9RightCommitUndone = { count ->
                // 输入框模式：半提交文本与后续编码整体在 composing 区域内（如「这shi」），
                // 不能在此清空组合区——setComposingText("") 会把光标带回编码起点，而此刻
                // 宿主回报的候选区间已失效，会被误判为用户移动光标，进而丢弃本次撤销
                // （编码被定稿成字面文本上屏）。交由随后的 applyComposition 原地重写组合区：
                // 有组合区时 setComposingText 只替换该区间，光标仍落在编码内。
                if (SettingsPreferences.getInputTextLocation(service)
                    != SettingsPreferences.INPUT_TEXT_INPUT_BOX
                ) {
                    service.deleteBeforeCursor(count)
                }
                // undo 联动：撤销 right commit 段时回滚用户词典调频。
                val undone = service.t9PartialSegments.removeLastOrNull()
                if (undone != null) {
                    service.serviceScope.launch(service.keyProcessingDispatcher) {
                        service.rimeEngine.t9Forget(undone.text, undone.pinyin)
                    }
                }
            },
            onT9RefreshComposition = { composition, injections ->
                // composition 由 T9 控制器在 flush 后一次取回并传入，
                // 避免在此再次 getComposition 造成重复 JNI 往返。
                service.mainHandler.post {
                    service.sessionController.applyComposition(composition, emptyList(), injections)
                }
            },
            onTCandidateTransform = { result ->
                // T9 后台线程（t9Dispatcher）调用：同步等插件至多 15ms，主线程零等待
                service.candidateTransform.transformForT9(result)
            },
            onT9SwitchAway = {
                service.keyRouter.postRimeJob {
                    service.sessionController.commitFirstCandidateAndClearT9()
                }
            },
            onCommitCandidateBeforeModeChange = {
                val cs = service.candidateState.value
                if (cs.pendingEnglishText.isNotEmpty()) {
                    // 英文直接上屏模式：编码字符已逐字落盘，切模式只需结束本轮输入（清状态），
                    // 不可再 commitText 否则会重复输出整个词。
                    service.candidateState.value = cs.copy(
                        pendingEnglishText = "",
                        associationCandidates = emptyList()
                    )
                } else if (!isT9Schema(service.uiState.value.currentSchemaId)
                    && cs.isComposing) {
                    if (cs.candidates.isNotEmpty()) {
                        if (service.rimeEngine.selectCandidate(0)) {
                            val text = service.rimeEngine.commit()
                            if (text.isNotEmpty()) service.commitText(text)
                        }
                    } else if (cs.preeditText.isNotEmpty()) {
                        // 提交原始键入串而非 preeditText：后者现为带回显分隔符的展示串（如 ni'hao），
                        // 直接上屏会混入分隔符。
                        service.commitText(cs.inputText)
                        service.rimeEngine.clearComposition()
                    }
                }
            },
            onShowQuickSendForm = {
                service.closeToolPanel()
                // 从 Overlay 页面（剪贴板面板）打开表单时必须退出 Overlay：
                // Overlay 渲染为全屏 clickable Box（吞点击），若不退出，表单会被
                // 盖在其下——可见但关闭按钮等点击全部失效（时灵时不灵的根源：
                // 从主键盘打开则无叠加）。
                service.keyboardViewModel.closeOverlay()
                val current = service.uiState.value
                service.uiState.value = current.copy(
                    showQuickSendForm = true,
                    quickSendFormFocused = true,
                    quickSendCodeFocused = false,
                    quickSendEditingItemId = null,
                    quickSendEditingItemText = "",
                    quickSendEditingItemCode = "",
                    enterKeyText = tr(R.string.s_queding),
                )
                // 撑高由 SideEffect 驱动：uiState 变化 → Compose 内容高度变化 →
                // updateHeight 改容器物理高度 → relayout → onComputeInsets 自动重算。
            },
            onQuickSendEditItem = { id, text, code ->
                // 同 onShowQuickSendForm：编辑入口在剪贴板面板内，先退出 Overlay 防表单被盖
                service.keyboardViewModel.closeOverlay()
                service.uiState.value = service.uiState.value.copy(
                    showQuickSendForm = true,
                    quickSendFormFocused = true,
                    quickSendCodeFocused = false,
                    quickSendEditingItemId = id,
                    quickSendEditingItemText = text,
                    quickSendEditingItemCode = code,
                    enterKeyText = tr(R.string.s_queding),
                )
                QuickSendFormEditTextHolder.editText?.let { et ->
                    et.setText(text)
                    et.setSelection(text.length)
                }
            },
            onHideQuickSendForm = {
                android.util.Log.d("QuickSendForm", "onHideQuickSendForm invoked")
                service.uiState.value = service.uiState.value.copy(
                    showQuickSendForm = false,
                    quickSendFormFocused = false,
                    quickSendCodeFocused = false,
                    quickSendEditingItemId = null,
                    quickSendEditingItemText = "",
                    quickSendEditingItemCode = "",
                    enterKeyText = tr(R.string.s_fasong),
                )
                QuickSendFormEditTextHolder.editText = null
                QuickSendFormCodeEditTextHolder.editText = null
                service.keyboardViewModel.showOverlay(OverlayRoute.Clipboard(1))
                // insets 恢复由 SideEffect 驱动：表单收起 → 容器物理高度还原 →
                // relayout → onComputeInsets 自动重算，无需强制触发。
            },
            onQuickSendFormFocusChange = { focused: Boolean ->
                // 聚焦时回车键为"确定"；失焦不改文案——表单仍显示，回车保持"确定"
                // 语义（提交并关闭，由 handleKeyPress 的 showQuickSendForm 分支保证）。
                // 关闭表单时由 onHideQuickSendForm / onWindowHidden 统一还原"发送"。
                val s = service.uiState.value
                service.uiState.value = if (focused) {
                    // 文本框抢回焦点：编码焦点清除，按键输入回到文本框
                    s.copy(quickSendFormFocused = true, quickSendCodeFocused = false, enterKeyText = tr(R.string.s_queding))
                } else {
                    s.copy(quickSendFormFocused = false, quickSendCodeFocused = false)
                }
            },
            onQuickSendCodeFocusChange = { focused: Boolean ->
                val s = service.uiState.value
                service.uiState.value = if (focused) {
                    // 编码框获得焦点：同样视为表单聚焦（回车"确定"/按键路由生效）
                    s.copy(quickSendFormFocused = true, quickSendCodeFocused = true, enterKeyText = tr(R.string.s_queding))
                } else {
                    s.copy(quickSendCodeFocused = false)
                }
            },
        )
    }
}
