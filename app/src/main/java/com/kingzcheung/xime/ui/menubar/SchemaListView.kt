package com.kingzcheung.xime.ui.menubar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.twotone.Gesture
import androidx.compose.material.icons.twotone.KeyboardAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.R
import com.kingzcheung.xime.i18n.tr
import com.kingzcheung.xime.settings.SchemaInfo
import com.kingzcheung.xime.ui.keyboard.WIDE_CONTAINER_WIDTH
import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import com.kingzcheung.xime.ui.keyboard.isT9Schema

@Composable
fun SchemaListView(
    schemas: List<SchemaInfo>,
    currentSchemaId: String,
    backgroundColor: Color,
    accentColor: Color,
    keyTextColor: Color,
    keyBgColor: Color,
    onSelectSchema: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // 功能 item 背景：与键盘按键背景一致（keyBgColor，浅色纯白、深色跟随 keyboard.colors）
    val itemBgColor = keyBgColor
    val textColor = keyTextColor
    val subTextColor = keyTextColor.copy(alpha = 0.65f)
    // 图标按钮容器色：surface 与 primary 的混合色调（带种子色但不过于强烈）
    val iconButtonContainer = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.primary,
        0.35f
    )
    // 布局按父容器真实宽度自适应（悬浮卡片/键盘收窄/分屏的容器宽 ≠ 屏幕宽），
    // 不读屏幕方向：宽容器（横屏全屏）用横排网格，其余按竖屏分页网格
    BoxWithConstraints(modifier = modifier) {
        val isWide = maxWidth >= WIDE_CONTAINER_WIDTH

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    // 返回按钮与候选栏 logo 同位（8dp），不再随宽容器加大缩进
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(iconButtonContainer)
                    .clickable { onBack?.invoke() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = tr(R.string.s_fanhui),
                    tint = textColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        if (isWide) {
            // 横屏：方案多时固定单行会把每格压到只剩一个字，
            // 按可用宽度自适应列数换行、超出可滚动（与菜单栏横屏同款方案）
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 64.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (schemas.isEmpty()) {
                    item {
                        Text(
                            text = tr(R.string.s_meiyoukeyongdeshurufangan),
                            color = subTextColor,
                            fontSize = 13.sp
                        )
                    }
                }
                items(schemas) { schema ->
                    SchemaGridItem(
                        schema = schema,
                        isSelected = schema.schemaId == currentSchemaId,
                        bgColor = itemBgColor,
                        textColor = textColor,
                        accentColor = accentColor,
                        onSelect = { onSelectSchema(schema.schemaId) },
                        modifier = Modifier.fillMaxWidth(),
                        isLandscape = true
                    )
                }
            }
        } else {
            val itemsPerPage = 8
            val pages = schemas.chunked(itemsPerPage).map { page ->
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
                if (schemas.isEmpty()) {
                    Text(
                        text = tr(R.string.s_meiyoukeyongdeshurufangan),
                        color = subTextColor,
                        fontSize = 13.sp
                    )
                } else {
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
                            pages[page].forEach { schema ->
                                if (schema != null) {
                                    SchemaGridItem(
                                        schema = schema,
                                        isSelected = schema.schemaId == currentSchemaId,
                                        bgColor = itemBgColor,
                                        textColor = textColor,
                                        accentColor = accentColor,
                                        onSelect = { onSelectSchema(schema.schemaId) },
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                                }
                            }
                        }
                    }

                    if (pages.size > 1) {
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
                                            if (index == pagerState.currentPage) textColor
                                            else textColor.copy(alpha = 0.3f)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun SchemaGridItem(
    schema: SchemaInfo,
    isSelected: Boolean,
    bgColor: Color,
    textColor: Color,
    subTextColor: Color = textColor,
    accentColor: Color,
    layoutHint: String? = null,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false
) {
    Column(
        modifier = modifier
            .then(if (isLandscape) Modifier.height(72.dp) else Modifier.aspectRatio(1f))
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable { onSelect() }
            .padding(if (isLandscape) 4.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when {
            isHandwritingSchema(schema.schemaId) ->
                Icon(
                    imageVector = Icons.TwoTone.Gesture,
                    contentDescription = schema.name,
                    tint = if (isSelected) accentColor else textColor,
                    modifier = Modifier.size(if (isLandscape) 18.dp else 24.dp)
                )
            isT9Schema(schema.schemaId) ->
                Icon(
                    painter = painterResource(R.drawable.keyboard_t9),
                    contentDescription = schema.name,
                    tint = if (isSelected) accentColor else textColor,
                    modifier = Modifier.size(if (isLandscape) 18.dp else 24.dp)
                )
            else ->
                Icon(
                    imageVector = Icons.TwoTone.KeyboardAlt,
                    contentDescription = schema.name,
                    tint = if (isSelected) accentColor else textColor,
                    modifier = Modifier.size(if (isLandscape) 18.dp else 24.dp)
                )
        }
        Spacer(modifier = Modifier.height(if (isLandscape) 2.dp else 4.dp))
        Text(
            text = schema.name,
            color = if (isSelected) accentColor else textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        if (layoutHint != null) {
            Text(
                text = layoutHint,
                color = if (isSelected) accentColor.copy(alpha = 0.7f) else subTextColor,
                fontSize = 8.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
