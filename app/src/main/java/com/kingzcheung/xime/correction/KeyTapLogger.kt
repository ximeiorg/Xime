package com.kingzcheung.xime.correction

import android.content.Context
import android.util.Log
import androidx.compose.ui.geometry.Rect
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 按键/会话日志（相邻键纠错的数据采集，默认关闭）。
 *
 * 事件流（同一文件 `filesDir/logs/key_tap.jsonl`，每行一个 JSON，`e` 为事件类型，`v` 为格式版本）：
 *  - `e=tap`    一次按键：`key` + 几何（`lx/ly` 局部坐标、`bl..bh` 按键矩形、`kl..kh` 键盘矩形、`d` 密度）
 *  - `e=cmp`    一次 Rime 组合快照（每个键处理后的解释）：`in` 输入串、`pre` preedit、
 *               `cmt` 本次上屏增量、`a` ascii、`c` 候选数组 `{t,c}`
 *  - `e=del`    一次退格：`in` 退格前输入、`pe` 英文 pending
 *  - `e=commit` 一次上屏：`t`(文本，见字段 `tx`)、`p` 是否粘贴
 *  公共字段：`v,seq,t,s`（schema 方案 id）
 *
 * 采集原则：**记录全部原始事件，不预过滤**——错误分型（空间型/拆字型/选错）在离线完成，
 * 规则可迭代重跑。旧版（无 `e`）行的 `e` 视为 `tap`。
 *
 * 用 `.jsonl` 而非 `.log`：FileLogger 轮转会清理非 `kime_` 前缀的 `*.log`。
 */
object KeyTapLogger {
    private const val TAG = "KeyTapLogger"
    private const val FILE_NAME = "logs/key_tap.jsonl"
    private const val MAX_BYTES = 32L * 1024 * 1024
    private const val KEEP_LINES_ON_TRIM = 200_000
    private const val MAX_CANDIDATES = 8
    private const val MAX_TEXT = 64

    /** 开关（由设置同步，见 XimeApplication / DeveloperScreen）。 */
    @Volatile
    var enabled: Boolean = false

    /** schema（拼音/五笔方案 id）提供者，由输入法服务注入。 */
    @Volatile
    var schemaProvider: (() -> String)? = null

    /** 当前键盘矩形（root px），由 KeyboardLayout 上报。 */
    @Volatile
    var keyboardBounds: Rect = Rect(0f, 0f, 0f, 0f)

    private var logFile: File? = null
    private val seq = AtomicLong(0)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "KeyTapLog").apply { isDaemon = true }
    }

    fun init(context: Context) {
        if (logFile != null) return
        logFile = File(context.filesDir, FILE_NAME)
    }

    /** 记录一次按键（几何）。仅在 [enabled] 时写盘，异常全部吞掉（采集不得影响输入）。 */
    fun recordTap(
        key: String,
        localX: Float,
        localY: Float,
        buttonBounds: Rect,
        density: Float,
    ) {
        val kb = keyboardBounds
        if (enabled) {
            write("tap") {
                put("key", key)
                put("lx", localX)
                put("ly", localY)
                put("bl", buttonBounds.left)
                put("bt", buttonBounds.top)
                put("bw", buttonBounds.width)
                put("bh", buttonBounds.height)
                put("kl", kb.left)
                put("kt", kb.top)
                put("kw", kb.width)
                put("kh", kb.height)
                put("d", density)
            }
        }
        // 邻键纠错（librime 插件版）：把按键几何作为先验送入插件（off 用同口径）
        if (CorrectionPriors.enabled && key.length == 1) {
            val ch = key[0].lowercaseChar()
            if (ch in 'a'..'z') {
                val hasGeom = kb.width > 0f && kb.height > 0f &&
                    buttonBounds.width > 0f && buttonBounds.height > 0f
                val offX = if (hasGeom) (localX - buttonBounds.width / 2f) / buttonBounds.width else null
                val offY = if (hasGeom) (localY - buttonBounds.height / 2f) / buttonBounds.height else null
                CorrectionPriors.onTapLetter(
                    pressedIdx = ch - 'a',
                    offXValue = offX,
                    offYValue = offY,
                    schema = runCatching { schemaProvider?.invoke() ?: "" }.getOrDefault(""),
                )
            }
        }
    }

    /** 记录一次 Rime 组合快照（每个键处理后的输入串/候选/上屏增量）。 */
    fun recordComposition(
        input: String,
        preedit: String,
        committed: String,
        ascii: Boolean,
        candidates: List<Pair<String, String>>,
    ) {
        if (!enabled) return
        write("cmp") {
            put("in", input.take(MAX_TEXT))
            put("pre", preedit.take(MAX_TEXT))
            put("cmt", committed.take(MAX_TEXT))
            put("a", ascii)
            put("c", buildJsonArray {
                candidates.take(MAX_CANDIDATES).forEach { (t, c) ->
                    add(buildJsonObject {
                        put("t", t.take(MAX_TEXT))
                        put("c", c.take(MAX_TEXT))
                    })
                }
            })
        }
    }

    /** 记录一次退格（`input` 为退格前输入串，`pendingEnglish` 为英文待上屏文本）。 */
    fun recordDelete(input: String, pendingEnglish: String) {
        CorrectionPriors.clear()  // 邻键纠错：退格后按键序列与编码不再对应，先验作废
        if (!enabled) return
        write("del") {
            put("in", input.take(MAX_TEXT))
            put("pe", pendingEnglish.take(MAX_TEXT))
        }
    }

    /** 记录一次上屏。 */
    fun recordCommit(text: String, isPaste: Boolean) {
        CorrectionPriors.clear()  // 邻键纠错：上屏后按键序列重新开始
        if (!enabled || text.isEmpty()) return
        write("commit") {
            put("tx", text.take(256))
            put("p", isPaste)
        }
    }

    private fun write(event: String, block: JsonObjectBuilder.() -> Unit) {
        val f = logFile ?: return
        val schema = runCatching { schemaProvider?.invoke() ?: "" }.getOrDefault("")
        val now = System.currentTimeMillis()
        val s = seq.incrementAndGet()
        executor.execute {
            try {
                val json = buildJsonObject {
                    put("v", 2)
                    put("e", event)
                    put("seq", s)
                    put("t", now)
                    put("s", schema)
                    block()
                }.toString()
                appendBounded(f, json)
            } catch (e: Exception) {
                Log.e(TAG, "write $event failed", e)
            }
        }
    }

    private fun appendBounded(file: File, line: String) {
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
        if (file.length() > MAX_BYTES) {
            val kept = file.readLines().takeLast(KEEP_LINES_ON_TRIM)
            file.writeText(kept.joinToString("\n") + "\n")
        }
    }
}
