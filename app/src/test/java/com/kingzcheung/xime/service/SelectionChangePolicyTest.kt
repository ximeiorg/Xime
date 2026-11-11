package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SelectionChangePolicy 决策：
 * - 用户移动光标 → 打断待上屏组合态（避免旧编码与新输入合并上屏到错误位置）
 * - 选区未变化 / 光标仍在 composing 区域内（自身写入）/ 无效选区信息 → 不打断
 */
class SelectionChangePolicyTest {

    private fun shouldBreak(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int = -1,
        candidatesEnd: Int = -1,
    ): Boolean = shouldBreakCompositionOnSelectionChange(
        oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
    )

    @Test
    fun `选区未变化不打断`() {
        assertFalse(shouldBreak(3, 3, 3, 3))
    }

    @Test
    fun `光标移到新位置应打断_无composing区域`() {
        assertTrue(shouldBreak(5, 5, 2, 2))
    }

    @Test
    fun `光标落在composing区域末尾不打断_input框模式自身写入`() {
        // setComposingText 写入编码后光标紧跟编码末尾（10 = candidatesEnd）
        assertFalse(shouldBreak(4, 4, 10, 10, candidatesStart = 3, candidatesEnd = 10))
    }

    @Test
    fun `光标在composing区域中间不打断_编码编辑光标`() {
        assertFalse(shouldBreak(10, 10, 5, 5, candidatesStart = 3, candidatesEnd = 10))
    }

    @Test
    fun `光标移出composing区域应打断_用户点击别处`() {
        assertTrue(shouldBreak(10, 10, 1, 1, candidatesStart = 3, candidatesEnd = 10))
    }

    @Test
    fun `拖动选区应打断`() {
        assertTrue(shouldBreak(8, 8, 2, 6))
    }

    @Test
    fun `无有效选区信息不打断`() {
        assertFalse(shouldBreak(-1, -1, -1, -1))
        assertFalse(shouldBreak(3, 3, -1, -1))
    }

    private fun removalCaret(
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ): Int? = restoreCaretAfterInputBoxComposingRemoval(
        newSelStart, newSelEnd, candidatesStart, candidatesEnd
    )

    @Test
    fun `编码在光标之后_删除后光标保持原位`() {
        // 文本：你好[g j]，光标点在句首 （1 < 编码起点 2）
        assertEquals(1, removalCaret(1, 1, 2, 5))
    }

    @Test
    fun `编码在光标之前_删除后光标前移编码长度`() {
        // 文本：[g j]你好，光标点在字后 （4 > 编码末尾 3）
        assertEquals(1, removalCaret(4, 4, 0, 3))
    }

    @Test
    fun `光标紧邻编码两端_恢复到删除后的等效位置`() {
        assertEquals(2, removalCaret(2, 2, 2, 5))
        assertEquals(2, removalCaret(5, 5, 2, 5))
    }

    @Test
    fun `光标仍在编码内不删除`() {
        assertNull(removalCaret(3, 3, 2, 5))
    }

    @Test
    fun `拖动选区不删除编码`() {
        assertNull(removalCaret(1, 4, 2, 5))
    }

    @Test
    fun `无组合区不删除`() {
        assertNull(removalCaret(1, 1, -1, -1))
        assertNull(removalCaret(1, 1, 5, 5))
    }
}

/**
 * 选区回调重入闸门：外层放行、处理中（自身写入触发的同步回调）拒绝、退出后恢复。
 * 回归 StackOverflowError——onUpdateSelection 写 InputConnection 后宿主同步回调 updateSelection。
 */
class SelectionUpdateGateTest {

    @Test
    fun `首次进入放行`() {
        assertTrue(SelectionUpdateGate().tryEnter())
    }

    @Test
    fun `处理中再次进入被拒_屏蔽自触发回调`() {
        val gate = SelectionUpdateGate()
        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())
        assertFalse(gate.tryEnter())
    }

    @Test
    fun `退出后可再次进入`() {
        val gate = SelectionUpdateGate()
        assertTrue(gate.tryEnter())
        gate.exit()
        assertTrue(gate.tryEnter())
    }
}

/**
 * 自身写入引起的光标位移识别。
 *
 * 回归 issue #1121 真机案例：五笔满码唯一上屏时，第 5 个键「上屏上一段首选 + 以该字母开新组合」，
 * 上屏引起的 onUpdateSelection（光标 +1）被误当成用户移动光标，把刚建立的组合态丢弃
 * —— 表现为第五码打不进候选框。
 */
class SelfWriteCaretGuardTest {

    private val window = 800L

    @Test
    fun `上屏一个字符后的一次回调被识别为自身写入`() {
        val guard = SelfWriteCaretGuard(window)
        guard.noteWrite(1, 1_000)
        assertTrue(guard.consumedBySelfWrite(3, 4, 1_020))
    }

    @Test
    fun `命中后不会重复消费`() {
        val guard = SelfWriteCaretGuard(window)
        guard.noteWrite(1, 1_000)
        assertTrue(guard.consumedBySelfWrite(3, 4, 1_020))
        // 之后的光标移动（即使位移相同）都是真实移动
        assertFalse(guard.consumedBySelfWrite(4, 5, 1_030))
    }

