package com.kingzcheung.xime.settings

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyPinyinPatchTest {

    private val patterns = listOf(
        "fuzz/^z([^h].*)$/zh$1/",
        "fuzz/^zh(.+)$/z$1/",
    )

    @Test
    fun `creates patch block in empty file`() {
        val result = FuzzyPinyinPatch.upsert("", patterns)
        assertTrue(result.changed)
        assertFalse(result.conflict)
        assertTrue(result.content.startsWith("patch:\n"))
        assertTrue(result.content.contains(FuzzyPinyinPatch.BEGIN))
        assertTrue(result.content.contains("\"speller/algebra/+\":"))
        assertTrue(result.content.contains("\"fuzz/^z([^h].*)$/zh$1/\""))
    }

    @Test
    fun `keeps existing user and other feature patches`() {
        val text = """
            # user comment
            patch:
              "engine/translators/+":
                - table_translator@custom_phrase
        """.trimIndent() + "\n"
        val result = FuzzyPinyinPatch.upsert(text, patterns)
        assertTrue(result.changed)
        assertTrue(result.content.contains("# user comment"))
        assertTrue(result.content.contains("engine/translators/+"))
        assertTrue(result.content.contains("speller/algebra/+"))
    }

    @Test
    fun `generated block is valid yaml`() {
        val content = FuzzyPinyinPatch.upsert("", patterns).content
        val root = SchemaManager.yaml.parseToYamlNode(content) as? YamlMap
        assertNotNull(root)
        val patch = root!!["patch"] as? YamlMap
        assertNotNull(patch)
        val rules = patch!!["speller/algebra/+"] as? YamlList
        assertNotNull(rules)
        assertEquals(patterns.size, rules!!.items.size)
    }

    @Test
    fun `is idempotent`() {
        val first = FuzzyPinyinPatch.upsert("", patterns)
        val second = FuzzyPinyinPatch.upsert(first.content, patterns)
        assertFalse(second.changed)
        assertEquals(first.content, second.content)
    }

    @Test
    fun `removes only the managed block`() {
        val text = """
            patch:
              "engine/translators/+":
                - table_translator@custom_phrase
        """.trimIndent() + "\n"
        val withBlock = FuzzyPinyinPatch.upsert(text, patterns).content
        val removed = FuzzyPinyinPatch.upsert(withBlock, emptyList())
        assertTrue(removed.changed)
        assertFalse(removed.content.contains(FuzzyPinyinPatch.BEGIN))
        assertTrue(removed.content.contains("engine/translators/+"))
    }

    @Test
    fun `removing from a file that only contains the block returns empty content`() {
        val withBlock = FuzzyPinyinPatch.upsert("", patterns).content
        val removed = FuzzyPinyinPatch.upsert(withBlock, emptyList())
        assertTrue(removed.changed)
        assertTrue(removed.content.isBlank())
    }

    @Test
    fun `user algebra patch is treated as conflict and our old block is removed`() {
        val userText = """
            patch:
              "speller/algebra/+":
                - derive/^foo$/bar/
        """.trimIndent() + "\n"
        val withOurBlock = FuzzyPinyinPatch.upsert("patch:\n", patterns).content + userText
        val result = FuzzyPinyinPatch.upsert(withOurBlock, patterns)
        assertTrue(result.conflict)
        assertFalse(result.content.contains(FuzzyPinyinPatch.BEGIN))
        assertTrue(result.content.contains("derive/^foo$/bar/"))
    }

    @Test
    fun `inline patch map is not rewritten`() {
        val result = FuzzyPinyinPatch.upsert("patch: {foo: bar}\n", patterns)
        assertTrue(result.conflict)
        assertFalse(result.content.contains(FuzzyPinyinPatch.BEGIN))
    }

    @Test
    fun `empty patch object is normalized before inserting`() {
        val result = FuzzyPinyinPatch.upsert("patch: {}\n", patterns)
        assertTrue(result.changed)
        assertFalse(result.content.contains("patch: {}"))
        assertTrue(result.content.contains("patch:\n"))
        assertTrue(result.content.contains(FuzzyPinyinPatch.BEGIN))
    }
}
