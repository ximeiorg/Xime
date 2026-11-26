package com.kingzcheung.xime.p2p

import org.json.JSONArray
import org.json.JSONObject

/** `buildInfo()` 的结果：`hasP2p=false` 说明包里的 .so 是空壳。 */
data class P2pBuildInfo(
    val ok: Boolean,
    val error: String? = null,
    val hasP2p: Boolean = false,
    val protocol: String? = null
)

/** 只关心成功与否的返回值（配对启动、取消等）。 */
data class P2pResult(
    val ok: Boolean,
    val error: String? = null
)

data class P2pIdentityInfo(
    val ok: Boolean,
    val error: String? = null,
    val did: String? = null
)

data class P2pStartInfo(
    val ok: Boolean,
    val error: String? = null,
    val did: String? = null,
    val port: Int = 0
)

/** 显示端配对的返回值：动态码与配对端口（`482 719` 分组由界面做）。 */
data class P2pPairStart(
    val ok: Boolean,
    val error: String? = null,
    val code: String? = null,
    val port: Int = 0
)

/** 一台设备的实时状态：`online` 有会话、`nearby` 收到过公告、`offline` 都没有。 */
data class P2pPeerStatus(
    val did: String,
    val name: String,
    val state: String,
    val lastSeen: Long? = null,
    val addr: String? = null
)

data class P2pStatusInfo(
    val ok: Boolean,
    val error: String? = null,
    val running: Boolean = false,
    val did: String? = null,
    val port: Int = 0,
    /** 每台设备的状态；节点没跑时为空——界面上「未知」不能画成「离线」。 */
    val peers: List<P2pPeerStatus> = emptyList()
)

/** 剪贴板条目，与 Rust `Profile` 的 JSON 同构（本版只同步文本）。 */
data class P2pProfile(
    val type: String,
    val hash: String,
    val text: String,
    val hasData: Boolean,
    val dataName: String?,
    val size: Long,
    val source: String?
)

/** 事件队列里的一条：`clipboard` / `peer` / `warning`。 */
data class P2pEvent(
    val type: String,
    val message: String? = null,
    val did: String? = null,
    val name: String? = null,
    val connected: Boolean? = null,
    val profile: P2pProfile? = null
)

data class P2pPeer(
    val did: String,
    val name: String,
    val pubEd: String = "",
    val pubX25519: String = "",
    val lastAddr: String? = null,
    val lastSeen: Long? = null,
    val pairedAt: Long = 0
)

/** 配对进度：`phase` 为 idle / waiting / done / failed / cancelled。 */
data class P2pPairState(
    val ok: Boolean,
    val error: String? = null,
    val phase: String = "idle",
    val code: String? = null,
    val port: Int = 0,
    val peer: P2pPeer? = null
)

/** Rust 返回的 JSON → Kotlin 模型。纯函数，不碰原生库，便于单测。 */
internal object P2pJson {

    fun buildInfo(raw: String): P2pBuildInfo {
        val json = JSONObject(raw)
        return P2pBuildInfo(
            ok = json.optBoolean("ok", false),
            error = json.stringOrNull("error"),
            hasP2p = json.optBoolean("hasP2p", false),
            protocol = json.stringOrNull("protocol")
        )
    }

    fun identity(raw: String): P2pIdentityInfo {
        val json = JSONObject(raw)
        return P2pIdentityInfo(
            ok = json.optBoolean("ok", false),
            error = json.stringOrNull("error"),
            did = json.stringOrNull("did")
        )
    }

    fun start(raw: String): P2pStartInfo {
        val json = JSONObject(raw)
        return P2pStartInfo(
            ok = json.optBoolean("ok", false),
            error = json.stringOrNull("error"),
            did = json.stringOrNull("did"),
            port = json.optInt("port", 0)
        )
    }

    fun pairStart(raw: String): P2pPairStart {
        val json = JSONObject(raw)
        return P2pPairStart(
            ok = json.optBoolean("ok", false),
            error = json.stringOrNull("error"),
            code = json.stringOrNull("code"),
            port = json.optInt("port", 0)
        )
    }

    fun result(raw: String): P2pResult {
        val json = JSONObject(raw)
        return P2pResult(
            ok = json.optBoolean("ok", false),
            error = json.stringOrNull("error")
        )
    }

