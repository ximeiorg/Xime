package com.kingzcheung.xime.ui.menubar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.twotone.Image
import androidx.compose.material.icons.twotone.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kingzcheung.xime.R
import com.kingzcheung.xime.clipboard.ClipboardDragSender
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.clipboard.clipboardLongPressDrag
import com.kingzcheung.xime.i18n.tr
import com.kingzcheung.xime.ui.CLIPBOARD_CARD_MAX_PX
import com.kingzcheung.xime.ui.keyboard.WIDE_CONTAINER_WIDTH
import com.kingzcheung.xime.ui.keyboard.tolerantClick
import com.kingzcheung.xime.ui.rememberClipboardImageRequest
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import java.io.File

@Composable
fun ClipboardView(
    clipboardItems: List<ClipboardItem>,
    quickSendItems: List<ClipboardItem>,
    selectedTab: Int,
    backgroundColor: Color,
    keyTextColor: Color,
    keyBgColor: Color,
    viewModel: KeyboardViewModel,
    onSelectItem: (String) -> Unit,
    onSplitWords: (String, Long) -> Unit,
    onBack: (() -> Unit)? = null,
    onClipboardTabChange: ((Int) -> Unit)? = null,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier,
    onQuickSendAddClick: (() -> Unit)? = null,
    onQuickSendEditItem: ((Long, String, String) -> Unit)? = null,
    onPullRemote: (() -> Unit)? = null,
    pullRemoteAvailable: Boolean = false,
    /** 图片条目点选（直插/回写由服务层处理）。 */
    onSelectImage: ((ClipboardItem) -> Unit)? = null,
    /** 图片条目 → 本地绝对文件（不存在返回 null，用于缩略图与拖拽）。 */
    imageFileOf: ((ClipboardItem) -> File?)? = null,
    /** 拖拽发送已成功启动（收起输入法，露出目标应用的接收区域）。 */
    onDragSendStarted: (() -> Unit)? = null,
) {
    // 卡片/格子背景：与菜单项背景一致（keyBgColor，浅色纯白、深色跟随 keyboard.colors）
    val itemBgColor = keyBgColor
    val textColor = keyTextColor
    val subTextColor = keyTextColor.copy(alpha = 0.65f)
    val accentColor = MaterialTheme.colorScheme.primary
    // 图标按钮容器色：surface 与 primary 的混合色调（带种子色但不过于强烈）
    val iconButtonContainer = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.primary,
        0.35f
    )
    var menuAnchor by remember { mutableStateOf<MenuAnchor?>(null) }
    var isMultiSelect by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showClearConfirm by remember { mutableStateOf(false) }

    fun exitMultiSelect() {
        isMultiSelect = false
        selectedIds = emptySet()
    }

    // 布局按父容器真实宽度自适应（悬浮卡片/键盘收窄/分屏的容器宽 ≠ 屏幕宽），
    // 不读屏幕方向：宽容器（横屏全屏）用大边距，其余按竖屏形态
    BoxWithConstraints(modifier = modifier) {
        val isWide = maxWidth >= WIDE_CONTAINER_WIDTH

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 返回按钮与候选栏 logo 同位（8dp），不再随宽容器加大缩进
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(iconButtonContainer)
                    .clickable { onBack?.invoke() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = tr(R.string.s_guanbimianban),
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(iconButtonContainer)
                    .padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (selectedTab == 0) accentColor else Color.Transparent)
                            .clickable { onClipboardTabChange?.invoke(0) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tr(R.string.s_jiantieban),
                            color = if (selectedTab == 0) Color.White else textColor,
                            fontSize = 11.sp,
                            fontWeight = if (selectedTab == 0) FontWeight.Medium else FontWeight.Normal
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (selectedTab == 1) accentColor else Color.Transparent)
                            .clickable { onClipboardTabChange?.invoke(1) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tr(R.string.s_kuaijiefasong),
                            color = if (selectedTab == 1) Color.White else textColor,
                            fontSize = 11.sp,
                            fontWeight = if (selectedTab == 1) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (selectedTab == 0) {
                if (isMultiSelect) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(iconButtonContainer)
                            .clickable { exitMultiSelect() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = tr(R.string.s_tuichuduoxuan),
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    // 剪贴板同步已启用（配置+启用插件）时，提供主动拉取按钮
                    if (pullRemoteAvailable && onPullRemote != null) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(iconButtonContainer)
                                .clickable(onClick = onPullRemote),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.Sync,
                                contentDescription = tr(R.string.s_laquyuanduanjiantieban),
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (clipboardItems.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(iconButtonContainer)
                                .clickable { showClearConfirm = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = tr(R.string.s_qingkongjiantieban),
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            if (selectedTab == 1 && onQuickSendAddClick != null) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(iconButtonContainer)
                        .clickable(onClick = onQuickSendAddClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = tr(R.string.s_tianjiakuaijiefasong),
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        val pagerState = rememberPagerState(
            initialPage = selectedTab,
            pageCount = { 2 }
        )

        LaunchedEffect(selectedTab) {
            pagerState.animateScrollToPage(selectedTab)
        }

        LaunchedEffect(pagerState.currentPage) {
            if (pagerState.currentPage != selectedTab) {
                onClipboardTabChange?.invoke(pagerState.currentPage)
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            if (page == 0) {
                ClipboardTabContent(
                    items = clipboardItems,
                    itemBgColor = itemBgColor,
                    textColor = textColor,
                    subTextColor = subTextColor,
                    accentColor = accentColor,
                    onSelect = onSelectItem,
                    onRemove = { id -> viewModel.removeClipboardItem(id) },
                    onAddToQuickSend = { id -> viewModel.addToQuickSend(id) },
                    onSplitWords = onSplitWords,
                    onLongPressItem = { item, isLeftColumn ->
                        menuAnchor = MenuAnchor(item, isLeftColumn, tab = 0)
                    },
                    isMultiSelect = isMultiSelect,
                    selectedIds = selectedIds,
                    onToggleSelect = { id ->
                        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                    },
                    onExitMultiSelect = { exitMultiSelect() },
                    onSelectImage = onSelectImage,
                    imageFileOf = imageFileOf,
                    onDragSendStarted = onDragSendStarted,
                )
            } else {
                QuickSendTabContent(
                    items = quickSendItems,
                    itemBgColor = itemBgColor,
                    textColor = textColor,
                    subTextColor = subTextColor,
                    accentColor = accentColor,
                    viewModel = viewModel,
                    onSelect = onSelectItem,
                    onQuickSendAddClick = onQuickSendAddClick,
                    onQuickSendEditItem = onQuickSendEditItem,
                    onDragSendStarted = onDragSendStarted,
                    onLongPressItem = { item, isLeftColumn ->
                        menuAnchor = MenuAnchor(item, isLeftColumn, tab = 1)
                    }
                )
            }
        }

        if (isMultiSelect) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr(R.string.s_yixuan_xiang, selectedIds.size),
                    color = textColor,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .clickable(enabled = selectedIds.isNotEmpty()) {
                            viewModel.removeClipboardItems(selectedIds.toList())
                            exitMultiSelect()
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = tr(R.string.s_shanchu),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(if (isWide) 15.dp else bottomPaddingDp.dp))

        menuAnchor?.let { anchor ->
            val menuItems = if (anchor.tab == 0) {
                if (anchor.item.isImage) {
                    // 图片条目：拆词/快捷发送无意义（文本操作），只剩多选 + 删除
                    listOf(
                        LongPressMenuEntry(
                            icon = Icons.Default.DoneAll,
                            label = tr(R.string.s_duoxuan),
                            onClick = {
                                isMultiSelect = true
                                selectedIds = emptySet()
                            }
                        ),
                        LongPressMenuEntry(
                            icon = Icons.Default.Delete,
                            label = tr(R.string.s_shanchu),
                            tint = MaterialTheme.colorScheme.error,
                            onClick = {
                                viewModel.removeClipboardItem(anchor.item.id)
                            }
                        )
                    )
                } else {
                    listOf(
                        LongPressMenuEntry(
                            icon = Icons.Default.ContentCut,
                            label = tr(R.string.s_chaici),
                            onClick = {
                                onSplitWords(anchor.item.text, anchor.item.id)
                            }
                        ),
                        LongPressMenuEntry(
                            icon = Icons.Outlined.StarBorder,
                            label = tr(R.string.s_kuaijie),
                            onClick = {
                                viewModel.addToQuickSend(anchor.item.id)
                            }
                        ),
                        LongPressMenuEntry(
                            icon = Icons.Default.DoneAll,
                            label = tr(R.string.s_duoxuan),
                            onClick = {
                                isMultiSelect = true
                                selectedIds = emptySet()
                            }
                        ),
                        LongPressMenuEntry(
                            icon = Icons.Default.Delete,
                            label = tr(R.string.s_shanchu),
                            tint = MaterialTheme.colorScheme.error,
                            onClick = {
                                viewModel.removeClipboardItem(anchor.item.id)
                            }
                        )
                    )
                }
            } else {
                listOfNotNull(
                    LongPressMenuEntry(
                        icon = Icons.Filled.PushPin,
                        label = tr(R.string.s_zhiding),
                        onClick = {
                            viewModel.togglePinQuickSend(anchor.item.id)
                        }
                    ),
                    onQuickSendEditItem?.let { edit ->
                        LongPressMenuEntry(
                            icon = Icons.Default.Create,
                            label = tr(R.string.s_bianji),
                            onClick = {
                                edit(anchor.item.id, anchor.item.text, anchor.item.code)
                            }
                        )
                    },
                    LongPressMenuEntry(
                        icon = Icons.Default.Delete,
                        label = tr(R.string.s_shanchu),
                        tint = MaterialTheme.colorScheme.error,
                        onClick = {
                            viewModel.removeQuickSendItem(anchor.item.id)
                        }
                    )
                )
            }
            LongPressMenuOverlay(
                text = if (anchor.item.isImage) clipboardImageLabel(anchor.item) else anchor.item.text,
                isLeftColumn = anchor.isLeftColumn,
                backgroundColor = backgroundColor,
                contentBgColor = itemBgColor,
                textColor = textColor,
                onDismiss = { menuAnchor = null },
                menuItems = menuItems
            )
        }

        if (showClearConfirm) {
            ClearClipboardConfirmOverlay(
                itemCount = clipboardItems.size,
                backgroundColor = backgroundColor,
                cardBgColor = itemBgColor,
                textColor = textColor,
                subTextColor = subTextColor,
                onCancel = { showClearConfirm = false },
                onConfirm = {
                    showClearConfirm = false
                    viewModel.clearClipboard()
                }
            )
        }
    }
    }
}

@Composable
private fun ClearClipboardConfirmOverlay(
    itemCount: Int,
    backgroundColor: Color,
    cardBgColor: Color,
    textColor: Color,
    subTextColor: Color,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = cardBgColor
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = tr(R.string.s_qingkongjiantieban),
                    color = textColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = tr(R.string.s_jiangshanchuquanbu_tiaojiantie_12f1e, itemCount),
                    color = subTextColor,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(onClick = onCancel)
                            .padding(horizontal = 18.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tr(R.string.s_quxiao),
                            color = textColor,
                            fontSize = 13.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.error)
                            .clickable(onClick = onConfirm)
                            .padding(horizontal = 18.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tr(R.string.s_qingkong),
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

internal data class MenuAnchor(
    val item: ClipboardItem,
    val isLeftColumn: Boolean,
    val tab: Int
)

data class LongPressMenuEntry(
    val icon: ImageVector,
    val label: String,
    val tint: Color? = null,
    val onClick: () -> Unit
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipboardTabContent(
    items: List<ClipboardItem>,
    itemBgColor: Color,
    textColor: Color,
    subTextColor: Color,
    accentColor: Color,
    onSelect: (String) -> Unit,
    onRemove: (Long) -> Unit,
    onAddToQuickSend: (Long) -> Unit,
    onSplitWords: (String, Long) -> Unit,
    onLongPressItem: (ClipboardItem, Boolean) -> Unit,
    isMultiSelect: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    onToggleSelect: (Long) -> Unit = {},
    onExitMultiSelect: () -> Unit = {},
    onSelectImage: ((ClipboardItem) -> Unit)? = null,
    imageFileOf: ((ClipboardItem) -> File?)? = null,
    onDragSendStarted: (() -> Unit)? = null,
) {
    val view = LocalView.current
    if (items.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = tr(R.string.s_jiantiebanweikong),
                color = subTextColor,
                fontSize = 13.sp
            )
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                val highlighted = isMultiSelect && item.id in selectedIds
                val onClick: () -> Unit = {
                    if (isMultiSelect) {
                        onToggleSelect(item.id)
                    } else if (item.isImage) {
                        onSelectImage?.invoke(item)
                    } else {
                        onSelect(item.text)
                    }
                }
                val onLongClick: () -> Unit = {
                    if (isMultiSelect) onExitMultiSelect()
                    else onLongPressItem(item, index % 2 == 0)
                }
                // 拖拽发送：长按后拖出 slop 启动；多选态不启用（选择模式冲突）。
                // 启动失败（文件缺失/URI 获取失败/系统拒绝）由手势回退长按菜单。
                val onStartDrag: (() -> Boolean)? = if (isMultiSelect) null else {
                    {
                        val ok = ClipboardDragSender.startDragAndDrop(view, item, imageFileOf?.invoke(item))
                        if (ok) onDragSendStarted?.invoke()
                        ok
                    }
                }
                if (item.isImage) {
                    ClipboardImageCard(
                        item = item,
                        highlighted = highlighted,
                        bgColor = itemBgColor,
                        textColor = textColor,
                        subTextColor = subTextColor,
                        accentColor = accentColor,
                        file = imageFileOf?.invoke(item),
                        modifier = Modifier.height(62.dp),
                        onClick = onClick,
                        onLongClick = onLongClick,
                        dragKey = item.id,
                        onStartDrag = onStartDrag,
                    )
                } else {
                    GridItemCard(
                        text = item.text,
                        highlighted = highlighted,
                        bgColor = itemBgColor,
                        textColor = textColor,
                        accentColor = accentColor,
                        modifier = Modifier.height(62.dp),
                        onClick = onClick,
                        onLongClick = onLongClick,
                        dragKey = item.id,
                        onStartDrag = onStartDrag,
                    )
                }
            }
        }
    }
}

/** 图片条目的菜单/长按浮层文案（图片没有可展示的文本）。 */
internal fun clipboardImageLabel(item: ClipboardItem): String {
    val size = if (item.width > 0 && item.height > 0) " · ${item.width}×${item.height}" else ""
    return tr(R.string.s_tupian, size)
}

/**
 * 剪贴板图片卡片：缩略图（Coil 按目标尺寸降采样，不整图解码）。
 * 文件缺失（配额淘汰/被清理）时显示占位文案，点选不再上屏。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipboardImageCard(
    item: ClipboardItem,
    highlighted: Boolean,
    bgColor: Color,
    textColor: Color,
    subTextColor: Color,
    accentColor: Color,
    file: File?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    dragKey: Any? = null,
    onStartDrag: (() -> Boolean)? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    val bg = if (highlighted) accentColor.copy(alpha = 0.18f) else bgColor
    Box(
        modifier = modifier
            .border(1.5.dp, if (highlighted) accentColor else Color.Transparent, shape)
            .clip(shape)
            .background(bg)
            // 长按/拖拽由 clipboardLongPressDrag 处理（长按未拖 → onLongClick 弹菜单）。
            // 用 tolerantClick 而非 combinedClickable：卡片位于 LazyVerticalGrid + HorizontalPager 内，
            // 轻点的小位移会被父级手势判定吃掉（部分 ROM/高采样屏幕表现为“点了没反应”）。
            // onLongClick 仍须传空回调：长按触发后抬起不再 dispatch onClick，避免误上屏
            .tolerantClick(onClick = onClick, onLongClick = {})
            // 拖拽手势必须放在点击手势之后（更内层）：Main pass 内层先收，
            // 长按触发后外层点击手势会消费后续事件，外层收到的位移恒为 Zero
            .clipboardLongPressDrag(
                key = dragKey,
                enabled = onStartDrag != null,
                onLongPress = onLongClick,
                onStartDrag = onStartDrag ?: { false },
            )
    ) {
        if (file != null) {
            AsyncImage(
                // 显式上限：卡片约 165×62dp，按 512px 方框降采样解码（不整图进内存）
                model = rememberClipboardImageRequest(file, CLIPBOARD_CARD_MAX_PX),
                contentDescription = clipboardImageLabel(item),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Image,
                    contentDescription = null,
                    tint = subTextColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tr(R.string.s_tupianyiqingli),
                    color = subTextColor,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun GridItemCard(
    text: String,
    highlighted: Boolean,
    bgColor: Color,
    textColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    dragKey: Any? = null,
    onStartDrag: (() -> Boolean)? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    val bg = if (highlighted) accentColor.copy(alpha = 0.18f) else bgColor
    Column(
        modifier = modifier
            .border(1.5.dp, if (highlighted) accentColor else Color.Transparent, shape)
            .clip(shape)
            .background(bg)
            // 长按/拖拽由 clipboardLongPressDrag 处理（长按未拖 → onLongClick 弹菜单）。
            // 用 tolerantClick 而非 combinedClickable：卡片位于 LazyVerticalGrid + HorizontalPager 内，
            // 轻点的小位移会被父级手势判定吃掉（部分 ROM/高采样屏幕表现为“点了没反应”）。
            // onLongClick 仍须传空回调：长按触发后抬起不再 dispatch onClick，避免误上屏
            .tolerantClick(onClick = onClick, onLongClick = {})
            // 拖拽手势必须放在点击手势之后（更内层）：Main pass 内层先收，
            // 长按触发后外层点击手势会消费后续事件，外层收到的位移恒为 Zero
            .clipboardLongPressDrag(
                key = dragKey,
                enabled = onStartDrag != null,
                onLongPress = onLongClick,
                onStartDrag = onStartDrag ?: { false },
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun LongPressMenuOverlay(
    text: String,
    isLeftColumn: Boolean,
    backgroundColor: Color,
    contentBgColor: Color,
    textColor: Color,
    onDismiss: () -> Unit,
    menuItems: List<LongPressMenuEntry>,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(backgroundColor)
                .clickable(onClick = onDismiss)
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier.weight(if (isLeftColumn) 2f else 1f),
                contentAlignment = Alignment.Center
            ) {
                if (isLeftColumn) {
                    ContentCard(
                        text = text,
                        bgColor = contentBgColor,
                        textColor = textColor
                    )
                } else {
                    MenuCard(menuItems = menuItems, cardBgColor = contentBgColor, onDismiss = onDismiss)
                }
            }

            Box(
                modifier = Modifier.weight(if (isLeftColumn) 1f else 2f),
                contentAlignment = Alignment.Center
            ) {
                if (isLeftColumn) {
                    MenuCard(menuItems = menuItems, cardBgColor = contentBgColor, onDismiss = onDismiss)
                } else {
                    ContentCard(
                        text = text,
                        bgColor = contentBgColor,
                        textColor = textColor
                    )
                }
            }
        }
    }
}

@Composable
private fun ContentCard(
    text: String,
    bgColor: Color,
    textColor: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = bgColor
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun MenuCard(
    menuItems: List<LongPressMenuEntry>,
    cardBgColor: Color,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = cardBgColor
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            menuItems.forEachIndexed { index, entry ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    )
                }
                LongPressMenuItem(
                    icon = entry.icon,
                    label = entry.label,
                    tint = entry.tint ?: MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        onDismiss()
                        entry.onClick()
                    }
                )
            }
        }
    }
}

@Composable
fun LongPressMenuItem(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 14.sp
        )
    }
}
