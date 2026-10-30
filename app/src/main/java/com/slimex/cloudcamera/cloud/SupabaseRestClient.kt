package com.slimex.cloudcamera.cloud

import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.util.DispatchersProvider
import com.slimex.cloudcamera.domain.model.CloudConfig
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import okio.Buffer
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin, explicit Supabase REST client (PostgREST + Storage + GoTrue health).
 *
 * The official Kotlin SDK is intentionally not used: it binds credentials at build
 * time, while this app requires a *runtime*, settings-first configuration entered by
 * the user after installation. Every call therefore takes an explicit [CloudConfig].
 */
@Singleton
class SupabaseRestClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val dispatchers: DispatchersProvider,
) {

    // ---------------------------------------------------------------- diagnostics

    suspend fun projectHealth(config: CloudConfig): HttpOutcome {
        val url = build(config, "auth", "v1", "health") ?: return invalidUrl()
        return execute(Request.Builder().url(url).get().supabaseAuth(config).build())
    }

    suspend fun restRoot(config: CloudConfig): HttpOutcome {
        val url = build(config, "rest", "v1") ?: return invalidUrl()
        return execute(
            Request.Builder()
                .url(url)
                .get()
                .supabaseAuth(config)
                .header("Accept", "application/json")
                .build(),
        )
    }

    suspend fun probeBucket(config: CloudConfig): HttpOutcome {
        val url = build(config, "storage", "v1", "object", "list", config.bucket)
            ?: return invalidUrl()
        val payload = """{"prefix":"","limit":1,"offset":0,"sortBy":{"column":"name","order":"asc"}}"""
        return execute(
            Request.Builder()
                .url(url)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .supabaseAuth(config)
                .header("Accept", "application/json")
                .build(),
        )
    }

    suspend fun probeTable(config: CloudConfig): HttpOutcome {
        val url = "${config.projectUrl}/rest/v1/${config.table}?select=id&limit=1"
            .toHttpUrlOrNull() ?: return invalidUrl()
        return execute(
            Request.Builder()
                .url(url)
                .get()
                .supabaseAuth(config)
                .header("Accept", "application/json")
                .build(),
        )
    }

    // ---------------------------------------------------------------- storage

    suspend fun uploadObject(
        config: CloudConfig,
        objectPath: String,
        file: File,
        contentType: String,
        onProgress: (Float) -> Unit = {},
    ): AppResult<String> = withContext(dispatchers.io) {
        val base = config.projectUrl.toHttpUrlOrNull()
            ?: return@withContext AppResult.Failure(AppFailure.unknown("Invalid Supabase project URL."))
        if (!file.exists() || file.length() == 0L) {
            return@withContext AppResult.Failure(AppFailure.storage("Local file is missing or empty."))
        }
        val url = base.newBuilder()
            .addPathSegment("storage")
            .addPathSegment("v1")
            .addPathSegment("object")
            .addPathSegment(config.bucket)
            .addPathSegments(objectPath)
            .build()

        val total = file.length().coerceAtLeast(1L)
        val requestBody: RequestBody = ProgressRequestBody(
            file = file,
            mediaType = contentType.toMediaTypeOrNull(),
        ) { sent -> onProgress((sent.toFloat() / total.toFloat()).coerceIn(0f, 1f)) }

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .supabaseAuth(config)
            .header("x-upsert", "true")
            .header("Cache-Control", "3600")
            .build()

        val outcome = execute(request)
        if (outcome.isSuccessful) {
            onProgress(1f)
            val key = outcome.body
                ?.let { runCatching { json.parseToJsonElement(it).jsonObject["Key"]?.jsonPrimitive?.content }.getOrNull() }
            AppResult.Success(key ?: objectPath)
        } else {
            AppResult.Failure(uploadFailure(outcome))
        }
    }

    suspend fun createSignedUrl(
        config: CloudConfig,
        objectPath: String,
        expiresInSeconds: Long,
    ): AppResult<String> = withContext(dispatchers.io) {
        val base = config.projectUrl.toHttpUrlOrNull()
            ?: return@withContext AppResult.Failure(AppFailure.unknown("Invalid Supabase project URL."))
        val url = base.newBuilder()
            .addPathSegment("storage")
            .addPathSegment("v1")
            .addPathSegment("object")
            .addPathSegment("sign")
            .addPathSegment(config.bucket)
            .addPathSegments(objectPath)
            .build()
        val payload = """{"expiresIn":$expiresInSeconds}"""
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .supabaseAuth(config)
            .header("Accept", "application/json")
            .build()
        val outcome = execute(request)
        if (!outcome.isSuccessful) {
            return@withContext AppResult.Failure(uploadFailure(outcome))
        }
        val signed = outcome.body?.let { body ->
            runCatching {
                json.parseToJsonElement(body).jsonObject["signedURL"]?.jsonPrimitive?.content
            }.getOrNull()
        }
        if (signed.isNullOrBlank()) {
            AppResult.Failure(AppFailure.unknown("Supabase did not return a signed URL."))
        } else {
            AppResult.Success(normalizeSignedUrl(config, signed))
        }
    }

    suspend fun deleteObject(config: CloudConfig, objectPath: String): AppResult<Unit> {
        val base = config.projectUrl.toHttpUrlOrNull()
            ?: return AppResult.Failure(AppFailure.unknown("Invalid Supabase project URL."))
        val url = base.newBuilder()
            .addPathSegment("storage")
            .addPathSegment("v1")
            .addPathSegment("object")
            .addPathSegment(config.bucket)
            .addPathSegments(objectPath)
            .build()
        val outcome = execute(Request.Builder().url(url).delete().supabaseAuth(config).build())
        return if (outcome.isSuccessful) AppResult.Success(Unit) else AppResult.Failure(uploadFailure(outcome))
    }

    // ---------------------------------------------------------------- database

    suspend fun selectRows(config: CloudConfig, query: String, limit: Int): AppResult<List<JsonObject>> {
        val url = "${config.projectUrl}/rest/v1/${config.table}?select=*&$query&limit=$limit"
            .toHttpUrlOrNull()
            ?: return AppResult.Failure(AppFailure.unknown("Invalid Supabase project URL."))
        val outcome = execute(
            Request.Builder().url(url).get().supabaseAuth(config).header("Accept", "application/json").build(),
        )
        if (!outcome.isSuccessful) {
            return AppResult.Failure(databaseFailure(outcome))
        }
        val body = outcome.body ?: return AppResult.Success(emptyList())
        return runCatching {
            json.parseToJsonElement(body).jsonArray.mapNotNull { it as? JsonObject }
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure(AppFailure.unknown("Could not read the media table response.")) },
        )
    }

    suspend fun insertRow(config: CloudConfig, payload: String): AppResult<Unit> {
        val url = "${config.projectUrl}/rest/v1/${config.table}".toHttpUrlOrNull()
            ?: return AppResult.Failure(AppFailure.unknown("Invalid Supabase project URL."))
        val outcome = execute(
            Request.Builder()
                .url(url)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .supabaseAuth(config)
                .header("Prefer", "return=minimal")
                .build(),
        )
        return if (outcome.isSuccessful) AppResult.Success(Unit) else AppResult.Failure(databaseFailure(outcome))
    }

    suspend fun deleteRow(config: CloudConfig, id: String): AppResult<Unit> {
        val url = "${config.projectUrl}/rest/v1/${config.table}?id=eq.$id".toHttpUrlOrNull()
            ?: return AppResult.Failure(AppFailure.unknown("Invalid Supabase project URL."))
        val outcome = execute(Request.Builder().url(url).delete().supabaseAuth(config).build())
        return if (outcome.isSuccessful) AppResult.Success(Unit) else AppResult.Failure(databaseFailure(outcome))
    }

    // ---------------------------------------------------------------- plumbing

    private suspend fun execute(request: Request): HttpOutcome = withContext(dispatchers.io) {
        val started = System.currentTimeMillis()
        try {
            client.newCall(request).execute().use { response ->
                val body = runCatching { response.body?.string() }.getOrNull()
                HttpOutcome(
                    code = response.code,
                    body = body,
                    transportError = null,
                    latencyMs = System.currentTimeMillis() - started,
                )
            }
        } catch (io: IOException) {
            HttpOutcome(
                code = 0,
                body = null,
                transportError = io.message ?: "Network error",
                latencyMs = System.currentTimeMillis() - started,
            )
        } catch (t: Throwable) {
            HttpOutcome(
                code = 0,
                body = null,
                transportError = t.message ?: "Unexpected networking error",
                latencyMs = System.currentTimeMillis() - started,
            )
        }
    }

    private fun build(config: CloudConfig, vararg segments: String): HttpUrl? {
        val base = config.projectUrl.toHttpUrlOrNull() ?: return null
        val builder = base.newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    private fun invalidUrl() = HttpOutcome(
        code = 0,
        transportError = "The stored project URL is not a valid URL. Re-save it in Settings.",
    )

    private fun Request.Builder.supabaseAuth(config: CloudConfig): Request.Builder = apply {
        header("apikey", config.publishableKey)
        header("Authorization", "Bearer ${config.publishableKey}")
    }

    private fun normalizeSignedUrl(config: CloudConfig, signed: String): String = when {
        signed.startsWith("http://") || signed.startsWith("https://") -> signed
        signed.startsWith("/storage/v1") -> config.projectUrl + signed
        signed.startsWith("/") -> config.projectUrl + "/storage/v1" + signed
        else -> config.projectUrl + "/storage/v1/" + signed
    }

    private fun uploadFailure(outcome: HttpOutcome): AppFailure {
        if (outcome.transportError != null) {
            return AppFailure.network(
                message = "Could not reach Supabase: ${outcome.transportError}",
            )
        }
        val server = serverMessage(outcome)
        return when (outcome.code) {
            401, 403 -> AppFailure.unauthorized(
                server ?: "Supabase rejected the request (${outcome.code}). Check the publishable key " +
                    "and the storage policies from supabase/storage.sql.",
            )
            404 -> AppFailure.unknown(server ?: "Bucket or object not found (404). Run supabase/storage.sql first.")
            409 -> AppFailure.unknown(server ?: "That object already exists in the bucket.")
            413 -> AppFailure.unknown(server ?: "The file is larger than the bucket's size limit.")
            else -> AppFailure.unknown(server ?: "Upload failed with HTTP ${outcome.code}.")
        }
    }

    private fun databaseFailure(outcome: HttpOutcome): AppFailure {
        if (outcome.transportError != null) {
            return AppFailure.network("Could not reach Supabase: ${outcome.transportError}")
        }
        val server = serverMessage(outcome)
        return when (outcome.code) {
            401, 403 -> AppFailure.unauthorized(
                server ?: "The media table is not readable with this key. Run supabase/schema.sql.",
            )
            404 -> AppFailure.unknown(
                server ?: "Media table was not found. Run supabase/schema.sql to create it.",
            )
            else -> AppFailure.unknown(server ?: "Database request failed with HTTP ${outcome.code}.")
        }
    }

    private fun serverMessage(outcome: HttpOutcome): String? {
        val body = outcome.body?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            listOf("message", "error", "msg", "error_description")
                .firstNotNullOfOrNull { key ->
                    runCatching { obj[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }
                }
        }.getOrNull()
    }

    /** Request body that reports upload progress while streaming the file. */
    private class ProgressRequestBody(
        private val file: File,
        private val mediaType: okhttp3.MediaType?,
        private val onBytes: (Long) -> Unit,
    ) : RequestBody() {

        override fun contentType() = mediaType

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val source = file.inputStream().source()
            source.use {
                val buffer = Buffer()
                var uploaded = 0L
                while (true) {
                    val read = it.read(buffer, BUFFER_SIZE)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    uploaded += read
                    onBytes(uploaded)
                }
                sink.flush()
            }
        }

        private companion object {
            const val BUFFER_SIZE = 16_384L
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
