package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardSyncSettingsContent(
    onBack: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToMarket: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember {
        mutableStateOf(SettingsPreferences.isClipboardSyncEnabled(context))
    }
    val syncPlugins = remember { ExtensionManager.getEnabledClipboardSyncPlugins(context) }
    // 偏好未设置时，自动选中第一个已启用的插件（与服务端 fallback 一致）
    var selectedPluginId by remember {
        mutableStateOf(
            SettingsPreferences.getClipboardSyncPluginId(context).ifEmpty {
                syncPlugins.firstOrNull()?.first ?: ""
            }
        )
    }
    // 当前选中的插件实例（切换时在 IO 协程里 launch 完成后更新，驱动表单立即渲染新插件）
    var activePlugin by remember {
        mutableStateOf(
            syncPlugins.firstOrNull { it.first == selectedPluginId } ?: syncPlugins.firstOrNull()
        )
    }
    val installedPlugins = remember { ExtensionManager.getAllInstalledPlugins() }
    val clipboardPlugins = remember { installedPlugins.filter { it.category == PluginCategory.CLIPBOARD_SYNC } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("剪贴板同步") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection(
                title = "同步开关",
                content = {
                    SettingsToggleItem(
                        icon = Icons.TwoTone.Sync,
                        title = "启用剪贴板同步",
                        subtitle = "将剪贴板文本与远端设备双向同步（拉取在启动及打开键盘/剪贴板面板时触发）",
                        checked = enabled,
                        onCheckedChange = { checked ->
                            enabled = checked
                            SettingsPreferences.setClipboardSyncEnabled(context, checked)
                        }
                    )
                }
            )

            if (enabled) {
                val plugin = syncPlugins.firstOrNull()
                if (plugin == null) {
                    SettingsSection(
                        title = "同步服务",
                        content = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "未启用剪贴板同步插件，请先在插件管理启用后再配置。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = onNavigateToPlugins,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("前往插件管理")
                                }
                            }
                        }
                    )
                } else {
                    SettingsSection(
                        title = "同步服务",
                        content = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (clipboardPlugins.isEmpty()) {
                                    Text(
                                        text = "未安装剪贴板同步插件，请先在扩展商店安装后再配置。",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(
                                        onClick = onNavigateToMarket,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("前往扩展商店")
                                    }
                                } else {
                                    clipboardPlugins.forEach { plugin ->
                                        val pluginId = plugin.id
                                        val isActive = pluginId == selectedPluginId
                                        val protocols = plugin.capabilities?.clipboardSync?.protocols.orEmpty()
                                        SettingsToggleItem(
                                            icon = Icons.TwoTone.Sync,
                                            title = plugin.name,
                                            subtitle = buildString {
                                                append(plugin.description)
                                                if (protocols.isNotEmpty()) {
                                                    append("\n同步协议: ")
                                                    append(protocols.joinToString("、"))
                                                }
                                            },
                                            checked = isActive,
                                            onCheckedChange = { checked ->
                                                if (checked && !isActive) {
                                                    selectedPluginId = pluginId
                                                    SettingsPreferences.setClipboardSyncPluginId(context, pluginId)
                                                    scope.launch(Dispatchers.IO) {
                                                        // 单选激活：同一时间只能运行 1 个剪贴板同步插件
                                                        clipboardPlugins
                                                            .filter { it.id != pluginId }
                                                            .forEach {
                                                                SettingsPreferences.setPluginEnabled(context, it.id, false)
                                                                com.kingzcheung.xime.plugin.core.runtime.PluginManager.unloadPlugin(it.id)
                                                            }
                                                        SettingsPreferences.setPluginEnabled(context, pluginId, true)
                                                        com.kingzcheung.xime.plugin.core.runtime.PluginManager.launchPlugin(pluginId)
                                                        // 重新获取已启用实例，驱动表单切换到新插件
                                                        val instance = ExtensionManager.getEnabledClipboardSyncPlugins(context)
                                                            .firstOrNull { it.first == pluginId }
                                                        activePlugin = instance
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                    activePlugin?.let { selected ->
                        val pluginId = selected.first
                        val pluginName = installedPlugins.find { it.id == pluginId }?.name ?: pluginId
                        PluginConfigFormScreen(
                            pluginId = pluginId,
                            plugin = selected.second,
                            pluginName = pluginName,
                            onBack = {},
                            embedded = true
                        )
                    }
                }
            }
        }
    }
}
