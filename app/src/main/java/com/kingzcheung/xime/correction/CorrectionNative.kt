package com.kingzcheung.xime.correction

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.util.FileLogger
import java.io.File

/**
 * 邻键误触纠错插件的 JNI 接口（librime translator: correction_translator）：
 *  1. 把最近按键的几何（字母 + off_x/off_y）作为**先验侧信道**送入 librime 插件；
 *  2. 确保目标五笔方案已注入 correction_translator（写 custom.yaml，幂等）。
 * 纠错的候选生成、打分、出词、排序全部在 librime 插件里完成（用同一份词典/用户词典）。
 */
object CorrectionNative {
    private const val TAG = "CorrectionNative"
    private const val ASSET = "corrector/channel.onnx"
    private const val FREQ_ASSET = "corrector/wubi_freq.bin"

    private val loaded: Boolean by lazy {
        try {
            // 符号在 librime_jni 内（correction_jni.cc），随 System.loadLibrary("rime_jni") 载入
            System.loadLibrary("rime_jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "load librime_jni failed", e)
            false
        }
    }

    /** 告诉插件把内部日志追加到哪个文件（部分 ROM 静音 logcat，文件日志是唯一可观测通道）。 */
    fun setLogFile(path: String) {
        if (!loaded) return
        try {
            nativeSetLogFile(path)
        } catch (e: Throwable) {
            FileLogger.e(TAG, "setLogFile failed: ${e.message}")
        }
    }

    /** 把 channel.onnx 从 assets 解出到 filesDir（ONNX Runtime 需要文件路径）。 */
    fun ensureChannelModel(context: Context): String? {
        return try {
            val dst = File(context.filesDir, "corrector/channel.onnx")
            if (!dst.exists() || dst.length() == 0L) {
                dst.parentFile?.mkdirs()
                context.assets.open(ASSET).use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
            }
            dst.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "extract channel model failed", e)
            null
        }
    }

    /**
     * 把插件自带的词频表（真实 ln 频次）解出到 filesDir。
     * 为什么需要：真机 wubi86.dict.yaml 的 weight 列只有 {10,20}，词与词的语言分
     * 几乎是常数（Δ ≤ 0.69 nats），而几何项是 −2~−12 nats —— 语言项压不过几何，
     * 纠错就永远不出词。这份表是语言项唯一真实的来源。
     */
    fun ensureFreqTable(context: Context): String? {
        return try {
            val dst = File(context.filesDir, "corrector/wubi_freq.bin")
            // 只判"存在"会把升级后的旧格式文件一直留在盘上（插件按魔数校验会加载失败），
            // 必须比对长度：词频包格式/内容一变，长度必变。
            // 无条件覆盖：词频包是纯数据（688KB），每次启动重写一次只有几毫秒；
            // 而"存在就跳过"会把旧格式/旧内容一直留在盘上，插件按魔数校验直接加载失败
            // （真机日志里就是 freq table load failed → 语言分退回常数 → 永不纠错）。
            run {
                dst.parentFile?.mkdirs()
                context.assets.open(FREQ_ASSET).use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                FileLogger.i(TAG, "freq table refreshed: ${dst.length()} bytes")
            }
            dst.absolutePath
        } catch (e: Exception) {
            FileLogger.e(TAG, "extract freq table failed: ${e.message}")
            null
        }
    }

    fun setModelPaths(channelPath: String, freqPath: String) {
        if (!loaded) return
        try {
            nativeSetModelPaths(channelPath, freqPath)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSetModelPaths failed", e)
        }
    }

    /** 设置（或清空）几何先验：三个数组等长，off 为 NaN 表示该键没有真实坐标。 */
    fun setTaps(letters: ByteArray, offX: FloatArray, offY: FloatArray) {
        if (!loaded) return
        try {
            nativeSetTaps(letters, offX, offY)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSetTaps failed", e)
        }
    }

    /**
     * 端上自校准的信道宽度（键宽/键高单位，σ）。
     * 结构化信道核用它把"落点偏移"折算成标准噪声单位；不校准时插件用 TSI 实测默认值。
     */
    fun setChannelParams(sigmaX: Float, sigmaY: Float) {
        if (!loaded) return
        try {
            nativeSetChannelParams(sigmaX, sigmaY)
        } catch (e: Throwable) {
            FileLogger.e(TAG, "setChannelParams failed: ${e.message}")
        }
    }

    fun clearTaps() {
        if (!loaded) return
        try {
            nativeClearTaps()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeClearTaps failed", e)
        }
    }

    /** 开关（关闭时插件直接返回空，对输入零影响）。 */
    fun setEnabled(enabled: Boolean) {
        if (!loaded) return
        try {
            nativeSetEnabled(enabled)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSetEnabled failed", e)
        }
    }

    /** 幂等注入 schema 补丁；返回 true 表示本次写入了补丁（调用方需触发部署）。 */
    fun ensureSchemaPatch(schema: String, userDataDir: String): Boolean {
        if (!loaded) return false
        return try {
            nativeEnsureCorrectedSchemaPatch(schema, userDataDir)
        } catch (e: Throwable) {
            Log.e(TAG, "ensureSchemaPatch failed", e)
            false
        }
    }

    private external fun nativeSetLogFile(path: String)
    private external fun nativeSetTaps(letters: ByteArray, offX: FloatArray, offY: FloatArray)
    private external fun nativeClearTaps()
    private external fun nativeSetChannelParams(sigmaX: Float, sigmaY: Float)
    private external fun nativeSetEnabled(enabled: Boolean)
    private external fun nativeSetModelPaths(channelPath: String, codeTablePath: String)
    private external fun nativeEnsureCorrectedSchemaPatch(schema: String, userDataDir: String): Boolean
}
