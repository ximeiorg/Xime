package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlContentPolymorphicSerializer
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.ui.TABLET_SMALLEST_WIDTH_DP
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

// ── 键盘手势配置 ──

enum class DisplayMode(val value: String) {
    KEY("key"), BUBBLE("bubble"), BOTH("both");

    companion object {
        fun fromValue(value: String): DisplayMode = entries.firstOrNull { it.value == value } ?: BOTH
    }
}

/** 按键布局模式，从 xime.yaml keyboard.button_layout 加载。 */
enum class ButtonLayout(val value: String) {
    STANDARD("standard"),
    COMPACT("compact");

    companion object {
        /** 未知/空白取值返回 null（视为未配置，回退下一层），避免非法值把内置值压成 standard。 */
        fun fromValueOrNull(value: String): ButtonLayout? =
            entries.firstOrNull { it.value == value.trim() }
    }
}

/**
 * 把滑动手势定义转换为布局层回调（九键/笔画布局共用，26 键在 KeyboardLayout 内联处理）。
 *
 * - COMMIT 走 [onCommitText]：落点由布局决定（九键直接上屏数字——T9 模式 onKeyPress(数字)
 *   会进拼音数字码组合，笔画则沿用按键路由保持原语义）；
 * - NONE 视为未绑定；
 * - 其余动作（复制/粘贴/光标移动/面板切换等）走 [onGestureAction]（UI 拦截层 → 动作分发）。
 */
internal fun swipeHandlerFor(
    def: KeyAction?,
    onCommitText: (String) -> Unit,
    onGestureAction: ((GestureAction, String) -> Unit)?,
): (() -> Unit)? {
    val action = def?.action ?: return null
    if (action == GestureAction.NONE) return null
    val value = def.value.ifEmpty { def.label }
    return {
        if (action == GestureAction.COMMIT) onCommitText(value)
        else onGestureAction?.invoke(action, value)
    }
}

/**
 * 键盘行布局配置，从 xime.yaml keyboard.<section>.layout 加载。
 * rows[i] 为第 i 行的按键 ID 列表。
 */
data class KeyboardLayoutConfig(
    val rows: List<List<String>> = emptyList(),
)

/**
 * 行内键宽的取齐策略（`layout.row_align`，行级 `align` 可覆盖）。
 */
enum class RowAlign(val value: String) {
    /** 键宽按列对齐：行宽 = 键数 / 列数，行居中（缺省）。 */
    UNIFORM("uniform"),

    /** 行内均分铺满整行（键宽 = 行宽 / 本行键数）。 */
    STRETCH("stretch");

    companion object {
        fun fromValue(value: String): RowAlign? =
            entries.firstOrNull { it.value.equals(value.trim(), ignoreCase = true) }
    }
}

/**
 * 一行键盘布局声明。数组/标量行等价于只给 [keys]（其余字段留空 = 用键盘级策略）。
 */
data class KeyboardRow(
    val keys: List<String>,
    /** 行级对齐覆盖；null = 用 [KeyboardRowsLayout.rowAlign]。 */
    val align: RowAlign? = null,
    /** 行占键盘内容宽度的比例（0..1）；null = 按 [align] 推导。 */
    val width: Float? = null,
)

/**
 * 一个键盘 section 的行布局：行声明 + 参考列数 + 键盘级对齐策略。
 * 由 `keyboard.<section>.layout` 的 rows / columns / row_align 解析而来。
 */
data class KeyboardRowsLayout(
    val rows: List<KeyboardRow>,
    /** 参考列数（`layout.columns`）；null = 取首行键数。 */
    val columns: Int? = null,
    /** 键盘级默认对齐（`layout.row_align`），行级 [KeyboardRow.align] 覆盖。 */
    val rowAlign: RowAlign = RowAlign.UNIFORM,
)

/**
 * 键盘行布局解析结果：中英文（或合并键 section）的行布局。
 */
internal data class QwertyRowsConfig(
    val zhLayout: KeyboardRowsLayout,
    val enLayout: KeyboardRowsLayout,
)

/**
 * 键盘阴影配置，从 xime.yaml keyboard.shadow 加载。
 */
data class KeyboardShadowConfig(
    val enabled: Boolean = true,
    val elevation: Int = 1,
    val shapeRadius: Int = 8,
)

/**
 * 单个键盘的间距覆盖配置。
 */
data class KeyboardSpacingConfig(
    val spacingX: Float? = null,
    val spacingY: Float? = null,
    /** 该键盘的按键圆角覆盖（dp，null = 用 `keyboard.key.corner_radius` 全局值） */
    val cornerRadius: Int? = null,
)

/**
 * 键盘按键配置，从 xime.yaml keyboard.key 加载。
 */
data class KeyboardKeyConfig(
    val cornerRadius: Int = 8,
    /** 按键横向间距（dp），未配置时各布局使用自身默认值 */
    val spacingX: Float? = null,
    /** 按键竖向间距（dp），未配置时各布局使用自身默认值 */
    val spacingY: Float? = null,
    /** 各键盘单独覆盖的圆角/间距，key 为键盘名（qwerty/t9/number/stroke/symbol，
     *  平板档可写 `<名>_tablet`，加载时并到同名基础项） */
    val spacingOverrides: Map<String, KeyboardSpacingConfig> = emptyMap(),
) {
    /**
     * 获取指定键盘的有效间距（覆盖值优先，回退全局值）。
     * @return Pair(横向, 竖向)，null 表示未配置
     */
    fun spacingFor(keyboard: String): Pair<Float?, Float?> {
        val override = spacingOverrides[keyboard]
        val sx = override?.spacingX ?: spacingX
        val sy = override?.spacingY ?: spacingY
        return sx to sy
    }

    /**
     * 获取指定键盘的有效按键圆角（dp）：`keyboard.key.<键盘名>.corner_radius` 优先，
     * 未配置回退全局 `corner_radius`。
     */
    fun cornerRadiusFor(keyboard: String): Int =
        spacingOverrides[keyboard]?.cornerRadius ?: cornerRadius
}

/**
 * 键盘颜色配置，从 xime.yaml keyboard.colors 加载。
 * 所有颜色值为 0xRRGGBB 格式（不含 alpha）。
 */
/** 全局键盘颜色后备，背景色已由 color_schemes 的 keyboard_background 接管。 */
data class KeyboardColorsConfig(
    val keyBgColor: Long = 0xFFFFFF,
    val keyBgColorDark: Long = 0x4A4A4A,
    val specialKeyBgColor: Long? = null,
    val specialKeyBgColorDark: Long? = null,
    val keyTextColor: Long = 0x202124,
    val keyTextColorDark: Long = 0xE8EAED,
    val candidateTextColor: Long = 0x1A73E8,
    val candidateTextColorDark: Long = 0x8AB4F8,
) {
    companion object {
        /** 键盘背景后备色，仅当 color_schemes 无 keyboard_background 时使用。 */
        const val FALLBACK_BG_LIGHT: Long = 0xE3E4E8
        const val FALLBACK_BG_DARK: Long = 0x202020
    }
}

/**
 * 解析一个键的手势绑定表（`keyboard.<section>.keys.<keyId>`）。
 *
 * 槽位：tap / double_tap / long_press / swipe_up / swipe_down / swipe_left / swipe_right，
 * 另有键级 `width`（列宽权重）。
 *
 * @param presets `keyboard.actions` 定义的可复用动作预设
 */
internal fun parseKeyBinding(
    map: com.charleskorn.kaml.YamlMap,
    presets: Map<String, KeyAction> = emptyMap(),
): KeyBinding {
    var tap: KeyAction? = null
    var doubleTap: KeyAction? = null
    var longPress: LongPressAction? = null
    var swipeUp: KeyAction? = null
    var swipeDown: KeyAction? = null
    var swipeLeft: KeyAction? = null
    var swipeRight: KeyAction? = null
    var width: Float? = null
    var cornerRadius: Int? = null
    var background: KeyColor? = null
    var backgroundDark: KeyColor? = null
    for ((kNode, vNode) in map.entries) {
        val name = (kNode as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
        val scalar = (vNode as? com.charleskorn.kaml.YamlScalar)?.content
        when (name) {
            "tap" -> tap = parseKeyAction(vNode, GestureAction.SEND_RIME, presets)
            "double_tap" -> doubleTap = parseKeyAction(vNode, GestureAction.COMMIT, presets)
            "long_press" -> longPress = parseLongPress(vNode, presets)
            "swipe_up" -> swipeUp = parseKeyAction(vNode, GestureAction.COMMIT, presets)
            "swipe_down" -> swipeDown = parseKeyAction(vNode, GestureAction.COMMIT, presets)
            "swipe_left" -> swipeLeft = parseKeyAction(vNode, GestureAction.COMMIT, presets)
            "swipe_right" -> swipeRight = parseKeyAction(vNode, GestureAction.COMMIT, presets)
            "width" -> width = scalar?.toFloatOrNull()
            // 按键级外观覆盖：不写则完全沿用键盘级/主题默认，默认样式不变
            "corner_radius" -> cornerRadius = scalar?.toIntOrNull()
            "background" -> background = scalar?.let(::parseKeyColor)
            "background_dark" -> backgroundDark = scalar?.let(::parseKeyColor)
        }
    }
    return KeyBinding(tap, doubleTap, longPress, swipeUp, swipeDown, swipeLeft, swipeRight, width,
        cornerRadius, background, backgroundDark)
}

/**
 * 解析按键背景：
 * - 0xRRGGBB / 0xAARRGGBB → 固定颜色
 * - accent / primary / special / key / keyboard（可带 theme. 前缀）→ 主题色记号
 * - 其它值视为未配置，保持默认背景
 */
private fun parseKeyColor(value: String): KeyColor? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val hex = trimmed.removePrefix("0x").removePrefix("0X")
    if (hex.length in 6..8 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        return hex.toLongOrNull(16)?.let(KeyColor::Hex)
    }
    val token = trimmed.lowercase().removePrefix("theme.")
    return token.takeIf { it in KEY_COLOR_TOKENS }?.let(KeyColor::Token)
}

/**
 * 解析 long_press。支持两种写法：`{ display?, values: [...] }` 与单项直写 `{ action?, value?, ... }`。
 *
 * - [display] 缺省为 `bubble`（长按弹出气泡滑动选择）；设为 `key` 则显示在键面，不弹气泡；
 * - [values] 为候选动作列表（单动作也写成单元素列表）；
 * - 不写 `values` 而直接给 `action`/`value`/`label`/`icon`/`use` 时，等价于只含这一项的列表；
 *   配合 `bubble: false` 即为「不弹气泡、长按直接执行该动作」。
 */
/** 长按气泡最多展示的候选数（气泡按此宽度布局）。 */
private const val MAX_LONG_PRESS_VALUES = 10

/** long_press 单项直写形式（不写 `values`）认可的字段。 */
private val LONG_PRESS_DIRECT_FIELDS = setOf("action", "value", "label", "icon", "use")

private fun parseLongPress(
    node: com.charleskorn.kaml.YamlNode,
    presets: Map<String, KeyAction>,
): LongPressAction? {
    if (node !is com.charleskorn.kaml.YamlMap) {
        if (node is YamlList) {
            Log.w("KeysConfigHelper", "long_press 需写成 { display, values: [...] } 形式，数组简写已不再支持")
        }
        return null
    }
    val valuesNode = node.opt<YamlList>("values")
    if (valuesNode == null) {
        // 单项直写：long_press: { action: "voice", value: "5", bubble: false }
        val fields = node.entries.keys.mapNotNull { (it as? YamlScalar)?.content }.toSet()
        if (fields.none { it in LONG_PRESS_DIRECT_FIELDS }) return null
        return LongPressAction(
            display = DisplayMode.BUBBLE,
            values = listOf(parseKeyAction(node, GestureAction.COMMIT, presets)),
        )
    }
    var display = node.opt<YamlScalar>("display")?.content
        ?.let { DisplayMode.fromValue(it) }
        ?: DisplayMode.BUBBLE
    if (display != DisplayMode.BUBBLE) {
        // 长按的键面绘制尚未实现（v1 曾有「键面显示前 2 个候选」）：一律按气泡处理，
        // 否则 display: key/both 会让长按候选既不画键面、也不弹气泡 —— 完全不可达。
        Log.w("KeysConfigHelper", "long_press 的 display 只支持 bubble：键面绘制未实现，已按 bubble 处理")
        display = DisplayMode.BUBBLE
    }
    if (valuesNode.items.size > MAX_LONG_PRESS_VALUES) {
        Log.w(
            "KeysConfigHelper",
            "long_press.values 最多 $MAX_LONG_PRESS_VALUES 项，已忽略多余 ${valuesNode.items.size - MAX_LONG_PRESS_VALUES} 项"
        )
    }
    val values = valuesNode.items.map { parseKeyAction(it, GestureAction.COMMIT, presets) }.take(MAX_LONG_PRESS_VALUES)
    if (values.any { it.label.ifEmpty { it.value }.isEmpty() }) {
        Log.w("KeysConfigHelper", "long_press 存在既无 label 也无 value 的项：该项无法在气泡中展示，也不会生效")
    }
    if (values.size > 1 && values.any { !it.bubble }) {
        Log.w("KeysConfigHelper", "long_press 多项时不支持 bubble: false（会让该项无法选中），已忽略")
    }
    return LongPressAction(
        display = display,
        values = values,
    )
}

/**
 * 解析单个手势槽位为 [KeyAction]。
 *
 * 取值形式：
 * - 字符串 `"q"` → 字面简写（按槽位默认动作 + value=文本）
 * - 对象 `{ label: "复制", action: copy }` → 内联动作
 * - 对象 `{ use: 预设名 }` → 引用 `keyboard.actions` 预设
 *
 * [defaultAction] 为槽位语义默认：tap → SEND_RIME，swipe/double_tap/long_press → COMMIT。
 */
