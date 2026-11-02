package com.kingzcheung.xime.settings

import android.content.Context
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.kingzcheung.xime.scheme.SchemeFiles
import com.kingzcheung.xime.util.FileLogger
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * 把用户级模糊拼音设置物化成各方案的 `<schema>.custom.yaml` 补丁。
 *
 * 状态只存在 prefs；custom 文件是派生物。换方案集时补丁随方案内容删除，
 * 下次启动/部署由 [reconcile] 按 prefs 重放。
 */
object FuzzyPinyinManager {

    private const val TAG = "FuzzyPinyin"

    enum class SchemaStatus {
        SUPPORTED,
        NO_SCHEMA,
        NO_ALGEBRA,
        NOT_PINYIN,
        DOUBLE_PINYIN,
        T9,
        SHAPE,
    }

    enum class SkipReason {
        UNSUPPORTED,
        USER_PATCH_CONFLICT,
        IO_ERROR,
    }

    data class SchemaSupport(
        val schemaId: String,
        val displayName: String,
        val supported: Boolean,
        val status: SchemaStatus,
    )

    data class Skip(val schemaId: String, val reason: SkipReason)

    data class ReconcileResult(
        val changedSchemas: List<String>,
        val appliedSchemas: List<String>,
        val skipped: List<Skip>,
    )

    internal data class WriteResult(
        val changed: Boolean,
        val conflict: Boolean,
        val error: Boolean,
    )

    fun getSelectedRules(context: Context): Set<String> =
        FuzzyPinyinRules.sanitize(SettingsPreferences.getFuzzyPinyinRules(context))

    fun setSelectedRules(context: Context, rules: Set<String>) {
        SettingsPreferences.setFuzzyPinyinRules(context, FuzzyPinyinRules.sanitize(rules))
    }

    /** 当前启用方案里哪些能应用模糊拼音（设置页展示用）。 */
    fun applicableSchemas(context: Context): List<SchemaSupport> {
        val rimeDir = SchemeFiles.rimeDir(context)
        return SchemaManager.getEnabledSchemas(context).mapNotNull { schemaId ->
            if (!File(rimeDir, "$schemaId.schema.yaml").isFile) return@mapNotNull null
            val status = inspectSchema(rimeDir, schemaId)
            SchemaSupport(
                schemaId = schemaId,
                displayName = SchemaManager.getSchemaDisplayName(context, schemaId) ?: schemaId,
                supported = status == SchemaStatus.SUPPORTED,
                status = status,
            )
        }
    }

    /**
     * 对账：目标方案写入当前规则的托管块，其余方案摘掉托管块。
     *
     * 只改文件，不触发部署；调用方（启动/部署/设置页）决定何时 deploy。
     */
    fun reconcile(context: Context): ReconcileResult = reconcile(
        rimeDir = SchemeFiles.rimeDir(context),
        enabledSchemas = SchemaManager.getEnabledSchemas(context),
        selectedRules = getSelectedRules(context),
    )

    /** 纯文件对账（可单测）：不依赖 Android Context / prefs。 */
    internal fun reconcile(
        rimeDir: File,
        enabledSchemas: Collection<String>,
        selectedRules: Collection<String>,
    ): ReconcileResult {
        val selected = FuzzyPinyinRules.sanitize(selectedRules)
        val patterns = FuzzyPinyinRules.patternsFor(selected)
        val enabled = enabledSchemas.toSet()
        val installed = SchemeFiles.installedSchemaIds(rimeDir)
        val statuses = installed.associateWith { inspectSchema(rimeDir, it) }

        val targets = if (patterns.isEmpty()) {
            emptySet()
        } else {
            installed.filter { it in enabled && statuses[it] == SchemaStatus.SUPPORTED }.toSet()
        }

        val changed = mutableListOf<String>()
        val skipped = mutableListOf<Skip>()
        for (schemaId in installed) {
            val file = File(rimeDir, "$schemaId.custom.yaml")
            val write = updateCustomFile(file, if (schemaId in targets) patterns else emptyList())
            when {
                write.conflict -> skipped += Skip(schemaId, SkipReason.USER_PATCH_CONFLICT)
                write.error -> skipped += Skip(schemaId, SkipReason.IO_ERROR)
            }
            if (write.changed) changed += schemaId
        }

        // 备份恢复可能带回已卸载方案遗留的 custom 文件：只清理带我们标记的文件。
        rimeDir.listFiles { file -> file.isFile && file.name.endsWith(".custom.yaml") }
            ?.forEach { file ->
                val schemaId = file.name.removeSuffix(".custom.yaml")
                if (schemaId in installed) return@forEach
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
                if (!text.contains(FuzzyPinyinPatch.BEGIN)) return@forEach
                val write = updateCustomFile(file, emptyList())
                if (write.changed) changed += schemaId
            }

        val skippedIds = skipped.map { it.schemaId }.toSet()
        val applied = (targets - skippedIds).sorted()
        if (skipped.isNotEmpty()) {
            FileLogger.w(TAG, "fuzzy reconcile skipped: ${skipped.joinToString { "${it.schemaId}:${it.reason}" }}")
        }
        return ReconcileResult(
            changedSchemas = changed.distinct().sorted(),
            appliedSchemas = applied,
            skipped = skipped,
        )
    }

