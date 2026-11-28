package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.ui.theme.glassRim
import com.kingzcheung.xime.i18n.localizeData

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.data.RecentUsageStore
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.isTablet
import com.kingzcheung.xime.data.SymbolCategory
import com.kingzcheung.xime.data.SymbolData
import kotlinx.coroutines.launch

@Composable
fun SymbolKeyboardLayout(
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    keyBgColor: Color,
    /** 功能键（返回、删除、分类 tab）的底色/文字色：与数字/常用符号面板的功能键同一套 */
    specialKeyBgColor: Color = keyBgColor,
    specialKeyTextColor: Color = textColor,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier,
    /** 分类 tab 切换与返回按钮的振动钩子（符号点击/删除经 onSelect 由调用方统一振动）。 */
    onHapticFeedback: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val isTablet = isTablet()
    val kbKey = KeysConfigHelper.getKeyboardKeyConfig()
    // 键圆角与键盘同档：xime.yaml 的 key.symbol.corner_radius 也能管到这块全量符号面板
    val keyCornerRadius = keyCornerRadiusFor(kbKey, "symbol", isTablet)
    // 键距与面板一致：面板是「每键内衬 spacing/2」，这里用等距 gap 表达 ⇒ gap = spacing
    val (keySpacingX, keySpacingY) = kbKey.spacingFor("symbol")
    val columnGap = ((keySpacingX ?: 2f) * 2).dp
    val rowGap = ((keySpacingY ?: 2f) * 2).dp
    // 最近使用（LRU）：惰性排序——面板打开期间点按任何符号只持久化使用记录，
    // 不重排当前 UI（最近使用页位置稳定，便于连续输入同一符号）；面板关闭后
    // 组合状态丢弃，下次打开重新读取持久化结果，即为最新顺序。
    val recentSymbols = remember {
        RecentUsageStore.get(context, RecentUsageStore.KEY_RECENT_SYMBOLS)
    }
    val displayCategories = remember {
        listOf(SymbolCategory(name = tr(R.string.s_zuijinshiyong), id = "recentSymbols", symbols = recentSymbols)) +
            SymbolData.categories
    }
    // 图标按钮容器色 = 功能键底色（与面板的返回/删除键同色，不再自造 lerp 色调）
    val iconButtonContainer = specialKeyBgColor
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { displayCategories.size }
    )

    // 布局按父容器真实宽度自适应（悬浮卡片/键盘收窄/分屏的容器宽 ≠ 屏幕宽），
    // 不再读屏幕方向：宽容器（横屏全屏）用大边距与更多列，其余按竖屏形态
    BoxWithConstraints(modifier = modifier) {
        val isWide = maxWidth >= WIDE_CONTAINER_WIDTH
        val symbolColumns = gridColumnCount(maxWidth, targetCellWidth = 50.dp, minColumns = 8, maxColumns = 15)
        // 外围留白与数字/常用符号面板同一套：平板两侧 16dp + 底部 8dp、
        // 手机竖屏 4dp、手机横屏（宽容器）沿用面板的 2/50dp 档
        val outerPadding = panelOuterPadding(isLandscape = isWide && !isTablet, isTablet = isTablet)

    CompositionLocalProvider(LocalKeyCornerRadius provides keyCornerRadius) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(outerPadding)
    ) {
        // 导航区：返回按钮
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(iconButtonContainer)
                    .tolerantClick {
                        onHapticFeedback?.invoke()
                        onBack()
                    },
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

        // 内容区：符号网格 + HorizontalPager
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 4.dp)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val category = displayCategories[page]
                val columns = symbolColumns

                if (category.symbols.isEmpty()) {
                    // 最近使用为空时的占位提示
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tr(R.string.s_zanwuzuijinshiyong),
                            color = textColor.copy(alpha = 0.5f),
                            fontSize = 14.sp
                        )
                    }
                } else Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(rowGap)
                ) {
                    category.symbols.chunked(columns).forEach { rowSymbols ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(columnGap)
                        ) {
                            rowSymbols.forEach { symbol ->
                                SymbolButton(
                                    symbol = symbol,
                                    onClick = {
                                        // 惰性排序：任何页（含最近使用页）点按都只持久化使用记录、
                                        // 不重排当前 UI（最近使用页位置稳定，便于连续输入同一符号）；
                                        // 面板关闭后下次打开重新读取 store，即为最新顺序。
                                        RecentUsageStore.record(
                                            context, RecentUsageStore.KEY_RECENT_SYMBOLS, symbol
                                        )
                                        onSelect(symbol)
                                    },
                                    modifier = Modifier.weight(1f),
                                    textColor = textColor,
                                    backgroundColor = keyBgColor,
                                )
                            }
                            repeat(columns - rowSymbols.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // 底部：分类 Tab + 删除按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(columnGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(columnGap)
            ) {
                displayCategories.forEachIndexed { index, category ->
                    SymbolCategoryTab(
                        name = localizeData(category.name),
                        isSelected = index == pagerState.currentPage,
                        onClick = {
                            onHapticFeedback?.invoke()
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        backgroundColor = backgroundColor,
                        textColor = textColor,
                        selectedBackgroundColor = accentColor
                    )
                }
            }

            KeyButton(
                text = tr(R.string.s_shanchu),
                onClick = { onSelect("delete") },
                backgroundColor = specialKeyBgColor,
                textColor = specialKeyTextColor,
                modifier = Modifier.width(48.dp),
                fontSize = 12.sp
            )
        }

        // 底部留空（至少覆盖导航栏 inset 与键盘底部内边距）
        Spacer(modifier = Modifier.height(bottomPaddingDp.dp))
    }
    }
    }
}

@Composable
private fun SymbolButton(
    symbol: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified,
    backgroundColor: Color,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
            .background(
                glassFill(
                    if (isPressed) androidx.compose.ui.graphics.lerp(backgroundColor, Color.Black, 0.2f)
                    else backgroundColor
                )
            )
            .glassRim(RoundedCornerShape(LocalKeyCornerRadius.current))
            .tolerantClick(
                showRipple = false,
                interactionSource = interactionSource,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            color = textColor,
            fontFamily = AppFonts.keyFontFamily
        )
    }
}

@Composable
private fun SymbolCategoryTab(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color,
    selectedBackgroundColor: Color = textColor.copy(alpha = 0.15f),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(LocalKeyCornerRadius.current / 2))
            .background(
                if (isSelected) selectedBackgroundColor
                else backgroundColor
            )
            .tolerantClick(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            color = if (isSelected) textColor else textColor.copy(alpha = 0.5f)
        )
    }
}
