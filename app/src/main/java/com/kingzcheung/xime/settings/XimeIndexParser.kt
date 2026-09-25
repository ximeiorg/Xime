package com.kingzcheung.xime.settings

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration

/**
 * 纯函数层：YAML 文本 → 模型；repo 相对路径解析；版本选择；appVersion 兼容性判定。
 * 不碰网络/Android，便于 100% 单测。
 */
object XimeIndexParser {

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    fun parseIndex(text: String): MarketIndex =
        yaml.decodeFromString(MarketIndex.serializer(), text)

    fun parseSubIndex(text: String): SchemesSubIndex =
        yaml.decodeFromString(SchemesSubIndex.serializer(), text)

    /** 解析扁平索引（rimes/index.yaml），其中 schemas 直接内联 MarketScheme 列表。 */
    fun parseDirectIndex(text: String): SchemasDirectIndex =
        yaml.decodeFromString(SchemasDirectIndex.serializer(), text)

    /** 解析插件扁平索引（plugins/index.yaml）。 */
    fun parsePluginsDirectIndex(text: String): PluginsDirectIndex =
        yaml.decodeFromString(PluginsDirectIndex.serializer(), text)

    /** 解析单个插件条目（与 [parseScheme] 对称，测试/工具用）。 */
    fun parsePlugin(text: String): MarketPlugin =
        yaml.decodeFromString(MarketPlugin.serializer(), text)

    /** 解析布局扁平索引（layouts/index.yaml）。 */
    fun parseLayoutsDirectIndex(text: String): LayoutsDirectIndex =
        yaml.decodeFromString(LayoutsDirectIndex.serializer(), text)

    fun parseScheme(text: String): MarketScheme =
        yaml.decodeFromString(MarketScheme.serializer(), migrateDownloadUrl(text))

    /**
     * 兼容旧格式 `downloadUrl: "..."` → 新格式 `downloadUrl:\n  - url: "..."`。
     * kaml 不支持在序列化器中做类型自适应，所以在文本层预处理。
     */
    private fun migrateDownloadUrl(yaml: String): String {
        return yaml.replace(Regex("""^(\s*)downloadUrl:\s+"([^"]*)"\s*$""", RegexOption.MULTILINE)) { match ->
            val indent = match.groupValues[1]
            val url = match.groupValues[2]
            "${indent}downloadUrl:\n${indent}  - url: \"$url\""
        }
    }

    /** 以 repo 相对路径为中心解析引用（去 ./、相对当前文件目录、折叠 ..、绝对 / 视为根）。 */
    fun resolveRepoPath(currentPath: String, ref: String): String {
        val r = ref.trim()
        val joined = when {
            r.startsWith("/") -> r.removePrefix("/")
            else -> {
                val base = currentPath.substringBeforeLast('/', "")
                if (base.isEmpty()) r else "$base/$r"
            }
        }
        val segs = ArrayDeque<String>()
        for (seg in joined.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (segs.isNotEmpty()) segs.removeLast()
                else -> segs.addLast(seg)
            }
        }
        return segs.joinToString("/")
    }

    /** appVersion 兼容性：空→true；>=X.Y.Z 取数值核心比较；无法解析→fail-open true。 */
    fun isCompatible(appVersion: String, constraint: String): Boolean {
        val c = constraint.trim()
        if (c.isEmpty()) return true
        if (!c.startsWith(">=")) return true // 不支持的操作符 → fail-open
        val min = numericCore(c.removePrefix(">=").trim())
        val app = numericCore(appVersion)
        // 任一无法解析（如 nightly-日期-commit、master 等非语义化版本号）→ fail-open
        if (min == null || app == null) return true
        for (i in 0..2) {
            if (app[i] != min[i]) return app[i] > min[i]
        }
        return true
    }

    /** 供"需 App ≥ x.y.z"文案。 */
    fun minAppVersionLabel(constraint: String): String {
        val c = constraint.trim()
        return when {
            c.startsWith(">=") -> c.removePrefix(">=").trim()
            c.startsWith(">") -> c.removePrefix(">").trim()
            else -> c
        }
    }

    fun toItem(
        scheme: MarketScheme,
        appVersion: String,
        installedVersion: String? = null,
    ): MarketSchemeItem =
        MarketSchemeItem(
            scheme = scheme,
            compatible = isCompatible(appVersion, scheme.appVersion),
            minAppVersion = minAppVersionLabel(scheme.appVersion),
            installedVersion = installedVersion,
        )

    /** 插件条目：兼容性判定与方案一致（appVersion 约束）；索引 v2 的 minHostVersion
     *  作为附加门禁折入（声明为 3.0.0 即按 >=3.0.0 判定）。[installedVersions] 为本地已安装版本表（id → versionName）。 */
    fun toPluginItem(
        plugin: MarketPlugin,
        appVersion: String,
        installedVersions: Map<String, String>,
    ): MarketPluginItem {
        val installedVersion = installedVersions[plugin.id]
        val hostCompatible = plugin.minHostVersion.isBlank() ||
            isCompatible(appVersion, ">=" + plugin.minHostVersion.trim())
        return MarketPluginItem(
            plugin = plugin,
            compatible = isCompatible(appVersion, plugin.appVersion) && hostCompatible,
            minAppVersion = minAppVersionLabel(plugin.appVersion),
            installed = installedVersion != null,
            installedVersion = installedVersion,
        )
    }

    /** 平台可见性（索引 v2 platforms）：未声明视为不限；声明了则仅 android 可见。 */
    fun isAvailableOnAndroid(plugin: MarketPlugin): Boolean =
        plugin.platforms.isEmpty() || "android" in plugin.platforms

    /** 布局条目：兼容性判定同方案/插件；[installedSchemaIds] 用于依赖方案校验。 */
    fun toLayoutItem(
        layout: MarketLayout,
        appVersion: String,
        installedVersion: String? = null,
        installedSchemaIds: Set<String> = emptySet(),
    ): MarketLayoutItem =
        MarketLayoutItem(
            layout = layout,
            compatible = isCompatible(appVersion, layout.appVersion),
            minAppVersion = minAppVersionLabel(layout.appVersion),
            installedVersion = installedVersion,
            schemeReady = layout.requiresSchemes.isEmpty() ||
                layout.requiresSchemes.all { it in installedSchemaIds },
        )

    /** 取版本号的数值核心 major.minor.patch（忽略 -beta/+build 后缀，缺位补 0）；无法解析返回 null。 */
    private fun numericCore(v: String): List<Int>? {
        val core = v.trim().takeWhile { it != '-' && it != '+' }
        if (core.isEmpty()) return null
        val parts = core.split('.').map { it.toIntOrNull() ?: return null }
        return (parts + listOf(0, 0, 0)).take(3)
    }
}
