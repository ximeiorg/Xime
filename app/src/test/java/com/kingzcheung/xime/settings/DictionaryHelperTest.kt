package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for the pure parts of [DictionaryHelper] — the fix that makes the dictionary
 * viewer follow `import_tables` (so schemes like quick5/cangjie5, whose words live in
 * imported tables, no longer show as empty).
 */
class DictionaryHelperTest {

    @Test
    fun `parseDictEntries reads word-code after data marker`() {
        val text = "# comment\n---\nname: x\n...\n日\ta\n曰\ta\n\n# note\n郎\tivnl\n"
        assertEquals(
            listOf(DictEntry("日", "a"), DictEntry("曰", "a"), DictEntry("郎", "ivnl")),
            DictionaryHelper.parseDictEntries(text),
        )
    }

    @Test
    fun `parseDictEntries keeps spaces inside tab separated pinyin code`() {
        val text = "...\n中国\tzhong guo\t100\n你好\tni hao\n"
        assertEquals(
            listOf(DictEntry("中国", "zhong guo", 100), DictEntry("你好", "ni hao")),
            DictionaryHelper.parseDictEntries(text),
        )
    }

    @Test
    fun `parseDictEntries ignores header before the marker`() {
        val text = "name: x\nimport_tables:\n  - foo\n...\n词\tcode\n"
        assertEquals(listOf(DictEntry("词", "code")), DictionaryHelper.parseDictEntries(text))
    }

    @Test
    fun `parseDictEntries empty when no entries (import-only dict like quick5)`() {
        val text = "name: quick5\nuse_preset_vocabulary: true\nimport_tables:\n  - cangjie5.base\n...\n"
        assertTrue(DictionaryHelper.parseDictEntries(text).isEmpty())
    }

    @Test
    fun `parseImportTables block form`() {
        val text = "name: quick5\nimport_tables:\n  - cangjie5.base\n  - quick5.supplement\n...\n词\tc\n"
        assertEquals(
            listOf("cangjie5.base", "quick5.supplement"),
            DictionaryHelper.parseImportTables(text),
        )
    }

    @Test
    fun `parseImportTables inline form`() {
        assertEquals(
            listOf("a", "b", "c"),
            DictionaryHelper.parseImportTables("import_tables: [a, b, c]\n...\n"),
        )
    }

    @Test
    fun `parseImportTables none`() {
        assertTrue(DictionaryHelper.parseImportTables("name: x\n...\n词\tc\n").isEmpty())
    }

    @Test
    fun `collectEntries follows import_tables and merges (quick5 case)`() {
        val files = mapOf(
            "quick5" to "import_tables:\n  - cangjie5.base\n  - quick5.supplement\n...\n",
            "cangjie5.base" to "...\n日\ta\n曰\ta\n",
            "quick5.supplement" to "...\n郎\tivnl\n",
        )
        assertEquals(
            listOf(DictEntry("日", "a"), DictEntry("曰", "a"), DictEntry("郎", "ivnl")),
            DictionaryHelper.collectEntries("quick5") { files[it] },
        )
    }

    @Test
    fun `collectEntries dedups table cycles`() {
        val files = mapOf(
            "a" to "import_tables:\n  - b\n...\n词1\tc1\n",
            "b" to "import_tables:\n  - a\n...\n词2\tc2\n",
        )
        assertEquals(
            listOf(DictEntry("词1", "c1"), DictEntry("词2", "c2")),
            DictionaryHelper.collectEntries("a") { files[it] },
        )
    }

    @Test
    fun `collectEntries skips missing tables gracefully`() {
        val files = mapOf("a" to "import_tables:\n  - missing\n...\n词\tc\n")
        assertEquals(
            listOf(DictEntry("词", "c")),
            DictionaryHelper.collectEntries("a") { files[it] },
        )
    }

    /**
     * 方案 `translator.packs` 声明的个人词库（如 pinyin_simp 的 user_simp）同样是方案里的
     * 静态码表，应与主词典一并展示 —— 这正是「个人词库」页签并入「方案词库」的落点。
     */
    @Test
    fun `collectEntries includes scheme packs as extra roots`() {
        val files = mapOf(
            "wubi86" to "...\n日\ta\n",
            "user_wubi86" to "import_tables:\n  - user_wubi86_extra\n...\n分组词条\twxyt\n",
            "user_wubi86_extra" to "...\n额外\tew\n",
        )
        assertEquals(
            listOf(DictEntry("日", "a"), DictEntry("分组词条", "wxyt"), DictEntry("额外", "ew")),
            DictionaryHelper.collectEntries("wubi86", listOf("user_wubi86")) { files[it] },
        )
    }

    // ── 文本码表（用户词库 userdb 读出的文本 / custom_phrase stabledb 共用解析） ──

    @Test
    fun `parseCodeTable reads word code and optional weight`() {
        val text = "测试\tce shi\n词条\tci tiao\t99\n"
        assertEquals(
            listOf(DictEntry("测试", "ce shi"), DictEntry("词条", "ci tiao", 99)),
            DictionaryHelper.parseCodeTable(text),
        )
    }

