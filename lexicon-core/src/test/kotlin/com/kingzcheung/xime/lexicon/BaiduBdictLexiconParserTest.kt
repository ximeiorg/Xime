package com.kingzcheung.xime.lexicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class BaiduBdictLexiconParserTest {

    @Test
    fun `parses synthetic bdict into pinyin entries`() {
        val parsed = BaiduBdictLexiconParser.parse("test.bdict", buildBdict())

        assertEquals("baidu-bdict", parsed.formatId)
        assertEquals("百度分类词库", parsed.formatName)
        assertEquals(LexiconCodeSystem.PINYIN, parsed.codeSystem)
        assertEquals(2, parsed.entries.size)
        assertEquals("福建土楼", parsed.entries.first().word)
        assertEquals("fu jian tu lou", parsed.entries.first().code)
        assertEquals("好", parsed.entries.last().word)
        assertEquals("hao", parsed.entries.last().code)
        assertEquals(2, parsed.skipped)
        assertEquals(1, parsed.duplicates)
    }

    @Test
    fun `parses real qiche bdict`() {
        val parsed = BaiduBdictLexiconParser.parse("汽车词汇大全.bdict", realSample())

        assertEquals(4105, parsed.entries.size)
        assertEquals(1, parsed.skipped)
        assertEquals(0, parsed.duplicates)
        assertEquals("江铃轻卡", parsed.entries.first().word)
        assertEquals("jiang ling qing ka", parsed.entries.first().code)
        assertEquals("二轴二档轮", parsed.entries.last().word)
        assertEquals("er zhou er dang lun", parsed.entries.last().code)
        assertTrue(parsed.entries.all { entry ->
            entry.code.isNotBlank() && entry.code.all { it in 'a'..'z' || it == ' ' }
        })
        assertTrue(parsed.entries.all { entry ->
            entry.word.codePointCount(0, entry.word.length) == entry.code.split(' ').size
        })

        val longWord = parsed.entries.first { it.word == "北京来宝汽车美容学校" }
        assertEquals("bei jing lai bao qi che mei rong xue xiao", longWord.code)
    }

    @Test
    fun `rejects file with unknown magic`() {
        val bytes = buildBdict().also { it[0] = 'x'.code.toByte() }
        val error = runCatching {
            BaiduBdictLexiconParser.parse("x.bdict", bytes)
        }.exceptionOrNull()
        assertTrue(error is LexiconFormatException)
    }

    @Test
    fun `rejects truncated entries`() {
        val bytes = buildBdict()
        val error = runCatching {
            BaiduBdictLexiconParser.parse("x.bdict", bytes.copyOf(bytes.size - 4))
        }.exceptionOrNull()
        assertTrue(error is LexiconFormatException)
    }

    @Test
    fun `rejects absurd syllable count`() {
        val bytes = buildBdict()
        putInt32(bytes, 0x350, 100_000)
        val error = runCatching {
            BaiduBdictLexiconParser.parse("x.bdict", bytes)
        }.exceptionOrNull()
        assertTrue(error is LexiconFormatException)
    }

    private fun realSample(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("bdict/qiche.bdict")!!.use { it.readBytes() }

    private fun buildBdict(): ByteArray {
        val body = ByteArrayOutputStream()
        writeEntry(body, listOf("fu", "jian", "tu", "lou"), "福建土楼")
        writeMixedEntry(body, "大众golf")
        writeEntry(body, listOf("zhu"), "#")
        writeEntry(body, listOf("fu", "jian", "tu", "lou"), "福建土楼")
        writeEntry(body, listOf("hao"), "好")

        val bytes = body.toByteArray()
        val out = ByteArray(0x350)
        "biptbdsw".toByteArray(Charsets.US_ASCII).copyInto(out)
        putInt32(out, 0x40, 0x350)
        putInt32(out, 0x44, bytes.size)
        putInt32(out, 0x60, 0x350 + bytes.size)
        return out + bytes
    }

    private fun writeEntry(out: ByteArrayOutputStream, syllables: List<String>, word: String) {
        writeRaw(out, syllables.map { pair(it) }, word)
    }

    /** 拼音 -> (声母索引, 韵母索引)；声母取最长匹配（zh/ch/sh 优先于 z/c/s）。 */
    private fun pair(syllable: String): Pair<Int, Int> {
        val initial = INITIALS.indices
            .filter { INITIALS[it].isNotEmpty() && syllable.startsWith(INITIALS[it]) }
            .maxByOrNull { INITIALS[it].length }!!
        val final = FINALS.indexOf(syllable.removePrefix(INITIALS[initial]))
        check(final >= 0) { "未知韵母：$syllable" }
        return initial to final
    }

    /** 混排词条：英文字母用 0xFF + ASCII 表示。 */
    private fun writeMixedEntry(out: ByteArrayOutputStream, word: String) {
        val pairs = listOf(
            1 to 27, // da
            19 to 8, // zhong
            0xFF to 'g'.code,
            0xFF to 'o'.code,
            0xFF to 'l'.code,
            0xFF to 'f'.code,
        )
        writeRaw(out, pairs, word)
    }

    private fun writeRaw(out: ByteArrayOutputStream, pairs: List<Pair<Int, Int>>, word: String) {
        putInt32(out, pairs.size)
        pairs.forEach { (initial, final) ->
            out.write(initial)
            out.write(final)
        }
        out.write(word.toByteArray(Charsets.UTF_16LE))
    }

    private fun putInt32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value shr 8) and 0xff)
        out.write((value shr 16) and 0xff)
        out.write((value shr 24) and 0xff)
    }

    private fun putInt32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    private companion object {
        val INITIALS = listOf(
            "c", "d", "b", "f", "g", "h", "ch", "j", "k", "l",
            "m", "n", "", "p", "q", "r", "s", "t", "sh", "zh",
            "w", "x", "y", "z",
        )
        val FINALS = listOf(
            "uang", "iang", "iong", "ang", "eng", "ian", "iao", "ing", "ong", "uai",
            "uan", "ai", "an", "ao", "ei", "en", "er", "ua", "ie", "in",
            "iu", "ou", "ia", "ue", "ui", "un", "uo", "a", "e", "i",
            "o", "u", "v",
        )
    }
}
