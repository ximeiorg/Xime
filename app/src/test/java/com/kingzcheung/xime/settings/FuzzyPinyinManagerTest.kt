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
    fun `reconcile writes only enabled and declared schemas`() {
        val dir = tempRimeDir()
        try {
            writeSchema(dir, "pinyin_simp", pinyinSchema)
            writeSchema(
                dir, "other_pinyin",
                """
                schema:
                  name: 第三方拼音
                speller:
                  algebra:
                    - derive/^([jqxy])u/$1v/
                """,
            )

            val first = FuzzyPinyinManager.reconcile(
                rimeDir = dir,
                enabledSchemas = setOf("pinyin_simp", "other_pinyin"),
                declaredSchemas = setOf("pinyin_simp"),
                selectedRules = setOf("z_zh"),
            )
            assertEquals(listOf("pinyin_simp"), first.appliedSchemas)
            assertTrue(File(dir, "pinyin_simp.custom.yaml").isFile)
            assertFalse(File(dir, "other_pinyin.custom.yaml").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `undeclared schema is untouched even if it looks like full pinyin`() {
        val dir = tempRimeDir()
        try {
            writeSchema(dir, "pinyin_simp", pinyinSchema)
            // 名字、字母表、代数都像全拼，但没在 fuzzy_pinyin.schemas 声明：按约定不猜。
            writeSchema(
                dir, "third_party_quanpin",
                """
                schema:
                  name: 某全拼
                speller:
                  alphabet: zyxwvutsrqponmlkjihgfedcba
                  algebra:
                    - abbrev/^([a-z]).+$/$1/
                    - derive/^([nl])ue$/$1ve/
                """,
            )

            val result = FuzzyPinyinManager.reconcile(
                rimeDir = dir,
                enabledSchemas = setOf("third_party_quanpin"),
                declaredSchemas = setOf("pinyin_simp"),
                selectedRules = setOf("z_zh"),
            )
            assertTrue(result.appliedSchemas.isEmpty())
            assertTrue(result.changedSchemas.isEmpty())
            assertFalse(File(dir, "third_party_quanpin.custom.yaml").exists())
            assertFalse(File(dir, "pinyin_simp.custom.yaml").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `declared schema is applied regardless of its name`() {
        val dir = tempRimeDir()
        try {
            // 显式声明是权威来源：第三方/自定义命名的全拼方案不必符合任何命名约定。
            writeSchema(
                dir, "my_scheme",
                """
                schema:
                  name: 自用拼音
                speller:
                  algebra:
                    - derive/^([jqxy])u/$1v/
                """,
            )
            val result = FuzzyPinyinManager.reconcile(
                rimeDir = dir,
                enabledSchemas = setOf("my_scheme"),
                declaredSchemas = setOf("my_scheme"),
                selectedRules = setOf("z_zh"),
            )
            assertEquals(listOf("my_scheme"), result.appliedSchemas)
            assertTrue(File(dir, "my_scheme.custom.yaml").readText().contains(FuzzyPinyinPatch.BEGIN))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `reconcile leaves disabled declared schemas untouched`() {
        val dir = tempRimeDir()
        try {
            writeSchema(dir, "pinyin_simp", pinyinSchema)
            val result = FuzzyPinyinManager.reconcile(
                rimeDir = dir,
                enabledSchemas = emptySet(),
                declaredSchemas = setOf("pinyin_simp"),
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
    fun `clearing rules removes the managed block`() {
        val dir = tempRimeDir()
        try {
            writeSchema(dir, "pinyin_simp", pinyinSchema)
            FuzzyPinyinManager.reconcile(
                rimeDir = dir,
                enabledSchemas = setOf("pinyin_simp"),
                declaredSchemas = setOf("pinyin_simp"),
                selectedRules = setOf("z_zh"),
            )
            assertTrue(File(dir, "pinyin_simp.custom.yaml").isFile)

            val cleared = FuzzyPinyinManager.reconcile(
                rimeDir = dir,
                enabledSchemas = setOf("pinyin_simp"),
                declaredSchemas = setOf("pinyin_simp"),
                selectedRules = emptySet(),
            )
            assertTrue(cleared.changedSchemas.contains("pinyin_simp"))
            assertFalse(File(dir, "pinyin_simp.custom.yaml").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `reconcile drops the block when a schema is undeclared later`() {
        val dir = tempRimeDir()
        try {
            writeSchema(dir, "pinyin_simp", pinyinSchema)
            FuzzyPinyinManager.reconcile(
                rimeDir = dir,
                enabledSchemas = setOf("pinyin_simp"),
                declaredSchemas = setOf("pinyin_simp"),
                selectedRules = setOf("z_zh"),
            )
            assertTrue(File(dir, "pinyin_simp.custom.yaml").isFile)

            // 用户把方案从 fuzzy_pinyin.schemas 删掉：下次对账要摘掉旧块。
            val result = FuzzyPinyinManager.reconcile(
                rimeDir = dir,
                enabledSchemas = setOf("pinyin_simp"),
                declaredSchemas = emptySet(),
                selectedRules = setOf("z_zh"),
            )
            assertTrue(result.changedSchemas.contains("pinyin_simp"))
            assertFalse(File(dir, "pinyin_simp.custom.yaml").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `orphan custom file with managed block is cleaned`() {
        val dir = tempRimeDir()
        try {
            writeSchema(dir, "pinyin_simp", pinyinSchema)
            val orphan = File(dir, "removed_schema.custom.yaml")
            orphan.writeText(
                "patch:\n" +
                    "${FuzzyPinyinPatch.BEGIN}\n" +
                    "  \"speller/algebra/+\":\n" +
                    "    - \"fuzz/^n(.+)$/l$1/\"\n" +
                    "${FuzzyPinyinPatch.END}\n",
            )

            val result = FuzzyPinyinManager.reconcile(
                rimeDir = dir,
                enabledSchemas = setOf("pinyin_simp"),
                declaredSchemas = setOf("pinyin_simp"),
                selectedRules = setOf("z_zh"),
            )
            assertTrue(result.changedSchemas.contains("removed_schema"))
            assertFalse(orphan.exists())
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
