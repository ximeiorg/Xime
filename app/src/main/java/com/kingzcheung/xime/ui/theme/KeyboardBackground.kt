package com.kingzcheung.xime.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kingzcheung.xime.settings.BackgroundConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 角度制 → 渐变起始终止点（归一化坐标 0..1）。 */
private fun angleToFractionalPoints(angleDeg: Int): Pair<Offset, Offset> {
    val rad = Math.toRadians(angleDeg.toDouble())
    val cosA = cos(rad)
    val sinA = sin(rad)
    val divisor = (abs(cosA) + abs(sinA)).coerceAtLeast(1e-10)
    return Offset(
        (0.5f - (cosA / divisor * 0.5f)).toFloat().coerceIn(0f, 1f),
        (0.5f - (sinA / divisor * 0.5f)).toFloat().coerceIn(0f, 1f),
    ) to Offset(
        (0.5f + (cosA / divisor * 0.5f)).toFloat().coerceIn(0f, 1f),
        (0.5f + (sinA / divisor * 0.5f)).toFloat().coerceIn(0f, 1f),
    )
}

/** 从 BackgroundConfig 解析出像素色值，供不支持渐变/图片的场景使用。 */
fun resolveSolidColorHex(config: BackgroundConfig, isDark: Boolean): Long? {
    return when (config.type) {
        "solid" -> if (isDark) config.colorDark ?: config.color else config.color
        "gradient" -> {
            val hexes = if (isDark) config.colorsDark ?: config.colors else config.colors
            hexes?.firstOrNull()
        }
        else -> null
    }
}

/** 从 BackgroundConfig 解析出 Compose Color。 */
fun resolveSolidColor(config: BackgroundConfig, isDark: Boolean): Color? {
    return resolveSolidColorHex(config, isDark)?.let { Color(0xFF000000 or it) }
}

/**
 * 应用 BackgroundConfig 背景到 Composable。
 *
 * - solid / gradient：直接用 [Modifier.background]
 * - image：用 [Modifier.drawWithContent] 在内容下方绘制图片
 *
 * [alpha] 只压底板、不压按键：玻璃态要让下面的内容透上来，键帽的可读性另算。
 * 纯色、渐变、图片三种背景一视同仁（图片用 saveLayer 整层乘 alpha）。
 */
@Composable
fun Modifier.keyboardBackground(
    background: BackgroundConfig?,
    isDark: Boolean,
    fallbackColor: Color = Color(0xFFE3E4E8),
    alpha: Float = 1f,
): Modifier {
    val tint = alpha.coerceIn(0f, 1f)
    if (background == null) return this.then(Modifier.background(fallbackColor.copy(alpha = fallbackColor.alpha * tint)))

    when (background.type) {
        "solid" -> {
            val hex = if (isDark) background.colorDark ?: background.color else background.color
            val color = hex?.let { Color(0xFF000000 or it) }
            return if (color != null) this.then(Modifier.background(color.copy(alpha = tint)))
            else this.then(Modifier.background(fallbackColor.copy(alpha = fallbackColor.alpha * tint)))
        }
        "gradient" -> {
            val hexes = if (isDark) background.colorsDark ?: background.colors else background.colors
            if (hexes != null && hexes.size >= 2) {
                val colors = hexes.map { (Color(0xFF000000 or it)).copy(alpha = tint) }
                val (startRatio, endRatio) = angleToFractionalPoints(background.angle ?: 0)
                return this.then(
                    Modifier.drawWithContent {
                        val start = Offset(size.width * startRatio.x, size.height * startRatio.y)
                        val end = Offset(size.width * endRatio.x, size.height * endRatio.y)
                        drawRect(
                            brush = Brush.linearGradient(colors, start, end),
                            size = size,
                        )
                        drawContent()
                    }
                )
            }
            return this.then(Modifier.background(fallbackColor.copy(alpha = fallbackColor.alpha * tint)))
        }
        "image" -> {
            val context = LocalContext.current
            val src = if (isDark) background.srcDark ?: background.src else background.src
            if (src.isNullOrBlank()) return this.then(Modifier.background(fallbackColor))

            var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(src) {
                imageBitmap = try {
                    withContext(Dispatchers.Default) {
                        openThemeImageStream(context, src)?.use { stream ->
                            BitmapFactory.decodeStream(stream)?.asImageBitmap()
                        }
                    }
                } catch (e: Exception) { null }
            }

            val bitmap = imageBitmap
            if (bitmap != null) {
                val fit = background.fit ?: "cover"
                val overlayAlpha = if (isDark) {
                    background.overlayAlphaDark ?: background.overlayAlpha
                } else {
                    background.overlayAlpha
                }
                return this.then(
                    Modifier.drawWithContent {
                        // 图片背景同样参与 alpha：整层乘一次，和纯色/渐变行为一致
                        val faded = tint < 1f
                        if (faded) {
                            drawIntoCanvas { canvas ->
                                canvas.saveLayer(
                                    Rect(0f, 0f, size.width, size.height),
                                    Paint().also { it.alpha = tint },
                                )
                                drawImageBackground(bitmap, fit)
                                if (overlayAlpha != null && overlayAlpha > 0f) {
                                    drawRect(color = Color.Black.copy(alpha = overlayAlpha.coerceIn(0f, 1f)))
                                }
                                canvas.restore()
                            }
                        } else {
                            drawImageBackground(bitmap, fit)
                            // 半透明黑色遮罩压暗背景，提升按键对比度
                            if (overlayAlpha != null && overlayAlpha > 0f) {
                                drawRect(
                                    color = Color.Black.copy(alpha = overlayAlpha.coerceIn(0f, 1f)),
                                )
                            }
                        }
                        drawContent()
                    }
                )
            }
            return this.then(Modifier.background(fallbackColor))
        }
        else -> return this.then(Modifier.background(fallbackColor))
    }
}

