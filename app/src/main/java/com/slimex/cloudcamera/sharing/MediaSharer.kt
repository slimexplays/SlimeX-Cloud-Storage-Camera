package com.slimex.cloudcamera.sharing

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.CloudMedia
import com.slimex.cloudcamera.domain.repository.CloudGalleryRepository
import com.slimex.cloudcamera.storage.StagingStore
import com.slimex.cloudcamera.workers.ShareCleanupWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shares a decrypted file **only** through the Android Sharesheet.
 *
 * There is no public URL and no "anyone with the link" flow: the file is decrypted into the
 * app's private cache, handed over as a `content://` URI with a temporary read grant, and cleaned
 * up later by [ShareCleanupWorker]. WhatsApp (or whichever app receives it) may keep its own copy
 * once it has been handed the bytes — that is outside this app's control and is documented in the
 * README.
 */
@Singleton
class MediaSharer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gallery: CloudGalleryRepository,
    private val staging: StagingStore,
) {

    suspend fun share(media: CloudMedia): AppResult<Unit> {
        val prepared = when (val result = gallery.prepareForShare(media)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return result
        }

        val uri = runCatching {
            FileProvider.getUriForFile(context, authority(), prepared)
        }.getOrNull() ?: return AppResult.Failure(
            AppFailure.storage("The media could not be exposed to the Sharesheet."),
        )

        val mimeType = media.mimeType ?: media.mediaType.mimeType
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, media.fileName)
            clipData = ClipData.newRawUri(media.fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(send, "Share ${if (media.mediaType.value == "video") "video" else "photo"}")
            .apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        return try {
            context.startActivity(chooser)
            // Deliberately delayed: the receiving app needs the file to stay readable.
            ShareCleanupWorker.schedule(context, delayMinutes = SHARE_TTL_MINUTES)
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            runCatching { prepared.delete() }
            AppResult.Failure(AppFailure.unknown("No app could handle the share request.", t))
        }
    }

    /** Removes share copies older than the TTL; returns the number of files deleted. */
    fun cleanupExpired(now: Long = System.currentTimeMillis()): Int {
        val cutoff = now - SHARE_TTL_MINUTES * 60_000L
        var deleted = 0
        staging.shareDir().listFiles()?.forEach { file: File ->
            if (file.isFile && file.lastModified() < cutoff) {
                if (runCatching { file.delete() }.getOrDefault(false)) deleted++
            }
        }
        return deleted
    }

    /** True while a shared copy is still within its TTL (the worker uses this to reschedule). */
    fun hasFreshShareCopies(now: Long = System.currentTimeMillis()): Boolean {
        val cutoff = now - SHARE_TTL_MINUTES * 60_000L
        return staging.shareDir().listFiles()?.any { it.isFile && it.lastModified() >= cutoff } == true
    }

    private fun authority(): String = "${context.packageName}.fileprovider"

    private companion object {
        const val SHARE_TTL_MINUTES = 15L
    }
}
