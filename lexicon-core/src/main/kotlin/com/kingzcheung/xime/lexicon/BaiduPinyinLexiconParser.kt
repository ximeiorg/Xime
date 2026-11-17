package com.kingzcheung.xime.lexicon

/**
 * 百度拼音文本词库（.txt，UTF-16LE）。
 *
 * 每行「词<Tab>拼音<Tab>词频」，拼音用 `'` 分音节、末尾可能带一个 `'`；
 * 只有两列的英文词条没有拼音，拼音词库用不上，跳过。
 */
object BaiduPinyinLexiconParser : LexiconParser {

    override val id: String = "bdpy"
    override val displayName: String = "百度拼音文本词库"
    override val extensions: List<String> = listOf(".txt")
    override val maxBytes: Int = 32 * 1024 * 1024

    override fun looksLike(bytes: ByteArray): Boolean {
        val text = TextLexiconSupport.decodeStrict(bytes, Charsets.UTF_16LE) ?: return false
        val body = text.removePrefix("\uFEFF")
        var lines = 0
        var matched = 0
        for (line in body.lineSequence()) {
            if (line.isBlank()) continue
            lines++
            val columns = line.split('\t')
            if (columns.size >= 2 &&
                columns[0].isNotBlank() &&
                TextLexiconSupport.pinyinCode(columns[1].split('\'').filter { it.isNotEmpty() }).isNotEmpty()
            ) {
                matched++
            }
        }
        return lines > 0 && matched * 10 >= lines * 6
    }

    override fun parse(fileName: String, bytes: ByteArray): ParsedLexicon {
        if (bytes.size % 2 != 0) {
            throw LexiconFormatException("UTF-16LE 字节数不是偶数，文件可能不完整")
        }
        val text = TextLexiconSupport.decodeStrict(bytes, Charsets.UTF_16LE, Int.MAX_VALUE)
            ?: throw LexiconFormatException("不是 UTF-16LE 文本，无法按百度拼音文本词库解析")
        val rows = ArrayList<TextLexiconSupport.Row>()
        var malformed = 0
        for (line in text.removePrefix("\uFEFF").lineSequence()) {
            if (line.isBlank()) continue
            val columns = line.split('\t')
            if (columns.size < 2) {
                malformed++
                continue
            }
            val code = TextLexiconSupport.pinyinCode(columns[1].split('\'').filter { it.isNotEmpty() })
            val word = columns[0].trim()
            if (code.isEmpty() || word.isEmpty()) {
                malformed++
                continue
            }
            val rank = columns.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
            rows += TextLexiconSupport.Row(word, code, TextLexiconSupport.weightOf(rank))
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
