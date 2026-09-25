package com.kingzcheung.xime.service

import android.view.inputmethod.EditorInfo
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ascii（中/西文）模式唯一控制入口。
 *
 * 状态权威：rime 引擎的 ascii_mode option，语义为**会话级状态**——
 * 打开键盘默认中文；用户切英文仅在当前输入会话内有效（收起键盘/切换
 * 应用后回到中文）；密码类输入框临时强制英文。不持久化到 user.yaml，
 * 历史 var/option/ascii_mode 残留值无人读取，不再影响初始状态。
 *
 * 引擎 ascii 的三个写入场景（均收敛到本类）：
 * - [switchAscii]：切换（用户显式 / 面板同步，[Reason] 仅用于日志溯源）
 * - [applyStartDecision]：新输入会话的确定性决策（默认中文，密码框临时英文）
 * - [restoreDefaultChoice]：会话结束归位（引擎回落中文，临时态不残留）
 */
internal class AsciiModeController(private val service: XimeInputMethodService) {

    /** 切换原因：仅用于日志溯源（均不持久化） */
    enum class Reason {
        /** 用户显式中英切换（主键盘切换键、菜单中西开关） */
        USER_TOGGLE,

        /** 面板上下文同步（进/出数字·符号面板、面板内中英键） */
        PANEL_SYNC,
    }

    /**
     * 切换引擎 ascii 模式（toggle 语义），完成后权威同步 uiState 与键盘布局。
     * 由 ImeKeyRouter 在 key-processing 线程调用：toggleAsciiMode 阻塞等待 rimeLock
     * （部署/维护持锁时排队，完成后自动切换），不静默失败、不阻塞主线程。
     * 返回 false 仅表示引擎不可用（session 创建失败）。
     */
    internal suspend fun switchAscii(
        reason: Reason,
        switchAction: String? = null,
    ): Boolean {
        if (service.candidateState.value.isInlineAsciiActive) {
            // 普通切换结束临时英文态；commit_* 随后继续执行其原有切换语义。
            finishInlineAscii(commitText = true)
            if (switchAction == null) return true
            return switchAscii(reason, switchAction)
        }
        val candState = service.candidateState.value
        val pendingEnglish = candState.pendingEnglishText
        val hardwareCommitCode = switchAction == "commit_code"
        FileLogger.i(
            XimeInputMethodService.TAG,
            "switchAscii[$reason]: action=${switchAction ?: "ui"}, pendingEnglish='${if (pendingEnglish.isEmpty()) "-" else pendingEnglish}', " +
                "isComposing=${candState.isComposing}, candidates=${candState.candidates.size}"
        )
        if (pendingEnglish.isNotEmpty()) {
            // 英文直接上屏模式：编码字符已逐字落盘，切模式只需结束本轮输入（清状态），
            // 不可再 commitText 否则会重复输出整个词。
            withContext(Dispatchers.Main) {
                service.candidateState.value = service.candidateState.value.copy(
                    pendingEnglishText = "",
                    associationCandidates = emptyList()
                )
            }
        } else if (candState.isComposing) {
            if (candState.candidates.isNotEmpty() && !hardwareCommitCode) {
                service.keyRouter.selectCandidateAsync(0)
            } else {
                // commit_code 按方案语义提交原始编码，不要误选候选词。
                val input = candState.inputText
                if (input.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        service.commitText(input)
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
                service.rimeEngine.clearComposition()
            }
        }
        val t0 = System.nanoTime()
        if (!service.rimeEngine.toggleAsciiMode()) {
            FileLogger.e(XimeInputMethodService.TAG, "switchAscii[$reason]: toggleAsciiMode FAILED (engine unavailable)")
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(service, "输入法引擎不可用，请稍后再试", android.widget.Toast.LENGTH_SHORT).show()
            }
            return false
        }
        val ascii = service.rimeEngine.isAsciiMode()
        FileLogger.i(
            XimeInputMethodService.TAG,
            "switchAscii[$reason]: toggleAsciiMode ok, took ${(System.nanoTime() - t0) / 1_000_000}ms, rime ascii=$ascii"
        )
        withContext(Dispatchers.Main) {
            // 显式同步 uiState.isAsciiMode（权威源 = rime 引擎状态），
            // 不依赖 updateUI 链路异步回写，避免键盘 UI 与 rime 状态脱钩。
            FileLogger.i(XimeInputMethodService.TAG, "switchAscii[$reason]: rime ascii=$ascii, ui before=${service.uiState.value.isAsciiMode}")
            service.uiState.value = service.uiState.value.copy(isAsciiMode = ascii)
            service.updateUI()
            // 主线程直接权威下发键盘布局切换（与 rime 状态一致），
            // 不依赖 Compose LaunchedEffect 侦测 uiState 后再异步 dispatch（部分机型调度延迟导致 UI 不更新）。
            val schemaId = service.rimeEngine.getCurrentSchema()
            service.keyboardViewModel.dispatch(
                com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction.AsciiModeChanged(ascii, schemaId)
            )
        }
        return true
    }

