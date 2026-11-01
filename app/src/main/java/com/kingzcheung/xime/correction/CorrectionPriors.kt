package com.kingzcheung.xime.correction

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.content.Context
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.util.FileLogger
import java.io.File
import java.util.ArrayDeque
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 邻键误触纠错的**先验推送器**（Kotlin 侧薄适配层）。
 *
 * 职责边界（推倒重来后的分工）：
 *  - 这里只负责：收集最近字母按键的几何 → 端上自校准（每键偏移滑窗中位数，
 *    行为数据只存本机 filesDir，APK 不携带任何人的偏移）
 *    → 与 Rime 当前组合编码**对齐** → 经侧信道送给插件；
 *  - 候选生成/打分/出词/排序全部在 librime 插件（correction_translator）里做，
 *    用同一份词典与用户词典，拿得到权重与用户调频。
 *
 * 对齐规则（宁可不用先验，也不用错位的）：
 *  只有当窗口尾部与当前编码完全一致时才推送；不一致（拼音反查、T9、部分提交等）就清空，
 *  插件侧拿不到对齐先验会直接返回空 —— 结构性避免误纠。
 *
 * 日志一律走 [FileLogger]（写 filesDir/logs）：部分 ROM（实测 realme/vivo）会把 logcat
 * 的应用日志静音，android.util.Log 在真机上取不到，纠错链路的可观测性只能靠文件日志。
 */
object CorrectionPriors {
    private const val TAG = "CorrectionPriors"
    private const val MAX_TAPS = 12
    /** 配置缺失时的兜底（xime.yaml correction.schemas 正常都有，防旧 APK/解析失败）。 */
    private val SCHEMAS_FALLBACK = setOf("wubi86", "wubi86_pinyin", "pinyin_simp")

    /** 端上自校准：从本机击键行为在线估计每键偏移（滑窗中位数），持久化到
     *  filesDir（应用私有目录，随用户数据存留/卸载清除）。APK 不携带任何人的偏移数据。 */
    private const val STATE_FILE = "corrector/personal_offset.json"
    private const val RING_CAP = 256          // 每键滑动窗口（最近 N 次落点）
    private const val ESTIMATE_EVERY = 128    // 每积累多少次击键重估一次
    private const val MIN_N_PER_KEY = 30      // 单键样本不足 → 回退全局中位数
    private const val MIN_N_GLOBAL = 300      // 全局也不足 → 不校准（冷启动直通）
    private const val OFF_GATE = 0.55f        // 越界疑似误触，不进窗口（中位数本身也稳健，双保险）
    private const val MIN_N_SCALE = 400       // 估信道宽度（σ）所需的最小击键数
    private const val SCALE_MIN = 0.15f       // σ 合理区间（键宽/键高单位）；越界视为不可信
    private const val SCALE_MAX = 0.40f
    private const val PERSIST_MIN_MS = 60_000L

    private var appContext: Context? = null
    private val letters = ArrayDeque<Char>(MAX_TAPS)
    private val offX = ArrayDeque<Float>(MAX_TAPS)
    private val offY = ArrayDeque<Float>(MAX_TAPS)

    /** 每键落点滑动窗口（原始 off，校准前）。 */
    private val ringX = Array(26) { ArrayDeque<Float>(RING_CAP) }
    private val ringY = Array(26) { ArrayDeque<Float>(RING_CAP) }
    private var ringTotal = 0
    private var sinceEstimate = 0
    private var lastPersistAt = 0L

    /** 当前生效的每键校准值；NaN = 该键未激活（冷启动/样本不足且无全局兜底）。 */
    private val actX = FloatArray(26) { Float.NaN }
    private val actY = FloatArray(26) { Float.NaN }
    private var globalX = Float.NaN
    private var globalY = Float.NaN
    /** 端上自校准的信道宽度（MAD 稳健估计）；NaN = 未校准，插件用 TSI 实测默认值。 */
    private var scaleX = Float.NaN
    private var scaleY = Float.NaN
    private var stateActive = false

