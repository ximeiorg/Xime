package com.kingzcheung.xime.service

import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.kingzcheung.xime.association.AssociationManager
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.rime.resolveRimeCandidateIndex
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 按键输入路由。
 *
 * 承载按键派发（handleKeyPress）、长按退格合并、候选选择/翻页、计算器候选等逻辑。
 * 所有共享状态通过 service 引用访问（同模块 internal 成员）。
 */
internal class ImeKeyRouter(private val service: XimeInputMethodService) {

    /**
     * 候选词变换（hotPath 插件能力）+ 发送 UI 更新。
     * 必须在 key-processing 线程调用：同步等插件至多 15ms；
     * 超时/失败/插件不干预（null）均回退原始候选（actions 为空 = 纯引擎语义）。
     */
    private fun sendTransformedResult(
        result: com.kingzcheung.xime.rime.RimeProcessResult,
        afterUpdate: (suspend () -> Unit)? = null,
    ) {
        val transformed = service.candidateTransform.transformFor(result)
        service.uiEventChannel.trySend {
            service.sessionController.updateUIWithResult(
                transformed?.let { result.copy(candidates = it.candidates.toTypedArray()) } ?: result,
                transformed?.actions ?: emptyList()
            )
            if (afterUpdate != null) afterUpdate()
        }
    }

