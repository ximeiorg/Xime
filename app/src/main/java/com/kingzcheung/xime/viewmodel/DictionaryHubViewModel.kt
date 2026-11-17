package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.dictionary.ImportedDictManager
import com.kingzcheung.xime.settings.PersonalDictManager
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.UserDictIoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「词库管理」入口页的轻量状态：只统计两类词库内容的数量，供入口卡片显示。
 *
 * 刻意不复用 [DictionarySettingsViewModel]：它会把整个方案词表（可能上万条）读进内存，
 * 而入口页只需要本机词库本数与当前方案的短语条数，都是廉价的文件操作。
 */
data class DictionaryHubUiState(
    /** 本机用户词库（`<词典名>.userdb`）本数；null 表示还没数出来。 */
    val userDictCount: Int? = null,
    /** 当前方案使用的快捷短语条数；null 表示还没数出来。 */
    val customPhraseCount: Int? = null,
    /** 已导入的外部词库本数；null 表示还没数出来。 */
    val importedDictCount: Int? = null
)

class DictionaryHubViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(DictionaryHubUiState())
    val uiState: StateFlow<DictionaryHubUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    /** 重新统计（从子页面返回时调用，导入过码表或新增过短语后数量会变）。 */
    fun load() {
        viewModelScope.launch {
            val counts = withContext(Dispatchers.IO) {
                val currentSchema = SchemaManager.getEnabledSchemas(context).firstOrNull()
                Triple(
                    UserDictIoManager.list(context).size,
                    PersonalDictManager.loadCustomPhrases(context, currentSchema).size,
                    ImportedDictManager.list(context).size
                )
            }
            _uiState.update {
                it.copy(
                    userDictCount = counts.first,
                    customPhraseCount = counts.second,
                    importedDictCount = counts.third,
                )
            }
        }
    }
}