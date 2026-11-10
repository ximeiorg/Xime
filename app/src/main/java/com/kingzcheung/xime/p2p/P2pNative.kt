package com.kingzcheung.xime.p2p

/**
 * xime-p2p 的原生门面（`libxime_p2p_jni.so`）。
 *
 * 协议、连接与配对状态全在 Rust 侧；这里只声明 JNI 符号，参数和返回值都是
 * JSON 字符串，由 [P2pBridge] 解析。SO 由 `scripts/build-p2p-android.sh`
 * 生成，四个 ABI 缺任意一个时 [loaded] 为 false，调用方不得再调其它方法。
 *
 * 类名/包名与 Rust 侧 `Java_com_kingzcheung_xime_p2p_P2pNative_*` 逐字对应，
 * 改名必须同步改 `app/src/main/rust/xime-p2p-jni/src/lib.rs`。
 */
object P2pNative {
    val loaded: Boolean = runCatching { System.loadLibrary("xime_p2p_jni") }.isSuccess

    external fun identity(dir: String): String

    external fun start(dir: String, listen: Boolean, discover: Boolean, port: Int): String

    external fun stop(): Int

    external fun setClipboard(text: String, source: String): Int

    external fun poll(max: Int): String

    external fun status(): String

    external fun pairDisplay(dir: String, name: String, timeoutSec: Int): String

    external fun pairInput(dir: String, name: String, code: String, addr: String, timeoutSec: Int): String

    external fun pairPoll(): String

    external fun pairCancel(): Int

    external fun peers(dir: String): String

    external fun forget(dir: String, did: String): Int

    external fun buildInfo(): String
}
