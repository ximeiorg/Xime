package com.kingzcheung.xime.data.db

import androidx.room3.Entity

/**
 * 用户输入的 n 元组计数（联想引擎的本地学习结果）。
 *
 * 用 [kind]（2=bigram，3=trigram）+ 三个 token 做联合主键；[t3] 对 bigram 为空串。
 * 之前整份 `user_ngram_cache.json` 覆盖写，进程被杀/写一半都可能丢，改为逐条计数。
 */
@Entity(tableName = "user_ngrams", primaryKeys = ["kind", "t1", "t2", "t3"])
data class UserNgramEntity(
    val kind: Int,
    val t1: String,
    val t2: String,
    val t3: String,
    val count: Int,
)
