package com.kingzcheung.xime.service

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `异步推理在切换到数字面板后迟到返回不会回填残留联想`() {
        val mainDispatcher = StandardTestDispatcher()
        runTest(mainDispatcher) {
            Dispatchers.setMain(mainDispatcher)
            try {
                val gate = CompletableDeferred<List<String>?>()
                val results = mutableListOf<List<String>>()
                val manager = PredictionManager(
                    context = mock(Context::class.java),
                    serviceScope = this,
                    onPredictionResult = { results += it },
                    isSmartPredictionEnabled = { true },
                    predictor = { _, _ -> gate.await() },
                )

                // 上屏「你的」发起异步推理（尚未返回）
                manager.appendCommittedText("你的")
                manager.getPrediction(manager.lastCommittedText)
                mainDispatcher.scheduler.runCurrent()

                // 切到 ?123 面板或输入数字时作废在途推理，并追加字面数字上下文
                manager.invalidatePendingPredictions()
                manager.appendCommittedText("1")

                // 迟到的旧推理结果返回：因代际已过期，不得回填候选栏
                gate.complete(listOf("人", "时候", "一"))
                mainDispatcher.scheduler.advanceUntilIdle()

                assertTrue("过期推理结果不应回填联想候选", results.isEmpty())
                // 数字面板内写入的数字已同步到上下文，切回中文后末尾仍为数字，不会误用旧上下文推理
                assertFalse(PredictionManager.isValidPredictionContext(manager.lastCommittedText))
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `抑制当前提交会清空联想并作废在途推理且不影响下次提交`() {
        val mainDispatcher = StandardTestDispatcher()
        runTest(mainDispatcher) {
            Dispatchers.setMain(mainDispatcher)
            try {
                val gate = CompletableDeferred<List<String>?>()
                var latest: List<String> = listOf("残留")
                val manager = PredictionManager(
                    context = mock(Context::class.java),
                    serviceScope = this,
                    onPredictionResult = { latest = it },
                    isSmartPredictionEnabled = { true },
                    predictor = { text, _ ->
                        when (text) {
                            "你的" -> gate.await()
                            "你的朋友" -> listOf("来了")
                            else -> error("不应推理被抑制的提交")
                        }
                    },
                )

                manager.getPrediction("你的")
                mainDispatcher.scheduler.runCurrent()

                manager.getPrediction("你的人", suppressPrediction = true)
                assertEquals(emptyList<String>(), latest)

                // 上一轮在途推理迟到完成，不得覆盖已清空的候选栏
                gate.complete(listOf("旧联想"))
                mainDispatcher.scheduler.advanceUntilIdle()
                assertEquals(emptyList<String>(), latest)

                manager.getPrediction("你的朋友")
                mainDispatcher.scheduler.advanceUntilIdle()
                assertEquals(listOf("来了"), latest)
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    @Test
    fun `上滑数字进入字面上屏路由而点按保留原路由`() {
        for (digit in '0'..'9') {
            assertTrue(ImeKeyRouter.isLiteralSwipeDigit(digit.toString(), isSwipe = true))
            assertFalse(ImeKeyRouter.isLiteralSwipeDigit(digit.toString(), isSwipe = false))
        }
        assertFalse(ImeKeyRouter.isLiteralSwipeDigit("，", isSwipe = true))
    }

    @Test
    fun `上滑联想开关控制非数字手势顶字上屏后的推理抑制`() {
        // 开关关闭（默认）：组合态上滑标点顶字应抑制本轮联想推理
        assertTrue(
            ImeKeyRouter.shouldSuppressSwipeSelectAssociation(
                isSwipe = true,
                wasComposing = true,
                swipeSelectAssociationEnabled = false,
            )
        )
        // 开关开启：组合态上滑标点顶字正常触发联想推理（不抑制）
        assertFalse(
            ImeKeyRouter.shouldSuppressSwipeSelectAssociation(
                isSwipe = true,
                wasComposing = true,
                swipeSelectAssociationEnabled = true,
            )
        )
        // 普通点按（如 5 行键盘点按数字选词 / 字母顶屏）：不受上滑开关影响
        assertFalse(
            ImeKeyRouter.shouldSuppressSwipeSelectAssociation(
                isSwipe = false,
                wasComposing = true,
                swipeSelectAssociationEnabled = false,
            )
        )
        // 空闲态上滑字面符号：本身由 isValidPredictionContext 过滤并清空，不提前吞抑制位
        assertFalse(
            ImeKeyRouter.shouldSuppressSwipeSelectAssociation(
                isSwipe = true,
                wasComposing = false,
                swipeSelectAssociationEnabled = false,
            )
        )
    }
}
