package com.kingzcheung.xime.correction

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 五笔码表（预编译自 assets/rime/wubi86.dict.yaml，见 jiucuo/scripts/export_code_table.py）。
 *
 * 提供码串评分：精确命中→log 频率占比；仅前缀（合法码的前缀）→ 固定前缀分；非法→重罚。
 * 用于纠错的解码判定：只有当"改成邻键后码更合法/更高频"时才认为该纠。
 */
object WubiCodeTable {
    private const val TAG = "WubiCodeTable"
    private const val PREFIX = -6.0f
    private const val INVALID = -30.0f
    private const val ASSET = "corrector/wubi_codes.bin"

    private var codes: Array<String> = emptyArray()
    private var logw: FloatArray = FloatArray(0)
    private var texts: Array<String> = emptyArray()

    fun load(context: Context, asset: String = ASSET) {
        if (codes.isNotEmpty()) return
        try {
            val bytes = context.assets.open(asset).use { it.readBytes() }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            bb.get(magic)
            if (String(magic, Charsets.US_ASCII) != "JWC2") {
                Log.e(TAG, "bad code table magic")
                return
            }
            val count = bb.getInt()
            val cs = Array(count) { "" }
            val ws = FloatArray(count)
            val ts = Array(count) { "" }
            for (i in 0 until count) {
                val codeLen = bb.get().toInt() and 0xFF
                val cb = ByteArray(codeLen)
                bb.get(cb)
                cs[i] = String(cb, Charsets.US_ASCII)
                val textLen = bb.get().toInt() and 0xFF
                val tb = ByteArray(textLen)
                bb.get(tb)
                ts[i] = String(tb, Charsets.UTF_8)
                ws[i] = bb.float
            }
            codes = cs
            logw = ws
            texts = ts
            Log.i(TAG, "wubi code table loaded: $count codes")
        } catch (e: Exception) {
            Log.e(TAG, "load code table failed", e)
        }
    }

    private fun indexOf(code: String): Int {
        if (codes.isEmpty() || code.isEmpty()) return -1
        var lo = 0
        var hi = codes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (codes[mid] < code) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 该码精确命中的最高频词条；无精确命中返回 null。 */
    fun topText(code: String): String? {
        val i = indexOf(code)
        return if (i in codes.indices && codes[i] == code) texts[i] else null
    }

    /** 码串评分（越大越好；INVALID 表示既非合法码也非任何合法码的前缀）。 */
    fun score(code: String): Float {
        val i = indexOf(code)
        if (i < 0 || i >= codes.size) return INVALID
        if (codes[i] == code) return logw[i]
        if (codes[i].startsWith(code)) return PREFIX
        return INVALID
    }

    /** 该编码是否"可用"：精确命中某码，或为某合法码的前缀（非非法）。 */
    fun isLegal(code: String): Boolean = score(code) > INVALID
}
