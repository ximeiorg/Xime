package com.kingzcheung.xime.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.model.ModelCategory
import com.kingzcheung.xime.model.ModelDownloadState
import com.kingzcheung.xime.model.ModelInfo
import com.kingzcheung.xime.model.ModelVersion
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.MarketPlugin
import com.kingzcheung.xime.settings.MarketLayoutItem
import com.kingzcheung.xime.settings.MarketScheme
import com.kingzcheung.xime.settings.MarketSchemeItem
import com.kingzcheung.xime.settings.MarketPluginItem
import com.kingzcheung.xime.settings.PluginVersion
import com.kingzcheung.xime.settings.SchemeVersion
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.viewmodel.ModelManagementUiState
import com.kingzcheung.xime.viewmodel.LayoutMarketUiState
import com.kingzcheung.xime.viewmodel.LayoutMarketViewModel
import com.kingzcheung.xime.viewmodel.ModelManagementViewModel
import com.kingzcheung.xime.viewmodel.ModelItemState
import com.kingzcheung.xime.viewmodel.PluginMarketUiState
import com.kingzcheung.xime.viewmodel.PluginMarketViewModel
import com.kingzcheung.xime.viewmodel.SchemaMarketUiState
import com.kingzcheung.xime.viewmodel.SchemaMarketViewModel

