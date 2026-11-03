package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ui.isTablet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 候选展开页条目：候选文本 + 拼音注释 + 跨页全局索引。
 */
data class CandidateEntry(
    val text: String,
    val comment: String = "",
    val globalIndex: Int = -1,
)

/**
 * 候选展开页数据。
 *
 * @param candidateRows 行分组的候选（行分组仅作展示分组）
 * @param keyBackgroundColor 左右两栏按键底色（键盘按键色）；[Color.Unspecified] 时
 *                          用 textColor 半透明兜底，保证单独预览时也不失形。
 */
data class CandidatePageState(
    val candidateRows: List<List<CandidateEntry>> = emptyList(),
    val associationCandidates: List<String> = emptyList(),
    val backgroundColor: Color,
    val textColor: Color,
    val keyBackgroundColor: Color = Color.Unspecified,
    val bottomPaddingDp: Int = 0,
    /** "只看单字"过滤开启中（左栏底部切换按钮的选中态） */
    val singleCharFilter: Boolean = false,
    /** 左栏符号列表（九键/笔画复刻各自键盘左栏的 side_symbols）；空=通用快捷符号 */
    val railSymbols: List<String> = emptyList(),
    /** 左栏宽度 dp（九键对齐其键盘左栏列宽：(页宽-12)×0.8/5，与 weight 分配同公式、
     *  浮点不取整——取整会带来 ±1dp 的背景宽度差）；0=默认固定宽度 */
    val leftRailWidthDp: Float = 0f,
    /** 左栏垂直缩进 dp（非九键布局的外层垂直边距，默认 6=原 Row 垂直边距）。
     *  九键布局走"键位区+内缩"模型（对齐其键盘左栏）：外层不留垂直边距，改由
     *  [leftRailBottomInsetDp] 补键盘根容器底部留白，列表容器/切换键各自内缩 */
    val leftRailInsetDp: Int = 6,
    /** 左栏面板水平缩进 dp（九键对齐其左栏面板 keySpacingX：面板四边带
     *  LocalKeyVisualPadding 缩进，展开页背景此前无水平缩进导致背景块比键盘态宽、
     *  展开切换时视觉跳位）；0=无缩进（非九键布局保持原全宽背景） */
    val leftRailInsetXDp: Float = 0f,
    /** 左栏键位视觉缩进的垂直分量 dp（九键 keySpacingY）：列表容器与底部切换键
     *  在各自键位区内缩出与键盘态面板/符号键相同的间隙；0=无缩进 */
    val leftRailInsetYDp: Float = 0f,
    /** 左栏键位区底部留白 dp（九键键盘根容器的 bottom=8dp）：对齐九键符号键的
     *  底边位置——此前展开页 RailKey 直抵页底，比键盘态低 8dp（向下挤压感） */
    val leftRailBottomInsetDp: Int = 0,
    /** 右栏键位区宽度 dp（九键对齐其右列列宽：(页宽-12)×0.8/5，浮点不取整）；
     *  0=默认固定宽度。>0 时右栏走与左栏同款"键位区+内缩"模型（顶从页顶起、
     *  底部留 [leftRailBottomInsetDp]、键各自内缩 keySpacing），展开/收起切换
     *  右栏轮廓与键盘态右列一致不跳位 */
    val rightRailWidthDp: Float = 0f,
    /** 左栏面板阴影（九键左栏面板带 crispShadow 底衬，展开页同参复刻，
     *  否则主题开阴影时两处面板色深不一致） */
    val railShadowEnabled: Boolean = false,
    val railShadowElevationDp: Float = 0f,
    val railShadowRadiusDp: Float = 0f,
    /** 左栏音节拼音候选（九键输入/选择态复刻，与键盘左栏同源）；非空时优先于 railSymbols */
    val railPinyinOptions: List<String> = emptyList(),
    /** 拼音候选项选中索引（九键 SELECTION 态），-1 无选中 */
    val railSelectedPinyinIndex: Int = -1,
    /** 拼音选中胶囊强调色（对齐九键 CandidateItem）；Unspecified 时用 textColor 兜底 */
    val railAccentColor: Color = Color.Unspecified,
    /** 拼音选中胶囊背景色；Unspecified 时按 [railAccentColor] 20% 透明派生 */
    val railSelectedBgColor: Color = Color.Unspecified,
    /** 右栏等分压缩（悬浮模式页高有限）：true 时 4 键均分列高（同横屏），
     *  false 保持竖屏固定 46dp 方块——固定尺寸在悬浮矮卡片下放不下会整体溢出，
     *  最底部的回车键被卡片圆角裁掉一半 */
    val rightRailEqualSplit: Boolean = false,
)

