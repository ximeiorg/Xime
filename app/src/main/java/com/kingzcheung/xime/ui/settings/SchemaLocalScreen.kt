package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.twotone.CloudDownload
import androidx.compose.material.icons.twotone.Computer
import androidx.compose.material.icons.twotone.DriveFolderUpload
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.viewmodel.LocalPackageItem
import com.kingzcheung.xime.viewmodel.LocalPackagePhase
import com.kingzcheung.xime.viewmodel.SchemaLocalViewModel
import com.kingzcheung.xime.viewmodel.SchemaSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemaLocalContent(
    onBack: () -> Unit,
    title: String = tr(R.string.s_fanganbao),
) {
    val context = LocalContext.current
    val viewModel: SchemaLocalViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 导入相关状态复用输入方案页的 ViewModel（导入逻辑与其一致）
    val importViewModel: SchemaSettingsViewModel = viewModel()
    val importUiState by importViewModel.uiState.collectAsStateWithLifecycle()

    var importMenuExpanded by remember { mutableStateOf(false) }
    var showWirelessSheet by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var wasDownloading by remember { mutableStateOf(false) }
    var showImportWarning by remember { mutableStateOf(false) }
    var dontShowImportWarning by remember { mutableStateOf(false) }
    var pendingImportAction by remember { mutableStateOf<() -> Unit>({}) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        for (uri in uris) {
            importViewModel.importSchemaFile(uri)
        }
    }

    fun requireImportWarning(action: () -> Unit) {
        if (SettingsPreferences.isSchemaImportWarningDismissed(context)) {
            action()
        } else {
            pendingImportAction = action
            showImportWarning = true
        }
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    // 导入完成后刷新本地包列表
    LaunchedEffect(Unit) {
        importViewModel.importCompleted.collect { viewModel.loadLocalPackages() }
    }

    LaunchedEffect(importUiState.toastMessage) {
        importUiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            importViewModel.clearToast()
        }
    }

    // 下载结束后自动关闭网络导入弹窗
    LaunchedEffect(importUiState.isDownloading) {
        if (wasDownloading && !importUiState.isDownloading) {
            showUrlDialog = false
            urlInput = ""
        }
        wasDownloading = importUiState.isDownloading
    }

    if (showImportWarning) {
        AlertDialog(
            onDismissRequest = { showImportWarning = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(tr(R.string.s_daorufanganxuzhi), fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        tr(R.string.s_shiyongdaorufangangongnengshi_b308e),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        tr(R.string.s_ruguonibuzhidaozijizaizuoshenm_2fa73),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { dontShowImportWarning = !dontShowImportWarning }
                    ) {
                        Checkbox(checked = dontShowImportWarning, onCheckedChange = { dontShowImportWarning = it })
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(tr(R.string.s_buzaixianshi), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (dontShowImportWarning) SettingsPreferences.setSchemaImportWarningDismissed(context, true)
                        showImportWarning = false
                        pendingImportAction()
                    }
                ) { Text(tr(R.string.s_jixudaoru)) }
            },
            dismissButton = {
                TextButton(onClick = { showImportWarning = false }) {
                    Text(tr(R.string.s_quxiao))
                }
            }
        )
    }

    if (showWirelessSheet) {
        WirelessImportSheet(
            onDismiss = {
                showWirelessSheet = false
                viewModel.loadLocalPackages()
            },
            onRefresh = { viewModel.loadLocalPackages() }
        )
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!importUiState.isDownloading) { showUrlDialog = false; urlInput = "" }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(tr(R.string.s_wangluodaoru), fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        tr(R.string.s_shuruyasuobaoxiazaidizhi),
                        style = MaterialTheme.typography.bodyMedium,
                        color = settingsSecondaryColor()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        tr(R.string.s_zhichi_zip_he_tar_gz_geshi),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    var urlFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusEvent { urlFocused = it.isFocused }
                            .clip(RoundedCornerShape(28.dp))
                            .background(
                                if (urlFocused) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                            )
                    ) {
                        BasicTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            enabled = !importUiState.isDownloading,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    if (urlInput.isEmpty() && !urlFocused) {
                                        Text(
                                            "https://example.com/schema.tar.gz",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = settingsSecondaryColor().copy(alpha = 0.5f)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                    if (importUiState.isDownloading) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                            )
                        ) {
                            Text(
                                text = tr(R.string.s_shiyongdaorufangangongnengshi_3fb80),
                                style = MaterialTheme.typography.labelSmall,
                                color = settingsSecondaryColor(),
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            tr(R.string.s_zhengzaixiazaibingjieya),
                            style = MaterialTheme.typography.labelSmall,
                            color = settingsSecondaryColor()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        wasDownloading = true
                        importViewModel.importFromUrl(urlInput)
                    },
                    enabled = urlInput.isNotBlank() && !importUiState.isDownloading
                ) { Text(tr(R.string.s_queding)) }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false; urlInput = "" }) { Text(tr(R.string.s_quxiao)) }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr(R.string.s_fanhui))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadLocalPackages() }) {
                        Icon(Icons.Default.Refresh, contentDescription = tr(R.string.s_shuaxin))
                    }
                    Box {
                        IconButton(onClick = { importMenuExpanded = true }) {
                            Icon(Icons.Default.Add, contentDescription = tr(R.string.s_daorufangan))
                        }
                        DropdownMenu(
                            expanded = importMenuExpanded,
                            onDismissRequest = { importMenuExpanded = false },
                            offset = DpOffset(0.dp, 4.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text(tr(R.string.s_liulanqidaoru)) },
                                onClick = {
                                    importMenuExpanded = false
                                    requireImportWarning { showWirelessSheet = true }
                                },
                                leadingIcon = {
                                    Icon(Icons.TwoTone.Computer, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(tr(R.string.s_wangluodaoru)) },
                                onClick = {
                                    importMenuExpanded = false
                                    requireImportWarning { showUrlDialog = true }
                                },
                                leadingIcon = {
                                    Icon(Icons.TwoTone.CloudDownload, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(tr(R.string.s_congwenjianxuanze)) },
                                onClick = {
                                    importMenuExpanded = false
                                    requireImportWarning { importLauncher.launch(arrayOf("*/*")) }
                                },
                                leadingIcon = {
                                    Icon(Icons.TwoTone.DriveFolderUpload, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (uiState.packages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (uiState.isLoading) tr(R.string.s_jiazaizhong_2) else tr(R.string.s_zanwubendifangan),
                            style = MaterialTheme.typography.bodyMedium,
                            color = settingsSecondaryColor(),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { viewModel.restoreBuiltin() },
                            enabled = uiState.packages.any { it.installed },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(tr(R.string.s_huifuneizhifangan))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            val installedCount = uiState.packages.count { it.installed }
                            val downloadedCount = uiState.packages.count { it.downloaded }
                            Text(
                                tr(R.string.s_gong_ge_yianzhuang_keanzhuang, uiState.packages.size, installedCount, downloadedCount - installedCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    items(uiState.packages, key = { it.packageId }) { item ->
                        LocalPackageCard(
                            item = item,
                            phase = if (uiState.installingId == item.packageId) uiState.phase else null,
                            onInstall = { viewModel.installPackage(item) },
                            onDelete = { viewModel.deleteDownloaded(item) },
                            onUninstall = { viewModel.uninstall(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalPackageCard(
    item: LocalPackageItem,
    phase: LocalPackagePhase?,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
    onUninstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                val statusLabel = when {
                    item.installed -> tr(R.string.s_yianzhuang)
                    item.downloaded -> tr(R.string.s_yixiazai)
                    else -> null
                }
                if (statusLabel != null) {
                    TagText(
                        statusLabel,
                        if (item.installed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "ID: ${item.packageId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (item.version.isNotEmpty() || item.fileCount > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        if (item.version.isNotEmpty()) append(tr(R.string.s_banben_3, item.version))
                        if (item.fileCount > 0) {
                            if (isNotEmpty()) append(" · ")
                            append(tr(R.string.s_gewenjian, item.fileCount))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    phase != null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when (phase) {
                                    LocalPackagePhase.INSTALLING -> tr(R.string.s_anzhuangzhong)
                                    LocalPackagePhase.DEPLOYING -> tr(R.string.s_bushuzhong)
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    item.installed -> {
                        OutlinedButton(onClick = onUninstall) { Text(tr(R.string.s_xiezai)) }
                    }
                    item.downloaded -> OutlinedButton(onClick = onInstall) { Text(tr(R.string.s_anzhuang)) }
                }
                Spacer(Modifier.weight(1f))
                if (item.packageId != "builtin" && item.downloaded) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = tr(R.string.s_shanchuxiazaiwenjian),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TagText(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
