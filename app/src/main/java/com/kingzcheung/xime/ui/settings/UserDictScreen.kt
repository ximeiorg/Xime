package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.settings.DictEntry
import com.kingzcheung.xime.settings.UserDictIoManager
import com.kingzcheung.xime.viewmodel.UserDictUiState
import com.kingzcheung.xime.viewmodel.UserDictViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「用户词库」页：输入时自动积累、存在 `<词典名>.userdb` 里的词条。
 *
 * 与旧版「词库管理」里的用户词库页签的区别：
 *  - 词典用顶部选择卡片切换，不再"列表 → 详情"两级跳（少一层选择器，也少一次点击）；
 *  - 页面自己不做内层导航，系统返回键逐层正确；
 *  - 导出/导入从条目页的溢出菜单提到本页顶栏，进页面就能看到。
 *
 * 词库读写都要在 native 侧销毁/重建输入会话，故用 [UserDictUiState.isReading] /
 * [UserDictUiState.isTransferring] 反馈等待。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDictContent(onBack: () -> Unit) {
    val viewModel: UserDictViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 导出到 / 从系统文件选择器导入：均走 librime 的文本码表（与 PC 端同格式）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) viewModel.exportTo(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importFrom(uri)
    }
    var showMenu by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    var entryToDelete by remember { mutableStateOf<DictEntry?>(null) }
    val selectedDict = uiState.selectedDict
    val selectedMeta = uiState.dicts.find { it.name == selectedDict }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_yonghuciku)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr(R.string.s_fanhui))
                    }
                },
                actions = {
                    if (uiState.isTransferring) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp).size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else if (selectedDict != null) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = tr(R.string.s_gengduocaozuo))
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(tr(R.string.s_daochuwenbenmabiao)) },
                                    onClick = {
                                        showMenu = false
                                        exportLauncher.launch(UserDictIoManager.exportFileName(selectedDict))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(tr(R.string.s_daoruwenbenmabiao)) },
                                    onClick = {
                                        showMenu = false
                                        importLauncher.launch(arrayOf("*/*"))
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            if (selectedDict != null) {
                FloatingActionButton(
                    onClick = { showAddSheet = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        Icons.Default.Add, contentDescription = tr(R.string.s_tianjiacitiao),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                uiState.isLoading -> {
                    Centered { CircularProgressIndicator() }
                }
                uiState.dicts.isEmpty() -> {
                    Centered {
                        UsageHint(
                            title = tr(R.string.s_haimeiyouyonghuciku),
                            message = tr(R.string.s_yongmougefangan_daguozi)
                        )
                    }
                }
                else -> {
                    ContextSelectorCard(
                        title = selectedDict ?: "",
                        subtitle = selectedMeta?.let { tr(R.string.s_zuijingengxin, formatUserDictTime(it.lastModified)) }
                            ?: tr(R.string.s_xuanzeyaochakandeciku),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        // 只有一本时退化成信息卡，不出现无意义的下拉
                        enabled = uiState.dicts.size > 1
                    ) { dismiss ->
                        for (dict in uiState.dicts) {
                            DropdownMenuItem(
                                text = { Text(dict.name) },
                                onClick = {
                                    dismiss()
                                    viewModel.selectDict(dict.name)
                                }
                            )
                        }
                    }

                    RimeSearchField(
                        query = uiState.searchQuery,
                        onQueryChange = viewModel::setSearchQuery,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    uiState.message?.let { message ->
                        TransferMessageBanner(
                            message = message,
                            isError = uiState.messageIsError,
                            onDismiss = viewModel::dismissMessage
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text(
                        text = if (uiState.searchQuery.isEmpty()) tr(R.string.s_gong_tiao, uiState.entries.size)
                        else tr(R.string.s_pipei_tiao_2, uiState.filteredEntries.size, uiState.entries.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = settingsSecondaryColor(),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )

                    when {
                        uiState.isReading -> {
                            Centered {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        tr(R.string.s_zhengzaiduquciku),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = settingsSecondaryColor()
                                    )
                                }
                            }
                        }
                        uiState.entries.isEmpty() -> {
                            Centered {
                                UsageHint(
                                    title = tr(R.string.s_zhebencikuhaimeiyoucitiao),
                                    message = tr(R.string.s_yongzhegefanganjixushuru_citia_3a000)
                                )
                            }
                        }
                        uiState.filteredEntries.isEmpty() -> {
                            Centered {
                                Text(tr(R.string.s_weizhaodaopipeitiaomu), color = settingsSecondaryColor())
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                // 底部留出 FAB 的位置，否则最后一条会被压住
                                contentPadding = PaddingValues(
                                    start = 16.dp, end = 16.dp, top = 6.dp, bottom = 88.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(
                                    items = uiState.filteredEntries,
                                    key = { "${it.word}\t${it.code}" }
                                ) { entry ->
                                    UserDictEntryRow(entry, onDelete = { entryToDelete = entry })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 新增：把一行码表交给引擎的导入通道写入（不改 native）
    if (showAddSheet) {
        UserDictAddSheet(
            onConfirm = { input ->
                showAddSheet = false
                viewModel.addEntry(input)
            },
            onDismiss = { showAddSheet = false }
        )
    }

    // 删除：确认后写一条负频率的码表（librime 的删除标记）
    entryToDelete?.let { entry ->
        DeleteEntryDialog(
            entry = entry,
            onConfirm = {
                entryToDelete = null
                viewModel.deleteEntry(entry)
            },
            onDismiss = { entryToDelete = null }
        )
    }
}

/**
 * 新增词条的底部弹层（词 + 编码 + 频率）。
 *
 * 形状与「快捷短语」的编辑弹层相同、语义不同（那是方案级 `custom_phrase`，
 * 这里是引擎 userdb 里的词条），所以暂时各写一份；等第三处也需要时再抽进
 * `SettingsCommonUi.kt`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserDictAddSheet(
    onConfirm: (UserDictIoManager.EntryInput) -> Unit,
    onDismiss: () -> Unit,
) {
    var word by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    // 纯函数校验：词/码不能空、不能含制表符换行；频率留空即 1
    val check = UserDictIoManager.checkEntryInput(word, code, weight)
    val error = (check as? UserDictIoManager.EntryInputCheck.Error)?.message

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text(
                tr(R.string.s_tianjiacitiao), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 20.dp)
            )
            OutlinedTextField(
                value = word, onValueChange = { word = it },
                label = { Text(tr(R.string.s_ci)) }, shape = RoundedCornerShape(12.dp), singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = code, onValueChange = { code = it },
                label = { Text(tr(R.string.s_bianma)) }, shape = RoundedCornerShape(12.dp), singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = weight, onValueChange = { weight = it },
                label = { Text(tr(R.string.s_pinlv_kexuan_yuedayueyouxian)) }, shape = RoundedCornerShape(12.dp),
                singleLine = true,
                isError = error != null,
                supportingText = if (error != null) {
                    { Text(error) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(tr(R.string.s_quxiao)) }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    modifier = Modifier.clickable(enabled = check is UserDictIoManager.EntryInputCheck.Ok) {
                        (check as? UserDictIoManager.EntryInputCheck.Ok)?.let { onConfirm(it.entry) }
                    },
                    shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        tr(R.string.s_queding), modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * 删除确认。引擎的删除是 tombstone：记录仍在库里，用户之后再输入并选中它就会被
 * 重新记录，所以文案不能写成"永久删除"；方案自带词表里的同一个词也不受影响。
 */
@Composable
private fun DeleteEntryDialog(entry: DictEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.s_shanchucitiao)) },
        text = {
            Text(
                tr(R.string.s_huicongyonghucikuzhongqudiao_y_c6896, entry.word, entry.code) +
                    tr(R.string.s_fanganzidaicibiaoliruoyeyouta_0c530) +
                    tr(R.string.s_nizhihouruguozaishurubingxuanz_f4558)
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(tr(R.string.s_shanchu), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr(R.string.s_quxiao)) }
        }
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun UserDictEntryRow(entry: DictEntry, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.word, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(entry.code, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (entry.weight != null) {
                    Text(
                        tr(R.string.s_pinlv, entry.weight),
                        style = MaterialTheme.typography.labelSmall,
                        color = settingsSecondaryColor()
                    )
                }
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
private fun TransferMessageBanner(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    val container = if (isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.secondaryContainer
    val onContainer = if (isError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        color = container
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = onContainer,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Clear, contentDescription = tr(R.string.s_guanbitishi),
                    modifier = Modifier.size(18.dp),
                    tint = onContainer
                )
            }
        }
    }
}

private val userDictTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatUserDictTime(timestamp: Long): String =
    if (timestamp <= 0L) tr(R.string.s_weizhi) else userDictTimeFormat.format(Date(timestamp))