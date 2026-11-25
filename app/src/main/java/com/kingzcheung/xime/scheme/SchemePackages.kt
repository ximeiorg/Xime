package com.kingzcheung.xime.scheme

import android.content.Context
import com.kingzcheung.xime.data.InstalledItemStore
import com.kingzcheung.xime.data.db.InstalledItemEntity
import com.kingzcheung.xime.settings.MarketVersionStore
import com.kingzcheung.xime.settings.SettingsPreferences

/**
 * 方案包列表：UI 只需要"我有哪些包、哪个在用"。
 *
 * 状态现算，不落清单文件：
 * - 可切换 = `market/<id>/` 里有压缩包；
 * - 使用中 = prefs 的 active（同一时刻只有一个）；
 * - 名称/版本 = `installed_items`（kind=scheme_package，离线可用），
 *   缺失时退回方案版本 / 包 id。
 */
object SchemePackages {

    data class Entry(
        val id: String,
        val name: String,
        val version: String,
        val downloaded: Boolean,
        val active: Boolean,
        val schemaCount: Int,
    )

    fun list(context: Context): List<Entry> {
        val active = SettingsPreferences.getActivePackageId(context)
        val infos = InstalledItemStore.all(context, InstalledItemStore.KIND_SCHEME_PACKAGE)
        val downloaded = SchemeFiles.downloadedPackageIds(context).toSet()
        val ids = (downloaded + infos.keys + listOfNotNull(active))
            .filter { it != SchemeFiles.BUILTIN_PACKAGE_ID }
            // 压缩包已删、又非当前使用中的包不再列出：历史版本删除时可能遗留
            // 安装元数据，这类条目既不能安装也不能删除，只会变成「未知」僵尸卡片
            .filter { it in downloaded || it == active }
            .sorted()
        val activeSchemaCount = SchemeFiles.installedSchemaIds(SchemeFiles.rimeDir(context)).size
        return ids.map { id ->
            Entry(
                id = id,
                name = infos[id]?.name?.takeIf { it.isNotBlank() } ?: id,
                version = infos[id]?.version?.takeIf { it.isNotBlank() }
                    ?: MarketVersionStore.getSchemeVersion(context, id).orEmpty(),
                downloaded = id in downloaded,
                active = id == active,
                schemaCount = if (id == active) activeSchemaCount else 0,
            )
        }
    }

    fun remember(context: Context, packageId: String, name: String, version: String) {
        if (packageId == SchemeFiles.BUILTIN_PACKAGE_ID) return
        if (name.isBlank() && version.isBlank()) return
        InstalledItemStore.put(
            context,
            InstalledItemEntity(
                kind = InstalledItemStore.KIND_SCHEME_PACKAGE,
                id = packageId,
                name = name.ifBlank { packageId },
                version = version,
            ),
        )
    }

    fun forget(context: Context, packageId: String) {
        InstalledItemStore.remove(context, InstalledItemStore.KIND_SCHEME_PACKAGE, packageId)
    }

    fun nameOf(context: Context, packageId: String): String =
        InstalledItemStore.get(context, InstalledItemStore.KIND_SCHEME_PACKAGE, packageId)
            ?.name?.takeIf { it.isNotBlank() } ?: packageId

    fun versionOf(context: Context, packageId: String): String =
        InstalledItemStore.get(context, InstalledItemStore.KIND_SCHEME_PACKAGE, packageId)
            ?.version?.takeIf { it.isNotBlank() }
            ?: MarketVersionStore.getSchemeVersion(context, packageId).orEmpty()
}
