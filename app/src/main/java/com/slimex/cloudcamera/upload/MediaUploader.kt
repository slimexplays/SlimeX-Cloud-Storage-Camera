package com.slimex.cloudcamera.upload

import com.slimex.cloudcamera.cloud.CloudContext
import com.slimex.cloudcamera.cloud.MediaRowInput
import com.slimex.cloudcamera.cloud.SupabaseDataApi
import com.slimex.cloudcamera.domain.repository.MediaStorageGateway
import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.util.StoragePaths
import com.slimex.cloudcamera.domain.model.UploadTask
import com.slimex.cloudcamera.media.ThumbnailFactory
import com.slimex.cloudcamera.security.EncryptedMedia
import com.slimex.cloudcamera.security.MediaCrypto
import com.slimex.cloudcamera.security.MediaKey
import com.slimex.cloudcamera.security.MediaKeyEntry
import com.slimex.cloudcamera.security.MediaKeyStore
import com.slimex.cloudcamera.storage.StagingStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** Everything produced by [MediaUploader.prepare]; key material lives in memory only. */
data class PreparedUpload(
    val mediaId: String,
    val objectKey: String,
    val encryptedFile: File,
    val encryptionKey: MediaKey,
    val plaintextChecksum: String,
    val ciphertextChecksum: String,
    val encryptedSize: Long,
    val thumbnailObjectKey: String?,
    val thumbnailFile: File?,
    val thumbnailKey: MediaKey?,
    val thumbnailChecksum: String?,
    val thumbnailSize: Long?,
    val thumbnailLocalPath: String?,
    val sourceFile: File,
)

/**
 * The encryption → upload → verify → metadata pipeline for a single capture.
 *
 * Key material is generated per attempt and kept in memory: if the process dies mid-upload the
 * partial ciphertext is discarded and re-encrypted with a fresh key, so a stale key can never
 * end up next to a file it does not belong to.
 */