    internal fun handleKeyPress(key: String, isShifted: Boolean) {
        // 空键无任何按键语义，且下游 Rime 路由按 key[0] 取码（key.lowercase()[0]），
        // 空串会越界崩溃（2026-09-14 真机实证：滑动手势 commit 值为空时触发）。
        if (key.isEmpty()) return
        if (service.uiState.value.toolPanelInputFocused) {
            val candState = service.candidateState.value
            val hasComposing = candState.isComposing || candState.inputText.isNotEmpty()
            when (key) {
                "enter" -> {
                    if (hasComposing) {
                        // 组合态：先把拼音提交进面板输入框，再触发生成
                        val input = candState.inputText
                        service.mainHandler.post {
                            ToolPanelEditTextHolder.editText?.let { et ->
                                val start = et.selectionStart.coerceAtLeast(0)
                                et.text?.replace(start, et.selectionEnd.coerceAtLeast(start), input)
                                try { et.setSelection(start + input.length) } catch (_: Exception) {}
                            }
                            service.rimeEngine.clearComposition()
                            service.candidateState.value = service.candidateState.value.copy(
                                inputText = "",
                                preeditText = "",
                                pendingEnglishText = "",
                                candidates = emptyList(),
                                candidateComments = emptyList(),
                                associationCandidates = emptyList(),
                                isComposing = false,
                                candidateActions = emptyList()
                            )
                        }
                    }
                    service.triggerToolPanelGenerate()
                    return
                }
                "delete" -> {
                    if (hasComposing) {
                        // 组合态：退格走 Rime，更新候选栏
                        service.rimeEngine.processKey(0xff08, 0)
                        val result = service.rimeEngine.getProcessResult(true)
                        if (result.inputText.isEmpty()) {
                            service.rimeEngine.clearComposition()
                        }
                        sendTransformedResult(result)
                    } else {
                        ToolPanelEditTextHolder.editText?.let { et ->
                            val start = et.selectionStart.coerceAtLeast(0)
                            val end = et.selectionEnd.coerceAtLeast(start)
                            if (start == end && start > 0) {
                                et.text?.delete(start - 1, start)
                                try { et.setSelection(start - 1) } catch (_: Exception) {}
                            } else if (end > start) {
                                et.text?.delete(start, end)
                                try { et.setSelection(start) } catch (_: Exception) {}
                            }
                        }
                    }
                    return
                }
                "space" -> {
                    if (hasComposing && candState.candidates.isNotEmpty()) {
                        // 组合态：空格选第一个候选（keyJobs 保序）
                        postRimeJob { selectCandidateAsync(0) }
                    } else {
                        ToolPanelEditTextHolder.editText?.let { et ->
                            val start = et.selectionStart.coerceAtLeast(0)
                            et.text?.insert(start, " ")
                            try { et.setSelection(start + 1) } catch (_: Exception) {}
                        }
                    }
                    return
                }
                else -> {
                    if (key.length == 1) {
                        val isLetter = key.matches(Regex("[a-zA-Z]"))
                        val isChineseMode = !service.uiState.value.isAsciiMode
                        if (isLetter && isChineseMode) {
                            // 中文模式：字母进 Rime 拼音组合，候选栏选词后经 commitText 重定向进面板输入框
                            val keyCode = key.lowercase()[0].code
                            val result = service.rimeEngine.processKeyAndGetResult(keyCode, 0)
                            if (result.processed) {
                                sendTransformedResult(result)
                            }
                            return
                        }
                        val char = if (isShifted) key.uppercase() else key
                        ToolPanelEditTextHolder.editText?.let { et ->
                            val start = et.selectionStart.coerceAtLeast(0)
                            et.text?.insert(start, char)
                            try { et.setSelection(start + char.length) } catch (_: Exception) {}
                        }
                        return
                    }
                }
            }
        }
        // 表单显示期间（无论 EditText 是否持有焦点）都拦截：
        // 焦点可能因点击表单外区域丢失，此时回车仍应提交并关闭表单、
        // 退格仍应作用于表单输入框，否则按键落入普通输入语义造成"关闭无效"。
        if (service.uiState.value.showQuickSendForm) {
            when (key) {
                "enter" -> {
                    val editText = QuickSendFormEditTextHolder.editText
                    val codeEditText = QuickSendFormCodeEditTextHolder.editText
                    val text = editText?.text?.toString() ?: ""
                    val code = codeEditText?.text?.toString()?.trim() ?: ""
                    val s = service.uiState.value
                    val editingId = s.quickSendEditingItemId
                    if (text.isNotBlank()) {
                        if (editingId != null) {
                            service.keyboardViewModel.updateQuickSendItem(editingId, text, code)
                        } else {
                            service.keyboardViewModel.addQuickSendText(text, code)
                        }
                    }
                    service.uiState.value = s.copy(
                        showQuickSendForm = false,
                        quickSendFormFocused = false,
                        quickSendCodeFocused = false,
                        quickSendEditingItemId = null,
                        quickSendEditingItemText = "",
                        quickSendEditingItemCode = ""
                    )
                    QuickSendFormEditTextHolder.editText = null
                    QuickSendFormCodeEditTextHolder.editText = null
                    service.keyboardViewModel.showOverlay(OverlayRoute.Clipboard(1))
                    return
                }
                "delete" -> {
                    val candState = service.candidateState.value
                    val isComposing = candState.isComposing || candState.inputText.isNotEmpty()
                    if (isComposing) {
                        // Rime 有组合态 → 转发退格到 Rime 清空候选字母/联想词
                        service.rimeEngine.processKey(0xff08, 0)
                        val result = service.rimeEngine.getProcessResult(true)
                        if (result.inputText.isEmpty()) {
                            service.rimeEngine.clearComposition()
                        }
                        sendTransformedResult(result)
                    } else {
                        // 无组合态 → 按焦点路由删除表单内（文本框/触发编码框）已上屏文字
                        service.deleteInQuickSendForm()
                    }
                    return
                }
            }
        }
        // 常驻语音模式下开始打字：先结束语音会话（提交已识别文本），再处理按键
        val current = service.uiState.value
        if (current.isVoiceMode && current.voiceSticky) {
            service.endVoiceSession()
        }
        // 长按退格以固定频率重复派发，走合并路径，避免 keyJobs 堆积导致候选栏抖动
        if (key == "delete") {
            handleDeleteKey()
            return
        }
        val job = service.serviceScope.launch(service.keyProcessingDispatcher, start = CoroutineStart.LAZY) {
            val state = service.uiState.value
            val candState = service.candidateState.value
            var needsUIUpdate = false
            var pendingResult: com.kingzcheung.xime.rime.RimeProcessResult? = null
            var committedText: String? = null
            
            when (key) {
                "clear_composition" -> {
                    // 只清输入态（预编辑/候选/联想/partial 累积/计算器/左栏），不动已上屏文本。
                    // 清理逻辑见 clearInputStateForKeys()（与 clear_all 输入态分支共用）。
                    clearInputStateForKeys()
                    needsUIUpdate = true
                }
                "clear_all" -> {
                    // 上滑清空 = 多次退格快捷方式（对标主流输入法）：输入态只清输入态，空闲态清空全部已上屏。
                    // 输入态判定见 hasInputState()——不能用 RIME getInput()，tryLocked 锁竞争时静默返回空。
                    if (hasInputState(candState)) {
                        // 输入态：只清输入态（等价于 clear_composition），并记录 lastClearedText 供下滑撤回。
                        // 需在 clearInputStateForKeys() 之前记录（该函数会清空 preeditText/inputText）。
                        val pendingEnglish = candState.pendingEnglishText
                        if (pendingEnglish.isNotEmpty()) {
                            // 直接上屏模式：英文编码已逐字落盘，"清输入态"需回删屏上对应字符；
                            // 校验光标前文本一致才删除并记录撤回，否则只清状态不动已上屏文本。
                            val removed = withContext(Dispatchers.Main) {
                                val ic = service.currentInputConnection ?: return@withContext false
                                val before = runCatching {
                                    ic.getTextBeforeCursor(pendingEnglish.length, 0)?.toString()
                                }.getOrNull()
                                before == pendingEnglish && ic.deleteSurroundingText(pendingEnglish.length, 0)
                            }
                            if (removed) service.lastClearedText = pendingEnglish
                        } else {
                            // 撤回重放用 inputText（原始键入串）：preeditText 现为带回显分隔符的
                            // 展示串（如 ni'hao），上屏必须无分隔符版本。T9 时 inputText 为合成显示态，
                            // 与 preeditText 同值，行为不变。
                            service.lastClearedText = candState.inputText
                        }
                        clearInputStateForKeys()
                    } else {
                        // 空闲态：清空输入框全部已上屏文本。
                        service.calculatorEngine.clear()
                        updateCalculatorCandidates()
                        // 记录撤回内容：输入框模式 getTextBeforeCursor 已含 composing 区（与 inputText 同源，
                        // 避免重复拼接）；候选栏模式输入框只有已上屏文本，需补候选栏编码 inputText。
                        // 英文输入态已被 hasInputState() 拦截，此处 pendingEnglishText 恒为空，拼接仅作防御。
                        val codeInInputBox = SettingsPreferences.getInputTextLocation(service) ==
                            SettingsPreferences.INPUT_TEXT_INPUT_BOX
                        val inputFieldText = withContext(Dispatchers.Main) {
                            service.currentInputConnection?.getTextBeforeCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0)?.toString() ?: ""
                        }
                        service.lastClearedText = when {
                            codeInInputBox -> inputFieldText + candState.pendingEnglishText
                            else -> inputFieldText + candState.inputText + candState.pendingEnglishText
                        }
                        // 清空 partial 累积，避免残留词被 buildT9DisplayState 拼进下一轮 preedit。
                        service.t9PartialSegments.clear()
                        service.rimeEngine.clearComposition()
                        service.candidateState.value = service.candidateState.value.copy(
                            candidates = emptyList(),
                            candidateComments = emptyList(),
                            associationCandidates = emptyList(),
                            pendingEnglishText = "",
                            inputText = "",
                            isComposing = false,
                            isShowingRecentClipboard = false,
                            candidateActions = emptyList()
                        )
                        withContext(Dispatchers.Main) {
                            service.currentInputConnection?.let {
                                service.endComposingInputBox()
                                // 删除输入框中所有文字
                                val textLen = inputFieldText.length
                                if (textLen > 0) {
                                    it.deleteSurroundingText(textLen, 0)
                                }
                            }
                        }
                    }
                    needsUIUpdate = true
                }
                "undo_clear" -> {
                    // 下滑撤回 = 撤销"上滑清空"，仅空闲态有效（输入态恢复会插入错误位置），
                    // 判定与 clear_all 共用 hasInputState()。
                    if (!hasInputState(candState)) {
                        val text = service.lastClearedText
                        if (text.isNotEmpty()) {
                            service.lastClearedText = ""
                            withContext(Dispatchers.Main) {
                                val ic = service.currentInputConnection
                                if (ic != null) {
                                    // newCursorPosition=1：光标停在撤回内容末尾；
                                    // 传 text.length 会被 clamp 到整段文本末尾。
                                    ic.commitText(text, 1)
                                }
                            }
                        }
                    }
                    needsUIUpdate = true
                }
                "enter" -> {
                    service.calculatorEngine.clear()
                    updateCalculatorCandidates()
                    if (candState.isComposing) {
                        // T9 模式提交完整预编辑（含 partial commit 累积），非 T9 模式用 RIME input。
                        val isT9 = isT9Schema(state.currentSchemaId)
                        val input = if (isT9 && candState.preeditText.isNotEmpty()) {
                            candState.preeditText
                        } else {
                            service.rimeEngine.getInput()
                        }
                        if (input.isNotEmpty()) {
                            withContext(Dispatchers.Main) { service.commitText(input) }
                        }
                        if (isT9) {
                            // 同步清空，避免异步 postRimeJob 延迟导致后续 backspace 拿到旧状态。
                            service.t9PartialSegments.clear()
                            service.rimeEngine.setInput("")
                            service.rimeEngine.clearComposition()
                        } else {
                            service.rimeEngine.clearComposition()
                        }
                        withContext(Dispatchers.Main) { service.endComposingInputBox() }
                        needsUIUpdate = true
                    } else {
                        service.rimeEngine.clearComposition()
                        withContext(Dispatchers.Main) {
                            val imeOptions = service.currentInputEditorInfo?.imeOptions ?: 0
                            val action = imeOptions and EditorInfo.IME_MASK_ACTION
                            val noEnterAction = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
                            when {
                                noEnterAction -> service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                                action == EditorInfo.IME_ACTION_GO ||
                                action == EditorInfo.IME_ACTION_SEARCH ||
                                action == EditorInfo.IME_ACTION_SEND ||
                                action == EditorInfo.IME_ACTION_NEXT ||
                                action == EditorInfo.IME_ACTION_DONE ->
                                    service.currentInputConnection?.performEditorAction(action)
                                else -> service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        service.candidateState.value = service.candidateState.value.copy(
                            inputText = "",
                            preeditText = "",
                            pendingEnglishText = "",
                            candidates = emptyList(),
                            candidateComments = emptyList(),
                            associationCandidates = emptyList(),
                            isComposing = false
                        )
                        if (isT9Schema(state.currentSchemaId)) {
                            service.uiState.value = service.uiState.value.copy(
                                t9ResetSignal = service.uiState.value.t9ResetSignal + 1,
                                t9RightCandidateSelectedCount = 0,
                                t9SelectedCandidatePinyin = ""
                            )
                        }
                    }
                }
                "space" -> {
                    val pendingEnglish = candState.pendingEnglishText

                    if (pendingEnglish.isNotEmpty()) {
                        // 直接上屏模式：词已逐字落盘，此处只提交空格并结束本轮英文输入。
                        withContext(Dispatchers.Main) {
                            service.commitText(" ")
                            service.candidateState.value = service.candidateState.value.copy(
                                pendingEnglishText = "",
                                associationCandidates = emptyList()
                            )
                        }
                    } else if (candState.isComposing) {
                        if (candState.candidates.isNotEmpty()) {
                            selectCandidateAsync(0)
                        } else {
                            val input = candState.inputText
                            if (input.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    service.commitText(input)
                                    service.candidateState.value = service.candidateState.value.copy(
                                        inputText = "",
                                        preeditText = "",
                                        isComposing = false,
                                        pendingEnglishText = "",
                                        candidates = emptyList(),
                                        candidateComments = emptyList(),
                                        associationCandidates = emptyList(),
                                        candidateActions = emptyList()
                                    )
                                }
                                service.rimeEngine.clearComposition()
                                // T9模式：清空partialCommit累积文本，避免下一轮输入
                                // preedit中残留上一轮的提交内容（如"看"→下一轮"看jihua"）
                                if (isT9Schema(state.currentSchemaId)) {
                                    withContext(Dispatchers.Main) {
                                        service.t9PartialSegments.clear()
                                        service.uiState.value = service.uiState.value.copy(
                                            t9ResetSignal = service.uiState.value.t9ResetSignal + 1,
                                            t9RightCandidateSelectedCount = 0,
                                            t9SelectedCandidatePinyin = ""
                                        )
                                    }
                                }
                                needsUIUpdate = true
                            }
                        }
                    } else if (!state.isAsciiMode &&
                        SettingsPreferences.isSpaceCommitAssociationEnabled(service) &&
                        candState.associationCandidates.isNotEmpty()
                    ) {
                        // 空格上屏联想候选（中文模式 + 开关开启 + 联想候选存在）：上屏第一个联想词。
                        // 仅中文联想——英文模式此分支不生效，空格保持上屏空格字符；
                        // 连续联想模式：commitText 会自动触发下一轮推理（一直上屏一直推理）；
                        // 单次联想模式：先置抑制标志，上屏引发的那轮推理被跳过并清空联想候选。
                        val association = candState.associationCandidates.first()
                        withContext(Dispatchers.Main) {
                            if (SettingsPreferences.isSingleAssociationMode(service)) {
                                service.predictionManager.suppressNextPredictionOnce()
                            }
                            service.commitText(association)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            service.commitText(" ")
                        }
                    }
                }
                "word_separator" -> {
                    if (candState.isComposing || candState.inputText.isNotEmpty()) {
                        val result = service.rimeEngine.processKeyAndGetResult(0x27, 0)
                        if (result.processed) {
                            sendTransformedResult(result)
                        } else {
                            needsUIUpdate = true
                        }
                    } else {
                        needsUIUpdate = true
                    }
                }
                "shift" -> {
                }
                "mode_change" -> {
                }
                "ime_switch" -> {
                    // 乐观更新：立即按目标模式切换 UI（主键盘布局/面板字符），不等引擎异步切换，
                    // 消除"进入面板/切键盘后才闪变"的可见延迟（引擎切换完成后权威同步，一致则无感）。
                    val state = service.uiState.value
                    val optimisticTarget = !state.isAsciiMode
                    val schemaId = service.rimeEngine.getCurrentSchema()
                    withContext(Dispatchers.Main) {
                        service.uiState.value = service.uiState.value.copy(isAsciiMode = optimisticTarget)
                        service.keyboardViewModel.dispatch(
                            com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction.AsciiModeChanged(optimisticTarget, schemaId)
                        )
                    }
                    // 在 key-processing 线程上执行切换：toggleAsciiMode 阻塞等待 rimeLock
                    // （部署/维护持锁时排队，完成后自动切换），不在主线程阻塞避免 ANR。
                    val t0 = System.nanoTime()
                    FileLogger.i(XimeInputMethodService.TAG, "ime_switch dispatched, ui ascii=${service.uiState.value.isAsciiMode}, thread=${Thread.currentThread().name}")
                    if (!service.schemaController.switchInputMethod()) {
                        // 引擎不可用：回滚乐观状态
                        withContext(Dispatchers.Main) {
                            service.uiState.value = service.uiState.value.copy(isAsciiMode = optimisticTarget)
                            service.keyboardViewModel.dispatch(
                                com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction.AsciiModeChanged(optimisticTarget, schemaId)
                            )
                        }
                    }
                    FileLogger.i(XimeInputMethodService.TAG, "ime_switch handled, total ${(System.nanoTime() - t0) / 1_000_000}ms (queue+rimeLock+main)")
                }
                "abc" -> {
                    service.calculatorEngine.clear()
                    updateCalculatorCandidates()
                }
                "number", "common_symbol" -> {
                    // Number/CommonSymbol 内部切换由 KeyboardView 的 key handler 处理
                }
                "emoji" -> {
                    withContext(Dispatchers.Main) {
                        service.commitText("😊")
                    }
                }
                else -> {
                    val isNumberKeyboard = service.keyboardViewModel.keyboardState.value is com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState.Number
                    val isCommonSymbolKeyboard = service.keyboardViewModel.keyboardState.value is com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState.CommonSymbol

                    val routeResult = com.kingzcheung.xime.calculator.routeCalculatorKey(
                        key = key,
                        isNumberKeyboard = isNumberKeyboard,
                        isCommonSymbolKeyboard = isCommonSymbolKeyboard,
                        calculatorEngine = service.calculatorEngine,
                    )
                    if (routeResult is com.kingzcheung.xime.calculator.CalculatorRouteResult.Handled) {
                        withContext(Dispatchers.Main) { service.commitText(routeResult.commitText) }
                        if (isNumberKeyboard) updateCalculatorCandidates()
                        needsUIUpdate = true
                        return@launch
                    }

                    val pendingEnglish = candState.pendingEnglishText
                    
                    // 非计算器键清除计算器状态
                    if (!key.matches(Regex("[0-9]")) && key !in listOf("+", "-", "*", "/", ".")) {
                        if (service.calculatorEngine.isActive() || service.calculatorEngine.getCandidate() != null) {
                            service.calculatorEngine.clear()
                            updateCalculatorCandidates()
                        }
                    }
                    
                    // 计算器模式：追踪数字、运算符和小数点
                    if (key.matches(Regex("[0-9]")) || key in listOf("+", "-", "*", "/", ".")) {
                        if (key.matches(Regex("[0-9]")) || key == ".") {
                            service.calculatorEngine.handleDigit(key)
                        } else {
                            service.calculatorEngine.handleOperator(key)
                        }
                        updateCalculatorCandidates()
                    }
                    
                    // 数字选词拦截（插件候选变换存在时）：数字键不进入引擎——rime 会自行选
                    // 引擎候选并返回 committedText，绕过插件候选；映射为对应位置显示候选的
                    // 点击（selectCandidateAsync 按 candidateActions 分流上屏）。
                    // actions 为空（无变换）时保持原生数字选词行为不变。
                    if (!state.isAsciiMode && candState.isComposing &&
                        key.length == 1 && key[0] in '1'..'9'
                    ) {
                        val actions = service.candidateState.value.candidateActions
                        val digitIndex = key[0] - '1'
                        if (actions.isNotEmpty() && digitIndex < service.candidateState.value.candidates.size) {
                            selectCandidateAsync(digitIndex)
                            return@launch
                        }
                    }

                    // 所有按键统一经过 Rime 引擎
                    // 字母键不进入此分支（即使 pendingEnglish 非空），需要继续积累编码
                    // 英文模式（isAsciiMode）下非字母键（QWERTY 上滑的数字/符号、数字/符号面板）
                    // 直接上屏，不进入 Rime 引擎与 pendingEnglish 累积（上滑字符即输即上）。
                    if ((state.isAsciiMode || pendingEnglish.isNotEmpty()) && !key.matches(Regex("[a-zA-Z]"))) {
                        val finalKey = key
                        withContext(Dispatchers.Main) {
                            // 直接上屏模式：pendingEnglish 对应字符已逐字落盘，只提交增量键值。
                            service.commitText(finalKey)
                            service.candidateState.value = service.candidateState.value.copy(
                                pendingEnglishText = "",
                                associationCandidates = emptyList()
                            )
                        }
                    } else {
                        val isChinese = !state.isAsciiMode
                        val char = key
                        val keyCode = key.lowercase()[0].code
                        val mask = if (isShifted) KeyEvent.META_SHIFT_ON else 0
                        val isLetter = key.matches(Regex("[a-zA-Z]"))
                        val isShiftedChinese = isShifted && isChinese && isLetter

                        // 非 ASCII 可打印字符（全角符号/中文标点）：直接上屏，不进入 Rime 引擎。
                        // Rime processKey 只接受标准键码，全角键码（如 U+FF0F）无法识别会被静默
                        // 丢弃，导致中文模式下符号面板点击全角字符无输出（与 Trime onText 行为一致）。
                        if (char.isNotEmpty() && char.any { it.code > 0x7E }) {
                            committedText = char
                            needsUIUpdate = true
                        } else if (isShifted && !isLetter) {
                            if (char.length == 1) {
                                val charCode = char[0].code
                                val processed = service.rimeEngine.processKey(charCode, 0)
                                if (processed) {
                                    val result = service.rimeEngine.getProcessResult(processed)
                                    // commitText 不走 CONFLATED channel：channel 会覆盖丢弃未消费事件，
                                    // 快速打字时中间的 commitText 会被吞（吃键）。
                                    if (result.committedText.isNotEmpty()) {
                                        withContext(Dispatchers.Main) { service.commitText(result.committedText) }
                                    }
                                    sendTransformedResult(result) { if (service.calculatorEngine.isActive()) updateCalculatorCandidates() }
                                } else {
                                    committedText = char
                                    needsUIUpdate = true
                                }
                            } else {
                                committedText = char
                                needsUIUpdate = true
                            }
                        } else {
                            // 注：T9 数字键不经过此处（T9KeyboardLayout 直接调
                            // controller.onDigitPressed → applyComposition）。
                            val result = service.rimeEngine.processKeyAndGetResult(keyCode, mask)
                            if (result.processed) {
                                if (isShiftedChinese && result.committedText != char) {
                                    service.rimeEngine.clearComposition()
                                    committedText = char
                                    needsUIUpdate = true
                                } else {
                                    val committed = result.committedText
                                    if (state.isAsciiMode && committed.isNotEmpty() && result.inputText.isEmpty() && result.candidates.isEmpty()) {
                                        val current = candState.pendingEnglishText
                                        val newPending = current + committed
                                        service.candidateState.value = service.candidateState.value.copy(pendingEnglishText = newPending)
                                        // 直接上屏模式：只提交 Rime 返回的增量字符（如智能引号转换结果），
                                        // 整段 pending 对应的文本已逐字上屏，不可重复提交。
                                        withContext(Dispatchers.Main) {
                                            service.commitText(committed)
                                        }
                                        sendTransformedResult(result) { if (service.calculatorEngine.isActive()) updateCalculatorCandidates() }
                                    } else {
                                        if (committed.isNotEmpty()) {
                                            withContext(Dispatchers.Main) { service.commitText(committed) }
                                        }
                                        sendTransformedResult(result) { if (service.calculatorEngine.isActive()) updateCalculatorCandidates() }
                                    }
                                }
                            } else {
                                val isAscii = state.isAsciiMode
                                if (!candState.isComposing || isShiftedChinese) {
                                                    if (isAscii) {
                                                        val charToCommit = if (isShifted) char.uppercase() else char.lowercase()
                                                        val currentPending = candState.pendingEnglishText
                                                        val newPending = currentPending + charToCommit
                                                        // 英文直接上屏模式：字符不经 composing region，即输即落盘；
                                                        // pendingEnglishText 仅作编码记录（供联想与选中候选后回删替换校验）。
                                                        withContext(Dispatchers.Main) {
                                                            service.commitText(charToCommit)
                                                        }
                                                        service.candidateState.value = service.candidateState.value.copy(
                                                            pendingEnglishText = newPending,
                                                            associationCandidates = emptyList(),
                                                            englishReplaceSupported = service.supportsEnglishCandidateReplace()
                                                        )
                                                        needsUIUpdate = true
                                    } else {
                                        committedText = char
                                        needsUIUpdate = true
                                    }
                                } else {
                                    val candidateText = if (service.rimeEngine.selectCandidate(0)) {
                                        service.rimeEngine.commit()
                                    } else {
                                        ""
                                    }
                                    committedText = candidateText + char
                                    needsUIUpdate = true
                                }
                            }
                        }
                    }
                }
            }
            
            if (needsUIUpdate) {
                val result = pendingResult
                val textToCommit = committedText
                if (result != null) {
                    if (textToCommit != null) {
                        withContext(Dispatchers.Main) { service.commitText(textToCommit) }
                    }
                    sendTransformedResult(result) {
                        if (service.calculatorEngine.isActive()) {
                            updateCalculatorCandidates()
                        }
                    }
                } else {
                    val capturedInputText = service.rimeEngine.getInput()
                    val capturedCandidates = service.rimeEngine.getCandidatesWithComments()
                    val capturedIsAscii = service.rimeEngine.isAsciiMode()
                    val capturedHasNext = service.rimeEngine.hasNextPage()
                    val capturedHasPrev = service.rimeEngine.hasPrevPage()
                    // 候选词变换（hotPath 插件能力）：内联刷新路径（无 RimeProcessResult），
                    // key-processing 线程同步调用；ascii 场景不变换（调用点语义与 transformFor 一致）
                    val transformed = if (capturedInputText.isNotEmpty() && !capturedIsAscii) {
                        service.candidateTransform.transform(
                            capturedInputText, "", capturedCandidates.toList(), capturedIsAscii
                        )
                    } else {
                        null
                    }
                    val displayCandidates: List<com.kingzcheung.xime.rime.RimeCandidate> =
                        transformed?.candidates ?: capturedCandidates.toList()
                    if (textToCommit != null) {
                        withContext(Dispatchers.Main) { service.commitText(textToCommit) }
                    }
                    service.uiEventChannel.trySend {
                        val pendingEnglish = service.candidateState.value.pendingEnglishText
                        val (filteredTexts, filteredComments) = if (capturedIsAscii) {
                            val filtered = capturedCandidates.filterNot { candidate ->
                                candidate.text.any { it.code in 0x4E00..0x9FFF }
                            }
                            filtered.map { it.text } to filtered.map { it.comment }
                        } else {
                            displayCandidates.map { it.text } to displayCandidates.map { it.comment }
                        }
                        val restricted = service.isEditorRestricted()
                        service.candidateState.value = service.candidateState.value.copy(
                            inputText = capturedInputText,
                            candidates = filteredTexts,
                            candidateComments = filteredComments,
                            isComposing = capturedInputText.isNotEmpty(),
                            associationCandidates = if (restricted || ((capturedIsAscii || !service.isChineseMode) && pendingEnglish.isEmpty())) emptyList() else service.candidateState.value.associationCandidates,
                            isShowingRecentClipboard = false,
                            hasNextPage = capturedHasNext,
                            hasPrevPage = capturedHasPrev,
                            candidateActions = transformed?.actions ?: emptyList()
                        )
                        if (capturedIsAscii != service.uiState.value.isAsciiMode) {
                            FileLogger.i(XimeInputMethodService.TAG, "keyRouter UI refresh: ascii ${service.uiState.value.isAsciiMode}->$capturedIsAscii")
                        }
                        service.uiState.value = service.uiState.value.copy(isAsciiMode = capturedIsAscii)
                        // 受限输入框（密码/终端/NO_SUGGESTIONS）不拉取英文联想：
                        // 联想会泄漏输入前缀，回删替换机制也会破坏受限宿主的输入
                        if (pendingEnglish.isNotEmpty() && !restricted && service.supportsEnglishCandidateReplace()) {
                            service.serviceScope.launch {
                                val candidates = service.predictionManager.getEnglishAssociations(pendingEnglish, PredictionManager.MAX_ASSOCIATION_COUNT)
                                withContext(Dispatchers.Main) {
                                    val current = service.candidateState.value
                                    // 在途联想过期校验：pendingEnglish 已变/已清 → 丢弃迟到回填
                                    if (current.pendingEnglishText == pendingEnglish) {
                                        service.candidateState.value = current.copy(associationCandidates = candidates)
                                    }
                                }
                            }
                        }
                        if (service.calculatorEngine.isActive()) {
                            updateCalculatorCandidates()
                        }
                    }
                }
            }
        }
        service.keyJobs.trySend(job)
    }

