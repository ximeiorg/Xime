package com.kingzcheung.xime.settings

import android.content.Context
import android.content.res.AssetManager
import com.kingzcheung.xime.keyboard.GestureAction
import com.kingzcheung.xime.keyboard.KeyActionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * 面板键盘（数字 / 常用符号）yaml 配置驱动：内置资产段结构、键宽度/动作覆盖、
 * 键 id 契约与 `<section>_tablet` 设备档变体。
 *
 * 背景：数字面板与常用符号面板原先分别是 `NumberKeyboardLayout` / `CommonSymbolKeyboardLayout`
 * 两份硬编码 Compose 布局；现由 `keyboard.number`（columns 三列）/ `keyboard.common_symbols(_en)`
 * （rows 行布局）段配置驱动，渲染统一走 `PanelKeyboardLayout`。本测试守住内置资产段与解析链路，
 * 防止「配置写对但解析不对」或引用未注册的动作/未知功能键 id。
 */
class KeyboardPanelConfigTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun assetXimeYaml(): String = repoFile("src/main/assets/xime.yaml").readText()

    /** 定位仓库内文件（单测 workingDir 可能是模块目录或仓库根目录，逐级向上查找）。 */
    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it }
            File(dir, "app/$rel").takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("file not found: $rel")
    }

    // ═══ 1. 内置资产：段结构与模式判定 ═══

    @Test
    fun `内置数字键盘段解析为三列布局`() {
        val layout = KeysConfigHelper.parsePanelLayoutYamlText(assetXimeYaml(), "number")
        assertNotNull("xime.yaml 缺少 number 段 layout", layout)
        assertTrue("有 left/right 时应判定为 columns 模式", layout!!.columns)
        // 左列：运算符键柱（四键一柱）+ 返回键
        assertEquals(listOf(listOf("+", "-", "*", "/"), listOf("back")), layout.left)
        // 中列：3×3 数字区 + 符号/0/小数点行
        assertEquals(
            listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("open_symbols", "0", "."),
            ),
            layout.rows,
        )
        assertEquals(listOf(listOf("delete"), listOf("space_bar"), listOf("emoji"), listOf("enter")), layout.right)
    }

    @Test
    fun `内置常用符号段为「中英共用一段」：无 _en 段、无 En 键`() {
        val text = assetXimeYaml()
        val symbols = KeysConfigHelper.parsePanelLayoutYamlText(text, "common_symbols")
            ?: error("xime.yaml 缺少 common_symbols 段 layout")
        assertTrue(!symbols.columns)
        assertEquals(
            listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("@", "＃", "＄", "＆", "＿", "－", "＋", "（", "）", "／"),
                listOf("open_symbols", "＊", "“", "”", "！", "？", ",", ".", "delete"),
                listOf("back", "number", "space_key", "enter"),
            ),
            symbols.rows,
        )
        // 符号默认中文形态（全角）；@、, 与 . 保留半角 ASCII（@ 需半角才能触发聊天工具提醒）
        assertTrue(
            "除 @ 外的符号键应为全角中文形态",
            symbols.rows[1].filter { it != "@" }.all { it.length == 1 && it[0].code > 0x7E },
        )
        assertTrue("@ 应为半角", "@" in symbols.rows[1])
        assertTrue(", 与 . 应保留半角", "," in symbols.rows[2] && "." in symbols.rows[2])
        assertQuotePair(symbols.rows.flatten(), "平板常用符号段")
        assertTrue(
            "不应再出现中文逗号/句号（改用半角 , 与 .）",
            symbols.rows.flatten().none { it == "，" || it == "。" },
        )
        assertQuotePair(symbols.rows.flatten(), "手机常用符号段")
        // 不再有中/英两段，也没有 En（ascii_toggle）键
        assertNull("符号面板不应再有 _en 段", KeysConfigHelper.parsePanelLayoutYamlText(text, "common_symbols_en"))
        assertTrue(
            "面板段清单里不应再有 _en 段",
            KeysConfigHelper.PANEL_SECTIONS.none { it.endsWith("_en") },
        )
        assertTrue(
            "布局里不应再出现 En 键",
            symbols.rows.flatten().none { it == "ascii_toggle" },
        )
    }

    @Test
    fun `面板段未配置时返回 null 由渲染层兜底`() {
        assertNull(KeysConfigHelper.parsePanelLayoutYamlText("keyboard:\n  qwerty:\n    keys:\n      a: { tap: \"a\" }", "number"))
        assertNull(KeysConfigHelper.parsePanelLayoutYamlText(assetXimeYaml(), "not_a_panel"))
    }

    @Test
    fun `段类型声明 columns 不进合并键布局`() {
        val types = KeysConfigHelper.parseSectionTypesYamlText(assetXimeYaml())
        assertEquals("columns", KeysConfigHelper.sectionKindOf("number", types))
        assertEquals("full", KeysConfigHelper.sectionKindOf("common_symbols", types))
        // 面板段不声明 schemas → 不会被方案绑定进合并键行布局（合并键方案仍解析到自己的段）
        val bindings = KeysConfigHelper.parseSchemaBindingsYamlText(assetXimeYaml())
        assertEquals("qwerty_14", KeysConfigHelper.resolveMergedSection("pinyin_14jian", bindings, types))
        assertTrue(
            "面板段不应出现在任何方案的段绑定里",
            bindings.values.none { it in KeysConfigHelper.PANEL_SECTIONS },
        )
    }

    // ═══ 2. 键级配置（宽度 / 标签 / 动作） ═══

    @Test
    fun `内置面板段键宽度与标签解析生效`() {
        val text = assetXimeYaml()
        val symbols = KeysConfigHelper.parseKeyboardYamlSection(text, "common_symbols")
        assertNotNull(symbols)
        assertEquals(1.2f, symbols!!["back"]?.width)
        assertEquals("返回", symbols["back"]?.tap?.label)
        assertEquals(1.2f, symbols["delete"]?.width)
        assertEquals(1.3f, symbols["open_symbols"]?.width)
        assertEquals(2.5f, symbols["space_key"]?.width)
        assertEquals(1.2f, symbols["enter"]?.width)

        val number = KeysConfigHelper.parseKeyboardYamlSection(text, "number")
        assertNotNull(number)
        // 数字面板回车走引擎（与主键盘同语义），不是直接上屏
        assertEquals(GestureAction.SEND_RIME, number!!["enter"]?.tap?.action)
        assertEquals("enter", number["enter"]?.tap?.value)
        assertEquals("确定", number["enter"]?.tap?.label)
    }

    @Test
    fun `面板段动作均已注册且功能键 id 有渲染组件`() {
        val text = assetXimeYaml()
        for (section in KeysConfigHelper.PANEL_SECTIONS) {
            val gestures = KeysConfigHelper.parseKeyboardYamlSection(text, section)
            assertNotNull("xime.yaml 缺少 $section keys", gestures)
            for ((id, binding) in gestures!!) {
                for (action in allSlotActions(binding)) {
                    if (action.action != null) {
                        assertEquals(
                            "面板段 $section 键 $id 的动作 ${action.action} 未在注册表登记",
                            action.action, KeyActionRegistry.fromId(action.action.value)?.action,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `内置面板布局的键 id 要么是单字值键要么是已登记功能键`() {
        val text = assetXimeYaml()
        for (section in KeysConfigHelper.PANEL_SECTIONS) {
            val layout = KeysConfigHelper.parsePanelLayoutYamlText(text, section) ?: continue
            val ids = layout.rows.flatten() + layout.left.flatten() + layout.right.flatten()
            assertTrue("$section 段布局不应为空", ids.isNotEmpty())
            for (id in ids) {
                assertTrue(
                    "$section 段的键 id \"$id\" 既不是单字值键、也不在 FUNCTION_KEY_IDS 里（渲染层会当成死键）",
                    id in KeysConfigHelper.FUNCTION_KEY_IDS || id.length == 1,
                )
            }
        }
    }

    // ═══ 3. 设备档变体（<section>_tablet）与自定义覆盖 ═══

    @Test
    fun `内置平板数字面板段为 9 列 4 行且键宽按设计稿比例`() {
        val text = assetXimeYaml()
        val layout = KeysConfigHelper.parsePanelLayoutYamlText(text, "number_tablet")
            ?: error("xime.yaml 缺少 number_tablet 段 layout")
        assertTrue("平板段用行布局（rows）表达", !layout.columns)
        assertEquals(
            listOf(
                listOf("(", ")", ":", "1", "2", "3", "+", "-", "delete"),
                listOf("!", "?", ";", "4", "5", "6", "*", "/", "enter"),
                listOf("#", "$", "%", "7", "8", "9", "=", "&", "space_key"),
                listOf("back", "open_symbols", ",", "0", ".", "cursor_left", "cursor_right", "back_right"),
            ),
            layout.rows,
        )
        val keys = KeysConfigHelper.parseKeyboardYamlSection(text, "number_tablet")
        assertNotNull("xime.yaml 缺少 number_tablet keys", keys)
        // width = 行内相对宽度（1 = 标准键；未写的键即 1 = 默认）
        assertEquals(2.3f, keys!!["1"]?.width)
        assertEquals(2.3f, keys[","]?.width)
        assertEquals(1.5f, keys["back"]?.width)
        assertEquals(1.5f, keys["open_symbols"]?.width)
        // 符号 / 运算符 / 功能列 / 光标键 / back_right 都是标准键宽，不写 width
        assertNull("标准键宽不应写 width（默认 1）", keys["("])
        assertNull("标准键宽不应写 width（默认 1）", keys["+"])
        assertNull("标准键宽不应写 width（默认 1）", keys["delete"])
        assertNull("标准键宽不应写 width（默认 1）", keys["space_key"])
        assertNull("标准键宽不应写 width（默认 1）", keys["cursor_left"])
        assertNull("标准键宽不应写 width（默认 1）", keys["back_right"])
    }

    @Test
    fun `内置平板符号面板段为 12 列 4 行且只有一段（中英共用）`() {
        val text = assetXimeYaml()
        val symbols = KeysConfigHelper.parsePanelLayoutYamlText(text, "common_symbols_tablet")
            ?: error("xime.yaml 缺少 common_symbols_tablet 段 layout")
        assertEquals(
            listOf(
                listOf("tab", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "delete"),
                listOf("caps", "@", "＃", "＄", "＿", "＆", "－", "＋", "（", "）", "enter"),
                listOf("open_symbols", "＊", "“", "”", "：", "；", "！", "？", "／", ",", "."),
                listOf("back", "number", "space_key", "cursor_left", "cursor_right", "back_right"),
            ),
            symbols.rows,
        )
        // 与手机同一套字形：@、, 与 . 半角，其余中文形态
        assertTrue(
            "@ 应为半角，其余符号键应为全角中文形态",
            symbols.rows[1].drop(1).dropLast(1).filter { it != "@" }.all { it[0].code > 0x7E },
        )
        assertTrue("@ 应为半角", "@" in symbols.rows[1])
        assertTrue(", 与 . 应保留半角", "," in symbols.rows[2] && "." in symbols.rows[2])
        assertQuotePair(symbols.rows.flatten(), "平板常用符号段")
        val keys = KeysConfigHelper.parseKeyboardYamlSection(text, "common_symbols_tablet")!!
        // 与平板 26 键逐列同宽（见 keyboard_tablet 各行的 tab/caps/shift_left/shift_right/mode_change/space）
        assertEquals(1.1f, keys["tab"]?.width)
        assertEquals(1.1f, keys["delete"]?.width)
        assertEquals(1.3f, keys["caps"]?.width)
        assertEquals(1.4f, keys["enter"]?.width)
        assertEquals(1.6f, keys["open_symbols"]?.width)
        assertEquals(1.2f, keys["back"]?.width)
        assertEquals(5.5f, keys["space_key"]?.width)
        assertEquals(1.2f, keys["back_right"]?.width)
        assertEquals(1.2f, keys["."]?.width)
        assertNull("标准键宽不应写 width（默认 1）", keys["number"])
        assertNull("标准键宽不应写 width（默认 1）", keys["@"])
        assertNull("标准键宽不应写 width（默认 1）", keys["cursor_left"])
        // 平板也只有一段（没有 _en 变体）
        assertNull("平板符号面板不应再有 _en 段", KeysConfigHelper.parsePanelLayoutYamlText(text, "common_symbols_en_tablet"))
    }

    @Test
    fun `平板面板段键 id 同样满足「单字值键或已登记功能键」契约`() {
        val text = assetXimeYaml()
        for (section in listOf("number_tablet", "common_symbols_tablet")) {
            val layout = KeysConfigHelper.parsePanelLayoutYamlText(text, section) ?: error("缺少 $section 段")
            val ids = layout.rows.flatten()
            assertTrue("$section 段布局不应为空", ids.isNotEmpty())
            for (id in ids) {
                assertTrue(
                    "$section 段的键 id \"$id\" 既不是单字值键、也不在 FUNCTION_KEY_IDS 里（渲染层会当成死键）",
                    id in KeysConfigHelper.FUNCTION_KEY_IDS || id.length == 1,
                )
            }
            // 设计稿两端各有一个「返回字母键盘」键（行为一致、宽度各自独立）
            assertEquals("$section 应两端各一个返回键", 1, ids.count { it == "back" })
            assertEquals("$section 应两端各一个返回键", 1, ids.count { it == "back_right" })
        }
    }

    @Test
    fun `平板键距覆盖：键盘名加 _tablet 后缀在平板上并到同名基础覆盖`() {
        val config = KeyboardKeyConfig(
            spacingX = 2f,
            spacingY = 4.25f,
            spacingOverrides = mapOf(
                "number" to KeyboardSpacingConfig(spacingX = 2f, spacingY = 2f),
                "number_tablet" to KeyboardSpacingConfig(spacingX = 4f, spacingY = 6f, cornerRadius = 6),
                "symbol_tablet" to KeyboardSpacingConfig(spacingX = 4f, spacingY = 6f),
            ),
        )
        val tablet = KeysConfigHelper.applyTabletKeyOverrides(config)
        assertEquals(4f to 6f, tablet.spacingFor("number"))
        assertEquals(4f to 6f, tablet.spacingFor("symbol"))
        assertEquals(6, tablet.cornerRadiusFor("number"))
        // 未写 *_tablet 的键盘保持原样；手机（不调用该函数）也不受影响
        assertEquals(2f to 2f, config.spacingFor("number"))
        assertEquals(2f to 4.25f, config.spacingFor("symbol"))
        assertEquals(8, config.cornerRadiusFor("number"))
    }

    @Test
    fun `内置资产平板面板段可解析且键距与 26 键同档`() {
        val text = assetXimeYaml()
        val keyConfig = KeysConfigHelper.parseKeyboardKeyYamlText(text)
            ?: error("xime.yaml 缺少 keyboard.key 配置")
        val tablet = KeysConfigHelper.applyTabletKeyOverrides(keyConfig)
        assertEquals(4f to 6f, tablet.spacingFor("number"))
        assertEquals(4f to 6f, tablet.spacingFor("symbol"))
        // 九键 / 笔画同样合并到设备档（平板键面内衬 4/6dp），与 26 键、面板同一档
        assertEquals(4f to 6f, tablet.spacingFor("t9"))
        assertEquals(4f to 6f, tablet.spacingFor("stroke"))
        // 手机档：仍是各面板原有间距
        assertEquals(2f to 2f, keyConfig.spacingFor("number"))
        assertEquals(2f to 4f, keyConfig.spacingFor("symbol"))
        assertEquals(2f to 2f, keyConfig.spacingFor("t9"))
        assertEquals(2f to 2f, keyConfig.spacingFor("stroke"))
    }

    @Test
    fun `内置资产在平板档下解析到面板平板块`() {
        val text = assetXimeYaml()
        // 平板：解析到 <段名>_tablet；手机：基础段（设备判定在 isTabletDevice，无法在单测里伪造成平板，
        // 这里直接覆盖「段名解析」这一环，设备判定由 resolveQwertySection 的已有用例覆盖）
        assertEquals("number_tablet", KeysConfigHelper.resolveQwertySection("number", text, null, isTablet = true))
        assertEquals("number", KeysConfigHelper.resolveQwertySection("number", text, null, isTablet = false))
        assertEquals("common_symbols_tablet", KeysConfigHelper.resolveQwertySection("common_symbols", text, null, isTablet = true))
        assertEquals("common_symbols", KeysConfigHelper.resolveQwertySection("common_symbols", text, null, isTablet = false))
        assertEquals("common_symbols_tablet", KeysConfigHelper.resolveQwertySection("common_symbols", text, null, isTablet = true))
    }

    @Test
    fun `按键圆角可按键盘覆盖且平板档走 _tablet 合并`() {
        val text = """
            keyboard:
              key:
                corner_radius: 8
                symbol: { corner_radius: 10 }
                number_tablet: { corner_radius: 6 }
        """.trimIndent()
        val config = KeysConfigHelper.parseKeyboardKeyYamlText(text) ?: error("key 配置解析失败")
        assertEquals(8, config.cornerRadiusFor("qwerty"))
        assertEquals(10, config.cornerRadiusFor("symbol"))
        assertEquals(8, config.cornerRadiusFor("number"))
        assertEquals(6, KeysConfigHelper.applyTabletKeyOverrides(config).cornerRadiusFor("number"))
        // 内置资产：面板没有键盘级圆角覆盖，取值 = 全局 8dp（渲染层的「平板大键面档」默认
        // 由 keyCornerRadiusFor 统一决定，见 KeyCornerRadiusTest）
        val builtIn = KeysConfigHelper.parseKeyboardKeyYamlText(assetXimeYaml()) ?: error("内置 key 配置解析失败")
        assertEquals(8, builtIn.cornerRadiusFor("number"))
        assertEquals(8, builtIn.cornerRadiusFor("symbol"))
        assertEquals(8, KeysConfigHelper.applyTabletKeyOverrides(builtIn).cornerRadiusFor("symbol"))
    }

    @Test
    fun `面板段支持平板变体整段替换`() {
        val withTablet = """
            keyboard:
              number:
                type: columns
                layout:
                  rows:
                    - [1, 2, 3]
              number_tablet:
                type: columns
                layout:
                  rows:
                    - [1, 2, 3, 4]
        """.trimIndent()
        assertEquals("number_tablet", KeysConfigHelper.resolveQwertySection("number", withTablet, null, isTablet = true))
        assertEquals("number", KeysConfigHelper.resolveQwertySection("number", withTablet, null, isTablet = false))
        // 只写了基础段时平板上仍用基础段；变体段也可写在 custom 层
        val baseOnly = "keyboard:\n  number:\n    type: columns\n"
        assertEquals("number", KeysConfigHelper.resolveQwertySection("number", baseOnly, null, isTablet = true))
        assertEquals(
            "number_tablet",
            KeysConfigHelper.resolveQwertySection("number", baseOnly, withTablet, isTablet = true),
        )
        // 变体段布局由调用方按解析出的段名读取
        assertEquals(4, KeysConfigHelper.parsePanelLayoutYamlText(withTablet, "number_tablet")!!.rows[0].size)
    }

    @Test
    fun `自定义面板段可整段覆盖内置布局`() {
        val custom = """
            keyboard:
              number:
                type: columns
                layout:
                  left:
                    - ["%"]
                    - back
                  rows:
                    - [7, 8, 9]
                  right: [delete, enter]
        """.trimIndent()
        val layout = KeysConfigHelper.parsePanelLayoutYamlText(custom, "number")
        assertNotNull(layout)
        assertTrue(layout!!.columns)
        assertEquals(listOf(listOf("%"), listOf("back")), layout.left)
        assertEquals(listOf(listOf("7", "8", "9")), layout.rows)
        assertEquals(listOf(listOf("delete"), listOf("enter")), layout.right)
    }

    @Test
    fun `面板 layout 逐字段合并 custom 未写的键保留内置`() {
        val builtIn = """
            keyboard:
              number:
                type: columns
                layout:
                  left:
                    - ["+", "-"]
                    - back
                  rows:
                    - [1, 2, 3]
                  right: [delete, enter]
        """.trimIndent()
        val custom = """
            keyboard:
              number:
                layout:
                  right: [enter]
        """.trimIndent()
        val merged = KeysConfigHelper.mergePanelLayouts(
            KeysConfigHelper.parsePanelLayoutPartialYamlText(custom, "number"),
            KeysConfigHelper.parsePanelLayoutPartialYamlText(builtIn, "number"),
        )
        assertNotNull(merged)
        // columns 模式由合并后是否仍有 left/right 决定（不能因 custom 只写 right 就退化成行模式）
        assertTrue(merged!!.columns)
        assertEquals(listOf(listOf("+", "-"), listOf("back")), merged.left)
        assertEquals(listOf(listOf("1", "2", "3")), merged.rows)
        assertEquals(listOf(listOf("enter")), merged.right)
    }

    @Test
    fun `面板 layout 双层都未配置时返回 null`() {
        assertNull(KeysConfigHelper.mergePanelLayouts(null, null))
    }

    // ═══ 4. loadConfig 装载链路（内置 + custom 覆盖） ═══

    @Test
    fun `loadConfig 装载面板段：custom 字段级覆盖布局与键级覆盖手势并把未覆盖项留给内置`() {
        val builtIn = """
            keyboard:
              number:
                type: columns
                layout:
                  left:
                    - ["+", "-"]
                    - back
                  rows:
                    - [1, 2, 3]
                  right: [delete, enter]
                keys:
                  delete: { width: 1.4 }
                  enter: { width: 1.2, tap: { action: send_rime, value: enter } }
              common_symbols:
                type: full
                layout:
                  rows:
                    - [1, 2]
                keys:
                  back: { tap: { label: "返回" } }
        """.trimIndent()
        val custom = """
            keyboard:
              number:
                layout:
                  right: [enter]
                keys:
                  enter: { tap: { label: "自定义" } }
        """.trimIndent()
        val filesDir = tmp.newFolder("files")
        val rimeDir = File(filesDir, "rime").apply { mkdirs() }
        File(rimeDir, "xime.custom.yaml").writeText(custom)
        val assets = mock<AssetManager>()
        whenever(assets.open("xime.yaml")).thenAnswer { ByteArrayInputStream(builtIn.toByteArray()) }
        whenever(assets.open("xime.custom.yaml")).thenThrow(IOException("not in assets"))
        val context = mock<Context>()
        whenever(context.assets).thenReturn(assets)
        whenever(context.filesDir).thenReturn(filesDir)

        KeysConfigHelper.loadConfig(context)

        // 布局：custom 只写 right → 其余字段保留内置，且仍为三列模式
        val number = KeysConfigHelper.getPanelLayout("number")
        assertNotNull("面板段未装载进缓存", number)
        assertTrue(number!!.columns)
        assertEquals(listOf(listOf("+", "-"), listOf("back")), number.left)
        assertEquals(listOf(listOf("1", "2", "3")), number.rows)
        assertEquals(listOf(listOf("enter")), number.right)
        // 手势：custom 覆盖同 id 键（整体替换，与主键盘 custom 同语义）
        assertEquals(
            "自定义",
            KeysConfigHelper.getGestureFor("number", "enter", isAsciiMode = false)?.tap?.label,
        )
        // 未被 custom 覆盖的键与段保留内置
        assertEquals(1.4f, KeysConfigHelper.getGestureFor("number", "delete", isAsciiMode = false)?.width)
        assertEquals(listOf(listOf("1", "2")), KeysConfigHelper.getPanelLayout("common_symbols")?.rows)
        assertEquals(
            "返回",
            KeysConfigHelper.getGestureFor("common_symbols", "back", isAsciiMode = false)?.tap?.label,
        )
    }

    @Test
    fun `平板符号面板逐列与 26 键同宽（切换键盘不跳）`() {
        val text = assetXimeYaml()
        val symbolRows = KeysConfigHelper.parsePanelLayoutYamlText(text, "common_symbols_tablet")!!.rows
        val symbolKeys = KeysConfigHelper.parseKeyboardYamlSection(text, "common_symbols_tablet")!!
        val qwertyRows = KeysConfigHelper.parseKeyboardLayoutYamlText(text, "qwerty_tablet")!!
        val qwertyKeys = KeysConfigHelper.parseKeyboardYamlSection(text, "qwerty_tablet")!!
        // 26 键功能键的兜底宽度（与渲染层 functionKeyWidth 的兜底表一致）
        fun qwertyWidth(id: String): Float = qwertyKeys[id]?.width?.takeIf { it > 0f } ?: when (id) {
            "shift", "delete", "shift_left", "shift_right", "caps" -> 1.4f
            "mode_change", "enter" -> 1.2f
            "earth", "comma" -> 0.8f
            "space" -> 3f
            else -> 1f
        }
        fun symbolWidth(id: String): Float = symbolKeys[id]?.width?.takeIf { it > 0f } ?: 1f

        assertEquals("行数应与 26 键一致", qwertyRows.size, symbolRows.size)
        for ((index, row) in symbolRows.withIndex()) {
            val qrow = qwertyRows[index]
            assertEquals("第 ${index + 1} 行键数应与 26 键一致", qrow.size, row.size)
            val symbolSum = row.sumOf { symbolWidth(it).toDouble() }
            val qwertySum = qrow.sumOf { qwertyWidth(it).toDouble() }
            assertEquals(
                "第 ${index + 1} 行总权重应与 26 键一致，否则整行缩放宽窄不同",
                qwertySum, symbolSum, 0.001,
            )
            for (column in row.indices) {
                assertEquals(
                    "第 ${index + 1} 行第 ${column + 1} 列应与 26 键同宽",
                    qwertyWidth(qrow[column]), symbolWidth(row[column]), 0.001f,
                )
            }
        }
    }

    /** 中文引号必须成对：左双引号与右双引号数量一致，且不出现孤立的右单引号。 */
    private fun assertQuotePair(ids: List<String>, where: String) {
        assertEquals("$where 的 “ 与 ” 必须成对出现", ids.count { it == "“" }, ids.count { it == "”" })
        assertTrue("$where 不应出现孤立的中文右单引号 ’", "’" !in ids)
        assertTrue("$where 不应出现孤立的中文左单引号 ‘", "‘" !in ids)
    }

    /** 一个键绑定表上的全部动作槽位（含长按候选）。 */
    private fun allSlotActions(binding: KeyBinding): List<KeyAction> = buildList {
        binding.tap?.let { add(it) }
        binding.doubleTap?.let { add(it) }
        binding.swipeUp?.let { add(it) }
        binding.swipeDown?.let { add(it) }
        binding.swipeLeft?.let { add(it) }
        binding.swipeRight?.let { add(it) }
        binding.longPress?.values?.let { addAll(it) }
    }
}
