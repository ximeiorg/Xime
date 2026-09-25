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
        fun fromValue(value: String): ButtonLayout =
            entries.firstOrNull { it.value == value } ?: STANDARD
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
    /** 各键盘单独覆盖的间距，key 为键盘名（qwerty/t9/number/stroke/symbol） */
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
 * 槽位：tap / double_tap / long_press / swipe_up / swipe_down / swipe_left / swipe_right；
 * 另有 `when_composing`（组合态覆盖）与 `sticky`（键级粘滞）。
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
    var composing: KeyBinding? = null
    var sticky = false
    var width: Float? = null
    for ((kNode, vNode) in map.entries) {
        val name = (kNode as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
        when (name) {
            "tap" -> tap = parseKeyAction(vNode, GestureAction.SEND_RIME, presets)
            "double_tap" -> doubleTap = parseKeyAction(vNode, GestureAction.COMMIT, presets)
            "long_press" -> longPress = parseLongPress(vNode, presets)
            "swipe_up" -> swipeUp = parseKeyAction(vNode, GestureAction.COMMIT, presets)
            "swipe_down" -> swipeDown = parseKeyAction(vNode, GestureAction.COMMIT, presets)
            "swipe_left" -> swipeLeft = parseKeyAction(vNode, GestureAction.COMMIT, presets)
            "swipe_right" -> swipeRight = parseKeyAction(vNode, GestureAction.COMMIT, presets)
            "when_composing" -> if (vNode is com.charleskorn.kaml.YamlMap) {
                composing = parseKeyBinding(vNode, presets)
            }
            "sticky" -> sticky = (vNode as? com.charleskorn.kaml.YamlScalar)?.content?.toBooleanStrictOrNull() ?: false
            "width" -> width = (vNode as? com.charleskorn.kaml.YamlScalar)?.content?.toFloatOrNull()
        }
    }
    return KeyBinding(tap, doubleTap, longPress, swipeUp, swipeDown, swipeLeft, swipeRight, composing, sticky, width)
}