/**
 * 扩展商店：商店式统一入口。
 * 一级导航 Tab（方案 / 模型），二级分类 chips 过滤，列表使用商店卡片，下拉刷新。
 * 插件 Tab 待插件市场索引完成后接入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketHubContent(
    onBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit = {},
    onNavigateToModelDetail: (String) -> Unit = {},
    onNavigateToPluginDetail: (String) -> Unit = {},
    onNavigateToLayoutDetail: (String) -> Unit = {},
    onNavigateToLocal: () -> Unit = {},
    onNavigateToModelLocal: () -> Unit = {},
    initialTab: Int = 0,
) {
    var tabIndex by remember { mutableIntStateOf(initialTab) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扩展商店") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (tabIndex == 0 || tabIndex == 1) {
                        TextButton(
                            onClick = if (tabIndex == 0) onNavigateToLocal else onNavigateToModelLocal,
                        ) {
                            Text("本地管理", style = MaterialTheme.typography.labelMedium)
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
            PrimaryTabRow(selectedTabIndex = tabIndex) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    text = { Text("方案") },
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    text = { Text("模型") },
                )
                Tab(
                    selected = tabIndex == 2,
                    onClick = { tabIndex = 2 },
                    text = { Text("插件") },
                )
                Tab(
                    selected = tabIndex == 3,
                    onClick = { tabIndex = 3 },
                    text = { Text("布局") },
                )
            }
            when (tabIndex) {
                0 -> SchemesMarketTab(
                    onNavigateToDetail = onNavigateToDetail,
                )
                1 -> ModelsMarketTab(
                    onNavigateToDetail = onNavigateToModelDetail,
                )
                2 -> PluginsMarketTab(
                    onNavigateToDetail = onNavigateToPluginDetail,
                )
                3 -> LayoutsMarketTab(
                    onNavigateToDetail = onNavigateToLayoutDetail,
                )
            }
        }
    }
}

/* ------------------------------- 方案 Tab ------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchemesMarketTab(
    onNavigateToDetail: (String) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SchemaMarketViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) isRefreshing = false
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            viewModel.loadSchemes()
        },
        state = pullRefreshState,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (uiState.availableTags.isNotEmpty()) {
                CategoryChips(
                    categories = uiState.availableTags,
                    selected = uiState.selectedTag,
                    onSelect = { viewModel.selectTag(if (it == uiState.selectedTag) null else it) },
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    uiState.isLoading && uiState.schemes.isEmpty() -> item {
                        MarketCenterBox(
                            modifier = Modifier.fillParentMaxSize(),
                            content = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(12.dp))
                                    Text("正在加载方案市场…", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        )
                    }

                    uiState.errorMessage != null && uiState.schemes.isEmpty() -> item {
                        MarketCenterBox(
                            modifier = Modifier.fillParentMaxSize(),
                            content = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        uiState.errorMessage ?: "加载失败",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Button(onClick = { viewModel.loadSchemes() }) { Text("重试") }
                                }
                            }
                        )
                    }

                    uiState.filteredSchemes.isEmpty() -> item {
                        MarketCenterBox(
                            modifier = Modifier.fillParentMaxSize(),
                            content = {
                                Text("没有匹配的方案", style = MaterialTheme.typography.bodyMedium)
                            }
                        )
                    }

                    else -> {
                        items(uiState.filteredSchemes, key = { it.scheme.id }) { item ->
                            val (iconContainer, iconContent) = schemeCategoryColors(item.scheme.tags.firstOrNull())
                            MarketStoreCard(
                                icon = schemeCategoryIcon(item.scheme.tags.firstOrNull()),
                                iconContainerColor = iconContainer,
                                iconContentColor = iconContent,
                                title = item.scheme.name.ifEmpty { item.scheme.id },
                                subtitle = item.scheme.description,
                                metaLine = buildString {
                                    if (item.scheme.author.isNotEmpty()) append("作者：${item.scheme.author}")
                                    val totalBytes = item.scheme.versions
                                        .firstOrNull { it.version == uiState.selectedVersions[item.scheme.id] }
                                        ?.downloadUrls?.sumOf { dl ->
                                            dl.size?.removeSuffix(" MB")?.trim()?.toDoubleOrNull()
                                                ?.let { (it * 1024.0 * 1024.0).toLong() } ?: 0L
                                        } ?: 0L
                                    if (totalBytes > 0) {
                                        if (item.scheme.author.isNotEmpty()) append("  ·  ")
                                        append("${"%.1f".format(totalBytes / (1024.0 * 1024.0))} MB")
                                    }
                                },
                                versions = item.scheme.versions.map { it.version },
                                selectedVersion = uiState.selectedVersions[item.scheme.id]
                                    ?: item.scheme.currentVersion,
                                onSelectVersion = { viewModel.selectVersion(item.scheme.id, it) },
                                onCardClick = { onNavigateToDetail(item.scheme.id) },
                                trailing = {
                                    SchemeTrailingButton(
                                        item = item,
                                        uiState = uiState,
                                        onDownload = { viewModel.downloadScheme(item) },
                                        onUpdate = { viewModel.updateScheme(item) },
                                    )
                                },
                            )
                        }
                        item {
                            Spacer(Modifier.height(8.dp))
                            val updatedAt = uiState.updatedAt.takeIf { it.isNotBlank() }
                            val sourceLabel = if (uiState.source.isNotBlank()) "来源：${uiState.source}" else "来自 Xime 官方源"
                            val footerText = buildString {
                                append("共 ${uiState.filteredSchemes.size} 个方案（$sourceLabel")
                                if (updatedAt != null) append(" · 更新于 $updatedAt")
                                append("）")
                            }
                            Text(
                                footerText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SchemeTrailingButton(
    item: MarketSchemeItem,
    uiState: SchemaMarketUiState,
    onDownload: () -> Unit,
    onUpdate: () -> Unit = onDownload,
) {
    val scheme = item.scheme
    when {
        uiState.downloadingId == scheme.id -> {
            OutlinedButton(onClick = onDownload, enabled = false) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("${(uiState.downloadProgress * 100).toInt()}%")
                }
            }
        }

        !item.compatible -> OutlinedButton(onClick = {}, enabled = false) {
            Text("需 App ≥ ${item.minAppVersion}")
        }

        scheme.id in uiState.downloadedIds && item.hasUpdate -> Button(
            onClick = onUpdate,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("更新")
        }

        scheme.id in uiState.downloadedIds -> {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "已安装",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                item.installedVersion?.let { version ->
                    Text(
                        "$version",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        else -> Button(
            onClick = onDownload,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("获取")
        }
    }
}

/* ------------------------------- 模型 Tab ------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelsMarketTab(
    onNavigateToDetail: (String) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: ModelManagementViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()

    // 从本地模型管理页删除后返回时，重新检查本地下载状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshDownloadedState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) isRefreshing = false
    }

    val categories = listOf(
        null to "全部",
        ModelCategory.PREDICTION to "联想",
        ModelCategory.HANDWRITING to "手写",
        ModelCategory.ASR to "语音",
        ModelCategory.OTHER to "其他",
    )

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            viewModel.refresh()
        },
        state = pullRefreshState,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            CategoryChips(
                categories = categories.map { it.second },
                selected = uiState.selectedCategory?.let { c ->
                    categories.firstOrNull { it.first == c }?.second
                },
                onSelect = { label ->
                    val target = categories.firstOrNull { it.second == label }?.first
                    viewModel.selectCategory(if (target == uiState.selectedCategory) null else target)
                },
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    uiState.isLoading && uiState.models.isEmpty() -> item {
                        MarketCenterBox(
                            modifier = Modifier.fillParentMaxSize(),
                            content = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(12.dp))
                                    Text("正在加载模型市场…", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        )
                    }

                    uiState.filteredModels.isEmpty() -> item {
                        MarketCenterBox(
                            modifier = Modifier.fillParentMaxSize(),
                            content = {
                                Text("暂无模型", style = MaterialTheme.typography.bodyMedium)
                            }
                        )
                    }

                    else -> {
                        items(uiState.filteredModels, key = { it.model.id }) { item ->
                            val (iconContainer, iconContent) = modelCategoryColors(item.model.category)
                            MarketStoreCard(
                                icon = modelCategoryIcon(item.model.category),
                                iconContainerColor = iconContainer,
                                iconContentColor = iconContent,
                                title = item.model.name,
                                subtitle = item.model.description.ifEmpty { item.model.id },
                                metaLine = buildString {
                                    append(modelCategoryLabel(item.model.category))
                                    val sizeLabel = if (item.isDownloaded && item.diskSize > 0) {
                                        modelFormatSize(item.diskSize)
                                    } else {
                                        item.model.size.ifEmpty { null }?.let { "约 $it" }
                                    }
                                    if (sizeLabel != null) {
                                        append("  ·  $sizeLabel")
                                    }
                                },
                                versions = item.model.versions.map { it.version },
                                selectedVersion = uiState.selectedVersions[item.model.id]
                                    ?: item.model.versions.firstOrNull()?.version.orEmpty(),
                                onSelectVersion = { viewModel.selectVersion(item.model.id, it) },
                                onCardClick = { onNavigateToDetail(item.model.id) },
                                trailing = {
                                    ModelTrailingButton(
                                        item = item,
                                        onDownload = { viewModel.downloadModel(item.model.id) },
                                    )
                                },
                            )
                        }
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "共 ${uiState.filteredModels.size} 个模型 · 模型为本地推理，耗电较高请斟酌使用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelTrailingButton(
    item: ModelItemState,
    onDownload: () -> Unit,
) {
    val downloadState = item.downloadState
    when {
        downloadState is ModelDownloadState.Downloading -> {
            Column(
                modifier = Modifier.width(96.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    "${(downloadState.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { downloadState.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                )
            }
        }
        downloadState is ModelDownloadState.Error -> {
            Text(
                "下载失败",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        item.isDownloaded && item.hasUpdate -> {
            Column(horizontalAlignment = Alignment.End) {
                Button(
                    onClick = onDownload,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("更新")
                }
                Text(
                    item.installedVersion.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item.isDownloaded -> Text(
            "已安装",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        else -> Button(
            onClick = onDownload,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("获取")
        }
    }
}

/* ------------------------------- 插件 Tab ------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginsMarketTab(
    onNavigateToDetail: (String) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: PluginMarketViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()

    // 每次进入插件 Tab 时刷新本地已安装状态（如从插件设置页卸载后返回），
    // 不重新拉取网络索引，避免缓存 installed=true 残留
    LaunchedEffect(Unit) {
        viewModel.refreshInstalledState()
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) isRefreshing = false
    }

    val categories = listOf("全部") + uiState.availableCategories

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            viewModel.refresh()
        },
        state = pullRefreshState,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (categories.size > 1) {
                CategoryChips(
                    categories = categories,
                    selected = uiState.selectedCategory,
                    onSelect = { label ->
                        viewModel.selectCategory(if (label == uiState.selectedCategory) null else label)
                    },
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    uiState.isLoading && uiState.plugins.isEmpty() -> item {
                        MarketCenterBox(
                            modifier = Modifier.fillParentMaxSize(),
                            content = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(12.dp))
                                    Text("正在加载插件市场…", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        )
                    }

                    uiState.errorMessage != null && uiState.plugins.isEmpty() -> item {
                        MarketCenterBox(
                            modifier = Modifier.fillParentMaxSize(),
                            content = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        uiState.errorMessage ?: "加载失败",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Button(onClick = { viewModel.loadPlugins() }) { Text("重试") }
                                }
                            }
                        )
                    }

                    uiState.filteredPlugins.isEmpty() -> item {
                        MarketCenterBox(
                            modifier = Modifier.fillParentMaxSize(),
                            content = {
                                Text("暂无插件", style = MaterialTheme.typography.bodyMedium)
                            }
                        )
                    }

                    else -> {
                        items(uiState.filteredPlugins, key = { it.plugin.id }) { item ->
                            val icon = pluginCategoryIcon(item.plugin.pluginType)
                            val (iconContainer, iconContent) = pluginCategoryColors(item.plugin.pluginType)
                            MarketStoreCard(
                                icon = icon,
                                iconText = item.plugin.icon,
                                iconContainerColor = iconContainer,
                                iconContentColor = iconContent,
                                title = item.plugin.name.ifEmpty { item.plugin.id },
                                subtitle = item.plugin.description,
                                metaLine = buildString {
                                    if (item.plugin.author.isNotEmpty()) append("作者：${item.plugin.author}")
                                    val typeLabel = pluginCategoryLabel(item.plugin.pluginType)
                                    if (typeLabel.isNotEmpty()) {
                                        if (item.plugin.author.isNotEmpty()) append("  ·  ")
                                        append(typeLabel)
                                    }
                                },
                                versions = item.plugin.versions.map { it.version },
                                selectedVersion = uiState.selectedVersions[item.plugin.id]
                                    ?: item.plugin.currentVersion,
                                onSelectVersion = { viewModel.selectVersion(item.plugin.id, it) },
                                onCardClick = { onNavigateToDetail(item.plugin.id) },
                                trailing = {
                                    PluginTrailingButton(
                                        item = item,
                                        uiState = uiState,
                                        onDownload = {
                                            viewModel.downloadPlugin(item.plugin.id)
                                        },
                                    )
                                },
                            )
                        }
                        item {
                            Spacer(Modifier.height(8.dp))
                            val updatedAt = uiState.updatedAt.takeIf { it.isNotBlank() }
                            val sourceLabel = if (uiState.source.isNotBlank()) "来源：${uiState.source}" else "来自 Xime 官方源"
                            val footerText = buildString {
                                append("共 ${uiState.filteredPlugins.size} 个插件（$sourceLabel")
                                if (updatedAt != null) append(" · 更新于 $updatedAt")
                                append("）")
                            }
                            Text(
                                footerText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginTrailingButton(
    item: MarketPluginItem,
    uiState: PluginMarketUiState,
    onDownload: () -> Unit,
) {
    val plugin = item.plugin
    when {
        uiState.downloadingId == plugin.id -> {
            OutlinedButton(onClick = onDownload, enabled = false) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("${(uiState.downloadProgress * 100).toInt()}%")
                }
            }
        }

        !item.compatible -> OutlinedButton(onClick = {}, enabled = false) {
            Text("需 App ≥ ${item.minAppVersion}")
        }

        item.installed && item.hasUpdate -> Button(
            onClick = onDownload,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("更新")
        }

        item.installed -> {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "已安装",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                item.installedVersion?.let { version ->
                    Text(
                        "$version",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        else -> Button(
            onClick = onDownload,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("获取")
        }
    }
}

/* ------------------------- 通用商店卡片 & 组件 ------------------------- */