    /**
     * 退格键合并入口（主线程调用）。
     *
     * 无退格任务在执行时启动一个；已有任务在执行/排队时只累加 [service.pendingDeleteCount]，
     * 由执行中的任务完成后顺带排空。这样长按退格不会让 keyJobs 无限堆积，
     * 候选栏 UI 更新保持平滑，抬手后最多多删 1~2 个字符。
     */
    internal fun handleDeleteKey() {
        val shouldLaunch = synchronized(service.deleteCoalesceLock) {
            if (service.deleteJobActive) {
                service.pendingDeleteCount++
                false
            } else {
                service.deleteJobActive = true
                true
            }
        }
        if (!shouldLaunch) return
        launchDeleteJob()
    }

    /**
     * 启动一个退格 job（keyJobs FIFO 保序）。完成后若仍有累积的退格请求，
     * 通过 [maybeScheduleFollowUp] 排入下一个 job。这样 keyJobs 中至多有一个
     * 退格 job 在执行/排队：既避免长按退格（~80ms 重复）超过 rime 退格吞吐时
     * 把 keyJobs 堆满、导致候选栏 UI 以"迟到的跳帧"方式刷新（一闪一闪），
     * 也保持与其它按键的相对顺序——合并的退格会在夹在中间的字母键之后执行。
     */
    internal fun launchDeleteJob() {
        val job = service.serviceScope.launch(service.keyProcessingDispatcher, start = CoroutineStart.LAZY) {
            try {
                processDeleteKey()
            } catch (t: Throwable) {
                FileLogger.e(XimeInputMethodService.TAG, "processDeleteKey failed", t)
            } finally {
                maybeScheduleFollowUp()
            }
        }
        service.keyJobs.trySend(job)
    }

