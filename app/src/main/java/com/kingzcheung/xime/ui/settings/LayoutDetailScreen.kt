package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kingzcheung.xime.settings.MarketLayoutItem
import com.kingzcheung.xime.viewmodel.LayoutMarketViewModel

/** 布局详情页：元信息、截图、版本列表；应用/恢复操作固定在底部栏。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutMarketDetailContent(
    layoutId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: LayoutMarketViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val item = uiState.layouts.firstOrNull { it.layout.id == layoutId }
    var confirmApply by remember { mutableStateOf(false) }
    val selectedVersion = item?.let {
        uiState.selectedVersions[it.layout.id] ?: it.layout.resolvedVersion()?.version.orEmpty()
    }.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_xiangqing)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr(R.string.s_fanhui))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            if (item != null) {
                LayoutDetailBottomBar(
                    item = item,
                    applied = uiState.appliedLayoutId == item.layout.id,
                    installing = uiState.installingId == item.layout.id,
                    onApply = { confirmApply = true },
                    onReset = { viewModel.resetLayout() },
                )
            }
        },
    ) { padding ->
        when {
            uiState.isLoading && item == null -> MarketCenterBox {
                CircularProgressIndicator()
            }

            item == null -> MarketCenterBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        tr(R.string.s_weizhaodaogaineirong),
                        style = MaterialTheme.typography.bodyMedium,
                        color = settingsSecondaryColor(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.loadLayouts(manual = true) }) { Text(tr(R.string.s_zhongxinjiazai)) }
                }
            }

            else -> LayoutDetailBody(
                item = item,
                viewModel = viewModel,
                selectedVersion = selectedVersion,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (confirmApply && item != null) {
        AlertDialog(
            onDismissRequest = { confirmApply = false },
            title = { Text(tr(R.string.s_yingyongbuju)) },
            text = {
                Column {
                    Text(
                        tr(
                            R.string.s_jiangfugaidangqiande_xime_cust_d2f7d,
                            item.layout.name.ifBlank { item.layout.id },
                        ),
                    )
                    if (item.layout.warning.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            item.layout.warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmApply = false
                    viewModel.applyLayout(item, selectedVersion)
                }) { Text(tr(R.string.s_yingyong)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmApply = false }) { Text(tr(R.string.s_quxiao)) }
            },
        )
    }
}

@Composable
private fun LayoutDetailBody(
    item: MarketLayoutItem,
    viewModel: LayoutMarketViewModel,
    selectedVersion: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val layout = item.layout
    val currentVersion = layout.versions.firstOrNull { it.version == selectedVersion }
        ?: layout.resolvedVersion()
    var previewUrl by remember { mutableStateOf<String?>(null) }

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
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Keyboard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(
                        layout.name.ifEmpty { layout.id },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (layout.author.isNotEmpty()) {
                        Text(
                            tr(R.string.s_zuozhe_2, layout.author),
                            style = MaterialTheme.typography.bodySmall,
                            color = settingsSecondaryColor(),
                        )
                    }
                }
            }
        }

        if (layout.warning.isNotEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                ) {
                    Text(
                        layout.warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        if (layout.description.isNotEmpty()) {
            item {
                Text(
                    layout.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        if (layout.tags.isNotEmpty()) {
            item { LayoutTagFlow(layout.tags) }
        }

        item {
            LayoutMetaCard(
                item = item,
                selectedVersion = selectedVersion,
                date = currentVersion?.date.orEmpty(),
                onOpenRepo = { openUrl(context, layout.repo) },
            )
        }

        if (currentVersion != null && currentVersion.changelog.isNotEmpty()) {
            item {
                Column {
                    Text(
                        tr(R.string.s_banben_2, selectedVersion),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        currentVersion.changelog,
                        style = MaterialTheme.typography.bodySmall,
                        color = settingsSecondaryColor(),
                    )
                }
            }
        }

        if (layout.versions.size > 1) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr(R.string.s_lishibanben), style = MaterialTheme.typography.titleSmall)
                    layout.versions.forEach { v ->
                        LayoutVersionRow(
                            version = v.version,
                            date = v.date,
                            selected = v.version == selectedVersion,
                            onClick = { viewModel.selectVersion(layout.id, v.version) },
                        )
                    }
                }
            }
        }

        if (layout.screenshots.isNotEmpty()) {
            item {
                // 截图缩略图横排：全宽渲染会把竖版键盘截图放大到整屏高，
                // 详情页被撑爆；点击仍进全屏预览（双指缩放）
                Text(tr(R.string.s_jietu), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(layout.screenshots) { url ->
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(120.dp)
                                .aspectRatio(9f / 19f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { previewUrl = url },
                        )
                    }
                }
            }
        }
    }

    previewUrl?.let { url ->
        ScreenshotPreviewDialog(url = url, onDismiss = { previewUrl = null })
    }
}

/** 元信息卡片：依赖方案、版本、许可证、仓库（可点击打开）。 */
@Composable
private fun LayoutMetaCard(
    item: MarketLayoutItem,
    selectedVersion: String,
    date: String,
    onOpenRepo: () -> Unit,
) {
    val layout = item.layout
    val schemes = layout.requiresSchemes.joinToString("、")
    val versionText = listOf(tr(R.string.s_banben_2, selectedVersion), date)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    val showVersion = selectedVersion.isNotBlank()
    val showDependency = layout.requiresSchemes.isNotEmpty()
    val showLicense = layout.license.isNotEmpty()
    val showRepo = layout.repo.isNotEmpty()
    if (!showVersion && !showDependency && !showLicense && !showRepo) return

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            if (showDependency) {
                MetaRow(
                    icon = Icons.Outlined.Extension,
                    text = if (item.schemeReady) {
                        tr(R.string.s_yilaifangan_yianzhuang, schemes)
                    } else {
                        tr(R.string.s_xuxian_anzhuangfangan, schemes)
                    },
                    color = if (item.schemeReady) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
            if (showVersion) {
                MetaRow(icon = Icons.Outlined.Info, text = versionText)
            }
            if (showLicense) {
                MetaRow(icon = Icons.Outlined.Verified, text = tr(R.string.s_xuke, layout.license))
            }
            if (showRepo) {
                MetaRow(
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    text = layout.repo,
                    color = MaterialTheme.colorScheme.primary,
                    underline = true,
                    onClick = onOpenRepo,
                )
            }
        }
    }
}

