package com.kingzcheung.xime.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface UserNgramDao {

    @Query("SELECT * FROM user_ngrams")
    suspend fun getAll(): List<UserNgramEntity>

    /** 计数 +delta；返回受影响行数（0 表示该 n 元组还不存在）。 */
    @Query(
        "UPDATE user_ngrams SET count = count + :delta " +
            "WHERE kind = :kind AND t1 = :t1 AND t2 = :t2 AND t3 = :t3"
    )
    suspend fun addCount(kind: Int, t1: String, t2: String, t3: String, delta: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserNgramEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<UserNgramEntity>)

    @Query("DELETE FROM user_ngrams")
    suspend fun clear()
}
