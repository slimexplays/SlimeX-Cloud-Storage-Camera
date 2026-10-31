package com.slimex.cloudcamera.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.slimex.cloudcamera.workers.UploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the durable upload worker.
 *
 * WorkManager owns the network constraint and the exponential backoff, so an offline capture is
 * uploaded as soon as a valid connection returns — even if the app process was killed.
 */
@Singleton
class UploadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun schedule(wifiOnly: Boolean) {
        runCatching {
            val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(networkType)
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }

    fun cancelAll() {
        runCatching { WorkManager.getInstance(context).cancelAllWorkByTag(TAG) }
    }

    companion object {
        const val TAG = "slimex-upload"
        const val UNIQUE_NAME = "slimex-upload-queue"
    }
}
