package com.kingzcheung.xime.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {

    @Query("SELECT * FROM clipboard_entries WHERE isQuickSend = 0 ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ClipboardEntry>>

    @Query("SELECT * FROM clipboard_entries WHERE isQuickSend = 1 ORDER BY timestamp DESC")
    fun observeQuickSend(): Flow<List<ClipboardEntry>>

    @Query("SELECT * FROM clipboard_entries WHERE text = :text AND isQuickSend = 0 LIMIT 1")
    suspend fun findByText(text: String): ClipboardEntry?

    @Query("SELECT * FROM clipboard_entries WHERE text = :text AND isQuickSend = 1 LIMIT 1")
    suspend fun findQuickSendByText(text: String): ClipboardEntry?

    /** 图片条目按内容 hash 去重（仅剪贴板，不含快捷发送）。 */
    @Query("SELECT * FROM clipboard_entries WHERE imageHash = :hash AND isQuickSend = 0 LIMIT 1")
    suspend fun findImageByHash(hash: String): ClipboardEntry?

    /** 删除前取条目（图片条目需据此回收磁盘文件）。 */
    @Query("SELECT * FROM clipboard_entries WHERE id IN (:ids)")
    suspend fun findByIds(ids: List<Long>): List<ClipboardEntry>

    /** 全部图片条目（含置顶），用于清空/孤儿清理/配额统计。 */
    @Query("SELECT * FROM clipboard_entries WHERE isQuickSend = 0 AND type = 'image'")
    suspend fun allImages(): List<ClipboardEntry>

    /** 图片条目总数（配额上限判定）。 */
    @Query("SELECT COUNT(*) FROM clipboard_entries WHERE isQuickSend = 0 AND type = 'image'")
    suspend fun countImages(): Int

    /** 图片占用总字节（配额上限判定）。 */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM clipboard_entries WHERE isQuickSend = 0 AND type = 'image'")
    suspend fun totalImageBytes(): Long

    /** 尚未消费的图片路径集合（孤儿清理时保护"刚复制还没用"的文件）。 */
    @Query("SELECT imagePath FROM clipboard_entries WHERE type = 'image' AND imagePath <> ''")
    suspend fun allImagePaths(): List<String>

    @Query("SELECT * FROM clipboard_entries WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ClipboardEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ClipboardEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<ClipboardEntry>)

    @Query("UPDATE clipboard_entries SET timestamp = :timestamp WHERE id = :id")
    suspend fun updateTimestamp(id: Long, timestamp: Long)

    @Query("DELETE FROM clipboard_entries WHERE isQuickSend = 0 AND id = :id")
    suspend fun deleteClipboardById(id: Long)

    @Query("DELETE FROM clipboard_entries WHERE isQuickSend = 1 AND id = :id")
    suspend fun deleteQuickSendById(id: Long)

    @Query("DELETE FROM clipboard_entries WHERE isQuickSend = 0 AND id IN (:ids)")
    suspend fun deleteClipboardByIds(ids: List<Long>)

    @Query("DELETE FROM clipboard_entries WHERE isQuickSend = 0")
    suspend fun clearAllClipboard()

    @Query("DELETE FROM clipboard_entries WHERE isPinned = 0")
    suspend fun clearUnpinned()

    @Query("SELECT COUNT(*) FROM clipboard_entries WHERE isPinned = 0")
    suspend fun countUnpinned(): Int

    @Query("DELETE FROM clipboard_entries WHERE isPinned = 0 AND id IN (SELECT id FROM clipboard_entries WHERE isPinned = 0 ORDER BY timestamp ASC LIMIT :limit)")
    suspend fun trimUnpinned(limit: Int)

    /** 淘汰最旧的非置顶图片条目（置顶图片永不自动淘汰）。 */
    @Query(
        "DELETE FROM clipboard_entries WHERE type = 'image' AND isQuickSend = 0 AND isPinned = 0 " +
            "AND id IN (SELECT id FROM clipboard_entries WHERE type = 'image' AND isQuickSend = 0 " +
            "AND isPinned = 0 ORDER BY timestamp ASC LIMIT :limit)"
    )
    suspend fun trimImages(limit: Int)

    /** 淘汰指定图片条目（配额超限时按 [ClipboardManager] 计算出的候选逐个删除）。 */
    @Query("DELETE FROM clipboard_entries WHERE isQuickSend = 0 AND id IN (:ids)")
    suspend fun deleteImageByIds(ids: List<Long>)

    @Query("DELETE FROM clipboard_entries WHERE isQuickSend = 1 AND id IN (SELECT id FROM clipboard_entries WHERE isQuickSend = 1 ORDER BY timestamp ASC LIMIT :limit)")
    suspend fun trimQuickSend(limit: Int)

    @Query("UPDATE clipboard_entries SET text = :text, timestamp = :now WHERE id = :id")
    suspend fun updateText(id: Long, text: String, now: Long)

    @Query("UPDATE clipboard_entries SET text = :text, code = :code, timestamp = :now WHERE id = :id")
    suspend fun updateQuickSendItem(id: Long, text: String, code: String, now: Long)

    @Query("UPDATE clipboard_entries SET consumed = 1 WHERE id = :id")
    suspend fun markConsumed(id: Long)

    @Query("DELETE FROM clipboard_entries")
    suspend fun deleteAll()

    @Transaction
    suspend fun upsertAndTrim(text: String, now: Long, maxItems: Int) {
        val existing = findByText(text)
        if (existing != null) {
            updateTimestamp(existing.id, now)
        } else {
            insert(ClipboardEntry(text = text, timestamp = now))
            val unpinned = countUnpinned()
            if (unpinned > maxItems) {
                trimUnpinned(unpinned - maxItems)
            }
        }
    }

    /**
     * 图片条目写入（按 [ClipboardEntry.imageHash] 去重）并按数量上限淘汰。
     * maxImages > 0 时仅在超限时删除最旧的**非置顶**图片条目；
     * 被删条目的磁盘文件由调用方（ClipboardManager）回收。
     */
    @Transaction
    suspend fun upsertImageAndTrim(entry: ClipboardEntry, maxImages: Int) {
        val existing = findImageByHash(entry.imageHash)
        if (existing != null) {
            updateTimestamp(existing.id, entry.timestamp)
        } else {
            insert(entry)
            if (maxImages > 0) {
                val count = countImages()
                if (count > maxImages) {
                    trimImages(count - maxImages)
                }
            }
        }
    }

    @Transaction
    suspend fun addQuickSend(sourceId: Long, now: Long, maxQuickSend: Int) {
        val source = findById(sourceId) ?: return
        val existing = findQuickSendByText(source.text)
        if (existing != null) {
            updateTimestamp(existing.id, now)
        } else {
            insert(
                ClipboardEntry(
                    text = source.text,
                    timestamp = now,
                    isPinned = true,
                    isQuickSend = true
                )
            )
        }
        val count = countQuickSend()
        if (count > maxQuickSend) {
            trimQuickSend(count - maxQuickSend)
        }
    }

    @Query("SELECT COUNT(*) FROM clipboard_entries WHERE isQuickSend = 1")
    suspend fun countQuickSend(): Int

    @Transaction
    suspend fun insertQuickSend(text: String, code: String, now: Long, maxQuickSend: Int) {
        val existing = findQuickSendByText(text)
        if (existing != null) {
            updateQuickSendItem(existing.id, text, code, now)
        } else {
            insert(
                ClipboardEntry(
                    text = text,
                    code = code,
                    timestamp = now,
                    isPinned = true,
                    isQuickSend = true
                )
            )
        }
        val count = countQuickSend()
        if (count > maxQuickSend) {
            trimQuickSend(count - maxQuickSend)
        }
    }
}
