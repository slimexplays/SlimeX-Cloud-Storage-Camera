package com.slimex.cloudcamera.cloud.r2

import com.slimex.cloudcamera.core.constants.AppConstants
import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.result.FailureKind
import com.slimex.cloudcamera.core.util.DispatchersProvider
import com.slimex.cloudcamera.domain.model.DownloadAuthorization
import com.slimex.cloudcamera.domain.model.ProviderUsage
import com.slimex.cloudcamera.domain.model.R2Config
import com.slimex.cloudcamera.domain.model.StoredObject
import com.slimex.cloudcamera.domain.model.UploadAuthorization
import com.slimex.cloudcamera.domain.model.WorkerHealth
import com.slimex.cloudcamera.domain.repository.AuthRepository
import com.slimex.cloudcamera.domain.repository.MediaStorageGateway
import com.slimex.cloudcamera.domain.repository.R2ConfigRepository
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.Buffer
import okio.source
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Media object store backed by Cloudflare R2, reached exclusively through the SlimeX Worker.
 *
 * Flow for an upload:
 *   1. the Worker validates the Supabase JWT and the object key (`{userId}/…`),
 *   2. the Worker returns a **short-lived presigned PUT URL** for that one object,
 *   3. the ciphertext is streamed straight to R2 (the Worker never buffers the file),
 *   4. the app asks the Worker for the object's size/etag and compares it with what it sent.
 *
 * Downloads mirror this with a short-lived presigned GET URL. At no point does the app hold an R2
 * key, and no bucket URL is ever persisted or shown.
 */