/**
 * 解析 long_press。统一为一种形式：`{ display?, values: [...] }`。
 *
 * - [display] 缺省为 `bubble`（长按弹出气泡滑动选择）；设为 `key` 则显示在键面，不弹气泡；
 * - [values] 为候选动作列表（单动作也写成单元素列表）。
 */
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
    val valuesNode = node.opt<YamlList>("values") ?: return null
    val display = node.opt<YamlScalar>("display")?.content
        ?.let { DisplayMode.fromValue(it) }
        ?: DisplayMode.BUBBLE
    val values = valuesNode.items.map { parseKeyAction(it, GestureAction.COMMIT, presets) }.take(10)
    return LongPressAction(
        display = display,
        values = values,
        repeat = values.firstOrNull()?.repeat ?: false,
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
        var labels: List<String> = emptyList()
        var action: GestureAction? = defaultAction
        var value = ""
        var display = "key"
        var bubble = true
        var repeat = false
        var sticky = false
        for ((k, v) in node.entries) {
            val key = (k as? com.charleskorn.kaml.YamlScalar)?.content ?: continue
            when (key) {
                "label" -> {
                    if (v is YamlList) {
                        labels = v.items.mapNotNull { (it as? YamlScalar)?.content }
                        label = labels.joinToString("\n")
                    } else {
                        label = (v as? YamlScalar)?.content ?: continue
                    }
                }
                "action" -> {
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
                "repeat" -> repeat = (v as? YamlScalar)?.content?.toBooleanStrictOrNull() ?: false
                "sticky" -> sticky = (v as? YamlScalar)?.content?.toBooleanStrictOrNull() ?: false
            }
        }
        val icon = if (label.startsWith("@")) label.removePrefix("@") else ""
        val cleanLabel = if (icon.isNotEmpty()) "" else label
        return KeyAction(
            action = action,
            value = value,
            label = cleanLabel,
            labels = labels,
            icon = icon,
            display = DisplayMode.fromValue(display),
            bubble = bubble,
            repeat = repeat,
            sticky = sticky,
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
    @SerialName("primary_color")
    val primaryColor: Long = 0,
    @SerialName("keyboard_bg_color")
    val keyboardBgColor: Long? = null,
    @SerialName("key_bg_color")
    val keyBgColor: Long? = null,
    @SerialName("key_bg_color_dark")
    val keyBgColorDark: Long? = null,
    @SerialName("special_key_bg_color")
    val specialKeyBgColor: Long? = null,
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

@Serializable
data class MetadataConfig(
    @SerialName("app_name")
    val appName: String = "Xime",
    @SerialName("app_version")
    val appVersion: String = "",
    @SerialName("platform")
    val platform: String = "android",
    @SerialName("config_version")
    val configVersion: Int = 1,
    @SerialName("generator")
    val generator: String = "",
    @SerialName("modified_time")
    val modifiedTime: String = "",
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
    /** 左列（自上而下）。candidates = 拼音候选面板占位（候选态显示音节，空闲态显示 side_symbols）。 */
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

internal data class KeyboardStrokePartial(
    val sideSymbols: List<String>? = null,
)

/** 字段级一路 fallback：custom → builtIn → 代码默认值。 */
internal inline fun <T> tiered(custom: T?, builtIn: T?, default: T): T = custom ?: builtIn ?: default

/** 字符串字段的 fallback：空白视为未配置，路由到下一级。 */
internal fun tieredText(custom: String?, builtIn: String?, default: String): String =
    custom?.takeIf { it.isNotBlank() }
        ?: builtIn?.takeIf { it.isNotBlank() }
        ?: default

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

@Serializable
data class XimeIndexConfig(
    @SerialName("base_urls")
    val baseUrls: List<String> = listOf("https://index.ximei.me/")
)

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
        "shift", "delete", "enter", "space", "mode_change", "symbol", "emoji", "earth", "voice", "comma"
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

    /** 笔画键盘左侧符号列内置默认（与历史硬编码一致）。 */
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

    // 字体配置缓存
    private var keyboardFontConfig: KeyboardFontConfig = KeyboardFontConfig()
    fun getKeyboardFontConfig(): KeyboardFontConfig = keyboardFontConfig
    
    // 按键布局模式缓存（中文 qwerty / 英文 qwerty_en）
    private var _buttonLayoutZh: ButtonLayout = ButtonLayout.STANDARD
    private var _buttonLayoutEn: ButtonLayout = ButtonLayout.STANDARD
    fun getButtonLayout(isAsciiMode: Boolean): ButtonLayout =
        if (isAsciiMode) _buttonLayoutEn else _buttonLayoutZh

    // 键盘行布局默认值
    private val DEFAULT_ZH_ROWS: List<List<String>> = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m"),
    )
    private val DEFAULT_EN_ROWS: List<List<String>> = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m"),
    )

    // 键盘行布局缓存（中文 / 英文）
    private var _zhRows: List<List<String>> = DEFAULT_ZH_ROWS
    private var _enRows: List<List<String>> = DEFAULT_EN_ROWS

    // 标准 26 键的基线缓存（合并键布局切换退出时恢复）
    private var _zhRowsBase: List<List<String>> = DEFAULT_ZH_ROWS
    private var _keyGestureConfigZhBase: Map<String, KeyBinding> = emptyMap()

    // 合并键布局缓存：section（qwerty_14/17/18 及 custom 新增）→ 行布局 / 手势配置
    private var _mergedRows: Map<String, List<List<String>>> = emptyMap()
    private var _mergedGestureConfigs: Map<String, Map<String, KeyBinding>> = emptyMap()
    private var _activeMergedSection: String? = null
    private var _activeSchemaId: String = ""

    // 合并键绑定缓存：schemaId → 键盘 section（xime.yaml keyboard.<section>.schemas 声明）
    private var _schemaSectionBindings: Map<String, String> = emptyMap()

    /**
     * 代码布局 section：由专属组件渲染（T9KeyboardLayout / StrokeKeyboardLayout /
     * HandwritingKeyboardLayout）。t9 有专属 layout{left,rows,right} 由 t9 配置解析器处理，
     * 不走通用 layout.rows；stroke/handwriting 无行数据。schemas 绑定到这些 section 的方案
     * 走 [codeLayoutForSchema] 查询，不进入合并键行布局缓存
     * （[mergedSectionForSchema] 对其返回 null）。
     */
    internal val CODE_LAYOUT_SECTIONS = setOf("t9", "stroke", "handwriting")

    // 九键/笔画手势配置缓存（keyboard.t9.keys / keyboard.stroke.keys，custom 键级覆盖）。
    // 键 id 不做大小写归一：九键为数字字符串 "1"~"9"，笔画为键面标签（一/丨/丿/丶/乛 等）。
    private var _t9GestureConfigs: Map<String, KeyBinding> = emptyMap()
    private var _strokeGestureConfigs: Map<String, KeyBinding> = emptyMap()

    /** 合并键方案（pinyin_14jian 等）对应的 xime.yaml 键盘 section，非合并键方案返回 null。 */
    internal fun mergedSectionForSchema(schemaId: String): String? =
        resolveMergedSection(schemaId, _schemaSectionBindings)

    /**
     * 绑定解析：仅认 schemas 声明（xime.yaml / xime.custom.yaml keyboard.<section>.schemas），
     * 未声明的方案一律全键盘（26 键），不做 id 关键字猜测。
     * 代码布局 section（t9/stroke）不是行数据布局，不作为合并键解析结果。
     */
    internal fun resolveMergedSection(schemaId: String, bindings: Map<String, String>): String? =
        bindings[schemaId]?.takeIf { it !in CODE_LAYOUT_SECTIONS }

    /** 查询方案声明的键盘 section（任意类型，含代码布局），未声明返回 null。 */
    fun boundSectionForSchema(schemaId: String): String? = _schemaSectionBindings[schemaId]

    /** 查询方案绑定的代码布局 section（t9 九键 / stroke 笔画），未绑定返回 null。 */
    fun codeLayoutForSchema(schemaId: String): String? =
        _schemaSectionBindings[schemaId]?.takeIf { it in CODE_LAYOUT_SECTIONS }

    /**
     * 按当前方案切换中文行布局与手势缓存（合并键布局 ↔ 标准 26 键）。
     * 在 KeyboardViewModel.dispatch/resetKeyboard 收到 schemaId 时调用；
     * 英文键盘不受影响（合并键布局仅中文拼音方案使用）。
     */
    fun setActiveKeyboardSchema(schemaId: String) {
        _activeSchemaId = schemaId
        val section = mergedSectionForSchema(schemaId)
        if (section == _activeMergedSection) return
        _activeMergedSection = section
        if (section != null) {
            _zhRows = _mergedRows[section] ?: DEFAULT_ZH_ROWS
            _keyGestureConfig.value = _mergedGestureConfigs[section] ?: emptyMap()
        } else {
            _zhRows = _zhRowsBase
            _keyGestureConfig.value = _keyGestureConfigZhBase
        }
    }

    /** 获取键盘行布局，每个子 List 为一行的按键 ID 列表，索引 0=第一行。
     *  合并键布局中合并键的 ID 为组内字母拼接（如 "qw"），渲染层按 ID 长度分配键宽。 */
    fun getKeyRows(isAsciiMode: Boolean): List<List<String>> =
        if (isAsciiMode) _enRows else _zhRows
    
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
    
    private fun loadXimeConfig(context: Context) {
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
            // 九键键盘配置（从原始 YAML 手动解析）
            keyboardT9Config = parseKeyboardT9FromAssets(context)
            // 笔画键盘配置（从原始 YAML 手动解析）
            keyboardStrokeConfig = parseKeyboardStrokeFromAssets(context)
            // 字体配置（从原始 YAML 手动解析）
            keyboardFontConfig = parseKeyboardFontsFromAssets(context)
            com.kingzcheung.xime.ui.keyboard.AppFonts.loadCustomFonts(keyboardFontConfig)
            // 按键布局（从原始 YAML 手动解析，中英文分开）
            val parsedLayouts = parseButtonLayoutFromAssets(context)
            _buttonLayoutZh = parsedLayouts.first
            _buttonLayoutEn = parsedLayouts.second
            // 键盘行布局
            val parsedRows = parseKeyboardLayoutFromAssets(context)
            _zhRowsBase = parsedRows.first
            _enRows = parsedRows.second
            // 合并键绑定（keyboard.<section>.schemas，custom 覆盖 builtIn 同名方案的绑定）
            val builtInBindings = readAssetText(context, XIME_CONFIG_FILE)
                ?.let { parseSchemaBindingsYamlText(it) } ?: emptyMap()
            val customBindings = readCustomText(context)
                ?.let { parseSchemaBindingsYamlText(it) } ?: emptyMap()
            _schemaSectionBindings = builtInBindings + customBindings
            // 合并键布局 sections：由 schemas 绑定动态发现（内置 qwerty_14/17/18 + custom 新增）；
            // 代码布局 section（t9/stroke）无行数据，走各自的专属配置解析，不在此加载
            val mergedRowsMap = mutableMapOf<String, List<List<String>>>()
            val mergedGesturesMap = mutableMapOf<String, Map<String, KeyBinding>>()
            for (section in _schemaSectionBindings.values.filter { it !in CODE_LAYOUT_SECTIONS }.distinct()) {
                parseLayoutSection(context, section)?.let { mergedRowsMap[section] = it }
                mergedGesturesMap[section] = parseGesturesSection(context, section)
            }
            _mergedRows = mergedRowsMap
            _mergedGestureConfigs = mergedGesturesMap
            // 九键/笔画手势（keyboard.t9.keys / keyboard.stroke.keys，custom 键级覆盖）
            _t9GestureConfigs = parseGesturesSection(context, "t9")
            _strokeGestureConfigs = parseGesturesSection(context, "stroke")
            // 基线缓存已刷新，先落标准 26 键的行布局/手势，合并键方案再由
            // setActiveKeyboardSchema 覆盖。不能只依赖 setActiveKeyboardSchema：
            // 非合并键方案 section 为 null，与刚重置的 _activeMergedSection(null) 相等
            // 会被提前 return，导致 xime.custom.yaml 的行布局/手势不生效（重新部署也无效）。
            _zhRows = _zhRowsBase
            _keyGestureConfig.value = _keyGestureConfigZhBase
            // 重新应用当前方案对应的合并键布局（上面重置了基线缓存）
            _activeMergedSection = null
            setActiveKeyboardSchema(_activeSchemaId)
            // 校验配置版本兼容性
            val merged = try { loadMergedConfig(context) } catch (_: YamlException) { null }
            val meta = merged?.metadata
            if (meta != null && meta.appVersion.isNotBlank()) {
                if (!checkVersionConstraint(BuildConfig.VERSION_NAME, meta.appVersion)) {
                    Log.w(TAG, "Config requires app_version ${meta.appVersion}, current is ${BuildConfig.VERSION_NAME}")
                }
            }
            _configVersion.value++
        } catch (e: Exception) {
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
        val defaultPresets = parseKeyboardActionsYamlText(defaultText)
        val defaultZh = parseKeyboardYamlSection(defaultText, "qwerty", defaultPresets) ?: return null
        val defaultEn = parseKeyboardYamlSection(defaultText, "qwerty_en", defaultPresets) ?: emptyMap()
        // 支持两种来源：files/rime/（浏览器导入）或 assets/（内置），自动 fallback
        val userData = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
        val customText = userData ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        val presets = defaultPresets + (customText?.let { parseKeyboardActionsYamlText(it) } ?: emptyMap())
        val customZh: Map<String, KeyBinding>?
        val customEn: Map<String, KeyBinding>?
        if (customText != null) {
            customZh = parseKeyboardYamlSection(customText, "qwerty", presets)
            customEn = parseKeyboardYamlSection(customText, "qwerty_en", presets)
        } else {
            customZh = null
            customEn = null
        }
        val zh = if (customZh != null) defaultZh + customZh else defaultZh
        val en = if (customEn != null) defaultEn + customEn else defaultEn
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
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
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
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
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
        return mergeKeyConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并按键配置：custom → builtIn → 代码默认值。 */
    internal fun mergeKeyConfigs(custom: KeyboardKeyPartial?, builtIn: KeyboardKeyPartial?): KeyboardKeyConfig =
        KeyboardKeyConfig(
            cornerRadius = tiered(custom?.cornerRadius, builtIn?.cornerRadius, 8),
            spacingX = tiered(custom?.spacingX, builtIn?.spacingX, null),
            spacingY = tiered(custom?.spacingY, builtIn?.spacingY, null),
            spacingOverrides = mergeSpacingOverrides(custom?.spacingOverrides, builtIn?.spacingOverrides),
        )

    /** 按键盘名合并间距覆盖：同名项字段级 fallback（custom 非空字段覆盖 builtIn）。 */
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
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
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
                        // 键盘级间距覆盖：keyboard.key.<键盘名>.spacing_x/spacing_y
                        var overrideX: Float? = null
                        var overrideY: Float? = null
                        for ((skNode, svNode) in vNode.entries) {
                            val skey = (skNode as? YamlScalar)?.content ?: continue
                            val svalue = (svNode as? YamlScalar)?.content ?: continue
                            if (skey == "spacing_x") {
                                overrideX = svalue.toFloatOrNull()
                            } else if (skey == "spacing_y") {
                                overrideY = svalue.toFloatOrNull()
                            }
                        }
                        spacingOverrides[key] = KeyboardSpacingConfig(overrideX, overrideY)
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
    private fun parseKeyboardT9FromAssets(context: Context): KeyboardT9Config {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardT9YamlPartial(it) }
        val custom = readCustomText(context)?.let { parseKeyboardT9YamlPartial(it) }
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

    /** 从 YAML 文本中提取 keyboard.t9 段（仅显式字段非 null）。 */
    internal fun parseKeyboardT9YamlPartial(yamlText: String): KeyboardT9Partial? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val t9Node = keyboardNode.opt<YamlMap>("t9") ?: return null
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
        val rows = layoutNode.opt<YamlList>("rows")?.let { parseLayoutRowsNode(it) }
        if (left.isNullOrEmpty() && right.isNullOrEmpty() && rows.isNullOrEmpty()) return null
        return KeyboardT9LayoutPartial(
            left = left?.takeIf { it.isNotEmpty() },
            rows = rows?.takeIf { it.isNotEmpty() },
            right = right?.takeIf { it.isNotEmpty() },
        )
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析笔画键盘配置。 */
    private fun parseKeyboardStrokeFromAssets(context: Context): KeyboardStrokeConfig {
        val builtIn = readAssetText(context, XIME_CONFIG_FILE)
            ?.let { parseKeyboardStrokeYamlPartial(it) }
        val custom = readCustomText(context)?.let { parseKeyboardStrokeYamlPartial(it) }
        return mergeStrokeConfigs(custom, builtIn)
    }

    /** 字段级一路 fallback 合并笔画配置：custom → builtIn → 代码默认值。 */
    internal fun mergeStrokeConfigs(custom: KeyboardStrokePartial?, builtIn: KeyboardStrokePartial?): KeyboardStrokeConfig =
        KeyboardStrokeConfig(
            sideSymbols = tiered(custom?.sideSymbols?.takeIf { it.isNotEmpty() },
                builtIn?.sideSymbols?.takeIf { it.isNotEmpty() }, DEFAULT_STROKE_SIDE_SYMBOLS),
        )

    /** 从 YAML 文本中提取 keyboard.stroke 段（仅显式字段非 null）。 */
    internal fun parseKeyboardStrokeYamlPartial(yamlText: String): KeyboardStrokePartial? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val strokeNode = keyboardNode.opt<YamlMap>("stroke") ?: return null
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

    /** 字段级一路 fallback 合并字体配置：custom → builtIn → 代码默认值（空字符串视为未配置）。 */
    internal fun mergeFontConfigs(custom: KeyboardFontPartial?, builtIn: KeyboardFontPartial?): KeyboardFontConfig =
        KeyboardFontConfig(
            keyFont = tieredText(custom?.keyFont, builtIn?.keyFont, ""),
            keyLabelFont = tieredText(custom?.keyLabelFont, builtIn?.keyLabelFont, ""),
            candidateFont = tieredText(custom?.candidateFont, builtIn?.candidateFont, ""),
            commentFont = tieredText(custom?.commentFont, builtIn?.commentFont, ""),
        )

    /** 从 YAML 文本中提取 keyboard.fonts 字体配置段（仅显式字段非 null）。 */
    internal fun parseKeyboardFontsYamlText(yamlText: String): KeyboardFontPartial? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
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
        val defaultZh = parseButtonLayoutYamlText(defaultText, "qwerty")
        val defaultEn = parseButtonLayoutYamlText(defaultText, "qwerty_en")
        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        val customZh = customText?.let { parseButtonLayoutYamlText(it, "qwerty") }
        val customEn = customText?.let { parseButtonLayoutYamlText(it, "qwerty_en") }
        val result = Pair(
            customZh ?: defaultZh ?: ButtonLayout.STANDARD,
            customEn ?: defaultEn ?: ButtonLayout.STANDARD,
        )
        return result
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析键盘行布局。 */
    private fun parseKeyboardLayoutFromAssets(context: Context): Pair<List<List<String>>, List<List<String>>> {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE) ?: return Pair(DEFAULT_ZH_ROWS, DEFAULT_EN_ROWS)

        val defaultZh = parseKeyboardLayoutYamlText(defaultText, "qwerty")
        val defaultEn = parseKeyboardLayoutYamlText(defaultText, "qwerty_en")

        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)

        val customZh = customText?.let { parseKeyboardLayoutYamlText(it, "qwerty") }
        val customEn = customText?.let { parseKeyboardLayoutYamlText(it, "qwerty_en") }

        return Pair(
            customZh ?: defaultZh ?: DEFAULT_ZH_ROWS,
            customEn ?: defaultEn ?: DEFAULT_EN_ROWS,
        )
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析指定 section 的键盘行布局（custom 整段覆盖 built-in）。 */
    private fun parseLayoutSection(context: Context, section: String): List<List<String>>? {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE)
        val default = defaultText?.let { parseKeyboardLayoutYamlText(it, section) }
        val customText = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
            ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        val custom = customText?.let { parseKeyboardLayoutYamlText(it, section) }
        return custom ?: default
    }

    /** 从 xime.yaml + xime.custom.yaml 合并解析指定 section 的手势配置（custom 键级覆盖 built-in）。 */
    private fun parseGesturesSection(context: Context, section: String): Map<String, KeyBinding> {
        val defaultText = readAssetText(context, XIME_CONFIG_FILE)
        val defaultPresets = defaultText?.let { parseKeyboardActionsYamlText(it) } ?: emptyMap()
        val default = defaultText?.let { parseKeyboardYamlSection(it, section, defaultPresets) } ?: emptyMap()
        val userData = readUserDataText(context, XIME_CUSTOM_CONFIG_FILE)
        val customText = userData ?: readAssetText(context, XIME_CUSTOM_CONFIG_FILE)
        val customPresets = customText?.let { parseKeyboardActionsYamlText(it) } ?: emptyMap()
        val presets = defaultPresets + customPresets
        val custom = customText?.let { parseKeyboardYamlSection(it, section, presets) }
        return if (custom != null) default + custom else default
    }

    /**
     * 从 YAML 文本提取合并键绑定：keyboard.<section>.schemas 列出的每个方案 id 映射到该 section。
     * 无 schemas 声明的 section 忽略；同一方案 id 在多个 section 声明时以后出现的为准。
     */
    internal fun parseSchemaBindingsYamlText(yamlText: String): Map<String, String> {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return emptyMap()
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
     * 从 YAML 文本中提取 keyboard.<section>.layout.rows。
     * 行元素支持嵌套子数组表示合并键：[[q, w], [e, r], t] → ["qw", "er", "t"]，
     * 合并键 ID 为组内字母拼接，渲染层按 ID 长度分配键宽。
     */
    internal fun parseKeyboardLayoutYamlText(yamlText: String, section: String): List<List<String>>? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val sectionNode = keyboardNode.opt<YamlMap>(section) ?: return null
            val layoutNode = sectionNode.opt<YamlMap>("layout") ?: return null
            val rowsNode = layoutNode.opt<YamlList>("rows") ?: return null
            parseLayoutRowsNode(rowsNode).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse keyboard layout config", e)
            null
        }
    }

    /** rows 节点 → 行列表：子数组为合并键（组内字母拼接为 ID），标量行手动拆分兜底。 */
    private fun parseLayoutRowsNode(rowsNode: YamlList): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        for (rowNode in rowsNode.items) {
            val row = when (rowNode) {
                is YamlList -> rowListToKeyIds(rowNode)
                is YamlScalar -> scalarRowToKeyIds(rowNode.content)
                else -> emptyList()
            }
            if (row.isNotEmpty()) {
                rows.add(row)
            }
        }
        return rows
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

    /** 从 YAML 文本中提取 keyboard.<section>.button_layout。 */
    private fun parseButtonLayoutYamlText(yamlText: String, section: String): ButtonLayout? {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
            val keyboardNode = root.opt<YamlMap>("keyboard") ?: return null
            val sectionNode = keyboardNode.opt<YamlMap>(section) ?: return null
            val layoutNode = sectionNode.opt<YamlScalar>("button_layout") ?: return null
            ButtonLayout.fromValue(layoutNode.content)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse button layout config", e)
            null
        }
    }

    /** 解析 `keyboard.actions` 可复用动作预设。 */
    internal fun parseKeyboardActionsYamlText(yamlText: String): Map<String, KeyAction> {
        return try {
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return emptyMap()
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
            val root = yaml.parseToYamlNode(yamlText) as? YamlMap ?: return null
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
            ximeIndex = custom.ximeIndex ?: default.ximeIndex,
            // 合并而非替换：custom 覆盖同名字题，其余保留内置主题，
            // 这样用户只需在 xime.custom.yaml 中添加自定义背景主题而不会丢失内置主题。
            colorSchemes = mergeColorSchemes(default.colorSchemes, custom.colorSchemes),
            style = mergeStyle(default.style, custom.style),
            metadata = custom.metadata ?: default.metadata,
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
                primaryColor = customEntry.primaryColor.takeIf { it != 0L } ?: base.primaryColor,
                keyboardBgColor = customEntry.keyboardBgColor ?: base.keyboardBgColor,
                keyBgColor = customEntry.keyBgColor ?: base.keyBgColor,
                keyBgColorDark = customEntry.keyBgColorDark ?: base.keyBgColorDark,
                specialKeyBgColor = customEntry.specialKeyBgColor ?: base.specialKeyBgColor,
                candidateBarBgColor = customEntry.candidateBarBgColor ?: base.candidateBarBgColor,
                keyTextColor = customEntry.keyTextColor ?: base.keyTextColor,
                keyTextColorDark = customEntry.keyTextColorDark ?: base.keyTextColorDark,
                candidateTextColor = customEntry.candidateTextColor ?: base.candidateTextColor,
                candidateTextColorDark = customEntry.candidateTextColorDark ?: base.candidateTextColorDark,
                candidateSelectedTextColor = customEntry.candidateSelectedTextColor ?: base.candidateSelectedTextColor,
                candidateSelectedTextColorDark = customEntry.candidateSelectedTextColorDark ?: base.candidateSelectedTextColorDark,
                keyboardBackground = customEntry.keyboardBackground ?: base.keyboardBackground,
                keyBackground = customEntry.keyBackground ?: base.keyBackground,
                candidateBarBackground = customEntry.candidateBarBackground ?: base.candidateBarBackground,
                dynamicColor = customEntry.dynamicColor || base.dynamicColor,
            )
        }
        return result
    }

    private fun readAssetText(context: Context, fileName: String): String? {
        return try {
            val inputStream = context.assets.open(fileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.readText()
            reader.close()
            inputStream.close()
            content
        } catch (e: Exception) {
            null
        }
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

    fun loadXimeIndexConfig(context: Context): XimeIndexConfig {
        val merged = loadMergedConfig(context)
        return merged.ximeIndex ?: XimeIndexConfig()
    }

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
        _t9GestureConfigs[id]?.longPress?.values
            ?.map { it.label.ifEmpty { it.value } }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }

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

    /** 获取某个按键指定手势的显示标签。 */
    fun getGestureLabel(key: String, gesture: String, isAsciiMode: Boolean = false): String? {
        val config = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val kc = config[key.lowercase()] ?: return null
        return when (gesture) {
            "tap" -> kc.tap?.label
            "swipe_up" -> kc.swipeUp?.label
            "swipe_down" -> kc.swipeDown?.label
            "long_press" -> kc.longPress?.values?.firstOrNull()?.label
            else -> null
        }
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

    /** 获取上滑显示文本（优先 label，fallback value） */
    fun getSwipeUpLabel(key: String, isAsciiMode: Boolean = false): String? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val gesture = configMap[key.lowercase()]?.swipeUp
        if (gesture != null) {
            if (gesture.label.isNotEmpty()) return gesture.label
            if (gesture.value.isNotEmpty()) return gesture.value
        }
        return config.swipeUp[key.lowercase()]
    }

    /** 获取上滑提交值（优先 value，fallback label） */
    fun getSwipeUpCommitValue(key: String, isAsciiMode: Boolean = false): String? {
        val configMap = if (isAsciiMode) _keyGestureConfigEn.value else _keyGestureConfig.value
        val gesture = configMap[key.lowercase()]?.swipeUp
        if (gesture != null) {
            if (gesture.value.isNotEmpty()) return gesture.value
            if (gesture.label.isNotEmpty()) return gesture.label
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