private fun parseKeyAction(
    node: com.charleskorn.kaml.YamlNode,
    defaultAction: GestureAction,
    presets: Map<String, KeyAction>,
): KeyAction {
    if (node is com.charleskorn.kaml.YamlScalar) {
        val text = node.content
        val icon = if (text.startsWith("@")) text.removePrefix("@") else ""
        val cleanLabel = if (icon.isNotEmpty()) "" else text
        return KeyAction(action = defaultAction, value = text, label = cleanLabel, icon = icon)
    }
    if (node is com.charleskorn.kaml.YamlMap) {
        // 预设引用：{ use: 名称 }
        val use = node.opt<YamlScalar>("use")?.content
        if (!use.isNullOrEmpty()) {
            val preset = presets[use]
            if (preset == null) {
                Log.w("KeysConfigHelper", "手势配置引用了未知动作预设: \"$use\"")
                return KeyAction()
            }
            return preset
        }
        var label = ""
        var iconName = ""
        var action: GestureAction? = defaultAction
        var explicitAction = false
        var value = ""
        var display = "key"
        var bubble = true
        for ((k, v) in node.entries) {
            val key = (k as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
            when (key) {
                "label" -> {
                    if (v is YamlList) {
                        // 数组 label 按多行显示：join 后就是显示文本
                        label = v.items.mapNotNull { (it as? YamlScalar)?.content }.joinToString("\n")
                    } else {
                        label = (v as? YamlScalar)?.content ?: continue
                    }
                }
                "action" -> {
                    explicitAction = true
                    val vStr = (v as? YamlScalar)?.content
                    if (vStr == null || vStr == "null") {
                        action = null
                    } else {
                        action = GestureAction.fromValue(vStr)
                        if (action == null) {
                            Log.w("KeysConfigHelper", "手势配置包含未知 action: \"$vStr\"，该手势将不生效")
                        }
                    }
                }
                "value" -> (v as? YamlScalar)?.content?.let { value = it }
                "display" -> (v as? YamlScalar)?.content?.let { display = it }
                "bubble" -> bubble = (v as? YamlScalar)?.content?.toBooleanStrictOrNull() ?: true
                // 图标名：`icon: mic` 与历史写法 `label: "@mic"` 等价
                "icon" -> (v as? YamlScalar)?.content?.takeIf { it.isNotBlank() }?.let { iconName = it.removePrefix("@") }
                else -> Log.w("KeysConfigHelper", "手势配置含未知字段 \"$key\"（多为拼写错误），已忽略")
            }
        }
        val iconFromLabel = if (label.startsWith("@")) label.removePrefix("@") else ""
        val icon = iconName.ifEmpty { iconFromLabel }
        val cleanLabel = if (iconFromLabel.isNotEmpty()) "" else label
        return KeyAction(
            action = action,
            value = value,
            label = cleanLabel,
            icon = icon,
            display = DisplayMode.fromValue(display),
            bubble = bubble,
            explicitAction = explicitAction,
        )
    }
    return KeyAction(action = null)
}

// ── 原有配置类 ──

/**
 * 背景配置，支持纯色/渐变/图片三种类型。
 *
 * YAML 示例：
 * ```yaml
 * # 纯色
 * keyboard_background:
 *   type: solid
 *   color: 0x8F73E2
 *   color_dark: 0x4A3F7A   # 可选，不指定则自动暗化
 *
 * # 渐变
 * keyboard_background:
 *   type: gradient
 *   colors: [0x8F73E2, 0xE8DEF8]        # 亮色渐变断点
 *   colors_dark: [0x4A3F7A, 0x2D2040]    # 暗色渐变断点（可选）
 *   angle: 135  # 角度制，0=左→右，90=下→上，180=右→左，270=上→下
 *
 * # 图片
 * keyboard_background:
 *   type: image
 *   src: "themes/lavender_bg.png"          # 相对于 assets/
 *   src_dark: "themes/lavender_bg_dark.png" # 暗色变体（可选）
 *   fit: cover  # cover | contain | fill | fit_width | fit_height | none
 * ```
 */
@Serializable
data class BackgroundConfig(
    val type: String = "solid",
    // solid
    val color: Long? = null,
    @SerialName("color_dark")
    val colorDark: Long? = null,
    // gradient
    val colors: List<Long>? = null,
    @SerialName("colors_dark")
    val colorsDark: List<Long>? = null,
    val angle: Int? = null,
    // image
    val src: String? = null,
    @SerialName("src_dark")
    val srcDark: String? = null,
    val fit: String? = null,
    /** 图片背景遮罩：半透明黑色覆盖层降低背景亮度（0~1，如 0.35 表示压暗 35%）。 */
    @SerialName("overlay_alpha")
    val overlayAlpha: Float? = null,
    /** 暗色模式下的遮罩强度，未配置时沿用 [overlayAlpha]。 */
    @SerialName("overlay_alpha_dark")
    val overlayAlphaDark: Float? = null,
)

@Serializable
data class ColorSchemeEntry(
    val name: String = "",
    /** 主色；null 表示未配置（可用 0x000000 显式指定纯黑，勿改用哨兵值判定）。 */
    @SerialName("primary_color")
    val primaryColor: Long? = null,
    @SerialName("keyboard_bg_color")
    val keyboardBgColor: Long? = null,
    @SerialName("key_bg_color")
    val keyBgColor: Long? = null,
    @SerialName("key_bg_color_dark")
    val keyBgColorDark: Long? = null,
    @SerialName("special_key_bg_color")
    val specialKeyBgColor: Long? = null,
    @SerialName("special_key_bg_color_dark")
    val specialKeyBgColorDark: Long? = null,
    @SerialName("candidate_bar_bg_color")
    val candidateBarBgColor: Long? = null,
    @SerialName("key_text_color")
    val keyTextColor: Long? = null,
    @SerialName("key_text_color_dark")
    val keyTextColorDark: Long? = null,
    @SerialName("candidate_text_color")
    val candidateTextColor: Long? = null,
    @SerialName("candidate_text_color_dark")
    val candidateTextColorDark: Long? = null,
    @SerialName("candidate_selected_text_color")
    val candidateSelectedTextColor: Long? = null,
    @SerialName("candidate_selected_text_color_dark")
    val candidateSelectedTextColorDark: Long? = null,
    /** 候选选中背景色；null 表示未配置，按主色 20% 透明派生。 */
    @SerialName("candidate_selected_bg_color")
    val candidateSelectedBgColor: Long? = null,
    @SerialName("candidate_selected_bg_color_dark")
    val candidateSelectedBgColorDark: Long? = null,
    @SerialName("keyboard_background")
    val keyboardBackground: BackgroundConfig? = null,
    @SerialName("key_background")
    val keyBackground: BackgroundConfig? = null,
    @SerialName("candidate_bar_background")
    val candidateBarBackground: BackgroundConfig? = null,
    /** Material You 动态配色：忽略静态颜色字段，运行时从壁纸调色板取色（仅 Android 12+）。 */
    @SerialName("dynamic_color")
    val dynamicColor: Boolean = false,
)

/**
 * 配置元信息。字段全部可空：null = 该层未配置，由 [KeysConfigHelper] 逐字段回退到下一层，
 * 因此 custom 只写 `modified_time` 时不会连带清掉内置的 `app_version` 校验条件。
 */
@Serializable
data class MetadataConfig(
    @SerialName("app_name")
    val appName: String? = null,
    @SerialName("app_version")
    val appVersion: String? = null,
    @SerialName("platform")
    val platform: String? = null,
    @SerialName("config_version")
    val configVersion: Int? = null,
    @SerialName("generator")
    val generator: String? = null,
    @SerialName("modified_time")
    val modifiedTime: String? = null,
)

@Serializable
data class ColorSchemeModeConfig(
    @SerialName("light")
    val light: String? = null,
    @SerialName("dark")
    val dark: String? = null,
)

/** 兼容 color_scheme 的两种写法：对象（{light, dark}）或标量字符串。 */
@OptIn(ExperimentalSerializationApi::class)
object ColorSchemeModeConfigSerializer :
    YamlContentPolymorphicSerializer<ColorSchemeModeConfig>(ColorSchemeModeConfig::class) {

    override fun selectDeserializer(node: YamlNode): DeserializationStrategy<ColorSchemeModeConfig> {
        // 标量写法（config generator 输出）：color_scheme: mu_shan_zi → light 使用该值。
        return if (node is YamlScalar) ScalarColorSchemeDeserializer else ColorSchemeModeConfig.serializer()
    }

    /** 把单个字符串的 color_scheme 反序列化为 light=该字符串。 */
    private object ScalarColorSchemeDeserializer : KSerializer<ColorSchemeModeConfig> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("ScalarColorScheme", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): ColorSchemeModeConfig =
            ColorSchemeModeConfig(light = decoder.decodeString())

        override fun serialize(encoder: Encoder, value: ColorSchemeModeConfig) {
            encoder.encodeString(value.light ?: "")
        }
    }
}

/** 字体配置，从 xime.yaml style 节点加载。字体文件放在 rime/fonts/ 目录下。 */
data class KeyboardFontConfig(
    val keyFont: String = "",
    val keyLabelFont: String = "",
    val candidateFont: String = "",
    val commentFont: String = "",
)

/**
 * 九键（T9）布局配置，从 xime.yaml keyboard.t9.layout 加载。
 *
 * 九键为三列结构：左列（候选面板占位 + 键）、主区（按行的按键网格）、右列（功能键列）。
 * 键 id 分三类：
 * - 数字 "0"~"9"：九键输入键（点按固定为九键数字输入；键面字母 keys.<id>.tap.label、
 *   长按候选 keys.<id>.long_press、手势 keys.<id> 均可配置）；
 * - [KeysConfigHelper.T9_FUNCTION_KEY_IDS] 中的功能键：渲染内置组件，位置可任意安排；
 * - 其余 id：自定义键，行为在 keys.<id> 定义（无配置时键面为键名、点按提交键名）。
 */
data class KeyboardT9LayoutConfig(
    /** 左列（自上而下）。candidates = 候选/符号面板占位（候选态显示音节，空闲态显示 side_symbols）。 */
    val left: List<String> = DEFAULT_T9_LEFT,
    /** 主区行：每个子列表一行，从左到右。 */
    val rows: List<List<String>> = DEFAULT_T9_ROWS,
    /** 右列（自上而下）。 */
    val right: List<String> = DEFAULT_T9_RIGHT,
) {
    companion object {
        val DEFAULT_T9_LEFT: List<String> = listOf("candidates", "symbol")
        val DEFAULT_T9_ROWS: List<List<String>> = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("number", "space", "earth"),
        )
        val DEFAULT_T9_RIGHT: List<String> = listOf("delete", "clear", "enter")
    }
}

/**
 * 九键（T9）键盘配置，从 xime.yaml keyboard.t9 加载。
 */
data class KeyboardT9Config(
    /** 左侧快捷符号栏（空闲态），列表长度不限，超过 4 个时键盘侧滚动显示。 */
    val sideSymbols: List<String>? = null,
    /** 布局（左列/主区行/右列）。 */
    val layout: KeyboardT9LayoutConfig = KeyboardT9LayoutConfig(),
)

/**
 * 笔画键盘配置，从 xime.yaml keyboard.stroke 加载。
 */
data class KeyboardStrokeConfig(
    /** 左侧快捷符号列，列表长度不限，超过 3 个时键盘侧滚动显示。 */
    val sideSymbols: List<String>? = null,
)

/**
 * 部分配置：仅包含 YAML 中显式配置的字段，null = 未配置。
 * 用于 custom → builtIn → 代码默认值的字段级一路 fallback 合并。
 */
internal data class KeyboardFontPartial(
    val keyFont: String? = null,
    val keyLabelFont: String? = null,
    val candidateFont: String? = null,
    val commentFont: String? = null,
)

internal data class KeyboardColorsPartial(
    val keyBgColor: Long? = null,
    val keyBgColorDark: Long? = null,
    val specialKeyBgColor: Long? = null,
    val specialKeyBgColorDark: Long? = null,
    val keyTextColor: Long? = null,
    val keyTextColorDark: Long? = null,
    val candidateTextColor: Long? = null,
    val candidateTextColorDark: Long? = null,
)

internal data class KeyboardShadowPartial(
    val enabled: Boolean? = null,
    val elevation: Int? = null,
    val shapeRadius: Int? = null,
)

internal data class KeyboardKeyPartial(
    val cornerRadius: Int? = null,
    val spacingX: Float? = null,
    val spacingY: Float? = null,
    val spacingOverrides: Map<String, KeyboardSpacingConfig>? = null,
)

internal data class KeyboardT9Partial(
    val sideSymbols: List<String>? = null,
    val layout: KeyboardT9LayoutPartial? = null,
)

internal data class KeyboardT9LayoutPartial(
    val left: List<String>? = null,
    val rows: List<List<String>>? = null,
    val right: List<String>? = null,
)

/** 面板段（number / common_symbols）layout 的未合并字段：null = 该层未写该键。 */
internal data class PanelLayoutPartial(
    val rows: List<List<String>>? = null,
    val left: List<List<String>>? = null,
    val right: List<List<String>>? = null,
)

internal data class KeyboardStrokePartial(
    val sideSymbols: List<String>? = null,
)

/** 字段级一路 fallback：custom → builtIn → 代码默认值。 */
internal inline fun <T> tiered(custom: T?, builtIn: T?, default: T): T = custom ?: builtIn ?: default

/**
 * kaml 0.104 的 [YamlMap.get] 是 reified 泛型取值，值类型与期望类型不符时抛
 * IncorrectTypeException（如 `fonts:` 段整体被注释时值为 YamlNull，`["fonts"] as? YamlMap`
 * 会在 get 内部直接抛异常，`as?` 无机会返回 null）。
 * 统一经 YamlNode 取值后再做真正的安全转换：段缺失/为 null/类型不符时返回 null 而非抛异常。
 */
private inline fun <reified T : YamlNode> YamlMap.opt(key: String): T? =
    get<YamlNode>(key) as? T

@Serializable
data class StyleConfig(
    @SerialName("color_scheme")
    @Serializable(with = ColorSchemeModeConfigSerializer::class)
    val colorScheme: ColorSchemeModeConfig? = null,
    @SerialName("dark_mode")
    val darkMode: Int? = null,
    @SerialName("key_font")
    val keyFont: String? = null,
    @SerialName("key_label_font")
    val keyLabelFont: String? = null,
    @SerialName("candidate_font")
    val candidateFont: String? = null,
    @SerialName("comment_font")
    val commentFont: String? = null,
)

@Serializable
data class XimeConfig(
    @SerialName("xime_index")
    val ximeIndex: XimeIndexConfig? = null,
    @SerialName("color_schemes")
    val colorSchemes: Map<String, ColorSchemeEntry>? = null,
    @SerialName("style")
    val style: StyleConfig? = null,
    @SerialName("metadata")
    val metadata: MetadataConfig? = null,
)

/**
 * 方案市场镜像基址。`baseUrls` 可空：null = 该层未配置，逐层回退；
 * 列表本身仍是整段替换（写 `[]` 视为未配置，回退到内置基址）。
 */
@Serializable
data class XimeIndexConfig(
    @SerialName("base_urls")
    val baseUrls: List<String>? = null,
) {
    /** 取值后的镜像基址：未配置或写空回退内置列表，调用方无需再判空。 */
    val resolvedBaseUrls: List<String>
        get() = baseUrls?.takeIf { it.isNotEmpty() } ?: DEFAULT_INDEX_BASE_URLS
}

/** 方案市场镜像基址兜底值（xime.yaml 与 custom 层均未配置 base_urls 时使用）。 */
internal val DEFAULT_INDEX_BASE_URLS: List<String> = listOf("https://index.ximei.me/")

data class KeysConfig(
    val swipeUp: Map<String, String> = emptyMap(),
    val swipeDownEnglish: Map<String, String> = emptyMap()
)

object KeysConfigHelper {
    private const val TAG = "KeysConfigHelper"
    private const val XIME_CONFIG_FILE = "xime.yaml"
    private const val XIME_CUSTOM_CONFIG_FILE = "xime.custom.yaml"

    /**
     * 由 `layout.rows` 引用的功能键 id（行为在其对应 `keys.<id>` 配置）。
     * 布局行里出现这些 id 时，渲染层按 id 选择功能键组件而非字母键。
     */
    val FUNCTION_KEY_IDS: Set<String> = setOf(
        "shift", "delete", "enter", "space", "mode_change", "symbol", "emoji", "earth", "voice", "comma",
        "cursor_left", "cursor_right", "tab", "caps", "shift_left", "shift_right",
        // 面板专用功能键（数字/常用符号键盘，行为内置：返回主键盘/切数字/切常用符号/打开全量符号/中英切换/空格）
        // back_right：平板面板右端的「返回字母键盘」键（与左端 back 同行为、各自可配宽度）
        "back", "back_right", "number", "open_symbols", "space_bar", "space_key",
    )