@Singleton
class MediaUploader @Inject constructor(
    private val crypto: MediaCrypto,
    private val storage: MediaStorageGateway,
    private val data: SupabaseDataApi,
    private val staging: StagingStore,
    private val thumbnails: ThumbnailFactory,
    private val keyStore: MediaKeyStore,
) {

    suspend fun prepare(task: UploadTask, userId: String): AppResult<PreparedUpload> {
        val source = File(task.filePath)
        if (!source.exists() || source.length() == 0L) {
            return AppResult.Failure(
                AppFailure.storage("The captured file is no longer on this device, so it cannot be uploaded."),
            )
        }

        // Encrypting needs room for a full second copy of the file.
        val free = staging.freeBytes()
        if (free < source.length() + ENCRYPT_HEADROOM_BYTES) {
            return AppResult.Failure(
                AppFailure.storage("Not enough free space to encrypt this file for upload."),
            )
        }

        // A previous attempt may have left ciphertext behind; it carries an unknown key, so drop it.
        val encryptedFile = staging.encryptedCopyOf(source)
        if (encryptedFile.exists()) encryptedFile.delete()

        val mainCipher = when (val result = crypto.encrypt(source, encryptedFile)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return result
        }

        val localThumb = task.thumbnailLocalPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
            ?: thumbnails.ensure(source, task.kind, task.mediaId)

        var thumbCipher: EncryptedMedia? = null
        if (localThumb != null) {
            val thumbEncryptedFile = File(localThumb.parentFile, localThumb.name + ".enc")
            if (thumbEncryptedFile.exists()) thumbEncryptedFile.delete()
            val encryptedThumb = crypto.encrypt(localThumb, thumbEncryptedFile)
            if (encryptedThumb is AppResult.Success) thumbCipher = encryptedThumb.data
        }

        return AppResult.Success(
            PreparedUpload(
                mediaId = task.mediaId,
                objectKey = StoragePaths.objectKey(userId, task.capturedAt, task.mediaId),
                encryptedFile = encryptedFile,
                encryptionKey = mainCipher.key,
                plaintextChecksum = mainCipher.checksum,
                ciphertextChecksum = crypto.sha256(encryptedFile),
                encryptedSize = mainCipher.ciphertextBytes,
                thumbnailObjectKey = thumbCipher?.let { StoragePaths.thumbnailKey(StoragePaths.objectKey(userId, task.capturedAt, task.mediaId)) },
                thumbnailFile = thumbCipher?.file,
                thumbnailKey = thumbCipher?.key,
                thumbnailChecksum = thumbCipher?.checksum,
                thumbnailSize = thumbCipher?.plaintextBytes,
                thumbnailLocalPath = localThumb?.absolutePath,
                sourceFile = source,
            ),
        )
    }

    /**
     * Streams the ciphertext (and the encrypted preview, when there is one) to the media backend.
     *
     * The gateway asks the Worker for a short-lived upload authorization per object; the app never
     * holds an R2 credential and never talks to a bucket URL it did not just receive.
     */
    suspend fun transfer(
        prepared: PreparedUpload,
        onProgress: (Float) -> Unit,
    ): AppResult<Unit> {
        val main = storage.upload(
            objectKey = prepared.objectKey,
            file = prepared.encryptedFile,
            contentType = OCTET_STREAM,
            onProgress = { fraction -> onProgress(fraction * 0.9f) },
        )
        if (main is AppResult.Failure) return main

        val thumbFile = prepared.thumbnailFile
        val thumbKey = prepared.thumbnailObjectKey
        if (thumbFile != null && thumbKey != null) {
            val thumb = storage.upload(
                objectKey = thumbKey,
                file = thumbFile,
                contentType = OCTET_STREAM,
                onProgress = { fraction -> onProgress(0.9f + fraction * 0.1f) },
            )
            if (thumb is AppResult.Failure) return thumb
        }

        onProgress(1f)
        return AppResult.Success(Unit)
    }

    /**
     * Confirms the bucket really holds the file: the object must exist with the expected size and,
     * for anything up to [VERIFY_DOWNLOAD_LIMIT], the ciphertext is fetched back and hashed.
     */
    suspend fun verify(prepared: PreparedUpload): AppResult<Unit> {
        val remote = when (val result = storage.stat(prepared.objectKey)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return AppResult.Failure(
                AppFailure.unknown(
                    "The upload finished but the object is not readable yet (${result.error.message}). Retrying.",
                ),
            )
        }

        if (remote.sizeBytes > 0L && remote.sizeBytes != prepared.encryptedSize) {
            return AppResult.Failure(
                AppFailure.storage(
                    "The cloud copy is ${remote.sizeBytes} bytes but ${prepared.encryptedSize} were uploaded.",
                ),
            )
        }

        if (prepared.encryptedSize <= VERIFY_DOWNLOAD_LIMIT) {
            val scratch = staging.scratchFile("verify")
            try {
                val downloaded = when (val result = storage.download(prepared.objectKey, scratch)) {
                    is AppResult.Success -> result.data
                    is AppResult.Failure -> return result
                }
                val actual = crypto.sha256(downloaded)
                if (!actual.equals(prepared.ciphertextChecksum, ignoreCase = false)) {
                    return AppResult.Failure(
                        AppFailure.storage("Checksum mismatch after upload. The file was not stored correctly."),
                    )
                }
            } finally {
                runCatching { scratch.delete() }
            }
        }

        val thumbKey = prepared.thumbnailObjectKey
        if (thumbKey != null) {
            val thumbRemote = when (val result = storage.stat(thumbKey)) {
                is AppResult.Success -> result.data
                is AppResult.Failure -> return AppResult.Failure(
                    AppFailure.unknown("The preview image was not stored yet. Retrying."),
                )
            }
            // The bucket holds the ciphertext, so compare against the encrypted file's length.
            val expectedThumb = prepared.thumbnailFile?.length() ?: 0L
            if (thumbRemote.sizeBytes > 0L && expectedThumb > 0L && thumbRemote.sizeBytes != expectedThumb) {
                return AppResult.Failure(AppFailure.storage("The uploaded preview is incomplete."))
            }
        }

        return AppResult.Success(Unit)
    }

    suspend fun saveMetadata(context: CloudContext, prepared: PreparedUpload, task: UploadTask): AppResult<Unit> {
        val entry = MediaKeyEntry(
            k = prepared.encryptionKey.keyBase64,
            n = prepared.encryptionKey.nonceBase64,
            tk = prepared.thumbnailKey?.keyBase64,
            tn = prepared.thumbnailKey?.nonceBase64,
        )
        when (val stored = keyStore.put(prepared.mediaId, entry)) {
            is AppResult.Failure -> return stored
            is AppResult.Success -> Unit
        }

        val row = MediaRowInput(
            id = prepared.mediaId,
            userId = context.userId,
            storagePath = prepared.objectKey,
            fileName = task.fileName,
            mimeType = task.mimeType,
            fileSize = prepared.sourceFile.length(),
            mediaType = task.kind,
            checksum = prepared.plaintextChecksum,
            createdAtIso = isoDate(task.capturedAt),
            thumbnailPath = prepared.thumbnailObjectKey,
            thumbnailSize = prepared.thumbnailSize,
            thumbnailChecksum = prepared.thumbnailChecksum,
        )
        return data.insertMedia(context.config, context.accessToken, row)
    }

    /** Removes the plaintext, the ciphertext and the encrypted preview — only after verification. */
    fun deleteLocalCopies(prepared: PreparedUpload): Int {
        val targets = buildList {
            add(prepared.sourceFile)
            add(prepared.encryptedFile)
            prepared.thumbnailFile?.let { add(it) }
            prepared.thumbnailLocalPath?.let { add(File(it)) }
        }
        return staging.deleteFiles(targets)
    }

    /** Drops ciphertext scratch for an abandoned attempt, keeping the original capture. */
    fun discardScratch(task: UploadTask): Int {
        val source = File(task.filePath)
        val encrypted = staging.encryptedCopyOf(source)
        val thumb = staging.thumbnailFile(task.mediaId)
        val thumbEncrypted = File(thumb.parentFile, thumb.name + ".enc")
        return staging.deleteFiles(listOf(encrypted, thumbEncrypted))
    }

    private fun isoDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(millis))

    private companion object {
        const val OCTET_STREAM = "application/octet-stream"
        const val VERIFY_DOWNLOAD_LIMIT = 64L * 1024 * 1024
        const val ENCRYPT_HEADROOM_BYTES = 16L * 1024 * 1024
    }
}
