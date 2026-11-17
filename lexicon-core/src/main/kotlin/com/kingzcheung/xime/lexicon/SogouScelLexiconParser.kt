package com.kingzcheung.xime.lexicon

/**
 * 搜狗细胞词库（.scel）解析器。
 *
 * 二进制格式与 imewlconverter 的 `SougouScelImporter` 一致：
 * - 0x120: Int32 词条分组数；
 * - 0x1540: Int32 拼音表条数，随后每条为 (UInt16 index, UInt16 byteLength, UTF-16LE 拼音)；
 * - 之后是词条分组：(UInt16 同音词数, UInt16 拼音索引字节数, 索引, 词...)；
 * - 每条词后固定 12 字节扩展信息（2 + 4 + 6）。
 *
 * 该格式自带拼音，解析后直接得到 Rime 全拼码；没有可靠词频，统一低权重。
 */
object SogouScelLexiconParser : LexiconParser {

    override val id: String = "sogou-scel"
    override val displayName: String = "搜狗细胞词库"
    override val extensions: List<String> = listOf(".scel")
    override val maxBytes: Int = 32 * 1024 * 1024

    /** .scel 没有可靠词频，统一给低权重，避免压过方案词。 */
    internal const val DEFAULT_WEIGHT = 1

    override fun parse(fileName: String, bytes: ByteArray): ParsedLexicon {
        val raw = SogouScelBinaryParser.parse(bytes)
        val converted = convert(raw)
        return ParsedLexicon(
            formatId = id,
            formatName = displayName,
            codeSystem = LexiconCodeSystem.PINYIN,
            entries = converted.entries,
            skipped = converted.skipped,
            duplicates = converted.duplicates,
        )
    }

    internal data class Conversion(
        val entries: List<LexiconEntry>,
        val skipped: Int,
        val duplicates: Int,
    )

    /** 原始分词 -> Rime 词条：去空、去重、过滤会破坏码表行的字符。 */
    internal fun convert(raw: List<RawScelEntry>): Conversion {
        val seen = HashSet<String>(raw.size * 2)
        val entries = ArrayList<LexiconEntry>(raw.size)
        var skipped = 0
        var duplicates = 0

        for (entry in raw) {
            val code = entry.code.trim()
            if (entry.word.isBlank() || code.isBlank()) {
                skipped++
                continue
            }
            if (entry.word.startsWith("#") ||
                entry.word.any { it == '\t' || it == '\n' || it == '\r' }
            ) {
                skipped++
                continue
            }
            if (!seen.add(entry.word + '\u0000' + code)) {
                duplicates++
                continue
            }
            entries += LexiconEntry(word = entry.word, code = code, weight = DEFAULT_WEIGHT)
        }
        return Conversion(entries, skipped, duplicates)
    }
}

/** 解析后的一行原始词条。 */
internal data class RawScelEntry(val word: String, val code: String)

/** .scel 二进制读取；失败统一转成 [LexiconFormatException]。 */
internal object SogouScelBinaryParser {

    private const val OFFSET_DICT_LEN = 0x120
    private const val OFFSET_PINYIN_TABLE = 0x1540
    private const val MAX_GROUPS = 1_000_000
    private const val MAX_PINYIN_TABLE = 200_000
    private const val MAX_PINYIN_BYTES = 1024
    private const val MAX_ENTRIES = 500_000
    private const val WORD_EXTRA_BYTES = 12

    fun parse(bytes: ByteArray): List<RawScelEntry> {
        if (bytes.size < OFFSET_PINYIN_TABLE + 4) {
            throw LexiconFormatException("文件太小，不是有效的 .scel")
        }
        return try {
            parseInternal(bytes)
        } catch (e: LexiconFormatException) {
            throw e
        } catch (e: Exception) {
            throw LexiconFormatException("解析 .scel 失败：${e.message ?: "格式不完整"}")
        }
    }

