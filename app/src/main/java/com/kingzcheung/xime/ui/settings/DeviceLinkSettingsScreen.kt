package com.kingzcheung.xime.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Badge
import androidx.compose.material.icons.twotone.Fingerprint
import androidx.compose.material.icons.twotone.Link
import androidx.compose.material.icons.twotone.Smartphone
import androidx.compose.material.icons.twotone.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.R
import com.kingzcheung.xime.i18n.tr
import com.kingzcheung.xime.p2p.P2pBridge
import com.kingzcheung.xime.p2p.P2pNative
import com.kingzcheung.xime.p2p.P2pPeer
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PairMode { None, Display, Input }

/** 配对码有效期，与 Rust 侧 `pair_display/pair_input` 的 60 秒一致。 */
private const val PAIR_TIMEOUT_SECONDS = 60

/** 卡片内边距，与全站设置项一致。 */
private val CardPadding = 16.dp

/** 行首图标尺寸与图标底圆角，与 [SettingsItem] 一致。 */
private val RowIconSize = 40.dp

/** 图标 + 间距后的正文缩进：标题列与输入框都从这条线开始。 */
private val RowContentIndent = 56.dp

/**
 * 设备互联（局域网直连）设置页。
 *
 * 页面结构与鸿蒙版（`ximeharmony` 的 `DeviceLinkPage.ets`）保持一致：
 * 功能开关 → 本机 → 配对设备 → 已配对设备（N）→ 说明。
 * 视觉沿用全站设置页的 `SettingsSection` + 40dp 圆角图标行，不再单独手写表单样式。
 *
 * 分工：本页只做**配对**，协议、握手与配对表都在 Rust 侧；剪贴板同步由输入法
 * 服务常驻的同步节点负责，配对完成后回「剪贴板同步」选择传输方式即可生效。
 *
 * 与鸿蒙版的差异只有一处：这里没有「存储位置」一行。鸿蒙的主 App 与输入法扩展
 * 是两个进程，必须确认共享沙箱；Android 端两者同属一个应用、同一份目录，
 * 不存在这个问题，写上去反而是噪音。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceLinkSettingsContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dir = remember { P2pBridge.dir(context) }
    val available = remember { P2pNative.loaded }

    var did by remember { mutableStateOf<String?>(null) }
    var peers by remember { mutableStateOf(emptyList<P2pPeer>()) }
    var mode by remember { mutableStateOf(PairMode.None) }
    var session by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf("idle") }
    var peerName by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    var codeInput by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var deviceName by remember { mutableStateOf("") }

    fun refreshPeers() {
        scope.launch(Dispatchers.IO) {
            val latest = P2pBridge.peers(dir)
            withContext(Dispatchers.Main) { peers = latest }
        }
    }

    fun saveDeviceName() {
        val resolved = deviceName.trim().ifEmpty { defaultDeviceName() }
        if (resolved != deviceName) deviceName = resolved
        SettingsPreferences.setDeviceLinkName(context, resolved)
    }

    LaunchedEffect(Unit) {
        deviceName = SettingsPreferences.getDeviceLinkName(context).ifBlank { defaultDeviceName() }
        if (!available) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val identity = P2pBridge.identity(dir)
            val loadedPeers = P2pBridge.peers(dir)
            withContext(Dispatchers.Main) {
                did = identity.did
                peers = loadedPeers
            }
        }
    }

    // 配对期间每秒取一次进度；done/failed/cancelled 即停止。
    LaunchedEffect(mode, session) {
        if (mode == PairMode.None || !available) return@LaunchedEffect
        val deadline = System.currentTimeMillis() + PAIR_TIMEOUT_SECONDS * 1000L
        while (isActive) {
            val state = withContext(Dispatchers.IO) { P2pBridge.pairPoll() }
            phase = state.phase
            state.peer?.let { peerName = it.name.ifBlank { it.did.take(8) } }
            error = state.error
            secondsLeft = ((deadline - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
            if (state.phase == "done") {
                SettingsPreferences.bumpClipboardSyncPeersRevision(context)
                refreshPeers()
            }
            if (state.phase == "done" || state.phase == "failed" || state.phase == "cancelled") break
            delay(1_000)
        }
    }

    fun startDisplay() {
        if (!available) return
        saveDeviceName()
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                P2pBridge.pairDisplay(dir, deviceName, PAIR_TIMEOUT_SECONDS)
            }
            busy = false
            code = result.code
            phase = if (result.ok) "waiting" else "failed"
            peerName = null
            error = result.error
            mode = PairMode.Display
            session++
        }
    }

    fun startInput() {
        if (!available) return
        val digits = codeInput.filter(Char::isDigit)
        if (digits.length != 6) return
        saveDeviceName()
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                P2pBridge.pairInput(dir, deviceName, digits, "", PAIR_TIMEOUT_SECONDS)
            }
            busy = false
            phase = if (result.ok) "waiting" else "failed"
            peerName = null
            error = result.error
            mode = PairMode.Input
            session++
        }
    }

    fun cancelPairing() {
        scope.launch(Dispatchers.IO) { P2pBridge.pairCancel() }
        mode = PairMode.None
        code = null
        phase = "idle"
        peerName = null
        error = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_shebeihulian)) },
                navigationIcon = {
                    IconButton(onClick = {
                        saveDeviceName()
                        if (mode != PairMode.None) cancelPairing()
                        onBack()
                    }) {
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
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!available) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tr(R.string.s_yuanshengkuweijiazai),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // ── 本机 ──────────────────────────────────────────────
            SettingsSection(title = tr(R.string.s_benji)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(CardPadding),
                    verticalAlignment = Alignment.Top
                ) {
                    SettingRowIcon(Icons.TwoTone.Badge)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr(R.string.s_shebeimingcheng),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = deviceName,
                            onValueChange = { deviceName = it.take(32) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            shape = RoundedCornerShape(10.dp),
                            supportingText = {
                                Text(
                                    text = tr(R.string.s_shebeimingcheng_shuoming),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { state -> if (!state.isFocused) saveDeviceName() }
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(start = RowContentIndent),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                SettingValueRow(
                    icon = Icons.TwoTone.Fingerprint,
                    title = tr(R.string.s_shebeibiaoqi),
                    value = when {
                        did.isNullOrEmpty() -> tr(R.string.s_jiuzhongzhong)
                        else -> did!!.take(16)
                    },
                    monospace = true
                )
            }

            // ── 配对设备 ──────────────────────────────────────────
            SettingsSection(title = tr(R.string.s_peiduishoubei)) {
                Column(
                    modifier = Modifier.padding(CardPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (mode) {
                        PairMode.Display -> DisplayPanel(
                            code = code,
                            phase = phase,
                            peerName = peerName,
                            error = error,
                            secondsLeft = secondsLeft,
                            busy = busy,
                            onRegenerate = ::startDisplay,
                            onCancel = ::cancelPairing
                        )

                        PairMode.Input -> InputPanel(
                            value = codeInput,
                            onValueChange = { codeInput = it.filter(Char::isDigit).take(6) },
                            phase = phase,
                            peerName = peerName,
                            error = error,
                            busy = busy,
                            onConnect = ::startInput,
                            onCancel = ::cancelPairing
                        )

                        PairMode.None -> {
                            Button(
                                onClick = ::startDisplay,
                                enabled = available && !busy,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.TwoTone.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(tr(R.string.s_zaijisuanjianshang_daima))
                            }
                            OutlinedButton(
                                onClick = {
                                    mode = PairMode.Input
                                    phase = "idle"
                                    error = null
                                    peerName = null
                                },
                                enabled = available && !busy,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Text(tr(R.string.s_shurujisuanjianshang_dema))
                            }
                        }
                    }

                    PairStatusText(phase = phase, peerName = peerName, error = error)

                    Text(
                        text = tr(R.string.s_peidui_shuoming_neirong),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── 已配对设备 ────────────────────────────────────────
            SettingsSection(title = tr(R.string.s_yipeiduishebei_shuliang, peers.size)) {
                if (peers.isEmpty()) {
                    Text(
                        text = tr(R.string.s_zanwuyipeiduishebei),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(CardPadding)
                    )
                } else {
                    peers.forEachIndexed { index, peer ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = RowContentIndent),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = CardPadding, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SettingRowIcon(Icons.TwoTone.Smartphone)
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = peer.name.ifBlank { peer.did.take(8) },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = peer.did.take(8),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        P2pBridge.forget(dir, peer.did)
                                        SettingsPreferences.bumpClipboardSyncPeersRevision(context)
                                        val latest = P2pBridge.peers(dir)
                                        withContext(Dispatchers.Main) { peers = latest }
                                    }
                                },
                                enabled = available
                            ) {
                                Text(
                                    text = tr(R.string.s_jiechu),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // ── 说明 ──────────────────────────────────────────────
            SettingsSection(title = tr(R.string.s_shuoming)) {
                Column(
                    modifier = Modifier.padding(CardPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        R.string.s_shuoming_1,
                        R.string.s_shuoming_2,
                        R.string.s_shuoming_3,
                        R.string.s_shuoming_4,
                        R.string.s_shuoming_5
                    ).forEach { id ->
                        Text(
                            text = tr(id),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 行首图标：40dp 圆角底 + 22dp 主题色图标，与 [SettingsItem] 同一套 token。 */
