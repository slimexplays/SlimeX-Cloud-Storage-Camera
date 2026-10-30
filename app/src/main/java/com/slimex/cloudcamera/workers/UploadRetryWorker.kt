package com.slimex.cloudcamera.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.slimex.cloudcamera.domain.repository.UploadRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Resumes pending uploads when the app is not in the foreground.
 * Real transfer work happens in the shared upload engine; this worker only restarts it.
 */
@HiltWorker
class UploadRetryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val uploadRepository: UploadRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        uploadRepository.resumePending()
        Result.success()
    } catch (t: Throwable) {
        Result.retry()
    }
}
