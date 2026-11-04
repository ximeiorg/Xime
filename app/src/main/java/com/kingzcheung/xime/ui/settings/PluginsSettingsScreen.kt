package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.twotone.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.plugin.ActivePluginSelection
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.EmojiPlugin
import com.kingzcheung.xime.plugin.core.api.PluginIcon
import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable
import com.kingzcheung.xime.plugin.core.model.Activation
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.model.TrustLevel
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.plugin.core.security.ErrorCategory
import com.kingzcheung.xime.plugin.core.security.PluginErrorLog
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.viewmodel.PluginsSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsSettingsContent(
    onBack: () -> Unit,
    onNavigateToPluginSettings: (String) -> Unit = {},
    onNavigateToPluginMarketDetail: (String) -> Unit = {},
    onNavigateToSpeechToText: () -> Unit = {},
    onNavigateToClipboardSync: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onNavigateToMarket: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: PluginsSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val importMessage by viewModel.importMessage.collectAsState()

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { viewModel.installPluginFromUri(it) }
    }

    var showWirelessSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(importMessage) {
        if (importMessage != null) {
            Toast.makeText(context, importMessage, Toast.LENGTH_SHORT).show()
            viewModel.consumeImportMessage()
        }
    }

    if (showWirelessSheet) {
        WirelessImportSheet(
            onDismiss = {
                showWirelessSheet = false
                viewModel.refreshPlugins()
            },
            onRefresh = { viewModel.refreshPlugins() }
        )
    }
    
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_chajianguanli)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = tr(R.string.s_fanhui)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshPlugins() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = tr(R.string.s_shuaxin)
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = tr(R.string.s_gengduo))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            offset = DpOffset(0.dp, 4.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text(tr(R.string.s_chajianshichang)) },
                                onClick = {
                                    showMenu = false
                                    onNavigateToMarket()
                                },
                                leadingIcon = {
                                    Icon(Icons.TwoTone.Storefront, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(tr(R.string.s_congwenjiananzhuangchajian)) },
                                onClick = {
                                    showMenu = false
                                    importLauncher.launch(arrayOf("*/*"))
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.FileOpen, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(tr(R.string.s_liulanqidaoru)) },
                                onClick = {
                                    showMenu = false
                                    showWirelessSheet = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Wifi, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                }
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (uiState.isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (uiState.errorMsg != null) {
                item {
                    Text(
                        text = tr(R.string.s_jiazaishibai_2, uiState.errorMsg),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                if (uiState.extensions.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddBox,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = tr(R.string.s_zanwuyianzhuangdechajian),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = tr(R.string.s_anzhuangchajianhoujiangzaicixi_e8aa0),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                } else {
                    // 单选类插件的"当前使用中"必须与**引擎同一套规则**（ActivePluginSelection）：
                    // 引擎在偏好为空/指向已卸载插件时会回退到"首个已启用插件"，
                    // 页面若只比较持久化偏好，就会出现"同步在跑、这里却显示未使用"。
                    val activeAsrPluginId = ActivePluginSelection.resolve(
                        SettingsPreferences.getSttOnlinePluginId(context),
                        ExtensionManager.getEnabledAsrPlugins(context).map { it.first }
                    )
                    val activeClipboardSyncPluginId = ActivePluginSelection.resolve(
                        SettingsPreferences.getClipboardSyncPluginId(context),
                        ExtensionManager.getEnabledClipboardSyncPlugins(context).map { it.first }
                    )
                    val activeBackupPluginId = ActivePluginSelection.resolve(
                        SettingsPreferences.getBackupPluginId(context),
                        ExtensionManager.getEnabledBackupPlugins(context).map { it.first }
                    )
                    items(uiState.extensions, key = { it.id }) { extension ->
                        ExtensionItem(
                            extension = extension,
                            pluginInstance = PluginManager.getPluginInstance(extension.id),
                            icon = uiState.icons[extension.id],
                            viewModel = viewModel,
                            onClick = { onNavigateToPluginSettings(extension.id) },
                            onOpenMarketDetail = { onNavigateToPluginMarketDetail(extension.id) },
                            isActive = when (extension.category) {
                                PluginCategory.ASR ->
                                    extension.id == activeAsrPluginId
                                PluginCategory.CLIPBOARD_SYNC ->
                                    extension.id == activeClipboardSyncPluginId
                                PluginCategory.BACKUP ->
                                    extension.id == activeBackupPluginId
                                else -> false
                            },
                            onActivate = when (extension.category) {
                                PluginCategory.ASR -> onNavigateToSpeechToText
                                PluginCategory.CLIPBOARD_SYNC -> onNavigateToClipboardSync
                                PluginCategory.BACKUP -> onNavigateToBackup
                                else -> null
                            }
                        )
                    }
                }
                
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = tr(R.string.s_tishi_anzhuangchajianhoudianji_f8547),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionItem(
    extension: PluginInfo,
    pluginInstance: Any?,
    icon: PluginIcon?,
    viewModel: PluginsSettingsViewModel,
    onClick: () -> Unit = {},
    onOpenMarketDetail: () -> Unit = {},
    isActive: Boolean = false,
    onActivate: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(viewModel.isPluginEnabled(extension.id)) }
    var showErrorDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showTrustConfirm by remember { mutableStateOf(false) }
    var trustConfirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    
    val errors = PluginErrorLog.getErrors(extension.id)
    val hasErrors = errors.isNotEmpty()

    val pendingHosts = remember(extension.id) {
        SettingsPreferences.getPluginPendingHosts(context, extension.id)
    }
    val hasPendingNetwork = pendingHosts.isNotEmpty() ||
        (extension.declaredHosts - SettingsPreferences.getPluginAuthorizedHosts(context, extension.id)).isNotEmpty()

    val hostCompatible = remember(extension.id) { viewModel.isHostCompatible(extension) }
    val hostRange = remember(extension) {
        buildString {
            if (!extension.minHostVersion.isNullOrBlank()) append(extension.minHostVersion)
            if (!extension.maxHostVersion.isNullOrBlank()) {
                if (isNotEmpty()) append(" - ") else append("≤ ")
                append(extension.maxHostVersion)
            }
        }
    }
    
    // 设置项探测走 IO：旧实现在组合期同步调 getSettingsSchema()，每次重组都会进一次
    // QuickJS（主线程被 JS 执行阻塞），且插件此刻若正被停用/重载（runtime.close()）会撞上
    // "Already closed"，被记成"[脚本错误] 调用 settings.schema"（v3.1.0 真机 record）。
    val configurable = pluginInstance as? IPluginConfigurable
    val schemaState = rememberPluginSettingsSchema(configurable, extension.id)
    val hasSettings = schemaState.schema.isNotEmpty() ||
        (pluginInstance as? EmojiPlugin)?.hasSettings() == true
    
    if (showErrorDialog && hasErrors) {
        PluginErrorDialog(
            pluginId = extension.id,
            pluginName = extension.name,
            errors = errors,
            onDismiss = { showErrorDialog = false },
            onClear = { 
                PluginErrorLog.clearErrors(extension.id)
                showErrorDialog = false
            }
        )
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenMarketDetail() }
                .padding(12.dp)
        ) {
            // 第一行：图标 + 标题 + 状态指示器
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PluginIconView(
                    icon = icon,
                    category = extension.category,
                    modifier = Modifier.padding(end = 10.dp)
                )

                Text(
                    text = extension.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                // 状态指示器（固定在右侧）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (hasErrors) {
                        // 可点：打开该插件的错误日志（原先只是指示器，详情对话框没有任何入口）
                        IconButton(
                            onClick = { showErrorDialog = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = tr(R.string.s_chakancuowurizhi_tiao, errors.size),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    if (hasPendingNetwork) {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = tr(R.string.s_wangluofangwendaishouquan),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    if (!hostCompatible) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = tr(R.string.s_yuzhuyingyongbanbenbujianrong),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            
            // 第二行：类型 + 版本
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = extension.category.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isEnabled) MaterialTheme.colorScheme.primary 
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("•", style = MaterialTheme.typography.bodySmall, 
                     color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Text(
                    text = "${extension.versionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // 信任徽标
                val trustBadge = trustBadge(extension.trustLevel)
                Text("•", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Text(
                    text = trustBadge.first,
                    style = MaterialTheme.typography.bodySmall,
                    color = trustBadge.second
                )
            }
            
            // 详情
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                
                if (extension.description.isNotEmpty()) {
                    Text(
                        text = extension.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                // 错误入口：整行可点（右上角 14dp 指示器太小，这里给出明确文案与"查看"）
                if (hasErrors) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showErrorDialog = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = pluginErrorSummary(errors.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = tr(R.string.s_chakan),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                val networkHosts = remember(extension.id) {
                    (extension.declaredHosts +
                        ExtensionManager.getConfiguredNetworkHosts(context, extension.id) +
                        SettingsPreferences.getPluginPendingHosts(context, extension.id))
                        .distinct()
                }
                if (networkHosts.isNotEmpty()) {
                    NetworkAccessSection(
                        pluginId = extension.id,
                        pluginName = extension.name,
                        hosts = networkHosts
                    )
                }

                if (!hostCompatible) {
                    Text(
                        text = tr(R.string.s_gaichajianbuzhichidangqianzhuy_81343) +
                            if (hostRange.isNotEmpty()) tr(R.string.s_yaoqiu, hostRange) else tr(R.string.s_qinggengxinzhuyingyonghouzhongshi),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    )
                }

                // 操作按钮行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (extension.category.activation == Activation.SINGLE) {
                        // 单选分类：激活在对应功能设置页完成，插件中心不显示启用开关
                        if (!hostCompatible) {
                            Text(
                                text = tr(R.string.s_yuzhuyingyongbanbenbujianrong),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text(
                                text = if (isActive) tr(R.string.s_dangqianshiyongzhong) else tr(R.string.s_weishiyong),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        if (!hostCompatible && hostRange.isNotEmpty()) {
                            Text(
                                text = tr(R.string.s_yaoqiu_2, hostRange),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        } else if (!isActive && onActivate != null) {
                            OutlinedButton(
                                onClick = {
                                    if (extension.trustLevel == TrustLevel.TRUSTED) {
                                        onActivate()
                                    } else {
                                        trustConfirmAction = { onActivate() }
                                        showTrustConfirm = true
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(tr(R.string.s_quxuanze), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    } else {
                        // 多选分类：插件管理不提供启用开关，启用/停用在使用处进行（如表情面板）
                        Text(
                            text = when {
                                !hostCompatible -> tr(R.string.s_bujianrong)
                                isEnabled -> tr(R.string.s_yiqiyong)
                                else -> tr(R.string.s_weiqiyong)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (!hostCompatible)
                                MaterialTheme.colorScheme.error
                            else if (isEnabled)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )

                        Spacer(modifier = Modifier.weight(1f))
                    }

                    // 设置按钮
                    if (hasSettings) {
                        OutlinedButton(
                            onClick = onClick,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(tr(R.string.s_shezhi), style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    // 删除按钮
                    if (extension.source == PluginSource.SYSTEM) {
                        IconButton(
                            onClick = {
                                try {
                                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    intent.data = Uri.parse("package:${extension.id}")
                                    context.startActivity(intent)
                                    Toast.makeText(context, tr(R.string.s_qingzaiyingyongxinxiyemianxiezai), Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, tr(R.string.s_wufadakai, e.message), Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = tr(R.string.s_xiezai),
                                 tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        IconButton(
                            onClick = { showDeleteConfirm = true }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = tr(R.string.s_xiezai),
                                 tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(tr(R.string.s_xiezaichajian)) },
            text = { Text(tr(R.string.s_quedingyaoxiezai_ma_nchajianwe_4f200, extension.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.uninstallPlugin(extension.id)
                    }
                ) {
                    Text(tr(R.string.s_xiezai), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(tr(R.string.s_quxiao))
                }
            }
        )
    }

    if (showTrustConfirm) {
        val badge = trustBadge(extension.trustLevel)
        AlertDialog(
            onDismissRequest = {
                showTrustConfirm = false
                trustConfirmAction = null
            },
            title = { Text(tr(R.string.s_qiyongfeiguanfangchajian)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr(R.string.s_chajian_weiguanfang, extension.name, badge?.first ?: tr(R.string.s_weizhilaiyuan)))
                    Text(tr(R.string.s_feiguanfangchajiankenengfangwe_08b02))
                    if (extension.description.isNotEmpty()) {
                        Text(
                            text = tr(R.string.s_miaoshu, extension.description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val action = trustConfirmAction
                        showTrustConfirm = false
                        trustConfirmAction = null
                        action?.invoke()
                    }
                ) {
                    Text(tr(R.string.s_jixuqiyong))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTrustConfirm = false
                    trustConfirmAction = null
                }) {
                    Text(tr(R.string.s_quxiao))
                }
            }
        )
    }
}

// 插件网络访问授权：展示声明/待授权域名，未授权可授权，已授权可撤销
@Composable
fun NetworkAccessSection(
    pluginId: String,
    pluginName: String,
    hosts: List<String>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var authorized by remember(pluginId) {
        mutableStateOf(SettingsPreferences.getPluginAuthorizedHosts(context, pluginId))
    }
    var pending by remember(pluginId) {
        mutableStateOf(SettingsPreferences.getPluginPendingHosts(context, pluginId))
    }
    var pendingHost by remember { mutableStateOf<String?>(null) }

    val unauthorized = hosts.filter { it !in authorized }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tr(R.string.s_wangluofangwen),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (unauthorized.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = tr(R.string.s_geyumingdaishouquan, unauthorized.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        hosts.forEach { host ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (host in pending) {
                        Text(
                            text = tr(R.string.s_fangwenbeiju_dengdaishouquan),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (host in authorized) {
                    Text(
                        text = tr(R.string.s_yishouquan),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = {
                        SettingsPreferences.revokePluginHost(context, pluginId, host)
                        authorized = authorized - host
                    }) {
                        Text(tr(R.string.s_chexiao), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(
                        text = tr(R.string.s_weishouquan),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = { pendingHost = host }) {
                        Text(
                            text = tr(R.string.s_shouquan),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    pendingHost?.let { host ->
        AlertDialog(
            onDismissRequest = { pendingHost = null },
            title = { Text(tr(R.string.s_shouquanwangluofangwen)) },
            text = { Text(tr(R.string.s_yunxuchajian_lianjie_bingfason_8adc0, pluginName, host)) },
            confirmButton = {
                TextButton(onClick = {
                    SettingsPreferences.authorizePluginHost(context, pluginId, host)
                    authorized = authorized + host
                    pending = pending - host
                    pendingHost = null
                }) {
                    Text(tr(R.string.s_yunxu), color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingHost = null }) {
                    Text(tr(R.string.s_quxiao))
                }
            }
        )
    }
}

// 信任徽标：返回 (标签, 颜色)
private fun trustBadge(level: TrustLevel): Pair<String, Color> {
    return when (level) {
        TrustLevel.TRUSTED -> Pair(tr(R.string.s_guanfang), Color(0xFF4CAF50))
        TrustLevel.THIRD_PARTY -> Pair(tr(R.string.s_disanfang), Color(0xFFF57C00))
        TrustLevel.UNKNOWN -> Pair(tr(R.string.s_weizhilaiyuan), Color(0xFFE53935))
    }
}

// 渲染插件图标：优先用插件提供的本地图标（文字或已提取到本地的资源文件），否则用分类默认图标
@Composable
fun PluginIconView(
    icon: PluginIcon?,
    category: PluginCategory,
    modifier: Modifier = Modifier,
    showBackground: Boolean = true
) {
    val bgModifier = if (showBackground) {
        modifier
            .size(36.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp)
            ).padding(4.dp)
    } else {
        modifier
    }
    val iconText = icon?.text
    if (!iconText.isNullOrBlank()) {
        Box(
            modifier = bgModifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = iconText,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
        }
        return
    }

    val assetPath = icon?.assetName
    if (assetPath != null) {
        val bitmap = remember(assetPath) {
            runCatching { BitmapFactory.decodeFile(assetPath) }.getOrNull()
        }
        if (bitmap != null) {
            val imageBitmap = remember(assetPath) { bitmap.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                modifier = bgModifier,
                contentScale = ContentScale.Crop
            )
            return
        }
    }

    Box(
        modifier = bgModifier,
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = getCategoryIcon(category),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun getCategoryIcon(category: PluginCategory): ImageVector = when (category) {
    PluginCategory.EMOJI -> Icons.Default.Face
    PluginCategory.ASR -> Icons.Default.Mic
    PluginCategory.PREDICTION -> Icons.Default.AutoAwesome
    PluginCategory.CLIPBOARD_SYNC -> Icons.Default.Sync
    PluginCategory.BACKUP -> Icons.Default.Backup
    PluginCategory.TOOL -> Icons.Default.AutoFixHigh
    PluginCategory.UNKNOWN -> Icons.Default.Extension
}

@Composable
private fun PluginErrorDialog(
    pluginId: String,
    pluginName: String,
    errors: List<PluginErrorLog.PluginError>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val pluginInfo = remember(pluginId) {
        PluginManager.getAllInstallPlugins().firstOrNull { it.id == pluginId }
    }
    val diagnosticText = remember(pluginId, errors) {
        buildPluginDiagnosticText(
            hostVersionName = BuildConfig.VERSION_NAME,
            pluginName = pluginName,
            pluginId = pluginId,
            pluginVersion = pluginInfo?.versionName ?: "?",
            enabled = pluginInfo?.enabled ?: true,
            errors = errors
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(tr(R.string.s_cuowurizhi, pluginName))
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
            ) {
                if (errors.isEmpty()) {
                    Text(
                        tr(R.string.s_zanwucuowujilu),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        tr(R.string.s_dianji_fuzhizhenduanxinxi_houz_55721),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        errors.forEachIndexed { index, error ->
                            PluginErrorCard(index = index, error = error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText(tr(R.string.s_xime_chajianzhenduanxinxi), diagnosticText))
                    Toast.makeText(context, tr(R.string.s_zhenduanxinxiyifuzhi_kezhantie_6d373), Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(tr(R.string.s_fuzhizhenduanxinxi))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) {
                    Text(tr(R.string.s_qingchurizhi), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) {
                    Text(tr(R.string.s_guanbi))
                }
            }
        }
    )
}

@Composable
private fun PluginErrorCard(index: Int, error: PluginErrorLog.PluginError) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#${index + 1} · ${formatErrorTime(error.timestamp)} · ${categoryLabel(error.category)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = PluginErrorLog.userMessage(error),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = PluginErrorLog.userHint(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            val stackTraceText = error.stackTrace
            if (stackTraceText != null && stackTraceText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stackTraceText.take(200) + "...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/** 分类徽章短名（用户可读）。 */
internal fun categoryLabel(category: ErrorCategory): String = when (category) {
    ErrorCategory.SCRIPT_ERROR -> tr(R.string.s_jiaobencuowu)
    ErrorCategory.NETWORK_DENIED -> tr(R.string.s_fangwenbeijujue)
    ErrorCategory.HTTP_ERROR -> tr(R.string.s_wangluoshibai)
    ErrorCategory.TIMEOUT_POISONED -> tr(R.string.s_yitingyong)
    ErrorCategory.TIMEOUT -> tr(R.string.s_chaoshi)
    ErrorCategory.STREAM_ERROR -> tr(R.string.s_lianjiezhongduan)
    ErrorCategory.OTHER -> tr(R.string.s_qita)
}

/** 插件错误入口文案（纯函数，可单测）。 */
internal fun pluginErrorSummary(count: Int): String = when {
    count <= 0 -> tr(R.string.s_zanwucuowujilu)
    else -> tr(R.string.s_you_tiaocuowujilu, count)
}

private fun formatErrorTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

/**
 * 组装一键反馈作者的诊断文本（含宿主/插件版本与全部错误详情）。
 * 纯函数，可单测。
 */
internal fun buildPluginDiagnosticText(
    hostVersionName: String,
    pluginName: String,
    pluginId: String,
    pluginVersion: String,
    enabled: Boolean,
    errors: List<PluginErrorLog.PluginError>
): String = buildString {
    appendLine("Xime v$hostVersionName")
    appendLine(tr(R.string.s_chajian_xinxi, pluginName, pluginId, pluginVersion, if (enabled) tr(R.string.s_yiqiyong) else tr(R.string.s_yijinyong)))
    appendLine()
    appendLine(tr(R.string.s_cuowujilu_tiao, errors.size))
    if (errors.isEmpty()) {
        appendLine(tr(R.string.s_wu))
    }
    errors.forEachIndexed { index, error ->
        appendLine("${index + 1}. ${formatErrorTime(error.timestamp)} [${categoryLabel(error.category)}]")
        appendLine("   ${PluginErrorLog.userMessage(error)}")
        appendLine("   ${PluginErrorLog.userHint(error)}")
        appendLine(tr(R.string.s_xiangqing_2, error.message))
        error.stackTrace?.let { stack ->
            appendLine(tr(R.string.s_duizhan))
            stack.lineSequence().take(60).forEach { line ->
                appendLine("     $line")
            }
        }
        appendLine()
    }
}