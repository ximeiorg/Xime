package com.kingzcheung.xime.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.onFocusChanged
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

/**
 * 设备互联（局域网直连）设置页。
 *
 * 页面结构与鸿蒙版（`ximeharmony` 的 `DeviceLinkPage.ets`）保持一致：
 * 功能开关 → 本机 → 配对设备 → 已配对设备（N）→ 说明。
 * 两端文案与操作顺序也一致，避免用户在两个平台上学习两套心智模型。
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
    // 总开关：与「剪贴板同步」页共用同一份偏好，不另立状态源，
    // 否则两处开关会互相覆盖，用户改了一处另一处却不变。
    var linkEnabled by remember { mutableStateOf(false) }

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

    fun setLinkEnabled(enabled: Boolean) {
        if (enabled) {
            // 先写传输方式再写总开关：输入法侧监听总开关变化后立刻按 P2P 启动
            SettingsPreferences.setClipboardSyncTransport(
                context,
                SettingsPreferences.ClipboardSyncTransport.P2P
            )
            SettingsPreferences.setClipboardSyncEnabled(context, true)
        } else {
            SettingsPreferences.setClipboardSyncEnabled(context, false)
        }
        linkEnabled = enabled
        if (!enabled && mode != PairMode.None) {
            scope.launch(Dispatchers.IO) { P2pBridge.pairCancel() }
            mode = PairMode.None
            code = null
            phase = "idle"
            peerName = null
        }
    }

    LaunchedEffect(Unit) {
        deviceName = SettingsPreferences.getDeviceLinkName(context).ifBlank { defaultDeviceName() }
        linkEnabled = SettingsPreferences.isClipboardSyncEnabled(context) &&
            SettingsPreferences.getClipboardSyncTransport(context) ==
            SettingsPreferences.ClipboardSyncTransport.P2P
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
        if (!available || !linkEnabled) return
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
        if (!available || !linkEnabled) return
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
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = tr(R.string.s_yuanshengkuweijiazai),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // ── 功能开关 ──────────────────────────────────────────
            SettingsSection(title = tr(R.string.s_gongnengkaiguan)) {
                SettingsToggleItem(
                    icon = Icons.TwoTone.AutoAwesome,
                    title = tr(R.string.s_qiyongshebeihulian),
                    subtitle = tr(R.string.s_qiyongshebeihulian_shuoming),
                    checked = linkEnabled,
                    onCheckedChange = ::setLinkEnabled
                )
            }

            // ── 本机 ──────────────────────────────────────────────
            SettingsSection(title = tr(R.string.s_benji)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = tr(R.string.s_shebeimingcheng),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.width(88.dp)
                        )
                        OutlinedTextField(
                            value = deviceName,
                            onValueChange = { deviceName = it.take(32) },
                            placeholder = { Text(tr(R.string.s_shebeimingcheng_shuoming)) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { state -> if (!state.isFocused) saveDeviceName() }
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = tr(R.string.s_shebeibiaoqi),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.width(88.dp)
                        )
                        Text(
                            text = when {
                                !linkEnabled -> tr(R.string.s_weiqiyong)
                                did.isNullOrEmpty() -> tr(R.string.s_jiuzhongzhong)
                                else -> did!!.take(16)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ── 配对设备 ──────────────────────────────────────────
            SettingsSection(title = tr(R.string.s_peiduishoubei)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!linkEnabled) {
                        Text(
                            text = tr(R.string.s_shebeihulian_yiguanbi),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
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
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        Icons.TwoTone.Link,
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
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(tr(R.string.s_shurujisuanjianshang_dema))
                                }
                            }
                        }
                    }
                    PairStatusText(phase = phase, peerName = peerName, error = error)
                }
            }

            // ── 已配对设备 ────────────────────────────────────────
            SettingsSection(title = tr(R.string.s_yipeiduishebei_shuliang, peers.size)) {
                if (peers.isEmpty()) {
                    Text(
                        text = tr(R.string.s_zanwuyipeiduishebei),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    peers.forEach { peer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
                            Text(
                                text = tr(R.string.s_jiechu),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .clickable(enabled = available) {
                                        scope.launch(Dispatchers.IO) {
                                            P2pBridge.forget(dir, peer.did)
                                            SettingsPreferences.bumpClipboardSyncPeersRevision(context)
                                            val latest = P2pBridge.peers(dir)
                                            withContext(Dispatchers.Main) { peers = latest }
                                        }
                                    }
                                    .padding(8.dp)
                            )
                        }
                    }
                }
            }

            // ── 说明 ──────────────────────────────────────────────
            SettingsSection(title = tr(R.string.s_shuoming)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listOf(
                        R.string.s_shuoming_1,
                        R.string.s_shuoming_2,
                        R.string.s_shuoming_3,
                        R.string.s_shuoming_4
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
        Text(
            text = code?.chunked(3)?.joinToString(" ") ?: "——— ———",
            fontSize = 30.sp,
            letterSpacing = 4.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = tr(R.string.s_zaijisuanjianshang_xianshi_peiduima),
            style = MaterialTheme.typography.bodyMedium,
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
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onRegenerate, enabled = !busy) {
                Text(tr(R.string.s_chongxinshengcheng))
            }
            OutlinedButton(onClick = onCancel) {
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
            label = { Text(tr(R.string.s_peiduima)) },
            placeholder = { Text(tr(R.string.s_peiduimatishi)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (phase == "waiting") {
            Text(
                text = tr(R.string.s_zhengzaicha_shebei),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(tr(R.string.s_quxiao))
            }
        } else {
            Button(
                onClick = onConnect,
                enabled = !busy && value.length == 6,
                modifier = Modifier.fillMaxWidth()
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
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 默认设备名：取机型名，便于在电脑端列表里一眼分辨。 */
private fun defaultDeviceName(): String =
    Build.MODEL?.trim().takeUnless { it.isNullOrEmpty() }?.take(32) ?: "Android"
