package com.kingzcheung.xime.settings

/**
 * `<schema>.custom.yaml` 里模糊拼音托管块的纯文本读写。
 *
 * 只动 `# >>> xime:fuzzy-pinyin` 与 `# <<< xime:fuzzy-pinyin` 之间的内容，
 * 不覆盖用户或其他功能（个人词库/T9/纠错）写入的 patch。
 */
internal object FuzzyPinyinPatch {

    const val BEGIN = "# >>> xime:fuzzy-pinyin (managed)"
    const val END = "# <<< xime:fuzzy-pinyin (managed)"

    private val patchHeader = Regex("""(?m)^patch[ \t]*:[ \t]*(?:\{\s*\})?[ \t]*$""")
    private val anyPatchHeader = Regex("""(?m)^patch[ \t]*:""")
    private val userAlgebra = Regex("""(?m)^[ \t]*["']?speller/algebra(?:/\+)?["']?[ \t]*:""")

    data class Result(
        val content: String,
        val changed: Boolean,
        /** 用户已手写 speller/algebra patch：不合并，交给调用方跳过并提示。 */
        val conflict: Boolean = false,
    )

    fun upsert(text: String, patterns: List<String>): Result {
        val source = text.removePrefix("\uFEFF")
        val base = stripDocumentEnd(strip(source)).trimEnd()

        if (patterns.isEmpty()) {
            val cleaned = cleanupEmptyPatch(base)
            val content = if (cleaned.isBlank()) "" else "$cleaned\n"
            return Result(content, content != source)
        }

        // 用户自己的 speller/algebra 补丁不硬合并，避免重复 key / 规则叠加；
        // 同时摘掉之前的托管块，避免旧规则继续生效。
        if (userAlgebra.containsMatchIn(base)) {
            val stripped = strip(source)
            return Result(stripped, stripped != source, conflict = true)
        }

        val block = buildBlock(patterns)
        val content = when {
            base.isBlank() -> "patch:\n$block"
            patchHeader.containsMatchIn(base) -> {
                val match = patchHeader.find(base)!!
                (base.substring(0, match.range.first) +
                    "patch:\n$block" +
                    base.substring(match.range.last + 1)).trimEnd() + "\n"
            }
            // `patch: {foo: bar}` 这类行内 map 不冒险改写，交给调用方跳过。
            anyPatchHeader.containsMatchIn(base) ->
                return Result(source, changed = false, conflict = true)
            else -> "$base\n\npatch:\n$block"
        }
        return Result(content, content != source)
    }

    /** 摘掉托管块；没有标记时原样返回。 */
    fun strip(text: String): String {
        if (!text.contains(BEGIN)) return text
        val out = ArrayList<String>()
        var inside = false
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed == BEGIN) {
                inside = true
                continue
            }
            if (inside) {
                if (trimmed == END) inside = false
                continue
            }
            out.add(line)
        }
        return out.joinToString("\n")
    }

    private fun buildBlock(patterns: List<String>): String = buildString {
        append(BEGIN).append('\n')
        append("  \"speller/algebra/+\":\n")
        for (pattern in patterns) {
            append("    - \"").append(pattern).append("\"\n")
        }
        append(END).append('\n')
    }

    /** 只剩我们创建的空 `patch:` 头时返回空串，调用方可删除文件。 */
    private fun cleanupEmptyPatch(base: String): String {
        val trimmed = base.trim()
        return if (trimmed.isEmpty() || trimmed == "patch:" || trimmed == "patch: {}") "" else base
    }

    private fun stripDocumentEnd(text: String): String {
        val trimmed = text.trimEnd()
        return if (trimmed.endsWith("...")) trimmed.dropLast(3).trimEnd() else trimmed
    }
}
