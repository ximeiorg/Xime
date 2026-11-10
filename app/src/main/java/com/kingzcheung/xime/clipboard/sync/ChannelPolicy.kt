package com.kingzcheung.xime.clipboard.sync

/**
 * 通道选择与故障切换（与 macOS `ximeyi-ime` 的通道判定同一套规则）。
 *
 * 通道是有序列表，首个是首选。只对内置的 `p2p` 通道做健康判定：插件通道没有可靠的
 * 「可连通」信号，硬判会抖，所以插件被选为首选时就一直用它，直到用户在设置里改。
 *
 * 纯函数 + 纯数据，不碰 Android API，便于单测。
 */
object ChannelPolicy {
    /** 内置直连通道 id；与插件 id 同一命名空间。 */
    const val CHANNEL_P2P = "p2p"

    /** 连上并稳定这么久才算健康。 */
    const val P2P_STABLE_MS = 5_000L
    /** 掉线（或一直连不上）超过这么久才判定不可用。 */
    const val P2P_LOST_GRACE_MS = 10_000L
    /** 切换后的最短驻留：插件走第三方服务，来回切会撞限流。 */
    const val MIN_DWELL_MS = 30_000L

    /**
     * 这一轮该用哪条通道；`null` 表示没有可用通道。
     *
     * @param channels 有序通道列表（首个为优先）
     * @param active 当前活跃通道
     * @param health P2P 健康度（其它通道无信号）
     */
    fun decide(
        channels: List<String>,
        active: String?,
        health: SyncHealth,
        dwellOk: Boolean,
        now: Long,
    ): String? {
        if (channels.isEmpty()) return null
        val preferred = channels.first()
        // 还没起来：用首选。
        if (active == null) return preferred
        if (active == preferred) {
            // 首选不可用 → 退到第二条；没有备用就保持（节点还在，可能只是对端离线）。
            // 首选不可用 → 退到第二条；没有备用就保持（节点还在，可能只是对端离线）。
            return if (preferred == CHANNEL_P2P && health.stale(now)) {
                channels.getOrNull(1) ?: preferred
            } else {
                preferred
            }
        }
        // 正在备用通道：首选是 P2P 且恢复稳定、且过了驻留期 → 切回。
        return if (preferred == CHANNEL_P2P && health.stable(now) && dwellOk) preferred else active
    }
}

/** P2P 通道的健康度采样。 */
class SyncHealth {
    var connected: Boolean = false
        private set
    private var upSince: Long = 0L
    private var downSince: Long = 0L

    /** 通道刚启用：从「未连接」开始计宽限期。 */
    fun started(now: Long) {
        connected = false
        upSince = 0L
        downSince = now
    }

    fun note(connected: Boolean, now: Long) {
        if (connected == this.connected) return
        this.connected = connected
        if (connected) {
            upSince = now
            downSince = 0L
        } else {
            upSince = 0L
            downSince = now
        }
    }

    fun stable(now: Long): Boolean =
        connected && upSince > 0L && now - upSince >= ChannelPolicy.P2P_STABLE_MS

    fun stale(now: Long): Boolean =
        !connected && downSince > 0L && now - downSince >= ChannelPolicy.P2P_LOST_GRACE_MS
}