    @Test
    fun `多次写入合并成一次回调`() {
        val guard = SelfWriteCaretGuard(window)
        guard.noteWrite(1, 1_000)
        guard.noteWrite(1, 1_010)
        assertTrue(guard.consumedBySelfWrite(3, 5, 1_020))
    }

    @Test
    fun `多次写入分批回调逐个消费`() {
        val guard = SelfWriteCaretGuard(window)
        guard.noteWrite(2, 1_000)
        assertTrue(guard.consumedBySelfWrite(3, 4, 1_010))
        assertTrue(guard.consumedBySelfWrite(4, 5, 1_020))
        assertFalse(guard.consumedBySelfWrite(5, 6, 1_030))
    }

    @Test
    fun `删除的同向负位移被识别`() {
        val guard = SelfWriteCaretGuard(window)
        guard.noteWrite(-1, 1_000)
        assertTrue(guard.consumedBySelfWrite(4, 3, 1_010))
    }

    @Test
    fun `方向相反视为真实移动`() {
        val guard = SelfWriteCaretGuard(window)
        guard.noteWrite(1, 1_000)
        // 超出「写入后极短窗口」（120ms）后，方向相反即认定用户移动光标
        assertFalse(guard.consumedBySelfWrite(4, 3, 1_200))
        // 假设被打破后清空预期，不再吞掉后续真实移动
        assertFalse(guard.consumedBySelfWrite(3, 4, 1_020))
    }

    @Test
    fun `位移超出预期视为真实移动`() {
        val guard = SelfWriteCaretGuard(window)
        guard.noteWrite(1, 1_000)
        assertFalse(guard.consumedBySelfWrite(2, 5, 1_200))
    }

    @Test
    fun `超出时间窗不再算自身写入`() {
        val guard = SelfWriteCaretGuard(window)
        guard.noteWrite(1, 1_000)
        assertFalse(guard.consumedBySelfWrite(3, 4, 1_000 + window + 1))
    }

    @Test
    fun `无写入记录时一律视为真实移动`() {
        val guard = SelfWriteCaretGuard(window)
        assertFalse(guard.consumedBySelfWrite(3, 4, 1_000))
    }

    @Test
    fun `会话重置后清空预期`() {
        val guard = SelfWriteCaretGuard(window)
        guard.noteWrite(1, 1_000)
        guard.reset()
        assertFalse(guard.consumedBySelfWrite(3, 4, 1_010))
    }
}

/** 宿主没给可用旧光标位置时：无法算位移，按自身写入处理，保留组合态。 */
class SelfWriteCaretGuardUnknownOldSelectionTest {

    @Test
    fun `旧光标为负时按自身写入处理`() {
        val guard = SelfWriteCaretGuard(350L)
        guard.noteWrite(1, 1_000)
        assertTrue(guard.consumedBySelfWrite(-1, 4, 1_010))
    }

    @Test
    fun `旧光标为负但没有待处理写入时仍视为真实移动`() {
        val guard = SelfWriteCaretGuard(350L)
        assertFalse(guard.consumedBySelfWrite(-1, 4, 1_010))
    }
}

/** 位移对不上预期时，写入后极短时间内的回调仍按自身写入处理（防宿主合并/规范化位移）。 */
class SelfWriteCaretGuardRecentWriteTest {

    @Test
    fun `极短窗口内位移对不上也算自身写入`() {
        val guard = SelfWriteCaretGuard(800L, 120L)
        guard.noteWrite(1, 1_000)
        assertTrue(guard.consumedBySelfWrite(3, 6, 1_030))
    }

    @Test
    fun `超出极短窗口且位移对不上则视为真实移动`() {
        val guard = SelfWriteCaretGuard(800L, 120L)
        guard.noteWrite(1, 1_000)
        assertFalse(guard.consumedBySelfWrite(3, 6, 1_200))
    }

    @Test
    fun `迟到但仍对得上预期的回调也算自身写入`() {
        val guard = SelfWriteCaretGuard(800L, 120L)
        guard.noteWrite(2, 1_000)
        assertTrue(guard.consumedBySelfWrite(3, 5, 1_700))
    }
}

/**
 * 组合区起点推算：刚写完的光标（组合区末尾）− 编码长度。
 *
 * 编码与上屏文字混在同一段文本里，只能按宿主真实回报反推，不做影子状态。
 */
class ComposingStartFromRegionEndTest {

    @Test
    fun `编码接在上屏文字之后`() {
        // 上屏 "abc" + 编码 "s s s"，写完光标在 8，编码长 5 → 起点 3
        assertEquals(3, composingStartFromRegionEnd(8, 5))
    }

    @Test
    fun `编码在文本开头`() {
        assertEquals(0, composingStartFromRegionEnd(5, 5))
    }

    @Test
    fun `空编码时起点就是光标`() {
        assertEquals(7, composingStartFromRegionEnd(7, 0))
    }

    @Test
    fun `宿主未回报光标或读数不自洽时返回空`() {
        assertNull(composingStartFromRegionEnd(-1, 5))
        // 光标比编码还靠前：说明宿主内容与本次写入已失同步，宁可不动光标
        assertNull(composingStartFromRegionEnd(2, 5))
    }
}
