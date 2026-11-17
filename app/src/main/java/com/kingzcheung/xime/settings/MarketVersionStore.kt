package com.kingzcheung.xime.settings

import android.content.Context
import com.kingzcheung.xime.data.InstalledItemStore
import com.kingzcheung.xime.data.db.InstalledItemEntity

/**
 * 扩展商店的本地版本号记录：方案/模型 下载时写入实际版本，删除时清除。
 *
 * 存在 Room `installed_items`（kind=scheme / model）；旧版
 * `market_installed_versions` prefs 由 [InstalledItemStore] 首次加载时迁移。
 * 插件不走这里：已安装版本直接来自 PluginManager 注册表（PluginInfo.versionName）。
 */
object MarketVersionStore {

    /* ─────────── 方案 ─────────── */

    fun getSchemeVersion(context: Context, schemeId: String): String? =
        InstalledItemStore.get(context, InstalledItemStore.KIND_SCHEME, schemeId)
            ?.version?.takeIf { it.isNotBlank() }

    /** 全部已下载方案：schemeId → version。 */
    fun getAllSchemeVersions(context: Context): Map<String, String> =
        InstalledItemStore.all(context, InstalledItemStore.KIND_SCHEME)
            .mapValues { it.value.version }
            .filterValues { it.isNotBlank() }

    fun setSchemeVersion(context: Context, schemeId: String, version: String) {
        InstalledItemStore.put(
            context,
            InstalledItemEntity(
                kind = InstalledItemStore.KIND_SCHEME,
                id = schemeId,
                version = version,
            ),
        )
    }

    fun removeSchemeVersion(context: Context, schemeId: String) {
        InstalledItemStore.remove(context, InstalledItemStore.KIND_SCHEME, schemeId)
    }

    /* ─────────── 模型 ─────────── */

    fun getModelVersion(context: Context, modelId: String): String? =
        InstalledItemStore.get(context, InstalledItemStore.KIND_MODEL, modelId)
            ?.version?.takeIf { it.isNotBlank() }

    /** 全部已下载模型：modelId → version。 */
    fun getAllModelVersions(context: Context): Map<String, String> =
        InstalledItemStore.all(context, InstalledItemStore.KIND_MODEL)
            .mapValues { it.value.version }
            .filterValues { it.isNotBlank() }

    fun setModelVersion(context: Context, modelId: String, version: String) {
        InstalledItemStore.put(
            context,
            InstalledItemEntity(
                kind = InstalledItemStore.KIND_MODEL,
                id = modelId,
                version = version,
            ),
        )
    }

    fun removeModelVersion(context: Context, modelId: String) {
        InstalledItemStore.remove(context, InstalledItemStore.KIND_MODEL, modelId)
    }
}
