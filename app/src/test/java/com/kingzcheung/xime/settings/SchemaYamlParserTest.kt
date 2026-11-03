package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SchemaYamlParserTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun writeSchema(content: String): File {
        val f = tempDir.newFile("test_schema.schema.yaml")
        f.writeText(content)
        return f
    }

    @Test
    fun `standard schema name`() {
        val f = writeSchema("""
            schema:
              schema_id: luna_pinyin
              name: 朙月拼音
              version: "0.23"
        """.trimIndent())
        val meta = SchemaManager.parseSchemaYaml(f)
        assertEquals("luna_pinyin", meta?.schemaId)
        assertEquals("朙月拼音", meta?.name)
    }

    @Test
    fun `name empty falls back to schema id`() {
        val f = writeSchema("""
            schema:
              schema_id: test_schema
              name: ""
              version: "1.0"
        """.trimIndent())
        val meta = SchemaManager.parseSchemaYaml(f)
        assertEquals("test_schema", meta?.schemaId)
        assertEquals("test_schema", meta?.name)
    }

    @Test
    fun `author as scalar`() {
        val f = writeSchema("""
            schema:
              schema_id: test_schema
              name: Test Schema
              author: "Single Author"
        """.trimIndent())
        val meta = SchemaManager.parseSchemaYaml(f)
        assertEquals("Single Author", meta?.author)
    }

    @Test
    fun `author as list takes first item`() {
        val f = writeSchema("""
            schema:
              schema_id: test_schema
              name: Test Schema
              author:
                - "Author One"
                - "Author Two"
        """.trimIndent())
        val meta = SchemaManager.parseSchemaYaml(f)
        assertEquals("Author One", meta?.author)
    }

    @Test
    fun `description block scalar`() {
        val f = writeSchema("""
            schema:
              schema_id: test_schema
              name: Test Schema
              description: |
                First line
                Second line
        """.trimIndent())
        val meta = SchemaManager.parseSchemaYaml(f)
        assertEquals("First line\nSecond line", meta?.description)
    }

    @Test
    fun `extra fields are ignored`() {
        val f = writeSchema("""
            schema:
              schema_id: test_schema
              name: Test Schema
              unknown_field: should_not_crash
            switches:
              - name: ascii_mode
        """.trimIndent())
        val meta = SchemaManager.parseSchemaYaml(f)
        assertEquals("test_schema", meta?.schemaId)
        assertEquals("Test Schema", meta?.name)
    }

    @Test
    fun `version with comments before schema block`() {
        val f = writeSchema("""
            # Rime schema
            # encoding: utf-8
            schema:
              schema_id: wubi86
              name: 五笔86版
              version: "1.0"
        """.trimIndent())
        val meta = SchemaManager.parseSchemaYaml(f)
        assertEquals("wubi86", meta?.schemaId)
        assertEquals("五笔86版", meta?.name)
    }

    @Test
    fun `patch section contains author field does not override schema name`() {
        val f = writeSchema("""
            schema:
              schema_id: test_schema
              name: Real Name
            patch:
              schema/name: Patched Name
        """.trimIndent())
        val meta = SchemaManager.parseSchemaYaml(f)
        assertEquals("Real Name", meta?.name)
    }

    @Test
    fun `file with no schema block returns null`() {
        val f = writeSchema("""
            switches:
              - name: ascii_mode
        """.trimIndent())
        val meta = SchemaManager.parseSchemaYaml(f)
        assertNull(meta)
    }

    @Test
    fun `extract switches with name and states`() {
        val content = """
            schema:
              schema_id: pinyin_simp
              name: 简体拼音
            switches:
              - name: ascii_mode
                reset: 0
                states: [ 中文, 西文 ]
              - name: full_shape
                states: [ 半角, 全角 ]
            engine:
              processors:
                - ascii_composer
        """.trimIndent()
        val block = SchemaManager.extractSwitchesBlock(content)
        assertNotNull(block)
        assertTrue(block!!.contains("- name: ascii_mode"))
        assertTrue(block.contains("- name: full_shape"))
        assertFalse(block.contains("engine:"))
    }

    @Test
    fun `extract switches with options`() {
        val content = """
            schema:
              schema_id: test_schema
              name: Test Schema
            switches:
              - options: [ zh_simp, zh_trad ]
                states: [ 简, 繁 ]
        """.trimIndent()
        val block = SchemaManager.extractSwitchesBlock(content)
        assertNotNull(block)
        assertTrue(block!!.contains("- options:"))
        assertTrue(block.contains("zh_simp"))
    }

    @Test
    fun `extract switches null when absent`() {
        val content = """
            schema:
              schema_id: test_schema
              name: Test Schema
        """.trimIndent()
        assertNull(SchemaManager.extractSwitchesBlock(content))
    }

    @Test
    fun `getSchemaSwitches maps to public model`() {
        val f = writeSchema("""
            schema:
              schema_id: pinyin_simp
              name: 简体拼音
            switches:
              - name: ascii_mode
                states: [ 中文, 西文 ]
              - name: full_shape
                states: [ 半角, 全角 ]
              - options: [ zh_simp, zh_trad ]
                states: [ 简, 繁 ]
              - command: foo
                states: [ A, B ]
        """.trimIndent())
        val switches = SchemaManager.parseSchemaSwitches(f)
        assertEquals(3, switches.size)
        assertEquals("ascii_mode", switches[0].name)
        assertEquals(emptyList<String>(), switches[0].options)
        assertEquals(listOf("中文", "西文"), switches[0].states)
        assertEquals("full_shape", switches[1].name)
        assertEquals(emptyList<String>(), switches[1].options)
        assertEquals(listOf("半角", "全角"), switches[1].states)
        assertEquals("", switches[2].name)
        assertEquals(listOf("zh_simp", "zh_trad"), switches[2].options)
        assertEquals(listOf("简", "繁"), switches[2].states)
    }

    @Test
    fun `getSchemaSwitches parses reset`() {
        val f = writeSchema("""
            schema:
              schema_id: english
              name: English
            switches:
              - name: ascii_mode
                reset: 1
                states: [ 中文, 英文 ]
              - name: full_shape
                reset: 0
                states: [ 半角, 全角 ]
              - name: no_reset
                states: [ A, B ]
        """.trimIndent())
        val switches = SchemaManager.parseSchemaSwitches(f)
        assertEquals(1, switches[0].reset)
        assertEquals(0, switches[1].reset)
        assertNull(switches[2].reset)
    }

    @Test
    fun `getSchemaSwitches ignores switches without states`() {
        val f = writeSchema("""
            schema:
              schema_id: test_schema
              name: Test Schema
            switches:
              - name: no_states
        """.trimIndent())
        val switches = SchemaManager.parseSchemaSwitches(f)
        assertEquals(emptyList<SchemaSwitch>(), switches)
    }

    @Test
    fun `getSchemaSwitches parses abbrev as list`() {
        val f = writeSchema("""
            schema:
              schema_id: test_schema
              name: Test Schema
            switches:
              - name: search_single_char
                states: [ 正常, 单字 ]
                abbrev: [ 词, 单 ]
        """.trimIndent())
        val switches = SchemaManager.parseSchemaSwitches(f)
        assertEquals(1, switches.size)
        assertEquals(listOf("词", "单"), switches[0].abbrev)
        assertEquals(emptyList<String>(), switches[0].options)
    }

    @Test
    fun `getSchemaSwitches parses abbrev as scalar`() {
        val f = writeSchema("""
            schema:
              schema_id: test_schema
              name: Test Schema
            switches:
              - name: traditionalization
                states: [ 简, 繁 ]
                abbrev: 繁
        """.trimIndent())
        val switches = SchemaManager.parseSchemaSwitches(f)
        assertEquals(1, switches.size)
        assertEquals(listOf("繁"), switches[0].abbrev)
    }

    @Test
    fun `getSchemaSwitches abbrev empty when absent`() {
        val f = writeSchema("""
            schema:
              schema_id: test_schema
              name: Test Schema
            switches:
              - name: ascii_mode
                states: [ 中, Ａ ]
        """.trimIndent())
        val switches = SchemaManager.parseSchemaSwitches(f)
        assertEquals(1, switches.size)
        assertEquals(emptyList<String>(), switches[0].abbrev)
    }

    @Test
    fun `getSchemaSwitches empty when file missing`() {
        val f = File(tempDir.root, "missing.schema.yaml")
        assertEquals(emptyList<SchemaSwitch>(), SchemaManager.parseSchemaSwitches(f))
    }

    @Test
    fun `parses realistic config with inline comments and option groups`() {
        val f = writeSchema("""
            schema:
              schema_id: test_schema
              name: Test Schema
            switches:
              - name: ascii_mode  # 中英输入状态
                states: [中文, 英文]
              - name: full_shape  #全角、半角字符输出
                states: [半角, 全角]
              - options: [raw_input, tone_display, full_pinyin]  # 归属 super_preedit.lua
                states: [原编码, 有声调, 无声调]
                #    reset: 2
              - options: [s2s, s2t, s2hk, s2tw]  # 简繁转换开关组
                states: [简体, 通繁, 港繁, 臺繁]
              - name: abbrev
                states: [简码关, 简码开]
                reset: 1
        """.trimIndent())
        val switches = SchemaManager.parseSchemaSwitches(f)
        assertEquals(5, switches.size)

        // name 开关：states 保留完整字符串
        assertEquals("ascii_mode", switches[0].name)
        assertEquals(listOf("中文", "英文"), switches[0].states)

        assertEquals("full_shape", switches[1].name)
        assertEquals(listOf("半角", "全角"), switches[1].states)

        // options 开关：3 个选项 3 个状态
        assertEquals("", switches[2].name)
        assertEquals(listOf("raw_input", "tone_display", "full_pinyin"), switches[2].options)
        assertEquals(listOf("原编码", "有声调", "无声调"), switches[2].states)

        // options 开关：4 个选项 4 个状态
        assertEquals("", switches[3].name)
        assertEquals(listOf("s2s", "s2t", "s2hk", "s2tw"), switches[3].options)
        assertEquals(listOf("简体", "通繁", "港繁", "臺繁"), switches[3].states)

        assertEquals("abbrev", switches[4].name)
        assertEquals(listOf("简码关", "简码开"), switches[4].states)
    }
}
