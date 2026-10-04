package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.kingzcheung.xime.correction.KeyTapLogger
import com.kingzcheung.xime.settings.ButtonLayout
import com.kingzcheung.xime.util.CharInfo
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/** 按键视觉缩进（padding），用于消除 spacedBy 死区。
 *  pointerInput 在 padding 之前，触摸区=全尺寸；
 *  shadow/clip/background 在 padding 之后，视觉区=缩进后。
 *  各布局按需要覆盖：QWERTY 默认 (2.dp, 4.25.dp)，T9/数字 (2.dp, 2.dp) */
val LocalKeyVisualPadding = staticCompositionLocalOf {
    PaddingValues(horizontal = 2.dp, vertical = 4.25.dp)
}

/** 上滑/下滑提示改画键面角标（上滑=右上角、下滑=底部）。
 *  手机横屏键矮，提示按默认方式画在主字符上下方（offset ±hintOffset）会被键盘顶部裁掉。 */
val LocalSwipeHintCorner = staticCompositionLocalOf { false }

/** 按键圆角半径，由各布局在根层通过 CompositionLocalProvider 提供。
 *  独立于 shadow.shape_radius，为统一配置化而设。 */
val LocalKeyCornerRadius = staticCompositionLocalOf { 8.dp }

/** 按键内容随按键实际高度放大；手机尺寸下保持原字号。 */
internal fun adaptiveKeyContentScale(
    keyHeightDp: Float,
    referenceHeightDp: Float = 56f,
): Float {
    if (!keyHeightDp.isFinite() || keyHeightDp <= 0f) return 1f
    return (keyHeightDp / referenceHeightDp).coerceIn(1f, 1.5f)
}

/** 滑动提示在大按键上比主字符增长稍快，避免视觉上仍然偏小。 */
internal fun adaptiveHintScale(contentScale: Float): Float =
    (1f + (contentScale - 1f) * 1.5f).coerceIn(1f, 1.7f)

/** 气泡跟随提示放大，但略微收敛，避免在平板上显得过重。 */
internal fun adaptiveBubbleScale(contentScale: Float): Float =
    adaptiveHintScale(contentScale).coerceAtMost(1.5f)

/** 主字符放大时同步拉开上下提示，手机尺寸下保持原来的 14dp 间距。 */
internal fun adaptiveHintOffsetDp(contentScale: Float): Float =
    (14f + (contentScale - 1f) * 25f).coerceIn(14f, 24f)

/**
 * 是否应由按键接管横向滑动、抑制父层光标手势。
 *
 * 仅当该键配置了左/右滑（[hasHorizontalSwipe]），且本次拖动为横向主导并超过 [horizontalThreshold]。
 * 阈值应低于父层光标手势激活阈值（60dp），以确保配置了左右滑的键优先接管横向滑动；
 * 未配置左右滑的键始终返回 false，横向滑动仍用于移动光标。
 */
internal fun shouldSuppressCursorMove(
    hasHorizontalSwipe: Boolean,
    dragOffsetX: Float,
    dragOffsetY: Float,
    horizontalThreshold: Float,
): Boolean = hasHorizontalSwipe &&
    abs(dragOffsetX) > abs(dragOffsetY) &&
    abs(dragOffsetX) > horizontalThreshold

data class SwipeState(
    val isSwiping: Boolean = false,
    val swipeText: String? = null,
    val isSwipeDown: Boolean = false,
    val charInfos: List<CharInfo> = emptyList(),
    val isPressed: Boolean = false,
    val pressedText: String? = null,
    val isDanger: Boolean = false,
    // 长按弹出选择
    val isLongPress: Boolean = false,
    val longPressItems: List<String> = emptyList(),
    val selectedLongPressIndex: Int = 0,
    val longPressDrawableIds: List<Int> = emptyList(),
)

private val shadowColorCache = HashMap<Color, Color>()

internal fun crispShadowColor(backgroundColor: Color): Color {
    return shadowColorCache.getOrPut(backgroundColor) {
        val r = backgroundColor.red
        val g = backgroundColor.green
        val b = backgroundColor.blue
        val maxChroma = maxOf(r, g, b) - minOf(r, g, b)
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        if (maxChroma > 0.05f) {
            Color(r * 0.95f, g * 0.95f, b * 0.95f, backgroundColor.alpha)
        } else if (luminance > 0.5f) {
            Color.Black.copy(alpha = 0.10f)
        } else {
            Color.White.copy(alpha = 0.12f)
        }
    }
}

/**
 * TalkBack 无障碍语义层：合并子节点为单一可聚焦按键、Button 角色、双击激活走语义 onClick。
 *
 * 语义动作与组件内的 pointerInput 手势互不影响——明眼用户触摸路径零变化；
 * TalkBack 双击执行 [tap]（绑定组件最新的 onClick）。语义配置只在组合期构建，
 * 不进入渲染路径、不产生额外重组；[description]/[state] 缺省时靠子 Text 自然朗读。
 */
internal fun Modifier.keySemantics(
    description: String?,
    state: String?,
    tap: () -> Unit,
): Modifier = semantics(mergeDescendants = true) {
    role = Role.Button
    if (description != null) contentDescription = description
    if (state != null) stateDescription = state
    onClick { tap(); true }
}

