package com.kingzcheung.xime.plugin.core.js

import android.app.Application
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.js.http.BlobStore
import com.kingzcheung.xime.plugin.core.model.PluginContext
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.security.ErrorCategory
import com.kingzcheung.xime.plugin.core.security.PluginErrorLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * backup 插件的超时策略回归（issue #1244）。
 *
 * 背景：整包上传可能耗时数分钟，通用 180s 看门狗会把"慢网络"判成"死循环"，
 * 一次超时就把插件停用到用户手动重载。现在的契约：
 * - 备份调用用独立的 5 分钟上限（[JsBackupPluginAdapter.BACKUP_CALL_TIMEOUT_MS]）；
 * - 超时只中止本次调用（`poisonOnTimeout = false`），不中毒，插件仍可继续使用；
 * - 错误日志分类为 [ErrorCategory.TIMEOUT]（"超时"，不是"已停用"），并带上扩展点名。
 */
class JsBackupTimeoutTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pluginId = "js-backup-timeout"

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    /** 与 app 层 PluginBlobStore 同语义的最小实现（够登记/解析/注销）。 */
    private class TestBlobStore : BlobStore {
        private val entries = LinkedHashMap<String, Pair<String, File>>()
        private var seq = 0
        override fun createTempFile(ownerPluginId: String): File =
            File(System.getProperty("java.io.tmpdir"), "blob-$seq")
        override fun register(ownerPluginId: String, file: File, owned: Boolean): String =
            "blob-${seq++}".also { entries[it] = ownerPluginId to file }
        override fun resolve(pluginId: String, id: String): File? =
            entries[id]?.takeIf { it.first == pluginId }?.second?.takeIf { it.isFile }
        override fun release(id: String) { entries.remove(id) }
    }

    private fun loadAdapter(timeoutMs: Long): Pair<JsBackupPluginAdapter, JsScriptRuntime> {
        val dir = tmp.newFolder("backup-timeout-${System.nanoTime()}")
        File(dir, "main.js").writeText(
            """
            globalThis.plugin = {
              backup: {
                push: function () { while (true) {} }
              },
              ping: function () { return 'pong'; }
            };
            """.trimIndent()
        )
        val config = InMemoryConfigStore()
        val runtime = JsScriptRuntime(pluginId, dir, "main.js", config, callTimeoutMs = 5_000)
        assertTrue("插件应能加载", runtime.load())
        val info = PluginInfo(
            id = pluginId, name = "WebDAV 备份", iconResId = 0, versionCode = 1,
            versionName = "3.0.0", path = File(dir, "main.js").absolutePath,
            description = "测试", type = "backup"
        )
        val adapter = JsBackupPluginAdapter(
            runtime,
            PluginContext(application = Application(), pluginInfo = info, configStore = config),
            blobStore = TestBlobStore(),
            callTimeoutMs = timeoutMs,
        )
        return adapter to runtime
    }

    @Test(timeout = 30_000)
    fun `备份超时只中止本次调用，不中毒`() {
        val (adapter, runtime) = loadAdapter(timeoutMs = 300)
        try {
            val archive = File(tmp.root, "hang.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val result = runBlocking { adapter.pushBackup("hang.zip", archive) }
            assertFalse("超时应返回失败", result.ok)

            // 关键：插件没有被停用，后续调用照常执行
            assertEquals("pong", runtime.call("ping"))

            val recorded = PluginErrorLog.getErrors(pluginId).last()
            assertEquals("超时不应把插件标成已停用", ErrorCategory.TIMEOUT, recorded.category)
            assertTrue(
                "超时日志应带扩展点名，实际：${recorded.message}",
                recorded.message.contains("backup.push")
            )
        } finally {
            PluginErrorLog.clearErrors(pluginId)
            runtime.close()
        }
    }

    @Test
    fun `备份调用上限为 5 分钟`() {
        assertEquals(5 * 60_000L, JsBackupPluginAdapter.BACKUP_CALL_TIMEOUT_MS)
    }
}
