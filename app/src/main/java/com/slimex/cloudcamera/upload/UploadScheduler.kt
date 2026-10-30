package com.slimex.cloudcamera.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.slimex.cloudcamera.workers.UploadRetryWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules a network-constrained safety-net worker.
 *
 * The in-process upload engine does the actual transfer (so progress is live in the UI);
 * this worker simply resumes anything still pending after the process was killed.
 */
@Singleton
class UploadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun scheduleRetry(delaySeconds: Long = 30) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<UploadRetryWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInitialDelay(delaySeconds.coerceAtLeast(0), TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }

    companion object {
        const val TAG = "slimex-upload"
        const val UNIQUE_NAME = "slimex-upload-retry"
    }
}
