package com.kingzcheung.xime.settings

import com.kingzcheung.xime.R
import com.kingzcheung.xime.lexicon.LexiconFormats

import com.kingzcheung.xime.i18n.tr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class UploadResult(
    val fileName: String,
    val success: Boolean,
    val error: String? = null,
    /** 导入管线给出的提示（例如「未挂载，需选择方案」）。 */
    val message: String? = null,
    /** 落点类型：lexicon / scheme_package / config / font / image / file / plugin。 */
    val kind: String? = null,
)

@Serializable
data class FileNode(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0,
    val mtime: Long = 0,
    val children: List<FileNode>? = null,
)

private val FileTreeSerializer = kotlinx.serialization.serializer<FileNode>()

/** 共享 Json 实例，用于解析 delete 请求体。 */
private val configJson: Json = Json { ignoreUnknownKeys = true }

class WirelessImportHelper(
    private val context: Context,
    /** 从词库页进入时预先选好的方案；网页上传的词库文件直接挂到这些方案。 */
    private val lexiconSchemeIds: List<String> = emptyList(),
    /** 词库页专用模式：页面只接受词库文件，其它类型直接拒绝。 */
    private val lexiconOnly: Boolean = lexiconSchemeIds.isNotEmpty(),
) {
    private var server: EmbeddedServer<*, *>? = null

    private val _uploadResults = Channel<UploadResult>(Channel.BUFFERED)
    val uploadResults: Flow<UploadResult> = _uploadResults.receiveAsFlow()

    fun getLocalIpAddress(): String? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wifi?.connectionInfo?.ipAddress ?: return null
            String.format("%d.%d.%d.%d",
                ip and 0xff,
                (ip shr 8) and 0xff,
                (ip shr 16) and 0xff,
                (ip shr 24) and 0xff
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing ACCESS_WIFI_STATE permission", e)
            null
        }
    }

    fun findAvailablePort(): Int {
        for (port in 5000..5099) {
            try {
                val sock = java.net.ServerSocket()
                sock.bind(java.net.InetSocketAddress(port))
                sock.close()
                return port
            } catch (_: Exception) { }
        }
        return (5000..5099).firstOrNull { port ->
            try {
                java.net.ServerSocket(port).use { true }
            } catch (_: Exception) { false }
        } ?: 0
    }

    fun start(port: Int): String? {
        if (server != null) return null
        val ip = getLocalIpAddress() ?: return null
        val url = "http://$ip:$port"

        // watchPaths 置空：ktor 的 stop() 会无条件 cleanupWatcher()，访问 lazy watcher 会在
        // Android 上凭空创建再销毁一个平台 WatchService（自动重载并未启用），其 finalizer
        // 随 GC 抛 ClosedWatchServiceException 刷日志；置空后该路径完全不触发。
        server = embeddedServer(CIO, port = port, watchPaths = emptyList()) {
            routing {
                // React 前端：index.html 与静态资源打包在 assets/www/
                get("/") {
                    serveAsset(call, "www/index.html")
                }
                get("/assets/{file...}") {
                    val file = call.parameters.getAll("file")?.joinToString("/")
                    if (file.isNullOrBlank() || file.contains("..")) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    serveAsset(call, "www/assets/$file")
                }
                // 页面模式（手动输入地址也能拿到，不依赖 URL 查询串）
                get("/config") {
                    val mode = if (lexiconOnly) "lexicon" else "any"
                    call.respondText(
                        """{"mode":"$mode"}""",
                        ContentType.Application.Json,
                    )
                }
                // 返回 app 数据目录树（filesDir 下递归），供前端右侧展示
                get("/tree") {
                    val root = context.filesDir
                    if (root == null) {
                        call.respond(HttpStatusCode.InternalServerError)
                    } else {
                        val json = Json.encodeToString(FileTreeSerializer, buildNode(root))
                        call.respondText(json, ContentType.Application.Json)
                    }
                }
                // 读取文本文件内容（前端查看文件用）
                get("/read") {
                    val path = call.request.queryParameters["path"]
                    val f = safeResolve(path)
                    if (f == null || !f.isFile) {
                        call.respondText("""{"error":"File not found"}""",
                            ContentType.Application.Json, HttpStatusCode.NotFound)
                    } else {
                        val text = f.readBytes().toString(Charsets.UTF_8)
                        call.respondText(text, ContentType.Text.Plain, HttpStatusCode.OK)
                    }
                }
                // 下载文件（流式，避免大文件 OOM）
                get("/download") {
                    val path = call.request.queryParameters["path"]
                    val f = safeResolve(path)
                    if (f == null || !f.isFile) {
                        call.respondText("""{"error":"File not found"}""",
                            ContentType.Application.Json, HttpStatusCode.NotFound)
                    } else {
                        call.response.header(
                            io.ktor.http.HttpHeaders.ContentDisposition,
                            "attachment; filename=\"${f.name}\""
                        )
                        call.respondFile(f)
                    }
                }
                // 删除文件或空目录
                post("/delete") {
                    val path = call.receiveText()
                    val p = try {
                        configJson.parseToJsonElement(path).jsonObject["path"]?.jsonPrimitive?.content
                    } catch (_: Exception) { null }
                    val f = safeResolve(p)
                    if (f == null) {
                        call.respondText("""{"success":false,"error":"Not found"}""",
                            ContentType.Application.Json, HttpStatusCode.NotFound)
                    } else {
                        val ok = if (f.isDirectory) f.listFiles()?.isEmpty() == true && f.delete()
                                 else f.delete()
                        call.respondText(
                            if (ok) """{"success":true}""" else """{"success":false,"error":"Delete failed"}""",
                            ContentType.Application.Json)
                    }
                }
                // 保存文本文件（浏览器端编辑器）。只允许 rime 目录下的配置/文本，
                // 编译产物、用户词典和二进制文件由策略层拒绝。
                post("/write") {
                    // JSON 转义可能让文本体积翻倍，给请求体留 8MB 上限，避免大包占内存
                    if ((call.request.contentLength() ?: 0L) > 8L * 1024 * 1024) {
                        call.respondText(
                            """{"success":false,"error":"Body too large"}""",
                            ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
                        return@post
                    }
                    val body = call.receiveText()
                    val obj = try {
                        configJson.parseToJsonElement(body).jsonObject
                    } catch (_: Exception) {
                        null
                    }
                    val rawPath = obj?.get("path")?.jsonPrimitive?.contentOrNull
                    val content = obj?.get("content")?.jsonPrimitive?.contentOrNull
                    if (rawPath.isNullOrBlank() || content == null) {
                        call.respondText(
                            """{"success":false,"error":"Missing path or content"}""",
                            ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    if (content.toByteArray(Charsets.UTF_8).size >
                        WirelessTextFilePolicy.MAX_WRITE_BYTES) {
                        call.respondText(
                            """{"success":false,"error":"File too large"}""",
                            ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
                        return@post
                    }
                    val f = safeResolve(rawPath)
                    val rimeDir = File(context.filesDir, "rime")
                    if (f == null || !WirelessTextFilePolicy.isWritableTarget(rimeDir, f)) {
                        call.respondText(
                            """{"success":false,"error":"Not writable"}""",
                            ContentType.Application.Json, HttpStatusCode.Forbidden)
                        return@post
                    }
                    val ok = WirelessTextFilePolicy.writeTextAtomically(f, content)
                    call.respondText(
                        if (ok) """{"success":true}"""
                        else """{"success":false,"error":"Write failed"}""",
                        ContentType.Application.Json,
                        if (ok) HttpStatusCode.OK else HttpStatusCode.InternalServerError)
                }
                post("/upload") {
                    val rimeDir = File(context.filesDir, "rime")
                    rimeDir.mkdirs()
                    val tmpFile = File.createTempFile("upload_", ".tmp", rimeDir)
                    var lastName = ""
                            var lastKind = ""
                            var lastMessage = ""
                            var lastError = ""

                    try {
                        // 1. 流式写入临时文件（不占堆内存）
                        val ch = call.receiveChannel()
                        java.io.FileOutputStream(tmpFile).use { fos ->
                            val buf = ByteArray(8192)
                            while (!ch.isClosedForRead) {
                                val n = ch.readAvailable(buf, 0, buf.size)
                                if (n <= 0) break
                                fos.write(buf, 0, n)
                            }
                        }

                        // 2. 从临时文件解析 multipart（RandomAccessFile 流式读取）
                        val ctHeader = call.request.headers["Content-Type"] ?: ""
                        val boundary = ctHeader.split("boundary=").getOrNull(1)?.trim()
                        if (boundary.isNullOrEmpty()) {
                            call.respondText("""{"success":false,"error":"No boundary"}""",
                                ContentType.Application.Json, HttpStatusCode.BadRequest)
                            return@post
                        }
                        val boundaryBytes = ("\r\n--$boundary").toByteArray()
                        val firstBoundary = ("--$boundary").toByteArray()
                        val headerEndMarker = "\r\n\r\n".toByteArray()

                        var saved = false
                        java.io.RandomAccessFile(tmpFile, "r").use { raf ->
                            var pos = findBytes(raf, firstBoundary, 0)
                            if (pos < 0) return@use

                            while (true) {
                                val partStart = pos + firstBoundary.size
                                val nextB = findBytes(raf, boundaryBytes, partStart)
                                if (nextB < 0) break
                                val partEnd = nextB

                                // 解析 part header
                                raf.seek(partStart.toLong())
                                val hdr = ByteArray(4096)
                                val hdrLen = readUntil(raf, hdr, headerEndMarker)
                                if (hdrLen < 0) { pos = nextB + 2; continue }

                                val headerStr = hdr.decodeToString(0, hdrLen)
                                val fn = Regex("""filename="([^"]*)"""").find(headerStr)
                                val name = fn?.groupValues?.getOrNull(1)
                                if (name.isNullOrEmpty() || name == "blob") { pos = nextB + 2; continue }

                                lastName = name
                                val contentStart = raf.filePointer
                                val contentLen = partEnd - contentStart

                when {
                    lexiconOnly && LexiconFormats.candidates(name).isEmpty() -> {
                        lastError = tr(R.string.s_daoruciku_zhi_ziku)
                        _uploadResults.trySend(
                            UploadResult(
                                fileName = name,
                                success = false,
                                error = lastError,
                            )
                        )
                    }
                    ImportManager.isUploadSupported(name) -> {
                        // 直接按分段区间流式导入，不再复制一份 partFile：语法模型动辄数百 MB，
                        // 多一次整文件复制就多一份峰值占用。
                        val result = if (ImportManager.isPluginFile(name)) {
                            // 插件安装需要可随机访问的包文件，仍落一份临时文件
                            val partFile = File.createTempFile("part_", "_$name", context.cacheDir)
                            try {
                                partFile.outputStream().use { out ->
                                    copyRegion(raf, contentStart, contentLen, out.channel)
                                }
                                ImportManager.importFile(
                                    context, name, partFile,
                                    autoEnable = false,
                                    lexiconSchemeIds = lexiconSchemeIds,
                                )
                            } finally {
                                partFile.delete()
                            }
                        } else {
                            PartInputStream(tmpFile, contentStart, contentLen).use { stream ->
                                ImportManager.importStream(
                                    context, name, stream,
                                    autoEnable = false,
                                    lexiconSchemeIds = lexiconSchemeIds,
                                )
                            }
                        }
                        when (result) {
                            is ImportManager.ImportResult.Plugin -> {
                                PluginManager.loadEnabledPlugins()
                                saved = true
                                lastKind = "plugin"
                                lastMessage = tr(R.string.s_chajian_anzhuangchenggong, result.pluginInfo?.name ?: name)
                                _uploadResults.trySend(
                                    UploadResult(
                                        fileName = name,
                                        success = true,
                                        kind = lastKind,
                                        message = lastMessage,
                                    )
                                )
                            }
                            is ImportManager.ImportResult.Content -> {
                                saved = result.success
                                val kind = result.kind?.name?.lowercase()
                                val message = result.message ?: kindHint(result.kind) ?: ""
                                lastKind = kind ?: ""
                                lastMessage = message
                                _uploadResults.trySend(
                                    if (result.success) {
                                        UploadResult(
                                            fileName = name,
                                            success = true,
                                            message = message,
                                            kind = kind,
                                        )
                                    } else {
                                        lastError = message ?: tr(R.string.s_baocunshibai)
                                        UploadResult(
                                            fileName = name,
                                            success = false,
                                            error = lastError,
                                            kind = kind,
                                        )
                                    }
                                )
                            }
                            else -> {
                                lastError = tr(R.string.s_buzhichidewenjianleixing)
                                _uploadResults.trySend(
                                    UploadResult(fileName = name, success = false, error = lastError)
                                )
                            }
                        }
                    }
                    else -> {
                        lastError = tr(R.string.s_buzhichidewenjianleixing)
                        _uploadResults.trySend(
                            UploadResult(fileName = name, success = false, error = lastError)
                        )
                    }
                }
                                pos = nextB + 2
                            }
                        }

                        if (saved) {
                            call.respondText(
                                """{"success":true,"file":"${escapeJson(lastName)}","kind":"${escapeJson(lastKind)}","message":"${escapeJson(lastMessage)}"}""",
                                ContentType.Application.Json,
                            )
                        } else {
                            call.respondText(
                                """{"success":false,"error":"${escapeJson(lastError.ifBlank { "上传失败" })}"}""",
                                ContentType.Application.Json, HttpStatusCode.BadRequest,
                            )
                        }
                    } finally {
                        tmpFile.delete()
                    }
                }
            }
        }.start(wait = false)

        Log.i(TAG, "Server started at $url")
        return url
    }

    /** 按落点类型给出上传后的下一步提示；词库自己有更具体的文案。 */
    private fun kindHint(kind: ImportKind?): String? = when (kind) {
        ImportKind.SCHEME_PACKAGE -> tr(R.string.s_daoru_fanganbao_tishi)
        ImportKind.CONFIG -> tr(R.string.s_daoru_peizhi_tishi)
        ImportKind.GRAMMAR -> tr(R.string.s_daoru_yufa_moxing_tishi)
        ImportKind.FONT, ImportKind.IMAGE -> tr(R.string.s_daoru_ziyuan_tishi)
        else -> null
    }

    private fun escapeJson(value: String): String = buildString(value.length + 8) {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        Log.i(TAG, "Server stopped")
    }
    /** 从 app assets 读取静态文件并响应，避免把前端源码硬编码进 Kotlin。 */
    private suspend fun serveAsset(call: ApplicationCall, assetPath: String) {
        val mime = when {
            assetPath.endsWith(".html") -> ContentType.Text.Html
            assetPath.endsWith(".js") -> ContentType.Text.JavaScript
            assetPath.endsWith(".css") -> ContentType.Text.CSS
            else -> ContentType.Application.OctetStream
        }
        val bytes = try {
            context.assets.open(assetPath).use { it.readBytes() }
        } catch (e: java.io.IOException) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        // 页面/资源随 APK 更新，浏览器缓存会导致“说明还是旧的”，统一 no-store。
        call.response.header("Cache-Control", "no-store")
        call.respondBytes(bytes, mime)
    }

    /** 解析前端传来的路径（filesDir 的绝对路径或其相对路径），阻止目录穿越（..）。 */
    private fun safeResolve(rawPath: String?): File? {
        if (rawPath.isNullOrBlank()) return null
        val root = context.filesDir ?: return null
        val normalized = rawPath.replace('\\', '/')
        val candidate = File(normalized).canonicalFile
        val rootCanonical = root.canonicalFile
        return if (candidate.canonicalPath.startsWith(rootCanonical.canonicalPath)) {
            candidate
        } else {
            // 回退：按相对 filesDir 解析
            val rel = normalized.trimStart('/')
            if (rel.split('/').any { it == ".." }) return null
            val c2 = File(root, rel).canonicalFile
            if (c2.canonicalPath.startsWith(rootCanonical.canonicalPath)) c2 else null
        }
    }

    /** 递归构建数据目录树。 */
    private fun buildNode(file: File): FileNode {        if (!file.isDirectory) {
            return FileNode(
                name = file.name,
                path = file.path,
                isDir = false,
                size = file.length(),
                mtime = file.lastModified(),
            )
        }
        val children = file.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.map { buildNode(it) }
            .orEmpty()
        return FileNode(
            name = file.name,
            path = file.path,
            isDir = true,
            size = children.sumOf { it.size },
            mtime = file.lastModified(),
            children = children,
        )
    }

    val isRunning: Boolean get() = server != null

    /** 把 raf 的 [start, start+length) 区间完整写入 target。transferTo 可能只搬一部分，需循环补齐。 */
    private fun copyRegion(
        raf: java.io.RandomAccessFile,
        start: Long,
        length: Long,
        target: java.nio.channels.FileChannel,
    ) {
        var pos = start
        var remaining = length
        while (remaining > 0) {
            val n = raf.channel.transferTo(pos, remaining, target)
            if (n <= 0) throw java.io.IOException("transferTo stalled at offset $pos")
            pos += n
            remaining -= n
        }
    }

    /** 在 RandomAccessFile 中查找字节模式，返回起始位置，未找到返回 -1 */
    private fun findBytes(raf: java.io.RandomAccessFile, pattern: ByteArray, startPos: Long): Long {
        val buf = ByteArray(8192)
        var pos = startPos
        while (pos < raf.length()) {
            raf.seek(pos)
            val n = raf.read(buf)
            if (n <= 0) break
            val end = n - pattern.size
            for (i in 0..end) {
                var match = true
                for (j in pattern.indices) {
                    if (buf[i + j] != pattern[j]) { match = false; break }
                }
                if (match) return pos + i
            }
            pos += maxOf(1, n - pattern.size + 1)
        }
        return -1
    }

    /** 从 RandomAccessFile 当前位置读取到 endMarker 为止，返回读取的字节数 */
    private fun readUntil(raf: java.io.RandomAccessFile, buf: ByteArray, endMarker: ByteArray): Int {
        var bufPos = 0
        while (bufPos < buf.size) {
            val b = raf.read()
            if (b < 0) break
            buf[bufPos++] = b.toByte()
            // 检查是否匹配 endMarker
            if (bufPos >= endMarker.size) {
                var match = true
                for (i in endMarker.indices) {
                    if (buf[bufPos - endMarker.size + i] != endMarker[i]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    return bufPos - endMarker.size
                }
            }
        }
        return -1
    }

    fun generateQrCode(url: String, size: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y,
                        if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "QR generation failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "WirelessImport"
    }
}

/**
 * 只读取 multipart 请求体里某一段的输入流：上传的大文件（如 .gram 语法模型）
 * 直接按区间交给导入管线，不再整段复制成第二个临时文件。
 */
internal class PartInputStream(
    file: File,
    start: Long,
    private var remaining: Long,
) : java.io.InputStream() {

    private val raf = java.io.RandomAccessFile(file, "r").also { it.seek(start) }

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = raf.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        if (len == 0) return 0
        val want = minOf(len.toLong(), remaining).toInt()
        val n = raf.read(b, off, want)
        if (n > 0) remaining -= n
        return n
    }

    override fun close() {
        raf.close()
    }
}