    fun status(raw: String): P2pStatusInfo {
        val json = JSONObject(raw)
        return P2pStatusInfo(
            ok = json.optBoolean("ok", false),
            error = json.stringOrNull("error"),
            running = json.optBoolean("running", false),
            did = json.stringOrNull("did"),
            port = json.optInt("port", 0),
            peers = json.optJSONArray("peers")?.let { array ->
                buildList(array.length()) {
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let { item ->
                            add(
                                P2pPeerStatus(
                                    did = item.optString("did"),
                                    name = item.optString("name"),
                                    state = item.optString("state", "offline"),
                                    lastSeen = if (item.has("last_seen") && !item.isNull("last_seen")) {
                                        item.optLong("last_seen")
                                    } else {
                                        null
                                    },
                                    addr = item.stringOrNull("addr")
                                )
                            )
                        }
                    }
                }
            } ?: emptyList()
        )
    }

    fun profile(json: JSONObject): P2pProfile = P2pProfile(
        type = json.optString("type", "text"),
        hash = json.optString("hash"),
        text = json.optString("text"),
        hasData = json.optBoolean("has_data", false),
        dataName = json.stringOrNull("data_name"),
        size = json.optLong("size", 0),
        source = json.stringOrNull("source")
    )

    fun event(json: JSONObject): P2pEvent = P2pEvent(
        type = json.optString("type"),
        message = json.stringOrNull("message"),
        did = json.stringOrNull("did"),
        name = json.stringOrNull("name"),
        connected = if (json.has("connected")) json.optBoolean("connected") else null,
        profile = json.optJSONObject("profile")?.let(::profile)
    )

    fun events(raw: String): List<P2pEvent> {
        val json = JSONObject(raw)
        if (!json.optBoolean("ok", false)) return emptyList()
        val array = json.optJSONArray("events") ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(event(it)) }
            }
        }
    }

    fun peer(json: JSONObject): P2pPeer = P2pPeer(
        did = json.optString("did"),
        name = json.optString("name"),
        pubEd = json.optString("pub_ed"),
        pubX25519 = json.optString("pub_x25519"),
        lastAddr = json.stringOrNull("last_addr"),
        lastSeen = if (json.has("last_seen") && !json.isNull("last_seen")) {
            json.optLong("last_seen")
        } else {
            null
        },
        pairedAt = json.optLong("paired_at", 0)
    )

    /** 反向：喂给 `xime_p2p_set_peers`（字段与 Rust 侧 `PairedPeer` 逐字对应）。 */
    fun peersJson(peers: List<P2pPeer>): String {
        val array = JSONArray()
        peers.forEach { peer ->
            array.put(
                JSONObject().apply {
                    put("did", peer.did)
                    put("name", peer.name)
                    put("pub_ed", peer.pubEd)
                    put("pub_x25519", peer.pubX25519)
                    peer.lastAddr?.let { put("last_addr", it) }
                    peer.lastSeen?.let { put("last_seen", it) }
                    put("paired_at", peer.pairedAt)
                }
            )
        }
        // 原生侧 set_peers 直接按 `PairedPeer[]` 反序列化，不要再包一层 {"peers": ...}。
        return array.toString()
    }

    fun peers(raw: String): List<P2pPeer> {
        val json = JSONObject(raw)
        if (!json.optBoolean("ok", false)) return emptyList()
        val array = json.optJSONArray("peers") ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(peer(it)) }
            }
        }
    }

    /**
     * v8 之前协议库自己落盘的 `files/p2p/peers.json`：`{"version":1,"peers":[...]}`。
     *
     * 只用于一次性迁移到 Room；与 [peers] 的区别是外层没有 `ok`、字段直接是设备列表。
     * 解析失败抛异常，由调用方决定是丢掉还是保留文件。
     */
    fun legacyStorePeers(raw: String): List<P2pPeer> {
        val array = JSONObject(raw).optJSONArray("peers") ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(peer(it)) }
            }
        }
    }

    fun pairState(raw: String): P2pPairState {
        val json = JSONObject(raw)
        return P2pPairState(
            ok = json.optBoolean("ok", false),
            error = json.stringOrNull("error"),
            phase = json.optString("phase", "idle"),
            code = json.stringOrNull("code"),
            port = json.optInt("port", 0),
            peer = json.optJSONObject("peer")?.let(::peer)
        )
    }
}

/**
 * 配对码的展示形式：只留数字、每 3 位一组，如 `482 719`。
 *
 * 原生 `code_text()` 返回的已经是分组后的 `482 719`，界面不能再按 3 位切一遍
 * （那会把空格算进分组，变成 `482  71 9`）；统一从这里出，输入端贴进来的
 * `482719` / `482-719` 也能显示成同一形态。
 */
internal fun formatPairCode(raw: String?): String {
    val digits = raw.orEmpty().filter(Char::isDigit)
    return if (digits.isEmpty()) "——— ———" else digits.chunked(3).joinToString(" ")
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifEmpty { null }
