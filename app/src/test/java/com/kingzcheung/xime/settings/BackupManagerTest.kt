package com.kingzcheung.xime.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupManagerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    /** 恢复期间收到的 prefs 恢复调用（prefsName -> json 文本）。 */
    private val restoredPrefs = mutableMapOf<String, String>()

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun zipOfText(entries: Map<String, String>): ByteArray =
        zipOf(entries.mapValues { it.value.toByteArray() })

    private fun restore(filesDir: File, bytes: ByteArray) =
        BackupManager.restoreArchive(filesDir, { prefsName, json ->
            restoredPrefs[prefsName] = json.toString(Charsets.UTF_8)
        }, bytes)

    private fun restore(filesDir: File, archive: File) =
        BackupManager.restoreArchive(filesDir, { prefsName, json ->
            restoredPrefs[prefsName] = json.toString(Charsets.UTF_8)
        }, archive)

    // ---- rime 条目（包根，兼容旧格式） ----

    @Test
    fun `restore unpacks rime entries into rime dir`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(
            mapOf(
                "default.yaml" to "config:",
                "opencc/ts.txt" to "opencc data"
            )
        )

        val result = restore(filesDir, bytes)

        assertTrue(result.isSuccess)
        assertEquals("config:", File(filesDir, "rime/default.yaml").readText())
        assertEquals("opencc data", File(filesDir, "rime/opencc/ts.txt").readText())
    }

    @Test
    fun `restore overwrites existing rime files`() {
        val filesDir = tempDir.newFolder("files")
        File(filesDir, "rime").mkdirs()
        File(filesDir, "rime/default.yaml").writeText("old:")
        val bytes = zipOfText(mapOf("default.yaml" to "new:"))

        val result = restore(filesDir, bytes)

        assertTrue(result.isSuccess)
        assertEquals("new:", File(filesDir, "rime/default.yaml").readText())
    }

    @Test
    fun `restore rejects path traversal entries`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(mapOf("../evil.txt" to "boom"))

        val result = restore(filesDir, bytes)

        assertTrue(result.isFailure)
        assertTrue(!File(filesDir.parentFile, "evil.txt").exists())
    }

    @Test
    fun `restore rejects absolute path entries`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(mapOf("/etc/evil.txt" to "boom"))

        val result = restore(filesDir, bytes)

        assertTrue(result.isFailure)
    }

    // ---- META 元数据条目 ----

    @Test
    fun `restore routes settings json to prefs restorer`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(
            mapOf("${BackupManager.META_PREFIX}settings.json" to """{"ascii_mode":{"t":"b","v":true}}""")
        )

        val result = restore(filesDir, bytes)

        assertTrue(result.isSuccess)
        assertEquals("kime_settings", restoredPrefs.keys.single())
        assertTrue(restoredPrefs["kime_settings"]!!.contains("ascii_mode"))
    }

    @Test
    fun `restore routes plugin config json to prefs restorer`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(
            mapOf(
                "${BackupManager.META_PREFIX}plugin_configs/com.example.plugin.json" to """{"url":{"t":"s","v":"secret"}}"""
            )
        )

        val result = restore(filesDir, bytes)

        assertTrue(result.isSuccess)
        assertEquals("plugin_cfg_com.example.plugin", restoredPrefs.keys.single())
    }

    @Test
    fun `restore rejects plugin config id with path separators`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(
            mapOf("${BackupManager.META_PREFIX}plugin_configs/../evil.json" to "{}")
        )

        val result = restore(filesDir, bytes)

        // id 含路径分隔符：不写 prefs、不落盘（条目被忽略），恢复本身不失败
        assertTrue(result.isSuccess)
        assertTrue(restoredPrefs.isEmpty())
        assertTrue(!File(filesDir, "evil.json").exists())
    }

    @Test
    fun `restore writes plugins xml and plugin packages`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(
            mapOf(
                "${BackupManager.META_PREFIX}plugins.xml" to "<plugins/>",
                "${BackupManager.META_PREFIX}plugins/com.example.plugin/main.lua" to "return {}",
                "${BackupManager.META_PREFIX}unknown.json" to "{}"
            )
        )

        val result = restore(filesDir, bytes)

        assertTrue(result.isSuccess)
        assertEquals("<plugins/>", File(filesDir, "plugins.xml").readText())
        assertEquals("return {}", File(filesDir, "plugins/com.example.plugin/main.lua").readText())
        // 未知元数据条目被跳过（向前兼容），且不落入 rime 目录
        assertTrue(!File(filesDir, "rime/unknown.json").exists())
    }

    @Test
    fun `restore rejects plugin package traversal`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(
            mapOf("${BackupManager.META_PREFIX}plugins/../evil.lua" to "boom")
        )

        val result = restore(filesDir, bytes)

        assertTrue(result.isFailure)
        assertTrue(!File(filesDir, "evil.lua").exists())
    }

    @Test
    fun `restore fails on empty archive`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(emptyMap())

        val result = restore(filesDir, bytes)

        assertTrue(result.isFailure)
    }

    // ---- 流式恢复入口（文件，不走内存） ----
    @Test
    fun `restore from file unpacks rime entries and meta prefs`() {
        val filesDir = tempDir.newFolder("files")
        val archive = File(tempDir.root, "backup.zip")
        archive.writeBytes(
            zipOfText(
                mapOf(
                    "default.yaml" to "config:",
                    "${BackupManager.META_PREFIX}settings.json" to """{"ascii_mode":{"t":"b","v":true}}"""
                )
            )
        )

        val result = restore(filesDir, archive)

        assertTrue(result.isSuccess)
        assertEquals("config:", File(filesDir, "rime/default.yaml").readText())
        assertTrue(restoredPrefs.containsKey("kime_settings"))
    }

    @Test
    fun `restore from file rejects absolute path entry`() {
        val filesDir = tempDir.newFolder("files")
        val archive = File(tempDir.root, "evil.zip")
        archive.writeBytes(zipOfText(mapOf("/tmp/evil.yaml" to "boom")))

        val result = restore(filesDir, archive)

        assertTrue(result.isFailure)
    }

    @Test
    fun `restore from file fails on empty archive`() {
        val filesDir = tempDir.newFolder("files")
        val archive = File(tempDir.root, "empty.zip")
        archive.writeBytes(zipOfText(emptyMap()))

        val result = restore(filesDir, archive)

        assertTrue(result.isFailure)
    }

    // ---- v3 清单类元数据（完整方案档） ----

    @Test
    fun `restore writes v3 plugin registry`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(
            mapOf("${BackupManager.META_PREFIX}plugins.json" to """{"version":1,"plugins":[]}""")
        )

        val result = restore(filesDir, bytes)

        assertTrue(result.isSuccess)
        assertEquals("""{"version":1,"plugins":[]}""", File(filesDir, "plugins.json").readText())
    }

    @Test
    fun `restore ignores legacy scheme registry and manifests`() {
        // 旧格式备份带 .registry.json / .manifests：模型已废弃（状态在 prefs），恢复时忽略。
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(
            mapOf(
                "${BackupManager.META_PREFIX}schemas/registry.json" to """{"version":1}""",
                "${BackupManager.META_PREFIX}schemas/manifests/stroke.schema.yaml.json" to """{"id":"s"}"""
            )
        )

        val result = restore(filesDir, bytes)

        assertTrue(result.isSuccess)
        assertFalse(File(filesDir, ".registry.json").exists())
        assertFalse(File(filesDir, ".manifests").exists())
    }

    @Test
    fun `restore ignores manifest id with path separators`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(
            mapOf("${BackupManager.META_PREFIX}schemas/manifests/../evil.json" to "boom")
        )

        val result = restore(filesDir, bytes)

        // 非法 id 条目跳过（恢复本身不失败），不得写到 .manifests 之外
        assertTrue(result.isSuccess)
        assertFalse(File(filesDir, "evil.json").exists())
        assertFalse(File(tempDir.root, "evil.json").exists())
    }

    // ---- 本地完整方案包识别（本地导入入口据此分流） ----

    @Test
    fun `isBackupPackage accepts package with meta entries and rejects snapshot zip`() {
        val filesDir = tempDir.newFolder("files-detect")
        val packageZip = File(filesDir, "package.zip").apply {
            writeBytes(zipOfText(mapOf("default.custom.yaml" to "patch:", "${BackupManager.META_PREFIX}settings.json" to "{}")))
        }
        val snapshotZip = File(filesDir, "snapshot.zip").apply {
            writeBytes(zipOfText(mapOf("sync/11111111-2222-3333-4444-555555555555/pinyin_simp.userdb.txt" to "x")))
        }
        val notZip = File(filesDir, "notes.txt").apply { writeText("hello") }

        assertTrue("带 _xime_backup 元数据的才是完整方案包", BackupManager.isBackupPackage(packageZip))
        assertFalse("词条快照包不是完整方案包", BackupManager.isBackupPackage(snapshotZip))
        assertFalse("非 zip 文件不是完整方案包", BackupManager.isBackupPackage(notZip))
        assertFalse("不存在的文件不是完整方案包", BackupManager.isBackupPackage(File(filesDir, "missing.zip")))
    }

    // ---- 数据库：先落 staging，由宿主关库后替换 ----

    @Test
    fun `restore stages database entries and ignores unknown names`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOf(
            mapOf(
                "_xime_backup/database/xime.db" to byteArrayOf(1, 2, 3),
                "_xime_backup/database/xime.db-wal" to byteArrayOf(4, 5),
                "_xime_backup/database/evil.txt" to byteArrayOf(9),
            )
        )

        val outcome = restore(filesDir, bytes)

        assertTrue(outcome.isSuccess)
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            File(filesDir, ".restore-db/xime.db").readBytes(),
        )
        assertArrayEquals(
            byteArrayOf(4, 5),
            File(filesDir, ".restore-db/xime.db-wal").readBytes(),
        )
        assertFalse(
            "只接受 xime.db 的三个文件名",
            File(filesDir, ".restore-db/evil.txt").exists(),
        )
    }

    // ---- 自造词快照：计数（恢复后据此触发引擎合并） ----

    @Test
    fun `restore counts dict snapshots and lands them under rime sync`() {
        val filesDir = tempDir.newFolder("files")
        val installationId = "11111111-2222-3333-4444-555555555555"
        val bytes = zipOfText(
            mapOf(
                "default.custom.yaml" to "patch:",
                "sync/$installationId/pinyin_simp.userdb.txt" to "词条\t1",
                "sync/$installationId/t9_digit.userdb.txt" to "词条\t2"
            )
        )

        val outcome = BackupManager.restoreArchiveOutcome(
            filesDir, { _, _ -> }, bytes
        ).getOrThrow()

        assertEquals("两个快照文件应计数", 2, outcome.dictSnapshotCount)
        assertEquals("三个条目全部落盘", 3, outcome.entryCount)
        assertEquals(
            "词条\t1",
            File(filesDir, "rime/sync/$installationId/pinyin_simp.userdb.txt").readText()
        )
    }

    @Test
    fun `restore reports zero dict snapshots for config-only package`() {
        val filesDir = tempDir.newFolder("files")
        val bytes = zipOfText(mapOf("default.custom.yaml" to "patch:"))

        val outcome = BackupManager.restoreArchiveOutcome(filesDir, { _, _ -> }, bytes).getOrThrow()

        assertEquals(0, outcome.dictSnapshotCount)
    }

    // ---- 词库：恢复只合并不覆盖 ----

    private val snapshotText = "# Rime user dictionary\n#@/db_type\tuserdb\nni hao \t你好\tc=3 d=4e-08 t=0"

    /**
     * 回归：旧版本导出的包里有 `*.userdb/` leveldb 目录。恢复时若直接落盘，
     * 就是整库覆盖，本机新打的词当场消失（用户反馈"恢复没有 merge，而是覆盖"）。
     * 词库必须只走 `*.userdb.txt` 快照合并。
     */
    @Test
    fun `restore never overwrites local leveldb user dict`() {
        val filesDir = tempDir.newFolder("files")
        val installationId = "11111111-2222-3333-4444-555555555555"
        val localDb = File(filesDir, "rime/pinyin_simp.userdb").apply { mkdirs() }
        val localManifest = File(localDb, "MANIFEST-000002").apply { writeText("本机现有词库") }

        val bytes = zipOfText(
            mapOf(
                "pinyin_simp.userdb/MANIFEST-000002" to "备份里的旧词库",
                "pinyin_simp.userdb/000003.log" to "旧日志",
                "rime.db" to "别的二进制",
                "sync/$installationId/pinyin_simp.userdb.txt" to snapshotText
            )
        )

        val outcome = BackupManager.restoreArchiveOutcome(filesDir, { _, _ -> }, bytes).getOrThrow()

        assertEquals("本机 leveldb 内容不得被覆盖", "本机现有词库", localManifest.readText())
        assertFalse("包内日志文件也不落盘", File(localDb, "000003.log").exists())
        assertFalse("包内 *.db 也不落盘", File(filesDir, "rime/rime.db").exists())
        assertEquals("快照仍然落盘并计数（交给引擎合并）", 1, outcome.dictSnapshotCount)
        assertEquals(
            snapshotText,
            File(filesDir, "rime/sync/$installationId/pinyin_simp.userdb.txt").readText()
        )
    }

    @Test
    fun `leveldb user dict entries are recognized but snapshots are not`() {
        assertTrue(BackupManager.isUserDbLeveldbEntry("pinyin_simp.userdb/CURRENT"))
        assertTrue(BackupManager.isUserDbLeveldbEntry("t9_digit.userdb/LOCK"))
        assertTrue(BackupManager.isUserDbLeveldbEntry("pinyin_simp.db"))
        assertTrue(BackupManager.isUserDbLeveldbEntry("pinyin_simp.db-wal"))
        assertFalse("快照不是 leveldb", BackupManager.isUserDbLeveldbEntry("sync/id/pinyin_simp.userdb.txt"))
        assertFalse("普通配置照常落盘", BackupManager.isUserDbLeveldbEntry("default.custom.yaml"))
    }
}
