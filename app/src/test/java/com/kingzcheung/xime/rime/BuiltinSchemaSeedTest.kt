package com.kingzcheung.xime.rime

import android.content.Context
import android.content.res.AssetManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 老内置安装升级：english 方案文件缺失/旧版时补种，第三方方案集不注入。
 */
class BuiltinSchemaSeedTest {

    private val schema = """
        schema:
          schema_id: english
          name: English
        switches:
          - name: english_spacing
            reset: 1
            states: [ 空格关, 空格开 ]
        filters:
          - lua_filter@*english_spacing
    """.trimIndent()

    private val oldSchema = """
        schema:
          schema_id: english
          name: English
        switches:
          - name: ascii_mode
            reset: 1
            states: [ 候选, 直输 ]
    """.trimIndent()

    private val dict = "name: english\n"
    private val lua = "return {}\n"

    private fun contextWithAssets(): Pair<Context, AssetManager> {
        val assets = mock<AssetManager>()
        whenever(assets.open(any())).thenAnswer { invocation ->
            val path = invocation.getArgument<String>(0)
            val content = when (path) {
                "rime/english.schema.yaml" -> schema
                "rime/english.dict.yaml" -> dict
                "rime/lua/english_spacing.lua" -> lua
                else -> error("unexpected asset: $path")
            }
            ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
        }
        val context = mock<Context> {
            on { getAssets() } doReturn assets
        }
        return context to assets
    }

    private fun tempRimeDir(): File =
        File.createTempFile("builtin_seed", "").apply {
            delete()
            mkdirs()
        }

    @Test
    fun `builtin package seeds missing english files`() {
        val (context, _) = contextWithAssets()
        val rimeDir = tempRimeDir()
        try {
            val restored = RimeConfigHelper.ensureBuiltinSchemaFiles(
                context, rimeDir, activePackageId = "builtin",
            )
            assertEquals(3, restored)
            assertTrue(File(rimeDir, "english.schema.yaml").readText().contains("english_spacing"))
            assertTrue(File(rimeDir, "english.dict.yaml").isFile)
            assertTrue(File(rimeDir, "lua/english_spacing.lua").isFile)
        } finally {
            rimeDir.deleteRecursively()
        }
    }

    @Test
    fun `old english schema without spacing is upgraded`() {
        val (context, _) = contextWithAssets()
        val rimeDir = tempRimeDir()
        try {
            File(rimeDir, "english.schema.yaml").writeText(oldSchema)
            val restored = RimeConfigHelper.ensureBuiltinSchemaFiles(
                context, rimeDir, activePackageId = "builtin",
            )
            assertEquals(3, restored)
            assertTrue(File(rimeDir, "english.schema.yaml").readText().contains("english_spacing"))
        } finally {
            rimeDir.deleteRecursively()
        }
    }

    @Test
    fun `existing current files are left untouched`() {
        val (context, _) = contextWithAssets()
        val rimeDir = tempRimeDir()
        try {
            File(rimeDir, "english.schema.yaml").writeText(schema)
            File(rimeDir, "english.dict.yaml").writeText(dict)
            File(rimeDir, "lua").mkdirs()
            File(rimeDir, "lua/english_spacing.lua").writeText(lua)
            val restored = RimeConfigHelper.ensureBuiltinSchemaFiles(
                context, rimeDir, activePackageId = "builtin",
            )
            assertEquals(0, restored)
        } finally {
            rimeDir.deleteRecursively()
        }
    }

    @Test
    fun `third party package is not seeded`() {
        val (context, _) = contextWithAssets()
        val rimeDir = tempRimeDir()
        try {
            val restored = RimeConfigHelper.ensureBuiltinSchemaFiles(
                context, rimeDir, activePackageId = "market-rime-ice",
            )
            assertEquals(0, restored)
            assertFalse(File(rimeDir, "english.schema.yaml").exists())
        } finally {
            rimeDir.deleteRecursively()
        }
    }
}
