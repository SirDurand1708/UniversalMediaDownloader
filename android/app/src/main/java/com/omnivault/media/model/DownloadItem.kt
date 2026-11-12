package com.omnivault.media.model

import java.io.Serializable
import java.util.UUID

enum class DownloadStatus {
    QUEUED,
    FETCHING_METADATA,
    DOWNLOADING,
    PROCESSING,
    COMPLETED,
    ERROR,
    CANCELLED
}

data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    var title: String = "Descargando recurso...",
    val format: MediaFormat,
    val platform: PlatformType = PlatformType.fromUrl(url),
    var progress: Int = 0,
    var speedText: String = "0.0 MB/s",
    var status: DownloadStatus = DownloadStatus.QUEUED,
    var errorReason: String? = null,
    var localFilePath: String? = null,
    var fileSizeFormatted: String = "",
    var thumbnailUrl: String? = null,
    var artist: String? = null,
    var playCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) : Serializable
