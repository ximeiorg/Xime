package com.kingzcheung.xime.plugin.core.security

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 插件错误日志（内存环形缓冲 + 可插拔持久化）。
 *
 * - 内存：每插件最多保留 [MAX_ERRORS_PER_PLUGIN] 条，插件中心 UI 实时读取。
 * - 持久化：注册 [PluginErrorStore] 后，新错误异步落盘、启动时恢复（进程重启不丢）。
 *   未注册 store 时退化为纯内存（测试/旧宿主行为不变）。
 * - 数据面：错误带 [ErrorCategory]，UI 可渲染用户可读的原因与建议
 *   （[userMessage]/[userHint]），终端的[用户带不出信息]问题靠"复制诊断信息"闭环解决。
 */
object PluginErrorLog {

    private const val TAG = "PluginErrorLog"
    private const val MAX_ERRORS_PER_PLUGIN = 20

    /** 持久化存取接口（app 层实现，如文件 JSONL）。实现无需线程安全：宿主串行调用。 */
    interface PluginErrorStore {
        /** 启动时全量恢复（返回历史错误，顺序任意）。 */
        fun load(): List<PluginError>

        /** 追加一条错误。 */
        fun append(pluginId: String, error: PluginError)

        /** 清除某插件全部错误（含持久化）。 */
        fun clearAll(pluginId: String)
    }

    data class PluginError(
        val timestamp: Long = System.currentTimeMillis(),
        val pluginId: String,
        val operation: String,
        val message: String,
        val stackTrace: String? = null,
        val category: ErrorCategory = ErrorCategory.OTHER
    )

    private val errorLogs = ConcurrentHashMap<String, MutableList<PluginError>>()

    @Volatile
    private var store: PluginErrorStore? = null

    /** 落盘专用单线程：文件 IO 不阻塞调用线程（JS 执行线程/IO 协程）。 */
    private val storeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "xime-plugin-error-store").apply { isDaemon = true }
    }

    /** 注册持久化 store 并恢复历史错误。仅首次生效（幂等，防止重复恢复）。 */
    fun initialize(store: PluginErrorStore?) {
        if (store == null || this.store != null) return
        this.store = store
        try {
            val loaded = store.load()
            if (loaded.isEmpty()) return
            errorLogs.clear()
            for (error in loaded) {
                errorLogs.getOrPut(error.pluginId) { mutableListOf() }.add(error)
            }
            // 恢复后按内存上限截断，避免历史累积拖垮 UI 与后续落盘
            errorLogs.values.forEach { trim(it) }
            Log.i(TAG, "Restored ${loaded.size} plugin errors from store")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore plugin errors from store", e)
            errorLogs.clear()
        }
    }

    /** 仅供测试：重置恢复状态与内存缓冲（生产代码不调用）。 */
    /** 仅供测试：重置恢复状态与内存缓冲（生产代码不调用）。 */
    internal fun resetForTest() {
        store = null
        errorLogs.clear()
    }

    fun logError(
        pluginId: String,
        operation: String,
        message: String,
        throwable: Throwable? = null,
        category: ErrorCategory = ErrorCategory.OTHER
    ) {
        Log.e(TAG, "[$pluginId] $operation: $message", throwable)

        val error = PluginError(
            pluginId = pluginId,
            operation = operation,
            message = message,
            stackTrace = throwable?.stackTraceToString(),
            category = category
        )

        errorLogs.getOrPut(pluginId) { mutableListOf() }.apply {
            add(error)
            trim(this)
        }

        store?.let { s ->
            try {
                storeExecutor.execute { s.append(pluginId, error) }
            } catch (e: Exception) {
                Log.e(TAG, "store.append 提交失败（不影响内存记录）", e)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun getErrors(pluginId: String): List<PluginError> {
        return errorLogs[pluginId]?.toList() ?: emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    fun getAllErrors(): Map<String, List<PluginError>> {
        return errorLogs.mapValues { it.value.toList() }
    }

    fun clearErrors(pluginId: String) {
        errorLogs.remove(pluginId)
        store?.let { s ->
            try {
                storeExecutor.execute { s.clearAll(pluginId) }
            } catch (e: Exception) {
                Log.e(TAG, "store.clearAll 提交失败（不影响内存清除）", e)
            }
        }
    }

    fun clearAllErrors() {
        errorLogs.clear()
    }

    fun hasErrors(pluginId: String): Boolean {
        return errorLogs[pluginId]?.isNotEmpty() ?: false
    }

    fun getLastError(pluginId: String): PluginError? {
        return errorLogs[pluginId]?.lastOrNull()
    }

    private fun trim(list: MutableList<PluginError>) {
        if (list.size > MAX_ERRORS_PER_PLUGIN) {
            val overflow = list.size - MAX_ERRORS_PER_PLUGIN
            list.subList(0, overflow).clear()
        }
    }

    // ---- 用户可读文案（终端用户导向，非技术细节） ----

    /** 可读的失败原因（一句话，面向用户）。 */
    fun userMessage(error: PluginError): String {
        return when (error.category) {
            ErrorCategory.SCRIPT_ERROR ->
                "插件脚本执行出错（${error.operation}）"
            ErrorCategory.NETWORK_DENIED -> "插件尝试访问未授权的服务器，已被阻止"
            ErrorCategory.HTTP_ERROR -> "插件网络请求失败（${error.operation}）"
            ErrorCategory.TIMEOUT_POISONED -> "插件执行超时，已停止运行"
            ErrorCategory.TIMEOUT -> "插件执行超时，本次操作已中止"
            ErrorCategory.STREAM_ERROR -> "插件网络连接中断"
            ErrorCategory.OTHER -> "插件运行出现问题（${error.operation}）"
        }
    }

    /** 处理建议（面向用户）。 */
    fun userHint(error: PluginError): String {
        return when (error.category) {
            ErrorCategory.SCRIPT_ERROR ->
                "请更新插件，或联系插件作者并附上诊断信息（含下方技术详情）。"
            ErrorCategory.NETWORK_DENIED ->
                "如需该服务器，请到插件中心进入本插件，在「网络访问」中完成授权后再试。"
            ErrorCategory.HTTP_ERROR ->
                "请检查网络连接或服务器状态后重试。"
            ErrorCategory.TIMEOUT_POISONED ->
                "请重新加载插件；若反复出现请更新插件或联系作者（疑似死循环）。"
            ErrorCategory.TIMEOUT ->
                "请重试；若反复出现，请检查网络连接或更新插件。"
            ErrorCategory.STREAM_ERROR ->
                "网络恢复后请重试；若频繁中断请联系插件作者。"
            ErrorCategory.OTHER ->
                "请查看下方技术详情；必要时联系插件作者并附诊断信息。"
        }
    }
}