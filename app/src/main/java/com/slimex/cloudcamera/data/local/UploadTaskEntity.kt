package com.slimex.cloudcamera.data.local

import com.slimex.cloudcamera.domain.model.MediaKind
import com.slimex.cloudcamera.domain.model.UploadStatus
import com.slimex.cloudcamera.domain.model.UploadTask
import kotlinx.serialization.Serializable

/** Persisted shape of [UploadTask] (DataStore JSON). Kept separate from the domain model. */
@Serializable
data class UploadTaskEntity(
    val id: String,
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
    val remotePath: String? = null,
    val capturedAt: Long,
    val durationMillis: Long? = null,
)

fun UploadTask.toEntity(): UploadTaskEntity = UploadTaskEntity(
    id = id,
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
    remotePath = remotePath,
    capturedAt = capturedAt,
    durationMillis = durationMillis,
)

fun UploadTaskEntity.toDomain(): UploadTask = UploadTask(
    id = id,
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
    remotePath = remotePath,
    capturedAt = capturedAt,
    durationMillis = durationMillis,
)
