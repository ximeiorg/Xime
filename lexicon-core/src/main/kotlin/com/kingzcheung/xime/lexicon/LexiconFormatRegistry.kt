package com.kingzcheung.xime.lexicon

/**
 * 词库格式注册表：导入入口只问这里「这个文件归谁解析」。
 *
 * 新增格式：实现 [LexiconParser]，加进 [parsers] 即可；导入管线、UI 文案、
 * `SchemaManager.saveImportedFile` 都不用再改。
 *
 * `.txt` 被多种格式共用，扩展名不足以区分，用 [detect] 读内容判断。
 */
object LexiconFormats {

    private val parsers: List<LexiconParser> = listOf(
        SogouScelLexiconParser,
        BaiduBdictLexiconParser,
        LibimeBinLexiconParser,
        SougouBinLexiconParser,
        BaiduPinyinLexiconParser,
        LibimeTextLexiconParser,
        SogouPinyinLexiconParser,
    )

    /** 按扩展名认领该文件的所有格式。 */
    fun candidates(fileName: String): List<LexiconParser> =
        parsers.filter { it.matches(fileName) }

    fun hasCandidate(fileName: String): Boolean = candidates(fileName).isNotEmpty()

    /** 扩展名能唯一确定格式时返回解析器；`.txt` 这类多格式共用的返回 null。 */
    fun findByFileName(fileName: String): LexiconParser? =
        candidates(fileName).singleOrNull()

    /** 按内容在候选格式里挑一个；都不匹配返回 null。 */
    fun detect(fileName: String, bytes: ByteArray): LexiconParser? =
        candidates(fileName).firstOrNull { it.looksLike(bytes) }

    /** 给「不支持这种格式」提示用。 */
    fun supportedDescription(): String =
        parsers.joinToString("、") { it.displayName }

    /** 已注册的格式（测试/调试用）。 */
    fun registered(): List<LexiconParser> = parsers
}
