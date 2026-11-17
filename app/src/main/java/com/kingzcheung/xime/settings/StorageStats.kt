package com.kingzcheung.xime.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.content.Context
import com.kingzcheung.xime.scheme.SchemeFiles
import com.kingzcheung.xime.clipboard.ClipboardImageStore
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.util.FileLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 存储空间分类统计与清除（设置 → 关于 → 存储空间）。
 *
 * 类目语义（清除安全性从高到低）：
 * - cache        cacheDir 整体（图片/临时下载缓存），清除后按需重建
 * - logs         filesDir/logs（保留 FileLogger 当日活跃文件，其句柄仍被持有）
 * - schema_cache rime/build 编译产物 + rime/logs；清除后复位部署标记，
 *                下次启动 ensureDeployment 自动全量重建，词库/自定义配置不受影响
 * - clipboard    Room 数据库文件（xime.db*，与导入词库元数据共用）+ filesDir/clipboard_images 图片文件；
 *                清除走 ClipboardManager 的剪贴板历史清空（保留快捷发送），
 *                并回收图片文件与数据库无引用的残留
 * - models       filesDir/models 已下载模型；清除后需重新下载
 * - plugins      filesDir/plugins + plugin_icons；不就地清除（批量卸载会连配置
 *                丢失），由插件管理页逐个卸载，本页只统计并引导跳转
 * - schemas      rime 其余数据（方案/用户词库/自定义配置），只统计不清除
 * - other        filesDir 其余数据（用户配置索引、联想 ngram 缓存等），只统计
 */
object StorageStats {

    const val ID_CACHE = "cache"
    const val ID_LOGS = "logs"
    const val ID_SCHEMA_CACHE = "schema_cache"
    const val ID_CLIPBOARD = "clipboard"
    const val ID_MODELS = "models"
    const val ID_PLUGINS = "plugins"
    const val ID_SCHEMAS = "schemas"
    const val ID_MARKET_PACKAGES = "market_packages"
    const val ID_OTHER = "other"

    /** 统计类目（只读信息；清除能力见 [clearCategory]）。 */
    data class Category(
        val id: String,
        val title: String,
        val description: String,
        val sizeBytes: Long,
        /** 提供就地"清理"按钮 */
        val clearable: Boolean,
        /** 点击行跳转对应管理页（如插件） */
        val navigable: Boolean = false,
    )

    // ── 路径 ──

    fun modelsDir(context: Context): File = File(context.filesDir, "models")
    fun rimeDir(context: Context): File = File(context.filesDir, "rime")
    fun rimeBuildDir(context: Context): File = File(context.filesDir, "rime/build")
    fun rimeLogsDir(context: Context): File = File(context.filesDir, "rime/logs")
    fun pluginsDir(context: Context): File = File(context.filesDir, "plugins")
    fun pluginIconsDir(context: Context): File = File(context.filesDir, "plugin_icons")
    fun logsDir(context: Context): File = File(context.filesDir, "logs")

    /** 方案市场下载包目录（与 rime/ 同级；builtin 子目录为内置方案包，不可清除）。 */
    fun marketDir(context: Context): File = File(context.filesDir, "market")
    fun databaseFiles(context: Context): List<File> {
        val dbDir = File(context.filesDir, "databases")
        return listOf("xime.db", "xime.db-wal", "xime.db-shm")
            .map { File(dbDir, it) }
            .filter { it.exists() }
    }

    /** 剪贴板图片目录（files/clipboard_images）。 */
    fun clipboardImagesDir(context: Context): File =
        ClipboardImageStore.rootDirOf(context.filesDir)

    // ── 统计 ──

