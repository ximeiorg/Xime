package com.kingzcheung.xime.data

import android.content.Context
import com.kingzcheung.xime.data.db.InstalledItemEntity
import com.kingzcheung.xime.data.db.XimeDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 方案包 / 方案 / 模型的安装元数据（名称、版本）。
 *
 * 从两处旧存储迁到 Room `installed_items`：
 * - `kime_settings/scheme_package_info`（JSON 串，包 id → 名称/版本）；
 * - `market_installed_versions` prefs（`scheme:` / `model:` 前缀的版本键）。
 *
 * 读多写少，进程内做一层缓存：首次访问从 DB 同步载入（并顺带迁移旧 prefs），
 * 之后写入立即更新缓存、后台串行落库，保持原有同步 API 不变。
 */
object InstalledItemStore {

    const val KIND_SCHEME_PACKAGE = "scheme_package"
    const val KIND_SCHEME = "scheme"
    const val KIND_MODEL = "model"

    private const val PREFS_SETTINGS = "kime_settings"
    private const val KEY_PACKAGE_INFO = "scheme_package_info"
    private const val PREFS_MARKET_VERSIONS = "market_installed_versions"
    private const val PREFIX_SCHEME = "scheme:"
    private const val PREFIX_MODEL = "model:"

    private val lock = Any()
    private val cache = HashMap<String, MutableMap<String, InstalledItemEntity>>()

    @Volatile
    private var loaded = false

    private val writeScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )

    /** 提前在 IO 线程预热，避免首次访问时在调用线程同步读库。 */
    fun warmUp(context: Context) {
        ensureLoaded(context.applicationContext)
    }

    fun get(context: Context, kind: String, id: String): InstalledItemEntity? {
        ensureLoaded(context)
        synchronized(lock) { return cache[kind]?.get(id) }
    }

    fun all(context: Context, kind: String): Map<String, InstalledItemEntity> {
        ensureLoaded(context)
        synchronized(lock) { return cache[kind]?.toMap() ?: emptyMap() }
    }

    fun put(context: Context, entity: InstalledItemEntity) {
        ensureLoaded(context)
        synchronized(lock) {
            cache.getOrPut(entity.kind) { HashMap() }[entity.id] = entity
        }
        writeScope.launch {
            runCatching { dao(context).upsert(entity) }
        }
    }

    fun remove(context: Context, kind: String, id: String) {
        ensureLoaded(context)
        synchronized(lock) { cache[kind]?.remove(id) }
        writeScope.launch {
            runCatching { dao(context).delete(kind, id) }
        }
    }

    /** 备份恢复/清空数据后丢弃缓存，下次访问重新从 DB 读。 */
    fun invalidate() {
        synchronized(lock) {
            cache.clear()
            loaded = false
        }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val rows = runBlocking(Dispatchers.IO) { dao(context).getAll() }
            rows.forEach { row ->
                cache.getOrPut(row.kind) { HashMap() }[row.id] = row
            }
            loaded = true
            migrateLegacyPrefs(context)
        }
    }

    /** 旧 prefs → 表；只补缺失项，迁完删掉旧键，避免双写。 */
    private fun migrateLegacyPrefs(context: Context) {
        val migrated = ArrayList<InstalledItemEntity>()

        val settingsPrefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val packageInfo = settingsPrefs.getString(KEY_PACKAGE_INFO, null)
        if (packageInfo != null) {
            runCatching {
                val json = JSONObject(packageInfo)
                json.keys().forEach { id ->
                    val entry = json.optJSONObject(id) ?: return@forEach
                    val name = entry.optString("n").orEmpty()
                    val version = entry.optString("v").orEmpty()
                    val record = InstalledItemEntity(KIND_SCHEME_PACKAGE, id, name, version)
                    if (cache[KIND_SCHEME_PACKAGE]?.get(id) == null) {
                        cache.getOrPut(KIND_SCHEME_PACKAGE) { HashMap() }[id] = record
                        migrated += record
                    }
                }
            }
            settingsPrefs.edit().remove(KEY_PACKAGE_INFO).apply()
        }

        val versionPrefs = context.getSharedPreferences(PREFS_MARKET_VERSIONS, Context.MODE_PRIVATE)
        val editor = versionPrefs.edit()
        versionPrefs.all.forEach { (key, value) ->
            val kind = when {
                key.startsWith(PREFIX_SCHEME) -> KIND_SCHEME
                key.startsWith(PREFIX_MODEL) -> KIND_MODEL
                else -> return@forEach
            }
            val id = key.removePrefix(if (kind == KIND_SCHEME) PREFIX_SCHEME else PREFIX_MODEL)
            val version = value as? String ?: return@forEach
            editor.remove(key)
            if (version.isBlank() || cache[kind]?.get(id) != null) return@forEach
            val record = InstalledItemEntity(kind, id, version = version)
            cache.getOrPut(kind) { HashMap() }[id] = record
            migrated += record
        }
        editor.apply()

        if (migrated.isNotEmpty()) {
            runBlocking(Dispatchers.IO) { dao(context).upsertAll(migrated) }
        }
    }

    private fun dao(context: Context) =
        XimeDatabase.getInstance(context).installedItemDao()
}
