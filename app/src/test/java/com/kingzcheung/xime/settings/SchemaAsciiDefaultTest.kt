package com.kingzcheung.xime.settings

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * 「方案声明默认英文」判定：内置 English 方案靠 `switches.ascii_mode.reset: 1`
 * 在切换方案/新输入会话时保持英文；普通中文方案 reset 0 或未声明，不受影响。
 */
class SchemaAsciiDefaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun contextWithRimeDir(): Pair<Context, File> {
        val filesDir = tmp.newFolder("files")
        val rimeDir = File(filesDir, "rime").apply { mkdirs() }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(filesDir)
        return context to rimeDir
    }

    private fun writeSchema(rimeDir: File, id: String, content: String) {
        File(rimeDir, "$id.schema.yaml").writeText(content)
    }

    @Test
    fun `ascii_mode reset 1 视为默认英文方案`() {
        val (context, rimeDir) = contextWithRimeDir()
        writeSchema(
            rimeDir, "english",
            """
            schema:
              schema_id: english
              name: English
            switches:
              - name: ascii_mode
                reset: 1
                states: [ 中文, 英文 ]
            """.trimIndent(),
        )
        assertTrue(SchemaManager.isAsciiDefaultSchema(context, "english"))
    }

    @Test
    fun `reset 0 或未声明不算默认英文`() {
        val (context, rimeDir) = contextWithRimeDir()
        writeSchema(
            rimeDir, "wubi86",
            """
            schema:
              schema_id: wubi86
              name: 五笔86
            switches:
              - name: ascii_mode
                reset: 0
                states: [ 中文, 英文 ]
            """.trimIndent(),
        )
        writeSchema(
            rimeDir, "pinyin_simp",
            """
            schema:
              schema_id: pinyin_simp
              name: 简体拼音
            switches:
              - name: ascii_mode
                states: [ 中文, 英文 ]
            """.trimIndent(),
        )
        assertFalse(SchemaManager.isAsciiDefaultSchema(context, "wubi86"))
        assertFalse(SchemaManager.isAsciiDefaultSchema(context, "pinyin_simp"))
        assertFalse(SchemaManager.isAsciiDefaultSchema(context, "missing"))
        assertNull(SchemaManager.schemaSwitchReset(context, "pinyin_simp", "ascii_punct"))
    }

    @Test
    fun `内置 English 方案声明了 reset 1`() {
        val switches = SchemaManager.parseSchemaSwitches(repoFile("rime/english.schema.yaml"))
        val asciiMode = switches.firstOrNull { it.name == "ascii_mode" }
        assertNotNull(asciiMode)
        assertEquals(1, asciiMode!!.reset)
    }

    @Test
    fun `English 方案暴露自动空格开关并引用 lua filter`() {
        val schemaFile = repoFile("rime/english.schema.yaml")
        val switches = SchemaManager.parseSchemaSwitches(schemaFile)
        val spacing = switches.firstOrNull { it.name == "english_spacing" }
        assertNotNull("english_spacing switch must be visible in 方案开关", spacing)
        assertEquals(1, spacing!!.reset)
        val asciiPunct = switches.firstOrNull { it.name == "ascii_punct" }
        assertNotNull(asciiPunct)
        assertEquals(1, asciiPunct!!.reset)
        assertTrue(
            "english_spacing.lua must be referenced by the schema",
            schemaFile.readText().contains("lua_filter@*english_spacing"),
        )
        assertTrue(repoFile("rime/lua/english_spacing.lua").isFile)
    }

    /** 词库头部 name 必须与文件名/方案 dictionary 一致，否则部署会去找
     *  `wanxiang_english.dict.yaml` 而编译失败。 */
    @Test
    fun `english 词库 name 与方案 dictionary 一致`() {
        val dictName = repoFile("rime/english.dict.yaml")
            .useLines { lines -> lines.take(10).firstOrNull { it.startsWith("name:") } }
            ?.removePrefix("name:")?.trim()
        assertEquals("english", dictName)
        assertTrue(repoFile("rime/english.schema.yaml").readText().contains("dictionary: english"))
    }

    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, "src/main/assets/$rel").takeIf { it.isFile }?.let { return it }
            File(dir, "app/src/main/assets/$rel").takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("$rel not found")
    }
}
