package com.kingzcheung.xime.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.settings.KeysConfigHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** 下载校验状态：null=未提供sha256, true=校验通过, false=校验不通过。 */
typealias Sha256Status = Boolean?

/** 安装结果（带失败原因 + 未解决依赖 + sha256 校验状态）。 */
data class InstallResult(
    val success: Boolean,
    val unresolvedDeps: List<String> = emptyList(),
    val failureReason: String? = null,
    /** null=未提供sha256, true=校验通过, false=校验不通过 */
    val sha256Status: Sha256Status = null,
)

/** 方案列表拉取结果（含命中的来源主机名，供 UI 显示「从哪个端点拉的」）。 */
data class SchemesFetch(
    val schemes: List<MarketSchemeItem>,
    val source: String,
    val updatedAt: String = "",
)

/** 插件列表拉取结果。 */
data class PluginsFetch(
    val plugins: List<MarketPluginItem>,
    val source: String,
    val updatedAt: String = "",
)

/** 布局列表拉取结果。 */
data class LayoutsFetch(
    val layouts: List<MarketLayoutItem>,
    val source: String,
    val updatedAt: String = "",
)

/** 布局应用结果：成功时带回本次释放到 rime 的相对文件清单（用于恢复默认时回收）。 */
data class LayoutInstallResult(
    val success: Boolean,
    val files: List<String> = emptyList(),
    val failureReason: String? = null,
)

/**
 * 方案市场数据源：从镜像基址的 rimes/index.yaml（扁平索引，schemas 内联所有 MarketScheme）
 * 获取方案列表，按版本 sha256 下载；安装后用 [RimeDependencyResolver] 补齐编译依赖。
 * 网络/Android 依赖集中在此层；解析/版本/兼容性逻辑在 [XimeIndexParser] 纯函数里。
 *
 * 端点列表通过 [xime.yaml] 的 `xime_index.base_urls` 配置，用户可自定义镜像列表。
 */
object XimeIndexSource {
    private const val TAG = "XimeIndexSource"
    private const val PLUGINS_INDEX_PATH = "plugins/v2/index.yaml"
    private val defaultBaseUrls = listOf("https://index.ximei.me/")

    private var baseUrls: List<String> = defaultBaseUrls
    private var mirrors: List<String> = buildMirrors(defaultBaseUrls)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val DownloadItem.fileName: String
        get() = url.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: "file.${url.substringAfterLast('.').takeIf { it.length in 1..6 } ?: "bin"}"

    /** 下载总量：索引精确字节数（sizeBytes）优先，缺省回退 size 文本估算（KB/MB/GB）。 */
    private val DownloadItem.resolvedSizeBytes: Long
        get() = sizeBytes?.takeIf { it > 0 } ?: parseSizeText(size) ?: 0L

    private fun buildMirrors(userUrls: List<String>): List<String> = userUrls

    private fun ensureConfigured(context: Context) {
        val configured = KeysConfigHelper.loadXimeIndexConfig(context).resolvedBaseUrls
        if (configured != baseUrls) {
            baseUrls = configured
            mirrors = buildMirrors(baseUrls)
        }
    }

    /** 镜像 base → 展示用主机名（如 index.ximei.me / fastly.jsdelivr.net）。 */
    private fun hostOf(base: String): String =
        base.substringAfter("://").substringBefore("/")

    /** 跟随索引跳转：根 → 子 → 逐方案（并行、部分失败容忍）。逐个镜像尝试直到获取到方案。 */
    suspend fun fetchSchemes(context: Context, appVersion: String): Result<SchemesFetch> =
        withContext(Dispatchers.IO) {
            ensureConfigured(context)
            try {
                // 遍历所有镜像，第一个成功获取到方案的返回
                for (base in mirrors) {
                    val result = tryFetchFromBase(base, appVersion)
                    if (result != null) return@withContext Result.success(result)
                }
                // 全部镜像都失败
                val lastUrl = mirrors.lastOrNull() ?: tr(R.string.s_weizhi)
                Result.failure(IOException(tr(R.string.s_wufalianjiedaofanganshichang_y_12d9b, mirrors.size)))
            } catch (e: Exception) {
                Log.e(TAG, "fetchSchemes failed", e)
                Result.failure(e)
            }
        }

