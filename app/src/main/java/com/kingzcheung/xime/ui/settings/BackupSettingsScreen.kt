package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.twotone.Backup
import androidx.compose.material.icons.twotone.CloudUpload
import androidx.compose.material.icons.twotone.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.plugin.ActivePluginSelection
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.BackupPlugin
import com.kingzcheung.xime.plugin.core.api.RemoteBackupEntry
import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.BackupManager
import com.kingzcheung.xime.settings.RimeExportManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 同步与备份设置页。
 *
 * 只有两条链路（引擎原生 sync 文本快照为共同底座，按时间戳合并不互相覆盖）：
 * - **词条同步**：自造词的增量备份——本机整理 + 云端多设备合并，一个主动作；
 * - **完整备份**：整机的时间点归档（设置/补丁 + 方案与资源 + 插件包 + 自造词快照），
 *   云端与本地出口同格；恢复是覆盖式，词条合并。
 *
 * 备份目标为已安装的 backup 类型插件（单选激活，与剪贴板同步同模式），
 * 包由宿主生成/恢复，服务器配置在所选插件的独立配置页。
 *
 * 交互设计（P0 重设计，见 docs/backup-ux-redesign.md）：
 * - **状态一眼可懂**：服务行给"已配置 / 未启用 / 未配置"三种引导态；两张卡右上给
 *   "进行中 / 失败 / 成功 / 从未"状态胶囊 + 相对时间（今天 12:30），不再只有颜色点；
 * - **失败留痕**：失败原因落盘（[SettingsPreferences.setLastSyncError]/[setLastBackupError]），
 *   回到本页仍是"上次失败：原因 + 重试"，snackbar 只做即时反馈；
 * - **一屏两个主任务**：`立即同步` / `立即备份`，低频与危险入口（导入导出词条、
 *   保存到本机、从文件恢复）集中到页尾「更多」段，不再是两个弹层；
 * - **只禁用相关操作**：拉云端列表是只读轻操作，不再整页禁用（`blocking`）；
 * - 删除云端备份先确认（不可恢复），恢复确认里明示覆盖语义。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsContent(
    onBack: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToMarket: () -> Unit,
    /** 打开所选备份服务的配置页（独立页面，与插件中心同一入口） */
    onNavigateToPluginSettings: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val installedPlugins = remember { ExtensionManager.getAllInstalledPlugins() }
    val backupPlugins = remember { installedPlugins.filter { it.category == PluginCategory.BACKUP } }
    val syncPlugins = remember { ExtensionManager.getEnabledBackupPlugins(context) }
    // 与插件管理页/引擎同一判定规则（ActivePluginSelection）：偏好为空或指向未启用插件时回退首个已启用项
    var selectedPluginId by remember {
        mutableStateOf(
            ActivePluginSelection.resolve(
                SettingsPreferences.getBackupPluginId(context),
                syncPlugins.map { it.first }
            )
        )
    }
    var activePlugin by remember {
        mutableStateOf(
            syncPlugins.firstOrNull { it.first == selectedPluginId } ?: syncPlugins.firstOrNull()
        )
    }
    // 当前进行中的操作标识（"syncAll"/"backup"/"list"/"restore:<id>"/"delete:<id>"/…）：
    // 按操作独立；loading 除本操作按钮内圈外，还在所属卡片给一条细进度线
    var busyOp by remember { mutableStateOf<String?>(null) }
    var showServicePicker by remember { mutableStateOf(false) }
    var remoteListExpanded by remember { mutableStateOf(false) }
    var snapshotListExpanded by remember { mutableStateOf(false) }
    // 恢复与删除都先确认：恢复=覆盖式落盘，删除=远端不可恢复
    var pendingRestoreEntry by remember { mutableStateOf<RemoteBackupEntry?>(null) }
    var pendingDeleteEntry by remember { mutableStateOf<RemoteBackupEntry?>(null) }
    var pendingLocalImportUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // 词条同步：上次时间 + 云端各设备快照（只读；合并由主动作完成）
    var lastSyncAt by remember { mutableStateOf(SettingsPreferences.getLastRimeSyncAt(context)) }
    var lastBackupAt by remember { mutableStateOf(SettingsPreferences.getLastBackupAt(context)) }
    var syncRemoteList by remember { mutableStateOf<List<RemoteBackupEntry>?>(null) }
    // 云端完整备份列表（null=未拉取；词条快照条目在拉取点过滤，这里只管备份包）
    var remoteList by remember { mutableStateOf<List<RemoteBackupEntry>?>(null) }
    // 失败状态（F1）：持久化，回到本页仍能看到"上次失败 + 重试"，不再只靠 snackbar 一闪而过
    var syncError by remember { mutableStateOf(SettingsPreferences.getLastSyncError(context)) }
    var backupError by remember { mutableStateOf(SettingsPreferences.getLastBackupError(context)) }

    // 进页面静默拉一次远端（IO）：主按钮要显示"云端 N 份 / N 台设备"，也省掉"点开才拉取"
    LaunchedEffect(activePlugin) {
        val plugin = activePlugin?.second ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            syncRemoteList = SyncManager.listRemoteSnapshots(plugin)
            remoteList = BackupManager.listRemote(plugin)?.filter { !SyncManager.isRemoteSyncEntry(it.name) }
        }
    }

    // 配置就绪（IO 读取；组合期不调插件运行时）：null=读取中
    val configured = rememberPluginConfigured(activePlugin?.second, activePlugin?.first ?: "")
    // 进行中的操作是否阻塞其他主动作：拉列表是只读轻操作，不阻塞（旧实现整页禁用）
    val blocking = busyOp != null && busyOp != "list"
    val serviceReady = activePlugin != null && configured != false

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busyOp = "import"
        scope.launch(Dispatchers.IO) {
            val result = SyncManager.importSnapshots(context, uris.toList())
            withContext(Dispatchers.Main) {
                busyOp = null
                lastSyncAt = SettingsPreferences.getLastRimeSyncAt(context)
                snackbar.showSnackbar(
                    result.fold(
                        onSuccess = { r ->
                            buildString {
                                if (r.snapshotFiles > 0) {
                                    append(tr(R.string.s_yidaoru_gekuaizhao, r.snapshotFiles))
                                    if (r.merged) append(tr(R.string.s_bingwanchenghebing))
                                }
                                if (r.importedEntries > 0) {
                                    if (isNotEmpty()) append("；")
                                    append(tr(R.string.s_yidaoru_tiaocitiao, r.importedEntries))
                                }
                            }
                        },
                        onFailure = { tr(R.string.s_daorushibai_2, it.message) }
                    )
                )
            }
        }
    }

    val localPackageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingLocalImportUri = uri
    }

    //词条区主动作：本机整理（引擎原生 sync）+ 已配置服务时顺带云端多设备合并
    fun runWordSync() {
        val plugin = activePlugin?.second
        busyOp = "syncAll"
        scope.launch(Dispatchers.IO) {
            val result: Result<Int> = if (plugin != null) {
                SyncManager.remoteSync(context, plugin)
            } else {
                SyncManager.syncNow(context).map { 0 }
            }
            val fresh = plugin?.let { SyncManager.listRemoteSnapshots(it) }
            withContext(Dispatchers.Main) {
                busyOp = null
                if (fresh != null) syncRemoteList = fresh
                lastSyncAt = SettingsPreferences.getLastRimeSyncAt(context)
                // 成功清空、失败落盘：页面状态与 snackbar 同一口径
                val error = result.exceptionOrNull()?.message ?: tr(R.string.s_weizhicuowu)
                SettingsPreferences.setLastSyncError(context, if (result.isSuccess) null else error)
                syncError = if (result.isSuccess) null else error
                snackbar.showSnackbar(
                    result.fold(
                        onSuccess = { pulled ->
                            when {
                                plugin == null -> tr(R.string.s_yizhenglibenjicitiao_weipeizhi_e3eb7)
                                pulled > 0 -> tr(R.string.s_yihebing_taishebeidecitiao_bin_54620, pulled)
                                else -> tr(R.string.s_yishangchuanbenjikuaizhao_yund_eb9ee)
                            }
                        },
                        onFailure = { tr(R.string.s_tongbushibai, error) }
                    )
                )
            }
        }
    }

    //备份区主动作：刷新词条快照 → 打整机完整包 → 经备份插件上云（流式，不占内存）
    fun runCloudBackup() {
        val plugin = activePlugin?.second ?: return
        busyOp = "backup"
        scope.launch(Dispatchers.IO) {
            val result = BackupManager.backupNow(context, plugin)
            withContext(Dispatchers.Main) {
                busyOp = null
                if (result.ok) {
                    lastBackupAt = SettingsPreferences.getLastBackupAt(context)
                    SettingsPreferences.setLastBackupError(context, null)
                    backupError = null
                } else {
                    val error = result.message ?: tr(R.string.s_weizhicuowu)
                    SettingsPreferences.setLastBackupError(context, error)
                    backupError = error
                }
                snackbar.showSnackbar(
                    if (result.ok) tr(R.string.s_beifenwancheng) + (result.message ?: "")
                    else tr(R.string.s_beifenshibai_yuanyin, result.message ?: tr(R.string.s_weizhicuowu))
                )
            }
        }
    }

    //云端恢复：覆盖同名文件，词条快照由引擎就绪后按时间戳合并
    fun runRestore(entry: RemoteBackupEntry) {
        val plugin = activePlugin?.second ?: return
        busyOp = "restore:${entry.id}"
        scope.launch(Dispatchers.IO) {
            val result = BackupManager.restore(context, plugin, entry.id)
            withContext(Dispatchers.Main) {
                busyOp = null
                lastSyncAt = SettingsPreferences.getLastRimeSyncAt(context)
                snackbar.showSnackbar(
                    if (result.isSuccess) tr(R.string.s_huifuwancheng_zhongqiyingyongh_d8f09)
                    else tr(R.string.s_huifushibai, result.exceptionOrNull()?.message)
                )
            }
        }
    }

    fun runDeleteRemote(entry: RemoteBackupEntry) {
        val plugin = activePlugin?.second ?: return
        busyOp = "delete:${entry.id}"
        scope.launch(Dispatchers.IO) {
            val ok = BackupManager.deleteRemote(plugin, entry.id)
            val fresh = if (ok) BackupManager.listRemote(plugin)?.filter { !SyncManager.isRemoteSyncEntry(it.name) } else null
            withContext(Dispatchers.Main) {
                busyOp = null
                if (ok) remoteList = fresh
                snackbar.showSnackbar(if (ok) tr(R.string.s_yishanchugaibeifen) else tr(R.string.s_shanchushibai))
            }
        }
    }

    // —— 刷新云端备份列表（过滤掉词条快照条目，它们属于词条区）——
    fun refreshRemoteList() {
        val plugin = activePlugin?.second ?: return
        busyOp = "list"
        scope.launch(Dispatchers.IO) {
            val list = BackupManager.listRemote(plugin)?.filter { !SyncManager.isRemoteSyncEntry(it.name) }
            val err = if (list == null) tr(R.string.s_huoqubeifenliebiaoshibai) else null
            withContext(Dispatchers.Main) {
                busyOp = null
                remoteList = list
                err?.let { snackbar.showSnackbar(it) }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_tongbuyubeifen)) },
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---------- 备份服务（词条同步与云端备份共用的通道） ----------
            SettingsSection(
                title = tr(R.string.s_beifenfuwu),
                content = {
                    when {
                        backupPlugins.isEmpty() -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.TwoTone.Backup,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = tr(R.string.s_weianzhuangbeifenchajian_yundu_93b89),
                                style = MaterialTheme.typography.bodyMedium,
                                color = settingsSecondaryColor()
                            )
                            Button(
                                onClick = onNavigateToMarket,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(tr(R.string.s_qianwangkuozhanshangdian))
                            }
                        }

                        // 未启用 / 未配置：给"去哪、做什么"的引导，而不是把主按钮灰着不说话
                        activePlugin == null -> ServiceGuideRow(
                            title = tr(R.string.s_beifenchajianyianzhuangdanweiq_4040f),
                            subtitle = tr(R.string.s_qiyonghoucainengyunduantongbuy_c2ca1),
                            actionText = tr(R.string.s_quchajianzhongxin),
                            onClick = onNavigateToPlugins
                        )

                        configured == false -> ServiceGuideRow(
                            title = tr(R.string.s_haimeitianfuwuqihezhanghao),
                            subtitle = tr(R.string.s_tianwancainengyunduantongbuyub_96d80),
                            actionText = tr(R.string.s_qupeizhi),
                            onClick = { activePlugin?.first?.let(onNavigateToPluginSettings) }
                        )

                        else -> CurrentBackupServiceItem(
                            pluginInfo = installedPlugins.find { it.id == activePlugin?.first },
                            pluginId = activePlugin?.first,
                            plugin = activePlugin?.second,
                            configured = configured,
                            lastSuccessAt = maxOf(lastSyncAt, lastBackupAt),
                            onClick = { showServicePicker = true },
                            onConfigure = activePlugin?.first?.let { id ->
                                { onNavigateToPluginSettings(id) }
                            }
                        )
                    }
                }
            )

            // ---------- 卡片一：用户词库同步（一个主动作） ----------
            SettingsSection(
                title = tr(R.string.s_yonghuciku),
                modifier = Modifier.animateContentSize(),
                content = {
                    FeatureCardHeader(
                        title = tr(R.string.s_yonghucikutongbu),
                        // 一句话自适应：没成功过讲"做什么"，成功过讲"上次成功 + 云端规模"
                        infoText = if (lastSyncAt > 0) buildString {
                            append(tr(R.string.s_shangcichenggong) + formatRelativeTime(lastSyncAt))
                            syncRemoteList?.let { append(tr(R.string.s_yunduan_taishebei, it.size)) }
                        } else {
                            tr(R.string.s_duotaishebeizidonghebing_xinci_80a28)
                        },
                        badgeText = if (busyOp == "syncAll") tr(R.string.s_jinxingzhong)
                        else if (syncError != null) tr(R.string.s_shibai)
                        else if (lastSyncAt > 0) tr(R.string.s_chenggong) else tr(R.string.s_congwei),
                        badgeTone = if (busyOp == "syncAll") StatusTone.Busy
                        else if (syncError != null) StatusTone.Fail
                        else if (lastSyncAt > 0) StatusTone.Ok else StatusTone.Never,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                    WordCardActions(
                        busyOp = busyOp,
                        blocking = blocking,
                        error = syncError,
                        onSync = ::runWordSync,
                        snapshotList = syncRemoteList,
                        expanded = snapshotListExpanded,
                        onToggleExpanded = { snapshotListExpanded = !snapshotListExpanded }
                    )
                }
            )

            // ---------- 卡片二：完整备份（一个主动作） ----------
            SettingsSection(
                title = tr(R.string.s_wanzhengbeifen),
                modifier = Modifier.animateContentSize(),
                content = {
                    FeatureCardHeader(
                        title = tr(R.string.s_beifenyuhuifu),
                        infoText = if (lastBackupAt > 0) buildString {
                            append(tr(R.string.s_shangcichenggong) + formatRelativeTime(lastBackupAt))
                            remoteList?.let { append(tr(R.string.s_yunduan_fen, it.size)) }
                        } else {
                            tr(R.string.s_shezhi_shurufangan_chajian_yic_4220a)
                        },
                        badgeText = if (busyOp == "backup") tr(R.string.s_jinxingzhong)
                        else if (backupError != null) tr(R.string.s_shibai)
                        else if (lastBackupAt > 0) tr(R.string.s_chenggong) else tr(R.string.s_congwei),
                        badgeTone = if (busyOp == "backup") StatusTone.Busy
                        else if (backupError != null) StatusTone.Fail
                        else if (lastBackupAt > 0) StatusTone.Ok else StatusTone.Never,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                    BackupCardActions(
                        busyOp = busyOp,
                        blocking = blocking,
                        serviceReady = serviceReady,
                        error = backupError,
                        onBackup = ::runCloudBackup,
                        onConfigure = { activePlugin?.first?.let(onNavigateToPluginSettings) },
                        onRefresh = ::refreshRemoteList,
                        remoteList = remoteList,
                        expanded = remoteListExpanded,
                        onToggleExpanded = {
                            remoteListExpanded = !remoteListExpanded
                            if (remoteListExpanded) refreshRemoteList()
                        },
                        onRestore = { pendingRestoreEntry = it },
                        onDelete = { pendingDeleteEntry = it }
                    )
                }
            )

            // ---------- 更多：低频与危险入口集中（原两个弹层） ----------
            SettingsSection(
                title = tr(R.string.s_gengduo),
                content = {
                    SettingsItem(
                        icon = Icons.Outlined.FileDownload,
                        title = tr(R.string.s_daorucitiao_kuaizhao_mabiao),
                        subtitle = tr(R.string.s_qita_rime_qianduandekuaizhaohu_f8cc8),
                        badgeText = if (busyOp == "import") tr(R.string.s_jinxingzhong) else null,
                        showArrow = true,
                        onClick = { if (busyOp == null) importLauncher.launch(arrayOf("*/*")) }
                    )
                    SettingsItem(
                        icon = Icons.Outlined.FileUpload,
                        title = tr(R.string.s_daochucitiaodaoxiazaimulu),
                        subtitle = tr(R.string.s_geiqita_rime_qianduanyong_jiey_bc0a9),
                        badgeText = if (busyOp == "export") tr(R.string.s_jinxingzhong) else null,
                        showArrow = true,
                        onClick = {
                            if (busyOp == null) {
                                busyOp = "export"
                                scope.launch(Dispatchers.IO) {
                                    val result = SyncManager.exportToDownloads(context)
                                    withContext(Dispatchers.Main) {
                                        busyOp = null
                                        snackbar.showSnackbar(
                                            result.fold(
                                                onSuccess = { tr(R.string.s_yibaocundaoxiazaimulu, it) },
                                                onFailure = { tr(R.string.s_daochushibai, it.message) }
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    )
                    SettingsItem(
                        icon = Icons.Outlined.SaveAlt,
                        title = tr(R.string.s_baocunwanzhengbeifendaoxiazaimulu),
                        subtitle = tr(R.string.s_huanji_lixiankeyong_yuyunduant_78157),
                        badgeText = if (busyOp == "exportLocal") tr(R.string.s_jinxingzhong) else null,
                        showArrow = true,
                        onClick = {
                            if (busyOp == null) {
                                busyOp = "exportLocal"
                                scope.launch(Dispatchers.IO) {
                                    val result = RimeExportManager.exportArchive(context)
                                    withContext(Dispatchers.Main) {
                                        busyOp = null
                                        if (result.isSuccess) {
                                            lastBackupAt = SettingsPreferences.getLastBackupAt(context)
                                        }
                                        snackbar.showSnackbar(
                                            result.fold(
                                                onSuccess = { tr(R.string.s_yidaochuwanzhengbeifendaoxiaza_c8a5d, it.fileName) },
                                                onFailure = { tr(R.string.s_daochushibai, it.message) }
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    )
                    SettingsItem(
                        icon = Icons.Outlined.SettingsBackupRestore,
                        title = tr(R.string.s_congwenjianhuifuwanzhengbeifen),
                        subtitle = tr(R.string.s_fugaishihuifu_huixiandanqueren),
                        showArrow = true,
                        onClick = { if (busyOp == null) localPackageLauncher.launch(arrayOf("*/*")) }
                    )
                }
            )
        }
    }

    if (showServicePicker) {
        // 切换备份服务：单选弹窗（点行即切换并关闭）
        SettingsSingleChoiceDialog(
            title = tr(R.string.s_xuanzebeifenfuwu),
            options = backupPlugins.map { plugin ->
                val protocols = plugin.capabilities?.backup?.protocols.orEmpty()
                SettingsChoiceOption(
                    id = plugin.id,
                    title = plugin.name,
                    subtitle = buildString {
                        append(plugin.description)
                        if (protocols.isNotEmpty()) {
                            append(tr(R.string.s_nbeifenxieyi))
                            append(protocols.joinToString("、"))
                        }
                    }
                )
            },
            selectedId = selectedPluginId,
            onSelect = { pickedId ->
                showServicePicker = false
                if (pickedId != selectedPluginId) {
                    selectedPluginId = pickedId
                    SettingsPreferences.setBackupPluginId(context, pickedId)
                    remoteList = null
                    // 换了服务：上一家的失败不再代表当前服务，清掉留痕
                    SettingsPreferences.setLastSyncError(context, null)
                    SettingsPreferences.setLastBackupError(context, null)
                    syncError = null
                    backupError = null
                    scope.launch(Dispatchers.IO) {
                        // 单选激活：同一时间只启用 1 个备份插件
                        backupPlugins
                            .filter { it.id != pickedId }
                            .forEach {
                                SettingsPreferences.setPluginEnabled(context, it.id, false)
                                PluginManager.unloadPlugin(it.id)
                            }
                        SettingsPreferences.setPluginEnabled(context, pickedId, true)
                        PluginManager.launchPlugin(pickedId)
                        // 重新获取已启用实例，驱动配置页入口与远端列表切换到新插件
                        activePlugin = ExtensionManager.getEnabledBackupPlugins(context)
                            .firstOrNull { it.first == pickedId }
                    }
                }
            },
            onDismiss = { showServicePicker = false }
        )
    }

    // 删除云端备份：不可恢复，先确认
    pendingDeleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDeleteEntry = null },
            title = { Text(tr(R.string.s_shanchuyunduanbeifen)) },
            text = { Text(tr(R.string.s_jiangyongjiushanchu_shanchuhou_95640, entry.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteEntry = null
                    runDeleteRemote(entry)
                }) {
                    Text(tr(R.string.s_shanchu), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteEntry = null }) { Text(tr(R.string.s_quxiao)) }
            }
        )
    }

    // 云端恢复：覆盖式（自造词合并），先确认
    pendingRestoreEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingRestoreEntry = null },
            title = { Text(tr(R.string.s_huifudaobenji)) },
            text = {
                Text(
                    tr(R.string.s_ba_fugaidaobenji_huifutongming_4a142, entry.name) +
                        tr(R.string.s_zizaocianshijianchuohebing_ben_b0e5d)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestoreEntry = null
                    runRestore(entry)
                }) { Text(tr(R.string.s_huifu)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreEntry = null }) { Text(tr(R.string.s_quxiao)) }
            }
        )
    }

    // 本地完整方案包导入：覆盖式恢复，先确认再解包
    pendingLocalImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingLocalImportUri = null },
            title = { Text(tr(R.string.s_congbendihuifuwanzhengbeifen)) },
            text = {
                Text(
                    tr(R.string.s_huifugai_rime_muluxiatongmingp_8d548) +
                        tr(R.string.s_zizaocianshijianchuohebing_buh_d11d6)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingLocalImportUri = null
                        busyOp = "importLocal"
                        scope.launch(Dispatchers.IO) {
                            val result = BackupManager.importLocal(context, uri)
                            withContext(Dispatchers.Main) {
                                busyOp = null
                                lastSyncAt = SettingsPreferences.getLastRimeSyncAt(context)
                                snackbar.showSnackbar(
                                    result.fold(
                                        onSuccess = { tr(R.string.s_yicongbendihuifu_zhongqiyingyo_6fcd1) },
                                        onFailure = { tr(R.string.s_daorushibai_2, it.message) }
                                    )
                                )
                            }
                        }
                    }
                ) { Text(tr(R.string.s_daoru)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingLocalImportUri = null }) { Text(tr(R.string.s_quxiao)) }
            }
        )
    }
}

