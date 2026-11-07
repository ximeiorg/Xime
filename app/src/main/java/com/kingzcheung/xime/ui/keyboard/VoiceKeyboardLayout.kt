package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.ui.theme.KeyboardThemes

@Composable
fun VoiceKeyboardLayout(
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    keyboardBackgroundColor: Color,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = false,
    themeId: String = "ocean_blue",
    bottomActive: Boolean = false,
    leftActive: Boolean = false,
    rightActive: Boolean = false,
    pluginName: String = "",
    recognitionState: RecognitionState = RecognitionState.IDLE,
    recognizedText: String = "",
    amplitude: Float = 0f,
    spectrum: FloatArray = FloatArray(16)
) {
    val accentColor = KeyboardThemes.getAccentColor(themeId, isDarkTheme)
    val inactiveColor = if (isDarkTheme) Color.Gray.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.9f)
    val activeColor = accentColor
    val contentScroll = rememberScrollState()
    // 识别文字变长时自动滚到底部，优先展示最新内容；内容不超高时保持垂直居中
    LaunchedEffect(contentScroll.maxValue) {
        if (contentScroll.maxValue > 0) contentScroll.scrollTo(contentScroll.maxValue)
    }

    Column(
        modifier = modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp)
        ) {
            val contentMinHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(contentScroll)
                    .heightIn(min = contentMinHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                // 内容不超过可视区时居中；超出时可滚动，不再让文字顶出上/下边界被遮挡
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                if (pluginName.isNotEmpty()) {
                    Text(
                        text = pluginName,
                        color = keyTextColor.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                val statusText = when (recognitionState) {
                    RecognitionState.IDLE -> tr(R.string.s_zhangankonggekaishishuohua)
                    RecognitionState.LISTENING -> tr(R.string.s_zhengzailingting)
                    RecognitionState.PROCESSING -> tr(R.string.s_zhengzaishibie)
                    RecognitionState.ERROR -> tr(R.string.s_shibiechucuo)
                }

                Text(
                    text = statusText,
                    color = keyTextColor.copy(alpha = 0.8f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )

                if (recognizedText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = recognizedText,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(64.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier.size(if (leftActive) 64.dp else 56.dp)
                ) {
                    drawCircle(
                        color = if (leftActive) activeColor else inactiveColor,
                        radius = size.minDimension / 2f
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = tr(R.string.s_chexiao),
                    tint = if (leftActive) Color.White else if (isDarkTheme) Color.DarkGray else Color.Gray,
                    modifier = Modifier
                        .size(28.dp)
                        .scale(if (leftActive) 1.15f else 1f)
                )
            }
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRoundRect(
                        color = inactiveColor.copy(alpha = 0.3f),
                        topLeft = Offset.Zero,
                        size = size,
                        cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx())
                    )
                }
                
                AudioSpectrumAnimation(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    isActive = recognitionState == RecognitionState.LISTENING || 
                               recognitionState == RecognitionState.PROCESSING,
                    amplitude = amplitude,
                    spectrum = spectrum
                )
            }
            
            Box(
                modifier = Modifier.size(64.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier.size(if (rightActive) 64.dp else 56.dp)
                ) {
                    drawCircle(
                        color = if (rightActive) activeColor else inactiveColor,
                        radius = size.minDimension / 2f
                    )
                }
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = tr(R.string.s_fasong),
                    tint = if (rightActive) Color.White else if (isDarkTheme) Color.DarkGray else Color.Gray,
                    modifier = Modifier
                        .size(28.dp)
                        .scale(if (rightActive) 1.15f else 1f)
                )
            }
        }
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                
                val centerX = canvasWidth / 2f
                val baseRadius = canvasWidth * 0.8f
                val centerY = baseRadius + canvasHeight * 0.05f
                
                val bottomPath = Path().apply {
                    moveTo(0f, canvasHeight)
                    lineTo(canvasWidth, canvasHeight)
                    lineTo(canvasWidth, 0f)
                    arcTo(
                        Rect(centerX - baseRadius, centerY - baseRadius, centerX + baseRadius, centerY + baseRadius),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = -180f,
                        forceMoveTo = false
                    )
                    lineTo(0f, canvasHeight)
                    close()
                }
                
                val baseColor = if (bottomActive) Color.White else inactiveColor
                drawPath(
                    path = bottomPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            baseColor.copy(alpha = 0.8f),
                            baseColor.copy(alpha = 0.5f),
                            baseColor.copy(alpha = 0.0f)
                        ),
                        startY = 0f,
                        endY = canvasHeight
                    )
                )
            }
            
            Text(
                text = tr(R.string.s_songkaijieshu),
                color = if (bottomActive) Color.DarkGray else Color.Gray,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun AudioSpectrumAnimation(
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    amplitude: Float = 0f,
    spectrum: FloatArray = FloatArray(16),
    barWidthFactor: Float = 2.2f,
    barCount: Int = 9,
    spacingRatio: Float = 0.6f,
    heightScale: Float = 0.85f,
) {
    if (!isActive) {
        Canvas(modifier = modifier) {}
        return
    }

    val amplitudeValue = amplitude.coerceIn(0f, 1f)

    // 每根条的目标高度：优先真实频谱频段值，无频谱时退化为整体振幅
    val barTargets = remember(spectrum, amplitudeValue, barCount) {
        FloatArray(barCount) { index ->
            if (spectrum.isNotEmpty()) {
                spectrum[index % spectrum.size]
            } else {
                amplitudeValue
            }
        }
    }
    val animatedValues = barTargets.mapIndexed { index, target ->
        animateFloatAsState(
            targetValue = target,
            animationSpec = tween(durationMillis = 120),
            label = "spectrumBar$index"
        )
    }

    val colors = listOf(
        Color(0xFFFF6B6B),
        Color(0xFFFF8E72),
        Color(0xFFFF9F43),
        Color(0xFFFFB86C),
        Color(0xFFFFEAA7),
        Color(0xFF55EFC4),
        Color(0xFF74D7AE),
        Color(0xFF54A0FF),
        Color(0xFF5F27CD),
    )

    Canvas(modifier = modifier) {
        val barWidth = size.width / (barCount * barWidthFactor)
        val spacing = barWidth * spacingRatio
        val maxHeight = size.height

        repeat(barCount) { index ->
            val barHeight = maxHeight * animatedValues[index].value.coerceIn(0.05f, 0.98f) * heightScale

            val totalWidth = barCount * barWidth + (barCount - 1) * spacing
            val startX = (size.width - totalWidth) / 2f
            val x = startX + index * (barWidth + spacing)
            val y = (maxHeight - barHeight) / 2f

            val barAlpha = 0.5f + amplitudeValue * 0.5f

            drawRoundRect(
                color = colors[index % colors.size].copy(alpha = barAlpha),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}