    /** native 侧是否曾被成功启用。关闭态下所有 JNI 调用必须短路：
     *  CorrectionNative 的 loaded 是 lazy 的，任何访问都会触发 rime_jni 的
     *  dlopen——开关关闭的用户不该在冷启动/每次退格时代价这笔加载。 */
    @Volatile
    private var nativeActive = false

    /** 最近一次推送状态（供设置页/日志诊断）。 */
    @Volatile
    var lastPush: String = tr(R.string.s_weichushihua)

    @Volatile
    var enabled: Boolean = false
        set(value) {
            field = value
            if (value) {
                val ok = ensureLoaded()
                lastPush = if (ok) tr(R.string.s_yiqiyong_dengdaianjian) else tr(R.string.s_qiyongshibai_moxing_mabiaobukeyong)
                FileLogger.i(TAG, "enabled=$ok ctx=${appContext != null}")
            } else {
                if (nativeActive) {
                    CorrectionNative.setEnabled(false)
                    CorrectionNative.clearTaps()
                    nativeActive = false
                }
                letters.clear(); offX.clear(); offY.clear()
                lastPush = tr(R.string.s_yiguanbi)
                FileLogger.i(TAG, "disabled")
            }
        }

    fun init(context: Context) {
        appContext = context.applicationContext
        FileLogger.i(TAG, "init")
    }

    /**
     * 该方案是否启用纠错（xime.yaml / xime.custom.yaml 的 correction.schemas 声明）。
     * 供按键先验门与 schema 补丁注入（RimeEngine）共用，保证两路口径一致。
     */
    fun isCorrectionSchema(schemaId: String): Boolean {
        val ctx = appContext ?: return false
        val schemas = KeysConfigHelper.correctionSchemas(ctx)
        return schemaId.trim() in schemas.ifEmpty { SCHEMAS_FALLBACK }
    }

    @Synchronized
    fun ensureLoaded(): Boolean {
        val ctx = appContext ?: run {
            FileLogger.e(TAG, "no context")
            return false
        }
        val modelPath = CorrectionNative.ensureChannelModel(ctx)
        // 插件内部日志写进应用日志目录（文件名前缀 kime_ 便于 FileLogger 轮转管理）
        CorrectionNative.setLogFile(FileLogger.correctionSinkPath())
        if (modelPath == null) {
            FileLogger.e(TAG, "channel model unavailable")
            return false
        }
        loadState()
        // 语言分与出词均来自各方案自己的 rime 词典（wubi86 词典已补 weight 列），
        // 无独立码表资产。
        val freqPath = CorrectionNative.ensureFreqTable(ctx)
        if (freqPath == null) {
            FileLogger.e(TAG, "freq table unavailable -> language score would be flat")
        }
        FileLogger.i(TAG, "channel=$modelPath freq=$freqPath")
        CorrectionNative.setModelPaths(modelPath, freqPath ?: "")
        CorrectionNative.setEnabled(true)
        nativeActive = true
        pushChannelParams()
        return true
    }

    /** 启动时读取上次运行估计的个人偏移（filesDir；APK/assets 不携带任何人的数据）。 */
    private fun loadState() {
        val ctx = appContext ?: return
        try {
            val f = File(ctx.filesDir, STATE_FILE)
            if (!f.exists()) {
                FileLogger.i(TAG, "offset state absent (cold start, uncalibrated until warmed up)")
                return
            }
            val obj = Json.parseToJsonElement(f.readText()).jsonObject
            val g = obj["global"]?.jsonArray
            val gx = g?.get(0)?.jsonPrimitive?.floatOrNull ?: Float.NaN
            val gy = g?.get(1)?.jsonPrimitive?.floatOrNull ?: Float.NaN
            var n = 0
            (obj["keys"] as? JsonObject)?.forEach { (k, v) ->
                val idx = (k.firstOrNull() ?: ' ') - 'a'
                if (idx in 0..25 && v is JsonArray && v.size >= 2) {
                    actX[idx] = v[0].jsonPrimitive.floatOrNull ?: Float.NaN
                    actY[idx] = v[1].jsonPrimitive.floatOrNull ?: Float.NaN
                    n++
                }
            }
            // 单键缺估计的键回退全局
            for (i in 0..25) {
                if (actX[i].isNaN() && !gx.isNaN()) {
                    actX[i] = gx
                    actY[i] = gy
                }
            }
            (obj["sigma"] as? JsonArray)?.let { a ->
                if (a.size >= 2) {
                    val sx = a[0].jsonPrimitive.floatOrNull ?: Float.NaN
                    val sy = a[1].jsonPrimitive.floatOrNull ?: Float.NaN
                    if (sx.isFinite() && sy.isFinite()) {
                        scaleX = sx
                        scaleY = sy
                    }
                }
            }
            stateActive = n > 0 || !gx.isNaN()
            FileLogger.i(TAG, "offset state loaded: $n keys, global=($gx,$gy)")
        } catch (e: Throwable) {
            FileLogger.i(TAG, "offset state unavailable: ${e.message}")
        }
    }

