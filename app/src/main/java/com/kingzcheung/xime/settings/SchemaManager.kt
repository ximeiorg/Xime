package com.kingzcheung.xime.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import com.kingzcheung.xime.scheme.SchemeArchive
import com.kingzcheung.xime.scheme.SchemeFiles
import com.kingzcheung.xime.scheme.SchemeInstaller
import com.kingzcheung.xime.scheme.SchemePackages
import com.kingzcheung.xime.scheme.SchemeStore
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

data class SchemaMeta(
    val schemaId: String,
    val name: String,
    val version: String = "",
    val author: String = "",
    val description: String = ""
)

/**
 * 方案中 `switches` 定义的一个开关项。
 * - [name] 非空时表示布尔开关（如 ascii_mode / full_shape / ascii_punct）。
 * - [options] 非空时表示多选一开关（如简繁切换，同一时刻只有一个 option 为 true）。
 * - [abbrev] 自定义缩写（可能每个状态一个），供菜单栏展示用；为空时取 states 首字符。
 */
data class SchemaSwitch(
    val name: String = "",
    val options: List<String> = emptyList(),
    val states: List<String> = emptyList(),
    val abbrev: List<String> = emptyList(),
    /** 方案声明的默认值（`reset`），未声明为 null。 */
    val reset: Int? = null,
)

@Serializable
internal data class SchemaYaml(val schema: SchemaEntry)

@Serializable
internal data class SchemaEntry(
    @SerialName("schema_id") val schemaId: String = "",
    val name: String = "",
    val version: String = "",
    val description: String? = null,
)

object SchemaManager {
    private const val TAG = "SchemaManager"
    private const val CUSTOM_YAML = "default.custom.yaml"
    private const val DEFAULT_YAML = "default.yaml"
    /** 内置预设所在 assets 目录（与 [RimeSharedFiles] 保持一致）。 */
    private const val ASSETS_RIME_DIR = "rime"
    /** 方案包 default.yaml 的读取上限（防止异常归档把整文件读进内存）。 */
    private const val MAX_PACKAGED_DEFAULT_YAML = 1_048_576L
    internal val yaml = Yaml(configuration = YamlConfiguration(strictMode = false, anchorsAndAliases = com.charleskorn.kaml.AnchorsAndAliases.Permitted(maxAliasCount = UInt.MAX_VALUE)))

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getRimeDir(context: Context): File =
        File(context.filesDir, "rime")

    /** market 根目录（与 rime/ 同级，不属于 Rime 数据）。 */
    fun getMarketDir(context: Context): File =
        File(context.filesDir, "market")

    /** 每个方案在 market 下的独立子目录：market/{schemeId}/ */
    fun getMarketDir(context: Context, schemeId: String): File =
        File(getMarketDir(context), schemeId)

    /** 检查方案的压缩包是否已下载（market/{schemeId}/ 存在且包含文件）。 */
    fun isSchemeDownloaded(context: Context, schemeId: String): Boolean {
        val dir = getMarketDir(context, schemeId)
        return dir.exists() && (dir.listFiles()?.any { it.isFile } == true)
    }

    /** 删除 market 中指定方案的整个子目录（含压缩包），并清理本地版本记录与包信息。 */
    fun deleteSchemeArchive(context: Context, schemeId: String): Boolean {
        val dir = getMarketDir(context, schemeId)
        val removed = if (!dir.exists()) false else dir.deleteRecursively()
        if (removed) {
            MarketVersionStore.removeSchemeVersion(context, schemeId)
        }
        // 包信息（名称/版本）必须一起清：否则条目仍由 scheme_package_info 留在列表里，
        // 既非已安装也非已下载，UI 只能显示「未知」。
        SchemePackages.forget(context, schemeId)
        SchemeStore.invalidate()
        return removed
    }

    data class InstallFromDirResult(
        val success: Boolean,
        val newSchemaIds: List<String> = emptyList(),
        val unresolvedDeps: List<String> = emptyList(),
        val failureReason: String? = null,
        val parseFailures: List<String> = emptyList(),
        /** 包没有声明 schema_list：装好后没有启用方案，需要用户手动选择并部署。 */
        val needsSelection: Boolean = false,
    )

    /**
     * 统一安装入口（市场 / 本地 / URL 归档都汇聚到这里），实现见 SchemeInstaller。
     *
     * 安装即切换并部署；失败原因已本地化，供 UI 直出。
     */
    suspend fun installPackageFromMarketDir(
        context: Context,
        packageId: String,
        displayName: String,
        version: String = "",
        fromMarket: Boolean = false,
        dependencies: List<String> = emptyList(),
        resolveDepUrl: (String) -> String? = { null },
        switchEnabled: Boolean = true,
    ): InstallFromDirResult = withContext(Dispatchers.IO) {
        val outcome = SchemeInstaller.installDownloaded(
            context = context,
            packageId = packageId,
            name = displayName,
            version = version,
            switchEnabled = switchEnabled,
            dependencies = dependencies,
            resolveDepUrl = resolveDepUrl,
        )
        if (outcome.success && fromMarket && version.isNotBlank()) {
            MarketVersionStore.setSchemeVersion(context, packageId, version)
        }
        InstallFromDirResult(
            success = outcome.success,
            newSchemaIds = outcome.schemaIds,
            unresolvedDeps = outcome.unresolvedDeps,
            failureReason = installFailureMessage(outcome),
            parseFailures = outcome.parseFailures,
            needsSelection = outcome.needsSelection,
        )
    }

