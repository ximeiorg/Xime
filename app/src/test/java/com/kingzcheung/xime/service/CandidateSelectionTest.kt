package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `select_candidate` 动作的候选解算：序号有候选才选中，否则退化为空格键语义。
 */
class CandidateSelectionTest {

    @Test
    fun `组合态下序号在候选范围内返回该索引`() {
        assertEquals(0, candidateIndexForSelection(0, isComposing = true, candidateCount = 3))
        assertEquals(1, candidateIndexForSelection(1, isComposing = true, candidateCount = 3))
        assertEquals(2, candidateIndexForSelection(2, isComposing = true, candidateCount = 3))
    }

    @Test
    fun `序号超出候选数量返回 null 由调用方退化为空格`() {
        assertNull(candidateIndexForSelection(2, isComposing = true, candidateCount = 1))
        assertNull(candidateIndexForSelection(9, isComposing = true, candidateCount = 3))
    }

    @Test
    fun `非组合态无论有无残留候选都不选词`() {
        assertNull(candidateIndexForSelection(0, isComposing = false, candidateCount = 3))
        // 候选栏残留但组合已结束（如刚上屏）：仍按空格处理，避免选中过期候选
        assertNull(candidateIndexForSelection(1, isComposing = false, candidateCount = 0))
    }

    @Test
    fun `组合态但没有候选返回 null`() {
        assertNull(candidateIndexForSelection(0, isComposing = true, candidateCount = 0))
    }

    @Test
    fun `非法序号返回 null`() {
        assertNull(candidateIndexForSelection(-1, isComposing = true, candidateCount = 3))
        assertNull(candidateIndexForSelection(-5, isComposing = true, candidateCount = 3))
    }
}
