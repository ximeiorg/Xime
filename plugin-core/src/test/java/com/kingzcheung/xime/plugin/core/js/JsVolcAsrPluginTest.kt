package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.api.AsrPluginListener
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.js.ws.WsHostApi
import com.kingzcheung.xime.plugin.core.js.ws.WsHostListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * 验证 volc-asr JS 版（v3）：火山 bigmodel_async 二进制协议（gzip + 帧头/序列号）全部在 JS 承载，
 * 宿主仅提供 host.ws（onBinary 回调槽）与 host.zlib / host.bin 原语。
 *
 * v3 契约：宿主调用路径为 speech.*（start/feed/stop/cancel/isConfigured）+ settings.schema。
 *
 * 迁移要点：JS 从 WS 二进制回调收到 frame（Uint8Array），取子段时要先复制成独立
 * Uint8Array（new Uint8Array(frame.subarray(...))）再交给 host.zlib.gunzip——
 * subarray 视图经 quickjs 桥接后 Kotlin 侧无法解析成字节，gunzip 会返回空导致解析中断。
 *
 * 测试与发布同源：载入 xipm build 产物 build/plugin-js/volc-asr/main.js。
 */
class JsVolcAsrPluginTest {

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
        val sentBinaries = mutableListOf<ByteArray>()
        var connectedUrl: String? = null
        var connectedHeaders: Map<String, String> = emptyMap()
        var hostListener: WsHostListener? = null
        var closed = false