    /**
     * 九键布局的功能键 id（keyboard.t9.layout 可把任意 id 安排到任意位置）：
     * candidates=拼音候选面板、symbol=符号、number=数字面板、space=空格、
     * earth=中英切换、delete=退格、clear=重输、enter=回车。
     * 组件与行为内置，不可经 keys.<id> 改绑；键在行/列中的相对宽度可用 keys.<id>.width 覆盖。
     */
    val T9_FUNCTION_KEY_IDS: Set<String> = setOf(
        "candidates", "symbol", "number", "space", "earth", "delete", "clear", "enter"
    )

    /** 九键左侧快捷符号栏内置默认值（T9KeyboardLayout 硬编码的历史行为）。 */
    val DEFAULT_T9_SIDE_SYMBOLS: List<String> = listOf("，", "。", "？", "！")

    /**
     * 当前设备是否为平板/大屏（smallestWidth ≥ 600dp，与 ui.isTablet() 的 sw600dp 档一致，
     * 不随横竖屏变化）。KeysConfigHelper 无 Compose 上下文，用 resources 配置直接判定。
     */
    internal fun isTabletDevice(context: Context): Boolean {
        // resources 可能取不到（单测 mock Context / 极端环境），此时按手机档处理而不是抛异常：
        // 该函数在 loadXimeConfig 内被调用，抛异常会让整份配置静默不生效（外层 catch 兜底）。
        val smallestWidthDp = context.resources?.configuration?.smallestScreenWidthDp ?: 0
        return smallestWidthDp >= TABLET_SMALLEST_WIDTH_DP
    }

    /** 平板设备档变体段/覆盖名的后缀（`qwerty_tablet`、`number_tablet`、`key.number_tablet` 同此约定）。 */
    internal const val TABLET_SECTION_SUFFIX = "_tablet"

    /** 判断 YAML 文本是否定义了 `keyboard.<section>` 段。 */
    private fun hasKeyboardSection(yamlText: String, section: String): Boolean = try {
        val root = cachedYamlNode(yamlText) as? YamlMap ?: return false
        val keyboardNode = root.opt<YamlMap>("keyboard") ?: return false
        keyboardNode.opt<YamlMap>(section) != null
    } catch (_: Exception) {
        false
    }

    /**
     * 平板设备档变体段名解析：平板上若 yaml 定义了 `<section>_tablet` 段（内置或用户自定义
     * 任一来源存在即可），QWERTY 全键盘整段改用平板变体；否则维持原段。
     *
     * 回退保证兼容：非平板不受影响；老用户自定义 yaml 没有 `_tablet` 段时也维持原行为；
     * 平板上只写了基础段自定义（无 `_tablet`）则走内置平板默认段。
     */
    internal fun resolveQwertySection(
        section: String,
        defaultText: String,
        customText: String?,
        isTablet: Boolean,
    ): String {
        if (!isTablet) return section
        val tabletSection = section + TABLET_SECTION_SUFFIX
        val hasTablet = hasKeyboardSection(defaultText, tabletSection) ||
            (customText != null && hasKeyboardSection(customText, tabletSection))
        return if (hasTablet) tabletSection else section
    }    /** 笔画键盘左侧符号列内置默认（与历史硬编码一致）。 */
    val DEFAULT_STROKE_SIDE_SYMBOLS: List<String> = listOf("。", "？", "！", "~")
    
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
    
    private var config: KeysConfig = KeysConfig(
        swipeUp = getDefaultSwipeUp(),
        swipeDownEnglish = getDefaultSwipeDownEnglish()
    )

    // 手势配置缓存（mutableStateOf 让 Compose 直接观察变更）
    // 中文键盘（qwerty）手势配置缓存
    private val _keyGestureConfig = mutableStateOf<Map<String, KeyBinding>>(emptyMap())
    val keyGestureConfig: Map<String, KeyBinding> get() = _keyGestureConfig.value
    
    // 英文键盘（qwerty_en）手势配置缓存
    private val _keyGestureConfigEn = mutableStateOf<Map<String, KeyBinding>>(emptyMap())
    val keyGestureConfigEn: Map<String, KeyBinding> get() = _keyGestureConfigEn.value
    
    // 键盘颜色配置缓存
    private var keyboardColorsConfig: KeyboardColorsConfig = KeyboardColorsConfig()
    
    // 键盘阴影配置缓存
    private var keyboardShadowConfig: KeyboardShadowConfig = KeyboardShadowConfig()

    // 键盘按键配置缓存
    private var keyboardKeyConfig: KeyboardKeyConfig = KeyboardKeyConfig()

    // 九键（T9）键盘配置缓存
    private var keyboardT9Config: KeyboardT9Config = KeyboardT9Config(sideSymbols = DEFAULT_T9_SIDE_SYMBOLS)
    private var keyboardStrokeConfig: KeyboardStrokeConfig = KeyboardStrokeConfig(sideSymbols = DEFAULT_STROKE_SIDE_SYMBOLS)

    // 面板段（数字/常用符号）配置缓存：keyboard.<section> 独立解析（布局 + 手势），custom 键级覆盖，
    // 支持 <section>_tablet 设备档变体（同 qwerty 机制）。布局缺失的段不入表，渲染层走内置兜底。
    private var _panelLayouts: Map<String, PanelLayoutConfig> = emptyMap()
    private var _panelGestureConfigs: Map<String, Map<String, KeyBinding>> = emptyMap()

    /** 面板段名清单（数字键盘 / 常用符号键盘；符号面板中英共用一套，无中/英两段）。 */
    val PANEL_SECTIONS: List<String> = listOf("number", "common_symbols")

    /** 面板布局：rows 模式（常用符号，行列表）或 columns 模式（数字键盘，左列/中行/右列）。 */
    fun getPanelLayout(section: String): PanelLayoutConfig? = _panelLayouts[section]

    /** 按段取键手势：qwerty 走主键盘缓存（含合并键段激活态），面板段走面板缓存。 */
    fun getGestureFor(section: String, key: String, isAsciiMode: Boolean = false): KeyBinding? =
        if (section == "qwerty") getKeyGesture(key, isAsciiMode) else _panelGestureConfigs[section]?.get(key)

    // 字体配置缓存
    private var keyboardFontConfig: KeyboardFontConfig = KeyboardFontConfig()
    fun getKeyboardFontConfig(): KeyboardFontConfig = keyboardFontConfig
    
    // 按键布局模式缓存（中文 qwerty / 英文 qwerty_en）
    private var _buttonLayoutZh: ButtonLayout = ButtonLayout.STANDARD
    private var _buttonLayoutEn: ButtonLayout = ButtonLayout.STANDARD
    // 合并键段级按键布局缓存：section（qwerty_14/17/18 及 custom 新增）→ 配置值（未配置的段不入表）
    private var _buttonLayoutBySection: Map<String, ButtonLayout> = emptyMap()
    fun getButtonLayout(isAsciiMode: Boolean): ButtonLayout =
        resolveButtonLayout(isAsciiMode, _activeMergedSection, _buttonLayoutBySection, _buttonLayoutZh, _buttonLayoutEn)

