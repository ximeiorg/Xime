package com.kingzcheung.xime.lexicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SogouScelLexiconParserTest {

    @Test
    fun `parses synthetic scel into pinyin entries`() {
        val parsed = SogouScelLexiconParser.parse("tangshi.scel", buildScel())

        assertEquals("sogou-scel", parsed.formatId)
        assertEquals("搜狗细胞词库", parsed.formatName)
        assertEquals(LexiconCodeSystem.PINYIN, parsed.codeSystem)
        assertEquals(3, parsed.entries.size)
        assertEquals("哀江头", parsed.entries.first().word)
        assertEquals("ai jiang tou", parsed.entries.first().code)
        assertEquals("哀江头之二", parsed.entries[1].word)
    }

    @Test
    fun `parses real errenzhuan scel`() {
        val parsed = SogouScelLexiconParser.parse("二人转词汇大全.scel", realSample())

        assertEquals(2615, parsed.entries.size)
        assertEquals("矮子步", parsed.entries.first().word)
        assertEquals("ai zi bu", parsed.entries.first().code)
        assertEquals("坐中央", parsed.entries.last().word)
        assertEquals("zuo zhong yang", parsed.entries.last().code)
        assertEquals(0, parsed.skipped)
        assertEquals(0, parsed.duplicates)
        assertTrue(parsed.entries.all { it.word.isNotEmpty() && it.code.isNotEmpty() })
        assertTrue(parsed.entries.all { entry ->
            entry.code.all { it in 'a'..'z' || it == ' ' }
        })
        assertTrue(parsed.entries.all { it.word.length == it.code.split(' ').size })

        val longWord = parsed.entries.first { it.word == "八个姑娘打擂台" }
        assertEquals("ba ge gu niang da lei tai", longWord.code)
    }

    @Test
    fun `rejects truncated real file`() {
        val bytes = realSample().copyOf(realSample().size / 2)
        val error = runCatching {
            SogouScelLexiconParser.parse("二人转词汇大全.scel", bytes)
        }.exceptionOrNull()
        assertTrue(error is LexiconFormatException)
    }

    @Test
    fun `rejects truncated file`() {
        val error = runCatching {
            SogouScelLexiconParser.parse("x.scel", buildScel().copyOf(0x1540))
        }.exceptionOrNull()
        assertTrue(error is LexiconFormatException)
    }

    @Test
    fun `rejects absurd group count`() {
        val error = runCatching {
            SogouScelLexiconParser.parse("x.scel", ByteArray(2048) { 1 })
        }.exceptionOrNull()
        assertTrue(error is LexiconFormatException)
    }

    @Test
    fun `convert skips empty comment and duplicate entries`() {
        val converted = SogouScelLexiconParser.convert(
            listOf(
                RawScelEntry("", "a"),
                RawScelEntry("字", ""),
                RawScelEntry("#注释", "zhu"),
                RawScelEntry("好", "hao"),
                RawScelEntry("好", "hao"),
            )
        )

        assertEquals(3, converted.skipped)
        assertEquals(1, converted.duplicates)
        assertEquals(1, converted.entries.size)
        assertEquals("hao", converted.entries.first().code)
    }

    private fun realSample(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("scel/errenzhuan.scel")!!.use { it.readBytes() }

    private fun buildScel(): ByteArray {
        val out = ByteArrayOutputStream()
        val header = ByteArray(0x1540 + 4)
        putInt32(header, 0x120, 2) // 两个词条分组
        putInt32(header, 0x1540, 3) // 三个拼音
        out.write(header)

        writePinyin(out, 0, "ai")
        writePinyin(out, 1, "jiang")
        writePinyin(out, 2, "tou")

        writeGroup(out, pinyin = listOf(0, 1, 2), words = listOf("哀江头"))
        writeGroup(
            out,
            pinyin = listOf(0, 1, 2),
            words = listOf("哀江头之二", "哀江头之三"),
        )
        return out.toByteArray()
    }

    private fun writePinyin(out: ByteArrayOutputStream, index: Int, pinyin: String) {
        val bytes = pinyin.toByteArray(Charsets.UTF_16LE)
        out.write(index and 0xff)
        out.write((index shr 8) and 0xff)
        out.write(bytes.size and 0xff)
        out.write((bytes.size shr 8) and 0xff)
        out.write(bytes)
    }

    private fun writeGroup(
        out: ByteArrayOutputStream,
        pinyin: List<Int>,
        words: List<String>,
    ) {
        out.write(words.size and 0xff)
        out.write((words.size shr 8) and 0xff)
        out.write((pinyin.size * 2) and 0xff)
        out.write(((pinyin.size * 2) shr 8) and 0xff)
        for (index in pinyin) {
            out.write(index and 0xff)
            out.write((index shr 8) and 0xff)
        }
        for (word in words) {
            val bytes = word.toByteArray(Charsets.UTF_16LE)
            out.write(bytes.size and 0xff)
            out.write((bytes.size shr 8) and 0xff)
            out.write(bytes)
            out.write(ByteArray(12)) // ext_len(2) + ext(10)
        }
    }

    private fun putInt32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xff).toByte()
    }
}
