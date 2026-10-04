package com.kingzcheung.xime.correction

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.rime.RimeEngine
import java.util.ArrayDeque
import java.util.Locale

/**
 * 纠错模型控制器。
 *
 * 维护最近 W 次字母按键的特征窗口，调原生前向得到 `p_geo`，再叠加**解码判定**：
 *   几何 top-k 候选 → 五笔码表校验（合法性/频率）+ margin → 决定是否"该改"。
 *
 * 两种用途（互相独立，可同时开）：
 *  - [enabled] 影子模式：只把每次判定写成 `e=shadow` 日志（不改行为）。
 *  - [active]  智能纠错：把判定出的修正编码交给 **Rime 旁路会话**出词（回退本地码表），
 *              结果作为**辅助候选**并入候选栏/展开页，用户自选，绝不替换原始输入。
 */
object CorrectorShadow {
    private const val TAG = "CorrectorShadow"
    private const val ALPHA = "abcdefghijklmnopqrstuvwxyz"
    private const val ASSET = "corrector/corrector_wubi.bin"
    private const val TOPK = 3
    /** 解码 margin：(候选概率 − 按下键概率) 低于此值不认为是按错。 */
    private const val MARGIN = 0.4f
    /** 码表"非法"分（与 WubiCodeTable 一致）：修正码高于此值即可用。 */
    private const val INVALID = -30.0f
    /** 单个修正编码最多取几个 Rime 候选。 */
    private const val MAX_CORRECTIONS = 2
    /** 启用纠错的方案（五笔主码输入；拼音仅作反查，不影响主码判定）。 */
    private val WUBI_SCHEMAS = setOf("wubi86", "wubi86_pinyin")

    const val W = 6

    /** 修正候选：词条文本 + 展示注释。 */
    data class Correction(val text: String, val comment: String)