    private fun parseInternal(bytes: ByteArray): List<RawScelEntry> {
        val reader = Reader(bytes)

        reader.seek(OFFSET_DICT_LEN)
        val groupCount = reader.readInt32()
        reader.seek(OFFSET_PINYIN_TABLE)
        val pinyinCount = reader.readInt32()

        if (groupCount !in 0..MAX_GROUPS) {
            throw LexiconFormatException("词条数异常：$groupCount")
        }
        if (pinyinCount !in 0..MAX_PINYIN_TABLE) {
            throw LexiconFormatException("拼音表条数异常：$pinyinCount")
        }

        val pinyinTable = HashMap<Int, String>(pinyinCount * 2)
        repeat(pinyinCount) {
            val index = reader.readUInt16()
            val byteLength = reader.readUInt16()
            if (byteLength % 2 != 0 || !reader.canRead(byteLength)) {
                throw LexiconFormatException("拼音表损坏")
            }
            pinyinTable[index] = reader.readUtf16(byteLength)
        }

        val entries = ArrayList<RawScelEntry>()
        repeat(groupCount) {
            val samePinyinCount = reader.readUInt16()
            val pinyinBytes = reader.readUInt16()
            if (pinyinBytes % 2 != 0 || pinyinBytes > MAX_PINYIN_BYTES ||
                !reader.canRead(pinyinBytes)
            ) {
                throw LexiconFormatException("拼音索引损坏")
            }

            val syllables = ArrayList<String>(pinyinBytes / 2)
            repeat(pinyinBytes / 2) {
                val key = reader.readUInt16()
                syllables += pinyinTable[key] ?: fallbackSyllable(key, pinyinTable.size)
            }
            val code = syllables.filter { it.isNotBlank() }.joinToString(" ")

            repeat(samePinyinCount) {
                val wordBytes = reader.readUInt16()
                if (wordBytes % 2 != 0 || !reader.canRead(wordBytes)) {
                    throw LexiconFormatException("词条长度损坏")
                }
                val word = reader.readUtf16(wordBytes)
                if (!reader.canRead(WORD_EXTRA_BYTES)) {
                    throw LexiconFormatException("词条扩展信息不完整")
                }
                reader.skip(WORD_EXTRA_BYTES)
                if (word.isNotEmpty() && code.isNotEmpty()) {
                    entries += RawScelEntry(word, code)
                }
                if (entries.size > MAX_ENTRIES) {
                    throw LexiconFormatException("词条数量超过上限")
                }
            }
        }
        return entries
    }

    /** 与参考实现一致：索引超出拼音表时按字母表回退。 */
    private fun fallbackSyllable(key: Int, tableSize: Int): String {
        val code = key - tableSize + 'a'.code
        return if (code in 'a'.code..'z'.code) code.toChar().toString() else ""
    }

    private class Reader(private val data: ByteArray) {
        private var position = 0

        fun seek(offset: Int) {
            if (offset !in 0..data.size) throw LexiconFormatException("偏移越界：$offset")
            position = offset
        }

        fun canRead(count: Int): Boolean = count >= 0 && position + count <= data.size

        fun readUInt16(): Int {
            if (!canRead(2)) throw LexiconFormatException("数据提前结束")
            val value = (data[position].toInt() and 0xff) or
                ((data[position + 1].toInt() and 0xff) shl 8)
            position += 2
            return value
        }

        fun readInt32(): Int {
            if (!canRead(4)) throw LexiconFormatException("数据提前结束")
            val value = (data[position].toInt() and 0xff) or
                ((data[position + 1].toInt() and 0xff) shl 8) or
                ((data[position + 2].toInt() and 0xff) shl 16) or
                ((data[position + 3].toInt() and 0xff) shl 24)
            position += 4
            return value
        }

        fun readUtf16(byteCount: Int): String {
            if (!canRead(byteCount)) throw LexiconFormatException("数据提前结束")
            val text = String(data, position, byteCount, Charsets.UTF_16LE)
            position += byteCount
            return text
        }

        fun skip(count: Int) {
            if (!canRead(count)) throw LexiconFormatException("数据提前结束")
            position += count
        }
    }
}
