package com.kingzcheung.xime.clipboard.sync

import android.util.Log
import com.kingzcheung.xime.clipboard.ClipboardImageStore
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.plugin.core.api.ClipboardProfile
import com.kingzcheung.xime.plugin.core.api.ClipboardSyncPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 剪贴板同步引擎（仿 ximed SyncEngine）。
 *
 * 规则（与 ximed 设计一致的三通道去重语义）：
 * - 本地剪贴板变化 → 与上次推送 hash 不同且非"自写内容" → 推送到远端
 * - 拉取远端（启动时 + 键盘显示时 pullOnce）→ 与本地 hash 及"自写 hash"比对 → 不同才写回本地剪贴板
 * - 自写抑制：引擎写回本地后记录 hash，监听到同一 hash 判定为回声，跳过推送
 *
 * 不再做后台轮询：拉取只在启动时和键盘显示（[pullOnce]）时各触发一次，
 * 避免 IME service 生命周期内频繁请求；推送仍由本地剪贴板变化实时触发。
 *
 * **图片（Phase 3 / 决策 D12）**：`clipboardChanged` 里的图片条目走 [pushLocalImage]，
 * 载荷为原图字节、去重键为内容 SHA-256（= 内容寻址文件名）；远端 `hasData` profile 走
 * [pullRemoteAttachment]（先校验字节 hash 再落盘 + 写回系统剪贴板）。插件未声明
 * `attachments` 时两个方向都直接跳过，文本同步零影响。
 *
 * 宿主只持有引擎与剪贴板桥，具体传输协议（WebDAV/S3/ximed）由 [ClipboardSyncPlugin]
 * 的插件实现（TS 编译产物，QuickJS 执行）承载。
 */
