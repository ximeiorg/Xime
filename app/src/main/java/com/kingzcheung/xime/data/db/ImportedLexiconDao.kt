package com.kingzcheung.xime.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface ImportedLexiconDao {

    @Query("SELECT * FROM imported_lexicons ORDER BY createdAt DESC")
    suspend fun getAll(): List<ImportedLexiconEntity>

    @Query("SELECT * FROM imported_lexicons WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ImportedLexiconEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ImportedLexiconEntity)

    @Query("DELETE FROM imported_lexicons WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE imported_lexicons SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}
