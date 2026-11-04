package com.kingzcheung.xime.plugin.core.js

import android.util.Log
import com.kingzcheung.xime.plugin.core.api.BackupDownload
import com.kingzcheung.xime.plugin.core.api.BackupPlugin
import com.kingzcheung.xime.plugin.core.api.BackupResult
import com.kingzcheung.xime.plugin.core.api.RemoteBackupEntry
import com.kingzcheung.xime.plugin.core.js.http.BlobStore
import com.kingzcheung.xime.plugin.core.js.sdk.JsPluginContract
import com.kingzcheung.xime.plugin.core.model.PluginContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * backup 类型 JS 插件的宿主侧适配器：实现 [BackupPlugin] 接口。
 *
 * 备份包生成/恢复由宿主 BackupManager 承载，协议逻辑（WebDAV / S3 / 自建 HTTP）
 * 由插件 JS 用 `host.http` + `host.crypto` 承载。
 *
 * **大包不跨 JS 桥**（v3.1 契约）：
 * - backup.push({name, size, archiveId}) → 适配器把宿主的 zip 文件登记为
 *   [BlobStore] 句柄，只把不透明 id 交给插件；插件 `host.http.upload` 由宿主按块发送
 * - backup.pull(id) → 插件返回 `host.http.download` 落盘的 blob 句柄（字符串），
 *   适配器解析为 [BackupDownload]；宿主恢复后经 [releaseBackup] 释放（删缓存文件）
 * - backup.list() → {id,name,createdAt,size} 数组
 * - backup.remove(id) / backup.test()
 *
 * **超时**：备份动作本质是长耗时网络 IO（整包上传/下载可能几十 MB），统一用
 * [BACKUP_CALL_TIMEOUT_MS] 而不是通用 180s，且超时只中止本次调用（`poisonOnTimeout = false`），
 * 不停用插件——慢网络不该被当成"死循环"。
 */
