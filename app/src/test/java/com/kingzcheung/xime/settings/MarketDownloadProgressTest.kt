package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketDownloadProgressTest {

    /** 记录每次进度回调，并按 (downloaded/total) 还原成比例。 */
    private class Recorder {
        val ratios = mutableListOf<Double>()
        val sizes = mutableListOf<Pair<Long, Long>>()

        fun progress(): (Long, Long) -> Unit = { done, total ->
            sizes.add(done to total)
            ratios.add(done.toDouble() / total)
        }
    }

    /**
     * 万象拼音标准版真实数据：索引 `sizeBytes` 对语法模型写的是 123,026,414，
     * 实际文件 418,071,596。修复前按声明总量算会跑到 298%。
     */
    @Test
    fun `索引体积过期时进度不超过 100 percent`() {
        val declared = listOf(parseSizeText("24.4 MB")!!, 123_026_414L)
        val actual = listOf(25_586_596L, 418_071_596L)
        val recorder = Recorder()
        val progress = MarketDownloadProgress(declared, recorder.progress())

        for (index in actual.indices) {
            progress.startFile(index)
            var read = 0L
            while (read < actual[index]) {
                read = minOf(read + 8L * 1024 * 1024, actual[index])
                progress.onBytes(read, actual[index])
            }
            progress.finishFile()
        }
        progress.complete()

        assertTrue("进度不得越界: ${recorder.ratios.max()}", recorder.ratios.all { it <= 1.0 })
        assertTrue("进度不得倒退", recorder.ratios.zipWithNext().all { (a, b) -> b >= a - 1e-9 })
        assertEquals(1.0, recorder.ratios.last(), 1e-9)
        // 修正后的总量 = 两个文件的真实长度之和
        assertEquals(25_586_596L + 418_071_596L, recorder.sizes.last().second)
    }

    /** 索引完全没给大小时（声明 0），也只能用真实长度把总量撑起来，不能超过 100%。 */
    @Test
    fun `声明缺失时按真实长度扩展总量`() {
        val recorder = Recorder()
        val progress = MarketDownloadProgress(listOf(0L), recorder.progress())

        progress.startFile(0)
        progress.onBytes(1024, 4096)
        progress.onBytes(4096, 4096)
        progress.finishFile()

        assertEquals(listOf(0.25, 1.0), recorder.ratios)
        assertEquals(4096L, recorder.sizes.last().second)
    }

    /** 声明偏大（文本估算往上取整）时，收尾补一次 100%，中途不越过 1。 */
    @Test
    fun `声明偏大时收尾补满`() {
        val recorder = Recorder()
        val declared = listOf(100L * 1024 * 1024, 100L * 1024 * 1024)
        val progress = MarketDownloadProgress(declared, recorder.progress())

        progress.startFile(0)
        progress.onBytes(60L * 1024 * 1024, 60L * 1024 * 1024)
        progress.finishFile()
        progress.startFile(1)
        progress.onBytes(100L * 1024 * 1024, 100L * 1024 * 1024)
        progress.finishFile()
        val beforeComplete = recorder.ratios.last()
        progress.complete()

        assertEquals(1.0, beforeComplete, 1e-9)
        assertEquals(1.0, recorder.ratios.last(), 1e-9)
        assertTrue("不得越界", recorder.ratios.all { it <= 1.0 })
    }

    /** 单个文件内不会因为 read 超过 contentLength 而越界。 */
    @Test
    fun `读取量大于声明时夹在 100 percent`() {
        val recorder = Recorder()
        val progress = MarketDownloadProgress(listOf(1000L), recorder.progress())

        progress.startFile(0)
        progress.onBytes(1500, 1000)

        assertTrue(recorder.ratios.single() <= 1.0)
    }

    @Test
    fun `解析索引文本大小`() {
        assertEquals(25_585_254L, parseSizeText("24.4 MB"))
        assertEquals(398_458_880L, parseSizeText("380 MB"))
        assertEquals(524_288L, parseSizeText("512 KB"))
        assertEquals(1_288_490_188L, parseSizeText("1.2GB"))
        assertEquals(23L, parseSizeText("23 B"))
        assertEquals(23L, parseSizeText("23"))
        assertEquals(1_073_741_824L, parseSizeText(" 1 GB "))
        assertNull(parseSizeText(null))
        assertNull(parseSizeText(""))
        assertNull(parseSizeText("   "))
        assertNull(parseSizeText("未知"))
        assertNull(parseSizeText("23 bytes"))
        assertNull(parseSizeText("0 MB"))
    }
}
