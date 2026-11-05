package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.settings.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字母/数字等"可上屏键"的 tap / 上滑动作分发（[dispatchKeyActionOrPress]、[swipeUpHandlerFor]）。
 */
class KeyboardGestureActionDispatchTest {

    /** 记录三类回调的调用情况。 */
    private class Recorder {
        val pressed = mutableListOf<String>()
        val committed = mutableListOf<String>()
        val gestures = mutableListOf<Pair<GestureAction, String>>()
        val onKeyPress: (String) -> Unit = { pressed += it }
        val onCommitText: (String) -> Unit = { committed += it }
        val onGestureAction: (GestureAction, String) -> Unit = { a, v -> gestures += a to v }
    }

    // ── tap ──

    @Test
    fun `未配置动作的 tap 沿用原有上屏路径`() {
        val r = Recorder()
        dispatchKeyActionOrPress(null, "q", r.onKeyPress, r.onCommitText, r.onGestureAction)
        assertEquals(listOf("q"), r.pressed)
        assertEquals(emptyList<Pair<GestureAction, String>>(), r.gestures)
    }

    @Test
    fun `send_rime 与槽位默认 commit 走 onKeyPress 以保留组合语义与 shift 大写`() {
        val r = Recorder()
        dispatchKeyActionOrPress(
            KeyAction(action = GestureAction.SEND_RIME, value = "q"), "Q",
            r.onKeyPress, r.onCommitText, r.onGestureAction,
        )
        // 槽位默认 commit：字符串简写/只写 value，未显式写 action
        dispatchKeyActionOrPress(
            KeyAction(action = GestureAction.COMMIT, value = "q"), "Q",
            r.onKeyPress, r.onCommitText, r.onGestureAction,
        )
        assertEquals(listOf("Q", "Q"), r.pressed)
        assertEquals(emptyList<String>(), r.committed)
        assertEquals(emptyList<Pair<GestureAction, String>>(), r.gestures)
    }

    @Test
    fun `显式 action commit 直接上屏且不经过 onKeyPress`() {
        val r = Recorder()
        dispatchKeyActionOrPress(
            KeyAction(action = GestureAction.COMMIT, value = "!", explicitAction = true), "1",
            r.onKeyPress, r.onCommitText, r.onGestureAction,
        )
        assertEquals(listOf("!"), r.committed)
        assertEquals(emptyList<String>(), r.pressed)
        assertEquals(emptyList<Pair<GestureAction, String>>(), r.gestures)
    }

    @Test
    fun `显式 commit 无 value 时回退 label 再回退按键默认值`() {
        val withLabel = Recorder()
        dispatchKeyActionOrPress(
            KeyAction(action = GestureAction.COMMIT, label = "，", explicitAction = true), "q",
            withLabel.onKeyPress, withLabel.onCommitText, withLabel.onGestureAction,
        )
        assertEquals(listOf("，"), withLabel.committed)

        val empty = Recorder()
        dispatchKeyActionOrPress(
            KeyAction(action = GestureAction.COMMIT, explicitAction = true), "Q",
            empty.onKeyPress, empty.onCommitText, empty.onGestureAction,
        )
        assertEquals(listOf("Q"), empty.committed)
        assertEquals(emptyList<String>(), empty.pressed)
    }

    @Test
    fun `copy 动作走手势分发且不上屏`() {
        val r = Recorder()
        dispatchKeyActionOrPress(
            KeyAction(action = GestureAction.COPY, value = "复制"), "c",
            r.onKeyPress, r.onCommitText, r.onGestureAction,
        )
        assertEquals(listOf(GestureAction.COPY to "复制"), r.gestures)
        assertEquals(emptyList<String>(), r.pressed)
    }

    @Test
    fun `switch_route 动作把 value 透传给手势分发`() {
        val r = Recorder()
        dispatchKeyActionOrPress(
            KeyAction(action = GestureAction.SWITCH_ROUTE, value = "clipboard"), "q",
            r.onKeyPress, r.onCommitText, r.onGestureAction,
        )
        assertEquals(listOf(GestureAction.SWITCH_ROUTE to "clipboard"), r.gestures)
    }

    @Test
    fun `command 动作走按键路由（由上层把 symbol 等命令名映射为动作）`() {
        val r = Recorder()
        dispatchKeyActionOrPress(
            KeyAction(action = GestureAction.COMMAND, value = "symbol"), null,
            r.onKeyPress, r.onCommitText, r.onGestureAction,
        )
        assertEquals(listOf("symbol"), r.pressed)
        assertEquals(emptyList<Pair<GestureAction, String>>(), r.gestures)
    }

