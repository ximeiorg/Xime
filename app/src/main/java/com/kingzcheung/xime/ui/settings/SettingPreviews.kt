package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置项的"效果自测"预览。
 *
 * 每个**看得见效果**的选项各配一张，规格统一：同一套外框（[SettingsPreviewFrame]）、
 * 同一段样例文案、同一套候选/键面画法。因此页面上五张预览的节奏是一致的，
 * 但每张只反映它自己那一项——改字号就看候选字，改密度就看候选数，不存在
 * "一张大预览里改哪儿都不明显、也分不清是哪一项在起作用"的问题。
 *
 * 为了让"只反映本项"成立，非本项的维度一律取中性固定值（字号 18sp、每页 4 个、
 * 注释关、编码在候选栏），而不是互相串用当前值。
 */

/** 预览外框：所有预览共用，保证圆角、底色、内边距、最小高度一致。 */
@Composable
fun SettingsPreviewFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // 底板保持浅色（半透明 surfaceVariant），层次靠内部元素压深来体现，而不是把底板压黑。
    val frameColor = scheme.surfaceVariant.copy(alpha = 0.5f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(frameColor)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

/**
 * 宿主输入框：所有涉及"输入框"的预览共用同一套度量。
 *
 * 高度由内容撑开（`padding` 而不是固定高度）：固定高度配小字号会把文字压扁、基线偏移，
 * 看起来"没对齐"。圆角 6dp、水平 8dp / 垂直 5dp 内边距，光标高度跟着字号走。
 */
@Composable
private fun PreviewInputBox(
    modifier: Modifier = Modifier,
    textSize: Int = 14,
    codeAfterCursor: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(scheme.onSurfaceVariant.copy(alpha = 0.12f))
            .border(0.5.dp, scheme.outlineVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = PREVIEW_COMMITTED,
            fontSize = textSize.sp,
            color = scheme.onSurface,
            maxLines = 1,
        )
        if (codeAfterCursor) {
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = PREVIEW_CODE,
                fontSize = textSize.sp,
                color = accent,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.width(1.dp))
        // 光标：比字号高 2dp，与文字行高对齐
        Box(
            modifier = Modifier
                .width(1.5.dp)
                .height((textSize + 2).dp)
                .background(accent)
        )
    }
}

/**
 * 候选行预览：字号 / 每页候选数 / 编码注释三项共用。
 *
 * 超出预览宽度的候选会被裁掉——这与实机候选栏"一页放不下就左右滑"的表现一致，
 * 因此"每页候选数"调大时图片不再变化，正是真实效果。
 */
@Composable
fun CandidateLinePreview(
    candidateTextSize: Int,
    candidateCount: Int,
    showComments: Boolean,
    modifier: Modifier = Modifier,
) {
    val count = candidateCount.coerceIn(1, PREVIEW_CANDIDATES.size)
    SettingsPreviewFrame(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PREVIEW_CANDIDATES.take(count).forEachIndexed { index, word ->
                PreviewCandidate(
                    word = word,
                    isSelected = index == 0,
                    candidateTextSize = candidateTextSize,
                    showComments = showComments,
                )
            }
        }
    }
}

/**
 * 编码位置预览：输入框 + 候选栏同框。
 *
 * 两种落点按实机表现画，而不是按字面"候选栏"想象的：
 * - 输入框：编码跟在光标后、与已上屏文字同行（`setComposingText` 写进宿主）；
 * - 候选栏：编码**不在栏内**，而是候选栏顶部之上的一条小胶囊（见 `CandidateBar.drawPreeditBubble`），
 *   与栏顶留 2dp，栏内只有候选；同时输入框里只剩已上屏文字与光标。
 *
 * 这一项描述的是"两个容器之间的位置关系"，所以必须同框出现；但与旧版左右并排两张静态示意图
 * 不同，这里是一张预览随选择实时切换。
 */
