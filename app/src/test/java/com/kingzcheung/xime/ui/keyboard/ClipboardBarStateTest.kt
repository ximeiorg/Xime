package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.data.db.ClipboardEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板候选栏状态测试（纯 JVM）。
 *
 * 重点锁定**索引对齐不变式**：`ClipboardDisplay.candidates` 与 `images` 必须等长同序，
 * 因为候选栏点选回调只传 index，服务层用该 index 去 `recentClipboardItemsState` 取条目——
 * 一旦错位就会"点图片上屏了文本"（或反之）。
 */
class ClipboardBarStateTest {

    private fun image(id: Long, timestamp: Long = id) = ClipboardItem(
        id = id,
        text = "",
        timestamp = timestamp,
        type = ClipboardEntry.TYPE_IMAGE,
        imagePath = "clipboard_images/$id.png",
        imageHash = "hash$id",
        sizeBytes = 1024,
    )

    private fun text(id: Long, value: String, timestamp: Long = id) = ClipboardItem(
        id = id,
        text = value,
        timestamp = timestamp,
    )

    /** 复刻服务层：candidates 取各条目的 text（图片为空串），images 取图片位。 */
    private fun itemsOf(items: List<ClipboardItem>): Pair<List<String>, List<ClipboardItem?>> =
        items.map { it.text } to items.map { if (it.isImage) it else null }

    @Test
    fun `剪贴板态下图片与文本混排且索引对齐`() {
        val items = listOf(text(1, "你好"), image(2), text(3, "world"), image(4))
        val (candidates, images) = itemsOf(items)

        val state = CandidateBarState.from(
            candidates = candidates,
            candidateComments = emptyList(),
            inputText = "",
            isComposing = false,
            associationCandidates = emptyList(),
            isShowingRecentClipboard = true,
            hasNextPage = false,
            clipboardImages = images,
        )

        assertTrue(state is CandidateBarState.ClipboardDisplay)
        val display = state as CandidateBarState.ClipboardDisplay
        assertEquals(listOf("你好", "", "world", ""), display.candidates)
        assertEquals(4, display.images.size)
        assertNull(display.images[0])
        assertEquals(2L, display.images[1]?.id)
        assertNull(display.images[2])
        assertEquals(4L, display.images[3]?.id)
        assertEquals(candidates.size, display.images.size)
    }

    @Test
    fun `全部为图片时仍进入剪贴板态（空文本占位不为空列表）`() {
        val items = listOf(image(1), image(2))
        val (candidates, images) = itemsOf(items)

        val state = CandidateBarState.from(
            candidates = candidates,
            candidateComments = emptyList(),
            inputText = "",
            isComposing = false,
            associationCandidates = emptyList(),
            isShowingRecentClipboard = true,
            hasNextPage = false,
            clipboardImages = images,
        )

        val display = state as CandidateBarState.ClipboardDisplay
        assertEquals(listOf("", ""), display.candidates)
        assertEquals(listOf(1L, 2L), display.images.map { it?.id })
    }

    @Test
    fun `非剪贴板态忽略图片参数`() {
        val state = CandidateBarState.from(
            candidates = listOf("候", "选"),
            candidateComments = emptyList(),
            inputText = "hou",
            isComposing = true,
            associationCandidates = emptyList(),
            isShowingRecentClipboard = false,
            hasNextPage = false,
            clipboardImages = listOf(image(9), null),
        )

        // 打字态走 ChineseCandidates，图片列表不参与渲染
        assertTrue(state is CandidateBarState.ChineseCandidates)
        assertEquals(listOf("候", "选"), (state as CandidateBarState.ChineseCandidates).candidates)
    }

    @Test
    fun `默认不传图片时图片位全为空`() {
        val state = CandidateBarState.from(
            candidates = listOf("仅文本"),
            candidateComments = emptyList(),
            inputText = "",
            isComposing = false,
            associationCandidates = emptyList(),
            isShowingRecentClipboard = true,
            hasNextPage = false,
        )
        assertTrue((state as CandidateBarState.ClipboardDisplay).images.isEmpty())
    }
}