        /**
         * 建立独立 mock 会话并清除上一会话的关闭标记。
         *
         * Args:
         *   url: 新会话地址。
         *   headers: 鉴权与资源请求头。
         *   listener: 新会话事件回调。
         * Returns:
         *   已登记连接请求时为 true。
         */
        override fun connect(url: String, headers: Map<String, String>, listener: WsHostListener): Boolean {
            closed = false
            connectedUrl = url
            connectedHeaders = headers
            hostListener = listener
            return true
        }
        override fun sendText(message: String): Boolean = true
        override fun sendBinary(data: ByteArray): Boolean { sentBinaries.add(data); return true }
        override fun close() { closed = true }
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
            val candidate = File(dir, "build/plugin-js/volc-asr/main.js")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("找不到 build/plugin-js/volc-asr/main.js，请先运行 xipm build")
    }

    private fun awaitUntil(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("等待条件超时", condition())
    }

    private fun u32be(n: Int): ByteArray = byteArrayOf(
        (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()
    )

    private fun header(msgType: Int, flags: Int, ser: Int, comp: Int): ByteArray =
        byteArrayOf(
            0x11,
            ((msgType shl 4) or flags).toByte(),
            ((ser shl 4) or comp).toByte(),
            0
        )

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    /** 构造服务端 FULL_SERVER_RESP 帧（flags 控制是否带 seq/event/末包标志）。 */
    private fun serverResp(json: String, flags: Int): ByteArray {
        val payload = gzip(json.toByteArray(Charsets.UTF_8))
        var body = ByteArray(0)
        if (flags and 0x1 != 0) body += u32be(0) // sequence
        if (flags and 0x4 != 0) body += u32be(0) // event
        body += u32be(payload.size)
        body += payload
        return header(0x9, flags, 0x1, 0x1) + body
    }

    /** 构造服务端 ERROR 帧。 */
    private fun serverError(code: Int, msg: String): ByteArray {
        val m = msg.toByteArray(Charsets.UTF_8)
        val body = u32be(code) + u32be(m.size) + m
        return header(0xF, 0, 0, 0) + body
    }

    @Test
    fun `volc js plugin owns bigmodel_async binary protocol`() {
        val dir = writePlugin()
        val mock = MockWsHostApi()
        val store = InMemoryConfigStore()
        val runtime = JsScriptRuntime(
            "com.kingzcheung.xime.plugin.volc_asr",
            dir, "main.js", store,
            wsHostApi = mock,
            injectAsr = true
        )
        try {
            assertTrue("main.js 应能加载", runtime.load())

            assertTrue("未配置时不就绪", runtime.call("speech.isConfigured") != true)

            // 设置 schema 非空（插件中心渲染表单的前提）
            val schema = (runtime.call("settings.schema") as? List<*>) ?: emptyList<Any?>()
            val schemaFields = schema.map { it as Map<*, *> }
            assertTrue("getSettingsSchema 应非空", schema.isNotEmpty())
            val schemaKeys = schemaFields.mapNotNull { it["key"]?.toString() }
            assertTrue("schema 应含 apiKey", schemaKeys.contains("apiKey"))
            assertTrue("schema 应含 resourceId", schemaKeys.contains("resourceId"))
            val apiKeyField = schemaFields.first { it["key"]?.toString() == "apiKey" }
            val appKeyField = schemaFields.first { it["key"]?.toString() == "appKey" }
            assertNull("apiKey 未声明 required（默认必填）", apiKeyField["required"])
            assertFalse("appKey 旧鉴权应为可选", appKeyField["required"] == true)

            val collector = ResultCollector()
            runtime.asrResultCallback = collector

            // 未配置 → start 失败并 emitError
            assertFalse("start 应失败（未配置）", runtime.callAsync("speech.start") == true)
            awaitUntil { collector.error != null }
            assertEquals("未配置 API Key，请在插件设置中填写", collector.error)

            // 配置后 start → 连接 openspeech 域名，带鉴权头
            store.set("apiKey", "test-api-key")
            assertTrue("start 应成功", runtime.callAsync("speech.start") == true)
            assertTrue("连接地址应为火山域名", mock.connectedUrl?.contains("openspeech.bytedance.com") == true)
            assertEquals("test-api-key", mock.connectedHeaders["X-Api-Key"])
            assertEquals("volc.seedasr.sauc.duration", mock.connectedHeaders["X-Api-Resource-Id"])
            assertTrue("应带 X-Api-Request-Id", mock.connectedHeaders["X-Api-Request-Id"].isNullOrEmpty().not())
            assertEquals("应带 X-Api-Sequence=-1", "-1", mock.connectedHeaders["X-Api-Sequence"])
            assertTrue("应带 X-Api-Connect-Id", mock.connectedHeaders["X-Api-Connect-Id"].isNullOrEmpty().not())

            // onWsOpen 槽 → JS 组装 full client request 帧（0x1, flags=POS_SEQUENCE, json, gzip）
            mock.hostListener?.onOpen()
            awaitUntil { mock.sentBinaries.isNotEmpty() }
            assertEquals("onWsOpen 应发送 full client request", 1, mock.sentBinaries.size)
            val full = mock.sentBinaries[0]
            assertEquals("帧头[0] 协议版本+头长", 0x11, full[0].toInt() and 0xFF)
            assertEquals("帧头[1] 消息类型 0x1+POS_SEQUENCE", (0x1 shl 4) or 0x1, full[1].toInt() and 0xFF)
            assertEquals("帧头[2] json+gzip", (0x1 shl 4) or 0x1, full[2].toInt() and 0xFF)
            assertTrue("full request 应带 seq", readInt32(full, 4) >= 1)
            val fullSize = readU32(full, 8)
            assertEquals("payload 长度字段正确", full.size - 12, fullSize)
            val fullJson = String(gunzip(full.copyOfRange(12, 12 + fullSize)), Charsets.UTF_8)
            assertTrue("full request 含 model_name", fullJson.contains("\"model_name\":\"bigmodel\""))
            assertTrue("full request 含音频格式", fullJson.contains("\"rate\":16000"))

            // audioReady 后音频直发（0x2, POS_SEQUENCE, raw, gzip）
            runtime.callAsync("speech.feed", byteArrayOf(1, 2, 3, 4))
            awaitUntil { mock.sentBinaries.size == 2 }
            assertEquals("audioReady 后直发音频帧", 2, mock.sentBinaries.size)
            val audio = mock.sentBinaries[1]
            assertEquals("音频帧消息类型 0x2", (0x2 shl 4) or 0x1, audio[1].toInt() and 0xFF)
            assertTrue(
                "音频帧 payload 应为 gzip",
                gzip(byteArrayOf(1, 2, 3, 4)).contentEquals(audio.copyOfRange(12, audio.size))
            )

            // partial 结果（flags=0x1 带 seq，无末包标志）
            mock.hostListener?.onBinary(serverResp("""{"result":{"text":"你好"}}""", flags = 0x1))
            awaitUntil { collector.partialText == "你好" }
            assertEquals("partial 结果", "你好", collector.partialText)
            assertTrue("partial 不应关闭连接", !mock.closed)

            // stop 在当前会话仍存活时发送末包，随后才接收服务端 final。
            runtime.callAsync("speech.stop")
            awaitUntil { mock.sentBinaries.size == 3 }
            val last = mock.sentBinaries.last()
            assertEquals("最后一包 flags=0x3", (0x2 shl 4) or 0x3, last[1].toInt() and 0xFF)
            assertTrue("最后一包 seq 应为负", readInt32(last, 4) < 0)

            // final 结果（flags=0x3 带 seq + 末包）→ emitFinal 并关闭连接
            mock.hostListener?.onBinary(serverResp("""{"result":{"text":"你好世界"}}""", flags = 0x3))
            awaitUntil { collector.finalText == "你好世界" }
            assertEquals("final 结果", "你好世界", collector.finalText)
            awaitUntil { mock.closed }
            assertTrue("final 后应关闭连接", mock.closed)

            // 服务端错误帧属于独立的新会话，配置发送完成后再注入错误。
            collector.error = null
            val sentBeforeReconnect = mock.sentBinaries.size
            assertTrue("错误测试的新会话应能启动", runtime.callAsync("speech.start") == true)
            assertFalse("新连接应重置 closed 标记", mock.closed)
            mock.hostListener?.onOpen()
            awaitUntil { mock.sentBinaries.size == sentBeforeReconnect + 1 }
            assertEquals(
                "错误会话应先发送 full client request",
                (0x1 shl 4) or 0x1,
                mock.sentBinaries.last()[1].toInt() and 0xFF
            )
            mock.hostListener?.onBinary(serverError(45000001, "auth failed"))
            awaitUntil { (collector.error ?: "").contains("45000001") }
            assertTrue("错误应上报", (collector.error ?: "").contains("45000001"))
            awaitUntil { mock.closed }
            assertTrue("服务端错误后应关闭新会话", mock.closed)
        } finally {
            runtime.close()
        }
    }

    private fun readU32(arr: ByteArray, offset: Int): Int =
        ((arr[offset].toInt() and 0xFF) shl 24) or
            ((arr[offset + 1].toInt() and 0xFF) shl 16) or
            ((arr[offset + 2].toInt() and 0xFF) shl 8) or
            (arr[offset + 3].toInt() and 0xFF)

    private fun readInt32(arr: ByteArray, offset: Int): Int {
        val v = ((arr[offset].toLong() and 0xFF) shl 24) or
            ((arr[offset + 1].toLong() and 0xFF) shl 16) or
            ((arr[offset + 2].toLong() and 0xFF) shl 8) or
            (arr[offset + 3].toLong() and 0xFF)
        return v.toInt()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        java.util.zip.GZIPInputStream(data.inputStream()).readBytes()
}
