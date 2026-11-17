package com.kingzcheung.xime.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.R
import com.kingzcheung.xime.i18n.tr
import com.kingzcheung.xime.dictionary.ImportedDictManager
import com.kingzcheung.xime.viewmodel.ImportedDictUiItem
import com.kingzcheung.xime.viewmodel.ImportedDictViewModel

/**
 * 「词库管理 → 导入词库」。
 *
 * 只管理 app 自己生成的派生词库；方案自带的静态词表仍去「输入方案 → 方案词表」看。
 * 导入复用 [com.kingzcheung.xime.settings.ImportManager]，本页只负责列表/启停/删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportedDictContent(onBack: () -> Unit) {
    val viewModel: ImportedDictViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<ImportedDictUiItem?>(null) }
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingRebind by remember { mutableStateOf<ImportedDictUiItem?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var pendingWeb by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var wirelessSchemes by remember { mutableStateOf<List<String>?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingImport = uri
    }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_daoruciku)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = tr(R.string.s_fanhui),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            enabled = !uiState.isWorking,
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = tr(R.string.s_gengduo),
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(tr(R.string.s_daoruciku_congwenjian)) },
                                onClick = {
                                    showMenu = false
                                    importLauncher.launch(arrayOf("*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr(R.string.s_liulanqidaoru)) },
                                onClick = {
                                    showMenu = false
                                    pendingWeb = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr(R.string.s_daoruciku_congurl)) },
                                onClick = {
                                    showMenu = false
                                    urlInput = ""
                                    showUrlDialog = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.isWorking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            when {
                uiState.isLoading && uiState.items.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.items.isEmpty() -> {
                    EmptyImportedDict(
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        onWebImport = { pendingWeb = true },
                        onUrlImport = {
                            urlInput = ""
                            showUrlDialog = true
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                    ) {
                        item {
                            Text(
                                text = tr(R.string.s_daoruciku_shuoming),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        items(uiState.items, key = { it.id }) { item ->
                            ImportedDictCard(
                                item = item,
                                busy = uiState.isWorking,
                                onEnabledChange = { viewModel.setEnabled(item, it) },
                                onRebind = { pendingRebind = item },
                                onDelete = { pendingDelete = item },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingImport?.let { uri ->
        SchemePickerDialog(
            options = uiState.schemeOptions,
            initialSelected = emptySet(),
            onDismiss = { pendingImport = null },
            onConfirm = { schemeIds ->
                pendingImport = null
                viewModel.import(uri, schemeIds)
            },
        )
    }

    pendingRebind?.let { item ->
        SchemePickerDialog(
            options = uiState.schemeOptions,
            initialSelected = item.schemeIds.toSet(),
            onDismiss = { pendingRebind = null },
            onConfirm = { schemeIds ->
                pendingRebind = null
                viewModel.rebind(item, schemeIds)
            },
        )
    }

    if (pendingWeb) {
        SchemePickerDialog(
            options = uiState.schemeOptions,
            initialSelected = emptySet(),
            onDismiss = { pendingWeb = false },
            onConfirm = { schemeIds ->
                pendingWeb = false
                wirelessSchemes = schemeIds
            },
        )
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text(tr(R.string.s_daoruciku_congurl)) },
            text = {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("URL") },
                    placeholder = { Text("https://example.com/dict.scel") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUrlDialog = false
                        pendingUrl = urlInput.trim()
                    },
                    enabled = urlInput.isNotBlank(),
                ) { Text(tr(R.string.s_queding)) }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) { Text(tr(R.string.s_quxiao)) }
            },
        )
    }

    pendingUrl?.let { url ->
        SchemePickerDialog(
            options = uiState.schemeOptions,
            initialSelected = emptySet(),
            onDismiss = { pendingUrl = null },
            onConfirm = { schemeIds ->
                pendingUrl = null
                viewModel.importFromUrl(url, schemeIds)
            },
        )
    }

    wirelessSchemes?.let { schemeIds ->
        WirelessImportSheet(
            onDismiss = {
                wirelessSchemes = null
                viewModel.load()
            },
            onRefresh = { viewModel.load() },
            lexiconSchemeIds = schemeIds,
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(tr(R.string.s_daoruciku_shanchu)) },
            text = { Text(tr(R.string.s_daoruciku_shanchu_queren, item.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.delete(item)
                    }
                ) {
                    Text(tr(R.string.s_shanchu))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(tr(R.string.s_quxiao))
                }
            },
        )
    }
}

@Composable
private fun EmptyImportedDict(
    onImport: () -> Unit,
    onWebImport: () -> Unit,
    onUrlImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = tr(R.string.s_daoruciku_kong),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = tr(R.string.s_daoruciku_shuoming),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onImport) {
            Text(tr(R.string.s_daoruciku_tianjia))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onWebImport) {
                Text(tr(R.string.s_liulanqidaoru))
            }
            TextButton(onClick = onUrlImport) {
                Text(tr(R.string.s_daoruciku_congurl))
            }
        }
    }
}

@Composable
private fun ImportedDictCard(
    item: ImportedDictUiItem,
    busy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onRebind: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${item.formatName} · ${item.codeSystem} · " +
                            tr(R.string.s_daoruciku_tiaoshu, item.entryCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = item.enabled,
                    onCheckedChange = onEnabledChange,
                    // 未挂载词库先让用户选方案，避免出现“已启用但不生效”。
                    enabled = !busy && item.schemeIds.isNotEmpty(),
                )
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = tr(R.string.s_daoruciku_shanchu),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tr(
                        R.string.s_daoruciku_guazai,
                        item.schemeNames.joinToString("、")
                            .ifEmpty { tr(R.string.s_ciku_weiguazai) },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRebind, enabled = !busy) {
                    Text(tr(R.string.s_daoruciku_genggaifangan))
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.schemeIds.isEmpty()) {
                    StatusBadge(
                        text = tr(R.string.s_ciku_weiguazai),
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else if (!item.enabled) {
                    StatusBadge(
                        text = tr(R.string.s_daoruciku_tingyong),
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (item.compiled) {
                    StatusBadge(
                        text = tr(R.string.s_daoruciku_yibianyi),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    StatusBadge(
                        text = tr(R.string.s_daoruciku_weibianyi),
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .background(container, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}


@Composable
private fun SchemePickerDialog(
    options: List<ImportedDictManager.SchemeOption>,
    initialSelected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val optionIds = options.map { it.id }.toSet()
    var selected by remember(initialSelected, optionIds) {
        mutableStateOf(initialSelected.intersect(optionIds))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.s_daoruciku_xuanzefangan)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (options.isEmpty()) {
                    Text(
                        text = tr(R.string.s_daoruciku_wukexuan),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (option.id in selected) {
                                        selected - option.id
                                    } else {
                                        selected + option.id
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = option.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) {
                                        selected + option.id
                                    } else {
                                        selected - option.id
                                    }
                                },
                            )
                            Text(
                                text = option.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty(),
            ) {
                Text(tr(R.string.s_queding))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr(R.string.s_quxiao))
            }
        },
    )
}
