package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 无线导入页文本编辑的写入策略单测：只允许改 rime 目录下的用户配置文本，
 * 编译产物 / 二进制 / 超大文件一律拒绝。
 */
class WirelessTextFilePolicyTest {

    private fun newRimeDir(): File =
        File(System.getProperty("java.io.tmpdir"), "wireless-write-test-${System.nanoTime()}")
            .apply { mkdirs() }

    @Test
    fun `yaml 文本可写`() {
        val root = newRimeDir()
        val file = File(root, "default.custom.yaml").apply { writeText("patch:\n") }
        assertTrue(WirelessTextFilePolicy.isWritableTarget(root, file))
        root.deleteRecursively()
    }

    @Test
    fun `rime 目录外不可写`() {
        val root = newRimeDir()
        val outside = File(root.parentFile, "outside-${System.nanoTime()}.yaml")
            .apply { writeText("x") }
        assertFalse(WirelessTextFilePolicy.isWritableTarget(root, outside))
        outside.delete()
        root.deleteRecursively()
    }

    @Test
    fun `build 下的产物不可写`() {
        val root = newRimeDir()
        val build = File(root, "build").apply { mkdirs() }
        val file = File(build, "default.yaml").apply { writeText("x") }
        assertFalse(WirelessTextFilePolicy.isWritableTarget(root, file))
        root.deleteRecursively()
    }

    @Test
    fun `二进制扩展名与用户词典不可写`() {
        val root = newRimeDir()
        val bin = File(root, "default.custom.yaml.bin").apply { writeText("x") }
        val userdb = File(root, "t9_digit.userdb.txt").apply { writeText("x") }
        assertFalse(WirelessTextFilePolicy.isWritableTarget(root, bin))
        assertFalse(WirelessTextFilePolicy.isWritableTarget(root, userdb))
        root.deleteRecursively()
    }

    @Test
    fun `超过上限的文件不可写`() {
        val root = newRimeDir()
        val big = File(root, "big.yaml")
        big.outputStream().use { out ->
            val chunk = ByteArray(1024 * 1024)
            repeat(3) { out.write(chunk) }
        }
        assertFalse(WirelessTextFilePolicy.isWritableTarget(root, big))
        root.deleteRecursively()
    }

    @Test
    fun `原子写入替换内容且不残留临时文件`() {
        val root = newRimeDir()
        val file = File(root, "default.custom.yaml").apply { writeText("old") }
        assertTrue(WirelessTextFilePolicy.writeTextAtomically(file, "new: 1\n"))
        assertEquals("new: 1\n", file.readText())
        assertEquals(
            listOf("default.custom.yaml"),
            root.listFiles()?.map { it.name }?.sorted(),
        )
        root.deleteRecursively()
    }

    @Test
    fun `写入内容超过上限直接失败`() {
        val root = newRimeDir()
        val file = File(root, "default.custom.yaml").apply { writeText("old") }
        val oversized = "x".repeat((WirelessTextFilePolicy.MAX_WRITE_BYTES + 1).toInt())
        assertFalse(WirelessTextFilePolicy.writeTextAtomically(file, oversized))
        assertEquals("old", file.readText())
        root.deleteRecursively()
    }

    @Test
    fun `扩展名白名单`() {
        assertTrue(WirelessTextFilePolicy.isEditableName("a.yaml"))
        assertTrue(WirelessTextFilePolicy.isEditableName("a.YML"))
        assertTrue(WirelessTextFilePolicy.isEditableName("custom_phrase.txt"))
        assertTrue(WirelessTextFilePolicy.isEditableName("script.lua"))
        assertFalse(WirelessTextFilePolicy.isEditableName("dict.bin"))
        assertFalse(WirelessTextFilePolicy.isEditableName("noext"))
    }
}
