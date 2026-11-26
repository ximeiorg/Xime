package com.kingzcheung.xime.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query

/**
 * 局域网直连的已配对设备（`p2p_peers`，v8）。
 *
 * 与桌面端 `xime.db` 的同名表逐列对齐：协议库（`xime-p2p`）现在只定义
 * `PeerBook` 读接口，不再自己存文件，配对结果由客户端落库。
 *
 * 写入方是界面与输入法服务（同进程）；原生侧拿到的是一份进程内镜像，
 * 由 [com.kingzcheung.xime.p2p.P2pPeers] 在启动前灌入、配对后读回。
 */
@Entity(tableName = "p2p_peers")
data class P2pPeerEntity(
    @PrimaryKey
    val did: String,
    val name: String,
    @ColumnInfo(name = "pub_ed") val pubEd: String,
    @ColumnInfo(name = "pub_x25519") val pubX25519: String,
    @ColumnInfo(name = "last_addr") val lastAddr: String? = null,
    @ColumnInfo(name = "last_seen") val lastSeen: Long? = null,
    @ColumnInfo(name = "paired_at", defaultValue = "0") val pairedAt: Long = 0,
)

@Dao
interface P2pPeerDao {

    @Query("SELECT * FROM p2p_peers ORDER BY paired_at, did")
    suspend fun getAll(): List<P2pPeerEntity>

    @Query("SELECT COUNT(*) FROM p2p_peers")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<P2pPeerEntity>)

    @Query("DELETE FROM p2p_peers WHERE did = :did")
    suspend fun delete(did: String)
}
