package com.kingzcheung.xime.lexicon

/**
 * Fcitx5 / libime 文本拼音词库（.txt）。
 *
 * 每行「词 拼音 [词频]」，拼音用 `'` 分音节；词面含空白/引号/反斜杠时用引号包裹并转义，
 * 与 libime `loadText` / `saveText` 一致。词频是浮点数，可省略。
 */
object LibimeTextLexiconParser : LexiconParser {

    override val id: String = "libimetxt"
    override val displayName: String = "Fcitx5/libime 文本词库"
    override val extensions: List<String> = listOf(".txt")
    override val maxBytes: Int = 32 * 1024 * 1024

    override fun looksLike(bytes: ByteArray): Boolean {
        val text = TextLexiconSupport.decodeStrict(bytes, Charsets.UTF_8) ?: return false
        return TextLexiconSupport.looksLikePinyinText(text, LibimeTextEscaping::tokenize)
    }

    override fun parse(fileName: String, bytes: ByteArray): ParsedLexicon {
        val text = TextLexiconSupport.decodeStrict(bytes, Charsets.UTF_8, Int.MAX_VALUE)
            ?: throw LexiconFormatException("不是 UTF-8 文本，无法按 libime 文本词库解析")
        val rows = ArrayList<TextLexiconSupport.Row>()
        var malformed = 0
        for (line in text.removePrefix("\uFEFF").lineSequence()) {
            if (line.isBlank()) continue
            val tokens = LibimeTextEscaping.tokenize(line)
            if (tokens.size !in 2..3) {
                malformed++
                continue
            }
            val code = TextLexiconSupport.pinyinCode(tokens[1].split('\''))
            if (code.isEmpty() || tokens[0].isBlank()) {
                malformed++
                continue
            }
            val rank = tokens.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            rows += TextLexiconSupport.Row(tokens[0], code, TextLexiconSupport.weightOf(rank))
        }
        val built = TextLexiconSupport.build(rows)
        return ParsedLexicon(
            formatId = id,
            formatName = displayName,
            codeSystem = LexiconCodeSystem.PINYIN,
            entries = built.entries,
            skipped = built.skipped + malformed,
            duplicates = built.duplicates,
        )
    }
}

/** fcitx `escapeForValue` / `consumeMaybeEscapedValue` 的对应实现。 */
internal object LibimeTextEscaping {

    private const val WHITESPACE = "\u000C\n\r\t\u000B "

    fun tokenize(line: String): List<String> {
        val tokens = ArrayList<String>(4)
        var index = 0
        while (index < line.length) {
            val consumed = consumeMaybeEscaped(line, index, tokens)
            if (consumed <= 0) break
            index = consumed
        }
        return tokens
    }

    /** 返回下一个 token 的起始下标；没有 token 时返回 [line.length]。 */
    private fun consumeMaybeEscaped(line: String, start: Int, out: MutableList<String>): Int {
        var i = start
        while (i < line.length && line[i] in WHITESPACE) i++
        if (i >= line.length) return line.length

        if (line[i] == '"') {
            val sb = StringBuilder()
            var escaping = false
            var closing = -1
            var j = i + 1
            while (j < line.length) {
                val c = line[j]
                if (!escaping) {
                    when (c) {
                        '\\' -> escaping = true
                        '"' -> {
                            closing = j + 1
                            j++
                            break
                        }
                        else -> sb.append(c)
                    }
                } else {
                    sb.append(unescape(c))
                    escaping = false
                }
                j++
            }
            if (closing > 0) {
                out += sb.toString()
                return closing
            }
        }

        var stop = i + 1
        while (stop < line.length && line[stop] !in WHITESPACE) stop++
        out += line.substring(i, stop)
        return stop
    }

    private fun unescape(c: Char): Char = when (c) {
        'n' -> '\n'
        'f' -> '\u000C'
        'r' -> '\r'
        't' -> '\t'
        'v' -> '\u000B'
        else -> c
    }
}
