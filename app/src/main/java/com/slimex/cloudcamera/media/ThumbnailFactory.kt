package com.slimex.cloudcamera.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import com.slimex.cloudcamera.core.constants.AppConstants
import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.util.SecureLog
import com.slimex.cloudcamera.domain.model.MediaKind
import com.slimex.cloudcamera.storage.StagingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the small JPEG previews used by the gallery.
 *
 * Thumbnails are generated from the *plaintext* right after capture (or after a decrypt), then
 * encrypted as their own bucket object, so the gallery can render a grid without ever fetching
 * a full-size photo or video.
 */
@Singleton
class ThumbnailFactory @Inject constructor(
    private val staging: StagingStore,
) {

    suspend fun create(source: File, kind: MediaKind, target: File): AppResult<File> =
        when (kind) {
            MediaKind.PHOTO -> fromPhoto(source, target)
            MediaKind.VIDEO -> fromVideo(source, target)
        }

    /** Returns the local thumbnail for [mediaId], creating it when it is missing. */
    suspend fun ensure(source: File, kind: MediaKind, mediaId: String): File? {
        val target = staging.thumbnailFile(mediaId)
        if (target.exists() && target.length() > 0) return target
        return when (val result = create(source, kind, target)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> {
                SecureLog.w(TAG, "Thumbnail failed: ${result.error.message}")
                null
            }
        }
    }

    private suspend fun fromPhoto(source: File, target: File): AppResult<File> =
        withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return@withContext AppResult.Failure(AppFailure.storage("Could not read the photo."))
                }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeFile(source.absolutePath, options)
                    ?: return@withContext AppResult.Failure(AppFailure.storage("Could not decode the photo."))

                val scaled = scaleToFit(decoded, AppConstants.THUMBNAIL_MAX_PX)
                val rotated = rotateToExif(scaled, readOrientation(source))
                writeJpeg(rotated, target)
                if (rotated !== decoded) decoded.recycle()
                if (scaled !== rotated) scaled.recycle()
                rotated.recycle()
                AppResult.Success(target)
            } catch (t: Throwable) {
                AppResult.Failure(AppFailure.storage(t.message ?: "Thumbnail generation failed.", t))
            }
        }

    private suspend fun fromVideo(source: File, target: File): AppResult<File> =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(source.absolutePath)
                val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return@withContext AppResult.Failure(AppFailure.storage("Could not read the video frame."))
                val scaled = scaleToFit(frame, AppConstants.THUMBNAIL_MAX_PX)
                writeJpeg(scaled, target)
                if (scaled !== frame) scaled.recycle()
                frame.recycle()
                AppResult.Success(target)
            } catch (t: Throwable) {
                AppResult.Failure(AppFailure.storage(t.message ?: "Video thumbnail failed.", t))
            } finally {
                runCatching { retriever.release() }
            }
        }

    private fun readOrientation(file: File): Int = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun rotateToExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = android.graphics.Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun scaleToFit(bitmap: Bitmap, maxPx: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxPx) return bitmap
        val ratio = maxPx.toFloat() / longest.toFloat()
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        val target = AppConstants.THUMBNAIL_MAX_PX * 2
        while (longest / 2 >= target) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun writeJpeg(bitmap: Bitmap, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        FileOutputStream(target).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, output)
        }
    }

    private companion object {
        const val TAG = "ThumbnailFactory"
        const val THUMB_QUALITY = 82
    }
}
