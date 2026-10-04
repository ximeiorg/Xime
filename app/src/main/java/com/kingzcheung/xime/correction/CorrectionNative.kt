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

    fun setModelPaths(channelPath: String) {
        if (!loaded) return
        try {
            nativeSetModelPaths(channelPath, "")
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
    private external fun nativeSetEnabled(enabled: Boolean)
    private external fun nativeSetModelPaths(channelPath: String, codeTablePath: String)
    private external fun nativeEnsureCorrectedSchemaPatch(schema: String, userDataDir: String): Boolean
}
