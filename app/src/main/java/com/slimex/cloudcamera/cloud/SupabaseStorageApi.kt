package com.slimex.cloudcamera.cloud

import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.util.DispatchersProvider
import com.slimex.cloudcamera.domain.model.CloudConfig
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.Buffer
import okio.source
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** One entry returned by the Storage list endpoint. */
data class StorageObject(
    val name: String,
    val sizeBytes: Long,
    val updatedAt: String?,
    val mimetype: String?,
)

/**
 * Supabase Storage REST calls for the **private** bucket.
 *
 * Every call carries the signed-in user's access token; the bucket itself has no public
 * policy, so objects can only be reached by their owner through RLS storage policies.
 */
@Singleton
class SupabaseStorageApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val dispatchers: DispatchersProvider,
) {

    suspend fun upload(
        config: CloudConfig,
        accessToken: String,
        objectPath: String,
        file: File,
        contentType: String,
        onProgress: (Float) -> Unit = {},
    ): AppResult<Unit> = withContext(dispatchers.io) {
        if (!file.exists() || file.length() == 0L) {
            return@withContext AppResult.Failure(AppFailure.storage("The encrypted file is missing or empty."))
        }
        val url = objectUrl(config, objectPath) ?: return@withContext invalidUrl()
        val total = file.length().coerceAtLeast(1L)
        val body = ProgressRequestBody(file, contentType.toMediaTypeOrNull()) { sent ->
            onProgress((sent.toFloat() / total.toFloat()).coerceIn(0f, 1f))
        }
        val request = Request.Builder()
            .url(url)
            .post(body)
            .authed(config, accessToken)
            .header("x-upsert", "true")
            .header("Cache-Control", "3600")
            .build()

        val outcome = execute(request)
        if (!outcome.isSuccessful) {
            return@withContext AppResult.Failure(CloudFailures.storage(outcome, "upload the file"))
        }
        onProgress(1f)
        AppResult.Success(Unit)
    }

    /** Downloads a private, encrypted object straight to [target] (streamed, no buffering). */
    suspend fun download(
        config: CloudConfig,
        accessToken: String,
        objectPath: String,
        target: File,
        onProgress: (Float) -> Unit = {},
    ): AppResult<File> = withContext(dispatchers.io) {
        val url = authenticatedObjectUrl(config, objectPath) ?: return@withContext invalidUrl()
        val request = Request.Builder().url(url).get().authed(config, accessToken).build()
        val temp = File(target.parentFile, target.name + ".part")
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val outcome = HttpOutcome(code = response.code, body = runCatching { response.body?.string() }.getOrNull())
                    return@withContext AppResult.Failure(CloudFailures.storage(outcome, "download the file"))
                }
                val payload = response.body ?: return@withContext AppResult.Failure(
                    AppFailure.unknown("Supabase returned an empty download."),
                )
                val total = payload.contentLength().coerceAtLeast(1L)
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
                            onProgress((written.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                }

                if (!temp.exists() || temp.length() <= 0L) {
                    temp.delete()
                    return@withContext AppResult.Failure(AppFailure.storage("The download was empty."))
                }
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return@withContext AppResult.Failure(AppFailure.storage("Could not finish writing the downloaded file."))
                }
                onProgress(1f)
                AppResult.Success(target)
            }
        } catch (io: IOException) {
            temp.delete()
            AppResult.Failure(AppFailure.network("Download failed: ${io.message ?: "network error"}"))
        } catch (t: Throwable) {
            temp.delete()
            AppResult.Failure(AppFailure.unknown("Download failed: ${t.message ?: "unexpected error"}"))
        }
    }

    /** Lists objects under [prefix] (non-recursive, like the Supabase dashboard). */
    suspend fun list(
        config: CloudConfig,
        accessToken: String,
        prefix: String,
        limit: Int = 100,
        search: String? = null,
    ): AppResult<List<StorageObject>> = withContext(dispatchers.io) {
        val base = config.projectUrl.toHttpUrlOrNull() ?: return@withContext invalidUrl()
        val url = base.newBuilder()
            .addPathSegment("storage")
            .addPathSegment("v1")
            .addPathSegment("object")
            .addPathSegment("list")
            .addPathSegment(config.bucket)
            .build()

        val payload = buildString {
            append("{\"prefix\":\"").append(prefix.escapeJson()).append("\",")
            append("\"limit\":").append(limit.coerceIn(1, 1000)).append(',')
            append("\"offset\":0,")
            search?.takeIf { it.isNotBlank() }?.let {
                append("\"search\":\"").append(it.escapeJson()).append("\",")
            }
            append("\"sortBy\":{\"column\":\"name\",\"order\":\"asc\"}}")
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON))
            .authed(config, accessToken)
            .header("Accept", "application/json")
            .build()

        val outcome = execute(request)
        if (!outcome.isSuccessful) {
            return@withContext AppResult.Failure(CloudFailures.storage(outcome, "list cloud files"))
        }
        val body = outcome.body ?: return@withContext AppResult.Success(emptyList())
        val parsed = runCatching {
            json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val name = obj.string("name") ?: return@mapNotNull null
                if (name.isBlank()) return@mapNotNull null
                val metadata = obj["metadata"] as? JsonObject
                StorageObject(
                    name = name,
                    sizeBytes = (metadata?.get("size") as? JsonPrimitive)
                        ?.let { runCatching { it.content.toLong() }.getOrNull() } ?: 0L,
                    updatedAt = obj.string("updated_at") ?: obj.string("created_at"),
                    mimetype = metadata?.string("mimetype"),
                )
            }
        }.getOrElse { emptyList() }
        AppResult.Success(parsed)
    }

    /** Removes several objects in one call (Storage batch delete). */
    suspend fun remove(
        config: CloudConfig,
        accessToken: String,
        objectPaths: List<String>,
    ): AppResult<Unit> = withContext(dispatchers.io) {
        if (objectPaths.isEmpty()) return@withContext AppResult.Success(Unit)
        val base = config.projectUrl.toHttpUrlOrNull() ?: return@withContext invalidUrl()
        val url = base.newBuilder()
            .addPathSegment("storage")
            .addPathSegment("v1")
            .addPathSegment("object")
            .addPathSegment(config.bucket)
            .build()
        val payload = objectPaths.joinToString(
            prefix = "{\"prefixes\":[",
            postfix = "]}",
        ) { "\"" + it.escapeJson() + "\"" }

        val request = Request.Builder()
            .url(url)
            .delete(payload.toRequestBody(JSON))
            .authed(config, accessToken)
            .build()

        val outcome = execute(request)
        if (!outcome.isSuccessful) {
            return@withContext AppResult.Failure(CloudFailures.storage(outcome, "delete cloud files"))
        }
        AppResult.Success(Unit)
    }

    // ---------------------------------------------------------------- plumbing

    private fun Request.Builder.authed(config: CloudConfig, accessToken: String): Request.Builder = apply {
        header("apikey", config.publishableKey)
        header("Authorization", "Bearer $accessToken")
    }

    private suspend fun execute(request: Request): HttpOutcome = withContext(dispatchers.io) {
        try {
            client.newCall(request).execute().use { response ->
                HttpOutcome(code = response.code, body = runCatching { response.body?.string() }.getOrNull())
            }
        } catch (io: IOException) {
            HttpOutcome(code = 0, transportError = io.message ?: "Network error")
        } catch (t: Throwable) {
            HttpOutcome(code = 0, transportError = t.message ?: "Unexpected networking error")
        }
    }

    private fun objectUrl(config: CloudConfig, objectPath: String): HttpUrl? {
        val base = config.projectUrl.toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            .addPathSegment("storage")
            .addPathSegment("v1")
            .addPathSegment("object")
            .addPathSegment(config.bucket)
            .addPathSegments(objectPath)
            .build()
    }

    private fun authenticatedObjectUrl(config: CloudConfig, objectPath: String): HttpUrl? {
        val base = config.projectUrl.toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            .addPathSegment("storage")
            .addPathSegment("v1")
            .addPathSegment("object")
            .addPathSegment("authenticated")
            .addPathSegment(config.bucket)
            .addPathSegments(objectPath)
            .build()
    }

    private fun invalidUrl(): AppResult.Failure = AppResult.Failure(
        AppFailure.unknown("The stored Supabase project URL is invalid. Re-save it in Settings."),
    )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.let { runCatching { it.content }.getOrNull() }?.takeIf { it.isNotBlank() }

    private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private class ProgressRequestBody(
        private val file: File,
        private val mediaType: okhttp3.MediaType?,
        private val onBytes: (Long) -> Unit,
    ) : RequestBody() {

        override fun contentType() = mediaType

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            file.source().use { source ->
                val buffer = Buffer()
                var uploaded = 0L
                while (true) {
                    val read = source.read(buffer, READ_CHUNK_BYTES)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    uploaded += read
                    onBytes(uploaded)
                }
                sink.flush()
            }
        }
    }

    private companion object {
        /** Int for byte arrays, Long for okio's read() contract. */
        const val BUFFER_BYTES = 64 * 1024
        const val READ_CHUNK_BYTES = 64L * 1024L
        val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
