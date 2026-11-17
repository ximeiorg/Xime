package com.kingzcheung.xime.lexicon

import java.io.IOException

/** 词库的编码体系；导入时据此选择可挂载的方案。 */
enum class LexiconCodeSystem {
    PINYIN,
    WUBI,
    OTHER,
}

/** 解析器输出的统一词条：词 + 已经整理成 Rime 可用的码 + 权重。 */
data class LexiconEntry(
    val word: String,
    val code: String,
    val weight: Int = 1,
)

/** 一次词库解析的结果；后续导入管线只依赖这个模型，不认具体格式。 */
data class ParsedLexicon(
    val formatId: String,
    val formatName: String,
    val codeSystem: LexiconCodeSystem,
    val entries: List<LexiconEntry>,
    val skipped: Int = 0,
    val duplicates: Int = 0,
)

/** 词库内容不符合该格式。 */
class LexiconFormatException(message: String) : IOException(message)

/**
 * 词库格式解析器。新增格式时实现本接口并注册到 [LexiconFormats]。
 *
 * 约定：
 * - `parse` 只做格式解析和归一化，不写文件、不部署；
 * - 输出的 [LexiconEntry.code] 必须已经是目标编码体系下的 Rime 码
 *   （拼音用空格分音节，如 `ni hao`）；
 * - 空词/空码/重复项由解析器自行处理，并通过 [ParsedLexicon.skipped] /
 *   [ParsedLexicon.duplicates] 报告。
 */
interface LexiconParser {

    /** 稳定标识，用于日志/清单。 */
    val id: String

    /** 用户可读的格式名，如「搜狗细胞词库」。 */
    val displayName: String

    /** 认领的扩展名（含点，小写比较）。 */
    val extensions: List<String>

    /** 单文件大小上限；导入前按此限制读取。 */
    val maxBytes: Int

    /** 按文件名认领格式；默认只比扩展名。 */
    fun matches(fileName: String): Boolean =
        extensions.any { fileName.endsWith(it, ignoreCase = true) }

    @Throws(LexiconFormatException::class)
    fun parse(fileName: String, bytes: ByteArray): ParsedLexicon
}
