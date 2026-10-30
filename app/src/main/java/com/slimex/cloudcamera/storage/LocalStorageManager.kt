package com.slimex.cloudcamera.storage

import android.content.Context
import android.os.StatFs
import com.slimex.cloudcamera.core.constants.AppConstants
import com.slimex.cloudcamera.domain.model.LocalMedia
import com.slimex.cloudcamera.domain.model.LocalStorageSummary
import com.slimex.cloudcamera.domain.model.MediaKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the app-private media tree:
 *
 *   /data/data/com.slimex.cloudcamera/files/media/photos
 *   /data/data/com.slimex.cloudcamera/files/media/videos
 *   /data/data/com.slimex.cloudcamera/files/media/thumbs
 *
 * Nothing is written to shared or public storage, so no storage permission is needed.
 */
@Singleton
class LocalStorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val rootDir: File
        get() = File(context.filesDir, AppConstants.MEDIA_ROOT_DIR).ensureDirectory()

    fun photosDir(): File = File(rootDir, AppConstants.PHOTOS_DIR).ensureDirectory()

    fun videosDir(): File = File(rootDir, AppConstants.VIDEOS_DIR).ensureDirectory()

    fun thumbsDir(): File = File(rootDir, AppConstants.THUMBS_DIR).ensureDirectory()

    fun newPhotoFile(): File = File(photosDir(), buildName(MediaKind.PHOTO))

    fun newVideoFile(): File = File(videosDir(), buildName(MediaKind.VIDEO))

    fun listMedia(): List<LocalMedia> {
        val photos = readDirectory(photosDir(), MediaKind.PHOTO)
        val videos = readDirectory(videosDir(), MediaKind.VIDEO)
        return (photos + videos).sortedByDescending { it.createdAtMillis }
    }

    fun deleteMedia(media: LocalMedia): Boolean = runCatching { File(media.filePath).delete() }.getOrDefault(false)

    fun deleteAllMedia(): Int {
        var deleted = 0
        listMedia().forEach { item -> if (deleteMedia(item)) deleted++ }
        return deleted
    }

    fun clearThumbnails(): Long {
        val dir = thumbsDir()
        var freed = 0L
        dir.listFiles()?.forEach { file ->
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) freed += size
        }
        return freed
    }

    fun summary(): LocalStorageSummary {
        val photos = readDirectory(photosDir(), MediaKind.PHOTO)
        val videos = readDirectory(videosDir(), MediaKind.VIDEO)
        val thumbsSize = thumbsDir().listFiles()?.sumOf { it.length() } ?: 0L
        val stat = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
        return LocalStorageSummary(
            photoCount = photos.size,
            videoCount = videos.size,
            photoBytes = photos.sumOf { it.sizeBytes },
            videoBytes = videos.sumOf { it.sizeBytes },
            thumbnailBytes = thumbsSize,
            deviceFreeBytes = stat?.availableBytes ?: 0L,
            deviceTotalBytes = stat?.totalBytes ?: 0L,
        )
    }

    private fun readDirectory(dir: File, kind: MediaKind): List<LocalMedia> =
        dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.length() > 0 }
            ?.filter { it.name.substringAfterLast('.', "").equals(kind.extension, ignoreCase = true) }
            ?.map { file ->
                LocalMedia(
                    filePath = file.absolutePath,
                    fileName = file.name,
                    kind = kind,
                    sizeBytes = file.length(),
                    createdAtMillis = file.lastModified(),
                )
            }
            ?.toList()
            ?: emptyList()

    private fun buildName(kind: MediaKind): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val suffix = UUID.randomUUID().toString().take(6)
        return "SLX_${stamp}_$suffix.${kind.extension}"
    }

    private fun File.ensureDirectory(): File {
        if (!exists()) runCatching { mkdirs() }
        return this
    }
}
