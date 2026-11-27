package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.plugin.core.security.PluginErrorLog
import com.kingzcheung.xime.viewmodel.LogViewerEvent
import com.kingzcheung.xime.viewmodel.LogViewerViewModel
import java.io.File

private const val TAG = "LogViewer"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: LogViewerViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is LogViewerEvent.ShareFile -> {
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = event.mimeType
                        putExtra(android.content.Intent.EXTRA_SUBJECT, event.subject)
                        putExtra(android.content.Intent.EXTRA_STREAM, event.uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = android.content.ClipData.newRawUri(null, event.uri)
                    }
                    val chooser = android.content.Intent.createChooser(shareIntent, tr(R.string.s_fenxiangrizhi))
                    context.startActivity(chooser)
                }
                is LogViewerEvent.SavedToDownloads -> {
                    snackbarHostState.showSnackbar(tr(R.string.s_yibaocundao, event.path))
                }
                is LogViewerEvent.ShowError -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }
    
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        tr(R.string.s_rizhichakanqi),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = tr(R.string.s_fanhui)
                        )
                    }
                },
                actions = {
                    val targetFile = uiState.selectedLogFile
                        ?: uiState.logFiles.firstOrNull()
                    if (targetFile != null) {
                        TextButton(onClick = { viewModel.saveToDownloads(targetFile) }) {
                            Text(tr(R.string.s_baocun))
                        }
                    }
                    IconButton(onClick = { viewModel.loadLogFiles() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = tr(R.string.s_shuaxin)
                        )
                    }
                    if (uiState.logFiles.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearAllLogs() }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = tr(R.string.s_qingkongsuoyourizhi)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.errorMsg != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = uiState.errorMsg!!,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = { viewModel.loadLogFiles() }) {
                        Text(tr(R.string.s_zhongxinjiazai))
                    }
                }
            }
        } else if (uiState.selectedLogFile != null && uiState.selectedLogFile!!.exists()) {
            LogContentSection(
                selectedLogFile = uiState.selectedLogFile!!,
                logContent = uiState.logContent,
                onBackToList = { viewModel.goBackToList() },
                onDelete = { viewModel.deleteLogFile(uiState.selectedLogFile!!) },
                onShare = { viewModel.shareLogFile(uiState.selectedLogFile!!) },
                onSave = { viewModel.saveToDownloads(uiState.selectedLogFile!!) }
            )
        } else {
            // 插件错误来自 PluginErrorLog 持久化快照（getAllErrors 已含启动恢复的历史），
            // 随刷新联动重取（logFiles 为 key）
            val pluginErrors = remember(uiState.logFiles) {
                com.kingzcheung.xime.plugin.core.security.PluginErrorLog.getAllErrors()
            }
            LogFilesList(
                logFiles = uiState.logFiles,
                pluginErrors = pluginErrors,
                onSelect = { viewModel.selectLogFile(it) },
                onShare = { viewModel.shareLogFile(it) },
                onSave = { viewModel.saveToDownloads(it) },
                onDelete = { viewModel.deleteLogFile(it) },
                context = context,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun LogContentSection(
    selectedLogFile: File,
    logContent: String,
    onBackToList: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selectedLogFile.name,
                fontWeight = FontWeight.Medium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onBackToList) {
                    Text(tr(R.string.s_fanhuiliebiao))
                }
                TextButton(onClick = onSave) {
                    Text(tr(R.string.s_baocun))
                }
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = tr(R.string.s_fenxiang),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = tr(R.string.s_shanchucirizhi),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        
        HorizontalDivider()
        
        if (logContent.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(tr(R.string.s_rizhiwenjianweikong))
            }
        } else {
            val lines = logContent.lines()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        fontSize = 12.sp,
                        color = when {
                            line.contains("ERROR") -> MaterialTheme.colorScheme.error
                            line.contains("WARNING") -> MaterialTheme.colorScheme.secondary
                            line.contains("FATAL") -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LogFilesList(
    logFiles: List<File>,
    pluginErrors: Map<String, List<com.kingzcheung.xime.plugin.core.security.PluginErrorLog.PluginError>>,
    onSelect: (File) -> Unit,
    onShare: (File) -> Unit,
    onSave: (File) -> Unit,
    onDelete: (File) -> Unit,
    context: android.content.Context,
    modifier: Modifier = Modifier
) {
    val pluginNames = remember(pluginErrors) {
        com.kingzcheung.xime.plugin.core.runtime.PluginManager.getAllInstallPlugins()
            .associate { it.id to it.name }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (logFiles.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = tr(R.string.s_zanwurizhiwenjian),
                            style = MaterialTheme.typography.bodyLarge,
                            color = settingsSecondaryColor()
                        )
                        Text(
                            text = tr(R.string.s_yingyongyunxingzhengchang_meiy_cb24c),
                            style = MaterialTheme.typography.bodySmall,
                            color = settingsSecondaryColor().copy(alpha = 0.7f)
                        )
                        Text(
                            text = tr(R.string.s_rizhimulu_logs, context.filesDir.absolutePath),
                            style = MaterialTheme.typography.bodySmall,
                            color = settingsSecondaryColor().copy(alpha = 0.6f)
                        )
                    }
                }
            }
        } else {
            item {
                Text(
                    text = tr(R.string.s_rizhiwenjian, logFiles.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            items(logFiles) { file ->
                LogFileItem(
                    file = file,
                    onClick = { onSelect(file) },
                    onShare = { onShare(file) },
                    onSave = { onSave(file) },
                    onDelete = { onDelete(file) }
                )
            }
            
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = tr(R.string.s_rizhiweizhi_logs, context.filesDir.absolutePath),
                    style = MaterialTheme.typography.bodySmall,
                    color = settingsSecondaryColor().copy(alpha = 0.6f)
                )
            }
        }
        
        if (pluginErrors.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = tr(R.string.s_chajiancuowu, pluginErrors.values.sumOf { it.size }),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
            pluginErrors.toList().forEach { (pluginId, errors) ->
                item(key = "plugin-error-$pluginId") {
                    PluginErrorsCard(
                        pluginId = pluginId,
                        pluginName = pluginNames[pluginId] ?: pluginId,
                        errors = errors
                    )
                }
            }
        }
    }
}

