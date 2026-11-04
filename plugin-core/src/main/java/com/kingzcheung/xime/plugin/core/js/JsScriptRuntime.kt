package com.kingzcheung.xime.plugin.core.js

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.JsObject
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.js.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.js.http.HttpResponse
import com.kingzcheung.xime.plugin.core.js.http.SseHostApi
import com.kingzcheung.xime.plugin.core.js.http.SseHostListener
import com.kingzcheung.xime.plugin.core.js.sdk.JsHostApi
import com.kingzcheung.xime.plugin.core.js.sdk.JsHostApiImpl
import com.kingzcheung.xime.plugin.core.js.sdk.JsPluginContract
import com.kingzcheung.xime.plugin.core.js.ws.WsHostApi
import com.kingzcheung.xime.plugin.core.js.ws.WsHostListener
import com.kingzcheung.xime.plugin.core.security.ErrorCategory
import com.kingzcheung.xime.plugin.core.security.PluginErrorLog
import com.dokar.quickjs.binding.ObjectBindingScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * JS 插件运行时（QuickJS，quickjs-kt 绑定）。
 *
 * ## 沙箱与隔离
 * - **一个插件一个 QuickJs 实例**：插件间 globalThis、全局变量完全隔离；
 *   一个插件的脚本错误不影响其他插件。
 * - **QuickJS 无 io/os/网络/反射**：没有文件系统、网络栈、Java 反射能力，
 *   插件只能经注入的 `host` 白名单（见 [JsHostApi] + 网络/加密/数据类子表）触及宿主。
 * - **宿主关闭 eval**：全局 eval/Function 构造器被禁用，防注入式代码执行。
 *
 * ## 入口
 * 插件包根目录的 `main.js` 定义全局对象 `plugin`（`globalThis.plugin = {...}`），
 * 宿主按约定方法名调用（见 [JsPluginContract]）。
 *
 * ## 值模型与 async（TS 范式，v3）
 * - 跨桥对象为普通 JS object（Kotlin Map），数组为 Array；字节一律 `Uint8Array`。
 * - host 网络/IO 服务为 async（`await host.http.request(...)`）：宿主桥在独立 job 线程池
 *   （asyncJobExecutor）执行，Promise settle 由 evaluate 驱动；失败 throw `XimeError`
 *   （code + message，无 lastError()）。纯计算 API（config/uuid/bin/crypto 哈希等）同步返回。
 * - 扩展点按需 async（panel.state/onAction、speech.*、clipboardSync.*、backup.*、
 *   onLoad/onUnload——宿主经 [callAsync] 走"发起 + 二次读取"）；transform.candidates、
 *   emoji.*、settings.* 必须同步（按键/渲染路径）。
 *
 * ## 超时与中毒
 * - 业务调用（load/call/onLoad/onUnload）硬超时 [CALL_TIMEOUT_MS]：引擎级
 *   `evaluationTimeoutMillis` 兜底死循环，超时后调用线程中断引擎、插件标记中毒
 *   （后续任何 JS 一律拒绝，直到宿主重载插件）。
 * - 超时只从任务**真正开始执行**时起算：排队等前一个调用不算超时；排队超过时限则放弃
 *   本次调用，且不中断正在执行的调用、不中毒（那不是插件代码的问题）。
 * - 以网络传输为主的长耗时扩展点（如 `backup.push`）可通过 [call]/[callAsync] 传更大的
 *   `timeoutMs`，并用 `poisonOnTimeout = false` 只中止本次调用（记录一次 TIMEOUT 错误，
 *   插件继续可用）。
 * - 回调槽（SSE/WS 事件）与事件通道：短超时兜底恶意死循环回调，**不中毒**。
 * - transformCandidates：hotPath 15ms 硬超时，超时报错回退原始候选（由调用方熔断）。
 */
