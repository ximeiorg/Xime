package com.kingzcheung.xime.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联想上下文判定：只有末尾是文字（汉字/字母）时才值得推理。
 *
 * 回归用户反馈「按空格不输入任何内容也会联想」「标点符号也会联想」「数字也会联想」——
 * 这几类上屏不承载语义，必须拦在推理之前。
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
    fun `阿拉伯数字结尾不联想`() {
        listOf("5", "13800138000", "3.14", "版本2", "iPhone 15", "20:30", "２０２６", "１２３").forEach {
            assertFalse("数字结尾[$it]不应触发联想", PredictionManager.isValidPredictionContext(it))
        }
        // 数字出现在中间不影响，只有末尾字符决定是否推理
        assertTrue(PredictionManager.isValidPredictionContext("2026年"))
        assertTrue(PredictionManager.isValidPredictionContext("买了3个苹果"))
    }

    @Test
    fun `字母结尾联想`() {
        assertTrue(PredictionManager.isValidPredictionContext("hello"))
        assertTrue(PredictionManager.isValidPredictionContext("Xime"))
    }

    @Test
    fun `标点后重新输入文字恢复联想`() {
        // 标点只是句断，用户继续打字后上下文重新有效
        assertFalse(PredictionManager.isValidPredictionContext("你好。"))
        assertTrue(PredictionManager.isValidPredictionContext("你好。世界"))
    }
}
