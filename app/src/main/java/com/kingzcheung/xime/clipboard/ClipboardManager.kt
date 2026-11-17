package com.kingzcheung.xime.clipboard

import com.kingzcheung.xime.R

import com.kingzcheung.xime.i18n.tr

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.kingzcheung.xime.data.db.ClipboardDao
import com.kingzcheung.xime.data.db.XimeDatabase
import com.kingzcheung.xime.data.db.ClipboardEntry
import com.kingzcheung.xime.plugin.core.api.ClipboardProfile
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import android.content.ClipboardManager as AndroidClipboardManager

data class ClipboardItem(
    val id: Long = 0,
    val text: String,
    /** 快捷发送触发编码（如 dh），空 = 仅内容命中不参与编码匹配。 */
    val code: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val isQuickSend: Boolean = false,
    val consumed: Boolean = false,
    /** 条目类型：[ClipboardEntry.TYPE_TEXT] / [ClipboardEntry.TYPE_IMAGE]（图片条目 text 为空串）。 */
    val type: String = ClipboardEntry.TYPE_TEXT,
    /** 图片文件在 `files/` 下的相对路径（如 `clipboard_images/<sha256>.png`）。 */
    val imagePath: String = "",
    /** 图片内容 SHA-256（去重键）。 */
    val imageHash: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
) {
    val isImage: Boolean get() = type == ClipboardEntry.TYPE_IMAGE
}

/** 图片占用快照（剪贴板设置页"已用空间"用）。 */
data class ImageUsage(
    val count: Int = 0,
    val bytes: Long = 0,
    val fileBytes: Long = 0,
)

class ClipboardManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ClipboardManager"
        private const val MAX_ITEMS = 1000
        private const val MAX_QUICK_SEND_ITEMS = 2000
        private const val PREFS_NAME = "clipboard_prefs"
        private const val KEY_CLIPBOARD_ITEMS = "clipboard_items"
        private const val KEY_QUICK_SEND_ITEMS = "quick_send_items"

        /** 拒收提示（图片过大/尺寸过大）的去重窗口，避免连续复制时连环弹 Toast。 */
        private const val REJECT_TOAST_INTERVAL_MS = 10_000L

        /**
         * 同步导入图片的**绝对**上限 = 设置允许的最大单张（1–20MB 的最大值）。
         *
         * 远端设备可能把单张上限调到比本机大，按本机上限拒收会让同步"看起来成功但没内容"；
         * 因此导入只用这个绝对上限兜底，不跟随本机的「单张最大体积」。
         */
        private val SYNC_IMPORT_MAX_BYTES: Long =
            SettingsPreferences.CLIPBOARD_IMAGE_MAX_MB_RANGE.last * 1024L * 1024L

        @Volatile
        private var instance: ClipboardManager? = null

        fun getInstance(context: Context): ClipboardManager {
            return instance ?: synchronized(this) {
                instance ?: ClipboardManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val androidClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as AndroidClipboardManager

    /** 图片文件存储（内容寻址：`files/clipboard_images/<sha256>.<ext>`）。 */
    private val imageStore = ClipboardImageStore(ClipboardImageStore.rootDirOf(context.filesDir))

    /** 图片落盘/入库/配额收敛互斥：避免并发采集时孤儿清理误删刚写入的文件。 */
    private val imageMutex = Mutex()

    /** 拒收提示需在主线程弹 Toast。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 上次拒收提示时间（[REJECT_TOAST_INTERVAL_MS] 去重；采集在 IO 线程，故 volatile）。 */
    @Volatile
    private var lastRejectToastAt = 0L

    /**
     * 当前系统剪贴板仍指向的"本应用图片"相对路径：淘汰时豁免，
     * 否则粘贴方后续读取会因文件已删而失败。由 [copyImageToSystemClipboard] 写入，
     * 剪贴板变为其它内容时清空。
     */
    @Volatile
    private var selfClipboardImagePath: String? = null

    private val clipboardListener = AndroidClipboardManager.OnPrimaryClipChangedListener {
        readClipboard()
    }

    /**
     * 采集系统剪贴板。
     *
     * 文本分支与旧行为一致（text → uri 字符串 → intent）；图片分支（[ClipData] 声明图片 MIME
     * 且带 uri）改为读取字节落盘为图片条目——旧实现会把 `content://…` 当**文本**存进历史，
     * 属既存缺陷，本次一并修掉（读取失败/非支持类型直接跳过，不再退化成 URI 文本）。
     */
    private fun readClipboard(retries: Int = 3) {
        try {
            val clipData = androidClipboardManager.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val description = clipData.description

                // 敏感剪贴板（密码等，API 33+ 系统标记）默认不入库，设置页可开
                if (isSensitiveClip(description) &&
                    !SettingsPreferences.isClipboardSensitiveCaptureEnabled(context)
                ) {
                    Log.d(TAG, "Sensitive clip ignored (setting off)")
                    clearSelfClipboardImage()
                    return
                }

                val imageUri = imageUriOf(clipData, description)
                if (imageUri != null) {
                    // 本应用自己写回的图片（FileProvider URI）：已入库，跳过采集避免重复读盘
                    if (isSelfProviderUri(imageUri)) {
                        Log.d(TAG, "Self-written image clip, skip capture")
                        return
                    }
                    // 非文本剪贴板：此前写回的系统剪贴板图片保护集失效
                    clearSelfClipboardImage()
                    // 设置页关闭"收集图片"时，图片剪贴板整体跳过（文本分支不受影响）
                    if (!SettingsPreferences.isClipboardImageCaptureEnabled(context)) {
                        Log.d(TAG, "Image capture disabled by settings, skip")
                        return
                    }
                    captureImage(imageUri, description)
                    return
                }

                // 非图片剪贴板：此前写入的系统剪贴板图片已不再是当前内容，保护集失效
                clearSelfClipboardImage()

                val item = clipData.getItemAt(0)
                val text = when {
                    item.text != null -> item.text.toString()
                    item.uri != null -> item.uri.toString()
                    item.intent != null -> item.intent.toUri(0)
                    else -> null
                }
                if (!text.isNullOrEmpty()) {
                    addItem(text)
                    return
                }
            }
            if (retries > 0) {
                Handler(Looper.getMainLooper()).postDelayed({ readClipboard(retries - 1) }, 100L)
            } else {
                Log.w(TAG, "Failed to read clipboard after all retries")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot read clipboard: missing permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error reading clipboard", e)
        }
    }

    /** 敏感剪贴板判定（API 33+ 由系统标记 `ClipDescription.EXTRA_IS_SENSITIVE`）。 */
    private fun isSensitiveClip(description: ClipDescription): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        } catch (e: Exception) {
            false
        }
    }

    /** 剪贴板图片 URI：仅接受声明图片 MIME 且首项带 uri 的 ClipData。 */
    private fun imageUriOf(clipData: ClipData, description: ClipDescription): Uri? {
        val hasImage = (0 until (description.mimeTypeCount)).any {
            description.getMimeType(it)?.startsWith("image/") == true
        }
        if (!hasImage) return null
        val uri = clipData.getItemAt(0).uri ?: return null
        return when (uri.scheme?.lowercase()) {
            ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_FILE -> uri
            else -> null
        }
    }

    private fun isSelfProviderUri(uri: Uri): Boolean =
        uri.authority == "${context.packageName}.fileprovider"

    /**
     * 读取图片 URI 字节 → 尺寸/体积准入 → 落盘 → 入库。
     *
     * 大小限制（决策 D8：不压缩，超限直接拒收 + 提示）：阈值由设置页可配，
     * 默认单张 5MB / 最长边 4096px（[ClipboardImageStore] 常量）。
     *
     * 其它失败路径（无读取权限/文件不存在/类型不支持）一律记日志后放弃，
     * **不**回退成"把 uri 当文本"（那会污染剪贴板历史）。
     */
    private fun captureImage(uri: Uri, description: ClipDescription) {
        scope.launch {
            try {
                val limits = imageLimits()
                val mimeHint = description.getMimeType(0) ?: context.contentResolver.getType(uri)
                val read = readImageBytes(uri, limits.maxBytes)
                if (read.tooLarge) {
                    Log.w(TAG, "Clipboard image exceeds ${limits.maxBytes} bytes, rejected: $uri")
                    notifyImageRejected(ClipboardImageStore.RejectReason.TOO_LARGE_BYTES, limits)
                    return@launch
                }
                val bytes = read.bytes ?: return@launch
                if (bytes.isEmpty()) {
                    Log.w(TAG, "Clipboard image is empty: $uri")
                    return@launch
                }
                val (width, height) = decodeBounds(bytes)
                val reason = ClipboardImageStore.rejectReason(
                    sizeBytes = bytes.size.toLong(),
                    width = width,
                    height = height,
                    maxBytes = limits.maxBytes,
                )
                if (reason != null) {
                    Log.w(TAG, "Clipboard image rejected ($reason): ${bytes.size} bytes, ${width}x$height")
                    notifyImageRejected(reason, limits)
                    return@launch
                }
                val extension = ClipboardImageStore.resolveExtension(mimeHint, bytes)
                if (extension == null) {
                    Log.w(TAG, "Unsupported clipboard image type (mime=$mimeHint), skipped")
                    return@launch
                }
                addImage(bytes, extension, mimeHint, width, height, limits)
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot read clipboard image (no URI grant): $uri", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to capture clipboard image: $uri", e)
            }
        }
    }

    /**
     * 拒收提示：主线程弹 Toast；连续复制大图时 10s 内只提示一次，
     * 避免用户在选择图片时被连环打扰（与插件网络授权提示同策略）。
     * 文案按当前生效阈值生成（设置页可调，写死常量会与实际判定不一致）。
     */
    private fun notifyImageRejected(reason: ClipboardImageStore.RejectReason, limits: ImageLimits) {
        val message = when (reason) {
            ClipboardImageStore.RejectReason.TOO_LARGE_BYTES ->
                tr(R.string.s_tupianchaoguo_mb_weibaocundaoj_71361, limits.maxBytes / 1024 / 1024)
            ClipboardImageStore.RejectReason.TOO_LARGE_DIMENSION ->
                tr(R.string.s_tupianchicunchaoguo_px_weibaoc_b335c, limits.maxEdge)
        }
        val now = System.currentTimeMillis()
        if (now - lastRejectToastAt < REJECT_TOAST_INTERVAL_MS) return
        lastRejectToastAt = now
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** 图片字节读取结果。[tooLarge] 为真表示超过单张上限（提前放弃，不整包读进内存）。 */
    private class ImageReadResult(val bytes: ByteArray?, val tooLarge: Boolean = false)

    /** 读取 URI 字节，超过单张上限即提前放弃。 */
    private fun readImageBytes(uri: Uri, maxBytes: Long): ImageReadResult {
        val input = context.contentResolver.openInputStream(uri) ?: return ImageReadResult(null)
        input.use {
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val read = it.read(chunk)
                if (read <= 0) break
                buffer.write(chunk, 0, read)
                if (buffer.size().toLong() > maxBytes) {
                    return ImageReadResult(null, tooLarge = true)
                }
            }
            return ImageReadResult(buffer.toByteArray())
        }
    }

    /**
     * 图片入库（与 [addItem] 对称：落盘 + 去重 + 配额 + 事件）。
     * @return 入库条目的相对路径（`clipboard_images/<hash>.<ext>`）；同图重复入库返回已存在的路径，
     *   落盘失败/配额淘汰后缺失返回 null。调用方（本地采集）可忽略返回值。
     */
    private suspend fun addImage(
        bytes: ByteArray,
        extension: String,
        mimeHint: String?,
        width: Int,
        height: Int,
        limits: ImageLimits,
    ): String? {
        return imageMutex.withLock {
            val hash = ClipboardProfile.sha256Hex(bytes)
            val mime = ClipboardImageStore.mimeForExtension(extension)
                ?: mimeHint?.takeIf { it.startsWith("image/") }
                ?: "application/octet-stream"
            val now = System.currentTimeMillis()
            val existing = dao.findImageByHash(hash)
            if (existing != null) {
                // 同图重复复制：仅更新时间戳置顶，不重复落盘
                dao.updateTimestamp(existing.id, now)
            } else {
                val relativePath = imageStore.save(bytes, hash, extension, limits.maxBytes) ?: run {
                    Log.e(TAG, "Failed to save clipboard image ($hash.$extension)")
                    return@withLock null
                }
                dao.upsertImageAndTrim(
                    ClipboardEntry(
                        text = "",
                        timestamp = now,
                        type = ClipboardEntry.TYPE_IMAGE,
                        imagePath = relativePath,
                        imageHash = hash,
                        mimeType = mime,
                        sizeBytes = bytes.size.toLong(),
                        width = width,
                        height = height,
                    ),
                    limits.maxCount,
                )
            }
            // 数量上限（DAO 内已裁）与容量上限：统一回收被删行的文件 + 孤儿文件
            enforceImageQuotaLocked(limits)
            // 回读入库结果（带 id；极端情况下容量超限且其余条目全受保护时可能已被淘汰）
            val row = dao.findImageByHash(hash) ?: return@withLock null
            _clipboardChanged.emit(row.toClipboardItem())
            row.imagePath
        }
    }

    /**
     * 导入远端同步来的图片（Phase 3 / 决策 D12）。
     *
     * 与本地采集路径的**刻意差异**：
     * - **不按本机「单张最大体积」拒收**：远端设备的上限可能更大（1–20MB 可调），
     *   按本机上限拒收会表现为"同步成功但什么都没发生"；这里只用绝对上限
     *   [SYNC_IMPORT_MAX_BYTES]（设置允许的最大值）兜底。
     * - 类型以**字节魔数**为准，`dataName` 的扩展名只作兜底（远端文件名不可当凭据）。
     * - 不做最长边拒收：超限图在源设备上已按同一规则处理过，这里再拒会让两台设备行为不一致；
     *   展示侧的内存上限由 Coil 的显式解码方框保证（见 `ui/ClipboardImageRequests.kt`）。
     *
     * 入库会经 [addImage] 触发 `clipboardChanged`——同步引擎在调用本方法**之前**就把该内容
     * 记为"自写"，避免自己把自己再推一轮（见 `ClipboardSyncBridge.pullRemoteAttachment`）。
     *
     * @param dataName 远端文件名（`<sha256>.<ext>`），仅用于兜底推断类型
     * @return 落盘后的**绝对路径**（可直接写系统剪贴板）；失败返回 null
     */
    suspend fun importImageFromSync(data: ByteArray, dataName: String?): String? {
        if (data.isEmpty()) return null
        if (data.size.toLong() > SYNC_IMPORT_MAX_BYTES) {
            Log.w(TAG, "Synced image exceeds hard cap (${data.size} bytes), skipped")
            return null
        }
        val extension = ClipboardImageStore.sniffExtension(data)
            ?: ClipboardImageStore.extensionForMime(mimeHintOf(dataName))
            ?: run {
                Log.w(TAG, "Unsupported synced image (dataName=$dataName), skipped")
                return null
            }
        val (width, height) = decodeBounds(data)
        val limits = ImageLimits(
            maxBytes = SYNC_IMPORT_MAX_BYTES,
            maxEdge = ClipboardImageStore.MAX_IMAGE_EDGE,
            maxCount = ClipboardImageStore.MAX_IMAGES,
            maxTotalBytes = maxOf(ClipboardImageStore.MAX_TOTAL_BYTES, SYNC_IMPORT_MAX_BYTES),
        )
        val relativePath = addImage(
            bytes = data,
            extension = extension,
            mimeHint = ClipboardImageStore.mimeForExtension(extension),
            width = width,
            height = height,
            limits = limits,
        ) ?: return null
        Log.d(TAG, "Imported synced image ($relativePath, ${data.size} bytes)")
        return imageStore.absoluteFile(relativePath).absolutePath
    }

    /** 从远端 `data_name`（`<hash>.<ext>`）推断 MIME，仅作类型兜底。 */
    private fun mimeHintOf(dataName: String?): String? {
        val extension = dataName?.substringAfterLast('.', "")?.lowercase()
        return if (extension.isNullOrEmpty()) null else ClipboardImageStore.mimeForExtension(extension)
    }

    /** 解析图片尺寸（只读头部，不解码像素，避免 OOM）；失败返回 (0, 0)。 */
    private fun decodeBounds(bytes: ByteArray): Pair<Int, Int> {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            options.outWidth.coerceAtLeast(0) to options.outHeight.coerceAtLeast(0)
        } catch (e: Exception) {
            0 to 0
        }
    }

    /**
     * 容量/数量配额收敛（须持有 [imageMutex] 调用）。
     * 淘汰候选由纯函数 [ClipboardImageStore.selectEvictions] 计算（置顶与保护集豁免），
     * 删除行后回收文件，最后做一次孤儿清理兜底。
     */
    private suspend fun enforceImageQuotaLocked(limits: ImageLimits) {
        val images = dao.allImages().map { it.toClipboardItem() }
        val evicted = ClipboardImageStore.selectEvictions(
            images = images,
            protectedPaths = protectedImagePaths(),
            maxImages = limits.maxCount,
            maxTotalBytes = limits.maxTotalBytes,
        )
        if (evicted.isNotEmpty()) {
            dao.deleteImageByIds(evicted.map { it.id })
            Log.d(TAG, "Image quota: evicted ${evicted.size} item(s)")
        }
        val known = dao.allImagePaths().toSet()
        imageStore.cleanupOrphans(known)
    }

    /**
     * 当前生效的图片配额。**只有单张上限**可由设置页调整（设置 → 数据与同步 → 剪贴板），
     * 其余三项取 [ClipboardImageStore] 内置常量。
     */
    private class ImageLimits(
        val maxBytes: Long,
        val maxEdge: Int,
        val maxCount: Int,
        val maxTotalBytes: Long,
    )

    /** 总容量至少为单张上限（否则刚采集的图片会因容量超限被立刻淘汰，表现为"复制了却什么都没发生"）。 */
    private fun imageLimits(): ImageLimits {
        val maxBytes = SettingsPreferences.getClipboardImageMaxMb(context) * 1024L * 1024L
        return ImageLimits(
            maxBytes = maxBytes,
            maxEdge = ClipboardImageStore.MAX_IMAGE_EDGE,
            maxCount = ClipboardImageStore.MAX_IMAGES,
            maxTotalBytes = maxOf(ClipboardImageStore.MAX_TOTAL_BYTES, maxBytes),
        )
    }

    /** 当前受保护的图片路径（系统剪贴板仍指向本应用文件时不淘汰）。 */
    private fun protectedImagePaths(): Set<String> =
        selfClipboardImagePath?.let { setOf(it) } ?: emptySet()

    private fun clearSelfClipboardImage() {
        selfClipboardImagePath = null
    }

    // 备份恢复会关闭并替换数据库文件，这里每次取当前实例，避免抓住已关闭的连接。
    private val database: XimeDatabase
        get() = XimeDatabase.getInstance(context)
    private val dao: ClipboardDao
        get() = database.clipboardDao()
    private val scope = XimeDatabase.scope()

    private val _clipboardItems = MutableStateFlow<List<ClipboardItem>>(emptyList())
    val clipboardItems: StateFlow<List<ClipboardItem>> = _clipboardItems.asStateFlow()

    private val _quickSendItems = MutableStateFlow<List<ClipboardItem>>(emptyList())
    val quickSendItems: StateFlow<List<ClipboardItem>> = _quickSendItems.asStateFlow()

    private val _recentItems = MutableStateFlow<List<ClipboardItem>>(emptyList())
    val recentItems: StateFlow<List<ClipboardItem>> = _recentItems.asStateFlow()

    /** 本地剪贴板变更事件流（新增/更新条目时发射，供剪贴板同步等外部消费）。 */
    private val _clipboardChanged = MutableSharedFlow<ClipboardItem>(extraBufferCapacity = 16)
    val clipboardChanged: SharedFlow<ClipboardItem> = _clipboardChanged.asSharedFlow()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyData()
        scope.launch {
            dao.observeAll().collect { entries ->
                _clipboardItems.value = entries.map { it.toClipboardItem() }
                updateRecentItems()
            }
        }
        scope.launch {
            dao.observeQuickSend().collect { entries ->
                _quickSendItems.value = entries.map { it.toClipboardItem() }
            }
        }
        scope.launch {
            // 启动自检：多余图片文件（含上次被淘汰行残留、.tmp 半成品）与容量超限收敛
            runCatching {
                imageMutex.withLock { enforceImageQuotaLocked(imageLimits()) }
            }.onFailure { Log.w(TAG, "Clipboard image startup cleanup failed", it) }
            // 启动自检：上次没删干净的中转图
            runCatching { MediaBridge.sweepStale(context) }
                .onFailure { Log.w(TAG, "Media bridge startup sweep failed", it) }
        }
        startListening()
    }

    /**
     * 将旧版 SharedPreferences 中的剪贴板/快捷发送数据一次性迁移到 Room。
     * 幂等：prefs 无数据时直接返回；迁移成功后删除 prefs 键，避免重复迁移。
     */
    fun migrateLegacyData() {
        val legacyClipboard = prefs.getString(KEY_CLIPBOARD_ITEMS, null)
        val legacyQuickSend = prefs.getString(KEY_QUICK_SEND_ITEMS, null)
        if (legacyClipboard == null && legacyQuickSend == null) return
        scope.launch {
            try {
                val entries = mutableListOf<ClipboardEntry>()
                legacyClipboard?.let { str ->
                    deserializeItems(str).forEach { item ->
                        entries.add(item.toEntry())
                    }
                }
                legacyQuickSend?.let { str ->
                    deserializeItems(str).forEach { item ->
                        entries.add(item.toEntry())
                    }
                }
                if (entries.isNotEmpty()) {
                    dao.insertAll(entries)
                }
                prefs.edit()
                    .remove(KEY_CLIPBOARD_ITEMS)
                    .remove(KEY_QUICK_SEND_ITEMS)
                    .apply()
                Log.i(TAG, "Migrated ${entries.size} legacy clipboard items to Room")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to migrate legacy clipboard items", e)
            }
        }
    }

    private fun updateRecentItems() {
        val now = System.currentTimeMillis()
        val cutoff = now - 10 * 1000L
        // 候选栏只投递文本条目：图片条目无文本可上屏，且其消费路径是剪贴板面板点选
        _recentItems.value = _clipboardItems.value.filter {
            it.timestamp >= cutoff && !it.isImage
        }
    }

    private fun deserializeItems(json: String): List<ClipboardItem> {
        if (json.isEmpty()) return emptyList()
        return json.split("|||").mapNotNull { itemStr ->
            val parts = itemStr.split(":::")
            if (parts.size == 5) {
                try {
                    ClipboardItem(
                        id = parts[0].toLong(),
                        text = parts[1].unescape(),
                        timestamp = parts[2].toLong(),
                        isPinned = parts[3].toBoolean(),
                        isQuickSend = parts[4].toBoolean()
                    )
                } catch (e: Exception) {
                    null
                }
            } else if (parts.size == 4) {
                try {
                    ClipboardItem(
                        id = parts[0].toLong(),
                        text = parts[1].unescape(),
                        timestamp = parts[2].toLong(),
                        isPinned = parts[3].toBoolean(),
                        isQuickSend = false
                    )
                } catch (e: Exception) {
                    null
                }
            } else null
        }
    }

    private fun String.unescape(): String {
        return this.replace("〈PIPE〉", "|||").replace("〈COLON〉", ":::")
    }

    private fun ClipboardItem.toEntry(): ClipboardEntry {
        return ClipboardEntry(
            id = 0,
            text = text,
            timestamp = timestamp,
            isPinned = isPinned,
            isQuickSend = isQuickSend,
            consumed = consumed,
            type = type,
            imagePath = imagePath,
            imageHash = imageHash,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            width = width,
            height = height
        )
    }

    private fun ClipboardEntry.toClipboardItem(): ClipboardItem {
        return ClipboardItem(
            id = id,
            text = text,
            code = code,
            timestamp = timestamp,
            isPinned = isPinned,
            isQuickSend = isQuickSend,
            consumed = consumed,
            type = type,
            imagePath = imagePath,
            imageHash = imageHash,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            width = width,
            height = height
        )
    }

    /**
     * 系统剪贴板监听是否已注册（回归防线：b592c882 曾漏调用 [startListening]，
     * 使复制图片/文本整体不入库。androidTest 断言此状态，防止再次误删）。
     */
    @Volatile
    internal var isListeningToSystemClipboard = false
        private set

    /**
     * 注册系统剪贴板变化监听（采集入口，单例仅在 init 调用一次）。
     * 幂等：系统侧重复注册同一 listener 会让每次剪贴板变化回调两次。
     */
    private fun startListening() {
        if (isListeningToSystemClipboard) return
        androidClipboardManager.addPrimaryClipChangedListener(clipboardListener)
        isListeningToSystemClipboard = true
    }

    fun release() {
        // Singleton — no cleanup needed.
    }

    fun addItem(text: String) {
        if (text.isBlank()) return
        scope.launch {
            dao.upsertAndTrim(text, System.currentTimeMillis(), MAX_ITEMS)
            _clipboardChanged.emit(
                ClipboardItem(
                    text = text,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun removeItem(id: Long) {
        scope.launch {
            deleteEntriesAndFiles(listOf(id))
        }
    }

    /** 批量删除剪贴板条目（仅 isQuickSend = 0，不影响快捷发送）。 */
    fun removeItems(ids: List<Long>) {
        if (ids.isEmpty()) return
        scope.launch {
            deleteEntriesAndFiles(ids)
        }
    }

    /** 清空剪贴板（异步；仅 isQuickSend = 0，不影响快捷发送）。见 [clearClipboardAndWait]。 */
    fun clearClipboard() {
        scope.launch { clearClipboardAndWait() }
    }

    /**
     * 清空剪贴板并等待完成（存储空间页清理后需立即重新统计，不能等异步任务）。
     * 文本条目随数据库删除；图片条目额外回收磁盘文件，最后兜底清理
     * 数据库已无引用的残留（半截 .tmp、异常退出遗留），保证统计页清理后占用归零。
     */
    suspend fun clearClipboardAndWait() {
        // 与图片落盘串行，避免清理过程中又有新图写入（清完即变孤儿）
        imageMutex.withLock {
            val paths = dao.allImages().map { it.imagePath }
            dao.clearAllClipboard()
            paths.forEach { imageStore.delete(it) }
            val removed = imageStore.cleanupOrphans(emptySet())
            if (removed > 0) Log.d(TAG, "Clipboard clear: removed $removed orphan image file(s)")
            // 历史已清空，系统剪贴板若仍指向本应用文件也不再需要保护
            clearSelfClipboardImage()
        }
    }

    /** 删除条目并回收对应图片文件（文本条目无文件，行为不变）。 */
    private suspend fun deleteEntriesAndFiles(ids: List<Long>) {
        val paths = dao.findByIds(ids).filter { it.imagePath.isNotEmpty() }.map { it.imagePath }
        dao.deleteClipboardByIds(ids)
        paths.forEach { imageStore.delete(it) }
    }

    fun splitItem(id: Long) {
        scope.launch {
            val item = _clipboardItems.value.find { it.id == id } ?: return@launch
            dao.deleteClipboardById(id)
            val now = System.currentTimeMillis()
            item.text.forEachIndexed { index, char ->
                dao.insert(
                    ClipboardEntry(
                        text = char.toString(),
                        timestamp = now + index
                    )
                )
            }
        }
    }

    fun clearAll() {
        scope.launch {
            // 仅清非置顶；图片条目同步回收文件（与 clearClipboard 一致）
            val paths = dao.allImages().filter { !it.isPinned }.map { it.imagePath }
            dao.clearUnpinned()
            paths.forEach { imageStore.delete(it) }
        }
    }

    fun addToQuickSend(id: Long) {
        scope.launch {
            dao.addQuickSend(id, System.currentTimeMillis(), MAX_QUICK_SEND_ITEMS)
        }
    }

    fun removeFromQuickSend(id: Long) {
        scope.launch {
            dao.deleteQuickSendById(id)
        }
    }

    fun togglePinQuickSend(id: Long) {
        scope.launch {
            dao.updateTimestamp(id, System.currentTimeMillis())
        }
    }

    fun updateQuickSendItem(id: Long, newText: String, newCode: String = ""): Boolean {
        if (newText.isBlank()) return false
        val index = _quickSendItems.value.indexOfFirst { it.id == id }
        if (index < 0) return false
        scope.launch {
            dao.updateQuickSendItem(id, newText, newCode.trim(), System.currentTimeMillis())
        }
        return true
    }

    fun addQuickSendItem(text: String, code: String = "") {
        if (text.isBlank()) return
        scope.launch {
            dao.insertQuickSend(text, code.trim(), System.currentTimeMillis(), MAX_QUICK_SEND_ITEMS)
        }
    }

    fun copyToSystemClipboard(text: String) {
        val clip = ClipData.newPlainText("kime_clipboard", text)
        androidClipboardManager.setPrimaryClip(clip)
    }

    fun getCurrentClipboardText(): String? {
        val clipData = androidClipboardManager.primaryClip
        return if (clipData != null && clipData.itemCount > 0) {
            clipData.getItemAt(0).text?.toString()
        } else null
    }

    /**
     * 候选栏展示的最近剪贴板项（[seconds] 秒窗口内且未消费）。
     *
     * 文本与**图片一并返回**（决策 D9：图片候选与文本候选同栏混排，图片条目 `text` 为空串）：
     * 返回顺序即候选栏索引顺序，调用方按 index 取用，**此处不可再过滤**，否则索引错位。
     * 图片点选走 [selectClipboardImage][com.kingzcheung.xime.service.ImeTextCommit.selectClipboardImage]。
     */
    fun getRecentItems(seconds: Int = 30): List<ClipboardItem> {
        val now = System.currentTimeMillis()
        val cutoff = now - seconds * 1000L
        // 用户点选上屏后标记 consumed 不再显示
        return _clipboardItems.value.filter { it.timestamp >= cutoff && !it.consumed }
    }

    /**
     * 标记指定文本的剪贴板条目为"已消费"（候选栏不再显示）。
     * 匹配最近一条未消费的相同文本，避免影响历史重复条目。
     */
    fun markConsumed(text: String) {
        scope.launch {
            val item = _clipboardItems.value
                .filter { it.text == text && !it.consumed }
                .maxByOrNull { it.timestamp } ?: return@launch
            dao.markConsumed(item.id)
        }
    }

    /** 按 id 标记已消费（图片条目经面板点选后调用）。 */
    fun markConsumedById(id: Long) {
        scope.launch {
            dao.markConsumed(id)
        }
    }

    /** 图片条目的绝对文件（不存在返回 null）——点选回写/直插用。 */
    fun imageFileOf(item: ClipboardItem): File? {
        if (!item.isImage || item.imagePath.isEmpty()) return null
        val file = imageStore.absoluteFile(item.imagePath)
        return if (file.exists()) file else null
    }

    /**
     * 图片占用快照（剪贴板设置页展示"已用空间"）。
     * @param count 图片条目数
     * @param bytes 条目登记的原始字节合计（用于与容量上限对比）
     * @param fileBytes 图片目录实际占用（含残留，用于展示清理效果）
     */
    suspend fun imageUsage(): ImageUsage = imageMutex.withLock {
        ImageUsage(
            count = dao.countImages(),
            bytes = dao.totalImageBytes(),
            fileBytes = imageStore.usedBytes(),
        )
    }

    /**
     * 把图片写入系统剪贴板。
     *
     * @param imagePath 图片文件绝对路径
     * @param label ClipData 标签（默认沿用 Emoji 路径的 emoji_image）
     * @param shareCacheCopy true（Emoji 原行为）：先复制到 `cacheDir/emoji_cache` 再共享，
     *   避免把插件资源/临时文件直接暴露；false（剪贴板图片）：直接共享 `files/clipboard_images`
     *   原文件，配合淘汰保护集保证粘贴方读取期间文件不被回收。
     */
    fun copyImageToSystemClipboard(
        imagePath: String,
        label: String = "emoji_image",
        shareCacheCopy: Boolean = true,
    ): Boolean {
        return try {
            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                Log.e(TAG, "Image file not found: $imagePath")
                return false
            }

            val sharedFile = if (shareCacheCopy) {
                val cacheDir = File(context.cacheDir, "emoji_cache")
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }
                val cacheFile = File(cacheDir, imageFile.name)
                FileInputStream(imageFile).use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                cacheFile
            } else {
                imageFile
            }

            val uri = getContentUriForImage(sharedFile, imageFile) ?: return false

            val clip = ClipData.newUri(context.contentResolver, label, uri)
            androidClipboardManager.setPrimaryClip(clip)

            // 只有共享原文件（files/clipboard_images）时才需要淘汰保护；
            // cache 副本不受图片配额约束，无需保护
            selfClipboardImagePath = if (shareCacheCopy) {
                null
            } else {
                ClipboardImageStore.relativePathOf(context.filesDir, sharedFile)
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy image to clipboard", e)
            false
        }
    }

    /**
     * 拖拽发送用：图片的可共享 content URI。
     * FileProvider 优先（零写入，接收方靠拖拽的 URI 读授权）；仅 FileProvider 抛错时走
     * [MediaBridge] 中转（Download/Xime，不写相册，用完即删）。取不到返回 null（回退长按菜单）。
     */
    fun dragImageUri(item: ClipboardItem): Uri? {
        val file = imageFileOf(item) ?: return null
        try {
            return fileProviderUri(file)
        } catch (e: Exception) {
            Log.w(TAG, "FileProvider getUriForFile failed for drag, falling back to MediaStore bridge", e)
        }
        val mime = ClipboardImageStore.mimeForExtension(file.extension) ?: "image/jpeg"
        return MediaBridge.insert(context, file, mime)
    }

    /**
     * 生成图片 content URI（复制到系统剪贴板 / Emoji 共享）：FileProvider 优先，
     * 抛错时降级 [MediaBridge] 中转（不写相册）。
     *
     * @param sharedFile 共享给外部的文件（FileProvider 用）
     * @param sourceFile 原始图片文件（中转与 MIME 推断用）
     */
    private fun getContentUriForImage(sharedFile: File, sourceFile: File = sharedFile): Uri? {
        try {
            return fileProviderUri(sharedFile)
        } catch (e: Exception) {
            Log.w(TAG, "FileProvider getUriForFile failed, falling back to MediaStore bridge", e)
        }
        val mime = ClipboardImageStore.mimeForExtension(sourceFile.extension) ?: "image/jpeg"
        return MediaBridge.insert(context, sourceFile, mime)
    }

    /** FileProvider URI（`files-path` 已覆盖 `files/clipboard_images` 与 `cache/emoji_cache`）。 */
    private fun fileProviderUri(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
}