@Composable
fun KeyButton(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    swipeText: String? = null,
    swipeDownText: String? = null,
    onSwipe: ((String) -> Unit)? = null,
    onSwipeDown: ((String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState) -> Unit)? = null,
    fontSize: androidx.compose.ui.unit.TextUnit? = null,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    /** 长按回调（含震动反馈），点按仍走 [onClick] */
    onLongClick: (() -> Unit)? = null,
    /** 右上角角标文字（如 T9 数字键的数字浮标） */
    badgeText: String? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    /** TalkBack 朗读描述；缺省时靠键面 Text 朗读（字母/文本键无需传） */
    a11yDescription: String? = null,
    /** TalkBack 状态播报（如 Shift 的"大写锁定"） */
    a11yState: String? = null,
) {
    var isPressed by remember { mutableStateOf(false) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var hasTriggeredSwipeUp by remember { mutableStateOf(false) }
    var hasTriggeredSwipeDown by remember { mutableStateOf(false) }
    var isSwiping by remember { mutableStateOf(false) }
    var isSwipeDown by remember { mutableStateOf(false) }
    var longPressActivated by remember { mutableStateOf(false) }
    var dragActivated by remember { mutableStateOf(false) }
    
    val density = LocalDensity.current
    val view = LocalView.current
    val keyFontFamily = AppFonts.keyFontFamily
    val keyLabelFontFamily = AppFonts.keyLabelFontFamily
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val swipeUpThreshold = with(density) { (-50).dp.toPx() }
    val swipeDownThreshold = with(density) { 50.dp.toPx() }
    val bubbleShowThresholdUp = swipeUpThreshold
    val bubbleShowThresholdDown = swipeDownThreshold
    // 水平位移超过该值视为横向手势（如键盘区滑动移动光标），不再触发点击。
    // 与 KeyboardView 光标手势激活阈值（activationThresholdPx = 60dp）对齐，
    // 消除 30~60dp 位移区间"点击被取消但光标手势未激活"的死区（打字吃键）。
    val horizontalClickCancelThreshold = with(density) { 60.dp.toPx() }
    // 父层光标手势已接管横向滑动时，本键不再判定点击（否则会"移动光标 + 打出一个字母"）
    val cursorGestureActive = LocalCursorGestureActive.current

    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }
    val keyCornerRadius = LocalKeyCornerRadius.current
    val keyClipShape = remember(keyCornerRadius) { RoundedCornerShape(keyCornerRadius) }
    
    // 辅助函数：生成更深的颜色（混合黑色）
    fun darkenColor(color: Color, factor: Float = 0.15f): Color {
        return Color(
            red = (color.red * (1 - factor)).coerceIn(0f, 1f),
            green = (color.green * (1 - factor)).coerceIn(0f, 1f),
            blue = (color.blue * (1 - factor)).coerceIn(0f, 1f),
            alpha = color.alpha
        )
    }
    
        Box(
            modifier = modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            dragActivated = true
                            isPressed = true
                            dragOffsetX = 0f
                            dragOffsetY = 0f
                            hasTriggeredSwipeUp = false
                            hasTriggeredSwipeDown = false
                            isSwiping = false
                            isSwipeDown = false
                        },
                        onDragEnd = {
                            val shouldClick = !cursorGestureActive.value &&
                            !hasTriggeredSwipeUp && !hasTriggeredSwipeDown &&
                            abs(dragOffsetX) < horizontalClickCancelThreshold
                            if (shouldClick) {
                                currentOnClick()
                            }
                            isPressed = false
                            currentOnRelease?.invoke()
                            dragOffsetX = 0f
                            dragOffsetY = 0f
                            hasTriggeredSwipeUp = false
                            hasTriggeredSwipeDown = false
                            isSwiping = false
                            isSwipeDown = false
                            longPressActivated = false
                            dragActivated = false
                            onSwipeStateChange?.invoke(SwipeState(false, null, false))
                        },
                        onDragCancel = {
                            isPressed = false
                            currentOnRelease?.invoke()
                            dragOffsetX = 0f
                            dragOffsetY = 0f
                            hasTriggeredSwipeUp = false
                            hasTriggeredSwipeDown = false
                            isSwiping = false
                            isSwipeDown = false
                            dragActivated = false
                            onSwipeStateChange?.invoke(SwipeState(false, null, false))
                        },
                        onDrag = { change, dragAmount ->
                            dragOffsetX += dragAmount.x
                            dragOffsetY += dragAmount.y
                            
                            if (dragOffsetY < 0) {
                                if (abs(dragOffsetY) > abs(dragOffsetX) * 1.1f) {
                                    val shouldShowBubble = dragOffsetY < bubbleShowThresholdUp && swipeText != null
                                    if (shouldShowBubble != isSwiping) {
                                        isSwiping = shouldShowBubble
                                        isSwipeDown = false
                                        onSwipeStateChange?.invoke(SwipeState(shouldShowBubble, swipeText, false))
                                    }
                                    
                                    if (dragOffsetY < swipeUpThreshold && !hasTriggeredSwipeUp && swipeText != null && onSwipe != null) {
                                        hasTriggeredSwipeUp = true
                                        onSwipe(swipeText)
                                    }
                                }
                            } else if (dragOffsetY > 0) {
                                if (dragOffsetY > abs(dragOffsetX) * 1.1f) {
                                    val shouldShowBubble = dragOffsetY > bubbleShowThresholdDown && swipeDownText != null
                                    if (shouldShowBubble != isSwipeDown) {
                                        isSwipeDown = shouldShowBubble
                                        isSwiping = shouldShowBubble
                                        onSwipeStateChange?.invoke(SwipeState(shouldShowBubble, swipeDownText, true))
                                    }
                                    
                                    if (dragOffsetY > swipeDownThreshold && !hasTriggeredSwipeDown && swipeDownText != null && onSwipeDown != null) {
                                        hasTriggeredSwipeDown = true
                                        onSwipeDown(swipeDownText)
                                    }
                                }
                            }
                        }
                    )
                }
                .pointerInput(currentOnLongClick != null) {
                    if (currentOnLongClick == null) {
                        detectTapGestures(
                            onPress = {
                                isPressed = true
                                onPress?.invoke()
                                val released = tryAwaitRelease()
                                // 位移/消费导致的取消：保留按压效果，由 onDragEnd/onDragCancel 统一清理，
                                // 避免快速打字时按压反馈提前消失（无气泡感）。
                                // outOfBounds 取消但拖动未激活时立即清理，防止状态泄漏。
                                if (released || !dragActivated) {
                                    isPressed = false
                                    currentOnRelease?.invoke()
                                }
                            },
                            onTap = {
                                if (!dragActivated && !hasTriggeredSwipeUp && !hasTriggeredSwipeDown) currentOnClick()
                            }
                        )
                    } else {
                        detectTapGestures(
                            onPress = {
                                isPressed = true
                                longPressActivated = false
                                onPress?.invoke()
                                val released = tryAwaitRelease()
                                if (released || !dragActivated) {
                                    isPressed = false
                                    currentOnRelease?.invoke()
                                }
                            },
                            onTap = {
                                if (!dragActivated && !hasTriggeredSwipeUp && !hasTriggeredSwipeDown && !longPressActivated) {
                                    currentOnClick()
                                }
                                longPressActivated = false
                            },
                            onLongPress = {
                                longPressActivated = true
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                currentOnLongClick?.invoke()
                            }
                        )
                    }
                }
            .padding(LocalKeyVisualPadding.current)
            .then(shadowModifier)
            .clip(keyClipShape)
            .background(
                if (isPressed) darkenColor(backgroundColor, 0.2f)
                else if (isHighlighted) backgroundColor.copy(alpha = 0.8f)
                else backgroundColor
            )
            .keySemantics(a11yDescription, a11yState) { currentOnClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = fontSize ?: if (text.length > 2) 14.sp else 16.sp,
            fontWeight = if (text.length > 2) FontWeight.Medium else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            fontFamily = keyFontFamily
        )
        
        if (!swipeText.isNullOrEmpty()) {
            val displayText = if (swipeText.length <= 4) swipeText else swipeText.take(4)
            Text(
                text = displayText,
                color = textColor.copy(alpha = 0.5f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.offset(y = (-14).dp),
                fontFamily = keyLabelFontFamily
            )
        }
        
        if (badgeText != null) {
            Text(
                text = badgeText,
                color = textColor.copy(alpha = 0.5f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp),
                fontFamily = keyLabelFontFamily
            )
        }
    }
}

