package com.kingzcheung.xime.rime

import com.kingzcheung.xime.util.FileLogger
import android.content.Context
import android.util.Log
import com.kingzcheung.xime.settings.PersonalDictManager
import com.kingzcheung.xime.settings.SchemaConfigHelper
import com.kingzcheung.xime.settings.FuzzyPinyinManager
import com.kingzcheung.xime.settings.MarketVersionStore
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.scheme.SchemeFiles
import com.kingzcheung.xime.scheme.SchemeInstaller
import com.kingzcheung.xime.scheme.SchemeStore
import com.kingzcheung.xime.settings.RimeDependencyResolver
import com.kingzcheung.xime.settings.RimeSharedFiles
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object RimeConfigHelper {
    private const val TAG = "RimeConfigHelper"
    private const val ASSETS_RIME_DIR = "rime"
    /** 内置 OpenCC 简繁转换数据：随内置方案集放在 assets/rime/opencc。 */
    private const val ASSETS_OPENCC_DIR = "rime/opencc"
    /** librime 查找 OpenCC 配置的目录名（相对 rime 数据目录）。 */
    private const val RIME_OPENCC_DIR = "opencc"
    /** app 固定的 default.custom.yaml 模板（assets 根，与 xime.yaml 并列，不随 submodule 分发）。 */
    private const val ASSETS_DEFAULT_CUSTOM = "default.custom.yaml"

    /** 市场索引中随 app 发布的内置默认方案集条目 id（见 rimes/index.yaml 的 `builtin`）。 */
    private const val BUILTIN_MARKET_ID = "builtin"
    /** 内置方案集的初始占位版本：`0.0.0` 保证不等于任何真实 git tag，从而在市场提示「更新」。 */
    private const val BUILTIN_PLACEHOLDER_VERSION = "0.0.0"
    /** 用户配置 BOM 扫描上限：词典等大文件不参与（体积大且不应被编辑器改写）。 */
    private const val BOM_SCAN_MAX_BYTES = 4L * 1024 * 1024

    /** 部署互斥：Application 预初始化与输入法服务初始化可能并发触发部署，串行化避免重复/并发全量编译。 */
    private val deploymentLock = Any()

    /** 冷启动自检（fullCheck=false）每进程只跑一次；用户主动部署不受此限。 */
    @Volatile
    private var startupCheckDone = false
    
    suspend fun initializeRimeDataAsync(context: Context): Pair<String, String> {
        val rimeDir = File(context.filesDir, "rime")
        
        // 迁移旧目录结构 (rime/shared/ + rime/user/) → 单一 rime/ 目录
        migrateOldStructure(context, rimeDir)
        
        // 迁移旧版 market 目录（rime/market/ → market/）
        migrateOldMarketDir(context)
        
        if (!rimeDir.exists()) {
            rimeDir.mkdirs()
        }
        
        copyAssetsToRimeDir(context, rimeDir)
        // 状态对齐（迁移一次 + 过滤失效项），再把启用列表镜像进 default.yaml。
        SchemeStore.ensure(context)
        // 老内置安装升级：补新增的 english 方案文件（不覆盖用户方案集）。
        ensureBuiltinSchemaFiles(context, rimeDir)
        SchemaManager.applyEnabledSchemasToDefaultYaml(context)
        // 模糊拼音是用户级状态：每次重建 rime 目录后按 prefs 重放补丁。
        FuzzyPinyinManager.reconcile(context)
        // 为所有启用方案打个人词库补丁
        PersonalDictManager.ensureSchemaPacks(context)
        // 不再在初始化阶段删 build：build 是否重建统一由 ensureDeployment()
        // 按增量优先策略决定，避免配置变化即全量重编译（60MB 词库持锁 30s+）。

        return Pair(rimeDir.absolutePath, rimeDir.absolutePath)
    }

    /**
     * 统一部署入口（进程内互斥），语义对齐成熟实现：
     *
     * - 用户主动部署 / 安装替换（[fullCheck] = true）：先重启维护（native 侧销毁
     *   会话 → `start_maintenance(true)` → 重建会话），再校验产物。
     *   维护会卸载已加载的方案与词典，沿用旧会话会让之后所有查询为空
     *   （症状：部署后打不出中文，必须重启 app），所以必须走同一条“重建会话”路径。
     * - 冷启动自检（[fullCheck] = false）：按数据目录顶层 yaml 的 mtime 做一次快速
     *   判断，无改动立即返回；每个进程只跑一次。
     *
     * 不再维护部署 hash：librime 的 `workspace_update` 会按
     * `__build_info/timestamps` 逐文件比对，`__include` 的自定义 yaml 也在其中；
     * 自建 hash 反而漏文件（曾出现改 `opencc/`、`lua/`、include 文件后部署不生效）。
     * 部署成功与否只看产物：librime 部署失败只写日志，返回值仍为 true。
     */
    fun ensureDeployment(context: Context, fullCheck: Boolean = false): Boolean {
        synchronized(deploymentLock) {
            // 外部编辑器保存 YAML 可能写入 UTF-8 BOM，librime 的解析器不会跳过 BOM，
            // 整份配置会解析失败（部署后方案无产物、打字无候选），部署前统一清掉。
            val bomFixed = stripBomFromUserYaml(File(context.filesDir, "rime"))
            if (bomFixed > 0) {
                FileLogger.w(TAG, "Stripped UTF-8 BOM from $bomFixed yaml file(s) before deploy")
            }
            // 刚补回基础文件：必须重跑维护，否则缺失文件不会被重新引用/编译
            val restored = ensureBuiltinBaseFiles(context)
            if (restored > 0) {
                FileLogger.w(TAG, "Restored $restored builtin file(s), forcing full check")
            }
            // 简繁转换数据不是编译输入，但会话重建后才懒加载：缺了要补（不强制重编）
            ensureOpenccAssets(context, SchemeFiles.rimeDir(context))

            val engine = RimeEngine.getInstance()
            if (!RimeEngine.isInitialized()) {
                val (userDataDir, sharedDataDir) = initializeRimeData(context)
                engine.initialize(userDataDir, sharedDataDir)
            }

            // 部署中断留下的半成品产物 mtime 比源文件新，维护会当作“已是最新”跳过，
            // 必须先删掉才会重编。
            val brokenRemoved = removeBrokenBuildArtifacts(File(context.filesDir, "rime"))
            val missing = missingCompiledSchemas(context)
            // 冷启动自检每进程一次；有缺失产物/补回基础文件/删过半成品时必须重跑
            if (!fullCheck && startupCheckDone && missing.isEmpty() &&
                !brokenRemoved && restored == 0
            ) {
                return true
            }
            val needFull = fullCheck || restored > 0 || brokenRemoved || missing.isNotEmpty()
            Log.i(
                TAG,
                "Deploying: fullCheck=$needFull, missing=${missing.size}, " +
                    "restored=$restored, brokenRemoved=$brokenRemoved",
            )
            var deployed = engine.deploy(needFull)
            if (!deployed) {
                FileLogger.e(TAG, "deploy returned false")
                return false
            }

            var stillMissing = missingCompiledSchemas(context)
            if (stillMissing.isNotEmpty() && !needFull) {
                // 只有一次兜底：不再做「逐方案补编 → 再全量」的多层重试
                FileLogger.w(
                    TAG,
                    "Still missing after incremental maintenance, forcing full check: " +
                        stillMissing.joinToString(),
                )
                deployed = engine.deploy(true)
                stillMissing = missingCompiledSchemas(context)
            }
            if (stillMissing.isNotEmpty()) {
                FileLogger.e(
                    TAG,
                    "Deployment finished but schemas are not compiled: ${stillMissing.joinToString()}",
                )
                return false
            }
            startupCheckDone = true
            return deployed
        }
    }

    /**
     * 去掉用户 YAML 的 UTF-8 BOM，返回实际改写的文件数。
     *
     * 文件管理器/记事本类编辑器保存时可能写入 BOM，librime 的 YAML 解析不跳过 BOM，
     * 整份配置会解析失败，表现为部署后方案没有编译产物（打字无候选）。
     * 幂等：无 BOM 不写盘；只扫根目录配置，跳过 `*.dict.yaml` 与超大文件。
     */
    internal fun stripBomFromUserYaml(rimeDir: File): Int {
        if (!rimeDir.isDirectory) return 0
        var fixed = 0
        rimeDir.listFiles()?.forEach { file ->
            if (!file.isFile || !file.name.endsWith(".yaml")) return@forEach
            if (file.name.endsWith(".dict.yaml") || file.length() > BOM_SCAN_MAX_BYTES) return@forEach
            runCatching {
                val bytes = file.readBytes()
                if (bytes.size >= 3 &&
                    bytes[0] == 0xEF.toByte() &&
                    bytes[1] == 0xBB.toByte() &&
                    bytes[2] == 0xBF.toByte()
                ) {
                    file.writeBytes(bytes.copyOfRange(3, bytes.size))
                    fixed++
                }
            }.onFailure { FileLogger.w(TAG, "strip BOM failed: ${file.name}", it) }
        }
        return fixed
    }

    /**
     * 已启用但缺少编译产物（build/<id>.prism.bin 或 build/<id>.schema.yaml）的方案。
     * librime 部署失败只写日志、部署任务本身仍返回成功，必须由宿主自行校验，
     * 否则「部署完成」会掩盖方案未编译（如 t9_pinyin 不在 schema_list 里）。
     */
    fun missingCompiledSchemas(context: Context): List<String> {
        val rimeDir = File(context.filesDir, "rime")
        // 只看源码还在的启用方案：源码已被卸载的残留项重编也补不出产物，
        // 不应因此让每次启动都触发一次维护。
        val enabled = SchemaManager.getEnabledSchemas(context)
            .filter { File(rimeDir, "$it.schema.yaml").isFile }
        return missingCompiledSchemas(
            enabled = enabled,
            buildDir = File(rimeDir, "build"),
        )
    }

    /** 纯文件校验（可单测）：启用方案是否都有至少一个编译产物。 */
    internal fun missingCompiledSchemas(
        enabled: List<String>,
        buildDir: File,
    ): List<String> = enabled.filter { schemaId ->
        !File(buildDir, "$schemaId.prism.bin").exists() &&
            !File(buildDir, "$schemaId.schema.yaml").exists()
    }

    /** [missingCompiledSchemas] 的显示名版本，用于部署失败提示。 */
    fun missingCompiledSchemaNames(context: Context): List<String> =
        missingCompiledSchemas(context).map { schemaId ->
            SchemaManager.getSchemaDisplayName(context, schemaId) ?: schemaId
        }
    
    fun initializeRimeData(context: Context): Pair<String, String> {
        val rimeDir = SchemeFiles.rimeDir(context)

        // 上次安装若在解压阶段被打断（EXTRACTING），先按压缩包重来一次，再读写 rime。
        SchemeInstaller.recoverIfNeeded(context)

        migrateOldStructure(context, rimeDir)
        
        if (!rimeDir.exists()) {
            rimeDir.mkdirs()
        }
        
        copyAssetsToRimeDir(context, rimeDir)
        // 同步初始化路径同样先对齐状态，再镜像 schema_list
        SchemeStore.ensure(context)
        ensureBuiltinSchemaFiles(context, rimeDir)
        SchemaManager.applyEnabledSchemasToDefaultYaml(context)
        FuzzyPinyinManager.reconcile(context)
        runBlocking { PersonalDictManager.ensureSchemaPacks(context) }
        // build 是否重建统一由 ensureDeployment() 决定（缺失/半成品时强制 fullCheck）

        return Pair(rimeDir.absolutePath, rimeDir.absolutePath)
    }

    /**
     * 部署产物完整性检查（半成品检测）：librime 的 table/prism 产物文件头是固定
     * magic（"Rime::Table/" / "Rime::Prism/"，Metadata.format 位于文件偏移 0）。
     * 部署进行中进程被杀会留下空文件或截断的半成品——其 mtime 比源文件新，
     * librime 增量维护会视为"已是最新"而跳过重编，导致该方案查询永远零命中
     * （症状：九键方案切换正常、按键进 buffer，但候选恒空、候选栏恒 IDLE）。
     */
    internal fun isBrokenBuildArtifact(file: File): Boolean {
        val expectedMagic = when {
            file.name.endsWith(".prism.bin") -> "Rime::Prism/"
            file.name.endsWith(".table.bin") -> "Rime::Table/"
            // 未知类型（如 reverse.bin、日志）不判损坏，避免误删触发部署循环
            else -> return false
        }
        if (!file.isFile || file.length() == 0L) return true
        if (file.length() < expectedMagic.length) return true
        return try {
            java.io.FileInputStream(file).use { input ->
                val head = ByteArray(expectedMagic.length)
                var read = 0
                while (read < head.size) {
                    val n = input.read(head, read, head.size - read)
                    if (n < 0) return true
                    read += n
                }
                !String(head, Charsets.US_ASCII).startsWith(expectedMagic)
            }
        } catch (_: IOException) {
            // 读不了不臆断损坏，交由 librime 运行时自检兜底
            false
        }
    }

    /**
     * 部署产物完整性：纯查询，不改磁盘。
     *
     * 仅用于 UI 提示（引导页/设置页）；「要不要重新部署」由 [ensureDeployment]
     * 自己判断（它会先删掉半成品产物再决定 fullCheck）。
     */
    fun isDeploymentComplete(context: Context): Boolean {
        val rimeDir = File(context.filesDir, "rime")
        val buildDir = File(rimeDir, "build")
        if (!buildDir.exists()) return false
        if (SchemaManager.getEnabledSchemas(context).isEmpty()) return false
        // 半成品产物（空文件/魔数错误）也算未完成
        if (buildDir.listFiles()?.any { it.isFile && isBrokenBuildArtifact(it) } == true) {
            return false
        }
        return missingCompiledSchemas(context).isEmpty()
    }

    /**
     * 删除部署中断留下的半成品产物（空文件/魔数错误），返回是否删过。
     *
     * librime 的 table/prism 产物文件头是固定 magic（"Rime::Table/" / "Rime::Prism/"）。
     * 部署被中断会留下空文件或截断文件，其 mtime 比源文件新，维护会当作“已是最新”
     * 跳过重编，导致该方案查询永远零命中。词典产物按 dictionary 名生成，可能与
     * schemaId 不同名（如 t9_pinyin 用 pinyin_simp 词典），故扫描整个 build 目录。
     */
    internal fun removeBrokenBuildArtifacts(rimeDir: File): Boolean {
        var removed = false
        File(rimeDir, "build").listFiles()?.forEach { artifact ->
            if (artifact.isFile && isBrokenBuildArtifact(artifact)) {
                if (artifact.delete()) {
                    removed = true
                    FileLogger.w(TAG, "Broken build artifact removed: ${artifact.name}")
                }
            }
        }
        return removed
    }

    /**
     * 内置方案集播种：只在 `rime` 里**一个方案都没有**时铺一份。
     *
     * 判据是内容而不是一次性标记：有内容说明用户已经有一整套方案（内置或第三方），
     * 不能覆盖；没有内容（全新安装、被清空、安装失败回退）才铺。
     */
    private fun copyAssetsToRimeDir(context: Context, targetDir: File): Boolean {
        // 简繁转换数据与方案集无关，每次初始化都补齐（旧版本升级/方案包替换后可能缺失）
        ensureOpenccAssets(context, targetDir)
        if (SchemeFiles.installedSchemaIds(targetDir).isNotEmpty()) {
            // 已有方案集：只补用户模板与存量迁移，不碰方案文件
            seedAndMigrateDefaultCustom(context, targetDir)
            return false
        }

        val copied = try {
            copyAssetsRecursively(context, ASSETS_RIME_DIR, targetDir)
        } catch (e: IOException) {
            FileLogger.e(TAG, "Failed to copy assets", e)
            false
        }
        if (!copied) return false

        seedBuiltinPackageVersion(context)
        ensureDefaultYaml(context, targetDir)
        seedAndMigrateDefaultCustom(context, targetDir)
        FileLogger.i(TAG, "Seeded builtin schemas (rime was empty)")
        return true
    }

    /**
     * 补齐内置 OpenCC 简繁转换数据（`assets/rime/opencc/` 下的 `.json` 与 `.ocd2`）。
     *
     * librime 的 simplifier 按 `<user_data_dir>/opencc/<config>` 查找转换配置，
     * 缺目录时「简入繁出」不生效（日志：`opencc config not found: s2hk.json`）。
     * 只补缺失文件，不覆盖用户自有数据（用户或方案包可能自带更新的 opencc 词典）；
     * 返回本次补齐的文件数。
     */
    internal fun ensureOpenccAssets(context: Context, rimeDir: File): Int {
        val names = try {
            context.assets.list(ASSETS_OPENCC_DIR)
        } catch (e: IOException) {
            FileLogger.e(TAG, "Failed to list opencc assets", e)
            null
        } ?: return 0

        val targetDir = File(rimeDir, RIME_OPENCC_DIR)
        var copied = 0
        for (name in names) {
            // LICENSE 等随包说明不落到 rime 数据目录
            if (!name.endsWith(".json") && !name.endsWith(".ocd2")) continue
            val target = File(targetDir, name)
            if (target.isFile && target.length() > 0L) continue
            try {
                target.parentFile?.mkdirs()
                context.assets.open("$ASSETS_OPENCC_DIR/$name").use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
                copied++
            } catch (e: IOException) {
                FileLogger.e(TAG, "Failed to copy opencc asset: $name", e)
            }
        }
        if (copied > 0) FileLogger.i(TAG, "Restored $copied builtin opencc file(s)")
        return copied
    }

    /**
     * default.custom.yaml 种子复制 + 存量迁移。
     *
     * 该文件完全归用户：缺失时复制一次 app 模板（不含 schema_list，见模板头注释）
     * 作为起始基线，此后 app 零写入——不再对齐 page_size、不再清理 key_binder，
     * 用户修改经部署合并覆盖 default.yaml 同名键、下次部署自动生效。
     * 存量迁移（[SchemaManager.migrateDefaultCustomSchemaList]）把老版本写入的
     * schema_list 迁往 prefs；必须先于任何 [SchemaManager.getEnabledSchemas]
     * 消费者执行（copyAssetsToRimeDir 之后的 applyEnabledSchemasToDefaultYaml /
     * ensureDeployment 均在其后，时序安全）。
     */
    private fun seedAndMigrateDefaultCustom(context: Context, targetDir: File) {
        val target = File(targetDir, ASSETS_DEFAULT_CUSTOM)
        try {
            if (!target.exists()) {
                copyAssetFile(context, ASSETS_DEFAULT_CUSTOM, target)
            }
        } catch (e: IOException) {
            FileLogger.e(TAG, "Failed to seed $ASSETS_DEFAULT_CUSTOM", e)
        }
        SchemaManager.migrateDefaultCustomSchemaList(context)
    }

    /**
     * 全新安装复制内置默认方案集后，为市场条目 `builtin`（type=built-in 的默认方案包，
     * 一个压缩包里含多个 schema）写入一个初始占位版本号。
     *
     * 市场方案的版本号与具体 .schema.yaml 无关，是方案集所属 git 仓库的 tag（见
     * rimes/index.yaml 中 builtin 条目）。随 app 发布的内置方案集在运行时无法自报其
     * 对应的 git tag，因此写入一个不等于任何真实 tag 的占位值，使市场 `hasUpdate`
     * （installedVersion != currentVersion）成立，从而向用户提示「更新」，把默认方案
     * 从市场更新到带真实版本号的版本。仅当该条目尚无版本记录时写入。
     */
    private fun seedBuiltinPackageVersion(context: Context) {
        if (MarketVersionStore.getSchemeVersion(context, BUILTIN_MARKET_ID) == null) {
            MarketVersionStore.setSchemeVersion(context, BUILTIN_MARKET_ID, BUILTIN_PLACEHOLDER_VERSION)
        }
    }

    /** 兜底确保 default.yaml 存在（首次复制失败/旧结构迁移时可能缺失），缺失才补且不覆盖已有内容。 */
    private fun ensureDefaultYaml(context: Context, targetDir: File) {
        val defaultYaml = File(targetDir, "default.yaml")
        if (defaultYaml.exists()) return
        copyAssetFile(context, "$ASSETS_RIME_DIR/default.yaml", defaultYaml)
    }
    
    /** 补齐缺失/空的内置共享文件（不覆盖已有内容），返回补齐数。 */
    fun ensureBuiltinBaseFiles(context: Context): Int {
        val rimeDir = File(context.filesDir, "rime")
        if (!rimeDir.exists()) rimeDir.mkdirs()
        var restored = 0
        for (name in RimeSharedFiles.names(context)) {
            val target = File(rimeDir, name)
            if (target.isFile && target.length() > 0L) continue
            copyAssetFile(context, "$ASSETS_RIME_DIR/$name", target)
            if (target.isFile && target.length() > 0L) {
                restored++
                FileLogger.w(TAG, "Restored missing builtin file: $name")
            }
        }
        return restored
    }

    /**
     * 老内置安装升级时补齐新增的内置方案文件（当前是 english 方案及其空格 filter）。
     *
     * `copyAssetsToRimeDir` 只在 rime 目录为空时整包播种；已有方案内容不会被覆盖，
     * 所以升级安装必须单独补这些新文件，否则英文方案只存在于 assets、用户永远看不到。
     * 只对活动包为内置包时执行，避免往第三方方案集里塞内置方案。
     */
    fun ensureBuiltinSchemaFiles(context: Context, rimeDir: File): Int =
        ensureBuiltinSchemaFiles(
            context = context,
            rimeDir = rimeDir,
            activePackageId = SchemeStore.state(context).activePackageId,
        )

    internal fun ensureBuiltinSchemaFiles(
        context: Context,
        rimeDir: File,
        activePackageId: String,
    ): Int {
        if (activePackageId != SchemeFiles.BUILTIN_PACKAGE_ID) {
            return 0
        }
        var restored = 0
        val englishSchema = File(rimeDir, "english.schema.yaml")
        val needsEnglishSchema = !englishSchema.isFile || englishSchema.length() == 0L ||
            !runCatching { englishSchema.readText(Charsets.UTF_8).contains("english_spacing") }
                .getOrDefault(false)
        if (needsEnglishSchema) {
            copyAssetFile(context, "$ASSETS_RIME_DIR/english.schema.yaml", englishSchema)
            if (englishSchema.isFile && englishSchema.length() > 0L) restored++
        }
        for (relative in listOf("english.dict.yaml", "lua/english_spacing.lua")) {
            val target = File(rimeDir, relative)
            if (target.isFile && target.length() > 0L) continue
            copyAssetFile(context, "$ASSETS_RIME_DIR/$relative", target)
            if (target.isFile && target.length() > 0L) {
                restored++
                FileLogger.w(TAG, "Restored upgraded builtin schema file: $relative")
            }
        }
        if (restored > 0) {
            FileLogger.i(TAG, "ensureBuiltinSchemaFiles: restored $restored english file(s)")
        }
        return restored
    }

    /**
     * 已启用方案引用、但缺 `build/<dict>.table.bin` 的反查词典：librime 只编译方案的
     * 主词典，反查词典要它自己的方案被部署才生成产物。返回 方案 id → 缺失词典名。
     */
    fun missingReverseLookupTables(context: Context): List<Pair<String, String>> {
        val rimeDir = File(context.filesDir, "rime")
        val buildDir = File(rimeDir, "build")
        val missing = mutableListOf<Pair<String, String>>()
        for (schemaId in SchemaManager.getEnabledSchemas(context)) {
            val schemaFile = File(rimeDir, "$schemaId.schema.yaml")
            if (!schemaFile.isFile) continue
            val refs = try {
                RimeDependencyResolver.scanSchemaRefs(schemaFile.readText(), null)
            } catch (e: IOException) {
                continue
            }
            for (dict in refs.reverseDicts) {
                if (!File(buildDir, "$dict.table.bin").exists()) missing.add(schemaId to dict)
            }
        }
        return missing
    }


    internal fun copyAssetsRecursively(context: Context, assetPath: String, targetDir: File): Boolean {
        val files = context.assets.list(assetPath)
        
        if (files.isNullOrEmpty()) {
            return false
        }
        
        var copiedAny = false
        
        for (fileName in files) {
            val fullAssetPath = "$assetPath/$fileName"
            val targetFile = File(targetDir, fileName)
            
            try {
                val subFiles = context.assets.list(fullAssetPath)
                if (!subFiles.isNullOrEmpty()) {
                    if (!targetFile.exists()) {
                        targetFile.mkdirs()
                    }
                    if (copyAssetsRecursively(context, fullAssetPath, targetFile)) {
                        copiedAny = true
                    }
                } else if (fileName.endsWith(".yaml") || fileName.endsWith(".lua")) {
                    val needsCopy = try {
                        if (targetFile.exists()) {
                            val fd = context.assets.openFd(fullAssetPath)
                            val sameSize = targetFile.length() == fd.length
                            fd.close()
                            !sameSize
                        } else true
                    } catch (_: Exception) {
                        true
                    }
                    if (needsCopy) {
                        copyAssetFile(context, fullAssetPath, targetFile)
                        copiedAny = true
                    }
                }
            } catch (e: IOException) {
                FileLogger.e(TAG, "Failed to process: $fullAssetPath", e)
            }
        }
        
        return copiedAny
    }
    
    private fun copyAssetFile(context: Context, assetPath: String, targetFile: File) {
        try {
            if (targetFile.exists() && targetFile.name.contains("custom")) {
                return
            }

            targetFile.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            FileLogger.e(TAG, "Failed to copy: $assetPath", e)
        }
    }

    private fun migrateOldStructure(context: Context, rimeDir: File) {
        val oldSharedDir = File(context.filesDir, "rime/shared")
        val oldUserDir = File(context.filesDir, "rime/user")
        
        if (!oldSharedDir.exists() && !oldUserDir.exists()) return
        
        Log.i(TAG, "Migrating old rime directory structure to single rime/ dir...")
        
        if (!rimeDir.exists()) rimeDir.mkdirs()
        
        // 迁移 user 数据（用户配置、build 产物、userdb）
        if (oldUserDir.exists()) {
            oldUserDir.listFiles()?.forEach { file ->
                val target = File(rimeDir, file.name)
                if (!target.exists()) {
                    file.renameTo(target)
                }
            }
        }
        
        // 迁移 shared 数据（方案文件）
        if (oldSharedDir.exists()) {
            oldSharedDir.listFiles()?.forEach { file ->
                val target = File(rimeDir, file.name)
                if (!target.exists()) {
                    file.renameTo(target)
                }
            }
        }
        
        // 删除旧目录
        oldSharedDir.deleteRecursively()
        oldUserDir.deleteRecursively()
        
        Log.i(TAG, "Migration complete")
    }

    /** 迁移旧版 market 目录（rime/market/ → market/）。 */
    private fun migrateOldMarketDir(context: Context) {
        val oldMarket = File(context.filesDir, "rime/market")
        if (!oldMarket.exists()) return

        val newMarket = SchemaManager.getMarketDir(context)
        if (!newMarket.exists()) {
            // 新位置不存在，直接重命名
            if (oldMarket.renameTo(newMarket)) {
                Log.i(TAG, "Migrated rime/market/ -> market/")
            } else {
                FileLogger.w(TAG, "Failed to rename rime/market/ to market/")
            }
        } else {
            // 新位置已存在，逐项合并
            oldMarket.listFiles()?.forEach { sub ->
                val target = File(newMarket, sub.name)
                if (!target.exists()) {
                    sub.renameTo(target)
                }
            }
            oldMarket.deleteRecursively()
            Log.i(TAG, "Merged rime/market/ into market/")
        }
    }

}