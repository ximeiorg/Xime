package com.kingzcheung.xime.scheme

import android.content.Context
import android.os.StatFs
import com.kingzcheung.xime.dictionary.ImportedDictManager
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.MarketVersionStore
import com.kingzcheung.xime.settings.RimeDependencyResolver
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 方案集的安装、切换、卸载。
 *
 * 核心流程（先删后解压，压缩包只有一份）：
 * 1. 探测归档（方案/体积/被丢弃条目）+ 空间预检 —— 失败时磁盘一个字节不动；
 * 2. 写意图（active/enabled/current + `EXTRACTING`）；
 * 3. 用户数据白名单旁置到 cache（同分区 rename）→ 清空 `rime` → 解压 → 归位；
 * 4. 标记 `READY` → 对齐状态 → 部署（失败仅记 `DEPLOY_FAILED`，可重试）。
 *
 * 中途被杀时 `EXTRACTING` 会留到下次启动，[recoverIfNeeded] 从压缩包重来。
 */
internal object SchemeInstaller {

    private const val TAG = "SchemeInstaller"
    private const val SPACE_MARGIN_RATIO = 1.1
    private const val SPACE_MARGIN_BYTES = 8L * 1024 * 1024

    enum class Failure { NO_ARCHIVE, ARCHIVE_CORRUPT, NO_SCHEMA, NO_SPACE, EXTRACT_FAILED }

    data class Outcome(
        val success: Boolean,
        val failure: Failure? = null,
        val schemaIds: List<String> = emptyList(),
        val parseFailures: List<String> = emptyList(),
        val missingSchemas: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val unresolvedDeps: List<String> = emptyList(),
        /** 包没有声明 `schema_list`：内容已装好，但没有启用任何方案，等用户手动选择。 */
        val needsSelection: Boolean = false,
    )

    @Volatile
    private var recovering = false

    /**
     * 安装 `market/<packageId>/` 里已下载的归档。
     *
     * @param switchEnabled 安装即切换（true）；更新同一包时传 false，保留用户现有选择。
     */
    fun installDownloaded(
        context: Context,
        packageId: String,
        name: String,
        version: String,
        switchEnabled: Boolean = true,
        dependencies: List<String> = emptyList(),
        resolveDepUrl: (String) -> String? = { null },
    ): Outcome {
        val dir = SchemeFiles.marketDir(context, packageId)
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return Outcome(false, Failure.NO_ARCHIVE)

        val archives = files.filter { SchemeArchive.isArchive(it.name) }
        val plain = files - archives.toSet()
        val probes = archives.associateWith { SchemeArchive.probe(it) }
        val broken = probes.entries.firstOrNull { it.value == null }?.key
        if (broken != null) {
            FileLogger.e(TAG, "corrupt archive: ${broken.name}")
            return Outcome(false, Failure.ARCHIVE_CORRUPT)
        }

        val schemaIds = (probes.values.filterNotNull().flatMap { it.schemaIds } +
            plain.filter { it.name.endsWith(".schema.yaml") }
                .map { it.name.removeSuffix(".schema.yaml") })
            .distinct()
        if (schemaIds.isEmpty()) return Outcome(false, Failure.NO_SCHEMA)

        val unpacked = probes.values.filterNotNull().sumOf { it.unpackedBytes } +
            plain.sumOf { it.length() }
        if (!hasFreeSpace(context, unpacked)) {
            FileLogger.w(TAG, "not enough space: need $unpacked bytes")
            return Outcome(false, Failure.NO_SPACE)
        }

        // 更新一个"非使用中"的包：只保留下载，不替换内容（否则会把当前方案集顶掉）。
        val isActive = SettingsPreferences.getActivePackageId(context) == packageId
        if (!switchEnabled && !isActive) {
            FileLogger.i(TAG, "$packageId is not active, keep download only")
            SchemePackages.remember(context, packageId, name, version)
            MarketVersionStore.setSchemeVersion(context, packageId, version)
            return Outcome(success = true, schemaIds = schemaIds)
        }
        val keepSelection = isActive
        val declared = SchemaManager.readPackagedSchemaList(context, packageId).orEmpty()
        val warnings = probes.values.filterNotNull().flatMap { it.skippedEntries }.distinct()

        return replaceContent(
            context = context,
            packageId = packageId,
            name = name,
            version = version,
            plannedSchemaIds = schemaIds,
            declared = declared,
            keepSelection = keepSelection,
            warnings = warnings,
            dependencies = dependencies,
            resolveDepUrl = resolveDepUrl,
            writeContent = { rimeDir ->
                var ok = true
                // 包内 xime.custom.yaml / themes / fonts 先落到 overlay：安装流程会先恢复
                // 旧用户数据，再由 restoreOverlay 覆盖，兼顾“保留旧资源”和“应用新包布局”。
                val overlay = SchemeFiles.overlayDir(context, packageId)
                overlay.deleteRecursively()
                overlay.mkdirs()
                for ((archive, probe) in probes) {
                    if (SchemeArchive.extract(archive, rimeDir, probe!!.baseDir) < 0) {
                        ok = false
                        break
                    }
                    if (SchemeArchive.extractUserData(archive, overlay, probe.baseDir) < 0) {
                        ok = false
                        break
                    }
                }
                if (ok) {
                    for (file in plain) {
                        val targetDir = if (SchemeFiles.isUserData(file.name)) overlay else rimeDir
                        runCatching { file.copyTo(File(targetDir, file.name), overwrite = true) }
                            .onFailure { ok = false }
                    }
                }
                ok
            },
        )
    }

