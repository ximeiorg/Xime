package com.kingzcheung.xime.settings

import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import com.kingzcheung.xime.ui.keyboard.isStrokeSchema
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 合并键布局（14/17/18 键）测试：
 * 1. xime.yaml 嵌套 rows 解析（子数组 → 拼接 ID）
 * 2. 前端键位分组与 Rime 方案 xlit 映射串的一致性
 *
 * 三个合并键方案不作为内置方案随包提供，schema 示例文件放在 docs/schemas_examples/
 * （供市场分发/用户导入），此处一致性校验守护示例文件与前端分组不脱节。
 */
class KeyboardMergedLayoutTest {

    // ── 嵌套 rows 解析 ──

    @Test
    fun `扁平 rows 解析不受影响`() {
        val yaml = """
            keyboard:
              qwerty:
                layout:
                  rows:
                    - [q, w, e, r, t, y, u, i, o, p]
                    - [a, s, d, f, g, h, j, k, l]
                    - [z, x, c, v, b, n, m]
        """.trimIndent()
        val rows = KeysConfigHelper.parseKeyboardLayoutYamlText(yaml, "qwerty")
        assertEquals(
            listOf(
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                listOf("z", "x", "c", "v", "b", "n", "m"),
            ),
            rows,
        )
    }

    @Test
    fun `嵌套子数组解析为拼接 ID`() {
        val yaml = """
            keyboard:
              qwerty_14:
                layout:
                  rows:
                    - [[q, w], [e, r], [t, y], [u, i], [o, p]]
                    - [[a, s], [d, f], [g, h], [j, k], [l]]
                    - [[z, x], [c, v], [b, n], [m]]
        """.trimIndent()
        val rows = KeysConfigHelper.parseKeyboardLayoutYamlText(yaml, "qwerty_14")
        assertEquals(
            listOf(
                listOf("qw", "er", "ty", "ui", "op"),
                listOf("as", "df", "gh", "jk", "l"),
                listOf("zx", "cv", "bn", "m"),
            ),
            rows,
        )
    }

    @Test
    fun `单字母与子数组混排行解析`() {
        val yaml = """
            keyboard:
              qwerty_17:
                layout:
                  rows:
                    - [[q, w], [e, r], t, y, [u, i], [o, p]]
                    - z, [x, c], v, [b, n], m
        """.trimIndent()
        val rows = KeysConfigHelper.parseKeyboardLayoutYamlText(yaml, "qwerty_17")
        assertEquals(
            listOf(
                listOf("qw", "er", "t", "y", "ui", "op"),
                listOf("z", "xc", "v", "bn", "m"),
            ),
            rows,
        )
    }

    @Test
    fun `section 缺失返回 null`() {
        assertNull(KeysConfigHelper.parseKeyboardLayoutYamlText("keyboard:\n  t9:\n    side_symbols: []", "qwerty_14"))
    }

    // ── 方案 → section 映射 ──
    // 绑定仅认 schemas 声明，代码不做 id 关键字猜测；单测环境无 Context 未加载
    // xime.yaml，mergedSectionForSchema 恒为 null（全键盘），
    // 声明路径由 resolveMergedSection / parseSchemaBindingsYamlText 用例覆盖。

    @Test
    fun `已声明的方案解析到对应 section`() {
        val bindings = mapOf(
            "pinyin_14jian" to "qwerty_14",
            "pinyin_17jian" to "qwerty_17",
            "pinyin_18jian" to "qwerty_18",
        )
        assertEquals("qwerty_14", KeysConfigHelper.resolveMergedSection("pinyin_14jian", bindings))
        assertEquals("qwerty_17", KeysConfigHelper.resolveMergedSection("pinyin_17jian", bindings))
        assertEquals("qwerty_18", KeysConfigHelper.resolveMergedSection("pinyin_18jian", bindings))
    }