@Composable
private fun SettingRowIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(RowIconSize)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** 只读的「标题 + 值」行，与 [SettingsItem] 同构，只是右侧不放箭头。 */
@Composable
private fun SettingValueRow(
    icon: ImageVector,
    title: String,
    value: String,
    monospace: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(CardPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingRowIcon(icon)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DisplayPanel(
    code: String?,
    phase: String,
    peerName: String?,
    error: String?,
    secondsLeft: Int,
    busy: Boolean,
    onRegenerate: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = code?.chunked(3)?.joinToString(" ") ?: "——— ———",
                fontSize = 30.sp,
                letterSpacing = 4.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = tr(R.string.s_zaijisuanjianshang_xianshi_peiduima),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (phase == "waiting" && secondsLeft > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = tr(R.string.s_miaohouzidongshixiao, secondsLeft),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onRegenerate,
                enabled = !busy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
            ) {
                Text(tr(R.string.s_chongxinshengcheng))
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
            ) {
                Text(tr(R.string.s_quxiao))
            }
        }
    }
}

@Composable
private fun InputPanel(
    value: String,
    onValueChange: (String) -> Unit,
    phase: String,
    peerName: String?,
    error: String?,
    busy: Boolean,
    onConnect: () -> Unit,
    onCancel: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(tr(R.string.s_peiduimatishi)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = MaterialTheme.typography.titleMedium,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (phase == "waiting") {
            Text(
                text = tr(R.string.s_zhengzaicha_shebei),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(tr(R.string.s_quxiao))
            }
        } else {
            Button(
                onClick = onConnect,
                enabled = !busy && value.length == 6,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(tr(R.string.s_kaishipeidui))
            }
        }
    }
}

@Composable
private fun PairStatusText(phase: String, peerName: String?, error: String?) {
    val text = when (phase) {
        "waiting" -> tr(R.string.s_dengdaiduifangshuru)
        "done" -> tr(R.string.s_peiduichenggong, peerName ?: "")
        "failed", "cancelled" -> tr(R.string.s_peiduishibai, error ?: phase)
        else -> null
    } ?: return
    val color = when (phase) {
        "done" -> MaterialTheme.colorScheme.primary
        "failed", "cancelled" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 默认设备名：取机型名，便于在对方设备列表里一眼分辨。 */
private fun defaultDeviceName(): String =
    Build.MODEL?.trim().takeUnless { it.isNullOrEmpty() }?.take(32) ?: "Android"
