package com.slimex.cloudcamera.domain.model

/** Lifecycle of a single upload job. Persisted so the queue survives process death. */
enum class UploadStatus {
    QUEUED,
    ENCRYPTING,
    WAITING_FOR_NETWORK,
    WAITING_FOR_CONFIG,
    WAITING_FOR_AUTH,
    UPLOADING,
    VERIFYING,
    UPLOADED,
    FAILED,
    CANCELLED;

    val isFinished: Boolean
        get() = this == UPLOADED || this == CANCELLED

    /**
     * True only for states in which the pipeline is doing (or about to do) work.
     * `WAITING_FOR_*` states are blocked, not active: nothing is progressing until the network,
     * the configuration or the session comes back.
     */
    val isActive: Boolean
        get() = this == QUEUED || this == ENCRYPTING || this == UPLOADING || this == VERIFYING

    val isRunning: Boolean
        get() = this == ENCRYPTING || this == UPLOADING || this == VERIFYING
}

data class UploadTask(
    val id: String,
    val mediaId: String,
    val filePath: String,
    val fileName: String,
    val kind: MediaKind,
    val sizeBytes: Long,
    val mimeType: String,
    val status: UploadStatus = UploadStatus.QUEUED,
    val progress: Float = 0f,
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
    val encryptedPath: String? = null,
    val encryptedSizeBytes: Long = 0,
    val checksum: String? = null,
    val remotePath: String? = null,
    val userId: String? = null,
    val capturedAt: Long = System.currentTimeMillis(),
    val durationMillis: Long? = null,
    /** Local thumbnail of the original, shown in the queue while the upload runs. */
    val thumbnailLocalPath: String? = null,
    /** Encrypted thumbnail sidecar object in the bucket (empty when none was produced). */
    val remoteThumbnailPath: String? = null,
) {
    val isVideo: Boolean get() = kind == MediaKind.VIDEO

    /** Local files may only be removed once the cloud copy has been verified. */
    val hasLocalFiles: Boolean
        get() = filePath.isNotEmpty() || encryptedPath != null
}

data class UploadQueueSummary(
    val pending: Int = 0,
    val uploading: Int = 0,
    val uploaded: Int = 0,
    val failed: Int = 0,
    val paused: Int = 0,
    val cancelled: Int = 0,
    val totalBytes: Long = 0,
    val uploadedBytes: Long = 0,
) {
    val active: Int get() = pending + uploading
    val hasWork: Boolean get() = pending > 0 || uploading > 0
}