    /**
     * 尝试从一个镜像基址获取方案列表。
     * 新索引格式：直接抓取 rimes/index.yaml，其中 schemas 已内联所有 MarketScheme。
     */
    private fun tryFetchFromBase(base: String, appVersion: String): SchemesFetch? {
        val repoPath = "rimes/index.yaml"
        val host = hostOf(base)
        try {
            val text = fetchTextSingle(base, repoPath) ?: return null
            val direct = XimeIndexParser.parseDirectIndex(text)
            val schemes = direct.schemas.distinctBy { it.id }
                .map { XimeIndexParser.toItem(it, appVersion) }
            Log.i(TAG, "tryFetchFromBase $host: 获取到 ${schemes.size} 个方案（扁平索引）")

            if (schemes.isEmpty()) {
                Log.w(TAG, "tryFetchFromBase $host: 0 个方案，尝试下一个镜像")
                return null
            }
            return SchemesFetch(schemes, host, direct.updatedAt)
        } catch (e: Exception) {
            Log.w(TAG, "tryFetchFromBase $host failed: ${e.message}")
            return null
        }
    }

    /**
     * 获取插件列表：抓取 plugins/v2/index.yaml（扁平索引，plugins 内联所有 MarketPlugin；
     * 索引 v2 含 icon/activation/minHostVersion/platforms/capabilities/network 扩展字段）。
     * 遍历镜像直到成功；非 android 平台条目不可见（[XimeIndexParser.isAvailableOnAndroid]）；
     * 已安装版本表（id → versionName）用于派生 installed/hasUpdate 状态。
     */
    suspend fun fetchPlugins(
        context: Context,
        appVersion: String,
        installedVersions: Map<String, String>,
    ): Result<PluginsFetch> = withContext(Dispatchers.IO) {
        ensureConfigured(context)
        try {
            for (base in mirrors) {
                val host = hostOf(base)
                try {
                    val text = fetchTextSingle(base, PLUGINS_INDEX_PATH) ?: continue
                    val direct = XimeIndexParser.parsePluginsDirectIndex(text)
                    val plugins = direct.plugins.distinctBy { it.id }
                        .filter { XimeIndexParser.isAvailableOnAndroid(it) }
                        .map { XimeIndexParser.toPluginItem(it, appVersion, installedVersions) }
                    if (plugins.isNotEmpty()) {
                        return@withContext Result.success(
                            PluginsFetch(plugins, host, direct.updatedAt)
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "fetchPlugins $host failed: ${e.message}")
                }
            }
            Result.failure(IOException(tr(R.string.s_wufahuoquchajianliebiao_yichan_a0eaa, mirrors.size)))
        } catch (e: Exception) {
            Log.e(TAG, "fetchPlugins failed", e)
            Result.failure(e)
        }
    }

    /**
     * 下载并安装插件（.xipk）。下载到 cache 后交给插件安装器解压到 files/plugins/<id>/。
     * 返回安装结果；sha256 由下载层校验（有提供时）。
     */
    suspend fun downloadAndInstallPlugin(
        context: Context,
        plugin: MarketPlugin,
        version: String? = null,
        onDownloadProgress: (Long, Long) -> Unit = { _, _ -> },
    ): InstallResult = withContext(Dispatchers.IO) {
        val v = if (version != null) {
            plugin.versions.firstOrNull { it.version == version }
        } else {
            plugin.resolvedVersion()
        } ?: return@withContext InstallResult(false, failureReason = tr(R.string.s_wukeyongbanben))
        val dl = v.downloadUrls.firstOrNull { it.url.isNotBlank() }
            ?: return@withContext InstallResult(false, failureReason = tr(R.string.s_queshaoxiazaidizhi))

        val fileName = dl.fileName.ifBlank { "${plugin.id}.xipk" }
        val tmpFile = File(context.cacheDir, "xime_plugin_${plugin.id}_$fileName")

        val downloadResult = try {
            client.newCall(Request.Builder().url(dl.url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext InstallResult(false, failureReason = tr(R.string.s_xiazaishibai_http, response.code))
                }
                val body = response.body ?: return@withContext InstallResult(false, failureReason = tr(R.string.s_xiazaishibai_3))
                val totalBytes = body.contentLength()
                val md = if (!dl.sha256.isNullOrBlank()) {
                    java.security.MessageDigest.getInstance("SHA-256")
                } else null
                var downloadedBytes = 0L
                body.byteStream().use { input ->
                    tmpFile.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var n = input.read(buf)
                        while (n >= 0) {
                            output.write(buf, 0, n)
                            md?.update(buf, 0, n)
                            downloadedBytes += n
                            if (totalBytes > 0) onDownloadProgress(downloadedBytes, totalBytes)
                            n = input.read(buf)
                        }
                    }
                }
                if (md != null) {
                    val actual = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (!actual.equals(dl.sha256!!.trim(), ignoreCase = true)) {
                        tmpFile.delete()
                        return@withContext InstallResult(false, failureReason = tr(R.string.s_wenjianxiaoyanshibai_sha256_bu_c0d6f))
                    }
                }
                InstallResult(success = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "downloadPlugin failed", e)
            tmpFile.delete()
            return@withContext InstallResult(false, failureReason = tr(R.string.s_xiazaishibai_4, e.message))
        }
        if (!downloadResult.success) return@withContext downloadResult

        // 安装（source=remote 代表市场来源，信任级别由插件中心判定）
        val install = try {
            com.kingzcheung.xime.plugin.core.runtime.PluginManager.installerManager.installPlugin(
                tmpFile, forceOverwrite = true,
                source = com.kingzcheung.xime.plugin.core.model.PluginSource.REMOTE,
            )
        } catch (e: Exception) {
            Log.e(TAG, "installPlugin failed", e)
            return@withContext InstallResult(false, failureReason = tr(R.string.s_anzhuangshibai_2, e.message))
        } finally {
            tmpFile.delete()
        }
        when (install) {
            is com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager.InstallResult.Success ->
                InstallResult(success = true, sha256Status = if (dl.sha256.isNullOrBlank()) null else true)
            is com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager.InstallResult.Failure ->
                InstallResult(false, failureReason = install.reason)
        }
    }

    /**
     * 获取布局列表：抓取 layouts/index.yaml（扁平索引，layouts 内联所有 MarketLayout）。
     * 遍历镜像直到成功；[installedVersions] 为本地已应用布局版本表（id → version）。
     */
    suspend fun fetchLayouts(
        context: Context,
        appVersion: String,
        installedVersions: Map<String, String>,
        installedSchemaIds: Set<String>,
    ): Result<LayoutsFetch> = withContext(Dispatchers.IO) {
        ensureConfigured(context)
        try {
            for (base in mirrors) {
                val host = hostOf(base)
                try {
                    val text = fetchTextSingle(base, "layouts/index.yaml") ?: continue
                    val direct = XimeIndexParser.parseLayoutsDirectIndex(text)
                    val layouts = direct.layouts.distinctBy { it.id }
                        .map {
                            XimeIndexParser.toLayoutItem(
                                layout = it,
                                appVersion = appVersion,
                                installedVersion = installedVersions[it.id],
                                installedSchemaIds = installedSchemaIds,
                            )
                        }
                    if (layouts.isNotEmpty()) {
                        return@withContext Result.success(
                            LayoutsFetch(layouts, host, direct.updatedAt)
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "fetchLayouts $host failed: ${e.message}")
                }
            }
            Result.failure(IOException(tr(R.string.s_wufahuoqubujuliebiao_yichangsh_4e00d, mirrors.size)))
        } catch (e: Exception) {
            Log.e(TAG, "fetchLayouts failed", e)
            Result.failure(e)
        }
    }

    /**
     * 下载并应用一个布局：下载 zip → sha256 强校验 → 解压到 rime 用户目录
     * （根级 xime.custom.yaml / themes/ / fonts/，防 zip-slip）。
     * 返回本次释放的相对文件清单；调用方负责重载配置并记录状态。
     */
    suspend fun installLayout(
        context: Context,
        layout: MarketLayout,
        version: String? = null,
        onDownloadProgress: (Long, Long) -> Unit = { _, _ -> },
    ): LayoutInstallResult = withContext(Dispatchers.IO) {
        val v = if (version != null) {
            layout.versions.firstOrNull { it.version == version }
        } else {
            layout.resolvedVersion()
        } ?: return@withContext LayoutInstallResult(false, failureReason = tr(R.string.s_wukeyongbanben))
        val dl = v.downloadUrls.firstOrNull { it.url.isNotBlank() }
            ?: return@withContext LayoutInstallResult(false, failureReason = tr(R.string.s_queshaoxiazaidizhi))
        val sha = dl.sha256?.takeIf { it.isNotBlank() }
            ?: v.sha256.takeIf { it.isNotBlank() }
            ?: return@withContext LayoutInstallResult(false, failureReason = tr(R.string.s_queshao_sha256_xiaoyanzhi))

        val tmp = File(context.cacheDir, "xime_layout_${layout.id}_${v.version}.zip")
        val download = downloadFileWithSha(dl.url, tmp, sha, onDownloadProgress)
        if (!download.success) {
            tmp.delete()
            return@withContext LayoutInstallResult(false, failureReason = download.failureReason)
        }
        val extracted = try {
            if (isZipFile(tmp)) extractLayoutZip(context, tmp) else installRawLayout(context, tmp)
        } catch (e: Exception) {
            Log.e(TAG, "installLayout decode failed", e)
            null
        } finally {
            tmp.delete()
        }
        if (extracted == null) {
            return@withContext LayoutInstallResult(
                false, failureReason = tr(R.string.s_jieyashibaihuobaoneirongbuhefa_05019),
            )
        }
        LayoutInstallResult(success = true, files = extracted)
    }

    /** 单文件形态：下载物即 xime.custom.yaml，直接写入 rime 用户目录。 */
    private fun installRawLayout(context: Context, src: File): List<String>? {
        val text = try {
            src.readText().trimStart('\uFEFF')
        } catch (e: Exception) {
            Log.e(TAG, "read raw layout failed", e)
            return null
        }
        if (!XimeIndexParser.isValidCustomConfigYaml(text)) {
            Log.w(TAG, "raw layout is not a valid yaml map")
            return null
        }
        val rimeDir = File(context.filesDir, "rime")
        if (!rimeDir.exists()) rimeDir.mkdirs()
        val target = File(rimeDir, "xime.custom.yaml")
        if (!writeAtomically(target) { it.writeText(text) }) return null
        return listOf("xime.custom.yaml")
    }

    /**
     * 原子落盘：先写同目录 `<name>.tmp` 再 rename 覆盖。
     *
     * xime.custom.yaml 是用户全部键盘配置的唯一载体，直接截断写时若进程被杀会留下
     * 半截 YAML，解析失败后所有配置静默回退内置值（用户视角即「配置全丢」）。
     * 同目录 rename 在同一分区内是原子的，失败时原文件保持不动。
     */
    private fun writeAtomically(target: File, write: (File) -> Unit): Boolean = try {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        write(tmp)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (e: Exception) {
        Log.e(TAG, "atomic write failed: ${target.name}", e)
        File(target.parentFile, "${target.name}.tmp").delete()
        false
    }

    /** 探测 zip（PK\x03\x04 魔数）；否则按单文件（裸 xime.custom.yaml）处理。 */
    private fun isZipFile(file: File): Boolean = try {
        val header = ByteArray(4)
        file.inputStream().use { ins ->
            val n = ins.read(header)
            n == 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
        }
    } catch (e: Exception) {
        false
    }

    /**
     * 恢复默认：删除清单内的文件（themes/fonts/自定义配置），并兜底移除 xime.custom.yaml。
     * 路径越界一律跳过，避免误删 rime 目录之外的文件。
     */
    suspend fun resetLayout(context: Context, files: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            val rimeDir = File(context.filesDir, "rime")
            val rimeCanonical = rimeDir.canonicalFile
            var ok = true
            val targets = (files + "xime.custom.yaml").distinct()
            for (rel in targets) {
                val target = File(rimeDir, rel).canonicalFile
                val within = target.path.startsWith(rimeCanonical.path + File.separator)
                if (within && target.exists() && !target.delete()) ok = false
            }
            ok
        }

    /** 下载到 [dest] 并强校验 sha256（不匹配即删除并失败）。 */
    private fun downloadFileWithSha(
        url: String,
        dest: File,
        expectedSha: String,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) {
                DownloadOutcome(false, tr(R.string.s_xiazaishibai_http, response.code))
            } else {
                val body = response.body ?: return DownloadOutcome(false, tr(R.string.s_xiazaishibai_3))
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
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
                val actual = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (!actual.equals(expectedSha.trim(), ignoreCase = true)) {
                    dest.delete()
                    DownloadOutcome(false, tr(R.string.s_wenjianxiaoyanshibai_sha256_bu_c0d6f))
                } else {
                    DownloadOutcome(true)
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "downloadLayout failed", e)
        dest.delete()
        DownloadOutcome(false, tr(R.string.s_xiazaishibai_4, e.message))
    }

    /** 解压布局包到 rime 用户目录：仅接受根级 xime.custom.yaml 与 themes/、fonts/ 下文件。 */
    private fun extractLayoutZip(context: Context, zipFile: File): List<String>? {
        val rimeDir = File(context.filesDir, "rime")
        if (!rimeDir.exists()) rimeDir.mkdirs()
        val rimeCanonical = rimeDir.canonicalFile
        val written = mutableListOf<String>()
        // 包内 xime.custom.yaml 可选（主题/字体包可以只有资源）；有则必须是合法 YAML 映射。
        if (!validateZipCustomConfig(zipFile)) return null
        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && !isAppleDoubleName(name)) {
                    val rel = name.removePrefix("./").trimStart('/')
                    val allowed = rel == "xime.custom.yaml" ||
                        rel.startsWith("themes/") || rel.startsWith("fonts/")
                    if (allowed) {
                        val target = File(rimeDir, rel).canonicalFile
                        if (target.path.startsWith(rimeCanonical.path + File.separator)) {
                            // 配置文件走原子写（半截 YAML 会让全部自定义配置静默失效）；
                            // 主题/字体等资源直接写，损坏后重新应用布局即可覆盖
                            val writtenOk = if (rel == "xime.custom.yaml") {
                                writeAtomically(target) { tmp ->
                                    tmp.outputStream().use { out -> zis.copyTo(out) }
                                }
                            } else {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { out -> zis.copyTo(out) }
                                true
                            }
                            if (writtenOk) written.add(rel)
                        } else {
                            Log.w(TAG, "skip zip-slip layout entry: $name")
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return written.ifEmpty { null }
    }

    /** 包内 xime.custom.yaml 可选；存在时必须是非空、可解析的 YAML 映射。 */
    private fun validateZipCustomConfig(zipFile: File): Boolean {
        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && !isAppleDoubleName(entry.name)) {
                    val rel = entry.name.removePrefix("./").trimStart('/')
                    if (rel == "xime.custom.yaml") {
                        val text = zis.readBytes().toString(Charsets.UTF_8)
                        return XimeIndexParser.isValidCustomConfigYaml(text)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return true // 没有 xime.custom.yaml：纯主题/字体包，字段全部走内置默认
    }

    private fun isAppleDoubleName(name: String): Boolean =
        name.startsWith("__MACOSX/") || name.contains("/._") || name.startsWith("._")

    private data class DownloadOutcome(val success: Boolean, val failureReason: String? = null)

    /** 从镜像基址获取文件内容，失败返回 null。 */
    private fun fetchTextSingle(base: String, repoPath: String): String? {        return try {
            client.newCall(Request.Builder().url(base + repoPath).build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.string()?.takeIf { it.isNotBlank() }
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchTextSingle $base$repoPath failed: ${e.message}")
            null
        }
    }

    /**
     * 下载一个方案到 market 目录（仅下载，不解压）。
     * 遍历所有 downloadUrl（如主包 + 语言模型等），逐一下载到 market/{schemeId}/。
     *
     * 进度总量优先用预探测到的真实 Content-Length（索引里的体积可能过期，像万象拼音的
     * 语法模型索引值与实际差 3 倍，会让进度冲到 298%），探测失败再回退索引声明值。
     */
    suspend fun downloadScheme(
        context: Context,
        scheme: MarketScheme,
        version: String? = null,
        onDownloadProgress: (Long, Long) -> Unit = { _, _ -> },
    ): InstallResult = withContext(Dispatchers.IO) {
        val v = if (version != null) {
            scheme.versions.firstOrNull { it.version == version }
        } else {
            scheme.resolvedVersion()
        } ?: return@withContext InstallResult(false, failureReason = tr(R.string.s_wukeyongbanben))
        if (v.downloadUrls.isEmpty() || v.downloadUrls.all { it.url.isBlank() }) {
            return@withContext InstallResult(false, failureReason = tr(R.string.s_queshaoxiazaidizhi))
        }

        val items = v.downloadUrls.filter { it.url.isNotBlank() }
        val declaredSizes = items.map { it.resolvedSizeBytes }
        val probedSizes = coroutineScope {
            items.map { dl -> async { probeContentLength(dl.url) } }.awaitAll()
        }
        val sizes = declaredSizes.indices.map { i ->
            probedSizes[i].takeIf { it > 0 } ?: declaredSizes[i]
        }
        val progress = MarketDownloadProgress(sizes, onDownloadProgress)
        var anyVerified = false

        for ((index, dl) in items.withIndex()) {
            progress.startFile(index)
            val result = SchemaManager.downloadToMarket(
                context, dl.url, scheme.id, dl.fileName, dl.sha256?.takeIf { it.isNotBlank() },
                expectedTotal = sizes[index],
                onProgress = { read, reportedTotal -> progress.onBytes(read, reportedTotal) },
            )
            if (!result.success) {
                val schemeDir = SchemaManager.getMarketDir(context, scheme.id)
                if (schemeDir.exists()) schemeDir.deleteRecursively()
                val reason = if (result.sha256Verified == false)
                    tr(R.string.s_wenjianxiaoyanshibai_sha256_bu_e9d3c) else tr(R.string.s_xiazaishibai_3)
                return@withContext InstallResult(
                    false, failureReason = reason, sha256Status = result.sha256Verified
                )
            }
            progress.finishFile()
            if (result.sha256Verified == true) {
                anyVerified = true
            }
        }
        progress.complete()
        InstallResult(success = true, sha256Status = if (anyVerified) true else null)
    }

    /** HEAD 探测真实体积；失败/无长度/超时返回 0（调用方回退索引声明值）。 */
    private suspend fun probeContentLength(url: String): Long = withTimeoutOrNull(3_000L) {
        val call = client.newCall(Request.Builder().url(url).head().build())
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "probeContentLength failed: $url (${e.message})")
                    if (cont.isActive) cont.resume(0L)
                }

                override fun onResponse(call: Call, response: Response) {
                    val length = response.use {
                        if (!it.isSuccessful) return@use 0L
                        // HEAD 响应的 body 为空，长度只可能来自头部；body 仅作代理改写的兜底
                        it.header("Content-Length")?.toLongOrNull()?.takeIf { v -> v > 0 }
                            ?: it.body.contentLength().takeIf { v -> v > 0 } ?: 0L
                    }
                    if (cont.isActive) cont.resume(length)
                }
            })
        }
    } ?: 0L

    /**
     * 从 market 目录安装已下载的方案到 rime 目录（解压/复制 + 依赖补齐）。
     * 安装前检测文件冲突（同名且内容不同），发现真正冲突则阻止安装。
     */
    suspend fun installFromMarket(
        context: Context,
        scheme: MarketScheme,
        resolveDepUrl: (String) -> String? = { null },
        switchEnabled: Boolean = true,
    ): InstallResult = withContext(Dispatchers.IO) {
        if (!SchemaManager.isSchemeDownloaded(context, scheme.id)) {
            return@withContext InstallResult(false, failureReason = tr(R.string.s_yasuobaobucunzai_qingxianxiazai))
        }
        val result = SchemaManager.installPackageFromMarketDir(
            context = context,
            packageId = scheme.id,
            displayName = scheme.name,
            version = scheme.currentVersion,
            fromMarket = true,
            dependencies = scheme.dependencies,
            resolveDepUrl = resolveDepUrl,
            switchEnabled = switchEnabled,
        )
        if (!result.success) {
            return@withContext InstallResult(false, failureReason = result.failureReason)
        }
        InstallResult(success = true, unresolvedDeps = result.unresolvedDeps)
    }
}
