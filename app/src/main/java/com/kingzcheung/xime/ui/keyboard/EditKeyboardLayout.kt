package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private data class Quadrant(
    val label: String,
    val startAngle: Float,
    val actionBase: String
)

private val quadrants = listOf(
    Quadrant("↑", 225f, "arrow_up"),
    Quadrant("→", 315f, "arrow_right"),
    Quadrant("↓", 45f, "arrow_down"),
    Quadrant("←", 135f, "arrow_left"),
)

@Composable
fun EditKeyboardLayout(
    onAction: (String) -> Unit,
    onBack: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    keyBgColor: Color,
    bottomPaddingDp: Int = 0,
    /** 与主键盘一致的主题按键圆角（LocalKeyCornerRadius），供复用的 KeyButton 读取 */
    keyCornerRadius: Dp = 8.dp,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    val keyBg = keyBgColor
    var isSelecting by remember { mutableStateOf(false) }

    fun arrowAction(base: String): String = if (isSelecting) "select_$base" else base

    // 布局按父容器真实宽度自适应（悬浮卡片/键盘收窄/分屏的容器宽 ≠ 屏幕宽），
    // 不再读屏幕方向：宽容器（横屏全屏）用大边距与两列布局，其余按竖屏形态
    BoxWithConstraints(modifier = modifier) {
        val isWide = maxWidth >= WIDE_CONTAINER_WIDTH
        val sideGap = if (isWide) 50.dp else 8.dp

    CompositionLocalProvider(
        LocalKeyCornerRadius provides keyCornerRadius,
        LocalKeyVisualPadding provides PaddingValues(horizontal = 2.dp, vertical = 2.dp),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                // 头部按钮与候选栏 logo 同位（8dp），不再随宽容器加大缩进
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(keyBg)
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowLeft,
                        contentDescription = tr(R.string.s_fanhui),
                        tint = textColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = sideGap)
        ) {
            SideButtonGrid(
                items = listOf(
                    tr(R.string.s_fuzhi) to "copy", tr(R.string.s_zhantie) to "paste",
                    tr(R.string.s_quanxuan) to "select_all", tr(R.string.s_jianqie) to "cut"
                ),
                columns = if (isWide) 2 else 1,
                onAction = onAction,
                keyBg = keyBg,
                textColor = textColor,
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                modifier = Modifier.weight(1f)
            )

            Box(
                modifier = Modifier
                    .weight(3f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                val circleModifier = if (isWide) {
                    Modifier.fillMaxHeight(1f).aspectRatio(1f)
                } else {
                    Modifier.fillMaxWidth(0.85f).aspectRatio(1f)
                }
                CircularDPad(
                    isSelecting = isSelecting,
                    onDirection = { dir -> onAction(arrowAction(dir)) },
                    onToggleSelect = {
                        val newSelecting = !isSelecting
                        isSelecting = newSelecting
                        onAction(if (newSelecting) "select_begin" else "select_end")
                    },
                    keyBg = keyBg,
                    textColor = textColor,
                    accentColor = accentColor,
                    backgroundColor = backgroundColor,
                    shadowEnabled = shadowEnabled,
                    shadowElevation = shadowElevation,
                    modifier = circleModifier
                )
            }

            SideButtonGrid(
                items = listOf(
                    tr(R.string.s_duanshou) to "home", tr(R.string.s_duanwei) to "end",
                    tr(R.string.s_shanchu) to "delete", tr(R.string.s_huiche) to "enter"
                ),
                columns = if (isWide) 2 else 1,
                onAction = onAction,
                keyBg = keyBg,
                textColor = textColor,
                shadowEnabled = shadowEnabled,
                shadowElevation = shadowElevation,
                shadowShapeRadius = shadowShapeRadius,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(
            modifier = Modifier.height(bottomPaddingDp.dp)
        )
    }
    }
    }
}