    @Test
    fun `未声明的方案一律全键盘`() {
        assertNull(KeysConfigHelper.mergedSectionForSchema("pinyin_simp"))
        assertNull(KeysConfigHelper.mergedSectionForSchema("t9_pinyin"))
        assertNull(KeysConfigHelper.mergedSectionForSchema("wubi86"))
        assertNull(KeysConfigHelper.mergedSectionForSchema(""))
        // id 含 14jian 关键字但未声明 → 同样全键盘（代码不做关键字猜测）
        assertNull(KeysConfigHelper.mergedSectionForSchema("wanxiang_14jian"))
    }

    // ── schemas 绑定声明 ──

    @Test
    fun `schemas 声明解析为绑定表`() {
        val yaml = """
            keyboard:
              qwerty_14:
                schemas: [pinyin_14jian, my_14jian]
                layout:
                  rows:
                    - [[q, w]]
              qwerty_20:
                schemas:
                  - pinyin_20jian
              t9:
                side_symbols: ["，"]
        """.trimIndent()
        val bindings = KeysConfigHelper.parseSchemaBindingsYamlText(yaml)
        assertEquals("qwerty_14", bindings["pinyin_14jian"])
        assertEquals("qwerty_14", bindings["my_14jian"])
        assertEquals("qwerty_20", bindings["pinyin_20jian"])
        assertEquals(3, bindings.size)
    }

    @Test
    fun `无 schemas 声明的 section 不产生绑定`() {
        val yaml = """
            keyboard:
              qwerty:
                layout:
                  rows:
                    - [q, w]
              t9:
                side_symbols: ["，"]
        """.trimIndent()
        assertTrue(KeysConfigHelper.parseSchemaBindingsYamlText(yaml).isEmpty())
    }

    @Test
    fun `绑定声明是唯一来源不做关键字猜测`() {
        val bindings = mapOf("my_layout" to "qwerty_20")
        // 已声明的方案按绑定走，即使 id 不含任何关键字
        assertEquals("qwerty_20", KeysConfigHelper.resolveMergedSection("my_layout", bindings))
        // 未声明 → null（全键盘），即使 id 含 14jian/t9 关键字
        assertNull(KeysConfigHelper.resolveMergedSection("wanxiang_14jian", bindings))
        assertNull(KeysConfigHelper.resolveMergedSection("t9_pinyin", bindings))
    }

    @Test
    fun `九键笔画手写判断无代码硬编码`() {
        // 单测环境未加载绑定（无 Context），任何 id（含内置 t9_pinyin/stroke/handwriting）
        // 都不应被识别为九键/笔画/手写——识别只能来自 schemas 声明
        assertFalse(isT9Schema("t9_pinyin"))
        assertFalse(isT9Schema("wanxiang_t9"))
        assertFalse(isStrokeSchema("stroke"))
        assertFalse(isHandwritingSchema("handwriting"))
        assertFalse(isT9Schema(""))
    }

    @Test
    fun `内置 xime yaml 三个布局均声明 schemas 绑定`() {
        val bindings = KeysConfigHelper.parseSchemaBindingsYamlText(ximeYamlText())
        assertEquals("qwerty_14", bindings["pinyin_14jian"])
        assertEquals("qwerty_17", bindings["pinyin_17jian"])
        assertEquals("qwerty_18", bindings["pinyin_18jian"])
    }

    @Test
    fun `t9 stroke handwriting 的绑定不进入合并键行布局`() {
        val bindings = mapOf(
            "t9_pinyin" to "t9",
            "stroke" to "stroke",
            "handwriting" to "handwriting",
            "pinyin_14jian" to "qwerty_14",
        )
        assertNull(KeysConfigHelper.resolveMergedSection("t9_pinyin", bindings))
        assertNull(KeysConfigHelper.resolveMergedSection("stroke", bindings))
        assertNull(KeysConfigHelper.resolveMergedSection("handwriting", bindings))
        assertEquals("qwerty_14", KeysConfigHelper.resolveMergedSection("pinyin_14jian", bindings))
    }

    @Test
    fun `内置 xime yaml 声明 t9 stroke handwriting 绑定`() {
        val bindings = KeysConfigHelper.parseSchemaBindingsYamlText(ximeYamlText())
        assertEquals("t9", bindings["t9_pinyin"])
        assertEquals("t9", bindings["t9"])
        assertEquals("t9", bindings["wanxiang_t9"])
        assertEquals("stroke", bindings["stroke"])
        assertEquals("handwriting", bindings["handwriting"])
    }