/**
 * 商店式卡片（M3）：surfaceContainerLow 分层 + 大圆角；图标块用容器色；
 * 标题/描述 + 右侧操作区；底部 meta 行 + 版本选择。
 */
@Composable
private fun MarketStoreCard(
    icon: ImageVector,
    iconContainerColor: Color,
    iconContentColor: Color,
    title: String,
    subtitle: String,
    metaLine: String,
    versions: List<String>,
    selectedVersion: String,
    onSelectVersion: (String) -> Unit,
    onCardClick: () -> Unit,
    trailing: @Composable () -> Unit,
    /** 索引 v2 的 manifest.icon 字符图标：非空时优先于分类图标渲染。 */
    iconText: String = "",
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCardClick() }
                .padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconContainerColor),
                    contentAlignment = Alignment.Center,
                ) {
                    if (iconText.isNotEmpty()) {
                        Text(
                            iconText,
                            color = iconContentColor,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    } else {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = iconContentColor,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                trailing()
            }

            if (metaLine.isNotEmpty() || versions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (metaLine.isNotEmpty()) {
                        Text(
                            metaLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (versions.isNotEmpty()) {
                        MarketVersionSelector(
                            versions = versions,
                            selectedVersion = selectedVersion,
                            onSelectVersion = onSelectVersion,
                        )
                    }
                }
            }
        }
    }
}

