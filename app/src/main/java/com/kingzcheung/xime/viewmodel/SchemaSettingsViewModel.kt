package com.kingzcheung.xime.viewmodel

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.scheme.SchemeDeployer
import com.kingzcheung.xime.scheme.SchemeFiles
import com.kingzcheung.xime.scheme.SchemePackages
import com.kingzcheung.xime.scheme.SchemeStore
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.MarketUpdateChecker
import com.kingzcheung.xime.settings.ImportManager
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.settings.SchemaMeta
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SchemaUiState(
    val allSchemas: List<SchemaMeta> = emptyList(),
    val enabledSchemas: List<String> = emptyList(),
    val currentSchema: String = "wubi86",
    /** 当前方案集（活动包）的显示信息，标题卡用。 */
    val activePackageId: String = "",
    val activePackageName: String = "",
    val activePackageVersion: String = "",
    val isDeploying: Boolean = false,
    val isDownloading: Boolean = false,
    val toastMessage: String? = null,
)

class SchemaSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private companion object {
        const val TAG = "SchemaSettingsVM"
    }

    private val _uiState = MutableStateFlow(SchemaUiState())
    val uiState: StateFlow<SchemaUiState> = _uiState.asStateFlow()

    private val _importCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val importCompleted: SharedFlow<Unit> = _importCompleted.asSharedFlow()

    init {
        refresh()
    }

    /** 一次读盘的结果：方案列表 + 状态 + 当前方案集信息。 */
    private data class Snapshot(
        val allSchemas: List<SchemaMeta>,
        val enabled: List<String>,
        val current: String,
        val activeId: String,
        val activeName: String,
        val activeVersion: String,
    )

    fun refresh() {
        // 在 IO 线程读盘（discoverSchemas/getEnabledSchemas 扫描文件），避免 ON_RESUME 在主线程卡顿
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val activeId = SettingsPreferences.getActivePackageId(context).orEmpty()
                val builtin = activeId.isEmpty() || activeId == SchemeFiles.BUILTIN_PACKAGE_ID
                val installed = SchemeFiles.installedSchemaIds(SchemeFiles.rimeDir(context))
                val installedSet = installed.toSet()
                Snapshot(
                    // 方案列表 = 磁盘上真实存在的方案；启用与否由用户在列表里控制
                    allSchemas = SchemaManager.discoverSchemas(context)
                        .filter { it.schemaId in installedSet },
                    enabled = SchemaManager.getEnabledSchemas(context),
                    current = SettingsPreferences.getCurrentSchema(context),
                    activeId = activeId,
                    activeName = if (builtin) "" else SchemePackages.nameOf(context, activeId),
                    activeVersion = if (builtin) "" else SchemePackages.versionOf(context, activeId),
                )
            }
            val sorted = snapshot.allSchemas.sortedByDescending { it.schemaId in snapshot.enabled }
            _uiState.update {
                it.copy(
                    allSchemas = sorted,
                    enabledSchemas = snapshot.enabled,
                    currentSchema = snapshot.current,
                    activePackageId = snapshot.activeId,
                    activePackageName = snapshot.activeName,
                    activePackageVersion = snapshot.activeVersion,
                )
            }
        }
    }

    fun toggleSchema(schema: SchemaMeta) {
        val enabled = _uiState.value.enabledSchemas.toMutableList()
        if (schema.schemaId in enabled) {
            if (enabled.size <= 1) {
                showToast(tr(R.string.s_zhishaoqiyongyigefangan))
                return
            }
            enabled.remove(schema.schemaId)
        } else {
            enabled.add(schema.schemaId)
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                SchemaManager.setEnabledSchemas(context, enabled)
            }
            // 以写入后的真实状态为准：过滤（磁盘上不存在的 id）和 current 回落
            // 都由 SchemeStore 决定，UI 不能自己拼一份。
            val (effective, current) = withContext(Dispatchers.IO) {
                SchemaManager.getEnabledSchemas(context) to SettingsPreferences.getCurrentSchema(context)
            }
            _uiState.update { it.copy(enabledSchemas = effective, currentSchema = current) }
        }
    }

    fun selectSchema(schema: SchemaMeta) {
        if (_uiState.value.currentSchema == schema.schemaId) return
        val previous = _uiState.value.currentSchema
        SchemeStore.setCurrent(context, schema.schemaId)
        _uiState.update { it.copy(currentSchema = schema.schemaId) }
        if (RimeEngine.isInitialized()) {
            val engine = RimeEngine.getInstance()
            // 部署/编译持锁期间 getAvailableSchemas 拿不到锁只会返回空集合，
            // 直接按 available 判定会把「正在部署」误报成「未部署，请点部署按钮」。
            // 先按维护状态给准确提示，并回滚偏好（部署结束后用户可重试）。
            if (engine.isMaintaining()) {
                rollbackSchemaSelection(previous)
                showToast(tr(R.string.s_cikubushuzhong_qingshaohouzaiq_7d5ce))
                return
            }
            val available = engine.getAvailableSchemas()
            // 部署刚结束或从未变更过启用列表时，available 就是权威列表；
            // 用户刚启用但还没部署的方案不在这里，提示去部署（不再用 compiled 猜）。
            val switchable = schema.schemaId in available
            if (switchable) {
                // 部署/编译进行中 switchSchema 不阻塞返回 false，避免主线程等待
                val switched = engine.switchSchema(schema.schemaId)
                when {
                    switched -> showToast(tr(R.string.s_yiqiehuandao, schema.name))
                    engine.isMaintaining() -> {
                        rollbackSchemaSelection(previous)
                        showToast(tr(R.string.s_cikubushuzhong_qingshaohouzaiq_7d5ce))
                    }
                    else -> {
                        // 既不在 available 也没有产物之外的情况：引擎确实加载不了
                        rollbackSchemaSelection(previous)
                        FileLogger.w(
                            TAG,
                            "selectSchema(${schema.schemaId}) failed: not switchable, " +
                                "available=${available.joinToString()}"
                        )
                        showToast(tr(R.string.s_fanganweibushu_qingzaifangangu_63cf5))
                    }
                }
            } else {
                rollbackSchemaSelection(previous)
                FileLogger.w(
                    TAG,
                    "selectSchema(${schema.schemaId}) rejected: not in deployed schema_list, " +
                        "available=${available.joinToString()}"
                )
                showToast(tr(R.string.s_qingdianji_bushu_anniu))
            }
        }
    }

    /**
     * 切换失败时回滚偏好与 UI 状态。不回滚有两个后果：再次点击会被
     * [selectSchema] 开头的同值短路拦住（用户永远无法重试）；偏好与
     * 引擎实际方案脱节后，IME 重启的方案恢复逻辑会拿到偏差好。
     */
    private fun rollbackSchemaSelection(previous: String) {
        SchemeStore.setCurrent(context, previous)
        _uiState.update { it.copy(currentSchema = previous) }
    }

    fun importSchemaFile(uri: Uri) {
        viewModelScope.launch {
            when (val result = ImportManager.import(context, uri)) {
                is ImportManager.ImportResult.Content -> {
                    refresh()
                    _importCompleted.tryEmit(Unit)
                    showToast(
                        result.message ?: when {
                            !result.success -> tr(R.string.s_daorushibai)
                            result.installedDirect -> tr(R.string.s_daoruchenggong_yifangru_rime_mulu)
                            else -> tr(R.string.s_daoruchenggong_kezailiebiaozho_f4ac5)
                        }
                    )
                }
                is ImportManager.ImportResult.Plugin -> {
                    withContext(Dispatchers.IO) { PluginManager.loadEnabledPlugins() }
                    refresh()
                    _importCompleted.tryEmit(Unit)
                    showToast(tr(R.string.s_chajian_anzhuangchenggong, result.pluginInfo?.name))
                }
                is ImportManager.ImportResult.Failed -> showToast(tr(R.string.s_daorushibai_2, result.reason))
                is ImportManager.ImportResult.Unsupported -> showToast(tr(R.string.s_buzhichidewenjianleixing))
            }
        }
    }

    fun importFromUrl(url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true) }
            val result = withContext(Dispatchers.IO) {
                SchemaManager.importFromUrl(getApplication(), url)
            }
            _uiState.update { it.copy(isDownloading = false) }
            refresh()
            _importCompleted.tryEmit(Unit)
            showToast(
                result.message ?: if (result.success) {
                    tr(R.string.s_daoruchenggong)
                } else {
                    tr(R.string.s_xiazaihuojieyashibai_qingjianc_5e8b6)
                }
            )
        }
    }

    fun deploySchema() {
        if (_uiState.value.isDeploying) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeploying = true) }
            val (result, missingReverseDicts) = withContext(Dispatchers.IO) {
                // 键盘配置可能在别处被改动，部署前重新加载一次
                KeysConfigHelper.loadConfig(context)
                KeyboardThemes.reload(context)
                val deployed = SchemeDeployer.deploy(context)
                KeysConfigHelper.loadConfig(context)
                KeyboardThemes.reload(context)
                // 反查词典由它自己的方案编译：缺产物时运行期会报 table.bin 不存在
                deployed to RimeConfigHelper.missingReverseLookupTables(context)
            }
            _uiState.update { it.copy(isDeploying = false) }
            val missingSchemas = result.missingSchemas.map { id ->
                SchemaManager.getSchemaDisplayName(context, id) ?: id
            }
            val missingDicts = missingReverseDicts.map { it.second }.distinct()
            if (result.missingSchemas.isNotEmpty()) {
                FileLogger.e("SchemaSettings", "部署后仍有方案未编译: ${result.missingSchemas.joinToString()}")
            }
            if (missingDicts.isNotEmpty()) {
                FileLogger.w(
                    "SchemaSettings",
                    "部署后反查词典仍缺编译产物: $missingDicts（对应方案: ${missingReverseDicts.map { it.first }.distinct()}）"
                )
            }
            showToast(
                when {
                    missingSchemas.isNotEmpty() ->
                        tr(R.string.s_bushushibai_fanganweibianyi, missingSchemas.joinToString("、"))
                    !result.ok -> tr(R.string.s_bushushibai)
                    missingDicts.isEmpty() -> tr(R.string.s_bushuwancheng)
                    else -> tr(R.string.s_bushuwancheng_fanchacidian, missingDicts.joinToString("、"))
                }
            )
            refresh()
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }
}
