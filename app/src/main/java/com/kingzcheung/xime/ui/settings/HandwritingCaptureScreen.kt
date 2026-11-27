package com.kingzcheung.xime.ui.settings

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.handwriting.HandwritingCandidate
import com.kingzcheung.xime.handwriting.HandwritingEngine
import com.kingzcheung.xime.handwriting.StrokePoint
import com.kingzcheung.xime.handwriting.capture.HandwritingSample
import com.kingzcheung.xime.handwriting.capture.HandwritingSampleCodec
import com.kingzcheung.xime.handwriting.capture.StrokePointMs
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "HandwritingCapture"

/** 采集导出事件：分享（FileProvider uri）/保存到 Downloads/错误提示。 */
sealed class CaptureEvent {
    data class Share(val uri: android.net.Uri, val subject: String) : CaptureEvent()
    data class Saved(val path: String) : CaptureEvent()
    data class Error(val message: String) : CaptureEvent()
}

/**
 * 手写采集样本仓库（内存）。导出时把当前样本序列化为 JSON（HandwritingSampleCodec）。
 */
class HandwritingCaptureViewModel(app: android.app.Application) : AndroidViewModel(app) {

    private val _samples = MutableStateFlow<List<HandwritingSample>>(emptyList())
    val samples: StateFlow<List<HandwritingSample>> = _samples

    private val _events = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<CaptureEvent> = _events

    /** 追加样本：同一个字可多次书写提交（多样本提升数据多样性）。 */
    fun addSample(sample: HandwritingSample) {
        _samples.update { list -> list + sample }
    }

    /** 删除该字的全部样本。 */
    fun removeAllSamples(target: String) {
        _samples.update { list -> list.filterNot { it.target == target } }
    }

    fun clearAll() {
        _samples.value = emptyList()
    }

    private fun exportFile(): File {
        val dir = File(getApplication<android.app.Application>().filesDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "xime_handwriting_$stamp.json")
    }

    private fun writeJson(file: File): String {
        val app = getApplication<android.app.Application>()
        val json = HandwritingSampleCodec.buildExportJson(
            samples = _samples.value,
            appVersion = BuildConfig.VERSION_NAME,
            device = AppInfo.deviceModel,
            androidVersion = AppInfo.androidVersion,
            exportedAtMs = System.currentTimeMillis(),
        )
        file.writeText(json, Charsets.UTF_8)
        return json
    }

    fun exportShare() {
        viewModelScope.launch {
            if (_samples.value.isEmpty()) {
                _events.emit(CaptureEvent.Error(tr(R.string.s_zanwucaijiyangben)))
                return@launch
            }
            try {
                val file = withContext(Dispatchers.IO) { exportFile().also { writeJson(it) } }
                val uri = FileProvider.getUriForFile(
                    getApplication(),
                    "${getApplication<android.app.Application>().packageName}.fileprovider",
                    file,
                )
                _events.emit(CaptureEvent.Share(uri, tr(R.string.s_xime_shouxiecaijishuju)))
            } catch (e: Exception) {
                Log.e(TAG, "exportShare failed", e)
                FileLogger.e(TAG, "exportShare failed", e)
                _events.emit(CaptureEvent.Error(tr(R.string.s_daochushibai_2, e.message)))
            }
        }
    }

    fun exportToDownloads() {
        viewModelScope.launch {
            if (_samples.value.isEmpty()) {
                _events.emit(CaptureEvent.Error(tr(R.string.s_zanwucaijiyangben)))
                return@launch
            }
            try {
                val file = withContext(Dispatchers.IO) { exportFile().also { writeJson(it) } }
                val savedPath = withContext(Dispatchers.IO) { saveToDownloads(file) }
                _events.emit(CaptureEvent.Saved(savedPath))
            } catch (e: Exception) {
                Log.e(TAG, "exportToDownloads failed", e)
                FileLogger.e(TAG, "exportToDownloads failed", e)
                _events.emit(CaptureEvent.Error(tr(R.string.s_baocunshibai_2, e.message)))
            }
        }
    }

    private fun saveToDownloads(file: File): String {
        val context = getApplication<android.app.Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            context.contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            } ?: throw IllegalStateException("openOutputStream failed")
            "Download/${file.name}"
        } else {
            @Suppress("DEPRECATION")
            val dest = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                file.name,
            )
            file.copyTo(dest, overwrite = true)
            "Download/${file.name}"
        }
    }
}

