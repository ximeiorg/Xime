package com.kingzcheung.xime.lexicon

/**
 * 词库格式注册表：导入入口只问这里「这个文件归谁解析」。
 *
 * 新增格式：实现 [LexiconParser]，加进 [parsers] 即可；导入管线、UI 文案、
 * `SchemaManager.saveImportedFile` 都不用再改。
 */
object LexiconFormats {

    private val parsers: List<LexiconParser> = listOf(
        SogouScelLexiconParser,
    )

    /** 按文件名找解析器；没有对应格式时返回 null，交回原有文件导入流程。 */
    fun findByFileName(fileName: String): LexiconParser? =
        parsers.firstOrNull { it.matches(fileName) }

    /** 给「不支持这种格式」提示用。 */
    fun supportedDescription(): String =
        parsers.joinToString("、") { it.displayName }

    /** 已注册的格式（测试/调试用）。 */
    fun registered(): List<LexiconParser> = parsers
}
