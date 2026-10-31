package com.slimex.cloudcamera.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.slimex.cloudcamera.sharing.MediaSharer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Deletes decrypted share copies after a delay instead of the moment the Sharesheet opens —
 * deleting immediately would break the hand-off to WhatsApp.
 *
 * The worker also reschedules itself while younger copies remain, so nothing is stranded if the
 * device was off when the first run was due.
 */
@HiltWorker
class ShareCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val mediaSharer: MediaSharer,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        mediaSharer.cleanupExpired()
        if (mediaSharer.hasFreshShareCopies()) {
            schedule(applicationContext, delayMinutes = RECHECK_MINUTES)
        }
        Result.success()
    } catch (t: Throwable) {
        Result.retry()
    }

    companion object {
        private const val TAG = "slimex-share-cleanup"
        private const val UNIQUE_NAME = "slimex-share-cleanup"
        private const val RECHECK_MINUTES = 15L

        fun schedule(context: Context, delayMinutes: Long = 15L) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<ShareCleanupWorker>()
                    .setInitialDelay(delayMinutes.coerceAtLeast(1L), TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().build())
                    .setBackoffCriteria(
                        BackoffPolicy.LINEAR,
                        WorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS,
                    )
                    .addTag(TAG)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            }
        }
    }
}
