package com.kingzcheung.xime.settings

import android.content.Context
import android.content.SharedPreferences
import com.kingzcheung.xime.clipboard.ClipboardImageStore
import com.kingzcheung.xime.plugin.core.runtime.PluginManager

object SettingsPreferences {
    private const val PREFS_NAME = "kime_settings"
    private const val KEY_CURRENT_SCHEMA = "current_schema"
    /** 双写标记：仅新版本双写后置 true，本地值才可信（旧版本只写 rime，本地是过时迁移值） */
    private const val KEY_CURRENT_SCHEMA_DUAL = "current_schema_dual"
    /** 启用方案列表（有序，逗号连接；schema id 为 [a-z0-9_] 无逗号），持久真相。 */
    private const val KEY_ENABLED_SCHEMAS = "enabled_schemas"
    /** 当前活动方案包 id（单活动包模型）；null 表示用户主动卸载了所有包。 */
    private const val KEY_ACTIVE_PACKAGE_ID = "active_package_id"
    /** default.custom.yaml 的 schema_list 是否已迁移进 prefs（一次性）。 */
    private const val KEY_DEFAULT_CUSTOM_MIGRATED = "default_custom_schema_list_migrated"
    private const val KEY_SCHEME_SELECTION_MIGRATED = "scheme_selection_v2_migrated"
    private const val KEY_PENDING_DICT_MERGE = "pending_dict_merge"
    private const val KEY_SETUP_COMPLETED = "setup_completed"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_VERBOSE_LOGGING = "verbose_logging"
    private const val KEY_PLUGIN_DEV_MODE = "plugin_dev_mode"
    /** 按键坐标日志开关（相邻键纠错数据采集，默认关闭）。 */
    private const val KEY_KEY_TAP_LOG = "key_tap_log"
    /** 纠错模型影子模式（只记录"会怎么改"，不改行为，默认关闭）。 */

    private const val KEY_CORRECTOR_ENABLED = "corrector_enabled"

    /** 模糊拼音选中的规则 id 集合；空集合 = 关闭。 */
    private const val KEY_FUZZY_PINYIN_RULES = "fuzzy_pinyin_rules"
    
    private const val KEY_SOUND_ENABLED = "sound_enabled"
    private const val KEY_SOUND_VOLUME = "sound_volume"
    private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
    private const val KEY_VIBRATION_INTENSITY = "vibration_intensity"
    private const val KEY_KEYBOARD_THEME = "keyboard_theme"
    
    const val KEY_SMART_PREDICTION_ENABLED = "smart_prediction_enabled"
    private const val KEY_PREDICTION_MODEL_REPO = "prediction_model_repo"
    private const val KEY_PREDICTION_SELECTED_MODEL = "prediction_selected_model"
    private const val KEY_SPACE_COMMIT_ASSOCIATION = "space_commit_association"
    private const val KEY_ASSOCIATION_SINGLE_MODE = "association_single_mode"
    
    const val KEY_STT_ENABLED = "stt_enabled"
    const val KEY_STT_ONLINE_PLUGIN_ID = "stt_online_plugin_id"
    const val KEY_STT_USE_LOCAL = "stt_use_local"
    const val KEY_STT_KEEP_ENGINE_ALIVE = "stt_keep_engine_alive"
    const val KEY_STT_DEBUG_RECORD = "stt_debug_record"
    
    /** 默认主题 ID，可从 xime.yaml 的 style.color_scheme 初始化。 */
    @JvmStatic
    var defaultKeyboardTheme: String = "lavender_purple"

    /** 默认显示模式，可从 xime.yaml 的 style.dark_mode 初始化。 */
    @JvmStatic
    var defaultDarkMode: Int = 2
    
    const val KEY_LANDSCAPE_SPLIT_KEYBOARD_ENABLED = "landscape_split_keyboard_enabled"
    const val KEY_HARDWARE_KEYBOARD_DETECTION_ENABLED = "hardware_keyboard_detection_enabled"

    private const val KEY_RIME_INSTALLATION_ID = "rime_installation_id"

    private const val KEY_LAST_RIME_SYNC_AT = "last_rime_sync_at"
    private const val KEY_LAST_BACKUP_AT = "last_backup_at"
    private const val KEY_LAST_SYNC_ERROR = "last_sync_error"
    private const val KEY_LAST_SYNC_ERROR_AT = "last_sync_error_at"
    private const val KEY_LAST_BACKUP_ERROR = "last_backup_error"
    private const val KEY_LAST_BACKUP_ERROR_AT = "last_backup_error_at"

    private const val KEY_MODE_CHANGE_TARGET = "mode_change_target"