/** 在 DrawScope 内绘制图片背景。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawImageBackground(
    bitmap: ImageBitmap,
    fit: String,
) {
    val srcW = bitmap.width.toFloat()
    val srcH = bitmap.height.toFloat()
    val dstW = size.width
    val dstH = size.height
    if (srcW <= 0f || srcH <= 0f || dstW <= 0f || dstH <= 0f) return

    val scale: Float
    val offsetX: Float
    val offsetY: Float

    when (fit) {
        "contain" -> {
            scale = min(dstW / srcW, dstH / srcH)
            offsetX = (dstW - srcW * scale) / 2f
            offsetY = (dstH - srcH * scale) / 2f
            clipRect {
                drawImage(
                    bitmap,
                    dstOffset = IntOffset(offsetX.roundToInt(), offsetY.roundToInt()),
                    dstSize = IntSize((srcW * scale).roundToInt(), (srcH * scale).roundToInt()),
                )
            }
        }
        "fill" -> {
            drawImage(
                bitmap,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(dstW.roundToInt(), dstH.roundToInt()),
            )
        }
        "fit_width" -> {
            scale = dstW / srcW
            offsetY = (dstH - srcH * scale) / 2f
            clipRect {
                drawImage(
                    bitmap,
                    dstOffset = IntOffset(0, offsetY.roundToInt()),
                    dstSize = IntSize((srcW * scale).roundToInt(), (srcH * scale).roundToInt()),
                )
            }
        }
        "fit_height" -> {
            scale = dstH / srcH
            offsetX = (dstW - srcW * scale) / 2f
            clipRect {
                drawImage(
                    bitmap,
                    dstOffset = IntOffset(offsetX.roundToInt(), 0),
                    dstSize = IntSize((srcW * scale).roundToInt(), (srcH * scale).roundToInt()),
                )
            }
        }
        "none" -> {
            drawImage(
                bitmap,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(srcW.roundToInt(), srcH.roundToInt()),
            )
        }
        else -> { // cover (default)
            scale = max(dstW / srcW, dstH / srcH)
            offsetX = (dstW - srcW * scale) / 2f
            offsetY = (dstH - srcH * scale) / 2f
            clipRect {
                drawImage(
                    bitmap,
                    dstOffset = IntOffset(offsetX.roundToInt(), offsetY.roundToInt()),
                    dstSize = IntSize((srcW * scale).roundToInt(), (srcH * scale).roundToInt()),
                )
            }
        }
    }
}
