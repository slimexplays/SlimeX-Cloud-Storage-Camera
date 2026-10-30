package com.slimex.cloudcamera.domain.model

/** Lifecycle of a single upload job. Persisted so the queue survives process death. */
enum class UploadStatus {
    QUEUED,
    WAITING_FOR_NETWORK,
    WAITING_FOR_CONFIG,
    UPLOADING,
    UPLOADED,
    FAILED,
    CANCELLED;

    val isFinished: Boolean
        get() = this == UPLOADED || this == CANCELLED

    val isActive: Boolean
        get() = this == QUEUED || this == UPLOADING || this == WAITING_FOR_NETWORK || this == WAITING_FOR_CONFIG
}

data class UploadTask(
    val id: String,
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
    val remotePath: String? = null,
    val capturedAt: Long = System.currentTimeMillis(),
    val durationMillis: Long? = null,
) {
    val isVideo: Boolean get() = kind == MediaKind.VIDEO
}

data class UploadQueueSummary(
    val pending: Int = 0,
    val uploading: Int = 0,
    val uploaded: Int = 0,
    val failed: Int = 0,
    val totalBytes: Long = 0,
    val uploadedBytes: Long = 0,
) {
    val active: Int get() = pending + uploading
    val hasWork: Boolean get() = pending > 0 || uploading > 0
}
