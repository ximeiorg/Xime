package com.kingzcheung.xime.service

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 输入框内 composing 的光标写入方式实测。
 *
 * 实测结论（ColorOS / Android 15）：
 * - `setComposingText(text, n)` 的 n 挪不动光标：替换 composing 区间后宿主把 Selection
 *   统一放到新文本末尾，n = 1..6 落点都是末尾。所以历史实现依赖这个参数做「编码编辑光标」
 *   从未生效，表现为 composing 时左右滑动移不动光标。
 * - 先 `setComposingText(text, 1)` 建立区间，再 `setSelection()` 才能把光标放到编码内部。
 */
@RunWith(AndroidJUnit4::class)
class ComposingCursorArgDeviceTest {

    private val text = "s s s"

    @Test
    fun setComposingText的参数挪不动光标() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val actual = mutableMapOf<Int, Int>()

        instrumentation.runOnMainSync {
            val editText = newEditText()
            val ic = requireNotNull(editText.onCreateInputConnection(EditorInfo()))
            ic.setComposingText(text, 1)
            for (ncp in 1..6) {
                ic.setComposingText(text, ncp)
                actual[ncp] = editText.selectionStart
            }
        }

        // 固定落点末尾：这是本实现改用 setSelection 的依据，若宿主行为变化此测试会提示
        assertEquals("光标落点：$actual", (1..6).associateWith { text.length }, actual)
    }

    @Test
    fun 写入组合区后再setSelection可以定位到编码内部() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        for (target in 0..text.length) {
            var caret = -1
            instrumentation.runOnMainSync {
                val editText = newEditText()
                val ic = requireNotNull(editText.onCreateInputConnection(EditorInfo()))
                ic.setComposingText(text, 1)
                ic.setSelection(target, target)
                caret = editText.selectionStart
            }
            assertEquals("目标下标 $target 实际落在 $caret", target, caret)
        }
    }

    @Test
    fun 连续左右移动光标时组合区起点保持稳定() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefix = "abc"
        val moves = listOf(4, 3, 2, 1, 0, 1, 2, 3, 4)

        instrumentation.runOnMainSync {
            val editText = newEditText().apply {
                setText(prefix)
                setSelection(prefix.length) // 组合区建在已有文本之后
            }
            val ic = requireNotNull(editText.onCreateInputConnection(EditorInfo()))
            ic.setComposingText(text, 1)

            for (target in moves) {
                // showInputBoxComposition 的实际算法：先写组合区，再按宿主真实回报反推起点
                ic.setComposingText(text, 1)
                val regionEnd = ic.getExtractedText(ExtractedTextRequest(), 0)?.selectionStart ?: -1
                val start = requireNotNull(composingStartFromRegionEnd(regionEnd, text.length)) {
                    "起点推算失败：regionEnd=$regionEnd"
                }
                ic.setSelection(start + target, start + target)

                val actual = ic.getExtractedText(ExtractedTextRequest(), 0)?.selectionStart ?: -1
                assertEquals("target=$target 应落在 ${prefix.length + target}，实际 $actual", prefix.length + target, actual)
            }
        }
    }

    private fun newEditText(): EditText {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return EditText(context).apply { setText("") }
    }
}

/**
 * 探测：`setComposingText(text, 1)` 之后光标是否稳定落在组合区末尾。
 *
 * 若是，组合区起点可以直接用「写完后的光标 − 编码长度」从宿主真实内容反推，
 * 不必在输入法侧维护编码影子状态（编码与上屏文字混在同一段文本里，影子状态会失真）。
 */
@RunWith(AndroidJUnit4::class)
class ComposingCaretAfterWriteProbe {

    private val text = "s s s"
    private val prefix = "abc"

    @Test
    fun 光标在中间时写入后是否回到组合区末尾() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val results = mutableListOf<String>()

        instrumentation.runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val editText = EditText(context).apply {
                setText(prefix)
                setSelection(prefix.length)
            }
            val ic = requireNotNull(editText.onCreateInputConnection(EditorInfo()))
            ic.setComposingText(text, 1)

            for (caretBefore in listOf(8, 7, 6, 3, 0)) {
                ic.setSelection(caretBefore, caretBefore)
                ic.setComposingText(text, 1)
                val after = ic.getExtractedText(ExtractedTextRequest(), 0)?.selectionStart ?: -1
                results.add("before=$caretBefore after=$after")
            }
        }

        // 期望每次都回到组合区末尾（3 + 5 = 8）
        assertEquals("写入后光标落点：${results.joinToString()}", List(5) { 8 }, results.map { it.substringAfter("after=").toInt() })
    }
}