    /** 恢复内置方案集（永远可重建，也是卸载其它包时的兜底）。 */
    fun restoreBuiltin(context: Context): Outcome = replaceContent(
        context = context,
        packageId = SchemeFiles.BUILTIN_PACKAGE_ID,
        name = "",
        version = "",
        plannedSchemaIds = SchemaManager.BUILTIN_SCHEMAS,
        declared = SchemaManager.BUILTIN_SCHEMAS,
        keepSelection = false,
        warnings = emptyList(),
        dependencies = emptyList(),
        resolveDepUrl = { null },
        writeContent = { rimeDir ->
            RimeConfigHelper.copyAssetsRecursively(context, SchemeFiles.RIME_DIR, rimeDir)
        },
    )

    /** 删除某个包的压缩包与覆盖文件；调用方负责先切走（删档后不可切回）。 */
    fun deleteArchive(context: Context, packageId: String): Boolean {
        if (packageId == SchemeFiles.BUILTIN_PACKAGE_ID) return false
        val dir = SchemeFiles.marketDir(context, packageId)
        val removed = !dir.exists() || dir.deleteRecursively()
        SchemeFiles.overlayDir(context, packageId).deleteRecursively()
        SchemePackages.forget(context, packageId)
        MarketVersionStore.removeSchemeVersion(context, packageId)
        SchemeStore.invalidate()
        FileLogger.i(TAG, "deleteArchive $packageId removed=$removed")
        return removed
    }

    /**
     * 启动自检的修复动作：
     * - `EXTRACTING`（上次安装被打断）→ 必须从压缩包重来，失败回内置；
     * - 内容不完整（启用方案的源文件缺失）→ 也重建，但压缩包不在时保持现状（不自作主张清算）。
     *
     * @return true 表示做了恢复动作。
     */
    fun recoverIfNeeded(context: Context): Boolean {
        if (recovering) return false
        val state = SchemeStore.installState(context)
        val active = SettingsPreferences.getActivePackageId(context)
        val interrupted = state == InstallState.EXTRACTING
        if (!interrupted && contentComplete(context, active)) return false

        recovering = true
        try {
            FileLogger.w(
                TAG,
                "recover: interrupted=$interrupted, active=$active, state=$state"
            )
            val outcome = if (active == null || active == SchemeFiles.BUILTIN_PACKAGE_ID) {
                restoreBuiltin(context)
            } else {
                installDownloaded(
                    context = context,
                    packageId = active,
                    name = SchemePackages.nameOf(context, active),
                    version = SchemePackages.versionOf(context, active),
                )
            }
            if (outcome.success) return true
            if (outcome.failure == Failure.NO_ARCHIVE || outcome.failure == Failure.ARCHIVE_CORRUPT) {
                if (interrupted) {
                    FileLogger.e(TAG, "recover failed (${outcome.failure}), falling back to builtin")
                    return restoreBuiltin(context).success
                }
                // 内容只是残缺、但压缩包没了：保持现状，交给状态层过滤
                FileLogger.w(TAG, "content incomplete but archive missing, keep as-is")
                SchemeStore.setInstallState(context, InstallState.READY)
            }
            return false
        } finally {
            recovering = false
        }
    }