@Composable
fun CodePositionPreview(
    codeInInputBox: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    SettingsPreviewFrame(modifier) {
        Column {
            PreviewInputBox(codeAfterCursor = codeInInputBox)

            if (!codeInInputBox) {
                // 编码胶囊：悬浮在候选栏顶部之上，与栏顶 2dp 间隙，左缘对齐候选栏。
                // 高度收紧到"一行小字 + 1dp 内边距"：实机胶囊是贴着栏顶的窄条，
                // 之前按默认行高量出来的背景明显偏胖。
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(scheme.onSurfaceVariant.copy(alpha = 0.10f))
                        .border(
                            0.5.dp,
                            scheme.outlineVariant.copy(alpha = 0.6f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = PREVIEW_CODE,
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.width(1.5.dp))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(8.dp)
                            .background(accent)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }

            // 候选栏：编码不在这里（栏内只有候选）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(scheme.onSurfaceVariant.copy(alpha = 0.08f))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PREVIEW_CANDIDATES.take(3).forEachIndexed { index, word ->
                    PreviewCandidate(
                        word = word,
                        isSelected = index == 0,
                        candidateTextSize = 16,
                        showComments = false,
                    )
                    if (index != 2) Spacer(modifier = Modifier.width(4.dp))
                }
            }
        }
    }
}

/**
 * 键面字号预览：一行字母键，标签按 [letterScale] 缩放。
 *
 * 只画一行：字号大小在同一个键宽下才看得准，行数多了反而会把注意力引到布局上。
 */