/**
 * 手写数据采集页（隐藏入口进入，用于采集笔迹样本优化手写模型）：
 * 粘贴一段文本 → 点击目标字 → 内嵌画布书写 → 识别预览人工确认 → 自动跳下一个未采字 → JSON 导出。
 *
 * 坐标语义与键盘手写一致：作画区局部像素坐标（左上原点，y 向下），模型侧按笔迹
 * bounding box 归一化（HandwritingInference.strokesToSequence），画布尺寸不影响分布。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandwritingCaptureScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: HandwritingCaptureViewModel = viewModel()
    val samples by viewModel.samples.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var sourceText by rememberSaveable { mutableStateOf("") }
    var currentTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is CaptureEvent.Share -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_SUBJECT, event.subject)
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = android.content.ClipData.newRawUri(null, event.uri)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, tr(R.string.s_fenxiangcaijishuju)))
                }
                is CaptureEvent.Saved -> snackbarHostState.showSnackbar(tr(R.string.s_yibaocundao, event.path))
                is CaptureEvent.Error -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    // 目标字符列表：按文本出现顺序去重，剔除空白
    val targetChars = remember(sourceText) {
        sourceText.filterNot { it.isWhitespace() }.let { if (it.isEmpty()) emptyList() else it.toList().distinct() }
    }
    // 每字样本数（同字可多次书写）
    val sampleCounts = remember(samples) { samples.groupingBy { it.target }.eachCount() }
    val remaining: List<String> = remember(targetChars, sampleCounts) {
        targetChars.filterNot { ch -> sampleCounts.containsKey(ch.toString()) }.map { it.toString() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(tr(R.string.s_shouxieshujucaiji)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr(R.string.s_fanhui))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    Text(
                        text = tr(R.string.s_yicai, samples.size),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = tr(R.string.s_gengduo))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(tr(R.string.s_fenxiang_json)) },
                            leadingIcon = { Icon(Icons.Filled.IosShare, contentDescription = null) },
                            onClick = { menuExpanded = false; viewModel.exportShare() },
                        )
                        DropdownMenuItem(
                            text = { Text(tr(R.string.s_baocundaoxiazai)) },
                            leadingIcon = { Icon(Icons.Filled.SaveAlt, contentDescription = null) },
                            onClick = { menuExpanded = false; viewModel.exportToDownloads() },
                        )
                        DropdownMenuItem(
                            text = { Text(tr(R.string.s_qingkongyangben)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menuExpanded = false; viewModel.clearAll() },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = sourceText,
                onValueChange = { sourceText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tr(R.string.s_zhantieyaocaijidewenben)) },
                placeholder = { Text(tr(R.string.s_zhantieyiduanhua_zhuzicaijibiji)) },
                singleLine = false,
                maxLines = 2,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (targetChars.isEmpty()) {
                    tr(R.string.s_gong_0_gemubiaozi)
                } else {
                    tr(R.string.s_gong_gemubiaozi_shengyu_ge, targetChars.size, remaining.size)
                },
                fontSize = 12.sp,
                color = settingsSecondaryColor(),
            )
            Spacer(Modifier.height(8.dp))

            // 文章式目标字流：36dp 小格紧凑排布，长文本一屏可见大量字
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (ch in targetChars) {
                        val chStr = ch.toString()
                        val count = sampleCounts[chStr] ?: 0
                        val selected = chStr == currentTarget
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(
                                    if (count > 0) MaterialTheme.colorScheme.tertiaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(7.dp),
                                        )
                                    } else Modifier
                                )
                                .clickable { currentTarget = chStr },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = chStr,
                                fontSize = 19.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (count > 0) {
                                Text(
                                    text = "×$count",
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 2.dp, bottom = 1.dp),
                                )
                            }
                        }
                    }
                }
            }

            currentTarget?.let { target ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                HandwritingCaptureEditor(
                    target = target,
                    existingCount = sampleCounts[target] ?: 0,
                    onConfirm = { sample ->
                        viewModel.addSample(sample)
                        currentTarget = remaining.firstOrNull { it != sample.target }
                    },
                    onDelete = {
                        viewModel.removeAllSamples(target)
                        currentTarget = null
                    },
                )
            }
        }
    }
}

/**
 * 单字书写编辑器：米字格画布 + 笔迹录制 + 实时识别预览 + 确认/重写。
 * 同一字可多次书写提交（追加样本），[existingCount] 为该字已有样本数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HandwritingCaptureEditor(
    target: String,
    existingCount: Int,
    onConfirm: (HandwritingSample) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val strokes = remember(target) { mutableStateListOf<List<StrokePoint>>() }
    var currentStroke by remember(target) { mutableStateOf<List<StrokePoint>>(emptyList()) }
    var candidates by remember(target) { mutableStateOf<List<HandwritingCandidate>>(emptyList()) }
    var canvasSizePx by remember(target) { mutableStateOf(0 to 0) }

    // 与键盘手写共享进程内单例引擎；无模型时 predict 返回空，仅记录笔迹
    LaunchedEffect(Unit) {
        val ok = withContext(Dispatchers.IO) { HandwritingEngine.initialize(context) }
        if (!ok) Log.w(TAG, "Handwriting model not available, capture without preview")
    }

    // 每笔完成后识别一次（含重写清空时复位）
    LaunchedEffect(strokes.size, target) {
        if (strokes.isEmpty()) {
            candidates = emptyList()
        } else {
            candidates = withContext(Dispatchers.Default) {
                HandwritingEngine.predict(strokes.map { stroke -> stroke.map { it.x to it.y } }, topK = 3)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = target,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = if (candidates.isEmpty()) tr(R.string.s_xiewanxianshishibieyulan) else tr(R.string.s_shibie) +
                        candidates.joinToString("  ") { it.char } + tr(R.string.s_querenhouanmubiaozi_jiyangben, target),
                    fontSize = 12.sp,
                    color = if (candidates.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (existingCount > 0) {
                    Text(
                        text = tr(R.string.s_yicai_geyangben_querenhouxinze_4269b, existingCount, existingCount + 1),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))

        val gridColor = MaterialTheme.colorScheme.outlineVariant
        val inkColor = MaterialTheme.colorScheme.onSurface
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .onSizeChanged { canvasSizePx = it.width to it.height }
                .pointerInput(target) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val stroke = mutableListOf(
                            StrokePoint(down.position.x, down.position.y),
                        )
                        currentStroke = stroke.toList()
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                change.consume()
                                // 帧内历史点：触屏采样率高于刷新率时一帧含多个采样，
                                // 全部入轨迹（跟手 + 采集数据保真）
                                for (h in change.historical) {
                                    stroke.add(StrokePoint(h.position.x, h.position.y))
                                }
                                stroke.add(StrokePoint(change.position.x, change.position.y))
                                currentStroke = stroke.toList()
                            } else {
                                break
                            }
                        } while (true)
                        // 抬笔：笔画结束（单点误触也保留，训练侧可过滤）
                        strokes.add(stroke.toList())
                        currentStroke = emptyList()
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 12f))
            // 田字格：外框 + 十字虚线
            drawRect(gridColor, style = Stroke(width = 2f))
            drawLine(gridColor, Offset(w / 2, 0f), Offset(w / 2, h), pathEffect = dash)
            drawLine(gridColor, Offset(0f, h / 2), Offset(w, h / 2), pathEffect = dash)
            // 对角虚线（米字格）
            drawLine(gridColor, Offset(0f, 0f), Offset(w, h), pathEffect = dash)
            drawLine(gridColor, Offset(w, 0f), Offset(0f, h), pathEffect = dash)

            val strokeWidth = 4.dp.toPx()
            fun renderStroke(points: List<StrokePoint>) {
                if (points.isEmpty()) return
                if (points.size == 1) {
                    drawCircle(inkColor, radius = strokeWidth / 2, center = Offset(points[0].x, points[0].y))
                    return
                }
                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                }
                drawPath(
                    path, inkColor,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            for (stroke in strokes) renderStroke(stroke)
            renderStroke(currentStroke)
        }
        Spacer(Modifier.height(8.dp))

        Row {
            OutlinedButton(
                onClick = {
                    strokes.clear()
                    currentStroke = emptyList()
                    candidates = emptyList()
                },
                enabled = strokes.isNotEmpty(),
            ) {
                Text(tr(R.string.s_zhongxie))
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    val w = canvasSizePx.first.toFloat()
                    val h = canvasSizePx.second.toFloat()
                    val t0 = strokes.firstOrNull()?.firstOrNull()?.timeMs ?: 0L
                    val top = candidates.firstOrNull()
                    onConfirm(
                        HandwritingSample(
                            target = target,
                            canvasWidthPx = w,
                            canvasHeightPx = h,
                            strokes = strokes.map { stroke ->
                                stroke.map { StrokePointMs(it.x, it.y, it.timeMs - t0) }
                            },
                            modelTop = top?.char,
                            modelTopScore = top?.score,
                        ),
                    )
                },
                enabled = strokes.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text(tr(R.string.s_querenbingxiayige))
            }
            if (existingCount > 0) {
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onDelete) {
                    Text(tr(R.string.s_shanchugaiziyangben), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
