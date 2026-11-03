package com.kingzcheung.xime.rime

import android.content.Context
import android.content.res.AssetManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 简入繁出依赖 librime 在 `<rime>/opencc/` 下按配置名查找 OpenCC 数据；
 * 缺失时日志只有 `opencc config not found: s2hk.json`，候选不转繁体。
 */
class OpenccAssetsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun openccAssetsDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, "src/main/assets/rime/opencc").takeIf { it.isDirectory }?.let { return it }
            File(dir, "app/src/main/assets/rime/opencc").takeIf { it.isDirectory }?.let { return it }
            dir = dir.parentFile
        }
        error("assets/rime/opencc not found")
    }

    @Test
    fun `内置数据包含简入繁出默认配置 s2hk`() {
        val assets = openccAssetsDir()
        assertTrue("缺少 s2hk.json", File(assets, "s2hk.json").isFile)
    }

    @Test
    fun `每个 OpenCC 配置引用的词典都已随包提供`() {
        val assets = openccAssetsDir()
        val configs = assets.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
        assertTrue("assets/rime/opencc 下没有配置", configs.isNotEmpty())

        val refPattern = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"")
        for (config in configs) {
            val refs = refPattern.findAll(config.readText()).map { it.groupValues[1] }.toSet()
            assertTrue("${config.name} 未引用任何词典", refs.isNotEmpty())
            for (dict in refs) {
                assertTrue("${config.name} 引用的 $dict 缺失", File(assets, dict).isFile)
            }
        }
    }

    @Test
    fun `只补缺失文件且不覆盖用户已有数据`() {
        val rimeDir = tmp.newFolder("rime")
        val existing = File(rimeDir, "opencc/s2hk.json").apply {
            parentFile?.mkdirs()
            writeText("user")
        }

        val assets = mock<AssetManager>()
        whenever(assets.list("rime/opencc")).thenReturn(
            arrayOf("s2hk.json", "STCharacters.ocd2", "LICENSE")
        )
        whenever(assets.open("rime/opencc/s2hk.json")).thenAnswer {
            ByteArrayInputStream("builtin".toByteArray())
        }
        whenever(assets.open("rime/opencc/STCharacters.ocd2")).thenAnswer {
            ByteArrayInputStream("dict".toByteArray())
        }
        val context = mock<Context>()
        whenever(context.assets).thenReturn(assets)

        val copied = RimeConfigHelper.ensureOpenccAssets(context, rimeDir)

        assertEquals(1, copied)
        assertEquals("user", existing.readText())
        assertEquals("dict", File(rimeDir, "opencc/STCharacters.ocd2").readText())
        assertFalse("LICENSE 不应落到 rime/opencc", File(rimeDir, "opencc/LICENSE").exists())
    }
}
