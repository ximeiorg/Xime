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
}
