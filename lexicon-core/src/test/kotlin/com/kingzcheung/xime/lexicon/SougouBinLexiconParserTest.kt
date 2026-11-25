package com.kingzcheung.xime.lexicon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SougouBinLexiconParserTest {

    @Test
    fun `parses real SGPU backup`() {
        val parsed = SougouBinLexiconParser.parse("sogou-new.bin", newSample())

        assertEquals("sgpybin", parsed.formatId)
        assertEquals("搜狗拼音备份词库", parsed.formatName)
        assertEquals(LexiconCodeSystem.PINYIN, parsed.codeSystem)
        assertEquals(30, parsed.entries.size)
        assertEquals(0, parsed.skipped)
        assertEquals(0, parsed.duplicates)

        assertEquals("斌", parsed.entries[0].word)
        assertEquals("bin", parsed.entries[0].code)
        assertEquals(1, parsed.entries[0].weight) // 备份词频为 0，回退到 1

        assertEquals("并", parsed.entries[1].word)
        assertEquals("bing", parsed.entries[1].code)
        assertEquals("不通", parsed.entries[2].word)
        assertEquals("bu tong", parsed.entries[2].code)
        assertEquals("不一定", parsed.entries[3].word)
        assertEquals("bu yi ding", parsed.entries[3].code)
        assertEquals("到", parsed.entries[4].word)
        assertEquals("dao", parsed.entries[4].code)

        assertEquals("看看", parsed.entries[9].word)
        assertEquals(2, parsed.entries[9].weight)
        assertEquals("是不是", parsed.entries[14].word)
        assertEquals(2, parsed.entries[14].weight)
        assertEquals("Issue", parsed.entries[28].word)
        assertEquals("i s s u e", parsed.entries[28].code)
        assertEquals(3, parsed.entries[28].weight)
        assertEquals("push", parsed.entries.last().word)
        assertEquals("p u s h", parsed.entries.last().code)

        assertValidPinyinEntries(parsed)
    }

    @Test
    fun `parses real pre-7_2 plain backup`() {
        val parsed = SougouBinLexiconParser.parse("sogou-old.bin", oldSample())

        assertEquals(40, parsed.entries.size)
        assertEquals(0, parsed.skipped)
        assertEquals(0, parsed.duplicates)

        assertEquals("啊", parsed.entries.first().word)
        assertEquals("a", parsed.entries.first().code)
        assertEquals(7, parsed.entries.first().weight)
        assertEquals("阿", parsed.entries[1].word)
        assertEquals("a", parsed.entries[1].code)
        assertEquals("代码", parsed.entries[10].word)
        assertEquals("dai ma", parsed.entries[10].code)
        assertEquals("的", parsed.entries[11].word)
        assertEquals(3, parsed.entries[11].weight)
        assertEquals("需求加上", parsed.entries.last().word)
        assertEquals("xu qiu jia shang", parsed.entries.last().code)

        assertValidPinyinEntries(parsed)
    }

    @Test
    fun `only recognizes sogou backup magic`() {
        assertTrue(SougouBinLexiconParser.looksLike(newSample()))
        assertTrue(SougouBinLexiconParser.looksLike(oldSample()))
        assertFalse(SougouBinLexiconParser.looksLike(ByteArray(0)))
        assertFalse(SougouBinLexiconParser.looksLike(ByteArray(4)))
        assertFalse(SougouBinLexiconParser.looksLike(newSample().copyOf(3)))

        val random = randomBin()
        assertFalse(SougouBinLexiconParser.looksLike(random))

        val error = runCatching {
            SougouBinLexiconParser.parse("random.bin", random)
        }.exceptionOrNull()
        assertTrue(error is LexiconFormatException)
    }

    @Test
    fun `rejects truncated new backup`() {
        val sample = newSample()
        for (size in intArrayOf(8, 0x50, 1_000, 40_000, 41_000)) {
            val error = runCatching {
                SougouBinLexiconParser.parse("sogou-new.bin", sample.copyOf(size))
            }.exceptionOrNull()
            assertTrue("size=$size", error is LexiconFormatException)
        }
    }

    @Test
    fun `rejects truncated old backup`() {
        val sample = oldSample()
        for (size in intArrayOf(0x20, 0x60)) {
            val error = runCatching {
                SougouBinLexiconParser.parse("sogou-old.bin", sample.copyOf(size))
            }.exceptionOrNull()
            assertTrue("size=$size", error is LexiconFormatException)
        }
    }

    @Test
    fun `convert skips invalid, dedupes and caps weight`() {
        val converted = SougouBinLexiconParser.convert(
            listOf(
                SogouBinEntry("", "a", 1),
                SogouBinEntry("好", "", 1),
                SogouBinEntry("坏\t词", "huai", 1),
                SogouBinEntry("好", "hao", 2),
                SogouBinEntry("好", "hao", 3),
                SogouBinEntry("字", "zi", 2_000_000),
                SogouBinEntry("词", "ci", 0),
            )
        )

        assertEquals(3, converted.skipped)
        assertEquals(1, converted.duplicates)
        assertEquals(3, converted.entries.size)
        assertEquals(listOf("好", "字", "词"), converted.entries.map { it.word })
        assertEquals(listOf(2, 1_000_000, 1), converted.entries.map { it.weight })
    }

    private fun assertValidPinyinEntries(parsed: ParsedLexicon) {
        assertTrue(parsed.entries.all { it.word.isNotEmpty() && it.code.isNotEmpty() })
        assertTrue(
            parsed.entries.all { entry ->
                entry.code.all { it in 'a'..'z' || it == ' ' }
            }
        )
        assertTrue(
            parsed.entries.all { entry ->
                entry.word.length == entry.code.split(' ').size
            }
        )
    }

    private fun newSample(): ByteArray = resource("sgpybin/sogou-new.bin")

    private fun oldSample(): ByteArray = resource("sgpybin/sogou-old.bin")

    private fun resource(path: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(path)!!.use { it.readBytes() }

    private fun randomBin(): ByteArray =
        ByteArray(2048) { index -> ((index * 37 + 11) and 0xff).toByte() }
}
