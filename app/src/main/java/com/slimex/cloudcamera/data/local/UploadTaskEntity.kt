package com.slimex.cloudcamera.data.local

import com.slimex.cloudcamera.domain.model.MediaKind
import com.slimex.cloudcamera.domain.model.UploadStatus
import com.slimex.cloudcamera.domain.model.UploadTask
import kotlinx.serialization.Serializable

/** Persisted shape of [UploadTask] (a JSON list inside DataStore). */
@Serializable
data class UploadTaskEntity(
    val id: String,
    val mediaId: String,
    val filePath: String,
    val fileName: String,
    val kind: String,
    val sizeBytes: Long,
    val mimeType: String,
    val status: String,
    val progress: Float = 0f,
    val attempts: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val errorMessage: String? = null,
    val encryptedPath: String? = null,
    val encryptedSizeBytes: Long = 0,
    val checksum: String? = null,
    val remotePath: String? = null,
    val userId: String? = null,
    val capturedAt: Long,
    val durationMillis: Long? = null,
    val thumbnailLocalPath: String? = null,
    val remoteThumbnailPath: String? = null,
)

fun UploadTask.toEntity(): UploadTaskEntity = UploadTaskEntity(
    id = id,
    mediaId = mediaId,
    filePath = filePath,
    fileName = fileName,
    kind = kind.value,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    status = status.name,
    progress = progress,
    attempts = attempts,
    createdAt = createdAt,
    updatedAt = updatedAt,
    errorMessage = errorMessage,
    encryptedPath = encryptedPath,
    encryptedSizeBytes = encryptedSizeBytes,
    checksum = checksum,
    remotePath = remotePath,
    userId = userId,
    capturedAt = capturedAt,
    durationMillis = durationMillis,
    thumbnailLocalPath = thumbnailLocalPath,
    remoteThumbnailPath = remoteThumbnailPath,
)

fun UploadTaskEntity.toDomain(): UploadTask = UploadTask(
    id = id,
    mediaId = mediaId,
    filePath = filePath,
    fileName = fileName,
    kind = MediaKind.fromValue(kind),
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    status = runCatching { UploadStatus.valueOf(status) }.getOrDefault(UploadStatus.QUEUED),
    progress = progress.coerceIn(0f, 1f),
    attempts = attempts,
    createdAt = createdAt,
    updatedAt = updatedAt,
    errorMessage = errorMessage,
    encryptedPath = encryptedPath,
    encryptedSizeBytes = encryptedSizeBytes,
    checksum = checksum,
    remotePath = remotePath,
    userId = userId,
    capturedAt = capturedAt,
    durationMillis = durationMillis,
    thumbnailLocalPath = thumbnailLocalPath,
    remoteThumbnailPath = remoteThumbnailPath,
)

/** Task that can be attempted right now (a retryable failure is attempted by the worker). */
fun UploadTask.isActionable(): Boolean =
    status == UploadStatus.QUEUED ||
        status == UploadStatus.WAITING_FOR_NETWORK ||
        status == UploadStatus.WAITING_FOR_CONFIG ||
        status == UploadStatus.WAITING_FOR_AUTH ||
        status == UploadStatus.FAILED

fun UploadTask.isBlocked(): Boolean =
    status == UploadStatus.WAITING_FOR_NETWORK ||
        status == UploadStatus.WAITING_FOR_CONFIG ||
        status == UploadStatus.WAITING_FOR_AUTH
