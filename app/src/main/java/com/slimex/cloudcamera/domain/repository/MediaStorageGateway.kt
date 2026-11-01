package com.slimex.cloudcamera.domain.repository

import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.DownloadAuthorization
import com.slimex.cloudcamera.domain.model.ProviderUsage
import com.slimex.cloudcamera.domain.model.StoredObject
import com.slimex.cloudcamera.domain.model.UploadAuthorization
import com.slimex.cloudcamera.domain.model.WorkerHealth
import java.io.File

/**
 * The media object store: an encrypted blob in Cloudflare R2, reached through an authenticated
 * Cloudflare Worker.
 *
 * Implementations must never see R2 keys — they hold a Supabase access token, ask the Worker for
 * a short-lived authorization and then transfer the ciphertext to/from R2.
 */
interface MediaStorageGateway {

    /** Public health probe of the configured Worker (no user data). */
    suspend fun health(): AppResult<WorkerHealth>

    /** Verified usage of the signed-in user's own prefix, computed by the Worker. */
    suspend fun usage(): AppResult<ProviderUsage>

    /** Asks the Worker for a short-lived PUT URL. */
    suspend fun authorizeUpload(objectKey: String, sizeBytes: Long, contentType: String): AppResult<UploadAuthorization>

    /** Asks the Worker for a short-lived GET URL. */
    suspend fun authorizeDownload(objectKey: String): AppResult<DownloadAuthorization>

    /** Streams [file] to R2 through the authorized URL and returns what the bucket reports. */
    suspend fun upload(
        objectKey: String,
        file: File,
        contentType: String,
        onProgress: (Float) -> Unit = {},
    ): AppResult<StoredObject>

    /** Streams the encrypted object into [target] (never into shared storage). */
    suspend fun download(
        objectKey: String,
        target: File,
        onProgress: (Float) -> Unit = {},
    ): AppResult<File>

    /** Bucket metadata for one object (size, etag, upload time). */
    suspend fun stat(objectKey: String): AppResult<StoredObject>

    /** Deletes one object; missing objects count as deleted. */
    suspend fun delete(objectKey: String): AppResult<Unit>

    /** Human label of the configured provider ("Cloudflare R2" by default). */
    suspend fun providerLabel(): String
}
