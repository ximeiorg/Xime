package com.kingzcheung.xime

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.kingzcheung.xime.correction.CorrectionPriors
import com.kingzcheung.xime.correction.KeyTapLogger
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.PluginConfigStoreImpl
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.model.ModelRuntime
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class XimeApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(16 * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_cache"))
                    .maxSizeBytes(32L * 1024 * 1024)
                    .build()
            }
            .build()
    }
    
    companion object {
        private const val TAG = "XimeApplication"
    }
    
    private val applicationScope = CoroutineScope(Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()

        FileLogger.init(this)
        KeyTapLogger.init(this)
        KeyTapLogger.enabled = SettingsPreferences.isKeyTapLogEnabled(this)
        // 邻键误触纠错（librime 插件版）：初始化先验推送器
        CorrectionPriors.init(this)
        CorrectionPriors.enabled = SettingsPreferences.isCorrectorEnabled(this)
        // 启动自检：确认生效 schema 里挂上了 correction_translator（写文件日志）
        if (CorrectionPriors.enabled) {
            Thread {
                runCatching { Thread.sleep(4000) }
                runCatching { CorrectionPriors.selfCheck(SettingsPreferences.getCurrentSchema(this)) }
            }.start()
        }
        AppFonts.initialize(this)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                FileLogger.e("CrashHandler", "Uncaught exception on thread: ${thread.name}", throwable)
                FileLogger.flushNow()
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        val isDebug = BuildConfig.DEBUG
        if (isDebug) {
            // 插件 console 日志落盘（xipm dev/logs 轮询回显，规避 ROM 后台日志限流）
            com.kingzcheung.xime.plugin.PluginDevConsoleFileSink.install(this)
        }
        PluginManager.configStoreFactory =
            PluginManager.PluginConfigStoreFactory { app, pluginId ->
                PluginConfigStoreImpl(app, pluginId)
            }
        PluginManager.wsHostApiFactory = { pluginId ->
            com.kingzcheung.xime.plugin.ws.WsHostApiImpl(this, pluginId)
        }
        PluginManager.httpHostApiFactory = { pluginId ->
            com.kingzcheung.xime.plugin.http.HttpHostApiImpl(this, pluginId)
        }
        PluginManager.sseHostApiFactory = { pluginId ->
            com.kingzcheung.xime.plugin.http.SseHostApiImpl(this, pluginId)
        }
        PluginManager.cryptoHostApiFactory = {
            com.kingzcheung.xime.plugin.crypto.CryptoHostApiImpl()
        }
        PluginManager.quickSendHostApiFactory = { _ ->
            com.kingzcheung.xime.plugin.QuickSendHostApiImpl(this)
        }
        PluginManager.clipboardHostApiFactory = { _ ->
            com.kingzcheung.xime.plugin.ClipboardHostApiImpl(this)
        }
        com.kingzcheung.xime.plugin.core.security.PluginErrorLog.initialize(
            com.kingzcheung.xime.plugin.FilePluginErrorStore(
                File(filesDir, "logs/plugins/errors.jsonl")
            )
        )
        PluginManager.initialize(this) {
            if (isDebug) {
                PluginManager.installPluginsFromAssetsForDebug("plugins")
            }
            
            PluginManager.loadEnabledPlugins()
        }
        
        ExtensionManager.initialize(this)

        // 从 xime.yaml 加载配色方案
        KeyboardThemes.initFromConfig(this)

        // 从 xime.yaml 的 style 读取默认主题和显示模式
        // color_scheme 可指定 dynamic（Material You 动态配色，仅 Android 12+，
        // 低版本 getThemeById 自动回退到内置配色）
        SettingsPreferences.defaultKeyboardTheme = KeysConfigHelper.loadDefaultThemeId(this)
        SettingsPreferences.defaultDarkMode = KeysConfigHelper.loadDefaultDarkMode(this)

        // 初始化模型运行时（内存管理 + 生命周期）
        ModelRuntime.attach(this)

        preInitializeRimeEngine()
    }
    
    private fun preInitializeRimeEngine() {
        if (RimeEngine.isInitialized()) {
            return
        }
        
        applicationScope.launch {
            try {
                val (userDataDir, sharedDataDir) = RimeConfigHelper.initializeRimeDataAsync(this@XimeApplication)
                val engine = RimeEngine.getInstance()
                engine.initialize(userDataDir, sharedDataDir)

                // 首次安装/升级后静默编译词库。ensureDeployment 内部带互斥且 hash 一致时跳过，
                // 统一负责 deploymentDone/hash 状态，避免与输入法服务的初始化重复触发全量编译。
                RimeConfigHelper.ensureDeployment(this@XimeApplication)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pre-initialize Rime engine", e)
            }
        }
    }
}