    fun getModeChangeTargetIsNumber(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_MODE_CHANGE_TARGET, false)
    }

    fun setModeChangeTargetIsNumber(context: Context, isNumber: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MODE_CHANGE_TARGET, isNumber).apply()
    }

    private const val KEY_AUTO_NUMBER_KEYBOARD = "auto_number_keyboard_for_number_fields"

    /** 进入纯数字输入框（号码/验证码等）时自动弹出数字键盘，默认开启。 */
    fun isAutoNumberKeyboardEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_NUMBER_KEYBOARD, true)
    }

    fun setAutoNumberKeyboardEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_NUMBER_KEYBOARD, enabled).apply()
    }

    fun isHardwareKeyboardDetectionEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_HARDWARE_KEYBOARD_DETECTION_ENABLED, true)

    fun setHardwareKeyboardDetectionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HARDWARE_KEYBOARD_DETECTION_ENABLED, enabled).apply()
    }
    
    private const val KEY_LAYOUT_PREFIX = "layout_pref_"
    
    private const val KEY_KEYBOARD_HEIGHT_DP = "keyboard_height_dp"
    private const val KEY_KEYBOARD_HEIGHT_DP_LANDSCAPE = "keyboard_height_dp_landscape"
    const val DEFAULT_KEYBOARD_HEIGHT_PERCENT = 35
    /** 横屏默认高度 = 竖屏高度（长边）× 此百分比。约等于旧值（横屏短边 × 49%），但基数稳定、与竖屏语义统一 */
    const val DEFAULT_KEYBOARD_HEIGHT_PERCENT_LANDSCAPE = 25

    private const val KEY_TOOLBAR_BUTTONS = "toolbar_buttons"
    private val DEFAULT_TOOLBAR_BUTTONS = com.kingzcheung.xime.keyboard.ToolbarButton.DEFAULT_VISIBLE.joinToString(",") { it.id }

    fun getToolbarButtons(context: Context): List<String> {
        val raw = getPrefs(context).getString(KEY_TOOLBAR_BUTTONS, DEFAULT_TOOLBAR_BUTTONS) ?: DEFAULT_TOOLBAR_BUTTONS
        return raw.split(",").filter { it.isNotEmpty() }
    }

    fun setToolbarButtons(context: Context, buttons: List<String>) {
        getPrefs(context).edit().putString(KEY_TOOLBAR_BUTTONS, buttons.joinToString(",")).apply()
    }


    private const val KEY_SCHEMA_IMPORT_WARNING_DISMISSED = "schema_import_warning_dismissed"

    private const val KEY_COMPACT_MODE = "compact_mode"
    private const val KEY_SHOW_CANDIDATE_COMMENTS = "show_candidate_comments"
    private const val KEY_INPUT_TEXT_LOCATION = "input_text_location"
    private const val KEY_PAGE_SIZE = "page_size"
    private const val KEY_CANDIDATE_TEXT_SIZE = "candidate_text_size"
    const val INPUT_TEXT_INPUT_BOX = "input_box"
    const val INPUT_TEXT_CANDIDATE_BAR = "candidate_bar"
    const val DEFAULT_PAGE_SIZE = 20 // 手机候选栏每页候选词数；schema 里的 page_size 来自 PC 版（5），太短，默认用 20

    fun isCompactModeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_COMPACT_MODE, true)
    }

    fun setCompactModeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_COMPACT_MODE, enabled).apply()
    }

    fun showCandidateComments(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_CANDIDATE_COMMENTS, true)
    }

    fun setShowCandidateComments(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_CANDIDATE_COMMENTS, show).apply()
    }

    fun getInputTextLocation(context: Context): String {
        return getPrefs(context).getString(KEY_INPUT_TEXT_LOCATION, INPUT_TEXT_CANDIDATE_BAR) ?: INPUT_TEXT_CANDIDATE_BAR
    }

    fun setInputTextLocation(context: Context, location: String) {
        getPrefs(context).edit().putString(KEY_INPUT_TEXT_LOCATION, location).apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun getPrefsPublic(context: Context): SharedPreferences {
        return getPrefs(context)
    }
    
    fun getCurrentSchema(context: Context): String {
        val prefs = getPrefs(context)
        // 双写标记已置：本地值是新版本写入的最新方案，进程重建后 rime 未初始化也能恢复
        if (prefs.getBoolean(KEY_CURRENT_SCHEMA_DUAL, false)) {
            val local = prefs.getString(KEY_CURRENT_SCHEMA, null)
            if (!local.isNullOrBlank()) return local
        }
        // 未双写（旧版本升级/首次）：rime user.yaml 为权威，本地仅作迁移兜底
        val fromRime = try {
            if (com.kingzcheung.xime.rime.RimeEngine.isInitialized()) {
                com.kingzcheung.xime.rime.RimeEngine.getInstance()
                    .getUserConfigString("var/previously_selected_schema")
            } else null
        } catch (_: Throwable) {
            null
        }
        if (!fromRime.isNullOrBlank()) {
            // 回写 SharedPreferences 并置双写标记，之后本地优先
            prefs.edit()
                .putString(KEY_CURRENT_SCHEMA, fromRime)
                .putBoolean(KEY_CURRENT_SCHEMA_DUAL, true)
                .apply()
            return fromRime
        }
        val legacy = prefs.getString(KEY_CURRENT_SCHEMA, null)
        if (!legacy.isNullOrBlank()) return legacy
        return "wubi86"
    }

    fun setCurrentSchema(context: Context, schemaId: String) {
        // 双写：SharedPreferences 保证进程重建后不依赖 rime 即可恢复，
        // librime user.yaml 保持引擎侧状态一致（switchSchema 后由 librime 持久化）
        getPrefs(context).edit()
            .putString(KEY_CURRENT_SCHEMA, schemaId)
            .putBoolean(KEY_CURRENT_SCHEMA_DUAL, true)
            .apply()
        try {
            if (com.kingzcheung.xime.rime.RimeEngine.isInitialized()) {
                com.kingzcheung.xime.rime.RimeEngine.getInstance()
                    .setUserConfigString("var/previously_selected_schema", schemaId)
            }
        } catch (_: Throwable) {
        }
    }
    
    /** 启用方案列表（有序，逗号连接；schema id 为 [a-z0-9_] 无逗号）。null = 从未写入。 */
    fun getEnabledSchemaList(context: Context): List<String>? {
        if (!getPrefs(context).contains(KEY_ENABLED_SCHEMAS)) return null
        return getPrefs(context).getString(KEY_ENABLED_SCHEMAS, "")
            ?.split(',')?.filter { it.isNotEmpty() }
    }

    fun setEnabledSchemaList(context: Context, schemas: List<String>) {
        getPrefs(context).edit().putString(KEY_ENABLED_SCHEMAS, schemas.joinToString(",")).apply()
    }

    /** 当前活动方案包；null 表示没有活动包（用户主动卸载了所有包）。 */
    fun getActivePackageId(context: Context): String? =
        getPrefs(context).getString(KEY_ACTIVE_PACKAGE_ID, null)

    fun setActivePackageId(context: Context, packageId: String?) {
        getPrefs(context).edit().apply {
            if (packageId == null) remove(KEY_ACTIVE_PACKAGE_ID)
            else putString(KEY_ACTIVE_PACKAGE_ID, packageId)
        }.apply()
    }

    fun isDefaultCustomMigrated(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DEFAULT_CUSTOM_MIGRATED, false)
    }

    fun setDefaultCustomMigrated(context: Context, migrated: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DEFAULT_CUSTOM_MIGRATED, migrated).apply()
    }

    /** 旧版"无 schema_list 时猜第一个方案"的选择是否已作废（一次性迁移）。 */
    fun isSchemeSelectionMigrated(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SCHEME_SELECTION_MIGRATED, false)
    }

    fun setSchemeSelectionMigrated(context: Context, migrated: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SCHEME_SELECTION_MIGRATED, migrated).apply()
    }

    /**
     * 待合并的自造词快照标记：恢复备份时若引擎未就绪（冷启动直接进设置页），
     * 快照已落盘但无法调 `syncUserData()` 合并——由 IME 服务在引擎就绪后补做。
     */
    fun isPendingDictMerge(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PENDING_DICT_MERGE, false)
    }

    fun setPendingDictMerge(context: Context, pending: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PENDING_DICT_MERGE, pending).apply()
    }

    /** 调试 verbose 日志总开关（仅 Debug 构建生效，Release 恒关闭）。 */
    fun isVerboseLoggingEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_VERBOSE_LOGGING, true)
    }

    fun setVerboseLoggingEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VERBOSE_LOGGING, enabled).apply()
    }

    /**
     * 插件开发模式：开启后允许 adb 通道热安装插件（xipm dev，仅约束 release 包；
     * debug 包 debuggable 本就全量开放，门禁直接放行）。入口隐藏（设置 → 关于 →
     * 连点设备信息 7 次解锁），默认关闭；关闭时热安装 Activity 秒退，adb 无法注入
     * 插件代码。状态持久化（区别于解锁 flag），否则每次冷启动都要重敲一遍才能用
     * xipm dev。
     */
    fun isPluginDevModeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PLUGIN_DEV_MODE, false)
    }

    fun setPluginDevModeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PLUGIN_DEV_MODE, enabled).apply()
    }

    /** 按键坐标日志（相邻键纠错采集），默认关闭。 */
    fun isKeyTapLogEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_KEY_TAP_LOG, false)
    }

    fun setKeyTapLogEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_KEY_TAP_LOG, enabled).apply()
    }

    /** 智能纠错（相邻键纠错候选，默认关闭）。 */
    fun isCorrectorEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CORRECTOR_ENABLED, false)
    }

    fun setCorrectorEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CORRECTOR_ENABLED, enabled).apply()
    }

    /** 模糊拼音规则 id 集合（空集合 = 关闭）。 */
    fun getFuzzyPinyinRules(context: Context): Set<String> =
        getPrefs(context).getStringSet(KEY_FUZZY_PINYIN_RULES, emptySet())
            ?.toSet()
            .orEmpty()

    fun setFuzzyPinyinRules(context: Context, rules: Set<String>) {
        getPrefs(context).edit()
            .putStringSet(KEY_FUZZY_PINYIN_RULES, rules.toSet())
            .apply()
    }

    fun isSetupCompleted(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SETUP_COMPLETED, false)
    }

    fun setSetupCompleted(context: Context, completed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SETUP_COMPLETED, completed).apply()
    }

    fun getDarkMode(context: Context): Int {
        // 0 = 浅色, 1 = 深色, 2 = 跟随系统（默认）
        return getPrefs(context).getInt(KEY_DARK_MODE, defaultDarkMode)
    }
    
    fun setDarkMode(context: Context, mode: Int) {
        getPrefs(context).edit().putInt(KEY_DARK_MODE, mode).apply()
    }
    
    fun isSoundEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SOUND_ENABLED, true)
    }
    
    fun setSoundEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }
    
    fun getSoundVolume(context: Context): Int {
        return getPrefs(context).getInt(KEY_SOUND_VOLUME, 20)
    }
    
    fun setSoundVolume(context: Context, volume: Int) {
        getPrefs(context).edit().putInt(KEY_SOUND_VOLUME, volume).apply()
    }
    
    fun isVibrationEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_VIBRATION_ENABLED, true)
    }
    
    fun setVibrationEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VIBRATION_ENABLED, enabled).apply()
    }
    
    fun getVibrationIntensity(context: Context): Int {
        return getPrefs(context).getInt(KEY_VIBRATION_INTENSITY, 30)
    }
    
    fun setVibrationIntensity(context: Context, intensity: Int) {
        getPrefs(context).edit().putInt(KEY_VIBRATION_INTENSITY, intensity).apply()
    }


    private const val KEY_HAPTIC_MODE = "haptic_mode"
    private const val KEY_HAPTIC_ON_KEYUP = "haptic_on_keyup"
    private const val KEY_VIBRATION_PRESS_DURATION = "vibration_press_duration"
    private const val KEY_VIBRATION_LONG_PRESS_DURATION = "vibration_long_press_duration"
    private const val KEY_VIBRATION_PRESS_AMPLITUDE = "vibration_press_amplitude"
    private const val KEY_VIBRATION_LONG_PRESS_AMPLITUDE = "vibration_long_press_amplitude"

    fun getHapticMode(context: Context): String {
        return getPrefs(context).getString(KEY_HAPTIC_MODE, "following_system") ?: "following_system"
    }

    fun setHapticMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_HAPTIC_MODE, mode).apply()
    }

    fun isHapticOnKeyUp(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAPTIC_ON_KEYUP, false)
    }

    fun setHapticOnKeyUp(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HAPTIC_ON_KEYUP, enabled).apply()
    }

    fun getVibrationPressDuration(context: Context): Int {
        return getPrefs(context).getInt(KEY_VIBRATION_PRESS_DURATION, 0)
    }

    fun setVibrationPressDuration(context: Context, duration: Int) {
        getPrefs(context).edit().putInt(KEY_VIBRATION_PRESS_DURATION, duration).apply()
    }

    fun getVibrationLongPressDuration(context: Context): Int {
        return getPrefs(context).getInt(KEY_VIBRATION_LONG_PRESS_DURATION, 0)
    }

    fun setVibrationLongPressDuration(context: Context, duration: Int) {
        getPrefs(context).edit().putInt(KEY_VIBRATION_LONG_PRESS_DURATION, duration).apply()
    }

    fun getVibrationPressAmplitude(context: Context): Int {
        return getPrefs(context).getInt(KEY_VIBRATION_PRESS_AMPLITUDE, 0)
    }

    fun setVibrationPressAmplitude(context: Context, amplitude: Int) {
        getPrefs(context).edit().putInt(KEY_VIBRATION_PRESS_AMPLITUDE, amplitude).apply()
    }

    fun getVibrationLongPressAmplitude(context: Context): Int {
        return getPrefs(context).getInt(KEY_VIBRATION_LONG_PRESS_AMPLITUDE, 0)
    }

    fun setVibrationLongPressAmplitude(context: Context, amplitude: Int) {
        getPrefs(context).edit().putInt(KEY_VIBRATION_LONG_PRESS_AMPLITUDE, amplitude).apply()
    }

    fun getKeyboardTheme(context: Context): String {
        return getPrefs(context).getString(KEY_KEYBOARD_THEME, defaultKeyboardTheme) ?: defaultKeyboardTheme
    }
    
    fun setKeyboardTheme(context: Context, themeId: String) {
        getPrefs(context).edit().putString(KEY_KEYBOARD_THEME, themeId).apply()
    }

    
    fun isPluginEnabled(context: Context, pluginId: String): Boolean {
        val prefs = getPrefs(context)
        val key = "plugin_enabled_$pluginId"
        
        if (prefs.contains(key)) {
            return prefs.getBoolean(key, false)
        }
        
        val pluginInfo = PluginManager.getAllInstallPlugins().find { it.id == pluginId }
        return pluginInfo?.enabled ?: true
    }
    
    fun setPluginEnabled(context: Context, pluginId: String, enabled: Boolean) {
        getPrefs(context).edit().putBoolean("plugin_enabled_$pluginId", enabled).apply()
    }
    
    fun isSmartPredictionEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SMART_PREDICTION_ENABLED, false)
    }
    
    fun setSmartPredictionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SMART_PREDICTION_ENABLED, enabled).apply()
    }
    
    fun getPredictionModelRepo(context: Context): String {
        return getPrefs(context).getString(KEY_PREDICTION_MODEL_REPO, "https://www.modelscope.cn/models/bikeand/predictive-text-small") 
            ?: "https://www.modelscope.cn/models/bikeand/predictive-text-small"
    }
    
    fun setPredictionModelRepo(context: Context, repo: String) {
        getPrefs(context).edit().putString(KEY_PREDICTION_MODEL_REPO, repo).apply()
    }
    
    fun getPredictionSelectedModel(context: Context): String {
        return getPrefs(context).getString(KEY_PREDICTION_SELECTED_MODEL, "predictive-text-small")
            ?: "predictive-text-small"
    }
    
    fun setPredictionSelectedModel(context: Context, modelId: String) {
        getPrefs(context).edit().putString(KEY_PREDICTION_SELECTED_MODEL, modelId).apply()
    }

    /** 空格上屏联想候选：有联想候选时按空格键直接上屏第一个联想词（而非空格字符） */
    fun isSpaceCommitAssociationEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SPACE_COMMIT_ASSOCIATION, false)
    }

    fun setSpaceCommitAssociationEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SPACE_COMMIT_ASSOCIATION, enabled).apply()
    }

    /**
     * 联想模式：false = 连续联想（联想上屏后继续推理，可连续上屏），true = 单次联想（只推理一次）。
     * 默认连续（保持历史行为）。
     */
    fun isSingleAssociationMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ASSOCIATION_SINGLE_MODE, false)
    }

    fun setAssociationSingleMode(context: Context, single: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ASSOCIATION_SINGLE_MODE, single).apply()
    }
    
    fun isSttEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STT_ENABLED, false)
    }
    
    fun setSttEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STT_ENABLED, enabled).apply()
    }

    fun getSttOnlinePluginId(context: Context): String {
        return getPrefs(context).getString(KEY_STT_ONLINE_PLUGIN_ID, "") ?: ""
    }

    fun setSttOnlinePluginId(context: Context, pluginId: String) {
        getPrefs(context).edit().putString(KEY_STT_ONLINE_PLUGIN_ID, pluginId).apply()
    }

    /** 语音转文本是否使用本地（离线）识别引擎。 */
    fun isSttUseLocal(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STT_USE_LOCAL, false)
    }

    fun setSttUseLocal(context: Context, useLocal: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STT_USE_LOCAL, useLocal).apply()
    }

    /**
     * 语音结束后是否保持识别引擎常驻（不随会话结束销毁）。
     * 开启后闲置一段时间再使用无需重新加载引擎，"开始聆听"响应快。
     * 仅本地（离线）模式生效：模型本就常驻 :asr 进程，保留 wrapper 代价极小；
     * 在线插件常驻需保持 WebSocket 长连接（耗电、占用服务端资源），不提供常驻。
     */
    fun isSttKeepEngineAlive(context: Context): Boolean {
        return isSttUseLocal(context) &&
            getPrefs(context).getBoolean(KEY_STT_KEEP_ENGINE_ALIVE, false)
    }

    fun setSttKeepEngineAlive(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STT_KEEP_ENGINE_ALIVE, enabled).apply()
    }

    /** 是否把语音识别期间的录音写入文件（调试用）。 */
    fun isSttDebugRecord(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_STT_DEBUG_RECORD, false)
    }

    fun setSttDebugRecord(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_STT_DEBUG_RECORD, enabled).apply()
    }

    // ---- 插件网络授权（per plugin per host） ----

    /** 插件已获用户授权的域名集合。 */
    fun getPluginAuthorizedHosts(context: Context, pluginId: String): Set<String> {
        val raw = getPrefs(context).getString("plugin_net_auth_$pluginId", "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    /** 授权插件访问指定域名。 */
    fun authorizePluginHost(context: Context, pluginId: String, host: String) {
        val hosts = getPluginAuthorizedHosts(context, pluginId) + host
        val pending = getPluginPendingHosts(context, pluginId) - host
        getPrefs(context).edit()
            .putString("plugin_net_auth_$pluginId", hosts.joinToString(","))
            .putString("plugin_net_pending_$pluginId", pending.joinToString(","))
            .apply()
    }

    /** 撤销插件对指定域名的授权。 */
    fun revokePluginHost(context: Context, pluginId: String, host: String) {
        val hosts = getPluginAuthorizedHosts(context, pluginId) - host
        getPrefs(context).edit()
            .putString("plugin_net_auth_$pluginId", hosts.joinToString(","))
            .apply()
    }

    /** 插件运行中被拒绝访问、待用户授权的域名集合（供 UI 显眼展示并引导授权）。 */
    fun getPluginPendingHosts(context: Context, pluginId: String): Set<String> {
        val raw = getPrefs(context).getString("plugin_net_pending_$pluginId", "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    /** 记录插件运行中被拒绝访问的域名（去重；返回 true 表示首次记录）。 */
    fun addPluginPendingHost(context: Context, pluginId: String, host: String): Boolean {
        val pending = getPluginPendingHosts(context, pluginId)
        if (host in pending) return false
        getPrefs(context).edit()
            .putString("plugin_net_pending_$pluginId", (pending + host).joinToString(","))
            .apply()
        return true
    }

    /** 清除插件的全部待授权记录。 */
    fun clearPluginPendingHosts(context: Context, pluginId: String) {
        getPrefs(context).edit().putString("plugin_net_pending_$pluginId", "").apply()
    }
    
    /** 横屏时是否使用分体键盘，默认关闭，由用户按需开启。 */
    fun isLandscapeSplitKeyboardEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_LANDSCAPE_SPLIT_KEYBOARD_ENABLED, false)
    }

    fun setLandscapeSplitKeyboardEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_LANDSCAPE_SPLIT_KEYBOARD_ENABLED, enabled).apply()
    }

    /**
     * rime installation.yaml 的稳定安装 id（宿主接管生成）：
     * 部署会删除 installation.yaml，词库同步前以本 id 重建，保证同步快照
     * 目录（sync/<installation_id>/）不随部署轮换。
     */
    fun getRimeInstallationId(context: Context): String {
        val prefs = getPrefs(context)
        prefs.getString(KEY_RIME_INSTALLATION_ID, null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_RIME_INSTALLATION_ID, id).apply()
        return id
    }

    /** 上次词库同步完成时间（毫秒时间戳，0=从未同步）。 */
    fun getLastRimeSyncAt(context: Context): Long =
        getPrefs(context).getLong(KEY_LAST_RIME_SYNC_AT, 0L)

    fun setLastRimeSyncAt(context: Context, at: Long) {
        getPrefs(context).edit().putLong(KEY_LAST_RIME_SYNC_AT, at).apply()
    }

    /** 上次生成完整备份包的时间（云端立即备份或本地导出成功时更新；毫秒时间戳，0=从未）。 */
    fun getLastBackupAt(context: Context): Long =
        getPrefs(context).getLong(KEY_LAST_BACKUP_AT, 0L)

    fun setLastBackupAt(context: Context, at: Long) {
        getPrefs(context).edit().putLong(KEY_LAST_BACKUP_AT, at).apply()
    }

    /**
     * 最近一次云端词条同步的失败原因（null=没有未消除的失败；成功后清空）。
     *
     * 目的：失败不再只是 snackbar 一闪而过——回到「同步与备份」页仍能看到"上次失败 + 重试"。
     */
    fun getLastSyncError(context: Context): String? =
        getPrefs(context).getString(KEY_LAST_SYNC_ERROR, null)

    /** 失败发生时间（毫秒；0=无）。 */
    fun getLastSyncErrorAt(context: Context): Long =
        getPrefs(context).getLong(KEY_LAST_SYNC_ERROR_AT, 0L)

    fun setLastSyncError(context: Context, message: String?) =
        setOpError(context, KEY_LAST_SYNC_ERROR, KEY_LAST_SYNC_ERROR_AT, message)

    /** 最近一次云端完整备份的失败原因（语义同 [getLastSyncError]）。 */
    fun getLastBackupError(context: Context): String? =
        getPrefs(context).getString(KEY_LAST_BACKUP_ERROR, null)

    fun getLastBackupErrorAt(context: Context): Long =
        getPrefs(context).getLong(KEY_LAST_BACKUP_ERROR_AT, 0L)

    fun setLastBackupError(context: Context, message: String?) =
        setOpError(context, KEY_LAST_BACKUP_ERROR, KEY_LAST_BACKUP_ERROR_AT, message)

    private fun setOpError(context: Context, key: String, atKey: String, message: String?) {
        val editor = getPrefs(context).edit()
        if (message.isNullOrBlank()) {
            editor.remove(key).remove(atKey)
        } else {
            editor.putString(key, message).putLong(atKey, System.currentTimeMillis())
        }
        editor.apply()
    }
    
    /** 获取方案偏好的键盘布局，默认全键盘 */
    fun getLayoutPreference(context: Context, schemaId: String): String {
        return getPrefs(context).getString("$KEY_LAYOUT_PREFIX$schemaId", "full") ?: "full"
    }
    
    /** 保存方案偏好的键盘布局 */
    fun setLayoutPreference(context: Context, schemaId: String, layout: String) {
        getPrefs(context).edit().putString("$KEY_LAYOUT_PREFIX$schemaId", layout).apply()
    }

    /** 方案卸载时清除该方案的布局偏好，避免同名方案重装后继承旧布局。 */
    fun removeLayoutPreference(context: Context, schemaId: String) {
        getPrefs(context).edit().remove("$KEY_LAYOUT_PREFIX$schemaId").apply()
    }
    
    fun getKeyboardHeightDp(context: Context): Int {
        return getKeyboardHeightDp(context, false)
    }

    fun getKeyboardHeightDp(context: Context, isLandscape: Boolean): Int {
        val key = if (isLandscape) KEY_KEYBOARD_HEIGHT_DP_LANDSCAPE else KEY_KEYBOARD_HEIGHT_DP
        val stored = getPrefs(context).getInt(key, -1)
        if (stored > 0) return stored
        // 不做跨方向回退：竖屏高度（可达屏高 60%）放到横屏短边上必然超出屏幕
        // （曾表现为"横屏键盘高得离谱"），横竖屏各自用自己的键 + 自己的默认值
        return getDefaultKeyboardHeightDp(context, isLandscape)
    }

    fun setKeyboardHeightDp(context: Context, heightDp: Int, isLandscape: Boolean = false) {
        val key = if (isLandscape) KEY_KEYBOARD_HEIGHT_DP_LANDSCAPE else KEY_KEYBOARD_HEIGHT_DP
        getPrefs(context).edit().putInt(key, heightDp).apply()
    }

    fun getDefaultKeyboardHeightDp(context: Context, isLandscape: Boolean = false): Int {
        val config = context.resources.configuration
        if (!isLandscape) return config.screenHeightDp * DEFAULT_KEYBOARD_HEIGHT_PERCENT / 100
        // 横屏以竖屏高度（长边）为基数：横屏短边随宽高比/系统栏波动大，
        // 长边是设备稳定值，且与悬浮模式 fallback（portraitScreenHeightDp × 百分比）语义一致
        val portraitHeightDp = maxOf(config.screenWidthDp, config.screenHeightDp)
        return portraitHeightDp * DEFAULT_KEYBOARD_HEIGHT_PERCENT_LANDSCAPE / 100
    }

    private const val KEY_KEYBOARD_BOTTOM_PADDING_DP = "keyboard_bottom_padding_dp"
    private const val DEFAULT_KEYBOARD_BOTTOM_PADDING_DP = 0

    fun getKeyboardBottomPaddingDp(context: Context): Int {
        return getPrefs(context).getInt(KEY_KEYBOARD_BOTTOM_PADDING_DP, DEFAULT_KEYBOARD_BOTTOM_PADDING_DP)
    }

    fun setKeyboardBottomPaddingDp(context: Context, paddingDp: Int) {
        getPrefs(context).edit().putInt(KEY_KEYBOARD_BOTTOM_PADDING_DP, paddingDp).apply()
    }

    /** 键盘贴底显示：忽略底部系统 inset，把键盘内容下移到屏幕底边（隐藏导航栏/手势条时使用）。 */
    private const val KEY_KEYBOARD_FLUSH_BOTTOM = "keyboard_flush_bottom"

    fun isKeyboardFlushBottom(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_KEYBOARD_FLUSH_BOTTOM, false)
    }

    fun setKeyboardFlushBottom(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_KEYBOARD_FLUSH_BOTTOM, enabled).apply()
    }

    /** 键盘左边距（dp）：>0 时键盘左缘内收（宽度调节；左右独立，可整体偏移）。 */
    private const val KEY_KEYBOARD_MARGIN_START_DP = "keyboard_margin_start_dp"

    /** 键盘右边距（dp）。 */
    private const val KEY_KEYBOARD_MARGIN_END_DP = "keyboard_margin_end_dp"

    fun getKeyboardMarginStartDp(context: Context): Int {
        return getPrefs(context).getInt(KEY_KEYBOARD_MARGIN_START_DP, 0)
    }

    fun setKeyboardMarginStartDp(context: Context, marginDp: Int) {
        getPrefs(context).edit().putInt(KEY_KEYBOARD_MARGIN_START_DP, marginDp).apply()
    }

    fun getKeyboardMarginEndDp(context: Context): Int {
        return getPrefs(context).getInt(KEY_KEYBOARD_MARGIN_END_DP, 0)
    }

    fun setKeyboardMarginEndDp(context: Context, marginDp: Int) {
        getPrefs(context).edit().putInt(KEY_KEYBOARD_MARGIN_END_DP, marginDp).apply()
    }

    fun isSchemaImportWarningDismissed(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SCHEMA_IMPORT_WARNING_DISMISSED, false)
    }

    fun setSchemaImportWarningDismissed(context: Context, dismissed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SCHEMA_IMPORT_WARNING_DISMISSED, dismissed).apply()
    }

    // ── 悬浮键盘设置 ──

    private const val KEY_FLOATING_MODE = "floating_mode"
    private const val KEY_FLOATING_MODE_LANDSCAPE = "floating_mode_landscape"
    private const val KEY_FLOATING_OFFSET_X = "floating_offset_x"
    private const val KEY_FLOATING_OFFSET_X_LANDSCAPE = "floating_offset_x_landscape"
    private const val KEY_FLOATING_OFFSET_Y = "floating_offset_y"
    private const val KEY_FLOATING_OFFSET_Y_LANDSCAPE = "floating_offset_y_landscape"

    fun isFloatingMode(context: Context, isLandscape: Boolean = false): Boolean {
        val key = if (isLandscape) KEY_FLOATING_MODE_LANDSCAPE else KEY_FLOATING_MODE
        return getPrefs(context).getBoolean(key, false)
    }

    fun setFloatingMode(context: Context, enabled: Boolean, isLandscape: Boolean = false) {
        val key = if (isLandscape) KEY_FLOATING_MODE_LANDSCAPE else KEY_FLOATING_MODE
        getPrefs(context).edit().putBoolean(key, enabled).apply()
    }

    fun getFloatingOffsetX(context: Context, isLandscape: Boolean = false): Int {
        val key = if (isLandscape) KEY_FLOATING_OFFSET_X_LANDSCAPE else KEY_FLOATING_OFFSET_X
        return getPrefs(context).getInt(key, 0)
    }

    fun setFloatingOffsetX(context: Context, offset: Int, isLandscape: Boolean = false) {
        val key = if (isLandscape) KEY_FLOATING_OFFSET_X_LANDSCAPE else KEY_FLOATING_OFFSET_X
        getPrefs(context).edit().putInt(key, offset).apply()
    }

    fun getFloatingOffsetY(context: Context, isLandscape: Boolean = false): Int {
        val key = if (isLandscape) KEY_FLOATING_OFFSET_Y_LANDSCAPE else KEY_FLOATING_OFFSET_Y
        return getPrefs(context).getInt(key, 0)
    }

    fun setFloatingOffsetY(context: Context, offset: Int, isLandscape: Boolean = false) {
        val key = if (isLandscape) KEY_FLOATING_OFFSET_Y_LANDSCAPE else KEY_FLOATING_OFFSET_Y
        getPrefs(context).edit().putInt(key, offset).apply()
    }

    fun getPageSize(context: Context): Int {
        return getPrefs(context).getInt(KEY_PAGE_SIZE, DEFAULT_PAGE_SIZE)
    }

    fun setPageSize(context: Context, pageSize: Int) {
        getPrefs(context).edit().putInt(KEY_PAGE_SIZE, pageSize).apply()
    }

    const val DEFAULT_CANDIDATE_TEXT_SIZE = 19

    fun getCandidateTextSize(context: Context): Int {
        return getPrefs(context).getInt(KEY_CANDIDATE_TEXT_SIZE, DEFAULT_CANDIDATE_TEXT_SIZE)
    }

    fun setCandidateTextSize(context: Context, size: Int) {
        getPrefs(context).edit().putInt(KEY_CANDIDATE_TEXT_SIZE, size).apply()
    }

    const val KEY_CLIPBOARD_SYNC_ENABLED = "clipboard_sync_enabled"
    const val KEY_CLIPBOARD_SYNC_PLUGIN_ID = "clipboard_sync_plugin_id"

    fun isClipboardSyncEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CLIPBOARD_SYNC_ENABLED, false)
    }

    fun setClipboardSyncEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CLIPBOARD_SYNC_ENABLED, enabled).apply()
    }

    fun getClipboardSyncPluginId(context: Context): String {
        return getPrefs(context).getString(KEY_CLIPBOARD_SYNC_PLUGIN_ID, "") ?: ""
    }

    fun setClipboardSyncPluginId(context: Context, pluginId: String) {
        getPrefs(context).edit().putString(KEY_CLIPBOARD_SYNC_PLUGIN_ID, pluginId).apply()
    }

    // ── 剪贴板图片（Phase 2：采集开关 + 单张上限可配） ──────────────

    /** 是否采集复制到剪贴板的图片（默认开，见 docs/clipboard-image-plan.md 决策 D1）。 */
    const val KEY_CLIPBOARD_IMAGE_CAPTURE = "clipboard_image_capture"

    private const val KEY_CLIPBOARD_IMAGE_MAX_MB = "clipboard_image_max_mb"

    /** 单张上限可选项区间（设置页滑块与读取校验共用，避免脏值导致滑块越界）。 */
    val CLIPBOARD_IMAGE_MAX_MB_RANGE = 1..20

    fun isClipboardImageCaptureEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CLIPBOARD_IMAGE_CAPTURE, true)
    }

    fun setClipboardImageCaptureEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CLIPBOARD_IMAGE_CAPTURE, enabled).apply()
    }

    /**
     * 单张图片上限（MB），默认 [ClipboardImageStore.MAX_IMAGE_BYTES]。
     * 其余限制（最长边/保留数量/总容量）为内置常量，不提供设置项。
     */
    fun getClipboardImageMaxMb(context: Context): Int {
        val default = (ClipboardImageStore.MAX_IMAGE_BYTES / 1024 / 1024).toInt()
        return getPrefs(context).getInt(KEY_CLIPBOARD_IMAGE_MAX_MB, default)
            .coerceIn(CLIPBOARD_IMAGE_MAX_MB_RANGE)
    }

    fun setClipboardImageMaxMb(context: Context, mb: Int) {
        getPrefs(context).edit()
            .putInt(KEY_CLIPBOARD_IMAGE_MAX_MB, mb.coerceIn(CLIPBOARD_IMAGE_MAX_MB_RANGE))
            .apply()
    }

    // ── 敏感剪贴板（API 33+ EXTRA_IS_SENSITIVE，默认不入库） ──────────

    /** 是否记录带敏感标记的剪贴板（密码、卡号等，默认关）。 */
    private const val KEY_CLIPBOARD_SENSITIVE_CAPTURE = "clipboard_sensitive_capture"

    fun isClipboardSensitiveCaptureEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CLIPBOARD_SENSITIVE_CAPTURE, false)
    }

    fun setClipboardSensitiveCaptureEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_CLIPBOARD_SENSITIVE_CAPTURE, enabled).apply()
    }

    const val KEY_BACKUP_PLUGIN_ID = "backup_plugin_id"

    fun getBackupPluginId(context: Context): String {
        return getPrefs(context).getString(KEY_BACKUP_PLUGIN_ID, "") ?: ""
    }

    fun setBackupPluginId(context: Context, pluginId: String) {
        getPrefs(context).edit().putString(KEY_BACKUP_PLUGIN_ID, pluginId).apply()
    }

    // ── 键盘布局市场：当前已应用的布局 ──────────────────────────

    private const val KEY_APPLIED_LAYOUT_ID = "applied_layout_id"
    private const val KEY_APPLIED_LAYOUT_VERSION = "applied_layout_version"
    private const val KEY_APPLIED_LAYOUT_FILES = "applied_layout_files"

    /** 已应用布局的 id（空表示未应用任何市场布局）。 */
    fun getAppliedLayoutId(context: Context): String =
        getPrefs(context).getString(KEY_APPLIED_LAYOUT_ID, "") ?: ""

    /** 已应用布局的版本（用于「有更新」提示）。 */
    fun getAppliedLayoutVersion(context: Context): String =
        getPrefs(context).getString(KEY_APPLIED_LAYOUT_VERSION, "") ?: ""

    /** 已应用布局释放到 rime 的相对文件清单（恢复默认时回收）。 */
    fun getAppliedLayoutFiles(context: Context): List<String> {
        val raw = getPrefs(context).getString(KEY_APPLIED_LAYOUT_FILES, "") ?: ""
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setAppliedLayout(context: Context, id: String, version: String, files: List<String>) {
        getPrefs(context).edit()
            .putString(KEY_APPLIED_LAYOUT_ID, id)
            .putString(KEY_APPLIED_LAYOUT_VERSION, version)
            .putString(KEY_APPLIED_LAYOUT_FILES, files.joinToString("\n"))
            .apply()
    }

    fun clearAppliedLayout(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_APPLIED_LAYOUT_ID)
            .remove(KEY_APPLIED_LAYOUT_VERSION)
            .remove(KEY_APPLIED_LAYOUT_FILES)
            .apply()
    }
}