    /** 影子模式（只记录判定）。 */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            field = value
            if (value) ensureLoaded()
        }

    /** 智能纠错（把判定出的修正作为辅助候选交出）。 */
    @Volatile
    var active: Boolean = false
        set(value) {
            field = value
            if (value) ensureLoaded()
        }

    private var appContext: Context? = null
    private var handle: CorrectorNative.Handle? = null
    private val buf = ArrayDeque<FloatArray>(W)
    private val pressedBuf = ArrayDeque<Int>(W)

    /** 最近一次按键判定的修正字母（-1 = 无）。 */
    private var pendingDec: Int = -1
    private var pendingCode: String = ""

    /** 当前组合的修正候选缓存（键为编码），供紧凑列与展开页共用，避免重复查询。 */
    private var cacheCode: String = ""
    private var cacheList: List<Correction> = emptyList()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @Synchronized
    private fun ensureLoaded() {
        val ctx = appContext ?: return
        if (handle == null) {
            handle = CorrectorNative.createFromAsset(ctx, ASSET)
            if (handle == null) Log.e(TAG, "corrector weights not loaded: $ASSET")
        }
        WubiCodeTable.load(ctx)
    }

    /** 清空窗口上下文（提交/退格时调用，避免把上一个码的字母带进本次预测）。 */
    @Synchronized
    fun reset() {
        buf.clear()
        pressedBuf.clear()
        pendingDec = -1
        pendingCode = ""
        cacheCode = ""
        cacheList = emptyList()
    }

    @Synchronized
    fun onTapLetter(
        pressedIdx: Int,
        x: Float,
        y: Float,
        offX: Float,
        offY: Float,
        schema: String,
    ) {
        if ((!enabled && !active) || pressedIdx !in 0..25) return
        val h = handle ?: run { ensureLoaded(); handle } ?: return

        val f = FloatArray(CorrectorNative.IN_DIM)
        f[0] = x; f[1] = y; f[2] = offX; f[3] = offY
        f[4 + pressedIdx] = 1f
        buf.addLast(f)
        pressedBuf.addLast(pressedIdx)
        while (buf.size > W) buf.removeFirst()
        while (pressedBuf.size > W) pressedBuf.removeFirst()

        val start = W - buf.size
        val input = FloatArray(W * CorrectorNative.IN_DIM)
        var i = 0
        for (row in buf) {
            System.arraycopy(row, 0, input, (start + i) * CorrectorNative.IN_DIM, CorrectorNative.IN_DIM)
            i++
        }
        val logits = CorrectorNative.forward(h, input, W) ?: return
        CorrectorNative.softmax(logits, W)
        val base = (W - 1) * CorrectorNative.VOCAB
        val order = (0 until CorrectorNative.VOCAB).sortedByDescending { logits[base + it] }

        // 解码判定：按模型概率从高到低，取第一个 margin 足够、且修正码"可用"
        // （精确命中或合法前缀，而非非法码）的候选。码表只做合法性否决，不再要求
        // "比原码更优"——打字途中原码/修正码常同为合法前缀，那样会挡掉绝大多数真错。
        val pressedCode = pressedBuf.joinToString("") { ALPHA[it].toString() }
        val sIn = WubiCodeTable.score(pressedCode)
        val pPressed = logits[base + pressedIdx]
        var dec = -1
        var sOut = Float.NEGATIVE_INFINITY
        for (c in order.take(TOPK)) {
            if (logits[base + c] - pPressed < MARGIN) continue
            val s = WubiCodeTable.score(pressedCode.dropLast(1) + ALPHA[c])
            if (s > INVALID) {
                sOut = s
                dec = c
                break
            }
        }

        // 智能纠错：仅五笔主码方案（wubi86 / wubi86_pinyin）下留待候选栏注入；新按键使旧缓存失效
        val wubi = schema.trim() in WUBI_SCHEMAS
        pendingDec = if (wubi) dec else -1
        pendingCode = pressedCode
        cacheCode = ""
        cacheList = emptyList()

        if (order[0] == pressedIdx) return  // 不改就不记
        if (!enabled) return
        KeyTapLogger.recordShadow(
            pressed = ALPHA[pressedIdx].toString(),
            top = order.take(TOPK).joinToString("") { ALPHA[it].toString() },
            probs = order.take(TOPK).joinToString(",") {
                String.format(Locale.US, "%.3f", logits[base + it])
            },
            offX = offX,
            offY = offY,
            act = dec >= 0,
            dec = if (dec >= 0) ALPHA[dec].toString() else "",
            sIn = sIn,
            sOut = sOut,
        )
    }

    /**
     * 当前组合的修正候选（**只读缓存**，不做 Rime 查询）。供展开页刷新等复用。
     * @param inputText 当前 Rime 组合编码
     */
    @Synchronized
    fun cachedCorrections(inputText: String, asciiMode: Boolean): List<Correction> {
        if (!active || asciiMode || inputText.isEmpty()) return emptyList()
        val lower = inputText.lowercase()
        return if (lower == cacheCode) cacheList else emptyList()
    }

    /**
     * 计算当前组合的修正候选：修正编码 → **Rime 旁路会话**出词（回退本地码表）。
     * 仅在 key-processing 线程调用（会查 Rime）；结果按编码缓存，供紧凑列/展开页共用。
     *
     * @param inputText 当前 Rime 组合编码（权威，用于还原"改哪个键"）
     * @param asciiMode 英文态不纠错
     */
    @Synchronized
    fun correctionsFor(inputText: String, asciiMode: Boolean): List<Correction> {
        if (!active || asciiMode || inputText.isEmpty()) return emptyList()
        val lower = inputText.lowercase()
        if (lower == cacheCode) return cacheList
        val dec = pendingDec
        if (dec < 0 || !lower.endsWith(pendingCode)) {
            cacheCode = lower
            cacheList = emptyList()
            return cacheList
        }
        val corrected = lower.dropLast(1) + ALPHA[dec]

        val out = ArrayList<Correction>(MAX_CORRECTIONS)
        val seen = HashSet<String>()
        val rime = RimeEngine.getInstance()
        for (c in rime.bypassLookup(corrected, MAX_CORRECTIONS)) {
            val t = c.text.trim()
            if (t.isEmpty() || !seen.add(t)) continue
            out.add(Correction(t, "纠错"))
            if (out.size >= MAX_CORRECTIONS) break
        }
        if (out.isEmpty()) {
            WubiCodeTable.topText(corrected)?.takeIf { it.isNotEmpty() }?.let {
                out.add(Correction(it, "纠错"))
            }
        }
        cacheCode = lower
        cacheList = out
        return out
    }
}