    /** 击键观察：入滑动窗口 + 达到阈值就重估（重估为 26×256 排序，亚毫秒级）。 */
    private fun observe(idx: Int, ox: Float, oy: Float) {
        if (abs(ox) > OFF_GATE || abs(oy) > OFF_GATE) return
        val rx = ringX[idx]
        val ry = ringY[idx]
        rx.addLast(ox)
        ry.addLast(oy)
        if (rx.size > RING_CAP) {
            rx.removeFirst()
            ry.removeFirst()
        }
        ringTotal++
        if (++sinceEstimate >= ESTIMATE_EVERY) {
            sinceEstimate = 0
            reestimate()
        }
    }

    private fun reestimate() {
        // 全局中位数（所有键合并）作为单键样本不足时的兜底
        val g = pooledMedians()
        globalX = g.first
        globalY = g.second
        var changed = 0
        for (i in 0..25) {
            if (ringX[i].size >= MIN_N_PER_KEY) {
                actX[i] = medianOf(FloatArray(ringX[i].size) { ringX[i].elementAt(it) })
                actY[i] = medianOf(FloatArray(ringY[i].size) { ringY[i].elementAt(it) })
                changed++
            } else if (!globalX.isNaN()) {
                actX[i] = globalX
                actY[i] = globalY
            }
        }
        stateActive = changed > 0 || !globalX.isNaN()
        estimateScale()
        pushChannelParams()
        appContext?.let { persist(it) }
    }

