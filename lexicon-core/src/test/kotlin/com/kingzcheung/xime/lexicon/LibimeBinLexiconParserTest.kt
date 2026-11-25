package com.kingzcheung.xime.lexicon

import com.github.luben.zstd.ZstdOutputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibimeBinLexiconParserTest {

    @Test
    fun `parses real libime binary dict`() {
        val parsed = LibimeBinLexiconParser.parse("web-slang.dict", realSample())

        assertEquals("libimebin", parsed.formatId)
        assertEquals("Fcitx5/libime 二进制词库", parsed.formatName)
        assertEquals(LexiconCodeSystem.PINYIN, parsed.codeSystem)
        assertEquals(423, parsed.entries.size)
        assertEquals(0, parsed.skipped)
        assertEquals(0, parsed.duplicates)

        assertEquals("芭比Q了", parsed.entries[0].word)
        assertEquals("ba bi q le", parsed.entries[0].code)
        assertEquals(1, parsed.entries[0].weight)
        assertEquals("百合", parsed.entries[1].word)
        assertEquals("bai he", parsed.entries[1].code)
        assertEquals("百错仍硕", parsed.entries[2].word)
        assertEquals("bai cuo reng shuo", parsed.entries[2].code)
        assertEquals("板斧", parsed.entries[3].word)
        assertEquals("斑竹", parsed.entries[4].word)
        assertEquals("XX控", parsed.entries.last().word)
        assertEquals("x x kong", parsed.entries.last().code)

        assertTrue(parsed.entries.all { it.word.isNotEmpty() && it.code.isNotEmpty() })
        assertTrue(parsed.entries.all { entry ->
            entry.code.all { it in 'a'..'z' || it == ' ' }
        })
        assertTrue(parsed.entries.all { it.word.length == it.code.split(' ').size })
    }

    @Test
    fun `parses another real downloaded libime dict`() {
        val parsed = LibimeBinLexiconParser.parse("web-slang.dict", downloadedSample())

        assertEquals(351, parsed.entries.size)
        assertEquals(0, parsed.skipped)
        assertEquals("八千党", parsed.entries.first().word)
        assertEquals("ba qian dang", parsed.entries.first().code)
        assertEquals("二共", parsed.entries.last().word)
        assertEquals("er gong", parsed.entries.last().code)
    }

    @Test
    fun `decompresses the whole datrie payload`() {
        // 真实文件是 2 个 zstd 帧：第一帧装下全部 DATrie，第二帧为空；尾部还有 block 链表。
        assertEquals(17756, LibimeBinFormat.readDatrie(realSample()).size)
    }

    @Test
    fun `only recognizes libime magic`() {
        assertTrue(LibimeBinLexiconParser.looksLike(realSample()))
        assertFalse(LibimeBinLexiconParser.looksLike(ByteArray(8)))
        assertFalse(LibimeBinLexiconParser.looksLike(realSample().copyOf(4)))

        val wrongMagic = realSample()
        wrongMagic[3] = (wrongMagic[3].toInt() xor 0x01).toByte()
        assertFalse(LibimeBinLexiconParser.looksLike(wrongMagic))

        val error = runCatching {
            LibimeBinLexiconParser.parse("wrong.dict", wrongMagic)
        }.exceptionOrNull()
        assertTrue(error is LexiconFormatException)
    }

    @Test
    fun `rejects truncated file`() {
        val sample = realSample()
        for (size in intArrayOf(8, 64, sample.size / 2)) {
            val error = runCatching {
                LibimeBinLexiconParser.parse("web-slang.dict", sample.copyOf(size))
            }.exceptionOrNull()
            assertTrue("size=$size", error is LexiconFormatException)
        }
    }

    @Test
    fun `parses legacy uncompressed datrie`() {
        val raw = LibimeBinFormat.readDatrie(realSample())
        val legacy = ByteArray(8 + raw.size)
        byteArrayOf(0x00, 0x0f, 0xc6.toByte(), 0x13).copyInto(legacy, 0)
        legacy[7] = 1 // version 1：不压缩
        raw.copyInto(legacy, 8)

        assertEquals(423, LibimeBinLexiconParser.parse("legacy.dict", legacy).entries.size)
    }

    @Test
    fun `reassembles datrie split across zstd frames`() {
        val raw = LibimeBinFormat.readDatrie(realSample())
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x00, 0x0f, 0xc6.toByte(), 0x13, 0x00, 0x00, 0x00, 0x02))
        for (part in listOf(raw.copyOfRange(0, 10_000), raw.copyOfRange(10_000, raw.size))) {
            ZstdOutputStream(out).use { it.write(part) }
        }

        assertEquals(423, LibimeBinLexiconParser.parse("multiframe.dict", out.toByteArray()).entries.size)
    }

    @Test
    fun `skips invalid keys and dedupes`() {
        val valid = "AA!好".toByteArray(Charsets.UTF_8) // 65,65 -> b + a
        val converted = LibimeBinLexiconParser.convert(
            sequenceOf(
                valid to 3.0f,
                valid.copyOf() to 3.0f,
                // 声母/韵母都越界，音节解不出
                byteArrayOf(0, 0, '!'.code.toByte()) + "字".toByteArray(Charsets.UTF_8) to 0f,
                byteArrayOf(65, 65) to 0f, // 没有分隔符
                "AAA!好".toByteArray(Charsets.UTF_8) to 0f, // 拼音字节数为奇数
                "AA!".toByteArray(Charsets.UTF_8) to 0f, // 没有汉字
            )
        )

        assertEquals(1, converted.entries.size)
        assertEquals("好", converted.entries.first().word)
        assertEquals("ba", converted.entries.first().code)
        assertEquals(3, converted.entries.first().weight)
        assertEquals(4, converted.skipped)
        assertEquals(1, converted.duplicates)
    }

    @Test
    fun `caps weight and falls back to default`() {
        val converted = LibimeBinLexiconParser.convert(
            sequenceOf(
                "AA!好".toByteArray(Charsets.UTF_8) to 2_500_000f,
                "AB!好".toByteArray(Charsets.UTF_8) to 0f,
                "AC!好".toByteArray(Charsets.UTF_8) to Float.NaN,
            )
        )

        assertEquals(listOf(1_000_000, 1, 1), converted.entries.map { it.weight })
    }

    private fun downloadedSample(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("libimebin/web-slang-downloaded.dict")!!
            .use { it.readBytes() }

    private fun realSample(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("libimebin/web-slang.dict")!!.use { it.readBytes() }
}
