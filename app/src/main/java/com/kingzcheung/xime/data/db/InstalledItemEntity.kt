package com.kingzcheung.xime.data.db

import androidx.room3.Entity

/**
 * 已下载/已安装的元数据：方案包名称、方案版本、模型版本。
 *
 * 原来分别存在 `kime_settings/scheme_package_info`（JSON 串）和
 * `market_installed_versions` prefs 里，现统一到一张表；[kind] 区分：
 * `scheme_package` / `scheme` / `model`。
 */
@Entity(tableName = "installed_items", primaryKeys = ["kind", "id"])
data class InstalledItemEntity(
    val kind: String,
    val id: String,
    val name: String = "",
    val version: String = "",
)
