package com.kingzcheung.xime.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "imported_lexicons")
data class ImportedLexiconEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val dictFile: String,
    @ColumnInfo(defaultValue = "") val formatId: String = "",
    @ColumnInfo(defaultValue = "") val formatName: String = "",
    @ColumnInfo(defaultValue = "PINYIN") val codeSystem: String = "PINYIN",
    @ColumnInfo(defaultValue = "") val schemes: String = "",
    @ColumnInfo(defaultValue = "0") val entryCount: Int = 0,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "") val sourceName: String = "",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
)
