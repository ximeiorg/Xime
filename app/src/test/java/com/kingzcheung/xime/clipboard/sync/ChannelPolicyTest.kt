package com.kingzcheung.xime.clipboard.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通道选择与故障切换判定（[ChannelPolicy]）。规则与 macOS 端保持一致。 */
class ChannelPolicyTest {

    private val t0 = 1_000_000L
    private val p2p = ChannelPolicy.CHANNEL_P2P
    private val webdav = "webdav"

    private fun health() = SyncHealth().apply { started(t0) }

    @Test
    fun `没有通道时不选任何通道`() {
        assertNull(ChannelPolicy.decide(emptyList(), null, health(), true, t0))
        assertNull(ChannelPolicy.decide(emptyList(), p2p, health(), true, t0))
    }

    @Test
    fun `首次直接用首选通道`() {
        assertEquals(
            p2p,
            ChannelPolicy.decide(listOf(p2p, webdav), null, health(), true, t0)
        )
        assertEquals(
            webdav,
            ChannelPolicy.decide(listOf(webdav, p2p), null, health(), true, t0)
        )
    }

    @Test
    fun `首选掉线超过宽限才退到备用`() {
        val h = health()
        // 宽限期内保持
        assertEquals(p2p, ChannelPolicy.decide(listOf(p2p, webdav), p2p, h, true, t0 + 5_000))
        // 超过宽限 → 备用
        assertEquals(
            webdav,
            ChannelPolicy.decide(
                listOf(p2p, webdav), p2p, h, true, t0 + ChannelPolicy.P2P_LOST_GRACE_MS
            )
        )
        // 没有备用就保持 p2p（节点继续等对端）
        assertEquals(
            p2p,
            ChannelPolicy.decide(listOf(p2p), p2p, h, true, t0 + ChannelPolicy.P2P_LOST_GRACE_MS)
        )
    }

    @Test
    fun `备用通道上等首选稳定且过驻留才切回`() {
        val h = health()
        h.note(true, t0)
        // 刚连上不稳定 → 留在备用
        assertEquals(webdav, ChannelPolicy.decide(listOf(p2p, webdav), webdav, h, true, t0 + 1_000))
        // 稳定但驻留未满 → 留在备用
        assertEquals(
            webdav,
            ChannelPolicy.decide(
                listOf(p2p, webdav), webdav, h, false, t0 + ChannelPolicy.P2P_STABLE_MS
            )
        )
        // 稳定 + 驻留满足 → 切回
        assertEquals(
            p2p,
            ChannelPolicy.decide(
                listOf(p2p, webdav), webdav, h, true, t0 + ChannelPolicy.P2P_STABLE_MS
            )
        )
    }

    @Test
    fun `首选是插件时不做健康判定`() {
        val h = health()
        h.note(true, t0)
        // 首选插件：即使 p2p 健康也不切（没有可靠的插件健康信号，切了会抖）
        assertEquals(
            webdav,
            ChannelPolicy.decide(listOf(webdav, p2p), webdav, h, true, t0 + 60_000)
        )
    }

    @Test
    fun `健康度按时间窗判定`() {
        val h = health()
        assertFalse(h.stale(t0))
        assertFalse(h.stable(t0))
        assertTrue(h.stale(t0 + ChannelPolicy.P2P_LOST_GRACE_MS))

        val up = t0 + 20_000
        h.note(true, up)
        assertFalse(h.stable(up))
        assertTrue(h.stable(up + ChannelPolicy.P2P_STABLE_MS))
        assertFalse(h.stale(up + ChannelPolicy.P2P_STABLE_MS))
    }
}