    private fun persist(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastPersistAt < PERSIST_MIN_MS) return
        lastPersistAt = now
        try {
            val f = File(ctx.filesDir, STATE_FILE)
            f.parentFile?.mkdirs()
            val keys = buildJsonObject {
                for (i in 0..25) {
                    if (!actX[i].isNaN() && ringX[i].size >= MIN_N_PER_KEY) {
                        put(('a' + i).toString(), buildJsonArray {
                            add(JsonPrimitive(round2(actX[i])))
                            add(JsonPrimitive(round2(actY[i])))
                        })
                    }
                }
            }
            f.writeText(buildJsonObject {
                put("unit", tr(R.string.s_off_x_lx_bw_2_bw_off_y_ly_bh_2_9edec))
                put("n_taps", ringTotal)
                if (!globalX.isNaN()) {
                    put("global", buildJsonArray {
                        add(JsonPrimitive(round2(globalX)))
                        add(JsonPrimitive(round2(globalY)))
                    })
                }
                put("keys", keys)
                if (scaleX.isFinite() && scaleY.isFinite()) {
                    put("sigma", buildJsonArray {
                        add(JsonPrimitive(round2(scaleX)))
                        add(JsonPrimitive(round2(scaleY)))
                    })
                }
            }.toString())
        } catch (e: Throwable) {
            FileLogger.i(TAG, "persist offset state failed: ${e.message}")
        }
    }

    /**
     * 用 MAD（中位数绝对偏差）稳健估计落点散布 —— 即结构化信道核的 σ。
     * 为什么用 MAD 而不是标准差：滑窗里约 6% 是真实误触（大偏移），标准差会被拉高，
     * 而信道核要的是"用户按对时手指抖多大"。MAD 对这类离群点不敏感（崩溃点 50%）。
     */
    private fun estimateScale() {
        if (ringTotal < MIN_N_SCALE) return
        val allX = FloatArray(ringTotal)
        val allY = FloatArray(ringTotal)
        var kx = 0
        for (i in 0..25) {
            val rx = ringX[i]
            val ry = ringY[i]
            for (j in 0 until rx.size) {
                if (kx >= allX.size) break
                allX[kx] = rx.elementAt(j)
                allY[kx] = ry.elementAt(j)
                kx++
            }
        }
        if (kx < MIN_N_SCALE) return
        val sx = robustScale(allX.copyOf(kx))
        val sy = robustScale(allY.copyOf(kx))
        scaleX = if (sx in SCALE_MIN..SCALE_MAX) sx else Float.NaN
        scaleY = if (sy in SCALE_MIN..SCALE_MAX) sy else Float.NaN
        FileLogger.i(TAG, "channel scale: sx=$scaleX sy=$scaleY (n=$kx)")
    }

    private fun robustScale(values: FloatArray): Float {
        if (values.size < MIN_N_SCALE) return Float.NaN
        val med = medianOf(values.copyOf())
        for (i in values.indices) values[i] = abs(values[i] - med)
        return 1.4826f * medianOf(values)
    }

    private fun pushChannelParams() {
        if (!nativeActive) return
        if (scaleX.isNaN() || scaleY.isNaN()) return
        CorrectionNative.setChannelParams(scaleX, scaleY)
    }

    /** 全部键滑窗样本合并后的中位数 (x, y)；样本不足返回 NaN。 */
    private fun pooledMedians(): Pair<Float, Float> {
        if (ringTotal < MIN_N_GLOBAL) return Float.NaN to Float.NaN
        val allX = FloatArray(ringTotal)
        val allY = FloatArray(ringTotal)
        var kx = 0
        for (i in 0..25) for (v in ringX[i]) allX[kx++] = v
        var ky = 0
        for (i in 0..25) for (v in ringY[i]) allY[ky++] = v
        return medianOf(allX) to medianOf(allY)
    }

    private fun medianOf(values: FloatArray): Float {
        if (values.isEmpty()) return Float.NaN
        values.sort()
        val m = values.size / 2
        return if (values.size % 2 == 1) values[m] else (values[m - 1] + values[m]) / 2f
    }

    private fun round2(v: Float): Double = Math.round(v * 10000.0) / 10000.0

    /**
     * 启动自检：确认生效的 schema 里确实挂上了 correction_translator。
     * 这是"插件是否真的参与合成"的硬证据（读的是合并 custom.yaml 后的最终配置），
     * 因为 librime 内部日志在部分 ROM 上不可观测。
     */
    fun selfCheck(schemaId: String) {
        try {
            val translators = RimeEngine.getInstance().getSchemaTranslators(schemaId)
            val has = translators.any { it.startsWith("correction_translator") }
            FileLogger.i(TAG, "selfCheck schema=$schemaId translators=$translators => correction=$has")
        } catch (e: Throwable) {
            FileLogger.e(TAG, "selfCheck failed: ${e.message}")
        }
    }

    /** 幂等注入 schema 补丁；返回 true 表示刚写入（需要重新部署）。 */
    fun ensureSchemaPatch(schema: String, userDataDir: String): Boolean {
        val written = CorrectionNative.ensureSchemaPatch(schema, userDataDir)
        if (written) FileLogger.i(TAG, "injected patch into $schema.custom.yaml")
        return written
    }

    /**
     * 记录一次字母按键（几何来自 KeyButton：off 已在键宽/键高单位下）。
     * 送入侧信道前做端上自校准：先 observe 入滑窗，再减该键当前生效的偏移估计
     * （滑窗中位数，行为见 [observe]/[reestimate]）。信道以「居中 = 干净」训练，
     * 不校准会把系统性个人偏移误读成误触证据。缓冲区里存的是**校准后残差**，
     * 与 C++ 侧 min_off 门的口径一致。
     */
    @Synchronized
    fun onTapLetter(pressedIdx: Int, offXValue: Float?, offYValue: Float?, schema: String) {
        if (!enabled) return
        if (!isCorrectionSchema(schema)) return
        if (pressedIdx !in 0..25) return
        var ox = offXValue ?: Float.NaN
        var oy = offYValue ?: Float.NaN
        if (!ox.isNaN() && !oy.isNaN()) {
            observe(pressedIdx, ox, oy)
            if (!actX[pressedIdx].isNaN()) {
                ox -= actX[pressedIdx]
                oy -= actY[pressedIdx]
            }
        }
        letters.addLast('a' + pressedIdx)
        offX.addLast(ox)
        offY.addLast(oy)
        while (letters.size > MAX_TAPS) {
            letters.removeFirst(); offX.removeFirst(); offY.removeFirst()
        }
        // 必须在 Rime 处理本键**之前**送达：插件的 Query 发生在 ProcessKey 内，
        // 若等 syncWithComposition（查询之后）才推送，插件永远比编码慢一拍，
        // 尾部对齐必然失败 → 纠错永不触发。此处先推全量，sync 再做对齐校验兜底。
        CorrectionNative.setTaps(
            letters.toCharArray().map { it.code.toByte() }.toByteArray(),
            offX.toFloatArray(),
            offY.toFloatArray()
        )
    }

    /** 清空（退格/上屏/清空组合）。 */
    @Synchronized
    fun clear() {
        letters.clear(); offX.clear(); offY.clear()
        // 退格/上屏高频路径：未启用过 native 时绝不去碰（避免 lazy dlopen + 无谓 JNI）
        if (nativeActive) CorrectionNative.clearTaps()
    }

    /**
     * 与当前 Rime 组合编码对齐后推送先验。
     * 在每次按键处理完、拿到 result.inputText 后调用。
     */
    @Synchronized
    fun syncWithComposition(inputText: String) {
        if (!enabled) return
        val code = inputText.lowercase()
        if (code.isEmpty()) {
            CorrectionNative.clearTaps()
            lastPush = tr(R.string.s_bianmaweikong_qingkongxianyan)
            return
        }
        if (letters.size < code.length) {
            CorrectionNative.clearTaps()
            lastPush = tr(R.string.s_anjianshu_bianma_qingkong, letters.size, code.length)
            FileLogger.d(TAG, lastPush!!)
            return
        }
        val n = code.length
        // 只保留与当前编码等长的尾部：缓冲区必须随组合编码收敛，否则上屏/反查留下的
        // 陈旧按键会一直堆在前面，之后每次 sync 都"尾部对不上"，纠错在整段输入里哑火
        // （真机日志里一次错位后 taps-unaligned 成片出现，就是这个原因）。
        while (letters.size > n) {
            letters.removeFirst(); offX.removeFirst(); offY.removeFirst()
        }
        val arr = letters.toCharArray()
        val oxa = offX.toFloatArray()
        val oya = offY.toFloatArray()
        val l = ByteArray(n)
        val ox = FloatArray(n)
        val oy = FloatArray(n)
        val sb = StringBuilder()
        for (i in 0 until n) {
            l[i] = code[i].code.toByte()
            // 字母对不上 = 这个落点不属于当前编码的这一位，按"无几何证据"送（NaN）
            val aligned = i < arr.size && arr[i] == code[i]
            ox[i] = if (aligned) oxa[i] else Float.NaN
            oy[i] = if (aligned) oya[i] else Float.NaN
            sb.append(if (aligned) String.format("%.2f/%.2f ", ox[i], oy[i]) else "? ")
        }
        CorrectionNative.setTaps(l, ox, oy)
        lastPush = tr(R.string.s_yituisong_code_off, code, sb)
        FileLogger.d(TAG, lastPush!!)
    }
}