class JsScriptRuntime(
    private val pluginId: String,
    private val pluginDir: File,
    private val entryScript: String,
    private val configStore: PluginConfigStore,
    hostApi: JsHostApi? = null,
    private val wsHostApi: WsHostApi? = null,
    private val httpHostApi: com.kingzcheung.xime.plugin.core.js.http.HttpHostApi? = null,
    private val cryptoHostApi: com.kingzcheung.xime.plugin.core.js.crypto.CryptoHostApi? = null,
    private val sseHostApi: SseHostApi? = null,
    private val quickSendHostApi: QuickSendHostApi? = null,
    private val clipboardHostApi: ClipboardHostApi? = null,
    /** speech 型插件才注入 `host.asr` 上行表（emitFinal/emitPartial/...）；
     *  非 speech 型不注入，保持"未声明的 API 不存在"的横切门禁口径。 */
    private val injectAsr: Boolean = false,
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS,
    private val callbackTimeoutMs: Long = CALLBACK_TIMEOUT_MS
) {

    companion object {
        private const val TAG = "JsRuntime"

        /** 插件业务调用（load/call/onLoad/onUnload）超时；超时后插件标记中毒不再执行。 */
        private const val CALL_TIMEOUT_MS = 180_000L

        /** JS 执行线程名前缀（[executor] 单线程）：[awaitIdle] 据此避免自等死锁。 */
        private const val JS_THREAD_PREFIX = "xime-js-"

        /** 网络回调（SSE/WS 事件）超时：回调应短促，恶意死循环回调兜底。 */
        private const val CALLBACK_TIMEOUT_MS = 5_000L

        /** 候选词变换（hotPath）超时：调用发生在按键路径，超时即回退原始候选（不中毒）。 */
        const val TRANSFORM_TIMEOUT_MS = 15L

        /** 候选词变换响应候选总数上限（超限截断，防插件撑爆候选栏）。 */
        const val TRANSFORM_MAX_CANDIDATES = 20

        /** zlib.gunzip 解压输出上限（防压缩炸弹撑爆内存）。 */
        private const val MAX_GUNZIP_OUTPUT_BYTES = 16 * 1024 * 1024

        /** require 模块单文件大小上限（模块是脚本，超限拒绝加载）。 */
        private const val MAX_MODULE_BYTES = 2L * 1024 * 1024

        // ---- 网络回调槽路径（ws.onOpen / sse.onData 等，见 JsPluginContract.SLOT_*） ----
        const val CB_ON_WS_OPEN = JsPluginContract.SLOT_WS_OPEN
        const val CB_ON_WS_MESSAGE = JsPluginContract.SLOT_WS_MESSAGE
        const val CB_ON_WS_BINARY = JsPluginContract.SLOT_WS_BINARY
        const val CB_ON_WS_ERROR = JsPluginContract.SLOT_WS_ERROR
        const val CB_ON_WS_CLOSE = JsPluginContract.SLOT_WS_CLOSE
        const val CB_ON_SSE_DATA = JsPluginContract.SLOT_SSE_DATA
        const val CB_ON_SSE_DONE = JsPluginContract.SLOT_SSE_DONE
        const val CB_ON_SSE_ERROR = JsPluginContract.SLOT_SSE_ERROR

        /** 全局 eval 屏蔽引导脚本（宿主内部使用，插件不可见）。 */
        internal const val BOOTSTRAP_SCOPE = "__ximeHost"

        /**
         * 二进制参数槽函数名（表达式内形如 `__ximeHost.bin(0)`）。
         *
         * 大字节参数**不进 JS 源码**：表达式只携带下标，真实字节经
         * [JsScriptRuntime.pendingBinaryArgs] 在 evaluate 前置入宿主槽、执行后清空，
         * 由该函数以 `UByteArray` 原生返回给 JS（quickjs-kt 映射为 `Uint8Array`）。
         * 历史实现把字节 base64 后拼进 JS 源码求值，N 字节包会同时驻留多份 1.33N 字符串
         * （实测存活集约 7 倍包体），大备份包直接 OOM。
         */
        internal const val BIN_SLOT_FN = "bin"

        /** JS 值 → Kotlin（quickjs 返回的 JsObject/List/UByteArray/原生类型 → 纯 Kotlin 结构）。 */
        fun jsToKotlin(value: Any?): Any? = when (value) {
            null -> null
            is JsObject -> {
                val map = LinkedHashMap<String, Any?>()
                for ((k, v) in value) map[k] = jsToKotlin(v)
                map
            }
            is Map<*, *> -> {
                val map = LinkedHashMap<String, Any?>()
                for ((k, v) in value) map[k.toString()] = jsToKotlin(v)
                map
            }
            is List<*> -> value.map { jsToKotlin(it) }
            is UByteArray -> value.toByteArray()
            is ByteArray -> value
            is Set<*> -> value.map { jsToKotlin(it) }
            else -> value
        }

        /**
         * Kotlin 值 → 内嵌 JS 表达式的字面量（null/bool/数字/JSON 字符串/对象/数组/字节）。
         *
         * 字节不序列化进源码：收集到 [sink]（顺序即槽下标），表达式内以
         * `__ximeHost.bin(i)` 引用，见 [BIN_SLOT_FN]。
         */
        fun kotlinToJs(value: Any?, sink: MutableList<ByteArray>): String = when (value) {
            null -> "null"
            is Boolean -> if (value) "true" else "false"
            is ByteArray -> binExpr(value, sink)
            is UByteArray -> binExpr(value.toByteArray(), sink)
            is Int, is Long -> value.toString()
            is Double -> formatNumber(value)
            is Float -> formatNumber(value.toDouble())
            is Number -> value.toDouble().toString()
            is String -> jsStringLiteral(value)
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                jsStringLiteral(k.toString()) + ":" + kotlinToJs(v, sink)
            }
            is List<*> -> value.joinToString(prefix = "[", postfix = "]") { kotlinToJs(it, sink) }
            else -> throw IllegalArgumentException("不支持的 JS 参数类型: ${value.javaClass.name}")
        }

        /** 字符串 → JS 字符串字面量（标准 JSON 转义，JSON 字符串是合法 JS 字面量）。 */
        internal fun jsStringLiteral(s: String): String =
            literalJson.encodeToString(String.serializer(), s)

        private val literalJson: Json by lazy { Json { encodeDefaults = true } }

        /** 数字转 JS 字面量：整数值不写小数点；非有限值回退 null。 */
        private fun formatNumber(d: Double): String {
            if (!d.isFinite()) return "null"
            return if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 9.007199254740992E15) {
                d.toLong().toString()
            } else {
                d.toString()
            }
        }

        /** 字节 → 槽位引用表达式（O(1) 长度，与包体大小无关）。 */
        private fun binExpr(bytes: ByteArray, sink: MutableList<ByteArray>): String {
            sink.add(bytes)
            return "$BOOTSTRAP_SCOPE.$BIN_SLOT_FN(${sink.size - 1})"
        }
    }

    private val api: JsHostApi = hostApi ?: JsHostApiImpl(pluginId, pluginDir, configStore)

    /** 网络回调槽（SSE/WS 事件）投递线程：与业务调用共享单线程执行器（串行）。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, JS_THREAD_PREFIX + pluginId).apply { isDaemon = true }
    }

    /**
     * async job 调度线程池（桥的 suspend 执行）。
     *
     * **不得复用 [executor]**：evaluate 在该线程执行并同步等待 async job（runBlocking），
     * 若 job 也调度到同一线程则自锁（job 排队等线程、evaluate 等 job）。
     * quickjs-kt 用内部 jsMutex 序列化 JS 状态访问，job 线程做 JS 操作（resolve promise）安全。
     */
    private val asyncJobExecutor = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "xime-js-job-$pluginId").apply { isDaemon = true }
    }

    /** QuickJS 引擎：JS 执行经 [executor] 串行；async job 在 [asyncJobExecutor]。 */
    private val engine: QuickJs = QuickJs.create(asyncJobExecutor.asCoroutineDispatcher())

    /**
     * 本次 evaluate 可用的二进制参数（表达式内 `__ximeHost.bin(i)` 按下标取用）。
     *
     * 调用方在 [runGuarded] 内先置入、执行完清空；`bin` 槽函数与 evaluate 都在
     * [executor] 线程执行，字段用不可变 List 整体替换（volatile）保证可见性，
     * 不给取消/超时路径留半清理状态。
     */
    @Volatile
    private var pendingBinaryArgs: List<ByteArray> = emptyList()

    /** 当前在途的宿主网络桥调用数：超时日志据此判断"卡在 JS 还是卡在网上"。 */
    private val activeHttpCalls = AtomicInteger(0)

    @Volatile
    private var loaded = false

    /**
     * 关闭标志：[close] 一开始就置位，此后一切调用按"无结果"处理，不再触碰引擎。
     *
     * 关闭与调用天然并发（设置页在组合期读 settings.schema，而插件可能正在被停用/重载）。
     * 旧实现只在 close() 末尾清 [loaded]，并直接 engine.close() + shutdownNow()：
     * 竞态窗口内被受理的调用会落到已关闭的引擎上，抛出
     * `QuickJsException: Already closed`，被记成"[脚本错误] 调用 settings.schema"，
     * 误导用户去更新插件；队列里尚未开始的调用则被 shutdownNow 取消，又被
     * [isInterruptLike] 判成超时并让插件中毒。
     */
    @Volatile
    private var closed = false

    /** 关闭期间被受理的调用：Callable 内二次判定后抛出，转为"无结果"（无栈，零开销）。 */
    private class RuntimeClosedSignal : RuntimeException(null, null, false, false)

    @Volatile
    private var poisoned = false

    private val pluginTableChecked = false

    /** ASR 插件后端设置的宿主结果回调（JS 的 host.asr.emit* 桥接目标）。 */
    @Volatile
    var asrResultCallback: com.kingzcheung.xime.plugin.core.api.AsrPluginListener? = null

    /** 宿主 WebSocket 白名单 API（app 层实现）。 */
    val wsApi: WsHostApi? = wsHostApi

    /** 已注入的 host 子能力（host.capabilities / host.has 数据源；init 期构建后只读）。 */
    private val injectedCapabilities = LinkedHashSet<String>()

    // ---- 下行事件（manifest capabilities.events 声明后启用） ----

    @Volatile
    private var subscribedEvents: Set<String> = emptySet()

    private var eventChannel: Channel<PluginEvent>? = null

    private var eventScope: CoroutineScope? = null

    /** SSE 会话 id 集合（用于 close 时统一清理）。 */
    private val activeSseSessions = ConcurrentHashMap.newKeySet<Int>()

    // ---- 初始化：宿主白名单注入 + 引导脚本 ----

    init {
        installHostBindings()
        installSandboxBootstrap()
    }

    /** 注入宿主白名单 API（JS `host` 命名空间，能力按宿主注入门禁）。 */
    private fun installHostBindings() {
        engine.define(JsPluginContract.GLOBAL_HOST) {
            function("sdkVersion") { _ -> api.sdkVersion }
            function("log") { args -> api.log(args.firstOrNull()?.toString() ?: ""); null }
            function("logError") { args -> api.logError(args.firstOrNull()?.toString() ?: ""); null }

            injectedCapabilities.add("config")
            define("config") {
                function("get") { args -> api.configGet(str(args, 0)) }
                function("set") { args -> api.configSet(str(args, 0), str(args, 1)); null }
                function("remove") { args -> api.configRemove(str(args, 0)); null }
                function("keys") { _ -> api.configKeys().toList() }
            }

            injectedCapabilities.add("resource")
            define("resource") {
                function("path") { args -> api.resourcePath(str(args, 0)) }
                asyncFunction<Any?>(bridgeName("list")) { args ->
                    val names = withContext(Dispatchers.IO) { api.resourceList(str(args, 0)) }
                    asyncResult(true, JsPluginContract.ERR_IO, null, value = names)
                }
            }

            injectedCapabilities.add("uuid")
            function("uuid") { _ -> api.uuid() }

            injectedCapabilities.add("bin")
            define("bin") {
                function("int32be") { args -> int32be(num(args, 0).toInt()) }
                function("uint32be") { args -> int32be(num(args, 0).toInt()) }
            }

            injectedCapabilities.add("zlib")
            define("zlib") {
                asyncFunction<Any?>(bridgeName("gzip")) { args ->
                    val data = bytes(args, 0)
                    if (data == null) {
                        asyncResult(false, JsPluginContract.ERR_INVALID, "gzip 输入为空")
                    } else {
                        val out = withContext(Dispatchers.IO) { gzip(data) }
                        if (out == null) asyncResult(false, JsPluginContract.ERR_IO, "gzip 失败")
                        else asyncResult(true, JsPluginContract.ERR_IO, null, value = out)
                    }
                }
                asyncFunction<Any?>(bridgeName("gunzip")) { args ->
                    val data = bytes(args, 0)
                    if (data == null) {
                        asyncResult(false, JsPluginContract.ERR_INVALID, "gunzip 输入为空")
                    } else {
                        val out = withContext(Dispatchers.IO) { gunzip(data) }
                        if (out == null) asyncResult(false, JsPluginContract.ERR_IO, "解压失败（数据非法）")
                        else asyncResult(true, JsPluginContract.ERR_IO, null, value = out)
                    }
                }
            }

            if (httpHostApi != null || sseHostApi != null) {
                injectedCapabilities.add("http")
                define("http") { buildHttpTable() }
            }
            if (wsHostApi != null) {
                injectedCapabilities.add("ws")
                define("ws") { buildWsTable() }
            }
            if (cryptoHostApi != null) {
                injectedCapabilities.add("crypto")
                define("crypto") { buildCryptoTable() }
            }
            if (injectAsr) {
                injectedCapabilities.add("asr")
                define("asr") { buildAsrEmitTable() }
            }
            if (quickSendHostApi != null) {
                injectedCapabilities.add("quickSend")
                define("quickSend") { buildQuickSendTable() }
            }
            if (clipboardHostApi != null) {
                injectedCapabilities.add("clipboard")
                define("clipboard") { buildClipboardTable() }
            }
        }

        // 引导作用域：宿主内部 helper（不属于插件 API 面，插件不应直接调用）
        engine.define(BOOTSTRAP_SCOPE) {
            function("b64") { args ->
                // 宽松 base64 解码（容忍缺失 padding，供 atob polyfill 使用）
                val raw = str(args, 0).filterNot { it.isWhitespace() }
                val padded = when (raw.length % 4) {
                    0 -> raw
                    2 -> "$raw=="
                    3 -> "$raw="
                    else -> return@function null
                }
                try {
                    java.util.Base64.getDecoder().decode(padded).toUByteArray()
                } catch (e: Exception) {
                    null
                }
            }
            function("b64Encode") { args ->
                bytes(args, 0)?.let { java.util.Base64.getEncoder().encodeToString(it) }
            }
            // 二进制参数槽：表达式 `__ximeHost.bin(i)` 取本次调用的第 i 个字节载荷。
            // 越界/已清空返回 null（插件拿到 undefined，不会拿到上一次调用的残留字节）。
            function(BIN_SLOT_FN) { args ->
                pendingBinaryArgs.getOrNull(num(args, 0).toInt())?.toUByteArray()
            }
            function("utf8Encode") { args ->
                str(args, 0).toByteArray(Charsets.UTF_8).toUByteArray()
            }
            function("utf8Decode") { args ->
                bytes(args, 0)?.toString(Charsets.UTF_8)
            }
            function("capabilities") { _ -> injectedCapabilities.toList() }
            function("resolveModule") { args -> resolveModulePath(str(args, 0), str(args, 1)) }
            function("readModule") { args -> readModuleText(str(args, 0)) }
        }
    }

    /**
     * 沙箱引导：屏蔽 eval/Function、挂 require 模块系统。
     *
     * require 用闭包内保存的原始 Function 构造器编译模块（globalThis.Function 已屏蔽，
     * 插件代码不可见），模块包 CommonJS 语义（module/exports/缓存/相对路径）。
     */
    private fun installSandboxBootstrap() {
        val code = """
            (function () {
              var _Function = Function;
              Object.defineProperty(globalThis, 'eval', { value: undefined, writable: false, configurable: false });
              Object.defineProperty(globalThis, 'Function', { value: undefined, writable: false, configurable: false });

              var moduleCache = {};
              var moduleDirStack = [];
              function dirOf(p) { var i = p.lastIndexOf('/'); return i < 0 ? '' : p.substring(0, i); }

              globalThis.require = function (id) {
                var fromDir = moduleDirStack.length ? moduleDirStack[moduleDirStack.length - 1] : '';
                var resolved = globalThis.$BOOTSTRAP_SCOPE.resolveModule(String(id), fromDir);
                if (!resolved) throw new Error("Cannot find module '" + id + "'");
                var cached = moduleCache[resolved];
                if (cached) return cached.exports;
                var source = globalThis.$BOOTSTRAP_SCOPE.readModule(resolved);
                if (source === null || typeof source === 'undefined') {
                  throw new Error("Cannot read module '" + resolved + "'");
                }
                var module = { exports: {} };
                moduleCache[resolved] = module;
                moduleDirStack.push(dirOf(resolved));
                try {
                  new _Function('module', 'exports', 'require', source)
                    .call(module.exports, module, module.exports, globalThis.require);
                } catch (e) {
                  throw new Error('module ' + resolved + ' failed: ' + (e && e.message ? e.message : e));
                } finally {
                  moduleDirStack.pop();
                }
                return module.exports;
              };

              // ---- 插件工厂（类型恒等；SDK 类型见 xime-plugin.d.ts） ----
              if (typeof globalThis.definePlugin === 'undefined') {
                globalThis.definePlugin = function (spec) { return spec; };
              }

              // ---- 能力探测 ----
              // host.capabilities：已注入子表名列表；host.has(name)：探测单个能力。
              // 未声明的子能力不存在（undefined，与历史契约一致），推荐用 has 显式探测后降级。
              var caps = globalThis.$BOOTSTRAP_SCOPE.capabilities();
              Object.defineProperty(globalThis.host, 'capabilities', {
                value: caps, writable: false, configurable: true, enumerable: true
              });
              Object.defineProperty(globalThis.host, 'has', {
                value: function (name) { return caps.indexOf(String(name)) >= 0; },
                writable: false, configurable: true, enumerable: true
              });

              // ---- 宿主环境 polyfill ----
              // quickjs-kt 的 QuickJS 为精简构建：仅 ECMAScript 语言内建，
              // 不含 Web API 扩展（console/TextEncoder/atob 等），由宿主补齐常用项。
              if (typeof globalThis.console === 'undefined') {
                var emit = function (level, args) {
                  var parts = [];
                  for (var i = 0; i < args.length; i++) {
                    var a = args[i];
                    parts.push(typeof a === 'string' ? a : String(a));
                  }
                  // level 与 message 分离：logcat 级别/落盘 level 字段已表达级别，
                  // 不混入消息体（避免终端回显出现 [log] [log] 双前缀）
                  var msg = parts.join(' ');
                  if (level === 'error') { globalThis.host.logError(msg); }
                  else { globalThis.host.log(msg); }
                };
                globalThis.console = {
                  log: function () { emit('log', arguments); },
                  info: function () { emit('info', arguments); },
                  warn: function () { emit('warn', arguments); },
                  error: function () { emit('error', arguments); },
                  debug: function () { emit('debug', arguments); }
                };
              }

              if (typeof globalThis.TextEncoder === 'undefined') {
                var TextEncoderShim = function TextEncoder() { this.encoding = 'utf-8'; };
                TextEncoderShim.prototype.encode = function (s) {
                  return globalThis.$BOOTSTRAP_SCOPE.utf8Encode(s === undefined ? '' : String(s));
                };
                globalThis.TextEncoder = TextEncoderShim;
              }

              if (typeof globalThis.TextDecoder === 'undefined') {
                var TextDecoderShim = function TextDecoder(label) {
                  this.encoding = String(label || 'utf-8').toLowerCase();
                };
                TextDecoderShim.prototype.decode = function (input) {
                  if (input === undefined || input === null) return '';
                  var u8 = (input instanceof Uint8Array)
                    ? input
                    : new Uint8Array(input.buffer ? input.buffer : input);
                  return globalThis.$BOOTSTRAP_SCOPE.utf8Decode(u8) || '';
                };
                globalThis.TextDecoder = TextDecoderShim;
              }

              if (typeof globalThis.atob === 'undefined') {
                globalThis.atob = function (s) {
                  var bytes = globalThis.$BOOTSTRAP_SCOPE.b64(String(s));
                  if (bytes === null || bytes === undefined) {
                    throw new Error('InvalidCharacterError: atob failed to decode');
                  }
                  var out = '';
                  for (var i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
                  return out;
                };
              }

              if (typeof globalThis.btoa === 'undefined') {
                globalThis.btoa = function (s) {
                  var str = String(s);
                  var bytes = new Uint8Array(str.length);
                  for (var i = 0; i < str.length; i++) {
                    var c = str.charCodeAt(i);
                    if (c > 255) {
                      throw new Error('InvalidCharacterError: btoa input outside Latin1 range');
                    }
                    bytes[i] = c;
                  }
                  return globalThis.$BOOTSTRAP_SCOPE.b64Encode(bytes);
                };
              }

              // ---- 配置糖：getJson（键不存在/内容非法/空值均返回 null，不抛异常） ----
              if (typeof globalThis.host.config.getJson !== 'function') {
                globalThis.host.config.getJson = function (key) {
                  var raw = globalThis.host.config.get(String(key));
                  if (raw === null || raw === undefined || raw === '') return null;
                  try { return JSON.parse(raw); } catch (e) { return null; }
                };
              }

              // ---- TS 范式错误：XimeError（结构化 code + message，async 服务失败时 throw） ----
              if (typeof globalThis.${JsPluginContract.GLOBAL_XIME_ERROR} === 'undefined') {
                var XimeErrorCtor = function XimeError(code, message) {
                  var cause = Error.call(this, String(message === undefined ? '' : message));
                  this.message = cause.message;
                  this.name = '${JsPluginContract.GLOBAL_XIME_ERROR}';
                  this.code = String(code === undefined ? '${JsPluginContract.ERR_UNKNOWN}' : code);
                  if (cause.stack) { this.stack = cause.stack; }
                };
                XimeErrorCtor.prototype = Object.create(Error.prototype);
                XimeErrorCtor.prototype.constructor = XimeErrorCtor;
                Object.defineProperty(globalThis, '${JsPluginContract.GLOBAL_XIME_ERROR}', {
                  value: XimeErrorCtor, writable: false, configurable: true
                });
              }

              // ---- async 服务包装：原生判别式桥（__name）→ Promise / throw XimeError ----
              (function () {
                function wrap(raw, fallback) {
                  return async function () {
                    var r = await raw.apply(null, arguments);
                    if (r && r.ok) return r.value;
                    var err = (r && r.error) || {};
                    throw new globalThis.${JsPluginContract.GLOBAL_XIME_ERROR}(
                      err.code || '${JsPluginContract.ERR_UNKNOWN}',
                      err.message || fallback
                    );
                  };
                }
                var prefix = '${JsPluginContract.BRIDGE_PREFIX}';
                var tables = [
                  [globalThis.host.http, ['request', 'upload', 'download', 'stream', 'closeStream'], '网络操作失败'],
                  [globalThis.host.ws, ['connect', 'sendText', 'sendBinary', 'close'], 'WebSocket 操作失败'],
                  [globalThis.host.zlib, ['gzip', 'gunzip'], '压缩操作失败'],
                  [globalThis.host.resource, ['list'], '目录读取失败']
                ];
                for (var i = 0; i < tables.length; i++) {
                  var table = tables[i][0];
                  if (!table) continue;
                  var names = tables[i][1];
                  for (var j = 0; j < names.length; j++) {
                    var raw = table[prefix + names[j]];
                    if (typeof raw === 'function') table[names[j]] = wrap(raw, tables[i][2]);
                  }
                }
              })();
            })();
        """.trimIndent()
        try {
            runGuarded("沙箱引导", 10_000, poisonOnTimeout = false) {
                runBlocking { engine.evaluate<Any?>(code, filename = "_bootstrap.js") }
            }
        } catch (e: Exception) {
            // 引导失败 = 沙箱环境不完整（polyfill/host 缺失），快速失败避免静默降级
            Log.e(TAG, "沙箱引导失败 for $pluginId", e)
            throw e
        }
    }

    // ---- require 模块系统（宿主侧路径解析与文件读取） ----

    /** 模块相对路径安全校验：允许子目录，禁止绝对路径/反斜杠/`..` 穿越。 */
    private fun isSafeModulePath(path: String): Boolean {
        if (path.isBlank() || path.length > 256) return false
        if (path.startsWith("/") || path.contains('\\')) return false
        return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }

    /** 规范化相对路径（消除 `./` 与 `a/../b`）；越出插件根目录返回 null。 */
    private fun normalizeRelativePath(path: String): String? {
        val parts = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> continue
                ".." -> {
                    if (parts.isEmpty()) return null
                    parts.removeLast()
                }
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    /**
     * 解析 require 目标：`spec` 相对当前模块目录 [fromDir]（插件目录内相对路径）。
     * @return 插件目录内规范相对路径；越界/不存在/非 .js 返回 null
     */
    internal fun resolveModulePath(spec: String, fromDir: String): String? {
        if (spec.isBlank() || spec.length > 256) return null
        val clean = spec.replace('\\', '/')
        if (clean.startsWith("/")) return null
        val base = if (fromDir.isBlank()) "" else "$fromDir/"
        val normalized = normalizeRelativePath("$base$clean") ?: return null
        val candidates = if (normalized.endsWith(".js")) {
            listOf(normalized)
        } else {
            listOf(normalized, "$normalized.js")
        }
        return candidates.firstOrNull { path ->
            isSafeModulePath(path) && path.endsWith(".js") &&
                File(pluginDir, path).let { it.isFile && it.length() <= MAX_MODULE_BYTES }
        }
    }

    /** 读取模块源码；路径不安全/非 .js/超限/不存在返回 null。 */
    internal fun readModuleText(relativePath: String): String? {
        if (!isSafeModulePath(relativePath) || !relativePath.endsWith(".js")) return null
        val file = File(pluginDir, relativePath)
        if (!file.isFile || file.length() > MAX_MODULE_BYTES) return null
        return try {
            file.readText()
        } catch (e: Exception) {
            Log.e(TAG, "readModule failed: $relativePath", e)
            null
        }
    }

    // ---- 值转换辅助 ----

    /** Kotlin Map/List → JS 对象字面量表达式（顺带收集其中的字节载荷到 [sink]）。 */
    private fun kotlinToJsExpr(value: Any?, sink: MutableList<ByteArray>): String =
        JsScriptRuntime.kotlinToJs(value, sink)

    /**
     * 参数表 → 实参 JS 表达式 + 其中的二进制载荷（顺序即 `__ximeHost.bin(i)` 下标）。
     *
     * 字节不进源码是 OOM 修复的关键：表达式长度与包体大小无关，只有字节本身跨桥一次。
     */
    internal fun argsExpr(args: List<Any?>): Pair<String, List<ByteArray>> {
        val binaries = ArrayList<ByteArray>(1)
        val js = args.joinToString(",") { kotlinToJsExpr(it, binaries) }
        return js to binaries
    }

    /** safe: Kotlin 值 → JS 可映射值（Map → JsObject，字节 → UByteArray）。 */
    private fun kotlinToJsValue(value: Any?): Any? {
        val converted = when (value) {
            null, is String, is Boolean -> value
            is Number -> value
            is ByteArray -> value.toUByteArray()
            is UByteArray -> value
            is List<*> -> value.map { kotlinToJsValue(it) }
            is Map<*, *> -> {
                val map = LinkedHashMap<String, Any?>()
                for ((k, v) in value) map[k.toString()] = kotlinToJsValue(v)
                JsObject(map)
            }
            else -> value?.toString()
        }
        return converted
    }

    /** 原生桥名（bootstrap wrapper 以 `__<name>` 探测并覆盖同名 API）。 */
    private fun bridgeName(name: String): String = JsPluginContract.BRIDGE_PREFIX + name

    /** 判别式结果（ok → value；否则 error{code,message}），JS 可映射值。 */
    private fun asyncResult(ok: Boolean, code: String, message: String?, value: Any? = true): Any? =
        kotlinToJsValue(
            if (ok) mapOf("ok" to true, "value" to value)
            else mapOf(
                "ok" to false,
                "error" to mapOf("code" to code, "message" to (message ?: "操作失败"))
            )
        )

    private fun str(args: Array<Any?>, i: Int): String = args.getOrNull(i)?.toString() ?: ""

    private fun num(args: Array<Any?>, i: Int): Double = (args.getOrNull(i) as? Number)?.toDouble() ?: 0.0

    private fun bytes(args: Array<Any?>, i: Int): ByteArray? = when (val v = args.getOrNull(i)) {
        null -> null
        is ByteArray -> v
        is UByteArray -> v.toByteArray()
        is List<*> -> v.mapNotNull { (it as? Number)?.toInt()?.toByte() }.toByteArray()
        is Number -> ByteArray(4) { ((v.toLong() ushr (24 - it * 8)) and 0xFF).toByte() }
        else -> (jsToKotlin(v) as? ByteArray)
    }

    private fun int32be(n: Int): UByteArray {
        return byteArrayOf(
            ((n ushr 24) and 0xFF).toByte(),
            ((n ushr 16) and 0xFF).toByte(),
            ((n ushr 8) and 0xFF).toByte(),
            (n and 0xFF).toByte()
        ).toUByteArray()
    }

    private fun gzip(data: ByteArray?): UByteArray? {
        if (data == null) return null
        return try {
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(data) }
            bos.toByteArray().toUByteArray()
        } catch (e: Exception) {
            api.log("zlib.gzip failed: ${e.message}")
            null
        }
    }

    private fun gunzip(data: ByteArray?): UByteArray? {
        if (data == null) return null
        return try {
            val gzipIn = GZIPInputStream(data.inputStream())
            val buffer = ByteArray(8192)
            val bos = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val n = gzipIn.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_GUNZIP_OUTPUT_BYTES) {
                    throw IllegalStateException("gunzip 输出超过上限（${MAX_GUNZIP_OUTPUT_BYTES / 1024 / 1024}MB）")
                }
                bos.write(buffer, 0, n)
            }
            bos.toByteArray().toUByteArray()
        } catch (e: Exception) {
            api.log("zlib.gunzip failed: ${e.message}")
            null
        }
    }

    // ---- host 子表构建 ----

    private fun ObjectBindingScope.buildHttpTable() {
        // async 桥：返回判别式结果（{ok:true,value} | {ok:false,error}），bootstrap 的
        // host.http.* wrapper 负责 Promise 语义与 throw XimeError（TS 范式）。
        // 阻塞 HTTP 实现经 withContext(IO) 执行，不占用插件线程。
        // 三个入口（request/upload/download）只在"请求体/响应体是否走宿主 blob"上不同，
        // 协议语义一律由插件承载（宿主不认识 WebDAV/S3）。
        fun responseValue(response: HttpResponse): Map<String, Any?> = mapOf(
            "status" to response.status,
            "headers" to response.headers,
            "body" to response.body,
            "text" to response.body.toString(Charsets.UTF_8),
            "blobId" to response.blobId,
            "size" to response.size
        )

        /** null 响应 → 判别式失败（原因取 lastError/code）；否则携带完整响应字段。 */
        fun respond(api: HttpHostApi?, response: HttpResponse?): Any? = when {
            api == null -> kotlinToJsValue(
                mapOf(
                    "ok" to false,
                    "error" to mapOf(
                        "code" to JsPluginContract.ERR_INTERNAL,
                        "message" to "http 能力未注入"
                    )
                )
            )
            response == null -> kotlinToJsValue(
                mapOf(
                    "ok" to false,
                    "error" to mapOf(
                        "code" to (api.lastErrorCode() ?: JsPluginContract.ERR_NETWORK),
                        "message" to (api.lastError() ?: "网络请求失败")
                    )
                )
            )
            else -> kotlinToJsValue(mapOf("ok" to true, "value" to responseValue(response)))
        }

        asyncFunction<Any?>(bridgeName("request")) { args ->
            val method = str(args, 0)
            val url = str(args, 1)
            val headers = headersFrom(args.getOrNull(2))
            val body = bytes(args, 3)
            val timeoutMillis = (args.getOrNull(4) as? Number)?.toInt()?.takeIf { it > 0 }
            val api = httpHostApi
            val response = api?.let {
                withHttpCall { withContext(Dispatchers.IO) { it.request(method, url, headers, body, timeoutMillis) } }
            }
            respond(api, response)
        }

        // 流式上传：body 来自宿主 blob（插件只持有不透明 id），大文件不经过 JS 堆
        asyncFunction<Any?>(bridgeName("upload")) { args ->
            val method = str(args, 0)
            val url = str(args, 1)
            val headers = headersFrom(args.getOrNull(2))
            val blobId = str(args, 3)
            val timeoutMillis = (args.getOrNull(4) as? Number)?.toInt()?.takeIf { it > 0 }
            val api = httpHostApi
            val response = api?.let {
                withHttpCall { withContext(Dispatchers.IO) { it.upload(method, url, headers, blobId, timeoutMillis) } }
            }
            respond(api, response)
        }

        // 流式下载：2xx 正文由宿主落盘为 blob（响应 body 为空，返回 blobId/size）
        asyncFunction<Any?>(bridgeName("download")) { args ->
            val method = str(args, 0)
            val url = str(args, 1)
            val headers = headersFrom(args.getOrNull(2))
            val timeoutMillis = (args.getOrNull(3) as? Number)?.toInt()?.takeIf { it > 0 }
            val api = httpHostApi
            val response = api?.let {
                withHttpCall { withContext(Dispatchers.IO) { it.download(method, url, headers, timeoutMillis) } }
            }
            respond(api, response)
        }

        if (sseHostApi != null) {
            // SSE 流式：async 返回会话 id；事件经 plugin 导出对象回调槽（带 sessionId 参数）
            asyncFunction<Any?>(bridgeName("stream")) { args ->
                val url = str(args, 0)
                val headers = headersFrom(args.getOrNull(1))
                val timeoutMillis = (args.getOrNull(2) as? Number)?.toInt()?.takeIf { it > 0 }
                val method = str(args, 3).ifEmpty { "GET" }.uppercase()
                val body = bytes(args, 4)
                // 会话 id 由宿主 connect 返回后才可知：listener 内通过可变持有器回填
                val sessionHolder = arrayOf(-1)
                val sessionId = sseHostApi.connect(url, headers, streamedListener(sessionHolder), timeoutMillis, method, body)
                sessionHolder[0] = sessionId
                if (sessionId >= 0) {
                    activeSseSessions.add(sessionId)
                    asyncResult(true, JsPluginContract.ERR_NETWORK, null, value = sessionId)
                } else {
                    asyncResult(
                        false, JsPluginContract.ERR_NETWORK,
                        sseHostApi.lastError() ?: "流式连接失败"
                    )
                }
            }
            asyncFunction<Any?>(bridgeName("closeStream")) { args ->
                val id = (args.getOrNull(0) as? Number)?.toInt() ?: -1
                activeSseSessions.remove(id)
                sseHostApi.close(id)
                asyncResult(true, JsPluginContract.ERR_NETWORK, null)
            }
        }
    }

    /** SSE 会话监听：会话 id 经 holder 回填（connect 返回后才可知），事件带 (sessionId, text)。 */
    private fun streamedListener(sessionHolder: Array<Int>): SseHostListener {
        return object : SseHostListener {
            private val sid: Int get() = sessionHolder[0]
            override fun onData(text: String) = dispatchCallback(CB_ON_SSE_DATA, listOf(sid, text))
            override fun onDone(fullText: String) = dispatchCallback(CB_ON_SSE_DONE, listOf(sid, fullText))
            override fun onError(message: String) = dispatchCallback(CB_ON_SSE_ERROR, listOf(sid, message))
        }
    }

    /** 宿主网络桥调用计数：超时日志里带上它，能区分"卡在 JS"和"卡在网上"。 */
    private suspend fun <T> withHttpCall(block: suspend () -> T): T {
        activeHttpCalls.incrementAndGet()
        try {
            return block()
        } finally {
            activeHttpCalls.decrementAndGet()
        }
    }

    private fun ObjectBindingScope.buildWsTable() {
        // async 服务：判别式结果，bootstrap wrapper 转 Promise / throw XimeError
        asyncFunction<Any?>(bridgeName("connect")) { args ->
            val url = str(args, 0)
            val headers = headersFrom(args.getOrNull(1))
            val api = wsHostApi
            val ok = api?.connect(url, headers, wsListener) ?: false
            asyncResult(ok, JsPluginContract.ERR_NETWORK, api?.lastError() ?: "连接失败")
        }
        asyncFunction<Any?>(bridgeName("sendText")) { args ->
            val message = str(args, 0)
            val api = wsHostApi
            val ok = api?.sendText(message) ?: false
            asyncResult(ok, JsPluginContract.ERR_NETWORK, api?.lastError() ?: "发送失败")
        }
        asyncFunction<Any?>(bridgeName("sendBinary")) { args ->
            val data = bytes(args, 0) ?: ByteArray(0)
            val api = wsHostApi
            val ok = api?.sendBinary(data) ?: false
            asyncResult(ok, JsPluginContract.ERR_NETWORK, api?.lastError() ?: "发送失败")
        }
        asyncFunction<Any?>(bridgeName("close")) { _ ->
            wsHostApi?.close()
            asyncResult(true, JsPluginContract.ERR_NETWORK, null)
        }
        // 同步：连接状态（纯内存读，无 IO）
        function("getState") { _ -> wsHostApi?.getState() ?: 0 }
    }

    private val wsListener = object : WsHostListener {
        override fun onOpen() = dispatchCallback(CB_ON_WS_OPEN, emptyList())
        override fun onMessage(text: String) = dispatchCallback(CB_ON_WS_MESSAGE, listOf(text))
        override fun onBinary(data: ByteArray) = dispatchCallback(CB_ON_WS_BINARY, listOf(data))
        override fun onError(message: String) = dispatchCallback(CB_ON_WS_ERROR, listOf(message))
        override fun onClose() {
            dispatchCallback(CB_ON_WS_CLOSE, emptyList())
        }
    }

    private fun ObjectBindingScope.buildCryptoTable() {
        function("sha256") { args -> cryptoHostApi?.sha256(bytes(args, 0) ?: ByteArray(0))?.toUByteArray() }
        function("hmacSha256") { args ->
            val key = bytes(args, 0) ?: ByteArray(0)
            cryptoHostApi?.hmacSha256(key, bytes(args, 1) ?: ByteArray(0))?.toUByteArray()
        }
        function("hmacSha1") { args ->
            val key = bytes(args, 0) ?: ByteArray(0)
            cryptoHostApi?.hmacSha1(key, bytes(args, 1) ?: ByteArray(0))?.toUByteArray()
        }
        function("hex") { args -> cryptoHostApi?.hex(bytes(args, 0) ?: ByteArray(0)) }
        function("base64") { args -> cryptoHostApi?.base64(bytes(args, 0) ?: ByteArray(0)) }
        function("utcTime") { args -> cryptoHostApi?.utcTime(str(args, 0)) }
        function("epochSeconds") { _ -> cryptoHostApi?.epochSeconds() }
    }

    private fun ObjectBindingScope.buildAsrEmitTable() {
        function("emitFinal") { args -> asrResultCallback?.onFinal(str(args, 0)); null }
        function("emitPartial") { args -> asrResultCallback?.onPartial(str(args, 0)); null }
        function("emitError") { args -> asrResultCallback?.onError(str(args, 0)); null }
        function("emitState") { args ->
            asrResultCallback?.onStateChanged(
                when ((args.getOrNull(0) as? Number)?.toInt()) {
                    1 -> com.kingzcheung.xime.plugin.core.api.AsrPluginState.LISTENING
                    2 -> com.kingzcheung.xime.plugin.core.api.AsrPluginState.PROCESSING
                    3 -> com.kingzcheung.xime.plugin.core.api.AsrPluginState.ERROR
                    else -> com.kingzcheung.xime.plugin.core.api.AsrPluginState.IDLE
                }
            )
            null
        }
    }

    private fun ObjectBindingScope.buildQuickSendTable() {
        function("list") { _ ->
            kotlinToJsValue(
                quickSendHostApi?.list()?.map {
                    mapOf(
                        "id" to it.id,
                        "text" to it.text,
                        "code" to it.code,
                        "timestamp" to it.timestamp,
                        "isPinned" to it.isPinned
                    )
                } ?: emptyList<Any?>()
            )
        }
    }

    private fun ObjectBindingScope.buildClipboardTable() {
        function("get") { _ ->
            clipboardHostApi?.getText()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun headersFrom(value: Any?): Map<String, String> {
        val raw = jsToKotlin(value) as? Map<*, *> ?: return emptyMap()
        return raw.mapNotNull { (k, v) -> k?.toString()?.let { it to v.toString() } }.toMap()
    }

    // ---- 执行入口：串行 + 限时 + 异步求值 ----

    /** runGuarded 的判定结果：区分"已超时（可能中毒）"与"正常返回（含 JS null）"。 */
    private sealed interface GuardResult<out T> {
        data class Ok<T>(val value: T) : GuardResult<T>

        /** 超时/中断/中毒后调用：本次未执行或未完成。 */
        data object TimedOut : GuardResult<Nothing>
    }

    /**
     * 在引擎串行调度器上执行 JS 代码并限时。
     *
     * 超时只从任务**开始执行**时起算：排队等前一个调用不算超时，超过时限只放弃本次调用
     * （不中断正在执行的调用、不中毒）。真正执行超时后按 [poisonOnTimeout] 决定是停用插件
     * 还是只中止本次调用。
     *
     * @param label 扩展点名（写进超时日志，便于定位是哪一次调用卡住）
     * @param binaries 本次表达式引用的字节载荷（`__ximeHost.bin(i)`），执行前置入、
     *   执行后清空；默认空表示表达式内无二进制参数。
     * @return [GuardResult.Ok]（正常返回，value 可为 null）或 [GuardResult.TimedOut]（超时/中断）
     */
    private fun <T> runGuarded(
        label: String,
        timeoutMs: Long,
        poisonOnTimeout: Boolean,
        binaries: List<ByteArray> = emptyList(),
        reportTimeoutOnAbort: Boolean = false,
        block: () -> T,
    ): GuardResult<T> {
        if (poisoned || closed) return GuardResult.TimedOut
        val started = CountDownLatch(1)
        val future = try {
            executor.submit(Callable {
                // 二次判定：任务真正开始执行时运行时可能已关闭。[close] 的屏障只保证
                // "已开始的调用"先于引擎关闭结束，不保证"提交"与"关闭"之间没有窗口。
                if (closed) throw RuntimeClosedSignal()
                pendingBinaryArgs = binaries
                started.countDown()
                try {
                    block()
                } finally {
                    pendingBinaryArgs = emptyList()
                }
            })
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // 执行器已随关闭销毁：这不是脚本错误，按"无结果"处理。
            return GuardResult.TimedOut
        }
        // 排队阶段：运行时单线程串行执行，前一个调用（如整包上传）没结束时本次任务还没开始。
        // 这里放弃即可——绝不能中断正在执行的 evaluate（那会打断别人的上传，还会被误判成
        // 插件死循环；真机记录过"长备份 + 设置页读 schema"被毒停）。
        if (!started.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            future.cancel(false)
            Log.w(TAG, "[$pluginId] $label 排队 ${timeoutMs}ms 仍未开始执行（运行时忙），本次调用放弃")
            return GuardResult.TimedOut
        }
        return try {
            GuardResult.Ok(future.get(timeoutMs, TimeUnit.MILLISECONDS))
        } catch (e: TimeoutException) {
            future.cancel(true)
            interruptAndHandleTimeout(poisonOnTimeout, reportTimeoutOnAbort, timeoutDescription(label, timeoutMs))
            GuardResult.TimedOut
        } catch (e: Exception) {
            future.cancel(true)
            // 关闭引发的失败（关闭信号 / 引擎已关闭 / shutdownNow 取消排队任务）：
            // 一律按"无结果"处理，既不记脚本错误也不中毒。
            if (closed || isClosedLike(e)) return GuardResult.TimedOut
            // QuickJsInterruptedException 或中断引发的 CancellationException 视为超时
            if (isInterruptLike(e))
                interruptAndHandleTimeout(poisonOnTimeout, reportTimeoutOnAbort, "$label 执行被中断")
            throw e
        }
    }

    /** 超时描述：带扩展点名与在途网络请求数，便于区分"卡在 JS"还是"卡在网上"。 */
    private fun timeoutDescription(label: String, timeoutMs: Long): String {
        val limit = if (timeoutMs >= 1000) "${timeoutMs / 1000}s" else "${timeoutMs}ms"
        val http = activeHttpCalls.get()
        return "$label 超过 $limit 未返回" + if (http > 0) "（当时有 $http 个网络请求在途）" else ""
    }

    /** 是否为"运行时已关闭"引发的失败（关闭信号 / 执行器拒绝 / 引擎已关闭）。 */
    private fun isClosedLike(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is RuntimeClosedSignal) return true
            if (cur is java.util.concurrent.RejectedExecutionException) return true
            if (cur is QuickJsException && cur.message.orEmpty().contains("closed", ignoreCase = true)) return true
            cur = cur.cause
        }
        return false
    }

    private fun isInterruptLike(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is java.util.concurrent.CancellationException) return true
            if (cur is QuickJsException) {
                val m = cur.message.orEmpty()
                if (m.contains("nterrupt", ignoreCase = true)) return true
            }
            cur = cur.cause
        }
        return false
    }

    private fun interruptAndHandleTimeout(
        poisonOnTimeout: Boolean,
        reportTimeoutOnAbort: Boolean,
        detail: String,
    ) {
        try {
            engine.interruptEvaluation()
        } catch (_: Exception) {
        }
        when {
            poisonOnTimeout -> poison(detail)
            reportTimeoutOnAbort -> recordTimeout(detail)
            else -> Log.w(TAG, "[$pluginId] $detail，本次调用已中止")
        }
    }

    private fun poison(detail: String) {
        if (poisoned) return
        poisoned = true
        Log.e(TAG, "[$pluginId] $detail，插件已标记中毒；JS 线程栈=${jsThreadStack()}")
        PluginErrorLog.logError(
            pluginId = pluginId,
            operation = "插件执行超时",
            message = "$detail（疑似死循环），已停止执行该插件，请重载或卸载",
            category = ErrorCategory.TIMEOUT_POISONED
        )
    }

    /** IO 型长调用超时：只中止本次调用，插件继续可用（不停用，避免把慢网络当成死循环）。 */
    private fun recordTimeout(detail: String) {
        Log.w(TAG, "[$pluginId] $detail，本次调用已中止（不中毒）")
        PluginErrorLog.logError(
            pluginId = pluginId,
            operation = "插件执行超时",
            message = "$detail，本次操作已中止；插件仍可继续使用",
            category = ErrorCategory.TIMEOUT
        )
    }

    /** JS 执行线程的栈（仅用于超时诊断日志；取不到时返回 n/a）。 */
    private fun jsThreadStack(): String {
        val target = JS_THREAD_PREFIX + pluginId
        val trace = Thread.getAllStackTraces().entries
            .firstOrNull { it.key.name == target }?.value ?: return "n/a"
        return trace.take(6).joinToString(" <- ") { it.toString() }
    }

    /**
     * 构造调用导出的表达式：`plugin.<path>(args...)`。
     * 路径按扩展点分组（如 `panel.state` / `events.onTextCommitted`），
     * 任一级缺失或末级非函数返回 undefined（不抛 TypeError）。
     */
    private fun callExpr(path: String, argsJs: String): String {
        return "(${functionGuardExpr(path)}) ? globalThis.plugin.$path($argsJs) : undefined"
    }

    /**
     * 方法存在性守卫表达式（路径各级存在且末级为函数）。
     *
     * 用途：[callExpr] 与 [hasMethod]——调用不存在的方法不会抛 TypeError，
     * 而 `typeof ... === 'function'` 是 JS 侧唯一可靠的"方法是否存在"判定。
     */
    private fun functionGuardExpr(path: String): String {
        val parts = path.split('.')
        val guards = ArrayList<String>()
        guards += "typeof globalThis.plugin !== 'undefined' && globalThis.plugin"
        var acc = "globalThis.plugin"
        for (i in 0 until parts.size - 1) {
            acc = "$acc.${parts[i]}"
            guards += acc
        }
        guards += "typeof globalThis.plugin.$path === 'function'"
        return guards.joinToString(" && ")
    }

    /**
     * 插件导出对象上是否存在指定路径的方法。
     *
     * 与 [call] / [callAsync] 的 null 返回值配合使用：这两个方法对"方法不存在"
     * 与"方法返回 null"都返回 null（成功语义），仅凭返回值无法区分。设置页按钮
     * 动作在派发前用它判定，避免"方法不存在"被上层误判为成功。
     */
    fun hasMethod(path: String): Boolean {
        if (!loaded || closed) return false
        return try {
            val result = runGuarded("hasMethod $path", callTimeoutMs, poisonOnTimeout = false) {
                runBlocking { engine.evaluate<Any?>(functionGuardExpr(path), filename = entryScript) }
            }
            when (result) {
                is GuardResult.Ok -> result.value as? Boolean ?: false
                GuardResult.TimedOut -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "hasMethod '$path' failed for $pluginId: ${e.message}", e)
            false
        }
    }

    /** 构造回调槽表达式：`plugin.onWsMessage(...)`；回调槽缺失静默 undefined。 */
    private fun slotExpr(method: String, argsJs: String): String {
        return callExpr(method, argsJs)
    }

    // ---- 回调槽投递（WS/SSE 事件） ----

    /** 投递事件到 JS 导出对象回调槽（短超时，不中毒）。 */
    private fun dispatchCallback(method: String, args: List<Any?>) {
        if (!loaded || poisoned) return
        val (argsJs, binaries) = argsExpr(args)
        val expr = slotExpr(method, argsJs)
        try {
            runGuarded(method, callbackTimeoutMs, poisonOnTimeout = false, binaries = binaries) {
                runBlocking { engine.evaluate<Any?>(expr, filename = entryScript) }
            }
        } catch (e: Exception) {
            api.log("$method 回调失败: ${e.message}")
            logScriptError("$method 回调", e)
        }
    }

    // ---- 下行事件（manifest capabilities.events 声明后启用） ----

    fun initEvents(subscribed: Set<String>) {
        subscribedEvents = subscribed
        if (subscribed.isEmpty()) return
        if (eventChannel != null) return
        val channel = Channel<PluginEvent>(Channel.CONFLATED)
        eventChannel = channel
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        eventScope = scope
        scope.launch {
            for (event in channel) {
                invokeEventCallback(event)
            }
        }
    }

    fun dispatchEvent(event: PluginEvent): Boolean {
        val channel = eventChannel ?: return false
        if (event.type !in subscribedEvents) return false
        return channel.trySend(event).isSuccess
    }

    private fun invokeEventCallback(event: PluginEvent) {
        if (!loaded || poisoned) return
        val (payloadJs, binaries) = argsExpr(listOf(event.payload))
        val slotPath = JsPluginContract.PATH_EVENTS_PREFIX + JsPluginContract.eventSlotName(event.type)
        val expr = callExpr(slotPath, payloadJs)
        try {
            runGuarded(slotPath, callbackTimeoutMs, poisonOnTimeout = false, binaries = binaries) {
                runBlocking { engine.evaluate<Any?>(expr, filename = entryScript) }
            }
        } catch (e: Exception) {
            api.log("事件槽回调失败（${event.type}）: ${e.message}")
            logScriptError("事件槽回调（${event.type}）", e)
        }
    }

    // ---- 候选词变换（hotPath，同步 15ms 硬超时） ----

    fun transformCandidates(request: CandidateTransformRequest): CandidateTransformOutcome {
        if (!loaded) return CandidateTransformOutcome.NoResponse
        val (reqJs, binaries) = argsExpr(
            listOf(
                mapOf(
                    "inputText" to request.inputText,
                    "preedit" to request.preedit,
                    "asciiMode" to request.asciiMode,
                    "candidates" to request.candidates.map {
                        mapOf("text" to it.text, "comment" to it.comment)
                    }
                )
            )
        )
        val expr = "(typeof globalThis.plugin !== 'undefined' && globalThis.plugin && " +
            "globalThis.plugin.transform && typeof globalThis.plugin.transform.candidates === 'function') ? " +
            "JSON.parse(JSON.stringify(globalThis.plugin.transform.candidates($reqJs))) : null"
        return try {
            val result = runGuarded("transform.candidates", TRANSFORM_TIMEOUT_MS, poisonOnTimeout = false, binaries = binaries) {
                runBlocking { engine.evaluate<Any?>(expr, filename = entryScript) }
            }
            val value = when (result) {
                is GuardResult.Ok -> result.value
                GuardResult.TimedOut -> return CandidateTransformOutcome.Failed
            }
            jsToKotlin(value).let {
                when {
                    it == null -> CandidateTransformOutcome.NoResponse
                    else -> parseTransformResponse(it as? Map<*, *>)
                }
            }
        } catch (e: Exception) {
            api.log("transform.candidates 调用失败: ${e.message}")
            CandidateTransformOutcome.Failed
        }
    }

    private fun parseTransformResponse(map: Map<*, *>?): CandidateTransformOutcome {
        if (map == null) return CandidateTransformOutcome.Failed
        val listRaw = (jsToKotlin(map["candidates"]) as? List<*>)
            ?: return CandidateTransformOutcome.Failed
        val items = mutableListOf<CandidateTransformItem>()
        for (item in listRaw) {
            val m = jsToKotlin(item) as? Map<*, *> ?: continue
            val comment = (m["comment"] as? String)?.takeIf { it.isNotBlank() }
            val engineIndex = (m["engineIndex"] as? Number)?.toInt()
            val text = m["text"] as? String
            when {
                engineIndex != null ->
                    items.add(CandidateTransformItem(engineIndex = engineIndex, text = null, comment = comment))
                !text.isNullOrEmpty() ->
                    items.add(CandidateTransformItem(engineIndex = null, text = text, comment = comment))
                else -> continue
            }
            if (items.size >= TRANSFORM_MAX_CANDIDATES) break
        }
        if (items.isEmpty()) return CandidateTransformOutcome.NoResponse
        return CandidateTransformOutcome.Success(items)
    }

    // ---- 生命周期 ----

    /**
     * 加载入口脚本并确认 `globalThis.plugin` 导出对象。
     * @return 是否加载成功（入口缺失 / 未导出 plugin / 脚本报错 / 超时均返回 false）
     */
    fun load(): Boolean {
        if (loaded) return true
        return try {
            val ok = runGuarded("脚本加载", callTimeoutMs, poisonOnTimeout = true) {
                runBlocking {
                    if (loaded) return@runBlocking true
                    val entryFile = File(pluginDir, entryScript)
                    if (!entryFile.exists()) {
                        Log.e(TAG, "Entry script not found: ${entryFile.absolutePath}")
                        com.kingzcheung.xime.plugin.core.security.PluginErrorLog.logError(
                            pluginId = pluginId,
                            operation = "脚本加载失败",
                            message = "入口脚本不存在: $entryScript",
                            category = com.kingzcheung.xime.plugin.core.security.ErrorCategory.SCRIPT_ERROR
                        )
                        return@runBlocking false
                    }
                    engine.evaluate<Any?>(entryFile.readText(), filename = entryScript)
                    val hasPlugin = engine.evaluate<Any?>(
                        "(typeof globalThis.plugin === 'object' && globalThis.plugin !== null)"
                    ) as? Boolean ?: false
                    if (!hasPlugin) {
                        Log.e(TAG, "Entry script did not export globalThis.plugin: $pluginId")
                        com.kingzcheung.xime.plugin.core.security.PluginErrorLog.logError(
                            pluginId = pluginId,
                            operation = "脚本加载失败",
                            message = "入口脚本未定义全局对象 plugin（请添加 globalThis.plugin = {...}）",
                            category = com.kingzcheung.xime.plugin.core.security.ErrorCategory.SCRIPT_ERROR
                        )
                        return@runBlocking false
                    }
                    loaded = true
                    Log.d(TAG, "Plugin $pluginId loaded from $entryScript")
                    true
                }
            }
            (ok as? GuardResult.Ok)?.value == true
        } catch (e: QuickJsException) {
            Log.e(TAG, "Failed to load JS plugin $pluginId", e)
            logScriptError("脚本加载失败", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load JS plugin $pluginId", e)
            logScriptError("脚本加载失败", e)
            false
        }
    }

    fun callOnLoad() = invokeLifecycle("onLoad")

    fun callOnUnload() = invokeLifecycle("onUnload")

    /** 生命周期调用（async 感知：插件可声明 async onLoad/onUnload，宿主等到 settle）。 */
    private fun invokeLifecycle(name: String) {
        if (!loaded || poisoned || closed) return
        try {
            runGuarded(name, callTimeoutMs, poisonOnTimeout = true) {
                runBlocking {
                    engine.evaluate<Any?>(
                        asyncStashExpr(slotExpr(name, "")),
                        filename = entryScript
                    )
                    engine.evaluate<Any?>(asyncReadExpr(), filename = entryScript)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "$name failed for $pluginId", e)
            logScriptError(name, e)
        }
    }

    /**
     * 调用插件导出对象上的方法。
     * @param name 方法名（如 getCategories / getEmojis / getPanelState / pushBackup）
     * @param args 参数（null/Boolean/Int/Long/Double/String/Map/List/ByteArray/Uint8Array）
     * @param timeoutMs 本次调用超时（默认 [callTimeoutMs]；长耗时网络类调用可放宽）
     * @param poisonOnTimeout 超时是否停用插件；网络/IO 型长调用传 false 只中止本次调用
     * @param reportTimeoutOnAbort 不中毒的超时是否写一条 TIMEOUT 错误记录（备份等显式操作传 true，
     *   按键热路径/callback 保持静默，避免刷屏）
     * @return JS 返回值（已转纯 Kotlin 结构）；方法不存在 / 报错 / 超时返回 null
     */
    fun call(
        name: String,
        vararg args: Any?,
        timeoutMs: Long = callTimeoutMs,
        poisonOnTimeout: Boolean = true,
        reportTimeoutOnAbort: Boolean = false,
    ): Any? {
        if (!loaded || closed) return null
        return try {
            val (argsJs, binaries) = argsExpr(args.toList())
            val expr = callExpr(name, argsJs)
            val result = runGuarded(name, timeoutMs, poisonOnTimeout, binaries = binaries, reportTimeoutOnAbort = reportTimeoutOnAbort) {
                runBlocking { engine.evaluate<Any?>(expr, filename = entryScript) }
            }
            when (result) {
                is GuardResult.Ok -> jsToKotlin(result.value)
                GuardResult.TimedOut -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Call '$name' failed for $pluginId: ${e.message}", e)
            api.log("Call '$name' failed: ${e.message}")
            logScriptError("调用 $name", e)
            null
        }
    }

    /**
     * 调用插件导出对象上的 async 方法（TS 范式：可返回 `Promise<T>` 或 `T`）。
     *
     * quickjs-kt 的 evaluate 不 unwrap 返回的 Promise（拿到 Promise 对象本身），
     * 故用「发起 + 二次读取」模式：先发起并把 settle 结果暂存到全局槽（evaluate 等到 settle），
     * 再读取暂存槽拆包。
     *
     * @param name 方法名（如 panel.state / panel.onAction 的路径）
     * @param args 参数（null/Boolean/Int/Long/Double/String/Map/List/ByteArray/Uint8Array）
     * @param timeoutMs 本次调用超时（默认 [callTimeoutMs]；长耗时网络类调用可放宽）
     * @param poisonOnTimeout 超时是否停用插件；网络/IO 型长调用传 false 只中止本次调用
     * @param reportTimeoutOnAbort 不中毒的超时是否写一条 TIMEOUT 错误记录
     *   （备份等显式操作传 true，按键热路径/callback 保持静默，避免刷屏）
     * @return 返回值（已转纯 Kotlin 结构）；方法不存在 / rejected / 报错 / 超时返回 null
     */
    fun callAsync(
        name: String,
        vararg args: Any?,
        timeoutMs: Long = callTimeoutMs,
        poisonOnTimeout: Boolean = true,
        reportTimeoutOnAbort: Boolean = false,
    ): Any? {
        if (!loaded || closed) return null
        return try {
            val (argsJs, binaries) = argsExpr(args.toList())
            val invoke = callExpr(name, argsJs)
            val result = runGuarded(name, timeoutMs, poisonOnTimeout, binaries = binaries, reportTimeoutOnAbort = reportTimeoutOnAbort) {
                runBlocking {
                    engine.evaluate<Any?>(asyncStashExpr(invoke), filename = entryScript)
                    engine.evaluate<Any?>(asyncReadExpr(), filename = entryScript)
                }
            }
            val value = when (result) {
                is GuardResult.Ok -> result.value
                GuardResult.TimedOut -> return null
            }
            val stash = jsToKotlin(value) as? Map<*, *> ?: return null
            when ((stash["status"] as? Number)?.toInt()) {
                1 -> jsToKotlin(stash["result"])
                2 -> {
                    val message = stash["error"]?.toString() ?: "未知错误"
                    Log.e(TAG, "CallAsync '$name' rejected for $pluginId: $message")
                    api.log("CallAsync '$name' rejected: $message")
                    null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "CallAsync '$name' failed for $pluginId: ${e.message}", e)
            api.log("CallAsync '$name' failed: ${e.message}")
            logScriptError("异步调用 $name", e)
            null
        }
    }

    /** 发起表达式：Promise.resolve(invoke).then(暂存值, 暂存错误)；evaluate 等到 settle。 */
    private fun asyncStashExpr(invokeExpr: String): String {
        val status = JsPluginContract.ASYNC_STASH_STATUS
        val result = JsPluginContract.ASYNC_STASH_RESULT
        val error = JsPluginContract.ASYNC_STASH_ERROR
        return "globalThis.$status = 0, globalThis.$result = undefined, globalThis.$error = undefined," +
            " Promise.resolve($invokeExpr).then(" +
            " function (v) { globalThis.$status = 1; globalThis.$result = v; }," +
            " function (e) { globalThis.$status = 2;" +
            " globalThis.$error = (e && e.message) ? String(e.message) : String(e); })"
    }

    /** 读取表达式：返回暂存槽快照对象（status/result/error）。 */
    private fun asyncReadExpr(): String {
        val status = JsPluginContract.ASYNC_STASH_STATUS
        val result = JsPluginContract.ASYNC_STASH_RESULT
        val error = JsPluginContract.ASYNC_STASH_ERROR
        return "({ status: globalThis.$status, result: globalThis.$result, error: globalThis.$error })"
    }

    /** 脚本错误打点：消息自带 main.js:行号（QuickJsException 携带 location）。 */
    private fun logScriptError(operation: String, e: Exception) {        val message = if (e is QuickJsException) {
            val file = e.fileName ?: entryScript
            val line = e.lineNumber
            val base = e.message ?: e.javaClass.simpleName
            if (line != null) "$file:$line $base" else "$file $base"
        } else {
            e.message ?: e.javaClass.simpleName
        }
        com.kingzcheung.xime.plugin.core.security.PluginErrorLog.logError(
            pluginId = pluginId,
            operation = operation,
            message = message,
            throwable = e,
            category = com.kingzcheung.xime.plugin.core.security.ErrorCategory.SCRIPT_ERROR
        )
    }

    fun close() {
        if (closed) return
        try {
            // onUnload 需要引擎可用：必须在置 [closed] 之前跑完。
            callOnUnload()
        } finally {
            // 1) 先置关闭标志并清 loaded：此后到达的调用一律按"无结果"返回，不再投递。
            closed = true
            loaded = false
            eventScope?.cancel()
            eventScope = null
            eventChannel?.close()
            eventChannel = null
            subscribedEvents = emptySet()
            activeSseSessions.forEach { sseHostApi?.close(it) }
            activeSseSessions.clear()
            // 2) 等已受理的调用跑完，再关引擎：避免引擎在 JS 执行中被关闭
            //    （旧实现先 engine.close() 再 shutdownNow：正在执行的调用报
            //    "Already closed"，排队中的调用被取消并被误判为超时中毒）。
            awaitIdle()
            try {
                engine.close()
            } catch (_: Exception) {
            }
            executor.shutdownNow()
            asyncJobExecutor.shutdownNow()
        }
    }

    /**
     * 等待 [executor] 上已受理的调用结束（单线程 FIFO：投递空任务即屏障）。
     *
     * 关闭阶段投递的调用会被 [closed] 拦下，故屏障只会等到"关闭前已开始"的调用。
     * 等待上限与 [callTimeoutMs] 同量级；真死循环的插件由超时路径中断，不会永久挂住。
     */
    private fun awaitIdle() {
        // close() 若由 JS 线程自身调用（onUnload 路径），投递屏障会自等死锁。
        if (Thread.currentThread().name.startsWith(JS_THREAD_PREFIX)) return
        try {
            executor.submit(Callable { }).get(callTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // 等待失败/超时：不再等待，按既有语义继续关闭
        }
    }
}