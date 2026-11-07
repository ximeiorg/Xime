package com.kingzcheung.xime.settings

import com.kingzcheung.xime.keyboard.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardGestureConfigTest {

    // ── 槽位默认动作 ──

    @Test
    fun `tap 字符串简写默认为 send_rime`() {
        val kc = parse("q: { tap: \"q\" }")["q"]!!
        assertEquals("q", kc.tap!!.label)
        assertEquals(GestureAction.SEND_RIME, kc.tap!!.action)
        assertEquals("q", kc.tap!!.value)
    }

    @Test
    fun `swipe 字符串简写默认为 commit`() {
        val kc = parse("a: { tap: \"a\", swipe_up: \"!\", swipe_left: \"?\" }")["a"]!!
        assertEquals(GestureAction.COMMIT, kc.swipeUp!!.action)
        assertEquals("!", kc.swipeUp!!.value)
        assertEquals(GestureAction.COMMIT, kc.swipeLeft!!.action)
        assertEquals("?", kc.swipeLeft!!.value)
        // 简写不是显式 action，字母区仍需保持按键路由
        assertFalse(kc.swipeUp!!.explicitAction)
        assertFalse(kc.swipeLeft!!.explicitAction)
    }

    @Test
    fun `显式 action 与槽位默认动作可区分`() {
        val kc = parse("""
            a:
              swipe_up: { action: commit, value: "~" }
              swipe_down: { value: "~" }
              tap: "a"
        """.trimIndent())["a"]!!
        assertTrue(kc.swipeUp!!.explicitAction)
        assertEquals(GestureAction.COMMIT, kc.swipeUp!!.action)
        assertFalse(kc.swipeDown!!.explicitAction)
        assertEquals(GestureAction.COMMIT, kc.swipeDown!!.action)
        assertFalse(kc.tap!!.explicitAction)
    }

    @Test
    fun `左右滑对象命令动作解析`() {
        val kc = parse("""
            delete:
              swipe_left: { action: "command", value: "clear_composition" }
              swipe_right: { action: "command", value: "clear_composition" }
        """.trimIndent())["delete"]!!
        assertEquals(GestureAction.COMMAND, kc.swipeLeft!!.action)
        assertEquals("clear_composition", kc.swipeLeft!!.value)
        assertEquals(GestureAction.COMMAND, kc.swipeRight!!.action)
        assertEquals("clear_composition", kc.swipeRight!!.value)
    }

    @Test
    fun `逗号上滑 command symbol 解析为打开符号面板命令`() {
        val kc = parse(
            """comma: { width: 0.8, tap: { label: "，", value: "," }, swipe_up: { label: "符号", action: "command", value: "symbol" } }"""
        )["comma"]!!
        assertEquals("，", kc.tap!!.label)
        assertEquals(",", kc.tap!!.value)
        assertEquals("符号", kc.swipeUp!!.label)
        assertEquals(GestureAction.COMMAND, kc.swipeUp!!.action)
        assertEquals("symbol", kc.swipeUp!!.value)
    }

    @Test
    fun `double_tap 字符串简写默认为 commit`() {
        val kc = parse("a: { tap: \"a\", double_tap: \"A\" }")["a"]!!
        assertEquals(GestureAction.COMMIT, kc.doubleTap!!.action)
        assertEquals("A", kc.doubleTap!!.value)
    }

    // ── 对象格式 ──

    @Test
    fun `对象格式指定 action copy`() {
        val su = parse("""
            c:
              swipe_up: { label: "复制", action: "copy" }
        """.trimIndent())["c"]!!.swipeUp!!
        assertEquals("复制", su.label)
        assertEquals(GestureAction.COPY, su.action)
    }

    @Test
    fun `对象格式省略 action 时取槽位默认`() {
        val tap = parse("""
            "comma":
              tap: { label: "，", value: "," }
        """.trimIndent())["comma"]!!.tap!!
        assertEquals(GestureAction.SEND_RIME, tap.action)
        assertEquals(",", tap.value)
    }

    @Test
    fun `action null 表示无动作`() {
        val sd = parse("s: { swipe_down: { label: \"\", action: null } }")["s"]!!.swipeDown!!
        assertNull(sd.action)
    }

    @Test
    fun `未知 action 不生效`() {
        val sd = parse("s: { swipe_up: { label: \"x\", action: \"no_such\" } }")["s"]!!.swipeUp!!
        assertNull(sd.action)
    }

    // ── display ──

    @Test
    fun `字符串简写 display 默认 both 对象默认 key`() {
        val kc = parse("a: { tap: \"a\", swipe_up: \"@\", swipe_down: { label: \"@\", action: \"commit\" } }")["a"]!!
        assertEquals(DisplayMode.BOTH, kc.swipeUp!!.display)
        assertEquals(DisplayMode.KEY, kc.swipeDown!!.display)
    }

    @Test
    fun `display bubble 解析`() {
        val sd = parse("a: { swipe_down: { label: \"@\", action: \"commit\", display: \"bubble\" } }")["a"]!!.swipeDown!!
        assertEquals(DisplayMode.BUBBLE, sd.display)
    }

    @Test
    fun `bubble 独立于 display 解析`() {
        val a = parse("""a: { swipe_up: { value: "1", display: "key", bubble: false } }""")["a"]!!.swipeUp!!
        assertEquals(DisplayMode.KEY, a.display)
        assertFalse(a.bubble)
        val b = parse("""b: { swipe_up: { value: "2", display: "bubble" } }""")["b"]!!.swipeUp!!
        assertEquals(DisplayMode.BUBBLE, b.display)
        assertTrue(b.bubble)
    }

    // ── icon ──

    @Test
    fun `label 以 @ 开头提取 icon 且 label 置空`() {
        val tap = parse("k: { tap: { label: \"@language\", action: \"toggle_ascii\" } }")["k"]!!.tap!!
        assertEquals("", tap.label)
        assertEquals("language", tap.icon)
    }

    @Test
    fun `字符串简写 @label 也提取 icon`() {
        val tap = parse("k: { tap: \"@language\" }")["k"]!!.tap!!
        assertEquals("", tap.label)
        assertEquals("language", tap.icon)
        assertEquals("@language", tap.value)
    }

    // ── long_press ──

    @Test
    fun `long_press 缺省 display 为 bubble`() {
        val lp = parse("""a: { long_press: { values: ["a", "A", "à"] } }""")["a"]!!.longPress!!
        assertEquals(DisplayMode.BUBBLE, lp.display)
        assertEquals(3, lp.values.size)
        assertEquals("a", lp.values[0].value)
        assertEquals(GestureAction.COMMIT, lp.values[0].action)
        assertEquals("à", lp.values[2].value)
    }

    @Test
    fun `long_press display key 回退为气泡（键面绘制未实现）`() {
        val lp = parse("""q: { long_press: { display: "key", values: ["q", "Q"] } }""")["q"]!!.longPress!!
        assertEquals(DisplayMode.BUBBLE, lp.display)
        assertEquals(2, lp.values.size)
    }

    @Test
    fun `label 写成数组按多行合并`() {
        val kb = parse("""q: { swipe_down: { label: ["q", "Q", "9"], action: "none" } }""")["q"]!!
        assertEquals("q\nQ\n9", kb.swipeDown!!.label)
    }

    @Test
    fun `icon 字段与 label 的 @ 前缀等价`() {
        val byField = parse("""q: { swipe_up: { icon: "mic", action: "voice" } }""")["q"]!!.swipeUp!!
        assertEquals("mic", byField.icon)
        assertEquals("", byField.label)
        val byLabel = parse("""x: { swipe_up: { label: "@mic", action: "voice" } }""")["x"]!!.swipeUp!!
        assertEquals("mic", byLabel.icon)
        assertEquals("", byLabel.label)
        // 显式 icon 优先，普通 label 原样保留
        val both = parse("""y: { swipe_up: { icon: "emoji", label: "表情" } }""")["y"]!!.swipeUp!!
        assertEquals("emoji", both.icon)
        assertEquals("表情", both.label)
    }

    @Test
    fun `未知字段被忽略且不影响其余字段`() {
        val kb = parse("""q: { swipe_up: { swip_up: "1", action: "commit", value: "1" } }""")["q"]!!
        assertEquals(GestureAction.COMMIT, kb.swipeUp!!.action)
        assertEquals("1", kb.swipeUp!!.value)
    }

    @Test
    fun `long_press 单动作写成单元素列表`() {
        val lp = parse("delete: { long_press: { values: [{ action: \"delete\" }] } }")["delete"]!!.longPress!!
        assertEquals(1, lp.values.size)
        assertEquals(GestureAction.DELETE, lp.values[0].action)
    }

    @Test
    fun `已移除的死字段被静默忽略`() {
        // when_composing / sticky / repeat 已从 schema 移除：写入配置不影响其它字段解析
        val kc = parse("""q: { sticky: true, swipe_up: { value: "1", repeat: true }, when_composing: { tap: "a" } }""")["q"]!!
        assertEquals("1", kc.swipeUp!!.value)
    }

    @Test
    fun `long_press 多值冒泡`() {
        val lp = parse("m: { long_press: { values: [{ label: \"number\", action: \"command\", value: \"mode_change_number\" }, { label: \"symbol\", action: \"command\", value: \"mode_change_common_symbol\" }] } }")["m"]!!.longPress!!
        assertEquals(2, lp.values.size)
        assertEquals("number", lp.values[0].label)
        assertEquals("mode_change_number", lp.values[0].value)
    }

    @Test
    fun `long_press 数组简写已不再支持`() {
        val lp = parse("""a: { long_press: ["a", "A"] }""")["a"]!!.longPress
        assertNull(lp)
    }

    @Test
    fun `long_press 显示项无 label 时回退 value`() {
        val lp = parse("""a: { long_press: { values: [{ value: "，" }, { label: "。", value: "。" }] } }""")["a"]!!.longPress!!
        assertEquals(listOf("，", "。"), KeysConfigHelper.longPressDisplayItems(lp))
    }

    @Test
    fun `long_press 显示项与动作映射的键完全一致`() {
        val lp = parse("""a: { long_press: { values: [{ value: "，" }, { label: "复制", action: "copy" }] } }""")["a"]!!.longPress!!
        val map = KeysConfigHelper.longPressActionMap(lp)!!
        assertEquals(setOf("，", "复制"), map.keys)
        assertEquals(GestureAction.COMMIT, map["，"]!!.action)
        assertEquals(GestureAction.COPY, map["复制"]!!.action)
    }

    @Test
    fun `long_press 显示项与查找键都为空时该项被丢弃`() {
        val lp = parse("""a: { long_press: { values: [{ action: "copy" }, { label: "复制", action: "copy" }] } }""")["a"]!!.longPress!!
        assertEquals(listOf("复制"), KeysConfigHelper.longPressDisplayItems(lp))
        assertEquals(setOf("复制"), KeysConfigHelper.longPressActionMap(lp)!!.keys)
    }

    @Test
    fun `long_press 超过 10 项时截断为 10 项`() {
        val items = (1..12).joinToString(", ") { """{ value: "$it" }""" }
        val lp = parse("""a: { long_press: { values: [$items] } }""")["a"]!!.longPress!!
        assertEquals(10, lp.values.size)
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"),
            KeysConfigHelper.longPressDisplayItems(lp),
        )
    }

    @Test
    fun `long_press 单项 bubble false 时不弹气泡`() {
        val lp = parse("""a: { long_press: { values: [{ label: "语音", action: "voice", bubble: false }] } }""")["a"]!!.longPress!!
        assertEquals(1, lp.values.size)
        assertFalse(lp.values[0].bubble)
        assertNull(KeysConfigHelper.longPressDisplayItems(lp))
        assertTrue(KeysConfigHelper.longPressActionMap(lp)!!.isEmpty())
    }

    @Test
    fun `long_press 多项时 bubble false 被忽略`() {
        val lp = parse("""a: { long_press: { values: [{ value: "，", bubble: false }, { value: "。" }] } }""")["a"]!!.longPress!!
        assertEquals(listOf("，", "。"), KeysConfigHelper.longPressDisplayItems(lp))
        assertEquals(setOf("，", "。"), KeysConfigHelper.longPressActionMap(lp)!!.keys)
    }

    @Test
    fun `long_press 可直写单个 action 与 value`() {
        val lp = parse("""a: { long_press: { action: "commit", value: "A" } }""")["a"]!!.longPress!!
        assertEquals(1, lp.values.size)
        assertEquals(GestureAction.COMMIT, lp.values[0].action)
        assertEquals("A", lp.values[0].value)
        assertEquals(listOf("A"), KeysConfigHelper.longPressDisplayItems(lp))
    }

    @Test
    fun `long_press 直写且 bubble false 时不弹气泡`() {
        val lp = parse("""a: { long_press: { action: "voice", bubble: false } }""")["a"]!!.longPress!!
        assertEquals(GestureAction.VOICE, lp.values[0].action)
        assertNull(KeysConfigHelper.longPressDisplayItems(lp))
    }

    @Test
    fun `long_press 空对象不生效`() {
        assertNull(parse("""a: { long_press: {} }""")["a"]!!.longPress)
    }

    @Test
    fun `键级 width 解析`() {
        val withWidth = parse("""enter: { tap: "enter", width: 1.2 }""")["enter"]!!
        assertEquals(1.2f, withWidth.width!!, 0.001f)
        assertNull(parse("""enter: { tap: "enter" }""")["enter"]!!.width)
    }

    // ── actions 预设 ──

    @Test
    fun `use 引用动作预设`() {
        val presets = KeysConfigHelper.parseKeyboardActionsYamlText(
            """
            keyboard:
              actions:
                switch_num: { action: "command", value: "mode_change_number" }
            """.trimIndent()
        )
        assertEquals("mode_change_number", presets["switch_num"]!!.value)
        val kc = parse("m: { tap: { use: \"switch_num\" } }", presets = presets)["m"]!!
        assertEquals(GestureAction.COMMAND, kc.tap!!.action)
        assertEquals("mode_change_number", kc.tap!!.value)
    }

    @Test
    fun `use 引用未知预设不生效`() {
        val kc = parse("m: { tap: { use: \"missing\" } }")["m"]!!
        assertNull(kc.tap!!.action)
    }

    // ── section 独立性 ──

    @Test
    fun `qwerty 与 qwerty_en 独立读取`() {
        val yaml = """
            keyboard:
              qwerty:
                keys:
                  earth: { tap: { label: "英", action: "toggle_ascii" } }
              qwerty_en:
                keys:
                  earth: { tap: { label: "中", action: "toggle_ascii" } }
        """.trimIndent()
        val zh = KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty")!!
        val en = KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty_en")!!
        assertEquals("英", zh["earth"]!!.tap!!.label)
        assertEquals("中", en["earth"]!!.tap!!.label)
        assertNotEquals(zh["earth"]!!.tap!!.label, en["earth"]!!.tap!!.label)
    }

    @Test
    fun `缺失键不影响其它键`() {
        val yaml = """
            keyboard:
              qwerty:
                keys:
                  q: { tap: "q" }
                  w: { tap: "w" }
        """.trimIndent()
        val zh = KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty")!!
        assertEquals(2, zh.size)
        assertNotNull(zh["w"])
        assertNull(zh["z"])
    }

    // ── 上滑默认值回退（旧默认符号表只在未配置 swipe_up 时生效） ──

    @Test
    fun `未配置 swipe_up 的键回退旧默认表`() {
        KeysConfigHelper.setKeyGestureConfigForTest(emptyMap())
        assertEquals("1", KeysConfigHelper.getSwipeUpCommitValue("q"))
        assertEquals("1", KeysConfigHelper.getSwipeUpLabel("q"))
    }

    @Test
    fun `配置了 swipe_up 但无 label 与 value 时不再回退旧默认表`() {
        KeysConfigHelper.setKeyGestureConfigForTest(
            mapOf("q" to KeyBinding(swipeUp = KeyAction(action = GestureAction.COPY)))
        )
        assertNull(KeysConfigHelper.getSwipeUpCommitValue("q"))
        assertNull(KeysConfigHelper.getSwipeUpLabel("q"))
        // 复位，避免影响其它用例
        KeysConfigHelper.setKeyGestureConfigForTest(emptyMap())
    }

    // ── 按压气泡开关（tap.bubble，默认 true） ──

    @Test
    fun `tap 的 bubble 默认开启`() {
        assertTrue(parse("q: { tap: \"q\" }")["q"]!!.tap!!.bubble)
        assertTrue(parse("q: { tap: { value: \"q\", label: \"q\" } }")["q"]!!.tap!!.bubble)
    }

    @Test
    fun `tap 的 bubble 可关闭且不影响其它字段`() {
        val tap = parse("q: { tap: { label: \"q\", value: \"q\", bubble: false } }")["q"]!!.tap!!
        assertFalse(tap.bubble)
        assertEquals("q", tap.label)
        assertEquals("q", tap.value)
        assertEquals(GestureAction.SEND_RIME, tap.action)
    }

    @Test
    fun `tap 与 swipe 的气泡开关互相独立`() {
        val binding = parse("q: { tap: { value: \"q\", bubble: false }, swipe_up: { value: \"1\", bubble: true } }")["q"]!!
        assertFalse(binding.tap!!.bubble)
        assertTrue(binding.swipeUp!!.bubble)
    }

    // ── 按键级外观覆盖（corner_radius / background） ──

    @Test
    fun `按键级圆角与背景可解析`() {
        val kc = parse(
            """enter: { tap: "enter", corner_radius: 14, background: 0x9FEF86, background_dark: 0x2F5A2F }"""
        )["enter"]!!
        assertEquals(14, kc.cornerRadius)
        assertEquals(KeyColor.Hex(0x9FEF86L), kc.background)
        assertEquals(KeyColor.Hex(0x2F5A2FL), kc.backgroundDark)
    }

    @Test
    fun `按键级背景支持主题色记号`() {
        assertEquals(KeyColor.Token("accent"), parse("""q: { background: accent }""")["q"]!!.background)
        assertEquals(KeyColor.Token("primary"), parse("""q: { background: theme.primary }""")["q"]!!.background)
        assertEquals(KeyColor.Token("special"), parse("""q: { background: SPECIAL }""")["q"]!!.background)
        assertNull(parse("""q: { background: not-a-color }""")["q"]!!.background)
    }

    @Test
    fun `按键级外观未配置时保持默认`() {
        val kc = parse("""q: { tap: "q" }""")["q"]!!
        assertNull(kc.cornerRadius)
        assertNull(kc.background)
        assertNull(kc.backgroundDark)
    }

    @Test
    fun `按键级背景支持带透明度与非法值忽略`() {
        val kc = parse(
            """q: { background: 0x80FFFFFF, background_dark: "not-a-color", corner_radius: "x" }"""
        )["q"]!!
        assertEquals(KeyColor.Hex(0x80FFFFFFL), kc.background)
        assertNull(kc.backgroundDark)
        assertNull(kc.cornerRadius)
    }

    // ── 外观-only 覆盖的合并规则 ──

    @Test
    fun `只写外观覆盖时保留默认手势`() {
        val default = """
            keyboard:
              qwerty:
                keys:
                  q: { tap: "q", swipe_up: "1", long_press: { display: bubble, values: ["q", "Q"] } }
        """.trimIndent()
        val custom = """
            keyboard:
              qwerty:
                keys:
                  q: { corner_radius: 20, background: 0xFFFF7043 }
        """.trimIndent()
        val (zh, _) = KeysConfigHelper.parseKeyboardGestureTexts(default, custom)!!
        val q = zh["q"]!!
        assertEquals("q", q.tap!!.value)
        assertEquals("1", q.swipeUp!!.value)
        assertEquals(2, q.longPress!!.values.size)
        assertEquals(20, q.cornerRadius)
        assertEquals(KeyColor.Hex(0xFFFF7043L), q.background)
    }

    @Test
    fun `写行为字段时仍整体替换默认手势`() {
        val default = """
            keyboard:
              qwerty:
                keys:
                  q: { tap: "q", swipe_up: "1", long_press: { display: bubble, values: ["q", "Q"] } }
        """.trimIndent()
        val custom = """
            keyboard:
              qwerty:
                keys:
                  q: { tap: "x" }
        """.trimIndent()
        val (zh, _) = KeysConfigHelper.parseKeyboardGestureTexts(default, custom)!!
        val q = zh["q"]!!
        assertEquals("x", q.tap!!.value)
        assertNull(q.swipeUp)
        assertNull(q.longPress)
    }

    // ── 辅助 ──

    private fun parse(
        keysFragment: String,
        section: String = "qwerty",
        presets: Map<String, KeyAction> = emptyMap(),
    ): Map<String, KeyBinding> {
        val indented = keysFragment.lines().joinToString("\n") { if (it.isBlank()) it else "      $it" }
        val yaml = "keyboard:\n  $section:\n    keys:\n$indented"
        return KeysConfigHelper.parseKeyboardYamlSection(yaml, section, presets) ?: emptyMap()
    }
}