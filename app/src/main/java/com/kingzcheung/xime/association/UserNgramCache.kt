package com.kingzcheung.xime.association

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.data.db.UserNgramEntity
import com.kingzcheung.xime.data.db.UserNgramDao
import com.kingzcheung.xime.data.db.XimeDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 用户输入的 n 元组统计（联想/纠错的上文本地学习结果）。
 *
 * 内存里保留 trie 负责打分；持久化从「整份 `user_ngram_cache.json` 覆盖写」改为
 * Room `user_ngrams` 表逐条计数：
 * - 每次输入只把 n 元组增量放进待写队列，1.5s/退出时批量落库，不阻塞输入；
 * - 进程被杀也只丢最后不到一个批次的增量，不会像整文件重写那样把旧数据写坏；
 * - 旧 JSON 在首次启动时一次性导入，然后删除。
 */
class UserNgramCache(private val context: Context) {

    private companion object {
        const val TAG = "UserNgramCache"
        const val LEGACY_FILE_NAME = "user_ngram_cache.json"
        const val FLUSH_DELAY_MS = 1_500L
    }

    private val bigramTrie = NgramTrie()
    private val trigramTrie = NgramTrie()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)
    private val flushMutex = Mutex()

    private val pendingLock = Any()
    private val pending = LinkedHashMap<NgramKey, Int>()
    private var writerStarted = false

    private val dao: UserNgramDao
        get() = XimeDatabase.getInstance(context).userNgramDao()

    private data class NgramKey(val kind: Int, val t1: String, val t2: String, val t3: String)

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            var rows = dao.getAll()
            if (rows.isEmpty()) {
                rows = importLegacyJson()
            } else {
                // 已迁移过：遗留文件不再需要（内容已在 DB 里）。
                File(context.filesDir, LEGACY_FILE_NAME).delete()
            }
            load(rows)
            ensureWriter()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize cache", e)
            false
        }
    }

    fun recordInput(text: String) {
        if (text.isBlank()) return
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return

        tokens.windowed(2).forEach { window ->
            bigramTrie.insert(window)
            enqueue(2, window)
        }
        tokens.windowed(3).forEach { window ->
            trigramTrie.insert(window)
            enqueue(3, window)
        }
    }

    private fun tokenize(text: String): List<String> {
        val punctuation = setOf('，', '。', '！', '？', '、', '；', '：', '"', '"', '\'', '\'', ' ', '\n', '\t')
        return text.filter { it.isLetterOrDigit() || it in punctuation }
            .map { it.toString() }
    }

    fun getBigramScore(word1: String, word2: String): Float =
        bigramTrie.getFrequency(listOf(word1, word2))

    fun getTrigramScore(word1: String, word2: String, word3: String): Float =
        trigramTrie.getFrequency(listOf(word1, word2, word3))

    fun getContextualScore(context: String, candidate: String): Float {
        val tokens = tokenize(context)
        if (tokens.isEmpty()) return 0f

        val lastToken = tokens.last()
        val bigramScore = getBigramScore(lastToken, candidate)
        val trigramScore = if (tokens.size >= 2) {
            getTrigramScore(tokens[tokens.size - 2], lastToken, candidate)
        } else {
            0f
        }
        return maxOf(bigramScore, trigramScore)
    }

    fun getUserCandidates(context: String, topK: Int = 5): List<Pair<String, Float>> {
        val tokens = tokenize(context)
        if (tokens.isEmpty()) return emptyList()

        val lastToken = tokens.last()
        val candidates = mutableListOf<Pair<String, Float>>()

        bigramTrie.getAllEntries().forEach { (ngram, _) ->
            if (ngram.size == 2 && ngram[0] == lastToken) {
                val score = getBigramScore(ngram[0], ngram[1])
                if (score > 0) candidates.add(ngram[1] to score)
            }
        }

        if (tokens.size >= 2) {
            trigramTrie.getAllEntries().forEach { (ngram, _) ->
                if (ngram.size == 3 && ngram[0] == tokens[tokens.size - 2] && ngram[1] == lastToken) {
                    val score = getTrigramScore(ngram[0], ngram[1], ngram[2])
                    if (score > 0) candidates.add(ngram[2] to score)
                }
            }
        }

        return candidates.sortedByDescending { it.second }.take(topK)
    }

    /** 立即把待写增量落库（设置页「保存用户数据」、清空前调用）。 */
    suspend fun save() = withContext(Dispatchers.IO) {
        ensureWriter()
        flushMutex.withLock { flushPending() }
    }

    fun clear() {
        bigramTrie.clear()
        trigramTrie.clear()
        synchronized(pendingLock) { pending.clear() }
        scope.launch {
            runCatching { dao.clear() }
            File(context.filesDir, LEGACY_FILE_NAME).delete()
        }
    }

    fun getCacheSize(): Int = bigramTrie.size() + trigramTrie.size()

    /* ───────────── 持久化 ───────────── */

    private fun enqueue(kind: Int, tokens: List<String>) {
        val key = if (kind == 3) {
            NgramKey(3, tokens[0], tokens[1], tokens[2])
        } else {
            NgramKey(2, tokens[0], tokens[1], "")
        }
        synchronized(pendingLock) {
            pending[key] = (pending[key] ?: 0) + 1
        }
        ensureWriter()
        flushSignal.trySend(Unit)
    }

    /** 单写协程：收到信号后合并一段时间内的增量再落库，避免每次输入都碰 DB。 */
    private fun ensureWriter() {
        synchronized(pendingLock) {
            if (writerStarted) return
            writerStarted = true
        }
        scope.launch {
            for (signal in flushSignal) {
                delay(FLUSH_DELAY_MS)
                runCatching { flushMutex.withLock { flushPending() } }
                    .onFailure { Log.e(TAG, "flush pending ngrams failed", it) }
            }
        }
    }

    private suspend fun flushPending() {
        val batch = synchronized(pendingLock) {
            if (pending.isEmpty()) return
            val copy = pending.toList()
            pending.clear()
            copy
        }
        for ((key, delta) in batch) {
            val updated = dao.addCount(key.kind, key.t1, key.t2, key.t3, delta)
            if (updated == 0) {
                dao.upsert(UserNgramEntity(key.kind, key.t1, key.t2, key.t3, delta))
            }
        }
    }

    private fun load(rows: List<UserNgramEntity>) {
        bigramTrie.clear()
        trigramTrie.clear()
        for (row in rows) {
            when (row.kind) {
                3 -> trigramTrie.insert(listOf(row.t1, row.t2, row.t3), row.count)
                2 -> bigramTrie.insert(listOf(row.t1, row.t2), row.count)
            }
        }
    }

    /** 旧 `user_ngram_cache.json` 一次性导入；成功后删除文件。 */
    private suspend fun importLegacyJson(): List<UserNgramEntity> {
        val file = File(context.filesDir, LEGACY_FILE_NAME)
        if (!file.isFile) return emptyList()
        val rows = ArrayList<UserNgramEntity>()
        val parsed = runCatching {
            val json = JSONObject(file.readText())
            readNgrams(json.optJSONArray("bigrams"), 2, rows)
            readNgrams(json.optJSONArray("trigrams"), 3, rows)
        }.isSuccess
        if (!parsed) {
            // 坏文件不再反复尝试（旧数据本来也只是缓存）。
            file.renameTo(File(file.parentFile, "$LEGACY_FILE_NAME.broken"))
            return emptyList()
        }
        if (rows.isEmpty()) {
            file.delete()
            return emptyList()
        }
        dao.upsertAll(rows)
        file.delete()
        Log.i(TAG, "migrated ${rows.size} ngrams from legacy json")
        return rows
    }

    private fun readNgrams(array: JSONArray?, kind: Int, out: MutableList<UserNgramEntity>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val tokens = entry.optJSONArray("tokens") ?: continue
            if (tokens.length() != kind) continue
            val values = (0 until kind).map { tokens.optString(it) }
            if (values.any { it.isEmpty() }) continue
            val count = entry.optInt("count", 0)
            if (count <= 0) continue
            out += UserNgramEntity(
                kind = kind,
                t1 = values[0],
                t2 = values[1],
                t3 = values.getOrElse(2) { "" },
                count = count,
            )
        }
    }
}
