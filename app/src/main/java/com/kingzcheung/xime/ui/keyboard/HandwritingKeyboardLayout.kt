package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.handwriting.HandwritingDeleteGesture
import com.kingzcheung.xime.handwriting.HandwritingEngine
import com.kingzcheung.xime.handwriting.HandwritingStrokeFx
import com.kingzcheung.xime.handwriting.HW_DELETE_LONG_PRESS_MS
import com.kingzcheung.xime.handwriting.HW_RECOGNIZE_WINDOW_LIMIT
import com.kingzcheung.xime.handwriting.OverlappedHandwritingRecognizer
import com.kingzcheung.xime.handwriting.StrokePoint
import com.kingzcheung.xime.handwriting.handwritingDeleteGesture
import com.kingzcheung.xime.handwriting.renderStrokes
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.isTablet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 超长闲置（ms）：停顿变淡后无新笔画达到此时长才真正清空识别窗口。 */
private const val HW_CLEAR_IDLE_MS = 3000L

/**
 * 手写键盘「键面」（底栏 / 右侧符号栏）的设备档尺寸（画布本身不受影响）。
 *
 * 这几处是定尺的，**不随键盘区高度自动放大**，而平板键盘区高度约为手机的 1.6 倍
 * （键盘高 = 屏高 × 比例）⇒ 平板上按 [TABLET_HINT_EXTRA_SCALE]（与平板键面提示同档）
 * 放大，外围留白/圆角/间距与 26 键、面板、九键、笔画完全同档。
 */
internal data class HandwritingChrome(
    /** 右侧符号栏宽度 */
    val sheetWidthDp: Float,
    /** 底栏高度 */
    val barHeightDp: Float,
    /** 键面内衬（相邻键面间距 = 2×） */
    val keyPaddingDp: Float,
    /** 底栏键间距 */
    val gapDp: Float,
    /** 键面文字 */
    val fontSizeSp: Float,
    /** 键盘区两侧留白（手机 4dp / 平板 16dp，见 [keyboardSidePaddingDp]） */
    val sidePaddingDp: Float,
    /** 键面圆角（设备档：手机 8dp / 平板 14dp） */
    val keyCornerRadius: Dp,
)

/** 手写键面尺寸档：手机用历史尺寸，平板整体按提示档放大并换到平板外围留白。 */
internal fun handwritingChromeFor(isTablet: Boolean, keyCornerRadius: Dp): HandwritingChrome {
    val scale = if (isTablet) TABLET_HINT_EXTRA_SCALE else 1f
    return HandwritingChrome(
        sheetWidthDp = 56f * scale,
        barHeightDp = 48f * scale,
        keyPaddingDp = 1f * scale,
        gapDp = 4f * scale,
        fontSizeSp = 16f * scale,
        sidePaddingDp = keyboardSidePaddingDp(isTablet),
        keyCornerRadius = keyCornerRadius,
    )
}

