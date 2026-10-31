package com.slimex.cloudcamera.domain.repository

import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.MediaKind
import com.slimex.cloudcamera.domain.model.UploadQueueSummary
import com.slimex.cloudcamera.domain.model.UploadTask
import kotlinx.coroutines.flow.StateFlow

/**
 * Persistent upload queue: captures are encrypted, transferred and verified in the background
 * and survive reboots, process death and connectivity loss.
 */
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

    /** Re-queues blocked jobs (and optionally exhausted retries) and schedules the worker. */
    suspend fun resumePending(retryFailed: Boolean = false)

    /** Processes everything that can run now; returns how many jobs remain queued. */
    suspend fun drain(): Int
}