    @Test
    fun `command 未登记的命令名不上屏`() {
        val r = Recorder()
        dispatchKeyActionOrPress(
            KeyAction(action = GestureAction.COMMAND, value = "symobl"), null,
            r.onKeyPress, r.onCommitText, r.onGestureAction,
        )
        assertEquals(emptyList<String>(), r.pressed)
        assertEquals(emptyList<String>(), r.committed)
    }

    @Test
    fun `上滑 command 动作同样走按键路由且不直连上屏`() {
        val r = Recorder()
        val handler = swipeUpHandlerFor(
            KeyAction(action = GestureAction.COMMAND, value = "symbol"), "symbol",
            r.onKeyPress, r.onCommitText, r.onGestureAction,
        )
        handler!!.invoke("符号")
        assertEquals(listOf("symbol"), r.pressed)
        assertEquals(emptyList<String>(), r.committed)
        assertEquals(emptyList<Pair<GestureAction, String>>(), r.gestures)
    }

    @Test
    fun `tap 的 action none 不触发任何回调`() {
        val r = Recorder()
        dispatchKeyActionOrPress(
            KeyAction(action = GestureAction.NONE), "q",
            r.onKeyPress, r.onCommitText, r.onGestureAction,
        )
        assertEquals(emptyList<String>(), r.pressed)
        assertEquals(emptyList<Pair<GestureAction, String>>(), r.gestures)
    }

    @Test
    fun `tap 未配置值时不触发上屏`() {
        val r = Recorder()
        dispatchKeyActionOrPress(
            KeyAction(action = GestureAction.COMMIT), null,
            r.onKeyPress, r.onCommitText, r.onGestureAction,
        )
        assertEquals(emptyList<String>(), r.pressed)
    }

    // ── 上滑 ──

    @Test
    fun `未配置上滑且无默认值时不生成处理器`() {
        val r = Recorder()
        assertNull(swipeUpHandlerFor(null, null, r.onKeyPress, r.onCommitText, r.onGestureAction))
    }

    @Test
    fun `上滑 action none 生成吸收处理器且不上屏`() {
        val r = Recorder()
        val handler = swipeUpHandlerFor(
            KeyAction(action = GestureAction.NONE), "1",
            r.onKeyPress, r.onCommitText, r.onGestureAction,
        )
        // 与下滑/左右滑一致：none 吸收上滑，避免竖向拖拽被落点判定误打出字符
        assertNotNull(handler)
        handler!!.invoke("1")
        assertEquals(emptyList<String>(), r.pressed)
        assertEquals(emptyList<String>(), r.committed)
        assertEquals(emptyList<Pair<GestureAction, String>>(), r.gestures)
    }

    @Test
    fun `未配置上滑但存在旧默认表取值时沿用原有上滑上屏`() {
        val r = Recorder()
        val handler = swipeUpHandlerFor(null, "1", r.onKeyPress, r.onCommitText, r.onGestureAction)
        assertNotNull(handler)
        handler!!.invoke("1")
        assertEquals(listOf("1"), r.pressed)
        assertEquals(emptyList<Pair<GestureAction, String>>(), r.gestures)
    }

    @Test
    fun `上滑配置非上屏动作时走手势分发`() {
        val r = Recorder()
        val handler = swipeUpHandlerFor(
            KeyAction(action = GestureAction.SWITCH_ROUTE, value = "clipboard"), "1",
            r.onKeyPress, r.onCommitText, r.onGestureAction,
        )
        assertNotNull(handler)
        handler!!.invoke("剪贴板")
        assertEquals(listOf(GestureAction.SWITCH_ROUTE to "clipboard"), r.gestures)
        assertEquals(emptyList<String>(), r.pressed)
    }

    @Test
    fun `上滑槽位默认 commit 取 commitValue 且走 onKeyPress 而非直接上屏`() {
        val r = Recorder()
        val handler = swipeUpHandlerFor(
            KeyAction(action = GestureAction.COMMIT, value = "~"), "~",
            r.onKeyPress, r.onCommitText, r.onGestureAction,
        )
        handler!!.invoke("～")
        // 槽位默认（未显式写 action）的字母上滑必须经 onKeyPress，保留输入法组合/候选语义
        assertEquals(listOf("~"), r.pressed)
        assertEquals(emptyList<String>(), r.committed)
    }