class ClipboardSyncBridge(
    private val clipboardManager: ClipboardManager,
    private val plugin: ClipboardSyncPlugin,
    val pluginId: String = "",
    /**
     * 所选插件是否声明 `capabilities.clipboard_sync.attachments`（Phase 3 / D12）。
     * 未声明 → 图片条目**不推送**、远端图片 profile 被跳过，文本同步完全不受影响（优雅降级）。
     *
     * 公开给宿主做"能力变更检测"：插件热更新后能力可能变化（如 1.0.2 → 1.1.0 才支持附件），
     * 能力变了必须重建 bridge，否则会一直沿用旧能力。
     */
    val supportsAttachments: Boolean = false,
    /**
     * 拉取最小间隔（秒）的配置读取器，由宿主接入所选插件的 configStore
     * （key 为 [Companion.CONFIG_KEY_PULL_INTERVAL_SECONDS]，插件 settings.schema
     * 声明同名 NUMBER 字段）。每次 [pullOnce] 时读取，插件设置改后即时生效；
     * 未配置/非法值回退 [Companion.DEFAULT_PULL_MIN_INTERVAL_MS]。
     */
    private val pullIntervalSeconds: () -> String? = { null }
) {
    companion object {
        private const val TAG = "ClipboardSync"
        private const val PUSH_RETRY_BACKOFF_MS = 5_000L

        /** 待重发的条目最多留这么久：剪贴板是单值资源，太旧的补发没有意义且可能覆盖新内容。 */
        private const val PENDING_TTL_MS = 10 * 60 * 1000L

        /** 拉取间隔配置在插件 configStore 中的 key（插件 settings.schema 需声明同名 NUMBER 字段）。 */
        const val CONFIG_KEY_PULL_INTERVAL_SECONDS = "pull_interval_seconds"

        /** 配置允许的范围（秒）：下限为纯防抖，上限防误填导致拉取停摆。 */
        const val PULL_INTERVAL_SECONDS_MIN = 1L
        const val PULL_INTERVAL_SECONDS_MAX = 600L

        /**
         * 拉取最小间隔默认值：键盘每次显示都会触发 [pullOnce]，高频切换时请求数会轻易
         * 超出 WebDAV 服务的限流阈值（坚果云免费版每 30 分钟仅允许 600 次请求，
         * 超限返回 503 且需等待解封），默认保守节流；用户可经插件配置下调。
         */
        const val DEFAULT_PULL_MIN_INTERVAL_MS = 30_000L

        /** 解析插件配置的拉取间隔（秒）→ 毫秒；空/非法回退默认，越界 clamp。 */
        fun resolvePullIntervalMs(rawSeconds: String?): Long {
            val seconds = rawSeconds?.trim()?.toLongOrNull()
                ?: return DEFAULT_PULL_MIN_INTERVAL_MS
            return seconds.coerceIn(PULL_INTERVAL_SECONDS_MIN, PULL_INTERVAL_SECONDS_MAX) * 1000L
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 上次推送/写入的 hash（去重）。 */
    @Volatile
    private var lastHash: String? = null

    /** 自写 hash：引擎写入本地剪贴板的内容 hash，用于回声抑制。 */
    @Volatile
    private var selfWritten: String? = null

    @Volatile
    private var running = false

    /** 推送失败后的退避截止时间。 */
    @Volatile
    private var retryUntil = 0L

    /**
     * 推送失败且还没补发成功的条目。
     *
     * 只有剪贴板变化才会触发推送，失败一次就丢会让「断网期间复制的内容」永远发不出去；
     * 这里留最新一条，由 [start] 里的补发循环按退避重试。
     */
    @Volatile
    private var pending: PendingPush? = null

    private data class PendingPush(val text: String, val hash: String, val at: Long)

    /** 推送互斥：剪贴板变化流与补发循环都会调 [pushLocal]，避免同一条重复发。 */
    private val pushMutex = Mutex()

    /** 上次真正发起拉取的时间（pullOnce 节流基准）。 */
    @Volatile
    private var lastPullAt = 0L

    /** 插件不支持附件时只提示一次（否则每次复制图片都会刷屏）。 */
    @Volatile
    private var attachmentsUnsupportedLogged = false

    private var collectJob: Job? = null

    fun start() {
        if (running) return
        running = true
        Log.d(TAG, "Sync started")

        // 1. 订阅本地剪贴板变化 → push（回声抑制：selfWritten 命中的跳过）
        //    图片条目按 type 分流：附件走 pushLocalImage，文本路径行为不变
        collectJob = clipboardManager.clipboardChanged
            .onEach { item ->
                if (item.isImage) {
                    pushLocalImage(item)
                    return@onEach
                }
                if (item.text.isBlank()) return@onEach
                val hash = ClipboardProfile.sha256Hex(item.text.toByteArray(Charsets.UTF_8))
                if (hash == selfWritten) {
                    Log.d(TAG, "Echo suppressed (self-written)")
                    return@onEach
                }
                pushLocal(item.text, hash)
            }
            .launchIn(scope)

        // 2. 启动即拉取一次（不受节流限制）；后续由键盘显示时 pullOnce() 触发
        scope.launch {
            lastPullAt = System.currentTimeMillis()
            pullRemote()
        }

        // 3. 补发循环：推送失败（离线/服务器不可达）时留住最新一条，退避后重试。
        //    否则「复制了但没发出去」只能等下一次剪贴板变化才可能补上。
        scope.launch {
            while (isActive) {
                delay(PUSH_RETRY_BACKOFF_MS)
                val item = pending ?: continue
                if (!running) continue
                if (System.currentTimeMillis() - item.at > PENDING_TTL_MS) {
                    pending = null
                    continue
                }
                pushLocal(item.text, item.hash)
            }
        }
    }

    /**
     * 切换通道后调用：把当前剪贴板补推一次。
     *
     * 新通道还没见过这条内容，而推送只由「剪贴板变化」触发——不补推的话，
     * 切换瞬间正在同步的那一条会停在旧通道上。
     */
    fun pushCurrent() {
        if (!running) return
        scope.launch {
            val text = clipboardManager.getCurrentClipboardText() ?: return@launch
            if (text.isBlank()) return@launch
            val hash = ClipboardProfile.sha256Hex(text.toByteArray(Charsets.UTF_8))
            pushLocal(text, hash)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        collectJob?.cancel()
        collectJob = null
        Log.d(TAG, "Sync stopped")
    }

    fun release() {
        stop()
        scope.cancel()
    }

    /** 键盘显示时触发一次拉取；节流：间隔内的触发直接跳过，不发起请求。 */
    fun pullOnce() {
        if (!running) return
        val now = System.currentTimeMillis()
        val minIntervalMs = resolvePullIntervalMs(pullIntervalSeconds())
        if (now - lastPullAt < minIntervalMs) {
            Log.d(TAG, "Pull throttled (min interval ${minIntervalMs / 1000}s)")
            return
        }
        lastPullAt = now
        scope.launch {
            pullRemote()
        }
    }

    private suspend fun pushLocal(text: String, hash: String) = pushMutex.withLock {
        if (!running) return@withLock
        if (hash == lastHash) {
            Log.d(TAG, "No change, skip push")
            return@withLock
        }
        if (System.currentTimeMillis() < retryUntil) {
            Log.d(TAG, "Push backoff active, skip")
            return@withLock
        }
        val profile = ClipboardProfile.fromText(text)
        val ok = try {
            plugin.push(profile)
        } catch (e: Exception) {
            Log.e(TAG, "push failed", e)
            false
        }
        if (ok) {
            lastHash = hash
            retryUntil = 0L
            pending = null
        } else {
            retryUntil = System.currentTimeMillis() + PUSH_RETRY_BACKOFF_MS
            pending = PendingPush(text, hash, System.currentTimeMillis())
        }
    }

    /**
     * 图片推送（Phase 3 / 决策 D12）：载荷是**原图字节**，宿主不压缩。
     *
     * 去重键 = 内容 SHA-256（图片 profile 的 `hash`），与本地内容寻址文件名同源 ⇒
     * 同一张图在任何设备上 hash 相同，重复推送幂等、不会来回乒乓。
     */
    private suspend fun pushLocalImage(item: ClipboardItem) {
        if (!running) return
        if (!supportsAttachments) {
            if (!attachmentsUnsupportedLogged) {
                attachmentsUnsupportedLogged = true
                Log.d(
                    TAG,
                    "Plugin ${pluginId.ifEmpty { "?" }} 未声明 attachments：图片不参与同步（文本不受影响）"
                )
            }
            return
        }
        if (System.currentTimeMillis() < retryUntil) {
            Log.d(TAG, "Image push backoff active, skip")
            return
        }
        val file = clipboardManager.imageFileOf(item) ?: run {
            Log.d(TAG, "Image file missing, skip push (${item.imagePath})")
            return
        }
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read clipboard image, skip push", e)
            return
        }
        if (bytes.isEmpty()) return

        val extension = file.extension.lowercase().ifEmpty {
            ClipboardImageStore.sniffExtension(bytes) ?: "png"
        }
        val profile = ClipboardProfile.fromImage(bytes, extension)
        if (profile.hash == lastHash) {
            Log.d(TAG, "No change, skip image push")
            return
        }
        val ok = try {
            plugin.push(profile)
        } catch (e: Exception) {
            Log.e(TAG, "push image failed", e)
            false
        }
        if (ok) {
            lastHash = profile.hash
            retryUntil = 0L
            Log.d(TAG, "Image pushed (${bytes.size} bytes, ${profile.dataName})")
        } else {
            retryUntil = System.currentTimeMillis() + PUSH_RETRY_BACKOFF_MS
        }
    }

    private suspend fun pullRemote() {
        val remote = try {
            plugin.pull()
        } catch (e: Exception) {
            Log.e(TAG, "pull failed", e)
            null
        }
        if (remote == null) return

        // 附件（图片）：走独立路径，文本路径完全不受影响
        if (remote.hasData) {
            pullRemoteAttachment(remote)
            return
        }

        val remoteHash = remote.hash
        val currentClipboard = clipboardManager.getCurrentClipboardText()
        val currentHash = currentClipboard?.let {
            ClipboardProfile.sha256Hex(it.toByteArray(Charsets.UTF_8))
        }

        // 与本地当前内容相同 或 与自己写回的内容相同 → 跳过（避免循环）
        if (remoteHash == currentHash || remoteHash == selfWritten) {
            Log.d(TAG, "Remote unchanged vs local, skip write")
            return
        }

        Log.d(TAG, "Remote changed, writing to local clipboard")
        lastHash = remoteHash
        selfWritten = remoteHash
        clipboardManager.copyToSystemClipboard(remote.text)
    }

    /**
     * 远端附件（图片）落盘 + 写回系统剪贴板。
     *
     * 远端内容不可信：**先用字节重算 hash 并与声明值比对**，不符即丢弃（不落盘、不写剪贴板）。
     * 落盘走 [ClipboardManager.importImageFromSync]（按内容寻址去重、纳入配额与面板列表），
     * 因此拉到的图片会和本地采集的图片一样出现在剪贴板面板与候选栏。
     */
    private suspend fun pullRemoteAttachment(remote: ClipboardProfile) {
        if (!supportsAttachments) {
            Log.d(TAG, "Plugin 未声明 attachments，跳过远端附件")
            return
        }
        val data = remote.data
        if (data == null || data.isEmpty()) {
            // 插件不支持附件或 blob 下载失败：不写回空文本（否则会清空用户剪贴板）
            Log.d(TAG, "Remote attachment unavailable, skip (dataName=${remote.dataName})")
            return
        }
        val hash = ClipboardProfile.sha256Hex(data)
        if (remote.hash.isNotEmpty() && remote.hash != hash) {
            Log.e(TAG, "Remote attachment hash mismatch, discard (declared=${remote.hash}, actual=$hash)")
            return
        }
        if (hash == selfWritten || hash == lastHash) {
            Log.d(TAG, "Remote attachment unchanged vs local, skip")
            return
        }
        // 先登记去重状态再入库：入库会触发 clipboardChanged，订阅者可能先于后续语句看到它
        lastHash = hash
        selfWritten = hash
        val path = clipboardManager.importImageFromSync(data, remote.dataName)
        if (path == null) {
            // 落盘/入库失败：回滚去重状态，否则同内容下次会被误判为"已同步"
            if (lastHash == hash) lastHash = null
            if (selfWritten == hash) selfWritten = null
            Log.e(TAG, "Failed to import remote image, dedupe state rolled back")
            return
        }
        val copied = clipboardManager.copyImageToSystemClipboard(
            imagePath = path,
            label = "xime_clipboard_image",
            // 直接共享 files/clipboard_images 原文件（配合淘汰保护集），与面板点选同一路径
            shareCacheCopy = false,
        )
        Log.d(TAG, "Remote image written to local clipboard (copied=$copied, $hash)")
    }
}