@Composable
fun SwipeableKeyButton(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    layoutMode: ButtonLayout = ButtonLayout.STANDARD,
    icon: Painter? = null,
    /**
     * 按下键帽气泡文本；null = 不弹按压气泡（键配置 `tap.bubble: false`）。
     * 缺省跟随键面文本，与改动前行为一致。
     */
    pressText: String? = text,
    swipeText: String? = null,
    swipeDownText: String? = null,
    /** 下滑文本显示在按键上（气泡为空，用于 display:key） */
    swipeDownKeyLabel: String? = null,
    /** 上滑文本显示在按键上（气泡则为空，用于 display:bubble） */
    swipeUpKeyLabel: String? = null,
    onSwipe: ((String) -> Unit)? = null,
    onSwipeDown: ((String) -> Unit)? = null,
    /** 左/右滑动作；配置任一后该键横向滑动即接管，自动放弃父层光标手势。 */
    onSwipeLeft: (() -> Unit)? = null,
    onSwipeRight: (() -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    onLongPressSelect: ((String) -> Unit)? = null,
    longPressItems: List<String>? = null,
    longPressDrawableIds: List<Int>? = null,
    /** 右上角角标文字（如 T9 数字键的数字浮标） */
    badgeText: String? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    swipeFontSize: androidx.compose.ui.unit.TextUnit = 9.sp,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    /** TalkBack 朗读描述；缺省时靠键面 Text 朗读 */
    a11yDescription: String? = null,
    /** TalkBack 状态播报 */
    a11yState: String? = null,
) {
    var isPressed by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var hasTriggeredSwipeUp by remember { mutableStateOf(false) }
    var hasTriggeredSwipeDown by remember { mutableStateOf(false) }
    var hasTriggeredSwipeLeft by remember { mutableStateOf(false) }
    var hasTriggeredSwipeRight by remember { mutableStateOf(false) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var isSwiping by remember { mutableStateOf(false) }
    var isSwipeDown by remember { mutableStateOf(false) }
    var buttonBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    var dragActivated by remember { mutableStateOf(false) }
    // 按键坐标日志：记录本次按下的局部坐标（相邻键纠错数据采集，关闭时零开销）
    var lastPressOffset by remember { mutableStateOf(Offset.Zero) }
    
    val currentText by rememberUpdatedState(text)
    val currentSwipeText by rememberUpdatedState(swipeText)
    val currentSwipeDownText by rememberUpdatedState(swipeDownText)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val currentOnSwipeDown by rememberUpdatedState(onSwipeDown)
    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentOnSwipeRight by rememberUpdatedState(onSwipeRight)
    val currentOnSwipeStateChange by rememberUpdatedState(onSwipeStateChange)
    val currentPressText by rememberUpdatedState(pressText)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPressSelect by rememberUpdatedState(onLongPressSelect)
    val currentLongPressItems by rememberUpdatedState(longPressItems)
    val currentLongPressDrawableIds by rememberUpdatedState(longPressDrawableIds)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    
    val density = LocalDensity.current
    val swipeUpThreshold = with(density) { (-50).dp.toPx() }
    val swipeDownThreshold = with(density) { 50.dp.toPx() }
    val bubbleShowThresholdUp = swipeUpThreshold
    val bubbleShowThresholdDown = swipeDownThreshold
    // 水平位移超过该值视为横向手势（如键盘区滑动移动光标），不再触发点击。
    // 与 KeyboardView 光标手势激活阈值（activationThresholdPx = 60dp）对齐，
    // 消除 30~60dp 位移区间"点击被取消但光标手势未激活"的死区（打字吃键）。
    val horizontalClickCancelThreshold = with(density) { 60.dp.toPx() }
    // 左/右滑触发阈值
    val swipeLeftThreshold = with(density) { (-50).dp.toPx() }
    val swipeRightThreshold = with(density) { 50.dp.toPx() }
    // 横向接管阈值：低于父层光标手势激活阈值（60dp），使配置了左右滑的键优先接管横向滑动
    val horizontalSwipeSuppressThreshold = with(density) { 30.dp.toPx() }
    val suppressCursorMove = LocalSuppressCursorMove.current
    // 父层光标手势已接管横向滑动时，本键不再判定点击（否则会"移动光标 + 打出一个字母"）
    val cursorGestureActive = LocalCursorGestureActive.current

    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }
    val keyCornerRadius = LocalKeyCornerRadius.current
    val keyClipShape = remember(keyCornerRadius) { RoundedCornerShape(keyCornerRadius) }
    val keyLabelFontFamily = AppFonts.keyLabelFontFamily
    val keyFontFamily = AppFonts.keyFontFamily

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragActivated = true
                        isPressed = true
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                        hasTriggeredSwipeUp = false
                        hasTriggeredSwipeDown = false
                        hasTriggeredSwipeLeft = false
                        hasTriggeredSwipeRight = false
                        isSwiping = false
                        isSwipeDown = false
                    },
                    onDragEnd = {
                        val shouldClick = !cursorGestureActive.value &&
                            !hasTriggeredSwipeUp && !hasTriggeredSwipeDown &&
                            !hasTriggeredSwipeLeft && !hasTriggeredSwipeRight &&
                            abs(dragOffsetX) < horizontalClickCancelThreshold
                        if (shouldClick) {
                            currentOnClick()
                        }
                        isPressed = false
                        currentOnRelease?.invoke()
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                        hasTriggeredSwipeUp = false
                        hasTriggeredSwipeDown = false
                        hasTriggeredSwipeLeft = false
                        hasTriggeredSwipeRight = false
                        isSwiping = false
                        isSwipeDown = false
                        dragActivated = false
                        currentOnSwipeStateChange?.invoke(SwipeState(false, null, false, emptyList(), false, null), buttonBounds)
                    },
                    onDragCancel = {
                        isPressed = false
                        currentOnRelease?.invoke()
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                        hasTriggeredSwipeUp = false
                        hasTriggeredSwipeDown = false
                        hasTriggeredSwipeLeft = false
                        hasTriggeredSwipeRight = false
                        isSwiping = false
                        isSwipeDown = false
                        dragActivated = false
                        currentOnSwipeStateChange?.invoke(SwipeState(false, null, false, emptyList(), false, null), buttonBounds)
                    },
                    onDrag = { change, dragAmount ->
                        dragOffsetX += dragAmount.x
                        dragOffsetY += dragAmount.y

                        // 配置了左/右滑的键：横向滑动即接管，抑制父层光标手势
                        val onSwipeLeftAction = currentOnSwipeLeft
                        val onSwipeRightAction = currentOnSwipeRight
                        if (shouldSuppressCursorMove(
                                onSwipeLeftAction != null || onSwipeRightAction != null,
                                dragOffsetX, dragOffsetY, horizontalSwipeSuppressThreshold
                            )
                        ) {
                            suppressCursorMove.value = true
                        }
                        if (dragOffsetX < swipeLeftThreshold && !hasTriggeredSwipeLeft && onSwipeLeftAction != null) {
                            hasTriggeredSwipeLeft = true
                            onSwipeLeftAction()
                        }
                        if (dragOffsetX > swipeRightThreshold && !hasTriggeredSwipeRight && onSwipeRightAction != null) {
                            hasTriggeredSwipeRight = true
                            onSwipeRightAction()
                        }

                        if (dragOffsetY < 0) {
                            if (abs(dragOffsetY) > abs(dragOffsetX) * 1.1f) {
                                val shouldShowBubble = dragOffsetY < bubbleShowThresholdUp && currentSwipeText != null
                                if (shouldShowBubble != isSwiping) {
                                    isSwiping = shouldShowBubble
                                    isSwipeDown = false
                                    currentOnSwipeStateChange?.invoke(SwipeState(shouldShowBubble, currentSwipeText, false, emptyList(), false, null), buttonBounds)
                                }
                                
                                // 上滑触发只看回调绑定，不依赖提示文本（swipeText 仅控制气泡/键面提示）：
                                // 提示与手势相互独立，提示是否绘制由按键配置的 display/bubble 决定，
                                // 手势只取决于回调是否绑定，与下滑触发语义一致。
                                val onSwipeValue = currentOnSwipe
                                if (dragOffsetY < swipeUpThreshold && !hasTriggeredSwipeUp && onSwipeValue != null) {
                                    hasTriggeredSwipeUp = true
                                    onSwipeValue(currentSwipeText ?: "")
                                }
                            }
                        } else if (dragOffsetY > 0) {
                            if (dragOffsetY > abs(dragOffsetX) * 1.1f) {
                                val shouldShowBubble = dragOffsetY > bubbleShowThresholdDown && currentSwipeDownText != null
                                if (shouldShowBubble != isSwipeDown) {
                                    isSwipeDown = shouldShowBubble
                                    isSwiping = shouldShowBubble
                                    currentOnSwipeStateChange?.invoke(SwipeState(shouldShowBubble, currentSwipeDownText, true, emptyList(), false, null), buttonBounds)
                                }
                                
                                val swipeDownTextValue = currentSwipeDownText
                                val onSwipeDownValue = currentOnSwipeDown
                                if (dragOffsetY > swipeDownThreshold && !hasTriggeredSwipeDown && onSwipeDownValue != null) {
                                    hasTriggeredSwipeDown = true
                                    onSwipeDownValue(swipeDownTextValue ?: "")
                                }
                            }
                        }
                    }
                )
            }
            .pointerInput(text, currentLongPressItems.isNullOrEmpty()) {
                if (currentLongPressItems.isNullOrEmpty()) {
                    detectTapGestures(
                        onPress = { offset ->
                            lastPressOffset = offset
                            isPressed = true
                            currentOnSwipeStateChange?.invoke(SwipeState(isPressed = true, pressedText = currentPressText), buttonBounds)
                            currentOnPress?.invoke()
                            val released = tryAwaitRelease()
                            if (released || !dragActivated) {
                                isPressed = false
                                currentOnRelease?.invoke()
                                currentOnSwipeStateChange?.invoke(SwipeState(false, null, false, emptyList(), false, null), buttonBounds)
                            }
                        },
                        onTap = {
                            if (!dragActivated && !hasTriggeredSwipeUp && !hasTriggeredSwipeDown) {
                                KeyTapLogger.recordTap(
                                    currentText, lastPressOffset.x, lastPressOffset.y,
                                    buttonBounds, density.density
                                )
                                currentOnClick()
                            }
                        }
                    )
                    return@pointerInput
                }
                
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    var localLongPressTriggered = false
                    var selectedIdx = 0
                    val downX = down.position.x
                    val items = currentLongPressItems ?: return@awaitEachGesture
                    
                    currentOnSwipeStateChange?.invoke(
                        SwipeState(isPressed = true, pressedText = currentPressText), buttonBounds
                    )
                    currentOnPress?.invoke()
                    
                    val longPressJob = scope.launch {
                        delay(400L)
                        localLongPressTriggered = true
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        currentOnSwipeStateChange?.invoke(
                            SwipeState(
                                isPressed = true,
                                isLongPress = true,
                                longPressItems = items,
                                selectedLongPressIndex = 0,
                                longPressDrawableIds = currentLongPressDrawableIds ?: emptyList()
                            ),
                            buttonBounds
                        )
                    }
                    
                    val cancelThresholdPx = with(density) { 5.dp.toPx() }
                    val downY = down.position.y
                    var swipeDetected = false
                    
                    try {
                        var lastReportedIdx = -1
                        var completed = false
                        while (!completed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            
                            if (change.isConsumed) continue
                            
                            if (!localLongPressTriggered) {
                                val deltaX = change.position.x - downX
                                val deltaY = change.position.y - downY
                                if (kotlin.math.abs(deltaX) > cancelThresholdPx || kotlin.math.abs(deltaY) > cancelThresholdPx) {
                                    swipeDetected = true
                                    longPressJob.cancel()
                                }
                            }
                            
                            if (localLongPressTriggered) {
                                val deltaX = change.position.x - downX
                                val itemWidth = buttonBounds.width / items.size
                                selectedIdx = ((deltaX / itemWidth) + if (items.size > 1) 0.5f else 0f).toInt()
                                    .coerceIn(0, items.size - 1)
                                
                                if (selectedIdx != lastReportedIdx) {
                                    lastReportedIdx = selectedIdx
                                    currentOnSwipeStateChange?.invoke(
                                        SwipeState(
                                            isPressed = true,
                                            isLongPress = true,
                                            longPressItems = items,
                                            selectedLongPressIndex = selectedIdx,
                                            longPressDrawableIds = currentLongPressDrawableIds ?: emptyList()
                                        ),
                                        buttonBounds
                                    )
                                }
                                change.consume()
                            }
                            
                            if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Release) {
                                completed = true
                                if (localLongPressTriggered) {
                                    val selected = items.getOrNull(selectedIdx)
                                    if (selected != null) {
                                        KeyTapLogger.recordTap(selected, downX, downY, buttonBounds, density.density)
                                        currentOnLongPressSelect?.invoke(selected)
                                    }
                                } else if (!dragActivated) {
                                    // 注意：不能再用 swipeDetected 抑制点击——swipeDetected 由 5dp 位移触发，
                                    // 而 dragActivated 由 touch slop（更大）触发。两者之间的位移区间
                                    // （5dp~touchSlop）若被 swipeDetected 吞掉点击，且 drag 未激活无 dragEnd
                                    // 兜底，会造成快速打字漏键（吃键）。5dp 位移只用于取消长按（longPressJob）。
                                    KeyTapLogger.recordTap(currentText, downX, downY, buttonBounds, density.density)
                                    currentOnClick()
                                }
                            }
                        }
                    } finally {
                        longPressJob.cancel()
                        isPressed = false
                        currentOnRelease?.invoke()
                        currentOnSwipeStateChange?.invoke(SwipeState(), buttonBounds)
                    }
                }
            }
            .onGloballyPositioned { coordinates ->
                buttonBounds = coordinates.boundsInRoot()
            }
            .padding(LocalKeyVisualPadding.current)
            .then(shadowModifier)
            .clip(keyClipShape)
            .background(
                if (isPressed) backgroundColor.copy(alpha = 0.7f)
                else if (isHighlighted) backgroundColor.copy(alpha = 0.8f)
                else backgroundColor
            )
            .keySemantics(a11yDescription, a11yState) { currentOnClick() },
        contentAlignment = if (layoutMode == ButtonLayout.COMPACT) Alignment.TopStart else Alignment.Center
    ) {
        val contentScale = adaptiveKeyContentScale(maxHeight.value)
        val hintScale = adaptiveHintScale(contentScale)
        val hintOffset = adaptiveHintOffsetDp(contentScale).dp
        val effectiveSwipeFontSize = (swipeFontSize.value * hintScale).sp

        if (layoutMode == ButtonLayout.COMPACT) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (icon != null) {
                    Icon(
                        painter = icon,
                        contentDescription = text,
                        tint = textColor,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 2.dp, start = 4.dp)
                            .size(16.dp)
                    )
                } else {
                    Text(
                        text = text,
                        color = textColor,
                        fontSize = ((if (fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) fontSize.value else if (text.length > 2) 13f else 16f) * contentScale).sp,
                        fontWeight = if (text.length > 2) FontWeight.Medium else FontWeight.Normal,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        lineHeight = 1.sp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 2.dp, start = 4.dp),
                        fontFamily = keyFontFamily
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxHeight()
                        .padding(top = 4.dp, end = 4.dp, bottom = 2.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    val swipeUpHint = swipeUpKeyLabel ?: swipeText
                    if (!swipeUpHint.isNullOrEmpty()) {
                        val displayText = if (swipeUpHint.length <= 2) swipeUpHint else swipeUpHint.take(2)
                        Text(
                            text = displayText,
                            color = textColor.copy(alpha = 0.6f),
                            fontSize = effectiveSwipeFontSize,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            lineHeight = 1.sp
                        )
                    }

                    val swipeDownHint = swipeDownKeyLabel
                    if (!swipeDownHint.isNullOrEmpty()) {
                        val hasChinese = swipeDownHint.any { it in '\u4e00'..'\u9fff' || it in '\u3400'..'\u4dbf' || it in '\uf900'..'\ufaff' }
                        val adjustedFontSize = if (hasChinese && effectiveSwipeFontSize > 6.sp) (effectiveSwipeFontSize.value * 0.85f).sp else effectiveSwipeFontSize
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.BottomEnd
                        ) {
                            val displayText = if (swipeDownHint.length <= 12) swipeDownHint else swipeDownHint.take(12)
                            Text(
                                text = displayText,
                                color = textColor.copy(alpha = 0.7f),
                                fontSize = adjustedFontSize,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Right,
                                maxLines = 3,
                                lineHeight = adjustedFontSize,
                                fontFamily = keyLabelFontFamily
                            )
                        }
                    }
                }
            }
        } else {
            if (icon != null) {
                Icon(
                    painter = icon,
                    contentDescription = text,
                    tint = textColor,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    text = text,
                    color = textColor,
                    fontSize = ((if (fontSize != androidx.compose.ui.unit.TextUnit.Unspecified) fontSize.value else if (text.length > 2) 14f else 18f) * contentScale).sp,
                    fontWeight = if (text.length > 2) FontWeight.Medium else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    fontFamily = keyFontFamily
                )
            }

            // 上滑提示与角标文字相同（如九键/笔画上滑输入键面数字）时不再重复渲染提示，
            // 角标已表达该信息；swipeText 状态保持非空，上滑触发与气泡不受影响。
            // 键矮场景（LocalSwipeHintCorner，手机横屏）：提示画角标（上=右上角、下=底部），
            // 与 SwipeableKeyButtonLandscape 同款，避免 offset 提示被键盘顶部裁掉。
            val swipeHintCorner = LocalSwipeHintCorner.current
            if (!(swipeUpKeyLabel ?: swipeText).isNullOrEmpty() && (swipeUpKeyLabel ?: swipeText) != badgeText) {
                val keyLabel = (swipeUpKeyLabel ?: swipeText)!!
                // 含换行的多行键面提示（如双拼韵母助记 "ue\nve"）：完整按行渲染；
                // 单行沿用 ≤4 字符截断，行为不变。角标模式的 1.sp 行高会把多行叠死，仅单行适用。
                val hintLineCount = keyLabel.count { it == '\n' } + 1
                val displayText = if (hintLineCount > 1) keyLabel
                    else if (keyLabel.length <= 4) keyLabel else keyLabel.take(4)
                Text(
                    text = displayText,
                    color = textColor.copy(alpha = 0.6f),
                    fontSize = effectiveSwipeFontSize,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = hintLineCount,
                    // 角标模式压掉默认行框：标签字体 metrics 的 ascent 大，不压行高字形会
                    // 沉在行框下半部，视觉上贴不到键顶（与下方 badge 的 1.sp 同款）
                    lineHeight = if (swipeHintCorner && hintLineCount == 1) 1.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                    modifier = if (swipeHintCorner) Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 4.dp)
                    else Modifier.offset(y = -hintOffset),
                    fontFamily = keyLabelFontFamily
                )
            }

            if (!swipeDownKeyLabel.isNullOrEmpty()) {
                // 多行处理与上滑提示同款（含 \n 完整渲染，单行 ≤4 字符截断）
                val hintLineCount = swipeDownKeyLabel.count { it == '\n' } + 1
                val displayText = if (hintLineCount > 1) swipeDownKeyLabel
                    else if (swipeDownKeyLabel.length <= 4) swipeDownKeyLabel else swipeDownKeyLabel.take(4)
                Text(
                    text = displayText,
                    color = textColor.copy(alpha = 0.5f),
                    fontSize = effectiveSwipeFontSize,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = hintLineCount,
                    lineHeight = if (swipeHintCorner && hintLineCount == 1) 1.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                    modifier = if (swipeHintCorner) Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 4.dp, bottom = 2.dp)
                    else Modifier.offset(y = hintOffset),
                    fontFamily = keyLabelFontFamily
                )
            }

            if (badgeText != null) {
                Text(
                    text = badgeText,
                    color = textColor.copy(alpha = 0.5f),
                    fontSize = (10f * hintScale).sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    lineHeight = 1.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                )
            }
        }
    }
}

