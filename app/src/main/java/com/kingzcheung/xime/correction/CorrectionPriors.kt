package com.kingzcheung.xime.correction

import android.content.Context
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.util.FileLogger
import java.util.ArrayDeque

/**
 * 邻键误触纠错的**先验推送器**（Kotlin 侧薄适配层）。
 *
 * 职责边界（推倒重来后的分工）：
 *  - 这里只负责：收集最近字母按键的几何 → 与 Rime 当前组合编码**对齐** → 经侧信道送给插件；
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

    private var appContext: Context? = null
    private val letters = ArrayDeque<Char>(MAX_TAPS)
    private val offX = ArrayDeque<Float>(MAX_TAPS)
    private val offY = ArrayDeque<Float>(MAX_TAPS)

    /** native 侧是否曾被成功启用。关闭态下所有 JNI 调用必须短路：
     *  CorrectionNative 的 loaded 是 lazy 的，任何访问都会触发 rime_jni 的
     *  dlopen——开关关闭的用户不该在冷启动/每次退格时代价这笔加载。 */
    @Volatile
    private var nativeActive = false

    /** 最近一次推送状态（供设置页/日志诊断）。 */
    @Volatile
    var lastPush: String = "未初始化"

    @Volatile
    var enabled: Boolean = false
        set(value) {
            field = value
            if (value) {
                val ok = ensureLoaded()
                lastPush = if (ok) "已启用，等待按键" else "启用失败（模型/码表不可用）"
                FileLogger.i(TAG, "enabled=$ok ctx=${appContext != null}")
            } else {
                if (nativeActive) {
                    CorrectionNative.setEnabled(false)
                    CorrectionNative.clearTaps()
                    nativeActive = false
                }
                letters.clear(); offX.clear(); offY.clear()
                lastPush = "已关闭"
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
        FileLogger.i(TAG, "channel=$modelPath")
        CorrectionNative.setModelPaths(modelPath)
        CorrectionNative.setEnabled(true)
        nativeActive = true
        return true
    }

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

    /** 记录一次字母按键（几何来自 KeyButton：off 已在键宽/键高单位下）。 */
    @Synchronized
    fun onTapLetter(pressedIdx: Int, offXValue: Float?, offYValue: Float?, schema: String) {
        if (!enabled) return
        if (!isCorrectionSchema(schema)) return
        if (pressedIdx !in 0..25) return
        letters.addLast('a' + pressedIdx)
        offX.addLast(offXValue ?: Float.NaN)
        offY.addLast(offYValue ?: Float.NaN)
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
            lastPush = "编码为空 → 清空先验"
            return
        }
        if (letters.size < code.length) {
            CorrectionNative.clearTaps()
            lastPush = "按键数(${letters.size})<编码(${code.length}) → 清空"
            FileLogger.d(TAG, lastPush!!)
            return
        }
        val n = code.length
        val arr = letters.toCharArray()
        val off = arr.size - n
        for (i in 0 until n) {
            if (arr[off + i] != code[i]) {
                CorrectionNative.clearTaps()
                lastPush = "按键序列与编码不一致(${String(arr)} vs $code) → 清空"
                FileLogger.d(TAG, lastPush!!)
                return
            }
        }
        val l = ByteArray(n)
        val ox = FloatArray(n)
        val oy = FloatArray(n)
        val oxa = offX.toFloatArray()
        val oya = offY.toFloatArray()
        val sb = StringBuilder()
        for (i in 0 until n) {
            l[i] = code[i].code.toByte()
            ox[i] = oxa[off + i]
            oy[i] = oya[off + i]
            sb.append(String.format("%.2f/%.2f ", ox[i], oy[i]))
        }
        CorrectionNative.setTaps(l, ox, oy)
        lastPush = "已推送 code=$code off=[$sb]"
        FileLogger.d(TAG, lastPush!!)
    }
}