/**
 * 候选展开页回调。
 *
 * @param onCandidateSelect 页内点选（携带跨页全局索引，KeyboardView 直接路由引擎）
 * @param onToggleSingleCharFilter 左栏底部"候选/单字"切换
 * @param onCommitText 左栏快捷符号上屏
 * @param onDelete     右栏退格键
 * @param onEnter      右栏回车键
 */
data class CandidatePageCallbacks(
    val onCandidateSelect: (CandidateEntry) -> Unit,
    val onAssociationSelect: ((Int) -> Unit)? = null,
    val onToggleSingleCharFilter: (() -> Unit)? = null,
    /** 长按候选删除自造词 */
    val onCandidateLongPress: ((CandidateEntry) -> Unit)? = null,
    /** 左栏拼音候选点选（九键音节切换，index 对应 railPinyinOptions） */
    val onRailPinyinSelect: ((Int) -> Unit)? = null,
    val onCommitText: ((String) -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    val onEnter: (() -> Unit)? = null,
)

/** 左栏快捷符号（对齐主流输入法候选展开页的符号栏）。 */
private val QUICK_SYMBOLS = listOf("？", "！", "……", "~")

/** 性能打点开关 */
private const val debugPerfLogging = true

/**
 * 候选展开页主体（三栏）——渲染在真实候选栏正下方（候选栏的在位展开态，非 Overlay 页）：
 * ┌────────┬────────────────────────────┬───────┐
 * │ ？     │  词 词词 词 词词词（流式换行）│ │ 退格  │
 * │ ！     │  词 词 词 词（附拼音注释）  │ │ 上一页│
 * │ ……     │  （行分组惰性渲染只画可见行，│ │ 下一页│
 * │ ~      │    超出视口即上下滑动）     │ │ 回车  │
 * │ 候选/单字│                          │ │       │
 * └────────┴────────────────────────────┴───────┘
 * 左栏样式对齐数字键盘左栏：上部快捷符号键、底部"候选/单字"切换。
 * 数据源为跨页全量候选，不做本地分页；收起按钮在上方候选栏右侧；
 * 编码删空时由宿主自动收起本页；翻页键 = 视口滚动一屏（上下到头自动置灰），
 * [pageScrollEvents] 供硬件键盘 DPAD 上/下联动同样的滚动。
 */
@Composable
fun CandidatePage(
    state: CandidatePageState,
    callbacks: CandidatePageCallbacks,
    modifier: Modifier = Modifier,
    pageScrollEvents: Flow<Int>? = null,
    onHapticFeedback: (() -> Unit)? = null,
) {
    // 布局按父容器真实宽度自适应（悬浮卡片/键盘收窄/分屏的容器宽 ≠ 屏幕宽），
    // 不读屏幕方向：宽容器（横屏全屏）左右栏更紧凑，其余按竖屏形态
    BoxWithConstraints(modifier = modifier) {
        val isWide = maxWidth >= WIDE_CONTAINER_WIDTH

    val leftRailWidth = if (isWide) 48.dp else 40.dp
    val rightRailWidth = if (isWide) 40.dp else 46.dp
    val keyBg = if (state.keyBackgroundColor == Color.Unspecified)
        state.textColor.copy(alpha = 0.12f) else state.keyBackgroundColor
    val dividerColor = state.textColor.copy(alpha = 0.12f)
    val railSymbols = state.railSymbols.ifEmpty { QUICK_SYMBOLS }
    // 九键输入/选择态：左栏显示音节拼音候选（与键盘左栏同源同点击）；空闲态回落符号列表
    val railItems = state.railPinyinOptions.ifEmpty { railSymbols }
    val isPinyinRail = state.railPinyinOptions.isNotEmpty()
    // 左栏面板阴影：九键左栏面板带 crispShadow 底衬（主题开阴影时），展开页同参复刻，
    // 否则两处面板的背景色深不一致（键盘态有暗边、展开态纯平）
    val density = LocalDensity.current
    val railShadowModifier = remember(
        state.railShadowEnabled, state.railShadowElevationDp, state.railShadowRadiusDp,
        density, keyBg
    ) {
        if (state.railShadowEnabled) {
            val offsetPx = with(density) { state.railShadowElevationDp.dp.toPx() }
            val cornerPx = with(density) { state.railShadowRadiusDp.dp.toPx() }
            val color = crispShadowColor(keyBg)
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
    // 左栏宽度：九键对齐其键盘左栏（宿主按同公式给的浮点 dp，不取整），其余布局用固定宽度
    val hasT9Rail = state.leftRailWidthDp > 0f
    val railWidthModifier = if (hasT9Rail)
        Modifier.fillMaxHeight().width(state.leftRailWidthDp.dp)
    else Modifier.fillMaxHeight().width(leftRailWidth)

    // 中间候选区滚动；翻页 = 滚动一屏
    val listState = rememberLazyListState()
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val scrollScope = rememberCoroutineScope()
    fun scrollPage(direction: Int) {
        val viewport = viewportHeightPx
        if (viewport > 0) {
            scrollScope.launch { listState.animateScrollBy(direction.toFloat() * viewport) }
        }
    }
    // 候选内容变化（新输入/切过滤/删词）回到顶部（列表实例每次重组都新建，用哈希做键）
    LaunchedEffect(state.candidateRows.hashCode()) {
        listState.scrollToItem(0)
    }
    // 硬件键盘 DPAD 上/下的翻页联动
    LaunchedEffect(pageScrollEvents) {
        pageScrollEvents?.collect { direction -> scrollPage(direction) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(state.backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                // 垂直边距下放各栏：左栏用 leftRailInsetDp（九键对其键盘左栏面板的
                // keySpacingY 缩进，切换展开/收起时左栏不跳位），中/右栏保持 6dp 原视觉。
                // start/end 4dp 对齐九键键盘根容器的 start/end 4dp 边距（左右栏背景的
                // 边缘要与键盘态面板同起点，展开切换才不横移）；非九键布局由各栏
                // 自行补足。
                // 九键左栏在位时改按键盘区外围留白档（手机 4dp / 平板 16dp，见
                // [keyboardSidePaddingDp]）——平板九键键盘的两侧留白是 16dp，
                // 展开页必须跟着走，否则左栏会比键盘态窄一截、展开/收起横移。
                .padding(
                    start = if (hasT9Rail) keyboardSidePaddingDp(isTablet()).dp else 4.dp,
                    end = when {
                        hasT9Rail -> keyboardSidePaddingDp(isTablet()).dp
                        state.rightRailWidthDp > 0f -> 4.dp
                        else -> 8.dp
                    }
                )
        ) {
            // ── 左栏：九键为音节拼音候选（输入/选择态）或 side_symbols（空闲态，
            // 连体面板——圆角背景与滚动裁剪由列表容器统一负责）+ 候选/单字切换（下）。
            // 条目 ≤4 均分填满；>4 最多显示 4 条、LazyColumn 滚动（对齐九键左栏）──
            Column(
                modifier = railWidthModifier
                    // 非九键布局补回 Row start 减掉的 4dp（九键布局背景起点须与键盘态
                    // 面板同源，不再补）
                    .padding(start = if (state.leftRailWidthDp > 0f) 0.dp else 4.dp)
                    .padding(
                        // 九键走"键位区+内缩"模型：外层不留垂直边距（同其键盘左列从
                        // 键盘区顶起），底部留白对齐键盘根容器的 bottom=8dp——否则
                        // 底部切换键直抵页底，比键盘态符号键低 8dp（向下挤压）
                        top = if (state.leftRailWidthDp > 0f) 0.dp else state.leftRailInsetDp.dp,
                        bottom = if (state.leftRailWidthDp > 0f)
                            state.leftRailBottomInsetDp.dp else state.leftRailInsetDp.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                var railListHeightPx by remember { mutableIntStateOf(0) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(3f)
                        // 面板四边内缩对齐九键左栏面板的 LocalKeyVisualPadding
                        // （keySpacingX/Y）：键盘态面板四边带缩进，展开页背景此前全宽
                        // 全高导致背景块比键盘态大、展开切换时外扩
                        .padding(
                            horizontal = state.leftRailInsetXDp.dp,
                            vertical = state.leftRailInsetYDp.dp
                        )
                        .then(railShadowModifier)
                        // 圆角由容器统一裁剪：列表滚动时内容被裁在圆角内，
                        // 圆角不再依赖首/末 item 的位置（修复滚动时首/尾圆角丢失）
                        .clip(RoundedCornerShape(LocalKeyCornerRadius.current))
                        .background(keyBg)
                        .onSizeChanged { railListHeightPx = it.height }
                ) {
                    if (railItems.size <= 4) {
                        railItems.forEachIndexed { index, item ->
                            CandidateRailSymbolKey(
                                text = item,
                                onClick = {
                                    if (isPinyinRail) callbacks.onRailPinyinSelect?.invoke(index)
                                    else callbacks.onCommitText?.invoke(item)
                                },
                                keyBg = keyBg,
                                textColor = state.textColor,
                                modifier = Modifier.weight(1f),
                                isSelected = isPinyinRail && index == state.railSelectedPinyinIndex,
                                accentColor = state.railAccentColor,
                                selectedBgColor = state.railSelectedBgColor,
                                isPinyin = isPinyinRail
                            )
                        }
                    } else {
                        // 每条高 = 列表区高/4（视口恰好显示 4 条），超出滚动查看
                        val itemHeightDp = with(LocalDensity.current) {
                            (railListHeightPx / 4).coerceAtLeast(1).toDp()
                        }
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(railItems) { index, item ->
                                CandidateRailSymbolKey(
                                    text = item,
                                    onClick = {
                                        if (isPinyinRail) callbacks.onRailPinyinSelect?.invoke(index)
                                        else callbacks.onCommitText?.invoke(item)
                                    },
                                    keyBg = keyBg,
                                    textColor = state.textColor,
                                    modifier = Modifier.height(itemHeightDp),
                                    isSelected = isPinyinRail && index == state.railSelectedPinyinIndex,
                                    accentColor = state.railAccentColor,
                                    selectedBgColor = state.railSelectedBgColor,
                                    isPinyin = isPinyinRail
                                )
                            }
                        }
                    }
                }
                RailKey(
                    onClick = { callbacks.onToggleSingleCharFilter?.invoke() },
                    keyBg = if (state.singleCharFilter) state.textColor.copy(alpha = 0.28f) else keyBg,
                    // 键位区内缩（同九键符号键 KeyButton 的 LocalKeyVisualPadding）：
                    // 视觉块顶/底边与键盘态符号键一致，间隙同为 spacedBy+两侧 keySpacingY
                    modifier = Modifier
                        .padding(
                            horizontal = state.leftRailInsetXDp.dp,
                            vertical = state.leftRailInsetYDp.dp
                        )
                        .weight(1f)
                ) {
                    // 显示当前模式：候选（全部）/ 单字（筛选中，高亮底色）
                    Text(
                        text = if (state.singleCharFilter) tr(R.string.s_danzi) else tr(R.string.s_houxuan),
                        color = state.textColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1
                    )
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

            // ── 中间：候选行分组列表（LazyColumn 只渲染可见行），联想词在末尾
            // 随内容一并滚动 ──
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .onSizeChanged { viewportHeightPx = it.height },
                state = listState,
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                itemsIndexed(
                    state.candidateRows,
                    key = { _, row -> row.first().globalIndex },
                    contentType = { _, _ -> "candidateRow" }
                ) { _, row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                    ) {
                        row.forEachIndexed { colIndex, entry ->
                            if (colIndex > 0) {
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .fillMaxHeight(0.6f)
                                        .align(Alignment.CenterVertically)
                                        .background(dividerColor)
                                )
                            }
                            CandidatePageItem(
                                entry = entry,
                                onClick = { callbacks.onCandidateSelect(entry) },
                                onLongClick = { callbacks.onCandidateLongPress?.invoke(entry) },
                                textColor = state.textColor,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                if (state.associationCandidates.isNotEmpty()) {
                    item(key = "assoc") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(dividerColor)
                            )
                            FlexRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalSpacing = 6.dp,
                                verticalSpacing = 6.dp
                            ) {
                                state.associationCandidates.forEachIndexed { index, candidate ->
                                    if (index > 0) FlexRowDivider(dividerColor)
                                    CandidatePageItem(
                                        entry = CandidateEntry(text = candidate),
                                        onClick = { callbacks.onAssociationSelect?.invoke(index) },
                                        textColor = state.textColor
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // ── 右栏：退格 / 上一页 / 下一页 / 回车 ──
            // 九键（rightRailWidthDp>0）：与键盘态右列同款"键位区+内缩"模型——列宽
            // 同公式浮点、顶从页顶起、底部留 leftRailBottomInsetDp、键各自内缩
            // keySpacing 并 weight 均分，展开/收起切换右栏轮廓不跳位；
            // 竖屏其余布局保持固定方块、垂直居中分布；宽容器/悬浮改为等分压缩
            val compactRail = isWide || state.rightRailEqualSplit
            val t9RightRail = state.rightRailWidthDp > 0f
            val railKeyModifier = if (compactRail || t9RightRail)
                Modifier
                    .padding(
                        horizontal = state.leftRailInsetXDp.dp,
                        vertical = state.leftRailInsetYDp.dp
                    )
                    .weight(1f)
            else Modifier.size(46.dp)
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(if (t9RightRail) state.rightRailWidthDp.dp else rightRailWidth)
                    .padding(
                        top = if (t9RightRail) 0.dp else 6.dp,
                        bottom = if (t9RightRail) state.leftRailBottomInsetDp.dp else 6.dp
                    ),
                verticalArrangement = if (compactRail || t9RightRail) Arrangement.spacedBy(4.dp)
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
                        contentDescription = tr(R.string.s_tuige),
                        tint = state.textColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                RailKey(
                    onClick = {
                        onHapticFeedback?.invoke()
                        scrollPage(-1)
                    },
                    keyBg = keyBg,
                    modifier = railKeyModifier,
                    enabled = listState.canScrollBackward
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowUp,
                        contentDescription = tr(R.string.s_shangyiye),
                        tint = if (listState.canScrollBackward) state.textColor else state.textColor.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                RailKey(
                    onClick = {
                        onHapticFeedback?.invoke()
                        scrollPage(1)
                    },
                    keyBg = keyBg,
                    modifier = railKeyModifier,
                    enabled = listState.canScrollForward
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = tr(R.string.s_xiayiye),
                        tint = if (listState.canScrollForward) state.textColor else state.textColor.copy(alpha = 0.3f),
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
                        contentDescription = tr(R.string.s_huiche),
                        tint = state.textColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 底部留白
        Spacer(
            modifier = Modifier.height(
                if (isWide) 15.dp else state.bottomPaddingDp.dp
            )
        )
    }
    }
}

/**
 * 候选条目：候选词与拼音注释拼进同一文本（注释用次级色 + 注释字体），
 * 字号固定不缩放，超宽时省略号截断。
 * 行内条目间竖分隔线：主候选区由行 Row 摆放（条目 weight 均分宽度），
 * 联想区仍由 FlexRow 摆放。
 */
@Composable
private fun CandidatePageItem(
    entry: CandidateEntry,
    onClick: () -> Unit,
    textColor: Color,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val displayComment = entry.comment.replace("~", "")
    val annotated = remember(entry.text, displayComment, textColor) {
        buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = textColor,
                    fontSize = 18.sp,
                    fontFamily = AppFonts.candidateFontFamily
                )
            ) {
                append(entry.text)
            }
            if (displayComment.isNotEmpty()) {
                withStyle(
                    SpanStyle(
                        color = textColor.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontFamily = AppFonts.commentFontFamily
                    )
                ) {
                    append(" ")
                    append(displayComment)
                }
            }
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isPressed) textColor.copy(alpha = 0.12f) else Color.Transparent)
            .tolerantClick(
                showRipple = false,
                interactionSource = interactionSource,
                onLongClick = onLongClick,
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = annotated,
            style = TextStyle(fontWeight = FontWeight.Normal),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 简化 flexbox 行布局：条目先按内容宽度贪心分行（放不下自动换行），再把每行
 * 剩余宽度均分给该行所有条目拉宽，保证每行两端饱满、行尾不留空白。
 * content 中的 [FlexRowDivider] 子项被摆放在其前条目与下一条目的间隙正中，
 * 行尾条目后的分隔线不绘制。
 */
@Composable
private fun FlexRow(
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 0.dp,
    verticalSpacing: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        when {
            measurables.isEmpty() -> layout(0, 0) {}
            constraints.maxWidth == Constraints.Infinity -> {
                // 无界宽度兜底：条目按自然尺寸线性排列，不做分行拉伸，跳过分隔线
                val items = measurables.filter { it.layoutId != FLEX_ROW_DIVIDER_ID }
                val placeables = items.map { it.measure(constraints) }
                layout(placeables.sumOf { it.width }, placeables.maxOf { it.height }) {
                    var x = 0
                    placeables.forEach { p ->
                        p.placeRelative(x, 0)
                        x += p.width
                    }
                }
            }
            else -> {
                val hSpace = horizontalSpacing.roundToPx()
                val vSpace = verticalSpacing.roundToPx()
                val maxWidth = constraints.maxWidth
                val perfT0 = android.os.SystemClock.elapsedRealtime()

                // 解析子项：条目 + 条目间分隔线（分隔线归属其前条目）
                val itemMeasurables = mutableListOf<Measurable>()
                val dividerOf = HashMap<Int, Measurable>()
                measurables.forEach { m ->
                    if (m.layoutId == FLEX_ROW_DIVIDER_ID) {
                        if (itemMeasurables.isNotEmpty()) dividerOf[itemMeasurables.lastIndex] = m
                    } else {
                        itemMeasurables.add(m)
                    }
                }

                // 第一遍：条目按完整单行宽度贪心分行（同一 Measurable 只允许 measure
                // 一次，探尺寸必须走 intrinsic；须用 maxIntrinsicWidth —— min 对中文
                // 返回约一字宽，会导致分行过多、条目被压出省略号）
                val naturalWidths = itemMeasurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
                val rows = mutableListOf<MutableList<Int>>()
                var current = mutableListOf<Int>()
                var currentWidth = 0
                naturalWidths.forEachIndexed { index, w ->
                    val nextWidth = if (current.isEmpty()) w else currentWidth + hSpace + w
                    if (current.isNotEmpty() && nextWidth > maxWidth) {
                        rows.add(current)
                        current = mutableListOf()
                        currentWidth = 0
                    }
                    current.add(index)
                    currentWidth = if (current.size == 1) w else currentWidth + hSpace + w
                }
                if (current.isNotEmpty()) rows.add(current)

                // 第二遍：按行测量条目（行内剩余宽度均分拉宽，余数摊给前几项凑满整行）
                // 与行内条目间竖线（约 60% 行高，居中），每个子项仅 measure 一次
                val rowPlacements = mutableListOf<List<Triple<Placeable, Int, Int>>>()
                val rowHeights = mutableListOf<Int>()
                rows.forEach { row ->
                    val naturalSum = row.sumOf { naturalWidths[it] } + hSpace * (row.size - 1)
                    val extra = (maxWidth - naturalSum).coerceAtLeast(0)
                    val per = extra / row.size
                    val remainder = extra % row.size
                    val measured = row.mapIndexed { i, itemIdx ->
                        val w = (naturalWidths[itemIdx] + per + if (i < remainder) 1 else 0)
                            .coerceAtMost(maxWidth)
                        itemIdx to itemMeasurables[itemIdx].measure(
                            Constraints(
                                minWidth = w,
                                maxWidth = w,
                                minHeight = 0,
                                maxHeight = constraints.maxHeight
                            )
                        )
                    }
                    val rowHeight = measured.maxOf { it.second.height }
                    val entries = mutableListOf<Triple<Placeable, Int, Int>>()
                    var x = 0
                    measured.forEachIndexed { i, (itemIdx, p) ->
                        entries.add(Triple(p, x, (rowHeight - p.height) / 2))
                        x += p.width
                        // 行内非末尾条目之后摆放竖分隔线（行尾条目后的线不绘制）
                        if (i < measured.lastIndex) {
                            dividerOf[itemIdx]?.let { div ->
                                val divHeight = (rowHeight * 0.6f).toInt().coerceAtLeast(1)
                                val divPlaceable = div.measure(
                                    Constraints(minHeight = divHeight, maxHeight = divHeight)
                                )
                                entries.add(
                                    Triple(
                                        divPlaceable,
                                        x + (hSpace - divPlaceable.width) / 2,
                                        (rowHeight - divHeight) / 2
                                    )
                                )
                            }
                        }
                        x += hSpace
                    }
                    rowPlacements.add(entries)
                    rowHeights.add(rowHeight)
                }

                val totalHeight = rowHeights.sum() +
                    vSpace * (rowHeights.size - 1).coerceAtLeast(0)
                if (debugPerfLogging) {
                    val cost = android.os.SystemClock.elapsedRealtime() - perfT0
                    if (cost > 3) {
                        android.util.Log.d(
                            "CandidatePerf",
                            "FlexRow measure: items=${itemMeasurables.size} rows=${rows.size} cost=${cost}ms"
                        )
                    }
                }
                layout(maxWidth, totalHeight) {
                    var y = 0
                    rowPlacements.forEachIndexed { ri, entries ->
                        entries.forEach { (p, x, entryY) -> p.placeRelative(x, y + entryY) }
                        y += rowHeights[ri] + vSpace
                    }
                }
            }
        }
    }
}

/** FlexRow 条目间竖分隔线的 layoutId 标记。 */
private const val FLEX_ROW_DIVIDER_ID = "flex_row_divider"

/**
 * FlexRow 条目间竖分隔线：宽度 1dp，高度由 FlexRow 按行高的 60% 指定；
 * 位于行尾条目之后时不会被绘制。调用处约定插在每个条目之前（首个条目前不插）。
 */
@Composable
private fun FlexRowDivider(color: Color) {
    Box(
        modifier = Modifier
            .layoutId(FLEX_ROW_DIVIDER_ID)
            .width(1.dp)
            .background(color)
    )
}

/**
 * 左栏符号/拼音条目（样式对齐九键键盘左栏 CandidateItem）：条目自身非按压透明
 * （背景由列容器统一绘制，条目不再叠画 keyBg——半透明主题键色叠两次会变亮），
 * 列容器负责圆角背景与滚动裁剪（首尾圆角不再附着在条目上，
 * 避免滚动时圆角随首/末条目滚出视口而丢失）。按压背景加深（0.7 透明度）。
 * [isSelected] 时渲染选中胶囊（对齐九键 CandidateItem 的 accentColor 高亮），
 * [isPinyin] 用拼音字号（13sp，对齐九键左栏），否则符号字号 16sp。
 */
@Composable
private fun CandidateRailSymbolKey(
    text: String,
    onClick: () -> Unit,
    keyBg: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    accentColor: Color = Color.Unspecified,
    selectedBgColor: Color = Color.Unspecified,
    isPinyin: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pillColor = if (accentColor == Color.Unspecified) textColor else accentColor
    val pillBg = if (selectedBgColor == Color.Unspecified) pillColor.copy(alpha = 0.2f) else selectedBgColor

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 非按压透明：容器已画 keyBg，条目再画一遍会把半透明主题键色叠两次
            // （合成后比键盘左栏面板更亮）；按压加深与九键 CandidateItem 同款
            .background(if (isPressed) keyBg.copy(alpha = 0.7f) else Color.Transparent)
            .tolerantClick(
                showRipple = false,
                interactionSource = interactionSource,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(pillBg)
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            ) {
                Text(
                    text = text,
                    color = pillColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    fontFamily = AppFonts.candidateFontFamily
                )
            }
        } else {
            Text(
                text = text,
                color = textColor,
                fontSize = if (isPinyin) 13.sp else 16.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                fontFamily = AppFonts.candidateFontFamily
            )
        }
    }
}

/** 栏内实体按键：圆角方块、按压加深（enabled=false 时淡化）。等分栏高由调用方传 weight。
 *  圆角默认取主题键圆角（LocalKeyCornerRadius），与九键右列 KeyButton、数字键盘右栏
 *  NumberSymbolKey 同源——此前右栏写死 14dp，与键盘态键圆角不一致。 */
@Composable
private fun RailKey(
    onClick: () -> Unit,
    keyBg: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = LocalKeyCornerRadius.current,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
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
