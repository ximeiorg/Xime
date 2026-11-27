package com.kingzcheung.xime.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.content.Context
import android.net.Uri
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * 统一的导入入口。
 *
 * 设计原则：**路由永远按「文件名 / 内容类型」集中在这里决定**，而不是由调用方传入的
 * "方向"参数决定。这样无论是文件选择器、分享、还是浏览器导入（web /upload），都汇聚到
 * 同一个路由核心，结构上不可能出现各入口路由逻辑不同步的问题。
 *
 * 调用方（各页面）只需要关心它从哪个入口触发导入，并对返回的 [ImportResult] 给出对应的
 * 成功文案与刷新动作即可。
 */
object ImportManager {

    sealed class ImportResult {
        /** 方案 / 词典 / 背景图等，走 [SchemaManager.saveImportedFile] 的落地结果。 */
        data class Content(
            val success: Boolean,
            val installedDirect: Boolean,
            /** 非空时优先作为提示文案（词库导入会带条数与挂载方案）。 */
            val message: String? = null,
            /** 落点类型（词库/方案包/配置/字体/图片/普通文件），供浏览器导入区分。 */
            val kind: ImportKind? = null,
        ) : ImportResult()

        /** 插件（.xipk）安装结果。 */
        data class Plugin(val pluginInfo: PluginInfo?) : ImportResult()

        data class Failed(val reason: String) : ImportResult()
        data class Unsupported(val fileName: String) : ImportResult()
    }

    /** 判断文件名是否为插件包（仅 .xipk）。 */
    fun isPluginFile(name: String): Boolean =
        name.endsWith(".xipk", ignoreCase = true)

    /**
     * 浏览器上传（web /upload）允许的文件类型。
     *
     * 清单等价于导入管线能落地的类型，集中在这里而不是散在路由里：网页 accept 里
     * 早就有 `.gram`，服务端白名单漏掉它，语法模型才会「传到 100% 提示类型不支持」。
     */
    fun isUploadSupported(name: String): Boolean = UPLOAD_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }

    private val UPLOAD_SUFFIXES = listOf(
        ".schema.yaml", ".dict.yaml", ".yaml", ".yml",
        ".gram",
        ".zip", ".tar.gz", ".tgz",
        ".xipk",
        ".scel", ".bdict", ".bin", ".dict", ".txt",
        ".jpg", ".jpeg", ".png",
        ".ttf", ".otf", ".woff", ".woff2",
    )

    /** 从 URI 导入（文件选择器、分享等）。按文件名自动路由。 */
    suspend fun import(
        context: Context,
        uri: Uri,
        lexiconSchemeIds: List<String> = emptyList(),
    ): ImportResult = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(context, uri)
            ?: return@withContext ImportResult.Failed(tr(R.string.s_wufashibiewenjianming))
        val name = SchemaManager.sanitizeDisplayName(displayName)

        if (isPluginFile(name)) {
            when (val r = PluginManager.installerManager.installPluginFromUri(uri)) {
                is InstallerManager.InstallResult.Success -> ImportResult.Plugin(r.pluginInfo)
                is InstallerManager.InstallResult.Failure -> ImportResult.Failed(r.reason)
            }
        } else {
            val stream = when (uri.scheme) {
                "file" -> java.io.FileInputStream(uri.path!!)
                else -> context.contentResolver.openInputStream(uri)
            }
            if (stream == null) {
                ImportResult.Failed(tr(R.string.s_wufaduquwenjian))
            } else {
                val r = SchemaManager.saveImportedFile(
                    context, name, stream, lexiconSchemeIds = lexiconSchemeIds
                )
                ImportResult.Content(r.success, r.installedDirect, r.message, r.kind)
            }
        }
    }

    /**
     * 从本地文件导入（浏览器上传的临时 partFile 等）。按文件名自动路由。
     *
     * @param autoEnable 是否在导入 .schema.yaml 时自动加入启用方案列表。
     *                   浏览器导入沿用历史行为传 false。
     */
    suspend fun importFile(
        context: Context,
        name: String,
        file: File,
        autoEnable: Boolean = true,
        lexiconSchemeIds: List<String> = emptyList(),
    ): ImportResult = withContext(Dispatchers.IO) {
        val safe = SchemaManager.sanitizeDisplayName(name)

        if (isPluginFile(safe)) {
            when (val r = PluginManager.installerManager.installPlugin(file, source = PluginSource.FILE)) {
                is InstallerManager.InstallResult.Success -> ImportResult.Plugin(r.pluginInfo)
                is InstallerManager.InstallResult.Failure -> ImportResult.Failed(r.reason)
            }
        } else {
            val r = SchemaManager.saveImportedFile(
                context, safe, file.inputStream(),
                autoEnable = autoEnable, lexiconSchemeIds = lexiconSchemeIds,
            )
            ImportResult.Content(r.success, r.installedDirect, r.message, r.kind)
        }
    }

    /**
     * 从流导入（浏览器上传的 multipart 分段等）。按文件名自动路由。
     *
     * 插件必须是可随机访问的文件（安装要校验包），此入口只接受非插件文件。
     */
    suspend fun importStream(
        context: Context,
        name: String,
        inputStream: InputStream,
        autoEnable: Boolean = true,
        lexiconSchemeIds: List<String> = emptyList(),
    ): ImportResult = withContext(Dispatchers.IO) {
        val safe = SchemaManager.sanitizeDisplayName(name)
        if (isPluginFile(safe)) return@withContext ImportResult.Unsupported(safe)
        val r = SchemaManager.saveImportedFile(
            context, safe, inputStream,
            autoEnable = autoEnable, lexiconSchemeIds = lexiconSchemeIds,
        )
        ImportResult.Content(r.success, r.installedDirect, r.message, r.kind)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment
    }
}
