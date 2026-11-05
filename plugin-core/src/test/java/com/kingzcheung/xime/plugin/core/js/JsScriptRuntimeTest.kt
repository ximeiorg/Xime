package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.security.ErrorCategory
import com.kingzcheung.xime.plugin.core.security.PluginErrorLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 测试用内存配置（Noop 的 set 是空操作，无法验证 host.config）。 */
private class MemoryConfigStore : PluginConfigStore {
    private val map = LinkedHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun set(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys
}

class JsScriptRuntimeTest {

    @After
    fun tearDown() {
        // 清理可能残留的错误日志（测试用独立 pluginId）
        listOf(
            "js-isolation-a", "js-isolation-b",
            "js-timeout", "js-timeout-nopoison", "js-error", "js-missing-entry", "js-host-api"
        ).forEach { PluginErrorLog.clearErrors(it) }
    }

    private fun runtimeFor(script: String, pluginDir: File, pluginId: String = "test"): JsScriptRuntime {
        val entry = File(pluginDir, "main.js")
        pluginDir.mkdirs()
        entry.writeText(script)
        return JsScriptRuntime(pluginId, pluginDir, "main.js", MemoryConfigStore())
    }

    @Test
    fun `loads main js and calls exported functions`() {
        val dir = File("build/js-runtime-basic"); dir.mkdirs()
        val runtime = runtimeFor(
            """
            globalThis.plugin = {
              getCategories: function() { return ['颜文字']; },
              getEmojis: function(query) {
                var out = [];
                for (var i = 0; i < query.topK; i++) {
                  out.push({ id: 'kaomoji_' + i, text: '(•ω•)' + i });
                }
                return out;
              }
            }
            """.trimIndent(),
            dir
        )
        assertTrue("main.js 应能加载", runtime.load())

        val categories = runtime.call("getCategories") as? List<*>
        assertEquals(listOf("颜文字"), categories?.map { it.toString() })

        val emojis = runtime.call(
            "getEmojis",
            mapOf("keyword" to "", "topK" to 5)
        ) as? List<*>
        assertEquals("应返回 topK=5 个", 5, emojis?.size)
        val first = emojis?.first() as? Map<*, *>
        assertEquals("kaomoji_0", first?.get("id")?.toString())
        assertTrue("text 非空", !first?.get("text").toString().isNullOrEmpty())
        runtime.close()
    }

    @Test
    fun `sandbox blocks eval and Function constructor`() {
        val dir = File("build/js-sandbox"); dir.mkdirs()
        val runtime = runtimeFor(
            """
            globalThis.plugin = {
              getTest: function() {
                try { return eval('1+1'); } catch (e) { return 'eval-blocked'; }
              },
              getFunction: function() {
                try { return Function('return 1')(); } catch (e) { return 'fn-blocked'; }
              }
            }
            """.trimIndent(),
            dir
        )
        runtime.load()
        assertEquals("eval 应被屏蔽", "eval-blocked", runtime.call("getTest")?.toString())
        assertEquals("Function 构造器应被屏蔽", "fn-blocked", runtime.call("getFunction")?.toString())
        runtime.close()
    }

    @Test
    fun `missing plugin export object is a load failure`() {
        val dir = File("build/js-no-export"); dir.mkdirs()
        val runtime = runtimeFor("globalThis.other = 42", dir)
        assertTrue("未导出 plugin 对象应加载失败", !runtime.load())
        val errors = PluginErrorLog.getErrors("test")
        assertTrue("应记录脚本错误", errors.isNotEmpty())
        assertEquals(ErrorCategory.SCRIPT_ERROR, errors.last().category)
        runtime.close()
    }

    @Test
    fun `each plugin gets an isolated engine`() {
        val dirA = File("build/js-iso-a"); dirA.mkdirs()
        val dirB = File("build/js-iso-b"); dirB.mkdirs()

        val runtimeA = runtimeFor(
            "globalThis.secret = 'PLUGIN_A'\nglobalThis.plugin = { getSecret: function() { return globalThis.secret; } }",
            dirA, "js-isolation-a"
        )
        val runtimeB = runtimeFor(
            "globalThis.plugin = { getSecret: function() { return typeof globalThis.secret === 'undefined' ? null : globalThis.secret; } }",
            dirB, "js-isolation-b"
        )
        runtimeA.load()
        runtimeB.load()

        assertEquals("PLUGIN_A", runtimeA.call("getSecret")?.toString())
        assertNull("插件 B 的引擎不应看到插件 A 的全局变量", runtimeB.call("getSecret"))
        runtimeA.close()
        runtimeB.close()
    }

