package com.kingzcheung.xime.ui.keyboard


import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Assignment
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.twotone.DarkMode
import androidx.compose.material.icons.twotone.EmojiEmotions
import androidx.compose.material.icons.twotone.Keyboard
import androidx.compose.material.icons.twotone.LightMode
import androidx.compose.material.icons.twotone.Padding
import androidx.compose.material.icons.twotone.PictureInPicture
import androidx.compose.material.icons.twotone.Quickreply
import androidx.compose.material.icons.twotone.Rotate90DegreesCcw
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.SettingsOverscan
import androidx.compose.material.icons.twotone.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class MenuItem(
    val icon: Painter? = null,
    val label: String,
    val action: () -> Unit,
    val textIcon: String? = null,
)

data class MenuBarState(
    val isVisible: Boolean,
    val isDarkTheme: Boolean,
    val darkMode: Int = 2,
    val backgroundColor: Color,
    val keyBgColor: Color = Color.White,
    val keyTextColor: Color = Color(0xFF202124),
    val isFloatingMode: Boolean = false,
    /** 承载区受限（独立 IME 面板）时「键盘调节」「浮动键盘」不可用，入口隐藏。 */
    val canResizeKeyboard: Boolean = true,
    val canFloatKeyboard: Boolean = true,
)

data class MenuBarCallbacks(
    val onDismiss: () -> Unit,
    val onClipboard: () -> Unit,
    val onQuickSend: () -> Unit,
    val onKeyboardResize: () -> Unit,
    val onEmoji: () -> Unit,
    val onReloadConfig: () -> Unit,
    val onSettings: () -> Unit,
    val onSchemaList: () -> Unit,
    val onSchemaSwitches: () -> Unit = {},
    val onToggleDarkMode: () -> Unit,
    val onFloatingModeToggle: (() -> Unit)? = null,
    val onToolbarCustomize: () -> Unit = {},
)

