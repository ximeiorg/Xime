package com.kingzcheung.xime.lexicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SogouPinyinLexiconParserTest {

    @Test
    fun `parses sogou text dictionary`() {
        val parsed = SogouPinyinLexiconParser.parse("sogou.txt", sample())

        assertEquals("sgpy", parsed.formatId)
        assertEquals("搜狗拼音文本词库", parsed.formatName)
        assertEquals(LexiconCodeSystem.PINYIN, parsed.codeSystem)
        assertEquals(7, parsed.entries.size)
        assertEquals(0, parsed.skipped)
        assertEquals(0, parsed.duplicates)

        assertEquals("深蓝词库转换器", parsed.entries.first().word)
        assertEquals("shen lan ci ku zhuan huan qi", parsed.entries.first().code)
        assertEquals("深", parsed.entries.last().word)
        assertEquals("shen", parsed.entries.last().code)
        assertTrue(parsed.entries.all { it.weight == 1 })
    }

    @Test
    fun `detects gbk sogou text but not utf8 libime text`() {
        assertTrue(SogouPinyinLexiconParser.looksLike(sample()))
        val libime = javaClass.classLoader!!
            .getResourceAsStream("libimetxt/libime.txt")!!.use { it.readBytes() }
        assertFalse(SogouPinyinLexiconParser.looksLike(libime))
    }

    private fun sample(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("sgpy/sogou.txt")!!.use { it.readBytes() }
}
