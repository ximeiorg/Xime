package com.kingzcheung.xime.viewmodel

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.settings.MarketSchemeItem
import com.kingzcheung.xime.settings.MarketUpdateChecker
import com.kingzcheung.xime.settings.MarketVersionStore
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.XimeIndexSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SchemaMarketUiState(
    val schemes: List<MarketSchemeItem> = emptyList(),
    val isLoading: Boolean = false,
    /** 正在下载的方案 id */
    val downloadingId: String? = null,
    /** 下载进度 0f~1f */
    val downloadProgress: Float = 0f,
    /** 已下载到 market 目录的方案 id（压缩包文件存在） */
    val downloadedIds: Set<String> = emptySet(),
    /** 已下载方案的版本号：schemeId → version */
    val downloadedVersions: Map<String, String> = emptyMap(),
    /** sha256 校验状态：null=未提供sha256, true=校验通过, false=校验不通过 */
    val sha256Status: Map<String, Boolean?> = emptyMap(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    /** 安装被清单系统阻止时的文件冲突明细（同名不同内容），非空时 UI 弹冲突对话框。 */
    /** 触发冲突的方案名（对话框标题用）。 */
    val searchQuery: String = "",
    // 本次方案列表实际命中的来源端点主机名（如 index.ximei.me），用于在界面上显示「从哪个端点拉的」
    val source: String = "",
    /** 用户选择的版本：schemeId → version 字符串 */
    val selectedVersions: Map<String, String> = emptyMap(),
    /** 索引文件最后更新时间 */
    val updatedAt: String = "",
    /** 选中的分类标签：null 表示全部 */
    val selectedTag: String? = null,
) {
    /** 索引中出现的全部分类标签（去重排序）。 */
    val availableTags: List<String>
        get() = schemes.flatMap { it.scheme.tags }.distinct().sorted()

    val filteredSchemes: List<MarketSchemeItem>
        get() = schemes.filter { item ->
            val q = searchQuery.trim()
            val matchesQuery = q.isBlank() ||
                item.scheme.name.contains(q, true) ||
                item.scheme.description.contains(q, true) ||
                item.scheme.tags.any { t -> t.contains(q, true) }
            val matchesTag = selectedTag == null ||
                item.scheme.tags.isEmpty() ||
                item.scheme.tags.any { it == selectedTag }
            matchesQuery && matchesTag
        }
}

class SchemaMarketViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(SchemaMarketUiState())
    val uiState: StateFlow<SchemaMarketUiState> = _uiState.asStateFlow()

    init {
        loadSchemes()
    }

    /** 加载/刷新方案列表。[manual] 为 true 时（用户点刷新）成功也弹 toast 并报告来源端点。 */
    fun loadSchemes(manual: Boolean = false) {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = XimeIndexSource.fetchSchemes(context, BuildConfig.VERSION_NAME)
            // 「已下载」靠 market/{schemeId}/ 子目录存在性判断
            val downloadedIds = withContext(Dispatchers.IO) {
                val marketDir = SchemaManager.getMarketDir(context)
                if (!marketDir.exists()) emptySet()
                else marketDir.listFiles()?.mapNotNull { sub ->
                    if (sub.isDirectory && sub.listFiles()?.any { it.isFile } == true) sub.name
                    else null
                }?.toSet() ?: emptySet()
            }
            // 已下载版本号：目录存在性为准，版本号从本地记录读取
            val allVersions: Map<String, String> = withContext(Dispatchers.IO) {
                MarketVersionStore.getAllSchemeVersions(context)
            }.filterKeys { it in downloadedIds }
            result.onSuccess { fetch ->
                if (fetch.schemes.isEmpty()) {
                    // 索引可达但没取到任何方案：当作软失败处理，不要用空列表覆盖已有数据
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            source = fetch.source,
                            errorMessage = if (it.schemes.isEmpty())
                                tr(R.string.s_weihuoqudaofangan_laiyuan_qing_5b8c1, fetch.source) else it.errorMessage,
                            toastMessage = if (it.schemes.isEmpty()) tr(R.string.s_weihuoqudaofangan_laiyuan, fetch.source)
                                else tr(R.string.s_shuaxinweihuoqudaofangan_yibao_f2e01),
                        )
                    }
                } else {
                    val existingSel = _uiState.value.selectedVersions
                    val mergedSel = fetch.schemes.associate { item ->
                        val id = item.scheme.id
                        val keep = existingSel[id]
                        if (keep != null && item.scheme.versions.any { it.version == keep }) {
                            id to keep
                        } else {
                            val v = item.scheme.resolvedVersion()
                            id to (v?.version ?: item.scheme.currentVersion)
                        }
                    }
                    _uiState.update {
                        it.copy(
                            schemes = fetch.schemes.map { item ->
                                item.copy(installedVersion = allVersions[item.scheme.id])
                            },
                            isLoading = false,
                            source = fetch.source,
                            updatedAt = fetch.updatedAt,
                            errorMessage = null,
                            downloadedIds = downloadedIds,
                            downloadedVersions = allVersions,
                            selectedVersions = mergedSel,
                            toastMessage = if (manual) tr(R.string.s_yishuaxin_laiyuan_2, fetch.source) else it.toastMessage,
                        )
                    }
                }
            }.onFailure { e ->
                val msg = e.message ?: tr(R.string.s_jiazaifanganshichangshibai)
                // 失败一律弹 toast；已有列表时还保留旧数据，空列表时另外保留整页错误+重试
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = if (it.schemes.isEmpty()) msg else it.errorMessage,
                        toastMessage = if (it.schemes.isEmpty()) msg else tr(R.string.s_shuaxinshibai, msg),
                    )
                }
            }
        }
    }

    fun setSearchQuery(q: String) = _uiState.update { it.copy(searchQuery = q) }

    /** 选择方案市场的分类标签。 */
    fun selectTag(tag: String?) = _uiState.update { it.copy(selectedTag = tag) }

    /** 选择指定方案的版本。 */
    fun selectVersion(schemeId: String, version: String) {
        _uiState.update { it.copy(selectedVersions = it.selectedVersions + (schemeId to version)) }
    }

    /** 下载方案到 market 目录（仅下载，不解压）。[version] 为空时使用已选版本。 */
    fun downloadScheme(item: MarketSchemeItem, version: String? = null) {
        if (_uiState.value.downloadingId != null) {
            showToast(tr(R.string.s_youqitafanganzhengzaixiazai_qi_504ea))
            return
        }
        if (!item.compatible) {
            showToast(tr(R.string.s_xu_app, item.minAppVersion))
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingId = item.scheme.id, downloadProgress = 0f) }
            val selectedVersion = version ?: _uiState.value.selectedVersions[item.scheme.id]
            val result = withContext(Dispatchers.IO) {
                XimeIndexSource.downloadScheme(
                    context, item.scheme,
                    version = selectedVersion,
                    onDownloadProgress = { downloaded, total ->
                        val progress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                        _uiState.update { it.copy(downloadProgress = progress) }
                    },
                )
            }
            val nowDownloaded = withContext(Dispatchers.IO) {
                SchemaManager.isSchemeDownloaded(context, item.scheme.id)
            }
            // 下载成功后记录本地版本号（用于更新检测）
            val actualVersion = if (nowDownloaded) {
                (version ?: selectedVersion ?: item.scheme.resolvedVersion()?.version
                    ?: item.scheme.currentVersion).also {
                    withContext(Dispatchers.IO) {
                        MarketVersionStore.setSchemeVersion(context, item.scheme.id, it)
                    }
                }
            } else null
            _uiState.update { st ->
                st.copy(
                    downloadingId = null,
                    downloadProgress = 0f,
                    downloadedIds = if (nowDownloaded) st.downloadedIds + item.scheme.id else st.downloadedIds,
                    downloadedVersions = if (actualVersion != null)
                        st.downloadedVersions + (item.scheme.id to actualVersion) else st.downloadedVersions,
                    schemes = st.schemes.map { s ->
                        if (s.scheme.id == item.scheme.id && actualVersion != null) {
                            s.copy(installedVersion = actualVersion)
                        } else s
                    },
                    sha256Status = if (result.success || result.sha256Status == false)
                        st.sha256Status + (item.scheme.id to result.sha256Status)
                    else st.sha256Status,
                )
            }
            val toast = when {
                !result.success && result.sha256Status == false ->
                    tr(R.string.s_sha256_xiaoyanbutongguo_wenjia_7236f)
                !result.success -> result.failureReason ?: tr(R.string.s_xiazaishibai_3)
                result.sha256Status == true -> tr(R.string.s_yixiazai_yixiaoyan, item.scheme.name)
                result.sha256Status == null -> tr(R.string.s_yixiazai_weiyanzheng, item.scheme.name)
                else -> tr(R.string.s_yixiazai_2, item.scheme.name)
            }
            showToast(toast)
            // 下载成功立即重算可更新计数（Tab 角标与设置主页角标实时刷新）
            if (result.success) MarketUpdateChecker.refreshNow(context)
        }
    }

    /**
     * 更新已下载的方案：重新下载当前版本并安装覆盖 rime/ 旧版。
     * 更新不强制切换启用方案（保留用户现有 schema_list）。
     */
    fun updateScheme(item: MarketSchemeItem) {
        if (_uiState.value.downloadingId != null) {
            showToast(tr(R.string.s_youqitafanganzhengzaicaozuo_qi_53c0b))
            return
        }
        if (!item.compatible) {
            showToast(tr(R.string.s_xu_app, item.minAppVersion))
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(downloadingId = item.scheme.id, downloadProgress = 0f) }
            val selectedVersion = _uiState.value.selectedVersions[item.scheme.id]
            val download = withContext(Dispatchers.IO) {
                XimeIndexSource.downloadScheme(
                    context, item.scheme,
                    version = selectedVersion,
                    onDownloadProgress = { downloaded, total ->
                        val progress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                        _uiState.update { it.copy(downloadProgress = progress) }
                    },
                )
            }
            if (!download.success) {
                val reason = if (download.sha256Status == false)
                    tr(R.string.s_xiazaixiaoyanshibai_wenjianken_14291) else (download.failureReason ?: tr(R.string.s_xiazaishibai_3))
                _uiState.update { it.copy(downloadingId = null, downloadProgress = 0f) }
                showToast(reason)
                return@launch
            }

            val install = withContext(Dispatchers.IO) {
                XimeIndexSource.installFromMarket(context, item.scheme, switchEnabled = false)
            }

            val newVersion = selectedVersion ?: item.scheme.resolvedVersion()?.version
                ?: item.scheme.currentVersion
            _uiState.update { st ->
                st.copy(downloadingId = null, downloadProgress = 0f)
            }

            if (install.success) {
                withContext(Dispatchers.IO) {
                    MarketVersionStore.setSchemeVersion(context, item.scheme.id, newVersion)
                }
                _uiState.update { st ->
                    st.copy(
                        downloadedVersions = st.downloadedVersions + (item.scheme.id to newVersion),
                        schemes = st.schemes.map { s ->
                            if (s.scheme.id == item.scheme.id) s.copy(installedVersion = newVersion)
                            else s
                        },
                    )
                }
                val msg = buildString {
                    append(tr(R.string.s_yigengxin, item.scheme.name))
                    if (install.unresolvedDeps.isNotEmpty()) {
                        append("\n" + tr(R.string.s_yilaiweiwanzheng, install.unresolvedDeps.joinToString("、")))
                    }
                }
                showToast(msg)
                MarketUpdateChecker.refreshNow(context)
            } else {
                showToast(install.failureReason ?: tr(R.string.s_anzhuangshibai))
            }
        }
    }

    fun clearToast() = _uiState.update { it.copy(toastMessage = null) }
    private fun showToast(message: String) = _uiState.update { it.copy(toastMessage = message) }
}
