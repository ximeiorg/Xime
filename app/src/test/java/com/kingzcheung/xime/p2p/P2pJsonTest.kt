package com.kingzcheung.xime.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pJsonTest {

    @Test
    fun `解析剪贴板事件`() {
        val raw = """
            {"ok":true,"events":[
              {"type":"clipboard","profile":{
                "type":"text","hash":"abc","text":"你好🙂","has_data":false,
                "data_name":null,"size":10,"source":"device-a"}}
            ]}
        """.trimIndent()

        val events = P2pJson.events(raw)
        assertEquals(1, events.size)
        assertEquals("clipboard", events[0].type)
        val profile = events[0].profile!!
        assertEquals("你好🙂", profile.text)
        assertEquals(10L, profile.size)
        assertEquals("device-a", profile.source)
        assertNull(profile.dataName)
        assertFalse(profile.hasData)
    }

    @Test
    fun `解析连接与警告事件`() {
        val raw = """
            {"ok":true,"events":[
              {"type":"peer","did":"aa","name":"MacBook","connected":true},
              {"type":"warning","message":"广播不可达"}
            ]}
        """.trimIndent()

        val events = P2pJson.events(raw)
        assertEquals(2, events.size)
        assertTrue(events[0].connected!!)
        assertEquals("MacBook", events[0].name)
        assertEquals("广播不可达", events[1].message)
    }

    @Test
    fun `解析失败返回空事件表`() {
        assertTrue(P2pJson.events("""{"ok":false,"error":"节点未启动"}""").isEmpty())
        assertTrue(P2pJson.events("""{"ok":true}""").isEmpty())
    }

    @Test
    fun `解析已配对设备`() {
        val raw = """
            {"ok":true,"peers":[
              {"did":"aa","name":"PC","last_addr":"192.168.1.5:47821","paired_at":100}
            ]}
        """.trimIndent()

        val peers = P2pJson.peers(raw)
        assertEquals(1, peers.size)
        assertEquals("192.168.1.5:47821", peers[0].lastAddr)
        assertEquals(100L, peers[0].pairedAt)
    }

    @Test
    fun `解析配对进度`() {
        val done = P2pJson.pairState(
            """{"ok":true,"phase":"done","peer":{"did":"bb","name":"Phone"},"port":47823}"""
        )
        assertEquals("done", done.phase)
        assertEquals("Phone", done.peer!!.name)

        val failed = P2pJson.pairState("""{"ok":true,"phase":"failed","error":"码错误"}""")
        assertEquals("failed", failed.phase)
        assertEquals("码错误", failed.error)
        assertNull(failed.peer)
    }

    @Test
    fun `解析显示端动态码`() {
        val start = P2pJson.pairStart("""{"ok":true,"code":"482719","port":47823}""")
        assertTrue(start.ok)
        assertEquals("482719", start.code)
        assertEquals(47823, start.port)
    }
}