    // 键盘行布局默认值：3 字母行（第三行两端功能键与控制行分离，与 xime.yaml 同行结构），
    // 控制行由 normalizeQwertyRows 补齐。第三行必须带 shift/delete：
    // 首帧早于配置装载时就是靠这份兜底布局，缺键会表现为「shift/退格不显示」。
    private val DEFAULT_ZH_ROWS: List<List<String>> = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("shift", "z", "x", "c", "v", "b", "n", "m", "delete"),
    )
    private val DEFAULT_EN_ROWS: List<List<String>> = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("shift", "z", "x", "c", "v", "b", "n", "m", "delete"),
    )

    // 键盘行布局缓存（中文 / 英文）：用 Compose 状态，配置晚于首帧就绪时自动触发重组
    // （普通字段做不到——首帧画完兜底布局后没人通知重画，直到按一次键才更新）
    private val _zhLayout = mutableStateOf(KeyboardRowsLayout(DEFAULT_ZH_ROWS.map { KeyboardRow(it) }))
    private val _enLayout = mutableStateOf(KeyboardRowsLayout(DEFAULT_EN_ROWS.map { KeyboardRow(it) }))

    // 标准 26 键的基线缓存（合并键布局切换退出时恢复）
    private var _zhLayoutBase: KeyboardRowsLayout = KeyboardRowsLayout(DEFAULT_ZH_ROWS.map { KeyboardRow(it) })
    private var _keyGestureConfigZhBase: Map<String, KeyBinding> = emptyMap()

    // 合并键布局缓存：section（qwerty_14/17/18 及 custom 新增）→ 行布局 / 手势配置
    private var _mergedLayouts: Map<String, KeyboardRowsLayout> = emptyMap()
    private var _mergedGestureConfigs: Map<String, Map<String, KeyBinding>> = emptyMap()
    private var _activeMergedSection: String? = null
    private var _activeSchemaId: String = ""

    // 合并键绑定缓存：schemaId → 键盘 section（xime.yaml keyboard.<section>.schemas 声明）
    private var _schemaSectionBindings: Map<String, String> = emptyMap()
    // 段类型缓存：section → type（keyboard.<section>.type 显式声明，custom 覆盖 builtIn；缺省按段名推断）
    private var _sectionTypes: Map<String, String> = emptyMap()

    /**
     * 代码布局 section：由专属组件渲染（T9KeyboardLayout / StrokeKeyboardLayout /
     * HandwritingKeyboardLayout）。t9 有专属 layout{left,rows,right} 由 t9 配置解析器处理，
     * 不走通用 layout.rows；stroke/handwriting 无行数据。schemas 绑定到这些类型段的方案
     * 走 [codeLayoutForSchema] 查询，不进入合并键行布局缓存
     * （[mergedSectionForSchema] 对其返回 null）。
     * 段名为内置保留名；任意段名可通过 keyboard.<section>.type 显式声明类型（[sectionKindOf]）。
     */
    internal val CODE_LAYOUT_SECTIONS = setOf(SECTION_TYPE_T9, SECTION_TYPE_STROKE, SECTION_TYPE_HANDWRITING)

    /** 段类型取值：全键盘、三类代码布局与面板三列布局（数字键盘）。 */
    internal const val SECTION_TYPE_FULL = "full"
    internal const val SECTION_TYPE_T9 = "t9"
    internal const val SECTION_TYPE_STROKE = "stroke"
    internal const val SECTION_TYPE_HANDWRITING = "handwriting"
    internal const val SECTION_TYPE_COLUMNS = "columns"
    private val SECTION_TYPES_KNOWN = setOf(
        SECTION_TYPE_FULL, SECTION_TYPE_T9, SECTION_TYPE_STROKE, SECTION_TYPE_HANDWRITING,
        SECTION_TYPE_COLUMNS,
    )

    /** 段类型判定：显式 type 声明优先，未声明按段名推断（保留名为代码布局，其余全键盘）。 */
    internal fun sectionKindOf(section: String, sectionTypes: Map<String, String>): String {
        sectionTypes[section]?.let { return it }
        return if (section in CODE_LAYOUT_SECTIONS) section else SECTION_TYPE_FULL
    }

    // 九键/笔画手势配置缓存（keyboard.t9.keys / keyboard.stroke.keys，custom 键级覆盖）。
    // 键 id 不做大小写归一：九键为数字字符串 "1"~"9"，笔画为键面标签（一/丨/丿/丶/乛 等）。
    // 以下单例为「当前激活段」的数据：type 声明的多个代码布局段各存一份（下方 BySection 缓存），
    // 方案切换时由 [setActiveKeyboardSchema] 换入。
    private var _t9GestureConfigs: Map<String, KeyBinding> = emptyMap()
    private var _strokeGestureConfigs: Map<String, KeyBinding> = emptyMap()
    private var _t9LayoutsBySection: Map<String, KeyboardT9Config> = emptyMap()
    private var _t9GestureConfigsBySection: Map<String, Map<String, KeyBinding>> = emptyMap()
    private var _strokeLayoutsBySection: Map<String, KeyboardStrokeConfig> = emptyMap()
    private var _strokeGestureConfigsBySection: Map<String, Map<String, KeyBinding>> = emptyMap()
    private var _activeT9Section: String? = null
    private var _activeStrokeSection: String? = null

    /** 全键盘方案（含合并键布局）对应的 xime.yaml 键盘 section，代码布局方案返回 null。 */
    internal fun mergedSectionForSchema(schemaId: String): String? =
        resolveMergedSection(schemaId, _schemaSectionBindings, _sectionTypes)

    /**
     * 绑定解析：仅认 schemas 声明（xime.yaml / xime.custom.yaml keyboard.<section>.schemas），
     * 未声明的方案一律全键盘（26 键），不做 id 关键字猜测。
     * 代码布局 section（type 声明或段名为 t9/stroke/handwriting）不是行数据布局，不作为合并键解析结果。
     */
    internal fun resolveMergedSection(
        schemaId: String,
        bindings: Map<String, String>,
        sectionTypes: Map<String, String> = emptyMap(),
    ): String? =
        bindings[schemaId]?.takeIf { sectionKindOf(it, sectionTypes) == SECTION_TYPE_FULL }

    /**
     * 按键布局解析：活动合并键段配置了 button_layout 时优先，未配置回退 qwerty/qwerty_en 全局值。
     * 英文态恒用英文全局值（合并键段与行布局同语义：仅中文态生效）。
     */
    internal fun resolveButtonLayout(
        isAsciiMode: Boolean,
        activeSection: String?,
        sectionLayouts: Map<String, ButtonLayout>,
        zhLayout: ButtonLayout,
        enLayout: ButtonLayout,
    ): ButtonLayout {
        if (!isAsciiMode && activeSection != null) {
            sectionLayouts[activeSection]?.let { return it }
        }
        return if (isAsciiMode) enLayout else zhLayout
    }

    /** 查询方案声明的键盘 section（任意类型，含代码布局），未声明返回 null。 */
    fun boundSectionForSchema(schemaId: String): String? = _schemaSectionBindings[schemaId]

    /** 查询方案绑定段的类型（full/t9/stroke/handwriting，显式 type 优先于段名推断），未声明返回 null。 */
    fun schemaSectionKind(schemaId: String): String? =
        _schemaSectionBindings[schemaId]?.let { sectionKindOf(it, _sectionTypes) }

    /** 查询方案绑定的代码布局 section（t9 九键 / stroke 笔画 / handwriting 手写），未绑定返回 null。 */
    fun codeLayoutForSchema(schemaId: String): String? =
        _schemaSectionBindings[schemaId]?.takeIf { sectionKindOf(it, _sectionTypes) in CODE_LAYOUT_SECTIONS }

    /**
     * 按当前方案切换键盘缓存（合并键布局 ↔ 标准 26 键 ↔ 代码布局按段切换）。
     * 在 KeyboardViewModel.dispatch/resetKeyboard 收到 schemaId 时调用；
     * 英文键盘不受影响（合并键布局仅中文拼音方案使用）。
     */
    fun setActiveKeyboardSchema(schemaId: String) {
        _activeSchemaId = schemaId
        val bound = _schemaSectionBindings[schemaId]
        val kind = bound?.let { sectionKindOf(it, _sectionTypes) }
        val merged = bound?.takeIf { kind == SECTION_TYPE_FULL }
        val t9 = bound?.takeIf { kind == SECTION_TYPE_T9 }
        val stroke = bound?.takeIf { kind == SECTION_TYPE_STROKE }
        if (merged == _activeMergedSection && t9 == _activeT9Section && stroke == _activeStrokeSection) return
        _activeMergedSection = merged
        if (merged != null) {
            _zhLayout.value = _mergedLayouts[merged]
                ?: KeyboardRowsLayout(DEFAULT_ZH_ROWS.map { KeyboardRow(it) })
            _keyGestureConfig.value = _mergedGestureConfigs[merged] ?: emptyMap()
        } else {
            _zhLayout.value = _zhLayoutBase
            _keyGestureConfig.value = _keyGestureConfigZhBase
        }
        // 代码布局按段切换：type 声明的任意段名可并存多份九键/笔画配置，激活哪个段就换入哪份
        t9?.let { s ->
            _t9LayoutsBySection[s]?.let { keyboardT9Config = it }
            _t9GestureConfigsBySection[s]?.let { _t9GestureConfigs = it }
            _activeT9Section = s
        }
        stroke?.let { s ->
            _strokeLayoutsBySection[s]?.let { keyboardStrokeConfig = it }
            _strokeGestureConfigsBySection[s]?.let { _strokeGestureConfigs = it }
            _activeStrokeSection = s
        }
    }

    /**
     * 获取键盘行布局：行声明（含行级 align/width）+ 参考列数 + 键盘级对齐策略。
     * 每个 [KeyboardRow] 的 keys 为一行的按键 ID 列表，索引 0 = 第一行；
     * 合并键布局中合并键的 ID 为组内字母拼接（如 "qw"），渲染层按 ID 长度分配键宽。
     * 读取的是 Compose 状态：配置就绪后调用方所在组合作用域会自动重组。
     */
    fun getRowsLayout(isAsciiMode: Boolean): KeyboardRowsLayout =
        if (isAsciiMode) _enLayout.value else _zhLayout.value
    
    /** 配置版本号，每次 loadConfig 时递增，用于 Compose 感知配置变更。 */
    private val _configVersion = MutableStateFlow(0)
    val configVersion: StateFlow<Int> = _configVersion.asStateFlow()
    
    private var mergedConfigCache: XimeConfig? = null
    private var mergedConfigVersion = 0
    
    fun loadConfig(context: Context): KeysConfig {
        loadXimeConfig(context)
        config = config.copy(
            swipeUp = getDefaultSwipeUp(),
            swipeDownEnglish = getDefaultSwipeDownEnglish()
        )
        return config
    }
    
    // 进程内配置缓存戳（xime.custom.yaml 的 mtime+size）：全量 YAML 解析真机实测可阻塞
    // 主线程 ~1.8s，而输入法服务重建时配置通常未变。内置 xime.yaml 随 APK 更新（进程重启）
    // 天然失效；外部改动 custom 文件经 mtime 感知穿透；解析失败不缓存下次重试。
    private var _loadedCustomStamp: Pair<Long, Long>? = null

    // 顶层 <section>.schemas 绑定及缓存戳（correction / fuzzy_pinyin，独立于全量解析——
    // 这些查询在按键热路径与设置页调用，只解析顶层一个列表，开销远小于 loadXimeConfig）。
    private val _topLevelSchemas = mutableMapOf<String, Pair<Pair<Long, Long>, Set<String>>>()

    /**
     * 启用邻键误触纠错的方案 id 集合（xime.yaml 顶层 correction.schemas，
     * xime.custom.yaml 同路径追加，与内置取并集）。解析失败/未配置时为空集。
     */
    fun correctionSchemas(context: Context): Set<String> =
        topLevelSchemas(context, "correction")

    /**
     * 模糊拼音作用的方案 id 集合（xime.yaml 顶层 fuzzy_pinyin.schemas）。
     *
     * 模糊规则是拼音字母层面的 fuzz，只对「全拼」有意义；而 Rime 方案没有类型字段，
     * 按名称/结构猜测会把双拼、形码误判成拼音并污染编码，因此只认显式声明。
     * 第三方全拼方案在 xime.custom.yaml 同路径追加即可。
     */
    fun fuzzyPinyinSchemas(context: Context): Set<String> =
        topLevelSchemas(context, "fuzzy_pinyin")

    private fun topLevelSchemas(context: Context, section: String): Set<String> {
        val customFile = File(context.filesDir, "rime/$XIME_CUSTOM_CONFIG_FILE")
        val stamp = if (customFile.exists()) {
            customFile.lastModified() to customFile.length()
        } else {
            0L to 0L
        }
        _topLevelSchemas[section]?.let { (cachedStamp, cached) ->
            if (cachedStamp == stamp) return cached
        }
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseTopLevelSchemasYamlText(it, section) } ?: emptySet()
        val custom = readCustomText(context)
            ?.let { parseTopLevelSchemasYamlText(it, section) } ?: emptySet()
        val merged = builtIn + custom
        _topLevelSchemas[section] = stamp to merged
        return merged
    }

    /** 从 YAML 文本提取顶层 `<section>.schemas` 列表（如 correction.schemas）。 */
    internal fun parseTopLevelSchemasYamlText(yamlText: String, section: String): Set<String> {
        return try {
            val root = cachedYamlNode(yamlText) as? YamlMap ?: return emptySet()
            val sectionNode = root.opt<YamlMap>(section) ?: return emptySet()
            val schemas = sectionNode.opt<YamlList>("schemas") ?: return emptySet()
            schemas.items.mapNotNull { (it as? YamlScalar)?.content?.trim()?.takeIf(String::isNotEmpty) }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse $section schemas", e)
            emptySet()
        }
    }

    private fun loadXimeConfig(context: Context) {
        val customFile = File(context.filesDir, "rime/$XIME_CUSTOM_CONFIG_FILE")
        val customStamp = if (customFile.exists()) {
            customFile.lastModified() to customFile.length()
        } else {
            0L to 0L
        }
        if (_loadedCustomStamp == customStamp) return
        _loadedCustomStamp = customStamp
        // 新一轮装载：丢掉上一轮的文本/解析树，确保读到的是当前资产与当前 custom 配置
        assetTextCache.clear()
        yamlNodeCache.clear()
        try {
            // 键盘手势（从原始 YAML 手动解析）
            val parsed = parseKeyboardFromAssets(context)
            _keyGestureConfigZhBase = parsed?.first ?: emptyMap()
            _keyGestureConfigEn.value = parsed?.second ?: emptyMap()
            // 键盘颜色（从原始 YAML 手动解析）
            keyboardColorsConfig = parseKeyboardColorsFromAssets(context)
            // 键盘阴影（从原始 YAML 手动解析）
            keyboardShadowConfig = parseKeyboardShadowFromAssets(context)
            // 键盘按键（从原始 YAML 手动解析）
            keyboardKeyConfig = parseKeyboardKeyFromAssets(context)
            // 字体配置（从原始 YAML 手动解析）
            keyboardFontConfig = parseKeyboardFontsFromAssets(context)
            com.kingzcheung.xime.ui.keyboard.AppFonts.loadCustomFonts(keyboardFontConfig)
            // 按键布局（从原始 YAML 手动解析，中英文分开）
            val parsedLayouts = parseButtonLayoutFromAssets(context)
            _buttonLayoutZh = parsedLayouts.first
            _buttonLayoutEn = parsedLayouts.second
            // 键盘行布局
            val parsedRows = parseKeyboardLayoutFromAssets(context)
            _zhLayoutBase = parsedRows.zhLayout
            _enLayout.value = parsedRows.enLayout
            // 合并键绑定（keyboard.<section>.schemas，custom 覆盖 builtIn 同名方案的绑定）
            // 内置/自定义两份 YAML 文本只读一次，后面几处复用（面板段装载也用同一份）
            val builtInText = readAssetText(context, XIME_CONFIG_FILE)
            val customTextForPanel = readCustomText(context)
            val builtInBindings = builtInText?.let { parseSchemaBindingsYamlText(it) } ?: emptyMap()
            val customBindings = customTextForPanel
                ?.let { parseSchemaBindingsYamlText(it) } ?: emptyMap()
            _schemaSectionBindings = builtInBindings + customBindings
            // 段类型声明（keyboard.<section>.type，custom 覆盖 builtIn）：显式声明优先于段名推断
            _sectionTypes = (builtInText?.let { parseSectionTypesYamlText(it) } ?: emptyMap()) +
                (customTextForPanel?.let { parseSectionTypesYamlText(it) } ?: emptyMap())
            // 合并键布局 sections：由 schemas 绑定动态发现（内置 qwerty_14/17/18 + custom 新增）；
            // 代码布局 section（type 声明或段名为 t9/stroke/handwriting）无行数据，走按段装载（下方）
            // （button_layout 同理：仅全键盘段支持，九键/笔画段不解析）
            val mergedLayoutsMap = mutableMapOf<String, KeyboardRowsLayout>()
            val mergedGesturesMap = mutableMapOf<String, Map<String, KeyBinding>>()
            val buttonLayoutsBySection = mutableMapOf<String, ButtonLayout>()
            for (section in _schemaSectionBindings.values
                .filter { sectionKindOf(it, _sectionTypes) == SECTION_TYPE_FULL }.distinct()) {
                parseRowsLayoutSection(context, section)?.let { mergedLayoutsMap[section] = it }
                mergedGesturesMap[section] = parseGesturesSection(context, section)
                parseButtonLayoutSection(context, section)?.let { buttonLayoutsBySection[section] = it }
            }
            _mergedLayouts = mergedLayoutsMap
            _mergedGestureConfigs = mergedGesturesMap
            _buttonLayoutBySection = buttonLayoutsBySection
            // 代码布局按段装载：绑定的代码布局段 + 内置保留名（未绑定时配置仍可用），
            // 各段独立解析；方案切换时由 setActiveKeyboardSchema 换入活动单例
            val t9LayoutsBySection = mutableMapOf<String, KeyboardT9Config>()
            val t9GestureConfigsBySection = mutableMapOf<String, Map<String, KeyBinding>>()
            val strokeLayoutsBySection = mutableMapOf<String, KeyboardStrokeConfig>()
            val strokeGestureConfigsBySection = mutableMapOf<String, Map<String, KeyBinding>>()
            for (section in (_schemaSectionBindings.values.filter {
                    sectionKindOf(it, _sectionTypes) in CODE_LAYOUT_SECTIONS } + CODE_LAYOUT_SECTIONS).distinct()) {
                when (sectionKindOf(section, _sectionTypes)) {
                    SECTION_TYPE_T9 -> {
                        t9LayoutsBySection[section] = parseKeyboardT9FromAssets(context, section)
                        t9GestureConfigsBySection[section] = parseGesturesSection(context, section)
                    }
                    SECTION_TYPE_STROKE -> {
                        strokeLayoutsBySection[section] = parseKeyboardStrokeFromAssets(context, section)
                        strokeGestureConfigsBySection[section] = parseGesturesSection(context, section)
                    }
                }
            }
            _t9LayoutsBySection = t9LayoutsBySection
            _t9GestureConfigsBySection = t9GestureConfigsBySection
            _strokeLayoutsBySection = strokeLayoutsBySection
            _strokeGestureConfigsBySection = strokeGestureConfigsBySection
            // 活动单例先落内置保留段（段缺省时保留字段默认值）
            keyboardT9Config = t9LayoutsBySection[SECTION_TYPE_T9] ?: keyboardT9Config
            _t9GestureConfigs = t9GestureConfigsBySection[SECTION_TYPE_T9] ?: emptyMap()
            keyboardStrokeConfig = strokeLayoutsBySection[SECTION_TYPE_STROKE] ?: keyboardStrokeConfig
            _strokeGestureConfigs = strokeGestureConfigsBySection[SECTION_TYPE_STROKE] ?: emptyMap()
            _activeT9Section = SECTION_TYPE_T9
            _activeStrokeSection = SECTION_TYPE_STROKE
            // 面板段（数字/常用符号）装载：布局 + 手势，支持 <section>_tablet 设备档变体
            // （default/custom 文本已在上面读过，直接复用，避免逐段重复读资产 + 重复解析）
            val panelIsTablet = isTabletDevice(context)
            val panelLayoutsBySection = mutableMapOf<String, PanelLayoutConfig>()
            val panelGesturesBySection = mutableMapOf<String, Map<String, KeyBinding>>()
            for (section in PANEL_SECTIONS) {
                val resolved = resolveQwertySection(section, builtInText ?: "", customTextForPanel, panelIsTablet)
                parsePanelLayoutSection(builtInText, customTextForPanel, section, resolved)
                    ?.let { panelLayoutsBySection[section] = it }
                panelGesturesBySection[section] =
                    parsePanelGesturesSection(builtInText, customTextForPanel, section, resolved)
            }
            _panelLayouts = panelLayoutsBySection
            _panelGestureConfigs = panelGesturesBySection
            // 基线缓存已刷新，先落标准 26 键的行布局/手势，合并键方案再由
            // setActiveKeyboardSchema 覆盖。不能只依赖 setActiveKeyboardSchema：
            // 非合并键方案 section 为 null，与刚重置的 _activeMergedSection(null) 相等
            // 会被提前 return，导致 xime.custom.yaml 的行布局/手势不生效（重新部署也无效）。
            _zhLayout.value = _zhLayoutBase
            _keyGestureConfig.value = _keyGestureConfigZhBase
            // 重新应用当前方案对应的合并键布局（上面重置了基线缓存）
            _activeMergedSection = null
            setActiveKeyboardSchema(_activeSchemaId)
            // 校验配置版本兼容性
            val merged = try { loadMergedConfig(context) } catch (_: YamlException) { null }
            val constraint = merged?.metadata?.appVersion?.takeIf { it.isNotBlank() }
            if (constraint != null && !checkVersionConstraint(BuildConfig.VERSION_NAME, constraint)) {
                Log.w(TAG, "Config requires app_version $constraint, current is ${BuildConfig.VERSION_NAME}")
            }
            _configVersion.value++
        } catch (e: Exception) {
            _loadedCustomStamp = null
            Log.w(TAG, "Failed to load xime config", e)
        }
    }

    private fun checkVersionConstraint(current: String, constraint: String): Boolean {
        val operator = when {
            constraint.startsWith(">=") -> constraint.substring(0, 2)
            constraint.startsWith("<=") -> constraint.substring(0, 2)
            constraint.startsWith(">") -> constraint.substring(0, 1)
            constraint.startsWith("<") -> constraint.substring(0, 1)
            constraint.startsWith("^") -> constraint.substring(0, 1)
            constraint.startsWith("~") -> constraint.substring(0, 1)
            else -> return true
        }
        val targetVerStr = constraint.removePrefix(operator).trim('"', '\'', ' ')
        val targetParts = targetVerStr.split('.').mapNotNull { it.toIntOrNull() }
        val currentParts = current.split('.').mapNotNull { it.toIntOrNull() }
        if (targetParts.size < 2 || currentParts.size < 2) return true
        val t = Triple(targetParts.getOrElse(0) { 0 }, targetParts.getOrElse(1) { 0 }, targetParts.getOrElse(2) { 0 })
        val c = Triple(currentParts[0], currentParts.getOrElse(1) { 0 }, currentParts.getOrElse(2) { 0 })
        val cmp = compareVersions(c, t)
        return when (operator) {
            ">=" -> cmp >= 0
            "<=" -> cmp <= 0
            ">"  -> cmp > 0
            "<"  -> cmp < 0
            "^"  -> c.first == t.first && (c.first != 0 || cmp >= 0)
            "~"  -> c.first == t.first && c.second >= t.second
            else -> true
        }
    }

    private fun compareVersions(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        return when {
            a.first != b.first -> a.first.compareTo(b.first)
            a.second != b.second -> a.second.compareTo(b.second)
            else -> a.third.compareTo(b.third)
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘手势配置。 */
    private fun parseKeyboardFromAssets(context: Context): Pair<Map<String, KeyBinding>, Map<String, KeyBinding>>? {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return null
        // 支持两种来源：files/rime/（浏览器导入）或 assets/（内置），自动 fallback
        val userData = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
        val customText = userData ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        return parseKeyboardGestureTexts(defaultText, customText, isTabletDevice(context))
    }

    /**
     * 纯文本合并解析键盘手势配置（default = xime.yaml，custom = xime.custom.yaml）。
     *
     * 预设合并为 default + custom（同名 custom 覆盖），合并结果供 custom keys 的
     * `{ use: }` 引用；keys 按键级合并（custom 同名键整体覆盖 default 键）。
     *
     * [isTablet] 时按设备档变体解析：yaml 存在 `<段>_tablet` 则整段改用平板变体
     * （见 [resolveQwertySection]）。
     */
    internal fun parseKeyboardGestureTexts(
        defaultText: String,
        customText: String?,
        isTablet: Boolean = false,
    ): Pair<Map<String, KeyBinding>, Map<String, KeyBinding>>? {
        val defaultPresets = parseKeyboardActionsYamlText(defaultText)
        val presets = defaultPresets + (customText?.let { parseKeyboardActionsYamlText(it) } ?: emptyMap())
        val zhSection = resolveQwertySection("qwerty", defaultText, customText, isTablet)
        val enSection = resolveQwertySection("qwerty_en", defaultText, customText, isTablet)
        val defaultZh = parseKeyboardYamlSection(defaultText, zhSection, defaultPresets)
            // 变体段只出现在自定义层时，default 层回退基础段
            ?: (if (zhSection != "qwerty") parseKeyboardYamlSection(defaultText, "qwerty", defaultPresets) else null)
            ?: return null
        val defaultEn = parseKeyboardYamlSection(defaultText, enSection, defaultPresets)
            ?: (if (enSection != "qwerty_en") parseKeyboardYamlSection(defaultText, "qwerty_en", defaultPresets) else emptyMap())
            ?: emptyMap()
        val customZh: Map<String, KeyBinding>? = customText?.let { parseKeyboardYamlSection(it, zhSection, presets) }
        val customEn: Map<String, KeyBinding>? = customText?.let { parseKeyboardYamlSection(it, enSection, presets) }
        val zh = if (customZh != null) mergeGestureMaps(defaultZh, customZh) else defaultZh
        val en = if (customEn != null) mergeGestureMaps(defaultEn, customEn) else defaultEn
        return Pair(zh, en)
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘颜色配置（字段级一路 fallback）。 */
    private fun parseKeyboardColorsFromAssets(context: Context): KeyboardColorsConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardColorsYamlText(it) }
        val custom = readCustomText(context)?.let { parseKeyboardColorsYamlText(it) }
        return mergeColorsConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并颜色配置：custom → builtIn → 代码默认值。 */
    internal fun mergeColorsConfigs(custom: KeyboardColorsPartial?, builtIn: KeyboardColorsPartial?): KeyboardColorsConfig =
        KeyboardColorsConfig(
            keyBgColor = tiered(custom?.keyBgColor, builtIn?.keyBgColor, 0xFFFFFF),
            keyBgColorDark = tiered(custom?.keyBgColorDark, builtIn?.keyBgColorDark, 0x4A4A4A),
            specialKeyBgColor = tiered(custom?.specialKeyBgColor, builtIn?.specialKeyBgColor, null),
            specialKeyBgColorDark = tiered(custom?.specialKeyBgColorDark, builtIn?.specialKeyBgColorDark, null),
            keyTextColor = tiered(custom?.keyTextColor, builtIn?.keyTextColor, 0x202124),
            keyTextColorDark = tiered(custom?.keyTextColorDark, builtIn?.keyTextColorDark, 0xE8EAED),
            candidateTextColor = tiered(custom?.candidateTextColor, builtIn?.candidateTextColor, 0x1A73E8),
            candidateTextColorDark = tiered(custom?.candidateTextColorDark, builtIn?.candidateTextColorDark, 0x8AB4F8),
        )

    /** 从 YAML 文本中提取 keyboard.colors 段（仅显式字段非 null）。 */
    internal fun parseKeyboardColorsYamlText(yamlText: String): KeyboardColorsPartial? {
        return try {
            val root = cachedYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val colorsNode = keyboardNode.opt<YamlMap>("colors") ?: return null
            var kBg: Long? = null
            var kBgDark: Long? = null
            var spKeyBg: Long? = null
            var spKeyBgDark: Long? = null
            var kTxt: Long? = null
            var kTxtDark: Long? = null
            var candTxt: Long? = null
            var candTxtDark: Long? = null
            for ((kNode, vNode) in colorsNode.entries) {
                val key = (kNode as? YamlScalar)?.content ?: continue
                val value = (vNode as? YamlScalar)?.content ?: continue
                val hex = value.removePrefix("0x").toLongOrNull(16) ?: continue
                when (key) {
                    "key_bg_color" -> kBg = hex
                    "key_bg_color_dark" -> kBgDark = hex
                    "special_key_bg_color" -> spKeyBg = hex
                    "special_key_bg_color_dark" -> spKeyBgDark = hex
                    "key_text_color" -> kTxt = hex
                    "key_text_color_dark" -> kTxtDark = hex
                    "candidate_text_color" -> candTxt = hex
                    "candidate_text_color_dark" -> candTxtDark = hex
                }
            }
            KeyboardColorsPartial(
                keyBgColor = kBg,
                keyBgColorDark = kBgDark,
                specialKeyBgColor = spKeyBg,
                specialKeyBgColorDark = spKeyBgDark,
                keyTextColor = kTxt,
                keyTextColorDark = kTxtDark,
                candidateTextColor = candTxt,
                candidateTextColorDark = candTxtDark,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard colors config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘阴影配置（字段级一路 fallback）。 */
    private fun parseKeyboardShadowFromAssets(context: Context): KeyboardShadowConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardShadowYamlText(it) }
        val custom = readCustomText(context)?.let { parseKeyboardShadowYamlText(it) }
        return mergeShadowConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并阴影配置：custom → builtIn → 代码默认值。 */
    internal fun mergeShadowConfigs(custom: KeyboardShadowPartial?, builtIn: KeyboardShadowPartial?): KeyboardShadowConfig =
        KeyboardShadowConfig(
            enabled = tiered(custom?.enabled, builtIn?.enabled, true),
            elevation = tiered(custom?.elevation, builtIn?.elevation, 1),
            shapeRadius = tiered(custom?.shapeRadius, builtIn?.shapeRadius, 8),
        )

    /** 从 YAML 文本中提取 keyboard.shadow 段（仅显式字段非 null）。 */
    internal fun parseKeyboardShadowYamlText(yamlText: String): KeyboardShadowPartial? {
        return try {
            val root = cachedYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val shadowNode = keyboardNode.opt<YamlMap>("shadow") ?: return null
            var enabled: Boolean? = null
            var elevation: Int? = null
            var shapeRadius: Int? = null
            for ((kNode, vNode) in shadowNode.entries) {
                val key = (kNode as? YamlScalar)?.content ?: continue
                val value = (vNode as? YamlScalar)?.content ?: continue
                when (key) {
                    "enabled" -> enabled = value.toBooleanStrictOrNull() ?: continue
                    "elevation" -> elevation = value.toIntOrNull() ?: continue
                    "shape_radius" -> shapeRadius = value.toIntOrNull() ?: continue
                }
            }
            KeyboardShadowPartial(enabled = enabled, elevation = elevation, shapeRadius = shapeRadius)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard shadow config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘按键配置（字段级一路 fallback）。 */
    private fun parseKeyboardKeyFromAssets(context: Context): KeyboardKeyConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardKeyYamlPartial(it) }
        val custom = readCustomText(context)?.let { parseKeyboardKeyYamlPartial(it) }
        val merged = mergeKeyConfigs(custom, builtIn)
        // 平板设备档：keyboard.key.<名>_tablet 覆盖同名基础覆盖（圆角 + 间距，与键盘段 <section>_tablet 同一约定）
        return if (isTabletDevice(context)) applyTabletKeyOverrides(merged) else merged
    }

    /**
     * 平板设备档覆盖：把 `keyboard.key.<名>_tablet` 的圆角/间距并到 `<名>` 上
     * （例 `number_tablet: { spacing_x: 6, spacing_y: 6, corner_radius: 8 }` 在平板上即 number 面板的值），
     * 手机不受影响。与键盘段 `<section>_tablet` 的整段替换保持同一套命名约定。
     */
    internal fun applyTabletKeyOverrides(config: KeyboardKeyConfig): KeyboardKeyConfig {
        val tabletKeys = config.spacingOverrides.keys.filter { it.endsWith(TABLET_SECTION_SUFFIX) }
        if (tabletKeys.isEmpty()) return config
        val merged = LinkedHashMap(config.spacingOverrides)
        for (name in tabletKeys) {
            merged[name.removeSuffix(TABLET_SECTION_SUFFIX)] = config.spacingOverrides.getValue(name)
        }
        return config.copy(spacingOverrides = merged)
    }

    /** 字段级一路 fallback 合并按键配置：custom → builtIn → 代码默认值。 */
    internal fun mergeKeyConfigs(custom: KeyboardKeyPartial?, builtIn: KeyboardKeyPartial?): KeyboardKeyConfig =
        KeyboardKeyConfig(
            cornerRadius = tiered(custom?.cornerRadius, builtIn?.cornerRadius, 8),
            spacingX = tiered(custom?.spacingX, builtIn?.spacingX, null),
            spacingY = tiered(custom?.spacingY, builtIn?.spacingY, null),
            spacingOverrides = mergeSpacingOverrides(custom?.spacingOverrides, builtIn?.spacingOverrides),
        )

    /** 按键盘名合并圆角/间距覆盖：同名项字段级 fallback（custom 非空字段覆盖 builtIn）。 */
    internal fun mergeSpacingOverrides(
        custom: Map<String, KeyboardSpacingConfig>?,
        builtIn: Map<String, KeyboardSpacingConfig>?,
    ): Map<String, KeyboardSpacingConfig> {
        if (custom == null) return builtIn ?: emptyMap()
        if (builtIn == null) return custom
        val result = LinkedHashMap<String, KeyboardSpacingConfig>(custom)
        for ((key, builtInValue) in builtIn) {
            val customValue = result[key]
            result[key] = if (customValue == null) {
                builtInValue
            } else {
                KeyboardSpacingConfig(
                    spacingX = customValue.spacingX ?: builtInValue.spacingX,
                    spacingY = customValue.spacingY ?: builtInValue.spacingY,
                    cornerRadius = customValue.cornerRadius ?: builtInValue.cornerRadius,
                )
            }
        }
        return result
    }

    /**
     * 兼容测试入口：从 YAML 文本中提取 keyboard.key 段并填充代码默认值。
     * > 合并（custom → builtIn → 默认）请使用 [parseKeyboardKeyYamlPartial]。
     */
    internal fun parseKeyboardKeyYamlText(yamlText: String): KeyboardKeyConfig? {
        val partial = parseKeyboardKeyYamlPartial(yamlText) ?: return null
        return KeyboardKeyConfig(
            cornerRadius = partial.cornerRadius ?: 8,
            spacingX = partial.spacingX,
            spacingY = partial.spacingY,
            spacingOverrides = partial.spacingOverrides ?: emptyMap(),
        )
    }

    /** 从 YAML 文本中提取 keyboard.key 段（仅显式字段非 null）。 */
    private fun parseKeyboardKeyYamlPartial(yamlText: String): KeyboardKeyPartial? {
        return try {
            val root = cachedYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            var cornerRadius: Int? = null
            var spacingX: Float? = null
            var spacingY: Float? = null
            val spacingOverrides = mutableMapOf<String, KeyboardSpacingConfig>()
            // 优先读取 key.corner_radius
            val keyNode = keyboardNode.opt<YamlMap>("key")
            if (keyNode != null) {
                for ((kNode, vNode) in keyNode.entries) {
                    val key = (kNode as? YamlScalar)?.content ?: continue
                    if (key == "corner_radius") {
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        cornerRadius = value.toIntOrNull()
                    } else if (key == "spacing_x") {
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        spacingX = value.toFloatOrNull()
                    } else if (key == "spacing_y") {
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        spacingY = value.toFloatOrNull()
                    } else if (vNode is YamlMap) {
                        // 键盘级覆盖：keyboard.key.<键盘名>.corner_radius/spacing_x/spacing_y
                        var overrideX: Float? = null
                        var overrideY: Float? = null
                        var overrideRadius: Int? = null
                        for ((skNode, svNode) in vNode.entries) {
                            val skey = (skNode as? YamlScalar)?.content ?: continue
                            val svalue = (svNode as? YamlScalar)?.content ?: continue
                            if (skey == "spacing_x") {
                                overrideX = svalue.toFloatOrNull()
                            } else if (skey == "spacing_y") {
                                overrideY = svalue.toFloatOrNull()
                            } else if (skey == "corner_radius") {
                                overrideRadius = svalue.toIntOrNull()
                            }
                        }
                        spacingOverrides[key] = KeyboardSpacingConfig(overrideX, overrideY, overrideRadius)
                    }
                }
            }
            // 未设置 key.corner_radius 时，回退读取 shadow.shape_radius
            if (cornerRadius == null) {
                val shadowNode = keyboardNode.opt<YamlMap>("shadow")
                if (shadowNode != null) {
                    for ((kNode, vNode) in shadowNode.entries) {
                        val key = (kNode as? YamlScalar)?.content ?: continue
                        val value = (vNode as? YamlScalar)?.content ?: continue
                        if (key == "shape_radius") {
                            cornerRadius = value.toIntOrNull()
                        }
                    }
                }
            }
            KeyboardKeyPartial(
                cornerRadius = cornerRadius,
                spacingX = spacingX,
                spacingY = spacingY,
                spacingOverrides = spacingOverrides.ifEmpty { null },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard key config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析九键键盘配置。 */
    private fun parseKeyboardT9FromAssets(context: Context, section: String): KeyboardT9Config {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardT9YamlPartial(it, section) }
        val custom = readCustomText(context)?.let { parseKeyboardT9YamlPartial(it, section) }
        return mergeT9Configs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并九键配置：custom → builtIn → 代码默认值。 */
    internal fun mergeT9Configs(custom: KeyboardT9Partial?, builtIn: KeyboardT9Partial?): KeyboardT9Config =
        KeyboardT9Config(
            sideSymbols = tiered(custom?.sideSymbols?.takeIf { it.isNotEmpty() },
                builtIn?.sideSymbols?.takeIf { it.isNotEmpty() }, DEFAULT_T9_SIDE_SYMBOLS),
            layout = mergeT9Layouts(custom?.layout, builtIn?.layout),
        )

    /** 字段级一路 fallback 合并九键布局：custom → builtIn → 代码默认值（列表整段覆盖）。 */
    internal fun mergeT9Layouts(custom: KeyboardT9LayoutPartial?, builtIn: KeyboardT9LayoutPartial?): KeyboardT9LayoutConfig =
        KeyboardT9LayoutConfig(
            left = tiered(custom?.left?.takeIf { it.isNotEmpty() },
                builtIn?.left?.takeIf { it.isNotEmpty() }, KeyboardT9LayoutConfig.DEFAULT_T9_LEFT),
            rows = tiered(custom?.rows?.takeIf { it.isNotEmpty() },
                builtIn?.rows?.takeIf { it.isNotEmpty() }, KeyboardT9LayoutConfig.DEFAULT_T9_ROWS),
            right = tiered(custom?.right?.takeIf { it.isNotEmpty() },
                builtIn?.right?.takeIf { it.isNotEmpty() }, KeyboardT9LayoutConfig.DEFAULT_T9_RIGHT),
        )

    /** 从 YAML 文本中提取 keyboard.<section> 九键段（仅显式字段非 null；缺省内置保留名 t9）。 */
    internal fun parseKeyboardT9YamlPartial(yamlText: String, section: String = SECTION_TYPE_T9): KeyboardT9Partial? {
        return try {
            val root = cachedYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val t9Node = keyboardNode.opt<YamlMap>(section) ?: return null
            val sideSymbols = t9Node.opt<YamlList>("side_symbols")
                ?.items?.mapNotNull { (it as? YamlScalar)?.content }
            val layout = t9Node.opt<YamlMap>("layout")?.let { parseT9LayoutNode(it) }
            KeyboardT9Partial(
                sideSymbols = sideSymbols?.ifEmpty { null },
                layout = layout,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard t9 config", e)
            null
        }
    }

    /** 解析 keyboard.t9.layout 节点（left/rows/right；rows 支持合并键嵌套与标量行兜底）。 */
    private fun parseT9LayoutNode(layoutNode: YamlMap): KeyboardT9LayoutPartial? {
        val left = layoutNode.opt<YamlList>("left")
            ?.items?.mapNotNull { (it as? YamlScalar)?.content }?.filter { it.isNotBlank() }
        val right = layoutNode.opt<YamlList>("right")
            ?.items?.mapNotNull { (it as? YamlScalar)?.content }?.filter { it.isNotBlank() }
        val rows = layoutNode.opt<YamlList>("rows")?.let { parseRowsNode(it).map { row -> row.keys } }
        if (left.isNullOrEmpty() && right.isNullOrEmpty() && rows.isNullOrEmpty()) return null
        return KeyboardT9LayoutPartial(
            left = left?.takeIf { it.isNotEmpty() },
            rows = rows?.takeIf { it.isNotEmpty() },
            right = right?.takeIf { it.isNotEmpty() },
        )
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析笔画键盘配置。 */
    private fun parseKeyboardStrokeFromAssets(context: Context, section: String): KeyboardStrokeConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardStrokeYamlPartial(it, section) }
        val custom = readCustomText(context)?.let { parseKeyboardStrokeYamlPartial(it, section) }
        return mergeStrokeConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并笔画配置：custom → builtIn → 代码默认值。 */
    internal fun mergeStrokeConfigs(custom: KeyboardStrokePartial?, builtIn: KeyboardStrokePartial?): KeyboardStrokeConfig =
        KeyboardStrokeConfig(
            sideSymbols = tiered(custom?.sideSymbols?.takeIf { it.isNotEmpty() },
                builtIn?.sideSymbols?.takeIf { it.isNotEmpty() }, DEFAULT_STROKE_SIDE_SYMBOLS),
        )

    /** 从 YAML 文本中提取 keyboard.<section> 笔画段（仅显式字段非 null；缺省内置保留名 stroke）。 */
    internal fun parseKeyboardStrokeYamlPartial(yamlText: String, section: String = SECTION_TYPE_STROKE): KeyboardStrokePartial? {
        return try {
            val root = cachedYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val strokeNode = keyboardNode.opt<YamlMap>(section) ?: return null
            val sideSymbols = strokeNode.opt<YamlList>("side_symbols")
                ?.items?.mapNotNull { (it as? YamlScalar)?.content }
            KeyboardStrokePartial(sideSymbols = sideSymbols?.ifEmpty { null })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard stroke config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析字体配置（字段级一路 fallback）。 */
    private fun parseKeyboardFontsFromAssets(context: Context): KeyboardFontConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardFontsYamlText(it) }
        val custom = readCustomText(context)?.let { parseKeyboardFontsYamlText(it) }
        return mergeFontConfigs(custom, builtIn)
    }

    /**
     * 字段级一路 fallback 合并字体配置：custom → builtIn → 代码默认值。
     *
     * 与其它段不同，字体字段的**空串是有意义的显式取值**（= 改用系统默认字体），
     * 不能被「空白视为未配置」的规则折叠掉，否则内置字体无法在 custom 里清空。
     * builtIn 层的空白仍视为脏数据丢弃，避免内置配置写脏后锁死该字段。
     */
    internal fun mergeFontConfigs(custom: KeyboardFontPartial?, builtIn: KeyboardFontPartial?): KeyboardFontConfig {
        fun tier(c: String?, b: String?): String = c ?: b?.takeIf { it.isNotBlank() } ?: ""
        return KeyboardFontConfig(
            keyFont = tier(custom?.keyFont, builtIn?.keyFont),
            keyLabelFont = tier(custom?.keyLabelFont, builtIn?.keyLabelFont),
            candidateFont = tier(custom?.candidateFont, builtIn?.candidateFont),
            commentFont = tier(custom?.commentFont, builtIn?.commentFont),
        )
    }

    /** 从 YAML 文本中提取 keyboard.fonts 字体配置段（仅显式字段非 null）。 */
    internal fun parseKeyboardFontsYamlText(yamlText: String): KeyboardFontPartial? {
        return try {
            val root = cachedYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val fontsNode = keyboardNode.opt<YamlMap>("fonts") ?: return null
            var keyFont: String? = null
            var keyLabelFont: String? = null
            var candidateFont: String? = null
            var commentFont: String? = null
            for ((kNode, vNode) in fontsNode.entries) {
                val key = (kNode as? YamlScalar)?.content ?: continue
                val value = (vNode as? YamlScalar)?.content ?: continue
                when (key) {
                    "key_font" -> keyFont = value
                    "key_label_font" -> keyLabelFont = value
                    "candidate_font" -> candidateFont = value
                    "comment_font" -> commentFont = value
                }
            }
            KeyboardFontPartial(
                keyFont = keyFont,
                keyLabelFont = keyLabelFont,
                candidateFont = candidateFont,
                commentFont = commentFont,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard fonts config", e)
            null
        }
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析按键布局模式（中英文分开）。 */
    private fun parseButtonLayoutFromAssets(context: Context): Pair<ButtonLayout, ButtonLayout> {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return Pair(ButtonLayout.STANDARD, ButtonLayout.STANDARD)
        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        return parseButtonLayoutTexts(defaultText, customText, isTabletDevice(context))
    }

    /** 纯文本合并解析按键布局：custom 覆盖 default（单标量叶级，非法值视为未配置），中英文独立，缺省回退 STANDARD。 */
    internal fun parseButtonLayoutTexts(
        defaultText: String,
        customText: String?,
        isTablet: Boolean = false,
    ): Pair<ButtonLayout, ButtonLayout> {
        val zhSection = resolveQwertySection("qwerty", defaultText, customText, isTablet)
        val enSection = resolveQwertySection("qwerty_en", defaultText, customText, isTablet)
        val defaultZh = parseButtonLayoutYamlText(defaultText, zhSection)
        val defaultEn = parseButtonLayoutYamlText(defaultText, enSection)
        val customZh = customText?.let { parseButtonLayoutYamlText(it, zhSection) }
        val customEn = customText?.let { parseButtonLayoutYamlText(it, enSection) }
        val result = Pair(
            customZh ?: defaultZh ?: ButtonLayout.STANDARD,
            customEn ?: defaultEn ?: ButtonLayout.STANDARD,
        )
        return result
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析中英文键盘行布局（rows + columns + row_align）。 */
    private fun parseKeyboardLayoutFromAssets(context: Context): QwertyRowsConfig {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE)
            ?: return QwertyRowsConfig(defaultRowsLayout(DEFAULT_ZH_ROWS), defaultRowsLayout(DEFAULT_EN_ROWS))

        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)

        val isTablet = isTabletDevice(context)
        val zhSection = resolveQwertySection("qwerty", defaultText, customText, isTablet)
        val enSection = resolveQwertySection("qwerty_en", defaultText, customText, isTablet)

        return QwertyRowsConfig(
            zhLayout = mergeRowsLayout(defaultText, customText, zhSection),
            enLayout = mergeRowsLayout(defaultText, customText, enSection),
        )
    }

    /**
     * 合并解析一个 section 的行布局：rows（custom 段级覆盖 built-in）、columns、row_align。
     * 三者各自独立回退，未配置时取缺省值（项目内置默认行 / 首行键数 / uniform）。
     */
    internal fun mergeRowsLayout(
        defaultText: String,
        customText: String?,
        section: String,
        defaultRows: List<List<String>> = emptyList(),
    ): KeyboardRowsLayout {
        val customLayout = customText?.let { parseKeyboardRowsYamlText(it, section) }
        val customColumns = customText?.let { parseKeyboardColumnsYamlText(it, section) }
        val customAlign = customText?.let { parseKeyboardRowAlignYamlText(it, section) }
        val builtInLayout = parseKeyboardRowsYamlText(defaultText, section)
        val builtInColumns = parseKeyboardColumnsYamlText(defaultText, section)
        val builtInAlign = parseKeyboardRowAlignYamlText(defaultText, section)
        val rows = customLayout ?: builtInLayout ?: defaultRows.map { KeyboardRow(it) }
        return KeyboardRowsLayout(
            rows = rows,
            columns = customColumns ?: builtInColumns,
            rowAlign = customAlign ?: builtInAlign ?: RowAlign.UNIFORM,
        )
    }

    /** 代码缺省行 → 行布局（列数按首行键数推导，对齐 uniform）。 */
    private fun defaultRowsLayout(rows: List<List<String>>): KeyboardRowsLayout =
        KeyboardRowsLayout(rows.map { KeyboardRow(it) })

    /** 从 xime.yaml + xime.custom.yaml 合并解析指定 section 的行布局（custom 段级覆盖 built-in）。 */
    private fun parseRowsLayoutSection(context: Context, section: String): KeyboardRowsLayout? {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE)
        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        val customLayout = customText?.let { parseKeyboardRowsYamlText(it, section) }
        val builtInLayout = defaultText?.let { parseKeyboardRowsYamlText(it, section) }
        if (customLayout == null && builtInLayout == null) return null
        return mergeRowsLayout(defaultText.orEmpty(), customText, section)
    }

    /** 解析指定合并键段（custom 覆盖 built-in）的按键布局模式；未配置返回 null（回退 qwerty 全局值）。 */
    private fun parseButtonLayoutSection(context: Context, section: String): ButtonLayout? {
        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        customText?.let { parseButtonLayoutYamlText(it, section) }?.let { return it }
        val defaultText = readAssetText(context, XIME_CONFIG_FILE)
        return defaultText?.let { parseButtonLayoutYamlText(it, section) }
    }
    private fun parseGesturesSection(context: Context, section: String): Map<String, KeyBinding> {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE)
        val defaultPresets = defaultText?.let { parseKeyboardActionsYamlText(it) } ?: emptyMap()
        val default = defaultText?.let { parseKeyboardYamlSection(it, section, defaultPresets) } ?: emptyMap()
        val userData = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
        val customText = userData ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        val customPresets = customText?.let { parseKeyboardActionsYamlText(it) } ?: emptyMap()
        val presets = defaultPresets + customPresets
        val custom = customText?.let { parseKeyboardYamlSection(it, section, presets) }
        return if (custom != null) mergeGestureMaps(default, custom) else default
    }

    /**
     * 从 YAML 文本提取合并键绑定：keyboard.<section>.schemas 列出的每个方案 id 映射到该 section。
     * 无 schemas 声明的 section 忽略；同一方案 id 在多个 section 声明时以后出现的为准。
     */
    internal fun parseSchemaBindingsYamlText(yamlText: String): Map<String, String> {
        return try {
            val root = cachedYamlNode(yamlText) as? YamlMap ?: return emptyMap()
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return emptyMap()
            val bindings = mutableMapOf<String, String>()
            for ((kNode, vNode) in keyboardNode.entries) {
                val section = (kNode as? YamlScalar)?.content ?: continue
                val schemas = (vNode as? YamlMap)?.opt<YamlList>("schemas") ?: continue
                for (item in schemas.items) {
                    val schemaId = (item as? YamlScalar)?.content?.trim().orEmpty()
                    if (schemaId.isNotEmpty()) bindings[schemaId] = section
                }
            }
            bindings
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse schema bindings", e)
            emptyMap()
        }
    }

    /**
     * 从 YAML 文本提取段类型声明：keyboard.<section>.type（full/t9/stroke/handwriting）。
     * 未知取值忽略并告警（回退按段名推断）；无 type 声明的 section 不产生条目。
     */
    internal fun parseSectionTypesYamlText(yamlText: String): Map<String, String> {
        return try {
            val root = cachedYamlNode(yamlText) as? YamlMap ?: return emptyMap()
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return emptyMap()
            val types = mutableMapOf<String, String>()
            for ((kNode, vNode) in keyboardNode.entries) {
                val section = (kNode as? YamlScalar)?.content ?: continue
                val declared = ((vNode as? YamlMap)?.opt<YamlScalar>("type")?.content ?: continue).trim()
                if (declared in SECTION_TYPES_KNOWN) {
                    types[section] = declared
                } else {
                    Log.w(TAG, "keyboard.$section.type 取值未知: \"$declared\"，已回退按段名推断")
                }
            }
            types
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse section types", e)
            emptyMap()
        }
    }

    /**
     * 从 YAML 文本中提取 keyboard.<section>.layout 面板布局：
     * layout 有 left/right 节点 → columns 模式（数字键盘三列，列项支持键柱子数组）；
     * 否则 rows → rows 模式（常用符号，行列表）。
     */
    internal fun parsePanelLayoutYamlText(yamlText: String, section: String): PanelLayoutConfig? =
        parsePanelLayoutPartialYamlText(yamlText, section)?.let { panelPartialToConfig(it) }

    /**
     * 解析 keyboard.<section>.layout 的**未合并**字段：null 字段 = 该层没写这个键。
     * 面板段要按字段合并（只写 right 时不能丢掉内置的 rows/left），所以需要保留「没写」与「写了空」的区别。
     */
    internal fun parsePanelLayoutPartialYamlText(yamlText: String, section: String): PanelLayoutPartial? {
        return try {
            val root = cachedYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val sectionNode = keyboardNode.opt<YamlMap>(section) ?: return null
            val layoutNode = sectionNode.opt<YamlMap>("layout") ?: return null
            PanelLayoutPartial(
                rows = layoutNode.opt<YamlList>("rows")?.let { parseRowsNode(it).map { row -> row.keys } },
                left = layoutNode.opt<YamlList>("left")?.let { parsePanelColumnItems(it) },
                right = layoutNode.opt<YamlList>("right")?.let { parsePanelColumnItems(it) },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse panel layout config", e)
            null
        }
    }

    /** partial → 配置形态：columns 模式由 left/right 是否出现决定，缺省字段落为空列表。 */
    private fun panelPartialToConfig(partial: PanelLayoutPartial): PanelLayoutConfig = PanelLayoutConfig(
        columns = partial.left != null || partial.right != null,
        rows = partial.rows ?: emptyList(),
        left = partial.left ?: emptyList(),
        right = partial.right ?: emptyList(),
    )

    /** 面板 layout 逐字段合并：custom 显式写出的字段覆盖 builtIn，其余保留内置。 */
    internal fun mergePanelLayouts(
        custom: PanelLayoutPartial?,
        builtIn: PanelLayoutPartial?,
    ): PanelLayoutConfig? {
        if (custom == null && builtIn == null) return null
        return panelPartialToConfig(
            PanelLayoutPartial(
                rows = custom?.rows ?: builtIn?.rows,
                left = custom?.left ?: builtIn?.left,
                right = custom?.right ?: builtIn?.right,
            )
        )
    }

    /** 列节点 → 列项列表：子数组 = 纵向键柱（如加减乘除四键一柱），标量 = 单键。 */
    private fun parsePanelColumnItems(node: YamlList): List<List<String>> =
        node.items.mapNotNull { item ->
            when (item) {
                is YamlList -> item.items.mapNotNull { (it as? YamlScalar)?.content?.takeIf { s -> s.isNotBlank() } }
                    .takeIf { it.isNotEmpty() }
                is YamlScalar -> item.content.takeIf { it.isNotBlank() }?.let { listOf(it) }
                else -> null
            }
        }

    /**
     * 合并解析面板段布局：逐字段 custom → builtIn（同为 resolved 段）。
     * 平板变体段（`<section>_tablet`）整段缺失时才回退基础段——变体段是另一种键面模式
     * （如 number 为三列、number_tablet 为逐行），跨段按字段混拼会拼出畸形键盘。
     */
    private fun parsePanelLayoutSection(
        defaultText: String?,
        customText: String?,
        baseSection: String,
        resolvedSection: String,
    ): PanelLayoutConfig? {
        fun builtInOf(section: String): PanelLayoutPartial? =
            defaultText?.let { parsePanelLayoutPartialYamlText(it, section) }
        val builtIn = builtInOf(resolvedSection)
            ?: if (resolvedSection != baseSection) builtInOf(baseSection) else null
        val custom = customText?.let { parsePanelLayoutPartialYamlText(it, resolvedSection) }
        return mergePanelLayouts(custom, builtIn)
    }

    /** 合并解析面板段手势（default + custom 键级覆盖；平板变体段缺失时回退基础段）。 */
    private fun parsePanelGesturesSection(
        defaultText: String?,
        customText: String?,
        baseSection: String,
        resolvedSection: String,
    ): Map<String, KeyBinding> {
        val defaultPresets = defaultText?.let { parseKeyboardActionsYamlText(it) } ?: emptyMap()
        val customPresets = customText?.let { parseKeyboardActionsYamlText(it) } ?: emptyMap()
        val presets = defaultPresets + customPresets
        fun parse(text: String?, section: String): Map<String, KeyBinding>? =
            text?.let { parseKeyboardYamlSection(it, section, presets) }
        val custom = customText?.let { parse(it, resolvedSection) }
        if (custom != null) {
            val default = parse(defaultText, resolvedSection)
                ?: parse(defaultText, baseSection)
                ?: emptyMap()
            return mergeGestureMaps(default, custom)
        }
        return parse(defaultText, resolvedSection)
            ?: parse(defaultText, baseSection)
            ?: emptyMap()
    }

    /**
     * 从 YAML 文本中提取 keyboard.<section>.layout.rows（只取按键 ID，忽略行级 align/width）。
     * 行元素支持嵌套子数组表示合并键：[[q, w], [e, r], t] → ["qw", "er", "t"]，
     * 合并键 ID 为组内字母拼接，渲染层按 ID 长度分配键宽。
     */
    internal fun parseKeyboardLayoutYamlText(yamlText: String, section: String): List<List<String>>? =
        parseKeyboardRowsYamlText(yamlText, section)?.map { it.keys }

    /**
     * 从 YAML 文本中提取 keyboard.<section>.layout.rows（带行级 align/width）。
     * 行支持数组/标量简写，或对象形式 `{ keys: [...], align: uniform|stretch, width: 0..1 }`。
     */
    internal fun parseKeyboardRowsYamlText(yamlText: String, section: String): List<KeyboardRow>? {
        return try {
            val layoutNode = layoutNodeOf(yamlText, section) ?: return null
            val rowsNode = layoutNode.opt<YamlList>("rows") ?: return null
            parseRowsNode(rowsNode).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard layout config", e)
            null
        }
    }

    /** 从 YAML 文本中提取 keyboard.<section>.layout.columns（参考列数）；缺省返回 null（取首行键数）。 */
    internal fun parseKeyboardColumnsYamlText(yamlText: String, section: String): Int? {
        return try {
            layoutNodeOf(yamlText, section)
                ?.opt<YamlScalar>("columns")?.content?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard columns config", e)
            null
        }
    }

    /** 从 YAML 文本中提取 keyboard.<section>.layout.row_align；缺省/非法返回 null（按 uniform 处理）。 */
    internal fun parseKeyboardRowAlignYamlText(yamlText: String, section: String): RowAlign? {
        return try {
            layoutNodeOf(yamlText, section)
                ?.opt<YamlScalar>("row_align")?.content?.let { RowAlign.fromValue(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard row_align config", e)
            null
        }
    }

    /** 定位 keyboard.<section>.layout 节点；任一层缺失返回 null。 */
    private fun layoutNodeOf(yamlText: String, section: String): YamlMap? {
        val root = cachedYamlNode(yamlText) as? YamlMap ?: return null
        val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
        val sectionNode = keyboardNode.opt<YamlMap>(section) ?: return null
        return sectionNode.opt<YamlMap>("layout")
    }

    /** rows 节点 → 行声明列表：数组/标量为简写行，映射为带 align/width 的完整行。 */
    private fun parseRowsNode(rowsNode: YamlList): List<KeyboardRow> {
        val rows = mutableListOf<KeyboardRow>()
        for (rowNode in rowsNode.items) {
            val row = when (rowNode) {
                is YamlList -> KeyboardRow(rowListToKeyIds(rowNode))
                is YamlScalar -> KeyboardRow(scalarRowToKeyIds(rowNode.content))
                is YamlMap -> parseRowMap(rowNode)
                else -> null
            }
            if (row != null && row.keys.isNotEmpty()) rows.add(row)
        }
        return rows
    }

    /** 对象行 → 行声明：keys（数组或标量行）+ 可选 align / width（非法值静默忽略）。 */
    private fun parseRowMap(rowNode: YamlMap): KeyboardRow {
        val keysNode = childNode(rowNode, "keys")
        val keys = when (keysNode) {
            is YamlList -> rowListToKeyIds(keysNode)
            is YamlScalar -> scalarRowToKeyIds(keysNode.content)
            else -> emptyList()
        }
        val align = childNode(rowNode, "align")
            ?.let { (it as? YamlScalar)?.content }
            ?.let { RowAlign.fromValue(it) }
        val width = childNode(rowNode, "width")
            ?.let { (it as? YamlScalar)?.content?.trim()?.toFloatOrNull() }
            ?.coerceIn(0f, 1f)
        return KeyboardRow(keys, align, width)
    }

    /** 取映射节点下指定名字的子节点。 */
    private fun childNode(map: YamlMap, name: String): YamlNode? {
        for ((key, value) in map.entries) {
            if (key.content == name) return value
        }
        return null
    }

    /** 行节点 → 按键 ID 列表：子数组为合并键（组内字母拼接为 ID），标量为单字母键。 */
    private fun rowListToKeyIds(rowList: YamlList): List<String> =
        rowList.items.mapNotNull { item ->
            when (item) {
                is YamlScalar -> item.content.takeIf { it.isNotBlank() }
                is YamlList -> item.items
                    .mapNotNull { (it as? YamlScalar)?.content }
                    .filter { it.isNotBlank() }
                    .joinToString("")
                    .takeIf { it.isNotEmpty() }
                else -> null
            }
        }

    /**
     * 标量行兜底：YAML 中以普通字母开头的行（如 "- z, [x, c], v"）不会成为 flow 序列，
     * 而是整行解析为单个标量。此处手动拆分：逗号为键分隔，方括号内为合并键组。
     */
    private fun scalarRowToKeyIds(content: String): List<String> {
        val text = content.trim()
        if (text.isEmpty()) return emptyList()
        if (!text.contains(',') && !text.contains('[')) return listOf(text)
        val items = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        fun flushItem() {
            val t = sb.toString().trim()
            if (t.isNotEmpty()) items.add(t)
            sb.clear()
        }
        for (ch in text) {
            when {
                ch == '[' -> { depth++; if (depth == 1) sb.clear() else sb.append(ch) }
                ch == ']' -> {
                    depth--
                    if (depth == 0) {
                        flushItem()
                    } else if (depth < 0) {
                        depth = 0
                    } else {
                        sb.append(ch)
                    }
                }
                ch == ',' && depth == 0 -> flushItem()
                ch == ',' && depth > 0 -> {} // 组内逗号仅分隔字母，拼接时丢弃
                ch == ' ' && depth > 0 -> {} // 组内空格一并丢弃（[x, c] → xc）
                else -> sb.append(ch)
            }
        }
        flushItem()
        return items
    }

    /** 从 YAML 文本中提取 keyboard.<section>.button_layout（section 名任意，含合并键段）。 */
    internal fun parseButtonLayoutYamlText(yamlText: String, section: String): ButtonLayout? {
        return try {
            val root = cachedYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val sectionNode = keyboardNode.opt<YamlMap>(section) ?: return null
            val layoutNode = sectionNode.opt<YamlScalar>("button_layout") ?: return null
            ButtonLayout.fromValueOrNull(layoutNode.content)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse button layout config", e)
            null
        }
    }

    /** 解析 `keyboard.actions` 可复用动作预设。 */
    internal fun parseKeyboardActionsYamlText(yamlText: String): Map<String, KeyAction> {
        return try {
            val root = cachedYamlNode(yamlText) as? YamlMap ?: return emptyMap()
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return emptyMap()
            val actionsNode = keyboardNode.opt<YamlMap>("actions") ?: return emptyMap()
            val result = mutableMapOf<String, KeyAction>()
            for ((kNode, vNode) in actionsNode.entries) {
                val name = (kNode as? YamlScalar)?.content ?: continue
                result[name] = parseKeyAction(vNode, GestureAction.COMMIT, emptyMap())
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard actions presets", e)
            emptyMap()
        }
    }

    /** 从 YAML 文本中提取 keyboard.<section>.keys 段。 */
    internal fun parseKeyboardYamlSection(
        yamlText: String,
        section: String,
        presets: Map<String, KeyAction> = emptyMap(),
    ): Map<String, KeyBinding>? {
        return try {
            val root = cachedYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val sectionNode = keyboardNode.opt<YamlMap>(section) ?: return null
            val keysNode = sectionNode.opt<YamlMap>("keys") ?: return null
            val result = mutableMapOf<String, KeyBinding>()
            for ((kNode, vNode) in keysNode.entries) {
                val key = (kNode as? YamlScalar)?.content ?: continue
                val gestureMap = vNode as? YamlMap ?: continue
                result[key] = parseKeyBinding(gestureMap, presets)
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard gesture section", e)
            null
        }
    }

    private fun loadMergedConfig(context: Context): XimeConfig {
        val currentVersion = _configVersion.value
        if (mergedConfigCache != null && mergedConfigVersion == currentVersion) {
            return mergedConfigCache!!
        }
        val default = parseConfig(readAssetText(context, XIME_CONFIG_FILE))
        val custom = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?.let { parseConfig(it) }
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
                ?.let { parseConfig(it) }
        val config = mergeConfig(default, custom)
        mergedConfigCache = config
        mergedConfigVersion = currentVersion
        return config
    }

    private fun parseConfig(content: String?): XimeConfig? {
        if (content == null) return null
        return try {
            yaml.decodeFromString(XimeConfig.serializer(), content)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse xime config", e)
            null
        }
    }

    private fun mergeConfig(default: XimeConfig?, custom: XimeConfig?): XimeConfig {
        if (custom == null) return default ?: XimeConfig()
        if (default == null) return custom
        return XimeConfig(
            ximeIndex = mergeXimeIndex(default.ximeIndex, custom.ximeIndex),
            // 合并而非替换：custom 覆盖同名字题，其余保留内置主题，
            // 这样用户只需在 xime.custom.yaml 中添加自定义背景主题而不会丢失内置主题。
            colorSchemes = mergeColorSchemes(default.colorSchemes, custom.colorSchemes),
            style = mergeStyle(default.style, custom.style),
            metadata = mergeMetadata(default.metadata, custom.metadata),
        )
    }

    /** xime_index 逐字段合并：custom 只覆盖显式写出的 base_urls（列表整段替换，`[]` 视为未配置）。 */
    private fun mergeXimeIndex(default: XimeIndexConfig?, custom: XimeIndexConfig?): XimeIndexConfig? {
        if (custom == null) return default
        if (default == null) return custom
        return XimeIndexConfig(baseUrls = custom.baseUrls?.takeIf { it.isNotEmpty() } ?: default.baseUrls)
    }

    /** metadata 逐字段合并：custom 只覆盖显式写出的字段。 */
    private fun mergeMetadata(default: MetadataConfig?, custom: MetadataConfig?): MetadataConfig? {
        if (custom == null) return default
        if (default == null) return custom
        return MetadataConfig(
            appName = custom.appName ?: default.appName,
            appVersion = custom.appVersion ?: default.appVersion,
            platform = custom.platform ?: default.platform,
            configVersion = custom.configVersion ?: default.configVersion,
            generator = custom.generator ?: default.generator,
            modifiedTime = custom.modifiedTime ?: default.modifiedTime,
        )
    }

    /** style 字段级深合并：custom 只覆盖显式配置的字段。 */
    private fun mergeStyle(default: StyleConfig?, custom: StyleConfig?): StyleConfig? {
        if (custom == null) return default
        if (default == null) return custom
        return StyleConfig(
            colorScheme = mergeColorSchemeMode(default.colorScheme, custom.colorScheme),
            darkMode = custom.darkMode ?: default.darkMode,
        )
    }

    /** color_scheme 的 light/dark 字段级合并：custom 只覆盖显式配置的模式。 */
    private fun mergeColorSchemeMode(
        default: ColorSchemeModeConfig?,
        custom: ColorSchemeModeConfig?,
    ): ColorSchemeModeConfig? {
        if (custom == null) return default
        if (default == null) return custom
        return ColorSchemeModeConfig(
            light = custom.light ?: default.light,
            dark = custom.dark ?: default.dark,
        )
    }

    /** 仅供单元测试验证 style 合并逻辑。 */
    internal fun mergeStyleForTest(default: StyleConfig?, custom: StyleConfig?): StyleConfig? =
        mergeStyle(default, custom)

    /** 仅供单元测试验证整份配置（xime_index / metadata）合并逻辑。 */
    internal fun mergeConfigForTest(default: XimeConfig?, custom: XimeConfig?): XimeConfig =
        mergeConfig(default, custom)

    /** 仅供单元测试验证 color_schemes 合并逻辑。 */
    internal fun mergeColorSchemesForTest(
        default: Map<String, ColorSchemeEntry>?,
        custom: Map<String, ColorSchemeEntry>?,
    ): Map<String, ColorSchemeEntry>? = mergeColorSchemes(default, custom)

    /** 仅供单元测试设置手势配置缓存（免加载真实 assets）。 */
    internal fun setKeyGestureConfigForTest(
        config: Map<String, KeyBinding>,
        isAsciiMode: Boolean = false,
    ) {
        if (isAsciiMode) _keyGestureConfigEn.value = config else _keyGestureConfig.value = config
    }

    private fun mergeColorSchemes(
        default: Map<String, ColorSchemeEntry>?,
        custom: Map<String, ColorSchemeEntry>?,
    ): Map<String, ColorSchemeEntry>? {
        if (custom == null) return default
        if (default == null) return custom
        // 字段级深合并：custom 只覆盖显式配置的字段，同名主题其余字段保留内置值。
        // 例如 custom 只写 primary_color 时，内置主题的 keyboard_background 不会被清掉。
        val result = default.toMutableMap()
        for ((id, customEntry) in custom) {
            val base = default[id] ?: run {
                result[id] = customEntry
                continue
            }
            result[id] = base.copy(
                name = customEntry.name.ifEmpty { base.name },
                primaryColor = customEntry.primaryColor ?: base.primaryColor,
                keyboardBgColor = customEntry.keyboardBgColor ?: base.keyboardBgColor,
                keyBgColor = customEntry.keyBgColor ?: base.keyBgColor,
                keyBgColorDark = customEntry.keyBgColorDark ?: base.keyBgColorDark,
                specialKeyBgColor = customEntry.specialKeyBgColor ?: base.specialKeyBgColor,
                specialKeyBgColorDark = customEntry.specialKeyBgColorDark ?: base.specialKeyBgColorDark,
                candidateBarBgColor = customEntry.candidateBarBgColor ?: base.candidateBarBgColor,
                keyTextColor = customEntry.keyTextColor ?: base.keyTextColor,
                keyTextColorDark = customEntry.keyTextColorDark ?: base.keyTextColorDark,
                candidateTextColor = customEntry.candidateTextColor ?: base.candidateTextColor,
                candidateTextColorDark = customEntry.candidateTextColorDark ?: base.candidateTextColorDark,
                candidateSelectedTextColor = customEntry.candidateSelectedTextColor ?: base.candidateSelectedTextColor,
                candidateSelectedTextColorDark = customEntry.candidateSelectedTextColorDark ?: base.candidateSelectedTextColorDark,
                candidateSelectedBgColor = customEntry.candidateSelectedBgColor ?: base.candidateSelectedBgColor,
                candidateSelectedBgColorDark = customEntry.candidateSelectedBgColorDark ?: base.candidateSelectedBgColorDark,
                keyboardBackground = customEntry.keyboardBackground ?: base.keyboardBackground,
                keyBackground = customEntry.keyBackground ?: base.keyBackground,
                candidateBarBackground = customEntry.candidateBarBackground ?: base.candidateBarBackground,
                dynamicColor = customEntry.dynamicColor || base.dynamicColor,
            )
        }
        return result
    }

    // 冷启动一次 loadConfig 会对同一份 xime.yaml 做数十次读取 + 解析（每个解析函数各自读资产、
    // 各段落又逐段重复解析），真机上是主线程耗时的主要来源。一次装载内文本与解析树各缓存一份，
    // 把重复读取/解析收敛为「每个文件一次」；装载开始前清空，保证 custom 改动仍然即时生效。
    private val assetTextCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val yamlNodeCache = java.util.concurrent.ConcurrentHashMap<String, YamlNode>()

    private fun cachedYamlNode(yamlText: String): YamlNode {
        yamlNodeCache[yamlText]?.let { return it }
        return yaml.parseToYamlNode(yamlText).also { yamlNodeCache[yamlText] = it }
    }

    private fun readAssetText(context: Context, fileName: String): String? {
        assetTextCache[fileName]?.let { return it }
        val content = try {
            val inputStream = context.assets.open(fileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val text = reader.readText()
            reader.close()
            inputStream.close()
            text
        } catch (e: Exception) {
            null
        }
        if (content != null) assetTextCache[fileName] = content
        return content
    }

    /** 从用户数据目录 (context.filesDir/rime/) 读取文件。 */
    private fun readUserDataText(context: Context, fileName: String): String? {
        val file = File(context.filesDir, "rime/$fileName")
        if (!file.exists()) return null
        return try {
            val text = file.readText().trimStart('\uFEFF')
            text
        } catch (e: Exception) {
            Log.w(TAG, "readUserDataText: failed", e)
            null
        }
    }

    /** 读取自定义配置文本：优先用户数据目录（浏览器导入），回退 assets 内置。 */
    private fun readCustomText(context: Context): String? =
        readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)

    /** 读取合并后的方案市场镜像配置；`base_urls` 缺省时回退内置基址（对外保证非空列表）。 */
    fun loadXimeIndexConfig(context: Context): XimeIndexConfig =
        XimeIndexConfig(baseUrls = loadMergedConfig(context).ximeIndex?.resolvedBaseUrls ?: DEFAULT_INDEX_BASE_URLS)

    /** 从 xime.yaml 加载 color_schemes 配置。 */
    fun loadColorSchemes(context: Context): Map<String, ColorSchemeEntry> {
        val merged = loadMergedConfig(context)
        return merged.colorSchemes ?: emptyMap()
    }

    /** 从 xime.yaml 加载默认主题 ID（style.color_scheme 的 light 字段）。 */
    fun loadDefaultThemeId(context: Context): String {
        val merged = loadMergedConfig(context)
        return merged.style?.colorScheme?.light ?: "lavender_purple"
    }

    /** 根据显示模式加载对应的默认主题 ID。 */
    fun loadThemeIdForMode(context: Context, isDark: Boolean): String {
        val merged = loadMergedConfig(context)
        val cs = merged.style?.colorScheme ?: return "lavender_purple"
        return if (isDark) (cs.dark ?: cs.light ?: "lavender_purple")
               else cs.light ?: "lavender_purple"
    }

    /** 从 xime.yaml 加载默认显示模式（style.dark_mode）。 */
    fun loadDefaultDarkMode(context: Context): Int {
        val merged = loadMergedConfig(context)
        return merged.style?.darkMode ?: 2
    }

    // ── 新公开 API ──

    /** 获取键盘颜色配置（从 xime.yaml keyboard.colors 加载）。 */
    fun getKeyboardColors(): KeyboardColorsConfig = keyboardColorsConfig

    /** 获取键盘阴影配置（从 xime.yaml keyboard.shadow 加载）。 */
    fun getKeyboardShadow(): KeyboardShadowConfig = keyboardShadowConfig

    /** 获取键盘按键配置（从 xime.yaml keyboard.key 加载）。 */
    fun getKeyboardKeyConfig(): KeyboardKeyConfig = keyboardKeyConfig

    /** 获取九键左侧快捷符号栏配置（xime.custom.yaml → xime.yaml → 内置默认值）。 */
    fun getT9SideSymbols(): List<String> =
        keyboardT9Config.sideSymbols ?: DEFAULT_T9_SIDE_SYMBOLS

    /** 获取九键布局配置（keyboard.t9.layout，custom → builtIn → 内置默认，字段级 fallback）。 */
    fun getT9Layout(): KeyboardT9LayoutConfig = keyboardT9Config.layout

    /** 九键键面标签（keyboard.t9.keys.<id>.tap.label，未配置返回 null，由调用方回退内置默认）。 */
    fun getT9KeyLabel(id: String): String? =
        _t9GestureConfigs[id]?.tap?.label?.takeIf { it.isNotEmpty() }

    /** 九键长按候选显示文本（keyboard.t9.keys.<id>.long_press.values 的 label，缺省 value；未配置返回 null）。 */
    fun getT9KeyLongPressLabels(id: String): List<String>? =
        longPressDisplayItems(_t9GestureConfigs[id]?.longPress)

    /**
     * 长按气泡的显示项文本：`label` 为空时回退 `value`；两者皆空的项无法展示，丢弃。
     *
     * 单项且该项标了 `bubble: false` 时返回 null：不弹气泡，长按直接执行该项动作；
     * 多项时不支持 `bubble: false`（会让该项无法选中），一律按气泡展示。
     *
     * 26 键字母区（标准/横屏紧凑）、逗号键、中英键、九键共用，保证各处 label 处理一致。
     */
    fun longPressDisplayItems(config: LongPressAction?): List<String>? {
        val values = config?.values ?: return null
        if (isBubbleOffSingleValue(values)) return null
        return values.map { longPressItemKey(it) }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
    }

    /** 长按气泡的「显示项文本 → 动作」映射；键与 [longPressDisplayItems] 完全一致（不可展示的项不进入映射）。 */
    fun longPressActionMap(config: LongPressAction?): Map<String, KeyAction>? {
        val values = config?.values ?: return null
        if (isBubbleOffSingleValue(values)) return emptyMap()
        return values.filter { longPressItemKey(it).isNotEmpty() }.associateBy { longPressItemKey(it) }
    }

    /** 单项 long_press 标了 `bubble: false`：不弹气泡，该项作为直接长按动作。 */
    private fun isBubbleOffSingleValue(values: List<KeyAction>): Boolean =
        values.size == 1 && !values.first().bubble

    /** 长按气泡条目的显示文本与查找键（label 优先，缺省 value）。 */
    private fun longPressItemKey(action: KeyAction): String = action.label.ifEmpty { action.value }

    /** 获取九键数字键手势配置（xime.yaml keyboard.t9.keys，custom 键级覆盖）。
     *  键 id 为数字字符串 "1"~"9"；无配置返回 null（布局不启用滑动）。 */
    fun getT9KeyGesture(key: String): KeyBinding? = _t9GestureConfigs[key]

    /** 获取笔画左侧快捷符号列（keyboard.stroke.side_symbols，可自定义）。 */
    fun getStrokeSideSymbols(): List<String> =
        keyboardStrokeConfig.sideSymbols ?: DEFAULT_STROKE_SIDE_SYMBOLS

    /** 获取笔画键手势配置（xime.yaml keyboard.stroke.keys，custom 键级覆盖）。
     *  键 id 为键面标签：一/丨/丿/丶/乛、*、分词、，、英。 */
    fun getStrokeKeyGesture(key: String): KeyBinding? = _strokeGestureConfigs[key]

    /** 获取某个按键的手势配置。 */
    fun getKeyGesture(key: String): KeyBinding? = keyGestureConfig[key.lowercase()]

    /** 根据输入模式获取某个按键的手势配置。 */
    fun getKeyGesture(key: String, isAsciiMode: Boolean): KeyBinding? {
        val config = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return config[key.lowercase()]
    }

    fun getKeyDisplayLabel(key: String, isAsciiMode: Boolean = false): String {
        val config = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val label = config[key.lowercase()]?.tap?.label
        if (label.isNullOrEmpty()) return key.uppercase()
        return if (label.any { it in 'a'..'z' || it in 'A'..'Z' }) label.uppercase() else label
    }

    fun getKeyCommitValue(key: String, isAsciiMode: Boolean = false): String {
        val config = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val value = config[key.lowercase()]?.tap?.value
        return value?.takeIf { it.isNotEmpty() }
            // 合并键 ID（如 "qw"）无手势配置时回退为代表字母（组内第一个字母）
            ?: key.takeIf { it.length > 1 }?.first()?.toString()
            ?: key
    }

    // ── 旧公开 API（兼容） ──
    
    fun getConfig(): KeysConfig = config
    
    fun getSwipeUpText(key: String, isAsciiMode: Boolean = false): String? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val gesture = configMap[key.lowercase()]?.swipeUp
        if (gesture != null) {
            if (gesture.value.isNotEmpty()) return gesture.value
            if (gesture.label.isNotEmpty()) return gesture.label
        }
        return config.swipeUp[key.lowercase()]
    }

    fun getSwipeUpAction(key: String, isAsciiMode: Boolean = false): GestureAction? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return configMap[key.lowercase()]?.swipeUp?.action
    }

    /** 获取上滑显示文本（优先 label，fallback value；仅未配置 swipe_up 时用旧默认表） */
    fun getSwipeUpLabel(key: String, isAsciiMode: Boolean = false): String? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val gesture = configMap[key.lowercase()]?.swipeUp
        if (gesture != null) {
            if (gesture.label.isNotEmpty()) return gesture.label
            if (gesture.value.isNotEmpty()) return gesture.value
            // 显式配置了 swipe_up（如只写了 action）时不再回退旧默认符号表，
            // 否则键面/气泡会显示与该手势无关的默认符号（q→"1"）。
            return null
        }
        return config.swipeUp[key.lowercase()]
    }

    /** 获取上滑提交值（优先 value，fallback label；仅未配置 swipe_up 时用旧默认表） */
    fun getSwipeUpCommitValue(key: String, isAsciiMode: Boolean = false): String? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val gesture = configMap[key.lowercase()]?.swipeUp
        if (gesture != null) {
            if (gesture.value.isNotEmpty()) return gesture.value
            if (gesture.label.isNotEmpty()) return gesture.label
            return null
        }
        return config.swipeUp[key.lowercase()]
    }
    
    fun getSwipeDownEnglishText(key: String, isAsciiMode: Boolean = false): String? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val fromYaml = configMap[key.lowercase()]?.swipeDown?.label
        if (fromYaml != null && fromYaml.isNotEmpty()) return fromYaml
        return config.swipeDownEnglish[key.lowercase()]
    }

    /** 获取下滑动作类型 */
    fun getSwipeDownAction(key: String, isAsciiMode: Boolean = false): GestureAction? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return configMap[key.lowercase()]?.swipeDown?.action
    }

    /** 获取下滑显示位置：key（按键上）或 bubble（气泡） */
    fun getSwipeDownDisplay(key: String, isAsciiMode: Boolean = false): DisplayMode {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return configMap[key.lowercase()]?.swipeDown?.display ?: DisplayMode.BOTH
    }

    /** 获取上滑显示位置：key（按键上）或 bubble（气泡） */
    fun getSwipeUpDisplay(key: String, isAsciiMode: Boolean = false): DisplayMode {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return configMap[key.lowercase()]?.swipeUp?.display ?: DisplayMode.BOTH
    }

    /** 上滑运行时是否弹出内容气泡（与 [getSwipeUpDisplay] 无关）。 */
    fun getSwipeUpBubble(key: String, isAsciiMode: Boolean = false): Boolean {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return configMap[key.lowercase()]?.swipeUp?.bubble ?: true
    }

    /** 下滑运行时是否弹出内容气泡（与 [getSwipeDownDisplay] 无关）。 */
    fun getSwipeDownBubble(key: String, isAsciiMode: Boolean = false): Boolean {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        return configMap[key.lowercase()]?.swipeDown?.bubble ?: true
    }

    private fun getDefaultSwipeUp(): Map<String, String> = mapOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
        "a" to "!", "s" to "@", "d" to "#", "f" to "$", "g" to "%",
        "h" to "^", "j" to "&", "k" to "(", "l" to ")",
        "z" to "|", "x" to "*", "c" to "\\", "v" to "?", "b" to "_",
        "n" to "-", "m" to "+"
    )
    
    private fun getDefaultSwipeDownEnglish(): Map<String, String> = mapOf(
        "q" to "Q", "w" to "W", "e" to "E", "r" to "R", "t" to "T",
        "y" to "Y", "u" to "U", "i" to "I", "o" to "O", "p" to "P",
        "a" to "A", "s" to "S", "d" to "D", "f" to "F", "g" to "G",
        "h" to "H", "j" to "J", "k" to "K", "l" to "L",
        "z" to "Z", "x" to "X", "c" to "C", "v" to "V", "b" to "B",
        "n" to "N", "m" to "M"
    )
    

}