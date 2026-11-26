package com.kingzcheung.xime.data.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

@Database(
    entities = [
        ClipboardEntry::class,
        ImportedLexiconEntity::class,
        UserNgramEntity::class,
        InstalledItemEntity::class,
        P2pPeerEntity::class,
    ],
    version = 8,
    exportSchema = false
)
abstract class XimeDatabase : RoomDatabase() {
    abstract fun clipboardDao(): ClipboardDao
    abstract fun importedLexiconDao(): ImportedLexiconDao
    abstract fun userNgramDao(): UserNgramDao
    abstract fun installedItemDao(): InstalledItemDao
    abstract fun p2pPeerDao(): P2pPeerDao

    companion object {
        private const val DATABASE_NAME = "xime.db"
        private const val LEGACY_DATABASE_NAME = "clipboard.db"

        /** v1 → v2：新增 consumed 列（候选栏"已消费"剪贴板项过滤）。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE clipboard_entries ADD COLUMN consumed INTEGER NOT NULL DEFAULT 0"
                ).step()
            }
        }

        /** v2 → v3：新增 code 列（快捷发送触发编码）。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    "ALTER TABLE clipboard_entries ADD COLUMN code TEXT NOT NULL DEFAULT ''"
                ).step()
            }
        }

        /**
         * v3 → v4：图片剪贴板支持——新增类型/图片元数据列与 imageHash 索引。
         * 既有行按 DEFAULT 落为文本条目，行为不变。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                listOf(
                    "ALTER TABLE clipboard_entries ADD COLUMN type TEXT NOT NULL DEFAULT 'text'",
                    "ALTER TABLE clipboard_entries ADD COLUMN imagePath TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE clipboard_entries ADD COLUMN imageHash TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE clipboard_entries ADD COLUMN mimeType TEXT NOT NULL DEFAULT ''",
                    "ALTER TABLE clipboard_entries ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE clipboard_entries ADD COLUMN width INTEGER NOT NULL DEFAULT 0",
                    "ALTER TABLE clipboard_entries ADD COLUMN height INTEGER NOT NULL DEFAULT 0",
                ).forEach { connection.prepare(it).step() }
                // 实体声明了 imageHash 索引：Room 会校验期望 schema，缺失索引会抛
                // "Migration didn't properly handle"，必须显式创建。
                connection.prepare(
                    "CREATE INDEX IF NOT EXISTS index_clipboard_entries_imageHash " +
                        "ON clipboard_entries (imageHash)"
                ).step()
            }
        }

        /** v4 → v5：导入词库元数据（原来在 files/imported_dicts/manifest.json）。 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    """
                    CREATE TABLE IF NOT EXISTS imported_lexicons (
                        id TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        dictFile TEXT NOT NULL,
                        formatId TEXT NOT NULL DEFAULT '',
                        formatName TEXT NOT NULL DEFAULT '',
                        codeSystem TEXT NOT NULL DEFAULT 'PINYIN',
                        schemes TEXT NOT NULL DEFAULT '',
                        entryCount INTEGER NOT NULL DEFAULT 0,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        sourceName TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                ).step()
            }
        }

        /** v5 → v6：用户 n 元组计数（原来整份写 files/user_ngram_cache.json）。 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    """
                    CREATE TABLE IF NOT EXISTS user_ngrams (
                        kind INTEGER NOT NULL,
                        t1 TEXT NOT NULL,
                        t2 TEXT NOT NULL,
                        t3 TEXT NOT NULL DEFAULT '',
                        count INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(kind, t1, t2, t3)
                    )
                    """.trimIndent()
                ).step()
            }
        }

        /** v6 → v7：方案包/方案/模型安装元数据（原 prefs JSON）。 */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    """
                    CREATE TABLE IF NOT EXISTS installed_items (
                        kind TEXT NOT NULL,
                        id TEXT NOT NULL,
                        name TEXT NOT NULL DEFAULT '',
                        version TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY(kind, id)
                    )
                    """.trimIndent()
                ).step()
            }
        }

        /** v7 → v8：局域网直连的已配对设备（与桌面端 `xime.db` 同名同列）。 */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.prepare(
                    """
                    CREATE TABLE IF NOT EXISTS p2p_peers (
                        did TEXT NOT NULL,
                        name TEXT NOT NULL,
                        pub_ed TEXT NOT NULL,
                        pub_x25519 TEXT NOT NULL,
                        last_addr TEXT,
                        last_seen INTEGER,
                        paired_at INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(did)
                    )
                    """.trimIndent()
                ).step()
            }
        }

        @Volatile
        private var instance: XimeDatabase? = null

        fun getInstance(context: Context): XimeDatabase {
            return instance ?: synchronized(this) {
                instance ?: run {
                    migrateDatabaseFile(context)
                    Room.databaseBuilder<XimeDatabase>(
                        context.applicationContext,
                        DATABASE_NAME
                    )
                        .setDriver(AndroidSQLiteDriver())
                        .setQueryCoroutineContext(Dispatchers.IO)
                        .addMigrations(
                            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                            MIGRATION_6_7, MIGRATION_7_8,
                        )
                        .build()
                        .also { instance = it }
                }
            }
        }

        /**
         * 历史数据库文件名 clipboard.db → xime.db。
         * 同一目录下直接 rename；失败则 copy，保留旧文件由系统清理。
         */
        private fun migrateDatabaseFile(context: Context) {
            val newDb = context.getDatabasePath(DATABASE_NAME)
            val oldDb = context.getDatabasePath(LEGACY_DATABASE_NAME)
            if (newDb.exists() && newDb.length() > 0) return
            if (!oldDb.exists()) return
            if (newDb.exists()) newDb.delete()
            newDb.parentFile?.mkdirs()
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                val source = File(oldDb.path + suffix)
                if (!source.exists()) return@forEach
                val target = File(newDb.path + suffix)
                if (!source.renameTo(target)) {
                    runCatching { source.copyTo(target, overwrite = true) }
                }
            }
            File(oldDb.path + ".lck").delete()
        }

        /** 关闭并丢弃单例；下次 [getInstance] 按当前文件重建（备份恢复用）。 */
        fun closeInstance() {
            synchronized(this) {
                runCatching { instance?.close() }
                instance = null
            }
        }

        /**
         * 用备份包里的 `database/xime.db*` 替换本机库。
         *
         * 先关连接再换文件，避免旧连接继续写已删除的 inode；调用后首次访问会重新打开。
         */
        fun replaceFromStaged(context: Context, stagedDir: File) {
            if (!stagedDir.isDirectory) return
            closeInstance()
            val target = context.getDatabasePath(DATABASE_NAME)
            target.parentFile?.mkdirs()
            listOf("", "-wal", "-shm", "-journal").forEach { File(target.path + it).delete() }
            stagedDir.listFiles()?.forEach { source ->
                val dest = File(target.path + source.name.removePrefix(DATABASE_NAME))
                if (!source.renameTo(dest)) {
                    runCatching { source.copyTo(dest, overwrite = true) }
                }
                source.delete()
            }
            stagedDir.deleteRecursively()
        }

        fun scope(): CoroutineScope {
            return CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
    }
}
