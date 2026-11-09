package com.kingzcheung.xime.p2p

import android.content.Context
import android.util.Log

/**
 * 局域网直连同步的节点生命周期。
 *
 * 只在「剪贴板同步 = 局域网直连」时启动；没有已配对设备就不占端口。节点跑在
 * 输入法进程里，键盘使用时在线，进程被回收即断开（与协议文档 §9 的状态机一致）。
 *
 * 所有 JNI 调用都走 Rust 侧的全局 Node，这里用锁保证 start/stop 不交错；
 * push/poll 由 [P2pClipboardPlugin] 从同步引擎串行调用。
 */
object P2pSyncController {
    private const val TAG = "P2pSync"
    const val PLUGIN_ID = "p2p"

    @Volatile
    var isRunning: Boolean = false
        private set

    /** 对端是否已连上（来自 `peer` 事件，断开即置 false）。 */
    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var pairedCount: Int = 0
        private set

    @Volatile
    private var dir: String? = null

    @Volatile
    private var did: String? = null

    @Synchronized
    fun start(context: Context): Boolean {
        if (isRunning) return true
        if (!P2pNative.loaded) {
            Log.w(TAG, "原生库未加载，跳过启动")
            return false
        }
        val dataDir = P2pBridge.dir(context)
        val identity = P2pBridge.identity(dataDir)
        if (!identity.ok || identity.did.isNullOrEmpty()) {
            Log.w(TAG, "身份不可用：${identity.error}")
            return false
        }
        val peers = P2pBridge.peers(dataDir)
        if (peers.isEmpty()) {
            Log.d(TAG, "尚无已配对设备，暂不启动")
            dir = dataDir
            did = identity.did
            pairedCount = 0
            return false
        }
        val started = P2pBridge.start(dataDir, listen = true, discover = true)
        if (!started.ok) {
            Log.w(TAG, "节点启动失败：${started.error}")
            return false
        }
        dir = dataDir
        did = identity.did
        pairedCount = peers.size
        isConnected = false
        isRunning = true
        Log.d(TAG, "节点已启动，端口 ${started.port}，已配对 ${peers.size} 台")
        return true
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        P2pBridge.stop()
        isRunning = false
        isConnected = false
        Log.d(TAG, "节点已停止")
    }

    /** 上行：文本交给 Rust 侧（hash 由 `Profile::from_text` 统一计算）。 */
    fun pushText(text: String): Boolean {
        if (!isRunning || text.isEmpty()) return false
        return P2pBridge.setClipboard(text, did.orEmpty())
    }

    /** 下行：取走事件队列，返回最新一条远端文本；同时更新连接状态。 */
    fun pollRemote(): P2pProfile? {
        if (!isRunning) return null
        var latest: P2pProfile? = null
        P2pBridge.poll(32).forEach { event ->
            when (event.type) {
                "peer" -> isConnected = event.connected == true
                "warning" -> Log.w(TAG, "p2p: ${event.message}")
                "clipboard" -> event.profile
                    ?.takeIf { it.type == "text" && it.text.isNotEmpty() }
                    ?.let { latest = it }
            }
        }
        return latest
    }
}
