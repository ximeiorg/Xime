package com.kingzcheung.xime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class KeyActionRegistryTest {

    @Test
    fun `所有既有 action 取值均可查询且回指对应枚举`() {
        for (gesture in GestureAction.entries) {
            val action = KeyActionRegistry.fromId(gesture.value)
            assertNotNull("action ${gesture.value} 应可查询", action)
            assertEquals(gesture, action!!.action)
        }
    }

    @Test
    fun `未知 action 取值返回 null`() {
        assertNull(KeyActionRegistry.fromId("no_such_action"))
        assertNull(KeyActionRegistry.fromId(""))
    }

    @Test
    fun `动作执行域划分正确`() {
        assertEquals(ActionDomain.SERVICE, KeyActionRegistry.fromId("commit")!!.domain)
        assertEquals(ActionDomain.SERVICE, KeyActionRegistry.fromId("enter")!!.domain)
        assertEquals(ActionDomain.UI, KeyActionRegistry.fromId("toggle_symbols")!!.domain)
        assertEquals(ActionDomain.UI, KeyActionRegistry.fromId("switch_route")!!.domain)
        assertEquals(ActionDomain.CROSS, KeyActionRegistry.fromId("delete")!!.domain)
        assertEquals(ActionDomain.CROSS, KeyActionRegistry.fromId("toggle_ascii")!!.domain)
        assertEquals(ActionDomain.SERVICE, KeyActionRegistry.fromId("cycle_schema")!!.domain)
        assertEquals(ActionDomain.SERVICE, KeyActionRegistry.fromId("repeat_space")!!.domain)
    }

    @Test
    fun `cycle_schema 交给服务层轮换方案`() {
        val executor = RecordingExecutor()
        KeyActionRegistry.execute(GestureAction.CYCLE_SCHEMA, KeyActionContext(executor), "")
        assertEquals(1, executor.schemaCycles)
    }

    @Test
    fun `repeat_space 按 value 次数分发空格`() {
        val executor = RecordingExecutor()
        KeyActionRegistry.execute(GestureAction.REPEAT_SPACE, KeyActionContext(executor), "3")
        assertEquals(listOf("space", "space", "space"), executor.dispatched)

        val defaulted = RecordingExecutor()
        KeyActionRegistry.execute(GestureAction.REPEAT_SPACE, KeyActionContext(defaulted), "")
        assertEquals(5, defaulted.dispatched.size)
    }

    @Test
    fun `newline 固定分发换行键且不携带 value`() {
        assertEquals(ActionDomain.SERVICE, KeyActionRegistry.fromId("newline")!!.domain)
        val executor = RecordingExecutor()
        KeyActionRegistry.execute(GestureAction.NEWLINE, KeyActionContext(executor), "")
        assertEquals(listOf("newline"), executor.dispatched)
    }

    @Test
    fun `光标动作按方向分发 moveCursor`() {
        assertEquals(ActionDomain.SERVICE, KeyActionRegistry.fromId("cursor_left")!!.domain)
        assertEquals(ActionDomain.SERVICE, KeyActionRegistry.fromId("cursor_right")!!.domain)
        val executor = RecordingExecutor()
        KeyActionRegistry.execute(GestureAction.CURSOR_LEFT, KeyActionContext(executor), "")
        KeyActionRegistry.execute(GestureAction.CURSOR_RIGHT, KeyActionContext(executor), "")
        assertEquals(listOf(-1, 1), executor.cursorMoves)
    }

    @Test
    fun `command 动作由服务域兜底执行`() {
        val executor = RecordingExecutor()
        KeyActionRegistry.execute(GestureAction.COMMAND, KeyActionContext(executor), "clear_composition")
        assertEquals(listOf("clear_composition"), executor.commands)
    }

    @Test
    fun `GestureAction fromValue 兼容既有取值`() {
        assertEquals(GestureAction.COPY, GestureAction.fromValue("copy"))
        assertEquals(GestureAction.SEND_RIME, GestureAction.fromValue("send_rime"))
        assertEquals(GestureAction.COMMAND, GestureAction.fromValue("command"))
        assertNull(GestureAction.fromValue("unknown_value"))
    }

    private class RecordingExecutor : ActionExecutor {
        val commands = mutableListOf<String>()
        val dispatched = mutableListOf<String>()
        val cursorMoves = mutableListOf<Int>()
        var schemaCycles = 0

        override fun commitText(text: String) = Unit
        override fun performEditorMenuAction(actionId: Int) = Unit
        override fun sendKeyEvent(keyCode: Int) = Unit
        override fun moveCursor(direction: Int) {
            cursorMoves.add(direction)
        }
        override fun executeCommand(name: String) {
            commands.add(name)
        }
        override fun repeatLastInput() = Unit
        override fun dispatchKey(key: String) {
            dispatched.add(key)
        }
        override fun cycleSchema() {
            schemaCycles++
        }
    }
}