    @Test
    fun `上滑显式 action commit 直接上屏`() {
        val r = Recorder()
        val handler = swipeUpHandlerFor(
            KeyAction(action = GestureAction.COMMIT, value = "~", explicitAction = true), "~",
            r.onKeyPress, r.onCommitText, r.onGestureAction,
        )
        handler!!.invoke("～")
        assertEquals(listOf("~"), r.committed)
        assertEquals(emptyList<String>(), r.pressed)
    }

    // ── 长按气泡选中的动作 ──

    @Test
    fun `长按 COMMIT 优先上屏动作 value 而非气泡显示文本`() {
        val r = Recorder()
        val map = mapOf("，" to KeyAction(action = GestureAction.COMMIT, value = "。", label = "，"))
        dispatchLongPressSelection("，", map, r.onKeyPress, r.onCommitText, r.onGestureAction)
        assertEquals(listOf("。"), r.committed)
        assertEquals(emptyList<String>(), r.pressed)
    }

    @Test
    fun `长按 COMMIT 无 value 时回退气泡显示文本`() {
        val r = Recorder()
        val map = mapOf("，" to KeyAction(action = GestureAction.COMMIT, label = "，"))
        dispatchLongPressSelection("，", map, r.onKeyPress, r.onCommitText, r.onGestureAction)
        assertEquals(listOf("，"), r.committed)
    }

    @Test
    fun `长按未配置动作时按上屏处理且不崩溃`() {
        val r = Recorder()
        // action 为 null：原实现会走 action!! 崩溃
        val map = mapOf("，" to KeyAction(value = "，"))
        dispatchLongPressSelection("，", map, r.onKeyPress, r.onCommitText, r.onGestureAction)
        assertEquals(listOf("，"), r.committed)
        assertEquals(emptyList<Pair<GestureAction, String>>(), r.gestures)
    }

    @Test
    fun `长按上屏无 onCommitText 时回退 onKeyPress`() {
        val r = Recorder()
        val map = mapOf("x" to KeyAction(action = GestureAction.COMMIT, value = "x"))
        dispatchLongPressSelection("x", map, r.onKeyPress, null, r.onGestureAction)
        assertEquals(listOf("x"), r.pressed)
    }

    @Test
    fun `长按非上屏动作走手势分发且不上屏`() {
        val r = Recorder()
        val map = mapOf("复制" to KeyAction(action = GestureAction.COPY, label = "复制"))
        dispatchLongPressSelection("复制", map, r.onKeyPress, r.onCommitText, r.onGestureAction)
        assertEquals(listOf(GestureAction.COPY to "复制"), r.gestures)
        assertEquals(emptyList<String>(), r.committed)
    }

    @Test
    fun `长按 action none 由分发吸收且不上屏`() {
        val r = Recorder()
        val map = mapOf("金 钅" to KeyAction(action = GestureAction.NONE, label = "金 钅"))
        dispatchLongPressSelection("金 钅", map, r.onKeyPress, r.onCommitText, r.onGestureAction)
        assertEquals(listOf(GestureAction.NONE to "金 钅"), r.gestures)
        assertEquals(emptyList<String>(), r.committed)
    }

    @Test
    fun `长按选中项未命中映射时按选中文本上屏兜底`() {
        val r = Recorder()
        dispatchLongPressSelection("孤", null, r.onKeyPress, r.onCommitText, r.onGestureAction)
        assertEquals(listOf("孤"), r.committed)
    }

    // ── 九键数字键点按改绑判定（t9DigitTapRebinds）──

    @Test
    fun `九键数字键仅配置 label 时不算改绑`() {
        // 内置 "3": { tap: { label: "DEF" } } 被解析为 SEND_RIME + 空 value，
        // 必须回退内置数字输入，否则点按把 'd' 当编码送进 RIME（beta7 回归）。
        assertFalse(t9DigitTapRebinds(KeyAction(action = GestureAction.SEND_RIME, label = "DEF")))
        assertFalse(t9DigitTapRebinds(null))
        assertFalse(t9DigitTapRebinds(KeyAction(action = null, label = "DEF")))
    }

    @Test
    fun `九键数字键显式配置动作或值时算改绑`() {
        assertTrue(t9DigitTapRebinds(KeyAction(action = GestureAction.COMMIT, value = "，")))
        assertTrue(t9DigitTapRebinds(KeyAction(action = GestureAction.COPY, label = "复制")))
        assertTrue(t9DigitTapRebinds(KeyAction(action = GestureAction.SEND_RIME, value = "abc")))
    }
}