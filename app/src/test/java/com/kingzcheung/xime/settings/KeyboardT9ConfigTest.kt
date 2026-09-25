package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardT9ConfigTest {

    private fun parseT9(yamlFragment: String): KeyboardT9Partial? =
        KeysConfigHelper.parseKeyboardT9YamlPartial("keyboard:\n  t9:\n    " + yamlFragment.replace("\n", "\n    "))

    @Test
    fun `解析 side_symbols 列表`() {
        val partial = parseT9("side_symbols:\n    - \"，\"\n    - \"。\"\n    - \"、\"\n    - \"？\"\n    - \"！\"")
        assertEquals(listOf("，", "。", "、", "？", "！"), partial?.sideSymbols)
    }

    @Test
    fun `未配置 side_symbols 返回 null`() {
        val partial = parseT9("other: 1")
        assertNull(partial?.sideSymbols)
    }

    @Test
    fun `keyboard 段缺失返回 null`() {
        assertNull(KeysConfigHelper.parseKeyboardT9YamlPartial("style:\n  dark_mode: 2"))
    }

    @Test
    fun `side_symbols 空列表视为未配置`() {
        val partial = parseT9("  side_symbols: []")
        assertNull(partial?.sideSymbols)
    }

    @Test
    fun `合并 custom 覆盖 builtin`() {
        val merged = KeysConfigHelper.mergeT9Configs(
            custom = KeyboardT9Partial(sideSymbols = listOf("、", "：")),
            builtIn = KeyboardT9Partial(sideSymbols = listOf("，", "。", "？", "！")),
        )
        assertEquals(listOf("、", "："), merged.sideSymbols)
    }

    @Test
    fun `合并 custom 未配置回退 builtin`() {
        val merged = KeysConfigHelper.mergeT9Configs(
            custom = KeyboardT9Partial(),
            builtIn = KeyboardT9Partial(sideSymbols = listOf("，", "。", "？", "！")),
        )
        assertEquals(listOf("，", "。", "？", "！"), merged.sideSymbols)
    }

    @Test
    fun `合并 双方未配置回退内置默认值`() {
        val merged = KeysConfigHelper.mergeT9Configs(custom = null, builtIn = null)
        assertEquals(KeysConfigHelper.DEFAULT_T9_SIDE_SYMBOLS, merged.sideSymbols)
    }

    @Test
    fun `custom 空列表视为未配置回退默认值`() {
        val merged = KeysConfigHelper.mergeT9Configs(
            custom = KeyboardT9Partial(sideSymbols = emptyList()),
            builtIn = null,
        )
        assertEquals(KeysConfigHelper.DEFAULT_T9_SIDE_SYMBOLS, merged.sideSymbols)
    }

    // ── layout（三列布局：left/rows/right）──

    @Test
    fun `解析 layout 三列布局`() {
        val partial = parseT9(
            "layout:\n" +
                "      left: [candidates, symbol]\n" +
                "      rows:\n" +
                "        - [\"1\", \"2\", \"3\"]\n" +
                "        - [number, space, earth]\n" +
                "      right: [delete, clear, enter]"
        )
        val layout = partial?.layout
        assertEquals(listOf("candidates", "symbol"), layout?.left)
        assertEquals(listOf(listOf("1", "2", "3"), listOf("number", "space", "earth")), layout?.rows)
        assertEquals(listOf("delete", "clear", "enter"), layout?.right)
    }

    @Test
    fun `layout 仅配置 rows 时其余字段独立为 null`() {
        val partial = parseT9("layout:\n      rows:\n        - [1, 2, 3]")
        val layout = partial?.layout
        assertEquals(listOf(listOf("1", "2", "3")), layout?.rows)
        assertNull(layout?.left)
        assertNull(layout?.right)
    }

    @Test
    fun `layout rows 支持嵌套合并键与标量行`() {
        val partial = parseT9("layout:\n      rows:\n        - [[q, w], e]\n        - z, x")
        assertEquals(
            listOf(listOf("qw", "e"), listOf("z", "x")),
            partial?.layout?.rows,
        )
    }

    @Test
    fun `layout 空列表视为未配置`() {
        val partial = parseT9("layout:\n      left: []\n      right: []\n      rows: []")
        assertNull(partial?.layout)
    }

    @Test
    fun `未配置 layout 返回 null`() {
        val partial = parseT9("side_symbols:\n    - \"。\"")
        assertNull(partial?.layout)
    }

    @Test
    fun `layout 合并 custom rows 覆盖 left 回退 builtin`() {
        val merged = KeysConfigHelper.mergeT9Layouts(
            custom = KeyboardT9LayoutPartial(rows = listOf(listOf("a", "b", "c"))),
            builtIn = KeyboardT9LayoutPartial(
                left = listOf("candidates"),
                rows = listOf(listOf("1", "2", "3")),
                right = listOf("delete"),
            ),
        )
        assertEquals(listOf("candidates"), merged.left)
        assertEquals(listOf(listOf("a", "b", "c")), merged.rows)
        assertEquals(listOf("delete"), merged.right)
    }

    @Test
    fun `layout 合并双方未配置回退内置默认`() {
        val merged = KeysConfigHelper.mergeT9Layouts(custom = null, builtIn = null)
        assertEquals(KeyboardT9LayoutConfig.DEFAULT_T9_LEFT, merged.left)
        assertEquals(KeyboardT9LayoutConfig.DEFAULT_T9_ROWS, merged.rows)
        assertEquals(KeyboardT9LayoutConfig.DEFAULT_T9_RIGHT, merged.right)
    }

    @Test
    fun `mergeT9Configs 携带布局合并`() {
        val merged = KeysConfigHelper.mergeT9Configs(
            custom = KeyboardT9Partial(layout = KeyboardT9LayoutPartial(left = listOf("candidates"))),
            builtIn = null,
        )
        assertEquals(listOf("candidates"), merged.layout.left)
        assertEquals(KeyboardT9LayoutConfig.DEFAULT_T9_ROWS, merged.layout.rows)
        assertEquals(KeyboardT9LayoutConfig.DEFAULT_T9_RIGHT, merged.layout.right)
    }

    @Test
    fun `t9 keys 支持键面字母与长按候选配置`() {
        val yaml = "keyboard:\n" +
            "  t9:\n" +
            "    keys:\n" +
            "      \"2\": { tap: { label: \"ABC\" }, long_press: { values: [a, b, c] } }"
        val bindings = KeysConfigHelper.parseKeyboardYamlSection(yaml, "t9")
        assertEquals("ABC", bindings?.get("2")?.tap?.label)
        val labels = bindings?.get("2")?.longPress?.values
            ?.map { it.label.ifEmpty { it.value } }
        assertEquals(listOf("a", "b", "c"), labels)
    }

    @Test
    fun `内置 xime yaml 的九键布局与键面默认可解析`() {
        val asset = java.io.File("src/main/assets/xime.yaml")
        if (!asset.exists()) return // 非 app 模块工作目录时跳过
        val text = asset.readText()
        val layout = KeysConfigHelper.parseKeyboardT9YamlPartial(text)?.layout
        assertEquals(KeyboardT9LayoutConfig.DEFAULT_T9_LEFT, layout?.left)
        assertEquals(KeyboardT9LayoutConfig.DEFAULT_T9_ROWS, layout?.rows)
        assertEquals(KeyboardT9LayoutConfig.DEFAULT_T9_RIGHT, layout?.right)
        val bindings = KeysConfigHelper.parseKeyboardYamlSection(text, "t9")!!
        assertEquals("分词", bindings["1"]?.tap?.label)
        assertEquals("ABC", bindings["2"]?.tap?.label)
        assertEquals(
            listOf("A", "B", "C"),
            bindings["2"]?.longPress?.values?.map { it.label.ifEmpty { it.value } },
        )
        // Y/N 单字母不被 YAML 解析为布尔
        assertEquals(
            listOf("W", "X", "Y", "Z"),
            bindings["9"]?.longPress?.values?.map { it.label.ifEmpty { it.value } },
        )
        // 手势内置默认不变：上滑直接输入键面数字
        assertEquals("2", bindings["2"]?.swipeUp?.value)
    }
}