@Composable
fun MenuBar(
    state: MenuBarState,
    callbacks: MenuBarCallbacks,
    modifier: Modifier = Modifier
) {
    if (!state.isVisible) return
    
    val textColor = state.keyTextColor
    // 功能 item 背景：与键盘按键背景一致（keyBgColor，浅色纯白、深色跟随 keyboard.colors）
    val itemBgColor = state.keyBgColor
    // 圆形图标按钮底色（关闭 / 设置）：深色下沿用按键的半透明白材质，和下方菜单项同色；
    // 亮色下用 surface 与 primary 的混合色调（带种子色但不过于强烈）
    val iconButtonContainer = if (state.isDarkTheme) state.keyBgColor
    else androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.primary,
        0.35f
    )
    val configuration = LocalConfiguration.current
    val isLandscape = !state.isFloatingMode && configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    
    val clipboardIcon = rememberVectorPainter(Icons.AutoMirrored.TwoTone.Assignment)
    val quickSendIcon = rememberVectorPainter(Icons.TwoTone.Quickreply)
    val keyboardResizeIcon = rememberVectorPainter(Icons.TwoTone.SettingsOverscan)
    val emojiIcon = rememberVectorPainter(Icons.TwoTone.EmojiEmotions)
    val darkModeIcon = when (state.darkMode) {
        0 -> rememberVectorPainter(Icons.TwoTone.DarkMode)
        1 -> rememberVectorPainter(Icons.TwoTone.LightMode)
        else -> rememberVectorPainter(if (state.isDarkTheme) Icons.TwoTone.LightMode else Icons.TwoTone.DarkMode)
    }
    val deployIcon = rememberVectorPainter(Icons.TwoTone.Rotate90DegreesCcw)
    val customizeIcon = rememberVectorPainter(Icons.TwoTone.Padding)
    val schemaIcon = rememberVectorPainter(Icons.TwoTone.Keyboard)
    val switchesIcon = rememberVectorPainter(Icons.TwoTone.Tune)
    val settingsIcon = rememberVectorPainter(Icons.TwoTone.Settings)

    val darkModeLabel = when (state.darkMode) {
        0 -> tr(R.string.s_shensemoshi)
        1 -> tr(R.string.s_qiansemoshi)
        else -> tr(R.string.s_gensuixitong)
    }

    val floatingIcon = rememberVectorPainter(Icons.TwoTone.PictureInPicture)
    val floatingLabel = if (state.isFloatingMode) tr(R.string.s_tuichuxuanfu) else tr(R.string.s_xuanfumoshi)
    val floatingAction = callbacks.onFloatingModeToggle ?: {}

    val menuItems = remember(darkModeIcon, darkModeLabel, state.isFloatingMode, state.canResizeKeyboard, state.canFloatKeyboard) {
        listOf(
            MenuItem(clipboardIcon, tr(R.string.s_jiantieban), callbacks.onClipboard),
            MenuItem(quickSendIcon, tr(R.string.s_kuaijiefasong), callbacks.onQuickSend),
            MenuItem(schemaIcon, tr(R.string.s_shurufangan), callbacks.onSchemaList),
            // 方案开关统一收进二级页（SchemaSwitches），一级菜单只留入口
            MenuItem(switchesIcon, tr(R.string.s_fangankaiguan), callbacks.onSchemaSwitches),
            MenuItem(emojiIcon, tr(R.string.s_biaoqing), callbacks.onEmoji),
            MenuItem(customizeIcon, tr(R.string.s_dingzhigongjulan), callbacks.onToolbarCustomize),
            // 悬浮模式下键盘内容为缩放的浮动卡片，高度不可调节，隐藏该入口；
            // 承载区受限（独立 IME 面板）时调节页的操作按钮在面板里显示不全，同样隐藏
            if (!state.isFloatingMode && state.canResizeKeyboard) MenuItem(keyboardResizeIcon, tr(R.string.s_jianpandiaojie), callbacks.onKeyboardResize) else null,
            MenuItem(darkModeIcon, darkModeLabel, callbacks.onToggleDarkMode),
            // 承载区受限时浮动键盘无处安放，隐藏入口
            if (state.canFloatKeyboard) MenuItem(floatingIcon, floatingLabel, floatingAction) else null,
            MenuItem(deployIcon, tr(R.string.s_bushufangan), callbacks.onReloadConfig),
        ).filterNotNull()
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(state.backgroundColor),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 顶栏的尺寸与内边距跟候选栏一致：菜单开合时关闭按钮正好落在 logo 的位置。
        // 横屏不再叠加 50dp 缩进——挖孔/导航栏的物理避让由服务层内容容器的边衬区统一处理，
        // 叠加会让按钮比 logo 右移 42dp，开合菜单时整块图标左右跳。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IconButtonRowHeight)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(IconButtonSize)
                    .clip(CircleShape)
                    .background(iconButtonContainer)
                    .clickable { callbacks.onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = tr(R.string.s_guanbicaidan),
                    tint = textColor,
                    modifier = Modifier.size(IconButtonGlyphSize)
                )
            }
            Box(
                modifier = Modifier
                    .size(IconButtonSize)
                    .clip(CircleShape)
                    .background(iconButtonContainer)
                    .clickable { callbacks.onSettings() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = settingsIcon,
                    contentDescription = tr(R.string.s_shezhi),
                    tint = textColor,
                    modifier = Modifier.size(IconButtonGlyphSize)
                )
            }
        }
        // 内容区（菜单项）
        if (isLandscape) {
            // 横屏：方案开关多的配置（如万象）可能有 20+ 项，固定单行会把每格压到只剩一个字，
            // 这里按可用宽度自适应列数并换行；项数超出可用高度时可滚动。
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 64.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(menuItems) { item ->
                    MenuItemButton(
                        item = item,
                        bgColor = itemBgColor,
                        textColor = textColor,
                        modifier = Modifier.fillMaxWidth(),
                        isLandscape = true
                    )
                }
            }
        } else {
            // 竖屏：每页最多 8 项（2 行 × 4 列），支持横向翻页
            val itemsPerPage = 8
            val pages = menuItems.chunked(itemsPerPage).map { page ->
                page + List(itemsPerPage - page.size) { null }
            }
            val pagerState = rememberPagerState(pageCount = { pages.size })
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceAround
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth()
                ) { page ->
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        maxItemsInEachRow = 4
                    ) {
                        pages[page].forEach { item ->
                            if (item != null) {
                                MenuItemButton(
                                    item = item,
                                    bgColor = itemBgColor,
                                    textColor = textColor,
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                            }
                        }
                    }
                }

                if (pages.size > 1) {

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
                                        if (index == pagerState.currentPage) textColor
                                        else textColor.copy(alpha = 0.3f)
                                    )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun MenuItemButton(
    item: MenuItem,
    bgColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false
) {
    Column(
        modifier = modifier
            .then(if (isLandscape) Modifier.height(72.dp) else Modifier.aspectRatio(1f))
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable { item.action() }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (item.textIcon != null) {
            Text(
                text = item.textIcon,
                color = textColor.copy(alpha = 0.7f),
                fontSize = if (isLandscape) 18.sp else 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        } else if (item.icon != null) {
            Icon(
                painter = item.icon,
                contentDescription = item.label,
                tint = textColor.copy(alpha = 0.7f),
                modifier = Modifier.size(if (isLandscape) 18.dp else 24.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = item.label,
            color = textColor,
            fontSize = if (isLandscape) 9.sp else 10.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
