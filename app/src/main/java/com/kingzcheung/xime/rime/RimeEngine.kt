package com.kingzcheung.xime.rime

import com.kingzcheung.xime.util.FileLogger
import android.util.Log
import java.io.File
import java.util.concurrent.locks.ReentrantLock

data class RimeCandidate(
    val text: String,
    val comment: String
)

/**
 * 批量查询当前 composition 状态。
 *
 * 通过 JNI 一次性返回 input/preedit/commit/candidates/paging/ascii_mode，
 * 避免 updateUI 中多次独立 JNI 调用带来的固定开销。
 */
data class RimeComposition(
    val input: String,
    val preedit: String,
    val committedText: String,
    val candidates: Array<RimeCandidate>,
    val hasNextPage: Boolean,
    val hasPrevPage: Boolean,
    val isAsciiMode: Boolean,
    /** native 快照：组合内光标（raw input 字符偏移）。当前显示层由宿主编辑光标驱动，此字段仅随快照返回。 */
    val caretPos: Int = 0,
    /** native 快照：preedit 中的光标（UTF-8 字节偏移）。 */
    val preeditCursorPos: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RimeComposition) return false
        return input == other.input &&
                preedit == other.preedit &&
                committedText == other.committedText &&
                candidates.contentEquals(other.candidates) &&
                hasNextPage == other.hasNextPage &&
                hasPrevPage == other.hasPrevPage &&
                isAsciiMode == other.isAsciiMode &&
                caretPos == other.caretPos &&
                preeditCursorPos == other.preeditCursorPos
    }

    override fun hashCode(): Int {
        var result = input.hashCode()
        result = 31 * result + preedit.hashCode()
        result = 31 * result + committedText.hashCode()
        result = 31 * result + candidates.contentHashCode()
        result = 31 * result + hasNextPage.hashCode()
        result = 31 * result + hasPrevPage.hashCode()
        result = 31 * result + isAsciiMode.hashCode()
        result = 31 * result + caretPos
        result = 31 * result + preeditCursorPos
        return result
    }
}

data class RimeProcessResult(
    val processed: Boolean,
    val committedText: String,
    val inputText: String,
    val preeditText: String,
    val candidates: Array<RimeCandidate>,
    val isAsciiMode: Boolean,
    val hasNextPage: Boolean,
    val hasPrevPage: Boolean,
    /**
     * T9 左侧面板状态（格式 STATE;PINYIN;DIGIT_LEN;SEL_DIGITS;PANEL_DIGITS;LEFT_LOCKED）。
     * 由 JNI 在 T9 会话活跃时填充，非 T9 场景为空字符串。
     */
    val t9PanelState: String = "",
    /**
     * T9 首音节候选（"pinyin|digitLength" 逗号分隔，如 "ji|2,li|2,j|1"）。
     * 由 JNI 一次计算，避免 Kotlin 侧重复取数。
     */
    val t9SyllableOptions: String = "",
    /** native 快照：组合内光标（raw input 字符偏移）。当前显示层由宿主编辑光标驱动，此字段仅随快照返回。 */
    val caretPos: Int = 0,
    /** native 快照：preedit 中的光标（UTF-8 字节偏移）。 */
    val preeditCursorPos: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RimeProcessResult) return false
        return processed == other.processed &&
                committedText == other.committedText &&
                inputText == other.inputText &&
                preeditText == other.preeditText &&
                candidates.contentEquals(other.candidates) &&
                isAsciiMode == other.isAsciiMode &&
                hasNextPage == other.hasNextPage &&
                hasPrevPage == other.hasPrevPage &&
                t9PanelState == other.t9PanelState &&
                t9SyllableOptions == other.t9SyllableOptions &&
                caretPos == other.caretPos &&
                preeditCursorPos == other.preeditCursorPos
    }

    override fun hashCode(): Int {
        var result = processed.hashCode()
        result = 31 * result + committedText.hashCode()
        result = 31 * result + inputText.hashCode()
        result = 31 * result + preeditText.hashCode()
        result = 31 * result + candidates.contentHashCode()
        result = 31 * result + isAsciiMode.hashCode()
        result = 31 * result + hasNextPage.hashCode()
        result = 31 * result + hasPrevPage.hashCode()
        result = 31 * result + t9PanelState.hashCode()
        result = 31 * result + t9SyllableOptions.hashCode()
        result = 31 * result + caretPos
        result = 31 * result + preeditCursorPos
        return result
    }
}