    /** 若长按退格期间有累积的请求，排入下一个退格 job；否则结束合并状态。 */
    internal fun maybeScheduleFollowUp() {
        val shouldLaunch = synchronized(service.deleteCoalesceLock) {
            if (service.pendingDeleteCount == 0) {
                service.deleteJobActive = false
                false
            } else {
                service.pendingDeleteCount--
                true
            }
        }
        if (shouldLaunch) {
            launchDeleteJob()
        }
    }

    /** 单次退格处理（service.keyProcessingDispatcher 上执行）。 */
    internal suspend fun processDeleteKey() {
        // 快捷发送表单显示：退格按焦点路由到表单内 EditText（与单击退格同一路径），不进入 Rime
        if (service.uiState.value.showQuickSendForm) {
            withContext(Dispatchers.Main) { service.deleteInQuickSendForm() }
            return
        }
        val candState = service.candidateState.value
        // 退格改变输入上下文：使在途的联想预测结果失效，防止过期结果迟到回填
        // associationCandidates，导致长按退格删除时候选栏在"联想词↔空"之间闪动。
        service.predictionManager.invalidatePendingPredictions()
        // 计算器模式：追踪退格
        service.calculatorEngine.handleDelete()
        updateCalculatorCandidates()

        // 数字/符号键盘：直接发送系统退格，不经过 Rime
        // 防止 T9 残留状态被 Rime 退格修改导致 UI 不一致
        val layoutState = service.keyboardViewModel.keyboardState.value
        if (layoutState is KeyboardLayoutState.Number || layoutState is KeyboardLayoutState.Symbol) {
            withContext(Dispatchers.Main) {
                service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
        } else when {
            // 1. 英文待处理文本：逐个删除字符，重新加载联想
            candState.pendingEnglishText.isNotEmpty() -> {
                val newPending = candState.pendingEnglishText.dropLast(1)
                if (newPending.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                        service.candidateState.value = service.candidateState.value.copy(
                            pendingEnglishText = newPending,
                            candidates = emptyList(),
                            candidateComments = emptyList(),
                            associationCandidates = emptyList(),
                            candidateActions = emptyList()
                        )
                    }
                    if (service.supportsEnglishCandidateReplace()) {
                        service.serviceScope.launch {
                            val candidates = service.predictionManager.getEnglishAssociations(newPending, PredictionManager.MAX_ASSOCIATION_COUNT)
                            withContext(Dispatchers.Main) {
                                service.candidateState.value = service.candidateState.value.copy(associationCandidates = candidates)
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                        service.candidateState.value = service.candidateState.value.copy(
                            pendingEnglishText = "",
                            candidates = emptyList(),
                            candidateComments = emptyList(),
                            associationCandidates = emptyList(),
                            isShowingRecentClipboard = false,
                            candidateActions = emptyList()
                        )
                    }
                    // 待确认英文已删空：候选展开页若开着则收起（无内容可展示）
                    service.maybeCollapseCandidatePage()
                }
            }

            // 2. Rime 编码中：让 Rime 处理退格，更新候选
            candState.isComposing || candState.inputText.isNotEmpty() -> {
                service.rimeEngine.processKey(0xff08, 0)
                val result = service.rimeEngine.getProcessResult(true)
                if (result.inputText.isEmpty()) {
                    service.rimeEngine.clearComposition()
                    // T9 部分提交：剩余编码删完后，已上屏/ composing 的部分候选词无法用
                    // RIME 退格删除，会一直卡在候选栏。这里撤销最近一次部分提交：
                    // 清空 composing 区域（或删除上屏文本）并从累积列表移除。
                    if (service.t9PartialSegments.isNotEmpty()) {
                        val len = service.t9PartialSegments.last().text.length
                        withContext(Dispatchers.Main) {
                            if (SettingsPreferences.getInputTextLocation(service)
                                == SettingsPreferences.INPUT_TEXT_INPUT_BOX) {
                                service.endComposingInputBox()
                            } else {
                                service.deleteBeforeCursor(len)
                            }
                        }
                        // undo 联动：撤销段时回滚用户词典调频。
                        val undone = service.t9PartialSegments.removeLastOrNull()
                        if (undone != null) {
                            service.rimeEngine.t9Forget(undone.text, undone.pinyin)
                        }
                    }
                }
                sendTransformedResult(result) { if (service.calculatorEngine.isActive()) updateCalculatorCandidates() }
            }

            // 3. 联想词或剪贴板：仅清空候选栏，不回删已上屏字符
            candState.associationCandidates.isNotEmpty() || candState.isShowingRecentClipboard -> {
                service.candidateState.value = service.candidateState.value.copy(
                    candidates = emptyList(),
                    candidateComments = emptyList(),
                    associationCandidates = emptyList(),
                    isShowingRecentClipboard = false
                )
                // 候选展开页：联想/剪贴板候选清空后无内容，收起
                service.maybeCollapseCandidatePage()
            }

            // 4. 无候选也无编码：直接回删已上屏文本
            else -> {
                service.predictionManager.deleteLastChar()

                withContext(Dispatchers.Main) {
                    service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                }

                service.candidateState.value = service.candidateState.value.copy(
                    candidates = emptyList(),
                    candidateComments = emptyList(),
                    associationCandidates = emptyList(),
                    isShowingRecentClipboard = false
                )
                // 候选展开页：无候选无编码，收起
                service.maybeCollapseCandidatePage()
            }
        }
    }

    /**
     * Posts a rime operation to [keyJobs] for sequential execution.
     * Ensures no interleaving with key processing.
     */
    internal fun postRimeJob(block: suspend CoroutineScope.() -> Unit) {
        val job = service.serviceScope.launch(service.keyProcessingDispatcher, start = CoroutineStart.LAZY) {
            block()
        }
        service.keyJobs.trySend(job)
    }

    suspend fun selectCandidateAsync(index: Int) {
        // 插件候选（candidate_transform 变换）：直接上屏插件文本（所见即所得），不走引擎选词。
        // 引擎引用项继续走下方引擎路径，用映射记录的引擎索引（显示 index 因插件候选插入而错位）。
        val pendingAction = service.candidateState.value.candidateActions.getOrNull(index)
        if (pendingAction != null && pendingAction.isPluginCandidate) {
            // 防御：中英/方案切换后引擎组合已清空但 candidateState 残留旧候选+actions，
            // 此时点选必须回落原生路径（引擎侧 selectCandidate 失败自动防呆，与旧行为一致），
            // 否则残留插件候选会绕过引擎校验直接上屏。
            val engineHasComposition = service.rimeEngine.getInput().isNotEmpty() ||
                service.rimeEngine.getCandidates().isNotEmpty()
            if (engineHasComposition) {
                commitPluginCandidate(pendingAction.commitText)
                return
            }
        }

        val selectedCandidate = if (index < service.candidateState.value.candidates.size) {
            service.candidateState.value.candidates[index]
        } else null

        val isT9 = isT9Schema(service.uiState.value.currentSchemaId)
        val candidatePinyin = if (isT9 && index < service.candidateState.value.candidateComments.size) {
            service.candidateState.value.candidateComments[index]
        } else null

        // 在 RIME 真正 select/commit 之前，先同步通知 T9 控制器消费数字。
        // 控制器返回 true 表示输入序列已被该候选词完整消费，服务层应视为 full commit。
        // 传入候选文本用于 C++ (comment, text) 双条件精确定位（注释歧义防错码）。
        val candidateTextLength = selectedCandidate?.length ?: 0
        val fullyConsumed = if (isT9) {
            service.keyboardCallbacks?.onT9RightCandidateWillBeSelected?.invoke(candidatePinyin, selectedCandidate, candidateTextLength) ?: false
        } else {
            false
        }

        // T9：跳过 service.rimeEngine.selectCandidate，消费已由 T9 处理器（t9_processor）
        // 独立完成。selectCandidate 会遗留 [confirmed, phony] 残留 composition 状态，
        // 导致后续 forceSendToRime 的 setInput 无法正常重建候选项（对齐 main 分支）。
        // 非 T9 才调用 selectCandidate，并用 resolveRimeCandidateIndex 修正候选 index，
        // 避免 UI 候选被过滤/重排后上屏错词。
        val selectRimeOk = if (isT9) {
            true
        } else {
            val rimeIndex = if (pendingAction != null && pendingAction.engineIndex >= 0) {
                // 变换映射记录的引擎候选索引（比按显示文本回查更准）
                pendingAction.engineIndex
            } else if (selectedCandidate != null) {
                resolveRimeCandidateIndex(index, selectedCandidate, service.rimeEngine.getCandidates().toList())
            } else {
                index
            }
            service.rimeEngine.selectCandidate(rimeIndex)
        }
        if (!selectRimeOk) return

        // T9：跳过 service.rimeEngine.commit()，用用户点选的 selectedCandidate 作为权威上屏文本。
        val committedText = if (isT9) "" else service.rimeEngine.commit()
        // T9 模式下 fullyConsumed 是判断 full/partial commit 的唯一权威：
        // 当控制器明确 partial commit 时，即使 RIME commit() 返回非空文本
        // （RIME 内部做了 partial commit），也不应走 full commit 路径。
        val isFullCommit = if (isT9) {
            fullyConsumed && selectedCandidate != null
        } else {
            committedText.isNotEmpty()
        }
        if (isFullCommit) {
            if (SettingsPreferences.isSmartPredictionEnabled(service) && selectedCandidate != null && AssociationManager.isInitialized()) {
                if (service.predictionManager.lastCommittedText.isNotEmpty()) {
                    val lastChar = service.predictionManager.lastCommittedText.last().toString()
                    service.predictionManager.recordInputPair(lastChar, selectedCandidate)
                }
            }
            // T9 full commit：以用户点选的候选词文本为权威上屏文本；
            // 非 T9 仍用 RIME committedText（可能含简繁转换等处理）。
            val textToMerge = if (isT9 && selectedCandidate != null) {
                selectedCandidate
            } else if (committedText.isNotEmpty()) {
                committedText
            } else {
                selectedCandidate!!
            }
            // T9 full commit：partial commit 累积文本（未单独上屏，只存在于
            // service.t9PartialSegments 的 composing 区）与本次上屏文本是独立词，必须拼接，
            // 不能去重（mergePartialCommitText 仅用于显示，会吞掉重复的词）。
            // 但若点击的是 partial 词本身（RIME 无菜单、候选栏显示的就是 partial 词，
            // 无拼音注释 candidatePinyin==null），则只提交累积的 partial 文本，避免重复。
            val partialTexts = service.t9PartialSegments.map { it.text }
            val fullCommitText = if (isT9 && partialTexts.isNotEmpty()) {
                if (candidatePinyin == null) {
                    partialTexts.joinToString("")
                } else {
                    partialTexts.joinToString("") + textToMerge
                }
            } else {
                textToMerge
            }
            // 调频拼音与上屏文本同源：partial 累积拼音 + 当前候选拼音（如 "ji hu a"）。
            val fullCommitPinyin = buildString {
                service.t9PartialSegments.forEachIndexed { i, seg ->
                    if (i > 0) append(' ')
                    append(seg.pinyin)
                }
                if (candidatePinyin != null) {
                    if (isNotEmpty()) append(' ')
                    append(candidatePinyin)
                }
            }
            withContext(Dispatchers.Main) {
                service.commitText(fullCommitText)
                service.t9PartialSegments.clear()
                service.candidateState.value = service.candidateState.value.copy(
                    inputText = "",
                    preeditText = "",
                    candidates = emptyList(),
                    candidateComments = emptyList(),
                    isComposing = false,
                    hasNextPage = false,
                    hasPrevPage = false,
                    isShowingRecentClipboard = false
                )
                service.uiState.value = service.uiState.value.copy(
                    t9ResetSignal = service.uiState.value.t9ResetSignal + 1,
                    t9RightCandidateSelectedCount = 0,
                    t9SelectedCandidatePinyin = ""
                )
            }
            // 用户词典调频：记忆实际上屏文本（后台线程，rimeLock 保护）。
            if (isT9 && fullCommitText.isNotEmpty() && fullCommitPinyin.isNotEmpty()) {
                service.rimeEngine.t9Memorize(fullCommitText, fullCommitPinyin)
            }
            // T9 跳过了 service.rimeEngine.commit()，需显式清除 RIME composition，
            // 防止残留状态影响后续输入（在 service.keyProcessingDispatcher 上执行）。
            if (isT9) service.rimeEngine.clearComposition()
        } else {
            withContext(Dispatchers.Main) {
                if (isT9) {
                    // partial commit：累积候选文本与拼音，供合并显示与 full commit 调频拼接。
                    if (selectedCandidate != null) {
                        service.t9PartialSegments.add(T9PartialSegment(selectedCandidate, candidatePinyin ?: ""))
                    }
                    // 保留状态字段，供 UI 层感知右侧选词事件
                    service.uiState.value = service.uiState.value.copy(
                        t9RightCandidateSelectedCount = service.uiState.value.t9RightCandidateSelectedCount + 1,
                        t9SelectedCandidatePinyin = candidatePinyin ?: ""
                    )
                    // RIME composition 未被 selectCandidate 修改（已跳过），
                    // 直接发送剩余数字到 RIME 重建 composition。
                    // 候选栏由 forceSendToRime 异步 post 刷新；此处不可同步调 updateUI，
                    // 否则与 refreshOnBackground 竞争 rimeLock，空数据覆盖候选栏。
                    service.keyboardCallbacks?.onT9ForceSendToRime?.invoke()
                } else {
                    service.updateUI()
                }
            }
        }
    }

    /**
     * 插件候选直接上屏（所见即所得）：不经引擎 select/commit。
     * 先上屏并清空候选状态（与 enter 提交分支同序），再清引擎组合态。
     */
    private suspend fun commitPluginCandidate(text: String) {
        withContext(Dispatchers.Main) {
            service.commitText(text)
            service.candidateState.value = service.candidateState.value.copy(
                inputText = "",
                preeditText = "",
                candidates = emptyList(),
                candidateComments = emptyList(),
                associationCandidates = emptyList(),
                pendingEnglishText = "",
                isComposing = false,
                isShowingRecentClipboard = false,
                hasNextPage = false,
                hasPrevPage = false,
                candidateActions = emptyList()
            )
            // T9：清 partial 累积与左栏状态（与引擎候选 full commit 同款清理，
            // 防残留 partial 混入下一轮 preedit / 左侧面板残留）
            service.t9PartialSegments.clear()
            service.uiState.value = service.uiState.value.copy(
                t9ResetSignal = service.uiState.value.t9ResetSignal + 1,
                t9RightCandidateSelectedCount = 0,
                t9SelectedCandidatePinyin = ""
            )
        }
        service.rimeEngine.clearComposition()
    }
    
    /**
     * 输入态判定（clear_all 与 undo_clear 共用）。
     *
     * 输入态 = 存在未上屏内容：RIME 组合（candidateState 派生字段）或英文输入
     *（pendingEnglishText 非空——英文按键不经 RIME，故 inputText/preeditText/isComposing 恒空，
     * 漏判会让 clear_all 把 composing 区文本与 pendingEnglishText 重复拼接）
     * 或 T9 partial 累积。
     * 不能用 RIME getInput()——tryLocked 锁竞争时静默返回空，会误判空闲态。
     */
    private fun hasInputState(candState: CandidateState): Boolean =
        candState.isComposing ||
            candState.inputText.isNotEmpty() ||
            candState.preeditText.isNotEmpty() ||
            candState.pendingEnglishText.isNotEmpty() ||
            service.t9PartialSegments.isNotEmpty()

    /**
     * 清空输入态（预编辑/候选/联想/partial 累积/计算器），不动已上屏文本。
     * 供 clear_composition 与 clear_all 输入态分支共用。
     *
     * 显式清 preeditText（与提交路径"残留根治"一致），不依赖 updateUI 自愈；
     * 输入框模式清 composing 区；T9 方案重置左侧候选区（其他键盘无左栏，跳过）。
     */
    private suspend fun clearInputStateForKeys() {
        service.calculatorEngine.clear()
        updateCalculatorCandidates()
        service.t9PartialSegments.clear()
        service.rimeEngine.clearComposition()
        service.candidateState.value = service.candidateState.value.copy(
            candidates = emptyList(),
            candidateComments = emptyList(),
            associationCandidates = emptyList(),
            pendingEnglishText = "",
            inputText = "",
            preeditText = "",
            isComposing = false,
            isShowingRecentClipboard = false
        )
        if (SettingsPreferences.getInputTextLocation(service) ==
            SettingsPreferences.INPUT_TEXT_INPUT_BOX
        ) {
            withContext(Dispatchers.Main) { service.endComposingInputBox() }
        }
        if (isT9Schema(service.uiState.value.currentSchemaId)) {
            service.uiState.value = service.uiState.value.copy(
                t9ResetSignal = service.uiState.value.t9ResetSignal + 1,
                t9RightCandidateSelectedCount = 0,
                t9SelectedCandidatePinyin = ""
            )
        }
    }

    /**
     * 更新计算器候选栏显示
     * 显示两个候选：
     * - index 0: 计算结果（如 "2"），点击直接替换为结果
     * - index 1: 带公式的结果（如 "1+1=2"），点击显示公式和结果
     */
    internal fun updateCalculatorCandidates() {
        val candidate = service.calculatorEngine.getCandidate()
        val result = service.calculatorEngine.getResult()
        service.candidateState.value = if (candidate != null && result.isNotEmpty()) {
            service.candidateState.value.copy(
                candidates = listOf(result, candidate),
                candidateComments = emptyList()
            )
        } else {
            // 如果计算器之前有显示但现在已清除，也要清空候选栏
            if (service.candidateState.value.candidates.isNotEmpty() && !service.calculatorEngine.isActive()) {
                service.candidateState.value.copy(
                    candidates = emptyList(),
                    candidateComments = emptyList()
                )
            } else {
                service.candidateState.value
            }
        }
    }

    internal fun selectCandidate(index: Int) {
        service.composeViewRef?.let { service.feedbackManager.performKeyPressEffect(view = it) }

        // 计算器模式（仅在数字/常用符号键盘下生效，防止状态残留导致其他键盘候选词点击异常）
        val layoutState = service.keyboardViewModel.keyboardState.value
        val isCalculatorKeyboard = layoutState is com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState.Number
                || layoutState is com.kingzcheung.xime.ui.keyboard.KeyboardLayoutState.CommonSymbol
        if (service.calculatorEngine.isActive() && isCalculatorKeyboard) {
            val result = service.calculatorEngine.getResult()
            val expression = service.calculatorEngine.getExpression()
            val formulaResult = service.calculatorEngine.getFormulaResult()
            if (result.isNotEmpty() && expression.isNotEmpty()) {
                val textToCommit: String
                // index 0: 纯结果（如 "2"）
                // index 1: 公式结果（如 "1+1=2"）
                textToCommit = when (index) {
                    0 -> result
                    1 -> formulaResult
                    else -> ""
                }
                if (textToCommit.isNotEmpty()) {
                    service.calculatorEngine.clear()
                    service.serviceScope.launch(Dispatchers.Main) {
                        val ic = service.currentInputConnection
                        if (ic != null) {
                            // 删除输入框中已键入的表达式
                            ic.deleteSurroundingText(expression.length, 0)
                            // 提交选中的文本
                            ic.commitText(textToCommit, textToCommit.length)
                        }
                        service.candidateState.value = CandidateState()
                    }
                }
            }
            return
        }
        
        if (service.candidateState.value.isShowingRecentClipboard && index >= 0 && index < service.recentClipboardItemsState.value.size) {
            val text = service.recentClipboardItemsState.value[index].text
            service.textCommit.selectClipboardItem(text)
            service.candidateState.value = service.candidateState.value.copy(
                isShowingRecentClipboard = false,
                candidates = emptyList(),
                candidateComments = emptyList()
            )
        } else {
            postRimeJob {
                selectCandidateAsync(index)
            }
        }
    }

    /**
     * 长按候选删除自造词（键盘无关，T9/全键盘通用）。
     * 标准 C API delete_candidate_on_current_page：librime 对 userdb 词条
     * 做 tombstone 标记（UpdateEntry -1），对非用户词由 rime 侧自行判定。
     * 显示索引经 resolveRimeCandidateIndex 映射回引擎原始索引（插件候选
     * 注入会使两者错位，与 selectCandidateAsync 同口径），删除后刷新候选。
     */
    internal fun deleteCandidate(displayIndex: Int) {
        postRimeJob {
            val text = service.candidateState.value.candidates.getOrNull(displayIndex)
            if (text.isNullOrEmpty()) return@postRimeJob
            val engineIndex = resolveRimeCandidateIndex(
                displayIndex, text, service.rimeEngine.getCandidates().toList()
            )
            val ok = service.rimeEngine.deleteCandidateOnCurrentPage(engineIndex)
            FileLogger.i(
                XimeInputMethodService.TAG,
                "DeleteCandidate: text='$text' display=$displayIndex engine=$engineIndex ok=$ok"
            )
            if (ok) {
                withContext(Dispatchers.Main) {
                    service.updateUI()
                }
            }
        }
    }
    
    internal fun pageDown() {
        postRimeJob {
            service.rimeEngine.pageDown()
            withContext(Dispatchers.Main) {
                service.updateUI()
            }
        }
    }
    
    internal fun pageUp() {
        postRimeJob {
            service.rimeEngine.pageUp()
            withContext(Dispatchers.Main) {
                service.updateUI()
            }
        }
    }

}