    /** 活动包的内容是否完整：至少有一个方案源文件，且启用列表里有源文件在。 */
    private fun contentComplete(context: Context, active: String?): Boolean {
        val installed = SchemeFiles.installedSchemaIds(SchemeFiles.rimeDir(context))
        if (installed.isEmpty()) return false
        if (active == null || active == SchemeFiles.BUILTIN_PACKAGE_ID) return true
        val enabled = SettingsPreferences.getEnabledSchemaList(context).orEmpty()
        return enabled.isEmpty() || enabled.any { it in installed }
    }

    /**
     * 内容替换 + 状态对齐 + 部署。所有失败都发生在 `EXTRACTING` 之前或之后：
     * - 之前：磁盘与状态都没动；
     * - 之后：内容残缺，但压缩包与旁置的用户数据都在，等下次启动重来。
     */
    private fun replaceContent(
        context: Context,
        packageId: String,
        name: String,
        version: String,
        plannedSchemaIds: List<String>,
        declared: List<String>,
        keepSelection: Boolean,
        warnings: List<String>,
        dependencies: List<String>,
        resolveDepUrl: (String) -> String?,
        writeContent: (File) -> Boolean,
    ): Outcome {
        val previousEnabled = SettingsPreferences.getEnabledSchemaList(context).orEmpty()
        val previousCurrent = SettingsPreferences.getCurrentSchema(context)
        val enabled = if (keepSelection) {
            SchemeLogic.filterEnabled(previousEnabled, plannedSchemaIds)
                .ifEmpty { SchemeLogic.initialEnabled(declared, plannedSchemaIds) }
        } else {
            SchemeLogic.initialEnabled(declared, plannedSchemaIds)
        }
        val current = if (keepSelection) {
            SchemeLogic.pickCurrent(previousCurrent, enabled)
        } else {
            enabled.firstOrNull().orEmpty()
        }

        // 意图先落盘：崩在下面任何一步，启动自检都能按它重解压。
        SchemeStore.setInstallState(context, InstallState.EXTRACTING)
        SchemeStore.commitIntent(context, packageId, enabled, current)

        closeEngine()
        val rimeDir = SchemeFiles.rimeDir(context)
        val hold = SchemeFiles.installHoldDir(context)
        holdUserData(rimeDir, hold)
        rimeDir.deleteRecursively()
        rimeDir.mkdirs()

        if (!writeContent(rimeDir)) {
            FileLogger.e(TAG, "extract failed for $packageId; left EXTRACTING for recovery")
            return Outcome(false, Failure.EXTRACT_FAILED)
        }

        restoreUserData(hold, rimeDir)
        restoreOverlay(context, packageId, rimeDir)
        // 包内可能缺共享预设（symbols/default 等）：缺失的用 app 基线补一份。
        RimeConfigHelper.ensureBuiltinBaseFiles(context)
        SchemaManager.ensureDefaultYamlForInstall(context)
        // 包声明的依赖：装到 rime 后与包一起编译（失败只记警告，不阻断安装）。
        val dep = if (dependencies.isEmpty()) null else runBlocking {
            RimeDependencyResolver.complete(
                context = context,
                schemaId = plannedSchemaIds.firstOrNull() ?: packageId,
                dependencies = dependencies,
                resolveUrl = resolveDepUrl,
            )
        }
        SchemeStore.setInstallState(context, InstallState.READY)

        SchemeStore.invalidate()
        SchemeStore.ensure(context)
        // 状态归一后立刻镜像 default.yaml：即使下面跳过部署，也不能把包内/基线的
        // 旧 schema_list 留给下一次部署去编译。
        SchemaManager.applyEnabledSchemasToDefaultYaml(context)
        // 包内容重建会丢掉旧 `<方案>.custom.yaml`：按 DB 里的绑定重写 imported dict 的
        // packs 块，并把已不存在的方案从记录里摘掉（记录本身/派生词典保留）。
        runCatching {
            runBlocking { ImportedDictManager.reconcileAfterPackageChange(context) }
        }.onFailure {
            FileLogger.w(TAG, "reconcile imported lexicons failed: ${it.message}")
        }
        val installed = SchemeFiles.installedSchemaIds(rimeDir)
        val parseFailures = plannedSchemaIds.filter { it !in installed }

        // 没有声明 schema_list 的包不自动部署：等用户在「输入方案」里勾选启用后再部署，
        // 否则会把包内/基线 default.yaml 里的旧列表当成启用方案去编译。
        val needsSelection = SchemeStore.state(context).enabled.isEmpty()
        val deploy = if (needsSelection) {
            FileLogger.i(TAG, "skip deploy for $packageId: no schema_list declared, waiting for selection")
            SchemeDeployer.Result(ok = true)
        } else {
            SchemeDeployer.deploy(context, force = true)
        }
        if (!deploy.ok) SchemeStore.setInstallState(context, InstallState.DEPLOY_FAILED)
        SchemePackages.remember(context, packageId, name, version)

        FileLogger.i(
            TAG,
            "replace done: package=$packageId, installed=${installed.joinToString()}, " +
                "deployed=${deploy.ok}, needsSelection=$needsSelection, missing=${deploy.missingSchemas.joinToString()}"
        )
        return Outcome(
            success = true,
            schemaIds = installed,
            parseFailures = parseFailures,
            missingSchemas = deploy.missingSchemas,
            warnings = warnings,
            unresolvedDeps = ((dep?.unresolved ?: emptyList()) + (dep?.stillMissingFiles ?: emptyList())).distinct(),
            needsSelection = needsSelection,
        )
    }

