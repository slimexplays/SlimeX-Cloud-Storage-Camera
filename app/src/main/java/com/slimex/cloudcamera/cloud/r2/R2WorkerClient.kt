package com.slimex.cloudcamera.cloud.r2

import com.slimex.cloudcamera.cloud.HttpOutcome
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP client for the SlimeX Cloudflare Worker API.
 *
 * Every protected call carries the signed-in user's **Supabase access token** as a bearer token;
 * the Worker validates it against Supabase and only then talks to R2. No R2 credential, bucket
 * name or object URL ever reaches this class from the app side.
 *
 * Endpoints (see `worker/src/index.js`):
 *
 *   GET    /health                        public liveness + bucket readiness
 *   GET    /v1/me                         validates the Supabase JWT
 *   GET    /v1/usage                      verified bytes/count under the caller's prefix
 *   POST   /v1/uploads/authorize          short-lived PUT URL for one object
 *   GET    /v1/downloads/authorize        short-lived GET URL for one object
 *   GET    /v1/objects/stat               HEAD one object (size, etag, upload time)
 *   DELETE /v1/objects                    delete one object
 */
@Singleton
class R2WorkerClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val dispatchers: DispatchersProvider,
) {

    suspend fun health(config: R2Config): AppResult<WorkerHealth> = withContext(dispatchers.io) {
        val url = endpoint(config, "health") ?: return@withContext invalidEndpoint()
        val started = System.currentTimeMillis()
        val outcome = execute(Request.Builder().url(url).get().acceptJson().build())
        val latency = System.currentTimeMillis() - started
        transport(outcome, "reach the media Worker")?.let { return@withContext AppResult.Failure(it) }
        if (!outcome.isSuccessful) {
            return@withContext AppResult.Failure(failure(outcome, "reach the media Worker"))
        }
        val body = parseObject(outcome.body)
            ?: return@withContext AppResult.Failure(
                AppFailure.unknown("The Worker answered with something that is not the SlimeX API."),
            )
        AppResult.Success(
            WorkerHealth(
                ok = body.boolean("ok") ?: (outcome.isSuccessful),
                provider = body.string("provider"),
                bucketReady = body.boolean("bucketReady"),
                version = body.string("version"),
                maxUploadBytes = body.long("maxUploadBytes"),
                latencyMs = latency,
            ),
        )
    }

    /** Confirms the Worker accepts the caller's Supabase JWT. */
    suspend fun verifySession(config: R2Config, accessToken: String): AppResult<String> =
        withContext(dispatchers.io) {
            val url = endpoint(config, "v1", "me") ?: return@withContext invalidEndpoint()
            val outcome = execute(Request.Builder().url(url).get().acceptJson().bearer(accessToken).build())
            transport(outcome, "authenticate with the media Worker")?.let { return@withContext AppResult.Failure(it) }
            if (!outcome.isSuccessful) {
                return@withContext AppResult.Failure(failure(outcome, "authenticate with the media Worker"))
            }
            val body = parseObject(outcome.body)
            val userId = body?.string("userId")
            if (userId.isNullOrBlank()) {
                return@withContext AppResult.Failure(
                    AppFailure.unauthorized("The Worker did not confirm your session. Sign in again."),
                )
            }
            AppResult.Success(userId)
        }

    suspend fun usage(config: R2Config, accessToken: String): AppResult<ProviderUsage> =
        withContext(dispatchers.io) {
            val url = endpoint(config, "v1", "usage") ?: return@withContext invalidEndpoint()
            val outcome = execute(Request.Builder().url(url).get().acceptJson().bearer(accessToken).build())
            transport(outcome, "read the storage usage")?.let { return@withContext AppResult.Failure(it) }
            if (!outcome.isSuccessful) {
                return@withContext AppResult.Failure(failure(outcome, "read the storage usage"))
            }
            val body = parseObject(outcome.body)
                ?: return@withContext AppResult.Failure(
                    AppFailure.unknown("The Worker returned no usage information."),
                )
            AppResult.Success(
                ProviderUsage(
                    objectCount = body.long("objectCount")?.toInt() ?: 0,
                    totalBytes = body.long("totalBytes") ?: 0L,
                    truncated = body.boolean("truncated") ?: false,
                ),
            )
        }

    suspend fun authorizeUpload(
        config: R2Config,
        accessToken: String,
        objectKey: String,
        sizeBytes: Long,
        contentType: String,
    ): AppResult<UploadAuthorization> = withContext(dispatchers.io) {
        val url = endpoint(config, "v1", "uploads", "authorize") ?: return@withContext invalidEndpoint()
        val payload = buildJsonObject {
            put("objectKey", JsonPrimitive(objectKey))
            put("sizeBytes", JsonPrimitive(sizeBytes))
            put("contentType", JsonPrimitive(contentType))
        }.toString()
        val outcome = execute(
            Request.Builder()
                .url(url)
                .post(payload.toRequestBody(JSON_MEDIA))
                .acceptJson()
                .bearer(accessToken)
                .build(),
        )
        transport(outcome, "request an upload authorization")?.let { return@withContext AppResult.Failure(it) }
        if (!outcome.isSuccessful) {
            return@withContext AppResult.Failure(failure(outcome, "request an upload authorization"))
        }
        val body = parseObject(outcome.body)
        val uploadUrl = body?.string("uploadUrl")
        if (uploadUrl.isNullOrBlank()) {
            return@withContext AppResult.Failure(
                AppFailure.unknown("The Worker did not return an upload URL. Try again."),
            )
        }
        AppResult.Success(
            UploadAuthorization(
                uploadUrl = uploadUrl,
                objectKey = body?.string("objectKey") ?: objectKey,
                expiresInSeconds = body?.long("expiresInSeconds")?.toInt() ?: 300,
            ),
        )
    }

    suspend fun authorizeDownload(
        config: R2Config,
        accessToken: String,
        objectKey: String,
    ): AppResult<DownloadAuthorization> = withContext(dispatchers.io) {
        val base = endpoint(config, "v1", "downloads", "authorize") ?: return@withContext invalidEndpoint()
        val url = base.newBuilder().addQueryParameter("objectKey", objectKey).build()
        val outcome = execute(Request.Builder().url(url).get().acceptJson().bearer(accessToken).build())
        transport(outcome, "request a download authorization")?.let { return@withContext AppResult.Failure(it) }
        if (!outcome.isSuccessful) {
            return@withContext AppResult.Failure(failure(outcome, "request a download authorization"))
        }
        val body = parseObject(outcome.body)
        val downloadUrl = body?.string("downloadUrl")
        if (downloadUrl.isNullOrBlank()) {
            return@withContext AppResult.Failure(
                AppFailure.unknown("The Worker did not return a download URL. Try again."),
            )
        }
        AppResult.Success(
            DownloadAuthorization(
                downloadUrl = downloadUrl,
                sizeBytes = body?.long("sizeBytes"),
                expiresInSeconds = body?.long("expiresInSeconds")?.toInt() ?: 300,
            ),
        )
    }

    suspend fun stat(config: R2Config, accessToken: String, objectKey: String): AppResult<StoredObject> =
        withContext(dispatchers.io) {
            val base = endpoint(config, "v1", "objects", "stat") ?: return@withContext invalidEndpoint()
            val url = base.newBuilder().addQueryParameter("objectKey", objectKey).build()
            val outcome = execute(Request.Builder().url(url).get().acceptJson().bearer(accessToken).build())
            transport(outcome, "verify the stored object")?.let { return@withContext AppResult.Failure(it) }
            if (outcome.code == 404) {
                return@withContext AppResult.Failure(
                    AppFailure.unknown("The object is not in the bucket yet."),
                )
            }
            if (!outcome.isSuccessful) {
                return@withContext AppResult.Failure(failure(outcome, "verify the stored object"))
            }
            val body = parseObject(outcome.body)
                ?: return@withContext AppResult.Failure(
                    AppFailure.unknown("The Worker returned no object details."),
                )
            AppResult.Success(
                StoredObject(
                    objectKey = body?.string("objectKey") ?: objectKey,
                    sizeBytes = body.long("sizeBytes") ?: 0L,
                    etag = body.string("etag"),
                    uploadedAtIso = body.string("uploadedAt"),
                ),
            )
        }

    suspend fun delete(config: R2Config, accessToken: String, objectKey: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            val base = endpoint(config, "v1", "objects") ?: return@withContext invalidEndpoint()
            val url = base.newBuilder().addQueryParameter("objectKey", objectKey).build()
            val outcome = execute(Request.Builder().url(url).delete().acceptJson().bearer(accessToken).build())
            transport(outcome, "delete the file")?.let { return@withContext AppResult.Failure(it) }
            // A missing object means the goal ("gone") is already met.
            if (outcome.isSuccessful || outcome.code == 404) return@withContext AppResult.Success(Unit)
            AppResult.Failure(failure(outcome, "delete the file"))
        }

    // ---------------------------------------------------------------- plumbing

    private fun endpoint(config: R2Config, vararg segments: String): HttpUrl? {
        val base = config.workerUrl.toHttpUrlOrNull() ?: return null
        val builder = base.newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        return builder.build()
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

    private fun Request.Builder.bearer(token: String): Request.Builder =
        header("Authorization", "Bearer $token")

    private fun Request.Builder.acceptJson(): Request.Builder =
        header("Accept", "application/json")

    private fun transport(outcome: HttpOutcome, action: String): AppFailure? {
        val error = outcome.transportError ?: return null
        return AppFailure.network(
            "Could not $action: $error. Check the Worker URL and your connection.",
        )
    }

    /** Maps structured Worker errors (`{"error":{"code","message"}}`) to typed failures. */
    private fun failure(outcome: HttpOutcome, action: String): AppFailure {
        val message = serverMessage(outcome)
        return when (outcome.code) {
            401, 403 -> AppFailure.unauthorized(
                message ?: "The media Worker rejected the request. Sign in again and retry.",
            )

            404 -> AppFailure(
                message ?: "Worker endpoint not found. Check the URL and that the Worker is deployed.",
                FailureKind.NOT_CONFIGURED,
            )

            405 -> AppFailure(
                message ?: "The configured URL does not expose the SlimeX Worker API. Check the endpoint.",
                FailureKind.NOT_CONFIGURED,
            )

            413 -> AppFailure.storage(
                message ?: "That file is larger than the Worker upload limit.",
            )

            429 -> AppFailure.network(message ?: "Too many requests. Wait a moment and retry.")

            502, 503, 504 -> AppFailure.network(
                message ?: "Cloudflare R2 is not reachable right now (HTTP ${outcome.code}).",
            )

            else -> AppFailure.unknown(message ?: "Could not $action (HTTP ${outcome.code}).")
        }
    }

    private fun serverMessage(outcome: HttpOutcome): String? {
        val body = outcome.body?.takeIf { it.isNotBlank() } ?: return null
        val parsed = parseObject(body)
        val errorObject = parsed?.get("error") as? JsonObject
        val message = errorObject?.string("message")
            ?: parsed?.string("message")
            ?: parsed?.string("error")
        if (!message.isNullOrBlank()) return message
        val text = body.trim()
        // Cloudflare returns an HTML page for unknown routes on workers.dev.
        if (text.startsWith("<")) {
            return "The endpoint answered with a web page instead of the SlimeX Worker API."
        }
        return text.takeIf { it.length <= 180 }
    }

    private fun parseObject(body: String?): JsonObject? {
        val raw = body?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.let { runCatching { it.content }.getOrNull() }?.takeIf { it.isNotBlank() }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.let { runCatching { it.content.toLong() }.getOrNull() }

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.let { runCatching { it.content.toBooleanStrict() }.getOrNull() }

    private fun invalidEndpoint(): AppResult.Failure = AppResult.Failure(
        AppFailure(
            message = "The stored Worker endpoint is invalid. Re-save it in Settings.",
            kind = FailureKind.NOT_CONFIGURED,
        ),
    )

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