    private fun installFailureMessage(outcome: SchemeInstaller.Outcome): String? {
        if (outcome.success) return null
        return when (outcome.failure) {
            SchemeInstaller.Failure.NO_ARCHIVE -> tr(R.string.s_yasuobaobucunzai)
            SchemeInstaller.Failure.ARCHIVE_CORRUPT -> tr(R.string.s_yasuobaosunhuai)
            SchemeInstaller.Failure.NO_SCHEMA -> tr(R.string.s_guidangzhongmeiyouwenjian)
            SchemeInstaller.Failure.NO_SPACE -> tr(R.string.s_anzhuang_kongjianbuzu)
            SchemeInstaller.Failure.EXTRACT_FAILED, null -> tr(R.string.s_anzhuangshibai)
        }
    }

    /** 在 market/{schemeId}/ 中查找已下载的归档文件。 */
    fun findMarketFile(context: Context, schemeId: String): File? {
        val dir = getMarketDir(context, schemeId)
        if (!dir.exists()) return null
        return dir.listFiles()?.firstOrNull { it.isFile }
    }

    /**
     * 读取方案包归档里的 default.yaml schema_list（作者声明的开箱启用方案集）。
     * default.yaml 本身受保护、不会导入，这里只读取用来决定启用列表；没有则返回 null。
     */
    internal fun readPackagedSchemaList(context: Context, packageId: String): List<String>? {
        val dir = getMarketDir(context, packageId)
        val files = dir.listFiles()?.filter { it.isFile } ?: return null
        for (file in files) {
            val text = when {
                file.name.endsWith(".zip", ignoreCase = true) -> readZipEntryText(file, DEFAULT_YAML)
                file.name.endsWith(".tar.gz", ignoreCase = true) ||
                    file.name.endsWith(".tgz", ignoreCase = true) -> readTarEntryText(file, DEFAULT_YAML)
                file.name == DEFAULT_YAML -> file.takeIf { it.length() in 1..MAX_PACKAGED_DEFAULT_YAML }
                    ?.readText()
                else -> null
            } ?: continue
            val ids = parseSchemaListBlock(text)
            if (ids.isNotEmpty()) return ids
        }
        return null
    }

