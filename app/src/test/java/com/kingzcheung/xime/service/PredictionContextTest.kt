package com.kingzcheung.xime.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联想上下文判定：只有末尾是文字/数字时才值得推理。
 *
 * 回归用户反馈「按空格不输入任何内容也会联想」「标点符号也会联想」——
 * 这两类上屏不承载语义，必须拦在推理之前。
 */
class PredictionContextTest {

    @Test
    fun `空上下文不联想`() {
        assertFalse(PredictionManager.isValidPredictionContext(""))
    }

    @Test
    fun `纯空白不联想`() {
        assertFalse(PredictionManager.isValidPredictionContext(" "))
        assertFalse(PredictionManager.isValidPredictionContext("   "))
        assertFalse(PredictionManager.isValidPredictionContext("\n"))
        assertFalse(PredictionManager.isValidPredictionContext("\u3000"))
    }

    @Test
    fun `末尾空白不联想`() {
        assertFalse(PredictionManager.isValidPredictionContext("你好 "))
        assertFalse(PredictionManager.isValidPredictionContext("hello "))
    }

    @Test
    fun `中英文标点不联想`() {
        listOf("。", "，", "、", "；", "：", "？", "！", "…", "——", "\"", "''", "《》").forEach {
            assertFalse("标点[$it]不应触发联想", PredictionManager.isValidPredictionContext(it))
        }
        assertFalse(PredictionManager.isValidPredictionContext("你好，"))
        assertFalse(PredictionManager.isValidPredictionContext("Hello."))
    }

    @Test
    fun `表情不联想`() {
        assertFalse(PredictionManager.isValidPredictionContext("😊"))
        assertFalse(PredictionManager.isValidPredictionContext("好的😊"))
    }

    @Test
    fun `汉字结尾联想`() {
        assertTrue(PredictionManager.isValidPredictionContext("你好"))
        assertTrue(PredictionManager.isValidPredictionContext("今天天气不错"))
    }

    @Test
    fun `字母数字结尾联想`() {
        assertTrue(PredictionManager.isValidPredictionContext("hello"))
        assertTrue(PredictionManager.isValidPredictionContext("iPhone 15"))
        assertTrue(PredictionManager.isValidPredictionContext("版本2"))
    }

    @Test
    fun `标点后重新输入文字恢复联想`() {
        // 标点只是句断，用户继续打字后上下文重新有效
        assertFalse(PredictionManager.isValidPredictionContext("你好。"))
        assertTrue(PredictionManager.isValidPredictionContext("你好。世界"))
    }
}
