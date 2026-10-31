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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns every directory this app writes to.
 *
 *   files/pending/photos  – captured photos awaiting a verified upload (internal, private)
 *   files/pending/videos  – captured videos awaiting a verified upload
 *   cache/thumbs          – evictable thumbnails
 *   cache/viewer          – decrypted media currently open in the viewer
 *   cache/share           – decrypted copies handed to the Sharesheet (delayed clean-up)
 *   cache/work            – scratch space for encryption / verification
 *
 * Nothing ever goes to DCIM, Pictures, Movies or MediaStore, so the app needs no storage
 * permission and no other app can read a capture before it is explicitly shared.
 *
 * Pending files are *never* removed by cache clean-up: only the upload pipeline deletes them,
 * and only after the cloud copy has been verified.
 */
@Singleton
class StagingStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val filesRoot: File
        get() = File(context.filesDir, AppConstants.STAGING_DIR).ensureDirectory()

    private val cacheRoot: File
        get() = context.cacheDir.ensureDirectory()

    fun photosDir(): File = File(filesRoot, "photos").ensureDirectory()

    fun videosDir(): File = File(filesRoot, "videos").ensureDirectory()

    fun thumbsDir(): File = File(cacheRoot, AppConstants.THUMBS_DIR).ensureDirectory()

    fun viewerDir(): File = File(cacheRoot, AppConstants.VIEWER_DIR).ensureDirectory()

    fun shareDir(): File = File(cacheRoot, AppConstants.SHARE_DIR).ensureDirectory()

    fun workDir(): File = File(cacheRoot, AppConstants.WORK_DIR).ensureDirectory()

    fun newCaptureFile(kind: MediaKind): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val suffix = java.util.UUID.randomUUID().toString().take(8)
        val name = "SLX_${stamp}_$suffix.${kind.extension}"
        return File(if (kind == MediaKind.VIDEO) videosDir() else photosDir(), name)
    }

    fun encryptedCopyOf(source: File): File =
        File(source.parentFile ?: photosDir(), source.nameWithoutExtension + "." + AppConstants.OBJECT_EXTENSION)

    fun thumbnailFile(mediaId: String): File = File(thumbsDir(), "$mediaId.jpg")

    fun viewerFile(mediaId: String, kind: MediaKind): File = File(viewerDir(), "$mediaId.${kind.extension}")

    fun shareFile(mediaId: String, kind: MediaKind): File = File(shareDir(), "$mediaId.${kind.extension}")

    fun scratchFile(prefix: String): File =
        File(workDir(), "${prefix}_${System.currentTimeMillis()}.tmp")

    // ---------------------------------------------------------------- listing

    fun listPending(): List<LocalMedia> =
        (readDirectory(photosDir(), MediaKind.PHOTO) + readDirectory(videosDir(), MediaKind.VIDEO))
            .sortedByDescending { it.createdAtMillis }

    /**
     * Part 1 stored captures in `files/media/...` unencrypted. They are moved into the staging
     * tree once so the new encrypted pipeline can pick them up instead of losing them.
     */
    fun migrateLegacyCaptures(): Int {
        val legacy = File(context.filesDir, AppConstants.LEGACY_MEDIA_DIR)
        if (!legacy.exists()) return 0
        var moved = 0
        listOf("photos" to MediaKind.PHOTO, "videos" to MediaKind.VIDEO, "" to null).forEach { (folder, kind) ->
            val dir = if (folder.isEmpty()) legacy else File(legacy, folder)
            dir.listFiles()?.forEach { file ->
                if (!file.isFile || file.length() <= 0L) return@forEach
                val resolvedKind = kind ?: when (file.extension.lowercase(Locale.US)) {
                    "mp4" -> MediaKind.VIDEO
                    "jpg", "jpeg" -> MediaKind.PHOTO
                    else -> null
                } ?: return@forEach
                val target = File(
                    if (resolvedKind == MediaKind.VIDEO) videosDir() else photosDir(),
                    file.name,
                )
                if (runCatching { file.renameTo(target) }.getOrDefault(false)) moved++
            }
            runCatching { dir.delete() }
        }
        runCatching { legacy.delete() }
        return moved
    }

    // ---------------------------------------------------------------- deletion

    fun deleteFiles(files: List<File>): Int {
        var deleted = 0
        files.forEach { file ->
            if (runCatching { file.exists() && file.delete() }.getOrDefault(false)) deleted++
        }
        return deleted
    }

    fun deletePending(media: LocalMedia): Boolean = runCatching { File(media.filePath).delete() }.getOrDefault(false)

    fun deleteThumbnail(mediaId: String): Boolean =
        runCatching { thumbnailFile(mediaId).delete() }.getOrDefault(false)

    /**
     * Removes only evictable caches. Pending (un-uploaded) media is deliberately untouched.
     * Returns the number of bytes freed.
     */
    fun clearCache(): Long {
        var freed = 0L
        listOf(thumbsDir(), viewerDir(), shareDir()).forEach { dir ->
            dir.listFiles()?.forEach { file ->
                val size = if (file.isFile) file.length() else 0L
                if (runCatching { file.delete() }.getOrDefault(false)) freed += size
            }
        }
        return freed
    }

    fun clearShareCache(): Long = clearDirectory(shareDir())

    fun clearViewerCache(): Long = clearDirectory(viewerDir())

    fun clearThumbnailCache(): Long = clearDirectory(thumbsDir())

    /** Removes work files older than an hour (a crash can leave ciphertext scratch behind). */
    fun pruneScratch(): Long {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        var freed = 0L
        workDir().listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                val size = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) freed += size
            }
        }
        return freed
    }

    private fun clearDirectory(dir: File): Long {
        var freed = 0L
        dir.listFiles()?.forEach { file ->
            val size = if (file.isFile) file.length() else 0L
            if (runCatching { file.delete() }.getOrDefault(false)) freed += size
        }
        return freed
    }

    // ---------------------------------------------------------------- summary

    fun summary(pending: List<LocalMedia> = listPending()): LocalStorageSummary {
        val encrypted = (photosDir().listFiles().orEmpty().asSequence() +
            videosDir().listFiles().orEmpty().asSequence())
            .filter { it.isFile && it.name.endsWith(".${AppConstants.OBJECT_EXTENSION}") }
            .sumOf { it.length() }

        val stat = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()

        return LocalStorageSummary(
            stagedPhotoCount = pending.count { it.kind == MediaKind.PHOTO },
            stagedVideoCount = pending.count { it.kind == MediaKind.VIDEO },
            stagedBytes = pending.sumOf { it.sizeBytes },
            encryptedBytes = encrypted,
            thumbnailBytes = sizeOf(thumbsDir()),
            viewerCacheBytes = sizeOf(viewerDir()),
            shareCacheBytes = sizeOf(shareDir()),
            deviceFreeBytes = stat?.availableBytes ?: 0L,
            deviceTotalBytes = stat?.totalBytes ?: 0L,
        )
    }

    private fun sizeOf(dir: File): Long =
        dir.listFiles().orEmpty().asSequence().filter { it.isFile }.sumOf { it.length() }

    private fun readDirectory(dir: File, kind: MediaKind): List<LocalMedia> =
        dir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.length() > 0L }
            .filter { it.name.endsWith(".${kind.extension}", ignoreCase = true) }
            .map { file ->
                LocalMedia(
                    filePath = file.absolutePath,
                    fileName = file.name,
                    kind = kind,
                    sizeBytes = file.length(),
                    createdAtMillis = file.lastModified(),
                )
            }
            .toList()

    private fun File.ensureDirectory(): File {
        if (!exists()) runCatching { mkdirs() }
        return this
    }
}
