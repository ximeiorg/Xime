package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.settings.DictEntry
import com.kingzcheung.xime.settings.PersonalDictManager
import com.kingzcheung.xime.viewmodel.CustomPhraseUiState
import com.kingzcheung.xime.viewmodel.CustomPhraseViewModel
import com.kingzcheung.xime.viewmodel.DictionarySettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「快捷短语」页：手写的 `custom_phrase`（stabledb 文本表），增删改都在这里。
 *
 * 从「词库管理」页签拆成独立页面后：输入方案的选择器只出现在本页（它只对短语有意义 ——
 * 短语文件与翻译器补丁都按方案决定），顶栏不再重复显示方案名。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomPhraseSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: CustomPhraseViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val schemaViewModel: DictionarySettingsViewModel = viewModel()
    val schemaState by schemaViewModel.uiState.collectAsStateWithLifecycle()

    // 切方案：补齐该方案的 custom_phrase 翻译器补丁，并让短语列表跟随方案
    LaunchedEffect(schemaState.selectedSchema) {
        val schemaId = schemaState.selectedSchema
        if (schemaId.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) { PersonalDictManager.ensureSchemaPack(context, schemaId) }
        viewModel.setSchema(schemaId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_kuaijieduanyu)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr(R.string.s_fanhui))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.showAddDialog() },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = tr(R.string.s_tianjia), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val schema = schemaState.availableSchemas.find { it.schemaId == schemaState.selectedSchema }
            ContextSelectorCard(
                title = schema?.name ?: schemaState.selectedSchema.ifEmpty { tr(R.string.s_weixuanzeshurufangan) },
                subtitle = tr(R.string.s_kuaijieduanyusuigaishurufangan_759f0),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                enabled = schemaState.availableSchemas.size > 1
            ) { dismiss ->
                for (s in schemaState.availableSchemas) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(s.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    s.schemaId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = settingsSecondaryColor()
                                )
                            }
                        },
                        onClick = {
                            dismiss()
                            schemaViewModel.selectSchema(s.schemaId)
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            CustomPhraseBody(viewModel = viewModel, uiState = uiState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomPhraseBody(
    viewModel: CustomPhraseViewModel,
    uiState: CustomPhraseUiState,
) {
    RimeSearchField(
        query = uiState.searchQuery,
        onQueryChange = viewModel::setSearchQuery,
        modifier = Modifier.padding(horizontal = 16.dp),
        placeholder = tr(R.string.s_sousuoduanyuhuobianma)
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (uiState.entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            UsageHint(
                title = tr(R.string.s_zanwukuaijieduanyu),
                message = tr(R.string.s_kuaijieduanyuyongyubuchongfang_d0b76),
                action = tr(R.string.s_dianyouxiajiao_tianjia_ranhouz_fd08d)
            )
        }
    } else {
        Text(
            text = tr(R.string.s_gong_tiao, uiState.entries.size) +
                if (uiState.searchQuery.isNotEmpty()) tr(R.string.s_pipei_tiao, uiState.filteredEntries.size) else "",
            style = MaterialTheme.typography.bodySmall,
            color = settingsSecondaryColor(),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (uiState.filteredEntries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(tr(R.string.s_weizhaodaopipeitiaomu), color = settingsSecondaryColor())
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                // 给右下角 FAB 留出空间，否则最后一条会被压住
                contentPadding = PaddingValues(bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(
                    items = uiState.filteredEntries,
                    key = { _, e -> "${e.word}\t${e.code}" }
                ) { _, entry ->
                    // 列表展示的是过滤后的结果，而增删改按过滤前的位置索引 ——
                    // 必须换算回 entries 里的真实下标，否则搜索状态下会改错/删错条目
                    val realIndex = uiState.entries.indexOf(entry)
                    PhraseItem(
                        entry = entry,
                        onEdit = {
                            viewModel.setEditing(realIndex, entry)
                            viewModel.showEditDialog()
                        },
                        onDelete = { viewModel.deleteEntry(realIndex) }
                    )
                }
            }
        }
    }

    if (uiState.showAddDialog) {
        PhraseEditDialog(
            title = tr(R.string.s_tianjiakuaijieduanyu),
            word = uiState.editWord,
            code = uiState.editCode,
            weight = uiState.editWeight,
            onWordChange = viewModel::setEditWord,
            onCodeChange = viewModel::setEditCode,
            onWeightChange = viewModel::setEditWeight,
            onConfirm = {
                viewModel.addEntry(uiState.editWord, uiState.editCode, uiState.editWeight.toIntOrNull())
                viewModel.hideAddDialog()
            },
            onDismiss = viewModel::hideAddDialog,
        )
    }
    if (uiState.showEditDialog) {
        PhraseEditDialog(
            title = tr(R.string.s_bianjikuaijieduanyu),
            word = uiState.editWord,
            code = uiState.editCode,
            weight = uiState.editWeight,
            onWordChange = viewModel::setEditWord,
            onCodeChange = viewModel::setEditCode,
            onWeightChange = viewModel::setEditWeight,
            onConfirm = {
                viewModel.updateEntry(uiState.editIndex, uiState.editWord, uiState.editCode, uiState.editWeight.toIntOrNull())
                viewModel.hideEditDialog()
            },
            onDismiss = viewModel::hideEditDialog,
        )
    }
}

@Composable
private fun PhraseItem(entry: DictEntry, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.word, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(2.dp))
                Text(entry.code, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                if (entry.weight != null) {
                    Text(
                        tr(R.string.s_quanzhong, entry.weight),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Edit, contentDescription = tr(R.string.s_bianji), modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete, contentDescription = tr(R.string.s_shanchu),
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PhraseEditDialog(
    title: String,
    word: String,
    code: String,
    weight: String,
    onWordChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onWeightChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text(
                title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 20.dp)
            )
            OutlinedTextField(
                value = word, onValueChange = onWordChange,
                label = { Text(tr(R.string.s_duanyu)) }, shape = RoundedCornerShape(12.dp), singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = code, onValueChange = onCodeChange,
                label = { Text(tr(R.string.s_bianma)) }, shape = RoundedCornerShape(12.dp), singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = weight, onValueChange = onWeightChange,
                label = { Text(tr(R.string.s_quanzhong_kexuan_yuedayueyouxian)) }, shape = RoundedCornerShape(12.dp), singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(tr(R.string.s_quxiao)) }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    modifier = Modifier.clickable(enabled = word.isNotBlank() && code.isNotBlank(), onClick = onConfirm),
                    shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        tr(R.string.s_queding), modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}