    /** 用户数据白名单旁置：同分区 rename，零拷贝；目标已存在时先删（上次中断的残留）。 */
    private fun holdUserData(rimeDir: File, hold: File) {
        hold.deleteRecursively()
        hold.mkdirs()
        rimeDir.listFiles()?.filter { SchemeFiles.isUserData(it.name) }?.forEach { entry ->
            val target = File(hold, entry.name)
            if (target.exists()) target.deleteRecursively()
            if (!entry.renameTo(target)) FileLogger.w(TAG, "hold failed: ${entry.name}")
        }
    }

    private fun restoreUserData(hold: File, rimeDir: File) {
        hold.listFiles()?.forEach { entry ->
            val target = File(rimeDir, entry.name)
            if (target.exists()) target.deleteRecursively()
            if (!entry.renameTo(target)) FileLogger.w(TAG, "restore failed: ${entry.name}")
        }
        hold.deleteRecursively()
    }

    /** 该包的单文件导入（`market/<id>/overlay/`）覆盖回内容。 */
    private fun restoreOverlay(context: Context, packageId: String, rimeDir: File) {
        val overlay = SchemeFiles.overlayDir(context, packageId)
        if (!overlay.isDirectory) return
        overlay.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.toRelativeString(overlay)
            val target = File(rimeDir, rel)
            target.parentFile?.mkdirs()
            runCatching { file.copyTo(target, overwrite = true) }
                .onFailure { FileLogger.w(TAG, "restore overlay failed: $rel") }
        }
    }

    private fun closeEngine() {
        if (!RimeEngine.isInitialized()) return
        runCatching { RimeEngine.getInstance().destroy() }
            .onFailure { FileLogger.e(TAG, "closeEngine failed", it) }
    }

    private fun hasFreeSpace(context: Context, unpackedBytes: Long): Boolean = try {
        val stat = StatFs(context.filesDir.path)
        val available = stat.availableBytes
        val need = (unpackedBytes * SPACE_MARGIN_RATIO).toLong() + SPACE_MARGIN_BYTES
        available >= need
    } catch (e: Exception) {
        FileLogger.w(TAG, "space check failed, assume ok: ${e.message}")
        true
    }
}
