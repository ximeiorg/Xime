package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** 英文键盘键帽文案：见 [KeysConfigHelper.asciiKeyFaceText]。 */
class KeyFaceTextTest {

    @Test
    fun `自定义 label 与提交值不同时显示 label`() {
        // qqq: { label: "123", value: "mode_change_number" }
        assertEquals(
            "123",
            KeysConfigHelper.asciiKeyFaceText(
                tapLabel = "123",
                rawCommitValue = "mode_change_number",
                isShifted = false,
            )
        )
    }

    @Test
    fun `字母键仍用提交值以跟随 shift`() {
        // 内置英文键盘 tap: "q" —— label 与提交值同字，不能把 "q" 当自定义文案
        assertEquals(
            "q",
            KeysConfigHelper.asciiKeyFaceText("q", "q", isShifted = false)
        )
        assertEquals(
            "Q",
            KeysConfigHelper.asciiKeyFaceText("q", "q", isShifted = true)
        )
    }

    @Test
    fun `无 label 时用提交值`() {
        assertEquals(
            "q",
            KeysConfigHelper.asciiKeyFaceText(null, "q", isShifted = false)
        )
        assertEquals(
            "Q",
            KeysConfigHelper.asciiKeyFaceText("", "q", isShifted = true)
        )
    }

    @Test
    fun `label 与提交值仅大小写不同时仍用提交值`() {
        assertEquals(
            "a",
            KeysConfigHelper.asciiKeyFaceText("A", "a", isShifted = false)
        )
        assertEquals(
            "A",
            KeysConfigHelper.asciiKeyFaceText("a", "a", isShifted = true)
        )
    }

    @Test
    fun `合并键 label 与代表字母不同时显示 label`() {
        // qw: { label: "qw", value: "q" }
        assertEquals(
            "qw",
            KeysConfigHelper.asciiKeyFaceText("qw", "q", isShifted = false)
        )
    }
}
