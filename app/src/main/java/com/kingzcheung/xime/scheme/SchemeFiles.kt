package com.kingzcheung.xime.scheme

import android.content.Context
import java.io.File

/**
 * 方案集相关路径与「文件属于谁」的唯一判定处。
 *
 * `files/rime` 里同时存在三类文件，任何安装/卸载都必须按类处理：
 * - 包内容：归档解压出来的一切，换包时整体丢弃；
 * - 用户数据：白名单 4 项，跨包保留；
 * - 引擎产物：`build/`，随包重建（包内自带的收下）。
 */
object SchemeFiles {

    const val RIME_DIR = "rime"
    /** 已下载/已导入的压缩包存放处（历史路径，沿用不改名）。 */
    const val MARKET_DIR = "market"
    /** 内置方案包的 id，不落盘，内容来自 assets/rime。 */
    const val BUILTIN_PACKAGE_ID = "builtin"

    private const val INSTALL_HOLD_DIR = "install-hold"
    private const val OVERLAY_DIR = "scheme-overlay"

    fun rimeDir(context: Context): File = File(context.filesDir, RIME_DIR)

    fun marketDir(context: Context): File = File(context.filesDir, MARKET_DIR)

    fun marketDir(context: Context, packageId: String): File =
        File(marketDir(context), packageId)

    /**
     * 某个包自己的单文件导入（切走时暂存，切回恢复）。
     *
     * 不放 `market/<id>/` 下：`market/builtin/` 是旧模型的内置快照，会被清理；
     * overlay 是用户内容，必须独立于压缩包的生死。
     */
    fun overlayDir(context: Context, packageId: String): File =
        File(File(context.filesDir, OVERLAY_DIR), packageId)

    /** 安装过程中旁置用户数据的位置：cache 目录，同分区 rename，装完即删。 */
    fun installHoldDir(context: Context): File =
        File(context.cacheDir, INSTALL_HOLD_DIR)

    /**
     * 跨包保留的用户数据白名单：
     * `xime.custom.yaml`、`themes/`、`fonts/`、`custom_phrase*.txt`，
     * 以及导入词库生成的 `xime_import_*.dict.yaml`（记录存在 SQLite 里，文件必须跟着保留）。
     *
     * 其余一切（`*.userdb/`、`sync/`、`*.custom.yaml`、`default.custom.yaml`）随包丢弃。
     */
    fun isUserData(relPath: String): Boolean {
        val path = relPath.replace('\\', '/').trimStart('/')
        if (path == "xime.custom.yaml") return true
        if (path == "themes" || path.startsWith("themes/")) return true
        if (path == "fonts" || path.startsWith("fonts/")) return true
        val base = path.substringAfterLast('/')
        if (path == base && base.startsWith("xime_import_") && base.endsWith(".dict.yaml")) return true
        return path == base && base.startsWith("custom_phrase") && base.endsWith(".txt")
    }

    /** 引擎产物与安装中间态，不跨包保留。 */
    fun isRuntime(relPath: String): Boolean {
        val path = relPath.replace('\\', '/').trimStart('/')
        return path.startsWith("build/") || path == "build"
    }

    /** 包内容 = 既不是用户数据也不是引擎产物。 */
    fun isPackageContent(relPath: String): Boolean =
        !isUserData(relPath) && !isRuntime(relPath)

    /**
     * `rime/` 根目录下真实存在的方案 id。
     *
     * 只有根目录的 `<id>.schema.yaml` 算方案：`build/` 里的同名文件是 librime 的
     * 编译产物副本，不是独立方案。
     */
    fun installedSchemaIds(rimeDir: File): List<String> {
        val files = rimeDir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && it.name.endsWith(".schema.yaml") }
            .map { it.name.removeSuffix(".schema.yaml") }
            .sorted()
            .toList()
    }

    /** `market/` 下有压缩包的包 id（"可切换"判据）。 */
    fun downloadedPackageIds(context: Context): List<String> =
        marketDir(context).listFiles()
            ?.filter { it.isDirectory && it.listFiles()?.any { f -> f.isFile } == true }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
}
