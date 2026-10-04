package com.kingzcheung.xime.correction

import android.content.Context
import android.util.Log

/**
 * 相邻键纠错几何模型的原生前向（libjiucuo_corrector.so，零第三方依赖）。
 *
 * 输入 [W, 30]：4 维几何（x, y, off_x, off_y）+ 26 维"实际按下字母" one-hot。
 * 输出 [W, 26]：每个位置的 26 字母 logits。
 *
 * 与 Python 训练/导出完全同口径（见 jiucuo/native、scripts/export_cpp.py）；
 * 数值已验证与 PyTorch 一致（最大差异 ~5e-6）。
 */
object CorrectorNative {
    private const val TAG = "CorrectorNative"
    const val IN_DIM = 30
    const val VOCAB = 26

    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("jiucuo_corrector")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "load libjiucuo_corrector.so failed", e)
            false
        }
    }

    class Handle internal constructor(internal val ptr: Long) : AutoCloseable {
        override fun close() {
            if (ptr != 0L) nativeFree(ptr)
        }
    }

    /** 从 assets 路径读取权重并创建句柄；库未加载或权重非法时返回 null。 */
    fun createFromAsset(context: Context, assetPath: String): Handle? {
        if (!loaded) return null
        return try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            val p = nativeCreate(bytes)
            if (p == 0L) {
                Log.e(TAG, "invalid weights: $assetPath")
                null
            } else {
                Handle(p)
            }
        } catch (e: Exception) {
            Log.e(TAG, "load weights failed: $assetPath", e)
            null
        }
    }

    /** 前向：x 长度须为 W*IN_DIM；成功返回 W*VOCAB logits，失败返回 null。 */
    fun forward(handle: Handle, x: FloatArray, w: Int): FloatArray? {
        if (handle.ptr == 0L || x.size != w * IN_DIM || w <= 0) return null
        val out = nativeForward(handle.ptr, x, w)
        return if (out.isEmpty()) null else out
    }

    /** 对 [W*VOCAB] 按行原地 softmax。 */
    fun softmax(logits: FloatArray, w: Int) = nativeSoftmax(logits, w)

    private external fun nativeCreate(weights: ByteArray): Long
    private external fun nativeForward(handle: Long, x: FloatArray, w: Int): FloatArray
    private external fun nativeSoftmax(logits: FloatArray, w: Int)
    private external fun nativeFree(handle: Long)
}
