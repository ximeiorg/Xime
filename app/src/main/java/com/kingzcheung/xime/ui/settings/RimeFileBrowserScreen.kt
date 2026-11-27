package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.graphics.Typeface
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kingzcheung.xime.settings.ExportResult
import com.kingzcheung.xime.settings.RimeExportManager
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SyncManager
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.Magnifier
import org.eclipse.tm4e.core.registry.IThemeSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FileEntry(
    val file: File,
    val isDirectory: Boolean,
    val name: String,
    val size: Long,
    val lastModified: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RimeFileBrowserContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val rootDir = remember { SchemaManager.getRimeDir(context) }
    var currentDir by remember { mutableStateOf(rootDir) }
    var entries by remember { mutableStateOf(listOf<FileEntry>()) }
    var viewingFile by remember { mutableStateOf<File?>(null) }
    var showDeleteDialog by remember { mutableStateOf<File?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportInProgress by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<ExportResult?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun refresh() {
        entries = currentDir.listFiles()
            ?.map { FileEntry(it, it.isDirectory, it.name, it.length(), it.lastModified()) }
            ?.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name })
            ?: emptyList()
    }

    fun deleteFile(file: File) {
        if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
        showDeleteDialog = null
        refresh()
        Toast.makeText(context, tr(R.string.s_yishanchu, file.name), Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(currentDir) { refresh() }

    if (viewingFile != null) {
        YamlEditor(
            file = viewingFile!!,
            onDeleted = { viewingFile = null },
            onBack = { viewingFile = null }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(tr(R.string.s_wenjianguanliqi), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val rel = if (currentDir == rootDir) "" else
                            currentDir.absolutePath.removePrefix(rootDir.absolutePath)
                        if (rel.isNotEmpty()) {
                            Text(rel, style = MaterialTheme.typography.bodySmall,
                                color = settingsSecondaryColor(),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    if (currentDir == rootDir) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr(R.string.s_fanhui))
                        }
                    } else {
                        IconButton(onClick = { currentDir = currentDir.parentFile ?: rootDir }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr(R.string.s_shangjimulu))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { currentDir = rootDir }) {
                        Icon(Icons.Default.Home, contentDescription = tr(R.string.s_genmulu))
                    }
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = tr(R.string.s_shuaxin))
                    }
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(Icons.Default.IosShare, contentDescription = tr(R.string.s_daochu))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center) {
                Text(tr(R.string.s_kongmulu), color = settingsSecondaryColor())
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding)
            ) {
                items(entries, key = { currentDir.absolutePath + "/" + it.name }) { entry ->
                    FileEntryRow(
                        entry = entry,
                        onView = {
                            if (entry.isDirectory) {
                                currentDir = entry.file
                            } else if (entry.name.endsWith(".bin") || entry.name.endsWith(".gram")) {
                                Toast.makeText(context, tr(R.string.s_buzhichidakaierjinzhiwenjian), Toast.LENGTH_SHORT).show()
                            } else {
                                viewingFile = entry.file
                            }
                        },
                        onDelete = {
                            showDeleteDialog = entry.file
                        },
                        onShare = {
                            RimeExportManager.shareSingleFile(context, entry.file)
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 72.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }

    showDeleteDialog?.let { file ->
        val isDir = file.isDirectory
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(if (isDir) tr(R.string.s_shanchumulu) else tr(R.string.s_shanchuwenjian)) },
            text = { Text(tr(R.string.s_queding_shanchu, file.name, if (isDir) tr(R.string.s_muluneirong_shanchu) else "")) },
            confirmButton = {
                TextButton(onClick = { deleteFile(file) }) {
                    Text(tr(R.string.s_shanchu), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text(tr(R.string.s_quxiao))
                }
            }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(tr(R.string.s_daochu_rime_peizhi)) },
            text = {
                Text(
                    tr(R.string.s_daochuzhengjiwanzhengkuaizhao_44789) +
                        tr(R.string.s_yuyunbeifenshitongyigebao_buha_44f27),
                    style = MaterialTheme.typography.bodySmall,
                    color = settingsSecondaryColor()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        exportInProgress = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                // 与云备份同构：导出前尽力刷新自造词快照（引擎未就绪则用现有快照）
                                SyncManager.syncNow(context)
                                RimeExportManager.exportArchive(context)
                            }
                            exportInProgress = false
                            result.fold(
                                onSuccess = { exportResult ->
                                    val msg = if (exportResult.savedToDownloads)
                                        tr(R.string.s_yibaocundao_downloads, exportResult.fileName)
                                    else
                                        tr(R.string.s_daochuwancheng)
                                    snackbarHostState.showSnackbar(msg)
                                },
                                onFailure = { error ->
                                    snackbarHostState.showSnackbar(tr(R.string.s_daochushibai_2, error.message))
                                }
                            )
                        }
                    },
                    enabled = !exportInProgress
                ) {
                    Text(if (exportInProgress) tr(R.string.s_daochuzhong) else tr(R.string.s_kaishidaochu))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showExportDialog = false },
                    enabled = !exportInProgress
                ) {
                    Text(tr(R.string.s_quxiao))
                }
            }
        )
    }

    if (exportInProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(tr(R.string.s_daochuzhong_2)) },
            text = { Text(tr(R.string.s_zhengzaidabao_rime_peizhi)) },
            confirmButton = {}
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YamlEditor(file: File, onDeleted: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var isEditing by remember { mutableStateOf(false) }
    var isModified by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var textLength by remember { mutableIntStateOf(0) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val initialContent = remember(file) {
        try {
            val text = file.readText(Charsets.UTF_8)
            if (text.startsWith('\uFEFF')) text.substring(1) else text
        } catch (_: Exception) {
            ""
        }
    }

    fun save() {
        val content = editor?.text?.toString() ?: return
        try {
            file.writeText(content, Charsets.UTF_8)
            isModified = false
            Toast.makeText(context, tr(R.string.s_yibaocun), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, tr(R.string.s_baocunshibai_2, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(tr(R.string.s_shanchuwenjian)) },
            text = { Text(tr(R.string.s_quedingshanchu_cicaozuobukechexiao, file.name)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    file.delete()
                    Toast.makeText(context, tr(R.string.s_yishanchu, file.name), Toast.LENGTH_SHORT).show()
                    onDeleted()
                }) {
                    Text(tr(R.string.s_shanchu), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(tr(R.string.s_quxiao))
                }
            }
        )
    }

    val textmateReady = remember {
        try {
            val appCtx = context.applicationContext
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(appCtx.assets))
            val themeReg = ThemeRegistry.getInstance()
            themeReg.loadTheme(
                ThemeModel(
                    IThemeSource.fromInputStream(
                        FileProviderRegistry.getInstance().tryGetInputStream("textmate/themes/light.json"),
                        "light.json", null
                    ), "Light"
                )
            )
            themeReg.setTheme("Light")
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
            true
        } catch (_: Exception) {
            false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sizeStr = formatSize(file.length())
                        val dateStr = dateFormat.format(Date(file.lastModified()))
                        val flag = if (isModified) tr(R.string.s_weibaocun) else ""
                        Text("$sizeStr · $dateStr$flag",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isModified) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr(R.string.s_fanhui))
                    }
                },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = { save() }) {
                            Icon(Icons.Default.Check, contentDescription = tr(R.string.s_baocun),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = {
                        isEditing = !isEditing
                        editor?.editable = isEditing
                    }) {
                        Icon(
                            if (isEditing) Icons.Default.Visibility else Icons.Default.Edit,
                            contentDescription = if (isEditing) tr(R.string.s_yulan) else tr(R.string.s_bianji),
                            tint = if (isEditing) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = tr(R.string.s_shanchu),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            AndroidView(
                factory = { ctx ->
                    CodeEditor(ctx).apply {
                        editable = isEditing
                        typefaceText = Typeface.MONOSPACE
                        setText(initialContent)
                        setTextSize(12f)
                        tabWidth = 4
                        isLineNumberEnabled = true
                        isWordwrap = false
                        isHighlightCurrentLine = false
                        nonPrintablePaintingFlags = 0
                        getComponent(Magnifier::class.java).setEnabled(false)

                        if (textmateReady) {
                            colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                            setEditorLanguage(TextMateLanguage.create("source.yaml", false))
                        }

                        subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                            textLength = text.toString().length
                            isModified = text.toString() != initialContent
                        }

                        editor = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileEntryRow(entry: FileEntry, onView: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit = {}) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onView),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (entry.isDirectory) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                    contentDescription = null,
                    tint = if (entry.isDirectory) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
        },
        headlineContent = {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Row {
                if (!entry.isDirectory && entry.size > 0) {
                    Text(
                        text = formatSize(entry.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = settingsSecondaryColor()
                    )
                    Text(" · ", color = settingsSecondaryColor())
                }
                Text(
                    text = dateFormat.format(Date(entry.lastModified)),
                    style = MaterialTheme.typography.bodySmall,
                    color = settingsSecondaryColor()
                )
            }
        },
        trailingContent = {
            if (!entry.isDirectory) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
    )
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    }
}
