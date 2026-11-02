package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FuzzyPinyinManagerTest {

    private fun tempRimeDir(): File =
        File.createTempFile("fuzzy_rime", "").apply {
            delete()
            mkdirs()
        }

    private fun writeSchema(rimeDir: File, id: String, text: String) {
        File(rimeDir, "$id.schema.yaml").writeText(text.trimIndent() + "\n")
    }

    private val pinyinSchema = """
        schema:
          schema_id: pinyin_simp
          name: 简体拼音
        speller:
          alphabet: zyxwvutsrqponmlkjihgfedcba
          algebra:
            - abbrev/^([a-z]).+$/$1/
            - derive/^([jqxy])u/$1v/
        translator:
          dictionary: pinyin_simp
    """

    @Test
    fun `full pinyin schema is supported`() {
        val dir = tempRimeDir()
        try {
            writeSchema(dir, "pinyin_simp", pinyinSchema)
            assertEquals(
                FuzzyPinyinManager.SchemaStatus.SUPPORTED,
                FuzzyPinyinManager.inspectSchema(dir, "pinyin_simp"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `t9 double pinyin wubi and no algebra are skipped`() {
        val dir = tempRimeDir()
        try {
            writeSchema(
                dir, "t9_pinyin",
                """
                schema:
                  name: 拼音九键
                speller:
                  algebra:
                    - derive/[abc]/2/
                """,
            )
            writeSchema(
                dir, "double_pinyin_flypy",
                """
                schema:
                  name: 小鹤双拼
                speller:
                  algebra:
                    - xform/iu$/$1Q/
                """,
            )
            writeSchema(
                dir, "wubi86",
                """
                schema:
                  name: 五笔86
                speller:
                  algebra:
                    - derive/^a/b/
                """,
            )
            writeSchema(
                dir, "pinyin_no_algebra",
                """
                schema:
                  name: 拼音无代数
                speller:
                  alphabet: abcdefghijklmnopqrstuvwxyz
                """,
            )
            assertEquals(FuzzyPinyinManager.SchemaStatus.T9, FuzzyPinyinManager.inspectSchema(dir, "t9_pinyin"))
            assertEquals(
                FuzzyPinyinManager.SchemaStatus.DOUBLE_PINYIN,
                FuzzyPinyinManager.inspectSchema(dir, "double_pinyin_flypy"),
            )
            assertEquals(FuzzyPinyinManager.SchemaStatus.SHAPE, FuzzyPinyinManager.inspectSchema(dir, "wubi86"))
            assertEquals(
                FuzzyPinyinManager.SchemaStatus.NO_ALGEBRA,
                FuzzyPinyinManager.inspectSchema(dir, "pinyin_no_algebra"),
            )
            assertEquals(
                FuzzyPinyinManager.SchemaStatus.NO_SCHEMA,
                FuzzyPinyinManager.inspectSchema(dir, "missing"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `unknown schema with algebra is not treated as pinyin`() {
        val dir = tempRimeDir()
        try {
            writeSchema(
                dir, "custom_shape",
                """
                schema:
                  name: 自定义形码
                speller:
                  algebra:
                    - derive/^a/b/
                """,
            )
            assertEquals(
                FuzzyPinyinManager.SchemaStatus.NOT_PINYIN,
                FuzzyPinyinManager.inspectSchema(dir, "custom_shape"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `reconcile writes only enabled supported schemas and removes on clear`() {
        val dir = tempRimeDir()
        try {
            writeSchema(dir, "pinyin_simp", pinyinSchema)
            writeSchema(
                dir, "wubi86",
                """
                schema:
                  name: 五笔86
                speller:
                  algebra:
                    - derive/^a/b/
                """,
            )

            val first = FuzzyPinyinManager.reconcile(
                rimeDir = dir,
                enabledSchemas = setOf("pinyin_simp", "wubi86"),
                selectedRules = setOf("z_zh"),
            )
            assertTrue(first.changedSchemas.contains("pinyin_simp"))
            assertFalse(first.changedSchemas.contains("wubi86"))
            assertTrue(File(dir, "pinyin_simp.custom.yaml").isFile)
            assertFalse(File(dir, "wubi86.custom.yaml").exists())
            assertEquals(listOf("pinyin_simp"), first.appliedSchemas)

            val cleared = FuzzyPinyinManager.reconcile(
                rimeDir = dir,
                enabledSchemas = setOf("pinyin_simp", "wubi86"),
                selectedRules = emptySet(),
            )
            assertTrue(cleared.changedSchemas.contains("pinyin_simp"))
            assertFalse(File(dir, "pinyin_simp.custom.yaml").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `reconcile leaves disabled schemas untouched`() {
        val dir = tempRimeDir()
        try {
            writeSchema(dir, "pinyin_simp", pinyinSchema)
            val result = FuzzyPinyinManager.reconcile(
                rimeDir = dir,
                enabledSchemas = emptySet(),
                selectedRules = setOf("z_zh"),
            )
            assertFalse(File(dir, "pinyin_simp.custom.yaml").exists())
            assertTrue(result.changedSchemas.isEmpty())
            assertTrue(result.appliedSchemas.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `updateCustomFile creates and removes the managed block`() {
        val dir = tempRimeDir()
        try {
            val file = File(dir, "pinyin_simp.custom.yaml")
            val patterns = FuzzyPinyinRules.patternsFor(setOf("z_zh"))
            val created = FuzzyPinyinManager.updateCustomFile(file, patterns)
            assertTrue(created.changed)
            assertTrue(file.readText().contains(FuzzyPinyinPatch.BEGIN))

            val removed = FuzzyPinyinManager.updateCustomFile(file, emptyList())
            assertTrue(removed.changed)
            assertFalse(file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `updateCustomFile leaves unrelated user content alone`() {
        val dir = tempRimeDir()
        try {
            val file = File(dir, "pinyin_simp.custom.yaml")
            val text = "patch:\n  \"engine/translators/+\":\n    - table_translator@custom_phrase\n"
            file.writeText(text)
            val result = FuzzyPinyinManager.updateCustomFile(file, emptyList())
            assertFalse(result.changed)
            assertEquals(text, file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `updateCustomFile reports user algebra conflict`() {
        val dir = tempRimeDir()
        try {
            val file = File(dir, "pinyin_simp.custom.yaml")
            val text = "patch:\n  \"speller/algebra/+\":\n    - derive/^foo$/bar/\n"
            file.writeText(text)
            val result = FuzzyPinyinManager.updateCustomFile(
                file,
                FuzzyPinyinRules.patternsFor(setOf("z_zh")),
            )
            assertTrue(result.conflict)
            assertFalse(result.changed)
            assertEquals(text, file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
