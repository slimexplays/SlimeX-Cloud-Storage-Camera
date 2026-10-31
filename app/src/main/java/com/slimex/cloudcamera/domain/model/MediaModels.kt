package com.slimex.cloudcamera.domain.model

enum class MediaKind(val storageFolder: String, val mimeType: String, val extension: String) {
    PHOTO("photos", "image/jpeg", "jpg"),
    VIDEO("videos", "video/mp4", "mp4");

    companion object {
        fun fromValue(value: String?): MediaKind =
            if (value.equals("video", ignoreCase = true)) VIDEO else PHOTO
    }

    val value: String
        get() = name.lowercase()
}

/** A staged file that still lives in app-private internal storage (awaiting verified upload). */
data class LocalMedia(
    val filePath: String,
    val fileName: String,
    val kind: MediaKind,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val durationMillis: Long? = null,
    val encrypted: Boolean = false,
) {
    val id: String get() = filePath
}

/**
 * Storage figures for the dashboard.
 *
 * Staged media is *pending* data: it is never deleted by cache clean-up, only after a
 * verified upload (handled by the upload pipeline).
 */
data class LocalStorageSummary(
    val stagedPhotoCount: Int = 0,
    val stagedVideoCount: Int = 0,
    val stagedBytes: Long = 0,
    val encryptedBytes: Long = 0,
    val thumbnailBytes: Long = 0,
    val viewerCacheBytes: Long = 0,
    val shareCacheBytes: Long = 0,
    val deviceFreeBytes: Long = 0,
    val deviceTotalBytes: Long = 0,
) {
    val photoCount: Int get() = stagedPhotoCount
    val videoCount: Int get() = stagedVideoCount
    val pendingBytes: Long get() = stagedBytes + encryptedBytes
    val cacheBytes: Long get() = thumbnailBytes + viewerCacheBytes + shareCacheBytes
    val totalBytes: Long get() = pendingBytes + cacheBytes

    val usedFraction: Float
        get() = if (deviceTotalBytes <= 0) 0f
        else ((deviceTotalBytes - deviceFreeBytes).toFloat() / deviceTotalBytes).coerceIn(0f, 1f)
}

data class RemoteUsage(
    val assetCount: Int = 0,
    val photoCount: Int = 0,
    val videoCount: Int = 0,
    val totalBytes: Long = 0,
)