@Composable
fun KeyboardRow(
    keys: List<String>,
    onKeyPress: (String) -> Unit,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    isShifted: Boolean,
    modifier: Modifier = Modifier,
    swipeKeys: List<String>? = null,
    swipeDownKeys: List<String>? = null,
    onSwipeKey: ((String) -> Unit)? = null,
    onSwipeDownKey: ((String) -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    onKeyPressDown: ((String) -> Unit)? = null,
    onKeyRelease: ((String) -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
    ) {
        keys.forEachIndexed { index, key ->
            val swipeText = swipeKeys?.getOrNull(index)
            val swipeDownText = swipeDownKeys?.getOrNull(index)
            val rowOnClick = remember(key, onKeyPress) { { onKeyPress(key) } }
            val rowOnPress: (() -> Unit)? = remember(key, onKeyPressDown) { { onKeyPressDown?.invoke(key); Unit } }
            val rowOnRelease: (() -> Unit)? = remember(key, onKeyRelease) { { onKeyRelease?.invoke(key); Unit } }
            SwipeableKeyButton(
                text = if (isShifted) key.uppercase() else key,
                onClick = rowOnClick,
                backgroundColor = keyBackgroundColor,
                textColor = keyTextColor,
                modifier = Modifier.weight(1f),
                swipeText = swipeText,
                swipeDownText = swipeDownText,
                onSwipe = onSwipeKey,
                onSwipeDown = onSwipeDownKey,
                onSwipeStateChange = onSwipeStateChange,
                onPress = rowOnPress,
                onRelease = rowOnRelease
            )
        }
    }
}

@Composable
fun IconKeyButton(
    icon: Painter,
    onClick: () -> Unit,
    backgroundColor: Color,
    iconColor: Color,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    /** TalkBack 朗读描述（图标键必传，否则读屏读"未标记"） */
    a11yDescription: String? = null,
    /** TalkBack 状态播报 */
    a11yState: String? = null,
) {
    var isPressed by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val currentOnClick by rememberUpdatedState(onClick)

    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }
    val keyCornerRadius = LocalKeyCornerRadius.current
    val keyClipShape = remember(keyCornerRadius) { RoundedCornerShape(keyCornerRadius) }
    
    // 辅助函数：生成更深的颜色（混合黑色）
    fun darkenColor(color: Color, factor: Float = 0.15f): Color {
        return Color(
            red = (color.red * (1 - factor)).coerceIn(0f, 1f),
            green = (color.green * (1 - factor)).coerceIn(0f, 1f),
            blue = (color.blue * (1 - factor)).coerceIn(0f, 1f),
            alpha = color.alpha
        )
    }
    
    Box(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onPress?.invoke()
                        tryAwaitRelease()
                        isPressed = false
                        onRelease?.invoke()
                    },
                    onTap = {
                        onClick()
                    }
                )
            }
            .padding(LocalKeyVisualPadding.current)
            .then(shadowModifier)
            .clip(keyClipShape)
            .background(
                if (isPressed) darkenColor(backgroundColor, 0.1f)
                else if (isHighlighted) darkenColor(backgroundColor, 0.2f)
                else backgroundColor
            )
            .keySemantics(a11yDescription, a11yState) { currentOnClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(iconSize)
        )

        // 右上角小圆点指示 — 仅在 isHighlighted 时显示
        if (isHighlighted) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(iconColor)
            )
        }
    }
}