/** 元信息行：图标 + 文本，可选点击（仓库链接等）。 */
@Composable
private fun MetaRow(
    icon: ImageVector,
    text: String,
    color: Color = settingsSecondaryColor(),
    underline: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            textDecoration = if (underline) TextDecoration.Underline else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 标签：圆角小片，替代原来的一行「a · b · c」。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayoutTagFlow(tags: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tags.forEach { tag ->
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    tag,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** 历史版本行：当前选中项高亮并显示勾选。 */
@Composable
private fun LayoutVersionRow(
    version: String,
    date: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                version,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (date.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.weight(1f))
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 底部固定操作栏：应用/重新应用与恢复默认，滚动时始终可见。 */
@Composable
private fun LayoutDetailBottomBar(
    item: MarketLayoutItem,
    applied: Boolean,
    installing: Boolean,
    onApply: () -> Unit,
    onReset: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (applied) {
                OutlinedButton(
                    onClick = onReset,
                    enabled = !installing,
                    modifier = Modifier.weight(1f),
                ) { Text(tr(R.string.s_huifumoren)) }
            }
            Button(
                onClick = onApply,
                enabled = item.schemeReady && !installing,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    when {
                        installing -> tr(R.string.s_yingyongzhong)
                        applied -> tr(R.string.s_zhongxinyingyong)
                        else -> tr(R.string.s_yingyong)
                    }
                )
            }
        }
    }
}

/** 打开外部链接：自动补全 https://，无浏览器时提示。 */
private fun openUrl(context: Context, url: String) {
    val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, tr(R.string.s_wufadakai, normalized), Toast.LENGTH_SHORT).show()
    }
}

/** 全屏截图预览：点击/返回关闭，支持双指缩放与拖动。 */
@Composable
private fun ScreenshotPreviewDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            offset = if (scale > 1f) offset + panChange else Offset.Zero
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .transformable(transformState),
            )
        }
    }
}