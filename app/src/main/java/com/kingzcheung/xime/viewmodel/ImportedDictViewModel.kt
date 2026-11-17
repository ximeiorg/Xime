package com.kingzcheung.xime.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.R
import com.kingzcheung.xime.i18n.tr
import com.kingzcheung.xime.lexicon.LexiconCodeSystem
import com.kingzcheung.xime.lexicon.LexiconFormats
import com.kingzcheung.xime.settings.ImportManager
import com.kingzcheung.xime.dictionary.ImportedDictManager
import com.kingzcheung.xime.settings.SchemaManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ImportedDictUiItem(
    val id: String,
    val displayName: String,
    val formatName: String,
    val codeSystem: String,
    val entryCount: Int,
    val schemeIds: List<String>,
    val schemeNames: List<String>,
    val enabled: Boolean,
    val compiled: Boolean,
)

data class ImportedDictUiState(
    val items: List<ImportedDictUiItem> = emptyList(),
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val message: String? = null,
    val schemeOptions: List<ImportedDictManager.SchemeOption> = emptyList(),
)

/** 「词库管理 → 导入词库」：列表、导入、启停、删除。 */
class ImportedDictViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(ImportedDictUiState())
    val uiState: StateFlow<ImportedDictUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val (items, options) = withContext(Dispatchers.IO) {
                val rimeDir = SchemaManager.getRimeDir(context)
                val options = ImportedDictManager.compatiblePinyinSchemes(context)
                val items = ImportedDictManager.list(context).map { record ->
                    ImportedDictUiItem(
                        id = record.id,
                        displayName = record.displayName,
                        formatName = record.formatName.ifBlank { tr(R.string.s_ciku) },
                        codeSystem = codeSystemLabel(record.codeSystem),
                        entryCount = record.entryCount,
                        schemeIds = record.attachedSchemas,
                        schemeNames = record.attachedSchemas.map { schemaId ->
                            SchemaManager.getSchemaDisplayName(context, schemaId) ?: schemaId
                        },
                        enabled = record.enabled,
                        compiled = File(rimeDir, "build/${record.id}.table.bin").isFile,
                    )
                }
                items to options
            }
            _uiState.update { it.copy(items = items, schemeOptions = options, isLoading = false) }
        }
    }

    /** 从 SAF 导入；只接受注册表里认领的词库格式。 */
    fun import(uri: Uri, schemeIds: List<String>) {
        viewModelScope.launch {
            val rawName = withContext(Dispatchers.IO) { queryDisplayName(uri) }
            val name = rawName?.let { SchemaManager.sanitizeDisplayName(it) }
            if (name == null || !LexiconFormats.hasCandidate(name)) {
                _uiState.update {
                    it.copy(
                        message = tr(
                            R.string.s_daoruciku_buzhichi,
                            LexiconFormats.supportedDescription(),
                        )
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(isWorking = true) }
            if (schemeIds.isEmpty()) {
                _uiState.update {
                    it.copy(message = tr(R.string.s_daoruciku_qingxuanze))
                }
                return@launch
            }
            val result = ImportManager.import(context, uri, lexiconSchemeIds = schemeIds)
            val message = when (result) {
                is ImportManager.ImportResult.Content -> {
                    if (result.success) {
                        result.message ?: tr(R.string.s_daoruciku_yigengxin, name)
                    } else {
                        result.message ?: tr(R.string.s_daorushibai)
                    }
                }
                is ImportManager.ImportResult.Failed ->
                    tr(R.string.s_daorushibai_2, result.reason)
                is ImportManager.ImportResult.Unsupported ->
                    tr(R.string.s_daoruciku_buzhichi, LexiconFormats.supportedDescription())
                is ImportManager.ImportResult.Plugin ->
                    tr(R.string.s_daoruciku_buzhichi, LexiconFormats.supportedDescription())
            }
            _uiState.update { it.copy(isWorking = false, message = message) }
            load()
        }
    }

    /** 从 URL 导入；调用方已让用户选好挂载方案。 */
    fun importFromUrl(url: String, schemeIds: List<String>) {
        if (schemeIds.isEmpty()) {
            _uiState.update { it.copy(message = tr(R.string.s_daoruciku_qingxuanze)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true) }
            val result = withContext(Dispatchers.IO) {
                SchemaManager.importFromUrl(
                    context = context,
                    url = url,
                    lexiconSchemeIds = schemeIds,
                )
            }
            _uiState.update {
                it.copy(
                    isWorking = false,
                    message = result.message ?: if (result.success) {
                        tr(R.string.s_daoruchenggong)
                    } else {
                        tr(R.string.s_daorushibai)
                    },
                )
            }
            load()
        }
    }

    fun setEnabled(item: ImportedDictUiItem, enabled: Boolean) {
        if (item.schemeIds.isEmpty()) {
            _uiState.update { it.copy(message = tr(R.string.s_ciku_xuanzefangan)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true) }
            val result = withContext(Dispatchers.IO) {
                ImportedDictManager.setEnabled(context, item.id, enabled)
            }
            _uiState.update {
                it.copy(
                    isWorking = false,
                    message = result.fold(
                        onSuccess = { tr(R.string.s_daoruciku_yigengxin, item.displayName) },
                        onFailure = { e ->
                            tr(R.string.s_daoruciku_caozuoshibai, e.message ?: tr(R.string.s_daorushibai))
                        },
                    ),
                )
            }
            load()
        }
    }

    fun rebind(item: ImportedDictUiItem, schemeIds: List<String>) {
        if (schemeIds.isEmpty()) {
            _uiState.update { it.copy(message = tr(R.string.s_daoruciku_qingxuanze)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true) }
            val result = withContext(Dispatchers.IO) {
                ImportedDictManager.rebind(context, item.id, schemeIds)
            }
            _uiState.update {
                it.copy(
                    isWorking = false,
                    message = result.fold(
                        onSuccess = { tr(R.string.s_daoruciku_yigengxin, item.displayName) },
                        onFailure = { e ->
                            tr(R.string.s_daoruciku_caozuoshibai, e.message ?: tr(R.string.s_daorushibai))
                        },
                    ),
                )
            }
            load()
        }
    }

    fun delete(item: ImportedDictUiItem) {
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true) }
            val result = withContext(Dispatchers.IO) {
                ImportedDictManager.delete(context, item.id)
            }
            _uiState.update {
                it.copy(
                    isWorking = false,
                    message = result.fold(
                        onSuccess = { tr(R.string.s_daoruciku_yishanchu, item.displayName) },
                        onFailure = { e ->
                            tr(R.string.s_daoruciku_caozuoshibai, e.message ?: tr(R.string.s_daorushibai))
                        },
                    ),
                )
            }
            load()
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun codeSystemLabel(value: String): String = when (value) {
        LexiconCodeSystem.PINYIN.name -> tr(R.string.subtype_label_pinyin)
        LexiconCodeSystem.WUBI.name -> tr(R.string.s_bianma_wubi)
        else -> tr(R.string.s_bianma_qita)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    }
}