class JsBackupPluginAdapter(
    runtime: JsScriptRuntime,
    pluginContext: PluginContext,
    private val blobStore: BlobStore? = null,
    /** 便于单测注入更短超时；线上固定 [BACKUP_CALL_TIMEOUT_MS]。 */
    private val callTimeoutMs: Long = BACKUP_CALL_TIMEOUT_MS,
) : JsPluginAdapter(runtime, pluginContext), BackupPlugin {

    /** 备份扩展点统一入口：长耗时、超时只中止本次调用（不中毒）。 */
    private fun callBackup(path: String, vararg args: Any?): Any? =
        runtime.callAsync(
            path, *args,
            timeoutMs = callTimeoutMs,
            poisonOnTimeout = false,
            reportTimeoutOnAbort = true,
        )

    override suspend fun pushBackup(name: String, archive: File): BackupResult =
        withContext(Dispatchers.IO) {
            val store = blobStore
                ?: return@withContext BackupResult(ok = false, message = "宿主未提供流式传输能力")
            // 所有权仍属调用方（BackupManager）：release 只注销句柄，不删文件
            val blobId = store.register(pluginContext.pluginInfo.id, archive, owned = false)
            try {
                val result = callBackup(
                    JsPluginContract.PATH_BACKUP_PUSH,
                    mapOf("name" to name, "size" to archive.length(), "archiveId" to blobId)
                )
                parseBackupResult(result)
            } catch (e: Exception) {
                Log.e("JsBackup", "pushBackup failed", e)
                BackupResult(ok = false, message = e.message ?: "pushBackup failed")
            } finally {
                store.release(blobId)
            }
        }

    override suspend fun pullBackup(id: String): BackupDownload? = withContext(Dispatchers.IO) {
        try {
            val result = callBackup(JsPluginContract.PATH_BACKUP_PULL, id)
            val blobId = (JsScriptRuntime.jsToKotlin(result) as? String)?.takeIf { it.isNotEmpty() }
                ?: return@withContext null
            val store = blobStore ?: return@withContext null
            val file = store.resolve(pluginContext.pluginInfo.id, blobId)
                ?: return@withContext null
            BackupDownload(blobId = blobId, file = file, size = file.length())
        } catch (e: Exception) {
            Log.e("JsBackup", "pullBackup failed", e)
            null
        }
    }

    override fun releaseBackup(blobId: String) {
        blobStore?.release(blobId)
    }

    override suspend fun listBackups(): List<RemoteBackupEntry>? = withContext(Dispatchers.IO) {
        try {
            val result = callBackup(JsPluginContract.PATH_BACKUP_LIST)
            val items = JsScriptRuntime.jsToKotlin(result) as? List<*> ?: return@withContext null
            items.mapNotNull { item ->
                val map = JsScriptRuntime.jsToKotlin(item) as? Map<*, *> ?: return@mapNotNull null
                val id = map["id"]?.toString()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                RemoteBackupEntry(
                    id = id,
                    name = map["name"]?.toString() ?: id,
                    createdAt = normalizeRemoteTimestamp((map["createdAt"] as? Number)?.toLong() ?: 0L),
                    size = (map["size"] as? Number)?.toLong() ?: -1L
                )
            }
        } catch (e: Exception) {
            Log.e("JsBackup", "listBackups failed", e)
            null
        }
    }

    override suspend fun deleteBackup(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            (JsScriptRuntime.jsToKotlin(callBackup(JsPluginContract.PATH_BACKUP_REMOVE, id)) as? Boolean) ?: false
        } catch (e: Exception) {
            Log.e("JsBackup", "deleteBackup failed", e)
            false
        }
    }

    override suspend fun testConnection(): String? = withContext(Dispatchers.IO) {
        try {
            val result = callBackup(JsPluginContract.PATH_BACKUP_TEST)
            val v = JsScriptRuntime.jsToKotlin(result)
            when (v) {
                null -> null
                is Boolean -> if (v) null else "连接失败"
                else -> v.toString().takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            e.message ?: "connection test failed"
        }
    }

    /**
     * 设置表单按钮动作派发。
     *
     * 插件的「测试连接」按钮 key 是 [JsPluginContract.ACTION_TEST_CONNECTION]，它**不是**
     * 插件顶层函数，而是宿主能力动作：此处映射到本适配器的 [testConnection]（即 JS
     * `backup.test()`）。不映射的话基类会当作顶层函数查找并落空，导致按钮静默"成功"。
     */
    override suspend fun onAction(action: String): String? = when (action) {
        JsPluginContract.ACTION_TEST_CONNECTION -> testConnection()
        else -> super<JsPluginAdapter>.onAction(action)
    }

    /** pushBackup 返回值兼容两种形态：bool 直接映射，对象取 {ok, id, message}。 */
    private fun parseBackupResult(result: Any?): BackupResult {
        val v = JsScriptRuntime.jsToKotlin(result)
        if (v is Map<*, *>) {
            val ok = (v["ok"] as? Boolean) ?: false
            return BackupResult(
                ok = ok,
                id = v["id"]?.toString()?.takeIf { it.isNotEmpty() },
                message = v["message"]?.toString()?.takeIf { it.isNotEmpty() }
            )
        }
        return BackupResult(ok = (v as? Boolean) ?: false)
    }

    companion object {
        /**
         * 备份扩展点调用上限（5 分钟）。
         *
         * 整包上传/下载是"真实耗时可到几十秒到几分钟"的网络操作，而不是脚本计算：
         * 通用 180s 在看门狗眼里和"死循环"无法区分（issue #1244）。这里放宽到 5 分钟兜底，
         * 真正的卡顿仍由 HTTP 层超时与用户取消兜住。
         */
        internal const val BACKUP_CALL_TIMEOUT_MS = 5 * 60_000L

        /**
         * 远端时间戳归一化：契约是**毫秒**，但插件可能返回**秒**。
         *
         * 真机反馈"列表里创建日期显示 1970 年"就是这么来的：webdav-backup v3.0.0 的
         * `epochFromParts` 返回秒（1.76e9），宿主当毫秒渲染 → 1970-01-21。
         * 插件已修，这里再兜一层：**已安装的旧 xipk 不重装也能显示正确时间**。
         *
         * 阈值 1e11：秒级时间戳要到 5138 年才会超过它，而毫秒级（2000 年后 ≥ 9.4e11）远大于它；
         * 0/负数（远端没给时间）原样保留，由 UI 按"未知"处理。
         */
        internal fun normalizeRemoteTimestamp(value: Long): Long =
            if (value in 1..99_999_999_999L) value * 1000 else value
    }
}
