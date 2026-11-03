package com.kingzcheung.xime.scheme

import android.content.Context
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.FuzzyPinyinManager
import com.kingzcheung.xime.settings.PersonalDictManager
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 部署的唯一入口。
 *
 * 顺序固定：补 `default.custom.yaml` 模板 → 把启用列表镜像进 `default.yaml`
 * → 个人词典补丁 → 重启维护（销毁/重建会话）→ 按产物校验结果。
 *
 * librime 的 `deploy()` 失败只写日志、返回码仍为 true，所以"是否成功"必须由
 * 产物校验决定，不能只看返回值。
 */
internal object SchemeDeployer {

    private const val TAG = "SchemeDeployer"
    private const val ASSETS_DEFAULT_CUSTOM = "default.custom.yaml"

    data class Result(
        val ok: Boolean,
        /** 未编译出产物的方案 id（部署成功≠每个方案都编译成功）。 */
        val missingSchemas: List<String> = emptyList(),
    )

    /**
     * @param force 内容刚整体替换过（build 属于旧包）→ 删 build 全量重编。
     */
    fun deploy(context: Context, force: Boolean = false): Result {
        val rimeDir = SchemeFiles.rimeDir(context)
        rimeDir.mkdirs()

        seedDefaultCustom(context, rimeDir)
        // 老内置安装升级：补新增的 english 方案文件；第三方方案集跳过。
        RimeConfigHelper.ensureBuiltinSchemaFiles(context, rimeDir)
        // 启用列表是状态，schema_list 是派生物：每次部署前重新镜像。
        SchemaManager.applyEnabledSchemasToDefaultYaml(context)
        // 模糊拼音是用户级状态，部署前物化成各方案 custom.yaml 的托管块。
        FuzzyPinyinManager.reconcile(context)
        runBlocking { PersonalDictManager.ensureSchemaPacks(context) }

        ensureEngine(context)

        // 内容整体替换（安装/更新方案包）时清一次产物，保证新包不带旧编译结果；
        // 普通部署不清 build，靠维护按文件时间戳重编变更项。
        if (force) File(rimeDir, "build").deleteRecursively()
        // 用户主动部署/安装替换一律 fullCheck：跳过顶层 mtime 快速判断，
        // 逐方案比对 __include 在内的所有引用文件（未变化的不重编）。
        val deployed = RimeConfigHelper.ensureDeployment(context, fullCheck = true)
        val missing = RimeConfigHelper.missingCompiledSchemas(context)
        if (!deployed) FileLogger.e(TAG, "deploy returned false")
        if (missing.isNotEmpty()) {
            FileLogger.e(TAG, "schemas without build artifacts: ${missing.joinToString()}")
        }
        return Result(ok = deployed && missing.isEmpty(), missingSchemas = missing)
    }

    /**
     * 部署需要已初始化的引擎（内容替换时引擎被销毁）。初始化会顺带跑
     * 状态自检与 schema_list 镜像，因此放在部署数据准备之后。
     */
    private fun ensureEngine(context: Context) {
        if (RimeEngine.isInitialized()) return
        val (userDataDir, sharedDataDir) = RimeConfigHelper.initializeRimeData(context)
        RimeEngine.getInstance().initialize(userDataDir, sharedDataDir)
    }

    /** `default.custom.yaml` 归用户：缺失时补一份 app 模板，存在则一字不改。 */
    private fun seedDefaultCustom(context: Context, rimeDir: File) {
        val target = File(rimeDir, ASSETS_DEFAULT_CUSTOM)
        if (target.exists()) return
        runCatching {
            context.assets.open(ASSETS_DEFAULT_CUSTOM).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }.onFailure { FileLogger.e(TAG, "seed $ASSETS_DEFAULT_CUSTOM failed", it) }
    }
}
