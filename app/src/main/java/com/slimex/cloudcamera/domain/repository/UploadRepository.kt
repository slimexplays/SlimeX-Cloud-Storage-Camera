package com.slimex.cloudcamera.domain.repository

import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.MediaKind
import com.slimex.cloudcamera.domain.model.UploadQueueSummary
import com.slimex.cloudcamera.domain.model.UploadTask
import kotlinx.coroutines.flow.StateFlow

/** Persistent upload queue backed by the Supabase Storage REST API. */
interface UploadRepository {
    val tasks: StateFlow<List<UploadTask>>
    val summary: StateFlow<UploadQueueSummary>

    suspend fun enqueue(
        filePath: String,
        kind: MediaKind,
        durationMillis: Long? = null,
        capturedAt: Long = System.currentTimeMillis(),
        startImmediately: Boolean = true,
    ): AppResult<String>

    suspend fun retry(taskId: String)
    suspend fun cancel(taskId: String)
    suspend fun clearFinished()

    /** Restarts every pending job — used on app start and by the retry worker. */
    suspend fun resumePending()
}
