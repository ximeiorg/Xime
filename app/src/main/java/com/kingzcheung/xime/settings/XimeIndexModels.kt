package com.kingzcheung.xime.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * xime-index（ximeiorg/xime-index）市场索引的数据模型。
 * 所有字段给默认值；解析时 strictMode=false 忽略未知键，对索引演进有韧性。
 */

@Serializable
data class MarketIndex(
    @SerialName("index_version") val indexVersion: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    val schemas: IndexRef? = null,
    val plugins: IndexRef? = null,
    val sources: List<IndexSource> = emptyList(),
)

@Serializable
data class IndexRef(val from: String = "")

@Serializable
data class IndexSource(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val description: String = "",
)

/**
 * 扁平索引格式：schemas 直接内联 MarketScheme 对象列表。
 * 对应 rimes/index.yaml（由 scripts/generate_index.py 生成）。
 */
@Serializable
data class SchemasDirectIndex(
    @SerialName("index_version") val indexVersion: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    val schemas: List<MarketScheme> = emptyList(),
)

@Serializable
data class SchemesSubIndex(
    @SerialName("index_version") val indexVersion: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    val schemas: List<SubIndexEntry> = emptyList(),
)

@Serializable
data class SubIndexEntry(val file: String = "", val version: String = "")

@Serializable
data class MarketScheme(
    val id: String = "",
    val name: String = "",
    val author: String = "",
    val description: String = "",
    val type: String = "remote",
    val tags: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    @SerialName("appVersion") val appVersion: String = "",
    @SerialName("currentVersion") val currentVersion: String = "",
    val versions: List<SchemeVersion> = emptyList(),
    val homepage: String = "",
    val license: String = "",
    val warning: String = "",
) {
    /** 当前应安装的版本：优先匹配 currentVersion，否则取第一条，都没有则 null。 */
    fun resolvedVersion(): SchemeVersion? =
        versions.firstOrNull { it.version == currentVersion } ?: versions.firstOrNull()
}

/** 下载条目（多文件时 downloadUrl 数组中的单条）。 */
@Serializable
data class DownloadItem(
    val url: String = "",
    val sha256: String? = null,
    val size: String? = null,
    /** 精确字节数（索引生成器提供；用于下载进度总量，缺省回退 size 字符串估算）。 */
    @SerialName("sizeBytes") val sizeBytes: Long? = null,
)

@Serializable
data class SchemeVersion(
    val version: String = "",
    val date: String = "",
    val changelog: String = "",
    @SerialName("downloadUrl")
    val downloadUrls: List<DownloadItem> = emptyList(),
    val size: String = "",
    val sha256: String = "",
)

/** 列表项 = 方案 + 运行期派生状态（不污染可序列化模型）。 */
data class MarketSchemeItem(
    val scheme: MarketScheme,
    val compatible: Boolean,
    val minAppVersion: String,
    /** 本地已下载的方案版本（无则未下载） */
    val installedVersion: String? = null,
) {
    /** 是否已有更新：已下载且本地版本 != 索引当前版本。 */
    val hasUpdate: Boolean
        get() = installedVersion != null && scheme.currentVersion.isNotBlank() &&
            installedVersion != scheme.currentVersion
}

/* ─────────────────────────── 插件市场 ─────────────────────────── */

/** 扁平插件索引格式：plugins 直接内联 MarketPlugin 对象列表（对应 plugins/index.yaml）。 */
@Serializable
data class PluginsDirectIndex(
    @SerialName("index_version") val indexVersion: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    val plugins: List<MarketPlugin> = emptyList(),
)

@Serializable
data class MarketPlugin(
    val id: String = "",
    val name: String = "",
    val author: String = "",
    val description: String = "",
    val type: String = "remote",
    val tags: List<String> = emptyList(),
    @SerialName("pluginType") val pluginType: String = "",
    /** 键面字符图标（manifest.icon，索引 v2 起提供；空 = 未声明，UI 回退分类图标）。 */
    val icon: String = "",
    /** 激活模式：single（单选激活，如语音）/ multi（多选，索引 v2 起提供；空 = 未声明）。 */
    val activation: String = "",
    /** 宿主最低版本（索引 v2 起提供；空 = 未声明，兼容性由 appVersion 约束承载）。 */
    @SerialName("minHostVersion") val minHostVersion: String = "",
    /** 目标平台（索引 v2 起提供；空 = 未声明，视为不限）。 */
    val platforms: List<String> = emptyList(),
    /** 能力声明（索引 v2 起提供）：已知类型见 [PluginCapabilities]，未知类型忽略；
     *  安装后的运行时事实来源仍是包内 manifest.json。 */
    val capabilities: PluginCapabilities = PluginCapabilities(),
    /** 网络策略（索引 v2 起提供）：需联网域名 / 是否允许自定义接口地址。 */
    val network: PluginNetworkPolicy = PluginNetworkPolicy(),
    @SerialName("appVersion") val appVersion: String = "",
    @SerialName("currentVersion") val currentVersion: String = "",
    val versions: List<PluginVersion> = emptyList(),
    val homepage: String = "",
    val license: String = "",
    val warning: String = "",
) {
    /** 当前应安装的版本：优先匹配 currentVersion，否则取第一条。 */
    fun resolvedVersion(): PluginVersion? =
        versions.firstOrNull { it.version == currentVersion } ?: versions.firstOrNull()
}

