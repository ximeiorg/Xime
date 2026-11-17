package com.kingzcheung.xime.dictionary

import com.kingzcheung.xime.settings.DictEntry
import com.kingzcheung.xime.settings.DictionaryHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedDictManagerTest {

    @Test
    fun `buildDictYaml writes rime header and entries`() {
        val text = ImportedDictManager.buildDictYaml(
            id = "xime_import_ab12cd34",
            entries = listOf(
                DictEntry("你好", "ni hao", 1),
                DictEntry("中国", "zhong guo", null),
            ),
        )

        assertTrue(text.contains("name: xime_import_ab12cd34"))
        assertTrue(text.contains("sort: by_weight"))
        assertTrue(text.contains("你好\tni hao\t1"))
        assertTrue(text.contains("中国\tzhong guo\n"))

        val parsed = DictionaryHelper.parseDictEntries(text)
        assertEquals(2, parsed.size)
        assertEquals("你好", parsed[0].word)
        assertEquals("ni hao", parsed[0].code)
    }

    @Test
    fun `real errenzhuan scel generates a complete rime dict`() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("scel/errenzhuan.scel")!!
            .use { it.readBytes() }
        val parsed = com.kingzcheung.xime.lexicon.SogouScelLexiconParser
            .parse("二人转词汇大全.scel", bytes)
        val entries = parsed.entries.map { DictEntry(it.word, it.code, it.weight) }
        val text = ImportedDictManager.buildDictYaml("xime_import_real_test", entries)

        assertTrue(text.contains("name: xime_import_real_test"))
        assertTrue(text.contains("矮子步\tai zi bu\t1"))

        val roundTrip = DictionaryHelper.parseDictEntries(text)
        assertEquals(2615, roundTrip.size)
        assertEquals("矮子步", roundTrip.first().word)
        assertEquals("ai zi bu", roundTrip.first().code)
        assertEquals("坐中央", roundTrip.last().word)
        assertEquals("zuo zhong yang", roundTrip.last().code)
    }

    @Test
    fun `upsert creates patch block when missing`() {
        val result = ImportedDictManager.upsertManagedPackBlock(
            original = "patch:\n  menu/page_size: 7\n",
            namespace = "translator",
            packNames = listOf("xime_import_ab12cd34"),
        )

        assertTrue(result.contains("\"translator/packs/+\":"))
        assertTrue(result.contains("    - xime_import_ab12cd34"))
        assertTrue(result.contains("menu/page_size: 7"))
    }

    @Test
    fun `upsert replaces inline empty patch`() {
        val result = ImportedDictManager.upsertManagedPackBlock(
            original = "patch: {}\n",
            namespace = "translator",
            packNames = listOf("xime_import_ab12cd34"),
        )

        assertTrue(result.startsWith("patch:\n"))
        assertTrue(result.contains("    - xime_import_ab12cd34"))
        assertFalse(result.contains("{}"))
    }

    @Test
    fun `upsert is idempotent and keeps user content`() {
        val first = ImportedDictManager.upsertManagedPackBlock(
            original = "patch:\n  menu/page_size: 7\n",
            namespace = "translator",
            packNames = listOf("xime_import_ab12cd34"),
        )
        val second = ImportedDictManager.upsertManagedPackBlock(
            original = first,
            namespace = "translator",
            packNames = listOf("xime_import_ab12cd34", "xime_import_ff00aa11"),
        )

        assertEquals(1, Regex("translator/packs/\\+").findAll(second).count())
        assertTrue(second.contains("- xime_import_ab12cd34"))
        assertTrue(second.contains("- xime_import_ff00aa11"))
        assertTrue(second.contains("menu/page_size: 7"))
    }

    @Test
    fun `removing managed block restores original patch`() {
        val original = "patch:\n  menu/page_size: 7\n"
        val withBlock = ImportedDictManager.upsertManagedPackBlock(
            original = original,
            namespace = "translator",
            packNames = listOf("xime_import_ab12cd34"),
        )
        val cleaned = ImportedDictManager.removeManagedBlock(withBlock)

        assertEquals(original.trimEnd(), cleaned)
        assertFalse(cleaned.contains("xime-imported-dicts"))
    }

    @Test
    fun `namespaced translator uses its own packs key`() {
        val result = ImportedDictManager.upsertManagedPackBlock(
            original = "",
            namespace = "my_translator",
            packNames = listOf("xime_import_ab12cd34"),
        )

        assertTrue(result.contains("\"my_translator/packs/+\":"))
        assertFalse(result.contains("\"translator/packs/+\""))
    }

    @Test
    fun `parseMainTranslator handles standard and namespaced translators`() {
        val standard = """
            engine:
              translators:
                - punct_translator
                - script_translator
        """.trimIndent()
        assertEquals(
            ImportedDictManager.TranslatorInfo("script_translator", "translator"),
            ImportedDictManager.parseMainTranslator(standard),
        )

        val namespaced = """
            engine:
              translators:
                - punct_translator
                - table_translator@custom
        """.trimIndent()
        assertEquals(
            ImportedDictManager.TranslatorInfo("table_translator", "custom"),
            ImportedDictManager.parseMainTranslator(namespaced),
        )

        assertNull(ImportedDictManager.parseMainTranslator("engine:\n  translators:\n    - punct_translator\n"))
    }

}