@Composable
private fun LogFileItem(
    file: File,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = file.name,
                    fontWeight = FontWeight.Medium
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatFileSize(file.length()),
                        style = MaterialTheme.typography.bodySmall,
                        color = settingsSecondaryColor()
                    )
                    Text(
                        text = "•",
                        color = settingsSecondaryColor().copy(alpha = 0.5f)
                    )
                    Text(
                        text = formatTimestamp(file.lastModified()),
                        style = MaterialTheme.typography.bodySmall,
                        color = settingsSecondaryColor()
                    )
                }
                
                val logType = when {
                    file.name.contains("WARNING") -> "WARNING"
                    file.name.contains("ERROR") -> "ERROR"
                    file.name.contains("FATAL") -> "FATAL"
                    file.name.startsWith("kime_") -> tr(R.string.s_apprizhi)
                    file.name.contains("rime.kime") -> tr(R.string.s_rimerizhi)
                    else -> "LOG"
                }
                
                Text(
                    text = logType,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (logType) {
                        "ERROR" -> MaterialTheme.colorScheme.error
                        "WARNING" -> MaterialTheme.colorScheme.secondary
                        "FATAL" -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        tr(R.string.s_apprizhi) -> MaterialTheme.colorScheme.primary
                        tr(R.string.s_rimerizhi) -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onSave) {
                    Text(tr(R.string.s_baocun), style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = tr(R.string.s_fenxiang),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = tr(R.string.s_shanchu),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginErrorsCard(
    pluginId: String,
    pluginName: String,
    errors: List<com.kingzcheung.xime.plugin.core.security.PluginErrorLog.PluginError>
) {
    val context = LocalContext.current
    var showDetail by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { showDetail = true },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = pluginName,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = tr(R.string.s_tiao_2, errors.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            errors.takeLast(2).reversed().forEach { error ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${formatTimestamp(error.timestamp)} · ${categoryLabel(error.category)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = settingsSecondaryColor()
                    )
                    Text(
                        text = com.kingzcheung.xime.plugin.core.security.PluginErrorLog.userMessage(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Text(
                text = tr(R.string.s_dianjichakanxiangqing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (showDetail) {
        PluginErrorsDetailDialog(
            pluginId = pluginId,
            pluginName = pluginName,
            errors = errors,
            onCopy = {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                val text = buildPluginDiagnosticText(
                    hostVersionName = com.kingzcheung.xime.BuildConfig.VERSION_NAME,
                    pluginName = pluginName,
                    pluginId = pluginId,
                    pluginVersion = com.kingzcheung.xime.plugin.core.runtime.PluginManager
                        .getAllInstallPlugins().firstOrNull { it.id == pluginId }?.versionName ?: "?",
                    enabled = true,
                    errors = errors
                )
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText(tr(R.string.s_xime_chajianzhenduanxinxi), text)
                )
                Toast.makeText(context, tr(R.string.s_zhenduanxinxiyifuzhi), Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showDetail = false }
        )
    }
}

@Composable
private fun PluginErrorsDetailDialog(
    pluginId: String,
    pluginName: String,
    errors: List<com.kingzcheung.xime.plugin.core.security.PluginErrorLog.PluginError>,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.s_cuowuxiangqing, pluginName)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                errors.reversed().forEachIndexed { index, error ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "${formatTimestamp(error.timestamp)} · ${categoryLabel(error.category)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = settingsSecondaryColor()
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = com.kingzcheung.xime.plugin.core.security.PluginErrorLog.userMessage(error),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = com.kingzcheung.xime.plugin.core.security.PluginErrorLog.userHint(error),
                                style = MaterialTheme.typography.bodySmall,
                                color = settingsSecondaryColor()
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = error.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            error.stackTrace?.let { stack ->
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stack,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = settingsSecondaryColor().copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                    if (index < errors.lastIndex) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
                if (errors.isEmpty()) {
                    Text(
                        tr(R.string.s_zanwucuowujilu),
                        style = MaterialTheme.typography.bodyMedium,
                        color = settingsSecondaryColor()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCopy() }) {
                Text(tr(R.string.s_fuzhizhenduanxinxi))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr(R.string.s_guanbi))
            }
        }
    )
}

private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> "${size / (1024 * 1024)} MB"
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}