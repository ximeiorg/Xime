package com.kingzcheung.xime.p2p

import android.content.Context
import com.kingzcheung.xime.data.db.P2pPeerEntity
import com.kingzcheung.xime.data.db.XimeDatabase
import kotlinx.coroutines.runBlocking

/**
 * 已配对设备的存储与「原生镜像」之间的同步。
 *
 * 协议库 `xime-p2p` 现在只定义 `PeerBook` 读接口，不再自己写文件；
 * 设备表归客户端，落在 Room 的 `p2p_peers`（与桌面端 `xime.db` 同名同列）。
 * 原生侧只持一份进程内镜像：
 *
 * - 启动节点前用 [pushToNative] 灌进去；
 * - 配对完成后用 [syncFromNative] 读回（它带公钥，界面拿不到）；
 * - 删除设备两边一起删：[remove]。
 */
object P2pPeers {

    fun list(context: Context): List<P2pPeer> = runBlocking {
        // Room 只允许在 IO 上跑；这里同步取，调用方都在后台线程或很轻的 UI 请求。
        XimeDatabase.getInstance(context).p2pPeerDao().getAll().map { it.toModel() }
    }

    fun count(context: Context): Int = runBlocking {
        XimeDatabase.getInstance(context).p2pPeerDao().count()
    }

    /** 把 Room 里的设备表灌进原生镜像。节点启动前必须调，否则原生侧认为没有设备。 */
    fun pushToNative(context: Context): Boolean {
        if (!P2pNative.loaded) return false
        val result = P2pBridge.setPeers(P2pJson.peersJson(list(context)))
        return result.ok
    }

    /** 配对完成后调用：把原生镜像里的完整记录（含公钥）读回写库。 */
    fun syncFromNative(context: Context): List<P2pPeer> {
        if (!P2pNative.loaded) return list(context)
        val peers = P2pBridge.peers()
        if (peers.isNotEmpty()) {
            runBlocking {
                XimeDatabase.getInstance(context).p2pPeerDao().upsertAll(peers.map { it.toEntity() })
            }
        }
        return peers
    }

    /** 解除关联：Room 与原生镜像一起删，避免重启后又「复活」。 */
    fun remove(context: Context, did: String) {
        runBlocking {
            XimeDatabase.getInstance(context).p2pPeerDao().delete(did)
        }
        if (P2pNative.loaded) {
            P2pBridge.forget(did)
        }
    }

    private fun P2pPeerEntity.toModel() =
        P2pPeer(
            did = did,
            name = name,
            pubEd = pubEd,
            pubX25519 = pubX25519,
            lastAddr = lastAddr,
            lastSeen = lastSeen,
            pairedAt = pairedAt,
        )

    private fun P2pPeer.toEntity() =
        P2pPeerEntity(
            did = did,
            name = name,
            pubEd = pubEd,
            pubX25519 = pubX25519,
            lastAddr = lastAddr,
            lastSeen = lastSeen,
            pairedAt = pairedAt,
        )
}
