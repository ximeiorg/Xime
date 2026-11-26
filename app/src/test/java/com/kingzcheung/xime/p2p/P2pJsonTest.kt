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

    @Test
    fun `状态快照带每台设备状态`() {
        val raw = """
            {"ok":true,"running":true,"did":"aa","port":47821,"peers":[
              {"did":"bb","name":"Mac","state":"online","since":100,"last_seen":120,
               "addr":"192.168.1.5:47821"},
              {"did":"cc","name":"Pad","state":"nearby","last_seen":90},
              {"did":"dd","name":"Old","state":"offline"}
            ]}
        """.trimIndent()

        val status = P2pJson.status(raw)
        assertTrue(status.running)
        assertEquals(3, status.peers.size)
        assertEquals("online", status.peers[0].state)
        assertEquals(120L, status.peers[0].lastSeen)
        assertEquals("192.168.1.5:47821", status.peers[0].addr)
        assertEquals("nearby", status.peers[1].state)
        assertEquals("offline", status.peers[2].state)
        assertNull(status.peers[2].lastSeen)

        // 节点没跑时没有 peers：界面据此显示「状态未知」，不能当成离线
        val stopped = P2pJson.status("""{"ok":true,"running":false}""")
        assertFalse(stopped.running)
        assertTrue(stopped.peers.isEmpty())
    }

    /**
     * 反向序列化喂给 `xime_p2p_set_peers`：Rust 侧直接按 `PairedPeer[]` 反序列化，
     * 包一层 `{"peers":...}` 会让原生解析失败、节点拒绝启动。
     */    @Test
    fun `设备表序列化为裸数组`() {
        val json = P2pJson.peersJson(
            listOf(
                P2pPeer(
                    did = "bb",
                    name = "Mac",
                    pubEd = "ed",
                    pubX25519 = "x",
                    lastAddr = "192.168.1.5:47821",
                    lastSeen = 120,
                    pairedAt = 100
                )
            )
        )
        val array = org.json.JSONArray(json)
        assertEquals(1, array.length())
        val item = array.getJSONObject(0)
        assertEquals("bb", item.getString("did"))
        assertEquals("ed", item.getString("pub_ed"))
        assertEquals("x", item.getString("pub_x25519"))
        assertEquals("192.168.1.5:47821", item.getString("last_addr"))
        assertEquals(120L, item.getLong("last_seen"))
        assertEquals(100L, item.getLong("paired_at"))
    }

    /** v8 之前的 `files/p2p/peers.json`：外层没有 `ok`，直接是 `{version, peers[]}`。 */
    @Test
    fun `解析旧版设备表`() {
        val raw = """
            {"version":1,"peers":[
              {"did":"aa","name":"Mac","pub_ed":"ed","pub_x25519":"x",
               "last_addr":"192.168.1.5:47821","paired_at":100}
            ]}
        """.trimIndent()

        val peers = P2pJson.legacyStorePeers(raw)
        assertEquals(1, peers.size)
        assertEquals("aa", peers[0].did)
        assertEquals("Mac", peers[0].name)
        assertEquals("ed", peers[0].pubEd)
        assertEquals("x", peers[0].pubX25519)
        assertEquals(100L, peers[0].pairedAt)

        // 空表与缺字段都不能炸：迁移时按公钥是否齐全再过滤一次
        assertTrue(P2pJson.legacyStorePeers("""{"version":1,"peers":[]}""").isEmpty())
        assertTrue(P2pJson.legacyStorePeers("""{"version":1}""").isEmpty())
        val partial = P2pJson.legacyStorePeers("""{"peers":[{"did":"bb"}]}""")
        assertEquals(1, partial.size)
        assertEquals("", partial[0].pubEd)
    }
}