/** 云端条目行用的绝对时间（列表里要能对得上文件）；卡片状态用相对时间见 [formatRelativeTime]。 */
private fun formatBackupTime(at: Long): String =
    if (at > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(at)) else tr(R.string.s_congwei)

/**
 * 相对时间：今天/昨天给到分钟，本年给月日，跨年给年月日。
 * "上次成功 今天 12:30"比"2026-10-10 12:30"更像人话；[now] 可注入以便测试。
 */
internal fun formatRelativeTime(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0) return tr(R.string.s_congwei)
    val cal = Calendar.getInstance().apply { timeInMillis = at }
    val nowCal = Calendar.getInstance().apply { timeInMillis = now }
    val sameYear = cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    if (sameYear && cal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)) return tr(R.string.s_jintian, time)
    val yesterdayCal = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -1)
    }
    if (cal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
    ) {
        return tr(R.string.s_zuotian, time)
    }
    val pattern = if (sameYear) "MM-dd HH:mm" else "yyyy-MM-dd"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(at))
}

/** 状态语义色（成功/失败/进行中/从未）；不靠颜色点表达状态，胶囊自带文字。 */
private enum class StatusTone { Ok, Fail, Busy, Never }

/** 状态胶囊：语义色 + 文字，TalkBack 可读。 */
@Composable
private fun StatusBadge(text: String, tone: StatusTone) {
    val container = when (tone) {
        StatusTone.Ok -> MaterialTheme.colorScheme.primaryContainer
        StatusTone.Fail -> MaterialTheme.colorScheme.errorContainer
        StatusTone.Busy -> MaterialTheme.colorScheme.secondaryContainer
        StatusTone.Never -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when (tone) {
        StatusTone.Ok -> MaterialTheme.colorScheme.onPrimaryContainer
        StatusTone.Fail -> MaterialTheme.colorScheme.onErrorContainer
        StatusTone.Busy -> MaterialTheme.colorScheme.onSecondaryContainer
        StatusTone.Never -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = container, contentColor = content) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * 功能卡头部：标题 + 状态胶囊一行，下面一行"最有用的那句话"。
 *
 * 不做 40dp 图标底：图标只占一行高度，却要吃掉 52dp 横向空间（还带一圈底色），
 * 把标题与状态挤成多行。卡片的视觉锚点交给主按钮的图标（立即同步 / 立即备份），
 * 分组语义交给 [SettingsSection] 标题。
 *
 * 第二行按状态自适应，永远只有一句话：还没成功过时讲"这个功能做什么"，
 * 成功过之后讲"上次成功 + 云端规模"——避免副标题与状态各占一行、信息挤在一起。
 */
@Composable
private fun FeatureCardHeader(
    title: String,
    infoText: String,
    badgeText: String,
    badgeTone: StatusTone,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // weight 让标题最后测量：胶囊先拿到完整宽度，标题用省略号而不是换行
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(modifier = Modifier.width(8.dp))
            StatusBadge(text = badgeText, tone = badgeTone)
        }
        Text(
            text = infoText,
            style = MaterialTheme.typography.bodySmall,
            color = settingsSecondaryColor()
        )
    }
}