@Composable
fun KeycapPreview(
    letterScale: Float,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val letterSize = (11f * letterScale).coerceIn(8f, 16f).sp
    SettingsPreviewFrame(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            QWERTY_ROW0.forEach { key ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(scheme.onSurfaceVariant.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = key,
                        fontSize = letterSize,
                        fontWeight = FontWeight.Normal,
                        color = scheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 横屏分体预览：四行键位（三行字母 + 控制行），开启时从中间裂开。
 *
 * 结构照实机 `LandscapeKeyboardContent`：
 * - 左右两半各 42%、中缝 16%（[SPLIT_HALF_WEIGHT] / [SPLIT_GAP_WEIGHT]）；
 * - 行拆分同 `splitRowForLandscape`：`take(half)` + `takeLast(half)`，奇数行的中间键
 *   （9 键行的第 5 个、控制行的空格）在两半各出现一次，所以**两半键数相同、同一行
 *   左右键宽一致**，空格自然被中缝劈成两块；
 * - 行错位沿用竖屏 QWERTY：只有第二行（A 行）两端内缩 [SPLIT_ROW_INSET]，
 *   第一行、第三行、控制行都贴边；分体后这个缩进只加在外侧（左半 start /
 *   右半 end），中缝固定不动、竖直对齐。
 *
 * 关闭态就是普通横屏键盘：整行铺满、无中缝，只有第二行的两端缩进。
 */
@Composable
fun SplitKeyboardPreview(
    splitEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    SettingsPreviewFrame(modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(KEY_ROW_GAP),
        ) {
            SPLIT_PREVIEW_ROWS.forEachIndexed { rowIndex, keys ->
                // 行错位沿用竖屏：只有第二行（A 行）两端内缩，其余行贴边。
                // 分体态这个缩进只加在外侧（左半 start / 右半 end），中缝不动。
                val rowInset = if (rowIndex == SPLIT_INSET_ROW) SPLIT_ROW_INSET else 0.dp
                if (splitEnabled) {
                    // 照实机 LandscapeKeyboardContent：两半各 42%、中缝 16%，中缝两侧固定不动、
                    // 竖直对齐。与 splitRowForLandscape 一致：take(half) / takeLast(half)，
                    // 奇数行的中间键（9 键行的第 5 个、控制行的空格）在两半各出现一次，
                    // 两半键数因此相同，同一行左右两半的键宽也一致。
                    val half = (keys.size + 1) / 2
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(KEYCAP_HEIGHT),
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(SPLIT_HALF_WEIGHT)
                                .padding(start = rowInset)
                                .fillMaxHeight(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            keys.take(half).forEach { PreviewKeycap(it) }
                        }
                        Spacer(modifier = Modifier.weight(SPLIT_GAP_WEIGHT))
                        Row(
                            modifier = Modifier
                                .weight(SPLIT_HALF_WEIGHT)
                                .padding(end = rowInset)
                                .fillMaxHeight(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            keys.takeLast(half).forEach { PreviewKeycap(it) }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(KEYCAP_HEIGHT)
                            .padding(horizontal = rowInset),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        keys.forEach { PreviewKeycap(it) }
                    }
                }
            }
        }
    }
}

/**
 * 贴底显示预览：键盘**整体向下移动**，第四行贴近底部手势条。
 *
 * 实机里这一项把 `activeBottomDp`（关闭时按导航栏边衬留十几到二十几 dp）归零，键盘高度不变、
 * 整块下沉，第四行随之靠近手势条，键盘上方被屏幕（输入区）裁掉的更多。
 *
 * 预览总高恒定，两态严格相等：
 * - 关闭：[KEYCAP_HEIGHT] 的第四行 + [FLUSH_GAP_CLOSED] 空隙 + 手势条；
 * - 打开：顶部补 [FLUSH_SHIFT] 的**第三行局部**（键盘下沉后空出来的那一段）+ 第四行
 *   + [FLUSH_GAP_OPEN] 空隙 + 手势条。
 *
 * 即 20 + 14 + 8 = 42 = 12 + 20 + 2 + 8：手势条位置不变，第四行整体下沉 [FLUSH_SHIFT]，
 * 上方不会出现空白，卡片也不会变高。第三行只露 [FLUSH_SHIFT] 那一段（不到一行高），
 * 用裁切表达"键盘下面还有内容"。
 */
@Composable
fun FlushBottomPreview(
    flushBottom: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    SettingsPreviewFrame(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 两态总高相同：关 = 20+14+8，开 = 12+20+2+8
                .height(KEYCAP_HEIGHT + FLUSH_GAP_CLOSED + 8.dp),
        ) {
            if (flushBottom) {
                // 键盘下沉后上方空出的那一段，用第三行的下半截填上；高度不够放一整行，
                // 所以只露一段。键行再向上让出 FLUSH_PEEK_GAP，露出的键帽下缘与第四行之间
                // 就正好是 [KEY_ROW_GAP]，与分体预览逐行之间的间距一致。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(FLUSH_SHIFT)
                        .clipToBounds(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(KEYCAP_HEIGHT)
                            // 键帽上/下圆角都推出窗口，只留中段直边：被裁切的一行本来就不该有
                            // 圆角，否则两个圆角相对会撑出 8dp 的假空隙，比真实行距宽得多。
                            .offset(y = FLUSH_SHIFT - KEYCAP_HEIGHT + KEYCAP_CORNER),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        SPLIT_PREVIEW_ROWS[2].forEach { PreviewKeycap(it) }
                    }
                }
            }
            PreviewKeyRow(keys = FLUSH_PREVIEW_ROW)
            // 关键差异：第四行到手势条的间距
            Spacer(
                modifier = Modifier.height(
                    if (flushBottom) FLUSH_GAP_OPEN else FLUSH_GAP_CLOSED
                )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                // 手势条代表系统导航条：底板是浅色，所以用深一档的 onSurface 叠出来才看得清
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(scheme.onSurface.copy(alpha = 0.45f))
                )
            }
        }
    }
}

/**
 * 预览键帽：两张键盘预览共用同一套画法。**不画键面文字**。
 *
 * 这两张要表达的是排布与间距（中缝、逐行错位、贴底），不是字号；在十几 dp 高的键帽里
 * 挤 9sp 字母既会裁切、也和实机观感对不上，反而像"预览坏了"。字母键与功能键仍用
 * 一浅一深两档底色区分，宽度按实机档位给，结构一样读得出来。
 */
private data class PreviewKey(val weight: Float = 1f, val isFunction: Boolean = false)

/** 整行键帽：贴底预览里按行画键盘，键高统一 [KEYCAP_HEIGHT]。 */
@Composable
private fun PreviewKeyRow(keys: List<PreviewKey>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(KEYCAP_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        keys.forEach { PreviewKeycap(it) }
    }
}

@Composable
private fun RowScope.PreviewKeycap(key: PreviewKey) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .weight(key.weight)
            .fillMaxHeight()
            .clip(RoundedCornerShape(KEYCAP_CORNER))
            .background(
                if (key.isFunction) scheme.onSurfaceVariant.copy(alpha = 0.30f)
                else scheme.onSurfaceVariant.copy(alpha = 0.16f)
            ),
    )
}

