package com.kingzcheung.xime.dictionary

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.R
import com.kingzcheung.xime.i18n.tr
import com.kingzcheung.xime.scheme.SchemeDeployer
import com.kingzcheung.xime.scheme.SchemeFiles
import com.kingzcheung.xime.lexicon.LexiconCodeSystem
import com.kingzcheung.xime.lexicon.LexiconFormats
import com.kingzcheung.xime.data.db.ImportedLexiconDao
import com.kingzcheung.xime.data.db.ImportedLexiconEntity
import com.kingzcheung.xime.data.db.XimeDatabase
import com.kingzcheung.xime.lexicon.ParsedLexicon
import com.kingzcheung.xime.settings.DictEntry
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 导入词库：把外部词库转换成派生 `.dict.yaml`，再通过
 * `<方案>.custom.yaml` 的 `translator/packs` 挂到方案上。
 *
 * 不改用户已有的 `.dict.yaml` / `.schema.yaml`，也不写 userdb/leveldb。
 * 具体格式由 [LexiconFormats] 里的解析器负责；本类只处理「统一词条模型 -> 派生词典 -> packs」。
 */
object ImportedDictManager {

    private const val TAG = "ImportedDict"

    private const val PACK_PREFIX = "xime_import_"
    private const val MANIFEST_DIR = "imported_dicts"
    private const val MANIFEST_FILE = "manifest.json"
    private const val BLOCK_START = "# >>> xime-imported-dicts"
    private const val BLOCK_END = "# <<< xime-imported-dicts"

    @Serializable
    data class ImportedDict(
        val id: String,
        val displayName: String,
        val entryCount: Int,
        val attachedSchemas: List<String> = emptyList(),
        val namespaces: Map<String, String> = emptyMap(),
        val createdAt: Long = 0L,
        /** 解析器格式 id。 */
        val formatId: String = "",
        /** 解析器格式名（如「搜狗细胞词库」），仅用于展示。 */
        val formatName: String = "",
        /** 用户选择的原文件名。 */
        val sourceName: String = "",
        /** [ParsedLexicon.codeSystem] 的名字，仅用于展示。 */
        val codeSystem: String = "PINYIN",
        /** 停用后保留文件和记录，只从 packs 托管块里摘掉。 */
        val enabled: Boolean = true,
    )

    @Serializable
    private data class Manifest(val items: List<ImportedDict> = emptyList())

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    data class SchemeOption(val id: String, val name: String)

    /** 可挂拼音词库的已启用方案；用户必须自己选，不能自动决定。 */
    fun compatiblePinyinSchemes(context: Context): List<SchemeOption> =
        SchemaManager.getEnabledSchemas(context)
            .filter { isPinyinScheme(context, it) }
            .map { schemaId ->
                SchemeOption(
                    id = schemaId,
                    name = SchemaManager.getSchemaDisplayName(context, schemaId) ?: schemaId,
                )
            }

    /**
     * 导入一本已解析的词库，挂到用户明确选择的方案上。
     *
     * 交互式导入要求 [schemeIds] 非空：多方案场景下 app 不能替用户决定。
     * 网页 / URL 等非交互入口传 [allowUnbound] = true，先入库不挂载，等用户到
     * 「词库管理」里选方案。
     */
    suspend fun importLexicon(
        context: Context,
        parsed: ParsedLexicon,
        sourceName: String,
        schemeIds: List<String>,
        allowUnbound: Boolean = false,
    ): Result<ImportedDict> = runCatching {
        if (parsed.entries.isEmpty()) {
            throw IOException(tr(R.string.s_ciku_wuneirong))
        }
        if (parsed.codeSystem != LexiconCodeSystem.PINYIN) {
            throw IOException(
                tr(R.string.s_ciku_geshibuzhichi, LexiconFormats.supportedDescription())
            )
        }
        val selected = schemeIds.distinct()
        if (selected.isEmpty() && !allowUnbound) {
            throw IOException(tr(R.string.s_ciku_xuanzefangan))
        }
        val namespaces = if (selected.isEmpty()) emptyMap() else resolveNamespaces(context, selected)

        Log.i(TAG, "import ${parsed.formatId}: entries=${parsed.entries.size}, " +
            "schemes=$selected")
        val entries = parsed.entries.map { DictEntry(it.word, it.code, it.weight) }
        importEntries(
            context = context,
            entries = entries,
            sourceName = sourceName,
            schemeIds = selected,
            namespaces = namespaces,
            parsedFormatId = parsed.formatId,
            parsedFormatName = parsed.formatName,
            parsedCodeSystem = parsed.codeSystem.name,
        )
    }

