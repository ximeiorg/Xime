package com.kingzcheung.xime.settings

import com.kingzcheung.xime.keyboard.GestureAction

/**
 * 单个动作定义（对应 YAML 中一个手势槽位的取值）。
 *
 * @param action 动作类型；null 表示无动作
 * @param value 动作参数（如 commit 的上屏文本、command 的命令名）
 * @param label 显示标签
 * @param icon 图标名（标签以 `@` 前缀书写时提取）
 * @param display 标签/气泡显示位置（key：画在键面；bubble：不画键面，留给气泡；both：都画）
 * @param bubble 运行时是否弹出该手势的内容气泡（与 display 无关，display 只管静态提示位置）
 * @param explicitAction 是否在 YAML 中显式写了 `action`。用于区分「显式 action: commit」
 *   （必须直接上屏、不经过引擎）与「槽位默认 commit」（字母区保持按键路由语义）。
 */
class KeyAction(
    val action: GestureAction? = null,
    val value: String = "",
    val label: String = "",
    val icon: String = "",
    val display: DisplayMode = DisplayMode.BOTH,
    val bubble: Boolean = true,
    val explicitAction: Boolean = false,
)

/**
 * 长按配置。
 *
 * 单动作对象 → [values] 仅一项；数组 → 气泡列表多项。
 *
 * @param display 显示模式；目前只支持 `bubble`（`key` / `both` 会在解析时回退为 `bubble` 并告警，
 *   长按候选的键面绘制尚未实现）
 * @param values 长按候选动作
 */
class LongPressAction(
    val display: DisplayMode = DisplayMode.KEY,
    val values: List<KeyAction> = emptyList(),
)

/**
 * 按键背景色配置：
 * - [Hex] 固定颜色（0xRRGGBB / 0xAARRGGBB）
 * - [Token] 主题色记号，渲染时按当前主题/明暗解析，跟随用户切换主题
 */
sealed interface KeyColor {
    data class Hex(val argb: Long) : KeyColor

    /** [name] 已规范化为小写，例如 accent / primary / special / key / keyboard。 */
    data class Token(val name: String) : KeyColor
}

/** 支持的主题色记号（也接受 theme. 前缀，解析时去掉）。 */
internal val KEY_COLOR_TOKENS: Set<String> = setOf(
    "accent", "primary", "special", "key", "keyboard",
)

/**
 * 一个键的手势绑定表。
 *
 * @param width 布局宽度权重；null 时功能键用内置默认宽度、字母键为 1
 * @param cornerRadius 该键的圆角半径（dp）；null = 沿用键盘级圆角
 * @param background 该键背景色（固定颜色或主题色记号）；null = 沿用主题按键底色
 * @param backgroundDark 暗色模式下的该键背景色；null = 沿用 [background]
 */
class KeyBinding(
    val tap: KeyAction? = null,
    val doubleTap: KeyAction? = null,
    val longPress: LongPressAction? = null,
    val swipeUp: KeyAction? = null,
    val swipeDown: KeyAction? = null,
    val swipeLeft: KeyAction? = null,
    val swipeRight: KeyAction? = null,
    val width: Float? = null,
    val cornerRadius: Int? = null,
    val background: KeyColor? = null,
    val backgroundDark: KeyColor? = null,
)

/** 只写了外观字段（corner_radius / background / background_dark）的覆盖。 */
internal fun KeyBinding.isStyleOnlyOverride(): Boolean =
    tap == null && doubleTap == null && longPress == null &&
        swipeUp == null && swipeDown == null && swipeLeft == null && swipeRight == null &&
        width == null && (cornerRadius != null || background != null || backgroundDark != null)

/**
 * 合并按键配置：
 * - 只写外观字段时，保留基础键的 tap/长按/滑动/宽度，仅覆盖外观；
 * - 写了任一行为字段时，沿用原有「键级整体替换」规则。
 *
 * 这样彩色键盘示例只写 background/corner_radius 也不会把上滑数字、长按候选等弄丢。
 */
internal fun mergeKeyBinding(base: KeyBinding?, override: KeyBinding): KeyBinding {
    if (base == null || !override.isStyleOnlyOverride()) return override
    return KeyBinding(
        tap = base.tap,
        doubleTap = base.doubleTap,
        longPress = base.longPress,
        swipeUp = base.swipeUp,
        swipeDown = base.swipeDown,
        swipeLeft = base.swipeLeft,
        swipeRight = base.swipeRight,
        width = base.width,
        cornerRadius = override.cornerRadius ?: base.cornerRadius,
        background = override.background ?: base.background,
        backgroundDark = override.backgroundDark ?: base.backgroundDark,
    )
}

/** 键级合并两组手势配置：custom 覆盖 default，外观-only 覆盖保留默认手势。 */
internal fun mergeGestureMaps(
    base: Map<String, KeyBinding>,
    overrides: Map<String, KeyBinding>,
): Map<String, KeyBinding> {
    if (overrides.isEmpty()) return base
    val merged = LinkedHashMap(base)
    for ((key, override) in overrides) merged[key] = mergeKeyBinding(base[key], override)
    return merged
}

/**
 * 面板段（数字/常用符号键盘）的布局描述。
 *
 * @param columns true = 三列模式（数字键盘：[left] 键柱列 / [rows] 数字区 / [right] 功能列）；
 *   false = 行模式（常用符号：[rows] 逐行渲染）
 * @param rows 行模式的全部行；三列模式的中列（数字区）行
 * @param left 三列模式左列：每项 = 单键（单元素列表）或纵向键柱（多元素列表，如加减乘除四键一柱）
 * @param right 三列模式右列：每项 = 单键
 */
data class PanelLayoutConfig(
    val columns: Boolean,
    val rows: List<List<String>> = emptyList(),
    val left: List<List<String>> = emptyList(),
    val right: List<List<String>> = emptyList(),
)
