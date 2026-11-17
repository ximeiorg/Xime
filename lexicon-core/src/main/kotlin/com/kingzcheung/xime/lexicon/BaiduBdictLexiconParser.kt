package com.kingzcheung.xime.lexicon

/**
 * 百度分类词库（.bdict）解析器。
 *
 * 二进制结构与 imewlconverter 的 `BaiduBdictImporter` 一致：
 * - 0x00: ASCII 魔数（`biptbdsw`，部分版本为 `bdict`）；
 * - 0x40: 中文词条区起始偏移，0x44: 词条区字节长度；
 * - 每条词条：Int32 音节数，随后每字两字节（声母索引、韵母索引，0xFF 表示英文字母），
 *   再跟 UTF-16LE 词。
 *
 * 词条区之后还有纯英文区与混排区（编码和词不等长），拼音方案无法直接使用，跳过。
 */
object BaiduBdictLexiconParser : LexiconParser {

    override val id: String = "baidu-bdict"
    override val displayName: String = "百度分类词库"
    override val extensions: List<String> = listOf(".bdict")
    override val maxBytes: Int = 32 * 1024 * 1024

    /** .bdict 没有词频字段，统一给低权重，避免压过方案词。 */
    internal const val DEFAULT_WEIGHT = 1

    override fun parse(fileName: String, bytes: ByteArray): ParsedLexicon {
        val raw = BaiduBdictBinaryParser.parse(bytes)
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

    /** 原始词条 -> Rime 词条：去空、去重、跳过含英文字母的混排词。 */
    internal fun convert(raw: List<RawBdictEntry>): Conversion {
        val seen = HashSet<String>(raw.size * 2)
        val entries = ArrayList<LexiconEntry>(raw.size)
        var skipped = 0
        var duplicates = 0

        for (entry in raw) {
            val word = entry.word.trim()
            val code = entry.code.trim()
            if (entry.mixed || word.isEmpty() || code.isEmpty()) {
                skipped++
                continue
            }
            if (word.startsWith("#") ||
                word.any { it == '\t' || it == '\n' || it == '\r' }
            ) {
                skipped++
                continue
            }
            if (!seen.add(word + '\u0000' + code)) {
                duplicates++
                continue
            }
            entries += LexiconEntry(word = word, code = code, weight = DEFAULT_WEIGHT)
        }
        return Conversion(entries, skipped, duplicates)
    }
}

/** 解析后的一行原始词条；[mixed] 表示含英文字母，拼音码不可用。 */
internal data class RawBdictEntry(
    val word: String,
    val code: String,
    val mixed: Boolean,
)

/** .bdict 二进制读取；失败统一转成 [LexiconFormatException]。 */
internal object BaiduBdictBinaryParser {

    private const val HEADER_SIZE = 0x350
    private const val OFFSET_DATA_START = 0x40
    private const val OFFSET_DATA_LENGTH = 0x44
    private const val OFFSET_DATA_END = 0x60
    private const val MAX_SYLLABLES = 1000
    private const val MAX_ENTRIES = 1_000_000

    private const val INITIAL_ENGLISH = 0xFF

    private val MAGICS = listOf("biptbdsw", "bdict")

    private val INITIALS = arrayOf(
        "c", "d", "b", "f", "g", "h", "ch", "j", "k", "l",
        "m", "n", "", "p", "q", "r", "s", "t", "sh", "zh",
        "w", "x", "y", "z",
    )

    private val FINALS = arrayOf(
        "uang", "iang", "iong", "ang", "eng", "ian", "iao", "ing", "ong", "uai",
        "uan", "ai", "an", "ao", "ei", "en", "er", "ua", "ie", "in",
        "iu", "ou", "ia", "ue", "ui", "un", "uo", "a", "e", "i",
        "o", "u", "v",
    )

    fun parse(bytes: ByteArray): List<RawBdictEntry> {
        if (bytes.size < HEADER_SIZE) {
            throw LexiconFormatException("文件太小，不是有效的 .bdict")
        }
        return try {
            parseInternal(bytes)
        } catch (e: LexiconFormatException) {
            throw e
        } catch (e: Exception) {
            throw LexiconFormatException("解析 .bdict 失败：${e.message ?: "格式不完整"}")
        }
    }

    private fun parseInternal(bytes: ByteArray): List<RawBdictEntry> {
        checkMagic(bytes)

        var start = readUInt32(bytes, OFFSET_DATA_START).toLong()
        if (start < HEADER_SIZE || start >= bytes.size) start = HEADER_SIZE.toLong()

        var end = start + readUInt32(bytes, OFFSET_DATA_LENGTH)
        if (end <= start || end > bytes.size) {
            // 少数版本 0x44 为空，退回 0x60 记录的词条区结束位置。
            end = readUInt32(bytes, OFFSET_DATA_END).toLong()
        }
        if (end <= start || end > bytes.size) {
            throw LexiconFormatException("词条区范围异常")
        }

        val raw = ArrayList<RawBdictEntry>()
        var pos = start.toInt()
        val endPos = end.toInt()

        while (pos + 4 <= endPos) {
            val syllables = readInt32(bytes, pos)
            pos += 4
            if (syllables == 0) break
            if (syllables < 0 || syllables > MAX_SYLLABLES) {
                throw LexiconFormatException("词条长度异常：$syllables")
            }
            val needed = syllables.toLong() * 4
            if (pos + needed > endPos) {
                throw LexiconFormatException("词条数据被截断")
            }

            val code = StringBuilder(syllables * 3)
            var mixed = false
            repeat(syllables) { index ->
                val initial = bytes[pos + index * 2].toInt() and 0xFF
                val final = bytes[pos + index * 2 + 1].toInt() and 0xFF
                if (index > 0) code.append(' ')
                when {
                    initial == INITIAL_ENGLISH -> {
                        mixed = true
                        code.append(final.toChar())
                    }
                    initial < INITIALS.size && final < FINALS.size ->
                        code.append(INITIALS[initial]).append(FINALS[final])
                    else -> throw LexiconFormatException("拼音索引异常：$initial/$final")
                }
            }
            pos += syllables * 2

            val word = String(bytes, pos, syllables * 2, Charsets.UTF_16LE)
            pos += syllables * 2
            if (word.codePointCount(0, word.length) != syllables) {
                throw LexiconFormatException("词条数据损坏")
            }
            raw += RawBdictEntry(word = word, code = code.toString(), mixed = mixed)
            if (raw.size > MAX_ENTRIES) {
                throw LexiconFormatException("词条数异常")
            }
        }
        return raw
    }

    private fun checkMagic(bytes: ByteArray) {
        val magic = String(bytes, 0, 8, Charsets.US_ASCII)
        if (MAGICS.none { magic.startsWith(it) }) {
            throw LexiconFormatException("文件头不是 .bdict")
        }
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) throw LexiconFormatException("文件头不完整")
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int = readUInt32(bytes, offset)
}