    /**
     * 方案包安装/切换后对齐绑定。
     *
     * 派生词典作为用户数据跨包保留；记录里已不存在的方案从绑定中摘掉，
     * 失去全部方案的记录保留为「未挂载」，等用户重新选方案（不删用户导入的词库）。
     * 同时重写各方案 `.custom.yaml` 的 packs 托管块——包内容重建会丢掉旧 custom.yaml。
     */
    suspend fun reconcileAfterPackageChange(context: Context): Result<Unit> = runCatching {
        val rimeDir = SchemaManager.getRimeDir(context)
        val existing = SchemeFiles.installedSchemaIds(rimeDir).toSet()
        val all = list(context)
        val updated = ArrayList<ImportedDict>(all.size)
        var changed = false
        for (record in all) {
            val keep = record.attachedSchemas.filter { it in existing }
            val next = if (keep.size == record.attachedSchemas.size) {
                record
            } else {
                changed = true
                record.copy(
                    attachedSchemas = keep,
                    namespaces = record.namespaces.filterKeys { it in keep },
                    enabled = record.enabled && keep.isNotEmpty(),
                )
            }
            updated += next
        }
        if (changed) {
            val dao = dao(context)
            updated.forEach { dao.upsert(it.toEntity()) }
            FileLogger.i(TAG, "reconciled imported lexicons after package change")
        }
        val schemes = updated.flatMap { it.attachedSchemas }.distinct()
        if (schemes.isNotEmpty()) {
            applyPacksForSchemes(context, schemes, updated)
        }
    }

    /** 删除一本导入词库：摘掉 packs、删派生词典、部署、更新清单。 */
    suspend fun delete(context: Context, id: String): Result<Unit> = runCatching {
        val all = list(context)
        val record = all.firstOrNull { it.id == id }
            ?: throw IOException("词库记录不存在")
        val remaining = all.filter { it.id != id }
        val backups = applyPacksForSchemes(
            context = context,
            schemeIds = record.attachedSchemas,
            records = remaining,
            namespaceHints = record.namespaces,
        )
        val rimeDir = SchemaManager.getRimeDir(context)
        val dictFile = File(rimeDir, "${record.id}.dict.yaml")
        val backupFile = File(context.cacheDir, "${record.id}.dict.yaml.bak")
        val hadDict = dictFile.isFile
        try {
            if (hadDict) {
                dictFile.copyTo(backupFile, overwrite = true)
                dictFile.delete()
            }
            SchemeDeployer.deploy(context)
            File(rimeDir, "build/${record.id}.table.bin").delete()
            dao(context).delete(record.id)
        } catch (e: Exception) {
            if (hadDict) backupFile.copyTo(dictFile, overwrite = true)
            restorePacks(backups)
            backupFile.delete()
            throw e
        }
        backupFile.delete()
    }