    /** 进入独立的 inline_ascii 临时英文编辑态。 */
    internal suspend fun startInlineAscii(): Boolean {
        val current = service.candidateState.value
        if (current.isInlineAsciiActive) return true

        if (current.pendingEnglishText.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                service.candidateState.value = service.candidateState.value.copy(
                    pendingEnglishText = "",
                    associationCandidates = emptyList()
                )
            }
        } else if (current.isComposing) {
            if (current.candidates.isNotEmpty()) {
                service.keyRouter.selectCandidateAsync(0)
            } else if (current.inputText.isNotEmpty()) {
                withContext(Dispatchers.Main) { service.commitText(current.inputText) }
                service.rimeEngine.clearComposition()
            }
        }

        withContext(Dispatchers.Main) {
            service.candidateState.value = service.candidateState.value.copy(
                isInlineAsciiActive = true,
                inlineAsciiText = "",
                inputText = "",
                preeditText = "",
                candidates = emptyList(),
                candidateComments = emptyList(),
                associationCandidates = emptyList(),
                isComposing = false,
                candidateActions = emptyList()
            )
        }
        service.rimeEngine.clearComposition()
        service.rimeEngine.setOption("ascii_mode", true)
        syncAsciiUi(true)
        return true
    }

    /** 结束 inline_ascii；返回结束前是否有暂存文本。 */
    internal suspend fun finishInlineAscii(commitText: Boolean): Boolean {
        val current = service.candidateState.value
        if (!current.isInlineAsciiActive) return false
        val text = current.inlineAsciiText
        if (commitText && text.isNotEmpty()) {
            withContext(Dispatchers.Main) { service.commitText(text) }
        }
        service.rimeEngine.clearComposition()
        service.rimeEngine.setOption("ascii_mode", false)
        withContext(Dispatchers.Main) {
            service.endComposingInputBox()
            service.candidateState.value = service.candidateState.value.copy(
                isInlineAsciiActive = false,
                inlineAsciiText = "",
                inputText = "",
                preeditText = "",
                candidates = emptyList(),
                candidateComments = emptyList(),
                associationCandidates = emptyList(),
                isComposing = false,
                candidateActions = emptyList()
            )
        }
        syncAsciiUi(false)
        return text.isNotEmpty()
    }

    internal suspend fun cancelInlineAscii() {
        finishInlineAscii(commitText = false)
    }

    internal fun cancelInlineAsciiForModeChange() {
        val current = service.candidateState.value
        if (!current.isInlineAsciiActive) return
        service.rimeEngine.clearComposition()
        service.rimeEngine.setOption("ascii_mode", false)
        service.endComposingInputBox()
        service.candidateState.value = current.copy(
            isInlineAsciiActive = false,
            inlineAsciiText = "",
            inputText = "",
            preeditText = "",
            candidates = emptyList(),
            candidateComments = emptyList(),
            associationCandidates = emptyList(),
            isComposing = false,
            candidateActions = emptyList()
        )
        service.uiState.value = service.uiState.value.copy(isAsciiMode = false)
    }

    /** UI 模式切换回调在主线程同步调用，不能等待协程。 */
    internal fun finishInlineAsciiForModeChange() {
        val current = service.candidateState.value
        if (!current.isInlineAsciiActive) return
        if (current.inlineAsciiText.isNotEmpty()) service.commitText(current.inlineAsciiText)
        service.rimeEngine.clearComposition()
        service.rimeEngine.setOption("ascii_mode", false)
        service.endComposingInputBox()
        service.candidateState.value = service.candidateState.value.copy(
            isInlineAsciiActive = false,
            inlineAsciiText = "",
            inputText = "",
            preeditText = "",
            candidates = emptyList(),
            candidateComments = emptyList(),
            associationCandidates = emptyList(),
            isComposing = false,
            candidateActions = emptyList()
        )
        service.uiState.value = service.uiState.value.copy(isAsciiMode = false)
    }

    private suspend fun syncAsciiUi(ascii: Boolean) {
        withContext(Dispatchers.Main) {
            service.uiState.value = service.uiState.value.copy(isAsciiMode = ascii)
            val schemaId = service.rimeEngine.getCurrentSchema()
            service.keyboardViewModel.dispatch(
                com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction.AsciiModeChanged(ascii, schemaId)
            )
        }
    }

    /**
     * 新输入会话的 ascii 决策：默认中文（英文态不跨收起存活）；
     * 密码类输入框临时强制英文（不持久化）。
     * 设置引擎选项并返回目标模式，供调用方同步 uiState 与键盘布局。
     */
    internal fun applyStartDecision(attribute: EditorInfo?): Boolean {
        if (!RimeEngine.isInitialized()) return service.uiState.value.isAsciiMode
        val target = EditorInfoClassifier.isPasswordEditor(attribute)
        if (service.rimeEngine.isAsciiMode() != target) {
            service.rimeEngine.setOption("ascii_mode", target)
            FileLogger.i(
                XimeInputMethodService.TAG,
                "applyStartDecision: ascii ${!target} -> $target (password=$target)"
            )
        }
        return target
    }

    /**
     * 会话结束归位：引擎 ascii 回落到默认中文。
     * 面板同步/密码框/用户显式切换产生的英文态均为会话级，不跨会话残留。
     */
    internal fun restoreDefaultChoice() {
        if (!RimeEngine.isInitialized()) return
        if (service.rimeEngine.isAsciiMode()) {
            service.rimeEngine.setOption("ascii_mode", false)
            FileLogger.i(XimeInputMethodService.TAG, "restoreDefaultChoice: ascii true -> false")
        }
    }
}