    private fun readZipEntryText(file: File, baseName: String): String? {
        ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull {
                !it.isDirectory &&
                    it.name.substringAfterLast('/') == baseName &&
                    it.size in 1..MAX_PACKAGED_DEFAULT_YAML
            } ?: return null
            return zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    private fun readTarEntryText(file: File, baseName: String): String? {
        TarArchiveInputStream(GzipCompressorInputStream(file.inputStream().buffered())).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (!entry.isDirectory &&
                    entry.name.substringAfterLast('/') == baseName &&
                    entry.size in 1..MAX_PACKAGED_DEFAULT_YAML
                ) {
                    return tar.readBytes().toString(Charsets.UTF_8)
                }
                entry = tar.nextEntry
            }
        }
        return null
    }

    internal fun getBuildDir(context: Context): File =
        File(getRimeDir(context), "build")

    private fun getCustomYamlFile(context: Context): File =
        File(getRimeDir(context), CUSTOM_YAML)

    fun isSchemaCompiled(context: Context, schemaId: String): Boolean {
        val buildDir = getBuildDir(context)
        return File(buildDir, "$schemaId.prism.bin").exists() ||
               File(buildDir, "$schemaId.schema.yaml").exists()
    }

    /**
     * 解析 default.yaml 文本里的 schema_list（纯函数）。
     * 用于读取方案包自带的 default.yaml：它声明了作者希望开箱启用的方案集，
     * 通常比包里所有 *.schema.yaml 少（例如白霜包还带注音/仓颉等辅助方案，
     * 全启用会因缺 zhuyin 资源导致部署报错）。
     */
    internal fun parseSchemaListBlock(text: String): List<String> {
        val lines = text.lines()
        val headerIdx = lines.indexOfFirst { it.trim() == "schema_list:" }
        if (headerIdx < 0) return emptyList()
        val ids = mutableListOf<String>()
        for (i in headerIdx + 1 until lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (!trimmed.startsWith("-")) break
            val item = trimmed.removePrefix("-").trim()
            if (!item.startsWith("schema:")) continue
            val id = item.removePrefix("schema:")
                .substringBefore("#")
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
            if (id.isNotEmpty()) ids.add(id)
        }
        return ids
    }

    // F1: 把启用方案直接写进 default.yaml 的 schema_list（纯函数，可单测）
    fun replaceSchemaListBlock(defaultYamlText: String, enabled: List<String>): String {
        // 保留原文件换行风格（CRLF/LF），避免把整文件换行符规范化
        val sep = if (defaultYamlText.contains("\r\n")) "\r\n" else "\n"
        val lines = defaultYamlText.lines()
        // 兼容 `schema_list:` 与 `schema_list: []`：后者是空启用时写入的，
        // 只按完全相等匹配会导致下次写入认不出旧块，在文末再追加一份重复 key。
        fun isHeader(line: String) = line.trimStart().startsWith("schema_list:")
        val headers = lines.indices.filter { isHeader(lines[it]) }

        if (headers.isEmpty()) {
            if (enabled.isEmpty()) return defaultYamlText
            // 没有 schema_list 块：在文末追加一个
            val sb = StringBuilder(defaultYamlText)
            if (!defaultYamlText.endsWith("\n")) sb.append(sep)
            sb.append(sep).append("schema_list:").append(sep)
            enabled.forEach { sb.append("  - schema: ").append(it).append(sep) }
            return sb.toString()
        }

        val firstHeader = headers.first()
        // 列表项缩进沿用原文件风格（没有列表项时用两空格）
        var indent = "  "
        val next = firstHeader + 1
        if (next < lines.size && lines[next].trimStart().startsWith("-")) {
            indent = lines[next].takeWhile { it == ' ' || it == '\t' }.ifEmpty { "  " }
        }

        // 历史写入可能留下多个 schema_list 块（重复 key 会让 librime 只认其中一份，
        // 新启用的方案永远不编译）：整块删掉，只在第一个位置写一份新的。
        val drop = BooleanArray(lines.size)
        for (h in headers) {
            drop[h] = true
            var k = h + 1
            while (k < lines.size && lines[k].trimStart().startsWith("-")) {
                drop[k] = true
                k++
            }
        }

        // 空列表写 `schema_list: []`：安装无声明方案包后必须先清空旧列表，
        // 否则包内/基线 default.yaml 会带着上一个包的 schema_list 去编译。
        val block = if (enabled.isEmpty()) {
            listOf("schema_list: []")
        } else {
            buildList {
                add("schema_list:")
                enabled.forEach { add("$indent- schema: $it") }
            }
        }

        val out = ArrayList<String>(lines.size)
        for (i in lines.indices) {
            if (i == firstHeader) {
                out += block
                continue
            }
            if (drop[i]) continue
            out += lines[i]
        }
        return out.joinToString(sep)
    }

    /**
     * F1: 把启用方案写回 `default.yaml` 的 schema_list。
     *
     * librime 的 AutoPatchConfigPlugin 会把 default.custom.yaml 的 patch 叠加到
     * default.yaml 上（部署编译阶段读取 schema_list 也会叠加），所以这里必须先剥掉
     * default.custom.yaml 里的 schema_list，否则第三方/内置方案包自带的旧列表会
     * 覆盖 app 的启用列表，导致未列出的方案（如 t9_pinyin）永不编译。
     *
     * @param schemaIds 缺省取当前启用列表；[setEnabledSchemas] 会显式传入避免重复读取。
     */
    fun applyEnabledSchemasToDefaultYaml(
        context: Context,
        schemaIds: List<String> = getEnabledSchemas(context)
    ) {
        stripSchemaListFromDefaultCustom(context)
        val rimeDir = getRimeDir(context)
        // 只镜像源文件还在的启用项：卸载方案包/冲突卸载可能留下已删方案的 id，
        // 写进 schema_list 会让 librime 报 missing input schema 并拖累整次部署。
        val effective = schemaIds.filter { File(rimeDir, "$it.schema.yaml").isFile }
        if (effective.size != schemaIds.size) {
            // 卸载旧方案包后 prefs 里可能还留着它的子方案 id（如切白霜后仍有
            // wubi86/t9_pinyin）；把这些已无源文件的残留项从启用列表清掉，
            // 避免每次部署都报 missing input schema。
            FileLogger.w(
                TAG,
                "Drop enabled schemas without source file: " +
                    (schemaIds - effective.toSet()).joinToString()
            )
            com.kingzcheung.xime.scheme.SchemeStore.writeEnabled(context, effective)
        }
        // default.yaml 属共享预设，正常不会缺席；但目录被清空/异常删除时它可能不在，
        // 直接 return 会让启用列表静默写不进去（部署随后回落到内置旧列表）。
        // 空启用列表也要写：安装无声明方案包后必须把旧 schema_list 清掉。
        val defaultYaml = ensureDefaultYaml(context, rimeDir) ?: return
        try {
            val text = defaultYaml.readText()
            val updated = replaceSchemaListBlock(text, effective)
            if (updated != text) {
                defaultYaml.writeText(updated)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write default.yaml schema_list", e)
        }
    }

    /**
     * 确保 `default.yaml` 存在：缺失或空文件时从 assets/rime 补一份。
     *
     * `default.yaml` 在 [SchemaManifestManager] 里属受保护文件、卸载不会删它，但它同时
     * 是共享预设，清空目录、手工删除等路径仍可能让它缺席。缺了就补，避免
     * [applyEnabledSchemasToDefaultYaml] 静默放弃写 `schema_list`。
     *
     * @return 可写的 default.yaml；补不回来时返回 null。
     */
    private fun ensureDefaultYaml(context: Context, rimeDir: File): File? {
        val defaultYaml = File(rimeDir, DEFAULT_YAML)
        if (defaultYaml.isFile && defaultYaml.length() > 0L) return defaultYaml
        return try {
            context.assets.open("$ASSETS_RIME_DIR/$DEFAULT_YAML").use { input ->
                defaultYaml.outputStream().use { output -> input.copyTo(output) }
            }
            FileLogger.w(TAG, "$DEFAULT_YAML missing, restored from assets before writing schema_list")
            defaultYaml.takeIf { it.isFile && it.length() > 0L }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to restore $DEFAULT_YAML from assets", e)
            null
        }
    }

    /** 安装流程用：`default.yaml` 缺失时从 assets 补一份。 */
    internal fun ensureDefaultYamlForInstall(context: Context): Boolean =
        ensureDefaultYaml(context, getRimeDir(context)) != null

    /**
     * 剥离 default.custom.yaml 里 app 托管的 schema_list 块（其余用户补丁保留）。
     *
     * 该块会被 librime 当作 default.yaml 的 patch，覆盖 app 从 prefs 镜像过去的
     * 启用列表。方案包（含内置「默认方案」包）自带该块时，会让 t9_pinyin 等
     * 未列出的方案不参与编译；而 default.custom.yaml 属于用户数据，安装时不会被
     * 冲突检测拦截，只能在这里兜底清理。幂等，无块时不写盘。
     */
    fun stripSchemaListFromDefaultCustom(context: Context) {
        val customFile = getCustomYamlFile(context)
        if (!customFile.exists()) return
        try {
            val text = customFile.readText()
            val (stripped, schemas) = stripSchemaListBlock(text)
            if (stripped != text) {
                customFile.writeText(stripped)
                FileLogger.w(
                    TAG,
                    "Stripped schema_list (${schemas.size} schemas) from default.custom.yaml; " +
                        "enabled list is managed by prefs"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to strip schema_list from default.custom.yaml", e)
        }
    }

    // sha256 校验 + 导入保护（纯函数，可单测）
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun streamSha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val dis = DigestInputStream(input, digest)
        val buffer = ByteArray(8192)
        while (dis.read(buffer) != -1) { }
        // 不关闭 dis——调用方负责管理流的生命周期
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun fileSha256(file: File): String? {
        return try {
            FileInputStream(file).use { streamSha256(it) }
        } catch (e: Exception) {
            Log.w(TAG, "sha256 failed for ${file.name}", e)
            null
        }
    }

    /** 生成基于当前时间的导入 ID，如 import_20260712_015947。 */
    fun generateImportId(): String =
        "import_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun getReferencedDictName(context: Context, schemaId: String): String? {
        val schemaFile = File(getRimeDir(context), "$schemaId.schema.yaml")
        if (!schemaFile.exists()) return null
        return try {
            val content = schemaFile.readText()
            // matches "  dictionary: wubi86" or "translator/dictionary: wubi86" or inline {dictionary:wubi86}
            val regex = Regex("""dictionary\s*:\s*['\"]?(\w[\w-]*)['\"]?""")
            regex.find(content)?.groupValues?.getOrNull(1)
        } catch (e: Exception) { null }
    }

    fun hasDictFile(context: Context, schemaId: String): Boolean {
        val dictName = getReferencedDictName(context, schemaId) ?: schemaId
        val f = File(getRimeDir(context), "$dictName.dict.yaml")
        return f.exists()
    }

    fun schemaNeedsDict(context: Context, schemaId: String): Boolean {
        val dictName = getReferencedDictName(context, schemaId) ?: schemaId
        return !File(getRimeDir(context), "$dictName.dict.yaml").exists()
    }

    fun getSchemaIssues(context: Context, schemaId: String): List<String> {
        val issues = mutableListOf<String>()
        val schemaFile = File(getRimeDir(context), "$schemaId.schema.yaml")
        if (!schemaFile.exists()) {
            issues.add(tr(R.string.s_queshao_schema_yaml_wenjian))
            return issues
        }
        val dictName = getReferencedDictName(context, schemaId) ?: schemaId
        if (!File(getRimeDir(context), "$dictName.dict.yaml").exists()) {
            issues.add(tr(R.string.s_queshao_dict_yaml_cidianwenjia_e4311, dictName))
        }
        return issues
    }

    fun discoverSchemas(context: Context): List<SchemaMeta> {
        val rimeDir = getRimeDir(context)
        if (!rimeDir.exists()) return emptyList()

        val schemas = mutableListOf<SchemaMeta>()
        val schemaFiles = rimeDir.listFiles { f -> f.name.endsWith(".schema.yaml") }
            ?: return emptyList()

        for (file in schemaFiles) {
            val meta = parseSchemaYaml(file)
            if (meta != null) {
                schemas.add(meta)
            }
        }

        schemas.sortBy { it.name }
        return schemas
    }

    internal fun parseSchemaYaml(file: File): SchemaMeta? {
        return try {
            val text = file.readText().trimStart('\uFEFF')
            val schemaBlock = extractSchemaBlock(text) ?: return null
            val entry = yaml.decodeFromString(SchemaYaml.serializer(), schemaBlock).schema
            if (entry.schemaId.isEmpty()) return null

            val author = parseAuthorFromText(text)

            SchemaMeta(
                schemaId = entry.schemaId,
                name = entry.name.ifEmpty { entry.schemaId },
                version = entry.version,
                author = author,
                description = entry.description ?: ""
            )
        } catch (e: Exception) {
            try { Log.w(TAG, "Failed to parse schema file: ${file.name}, error=${e.message}, skip", e) } catch (_: Exception) {}
            null
        }
    }

    /**
     * 从 YAML 文本中提取顶层 `schema:` 块及其子内容，返回一个可独立解析的 YAML 片段。
     * 避免 kaml 因为文件的其他部分（重复 `__include`、锚点等）而报错。
     */
    internal fun extractSchemaBlock(yamlText: String): String? {
        val lines = yamlText.lines()
        val schemaLineIndex = lines.indexOfFirst {
            it.trimStart().let { t -> t.startsWith("schema:") && (t.length == 7 || t[7] == ' ') }
        }
        if (schemaLineIndex < 0) return null

        val schemaIndent = lines[schemaLineIndex].length - lines[schemaLineIndex].trimStart().length
        val result = mutableListOf("schema:")
        for (i in schemaLineIndex + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val indent = line.length - line.trimStart().length
            if (indent <= schemaIndent) break
            result.add(line)
        }
        return result.joinToString("\n")
    }

    private fun parseAuthorFromText(yamlText: String): String {
        val lines = yamlText.lines()
        var inAuthor = false
        for (line in lines) {
            val trimmed = line.trimStart()
            if (!inAuthor && trimmed.startsWith("author:")) {
                val rest = trimmed.removePrefix("author:").trim()
                if (rest.isNotEmpty()) return rest.removeSurrounding("\"").removePrefix("- ").trim()
                inAuthor = true
                continue
            }
            if (inAuthor) {
                if (trimmed.startsWith("- ")) {
                    return trimmed.removePrefix("- ").trim().removeSurrounding("\"")
                }
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    inAuthor = false
                }
            }
        }
        return ""
    }

    /** 仅从 .schema.yaml 读取显示名，不依赖编译/启用状态。 */
    fun getSchemaDisplayName(context: Context, schemaId: String): String? {
        val file = File(getRimeDir(context), "$schemaId.schema.yaml")
        if (!file.exists()) return null
        return try {
            val entry = yaml.decodeFromString(SchemaYaml.serializer(), file.readText().trimStart('\uFEFF')).schema
            entry.name.ifEmpty { null }
        } catch (e: Exception) {
            try { Log.e(TAG, "Failed to parse schema name for $schemaId", e) } catch (_: Exception) {}
            null
        }
    }

    /**
     * 读取方案顶层 `switches:` 中可展示的开关项（name 或 options 存在且有 states）。
     * `switches` 位于 schema 块之外，需单独解析。解析失败或不存在时返回空列表。
     */
    fun getSchemaSwitches(context: Context, schemaId: String): List<SchemaSwitch> {
        val file = File(getRimeDir(context), "$schemaId.schema.yaml")
        if (!file.exists()) return emptyList()
        return parseSchemaSwitches(file)
    }

    /**
     * 方案是否声明默认英文（`switches` 中 `ascii_mode` 的 `reset: 1`）。
     *
     * 纯英文方案靠这个标记在切换方案/新输入会话时保持英文；
     * 普通中文方案的 `ascii_mode` 没有 reset 或为 0，不受影响。
     */
    fun isAsciiDefaultSchema(context: Context, schemaId: String): Boolean =
        schemaSwitchReset(context, schemaId, "ascii_mode") == 1

    /**
     * 读取方案某个 boolean switch 声明的 `reset`（未声明返回 null）。
     *
     * 让「进入方案用什么默认状态」由方案 yaml 决定，而不是在 Android 层按方案名硬编码：
     * `ascii_mode reset: 1` = 默认英文，`ascii_punct reset: 1` = 保留方案自己的标点默认值。
     */
    fun schemaSwitchReset(context: Context, schemaId: String, switchName: String): Int? {
        if (schemaId.isEmpty()) return null
        return getSchemaSwitches(context, schemaId)
            .firstOrNull { it.name == switchName }
            ?.reset
    }

    /** 纯解析：从 .schema.yaml 读取 switches。 */
    internal fun parseSchemaSwitches(file: File): List<SchemaSwitch> {
        return try {
            val text = file.readText().trimStart('\uFEFF')
            val block = extractSwitchesBlock(text) ?: return emptyList()
            val listNode = yaml.parseToYamlNode(block) as? YamlList ?: return emptyList()
            listNode.items.mapNotNull { item ->
                parseSwitchEntry(item as? YamlMap ?: return@mapNotNull null)
            }
        } catch (e: Exception) {
            try { Log.w(TAG, "Failed to parse switches for ${file.name}: ${e.message}", e) } catch (_: Exception) {}
            emptyList()
        }
    }

    private fun parseSwitchEntry(entry: YamlMap): SchemaSwitch? {
        val states = (entry["states"] as? YamlList)
            ?.items?.mapNotNull { (it as? YamlScalar)?.content }
            .orEmpty()
        if (states.isEmpty()) return null
        val name = (entry["name"] as? YamlScalar)?.content.orEmpty()
        val options = (entry["options"] as? YamlList)
            ?.items?.mapNotNull { (it as? YamlScalar)?.content }
            .orEmpty()
        val abbrev = parseAbbrev(entry["abbrev"])
        val reset = (entry["reset"] as? YamlScalar)?.content?.toIntOrNull()
        return when {
            name.isNotBlank() -> SchemaSwitch(
                name = name, options = emptyList(), states = states, abbrev = abbrev, reset = reset
            )
            options.isNotEmpty() -> SchemaSwitch(
                name = "", options = options, states = states, abbrev = abbrev, reset = reset
            )
            else -> null
        }
    }

    /** abbrev 可以是单个字符串或每个状态一个的字符串列表。 */
    private fun parseAbbrev(node: YamlNode?): List<String> = when (node) {
        is YamlScalar -> listOf(node.content)
        is YamlList -> node.items.mapNotNull { (it as? YamlScalar)?.content }
        else -> emptyList()
    }

    /**
     * 从 YAML 文本中提取顶层 `switches:` 的列表项内容（不含 `switches:` 头行），
     * 返回一个可独立作为列表解析的 YAML 片段；不存在时返回 null。
     */
    internal fun extractSwitchesBlock(yamlText: String): String? {
        val lines = yamlText.lines()
        val idx = lines.indexOfFirst {
            it.trimStart().let { t -> t == "switches:" || (t.startsWith("switches:") && t[9] == ' ') }
        }
        if (idx < 0) return null
        val result = mutableListOf<String>()
        for (i in idx + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val indent = line.length - line.trimStart().length
            if (indent == 0) break
            result.add(line)
        }
        if (result.isEmpty()) return null
        return result.joinToString("\n")
    }

    /** 内置方案（保持默认启用顺序）。 */
    internal val BUILTIN_SCHEMAS = listOf("wubi86", "wubi86_pinyin", "pinyin_simp", "t9_pinyin", "english")

    /**
     * 启用方案列表（真相在 prefs，由 [com.kingzcheung.xime.scheme.SchemeStore] 维护）。
     *
     * 读取时保证不变量：列表里只有真实存在的方案、且至少有一个；两者的对齐
     * （含老版本升级的内置补齐）都在 SchemeStore.ensure 里一次完成。
     */
    fun getEnabledSchemas(context: Context): List<String> =
        com.kingzcheung.xime.scheme.SchemeStore.state(context).enabled

    /**
     * 写入启用方案列表：状态走 SchemeStore，`default.yaml` 的 `schema_list`
     * 是派生物，随写入重新镜像。
     */
    fun setEnabledSchemas(context: Context, schemaIds: List<String>) {
        val effective = com.kingzcheung.xime.scheme.SchemeStore.writeEnabled(context, schemaIds)
        applyEnabledSchemasToDefaultYaml(context, effective)
    }

    /**
     * 存量迁移（一次性）：把 default.custom.yaml 里 app 托管的 schema_list 迁入
     * prefs 并从用户文件中删除该块——此后该文件完全归用户、app 零写入，用户对
     * page_size/key_binder 等任何内容的修改不再被覆盖。
     * [force] 供备份恢复用：旧格式备份包会带回带 schema_list 的文件，无视标记重跑。
     * 幂等：无块时为无操作。失败不置标记，下次启动重试。
     */
    fun migrateDefaultCustomSchemaList(context: Context, force: Boolean = false) {
        if (!force && SettingsPreferences.isDefaultCustomMigrated(context)) return
        try {
            val customFile = getCustomYamlFile(context)
            if (customFile.exists()) {
                val text = customFile.readText()
                val (stripped, schemas) = stripSchemaListBlock(text)
                if (schemas.isNotEmpty()) {
                    val effective = com.kingzcheung.xime.scheme.SchemeStore.writeEnabled(context, schemas)
                    applyEnabledSchemasToDefaultYaml(context, effective)
                }
                if (stripped != text) {
                    customFile.writeText(stripped)
                    FileLogger.i(TAG, "Migrated schema_list out of default.custom.yaml (${schemas.size} schemas)")
                }
            }
            SettingsPreferences.setDefaultCustomMigrated(context, true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate default.custom.yaml schema_list", e)
        }
    }

    /**
     * 从 default.custom.yaml 文本剥离 app 托管的 schema_list 块（"schema_list:" 行
     * 及其后连续的 "- " 列表项），返回 (新文本, 原方案列表)。保留原换行风格与
     * 块外全部内容；无块时原样返回。
     */
    internal fun stripSchemaListBlock(text: String): Pair<String, List<String>> {
        val sep = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.lines()
        val headerIdx = lines.indexOfFirst { it.trim() == "schema_list:" }
        if (headerIdx < 0) return text to emptyList()
        var end = headerIdx + 1
        val schemas = mutableListOf<String>()
        while (end < lines.size) {
            val trimmed = lines[end].trim()
            if (trimmed.startsWith("- schema:")) {
                val id = trimmed.removePrefix("- schema:").trim()
                if (id.isNotEmpty()) schemas.add(id)
            } else if (!trimmed.startsWith("-")) {
                break
            }
            end++
        }
        val rebuilt = lines.subList(0, headerIdx) + lines.subList(end, lines.size)
        var result = rebuilt.joinToString(sep)
        if (text.endsWith("\n") && !result.endsWith("\n")) result += sep
        return result to schemas
    }

    fun toggleSchema(context: Context, schemaId: String) {
        val enabled = getEnabledSchemas(context).toMutableList()
        if (schemaId in enabled) {
            enabled.remove(schemaId)
        } else {
            enabled.add(schemaId)
        }
        setEnabledSchemas(context, enabled)
    }

    fun isSchemaEnabled(context: Context, schemaId: String): Boolean {
        return schemaId in getEnabledSchemas(context)
    }

    /**
     * 卸载。两种语义：
     * - **包级**（活动包 / 有压缩包的 id）：先切回内置包，保证任何时候都有能用的输入法；
     *   压缩包是否删除由 [deleteSchemeArchive] 决定。
     * - **单个方案**：不删文件，只从启用列表移出（可逆，且不牵扯共享词典）。
     */
    suspend fun deleteSchemaFiles(context: Context, schemaId: String): Boolean = withContext(Dispatchers.IO) {
        val isPackage = schemaId == SchemeFiles.BUILTIN_PACKAGE_ID ||
            SettingsPreferences.getActivePackageId(context) == schemaId ||
            SchemeFiles.marketDir(context, schemaId).exists()

        if (!isPackage) {
            setEnabledSchemas(context, getEnabledSchemas(context) - schemaId)
            SettingsPreferences.removeLayoutPreference(context, schemaId)
            if (SettingsPreferences.getCurrentSchema(context) == schemaId) {
                // 移出后可能一个都不剩：当前方案同步清空，避免指向已禁用方案。
                SchemeStore.setCurrent(context, getEnabledSchemas(context).firstOrNull().orEmpty())
            }
            FileLogger.i(TAG, "deleteSchemaFiles: '$schemaId' disabled instead of deleted")
            return@withContext true
        }

        if (SettingsPreferences.getActivePackageId(context) == schemeIdOrNull(schemaId)) {
            val restored = SchemeInstaller.restoreBuiltin(context)
            FileLogger.i(TAG, "deleteSchemaFiles: uninstalled '$schemaId', restoredBuiltin=${restored.success}")
            return@withContext restored.success
        }
        SchemeStore.invalidate()
        true
    }

    private fun schemeIdOrNull(schemaId: String): String? = schemaId

    data class ImportResult(
        val success: Boolean,
        val installedDirect: Boolean = false,
    )

    /** 判断文件名是否为压缩包。 */
    fun isArchive(name: String): Boolean =
        name.endsWith(".zip", ignoreCase = true) ||
        name.endsWith(".tar.gz", ignoreCase = true) ||
        name.endsWith(".tgz", ignoreCase = true)

    /** 判断文件名是否为图片（背景图等，导入到 themes/）。 */
    fun isImage(name: String): Boolean =
        name.endsWith(".jpg", ignoreCase = true) ||
        name.endsWith(".jpeg", ignoreCase = true) ||
        name.endsWith(".png", ignoreCase = true)

    /** 判断文件名是否为字体文件（导入到 fonts/）。 */
    fun isFont(name: String): Boolean =
        name.endsWith(".ttf", ignoreCase = true) ||
        name.endsWith(".otf", ignoreCase = true) ||
        name.endsWith(".woff", ignoreCase = true) ||
        name.endsWith(".woff2", ignoreCase = true)

    /** fonts 目录：rime/fonts/，存放用户导入的自定义字体。 */
    fun getFontsDir(context: Context): File =
        File(getRimeDir(context), "fonts")

    /** themes 目录：rime/themes/，存放用户导入或自定义的背景图片。 */
    fun getThemesDir(context: Context): File =
        File(getRimeDir(context), "themes")

    /**
     * 某些 Android 内容提供器会将未知 MIME 类型的文件（如 .yaml）自动追加 .txt 后缀，
     * 导致 xime.custom.yaml 变成 xime.custom.yaml.txt。此处修复已知情况。
     */
    fun sanitizeDisplayName(name: String): String {
        return when {
            name.endsWith(".yaml.txt", ignoreCase = true) ->
                name.removeSuffix(".txt")
            name.endsWith(".dict.yaml.txt", ignoreCase = true) ->
                name.removeSuffix(".txt")
            else -> name
        }
    }

    /**
     * 统一保存导入的文件：
     * - 压缩包 → `market/<importId>/`，等用户在方案包页点"安装"；
     * - 字体 → `rime/fonts/`、图片 → `rime/themes/`（用户数据，跨包保留）；
     * - 其它（`.schema.yaml` / `.dict.yaml` / `.txt` 等）→ 直接落 `rime/`，
     *   并同步进当前包的 overlay（切走时随旧包暂存，切回时恢复）。
     */
    suspend fun saveImportedFile(
        context: Context,
        displayName: String,
        inputStream: java.io.InputStream,
        autoEnable: Boolean = true,
    ): ImportResult = withContext(Dispatchers.IO) {
        val name = sanitizeDisplayName(displayName)
        when {
            isFont(name) -> ImportResult(saveInto(getFontsDir(context), name, inputStream))
            isImage(name) -> ImportResult(saveInto(getThemesDir(context), name, inputStream))
            isArchive(name) -> {
                val pkgDir = getMarketDir(context, generateImportId())
                ImportResult(saveInto(pkgDir, name, inputStream))
            }
            else -> {
                val ok = saveInto(getRimeDir(context), name, inputStream)
                if (!ok) return@withContext ImportResult(false)
                copyToOverlay(context, name)
                if (autoEnable && name.endsWith(".schema.yaml")) {
                    val schemaId = name.removeSuffix(".schema.yaml")
                    setEnabledSchemas(context, getEnabledSchemas(context) + schemaId)
                }
                ImportResult(true, installedDirect = true)
            }
        }
    }

    /** 写到目录下的同名文件；父目录按需创建。 */
    private fun saveInto(dir: File, name: String, inputStream: java.io.InputStream): Boolean = try {
        dir.mkdirs()
        val target = File(dir, name)
        target.parentFile?.mkdirs()
        inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        FileLogger.i(TAG, "Imported $name -> ${dir.name}/")
        true
    } catch (e: Exception) {
        FileLogger.e(TAG, "Failed to import $name", e)
        false
    }

    /** 单文件导入属于当前方案包：复制一份到 `market/<id>/overlay/`，切包后可恢复。 */
    private fun copyToOverlay(context: Context, name: String) {
        val active = SettingsPreferences.getActivePackageId(context) ?: return
        val source = File(getRimeDir(context), name)
        if (!source.isFile) return
        val target = File(SchemeFiles.overlayDir(context, active), name)
        runCatching {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }.onFailure { FileLogger.w(TAG, "overlay copy failed: $name") }
    }

    suspend fun importSchemaFile(context: Context, uri: Uri): ImportResult {
        return withContext(Dispatchers.IO) {
            val displayName = getFileName(context, uri) ?: return@withContext ImportResult(false)

            val inputStream = when (uri.scheme) {
                "file" -> java.io.FileInputStream(uri.path!!)
                else -> context.contentResolver.openInputStream(uri)
            } ?: return@withContext ImportResult(false)

            saveImportedFile(context, displayName, inputStream)
        }
    }


    /**
     * 找到所有 .schema.yaml 文件所在的共同父目录作为基目录。
     * 例如 zip 结构为 rime-ice-main/rime_ice.schema.yaml，
     * 则返回 "rime-ice-main/"，解压时剥离此前缀。
     * 基目录下的子目录（如 cn_dicts/）会原样保留。
     */
    internal fun findSchemaBaseDir(entryNames: List<String>): String {
        val schemaEntries = entryNames.filter { it.endsWith(".schema.yaml") }
        if (schemaEntries.isEmpty()) {
            // 无 .schema.yaml 的包(如 rime-essay 只含 essay.txt、rime-prelude 含 symbols.yaml）：
            // 若所有条目同处唯一壳目录（GitHub 归档形如 <repo>-<branch>/），剥掉它，
            // 否则文件会落进子目录（rime/rime-essay-master/essay.txt）导致 rime 读不到。
            val files = entryNames.filter { it.isNotBlank() }
            if (files.isEmpty() || files.any { !it.contains('/') }) return ""
            val tops = files.map { it.substringBefore('/') }.distinct()
            return if (tops.size == 1) "${tops[0]}/" else ""
        }
        // 获取所有 .schema.yaml 的父目录
        val parentDirs = schemaEntries.map { name ->
            val idx = name.lastIndexOf('/')
            if (idx >= 0) name.substring(0, idx + 1) else ""
        }.distinct()
        // 如果所有 .schema.yaml 在同一个父目录下，返回该目录作为基目录
        if (parentDirs.size == 1) {
            return parentDirs[0]
        }
        // 在不同目录下，找最长公共前缀
        val commonPrefix = parentDirs.reduce { a, b -> a.commonPrefixWith(b) }
        val idx = commonPrefix.lastIndexOf('/')
        return if (idx >= 0) commonPrefix.substring(0, idx + 1) else ""
    }


    /**
     * 从 URL 下载文件到 market/{schemeId}/ 目录（仅下载，不解压）。
     * 支持任意文件类型（zip、tar.gz、yaml 等）。
     */
    /** 下载结果：success + sha256 校验状态（null=未提供, true=通过, false=不通过）。 */
    data class DownloadResult(val success: Boolean, val sha256Verified: Boolean? = null)

    /** 下载后的完整性校验：非归档文件视为有效，归档用 SchemeArchive 探测一次。 */
    private fun validateArchive(file: File): Boolean =
        !isArchive(file.name) || SchemeArchive.probe(file) != null

    suspend fun downloadToMarket(
        context: Context,
        url: String,
        schemeId: String,
        fileName: String,
        expectedSha256: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): DownloadResult = withContext(Dispatchers.IO) {
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Xime:SchemaDownload")
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(5 * 60 * 1000L)
        try {
            val schemeDir = getMarketDir(context, schemeId)
            if (!schemeDir.exists()) schemeDir.mkdirs()
            val targetFile = File(schemeDir, fileName)

            downloadClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: ${response.code} $url")
                    return@withContext DownloadResult(false)
                }
                val body = response.body ?: return@withContext DownloadResult(false)
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                val md = if (!expectedSha256.isNullOrBlank()) MessageDigest.getInstance("SHA-256") else null
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buf = ByteArray(8192)
                        var n = input.read(buf)
                        while (n >= 0) {
                            output.write(buf, 0, n)
                            md?.update(buf, 0, n)
                            downloadedBytes += n
                            if (totalBytes > 0) onProgress(downloadedBytes, totalBytes)
                            n = input.read(buf)
                        }
                    }
                }
                if (md != null && !expectedSha256.isNullOrBlank()) {
                    val actual = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                        Log.e(TAG, "sha256 mismatch for $url: expected=${expectedSha256.trim()} actual=$actual")
                        targetFile.delete()
                        return@withContext DownloadResult(false, sha256Verified = false)
                    }
                    Log.i(TAG, "Downloaded (sha256 verified): ${targetFile.absolutePath}")
                    DownloadResult(true, sha256Verified = true)
                } else {
                    val valid = validateArchive(targetFile)
                    if (!valid) {
                        Log.e(TAG, "Archive validation failed for $url, file is corrupted")
                        targetFile.delete()
                        return@withContext DownloadResult(false)
                    }
                    Log.i(TAG, "Downloaded (unverified, archive validated): ${targetFile.absolutePath}")
                    DownloadResult(true, sha256Verified = null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadToMarket failed: $url", e)
            val schemeDir = getMarketDir(context, schemeId)
            val targetFile = File(schemeDir, fileName)
            if (targetFile.exists()) targetFile.delete()
            if (schemeDir.exists() && schemeDir.listFiles().isNullOrEmpty()) {
                schemeDir.delete()
            }
            DownloadResult(false)
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    /**
     * 验证压缩包完整性：zip 尝试列出条目，tar.gz 尝试读取首条。
     * 非归档文件直接返回 true（无法校验）。
     */
    suspend fun importFromUrl(
        context: Context,
        url: String,
        expectedSha256: String? = null,
        archiveName: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val fileName = archiveName ?: url.substringAfterLast("/").takeIf { it.isNotBlank() }
                ?: "download"
            val tmpFile = File.createTempFile("import_", ".tmp", context.cacheDir)

            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()
                val sha256Ok = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Download failed: ${response.code} $url")
                        return@withContext ImportResult(false)
                    }
                    val body = response.body ?: return@withContext ImportResult(false)
                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L
                    val md = MessageDigest.getInstance("SHA-256")
                    body.byteStream().use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            val buf = ByteArray(8192)
                            var n = input.read(buf)
                            while (n >= 0) {
                                output.write(buf, 0, n)
                                md.update(buf, 0, n)
                                downloadedBytes += n
                                if (totalBytes > 0) onProgress(downloadedBytes, totalBytes)
                                n = input.read(buf)
                            }
                        }
                    }
                    if (!expectedSha256.isNullOrBlank()) {
                        val actual = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                        if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                            Log.e(TAG, "sha256 mismatch for $url")
                            return@withContext ImportResult(false)
                        }
                    }
                    true
                }
                if (!sha256Ok) return@withContext ImportResult(false)

                // 通过统一函数保存
                val result = saveImportedFile(context, fileName, tmpFile.inputStream())
                Log.i(TAG, "Imported from url $url -> ${if (result.installedDirect) "rime/" else "market/"} ($fileName)")
                result
            } finally {
                tmpFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import from URL: $url", e)
            ImportResult(false)
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return it.getString(nameIndex)
                }
            }
        }
        return uri.lastPathSegment
    }
}