/** 将一次按键得到的完整结果转为 RimeComposition，供 T9 控制器复用，避免重复 JNI。 */
fun RimeProcessResult.toComposition(): RimeComposition {
    return RimeComposition(
        input = inputText,
        preedit = preeditText,
        committedText = committedText,
        candidates = candidates,
        hasNextPage = hasNextPage,
        hasPrevPage = hasPrevPage,
        isAsciiMode = isAsciiMode,
        caretPos = caretPos,
        preeditCursorPos = preeditCursorPos,
    )
}

class RimeEngine {

    companion object {
        private const val TAG = "RimeEngine"
        private var instance: RimeEngine? = null
        private var deploymentCallback: ((Boolean, String) -> Unit)? = null

        /** 全局 Rime 引擎锁 — 所有 native 调用必须通过此锁同步 */
        val rimeLock = ReentrantLock()

        init {
            System.loadLibrary("rime_jni")
        }

        fun getInstance(): RimeEngine {
            return instance ?: synchronized(this) {
                instance ?: RimeEngine().also { instance = it }
            }
        }

        fun isInitialized(): Boolean = instance?.isInitialized ?: false

        /**
         * 检查指定的 Rime 模块是否已注册（用于验证插件集成）
         */
        fun isModuleRegistered(moduleName: String): Boolean {
            val engine = instance ?: return false
            if (!engine.isInitialized) return false
            return engine.nativeIsModuleRegistered(moduleName)
        }

        fun setDeploymentCallback(callback: (isDeploying: Boolean, message: String) -> Unit) {
            deploymentCallback = callback
        }
    }

    private var isInitialized = false
    private val initLock = Any()
    @Volatile
    private var userDataDir: String = ""

    /**
     * 管理路径（初始化/部署/会话创建/方案切换等）：阻塞等待锁。
     * 这类调用在后台线程执行，等待部署/编译完成是预期行为。
     */
    private inline fun <T> locked(block: () -> T): T {
        rimeLock.lock()
        try {
            return block()
        } finally {
            rimeLock.unlock()
        }
    }

    /**
     * 输入/查询路径：非阻塞获取锁。拿不到锁（如全量部署持锁中）立即返回
     * [defaultValue] 而不等待，避免主线程 UI（updateUI → getComposition 等）
     * 被 20+ 秒的部署阻塞导致 ANR。拿不到锁时不进入 native，并发安全。
     */
    private inline fun <T> tryLocked(defaultValue: T, block: () -> T): T {
        if (!rimeLock.tryLock()) return defaultValue
        try {
            return block()
        } finally {
            rimeLock.unlock()
        }
    }

    private fun notifyDeploymentStatus(isDeploying: Boolean, message: String) {
        deploymentCallback?.invoke(isDeploying, message)
    }

    fun initialize(userDataDir: String, sharedDataDir: String) {
        if (!isInitialized) {
            synchronized(initLock) {
                if (!isInitialized) {
                    try {
                        this.userDataDir = userDataDir
                        nativeInstallSignalHandler(userDataDir)
                        notifyDeploymentStatus(true, "正在加载输入法引擎...")
                        nativeInitialize(userDataDir, sharedDataDir)
                        isInitialized = true

                        // 部署后主动触发：为已部署目录中所有 T9 方案打九键特有补丁
                        // （t9 四要素 + 个人词库 packs 兜底），保证第三方九键方案
                        // 首次使用前 custom.yaml 健康、用户词典生效。
                        ensureT9SchemaPatchesForDeployedSchemas(userDataDir)

                        // startup 只初始化引擎，不创建 session
                        // session 在第一次使用时延迟创建（ensureSession）
                        // 部署在后台异步运行，不阻塞

                        notifyDeploymentStatus(false, "")
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Error during Rime initialization", e)
                        notifyDeploymentStatus(false, "初始化失败")
                    }
                }
            }
        }
    }

