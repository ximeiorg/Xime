package com.kingzcheung.xime.lexicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LexiconFormatsTest {

    @Test
    fun `resolves scel case insensitively`() {
        assertEquals("sogou-scel", LexiconFormats.findByFileName("a.scel")?.id)
        assertEquals("sogou-scel", LexiconFormats.findByFileName("A.SCEL")?.id)
    }

    @Test
    fun `resolves bdict case insensitively`() {
        assertEquals("baidu-bdict", LexiconFormats.findByFileName("汽车词汇大全.bdict")?.id)
        assertEquals("baidu-bdict", LexiconFormats.findByFileName("A.BDICT")?.id)
    }

    @Test
    fun `does not claim rime dictionary or unknown files`() {
        assertNull(LexiconFormats.findByFileName("wubi86.dict.yaml"))
        assertNull(LexiconFormats.findByFileName("something.txt"))
        assertFalse(LexiconFormats.hasCandidate("wubi86.dict.yaml"))
    }

    @Test
    fun `txt is ambiguous and resolved by content`() {
        assertNull(LexiconFormats.findByFileName("words.txt"))
        assertTrue(LexiconFormats.hasCandidate("words.txt"))

        assertEquals("libimetxt", LexiconFormats.detect("words.txt", resource("libimetxt/libime.txt"))?.id)
        assertEquals("sgpy", LexiconFormats.detect("words.txt", resource("sgpy/sogou.txt"))?.id)
        assertEquals("bdpy", LexiconFormats.detect("words.txt", resource("bdpy/baidu.txt"))?.id)
        assertNull(LexiconFormats.detect("words.txt", "hello world\n".toByteArray()))
    }

    @Test
    fun `binary formats are resolved by extension`() {
        assertEquals("libimebin", LexiconFormats.findByFileName("web-slang.dict")?.id)
        assertEquals("sgpybin", LexiconFormats.findByFileName("搜狗备份.bin")?.id)
        assertNull(LexiconFormats.detect("web-slang.dict", "not a libime dict".toByteArray()))
    }

    @Test
    fun `supported description lists registered formats`() {
        val description = LexiconFormats.supportedDescription()
        assertTrue(description.contains("搜狗细胞词库"))
        assertTrue(description.contains("百度分类词库"))
        assertTrue(description.contains("Fcitx5/libime 文本词库"))
        assertTrue(description.contains("搜狗拼音文本词库"))
        assertTrue(description.contains("百度拼音文本词库"))
        assertTrue(description.contains("Fcitx5/libime 二进制词库"))
        assertTrue(description.contains("搜狗拼音备份词库"))
        assertTrue(LexiconFormats.registered().any { it.id == "sogou-scel" })
        assertTrue(LexiconFormats.registered().any { it.id == "baidu-bdict" })
    }

    private fun resource(path: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(path)!!.use { it.readBytes() }
}
