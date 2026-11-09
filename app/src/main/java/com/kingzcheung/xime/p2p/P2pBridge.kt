package com.kingzcheung.xime.p2p

import android.content.Context
import java.io.File

/**
 * 跨设备剪贴板（局域网直连）的 Kotlin 门面。
 *
 * 只负责「调用原生 + 把 JSON 变成模型」，业务判定（何时启停、回声抑制）留给
 * 同步驱动。主 App 的配对页与输入法服务同包同进程，`filesDir/p2p` 天然共享，
 * 不需要鸿蒙那样的共享沙箱。
 */
object P2pBridge {
    private const val NOT_LOADED = "p2p 原生库未加载"

    /** `identity.bin` / `peers.json` 所在目录；Rust 侧会自行创建。 */
    fun dir(context: Context): String = File(context.filesDir, "p2p").absolutePath

    fun buildInfo(): P2pBuildInfo = if (!P2pNative.loaded) {
        P2pBuildInfo(ok = false, error = NOT_LOADED)
    } else {
        runCatching { P2pJson.buildInfo(P2pNative.buildInfo()) }
            .getOrElse { P2pBuildInfo(ok = false, error = describe(it)) }
    }

    fun identity(dir: String): P2pIdentityInfo = if (!P2pNative.loaded) {
        P2pIdentityInfo(ok = false, error = NOT_LOADED)
    } else {
        runCatching { P2pJson.identity(P2pNative.identity(dir)) }
            .getOrElse { P2pIdentityInfo(ok = false, error = describe(it)) }
    }

    fun start(dir: String, listen: Boolean = true, discover: Boolean = true, port: Int = 0): P2pStartInfo =
        if (!P2pNative.loaded) {
            P2pStartInfo(ok = false, error = NOT_LOADED)
        } else {
            runCatching { P2pJson.start(P2pNative.start(dir, listen, discover, port)) }
                .getOrElse { P2pStartInfo(ok = false, error = describe(it)) }
        }

    fun stop(): Boolean = P2pNative.loaded && P2pNative.stop() == 1

    fun setClipboard(text: String, source: String): Boolean =
        P2pNative.loaded && P2pNative.setClipboard(text, source) == 1

    fun poll(max: Int = 32): List<P2pEvent> =
        if (!P2pNative.loaded) emptyList() else runCatching { P2pJson.events(P2pNative.poll(max)) }.getOrDefault(emptyList())

    fun status(): P2pStatusInfo = if (!P2pNative.loaded) {
        P2pStatusInfo(ok = false, error = NOT_LOADED)
    } else {
        runCatching { P2pJson.status(P2pNative.status()) }
            .getOrElse { P2pStatusInfo(ok = false, error = describe(it)) }
    }

    fun pairDisplay(dir: String, name: String, timeoutSec: Int = 60): P2pPairStart = if (!P2pNative.loaded) {
        P2pPairStart(ok = false, error = NOT_LOADED)
    } else {
        runCatching { P2pJson.pairStart(P2pNative.pairDisplay(dir, name, timeoutSec)) }
            .getOrElse { P2pPairStart(ok = false, error = describe(it)) }
    }

    fun pairInput(
        dir: String,
        name: String,
        code: String,
        addr: String = "",
        timeoutSec: Int = 60
    ): P2pResult = if (!P2pNative.loaded) {
        P2pResult(ok = false, error = NOT_LOADED)
    } else {
        runCatching { P2pJson.result(P2pNative.pairInput(dir, name, code, addr, timeoutSec)) }
            .getOrElse { P2pResult(ok = false, error = describe(it)) }
    }

    fun pairPoll(): P2pPairState = if (!P2pNative.loaded) {
        P2pPairState(ok = false, error = NOT_LOADED)
    } else {
        runCatching { P2pJson.pairState(P2pNative.pairPoll()) }
            .getOrElse { P2pPairState(ok = false, error = describe(it)) }
    }

    fun pairCancel(): Boolean = P2pNative.loaded && P2pNative.pairCancel() == 1

    fun peers(dir: String): List<P2pPeer> =
        if (!P2pNative.loaded) emptyList() else runCatching { P2pJson.peers(P2pNative.peers(dir)) }.getOrDefault(emptyList())

    fun forget(dir: String, did: String): Boolean = P2pNative.loaded && P2pNative.forget(dir, did) == 1

    private fun describe(error: Throwable): String =
        "解析原生返回失败：${error.message ?: error.javaClass.simpleName}"
}