    fun ensureSession(timeoutMs: Long = 60000L): Boolean {
        if (!isInitialized) return false

        // Quick check without lock — native calls are thread-safe reads
        if (nativeHasSession() && getAvailableSchemas().isNotEmpty()) return true

        // 等待编译完成（不持有 rimeLock，避免阻塞主线程 UI 操作）
        var waited = 0L
        while (nativeIsMaintaining() && waited < timeoutMs) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return false
            }
            waited += 1000
        }

        locked {
            waited = 0L
            while (waited < timeoutMs) {
                if (!nativeHasSession()) {
                    nativeCreateSession()
                }
                if (getAvailableSchemas().isNotEmpty()) {
                    return true
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return false
                }
                waited += 1000
            }
            FileLogger.w(TAG, "ensureSession: schemas not available after ${timeoutMs}ms, deployment may still be running")
            return false
        }
    }

    fun isMaintaining(): Boolean {
        return nativeIsMaintaining()
    }

    /** 引擎是否繁忙（维护中或 rimeLock 被占用）：非阻塞探测，供 UI 立即反馈使用。 */
    fun isEngineBusy(): Boolean {
        return nativeIsMaintaining() || rimeLock.isLocked
    }

    fun getCurrentSchema(): String {
        if (!nativeHasSession()) return ""
        return tryLocked("") {
            nativeGetCurrentSchema() ?: ""
        }
    }

    fun processKey(keycode: Int, mask: Int): Boolean {
        if (!isInitialized) return false
        return tryLocked(false) {
            if (!nativeHasSession() && !nativeCreateSession()) return@tryLocked false
            nativeProcessKey(keycode, mask)
        }
    }

    fun processKeyAndGetResult(keycode: Int, mask: Int): RimeProcessResult {
        if (!isInitialized) return RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)
        return tryLocked(RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)) {
            if (!nativeHasSession() && !nativeCreateSession())
                return@tryLocked RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)
            nativeProcessKeyAndGetResult(keycode, mask)
        }
    }

    fun getProcessResult(processed: Boolean): RimeProcessResult {
        if (!isInitialized) return RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)
        // 必须持 rimeLock：nativeGetProcessResult 内部 RimeGetContext → Menu::Prepare
        // 会惰性生成候选页（遍历 translations），与 t9FlushRimeInput（set_input → Reset，
        // 释放旧 translations）并发会导致悬空 → ScriptTranslation::Peek 空指针崩溃。
        // 部署/编译持锁期间拿不到锁直接返回空结果（不进 native），避免阻塞调用线程。
        return tryLocked(RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)) {
            nativeGetProcessResult(processed)
        }
    }

    fun getCandidates(): Array<String> {
        return tryLocked(emptyArray()) {
            if (!nativeHasSession()) return@tryLocked emptyArray()
            nativeGetCandidates() ?: emptyArray()
        }
    }

    fun getCandidatesWithComments(): Array<RimeCandidate> {
        return tryLocked(emptyArray()) {
            if (!nativeHasSession()) return@tryLocked emptyArray()
            val rawCandidates = nativeGetCandidatesWithComments() ?: emptyArray()
            rawCandidates.map { pair ->
                RimeCandidate(
                    text = pair.getOrElse(0) { "" },
                    comment = pair.getOrElse(1) { "" }
                )
            }.toTypedArray()
        }
    }

    /**
     * 跨页获取整个候选列表（与引擎分页无关），供候选展开页本地分页使用。
     * @param maxCount 收集上限，防御超大列表
     */
    fun getAllCandidates(maxCount: Int = 500): Array<RimeCandidate> {
        return tryLocked(emptyArray()) {
            if (!nativeHasSession()) return@tryLocked emptyArray()
            val rawCandidates = nativeGetAllCandidates(maxCount) ?: emptyArray()
            rawCandidates.map { pair ->
                RimeCandidate(
                    text = pair.getOrElse(0) { "" },
                    comment = pair.getOrElse(1) { "" }
                )
            }.toTypedArray()
        }
    }

    fun getInput(): String {
        return tryLocked("") {
            nativeGetInput() ?: ""
        }
    }

    /**
     * 批量查询当前 composition 全部信息。
     *
     * 一次 JNI 调用返回 input、preedit、committedText、candidates、分页和 ascii_mode，
     * 是 T9 路径 updateUI 的首选查询接口，可替代多次独立 JNI 调用。
     */
    fun getComposition(): RimeComposition {
        return tryLocked(RimeComposition("", "", "", emptyArray(), false, false, false)) {
            nativeGetComposition()
        }
    }

    fun selectCandidate(index: Int): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            nativeSelectCandidate(index)
        }
    }

    /**
     * 按候选列表全局索引选词（跨页，与 getAllCandidates 遍历顺序一致）。
     * 候选展开页本地分页点选走此接口：本地页内索引 + 页偏移 = 全局索引。
     */
    fun selectCandidateByGlobalIndex(index: Int): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            nativeSelectCandidateByGlobalIndex(index)
        }
    }

    /**
     * 删除当前页候选（长按候选栏删除自造词）。
     * 标准 C API delete_candidate_on_current_page：librime 对用户词典词条
     * 执行 tombstone 标记（UpdateEntry -1），键盘无关（T9/全键盘通用）。
     * @param index 当前页内的候选索引
     */
    fun deleteCandidateOnCurrentPage(index: Int): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            nativeDeleteCandidateOnCurrentPage(index)
        }
    }

    /**
     * 按候选列表全局索引删除（跨页，与 getAllCandidates 遍历顺序一致），
     * 供候选展开页本地分页长按删除自造词。
     */
    fun deleteCandidateByGlobalIndex(index: Int): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            nativeDeleteCandidateByGlobalIndex(index)
        }
    }

    fun pageDown(): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            nativePageDown()
        }
    }

    fun pageUp(): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            nativePageUp()
        }
    }

    fun hasNextPage(): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            nativeHasNextPage()
        }
    }

    fun hasPrevPage(): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            nativeHasPrevPage()
        }
    }

    fun commit(): String {
        return tryLocked("") {
            nativeCommit() ?: ""
        }
    }

    fun clearComposition() {
        if (!nativeHasSession()) return
        tryLocked(Unit) {
            nativeClearComposition()
        }
    }

    /**
     * 设置 RIME 引擎的输入字符串。
     *
     * 一次 JNI 调用完成整个输入设置，替代逐字符 processKey。
     * 调用后引擎会重新执行完整的处理管线（Speller → Segmentor → Translator）。
     * 支持分隔符 '，如 setInput("ji'he") 会告知 RIME 音节边界。
     *
     * @param input 拼音或数字字符串，如 "zhongguo" 或 "54482"
     * @return 是否设置成功
     */
    fun setInput(input: String): Boolean {
        if (!isInitialized) return false
        return tryLocked(false) {
            if (!nativeHasSession() && !nativeCreateSession()) return@tryLocked false
            nativeSetInput(input)
        }
    }

    fun toggleAsciiMode(): Boolean {
        // 用户显式切换操作：阻塞等待锁（部署/维护持锁时排队，完成后自动切换），
        // 不静默失败；调用方保证不在主线程执行（ImeKeyRouter 的 key-process 线程）。
        return locked {
            if (!nativeHasSession() && !nativeCreateSession()) return@locked false
            nativeToggleAsciiMode()
        }
    }

    fun isAsciiMode(): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession() && !nativeCreateSession()) return@tryLocked false
            nativeIsAsciiMode()
        }
    }

    fun setOption(option: String, value: Boolean) {
        if (!nativeHasSession()) return
        nativeSetOption(option, value)
    }

    fun getOption(option: String): Boolean {
        if (!nativeHasSession()) return false
        return nativeGetOption(option)
    }

    fun setPageSize(schemaId: String, pageSize: Int) {
        if (!isInitialized) return
        nativeSetPageSize(schemaId, pageSize)
    }

    fun switchSchema(schemaId: String): Boolean {
        // 部署/全量编译进行中（30s+）不阻塞等待：直接返回 false，避免主线程
        // onStartInput/selectSchema 等路径 ANR。部署完成后的 initRimeEngine
        // 流程会重新切换方案。非部署场景保持阻塞锁语义，保证切换可靠。
        if (isMaintaining()) {
            FileLogger.w(TAG, "switchSchema($schemaId) skipped: deployment in progress")
            return false
        }
        locked {
            if (!nativeHasSession()) {
                FileLogger.w(TAG, "switchSchema($schemaId) failed: no rime session")
                return false
            }
            // 在切换方案前，确保 T9 方案的 schema 补丁已注入
            // 这会在 user_data_dir 中创建 {schemaId}.custom.yaml 文件，
            // RIME 引擎加载方案时会自动应用 custom.yaml 中的 patch 补丁
            nativeEnsureT9SchemaPatches(schemaId)
            val switched = nativeSwitchSchema(schemaId)
            if (!switched) {
                // 常见于方案未部署（不在 schema_list，如老版本升级残留）：
                // 留证便于反馈日志定位（用户症状：键盘已切换但按键无候选）
                FileLogger.w(TAG, "switchSchema($schemaId) failed: schema not available, current=${getCurrentSchema()}")
            }
            return switched
        }
    }

    fun startMaintenance(full: Boolean): Boolean {
        if (!isInitialized) return false
        locked {
            return nativeStartMaintenance(full)
        }
    }

    fun deploy(): Boolean {
        if (!isInitialized) return false
        locked {
            // 部署前确保 T9 补丁（含个人词库 packs 确定性名）已写入，
            // 部署时 librime 才会编译对应的 user_<schemaId>.table.bin。
            // 幂等：补丁已就位时 native 侧直接 skip，开销极小。
            ensureT9SchemaPatchesForDeployedSchemas(userDataDir)
            return nativeDeploy()
        }
    }

    /**
     * 增量维护部署：librime 根据文件时间戳只编译变更的 schema/dict，
     * 避免配置小幅变化（如 custom.yaml 补丁）触发 60MB 词库全量重编译。
     * 与 [deploy] 一样持 rimeLock 并等待维护完成，保证维护期间其他
     * native 调用不会并发进入引擎。
     */
    fun deployIncremental(): Boolean {
        if (!isInitialized) return false
        locked {
            ensureT9SchemaPatchesForDeployedSchemas(userDataDir)
            if (!nativeStartMaintenance(false)) {
                FileLogger.w(TAG, "deployIncremental: startMaintenance returned false, falling back to full deploy")
                return false
            }
            var waited = 0L
            while (nativeIsMaintaining() && waited < 180_000L) {
                Thread.sleep(100)
                waited += 100
            }
            if (nativeIsMaintaining()) {
                FileLogger.w(TAG, "deployIncremental: maintenance timed out")
                return false
            }
            // 维护完成后更新 last_build_time，避免下次启动增量检测误判需重编译
            nativeUpdateLastBuildTime()
            return true
        }
    }

    /**
     * 用户词典同步（librime 原生 sync）：合并 sync 目录下其他设备的快照进 userdb，
     * 并导出本机快照（TSV 文本，时间戳合并）。持 rimeLock 独占至维护结束，
     * 期间输入查询走 tryLocked 立即降级，不会阻塞 UI。
     * 调用前需保证 installation.yaml 存在且 id 稳定（见 SyncManager.ensureInstallationYaml）。
     */
    fun syncUserData(): Boolean {
        if (!isInitialized) return false
        locked {
            return nativeSyncUserData()
        }
    }

    fun lookupText(text: String): String {
        if (!isInitialized || text.isEmpty()) return ""
        return tryLocked("") {
            if (!nativeHasSession()) return@tryLocked ""
            nativeLookupText(text) ?: ""
        }
    }

    fun getAvailableSchemas(): Array<String> {
        return nativeGetAvailableSchemas() ?: emptyArray()
    }

    /** 读取方案配置字符串项（librime 解析，含 custom.yaml patch 合并后的最终值）。 */
    fun getSchemaString(schemaId: String, key: String): String? {
        if (!isInitialized) return null
        return nativeGetSchemaString(schemaId, key)
    }

    /**
     * 读取当前活动方案的中英文切换键配置（schema + custom.yaml patch 合并后的最终值）。
     * keyName 使用 Rime 配置名，如 Shift_L、Control_R；未配置时返回 null。
     */
    fun getCurrentSchemaSwitchKeyAction(keyName: String): String? {
        if (!isInitialized || keyName.isBlank()) return null
        return tryLocked(null) {
            val schemaId = nativeGetCurrentSchema()?.takeIf { it.isNotBlank() }
                ?: return@tryLocked null
            // ascii_composer 通常来自 default.yaml；方案自身配置仍优先支持覆盖。
            (nativeGetSchemaString(schemaId, "ascii_composer/switch_key/$keyName")
                ?: nativeGetDefaultConfigString("ascii_composer/switch_key/$keyName"))
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }

    /** 读取方案配置列表项（librime 解析，含 custom.yaml patch 合并后的最终值）。 */
    fun getSchemaList(schemaId: String, key: String): List<String> {
        if (!isInitialized) return emptyList()
        return nativeGetSchemaList(schemaId, key)?.toList() ?: emptyList()
    }

    /** 读取方案自带 translator.packs 中声明的个人词库名。 */
    fun getSchemaPacks(schemaId: String): List<String> =
        getSchemaList(schemaId, "translator/packs")

    /** 读取方案 translator.dictionary 主词典名。 */
    fun getSchemaDictionary(schemaId: String): String? =
        getSchemaString(schemaId, "translator/dictionary")

    /** 读取方案 engine/translators 翻译器列表。 */
    fun getSchemaTranslators(schemaId: String): List<String> =
        getSchemaList(schemaId, "engine/translators")

    /** 方案是否有 speller.algebra（固定音节表/自动造词规则）。 */
    fun hasSpellerAlgebra(schemaId: String): Boolean =
        getSchemaList(schemaId, "speller/algebra").isNotEmpty()

    /** 读取方案 custom_phrase.user_dict（自定义短语词典名）。 */
    fun getCustomPhraseDictName(schemaId: String): String? =
        getSchemaString(schemaId, "custom_phrase/user_dict")

    // ── user.yaml 用户状态 ──

    /** 读取 user.yaml 用户状态字符串（如 var/previously_selected_schema）。 */
    fun getUserConfigString(key: String): String? {
        if (!isInitialized) return null
        return nativeGetUserConfigString(key)
    }

    /** 读取 user.yaml 用户状态布尔值（如 var/option/ascii_mode）。 */
    fun getUserConfigBool(key: String): Boolean {
        if (!isInitialized) return false
        return nativeGetUserConfigBool(key)
    }

    /** 写 user.yaml 用户状态字符串（auto_save，自动落盘）。 */
    fun setUserConfigString(key: String, value: String) {
        if (!isInitialized) return
        nativeSetUserConfigString(key, value)
    }

    /** 写 user.yaml 用户状态布尔值（auto_save，自动落盘）。 */
    fun setUserConfigBool(key: String, value: Boolean) {
        if (!isInitialized) return
        nativeSetUserConfigBool(key, value)
    }

    /** 运行时切换 JNI verbose 日志（仅 Debug 构建生效，Release 为空操作）。 */
    fun setVerboseLogging(enabled: Boolean) {
        locked {
            nativeSetVerboseLogging(enabled)
        }
    }

    fun destroy() {
        if (isInitialized) {
            locked {
                nativeDestroy()
                isInitialized = false
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // T9 Processor 公共 API
    // ═══════════════════════════════════════════════════════════

    /**
     * 右选候选：根据候选拼音注释、候选文本与文本长度执行右侧选词（消费计算）。
     * 委托给 T9RightCommitHandler 三层消费算法，判断 full/partial commit。
     * 调频不在此进行——由 Kotlin 在 full commit 上屏后经 [t9Memorize] 单独调用。
     * @param pinyin 候选词的拼音注释（如 "li hua"）
     * @param text 候选词文本（如 "丽华"）；用于 (comment, text) 双条件精确定位，
     *   避免同注释歧义（如 几股/击鼓 均 "ji gu"）时捕获他词的调频码
     * @param textLength 候选词字数（如 "丽华" 为 2）
     */
    fun t9SelectCandidate(pinyin: String, text: String?, textLength: Int): Boolean {
        if (!isInitialized) return false
        return tryLocked(false) {
            if (!nativeHasSession() && !nativeCreateSession()) return@tryLocked false
            nativeT9SelectCandidate(pinyin, text, textLength)
        }
    }

    /** 用户词典写入/回滚公共实现：memorize=true → commits=+1；false → commits=-1。 */
    private fun t9DictOp(text: String, pinyin: String, memorize: Boolean): Boolean {
        if (!isInitialized || text.isEmpty() || pinyin.isEmpty()) return false
        return tryLocked(false) {
            if (!nativeHasSession() && !nativeCreateSession()) return@tryLocked false
            if (memorize) nativeT9Memorize(text, pinyin) else nativeT9Forget(text, pinyin)
        }
    }

    /**
     * 用户词典调频写入：在 full commit（含 partial 拼接）上屏后调用，
     * text 为上屏完整文本，pinyin 为空格分隔的拼音音节串（如 "ji hu a"）。
     * C++ 构造 RIME 原生 DictEntry 写入（key 由 RIME 生成）。
     */
    fun t9Memorize(text: String, pinyin: String): Boolean = t9DictOp(text, pinyin, true)

    /** 用户词典调频回滚（undo 联动）：撤销 right commit 段时调用，commits=-1。 */
    fun t9Forget(text: String, pinyin: String): Boolean = t9DictOp(text, pinyin, false)

    // Native 方法声明
    private external fun nativeInitialize(userDataDir: String, sharedDataDir: String)
    private external fun nativeInstallSignalHandler(userDataDir: String)
    private external fun nativeSetVerboseLogging(enabled: Boolean)
    private external fun nativeCreateSession(): Boolean
    private external fun nativeHasSession(): Boolean
    private external fun nativeIsMaintaining(): Boolean
    private external fun nativeGetCurrentSchema(): String?
    private external fun nativeProcessKey(keycode: Int, mask: Int): Boolean
    private external fun nativeProcessKeyAndGetResult(keycode: Int, mask: Int): RimeProcessResult
    private external fun nativeGetProcessResult(processed: Boolean): RimeProcessResult
    private external fun nativeGetCandidates(): Array<String>?
    private external fun nativeGetCandidatesWithComments(): Array<Array<String>>?
    private external fun nativeGetAllCandidates(maxCount: Int): Array<Array<String>>?
    private external fun nativeGetInput(): String?
    private external fun nativeGetComposition(): RimeComposition
    private external fun nativeSelectCandidate(index: Int): Boolean
    private external fun nativeSelectCandidateByGlobalIndex(index: Int): Boolean
    private external fun nativeDeleteCandidateOnCurrentPage(index: Int): Boolean
    private external fun nativeDeleteCandidateByGlobalIndex(index: Int): Boolean
    private external fun nativePageDown(): Boolean
    private external fun nativePageUp(): Boolean
    private external fun nativeHasNextPage(): Boolean
    private external fun nativeHasPrevPage(): Boolean
    private external fun nativeCommit(): String?
    private external fun nativeClearComposition()
    private external fun nativeSetInput(input: String): Boolean
    private external fun nativeToggleAsciiMode(): Boolean
    private external fun nativeIsAsciiMode(): Boolean
    private external fun nativeSetOption(option: String, value: Boolean)
    private external fun nativeGetOption(option: String): Boolean
    private external fun nativeSwitchSchema(schemaId: String): Boolean
    private external fun nativeEnsureT9SchemaPatches(schemaId: String): Boolean

    /**
     * 部署后主动触发：遍历已部署 schema 目录，对每个方案调用 nativeEnsureT9SchemaPatches。
     * native 内部 Phase 1 会判定并跳过非 T9 方案（幂等无害）；
     * 未启用方案仅写 custom.yaml 无副作用（后续切换到它时即生效）。
     *
     * 注意：此处【不】触发部署。历史上曾根据 native 返回值（词库未编译）在此调用
     * nativeDeploy，导致 initialize 的 initLock 内同步执行大部署，与引擎初始化/
     * 首次部署时序冲突引发 SIGSEGV（新装引导页场景）。词库编译统一由
     * 引导页/设置页的部署流程（build 缺失或用户显式部署）负责。
     */
    private fun ensureT9SchemaPatchesForDeployedSchemas(userDataDir: String) {
        val schemas = File(userDataDir).listFiles { f ->
            f.isFile && f.name.endsWith(".schema.yaml")
        }?.map { it.name.removeSuffix(".schema.yaml") } ?: return
        for (schemaId in schemas) {
            try {
                nativeEnsureT9SchemaPatches(schemaId)
            } catch (_: Throwable) {
                // 单个方案补丁失败不阻断引擎初始化
            }
        }
    }
    private external fun nativeStartMaintenance(full: Boolean): Boolean
    private external fun nativeSyncUserData(): Boolean
    private external fun nativeDeploy(): Boolean
    private external fun nativeDeploySchema(schemaId: String): Boolean
    private external fun nativeLookupText(text: String): String
    private external fun nativeGetAvailableSchemas(): Array<String>?
    private external fun nativeGetSchemaList(schemaId: String, key: String): Array<String>?
    private external fun nativeGetSchemaString(schemaId: String, key: String): String?
    private external fun nativeGetDefaultConfigString(key: String): String?
    private external fun nativeGetUserConfigString(key: String): String?
    private external fun nativeGetUserConfigBool(key: String): Boolean
    private external fun nativeSetUserConfigString(key: String, value: String): Boolean
    private external fun nativeSetUserConfigBool(key: String, value: Boolean): Boolean
    private external fun nativeIsModuleRegistered(moduleName: String): Boolean
    private external fun nativeUpdateLastBuildTime()
    private external fun nativeSetPageSize(schemaId: String, pageSize: Int)
    private external fun nativeDestroy()
    private external fun nativeT9SelectCandidate(pinyin: String, text: String?, textLength: Int): Boolean
    private external fun nativeT9Memorize(text: String, pinyin: String): Boolean
    private external fun nativeT9Forget(text: String, pinyin: String): Boolean
    private external fun nativeT9SelectPinyinDirect(pinyin: String, digitLength: Int): Boolean
    private external fun nativeT9GetLeftPanelState(): String?
    private external fun nativeT9ClearComposition(mode: Int)
    private external fun nativeT9FlushRimeInput()
    private external fun nativeT9GetAndConsumeUndoneRightCommitCount(): Int
    private external fun nativeT9GetRemainingDigits(): String?
    private external fun nativeT9GetFirstSyllableOptions(digits: String, maxResults: Int): String?

    /**
     * 直接选择拼音：传入拼音和对应数字长度，t9_processor 替换 buffer。
     */
    fun t9SelectPinyinDirect(pinyin: String, digitLength: Int): Boolean {
        if (!isInitialized) return false
        return tryLocked(false) {
            if (!nativeHasSession() && !nativeCreateSession()) return@tryLocked false
            nativeT9SelectPinyinDirect(pinyin, digitLength)
        }
    }

    /**
     * 获取 partial commit 后 t9_processor 中剩余的数字串。
     */
    fun t9GetRemainingDigits(): String {
        if (!isInitialized) return ""
        return tryLocked("") {
            nativeT9GetRemainingDigits() ?: ""
        }
    }

    /**
     * 获取左侧面板状态字符串（格式：STATE;PINYIN;DIGIT_LEN;SEL_DIGITS;PANEL_DIGITS;LEFT_LOCKED）。
     * 用于 Kotlin 侧同步 C++ 的选中拼音和面板数字。
     */
    fun t9GetLeftPanelState(): String {
        if (!isInitialized) return "IDLE;;;;;0"
        return tryLocked("IDLE;;;;;0") {
            nativeT9GetLeftPanelState() ?: "IDLE;;;;;0"
        }
    }

    /**
     * 获取并消费 P1 撤销 RightCommit 的计数。
     * 每次 P1 撤销自增 1，每次查询后自减，避免重复消费。
     */
    fun t9GetAndConsumeUndoneRightCommitCount(): Int {
        if (!isInitialized) return 0
        return tryLocked(0) {
            nativeT9GetAndConsumeUndoneRightCommitCount()
        }
    }

    /**
     * 获取首音节候选列表（P3 方案 A：替代 Kotlin T9PinyinMap.firstSyllableOptions）。
     * 返回格式 "pinyin|digitLength" 逗号分隔，如 "ji|2,li|2,j|1,k|1,l|1"。
     */
    fun t9GetFirstSyllableOptions(digits: String, maxResults: Int = 20): List<Pair<String, Int>> {
        if (!isInitialized || digits.isEmpty()) return emptyList()
        return tryLocked(emptyList()) {
            val raw = nativeT9GetFirstSyllableOptions(digits, maxResults) ?: return@tryLocked emptyList()
            if (raw.isEmpty()) return@tryLocked emptyList()
            raw.split(",").mapNotNull { entry ->
                val parts = entry.split("|")
                if (parts.size == 2) {
                    Pair(parts[0], parts[1].toIntOrNull() ?: 1)
                } else null
            }
        }
    }

    /**
     * 清空 T9Processor 全部状态（buffer + undo + state machine）+ RIME composition。
     * @param mode 0=仅清 composition（保留 local state），1=全清（clearAll 场景）
     */
    fun t9ClearComposition(mode: Int) {
        if (!isInitialized) return
        tryLocked(Unit) {
            nativeT9ClearComposition(mode)
        }
    }

    /**
     * 执行 T9Processor 累积的待发送引擎动作（set_input → compose / clear 等）。
     *
     * 异步 flush 模型：T9 处理器在 processKey 内只标记待发送内容（SendToRime），
     * 真正触发引擎 compose 的调用延迟到此处执行。必须由调用方保证在
     * processKey 之后的**后台线程**调用，避免引擎 compose（2-23ms）阻塞 UI 线程。
     */
    fun t9FlushRimeInput() {
        if (!isInitialized) return
        tryLocked(Unit) {
            nativeT9FlushRimeInput()
        }
    }

    fun deploySchema(schemaId: String): Boolean {
        if (!isInitialized) return false
        return nativeDeploySchema(schemaId)
    }

    fun updateLastBuildTime() {
        if (!isInitialized) return
        nativeUpdateLastBuildTime()
    }
}