    // ── 前端分组 ↔ Rime xlit 映射一致性 ──

    /** 定位仓库内文件（单测 workingDir 可能是模块目录或仓库根目录，逐级向上查找）。 */
    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it }
            File(dir, "app/$rel").takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        error("file not found: $rel")
    }

    private fun ximeYamlText(): String =
        repoFile("src/main/assets/xime.yaml").readText()

    private fun schemaExampleText(schemaId: String): String =
        repoFile("docs/schemas_examples/$schemaId.schema.yaml").readText()

    /** 从合并键方案 schema 提取 xlit 映射：字母 → 代表字母。 */
    private fun xlitMap(schemaText: String): Map<Char, Char> {
        val xlit = Regex("""xlit/([A-Z]{26})/([a-z]{26})/""").find(schemaText)
            ?: error("xlit missing in schema")
        val (source, target) = xlit.destructured
        assertEquals("xlit 源串应为 26 个大写字母", "QWERTYUIOPASDFGHJKLZXCVBNM", source)
        assertEquals("xlit 目标串长度应为 26", 26, target.length)
        return source.zip(target).toMap().mapKeys { it.key.lowercaseChar() }
    }

    /** 行布局里的合并键 id：过滤掉功能键（shift / delete / 控制行不参与 xlit 分组）。 */
    private fun mergeIds(rows: List<List<String>>): List<String> =
        rows.flatten().filter { it !in KeysConfigHelper.FUNCTION_KEY_IDS }

    /** 从行布局推导合并分组：每个字母 → 所在组 ID 的首字母（代表字母）。 */
    private fun representativeMap(ids: List<String>): Map<Char, Char> =
        ids.flatMap { id -> id.map { it to id.first() } }.toMap()

    @Test
    fun `14键前端分组与 Rime xlit 映射一致`() {
        val rows = KeysConfigHelper.parseKeyboardLayoutYamlText(ximeYamlText(), "qwerty_14")
            ?: error("xime.yaml 缺少 qwerty_14 rows")
        val ids = mergeIds(rows)
        assertEquals(14, ids.size)
        assertEquals(representativeMap(ids), xlitMap(schemaExampleText("pinyin_14jian")))
    }

    @Test
    fun `17键前端分组与 Rime xlit 映射一致`() {
        val rows = KeysConfigHelper.parseKeyboardLayoutYamlText(ximeYamlText(), "qwerty_17")
            ?: error("xime.yaml 缺少 qwerty_17 rows")
        val ids = mergeIds(rows)
        assertEquals(17, ids.size)
        assertEquals(representativeMap(ids), xlitMap(schemaExampleText("pinyin_17jian")))
    }

    @Test
    fun `18键前端分组与 Rime xlit 映射一致`() {
        val rows = KeysConfigHelper.parseKeyboardLayoutYamlText(ximeYamlText(), "qwerty_18")
            ?: error("xime.yaml 缺少 qwerty_18 rows")
        val ids = mergeIds(rows)
        assertEquals(18, ids.size)
        assertEquals(representativeMap(ids), xlitMap(schemaExampleText("pinyin_18jian")))
    }

    @Test
    fun `三个方案的 xlit 映射串与万象参考一致`() {
        assertTrue(
            schemaExampleText("pinyin_14jian")
                .contains("xlit/QWERTYUIOPASDFGHJKLZXCVBNM/qqeettuuooaaddggjjlzzccbbm"),
        )
        assertTrue(
            schemaExampleText("pinyin_17jian")
                .contains("xlit/QWERTYUIOPASDFGHJKLZXCVBNM/qwwrryyiooassffhjjlzxxvbbm"),
        )
        assertTrue(
            schemaExampleText("pinyin_18jian")
                .contains("xlit/QWERTYUIOPASDFGHJKLZXCVBNM/qwwrryuiipassffhjjlzxxvbbm"),
        )
    }

    // ── 手势配置完整性 ──

    @Test
    fun `内置 qwerty rows 含功能键且与 keys 对应`() {
        val yaml = ximeYamlText()
        val rows = KeysConfigHelper.parseKeyboardLayoutYamlText(yaml, "qwerty")
            ?: error("xime.yaml 缺少 qwerty rows")
        val keys = KeysConfigHelper.parseKeyboardYamlSection(yaml, "qwerty")
            ?: error("xime.yaml 缺少 qwerty keys")
        val flat = rows.flatten()
        // 第 3 行两端：shift / delete
        assertTrue("rows 应含 shift", flat.contains("shift"))
        assertTrue("rows 应含 delete", flat.contains("delete"))
        // 第 4 行控制行
        for (id in listOf("mode_change", "space", "earth", "enter")) {
            assertTrue("rows 应含控制键 $id", flat.contains(id))
        }
        // 逗号键（配置驱动）仍在 keys 中且为功能键 id
        assertTrue("FUNCTION_KEY_IDS 应含 comma", "comma" in KeysConfigHelper.FUNCTION_KEY_IDS)
        assertTrue("keys 应含 comma 配置", keys.containsKey("comma"))
    }

    @Test
    fun `docs 配置示例若定义 rows 必须含功能键`() {
        val dir = repoFile("docs/config_examples")
        val files = dir.listFiles { f -> f.isDirectory }
            ?.map { File(it, "xime.custom.yaml") }
            ?.filter { it.exists() }
            ?: emptyList()
        assertTrue("应找到示例配置", files.isNotEmpty())
        for (f in files) {
            // 仅校验显式定义了 layout.rows 的示例（未定义的会继承内置 rows，无需校验）
            val rows = KeysConfigHelper.parseKeyboardLayoutYamlText(f.readText(), "qwerty") ?: continue
            val flat = rows.flatten()
            for (id in listOf("shift", "delete", "mode_change", "comma", "space", "earth", "enter")) {
                assertTrue("${f.parentFile?.name} 的 rows 应含 $id", flat.contains(id))
            }
        }
    }

    @Test
    fun `三个合并键布局都是四行且含功能键`() {
        val yaml = ximeYamlText()
        for (section in listOf("qwerty_14", "qwerty_17", "qwerty_18")) {
            val rows = KeysConfigHelper.parseKeyboardLayoutYamlText(yaml, section)
                ?: error("xime.yaml 缺少 $section rows")
            val keys = KeysConfigHelper.parseKeyboardYamlSection(yaml, section)
                ?: error("xime.yaml 缺少 $section keys")
            val flat = rows.flatten()
            assertEquals("$section 应为 3 字母行 + 1 控制行", 4, rows.size)
            for (id in listOf("shift", "delete", "mode_change", "comma", "space", "earth", "enter")) {
                assertTrue("$section 的 rows 应含 $id", flat.contains(id))
            }
            // 合并键段不继承 qwerty 段的手势（parseGesturesSection 只读本段 keys），
            // 缺定义时这些键渲染不出来：内置布局必须自己写全。
            for (id in listOf("shift", "delete", "mode_change", "space", "enter")) {
                assertTrue("$section 的 keys 应含 $id", keys.containsKey(id))
            }
        }
    }

    @Test
    fun `三个布局每个字母键都有 swipe_up`() {
        for (section in listOf("qwerty_14", "qwerty_17", "qwerty_18")) {
            val yaml = ximeYamlText()
            val rows = KeysConfigHelper.parseKeyboardLayoutYamlText(yaml, section)
                ?: error("xime.yaml 缺少 $section rows")
            val gestures = KeysConfigHelper.parseKeyboardYamlSection(yaml, section)
                ?: error("xime.yaml 缺少 $section keys")
            for (key in mergeIds(rows)) {
                val swipeUp = gestures[key.lowercase()]?.swipeUp
                assertTrue(
                    "$section 的按键 $key 缺少 swipe_up 配置",
                    swipeUp != null && (swipeUp.label.isNotEmpty() || swipeUp.value.isNotEmpty()),
                )
            }
        }
    }
}