    @Test
    fun `infinite loop times out and poisons runtime`() {
        val dir = File("build/js-timeout"); dir.mkdirs()
        val entry = File(dir, "main.js")
        entry.writeText("globalThis.plugin = { spin: function() { while (true) {} } }")
        val runtime = JsScriptRuntime(
            "js-timeout", dir, "main.js", MemoryConfigStore(),
            callTimeoutMs = 1_000, callbackTimeoutMs = 300
        )
        runtime.load()

        val start = System.currentTimeMillis()
        val result = runtime.call("spin")
        val elapsed = System.currentTimeMillis() - start
        assertNull("死循环应超时返回 null", result)
        assertTrue("超时应在限时附近返回，实际 ${elapsed}ms", elapsed >= 800 && elapsed < 15_000)
        // 超时日志要能看出是哪一次调用卡住（扩展点名进 message）
        val recorded = PluginErrorLog.getErrors("js-timeout").last()
        assertEquals(ErrorCategory.TIMEOUT_POISONED, recorded.category)
        assertTrue("超时日志应带调用名，实际：${recorded.message}", recorded.message.contains("spin"))

        // 中毒后：后续调用立即失败
        val second = runtime.call("spin")
        assertNull("中毒后调用应立即返回 null", second)
        runtime.close()
    }

    @Test
    fun `timeout without poison only aborts current call`() {
        val pluginId = "js-timeout-nopoison"
        val dir = File("build/js-timeout-nopoison"); dir.mkdirs()
        File(dir, "main.js").writeText(
            "globalThis.plugin = { spin: function() { while (true) {} }, ping: function() { return 'pong'; } }"
        )
        val runtime = JsScriptRuntime(pluginId, dir, "main.js", MemoryConfigStore(), callTimeoutMs = 1_000)
        try {
            assertTrue(runtime.load())
            assertNull(
                runtime.call(
                    "spin", timeoutMs = 500,
                    poisonOnTimeout = false, reportTimeoutOnAbort = true,
                )
            )
            // 不中毒：后续调用照常执行（引擎中断已复位）
            assertEquals("pong", runtime.call("ping"))
            val recorded = PluginErrorLog.getErrors(pluginId).last()
            assertEquals(ErrorCategory.TIMEOUT, recorded.category)
            assertTrue("超时日志应带调用名，实际：${recorded.message}", recorded.message.contains("spin"))
        } finally {
            PluginErrorLog.clearErrors(pluginId)
            runtime.close()
        }
    }

    @Test
    fun `script error is recorded with category and line number`() {
        val pluginId = "js-error"
        val dir = File("build/js-err-line"); dir.mkdirs()
        val entry = File(dir, "main.js")
        entry.writeText(
            "globalThis.plugin = {\n" +
                "  boom: function() { throw new Error('boom'); }\n" +
                "}"
        )
        val runtime = JsScriptRuntime(pluginId, dir, "main.js", MemoryConfigStore())
        try {
            assertTrue(runtime.load())
            assertNull(runtime.call("boom"))

            val errors = PluginErrorLog.getErrors(pluginId)
            assertTrue("应记录脚本错误，实际 ${errors.size} 条", errors.isNotEmpty())
            val recorded = errors.last()
            assertEquals(ErrorCategory.SCRIPT_ERROR, recorded.category)
            assertTrue(
                "消息应含 main.js 行号，实际: ${recorded.message}",
                recorded.message!!.contains("main.js") && recorded.message.contains(":")
            )
        } finally {
            PluginErrorLog.clearErrors(pluginId)
            runtime.close()
        }
    }

    @Test
    fun `entry missing is recorded as script error`() {
        val pluginId = "js-missing-entry"
        val dir = File("build/js-missing-entry"); dir.mkdirs()
        val runtime = JsScriptRuntime(pluginId, dir, "main.js", MemoryConfigStore())
        try {
            assertTrue(!runtime.load())
            val errors = PluginErrorLog.getErrors(pluginId)
            assertTrue("缺少入口脚本应记录错误", errors.isNotEmpty())
            assertEquals(ErrorCategory.SCRIPT_ERROR, errors.last().category)
        } finally {
            PluginErrorLog.clearErrors(pluginId)
            runtime.close()
        }
    }

    @Test
    fun `host api config bin and native json are available`() {
        val dir = File("build/js-host-api"); dir.mkdirs()
        val runtime = runtimeFor(
            """
            globalThis.plugin = {
              readConfig: function() {
                host.config.set('k', 'v');
                return host.config.get('k');
              },
              encodeJson: function() { return JSON.stringify({ a: 1 }); },
              int32: function() { return host.bin.int32be(258); },
              log: function() { host.log('hello'); return 'ok'; }
            }
            """.trimIndent(),
            dir, "js-host-api"
        )
        runtime.load()
        assertEquals("v", runtime.call("readConfig")?.toString())
        assertEquals("{\"a\":1}", runtime.call("encodeJson")?.toString())
        val int32 = runtime.call("int32") as? ByteArray
        assertNotNull("int32be 应返回字节数组", int32)
        runtime.close()
    }
}