/** 版本选择：单版本直接展示文本，多版本提供下拉。 */
@Composable
private fun MarketVersionSelector(
    versions: List<String>,
    selectedVersion: String,
    onSelectVersion: (String) -> Unit,
) {
    if (versions.size <= 1) {
        Text(
            "${versions.firstOrNull().orEmpty()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            modifier = Modifier.height(IntrinsicSize.Min),
        ) {
            Text(
                "版本 ${selectedVersion.ifEmpty { versions.first() }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = "选择版本",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            versions.forEach { v ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "$v",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (v == selectedVersion.ifEmpty { versions.first() }) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "当前",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelectVersion(v)
                        expanded = false
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 横向滚动的分类 FilterChip 行（二级分类导航）。 */
@Composable
private fun CategoryChips(
    categories: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(categories) { _, category ->
            FilterChip(
                selected = category == selected,
                onClick = { onSelect(category) },
                label = { Text(category, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

/** 居中容器：空态/加载/错误占位。 */
@Composable
internal fun MarketCenterBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            content()
        }
    }
}

/** 详情页标签 chip。 */
@Composable
private fun MarketTag(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 详情页元信息行（key: value）。 */
@Composable
private fun DetailMetaRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/* ------------------------------- 方案详情页 ------------------------------- */

/** 方案详情页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketSchemeDetailContent(
    schemeId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SchemaMarketViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val item = uiState.schemes.firstOrNull { it.scheme.id == schemeId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("方案详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        when {
            uiState.isLoading && item == null -> MarketCenterBox {
                CircularProgressIndicator()
            }

            item == null -> MarketCenterBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "未找到该方案",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.loadSchemes() }) { Text("重新加载") }
                }
            }

            else -> SchemeDetailBody(
                item = item,
                uiState = uiState,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchemeDetailBody(
    item: MarketSchemeItem,
    uiState: SchemaMarketUiState,
    viewModel: SchemaMarketViewModel,
    modifier: Modifier = Modifier,
) {
    val scheme = item.scheme
    val (iconContainer, iconContent) = schemeCategoryColors(scheme.tags.firstOrNull())
    val selectedVersion = uiState.selectedVersions[scheme.id] ?: scheme.currentVersion
    val selectedVersionObj = scheme.versions.firstOrNull { it.version == selectedVersion }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(iconContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        schemeCategoryIcon(scheme.tags.firstOrNull()),
                        contentDescription = null,
                        tint = iconContent,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        scheme.name.ifEmpty { scheme.id },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (scheme.author.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            scheme.author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (scheme.description.isNotEmpty()) {
            item {
                Text(
                    scheme.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (scheme.tags.isNotEmpty()) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    scheme.tags.forEach { tag -> MarketTag(tag) }
                }
            }
        }

        if (scheme.dependencies.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "依赖",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        scheme.dependencies.forEach { dep -> MarketTag(dep) }
                    }
                }
            }
        }

        if (scheme.warning.isNotEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                ) {
                    Text(
                        scheme.warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        item {
            Text(
                "版本历史",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (scheme.versions.isEmpty()) {
            item {
                Text(
                    "暂无版本信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            items(scheme.versions, key = { it.version }) { v ->
                SchemeVersionCard(
                    version = v,
                    selected = v.version == selectedVersion,
                    onClick = { viewModel.selectVersion(scheme.id, v.version) },
                    trailing = {
                        SchemeVersionDownloadButton(
                            item = item,
                            uiState = uiState,
                            version = v.version,
                            onDownload = { viewModel.downloadScheme(item, v.version) },
                        )
                    },
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (scheme.appVersion.isNotEmpty()) {
                    DetailMetaRow("APP 版本要求", scheme.appVersion)
                }
                if (scheme.license.isNotEmpty()) {
                    DetailMetaRow("许可证", scheme.license)
                }
                if (scheme.homepage.isNotEmpty()) {
                    DetailMetaRow("主页", scheme.homepage)
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 版本卡片内嵌下载按钮（Play Store 风格）。 */
@Composable
private fun SchemeVersionDownloadButton(
    item: MarketSchemeItem,
    uiState: SchemaMarketUiState,
    version: String,
    onDownload: () -> Unit,
) {
    val scheme = item.scheme
    when {
        uiState.downloadingId == scheme.id -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${(uiState.downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        !item.compatible -> OutlinedButton(
            onClick = {},
            enabled = false,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text("需 App ≥ ${item.minAppVersion}", style = MaterialTheme.typography.labelSmall)
        }

        scheme.id in uiState.downloadedIds -> OutlinedButton(
            onClick = onDownload,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text("重新下载", style = MaterialTheme.typography.labelSmall)
        }

        else -> Button(
            onClick = onDownload,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        ) {
            Text("下载", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SchemeVersionCard(
    version: SchemeVersion,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${version.version}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "当前版本",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            if (version.date.isNotEmpty()) {
                Text(
                    version.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (version.changelog.isNotEmpty()) {
                Text(
                    version.changelog,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val totalBytes = version.downloadUrls.sumOf { dl ->
                dl.size?.removeSuffix(" MB")?.trim()?.toDoubleOrNull()
                    ?.let { (it * 1024.0 * 1024.0).toLong() } ?: 0L
            }
            if (totalBytes > 0) {
                Text(
                    "${"%.1f".format(totalBytes / (1024.0 * 1024.0))} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/* ------------------------------- 模型详情页 ------------------------------- */

/** 模型详情页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketModelDetailContent(
    modelId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: ModelManagementViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val item = uiState.models.firstOrNull { it.model.id == modelId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        when {
            uiState.isLoading && item == null -> MarketCenterBox {
                CircularProgressIndicator()
            }

            item == null -> MarketCenterBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "未找到该模型",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.refresh() }) { Text("重新加载") }
                }
            }

            else -> ModelDetailBody(
                item = item,
                uiState = uiState,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ModelDetailBody(
    item: ModelItemState,
    uiState: ModelManagementUiState,
    viewModel: ModelManagementViewModel,
    modifier: Modifier = Modifier,
) {
    val model = item.model
    val (iconContainer, iconContent) = modelCategoryColors(model.category)
    val selectedVersion = uiState.selectedVersions[model.id]
        ?: model.versions.firstOrNull()?.version.orEmpty()
    val selectedVersionObj = model.versions.firstOrNull { it.version == selectedVersion }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(iconContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        modelCategoryIcon(model.category),
                        contentDescription = null,
                        tint = iconContent,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        modelCategoryLabel(model.category),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (model.description.isNotEmpty()) {
            item {
                Text(
                    model.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "使用本地模型是有代价的，它可能会占用内存和 CPU，导致手机发热，请斟酌使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        if (selectedVersionObj != null && selectedVersionObj.size.isNotEmpty()) {
            item {
                DetailMetaRow("大小", selectedVersionObj.size)
            }
        } else if (model.size.isNotEmpty()) {
            item {
                DetailMetaRow("大小", model.size)
            }
        }

        item {
            Text(
                "版本历史",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (model.versions.isEmpty()) {
            item {
                Text(
                    "暂无版本信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            items(model.versions, key = { it.version }) { v ->
                ModelVersionCard(
                    version = v,
                    selected = v.version == selectedVersion,
                    onClick = { viewModel.selectVersion(model.id, v.version) },
                    trailing = {
                        ModelVersionDownloadButton(
                            item = item,
                            version = v,
                            onDownload = { viewModel.downloadModel(model.id, v) },
                        )
                    },
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 模型版本卡片内嵌下载按钮（Play Store 风格）。 */
@Composable
private fun ModelVersionDownloadButton(
    item: ModelItemState,
    version: ModelVersion,
    onDownload: () -> Unit,
) {
    val downloadState = item.downloadState
    when {
        downloadState is ModelDownloadState.Downloading -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${(downloadState.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        downloadState is ModelDownloadState.Error -> {
            Text(
                "下载失败",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        item.isDownloaded -> Text(
            "已安装",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        else -> Button(
            onClick = onDownload,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        ) {
            Text("下载", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ModelVersionCard(
    version: ModelVersion,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${version.version}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "当前版本",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            if (version.date.isNotEmpty()) {
                Text(
                    version.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (version.changelog.isNotEmpty()) {
                Text(
                    version.changelog,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (version.size.isNotEmpty()) {
                Text(
                    version.size,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/* ------------------------------- 图标映射 ------------------------------- */

private fun schemeCategoryIcon(tag: String?): ImageVector = Icons.Default.AutoAwesome

/** 方案分类色块：统一用 primaryContainer，随主题变化。 */
@Composable
private fun schemeCategoryColors(tag: String?): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return scheme.primaryContainer to scheme.onPrimaryContainer
}

/** 模型分类色块：按类别用 M3 分层容器色。 */
@Composable
private fun modelCategoryColors(category: ModelCategory): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (category) {
        ModelCategory.PREDICTION ->
            scheme.primaryContainer to scheme.onPrimaryContainer
        ModelCategory.HANDWRITING ->
            scheme.secondaryContainer to scheme.onSecondaryContainer
        ModelCategory.ASR ->
            scheme.tertiaryContainer to scheme.onTertiaryContainer
        ModelCategory.OTHER ->
            scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
}

private fun modelCategoryIcon(category: ModelCategory): ImageVector = when (category) {
    ModelCategory.PREDICTION -> Icons.Default.AutoAwesome
    ModelCategory.ASR -> Icons.Default.GraphicEq
    ModelCategory.HANDWRITING -> Icons.Outlined.Gesture
    ModelCategory.OTHER -> Icons.Default.AutoAwesome
}

/** 插件分类图标（pluginType 用 PluginCategory 的 id：emoji/speech/prediction）。 */
private fun pluginCategoryIcon(pluginType: String): ImageVector = when (pluginType) {
    "emoji" -> Icons.Default.SentimentSatisfiedAlt
    "speech" -> Icons.Default.GraphicEq
    "prediction" -> Icons.Default.AutoAwesome
    "clipboard_sync" -> Icons.Default.Sync
    "tool" -> Icons.Default.AutoFixHigh
    "backup" -> Icons.Default.CloudUpload
    else -> Icons.Default.Extension
}

/** 插件分类色块。 */
@Composable
private fun pluginCategoryColors(pluginType: String): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (pluginType) {
        "emoji" -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        "speech" -> scheme.primaryContainer to scheme.onPrimaryContainer
        "prediction" -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
}

private fun pluginCategoryLabel(pluginType: String): String = when (pluginType) {
    "emoji" -> "表情"
    "speech" -> "语音转文本"
    "prediction" -> "智能预测"
    "clipboard_sync" -> "剪贴板同步"
    "tool" -> "工具"
    "backup" -> "云备份"
    else -> "其他"
}

/** 激活方式显示文案（索引 v2 activation）：single=单选激活 / multi=多选激活；未声明返回空。 */
private fun pluginActivationLabel(activation: String): String = when (activation) {
    "single" -> "单选激活"
    "multi" -> "多选激活"
    else -> ""
}

/** 能力声明摘要（索引 v2 capabilities，已知能力拼接；无已知能力返回空）。 */
internal fun pluginCapabilitiesSummary(plugin: MarketPlugin): String = buildString {
    plugin.capabilities.speech?.let { speech ->
        if (speech.inputMode == "streaming") append("流式识别")
        if (speech.supportsPartialResults) {
            if (isNotEmpty()) append("、")
            append("支持部分结果")
        }
    }
    plugin.capabilities.tool?.let { tool ->
        when (tool.display) {
            "direct" -> {
                if (isNotEmpty()) append("、")
                append("直接调用")
            }
            "passive" -> {
                if (isNotEmpty()) append("、")
                append("被动展示")
            }
        }
    }
}

internal fun modelCategoryLabel(category: ModelCategory): String = when (category) {
    ModelCategory.PREDICTION -> "联想"
    ModelCategory.ASR -> "语音"
    ModelCategory.HANDWRITING -> "手写"
    ModelCategory.OTHER -> "其他"
}

internal fun modelFormatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }
}

/* ---------------------------- 插件详情页 ---------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginMarketDetailContent(
    pluginId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: PluginMarketViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 进入详情页时刷新本地已安装状态（卸载后返回不再残留 installed=true）
    LaunchedEffect(Unit) {
        viewModel.refreshInstalledState()
    }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val item = uiState.plugins.firstOrNull { it.plugin.id == pluginId }
    val localItem = remember(item, pluginId) {
        if (item == null) {
            com.kingzcheung.xime.plugin.ExtensionManager.getAllInstalledPlugins()
                .find { it.id == pluginId }
                ?.let { info ->
                    MarketPluginItem(
                        plugin = MarketPlugin(
                            id = info.id,
                            name = info.name,
                            author = "",
                            description = info.description,
                            pluginType = info.category.id,
                            currentVersion = info.versionName,
                            versions = listOf(PluginVersion(version = info.versionName)),
                        ),
                        compatible = true,
                        minAppVersion = info.minHostVersion.orEmpty(),
                        installed = true,
                        installedVersion = info.versionName,
                    )
                }
        } else {
            null
        }
    }
    val displayItem = item ?: localItem

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("插件详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        when {
            uiState.isLoading && displayItem == null -> MarketCenterBox {
                CircularProgressIndicator()
            }

            displayItem == null -> MarketCenterBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "未找到该插件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.loadPlugins() }) { Text("重新加载") }
                }
            }

            else -> PluginDetailBody(
                item = displayItem,
                uiState = uiState,
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun PluginDetailBody(
    item: MarketPluginItem,
    uiState: PluginMarketUiState,
    viewModel: PluginMarketViewModel,
    modifier: Modifier = Modifier,
) {
    val plugin = item.plugin
    val (iconContainer, iconContent) = pluginCategoryColors(plugin.pluginType)
    val selectedVersion = uiState.selectedVersions[plugin.id]
        ?: plugin.resolvedVersion()?.version.orEmpty()

    val installedPlugin = remember(item.plugin.id, item.installed) {
        if (item.installed) {
            PluginManager.getAllInstallPlugins().find { it.id == item.plugin.id }
        } else {
            null
        }
    }
    val context = LocalContext.current
    val declaredHosts = installedPlugin?.declaredHosts.orEmpty()
    val networkHosts = remember(installedPlugin?.id, plugin.id, plugin.network) {
        if (installedPlugin != null) {
            (declaredHosts +
                ExtensionManager.getConfiguredNetworkHosts(context, installedPlugin.id) +
                SettingsPreferences.getPluginPendingHosts(context, installedPlugin.id)).distinct()
        } else {
            // 未安装：用索引 v2 声明的域名做联网预览
            plugin.network.hosts
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(iconContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (plugin.icon.isNotEmpty()) {
                        Text(
                            plugin.icon,
                            color = iconContent,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    } else {
                        Icon(
                            pluginCategoryIcon(plugin.pluginType),
                            contentDescription = null,
                            tint = iconContent,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        plugin.name.ifEmpty { plugin.id },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        pluginCategoryLabel(plugin.pluginType).ifEmpty { "插件" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (plugin.description.isNotEmpty()) {
            item {
                Text(
                    plugin.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (plugin.author.isNotEmpty()) {
            item {
                DetailMetaRow("作者", plugin.author)
            }
        }

        val typeLabel = pluginCategoryLabel(plugin.pluginType)
        if (typeLabel.isNotEmpty()) {
            item {
                DetailMetaRow("类型", typeLabel)
            }
        }

        // 索引 v2 元信息（旧索引无这些字段时各行为空串不渲染）
        val activationLabel = pluginActivationLabel(plugin.activation)
        if (activationLabel.isNotEmpty()) {
            item {
                DetailMetaRow("激活方式", activationLabel)
            }
        }
        if (plugin.platforms.isNotEmpty()) {
            item {
                DetailMetaRow("平台", plugin.platforms.joinToString("、") { "Android" })
            }
        }
        if (plugin.minHostVersion.isNotBlank()) {
            item {
                DetailMetaRow("需宿主版本", plugin.minHostVersion)
            }
        }
        val capabilitiesSummary = pluginCapabilitiesSummary(plugin)
        if (capabilitiesSummary.isNotEmpty()) {
            item {
                DetailMetaRow("能力", capabilitiesSummary)
            }
        }

        if (plugin.warning.isNotEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                ) {
                    Text(
                        plugin.warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        if (plugin.tags.isNotEmpty()) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    plugin.tags.forEach { tag -> MarketTag(tag) }
                }
            }
        }

        if (networkHosts.isNotEmpty()) {
            item {
                Text(
                    "网络权限",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        NetworkAccessSection(
                            pluginId = item.plugin.id,
                            pluginName = plugin.name.ifEmpty { plugin.id },
                            hosts = networkHosts,
                        )
                    }
                }
            }
        }

        item {
            Text(
                "版本历史",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (plugin.versions.isEmpty()) {
            item {
                Text(
                    "暂无版本信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            items(plugin.versions, key = { it.version }) { v ->
                PluginVersionCard(
                    version = v,
                    selected = v.version == selectedVersion,
                    onClick = { viewModel.selectVersion(plugin.id, v.version) },
                    trailing = {
                        PluginVersionDownloadButton(
                            item = item,
                            uiState = uiState,
                            version = v,
                            onDownload = { viewModel.downloadPlugin(plugin.id, v.version) },
                        )
                    },
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 插件版本卡片内嵌下载按钮（对齐模型版本卡）。 */
@Composable
private fun PluginVersionDownloadButton(
    item: MarketPluginItem,
    uiState: PluginMarketUiState,
    version: PluginVersion,
    onDownload: () -> Unit,
) {
    when {
        uiState.downloadingId == item.plugin.id -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${(uiState.downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        !item.compatible -> Text(
            "需 App ≥ ${item.minAppVersion}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        item.installed && item.hasUpdate -> Button(
            onClick = onDownload,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        ) {
            Text("更新", style = MaterialTheme.typography.labelSmall)
        }

        item.installed -> Text(
            "已安装",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        else -> Button(
            onClick = onDownload,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        ) {
            Text("下载", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PluginVersionCard(
    version: PluginVersion,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${version.version}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "当前版本",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            if (version.date.isNotEmpty()) {
                Text(
                    version.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (version.changelog.isNotEmpty()) {
                Text(
                    version.changelog,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (version.size.isNotEmpty()) {
                Text(
                    version.size,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/* ------------------------------- 布局 Tab ------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayoutsMarketTab(
    onNavigateToDetail: (String) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: LayoutMarketViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()
    var confirmApply by remember { mutableStateOf<MarketLayoutItem?>(null) }

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) isRefreshing = false
    }

    confirmApply?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmApply = null },
            title = { Text("应用布局") },
            text = {
                Text("将覆盖当前的 xime.custom.yaml，且不保留备份。是否继续应用「${target.layout.name.ifBlank { target.layout.id }}」？")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmApply = null
                    viewModel.applyLayout(target)
                }) { Text("应用") }
            },
            dismissButton = {
                TextButton(onClick = { confirmApply = null }) { Text("取消") }
            },
        )
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            viewModel.loadLayouts(manual = true)
        },
        state = pullRefreshState,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (uiState.availableTags.isNotEmpty()) {
                CategoryChips(
                    categories = uiState.availableTags,
                    selected = uiState.selectedTag,
                    onSelect = { viewModel.selectTag(if (it == uiState.selectedTag) null else it) },
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    uiState.isLoading && uiState.layouts.isEmpty() -> item {
                        MarketCenterBox(
                            modifier = Modifier.fillParentMaxSize(),
                            content = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(12.dp))
                                    Text("正在加载主题与布局…", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        )
                    }

                    uiState.errorMessage != null && uiState.layouts.isEmpty() -> item {
                        MarketCenterBox(
                            modifier = Modifier.fillParentMaxSize(),
                            content = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        uiState.errorMessage ?: "加载失败",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Button(onClick = { viewModel.loadLayouts(manual = true) }) { Text("重试") }
                                }
                            }
                        )
                    }

                    uiState.filteredLayouts.isEmpty() -> item {
                        MarketCenterBox(
                            modifier = Modifier.fillParentMaxSize(),
                            content = {
                                Text("暂无内容", style = MaterialTheme.typography.bodyMedium)
                            }
                        )
                    }

                    else -> {
                        items(uiState.filteredLayouts, key = { it.layout.id }) { item ->
                            val layout = item.layout
                            val applied = uiState.appliedLayoutId == layout.id
                            MarketStoreCard(
                                icon = Icons.Outlined.Keyboard,
                                iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                title = layout.name.ifEmpty { layout.id },
                                subtitle = layout.description.ifEmpty { layout.id },
                                metaLine = buildString {
                                    if (layout.author.isNotEmpty()) append("作者：${layout.author}")
                                    if (layout.license.isNotEmpty()) {
                                        if (isNotEmpty()) append("  ·  ")
                                        append(layout.license)
                                    }
                                    if (layout.requiresSchemes.isNotEmpty()) {
                                        if (isNotEmpty()) append("  ·  ")
                                        append("需 ${layout.requiresSchemes.joinToString("、")} 方案")
                                    }
                                    if (applied && item.hasUpdate) {
                                        if (isNotEmpty()) append("  ·  ")
                                        append("有更新")
                                    }
                                },
                                versions = layout.versions.map { it.version },
                                selectedVersion = uiState.selectedVersions[layout.id]
                                    ?: layout.resolvedVersion()?.version.orEmpty(),
                                onSelectVersion = { viewModel.selectVersion(layout.id, it) },
                                onCardClick = { onNavigateToDetail(layout.id) },
                                trailing = {
                                    LayoutTrailingButton(
                                        item = item,
                                        applied = applied,
                                        installing = uiState.installingId == layout.id,
                                        progress = uiState.installProgress,
                                        onApply = { confirmApply = item },
                                        onReset = { viewModel.resetLayout() },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayoutTrailingButton(
    item: MarketLayoutItem,
    applied: Boolean,
    installing: Boolean,
    progress: Float,
    onApply: () -> Unit,
    onReset: () -> Unit,
) {
    when {
        installing -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LinearProgressIndicator(
                    progress = { if (progress > 0f) progress else 0f },
                    modifier = Modifier.width(72.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text("应用中…", style = MaterialTheme.typography.labelSmall)
            }
        }

        !item.schemeReady -> {
            OutlinedButton(onClick = {}, enabled = false) { Text("缺少依赖") }
        }

        applied -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "已应用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onReset) { Text("恢复默认") }
            }
        }

        !item.compatible -> {
            OutlinedButton(onClick = {}, enabled = false) { Text("需更高版本") }
        }

        else -> {
            Button(onClick = onApply) { Text("应用") }
        }
    }
}