@Singleton
class WorkerMediaStorage @Inject constructor(
    private val r2ConfigRepository: R2ConfigRepository,
    private val authRepository: AuthRepository,
    private val worker: R2WorkerClient,
    /** Plain client for presigned transfers: these requests must not carry Supabase headers. */
    private val transferClient: OkHttpClient,
    private val dispatchers: DispatchersProvider,
) : MediaStorageGateway {

    override suspend fun health(): AppResult<WorkerHealth> {
        val config = configOrNull() ?: return notConfigured()
        return worker.health(config)
    }

    override suspend fun usage(): AppResult<ProviderUsage> {
        val config = configOrNull() ?: return notConfigured()
        val token = accessToken() ?: return signedOut()
        return worker.usage(config, token)
    }

    override suspend fun providerLabel(): String =
        r2ConfigRepository.current()?.providerName ?: AppConstants.DEFAULT_STORAGE_PROVIDER

    override suspend fun authorizeUpload(
        objectKey: String,
        sizeBytes: Long,
        contentType: String,
    ): AppResult<UploadAuthorization> {
        val config = configOrNull() ?: return notConfigured()
        val token = accessToken() ?: return signedOut()
        return worker.authorizeUpload(config, token, objectKey, sizeBytes, contentType)
    }

    override suspend fun authorizeDownload(objectKey: String): AppResult<DownloadAuthorization> {
        val config = configOrNull() ?: return notConfigured()
        val token = accessToken() ?: return signedOut()
        return worker.authorizeDownload(config, token, objectKey)
    }

    override suspend fun upload(
        objectKey: String,
        file: File,
        contentType: String,
        onProgress: (Float) -> Unit,
    ): AppResult<StoredObject> = withContext(dispatchers.io) {
        if (!file.exists() || file.length() == 0L) {
            return@withContext AppResult.Failure(
                AppFailure.storage("The encrypted file is missing or empty, so it was not uploaded."),
            )
        }
        val sizeBytes = file.length()
        val authorization = when (val result = authorizeUpload(objectKey, sizeBytes, contentType)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return@withContext result
        }

        val total = sizeBytes.coerceAtLeast(1L)
        val body = ProgressRequestBody(file, contentType.toMediaTypeOrNull()) { sent ->
            onProgress((sent.toFloat() / total.toFloat()).coerceIn(0f, 1f))
        }
        val request = Request.Builder()
            .url(authorization.uploadUrl)
            .put(body)
            .header("Content-Type", contentType)
            .build()

        try {
            transferClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    return@withContext AppResult.Failure(uploadFailure(response.code, text))
                }
            }
        } catch (io: IOException) {
            return@withContext AppResult.Failure(
                AppFailure.network("Upload to Cloudflare R2 failed: ${io.message ?: "network error"}"),
            )
        } catch (t: Throwable) {
            return@withContext AppResult.Failure(
                AppFailure.unknown("Upload to Cloudflare R2 failed: ${t.message ?: "unexpected error"}"),
            )
        }

        onProgress(1f)

        // Confirm with the Worker what the bucket actually stored (size + etag).
        val config = configOrNull() ?: return@withContext notConfigured()
        val token = accessToken() ?: return@withContext signedOut()
        when (val stat = worker.stat(config, token, objectKey)) {
            is AppResult.Success -> {
                if (stat.data.sizeBytes != sizeBytes) {
                    return@withContext AppResult.Failure(
                        AppFailure.storage(
                            "The bucket stored ${stat.data.sizeBytes} bytes but $sizeBytes were sent.",
                        ),
                    )
                }
                AppResult.Success(stat.data)
            }

            // The PUT already succeeded; a stat hiccup must not fail the upload, the caller simply
            // gets a result with the size it sent.
            is AppResult.Failure -> AppResult.Success(
                StoredObject(objectKey = objectKey, sizeBytes = sizeBytes, etag = null, uploadedAtIso = null),
            )
        }
    }

    override suspend fun download(
        objectKey: String,
        target: File,
        onProgress: (Float) -> Unit,
    ): AppResult<File> = withContext(dispatchers.io) {
        val authorization = when (val result = authorizeDownload(objectKey)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return@withContext result
        }

        val temp = File(target.parentFile, target.name + ".part")
        val request = Request.Builder().url(authorization.downloadUrl).get().build()
        try {
            transferClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    return@withContext AppResult.Failure(uploadFailure(response.code, text, "download"))
                }
                val payload = response.body
                    ?: return@withContext AppResult.Failure(AppFailure.unknown("The download was empty."))
                val total = payload.contentLength().takeIf { it > 0 } ?: authorization.sizeBytes ?: 0L
                target.parentFile?.mkdirs()
                temp.delete()

                payload.byteStream().use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                onProgress((written.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        } catch (io: IOException) {
            temp.delete()
            return@withContext AppResult.Failure(
                AppFailure.network("Download from Cloudflare R2 failed: ${io.message ?: "network error"}"),
            )
        } catch (t: Throwable) {
            temp.delete()
            return@withContext AppResult.Failure(
                AppFailure.unknown("Download from Cloudflare R2 failed: ${t.message ?: "unexpected error"}"),
            )
        }

        if (!temp.exists() || temp.length() <= 0L) {
            temp.delete()
            return@withContext AppResult.Failure(AppFailure.storage("The download was empty or incomplete."))
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.delete()
            return@withContext AppResult.Failure(
                AppFailure.storage("Could not finish writing the downloaded file."),
            )
        }
        onProgress(1f)
        AppResult.Success(target)
    }

    override suspend fun stat(objectKey: String): AppResult<StoredObject> {
        val config = configOrNull() ?: return notConfigured()
        val token = accessToken() ?: return signedOut()
        return worker.stat(config, token, objectKey)
    }

    override suspend fun delete(objectKey: String): AppResult<Unit> {
        val config = configOrNull() ?: return notConfigured()
        val token = accessToken() ?: return signedOut()
        return worker.delete(config, token, objectKey)
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun configOrNull(): R2Config? = r2ConfigRepository.current()

    private suspend fun accessToken(): String? =
        when (val result = authRepository.requireSession()) {
            is AppResult.Success -> result.data.accessToken
            is AppResult.Failure -> null
        }

    private fun notConfigured(): AppResult.Failure = AppResult.Failure(
        AppFailure(
            message = "Cloudflare R2 is not configured. Add your Worker endpoint in Settings → Cloud configuration.",
            kind = FailureKind.NOT_CONFIGURED,
        ),
    )

    private fun signedOut(): AppResult.Failure = AppResult.Failure(
        AppFailure.unauthorized("Sign in to your Supabase account to use cloud storage."),
    )

    private fun uploadFailure(code: Int, body: String?, action: String = "upload to Cloudflare R2"): AppFailure {
        val detail = body?.takeIf { it.isNotBlank() && !it.trimStart().startsWith("<") }
            ?.take(180)
        return when (code) {
            401, 403 -> AppFailure.unauthorized(
                detail ?: "R2 rejected the ${action}. The signed link may have expired — retry.",
            )

            404 -> AppFailure.unknown(detail ?: "The object was not found in the bucket while trying to $action.")

            413 -> AppFailure.storage(detail ?: "The file is larger than the bucket's limit.")

            429 -> AppFailure.network(detail ?: "Too many requests to R2. Retrying later.")

            else -> AppFailure.unknown(
                detail ?: "Could not $action (HTTP $code).",
            )
        }
    }

    /** Streams a file to the network without loading it into memory, reporting progress. */
    private class ProgressRequestBody(
        private val file: File,
        private val mediaType: MediaType?,
        private val onBytes: (Long) -> Unit,
    ) : RequestBody() {

        override fun contentType(): MediaType? = mediaType

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            file.source().use { source ->
                val buffer = Buffer()
                var uploaded = 0L
                while (true) {
                    val read = source.read(buffer, CHUNK_BYTES)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    uploaded += read
                    onBytes(uploaded)
                }
            }
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val CHUNK_BYTES = 64L * 1024L
    }
}