    @Test
    fun `parseCodeTable skips comments and blank lines`() {
        val text = "# Rime table\n\n#@/db_type\ttabledb\n测试\tce shi\n"
        assertEquals(listOf(DictEntry("测试", "ce shi")), DictionaryHelper.parseCodeTable(text))
    }

    @Test
    fun `parseCodeTable does not require a data marker`() {
        // 用户词库读出的文本没有头部、没有 `...`，直接就是词条行
        val text = "日\ta\n曰\ta\n郎\tivnl\n"
        assertEquals(
            listOf(DictEntry("日", "a"), DictEntry("曰", "a"), DictEntry("郎", "ivnl")),
            DictionaryHelper.parseCodeTable(text),
        )
    }

    @Test
    fun `parseCodeTable preserves spaces inside code`() {
        val text = "你好\tni hao\n世界\tshi jie\n"
        assertEquals(
            listOf(DictEntry("你好", "ni hao"), DictEntry("世界", "shi jie")),
            DictionaryHelper.parseCodeTable(text),
        )
    }

    @Test
    fun `parseCodeTable skips lines without a tab`() {
        assertEquals(listOf(DictEntry("词", "code")), DictionaryHelper.parseCodeTable("只有词\n词\tcode\n"))
    }

    @Test
    fun `parseCodeTable treats non numeric weight as absent`() {
        assertEquals(listOf(DictEntry("a", "b")), DictionaryHelper.parseCodeTable("a\tb\tx\n"))
    }

    @Test
    fun `parseCodeTable reads negative weight from userdb commits`() {
        // librime userdb 的 value 里 commits 可能为负；这里按原样保留数值
        assertEquals(listOf(DictEntry("a", "b", -3)), DictionaryHelper.parseCodeTable("a\tb\t-3\n"))
    }

    // ── translator.packs（方案自带的个人词库声明） ──

    @Test
    fun `readSchemaPacks returns packs declared in schema`() {
        val rimeDir = createTempDir()
        java.io.File(rimeDir, "pinyin_simp.schema.yaml").writeText("""
translator:
  dictionary: pinyin_simp
  packs:
    - user_simp
    - user_extra
  preedit_format:
    - xform/a/b
""".trimIndent(), Charsets.UTF_8)
        assertEquals(listOf("user_simp", "user_extra"), DictionaryHelper.readSchemaPacks(rimeDir, "pinyin_simp"))
    }

    @Test
    fun `readSchemaPacks returns empty when schema has no packs`() {
        val rimeDir = createTempDir()
        java.io.File(rimeDir, "wubi86.schema.yaml").writeText("translator:\n  dictionary: wubi86\n", Charsets.UTF_8)
        assertTrue(DictionaryHelper.readSchemaPacks(rimeDir, "wubi86").isEmpty())
    }

    @Test
    fun `readSchemaPacks returns empty when schema file missing`() {
        assertTrue(DictionaryHelper.readSchemaPacks(createTempDir(), "nonexistent").isEmpty())
    }

    @Test
    fun `readSchemaPacks parses real pinyin schema structure via kaml`() {
        val rimeDir = createTempDir()
        java.io.File(rimeDir, "pinyin_simp.schema.yaml").writeText("""
schema:
  schema_id: pinyin_simp
  name: 简体拼音
switches:
  - name: ascii_mode
    states: [ 中文, 西文 ]
engine:
  translators:
    - punct_translator
    - script_translator
    - reverse_lookup_translator
    - lua_translator@*uuid
translator:
  dictionary: pinyin_simp
  packs:
    - user_simp
  preedit_format:
    - xform/([nl])v/$1ü/
reverse_lookup:
  dictionary: stroke
  prefix: "`"
punctuator:
  import_preset: default
  __include: symbols:/punctuator
""".trimIndent(), Charsets.UTF_8)
        assertEquals(listOf("user_simp"), DictionaryHelper.readSchemaPacks(rimeDir, "pinyin_simp"))
    }

    @Test
    fun `readSchemaPacks falls back to regex when yaml parse fails`() {
        val rimeDir = createTempDir()
        // 非法 YAML（引用未定义别名），kaml 解析失败后应回退正则
        java.io.File(rimeDir, "bad.schema.yaml").writeText("""
translator:
  dictionary: bad
  packs:
    - user_bad
foo: *undefined_alias
""".trimIndent(), Charsets.UTF_8)
        assertEquals(listOf("user_bad"), DictionaryHelper.readSchemaPacks(rimeDir, "bad"))
    }

    @Test
    fun `readSchemaPacks keeps packs that are not user_ prefixed`() {
        val rimeDir = createTempDir()
        java.io.File(rimeDir, "my.schema.yaml").writeText(
            "translator:\n  dictionary: my\n  packs:\n    - my_extra\n", Charsets.UTF_8
        )
        assertEquals(listOf("my_extra"), DictionaryHelper.readSchemaPacks(rimeDir, "my"))
    }

    private fun createTempDir(): File {
        val dir = File.createTempFile("dictionary_helper_test_dir", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }
}
