package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 候选展开页数据。
 *
 * @param keyBackgroundColor 左右两栏按键底色（键盘按键色）；[Color.Unspecified] 时
 *                          用 textColor 半透明兜底，保证单独预览时也不失形。
 */
data class CandidatePageState(
    val candidates: List<String>,
    val candidateComments: List<String> = emptyList(),
    val associationCandidates: List<String> = emptyList(),
    val backgroundColor: Color,
    val textColor: Color,
    val keyBackgroundColor: Color = Color.Unspecified,
    val hasNextPage: Boolean = false,
    val hasPrevPage: Boolean = false,
    val bottomPaddingDp: Int = 0,
)

/**
 * 候选展开页回调。
 *
 * @param onCommitText 左栏快捷符号上屏
 * @param onDelete     右栏退格键
 * @param onEnter      右栏回车键
 * @param onBack       顶部栏收起按钮，关闭展开页回到键盘
 */
data class CandidatePageCallbacks(
    val onCandidateSelect: (Int) -> Unit,
    val onAssociationSelect: ((Int) -> Unit)? = null,
    val onPageDown: (() -> Unit)? = null,
    val onPageUp: (() -> Unit)? = null,
    val onCommitText: ((String) -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    val onEnter: (() -> Unit)? = null,
)

/** 左栏快捷符号（对齐主流输入法候选展开页的符号栏）。 */
private val QUICK_SYMBOLS = listOf("？", "！", "……", "~")

/**
 * 候选展开页主体（三栏）——渲染在真实候选栏正下方（候选栏的在位展开态，非 Overlay 页）：
 * ┌──────┬──────────────────────────────┬───────┐
 * │ ？   │  候选 │ 候选 │ 候选 │ 候选 │ 候选 │ │ 退格  │
 * │ ！   │  候选 │ 候选 │ 候选 │ 候选 │ 候选 │ │ 上一页│
 * │ ……   │  （竖分隔线网格，联想词在下方）   │ │ 下一页│
 * │ ~    │                              │ │ 回车  │
 * └──────┴──────────────────────────────┴───────┘
 * 收起按钮在上方候选栏右侧；编码删空时由宿主自动收起本页。
 */
@Composable
fun CandidatePage(
    state: CandidatePageState,
    callbacks: CandidatePageCallbacks,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val isLandscape =
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val gridColumns = if (isLandscape) 8 else 5
    val leftRailWidth = if (isLandscape) 48.dp else 40.dp
    val rightRailWidth = if (isLandscape) 40.dp else 46.dp
    val keyBg = if (state.keyBackgroundColor == Color.Unspecified)
        state.textColor.copy(alpha = 0.12f) else state.keyBackgroundColor
    val dividerColor = state.textColor.copy(alpha = 0.12f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(state.backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // ── 左栏：快捷符号 ──
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(leftRailWidth)
            ) {
                QUICK_SYMBOLS.forEach { symbol ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .tolerantClick { callbacks.onCommitText?.invoke(symbol) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = symbol,
                            color = state.textColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            fontFamily = AppFonts.candidateFontFamily
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(dividerColor)
            )
            Spacer(modifier = Modifier.width(8.dp))

            // ── 中间：候选网格（行等分撑满剩余高度） ──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                if (state.candidates.isNotEmpty()) {
                    CandidateGrid(
                        modifier = Modifier.weight(1f),
                        candidates = state.candidates,
                        comments = state.candidateComments,
                        columns = gridColumns,
                        textColor = state.textColor,
                        onSelect = callbacks.onCandidateSelect,
                    )
                }
                if (state.associationCandidates.isNotEmpty()) {
                    if (state.candidates.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(dividerColor)
                        )
                    }
                    CandidateGrid(
                        modifier = Modifier.weight(1f),
                        candidates = state.associationCandidates,
                        comments = emptyList(),
                        columns = gridColumns,
                        textColor = state.textColor,
                        onSelect = { index -> callbacks.onAssociationSelect?.invoke(index) },
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // ── 右栏：退格 / 上一页 / 下一页 / 回车 ──
            // 竖屏固定方块、垂直居中分布；横屏栏高有限改为等分压缩
            val railKeyModifier = if (isLandscape) Modifier.weight(1f) else Modifier.size(46.dp)
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(rightRailWidth),
                verticalArrangement = if (isLandscape) Arrangement.spacedBy(4.dp)
                else Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
            ) {
                RailKey(
                    onClick = { callbacks.onDelete?.invoke() },
                    keyBg = keyBg,
                    modifier = railKeyModifier,
                    enabled = callbacks.onDelete != null
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "退格",
                        tint = state.textColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                RailKey(
                    onClick = { callbacks.onPageUp?.invoke() },
                    keyBg = keyBg,
                    modifier = railKeyModifier,
                    enabled = state.hasPrevPage && callbacks.onPageUp != null
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = "上一页",
                        tint = if (state.hasPrevPage) state.textColor else state.textColor.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                RailKey(
                    onClick = { callbacks.onPageDown?.invoke() },
                    keyBg = keyBg,
                    modifier = railKeyModifier,
                    enabled = state.hasNextPage && callbacks.onPageDown != null
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "下一页",
                        tint = if (state.hasNextPage) state.textColor else state.textColor.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                RailKey(
                    onClick = { callbacks.onEnter?.invoke() },
                    keyBg = keyBg,
                    modifier = railKeyModifier,
                    enabled = callbacks.onEnter != null
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardReturn,
                        contentDescription = "回车",
                        tint = state.textColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 底部留白
        Spacer(
            modifier = Modifier.height(
                if (isLandscape) 15.dp else state.bottomPaddingDp.dp
            )
        )
    }
}

/**
 * 候选网格：固定 [columns] 列，行内条目以竖分隔线相隔（对齐参考样式）。
 * [modifier] 决定网格占位（调用方用 weight 等分），行数在网格内等分高度。
 */
@Composable
private fun CandidateGrid(
    modifier: Modifier = Modifier,
    candidates: List<String>,
    comments: List<String>,
    columns: Int,
    textColor: Color,
    onSelect: (Int) -> Unit,
) {
    val rows = candidates.chunked(columns)
    Column(modifier = modifier.fillMaxWidth()) {
        rows.forEachIndexed { rowIndex, rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                rowItems.forEachIndexed { colIndex, candidate ->
                    if (colIndex > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight(0.5f)
                                .width(1.dp)
                                .align(Alignment.CenterVertically)
                                .background(textColor.copy(alpha = 0.15f))
                        )
                    }
                    CandidateGridCell(
                        text = candidate,
                        comment = comments.getOrElse(rowIndex * columns + colIndex) { "" },
                        onClick = { onSelect(rowIndex * columns + colIndex) },
                        textColor = textColor,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

/** 网格单元：候选文本（附拼音注释）居中，按压高亮与主键盘同风格。 */
@Composable
private fun CandidateGridCell(
    text: String,
    comment: String,
    onClick: () -> Unit,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val displayComment = comment.replace("~", "")
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isPressed) textColor.copy(alpha = 0.12f) else Color.Transparent)
            .tolerantClick(
                showRipple = false,
                interactionSource = interactionSource,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                color = textColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                fontFamily = AppFonts.candidateFontFamily
            )
            if (displayComment.isNotEmpty()) {
                Text(
                    text = displayComment,
                    color = textColor.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 2.dp),
                    fontFamily = AppFonts.commentFontFamily
                )
            }
        }
    }
}

/** 右栏实体按键：圆角方块、按压加深（enabled=false 时淡化）。等分栏高由调用方传 weight。 */
@Composable
private fun RailKey(
    onClick: () -> Unit,
    keyBg: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    !enabled -> keyBg.copy(alpha = 0.4f)
                    isPressed -> keyBg.copy(alpha = 0.7f)
                    else -> keyBg
                }
            )
            .tolerantClick(
                enabled = enabled,
                showRipple = false,
                interactionSource = interactionSource,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center,
        content = content
    )
}
