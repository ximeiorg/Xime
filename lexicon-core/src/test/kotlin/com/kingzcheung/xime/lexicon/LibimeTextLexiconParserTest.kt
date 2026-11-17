package com.kingzcheung.xime.lexicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibimeTextLexiconParserTest {

    @Test
    fun `parses libime text dictionary`() {
        val parsed = LibimeTextLexiconParser.parse("libime.txt", sample())

        assertEquals("libimetxt", parsed.formatId)
        assertEquals("Fcitx5/libime 文本词库", parsed.formatName)
        assertEquals(LexiconCodeSystem.PINYIN, parsed.codeSystem)
        assertEquals(5, parsed.entries.size)
        assertEquals(0, parsed.duplicates)
        assertEquals(2, parsed.skipped)

        assertEquals(
            listOf("倪辉", "小企鹅", "X光", "绿色", "a b"),
            parsed.entries.map { it.word },
        )
        assertEquals(
            listOf("ni hui", "xiao qi e", "x guang", "lve se", "ni hao"),
            parsed.entries.map { it.code },
        )
        assertEquals(listOf(1, 1, 1, 1, 5), parsed.entries.map { it.weight })
    }

    @Test
    fun `detects libime text by content`() {
        assertTrue(LibimeTextLexiconParser.looksLike(sample()))
    }

    private fun sample(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("libimetxt/libime.txt")!!.use { it.readBytes() }
}
