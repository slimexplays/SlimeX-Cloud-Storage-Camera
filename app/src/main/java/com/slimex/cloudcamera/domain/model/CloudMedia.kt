package com.slimex.cloudcamera.domain.model

/** A row of `public.media_files` — the cloud copy of one captured photo or video. */
data class CloudMedia(
    val id: String,
    val userId: String,
    val storagePath: String,
    val fileName: String,
    val mimeType: String?,
    val fileSize: Long,
    val mediaType: MediaKind,
    val encrypted: Boolean,
    val checksum: String?,
    val createdAtMillis: Long,
    /**
     * Optional encrypted sidecar thumbnail so the gallery never downloads full media.
     * The matching AES-256-GCM key lives in the local key ring, never in this row.
     */
    val thumbnailPath: String? = null,
) {
    val hasThumbnail: Boolean get() = !thumbnailPath.isNullOrBlank()

    /** Only the path *inside* the bucket; the bucket name itself lives in the config. */
    val objectKey: String get() = storagePath.trimStart('/')
}

/** Gallery filters and sorting, mapped straight onto PostgREST query parameters. */
enum class MediaFilter(val label: String) {
    ALL("All"),
    PHOTOS("Photos"),
    VIDEOS("Videos");

    val mediaType: String?
        get() = when (this) {
            ALL -> null
            PHOTOS -> "photo"
            VIDEOS -> "video"
        }
}

enum class MediaSort(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    LARGEST("Largest first"),
    SMALLEST("Smallest first");

    val order: String
        get() = when (this) {
            NEWEST -> "created_at.desc"
            OLDEST -> "created_at.asc"
            LARGEST -> "file_size.desc"
            SMALLEST -> "file_size.asc"
        }
}
