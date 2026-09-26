package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.api.AsrPluginListener
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.js.ws.WsHostApi
import com.kingzcheung.xime.plugin.core.js.ws.WsHostListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 验证 funasr-asr JS 版（v3）：全部 ASR 逻辑（状态机/prebuffer/协议）在 JS，
 * 宿主只提供通用 WebSocket 原语（mock 验证 JS 对 host.ws 的使用）。
 *
 * v3 契约：宿主调用路径为 speech.*（start/feed/stop/cancel/isConfigured）+ settings.schema；
 * WS 事件投递到 plugin.ws.onOpen/onMessage/onClose/onError 回调槽。
 *
 * 测试与发布同源：载入 xipm build 产物 build/plugin-js/funasr-asr/main.js。
 */
class JsAsrPluginTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    private class MockWsHostApi : WsHostApi {
        val sentTexts = mutableListOf<String>()
        val sentBinaries = mutableListOf<ByteArray>()
        var connectedUrl: String? = null
        var connectedHeaders: Map<String, String> = emptyMap()
        var hostListener: WsHostListener? = null
        var closeCount = 0

        override fun connect(url: String, headers: Map<String, String>, listener: WsHostListener): Boolean {
            connectedUrl = url
            connectedHeaders = headers
            hostListener = listener
            return true
        }
        override fun sendText(message: String): Boolean { sentTexts.add(message); return true }
        override fun sendBinary(data: ByteArray): Boolean { sentBinaries.add(data); return true }
        override fun close() { closeCount++ }
        override fun getState(): Int = 2
        override fun lastError(): String? = null
    }

    private class ResultCollector : AsrPluginListener {
        var finalText = ""
        var partialText = ""
        var error: String? = null
        override fun onFinal(text: String) { finalText = text }
        override fun onPartial(text: String) { partialText = text }
        override fun onError(message: String) { error = message }
    }

    /** 载入真实插件产物（xipm build 输出），测试与发布同源。 */
    private fun writePlugin(): File {
        val dir = tmp.newFolder()
        pluginSourceFile().copyTo(File(dir, "main.js"))
        return dir
    }

    private fun pluginSourceFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "build/plugin-js/funasr-asr/main.js")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("找不到 build/plugin-js/funasr-asr/main.js，请先运行 xipm build")
    }

    private fun awaitUntil(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("等待条件超时", condition())
    }

    @Test
    fun `funasr js plugin owns all asr logic`() {
        val dir = writePlugin()
        val mock = MockWsHostApi()
        val store = InMemoryConfigStore()
        val runtime = JsScriptRuntime(
            "com.kingzcheung.xime.plugin.funasr_asr",
            dir, "main.js", store,
            wsHostApi = mock,
            injectAsr = true
        )
        try {
            assertTrue("main.js 应能加载", runtime.load())

            val schema = (runtime.call("settings.schema") as? List<*>) ?: emptyList<Any?>()
            assertTrue("schema 应导出 apiKey 设置", (schema.map { (it as? Map<*, *>)?.get("key")?.toString() }).contains("apiKey"))
            assertTrue("未配置时不就绪", runtime.call("speech.isConfigured") != true)

            val collector = ResultCollector()
            runtime.asrResultCallback = collector

            // 未配置 apiKey → start 失败并 emitError
            assertFalse("start 应失败（未配置）", runtime.callAsync("speech.start") == true)
            awaitUntil { collector.error != null }
            assertEquals("未配置 API Key，请在插件设置中填写", collector.error)

            // 配置后 start → JS 用 host.ws 发起连接
            store.set("apiKey", "test-key-123")
            assertTrue("start 应成功", runtime.callAsync("speech.start") == true)
            assertTrue("连接地址应为 dashscope 白名单域名", mock.connectedUrl?.contains("dashscope.aliyuncs.com") == true)
            assertEquals("Bearer test-key-123", mock.connectedHeaders["Authorization"])

            // onWsOpen 槽 → JS 组装 run-task（官方格式：task_group/task/function/model 在 payload）
            mock.hostListener?.onOpen()
            awaitUntil { mock.sentTexts.isNotEmpty() }
            val runTask = mock.sentTexts[0]
            assertTrue("run-task 含 action", runTask.contains("\"action\":\"run-task\""))
            assertTrue("payload 含 task_group", runTask.contains("\"task_group\":\"audio\""))
            assertTrue("payload 含 model", runTask.contains("\"model\":\"qwen-audio-3.1-asr-flash-streaming\""))
            assertTrue("payload 含 function", runTask.contains("\"function\":\"recognition\""))
            assertTrue("payload 含 sample_rate", runTask.contains("\"sample_rate\":16000"))

            // 音频提交给 JS：task-started 前缓冲（不经宿主直接发）
            runtime.callAsync("speech.feed", byteArrayOf(1, 2, 3, 4))
            assertTrue("task-started 前不应直发音频", mock.sentBinaries.isEmpty())

            // task-started → JS 冲刷 prebuffer
            mock.hostListener?.onMessage(
                """{"header":{"event":"task-started","task_id":"t1"},"payload":{}}"""
            )
            awaitUntil { mock.sentBinaries.isNotEmpty() }
            assertEquals("task-started 后冲刷缓冲音频", 1, mock.sentBinaries.size)

            // 音频直发（audioReady）
            runtime.callAsync("speech.feed", byteArrayOf(5, 6, 7, 8))
            assertEquals("audioReady 后直发", 2, mock.sentBinaries.size)

            // result-generated（partial / final）→ JS 解析并 emit
            mock.hostListener?.onMessage(
                """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"你好","sentence_end":false}}}}"""
            )
            awaitUntil { collector.partialText == "你好" }
            assertEquals("partial 结果", "你好", collector.partialText)

            mock.hostListener?.onMessage(
                """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"text":"你好世界","sentence_end":true}}}}"""
            )
            awaitUntil { collector.finalText == "你好世界" }
            assertEquals("final 结果", "你好世界", collector.finalText)

            // heartbeat 消息应被忽略
            mock.hostListener?.onMessage(
                """{"header":{"event":"result-generated"},"payload":{"output":{"sentence":{"heartbeat":true}}}}"""
            )
            assertTrue("heartbeat 不产生结果", collector.finalText == "你好世界")

            // task-failed → JS 解析并 emitError
            mock.hostListener?.onMessage(
                """{"header":{"event":"task-failed","error_code":"InvalidParameter","error_message":"bad param"},"payload":{}}"""
            )
            awaitUntil { (collector.error ?: "").contains("InvalidParameter") }
            assertTrue("task-failed 应上报错误", (collector.error ?: "").contains("InvalidParameter"))

            // stop → JS 发 finish-task
            runtime.callAsync("speech.stop")
            assertTrue("stop 发送 finish-task", mock.sentTexts.any { it.contains("\"action\":\"finish-task\"") })
        } finally {
            runtime.close()
        }
    }
}