@Composable
fun SwipeableIconKeyButton(
    icon: Painter,
    onClick: () -> Unit,
    backgroundColor: Color,
    iconColor: Color,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
    swipeText: String? = null,
    onSwipe: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    // 上滑/下滑/左滑增强
    swipeUpLabel: String? = null,
    swipeDownLabel: String? = null,
    onSwipeUp: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
    /** 右滑动作；与 [onSwipeLeft] 任一配置后，该键横向滑动即接管，自动放弃父层光标手势。 */
    onSwipeRight: (() -> Unit)? = null,
    onSwipeStateChange: ((SwipeState, Rect) -> Unit)? = null,
    shadowEnabled: Boolean = true,
    shadowElevation: Dp = 1.dp,
    shadowShapeRadius: Dp = 8.dp,
    /** TalkBack 朗读描述（图标键必传，否则读屏读"未标记"） */
    a11yDescription: String? = null,
    /** TalkBack 状态播报 */
    a11yState: String? = null,
) {
    var isPressed by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var hasTriggeredSwipe by remember { mutableStateOf(false) }
    var hasTriggeredSwipeDown by remember { mutableStateOf(false) }
    var hasTriggeredSwipeLeft by remember { mutableStateOf(false) }
    var hasTriggeredSwipeRight by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var isSwipingUp by remember { mutableStateOf(false) }
    var isSwipingDown by remember { mutableStateOf(false) }
    var isDangerZone by remember { mutableStateOf(false) }
    var hasReachedClearThreshold by remember { mutableStateOf(false) }
    var hasReachedUndoThreshold by remember { mutableStateOf(false) }
    var isLongPress by remember { mutableStateOf(false) }
    var hasTriggeredLongPress by remember { mutableStateOf(false) }
    var buttonBounds by remember { mutableStateOf(Rect(0f, 0f, 0f, 0f)) }
    var dragActivated by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentOnSwipeRight by rememberUpdatedState(onSwipeRight)
    val keyLabelFontFamily = AppFonts.keyLabelFontFamily
    
    val density = LocalDensity.current
    val swipeUpThreshold = with(density) { (-50).dp.toPx() }
    val swipeDownThreshold = with(density) { 50.dp.toPx() }
    val swipeLeftThreshold = with(density) { (-50).dp.toPx() }
    val swipeRightThreshold = with(density) { 50.dp.toPx() }
    // 横向接管阈值：低于父层光标手势激活阈值（60dp），使配置了左右滑的键优先接管横向滑动
    val horizontalSwipeSuppressThreshold = with(density) { 30.dp.toPx() }
    val suppressCursorMove = LocalSuppressCursorMove.current
    // 父层光标手势已接管横向滑动时，本键不再判定点击（否则会"移动光标 + 打出一个字母"）
    val cursorGestureActive = LocalCursorGestureActive.current
    val bubbleShowThresholdUp = swipeUpThreshold
    val bubbleShowThresholdDown = swipeDownThreshold
    
    // 上滑清空/下滑撤回需要更大的滑动距离，防止误触
    val clearActionThreshold = with(density) { (-50).dp.toPx() }
    val undoActionThreshold = with(density) { 50.dp.toPx() }
    // 水平位移超过该值视为横向手势（如键盘区滑动移动光标），不再触发点击。
    // 与 KeyboardView 光标手势激活阈值（activationThresholdPx = 60dp）对齐，
    // 消除 30~60dp 位移区间"点击被取消但光标手势未激活"的死区（打字吃键）。
    val horizontalClickCancelThreshold = with(density) { 60.dp.toPx() }
    
    LaunchedEffect(isLongPress) {
        if (isLongPress && onLongClick != null) {
            hasTriggeredLongPress = true
            while (isLongPress) {
                onLongClick()
                // 长按重复间隔 30ms：80ms 时退格删除以 12.5Hz 离散更新
                // 候选栏，低于视觉融合阈值，看起来像"一闪一闪"；30ms 时更新更密集更顺滑。
                delay(30)
            }
        }
    }

    val shadowModifier = remember(shadowEnabled, shadowElevation, shadowShapeRadius, density, backgroundColor) {
        if (shadowEnabled) {
            val offsetPx = with(density) { shadowElevation.toPx() }
            val cornerPx = with(density) { shadowShapeRadius.toPx() }
            val color = crispShadowColor(backgroundColor)
            Modifier.drawBehind {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, offsetPx),
                    size = size,
                    cornerRadius = CornerRadius(cornerPx)
                )
            }
        } else Modifier
    }
    val keyCornerRadius = LocalKeyCornerRadius.current
    val keyClipShape = remember(keyCornerRadius) { RoundedCornerShape(keyCornerRadius) }
    
    fun darkenColor(color: Color, factor: Float = 0.15f): Color {
        return Color(
            red = (color.red * (1 - factor)).coerceIn(0f, 1f),
            green = (color.green * (1 - factor)).coerceIn(0f, 1f),
            blue = (color.blue * (1 - factor)).coerceIn(0f, 1f),
            alpha = color.alpha
        )
    }
    
    Box(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        onPress?.invoke()
                        val released = tryAwaitRelease()
                        if (released || !dragActivated) {
                            isPressed = false
                            currentOnRelease?.invoke()
                            isLongPress = false
                            // 长按结束后必须重置：onTap 不会在长按后触发，
                            // 若残留 true 会吞掉下一次点击（退格键吃键）。
                            // onDragEnd/onDragCancel 虽也重置，但仅 drag 激活时触发，
                            // 长按无位移（drag 未激活）时走不到那里。
                            hasTriggeredLongPress = false
                        }
                    },
                    onTap = {
                        if (!dragActivated && !isDragging && !hasTriggeredLongPress) {
                            onClick()
                        }
                        hasTriggeredLongPress = false
                    },
                    onLongPress = {
                        isLongPress = true
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragActivated = true
                        isDragging = true
                        isPressed = true
                        dragOffsetY = 0f
                        dragOffsetX = 0f
                        hasTriggeredSwipe = false
                        hasTriggeredSwipeDown = false
                        hasTriggeredSwipeLeft = false
                        hasTriggeredSwipeRight = false
                        isSwipingUp = false
                        isSwipingDown = false
                        isDangerZone = false
                        hasReachedClearThreshold = false
                        hasReachedUndoThreshold = false
                        onSwipeStateChange?.invoke(SwipeState(), buttonBounds)
                        onPress?.invoke()
                    },
                    onDragEnd = {
                        if (hasReachedClearThreshold && onSwipeUp != null) {
                            onSwipeUp()
                        } else if (hasReachedUndoThreshold && onSwipeDown != null) {
                            onSwipeDown()
                        } else if (isSwipingUp && !hasTriggeredSwipe && onSwipe != null) {
                            hasTriggeredSwipe = true
                            onSwipe()
                        } else if (dragOffsetY < swipeUpThreshold && !hasTriggeredSwipe && onSwipe != null) {
                            hasTriggeredSwipe = true
                            onSwipe()
                        } else if (
                            !cursorGestureActive.value && !hasTriggeredLongPress &&
                            !hasTriggeredSwipeLeft && !hasTriggeredSwipeRight
                        ) {
                            currentOnClick()
                        }
                        dragActivated = false
                        isPressed = false
                        currentOnRelease?.invoke()
                        dragOffsetY = 0f
                        dragOffsetX = 0f
                        hasTriggeredSwipe = false
                        hasTriggeredSwipeDown = false
                        hasTriggeredSwipeLeft = false
                        hasTriggeredSwipeRight = false
                        isDragging = false
                        isSwipingUp = false
                        isSwipingDown = false
                        isDangerZone = false
                        hasReachedClearThreshold = false
                        hasReachedUndoThreshold = false
                        isLongPress = false
                        // 手势结束（含位移场景 tap 取消）必须重置，否则残留 true 会吞掉后续点击
                        hasTriggeredLongPress = false
                        onSwipeStateChange?.invoke(SwipeState(), buttonBounds)
                    },
                    onDragCancel = {
                        dragActivated = false
                        isPressed = false
                        currentOnRelease?.invoke()
                        dragOffsetY = 0f
                        dragOffsetX = 0f
                        hasTriggeredSwipe = false
                        hasTriggeredSwipeDown = false
                        hasTriggeredSwipeLeft = false
                        hasTriggeredSwipeRight = false
                        isDragging = false
                        isSwipingUp = false
                        isSwipingDown = false
                        isDangerZone = false
                        hasReachedClearThreshold = false
                        hasReachedUndoThreshold = false
                        isLongPress = false
                        hasTriggeredLongPress = false
                        onSwipeStateChange?.invoke(SwipeState(), buttonBounds)
                    },
                    onDrag = { change, dragAmount ->
                        dragOffsetY += dragAmount.y
                        dragOffsetX += dragAmount.x

                        // 配置了左/右滑的键：横向滑动即接管，抑制父层光标手势
                        val onSwipeLeftAction = currentOnSwipeLeft
                        val onSwipeRightAction = currentOnSwipeRight
                        if (shouldSuppressCursorMove(
                                onSwipeLeftAction != null || onSwipeRightAction != null,
                                dragOffsetX, dragOffsetY, horizontalSwipeSuppressThreshold
                            )
                        ) {
                            suppressCursorMove.value = true
                        }

                        // 位移超过手势阈值才打断长按（轻微抖动不中断重复删除），
                        // 阈值与各手势触发阈值一致（左滑 -50dp / 上滑 -50dp / 下滑 50dp / 右滑 60dp）
                        if (isLongPress && (dragOffsetY < swipeUpThreshold || dragOffsetY > swipeDownThreshold || dragOffsetX < swipeLeftThreshold || dragOffsetX > horizontalClickCancelThreshold)) {
                            isLongPress = false
                        }
                        
                        if (dragOffsetX < swipeLeftThreshold && !hasTriggeredSwipeLeft && onSwipeLeftAction != null) {
                            hasTriggeredSwipeLeft = true
                            onSwipeLeftAction()
                        }

                        if (dragOffsetX > swipeRightThreshold && !hasTriggeredSwipeRight && onSwipeRightAction != null) {
                            hasTriggeredSwipeRight = true
                            onSwipeRightAction()
                        }
                        
                        if (dragOffsetY < 0 && dragOffsetX >= swipeLeftThreshold) {
                            val showUp = dragOffsetY < bubbleShowThresholdUp && swipeUpLabel != null
                            if (showUp != isSwipingUp) {
                                isSwipingUp = showUp
                                isSwipingDown = false
                                onSwipeStateChange?.invoke(
                                    SwipeState(isSwiping = showUp, swipeText = swipeUpLabel, isSwipeDown = false),
                                    buttonBounds
                                )
                            }
                            
                            val inDanger = dragOffsetY < clearActionThreshold
                            if (inDanger != isDangerZone) {
                                isDangerZone = inDanger
                                onSwipeStateChange?.invoke(
                                    SwipeState(isSwiping = true, swipeText = swipeUpLabel, isSwipeDown = false, isDanger = inDanger),
                                    buttonBounds
                                )
                            }
                            
                            hasReachedClearThreshold = inDanger
                        }
                        
                        if (dragOffsetY > 0 && dragOffsetX >= swipeLeftThreshold) {
                            val showDown = dragOffsetY > bubbleShowThresholdDown && swipeDownLabel != null
                            if (showDown != isSwipingDown) {
                                isSwipingDown = showDown
                                isSwipingUp = false
                                onSwipeStateChange?.invoke(
                                    SwipeState(isSwiping = showDown, swipeText = swipeDownLabel, isSwipeDown = true),
                                    buttonBounds
                                )
                            }
                            
                            val inDanger = dragOffsetY > undoActionThreshold
                            if (inDanger != isDangerZone) {
                                isDangerZone = inDanger
                                onSwipeStateChange?.invoke(
                                    SwipeState(isSwiping = true, swipeText = swipeDownLabel, isSwipeDown = true, isDanger = inDanger),
                                    buttonBounds
                                )
                            }
                            
                            hasReachedUndoThreshold = inDanger
                        }
                    }
                )
            }
            .onGloballyPositioned { coordinates ->
                buttonBounds = coordinates.boundsInRoot()
            }
            .padding(LocalKeyVisualPadding.current)
            .then(shadowModifier)
            .clip(keyClipShape)
            .background(
                if (isPressed) darkenColor(backgroundColor, 0.2f)
                else if (isHighlighted) backgroundColor.copy(alpha = 0.8f)
                else backgroundColor
            )
            .keySemantics(a11yDescription, a11yState) { currentOnClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(iconSize)
        )
        
        if (!swipeText.isNullOrEmpty()) {
            // 键矮场景（LocalSwipeHintCorner，手机横屏）：提示画右上角，避免 offset 提示被键盘顶部裁掉
            val hintCorner = LocalSwipeHintCorner.current
            Text(
                text = swipeText,
                color = iconColor.copy(alpha = 0.5f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                lineHeight = if (hintCorner) 1.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                modifier = if (hintCorner) Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 4.dp)
                else Modifier.offset(y = (-14).dp),
                fontFamily = keyLabelFontFamily
            )
        }
    }
}
