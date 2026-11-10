package com.kingzcheung.xime.p2p

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** S0 冒烟：确认 .so 真的链进包、JNI 符号对得上、Rust 侧能建目录/身份/节点。 */
@RunWith(AndroidJUnit4::class)
class P2pNativeSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 原生库可加载且身份与节点状态可用() {
        val info = P2pBridge.buildInfo()
        assertTrue("buildInfo 失败：${info.error}", info.ok)
        assertTrue("包内 .so 未链接 xime-p2p", info.hasP2p)
        assertEquals("v0.2", info.protocol)

        val dir = P2pBridge.dir(context)
        val identity = P2pBridge.identity(dir)
        assertTrue("identity 失败：${identity.error}", identity.ok)
        assertEquals(32, identity.did!!.length)

        val started = P2pBridge.start(dir, listen = true, discover = false)
        assertTrue("start 失败：${started.error}", started.ok)
        assertTrue(started.port > 0)
        assertTrue(P2pBridge.status().running)

        assertTrue(P2pBridge.setClipboard("你好🙂", identity.did))
        assertTrue(P2pBridge.stop())
        assertFalse(P2pBridge.status().running)
    }
}
