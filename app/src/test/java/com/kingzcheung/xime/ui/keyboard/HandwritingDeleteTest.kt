package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #1314：手写模式下按删除键应直接删除已上屏单字，而非回退单笔并触发重识别；
 * 删除或清窗后，在途的旧识别回包必须被丢弃，防止迟到结果覆写文本。
 */
class HandwritingDeleteTest {

    @Test
    fun singleActiveCharDeletesOneCharAndClearsState() {
        val transition = resolveHandwritingDelete(tail = "中", activeLen = 1)
        assertEquals(
            HandwritingDeleteTransition(
                deleteCharsCount = 1,
                nextTail = "",
                nextActiveLen = 0,
                forwardSystemDelete = false,
            ),
            transition,
        )
    }

    @Test
    fun settledTailDeletesLastCharOnly() {
        val transition = resolveHandwritingDelete(tail = "你好", activeLen = 0)
        assertEquals(
            HandwritingDeleteTransition(
                deleteCharsCount = 1,
                nextTail = "你",
                nextActiveLen = 0,
                forwardSystemDelete = false,
            ),
            transition,
        )
    }

    @Test
    fun overlappedActiveWindowDeletesSingleCharNotEntireWindow() {
        val first = resolveHandwritingDelete(tail = "你好", activeLen = 2)
        assertEquals(
            HandwritingDeleteTransition(
                deleteCharsCount = 1,
                nextTail = "你",
                nextActiveLen = 0,
                forwardSystemDelete = false,
            ),
            first,
        )

        val second = resolveHandwritingDelete(tail = first.nextTail, activeLen = first.nextActiveLen)
        assertEquals(
            HandwritingDeleteTransition(
                deleteCharsCount = 1,
                nextTail = "",
                nextActiveLen = 0,
                forwardSystemDelete = false,
            ),
            second,
        )

        val third = resolveHandwritingDelete(tail = second.nextTail, activeLen = second.nextActiveLen)
        assertEquals(
            HandwritingDeleteTransition(
                deleteCharsCount = 0,
                nextTail = "",
                nextActiveLen = 0,
                forwardSystemDelete = true,
            ),
            third,
        )
    }

    @Test
    fun supplementaryCjkCharDeletesFullSurrogatePair() {
        val extB = "\uD840\uDC00" // U+20000 𠀀
        val transition = resolveHandwritingDelete(tail = "你$extB", activeLen = 3)
        assertEquals(
            HandwritingDeleteTransition(
                deleteCharsCount = 2,
                nextTail = "你",
                nextActiveLen = 0,
                forwardSystemDelete = false,
            ),
            transition,
        )
    }

    @Test
    fun emptyTailForwardsSystemDeleteEvenWithStaleActiveLen() {
        val expected = HandwritingDeleteTransition(
            deleteCharsCount = 0,
            nextTail = "",
            nextActiveLen = 0,
            forwardSystemDelete = true,
        )
        assertEquals(expected, resolveHandwritingDelete(tail = "", activeLen = 0))
        assertEquals(expected, resolveHandwritingDelete(tail = "", activeLen = 2))
    }

    @Test
    fun recognitionGateAcceptsCurrentGenerationWithActiveStrokes() {
        assertTrue(
            shouldApplyHandwritingRecognition(
                requestGeneration = 3,
                currentGeneration = 3,
                activeStrokeCount = 2,
            )
        )
    }

    @Test
    fun recognitionGateRejectsStaleGenerationOrEmptyActiveStrokes() {
        assertFalse(
            shouldApplyHandwritingRecognition(
                requestGeneration = 3,
                currentGeneration = 4,
                activeStrokeCount = 2,
            )
        )
        assertFalse(
            shouldApplyHandwritingRecognition(
                requestGeneration = 3,
                currentGeneration = 3,
                activeStrokeCount = 0,
            )
        )
    }

    /** 模拟服务端 replaceBefore：光标前恰为 expected 才替换。 */
    private class FakeEditor(var text: String) {
        fun replaceTail(newTail: String, expectedTail: String): Boolean {
            if (!text.endsWith(expectedTail)) return false
            text = text.dropLast(expectedTail.length) + newTail
            return true
        }
    }

    @Test
    fun supplementaryCharThenSpaceThenDeleteRemovesOnlyTheSpace() {
        val editor = FakeEditor("\uD840\uDC00 ")
        val transition = applyHandwritingDelete(tail = "\uD840\uDC00") { newTail, expected ->
            editor.replaceTail(newTail, expected)
        }
        assertTrue(transition.forwardSystemDelete)
        assertEquals("", transition.nextTail)
        assertEquals("\uD840\uDC00 ", editor.text)
    }

    @Test
    fun supplementaryCharThenPunctuationThenDeleteRemovesOnlyThePunctuation() {
        val editor = FakeEditor("\uD840\uDC00，")
        val transition = applyHandwritingDelete(tail = "\uD840\uDC00") { newTail, expected ->
            editor.replaceTail(newTail, expected)
        }
        assertTrue(transition.forwardSystemDelete)
        assertEquals("", transition.nextTail)
        assertEquals("\uD840\uDC00，", editor.text)
    }

    @Test
    fun supplementaryCharDeletesWholeCodePointWhenTailMatchesCursor() {
        val editor = FakeEditor("a\uD840\uDC00")
        val transition = applyHandwritingDelete(tail = "\uD840\uDC00") { newTail, expected ->
            editor.replaceTail(newTail, expected)
        }
        assertFalse(transition.forwardSystemDelete)
        assertEquals("", transition.nextTail)
        assertEquals("a", editor.text)
    }
}
