package com.slimex.cloudcamera.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.slimex.cloudcamera.domain.model.UploadStatus
import com.slimex.cloudcamera.domain.repository.UploadRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Drains the persistent upload queue.
 *
 * WorkManager applies the network constraint and exponential backoff; this worker only asks the
 * repository to process whatever is runnable, then retries itself while work remains.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val uploadRepository: UploadRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        uploadRepository.resumePending(retryFailed = true)
        val remaining = uploadRepository.drain()
        val stillWaitingForNetwork = uploadRepository.tasks.value
            .any { it.status == UploadStatus.WAITING_FOR_NETWORK }
        when {
            // WorkManager keeps the network constraint, so a retry fires when a connection is back.
            stillWaitingForNetwork && runAttemptCount < MAX_RETRIES -> Result.retry()
            remaining > 0 && runAttemptCount < MAX_RETRIES -> Result.retry()
            else -> Result.success()
        }
    } catch (t: Throwable) {
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
    }

    private companion object {
        const val MAX_RETRIES = 6
    }
}