    /** 解析单个方案是否适合模糊拼音。保守判定：宁可少识别，不误改五笔/双拼/九键。 */
    internal fun inspectSchema(rimeDir: File, schemaId: String): SchemaStatus {
        val file = File(rimeDir, "$schemaId.schema.yaml")
        if (!file.isFile) return SchemaStatus.NO_SCHEMA
        val text = runCatching { file.readText(Charsets.UTF_8).removePrefix("\uFEFF") }
            .getOrNull() ?: return SchemaStatus.NO_SCHEMA
        val root = runCatching { SchemaManager.yaml.parseToYamlNode(text) as? YamlMap }
            .getOrNull() ?: return SchemaStatus.NOT_PINYIN

        val schemaName = (root["schema"] as? YamlMap)
            ?.let { (it["name"] as? YamlScalar)?.content }
            .orEmpty()
        val idAndName = "$schemaId $schemaName".lowercase(Locale.ROOT)

        if (idAndName.contains("t9") || idAndName.contains("九键") ||
            idAndName.contains("九宫") || idAndName.contains("九宮")
        ) {
            return SchemaStatus.T9
        }
        if (idAndName.contains("double_pinyin") || idAndName.contains("双拼") ||
            idAndName.contains("雙拼")
        ) {
            return SchemaStatus.DOUBLE_PINYIN
        }
        if (SHAPE_MARKERS.any { idAndName.contains(it) }) {
            return SchemaStatus.SHAPE
        }

        val speller = root["speller"] as? YamlMap ?: return SchemaStatus.NO_ALGEBRA
        val algebra = (speller["algebra"] as? YamlList)
            ?.items
            ?.mapNotNull { (it as? YamlScalar)?.content }
            .orEmpty()
        if (algebra.isEmpty()) return SchemaStatus.NO_ALGEBRA

        // 双拼用大写中间码，九键把字母映射成数字；这两类追加小写拼音规则会污染。
        if (algebra.any { rule -> rule.any { it.isUpperCase() } }) {
            return SchemaStatus.DOUBLE_PINYIN
        }
        if (algebra.any { DIGIT_ALGEBRA.containsMatchIn(it) }) {
            return SchemaStatus.T9
        }

        val hasPinyinName = idAndName.contains("pinyin") || idAndName.contains("拼音")
        val hasPinyinAlgebra = algebra.any { rule ->
            rule.startsWith("abbrev/") ||
                rule.contains("([jqxy])u") ||
                rule.contains("([nl])ue") ||
                rule.contains("([iu])a(o|ng?)")
        }
        return if (hasPinyinName || hasPinyinAlgebra) {
            SchemaStatus.SUPPORTED
        } else {
            SchemaStatus.NOT_PINYIN
        }
    }

    internal fun updateCustomFile(file: File, patterns: List<String>): WriteResult {
        val exists = file.isFile
        val original = if (exists) {
            runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
                FileLogger.w(TAG, "read ${file.name} failed", it)
                return WriteResult(changed = false, conflict = false, error = true)
            }
        } else {
            ""
        }

        // 没有我们的块且本次不写入：不要碰用户文件，避免无意义 mtime 变化触发部署。
        if (patterns.isEmpty() && !original.contains(FuzzyPinyinPatch.BEGIN)) {
            return WriteResult(changed = false, conflict = false, error = false)
        }

        val patch = FuzzyPinyinPatch.upsert(original, patterns)
        if (!patch.changed) {
            return WriteResult(changed = false, conflict = patch.conflict, error = false)
        }
        return try {
            if (patch.content.isBlank()) {
                if (exists) file.delete()
            } else {
                writeAtomically(file, patch.content)
            }
            WriteResult(changed = true, conflict = patch.conflict, error = false)
        } catch (e: Exception) {
            FileLogger.w(TAG, "write ${file.name} failed", e)
            WriteResult(changed = false, conflict = patch.conflict, error = true)
        }
    }

    private fun writeAtomically(file: File, content: String) {
        val dir = file.parentFile ?: error("no parent dir: ${file.path}")
        dir.mkdirs()
        val tmp = File.createTempFile("${file.name}.", ".tmp", dir)
        try {
            tmp.writeText(content, Charsets.UTF_8)
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private val SHAPE_MARKERS = listOf(
        "wubi", "五笔", "五筆",
        "stroke", "笔画", "筆畫",
        "handwriting", "手写", "手寫",
        "number", "数字", "數字",
    )

    private val DIGIT_ALGEBRA = Regex("""(?:derive|xform|fuzz)/\[[^\]]*]/\d""")
}
