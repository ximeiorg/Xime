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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.twotone.Devices
import androidx.compose.material.icons.twotone.Link
import androidx.compose.material.icons.twotone.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
 * 设备互联（局域网直连）的配对页。
 *
 * 协议、握手与配对表全在 Rust 侧；这一页只做三件事：展示本机 did、生成/输入
 * 6 位配对码并轮询进度、列出/删除已配对设备。同步开关不在这里——配对完成后
 * 回「剪贴板同步」选择传输方式。
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

    fun refreshPeers() {
        scope.launch(Dispatchers.IO) {
            val latest = P2pBridge.peers(dir)
            withContext(Dispatchers.Main) { peers = latest }
        }
    }

    LaunchedEffect(Unit) {
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
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                P2pBridge.pairDisplay(dir, deviceName(), PAIR_TIMEOUT_SECONDS)
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
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                P2pBridge.pairInput(dir, deviceName(), digits, "", PAIR_TIMEOUT_SECONDS)
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

            SettingsSection(title = tr(R.string.s_shebeihulian)) {
                Text(
                    text = tr(R.string.s_shebeihulian_shuoming),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Devices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr(R.string.s_benjibiaoshi),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = did ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SettingsSection(title = tr(R.string.s_peidui)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = ::startDisplay,
                        enabled = available && !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.TwoTone.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(tr(R.string.s_xianshipeiduima))
                    }
                    Button(
                        onClick = {
                            mode = PairMode.Input
                            phase = "idle"
                            error = null
                            peerName = null
                        },
                        enabled = available && !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(tr(R.string.s_shurupeiduima))
                    }
                }

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

                    PairMode.None -> Unit
                }
            }

            SettingsSection(title = tr(R.string.s_yipeiduishebei)) {
                if (peers.isEmpty()) {
                    Text(
                        text = tr(R.string.s_zanwuyipeiduishebei),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    peers.forEachIndexed { index, peer ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 56.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.Sync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = peer.name.ifBlank { peer.did.take(8) },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = buildString {
                                        append(peer.did.take(16))
                                        peer.lastAddr?.takeIf { it.isNotBlank() }?.let {
                                            append(" · ")
                                            append(it)
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
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
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = tr(R.string.s_shanchuipeidui),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
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
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = tr(R.string.s_peiduima),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = code?.chunked(3)?.joinToString(" ") ?: "——— ———",
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            PairStatusText(phase = phase, peerName = peerName, error = error)
            if (phase == "waiting" && secondsLeft > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tr(R.string.s_miaohouzidongshixiao, secondsLeft),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRegenerate, enabled = !busy) {
                    Text(tr(R.string.s_chongxinshengcheng))
                }
                TextButton(onClick = onCancel) {
                    Text(tr(R.string.s_quxiao))
                }
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
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(tr(R.string.s_peiduima)) },
                placeholder = { Text(tr(R.string.s_peiduimatishi)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onConnect,
                    enabled = !busy && value.length == 6,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tr(R.string.s_lianjie))
                }
                TextButton(onClick = onCancel) {
                    Text(tr(R.string.s_quxiao))
                }
            }
            PairStatusText(phase = phase, peerName = peerName, error = error)
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    )
}

private fun deviceName(): String =
    Build.MODEL?.trim().takeUnless { it.isNullOrEmpty() }?.take(32) ?: "Android"
