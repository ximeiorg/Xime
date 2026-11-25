package com.kingzcheung.xime.lexicon

/**
 * 搜狗拼音文本词库（.txt，GBK）。
 *
 * 每行形如 `'ni'hao 你好`：行首 `'` 后是拼音，空格后是词。没有词频。
 */
object SogouPinyinLexiconParser : LexiconParser {

    override val id: String = "sgpy"
    override val displayName: String = "搜狗拼音文本词库"
    override val extensions: List<String> = listOf(".txt")
    override val maxBytes: Int = 32 * 1024 * 1024

    private val lineRegex = Regex("""^'[a-zA-Z']+\s+\S.*$""")

    override fun looksLike(bytes: ByteArray): Boolean {
        val text = TextLexiconSupport.decodeStrict(bytes, java.nio.charset.Charset.forName("GBK")) ?: return false
        var lines = 0
        var matched = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            lines++
            if (lineRegex.matches(line.trim())) matched++
        }
        return lines > 0 && matched * 10 >= lines * 6
    }

    override fun parse(fileName: String, bytes: ByteArray): ParsedLexicon {
        val text = TextLexiconSupport.decodeStrict(bytes, java.nio.charset.Charset.forName("GBK"), Int.MAX_VALUE)
            ?: throw LexiconFormatException("不是 GBK 文本，无法按搜狗拼音文本词库解析")
        val rows = ArrayList<TextLexiconSupport.Row>()
        var malformed = 0
        for (line in text.removePrefix("\uFEFF").lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (!trimmed.startsWith("'")) {
                malformed++
                continue
            }
            val body = trimmed.substring(1)
            val space = body.indexOf(' ')
            if (space <= 0) {
                malformed++
                continue
            }
            val code = TextLexiconSupport.pinyinCode(body.substring(0, space).split('\''))
            val word = body.substring(space + 1).trim()
            if (code.isEmpty() || word.isEmpty()) {
                malformed++
                continue
            }
            rows += TextLexiconSupport.Row(word, code)
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