@Composable
fun HandwritingKeyboardLayout(
    onKeyPress: (String) -> Unit = {},
    /**
     * 叠写识别结果上报（每次笔画更新后）：
     * segments 为当前笔画窗口的最优切分（最后一段=当前字，前面的段已可固化）。
     * 接收方负责把段首选字拼接写入屏上（替换式）并刷新候选栏。
     */
    onRecognition: ((List<OverlappedHandwritingRecognizer.Segment>) -> Unit)? = null,
    /** 叠写满上限时最早段被固化（笔画已裁剪出识别窗口），携带该段首选字。 */
    onSegmentSettled: ((String) -> Unit)? = null,
    /** 撤销最后一笔至空：屏上活动区字应一并撤销，由接收方处理。 */
    onUndoActive: (() -> Unit)? = null,
    onButtonFeedback: ((String) -> Unit)? = null,
    keyTextColor: Color = Color(0xFF333333),
    keyBackgroundColor: Color = Color(0xFFE0E0E0),
    specialKeyBackgroundColor: Color = Color(0xFFD0D0D0),
    bottomPaddingDp: Int = 18,
    modifier: Modifier = Modifier,
    clearSignal: Int = 0,
    specialKeyTextColor: Color = Color.White,
) {
    val strokes = remember { mutableStateListOf<List<StrokePoint>>() }
    // 识别固化前缀（头部）：这些笔画已退出识别窗口（停顿定型/窗口超限滑窗）
    var settledCount by remember { mutableIntStateOf(0) }
    // 视觉变淡前缀（头部）：DP 切出新段（前面是已完成字）或固化时推进；
    // 仅视觉淡出，笔画仍留在识别窗口——"在"中途淡出前几笔，写完仍能合并识别纠正
    var fadingPrefix by remember { mutableIntStateOf(0) }
    // 视觉消失前缀（头部）：变淡 450ms 后推进，渲染时跳过
    var gonePrefix by remember { mutableIntStateOf(0) }
    var currentStrokePoints by remember { mutableStateOf<List<StrokePoint>>(emptyList()) }
    var dragVersion by remember { mutableIntStateOf(0) }
    var lastStrokeEndMs by remember { mutableLongStateOf(0L) }
    var pressedButton by remember { mutableIntStateOf(-1) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // 设备档（与 26 键 / 面板 / 九键 / 笔画同档）：底栏与右侧符号栏是定尺键面，
    // 按 [handwritingChromeFor] 取手机/平板两档，圆角/间距/外围留白与其它键盘一致。
    val tablet = isTablet()
    val keyCornerRadius = keyCornerRadiusFor(KeysConfigHelper.getKeyboardKeyConfig(), "handwriting", tablet)
    val chrome = handwritingChromeFor(tablet, keyCornerRadius)
    val sheetWidth = chrome.sheetWidthDp.dp
    val barHeight = chrome.barHeightDp.dp
    val keyPadding = chrome.keyPaddingDp.dp
    val buttonGap = chrome.gapDp.dp
    val buttonFontSize = chrome.fontSizeSp.sp
    val sidePadding = chrome.sidePaddingDp.dp
    val sheetWPx = with(density) { sheetWidth.toPx() }
    val barHPx = with(density) { barHeight.toPx() }
    val recognizer = remember { OverlappedHandwritingRecognizer() }
    var recognizeJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            HandwritingEngine.initialize(context)
        }
    }

    /**
     * 视觉消失调度：450ms 后 [gonePrefix] 推进到 target（不超 [fadingPrefix]）。
     * 笔画数据保留在 [strokes]（识别窗口/前缀计数不受影响），渲染层跳过即可。
     */
    fun scheduleGone(target: Int) {
        scope.launch {
            delay(450L)
            if (gonePrefix < target) {
                gonePrefix = target.coerceAtMost(fadingPrefix)
                dragVersion++
            }
        }
    }

    /**
     * 清空笔画窗口：作废在途识别（旧结果不得回写上屏）+ 清笔画/前缀 + 失效识别缓存。
     * 窗口被清空后若还有在途任务，其结果会基于已撤销的笔画重新上屏（退格后"字又冒出来"）。
     */
    fun clearStrokeWindow() {
        recognizeJob?.cancel()
        recognizeJob = null
        strokes.clear()
        fadingPrefix = 0
        gonePrefix = 0
        settledCount = 0
        dragVersion++
        recognizer.reset()
    }

    // 停顿处理（自适应阈值）：笔画变淡提示"已识别上屏"，但识别窗口全程保留——
    // 单字中途的笔序停顿（如"中"写完"口"后停顿）不会清窗，后续笔画落下时
    // 全窗口重新识别自动纠正（"口"→"中"）。分字完全交给 DP 的时间偏置+
    // 相对停顿阈值，滑窗（HW_RECOGNIZE_WINDOW_LIMIT）兜底。
    // 超长闲置（CLEAR_IDLE_MS）无新笔画才真正清窗（下个字从零开始）。
    LaunchedEffect(lastStrokeEndMs) {
        if (lastStrokeEndMs > 0L) {
            delay(HandwritingStrokeFx.splitPauseMs(strokes.size - settledCount))
            if (strokes.isNotEmpty()) {
                fadingPrefix = strokes.size
                dragVersion++
                scheduleGone(strokes.size)
            }
            delay(HW_CLEAR_IDLE_MS)
            clearStrokeWindow()
        }
    }
    LaunchedEffect(clearSignal) {
        clearStrokeWindow()
    }

    /** 当前未固化窗口的笔间时间间隔（gaps[j] = 第 j 笔起笔与上一笔收笔的间隔）。 */
    fun windowGaps(window: List<List<StrokePoint>>): List<Long> =
        HandwritingStrokeFx.windowGaps(window)

    /**
     * 叠写识别调度（串行化：新请求取消旧任务，旧结果作废）。
     * 识别输入只取未固化笔画（[settledCount] 之后的窗口），并携带笔间间隔
     * 供 DP 做时间偏置（停顿处倾向切分、连笔处压制切分）。
     * 固化由"窗口笔画超限"驱动（正常分割靠停顿）：
     * 窗口超过 [RECOGNIZE_WINDOW_LIMIT] 笔时固化最早段（缓存平移无重推理），循环至不超限。
     */
    fun scheduleRecognition() {
        recognizeJob?.cancel()
        if (settledCount >= strokes.size) return
        recognizeJob = scope.launch {
            // 窗口超限：识别一次取最早段固化出窗，循环至不超限
            while (strokes.size - settledCount > HW_RECOGNIZE_WINDOW_LIMIT && isActive) {
                val window = strokes.drop(settledCount)
                val pairs = window.map { stroke -> stroke.map { Pair(it.x, it.y) } }
                val result = withContext(Dispatchers.Default) {
                    recognizer.recognize(pairs, windowGaps(window))
                }
                if (!isActive) return@launch
                val first = result.segments.firstOrNull() ?: return@launch
                val firstChar = first.candidates.firstOrNull()?.char ?: return@launch
                onSegmentSettled?.invoke(firstChar)
                withContext(Dispatchers.Main) {
                    settledCount = (settledCount + first.strokeCount).coerceAtMost(strokes.size)
                    if (settledCount > fadingPrefix) fadingPrefix = settledCount
                    dragVersion++
                    recognizer.onStrokesTrimmed(first.strokeCount)
                    scheduleGone(fadingPrefix)
                }
            }
            if (settledCount >= strokes.size || !isActive) return@launch
            // 正常识别上报（固化交给停顿分割）
            val window = strokes.drop(settledCount)
            val pairs = window.map { stroke -> stroke.map { Pair(it.x, it.y) } }
            val result = withContext(Dispatchers.Default) {
                recognizer.recognize(pairs, windowGaps(window))
            }
            if (!isActive) return@launch
            if (result.segments.isNotEmpty()) {
                onRecognition?.invoke(result.segments)
                if (result.segments.size >= 2) {
                    // DP 切出新段：仅当段边界存在"换字停顿"（gap ≥ HW_FADE_GAP_MS）时，
                    // 前面段（已完成字，结果已上屏）笔画才变淡（视觉）。
                    // 连笔中途的切分抖动（如"在"=[一][丿][丨]）不触发淡出。
                    // 识别窗口保留全部笔画（不固化），后续合并纠正（如"在"写完）仍可进行。
                    val doneStrokes = HandwritingStrokeFx.settledStrokesBeforeCurrent(
                        result.segments,
                        windowGaps(window),
                    )
                    if (doneStrokes > 0) {
                        val target = (settledCount + doneStrokes).coerceAtLeast(fadingPrefix)
                        if (target > fadingPrefix) {
                            fadingPrefix = target
                            dragVersion++
                            scheduleGone(target)
                        }
                    }
                }
            }
        }
    }

    fun onStrokeEnd() {
        lastStrokeEndMs = System.currentTimeMillis()
    }

    Box(modifier = modifier.fillMaxSize().padding(bottom = bottomPaddingDp.dp)) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(sheetWidth)
                // 底部让出底栏（含其下边距），与底栏高度同档
                .padding(end = buttonGap, top = buttonGap, bottom = barHeight + buttonGap),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf("，", "。", "？", "！", tr(R.string.s_shanchu)).forEachIndexed { i, text ->
                val pressed = pressedButton == i
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(keyPadding)
                        .clip(RoundedCornerShape(keyCornerRadius))
                        .background(if (pressed) Color(0x80000000) else Color(0x12000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text, color = keyTextColor, fontSize = buttonFontSize,
                        fontWeight = if (text.length <= 1) FontWeight.Normal else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        fontFamily = AppFonts.keyFontFamily)
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(barHeight)
                .padding(start = sidePadding, end = sidePadding, bottom = buttonGap),
            horizontalArrangement = Arrangement.spacedBy(buttonGap)
        ) {
            listOf(tr(R.string.s_fuhao) to "symbol", "123" to "number", tr(R.string.s_kongge) to "space",
                  "ABC" to "ime_switch", tr(R.string.s_huanxing) to "enter"
            ).forEachIndexed { i, (text, action) ->
                val idx = i + 5
                val bg = if (text == tr(R.string.s_fuhao) || text == tr(R.string.s_huanxing)) specialKeyBackgroundColor else keyBackgroundColor
                val txtColor = if (text == tr(R.string.s_fuhao) || text == tr(R.string.s_huanxing)) specialKeyTextColor else keyTextColor
                val w = when (text) {
                    tr(R.string.s_kongge) -> 1.8f
                    "123", "ABC" -> 0.7f
                    else -> 1f
                }
                Box(
                    modifier = Modifier.weight(w).fillMaxSize().padding(keyPadding)
                        .clip(RoundedCornerShape(keyCornerRadius))
                        .background(if (pressedButton == idx) Color(0x40000000) else bg),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text, color = txtColor, fontSize = buttonFontSize,
                        fontWeight = if (text.length <= 1) FontWeight.Normal else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        fontFamily = AppFonts.keyFontFamily)
                }
            }
        }

        key(dragVersion) {
            Canvas(Modifier.fillMaxSize()) {
                // 三段渲染：[0,gonePrefix) 已消失不画；[gonePrefix,fadingPrefix) 变淡中；
                // [fadingPrefix,end) 正常显示。正在书写的笔画（currentStrokePoints）必须
                // 无条件渲染——它尚未进入 strokes，包进条件会导致笔迹写完才显示
                if (gonePrefix < fadingPrefix && gonePrefix < strokes.size) {
                    renderStrokes(
                        strokes.drop(gonePrefix).take(fadingPrefix - gonePrefix),
                        emptyList(),
                        keyTextColor.copy(alpha = 0.3f),
                    )
                }
                renderStrokes(
                    strokes.drop(fadingPrefix),
                    currentStrokePoints,
                    keyTextColor,
                )
            }
        }

        val buttonActions = remember {
            listOf(
                listOf("，" to "，", "。" to "。", "？" to "？", "！" to "！", tr(R.string.s_shanchu) to "delete"),
                emptyList()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val sx = down.position.x
                        val sy = down.position.y
                        var dragged = false

                        val w = size.width.toFloat()
                        val h = size.height.toFloat()

                        pressedButton = when {
                            sx >= w - sheetWPx && sy < h - barHPx -> {
                                val cellH = (h - barHPx) / 5f
                                ((sy / cellH).toInt().coerceIn(0, 4))
                            }
                            sy >= h - barHPx -> {
                                val seg = w / 5.2f
                                val btnIdx = when {
                                    sx < seg -> 0; sx < seg * 1.7f -> 1
                                    sx < seg * 3.5f -> 2; sx < seg * 4.2f -> 3
                                    else -> 4
                                }
                                btnIdx + 5
                            }
                            else -> -1
                        }

                        do {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull() ?: break

                            if (ch.pressed) {
                                ch.consume()
                                if (!dragged) {
                                    val dist = (ch.position - down.position).getDistance()
                                    if (dist > 12f) {
                                        lastStrokeEndMs = 0L
                                        dragged = true
                                        pressedButton = -1
                                        currentStrokePoints = listOf(StrokePoint(sx, sy))
                                        dragVersion++
                                    }
                                } else {
                                    currentStrokePoints = currentStrokePoints + StrokePoint(ch.position.x, ch.position.y)
                                    dragVersion++
                                }
                            } else {
                                if (dragged) {
                                    val finalStroke = currentStrokePoints
                                    if (finalStroke.size >= 2) {
                                        strokes.add(finalStroke)
                                        currentStrokePoints = emptyList()
                                    }
                                    currentStrokePoints = emptyList()
                                    onStrokeEnd()
                                    if (strokes.isNotEmpty()) {
                                        scheduleRecognition()
                                    }
                                } else {
                                        if (sx >= w - sheetWPx && sy < h - barHPx) {
                                        val cellH = (h - barHPx) / 5f
                                        val idx = (sy / cellH).toInt().coerceIn(0, 4)
                                        val action = listOf("，", "。", "？", "！", "delete")[idx]
                                        val longPress =
                                            ch.uptimeMillis - down.uptimeMillis >= HW_DELETE_LONG_PRESS_MS
                                        if (action != "delete") {
                                            onButtonFeedback?.invoke(action)
                                            onKeyPress(action)
                                        } else when (handwritingDeleteGesture(longPress, settledCount < strokes.size)) {
                                            HandwritingDeleteGesture.UNDO_STROKE -> {
                                                // 长按删除键：撤销最后一笔未固化笔画并重新识别
                                                onButtonFeedback?.invoke(action)
                                                strokes.removeAt(strokes.size - 1)
                                                dragVersion++
                                                if (settledCount >= strokes.size) {
                                                    // 撤空：在途识别作废，屏上活动区字一并撤销
                                                    recognizeJob?.cancel()
                                                    recognizeJob = null
                                                    recognizer.reset()
                                                    onUndoActive?.invoke()
                                                } else {
                                                    scheduleRecognition()
                                                }
                                            }
                                            HandwritingDeleteGesture.DELETE_TEXT -> {
                                                // 短按删除键：删已上屏文本（与其它键盘退格一致），
                                                // 同时清笔画窗口——否则在途识别会把刚删掉的字写回去
                                                clearStrokeWindow()
                                                onButtonFeedback?.invoke(action)
                                                onKeyPress(action)
                                            }
                                        }
                                    } else if (sy >= h - barHPx) {
                                        val seg = w / 5.2f
                                        val idx = when {
                                            sx < seg -> 0; sx < seg * 1.7f -> 1
                                            sx < seg * 3.5f -> 2; sx < seg * 4.2f -> 3
                                            else -> 4
                                        }
                                        onButtonFeedback?.invoke(listOf("symbol", "number", "space", "ime_switch", "enter")[idx])
                                        onKeyPress(listOf("symbol", "number", "space", "ime_switch", "enter")[idx])
                                    }
                                }
                                pressedButton = -1
                                break
                            }
                        } while (true)
                    }
                }
        )
    }
}