@Composable
private fun CircularDPad(
    isSelecting: Boolean,
    onDirection: (String) -> Unit,
    onToggleSelect: () -> Unit,
    keyBg: Color,
    textColor: Color,
    accentColor: Color,
    backgroundColor: Color,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    modifier: Modifier = Modifier
) {
    val outerFraction = 0.92f
    val innerFraction = 0.38f

    var pressedAction by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current
    val shadowModifier = remember(shadowEnabled, shadowElevation, density, keyBg) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val color = crispShadowColor(keyBg)
            Modifier.drawBehind {
                // 底部投影：与各键盘按键的 crisp 阴影同风格（圆形）
                drawCircle(
                    color = color,
                    radius = size.minDimension / 2f,
                    center = Offset(size.width / 2f, size.height / 2f + offsetPx)
                )
            }
        } else Modifier
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .then(shadowModifier)
            .clip(CircleShape)
            .background(keyBg)
            .pointerInput(isSelecting) {
                detectTapGestures(
                    onPress = { offset ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val cx = w / 2f
                        val cy = h / 2f
                        val dist = sqrt((offset.x - cx) * (offset.x - cx) + (offset.y - cy) * (offset.y - cy))
                        val outerR = w / 2f * outerFraction
                        val innerR = w / 2f * innerFraction
                        when {
                            dist < innerR -> pressedAction = "center"
                            dist < outerR -> {
                                val angle = atan2(offset.y - cy, offset.x - cx)
                                val deg = ((Math.toDegrees(angle.toDouble()) + 360) % 360).toFloat()
                                pressedAction = when {
                                    deg in 225f..314f -> quadrants[0].actionBase
                                    deg in 315f..359f || deg in 0f..44f -> quadrants[1].actionBase
                                    deg in 45f..134f -> quadrants[2].actionBase
                                    else -> quadrants[3].actionBase
                                }
                            }
                            else -> pressedAction = null
                        }
                        tryAwaitRelease()
                        pressedAction = null
                    },
                    onTap = { offset ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val cx = w / 2f
                        val cy = h / 2f
                        val dist = sqrt((offset.x - cx) * (offset.x - cx) + (offset.y - cy) * (offset.y - cy))
                        val outerR = w / 2f * outerFraction
                        val innerR = w / 2f * innerFraction
                        when {
                            dist < innerR -> onToggleSelect()
                            dist < outerR -> {
                                val angle = atan2(offset.y - cy, offset.x - cx)
                                val deg = ((Math.toDegrees(angle.toDouble()) + 360) % 360).toFloat()
                                val dirAction = when {
                                    deg in 225f..314f -> quadrants[0].actionBase
                                    deg in 315f..359f || deg in 0f..44f -> quadrants[1].actionBase
                                    deg in 45f..134f -> quadrants[2].actionBase
                                    else -> quadrants[3].actionBase
                                }
                                onDirection(dirAction)
                            }
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val outerR = w / 2f * outerFraction
            val innerR = w / 2f * innerFraction

            for (q in quadrants) {
                drawArc(
                    color = keyBg,
                    startAngle = q.startAngle,
                    sweepAngle = 90f,
                    useCenter = true,
                    topLeft = Offset.Zero,
                    size = size
                )
            }

            // 四象限分隔槽：沿对角线方向（象限边界）用键盘背景色画圆头细线，
            // 把上/下/左/右四个方向区隔开，与其他键盘的键间距观感对齐
            val dividerWidth = 2.dp.toPx()
            for (deg in listOf(45f, 135f, 225f, 315f)) {
                val rad = Math.toRadians(deg.toDouble()).toFloat()
                val startR = innerR * 1.06f
                drawLine(
                    color = backgroundColor,
                    start = Offset(cx + startR * cos(rad), cy + startR * sin(rad)),
                    end = Offset(cx + outerR * cos(rad), cy + outerR * sin(rad)),
                    strokeWidth = dividerWidth,
                    cap = StrokeCap.Round
                )
            }

            drawCircle(color = backgroundColor, radius = innerR)

            val centerR = innerR * 0.82f
            if (isSelecting) {
                drawCircle(color = accentColor.copy(alpha = 0.25f), radius = centerR)
            }
            drawCircle(color = keyBg, radius = centerR)
            drawCircle(
                color = textColor.copy(alpha = 0.15f),
                radius = centerR,
                style = Stroke(width = 1.dp.toPx())
            )

            // 按下效果：外圆向内圆方向渐变
            val pressedIdx = quadrants.indexOfFirst { it.actionBase == pressedAction }
            if (pressedIdx >= 0) {
                val q = quadrants[pressedIdx]
                val gradient = Brush.radialGradient(
                    0f to Color.Transparent,
                    (innerR / outerR) to Color.Transparent,
                    1f to accentColor.copy(alpha = 0.3f),
                    center = Offset(cx, cy),
                    radius = outerR
                )
                drawArc(
                    brush = gradient,
                    startAngle = q.startAngle,
                    sweepAngle = 90f,
                    useCenter = true,
                    topLeft = Offset.Zero,
                    size = size
                )
            }

            // 箭头与按键文字同源（主题 keyTextColor），但 28sp 大字形全不透明时显得过黑，
            // 降一档不透明度柔和处理；深浅主题均随主题色
            directionLabelPaint.textSize = 28.sp.toPx()
            directionLabelPaint.color = textColor.copy(alpha = 0.72f).toArgb()
            val labelR = (innerR + outerR) / 2f
            for (q in quadrants) {
                val midDeg = Math.toRadians((q.startAngle + 45f).toDouble()).toFloat()
                val lx = cx + labelR * cos(midDeg)
                val ly = cy + labelR * sin(midDeg)
                drawContext.canvas.nativeCanvas.drawText(
                    q.label, lx, ly + directionLabelPaint.textSize * 0.35f, directionLabelPaint
                )
            }

            val centerLabel = if (isSelecting) tr(R.string.s_quxiao) else tr(R.string.s_xuanze)
            centerLabelPaint.textSize = 13.sp.toPx()
            centerLabelPaint.color = if (isSelecting) accentColor.toArgb() else textColor.toArgb()
            drawContext.canvas.nativeCanvas.drawText(
                centerLabel, cx, cy + centerLabelPaint.textSize * 0.35f, centerLabelPaint
            )
        }
    }
}

private val directionLabelPaint = android.graphics.Paint().apply {
    isAntiAlias = true
    textAlign = android.graphics.Paint.Align.CENTER
    typeface = AppFonts.keyFontTypeface
}

private val centerLabelPaint = android.graphics.Paint().apply {
    isAntiAlias = true
    textAlign = android.graphics.Paint.Align.CENTER
    typeface = AppFonts.keyFontTypeface
}

@Composable
private fun SideButtonGrid(
    items: List<Pair<String, String>>,
    columns: Int,
    onAction: (String) -> Unit,
    keyBg: Color,
    textColor: Color,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    val rows = items.chunked(columns)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { (label, action) ->
                    KeyButton(
                        text = label,
                        onClick = { onAction(action) },
                        backgroundColor = keyBg,
                        textColor = textColor,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        fontSize = 14.sp,
                        shadowEnabled = shadowEnabled,
                        shadowElevation = shadowElevation,
                        shadowShapeRadius = shadowShapeRadius,
                    )
                }
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}


