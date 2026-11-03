package com.kingzcheung.xime.settings

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for the pure parts of the dependency-completion engine
 * ([RimeDependencyResolver.scanSchemaRefs], [RimeDependencyResolver.expectedFiles],
 * [RimeDependencyResolver.completeCore]). Fixtures mirror real Rime schema/dict files.
 */
class RimeDependencyResolverTest {

    // 与内置 wubi86_pinyin.schema.yaml 结构一致（五笔反查拼音）
    private val wubi86PinyinSchema = """
        schema:
          schema_id: wubi86_pinyin
          dependencies:
            - pinyin_simp
        translator:
          dictionary: wubi86
        reverse_lookup:
          dictionary: pinyin_simp
          tips: "〔拼音〕"
        punctuator:
          import_preset: default
        key_binder:
          import_preset: default
    """.trimIndent()

    // 与内置 pinyin_simp.schema.yaml 结构一致（拼音反查笔画）
    private val pinyinSimpSchema = """
        schema:
          schema_id: pinyin_simp
          dependencies:
            - stroke
        translator:
          dictionary: pinyin_simp
        reverse_lookup:
          dictionary: stroke
          prefix: "`"
        recognizer:
          import_preset: default
    """.trimIndent()

    // 与上游 rime-cangjie 结构一致（跨仓库：反查 luna_pinyin、主词典 import_tables、essay）
    private val cangjieSchema = """
        schema:
          schema_id: cangjie5
          dependencies:
            - luna_quanpin
        translator:
          dictionary: cangjie5
        reverse_lookup:
          dictionary: luna_pinyin
          prism: luna_quanpin
        punctuator:
          import_preset: symbols
        key_binder:
          import_preset: default
    """.trimIndent()

    private val cangjieDict = """
        ---
        name: "cangjie5"
        version: "1.0"
        sort: by_weight
        use_preset_vocabulary: true
        import_tables:
          - cangjie5.base
          - cangjie5.stem
        ...
    """.trimIndent()

    @Test
    fun `scan wubi86_pinyin refs`() {
        val refs = RimeDependencyResolver.scanSchemaRefs(wubi86PinyinSchema, null)
        assertEquals(setOf("wubi86"), refs.mainDicts)
        assertEquals(setOf("pinyin_simp"), refs.reverseDicts)
        assertEquals(setOf("default"), refs.presets)
        assertEquals(setOf("pinyin_simp"), refs.schemaDeps)
        assertEquals(false, refs.needsEssay)
    }

    @Test
    fun `scan pinyin_simp refs`() {
        val refs = RimeDependencyResolver.scanSchemaRefs(pinyinSimpSchema, null)
        assertEquals(setOf("pinyin_simp"), refs.mainDicts)
        assertEquals(setOf("stroke"), refs.reverseDicts)
        assertEquals(setOf("stroke"), refs.schemaDeps)
    }

    @Test
    fun `scan cangjie refs combines schema and dict`() {
        val refs = RimeDependencyResolver.scanSchemaRefs(cangjieSchema, cangjieDict)
        assertEquals(setOf("cangjie5", "cangjie5.base", "cangjie5.stem"), refs.mainDicts)
        assertEquals(setOf("luna_pinyin"), refs.reverseDicts)
        assertEquals(setOf("symbols", "default"), refs.presets)
        assertEquals(setOf("luna_quanpin"), refs.schemaDeps)
        assertTrue("use_preset_vocabulary true → essay", refs.needsEssay)
    }

    @Test
    fun `scan strips inline comments from dependency values`() {
        val schema = """
            schema:
              schema_id: wubi86_trad
              dependencies:
                - pinyin_simp  # 反查使用的拼音词库
            translator:
              dictionary: wubi86  # 主词典
            reverse_lookup:
              dictionary: pinyin_simp  # 反查使用的拼音词库
            punctuator:
              import_preset: default  # 共享预设
        """.trimIndent()
        val refs = RimeDependencyResolver.scanSchemaRefs(schema, null)
        assertEquals(setOf("wubi86"), refs.mainDicts)
        assertEquals(setOf("pinyin_simp"), refs.reverseDicts)
        assertEquals(setOf("default"), refs.presets)
        assertEquals(setOf("pinyin_simp"), refs.schemaDeps)
    }

    @Test
    fun `scan strips inline comments from dictionary lists and essay flag`() {
        val dict = """
            ---
            name: "cangjie5"
            use_preset_vocabulary: true  # 使用预设词库
            import_tables:
              - cangjie5.base  # 基础词库
              - cangjie5.stem  # 词干
            ...
        """.trimIndent()
        val refs = RimeDependencyResolver.scanSchemaRefs("", dict)
        assertEquals(setOf("cangjie5.base", "cangjie5.stem"), refs.mainDicts)
        assertTrue(refs.needsEssay)
    }

    @Test
    fun `expectedFiles maps names to filenames`() {
        val refs = SchemaRefs(
            mainDicts = setOf("cangjie5", "cangjie5.base"),
            reverseDicts = setOf("luna_pinyin"),
            presets = setOf("symbols"),
            needsEssay = true,
        )
        val files = RimeDependencyResolver.expectedFiles(refs)
        assertEquals(
            setOf(
                "cangjie5.dict.yaml", "cangjie5.base.dict.yaml",
                "luna_pinyin.dict.yaml", "symbols.yaml", "essay.txt",
            ),
            files,
        )
    }

