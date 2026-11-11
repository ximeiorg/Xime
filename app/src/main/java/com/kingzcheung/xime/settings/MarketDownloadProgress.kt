package com.kingzcheung.xime.settings

/**
 * 索引里的文本大小（"24.4 MB" / "380 MB" / "1.2GB" / "512 KB"）→ 字节数。
 *
 * 索引体积由生成脚本抓取，可能滞后或缺失 [DownloadItem.sizeBytes]，此时只能退回文本估算。
 * 解析失败（空串、纯描述文本）返回 null，让调用方按“未知大小”处理而不是当成 0 参与求和。
 */
internal fun parseSizeText(raw: String?): Long? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val match = SIZE_TEXT_PATTERN.find(text) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    if (value <= 0.0) return null
    val factor = when (match.groupValues[2].uppercase().firstOrNull()) {
        'K' -> 1024.0
        'M' -> 1024.0 * 1024.0
        'G' -> 1024.0 * 1024.0 * 1024.0
        'T' -> 1024.0 * 1024.0 * 1024.0 * 1024.0
        else -> 1.0 // 纯字节数，或 "B"
    }
    return (value * factor).toLong().takeIf { it > 0 }
}

private val SIZE_TEXT_PATTERN = Regex("""^([0-9]+(?:\.[0-9]+)?)\s*([KMGT]?i?B?)$""", RegexOption.IGNORE_CASE)

/**
 * 多文件下载的总进度聚合器。
 *
 * 一个方案常由多个文件组成（主包 + 语法模型等），索引给出每个文件的大小，但这份元数据
 * 可能过期：例如万象拼音标准版的语法模型实际 418,071,596 字节，索引 `sizeBytes` 却是
 * 123,026,414，直接用「已下载 / 声明总量」算会得到 298% 这种越界进度。
 *
 * 因此这里以声明总量为**起点**，在拿到 HTTP 响应的真实 Content-Length 后即时修正当前
 * 文件的份额；输出的「已下载」永远被夹在 `0..total` 内，比例单调不减且不超过 100%。
 * 修正总量会让已显示的比例变小，这里直接跳过这类回调，避免进度条倒退。
 */
internal class MarketDownloadProgress(
    declaredSizes: List<Long>,
    private val onProgress: (downloaded: Long, total: Long) -> Unit,
) {
    private val declared = declaredSizes.map { it.coerceAtLeast(0L) }
    private var completedBytes = 0L
    private var currentDeclared = 0L
    private var currentActual = 0L
    private var totalBytes = declared.sum()
    private var lastRatio = -1.0

    /** 开始下载第 [index] 个文件。 */
    fun startFile(index: Int) {
        currentDeclared = declared.getOrElse(index) { 0L }
        currentActual = 0L
    }

    /**
     * 单个文件的字节进度回调。[reportedTotal] 为本次响应的真实长度（未知时为 0 或 -1），
     * 与索引声明值不同则以真实值为准修正总量。
     */
    fun onBytes(read: Long, reportedTotal: Long) {
        val readBytes = read.coerceAtLeast(0L)
        if (reportedTotal > 0 && reportedTotal != currentActual) {
            totalBytes += reportedTotal - currentDeclared
            currentActual = reportedTotal
        }
        val downloaded = completedBytes + readBytes
        // 真实长度未知（chunked）时可能读到比声明更多：抬升总量而不是让进度超过 100%
        val total = maxOf(totalBytes, downloaded)
        if (total <= 0) return
        val ratio = downloaded.toDouble() / total
        if (ratio < lastRatio) return // 总量刚被放大，等进度追上再更新，避免倒退
        lastRatio = ratio
        onProgress(downloaded.coerceAtMost(total), total)
    }

    /** 当前文件下载完成，计入已完成字节。 */
    fun finishFile() {
        completedBytes += if (currentActual > 0) currentActual else currentDeclared
    }

    /** 全部文件结束，补一次 100%，避免声明值偏大时停在小数点后。 */
    fun complete() {
        if (totalBytes > 0) {
            lastRatio = 1.0
            onProgress(totalBytes, totalBytes)
        }
    }
}
