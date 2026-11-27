package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.PluginIcon
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.plugin.core.api.AsrPlugin
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.speech.AsrBackendFactory
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AsrProvider(
    val id: String,
    val name: String,
    val description: String,
    val iconRes: Int? = null,
    val icon: ImageVector? = null,
    val pluginIcon: PluginIcon? = null,
    val isOnline: Boolean,
    val isConfigured: Boolean,
    val isActive: Boolean = false,
    val features: List<String> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechToTextSettingsContent(
    onBack: () -> Unit,
    onNavigateToPluginSettings: (String) -> Unit = {},
    onNavigateToPlugins: () -> Unit = {},
    /** 跳转扩展商店的模型页（本地模型未安装时，模型卡片的去下载去处）。 */
    onNavigateToModelMarket: () -> Unit = {}
) {
    val context = LocalContext.current

    var activeAsrPluginId by remember {
        mutableStateOf(SettingsPreferences.getSttOnlinePluginId(context))
    }

    var useLocal by remember {
        mutableStateOf(OfflineAsrSettings.isSupported() && SettingsPreferences.isSttUseLocal(context))
    }

    var keepEngineAlive by remember {
        mutableStateOf(SettingsPreferences.isSttKeepEngineAlive(context))
    }

    // 在线插件列表在 IO 组装：isConfigured()（→ 插件 settings.schema）与 extractPluginIcon
    // 都会进插件运行时（QuickJS）。组合期同步调用既阻塞主线程，又会与插件卸载/重载的
    // runtime.close() 竞态（v3.1.0 真机 record："调用 settings.schema: Already closed"）。
    var onlineProviders by remember { mutableStateOf<List<AsrProvider>>(emptyList()) }
    var providersLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(activeAsrPluginId) {
        val built = runCatching {
            withContext(Dispatchers.IO) {
                ExtensionManager.getAllInstalledPlugins()
                    .filter { it.category == PluginCategory.ASR }
                    .map { info ->
                        val instance = PluginManager.getPluginInstance(info.id) as? AsrPlugin
                        if (instance != null) {
                            val caps = instance.getCapabilities()
                            AsrProvider(
                                id = info.id,
                                name = info.name,
                                description = tr(R.string.s_zaixianyuyinshibiechajian),
                                isOnline = true,
                                isConfigured = instance.isConfigured(),
                                isActive = info.id == activeAsrPluginId,
                                pluginIcon = ExtensionManager.extractPluginIcon(context, info.id, instance, info),
                                features = buildList {
                                    add(if (caps.inputMode == "streaming") tr(R.string.s_shishiliushi) else tr(R.string.s_wenjianshibie))
                                    if (caps.supportsPartialResults) add(tr(R.string.s_zhongjianjieguo))
                                    if (caps.requiresNetwork) add(tr(R.string.s_zaixian))
                                }
                            )
                        } else {
                            AsrProvider(
                                id = info.id,
                                name = info.name,
                                description = info.description.ifBlank { tr(R.string.s_zaixianyuyinshibiechajian) },
                                isOnline = true,
                                isConfigured = false,
                                isActive = info.id == activeAsrPluginId,
                                features = listOf(tr(R.string.s_zaixian))
                            )
                        }
                    }
            }
        }.getOrElse { emptyList() }
        onlineProviders = built
        providersLoaded = true
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_yuyinzhuanwenben)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = tr(R.string.s_fanhui)
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
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            val scope = rememberCoroutineScope()

            if (OfflineAsrSettings.isSupported()) {
                // 本地/在线引擎切换开关
                OfflineAsrSettings.EngineSelector(
                    useLocal = useLocal,
                    onUseLocalChange = {
                        useLocal = it
                        SettingsPreferences.setSttUseLocal(context, it)
                        if (it) {
                            // 打开本地识别：预热模型并常驻，避免语音时加载延迟丢开头音频
                            scope.launch(Dispatchers.IO) {
                                AsrBackendFactory.warmup(context)
                            }
                        } else {
                            // 关闭本地识别：卸载常驻模型
                            scope.launch(Dispatchers.IO) {
                                AsrBackendFactory.releaseModel()
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (useLocal) {
                    // 引擎常驻开关（仅本地模式显示/生效）：语音结束后保留引擎，闲置后再用免重建。
                    // 在线插件常驻需保持 WebSocket 长连接（耗电、占用服务端资源），不提供该选项。
                    // 注意：必须放在 ModelSection 之前——其内部 Column 为 fillMaxSize，
                    // 放在它后面会被挤出可视区
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tr(R.string.s_baochiyinqingchangzhu),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = tr(R.string.s_yuyinjieshuhoumoxingbuzidongsh_2ea8d),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                                )
                            }
                            Switch(
                                checked = keepEngineAlive,
                                onCheckedChange = {
                                    keepEngineAlive = it
                                    SettingsPreferences.setSttKeepEngineAlive(context, it)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // 本地模型下载/管理卡片（内部 fillMaxSize 自滚动，须放在本分支最后）
                    OfflineAsrSettings.ModelSection(onNavigateToDownload = onNavigateToModelMarket)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!useLocal) {
                if (!providersLoaded) {
                    // 首次组装还在 IO：给加载态，避免闪一下"未选择服务"
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    OnlineAsrTab(
                        providers = onlineProviders,
                        activeProviderId = activeAsrPluginId,
                        onProviderSelect = { provider ->
                            val wasConfigured = provider.isConfigured
                            scope.launch(Dispatchers.IO) {
                                // 单选激活：同一时间只能使用 1 个在线 ASR 插件
                                ExtensionManager.getAllInstalledPlugins()
                                    .filter { it.category == PluginCategory.ASR && it.id != provider.id }
                                    .forEach { SettingsPreferences.setPluginEnabled(context, it.id, false) }
                                SettingsPreferences.setSttOnlinePluginId(context, provider.id)
                                SettingsPreferences.setPluginEnabled(context, provider.id, true)
                                PluginManager.launchPlugin(provider.id)
                                activeAsrPluginId = provider.id
                                if (!wasConfigured) {
                                    withContext(Dispatchers.Main) {
                                        onNavigateToPluginSettings(provider.id)
                                    }
                                }
                            }
                        },
                        onManagePlugins = onNavigateToPlugins,
                        onSettings = onNavigateToPluginSettings
                    )
                }
            }
        }
    }
}

/**
 * 在线 ASR：只显示**当前使用**的服务（一行），切换走单选弹窗（[SettingsSingleChoiceDialog]）。
 * 服务商再多页面长度也恒定，选完即切换；未配置的服务切换后自动跳到它的配置页。
 */
@Composable
fun OnlineAsrTab(
    providers: List<AsrProvider>,
    activeProviderId: String,
    onProviderSelect: (AsrProvider) -> Unit,
    onManagePlugins: () -> Unit = {},
    onSettings: (String) -> Unit = {}
) {
    val activeProvider = providers.firstOrNull { it.id == activeProviderId }
    var showPicker by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = tr(R.string.s_zaixianyuyinshibiefuwushangzhi_1752c),
                style = MaterialTheme.typography.bodyMedium,
                color = settingsSecondaryColor(),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        item {
            SettingsSection(
                title = tr(R.string.s_dangqianfuwu),
                content = {
                    // 入口始终可见：只装 1 个服务时也能点开确认候选与配置状态
                    CurrentAsrProviderItem(
                        provider = activeProvider,
                        onSwitch = { showPicker = true },
                        onSettings = { activeProvider?.let { onSettings(it.id) } }
                    )
                }
            )
        }

        item {
            OutlinedButton(
                onClick = onManagePlugins,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(tr(R.string.s_guanlichajian))
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = tr(R.string.s_zaixian_asr_xuyaowangluolianji_2e59f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    if (showPicker) {
        SettingsSingleChoiceDialog(
            title = tr(R.string.s_xuanzeyuyinshibiefuwu),
            options = providers.map { provider ->
                SettingsChoiceOption(
                    id = provider.id,
                    title = provider.name,
                    subtitle = buildString {
                        append(provider.description)
                        if (provider.features.isNotEmpty()) {
                            append("\n")
                            append(provider.features.joinToString(" · "))
                        }
                        // 未配置的服务选中后会跳到配置页，这里先说明状态
                        append(if (provider.isConfigured) tr(R.string.s_nyipeizhi) else tr(R.string.s_nweipeizhi))
                    }
                )
            },
            selectedId = activeProviderId,
            onSelect = { pickedId ->
                showPicker = false
                providers.firstOrNull { it.id == pickedId }?.let(onProviderSelect)
            },
            onDismiss = { showPicker = false }
        )
    }
}

/**
 * 当前使用的在线识别服务（一行）：插件自己的图标 + 名称/描述 + 能力标签，
 * 右侧齿轮进配置页、整行可点打开服务选择弹窗。整行**始终可点**——只装一个服务时，
 * 这个入口是页面上唯一能确认候选与配置状态的地方。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrentAsrProviderItem(
    provider: AsrProvider?,
    onSwitch: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSwitch)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PluginIconView(
            icon = provider?.pluginIcon,
            category = PluginCategory.ASR
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider?.name ?: tr(R.string.s_weixuanzefuwu),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = provider?.description ?: tr(R.string.s_qingxuanzeyaoshiyongdezaixiany_07ac7),
                style = MaterialTheme.typography.bodySmall,
                color = settingsSecondaryColor()
            )
            if (!provider?.features.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                // 标签用 FlowRow 换行而不是挤在一行：行尾常驻「选择/切换」+ 箭头后，
                // 单行 Row 会把每个标签压窄、文字折成两行（观感"变形"）
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    provider.features.forEach { feature ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                        ) {
                            Text(
                                text = feature,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
        if (provider != null) {
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = tr(R.string.s_peizhi_3, provider.name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(
            text = if (provider == null) tr(R.string.s_xuanze) else tr(R.string.s_qiehuan),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