    @Test
    fun `completeCore downloads all top deps`() = runTest {
        val got = mutableListOf<String>()
        val out = RimeDependencyResolver.completeCore(
            topDeps = listOf("a", "b"),
            resolveUrl = { "https://x/$it.zip" },
            download = { url -> got.add(url); true },
            readPkgDeps = { emptyList() },
        )
        assertEquals(listOf("a", "b"), out.downloaded)
        assertTrue(out.failed.isEmpty() && out.unresolved.isEmpty())
        assertEquals(listOf("https://x/a.zip", "https://x/b.zip"), got)
    }

    @Test
    fun `completeCore follows transitive deps`() = runTest {
        val out = RimeDependencyResolver.completeCore(
            topDeps = listOf("cangjie"),
            resolveUrl = { "https://x/$it.zip" },
            download = { true },
            readPkgDeps = { id -> if (id == "cangjie") listOf("luna") else emptyList() },
        )
        assertEquals(listOf("cangjie", "luna"), out.downloaded)
    }

    @Test
    fun `completeCore dedups cycles`() = runTest {
        val out = RimeDependencyResolver.completeCore(
            topDeps = listOf("a", "a"),
            resolveUrl = { "https://x/$it.zip" },
            download = { true },
            readPkgDeps = { id -> if (id == "a") listOf("a") else emptyList() },
        )
        assertEquals(listOf("a"), out.downloaded)
    }

    @Test
    fun `completeCore reports unresolved when no url`() = runTest {
        val out = RimeDependencyResolver.completeCore(
            topDeps = listOf("a", "b"),
            resolveUrl = { id -> if (id == "a") "https://x/a.zip" else null },
            download = { true },
            readPkgDeps = { emptyList() },
        )
        assertEquals(listOf("a"), out.downloaded)
        assertEquals(listOf("b"), out.unresolved)
    }

    @Test
    fun `completeCore reports failed downloads`() = runTest {
        val out = RimeDependencyResolver.completeCore(
            topDeps = listOf("a", "b"),
            resolveUrl = { "https://x/$it.zip" },
            download = { url -> !url.contains("/b.zip") },
            readPkgDeps = { emptyList() },
        )
        assertEquals(listOf("a"), out.downloaded)
        assertEquals(listOf("b"), out.failed)
    }

    // ── 本地优先（local-first）：上游/app 已提供的依赖不重复下载 ──

    @Test
    fun `completeCore skips locally satisfied deps without downloading`() = runTest {
        val fetched = mutableListOf<String>()
        val out = RimeDependencyResolver.completeCore(
            topDeps = listOf("luna_pinyin", "symbols"),
            resolveUrl = { "https://x/$it.zip" },
            download = { url -> fetched.add(url); true },
            readPkgDeps = { emptyList() },
            isLocallySatisfied = { id -> id == "symbols" }, // symbols 由上游/app 提供
        )
        assertEquals("只下缺的 luna_pinyin", listOf("luna_pinyin"), out.downloaded)
        assertEquals("symbols 记为已具备", listOf("symbols"), out.alreadyPresent)
        assertEquals("symbols 不重复抓取", listOf("https://x/luna_pinyin.zip"), fetched)
        assertTrue(out.unresolved.isEmpty() && out.failed.isEmpty())
    }

    @Test
    fun `completeCore still follows transitive deps of a locally satisfied dep`() = runTest {
        val out = RimeDependencyResolver.completeCore(
            topDeps = listOf("a"),
            resolveUrl = { "https://x/$it.zip" },
            download = { true },
            readPkgDeps = { id -> if (id == "a") listOf("b") else emptyList() },
            isLocallySatisfied = { id -> id == "a" }, // a 本地有，但它的传递依赖 b 可能缺
        )
        assertEquals(listOf("a"), out.alreadyPresent)
        assertEquals("传递依赖 b 仍补齐", listOf("b"), out.downloaded)
    }

    @Test
    fun `completeCore locally satisfied dep is not unresolved even without url`() = runTest {
        val out = RimeDependencyResolver.completeCore(
            topDeps = listOf("symbols"),
            resolveUrl = { null }, // 根本没有下载源
            download = { true },
            readPkgDeps = { emptyList() },
            isLocallySatisfied = { true }, // 本地已具备
        )
        assertTrue("本地有 → 不算未获取", out.unresolved.isEmpty())
        assertTrue(out.downloaded.isEmpty())
        assertEquals(listOf("symbols"), out.alreadyPresent)
    }

    @Test
    fun `isDependencyLocallyPresent detects dict and preset files`() {
        val dir = File.createTempFile("rimetest", "").apply { delete(); mkdirs() }
        try {
            assertFalse(RimeDependencyResolver.isDependencyLocallyPresent(dir, "luna_pinyin"))
            File(dir, "luna_pinyin.dict.yaml").writeText("x")
            assertTrue("词典存在 → 已具备", RimeDependencyResolver.isDependencyLocallyPresent(dir, "luna_pinyin"))

            assertFalse(RimeDependencyResolver.isDependencyLocallyPresent(dir, "symbols"))
            File(dir, "symbols.yaml").writeText("x")
            assertTrue("preset 存在 → 已具备", RimeDependencyResolver.isDependencyLocallyPresent(dir, "symbols"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
