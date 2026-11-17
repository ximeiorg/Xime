package com.kingzcheung.xime.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface InstalledItemDao {

    @Query("SELECT * FROM installed_items WHERE kind = :kind")
    suspend fun getByKind(kind: String): List<InstalledItemEntity>

    @Query("SELECT * FROM installed_items")
    suspend fun getAll(): List<InstalledItemEntity>

    @Query("SELECT * FROM installed_items WHERE kind = :kind AND id = :id LIMIT 1")
    suspend fun get(kind: String, id: String): InstalledItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: InstalledItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<InstalledItemEntity>)

    @Query("DELETE FROM installed_items WHERE kind = :kind AND id = :id")
    suspend fun delete(kind: String, id: String)
}
