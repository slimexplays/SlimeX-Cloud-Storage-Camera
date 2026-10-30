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

/** A file that lives in app-private internal storage. */
data class LocalMedia(
    val filePath: String,
    val fileName: String,
    val kind: MediaKind,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val durationMillis: Long? = null,
) {
    val id: String get() = filePath
}

/** Local + remote storage figures shown on the Storage screen. */
data class LocalStorageSummary(
    val photoCount: Int = 0,
    val videoCount: Int = 0,
    val photoBytes: Long = 0,
    val videoBytes: Long = 0,
    val thumbnailBytes: Long = 0,
    val deviceFreeBytes: Long = 0,
    val deviceTotalBytes: Long = 0,
) {
    val totalMediaBytes: Long get() = photoBytes + videoBytes
    val totalBytes: Long get() = totalMediaBytes + thumbnailBytes

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
