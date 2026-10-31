package com.kingzcheung.xime.ui.menubar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.R
import com.kingzcheung.xime.i18n.tr
import com.kingzcheung.xime.keyboard.ToolbarButton
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import com.kingzcheung.xime.keyboard.isBuiltinToolbarButtonAvailable
import com.kingzcheung.xime.ui.keyboard.ToolbarButtonIcon
import com.kingzcheung.xime.ui.keyboard.WIDE_CONTAINER_WIDTH

@Composable
fun ToolbarCustomizeView(
    toolbarButtons: List<String>,
    pluginButtons: List<ToolbarButtonItem.Plugin> = emptyList(),
    isSttEnabled: Boolean = true,
    keyTextColor: Color,
    backgroundColor: Color,
    accentColor: Color,
    keyBgColor: Color,
    onUpdateToolbarButtons: ((List<String>) -> Unit)?,
    onDismiss: () -> Unit,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 与候选栏显示同一口径：关掉语音转文本、未装手写模型时，定制列表里也不该出现
    val builtinButtons = ToolbarButton.entries
        .filter {
            isBuiltinToolbarButtonAvailable(
                it,
                isSttEnabled = isSttEnabled,
                hasHandwritingModel = { com.kingzcheung.xime.handwriting.HandwritingEngine.hasModel(context) },
            )
        }
        .map { ToolbarButtonItem.Builtin(it) }
    val allButtons = builtinButtons + pluginButtons
    val itemById = remember(allButtons) { allButtons.associateBy { it.id } }
    val originalButtons = remember { toolbarButtons }
    var enabledIds by remember(toolbarButtons) { mutableStateOf(toolbarButtons.toSet()) }

    fun toggleButton(item: ToolbarButtonItem) {
        enabledIds = if (item.id in enabledIds) enabledIds - item.id else enabledIds + item.id
        val newList = toolbarButtons.toMutableList()
        if (item.id in toolbarButtons) newList.remove(item.id) else newList.add(item.id)
        onUpdateToolbarButtons?.invoke(newList)
    }

    // 图标按钮容器色：按键背景与强调色的混合色调（带主题色但不过于强烈）
    val iconButtonContainer = androidx.compose.ui.graphics.lerp(
        keyBgColor,
        accentColor,
        0.25f
    )

    // 布局按父容器真实宽度自适应（悬浮卡片/键盘收窄/分屏的容器宽 ≠ 屏幕宽），
    // 不读屏幕方向：宽容器（横屏全屏）用大边距，其余按竖屏形态
    BoxWithConstraints(modifier = modifier) {
        val isWide = maxWidth >= WIDE_CONTAINER_WIDTH

    val pages = allButtons.chunked(8).map { page ->
        page + List(8 - page.size) { null }
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                // 确认按钮与候选栏 logo 同位（8dp），不再随宽容器加大缩进
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(iconButtonContainer)
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = tr(R.string.s_queding),
                    tint = keyTextColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val previewButtons = toolbarButtons.mapNotNull { itemById[it] }
                if (previewButtons.isNotEmpty()) {
                    previewButtons.forEach { button ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(iconButtonContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            ToolbarButtonIcon(
                                item = button,
                                tint = keyTextColor.copy(0.6f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(keyBgColor)
                .padding(16.dp,10.dp)
        ) {
            if (isWide) {
                // 宽容器（横屏全屏）：固定每行 4 项会把格子撑到超宽、圆圈随宽度等比溢出卡片，
                // 改按可用宽度自适应列数、超出可滚动（与菜单栏横屏同款方案）
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 72.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(allButtons) { button ->
                        ToolbarToggleItem(
                            button = button,
                            isEnabled = button.id in enabledIds,
                            keyTextColor = keyTextColor,
                            accentColor = accentColor,
                            onToggle = { toggleButton(button) },
                        )
                    }
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth()
                ) { page ->
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        maxItemsInEachRow = 4
                    ) {
                        pages[page].forEach { button ->
                            if (button != null) {
                                ToolbarToggleItem(
                                    button = button,
                                    isEnabled = button.id in enabledIds,
                                    keyTextColor = keyTextColor,
                                    accentColor = accentColor,
                                    modifier = Modifier.weight(1f),
                                    onToggle = { toggleButton(button) },
                                )
                            } else {
                                Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                            }
                        }
                    }
                }
            }
        }
        if (!isWide && pages.size > 1) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pages.size) { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == pagerState.currentPage) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) keyTextColor
                                else keyTextColor.copy(alpha = 0.3f)
                            )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(if (isWide) 15.dp else bottomPaddingDp.dp))
    }
    }
}

@Composable
private fun ToolbarToggleItem(
    button: ToolbarButtonItem,
    isEnabled: Boolean,
    keyTextColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(
                    if (isEnabled) accentColor.copy(0.2f)
                    else Color.Transparent
                )
                .border(
                    width = 1.dp,
                    color = if (isEnabled) Color.Transparent
                    else keyTextColor.copy(alpha = 0.15f),
                    shape = CircleShape
                )
                .clickable { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            ToolbarButtonIcon(
                item = button,
                tint = if (isEnabled) accentColor else keyTextColor.copy(alpha = 0.8f),
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = button.label,
            fontSize = 10.sp,
            color = keyTextColor.copy(alpha = 0.8f),
            lineHeight = 1.sp,
            maxLines = 1
        )
    }
}
