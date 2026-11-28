package com.kingzcheung.xime.service

import android.content.Context
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

class VoiceKeyboardContainer(
    context: Context,
    private val uiStateProvider: () -> InputUIState,
    private val onUiStateChanged: (InputUIState) -> Unit,
    private val onPerformVibration: (View) -> Unit,
    private val onPerformUndo: () -> Unit,
    private val onPerformSearch: () -> Unit,
    private val onStopRecognition: () -> Unit,
    private val isRecording: () -> Boolean,
    private val setRecording: (Boolean) -> Unit,
    private val onVoiceDismiss: () -> Unit = {},
    private val onTouchCancel: () -> Unit = {},
    /** 键盘内容高度落到 View 上之后回调（px）：开窗口模糊时窗口高度要跟着它走。 */
    private val onHeightApplied: (Int) -> Unit = {},
) : FrameLayout(context) {

    private var isTrackingVoiceButtons = false
    private var lastLeftActive = false
    private var lastRightActive = false

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0f
    }

    fun enableVoiceButtonTracking() {
        isTrackingVoiceButtons = true
    }

    fun updateHeight(heightDp: Int) {
        val heightPx = (heightDp * resources.displayMetrics.density).toInt()
        val params = layoutParams ?: return
        if (params.height != heightPx) {
            params.height = heightPx
            layoutParams = params
            requestLayout()
        }
        onHeightApplied(heightPx)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            when (it.action) {
                MotionEvent.ACTION_DOWN -> {
                    handleActionDown(it)
                }

                MotionEvent.ACTION_UP -> {
                    handleActionUp()
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 系统手势（如三指截图）截走触摸流时，IME 收不到 UP，Compose 手势也不会被取消。
                    // 这里把 cancel 上抛，触发活动键盘 remount 来取消所有进行中的手势协程。
                    handleActionUp()
                    onTouchCancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    handleActionMove(it)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun handleActionDown(ev: MotionEvent) {
        val uiState = uiStateProvider()
        val isVoiceMode = uiState.isVoiceMode && !uiState.voiceSticky

        lastLeftActive = false
        lastRightActive = false

        if (isVoiceMode) {
            val yThreshold = height * 0.6f

            if (ev.y > yThreshold) {
                isTrackingVoiceButtons = true
                onUiStateChanged(uiStateProvider().copy(
                    voiceButtonState = VoiceButtonState(bottomActive = true)
                ))
            }
        }
    }

    private fun handleActionUp() {
        val state = uiStateProvider()

        // 常驻语音（工具栏进入）不拦截触摸：空格键/工具栏自行结束语音
        if (state.voiceSticky) {
            isTrackingVoiceButtons = false
            lastLeftActive = false
            lastRightActive = false
            return
        }

        if (state.isVoiceMode || isRecording()) {
            if (state.voiceButtonState.leftActive) {
                onPerformUndo()
            } else if (state.voiceButtonState.rightActive) {
                onPerformSearch()
            }

            if (isRecording()) {
                onStopRecognition()
                setRecording(false)
            }

            if (state.isVoiceMode) {
                onVoiceDismiss()
            }
        }

        isTrackingVoiceButtons = false
        lastLeftActive = false
        lastRightActive = false
    }

    private fun handleActionMove(ev: MotionEvent) {
        val isVoiceMode = uiStateProvider().isVoiceMode && !uiStateProvider().voiceSticky

        if (isVoiceMode && isTrackingVoiceButtons) {
            val yThreshold = height * 0.6f
            val leftButtonEnd = width * 0.25f
            val rightButtonStart = width * 0.75f

            if (ev.y > yThreshold) {
                when {
                    ev.x < leftButtonEnd -> {
                        if (!lastLeftActive) {
                            onPerformVibration(this@VoiceKeyboardContainer)
                            lastLeftActive = true
                        }
                        onUiStateChanged(uiStateProvider().copy(
                            voiceButtonState = VoiceButtonState(leftActive = true)
                        ))
                    }
                    ev.x > rightButtonStart -> {
                        if (!lastRightActive) {
                            onPerformVibration(this@VoiceKeyboardContainer)
                            lastRightActive = true
                        }
                        onUiStateChanged(uiStateProvider().copy(
                            voiceButtonState = VoiceButtonState(rightActive = true)
                        ))
                    }
                    else -> {
                        lastLeftActive = false
                        lastRightActive = false
                        onUiStateChanged(uiStateProvider().copy(
                            voiceButtonState = VoiceButtonState(bottomActive = true)
                        ))
                    }
                }
            } else if (ev.x < leftButtonEnd) {
                if (!lastLeftActive) {
                    onPerformVibration(this@VoiceKeyboardContainer)
                    lastLeftActive = true
                }
                onUiStateChanged(uiStateProvider().copy(
                    voiceButtonState = VoiceButtonState(leftActive = true)
                ))
            } else if (ev.x > rightButtonStart) {
                if (!lastRightActive) {
                    onPerformVibration(this@VoiceKeyboardContainer)
                    lastRightActive = true
                }
                onUiStateChanged(uiStateProvider().copy(
                    voiceButtonState = VoiceButtonState(rightActive = true)
                ))
            } else {
                lastLeftActive = false
                lastRightActive = false
                onUiStateChanged(uiStateProvider().copy(
                    voiceButtonState = VoiceButtonState()
                ))
            }
        }
    }
}