    /** 递归统计目录大小；目录不存在返回 0。 */
    fun directorySize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { directorySize(it) } ?: 0L
    }

    /** 汇总各类目占用（IO 耗时，须在后台线程调用）。 */
    fun collectCategories(context: Context): List<Category> {
        val modelsSize = directorySize(modelsDir(context))
        val rimeSize = directorySize(rimeDir(context))
        val buildSize = directorySize(rimeBuildDir(context)) + directorySize(rimeLogsDir(context))
        val pluginsSize = directorySize(pluginsDir(context)) + directorySize(pluginIconsDir(context))
        val marketSize = directorySize(marketDir(context))
        // 剪贴板类目 = Room 库文件 + 图片文件（files/clipboard_images）；
        // 不并入的话图片目录会落进 otherSize（不可清理的"其他数据"）
        val clipboardSize = databaseFiles(context).sumOf { it.length() } +
            directorySize(clipboardImagesDir(context))
        val logsSize = directorySize(logsDir(context))
        val cacheSize = directorySize(context.cacheDir)

        val accounted = modelsSize + rimeSize + pluginsSize + marketSize + clipboardSize + logsSize
        val otherSize = (directorySize(context.filesDir) - accounted).coerceAtLeast(0L)

        return listOf(
            Category(
                id = ID_MODELS,
                title = tr(R.string.s_lixianmoxing),
                description = tr(R.string.s_lianxiang_shouxie_yuyinshibiem_92b32),
                sizeBytes = modelsSize,
                clearable = true,
            ),
            Category(
                id = ID_SCHEMA_CACHE,
                title = tr(R.string.s_fanganbianyihuancun),
                description = tr(R.string.s_shurufangandebianyichanwuyuriz_09c3d),
                sizeBytes = buildSize,
                clearable = true,
            ),
            Category(
                id = ID_SCHEMAS,
                title = tr(R.string.s_fanganyuciku),
                description = tr(R.string.s_yianzhuangfangan_yonghucikuyuz_4ff01),
                sizeBytes = (rimeSize - buildSize).coerceAtLeast(0L),
                clearable = false,
            ),
            Category(
                id = ID_PLUGINS,
                title = tr(R.string.s_chajian),
                description = tr(R.string.s_yianzhuangchajianjiqiziyuan_xi_10fe0),
                sizeBytes = pluginsSize,
                clearable = false,
                navigable = true,
            ),
            Category(
                id = ID_MARKET_PACKAGES,
                title = tr(R.string.s_yixiazaifanganbao),
                description = tr(R.string.s_fanganshichangdeanzhuangbao_ne_dc374),
                sizeBytes = marketSize,
                clearable = true,
            ),
            Category(
                id = ID_CLIPBOARD,
                title = tr(R.string.s_jiantiebanlishi),
                description = tr(R.string.s_bendijiantiebanwenziyutupianji_4f15c),
                sizeBytes = clipboardSize,
                clearable = true,
            ),
            Category(
                id = ID_LOGS,
                title = tr(R.string.s_rizhi),
                description = tr(R.string.s_yunxingrizhiwenjian_baoliudang_cea09),
                sizeBytes = logsSize,
                clearable = true,
            ),
            Category(
                id = ID_CACHE,
                title = tr(R.string.s_huancun),
                description = tr(R.string.s_tupianyulinshiwenjianhuancun_k_c74ad),
                sizeBytes = cacheSize,
                clearable = true,
            ),
            Category(
                id = ID_OTHER,
                title = tr(R.string.s_qitashuju),
                description = tr(R.string.s_yonghupeizhisuoyin_lianxiangci_d85b5),
                sizeBytes = otherSize,
                clearable = false,
            ),
        )
    }

    fun totalSize(categories: List<Category>): Long = categories.sumOf { it.sizeBytes }

    // ── 清除 ──

    /**
     * 清除指定类目（IO 耗时，须在后台线程调用；剪贴板类目会等待引擎清空完成，
     * 以便调用方紧接着重新统计时能读到清理后的真实占用）。
     * @return 是否有内容被清理（空目录清理也返回 true，UI 不区分）
     */
    suspend fun clearCategory(context: Context, id: String): Boolean {
        return when (id) {
            ID_MODELS -> clearModels(context)
            ID_SCHEMA_CACHE -> clearSchemaCache(context)
            ID_CLIPBOARD -> {
                // 走引擎清空：保留快捷发送与置顶，回收图片文件与残留，并等待完成
                ClipboardManager.getInstance(context).clearClipboardAndWait()
                true
            }
            ID_LOGS -> clearLogs(context)
            ID_CACHE -> deleteContents(context.cacheDir)
            ID_MARKET_PACKAGES -> clearMarketPackages(context)
            else -> false
        }
    }

    /**
     * 清除非内置的已下载方案包（与方案管理页"删除已下载"同语义）：
     * 已安装方案不受影响；builtin 目录是内置方案包（系统依赖），必须保留。
     * 逐个走 deleteSchemeArchive 以同步清理本地版本记录。
     */
    private fun clearMarketPackages(context: Context): Boolean {
        var ok = true
        marketDir(context).listFiles()?.forEach { pkg ->
            if (pkg.name == SchemeFiles.BUILTIN_PACKAGE_ID) return@forEach
            if (!SchemaManager.deleteSchemeArchive(context, pkg.name)) ok = false
        }
        return ok
    }

    private fun clearModels(context: Context): Boolean {
        var ok = true
        for (model in ModelManager.getAllModels()) {
            if (!ModelManager.deleteModel(context, model)) ok = false
        }
        // 未知来源的残余模型目录（如旧版本残留）一并移除
        modelsDir(context).listFiles()?.forEach { dir ->
            if (!dir.deleteRecursively()) ok = false
        }
        return ok
    }

    /**
     * 清除 rime 编译产物与日志。
     * build 被清空后，下一次 ensureDeployment 会因产物缺失自动升级为 fullCheck 维护，
     * 不需要额外的部署标记。
     */
    private fun clearSchemaCache(context: Context): Boolean =
        deleteContents(rimeBuildDir(context)) && deleteContents(rimeLogsDir(context))

    private fun clearLogs(context: Context): Boolean {
        // 当日活跃文件被 FileLogger 持有句柄，删除后空间不会释放且新日志丢失，保留
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val keep = "kime_$today.log"
        val dir = logsDir(context)
        var ok = true
        dir.listFiles()?.forEach { f ->
            if (f.name != keep && !f.delete()) ok = false
        }
        FileLogger.i("StorageStats", "Logs cleared (kept active file: $keep)")
        return ok
    }

    /** 清空目录内容（保留目录本身），返回是否全部成功。 */
    private fun deleteContents(dir: File): Boolean {
        if (!dir.exists()) return true
        var ok = true
        dir.listFiles()?.forEach { f ->
            if (!f.deleteRecursively()) ok = false
        }
        return ok
    }
}