@Composable
private fun PreviewCandidate(
    word: String,
    isSelected: Boolean,
    candidateTextSize: Int,
    showComments: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val textColor = if (isSelected) accent else scheme.onSurface
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (isSelected) accent.copy(alpha = 0.2f) else Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = word,
            fontSize = candidateTextSize.sp,
            color = textColor,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
        if (showComments) {
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = PREVIEW_CODE,
                // 与实机 CandidateItem 同一比例：注释 = 候选字号的 11/19
                fontSize = (candidateTextSize * 11f / 19f).sp,
                color = textColor.copy(alpha = if (isSelected) 0.6f else 0.5f),
                maxLines = 1,
            )
        }
    }
}

/**
 * 分体预览的四行：10 / 9 /（shift + 7 字母 + 退格）/ 控制行。第三行两端 1.4 倍宽是
 * `functionKeyWidth` 里 shift / delete 的默认档。
 * 开关只改排布（中缝 + 逐行阶梯），不改键数与键宽关系。
 *
 * 横屏分体是从竖屏同行拆出来的，不保留竖屏第二行的两侧缩进，所以这里也没有 inset。
 */
private val QWERTY_ROW0 = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")

/**
 * 控制行（`DEFAULT_QWERTY_ROWS[3]`）：中英切换 / 逗号 / 空格 / 键盘选择 / 回车，
 * 宽度按 `functionKeyWidth` 的默认档 1.2 / 0.8 / 3 / 0.8 / 1.2。空格独大是这一行最好认的特征。
 *
 * 分体与贴底两张预览共用：贴底那张只画这一行（它才是贴着底部手势条的那一行）。
 * 声明必须排在 [SPLIT_PREVIEW_ROWS] 之前——Kotlin 顶层属性按声明顺序初始化。
 */
private val FUNCTION_PREVIEW_ROW: List<PreviewKey> = listOf(
    PreviewKey(1.2f, isFunction = true),
    PreviewKey(0.8f),
    PreviewKey(3f),
    PreviewKey(0.8f),
    PreviewKey(1.2f, isFunction = true),
)

private val SPLIT_PREVIEW_ROWS: List<List<PreviewKey>> = listOf(
    List(10) { PreviewKey() },
    List(9) { PreviewKey() },
    listOf(PreviewKey(1.4f, isFunction = true)) + List(7) { PreviewKey() } +
        listOf(PreviewKey(1.4f, isFunction = true)),
    FUNCTION_PREVIEW_ROW,
)

/** 分体态左右两半的宽度占比与中缝占比：`LandscapeKeyboardContent` 的 0.42 / 0.16 / 0.42。 */
private const val SPLIT_HALF_WEIGHT = 0.42f
private const val SPLIT_GAP_WEIGHT = 0.16f

/**
 * 行错位：与竖屏 QWERTY 一致，只有第二行（A 行）两端各内缩 [SPLIT_ROW_INSET]，
 * 第一行（Q 行）、第三行（shift 行）、控制行都贴边。
 */
private const val SPLIT_INSET_ROW = 1
private val SPLIT_ROW_INSET = 8.dp

/** 贴底预览只画控制行本身。 */
private val FLUSH_PREVIEW_ROW: List<PreviewKey> = FUNCTION_PREVIEW_ROW

/** 键帽高度：去掉键面文字后可以更接近实机的键块比例；分体预览四行，20dp 兼顾"像键"与卡片高度。 */
private val KEYCAP_HEIGHT = 20.dp

/** 键帽圆角：抽成常量，贴底预览推算"裁切后只剩中段"时要用到它。 */
private val KEYCAP_CORNER = 4.dp

/** 键盘各行之间的垂直间距：分体预览与贴底预览共用，保证两处行距一致。 */
private val KEY_ROW_GAP = 3.dp

/** 贴底预览：第四行到手势条的间距（关 / 开），差值就是键盘整体下沉的位移。 */
private val FLUSH_GAP_CLOSED = 14.dp
private val FLUSH_GAP_OPEN = 2.dp
private val FLUSH_SHIFT = FLUSH_GAP_CLOSED - FLUSH_GAP_OPEN


/**
 * 预览样例文案：故意不参与本地化——它只是"这个字号/这一页长什么样"的量具，
 * 与实机主题预览里写死 `shu ru fa` 的处理一致；换成真实候选会随方案变化而抖动。
 */
private const val PREVIEW_CODE = "ni hao"
private const val PREVIEW_COMMITTED = "你好"
private val PREVIEW_CANDIDATES = listOf("你好", "拟好", "你号", "泥蒿", "腻好", "逆号")