    /** 启用/停用：停用只摘 packs，保留派生词典和记录。 */
    suspend fun setEnabled(context: Context, id: String, enabled: Boolean): Result<Unit> = runCatching {
        val all = list(context)
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) throw IOException("词库记录不存在")
        if (all[index].attachedSchemas.isEmpty()) {
            throw IOException(tr(R.string.s_ciku_xuanzefangan))
        }
        val updated = all.toMutableList()
        updated[index] = all[index].copy(enabled = enabled)
        val record = updated[index]
        val backups = applyPacksForSchemes(
            context = context,
            schemeIds = record.attachedSchemas,
            records = updated,
            namespaceHints = record.namespaces,
        )
        try {
            SchemeDeployer.deploy(context)
            if (enabled) {
                val table = File(SchemaManager.getRimeDir(context), "build/${record.id}.table.bin")
                if (!table.isFile) throw IOException(tr(R.string.s_ciku_meiyouchanwu, record.id))
            }
            dao(context).setEnabled(record.id, enabled)
        } catch (e: Exception) {
            restorePacks(backups)
            runCatching { SchemeDeployer.deploy(context) }
            throw e
        }
    }

    private data class CustomBackup(val file: File, val original: String?)

    /** 按期望的清单重写各方案的 packs 托管块；返回原文件内容用于回滚。 */
    private fun applyPacksForSchemes(
        context: Context,
        schemeIds: Collection<String>,
        records: List<ImportedDict>,
        namespaceHints: Map<String, String> = emptyMap(),
    ): List<CustomBackup> {
        val rimeDir = SchemaManager.getRimeDir(context)
        val backups = mutableListOf<CustomBackup>()
        try {
            for (schemeId in schemeIds.distinct()) {
                val namespace = namespaceHints[schemeId]
                    ?: records.firstOrNull { schemeId in it.attachedSchemas }
                        ?.namespaces?.get(schemeId)
                    ?: "translator"
                val custom = File(rimeDir, "$schemeId.custom.yaml")
                val original = if (custom.isFile) custom.readText(Charsets.UTF_8) else null
                val packs = records
                    .filter {
                        it.enabled && schemeId in it.attachedSchemas &&
                            (it.namespaces[schemeId] ?: "translator") == namespace
                    }
                    .map { it.id }

                // 方案原文件里声明的 packs 可能指向不存在的词典（如 pinyin_simp 的
                // user_simp）。这种悬空项排在最前会让 librime 的 pack 循环丢掉
                // syllabary，导致后面导入词库编出 0 条目表；不碰原 schema，
                // 在 custom.yaml 里改成显式覆盖 packs，只保留有源文件的那几项。
                val schemaFile = File(rimeDir, "$schemeId.schema.yaml")
                val declared = if (schemaFile.isFile) {
                    parsePacks(schemaFile.readText(Charsets.UTF_8), namespace)
                } else {
                    emptyList()
                }
                val validDeclared = declared.filter { File(rimeDir, "$it.dict.yaml").isFile }
                val hasDangling = validDeclared.size != declared.size
                val finalPacks = if (hasDangling) {
                    (validDeclared + packs).distinct()
                } else {
                    packs
                }

                if (original == null && finalPacks.isEmpty()) continue
                backups += CustomBackup(custom, original)
                custom.parentFile?.mkdirs()
                custom.writeText(
                    upsertManagedPackBlock(
                        original ?: "",
                        namespace,
                        finalPacks,
                        replacePacks = hasDangling,
                    ),
                    Charsets.UTF_8,
                )
            }
        } catch (e: Exception) {
            restorePacks(backups)
            throw e
        }
        return backups
    }

    private fun restorePacks(backups: List<CustomBackup>) {
        for (backup in backups) {
            val original = backup.original
            if (original == null) {
                backup.file.delete()
            } else {
                backup.file.writeText(original, Charsets.UTF_8)
            }
        }
    }

    /** 已导入的词库（Room 元数据 + 文件索引）。 */
    suspend fun list(context: Context): List<ImportedDict> {
        ensureLegacyMigrated(context)
        val dao = dao(context)
        return dao.getAll().map { entity ->
            val (schemas, namespaces) = decodeSchemes(entity.schemes)
            ImportedDict(
                id = entity.id,
                displayName = entity.displayName,
                entryCount = entity.entryCount,
                attachedSchemas = schemas,
                namespaces = namespaces,
                createdAt = entity.createdAt,
                formatId = entity.formatId,
                formatName = entity.formatName,
                sourceName = entity.sourceName,
                codeSystem = entity.codeSystem,
                enabled = entity.enabled,
            )
        }
    }

    internal fun buildDictYaml(
        id: String,
        entries: List<DictEntry>,
        sourceName: String = "",
    ): String {
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "非法词库名：$id" }
        val sb = StringBuilder()
        sb.append("# Rime dictionary\n")
        sb.append("# encoding: utf-8\n")
        sourceName.trim().takeIf { it.isNotEmpty() }?.let {
            sb.append("# imported from: ").append(it.replace('\n', ' ').replace('\r', ' ')).append('\n')
        }
        sb.append("---\n")
        sb.append("name: ").append(id).append('\n')
        sb.append("version: \"1\"\n")
        sb.append("sort: by_weight\n")
        sb.append("use_preset_vocabulary: false\n")
        sb.append("...\n")
        for (entry in entries) {
            sb.append(entry.word).append('\t').append(entry.code)
            entry.weight?.let { sb.append('\t').append(it) }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 流式写派生词典，避免大词库在内存里再拼一份完整文本。 */
    internal fun writeDictYaml(
        file: File,
        id: String,
        entries: List<DictEntry>,
        sourceName: String = "",
    ) {
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "非法词库名：$id" }
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append("# Rime dictionary\n")
            writer.append("# encoding: utf-8\n")
            sourceName.trim().takeIf { it.isNotEmpty() }?.let {
                writer.append("# imported from: ")
                    .append(it.replace('\n', ' ').replace('\r', ' '))
                    .append('\n')
            }
            writer.append("---\n")
            writer.append("name: ").append(id).append('\n')
            writer.append("version: \"1\"\n")
            writer.append("sort: by_weight\n")
            writer.append("use_preset_vocabulary: false\n")
            writer.append("...\n")
            for (entry in entries) {
                writer.append(entry.word).append('\t').append(entry.code)
                entry.weight?.let { writer.append('\t').append(it.toString()) }
                writer.append('\n')
            }
        }
    }

    /**
     * 写入/替换 app 托管的 packs 块。保留 custom.yaml 里用户自己的内容。
     * [packNames] 为空时只移除托管块。
     */
    internal fun upsertManagedPackBlock(
        original: String,
        namespace: String,
        packNames: List<String>,
        /** true = 直接覆盖 `packs`；false = 追加到现有 `packs`。 */
        replacePacks: Boolean = false,
    ): String {
        val without = removeManagedBlock(original)
        val cleaned = without.trimEnd()
        if (packNames.isEmpty()) {
            return if (cleaned.isBlank()) "" else "$cleaned\n"
        }
        packNames.forEach {
            require(it.matches(Regex("[A-Za-z0-9_-]+"))) { "非法 packs 项：$it" }
        }

        val key = if (replacePacks) {
            "\"$namespace/packs\""
        } else {
            "\"$namespace/packs/+\""
        }
        val block = buildString {
            append("  ").append(BLOCK_START).append('\n')
            append("  ").append(key).append(":\n")
            packNames.distinct().forEach { append("    - ").append(it).append('\n') }
            append("  ").append(BLOCK_END)
        }

        if (cleaned.isBlank()) return "patch:\n$block\n"

        val inlineEmpty = Regex("(?m)^patch:[ \\t]*\\{[ \\t]*\\}[ \\t]*$")
        val base = if (inlineEmpty.containsMatchIn(cleaned)) {
            inlineEmpty.replaceFirst(cleaned, "patch:")
        } else {
            cleaned
        }
        val patchLine = Regex("(?m)^patch:[ \\t]*(?:#.*)?$").find(base)
            ?: return "$base\n\npatch:\n$block\n"
        val insertAt = patchLine.range.last + 1
        return base.substring(0, insertAt) + "\n" + block + base.substring(insertAt) + "\n"
    }

    internal fun removeManagedBlock(text: String): String {
        val pattern = Regex(
            "(?ms)^[ \\t]*${Regex.escape(BLOCK_START)}.*?^[ \\t]*${Regex.escape(BLOCK_END)}[ \\t]*\\r?\\n?"
        )
        return pattern.replace(text, "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trimEnd()
    }

    /**
     * 读取 schema 顶层的 `<namespace>/packs` 列表（纯文本解析，不引入完整 YAML 解析，
     * 也避免兼容 `__include`/锚点等 Rime 扩展写法）。
     */
    internal fun parsePacks(schemaText: String, namespace: String): List<String> {
        val result = ArrayList<String>()
        var inSection = false
        var inPacks = false
        for (raw in schemaText.lineSequence()) {
            val line = raw.substringBefore('#').trimEnd()
            if (line.isBlank()) continue
            val indent = line.length - line.trimStart().length
            val trimmed = line.trim()
            if (!inSection) {
                if (indent == 0 && trimmed == "$namespace:") inSection = true
                continue
            }
            if (indent == 0) break
            if (!inPacks) {
                if (indent == 2 && trimmed == "packs:") inPacks = true
                continue
            }
            if (trimmed.startsWith("- ")) {
                val name = trimmed.removePrefix("- ").trim().trim('"', '\'')
                if (name.isNotEmpty()) result += name
            } else if (indent <= 2) {
                inPacks = false
            }
        }
        return result
    }

    internal data class TranslatorInfo(val component: String, val namespace: String)

    /** 从 schema 的 `engine/translators` 里找第一个 table/script translator。 */
    internal fun parseMainTranslator(schemaText: String): TranslatorInfo? {
        var inTranslators = false
        for (raw in schemaText.lineSequence()) {
            val line = raw.trim()
            if (!inTranslators) {
                if (line == "translators:") inTranslators = true
                continue
            }
            if (line.isEmpty() || line.startsWith("#")) continue
            if (!line.startsWith("-")) break
            val item = line.removePrefix("-").substringBefore('#').trim()
            if (item.isEmpty()) continue
            val component = item.substringBefore('@').trim()
            val namespace = if ('@' in item) item.substringAfter('@').trim() else "translator"
            if (component == "table_translator" || component == "script_translator") {
                return TranslatorInfo(component, namespace)
            }
        }
        return null
    }

    private fun resolveNamespaces(
        context: Context,
        schemeIds: List<String>,
    ): Map<String, String> {
        val namespaces = LinkedHashMap<String, String>()
        for (schemaId in schemeIds) {
            if (!isPinyinScheme(context, schemaId)) {
                throw IOException(tr(R.string.s_ciku_fanganbujianrong, schemaId))
            }
            val namespace = detectTranslator(context, schemaId)?.namespace
                ?: throw IOException(tr(R.string.s_ciku_wufanyiqi))
            namespaces[schemaId] = namespace
        }
        return namespaces
    }

    internal fun isPinyinScheme(context: Context, schemaId: String): Boolean {
        if (schemaId.startsWith("t9") || schemaId.contains("handwriting")) return false
        val schemaText = runCatching {
            File(SchemaManager.getRimeDir(context), "$schemaId.schema.yaml")
                .readText(Charsets.UTF_8)
        }.getOrNull() ?: return false
        return parseMainTranslator(schemaText)?.component == "script_translator"
    }

    private fun detectTranslator(context: Context, schemaId: String): TranslatorInfo? {
        val schemaFile = File(SchemaManager.getRimeDir(context), "$schemaId.schema.yaml")
        if (!schemaFile.isFile) return null
        return runCatching {
            parseMainTranslator(schemaFile.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    private suspend fun importEntries(
        context: Context,
        entries: List<DictEntry>,
        sourceName: String,
        schemeIds: List<String>,
        namespaces: Map<String, String>,
        parsedFormatId: String,
        parsedFormatName: String,
        parsedCodeSystem: String,
    ): ImportedDict {
        val rimeDir = SchemaManager.getRimeDir(context)
        val existing = list(context)
        val id = newId(rimeDir, sourceName)
        val dictFile = File(rimeDir, "$id.dict.yaml")
        val record = ImportedDict(
            id = id,
            displayName = displayName(sourceName),
            entryCount = entries.size,
            attachedSchemas = schemeIds,
            namespaces = namespaces,
            createdAt = System.currentTimeMillis(),
            formatId = parsedFormatId,
            formatName = parsedFormatName,
            sourceName = sourceName,
            codeSystem = parsedCodeSystem,
            // 未挂载的导入先停用，避免出现在「已启用」状态却没有方案可生效。
            enabled = schemeIds.isNotEmpty(),
        )
        val backups = mutableListOf<CustomBackup>()
        try {
            Log.i(TAG, "importEntries id=$id schemes=$schemeIds")
            writeDictYaml(dictFile, id, entries, sourceName)
            backups += applyPacksForSchemes(
                context = context,
                schemeIds = schemeIds,
                records = existing + record,
                namespaceHints = namespaces,
            )

            // 未挂载时不写 packs，也不会有 table 产物；等用户绑定后再编译。
            if (schemeIds.isNotEmpty()) {
                val deploy = SchemeDeployer.deploy(context)
                val table = File(rimeDir, "build/$id.table.bin")
                Log.i(TAG, "deploy ok=${deploy.ok} missing=${deploy.missingSchemas} table=${table.isFile}")
                if (!table.isFile) {
                    // 部署返回 false 也可能只是别的方案缺产物；这里只认本词库的编译产物。
                    throw IOException(
                        if (deploy.ok) tr(R.string.s_ciku_meiyouchanwu, id)
                        else tr(R.string.s_ciku_bushu_shibai)
                    )
                }
                // 防御：librime 的 packs 编译曾出现「文件存在但 0 条目」的产物
                // （同一 schema 有多个 packs 且前一个源文件缺失时 syllabary 被搬空）。
                if (tableEntryCount(table) == 0) {
                    throw IOException(tr(R.string.s_ciku_bianyi_weikong, id))
                }
            }

            dao(context).upsert(record.toEntity())
            return record
        } catch (e: Exception) {
            Log.e(TAG, "importEntries failed: ${e.message}", e)
            dictFile.delete()
            restorePacks(backups)
            // 尽量把已部署的 pack 撤掉；失败也不再掩盖原始错误。
            runCatching { SchemeDeployer.deploy(context) }
            throw e
        }
    }

    /** 已导入词库改挂到新方案集合；旧方案不在新集合里就摘掉。 */
    suspend fun rebind(context: Context, id: String, schemeIds: List<String>): Result<Unit> = runCatching {
        val all = list(context)
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) throw IOException("词库记录不存在")
        val selected = schemeIds.distinct()
        if (selected.isEmpty()) throw IOException(tr(R.string.s_ciku_xuanzefangan))
        val old = all[index]
        val namespaces = resolveNamespaces(context, selected)
        val updated = all.toMutableList()
        // 首次给未挂载词库绑定方案时自动启用。
        updated[index] = old.copy(
            attachedSchemas = selected,
            namespaces = namespaces,
            enabled = if (old.attachedSchemas.isEmpty()) true else old.enabled,
        )

        val affected = (old.attachedSchemas + selected).distinct()
        val hints = old.namespaces + namespaces
        val backups = applyPacksForSchemes(context, affected, updated, hints)
        try {
            SchemeDeployer.deploy(context)
            val table = File(SchemaManager.getRimeDir(context), "build/${old.id}.table.bin")
            if (!table.isFile) throw IOException(tr(R.string.s_ciku_meiyouchanwu, old.id))
            dao(context).upsert(updated[index].toEntity())
        } catch (e: Exception) {
            restorePacks(backups)
            runCatching { SchemeDeployer.deploy(context) }
            throw e
        }
    }

    /**
     * 读 `Rime::Table/4.0` 表头里的 num_entries（偏移 0x28，小端）。
     * 只认这个格式；其它版本返回 null（不做判断）。
     */
    private fun tableEntryCount(file: File): Int? = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(0x2C)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n <= 0) return null
                read += n
            }
            val format = String(header, 0, 32, Charsets.US_ASCII).trimEnd('\u0000')
            if (!format.startsWith("Rime::Table/")) return null
            (header[0x28].toInt() and 0xff) or
                ((header[0x29].toInt() and 0xff) shl 8) or
                ((header[0x2a].toInt() and 0xff) shl 16) or
                ((header[0x2b].toInt() and 0xff) shl 24)
        }
    }.getOrNull()

    private fun newId(rimeDir: File, sourceName: String): String {
        repeat(20) {
            val seed = "$sourceName|${System.currentTimeMillis()}|${Random.nextLong()}"
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(seed.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                .take(8)
            val id = "$PACK_PREFIX$hash"
            if (!File(rimeDir, "$id.dict.yaml").exists()) return id
        }
        throw IOException("无法生成词库文件名")
    }

    private fun displayName(sourceName: String): String {
        val base = sourceName.substringAfterLast('/').substringAfterLast('\\')
        val withoutDictSuffix = base.removeSuffix(".dict.yaml")
        val name = (if ('.' in withoutDictSuffix) {
            withoutDictSuffix.substringBeforeLast('.')
        } else {
            withoutDictSuffix
        }).trim().ifEmpty { "导入词库" }
        return name.take(60)
    }

    private fun manifestFile(context: Context): File =
        File(File(context.filesDir, MANIFEST_DIR), MANIFEST_FILE)

    private fun dao(context: Context): ImportedLexiconDao =
        XimeDatabase.getInstance(context).importedLexiconDao()

    private fun ImportedDict.toEntity(): ImportedLexiconEntity =
        ImportedLexiconEntity(
            id = id,
            displayName = displayName,
            dictFile = "$id.dict.yaml",
            formatId = formatId,
            formatName = formatName,
            codeSystem = codeSystem,
            entryCount = entryCount,
            schemes = encodeSchemes(attachedSchemas, namespaces),
            enabled = enabled,
            sourceName = sourceName,
            createdAt = createdAt,
        )

    /** 方案绑定序列化成 `schemaId=namespace;...`，避免为少量绑定单独建表。 */
    private fun encodeSchemes(
        schemas: List<String>,
        namespaces: Map<String, String>,
    ): String = schemas.joinToString(";") { schemaId ->
        "$schemaId=${namespaces[schemaId] ?: "translator"}"
    }

    private fun decodeSchemes(value: String): Pair<List<String>, Map<String, String>> {
        if (value.isBlank()) return emptyList<String>() to emptyMap()
        val namespaces = LinkedHashMap<String, String>()
        val schemas = ArrayList<String>()
        for (part in value.split(';')) {
            val schemaId = part.substringBefore('=').trim()
            if (schemaId.isEmpty()) continue
            schemas += schemaId
            namespaces[schemaId] = part.substringAfter('=', "translator").trim()
                .ifEmpty { "translator" }
        }
        return schemas to namespaces
    }

    private val legacyMutex = Mutex()

    @Volatile
    private var legacyMigrated = false

    /** 旧版本 manifest.json → Room；只跑一次，之后不再写 JSON。 */
    private suspend fun ensureLegacyMigrated(context: Context) {
        if (legacyMigrated) return
        legacyMutex.withLock {
            if (legacyMigrated) return
            val file = manifestFile(context)
            if (file.isFile) {
                val items = runCatching {
                    json.decodeFromString(Manifest.serializer(), file.readText(Charsets.UTF_8)).items
                }.getOrNull()
                if (items != null) {
                    val dao = dao(context)
                    items.forEach { old ->
                        if (dao.getById(old.id) == null) {
                            dao.upsert(old.toEntity())
                        }
                    }
                    file.renameTo(File(file.parentFile, "manifest.json.migrated"))
                }
            }
            legacyMigrated = true
        }
    }
}
