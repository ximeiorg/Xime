package com.kingzcheung.xime.lexicon

import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 文本词库共用的词条转换与内容探测。 */
internal object TextLexiconSupport {

    const val DEFAULT_WEIGHT = 1
    private const val MAX_WEIGHT = 1_000_000
    private const val SNIFF_BYTES = 64 * 1024

    /** 解析后的一行原始词条；weight 为 0 表示没有词频。 */
    data class Row(val word: String, val code: String, val weight: Int = 0)

    data class Built(
        val entries: List<LexiconEntry>,
        val skipped: Int,
        val duplicates: Int,
    )

    fun weightOf(rank: Double): Int =
        if (rank.isFinite() && rank > DEFAULT_WEIGHT) {
            rank.toLong().coerceAtMost(MAX_WEIGHT.toLong()).toInt()
        } else {
            DEFAULT_WEIGHT
        }

    fun weightOf(rank: Int): Int =
        if (rank > DEFAULT_WEIGHT) rank.coerceAtMost(MAX_WEIGHT) else DEFAULT_WEIGHT

    /** 词面/编码归一化并去重；空词、空码、控制字符、注释行按跳过处理。 */
    fun build(rows: List<Row>): Built {
        val seen = HashSet<String>(rows.size * 2)
        val entries = ArrayList<LexiconEntry>(rows.size)
        var skipped = 0
        var duplicates = 0
        for (row in rows) {
            val word = row.word.trim()
            val code = row.code.trim()
            if (word.isEmpty() || code.isEmpty()) {
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
            entries += LexiconEntry(
                word = word,
                code = code,
                weight = weightOf(row.weight),
            )
        }
        return Built(entries, skipped, duplicates)
    }

    /** 严格解码；非法字节返回 null（用于区分 UTF-8 / GBK 内容）。 */
    fun decodeStrict(bytes: ByteArray, charset: Charset, limit: Int = SNIFF_BYTES): String? {
        val head = if (bytes.size > limit) bytes.copyOf(limit) else bytes
        // 探测切片可能截断多字节字符，从尾部回退几个字节再试。
        for (cut in 0..minOf(3, head.size)) {
            val slice = if (cut == 0) head else head.copyOf(head.size - cut)
            try {
                return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(slice))
                    .toString()
            } catch (_: CharacterCodingException) {
                // 继续回退
            }
        }
        return null
    }

    /** 拼音编码：音节用空格分隔、小写；不像拼音时返回空串。 */
    fun pinyinCode(syllables: List<String>): String {
        if (syllables.isEmpty()) return ""
        val normalized = ArrayList<String>(syllables.size)
        for (syllable in syllables) {
            val s = syllable.trim().lowercase()
            if (s.isEmpty() || s.length > 6 || s.any { it !in 'a'..'z' }) return ""
            normalized += s
        }
        return normalized.joinToString(" ")
    }

    /** 汉字（含扩展区/兼容区），用于把词库文本和普通英文文本区分开。 */
    fun containsCjk(text: String): Boolean = text.any { c ->
        c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF ||
            c.code in 0xF900..0xFAFF || c.code in 0x20000..0x2FA1F
    }

    /** 判断文本是否整体像「2~3 列、第二列是拼音」的词库。 */
    fun looksLikePinyinText(text: String, tokenize: (String) -> List<String>): Boolean {
        var lines = 0
        var matched = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            lines++
            val tokens = tokenize(line)
            if (tokens.size in 2..3 &&
                tokens[0].isNotBlank() &&
                containsCjk(tokens[0]) &&
                pinyinCode(tokens[1].split('\'').filter { it.isNotEmpty() }).isNotEmpty()
            ) {
                matched++
            }
        }
        return lines > 0 && matched * 10 >= lines * 6
    }
}
