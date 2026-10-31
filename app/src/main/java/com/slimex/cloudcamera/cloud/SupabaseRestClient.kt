package com.slimex.cloudcamera.cloud

import com.slimex.cloudcamera.core.util.DispatchersProvider
import com.slimex.cloudcamera.domain.model.CloudConfig
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Diagnostics-only client used by the setup screen and the connection tester.
 *
 * Real data access lives in [SupabaseAuthApi], [SupabaseStorageApi] and [SupabaseDataApi],
 * which always send the signed-in user's access token. This class only probes endpoints with
 * the publishable key, so it can never read or write a user's media.
 */
@Singleton
class SupabaseRestClient @Inject constructor(
    private val client: OkHttpClient,
    private val dispatchers: DispatchersProvider,
) {

    suspend fun projectHealth(config: CloudConfig): HttpOutcome {
        val url = build(config, "auth", "v1", "health") ?: return invalidUrl()
        return execute(Request.Builder().url(url).get().publicKey(config).build())
    }

    suspend fun restRoot(config: CloudConfig): HttpOutcome {
        val url = build(config, "rest", "v1") ?: return invalidUrl()
        return execute(
            Request.Builder()
                .url(url)
                .get()
                .publicKey(config)
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
                .publicKey(config)
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
                .publicKey(config)
                .header("Accept", "application/json")
                .build(),
        )
    }

    private suspend fun execute(request: Request): HttpOutcome = withContext(dispatchers.io) {
        val started = System.currentTimeMillis()
        try {
            client.newCall(request).execute().use { response ->
                HttpOutcome(
                    code = response.code,
                    body = runCatching { response.body?.string() }.getOrNull(),
                    latencyMs = System.currentTimeMillis() - started,
                )
            }
        } catch (io: IOException) {
            HttpOutcome(
                code = 0,
                transportError = io.message ?: "Network error",
                latencyMs = System.currentTimeMillis() - started,
            )
        } catch (t: Throwable) {
            HttpOutcome(
                code = 0,
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

    private fun Request.Builder.publicKey(config: CloudConfig): Request.Builder = apply {
        header("apikey", config.publishableKey)
        header("Authorization", "Bearer ${config.publishableKey}")
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