/** 上次失败提示行：原因 + 重试（失败不再只是 snackbar 一闪而过）。 */
@Composable
private fun FailureRow(message: String, enabled: Boolean, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = tr(R.string.s_shangcishibai, message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry, enabled = enabled) { Text(tr(R.string.s_zhongshi)) }
    }
}

/** 服务引导行（未启用 / 未配置）：说清"做什么、去哪做"，替代"灰按钮 + 一行小字"。 */
@Composable
private fun ServiceGuideRow(
    title: String,
    subtitle: String,
    actionText: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = settingsSecondaryColor()
            )
        }
        Text(
            text = actionText,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

/** 词条卡主体：主动作 + 失败重试 + 云端设备快照展开区。 */
@Composable
private fun WordCardActions(
    busyOp: String?,
    blocking: Boolean,
    error: String?,
    onSync: () -> Unit,
    snapshotList: List<RemoteBackupEntry>?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (busyOp == "syncAll") {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
        if (error != null) {
            FailureRow(message = error, enabled = !blocking, onRetry = onSync)
        }
        Button(
            onClick = onSync,
            enabled = !blocking,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busyOp == "syncAll") {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.TwoTone.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(tr(R.string.s_lijitongbu), modifier = Modifier.padding(start = 8.dp))
        }
        // 云端各设备快照：默认收起，每台设备一份固定名包；只读
        if (snapshotList != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !blocking, onClick = onToggleExpanded)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr(R.string.s_yunduanshebeikuaizhao_tai, snapshotList.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = settingsSecondaryColor(),
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (expanded) {
                if (snapshotList.isEmpty()) {
                    Text(
                        text = tr(R.string.s_yunduanhaimeiyoukuaizhao_tongb_8b2ce),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    snapshotList.forEach { entry -> RemoteEntryMeta(entry, kbUnit = true) }
                }
            }
        }
    }
}

/** 备份卡主体：主动作 + 失败重试 + 云端记录展开区（恢复/删除）。 */
@Composable
private fun BackupCardActions(
    busyOp: String?,
    blocking: Boolean,
    serviceReady: Boolean,
    error: String?,
    onBackup: () -> Unit,
    onConfigure: () -> Unit,
    onRefresh: () -> Unit,
    remoteList: List<RemoteBackupEntry>?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRestore: (RemoteBackupEntry) -> Unit,
    onDelete: (RemoteBackupEntry) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (busyOp == "backup") {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
        if (!serviceReady) {
            // 不是"灰按钮不说话"：说清为什么不可用 + 一键去配置
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr(R.string.s_xianpeizhibeifenfuwucainengyun_ff570),
                    style = MaterialTheme.typography.bodySmall,
                    color = settingsSecondaryColor(),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onConfigure, enabled = !blocking) { Text(tr(R.string.s_qupeizhi)) }
            }
        }
        if (error != null) {
            FailureRow(message = error, enabled = !blocking, onRetry = onBackup)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onBackup,
                modifier = Modifier.weight(1f),
                enabled = !blocking && serviceReady
            ) {
                if (busyOp == "backup") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.TwoTone.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Text(
                    text = tr(R.string.s_lijibeifen),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            OutlinedButton(
                onClick = onToggleExpanded,
                modifier = Modifier.weight(1f),
                // 拉列表是只读轻操作：只禁用它自己，不阻塞页面其他动作
                enabled = busyOp == null || busyOp == "list"
            ) {
                if (busyOp == "list") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = buildString {
                            append(if (expanded) tr(R.string.s_shouqijilu) else tr(R.string.s_beifenjilu))
                            remoteList?.let { append(" ${it.size}") }
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
        // 云端备份包列表（恢复=覆盖式，自造词按时间戳合并）
        if (expanded && remoteList == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr(R.string.s_meiladaobeifenliebiao_jianchaw_d63c8),
                    style = MaterialTheme.typography.bodySmall,
                    color = settingsSecondaryColor(),
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onRefresh,
                    enabled = busyOp == null || busyOp == "list"
                ) { Text(tr(R.string.s_zhongshi)) }
            }
        }
        if (expanded && remoteList != null) {
            if (remoteList.isEmpty()) {
                Text(
                    text = tr(R.string.s_haimeiyoubeifen_dian_lijibeife_c3bae),
                    style = MaterialTheme.typography.bodyMedium,
                    color = settingsSecondaryColor()
                )
            }
            remoteList.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        // 远端文件名可能很长（含时间戳/设备 id）：单行省略，别把行高撑成两行
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        RemoteEntryMetaRow(entry, mbUnit = true)
                    }
                    TextButton(
                        onClick = { onRestore(entry) },
                        enabled = !blocking
                    ) {
                        if (busyOp == "restore:${entry.id}") {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(tr(R.string.s_huifu))
                        }
                    }
                    IconButton(
                        onClick = { onDelete(entry) },
                        enabled = !blocking && busyOp != "delete:${entry.id}"
                    ) {
                        if (busyOp == "delete:${entry.id}") {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = tr(R.string.s_shanchu),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 云端条目的两行信息（词条区/备份区共用）。 */
@Composable
private fun RemoteEntryMeta(entry: RemoteBackupEntry, kbUnit: Boolean) {
    Column {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        RemoteEntryMetaRow(entry, mbUnit = !kbUnit)
    }
}

@Composable
private fun RemoteEntryMetaRow(entry: RemoteBackupEntry, mbUnit: Boolean) {
    Text(
        text = buildString {
            if (entry.createdAt > 0) {
                append(formatBackupTime(entry.createdAt))
            }
            if (entry.size >= 0) {
                if (isNotEmpty()) append(" · ")
                append(
                    if (mbUnit) String.format(Locale.getDefault(), "%.1f MB", entry.size / 1024.0 / 1024.0)
                    else String.format(Locale.getDefault(), "%.1f KB", entry.size / 1024.0)
                )
            }
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline
    )
}

/**
 * 当前生效的备份服务（一行）：图标 + 名称 + 状态胶囊（已配置/检查中）+ 上次成功时间 + 协议。
 *
 * 切换入口收进对话框（[SettingsSingleChoiceDialog] 的等价内联实现）：插件多时页面长度恒定，
 * 这里只回答"现在用的是谁、能不能用、上次什么时候成过"。整行**始终可点**——只装一个插件时，
 * 这个入口是页面上唯一能确认候选与协议的地方，藏掉它会让"当前生效的是谁"无处可查。
 */
@Composable
private fun CurrentBackupServiceItem(
    pluginInfo: PluginInfo?,
    pluginId: String?,
    plugin: BackupPlugin?,
    /** 配置就绪：null=读取中（不显示胶囊，避免误报） */
    configured: Boolean?,
    /** 最近一次云端操作成功时间（0=还没有成功过） */
    lastSuccessAt: Long,
    onClick: () -> Unit,
    /** 非空时在行内提供「配置」入口（打开独立配置页） */
    onConfigure: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val icon = remember(pluginId, plugin) {
        if (pluginId == null || plugin == null) null
        else ExtensionManager.extractPluginIcon(context, pluginId, plugin, pluginInfo)
    }
    val protocols = pluginInfo?.capabilities?.backup?.protocols.orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PluginIconView(
            icon = icon,
            category = PluginCategory.BACKUP
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 名字必须带 weight：Row 先测量非 weight 子项，若名字先吃满宽度，
                // 后面的状态胶囊只能拿到几 dp —— 会被压成"一条竖着的色条"（已配置三字逐字换行）
                Text(
                    text = pluginInfo?.name ?: pluginId ?: tr(R.string.s_weixuanzebeifenfuwu),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Text(
                text = if (lastSuccessAt > 0) {
                    tr(R.string.s_shangcichenggong) + formatRelativeTime(lastSuccessAt)
                } else {
                    tr(R.string.s_haimeiyouchenggongguo)
                },
                style = MaterialTheme.typography.bodySmall,
                color = settingsSecondaryColor()
            )
            if (protocols.isNotEmpty()) {
                Text(
                    text = tr(R.string.s_beifenxieyi) + protocols.joinToString("、"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (onConfigure != null) {
            ServiceConfigChip(configured = configured, onClick = onConfigure)
            Spacer(modifier = Modifier.width(4.dp))
        }
        // 「切换」文字去掉：整行可点 + 右侧 chevron 已表达"点开换服务"，
        // 省下的宽度留给插件名
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = tr(R.string.s_qiehuanbeifenfuwu),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 服务行右侧的"状态 + 配置"合一入口：一个可点的状态胶囊，点它就是打开配置页。
 *
 * 旧版是「已配置」胶囊 + 「配置」文字按钮两个元素，同一件事占两份横向空间；
 * 配置状态还没读出来（null）时显示中性的「配置」，不误报"已配置"。
 */
@Composable
private fun ServiceConfigChip(configured: Boolean?, onClick: () -> Unit) {
    val ready = configured == true
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = if (ready) tr(R.string.s_yipeizhi) else tr(R.string.s_peizhi),
                style = MaterialTheme.typography.labelLarge
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        // 两款都是"填充款"，因此必须显式去掉描边：M3 的 AssistChip 默认是 outlined
        // （透明底 + 1dp outline），只改容器色会得到"填充 + 描边"这个规范里不存在的组合。
        // 已配置 = primaryContainer 填充（状态语义）；状态未读出 = 中性填充，不误报已配置。
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHighest,
            labelColor = if (ready) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            trailingIconContentColor = if (ready) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        ),
        border = null
    )
}
