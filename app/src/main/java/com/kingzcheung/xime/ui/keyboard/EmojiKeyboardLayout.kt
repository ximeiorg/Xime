package com.kingzcheung.xime.ui.keyboard

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.data.EmojiCategory
import com.kingzcheung.xime.data.EmojiData
import com.kingzcheung.xime.data.RecentUsageStore
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.PluginResultItem
import com.kingzcheung.xime.plugin.core.api.PluginIcon

@Composable
fun EmojiKeyboardLayout(
    onEmojiSelect: (String) -> Unit,
    onImageEmojiSelect: ((String) -> Unit)? = null,
    onBack: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier,
    /** 分类 tab 切换的振动钩子（emoji 点击/删除经 onEmojiSelect 由调用方统一振动）。 */
    onHapticFeedback: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val clipboardManager = remember { ClipboardManager.getInstance(context) }

    // 图标按钮容器色：surface 与 primary 的混合色调（带种子色但不过于强烈）
    val iconButtonContainer = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.primary,
        0.15f
    )

    var selectedTopTabIndex by remember { mutableStateOf(0) }
    var selectedSubCategoryIndex by remember { mutableStateOf(0) }

    val allCategories by ExtensionManager.emojiCategoriesFlow.collectAsStateWithLifecycle()
    val pluginCategories = allCategories.filter { it.isPlugin }
    val builtinCategories = allCategories.filter { !it.isPlugin }

    // 最近使用（LRU）：惰性排序——面板打开期间点按任何 emoji 只持久化使用记录，
    // 不重排当前 UI（最近使用页位置稳定，便于连续输入）；面板关闭后组合状态丢弃，
    // 下次打开重新读取持久化结果，即为最新顺序。
    val recentEmojis = remember {
        RecentUsageStore.get(context, RecentUsageStore.KEY_RECENT_EMOJIS)
    }
    val recentCategory = EmojiCategory(name = "最近使用", icon = "🕘", emojis = recentEmojis)
    val displayBuiltinCategories = remember(builtinCategories) {
        listOf(recentCategory) + builtinCategories
    }

    // 按 pluginId 分组插件子分类（用于顶层 tab 和底部子分类 tab）
    val pluginGroupEntries = remember(pluginCategories) {
        pluginCategories.groupBy { it.pluginId ?: it.name }.entries.toList()
    }

    // 当前顶层 tab 对应的子分类列表
    val currentSubCategories = if (selectedTopTabIndex == 0) {
        displayBuiltinCategories
    } else {
        val groupIdx = selectedTopTabIndex - 1
        if (groupIdx < pluginGroupEntries.size) pluginGroupEntries[groupIdx].value
        else emptyList()
    }

    // 所有页面的扁平索引（用于动画过渡）
    val currentPageIndex = if (selectedTopTabIndex == 0) {
        selectedSubCategoryIndex.coerceIn(0, maxOf(0, displayBuiltinCategories.lastIndex))
    } else {
        val groupIdx = selectedTopTabIndex - 1
        val startPage = displayBuiltinCategories.size + pluginGroupEntries.take(groupIdx).sumOf { it.value.size }
        val groupSize = if (groupIdx < pluginGroupEntries.size) pluginGroupEntries[groupIdx].value.lastIndex else 0
        startPage + selectedSubCategoryIndex.coerceIn(0, maxOf(0, groupSize))
    }
    val totalPages = displayBuiltinCategories.size + pluginCategories.size

    // 布局按父容器真实宽度自适应（悬浮卡片/键盘收窄/分屏的容器宽 ≠ 屏幕宽），
    // 不再读屏幕方向：宽容器（横屏全屏）用大边距与更多列，其余按竖屏形态
    BoxWithConstraints(modifier = modifier) {
        val isWide = maxWidth >= WIDE_CONTAINER_WIDTH
        val emojiColumns = gridColumnCount(maxWidth, targetCellWidth = 50.dp, minColumns = 8, maxColumns = 15)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // 导航区：返回按钮 + 顶层 Tab（Emoji / 插件）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(start = if (isWide) 50.dp else 8.dp, end = if (isWide) 50.dp else 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 返回按钮
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(iconButtonContainer)
                        .tolerantClick { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowLeft,
                        contentDescription = "返回",
                        tint = textColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 顶层 Tab（ClipboardView 样式）
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(iconButtonContainer)
                        .padding(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        // Emoji 主 Tab
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (selectedTopTabIndex == 0) accentColor.copy(0.4f)
                                    else Color.Transparent
                                )
                                .tolerantClick {
                                    onHapticFeedback?.invoke()
                                    selectedTopTabIndex = 0
                                    selectedSubCategoryIndex = 0
                                }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "😊",
                                fontSize = 14.sp
                            )
                        }

                        // 插件 Tab（按 pluginId 分组，每插件一个顶层 tab）
                        pluginGroupEntries.forEachIndexed { index, (_, subCats) ->
                            val firstCat = subCats.first()
                            val pluginIcon = firstCat.pluginIcon
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(
                                        if (selectedTopTabIndex == index + 1) accentColor.copy(0.4f)
                                        else Color.Transparent
                                    )
                                    .tolerantClick {
                                        onHapticFeedback?.invoke()
                                        selectedTopTabIndex = index + 1
                                        selectedSubCategoryIndex = 0
                                    }
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (pluginIcon?.assetName != null) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(pluginIcon.assetName)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = firstCat.name,
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .padding(2.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    Text(
                                        text = pluginIcon?.text ?: firstCat.icon,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val pagerState = rememberPagerState(
            initialPage = currentPageIndex,
            pageCount = { totalPages }
        )

        // 外部切换分类时同步到 Pager
        LaunchedEffect(currentPageIndex) {
            pagerState.animateScrollToPage(currentPageIndex)
        }

        // Pager 滑动时同步到外部状态
        LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
            val page = pagerState.currentPage
            if (!pagerState.isScrollInProgress && page != currentPageIndex) {
                if (page < displayBuiltinCategories.size) {
                    selectedTopTabIndex = 0
                    selectedSubCategoryIndex = page
                } else {
                    // 找到该 page 属于哪个插件组的哪个子分类
                    var remaining = page - builtinCategories.size
                    for ((groupIdx, entry) in pluginGroupEntries.withIndex()) {
                        if (remaining < entry.value.size) {
                            selectedTopTabIndex = groupIdx + 1
                            selectedSubCategoryIndex = remaining
                            break
                        }
                        remaining -= entry.value.size
                    }
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = if (isWide) 50.dp else 4.dp)
                .padding(bottom = 4.dp)
        ) { pageIndex ->
            val category = if (pageIndex < displayBuiltinCategories.size) {
                displayBuiltinCategories[pageIndex]
            } else {
                pluginCategories[pageIndex - displayBuiltinCategories.size]
            }

            if (category.isPlugin && category.emojiItems != null) {
                val hasImages = category.emojiItems.any { it.imageUrl != null }
                val defaultCols = if (hasImages) 6 else emojiColumns
                val columns = if (category.layoutColumns > 0) category.layoutColumns else defaultCols
                val itemHeightDp = if (category.layoutItemHeightDp > 0) category.layoutItemHeightDp
                    else (if (hasImages) 60 else 40)

                // 行分组缓存：chunked 每次重组重算会产生大量临时列表，
                // remember 后仅在数据/列数变化时重建
                val emojiRows = remember(category.emojiItems, columns) {
                    category.emojiItems.chunked(columns)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // 图片表情行间距与列间距(6dp)对齐；文本表情保持紧凑 2dp
                    verticalArrangement = Arrangement.spacedBy(if (hasImages) 6.dp else 2.dp)
                ) {
                    items(
                        items = emojiRows,
                        // 稳定 key 提升滚动复用率（id 在单分类内唯一）
                        key = { row -> row.firstOrNull()?.id ?: row.hashCode() },
                        contentType = { if (hasImages) "emoji-image-row" else "emoji-text-row" }
                    ) { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowItems.forEach { item ->
                                PluginEmojiButton(
                                    emojiItem = item,
                                    defaultHeightDp = itemHeightDp,
                                    backgroundColor = backgroundColor,
                                    textColor = textColor,
                                    onClick = {
                                        val imageUrl = item.imageUrl
                                        if (imageUrl != null && onImageEmojiSelect != null) {
                                            onImageEmojiSelect(imageUrl)
                                        } else if (imageUrl != null) {
                                            val success =
                                                clipboardManager.copyImageToSystemClipboard(
                                                    imageUrl,
                                                    item.text
                                                )
                                            if (success) {
                                                Toast.makeText(
                                                    context,
                                                    "已复制表情，可粘贴发送",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "复制失败",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        } else {
                                            onEmojiSelect(item.insertText ?: item.text)
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(columns - rowItems.size) {
                                Spacer(modifier = Modifier
                                    .weight(1f)
                                    .height((itemHeightDp).dp))
                            }
                        }
                    }
                }
            } else if (category.emojis.isEmpty()) {
                // 最近使用为空时的占位提示
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无最近使用",
                        color = textColor.copy(alpha = 0.5f),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(category.emojis.chunked(emojiColumns)) { rowEmojis ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            rowEmojis.forEach { emoji ->
                                EmojiButton(
                                    emoji = emoji,
                                    onClick = {
                                        // 惰性排序：任何页（含最近使用页）点按都只持久化使用记录、
                                        // 不重排当前 UI（最近使用页位置稳定，便于连续输入）；
                                        // 面板关闭后下次打开重新读取 store，即为最新顺序。
                                        RecentUsageStore.record(
                                            context, RecentUsageStore.KEY_RECENT_EMOJIS, emoji
                                        )
                                        onEmojiSelect(emoji)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(emojiColumns - rowEmojis.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // 底部：子分类 Tab 或留空 + 删除按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = if (isWide) 50.dp else 4.dp, vertical = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentSubCategories.isNotEmpty()) {
                // 显示当前顶层 tab 的子分类
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    currentSubCategories.forEachIndexed { index, category ->
                        if (category.isPlugin) {
                            // 插件子分类：显示分类名，不用 pluginIcon（那是插件级图标）
                            EmojiCategoryTab(
                                icon = category.name,
                                pluginIcon = null,
                                isSelected = index == selectedSubCategoryIndex,
                                onClick = {
                                    onHapticFeedback?.invoke()
                                    selectedSubCategoryIndex = index
                                },
                                backgroundColor = backgroundColor,
                                textColor = textColor,
                                selectedBackgroundColor = accentColor,
                                modifier = Modifier.widthIn(min = 36.dp)
                            )
                        } else {
                            EmojiCategoryTab(
                                icon = category.icon,
                                pluginIcon = category.pluginIcon,
                                isSelected = index == selectedSubCategoryIndex,
                                onClick = {
                                    onHapticFeedback?.invoke()
                                    selectedSubCategoryIndex = index
                                },
                                backgroundColor = backgroundColor,
                                textColor = textColor,
                                selectedBackgroundColor = accentColor,
                                modifier = Modifier.width(36.dp)
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            KeyButton(
                text = "删除",
                onClick = { onEmojiSelect("delete") },
                backgroundColor = backgroundColor,
                textColor = textColor,
                modifier = Modifier.width(48.dp),
                fontSize = 12.sp
            )
        }

        // 底部留空
        Spacer(modifier = Modifier.height(if (isWide) 15.dp else bottomPaddingDp.dp))
    }
    }
}

@Composable
fun EmojiCategoryTab(
    icon: String,
    pluginIcon: PluginIcon? = null,
    isSelected: Boolean,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    selectedBackgroundColor: Color = textColor.copy(alpha = 0.15f),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))

            .background(
                if (isSelected) selectedBackgroundColor.copy(0.4f)
                else backgroundColor
            )
            .padding(horizontal = 5.dp)
            .tolerantClick(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (pluginIcon?.assetName != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(pluginIcon.assetName)
                    .crossfade(true)
                    .build(),
                contentDescription = icon,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = pluginIcon?.text ?: icon,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun EmojiButton(
    emoji: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .tolerantClick(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            fontSize = 22.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun PluginEmojiButton(
    emojiItem: PluginResultItem,
    onClick: () -> Unit,
    defaultHeightDp: Int = 40,
    backgroundColor: Color = Color.Unspecified,
    textColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isLightTheme =
        (backgroundColor.red + backgroundColor.green + backgroundColor.blue) / 3f > 0.5f
    val buttonBackgroundColor = if (isLightTheme) Color.White.copy(alpha = 0.8f)
    else Color.LightGray.copy(alpha = 0.15f)
    val contentColor = if (isLightTheme) Color.Black else textColor

    Box(
        modifier = modifier
            .height(defaultHeightDp.dp)
            .then(
                if (emojiItem.imageUrl != null) Modifier.aspectRatio(1f)
                else Modifier.fillMaxWidth()
            )
            .clip(RoundedCornerShape(4.dp))
            .background(buttonBackgroundColor)
            .tolerantClick(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        if (emojiItem.imageUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(emojiItem.imageUrl)
                    // 高频网格滚动场景：关闭渐显动画，降低加载突发期的重绘压力
                    .crossfade(false)
                    .build(),
                contentDescription = emojiItem.text,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(2.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = emojiItem.text,
                fontSize = 12.sp,
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}