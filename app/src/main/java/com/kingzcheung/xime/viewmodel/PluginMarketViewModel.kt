package com.kingzcheung.xime.viewmodel

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.MarketUpdateChecker
import com.kingzcheung.xime.settings.XimeIndexSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kingzcheung.xime.settings.MarketPluginItem

data class PluginMarketUiState(
    val plugins: List<MarketPluginItem> = emptyList(),
    val isLoading: Boolean = false,
    /** 正在下载的插件 id */
    val downloadingId: String? = null,
    /** 下载进度 0f~1f */
    val downloadProgress: Float = 0f,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val source: String = "",
    val updatedAt: String = "",
    /** 用户选择的版本：pluginId → version 字符串 */
    val selectedVersions: Map<String, String> = emptyMap(),
    /** 选中的分类（pluginType）：null 表示全部 */
    val selectedCategory: String? = null,
) {
    val availableCategories: List<String>
        get() = plugins.mapNotNull { it.plugin.pluginType.takeIf { t -> t.isNotBlank() } }.distinct().sorted()

    val filteredPlugins: List<MarketPluginItem>
        get() = if (selectedCategory == null) plugins
        else plugins.filter { it.plugin.pluginType == selectedCategory }
}

class PluginMarketViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(PluginMarketUiState())
    val uiState: StateFlow<PluginMarketUiState> = _uiState.asStateFlow()

    init {
        loadPlugins()
    }

    /** 已安装插件版本表：id → versionName。 */
    private fun installedVersions(): Map<String, String> = try {
        PluginManager.getAllInstallPlugins()
            .filter { it.version.isNotBlank() }
            .associate { it.id to it.version }
    } catch (e: Exception) {
        emptyMap()
    }

    fun loadPlugins() {
        viewModelScope.launch {
            if (_uiState.value.isLoading) return@launch
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val appVersion = com.kingzcheung.xime.BuildConfig.VERSION_NAME
            val installed = withContext(Dispatchers.IO) { installedVersions() }
            val result = XimeIndexSource.fetchPlugins(context, appVersion, installed)
            result.onSuccess { fetch ->
                val existingSel = _uiState.value.selectedVersions
                val mergedSel = fetch.plugins.associate { item ->
                    val keep = existingSel[item.plugin.id]
                    if (keep != null && item.plugin.versions.any { it.version == keep }) {
                        item.plugin.id to keep
                    } else {
                        item.plugin.id to (item.plugin.resolvedVersion()?.version ?: item.plugin.currentVersion)
                    }
                }
                _uiState.update {
                    it.copy(
                        plugins = fetch.plugins,
                        isLoading = false,
                        source = fetch.source,
                        updatedAt = fetch.updatedAt,
                        errorMessage = null,
                        selectedVersions = mergedSel,
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = if (it.plugins.isEmpty()) (e.message ?: tr(R.string.s_jiazaichajianshichangshibai)) else it.errorMessage,
                        toastMessage = if (it.plugins.isEmpty()) null else tr(R.string.s_shuaxinshibai, e.message),
                    )
                }
            }
        }
    }

    fun refresh() {
        loadPlugins()
    }

    /**
     * 仅重新计算已安装状态（不重新拉取网络索引）。
     * 卸载插件后从其他页面返回市场时调用，避免缓存 installed=true 残留。
     */
    fun refreshInstalledState() {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { installedVersions() }
            _uiState.update { st ->
                st.copy(
                    plugins = st.plugins.map { p ->
                        val isInstalled = p.plugin.id in installed
                        if (p.installed != isInstalled || p.installedVersion != installed[p.plugin.id]) {
                            p.copy(
                                installed = isInstalled,
                                installedVersion = installed[p.plugin.id],
                            )
                        } else p
                    },
                )
            }
        }
    }

    fun selectCategory(category: String?) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun selectVersion(pluginId: String, version: String) {
        _uiState.update { it.copy(selectedVersions = it.selectedVersions + (pluginId to version)) }
    }

    /** 下载并安装插件；完成后刷新已安装状态。 */
    fun downloadPlugin(pluginId: String, version: String? = null) {
        if (_uiState.value.downloadingId != null) {
            showToast(tr(R.string.s_youqitachajianzhengzaixiazai_q_6304d))
            return
        }
        val item = _uiState.value.plugins.firstOrNull { it.plugin.id == pluginId } ?: return
        if (!item.compatible) {
            showToast(tr(R.string.s_xu_app, item.minAppVersion))
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingId = pluginId, downloadProgress = 0f) }
            val targetVersion = version ?: _uiState.value.selectedVersions[pluginId]
            val result = XimeIndexSource.downloadAndInstallPlugin(
                context, item.plugin,
                version = targetVersion,
                onDownloadProgress = { downloaded, total ->
                    val progress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                    _uiState.update { it.copy(downloadProgress = progress) }
                },
            )
            // 刷新已安装状态（含版本号，供 hasUpdate 判断）
            val installed = withContext(Dispatchers.IO) { installedVersions() }
            _uiState.update { st ->
                st.copy(
                    downloadingId = null,
                    downloadProgress = 0f,
                    plugins = st.plugins.map { p ->
                        if (p.plugin.id == pluginId) {
                            p.copy(
                                installed = pluginId in installed,
                                installedVersion = installed[pluginId],
                            )
                        } else p
                    },
                )
            }
            val toast = when {
                !result.success -> result.failureReason ?: tr(R.string.s_xiazai_anzhuangshibai)
                else -> tr(R.string.s_chajian_yianzhuang, item.plugin.name)
            }
            showToast(toast)
            // 安装成功立即重算可更新计数（Tab 角标与设置主页角标实时刷新）
            if (result.success) MarketUpdateChecker.refreshNow(context)
        }
    }

    fun clearToast() = _uiState.update { it.copy(toastMessage = null) }

    private fun showToast(message: String) = _uiState.update { it.copy(toastMessage = message) }
}
