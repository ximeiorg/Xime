package com.kingzcheung.xime.lexicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduPinyinLexiconParserTest {

    @Test
    fun `parses baidu text dictionary`() {
        val parsed = BaiduPinyinLexiconParser.parse("baidu.txt", sample())

        assertEquals("bdpy", parsed.formatId)
        assertEquals("百度拼音文本词库", parsed.formatName)
        assertEquals(LexiconCodeSystem.PINYIN, parsed.codeSystem)
        assertEquals(7, parsed.entries.size)
        assertEquals(0, parsed.skipped)
        assertEquals(0, parsed.duplicates)

        assertEquals("深蓝词库转换器", parsed.entries.first().word)
        assertEquals("shen lan ci ku zhuan huan qi", parsed.entries.first().code)
        assertEquals(1, parsed.entries.first().weight)
        assertEquals("深", parsed.entries.last().word)
        assertEquals(7, parsed.entries.last().weight)
    }

    @Test
    fun `detects utf16 baidu text but not other text formats`() {
        assertTrue(BaiduPinyinLexiconParser.looksLike(sample()))
        val libime = javaClass.classLoader!!
            .getResourceAsStream("libimetxt/libime.txt")!!.use { it.readBytes() }
        assertFalse(BaiduPinyinLexiconParser.looksLike(libime))
    }

    private fun sample(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("bdpy/baidu.txt")!!.use { it.readBytes() }
}