/**
 * 插件能力声明（索引 v2 capabilities）。
 * 已知能力强类型建模；未知能力键（emoji/candidate_transform/events 等）由
 * strictMode=false 忽略，后续需要时在此扩展字段即可。
 */
@Serializable
data class PluginCapabilities(
    val tool: PluginToolCapability? = null,
    val speech: PluginSpeechCapability? = null,
)

@Serializable
data class PluginToolCapability(
    /** 面板展示方式：passive（被动展示）/ direct（直接调用）。 */
    val display: String = "",
)

@Serializable
data class PluginSpeechCapability(
    /** 输入模式：streaming（流式）等。 */
    @SerialName("inputMode") val inputMode: String = "",
    /** 是否支持部分（中间）结果。 */
    @SerialName("supportsPartialResults") val supportsPartialResults: Boolean = false,
)

/** 插件网络策略（索引 v2 network）。 */
@Serializable
data class PluginNetworkPolicy(
    /** 需要联网的域名清单。 */
    val hosts: List<String> = emptyList(),
    /** 是否允许用户自定义接口地址（如 OpenAI 兼容端点）。 */
    @SerialName("allowCustomHosts") val allowCustomHosts: Boolean = false,
)

@Serializable
data class PluginVersion(
    val version: String = "",
    val date: String = "",
    val changelog: String = "",
    @SerialName("downloadUrl")
    val downloadUrls: List<DownloadItem> = emptyList(),
    val size: String = "",
    val sha256: String = "",
)

/** 列表项 = 插件 + 运行期派生状态。 */
data class MarketPluginItem(
    val plugin: MarketPlugin,
    val compatible: Boolean,
    val minAppVersion: String,
    val installed: Boolean,
    /** 本地已安装的插件版本（安装后来自 PluginInfo.versionName） */
    val installedVersion: String? = null,
) {
    /** 是否已有更新：已安装且本地版本 != 索引当前版本。 */
    val hasUpdate: Boolean
        get() = installed && installedVersion != null && plugin.currentVersion.isNotBlank() &&
            installedVersion != plugin.currentVersion
}

/* ─────────────────────────── 布局市场 ─────────────────────────── */

/**
 * 扁平布局索引格式：layouts 直接内联 MarketLayout 对象列表（对应 layouts/index.yaml）。
 * 中心索引只做引用，包/资源/截图托管在作者自有仓库。
 */
@Serializable
data class LayoutsDirectIndex(
    @SerialName("index_version") val indexVersion: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    val layouts: List<MarketLayout> = emptyList(),
)

@Serializable
data class MarketLayout(
    val id: String = "",
    val name: String = "",
    val author: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val repo: String = "",
    val homepage: String = "",
    val license: String = "",
    val warning: String = "",
    @SerialName("appVersion") val appVersion: String = "",
    /** 依赖的输入方案 id：非空且未安装时不可应用。 */
    @SerialName("requiresSchemes") val requiresSchemes: List<String> = emptyList(),
    /** 详情页截图 URL（1~5 张）。 */
    val screenshots: List<String> = emptyList(),
    @SerialName("currentVersion") val currentVersion: String = "",
    val versions: List<LayoutVersion> = emptyList(),
) {
    /** 当前应安装的版本：优先匹配 currentVersion，否则取第一条，都没有则 null。 */
    fun resolvedVersion(): LayoutVersion? =
        versions.firstOrNull { it.version == currentVersion } ?: versions.firstOrNull()
}

@Serializable
data class LayoutVersion(
    val version: String = "",
    val date: String = "",
    val changelog: String = "",
    @SerialName("downloadUrl")
    val downloadUrls: List<DownloadItem> = emptyList(),
    val size: String = "",
    val sha256: String = "",
)

/** 布局列表项 = 布局 + 运行期派生状态。 */
data class MarketLayoutItem(
    val layout: MarketLayout,
    val compatible: Boolean,
    val minAppVersion: String,
    /** 本地已应用布局的版本（无则未应用） */
    val installedVersion: String? = null,
    /** 依赖方案是否已安装（requiresSchemes 为空视为满足） */
    val schemeReady: Boolean = true,
) {
    val applied: Boolean get() = installedVersion != null

    /** 是否已有更新：已应用且本地版本 != 索引当前版本。 */
    val hasUpdate: Boolean
        get() = applied && layout.currentVersion.isNotBlank() &&
            installedVersion != layout.currentVersion
}
