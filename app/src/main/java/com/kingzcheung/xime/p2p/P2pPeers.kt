package com.kingzcheung.xime.p2p

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.data.db.P2pPeerEntity
import com.kingzcheung.xime.data.db.XimeDatabase
import kotlinx.coroutines.runBlocking
import java.io.File

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
 *
 * **本类的方法都会阻塞（Room 查询 + 文件/JSON），禁止在主线程调用。**
 */
object P2pPeers {

    private const val TAG = "P2pPeers"

    /** v8 之前的设备表文件名（协议库自己写的），迁移后改名收尾。 */
    private const val LEGACY_FILE = "peers.json"

    fun list(context: Context): List<P2pPeer> = runBlocking {
        migrateLegacyIfNeeded(context)
        XimeDatabase.getInstance(context).p2pPeerDao().getAll().map { it.toModel() }
    }

    fun count(context: Context): Int = runBlocking {
        XimeDatabase.getInstance(context).p2pPeerDao().count()
    }

    /**
     * 一次性迁移：v8 之前设备表是 `files/p2p/peers.json`（协议库自己落盘），
     * 升级后只认 Room，不迁移等于让用户所有已配对设备默默消失。
     *
     * 解析失败时保留原文件（用户还能手工救），成功后改名为 `.migrated`，
     * 避免每次启动重复导入。
     */
    private suspend fun migrateLegacyIfNeeded(context: Context) {
        val dir = File(P2pBridge.dir(context))
        val legacy = File(dir, LEGACY_FILE)
        if (!legacy.isFile) return
        val peers = runCatching { P2pJson.legacyStorePeers(legacy.readText()) }
            .onFailure { Log.w(TAG, "旧设备表解析失败，保留原文件：${it.message}") }
            .getOrNull() ?: return
        // 没有公钥的记录握手时必然失败，迁进来只会在界面上多出一个连不上的幽灵设备。
        val usable = peers.filter { it.pubEd.isNotEmpty() && it.pubX25519.isNotEmpty() }
        if (usable.size < peers.size) {
            Log.w(TAG, "旧设备表有 ${peers.size - usable.size} 条记录缺少公钥，已跳过")
        }
        if (usable.isNotEmpty()) {
            XimeDatabase.getInstance(context).p2pPeerDao().upsertAll(usable.map { it.toEntity() })
            Log.d(TAG, "旧设备表已迁入 Room：${usable.size} 台")
        }
        val target = File(dir, "$LEGACY_FILE.migrated")
        val renamed = runCatching {
            if (target.exists()) target.delete()
            legacy.renameTo(target)
        }.getOrDefault(false)
        if (!renamed) Log.w(TAG, "旧设备表改名失败，下次启动会重复导入（upsert 幂等，不会重复设备）")
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
