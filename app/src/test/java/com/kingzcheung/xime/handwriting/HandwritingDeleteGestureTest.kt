package com.kingzcheung.xime.handwriting

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 手写页删除键语义单测（issue #1314）：短按一律删已上屏文本，
 * 只有"长按 + 还有笔画可撤"才走撤笔重识别。
 */
class HandwritingDeleteGestureTest {

    @Test
    fun `短按有笔画时也是删文本`() {
        assertEquals(
            HandwritingDeleteGesture.DELETE_TEXT,
            handwritingDeleteGesture(longPress = false, hasStrokes = true),
        )
    }

    @Test
    fun `短按无笔画时删文本`() {
        assertEquals(
            HandwritingDeleteGesture.DELETE_TEXT,
            handwritingDeleteGesture(longPress = false, hasStrokes = false),
        )
    }

    @Test
    fun `长按有笔画时撤笔`() {
        assertEquals(
            HandwritingDeleteGesture.UNDO_STROKE,
            handwritingDeleteGesture(longPress = true, hasStrokes = true),
        )
    }

    @Test
    fun `长按但窗口已清空回退为删文本`() {
        assertEquals(
            HandwritingDeleteGesture.DELETE_TEXT,
            handwritingDeleteGesture(longPress = true, hasStrokes = false),
        )
    }

    @Test
    fun `叠写活动区多个字时退格只删最后一个字`() {
        assertEquals(1, handwritingDeleteCharCount(activeLen = 3, lastSegLen = 1))
        assertEquals(1, handwritingDeleteCharCount(activeLen = 2, lastSegLen = 1))
    }

    @Test
    fun `活动区只有一个字时退格删一个`() {
        assertEquals(1, handwritingDeleteCharCount(activeLen = 1, lastSegLen = 1))
    }

    @Test
    fun `没有活动字时不删字符`() {
        assertEquals(0, handwritingDeleteCharCount(activeLen = 0, lastSegLen = 1))
    }

    @Test
    fun `末段长度缺失或越界时退格按一个字处理`() {
        assertEquals(1, handwritingDeleteCharCount(activeLen = 2, lastSegLen = 0))
        assertEquals(1, handwritingDeleteCharCount(activeLen = 1, lastSegLen = 3))
    }
}
