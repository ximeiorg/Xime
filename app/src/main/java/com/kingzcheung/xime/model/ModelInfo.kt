package com.kingzcheung.xime.model

enum class ModelCategory {
    PREDICTION,
    ASR,
    HANDWRITING,
    OTHER
}

data class ModelFile(
    val name: String,
    val downloadUrl: String
)

/** 单个模型版本：文件清单 + 归档 + 元信息。 */
data class ModelVersion(
    val version: String = "",
    val date: String = "",
    val changelog: String = "",
    val files: List<ModelFile> = emptyList(),
    val archiveUrl: String? = null,
    val size: String = "",
    val sha256: String = ""
)

data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val category: ModelCategory,
    val versions: List<ModelVersion> = emptyList(),
    val size: String = "",
    /** 支持的平台；空 = 索引未声明，视为不限。 */
    val platforms: List<String> = emptyList()
) {
    /** 是否可在当前（Android）端使用：未声明视为支持，声明了则须含 android。 */
    val availableOnAndroid: Boolean
        get() = platforms.isEmpty() || "android" in platforms

    /** 默认取第一个版本（索引中第一个为最新）。 */
    fun resolvedVersion(): ModelVersion? = versions.firstOrNull()

    /** 兼容访问器：委托到默认版本，现有 ModelManager/ModelDownloader 逻辑不感知版本。 */
    val files: List<ModelFile> get() = resolvedVersion()?.files ?: emptyList()
    val archiveUrl: String? get() = resolvedVersion()?.archiveUrl
}

sealed class ModelDownloadState {
    data object Idle : ModelDownloadState()
    data class Downloading(
        val progress: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : ModelDownloadState()
    data class Error(val message: String) : ModelDownloadState()
    data object Complete : ModelDownloadState()
}
