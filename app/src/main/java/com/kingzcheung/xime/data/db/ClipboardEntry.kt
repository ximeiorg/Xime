package com.kingzcheung.xime.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "clipboard_entries",
    indices = [
        Index(value = ["text"]),
        Index(value = ["imageHash"]),
    ]
)
data class ClipboardEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    /** 快捷发送触发编码（如 dh）：用户输入编码前缀命中后，对应快捷条目进入候选栏。 */
    @ColumnInfo(defaultValue = "") val code: String = "",
    @ColumnInfo(defaultValue = "0") val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val isPinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isQuickSend: Boolean = false,
    @ColumnInfo(defaultValue = "0") val consumed: Boolean = false,
    /** 条目类型：[TYPE_TEXT] 文本 / [TYPE_IMAGE] 图片（图片条目的 text 为空串）。 */
    @ColumnInfo(defaultValue = TYPE_TEXT) val type: String = TYPE_TEXT,
    /** 图片文件在 `files/` 下的相对路径（如 `clipboard_images/<sha256>.png`）；非图片为空串。 */
    @ColumnInfo(defaultValue = "") val imagePath: String = "",
    /** 图片内容 SHA-256（图片去重键）；非图片为空串。 */
    @ColumnInfo(defaultValue = "") val imageHash: String = "",
    /** 图片 MIME（如 image/png）；非图片为空串。 */
    @ColumnInfo(defaultValue = "") val mimeType: String = "",
    /** 图片字节大小（配额统计与淘汰用）。 */
    @ColumnInfo(defaultValue = "0") val sizeBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val width: Int = 0,
    @ColumnInfo(defaultValue = "0") val height: Int = 0,
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
    }
}