package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 宽容点击：语义同 Modifier.clickable，但点击判定改为「抬起时累计位移仍在
 * 容差内即触发」，不因事件被父级滚动/翻页手势消费而静默取消。
 *
 * 背景：候选页、表情页、符号页的按钮外层是 Pager/滚动容器。系统手势判定下，
 * 按下后位移超过 touch slop 即被判为滚动/翻页，clickable 随之取消点击；
 * 部分 ROM 的 slop 更小、采样更敏感，轻点的小位移也会超限，表现为
 * 「点了没反应」。容差取 2 倍 touch slop 与 12dp 的较大者：轻点不再丢点击，
 * 真实滚动/翻页的位移远超容差，不受影响。
 *
 * 本手势不消费任何指针事件，父级 Pager/滚动的行为保持系统默认。
 * showRipple = false 时涟漪反馈交给调用方自绘（配合 interactionSource 的
 * Press/Release/Cancel 事件，语义与 clickable 一致）。
 *
 * [onLongClick] 非空时启用长按：按住超过系统长按超时且位移未超容差即触发
 * （触发一次后抬起不再 onClick，语义同 combinedClickable）。位移超容差则
 * 取消长按资格（视为滚动/滑动）。
 *
 * [longPressHaptic] 为 true 时长按触发的同时发长按触觉反馈（对齐 combinedClickable）。
 * 默认关：多数按键调用方已有自己的触觉链路，默认开会重复震动。
 */
fun Modifier.tolerantClick(
    enabled: Boolean = true,
    showRipple: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    longPressHaptic: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentLongPressHaptic by rememberUpdatedState(longPressHaptic)
    val hapticFeedback = LocalHapticFeedback.current
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val resolvedIndication = if (showRipple) LocalIndication.current else null

    val indicationModifier = if (resolvedIndication != null) {
        Modifier.indication(source, resolvedIndication)
    } else {
        Modifier
    }

    indicationModifier.then(
        Modifier.pointerInput(currentEnabled, onLongClick != null) {
            if (!currentEnabled) return@pointerInput
            val slopPx = maxOf(2f * viewConfiguration.touchSlop, 12.dp.toPx())
            val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val press = PressInteraction.Press(down.position)
                source.tryEmit(press)
                var travelled = 0f
                var travelExceeded = false
                var longPressFired = false
                var awaitingLongPress = onLongClick != null
                var lastEventTime = down.uptimeMillis
                while (true) {
                    val event = if (awaitingLongPress) {
                        // 长按计时窗口：无事件（按住不动）超时即触发长按
                        val remaining = longPressTimeoutMs - (lastEventTime - down.uptimeMillis)
                        withTimeoutOrNull(remaining.coerceAtLeast(0L)) {
                            awaitPointerEvent()
                        }
                    } else {
                        awaitPointerEvent()
                    }
                    if (event == null) {
                        // 长按超时触发
                        longPressFired = true
                        awaitingLongPress = false
                        if (currentLongPressHaptic) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        currentOnLongClick?.invoke()
                        source.tryEmit(PressInteraction.Cancel(press))
                        continue
                    }
                    event.changes.firstOrNull()?.uptimeMillis?.let { lastEventTime = it }
                    val pressedChange = event.changes.firstOrNull { it.pressed }
                    val upChange = event.changes.firstOrNull { it.changedToUp() }
                    travelled += (pressedChange ?: upChange)?.positionChange()?.getDistance() ?: 0f
                    if (pressedChange == null) {
                        if (upChange != null && !longPressFired && travelled <= slopPx) {
                            currentOnClick()
                            source.tryEmit(PressInteraction.Release(press))
                        } else {
                            source.tryEmit(PressInteraction.Cancel(press))
                        }
                        break
                    }
                    if (awaitingLongPress && travelled > slopPx) {
                        // 位移超容差：取消长按资格（视为滑动），仅保留抬起判定
                        awaitingLongPress = false
                    }
                }
            }
        }
    )
}

/**
 * 容差点击检测：语义同 [detectTapGestures]，但**不因指针越出节点矩形而取消**。
 *
 * 取消口径与拖拽激活对齐：净位移（向量长度）达到 [slopPx] 才视为滑动，交给拖拽的
 * `onDragEnd` 兜底；否则一律算点击。用路径长度会在抖动/回摆时先超 slop 而净位移没超，
 * 点击被取消、拖拽又没激活 → 两头都不响应（快速打字吞键）。
 *
 * @param onPress 按下瞬间（相当于原 `onPress` 的前半段）
 * @param onPressEnd 手势结束时回调，[released] 为 true 表示正常抬起
 * @param onTap 松手且未取消时触发
 * @param onLongPress 非空时启用长按（按住超过系统长按超时且位移未超容差时触发一次）
 * @param slopPx 位移容差，默认系统 touch slop（与拖拽激活阈值一致，避免反向死区）
 */
internal suspend fun PointerInputScope.detectTolerantTapGestures(
    onPress: (Offset) -> Unit = {},
    onPressEnd: (released: Boolean) -> Unit = {},
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    slopPx: Float = viewConfiguration.touchSlop,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    down.consume()
    onPress(down.position)

    val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis
    var netMove = Offset.Zero
    var longPressFired = false
    var awaitingLongPress = onLongPress != null
    var lastEventTime = down.uptimeMillis
    var released = false
    var cancelled = false

    // 协程被取消（键盘重挂/触摸流取消）时也要回调 onPressEnd，便于调用方清按压态
    try {
        while (true) {
            // 长按窗口内按住不动（无事件）超时即判定长按
            val event = if (awaitingLongPress) {
                val remaining = longPressTimeoutMs - (lastEventTime - down.uptimeMillis)
                withTimeoutOrNull(remaining.coerceAtLeast(0L)) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }

            if (event == null) {
                longPressFired = true
                awaitingLongPress = false
                onLongPress?.invoke()
                continue
            }

            event.changes.firstOrNull()?.let { lastEventTime = it.uptimeMillis }
            // 只认本手指（多指时其余指针不属于本键，不做兜底取首个，避免误判抬起）
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                cancelled = true
                break
            }
            if (change.isConsumed) {
                // 被拖拽/父层手势接管
                cancelled = true
                break
            }
            if (change.changedToUpIgnoreConsumed()) {
                released = true
                change.consume()
                break
            }
            if (longPressFired) {
                // 长按已触发：吞掉后续位移，避免与拖拽手势重复响应（等价 consumeUntilUp）
                change.consume()
                continue
            }
            netMove += change.positionChangeIgnoreConsumed()
            if (netMove.getDistance() >= slopPx) {
                // 净位移达到 slop：视为滑动，交给拖拽 onDragEnd 兜底点击
                cancelled = true
                break
            }
        }
    } finally {
        onPressEnd(released)
    }
    if (released && !cancelled && !longPressFired) onTap()
}
