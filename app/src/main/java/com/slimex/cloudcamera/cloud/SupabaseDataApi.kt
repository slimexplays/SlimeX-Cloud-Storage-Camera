package com.slimex.cloudcamera.cloud

import com.slimex.cloudcamera.core.constants.AppConstants
import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.util.DispatchersProvider
import com.slimex.cloudcamera.domain.model.CloudConfig
import com.slimex.cloudcamera.domain.model.CloudMedia
import com.slimex.cloudcamera.domain.model.MediaFilter
import com.slimex.cloudcamera.domain.model.MediaKind
import com.slimex.cloudcamera.domain.model.MediaSort
import com.slimex.cloudcamera.domain.model.RemoteUsage
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/** Row written to `public.media_files` after a verified upload. */
data class MediaRowInput(
    val id: String,
    val userId: String,
    val storagePath: String,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val mediaType: MediaKind,
    val checksum: String,
    val createdAtIso: String,
    val thumbnailPath: String?,
    val thumbnailSize: Long?,
    val thumbnailChecksum: String?,
)

/**
 * PostgREST access to the app's own tables.
 *
 * Row level security guarantees that a signed-in user can only ever see or modify their own
 * rows, so filtering by `user_id` here is defence in depth, not the only protection.
 */
@Singleton
class SupabaseDataApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val dispatchers: DispatchersProvider,
) {

    suspend fun listMedia(
        config: CloudConfig,
        accessToken: String,
        userId: String,
        filter: MediaFilter,
        sort: MediaSort,
        search: String? = null,
        limit: Int = 100,
        offset: Int = 0,
    ): AppResult<List<CloudMedia>> = withContext(dispatchers.io) {
        val builder = tableUrl(config, config.table)?.newBuilder() ?: return@withContext invalidUrl()
        builder.addQueryParameter("select", SELECT_COLUMNS)
        builder.addQueryParameter("user_id", "eq.$userId")
        builder.addQueryParameter("order", sort.order)
        builder.addQueryParameter("limit", limit.coerceIn(1, 200).toString())
        builder.addQueryParameter("offset", offset.coerceAtLeast(0).toString())
        filter.mediaType?.let { builder.addQueryParameter("media_type", "eq.$it") }
        search?.trim()?.takeIf { it.isNotBlank() }?.let {
            val escaped = it.replace("*", "").replace(",", " ")
            builder.addQueryParameter("file_name", "ilike.*$escaped*")
        }

        val outcome = execute(Request.Builder().url(builder.build()).get().authed(config, accessToken).build())
        if (!outcome.isSuccessful) {
            return@withContext AppResult.Failure(CloudFailures.database(outcome, "load the cloud gallery"))
        }
        val body = outcome.body ?: return@withContext AppResult.Success(emptyList())
        val rows = runCatching {
            json.parseToJsonElement(body).jsonArray.mapNotNull { row -> (row as? JsonObject)?.toCloudMedia() }
        }.getOrElse { return@withContext AppResult.Failure(AppFailure.unknown("Could not read the gallery response.")) }
        AppResult.Success(rows)
    }

    suspend fun findMediaByPath(
        config: CloudConfig,
        accessToken: String,
        storagePath: String,
    ): AppResult<CloudMedia?> = withContext(dispatchers.io) {
        val builder = tableUrl(config, config.table)?.newBuilder() ?: return@withContext invalidUrl()
        builder.addQueryParameter("select", SELECT_COLUMNS)
        builder.addQueryParameter("storage_path", "eq.$storagePath")
        builder.addQueryParameter("limit", "1")
        val outcome = execute(Request.Builder().url(builder.build()).get().authed(config, accessToken).build())
        if (!outcome.isSuccessful) {
            return@withContext AppResult.Failure(CloudFailures.database(outcome, "verify the uploaded row"))
        }
        val body = outcome.body ?: return@withContext AppResult.Success(null)
        val row = runCatching {
            json.parseToJsonElement(body).jsonArray.firstOrNull()
                ?.let { row -> (row as? JsonObject)?.toCloudMedia() }
        }.getOrNull()
        AppResult.Success(row)
    }

    suspend fun insertMedia(
        config: CloudConfig,
        accessToken: String,
        row: MediaRowInput,
    ): AppResult<Unit> = withContext(dispatchers.io) {
        val url = tableUrl(config, config.table) ?: return@withContext invalidUrl()
        val payload = buildJsonObject {
            put("id", JsonPrimitive(row.id))
            put("user_id", JsonPrimitive(row.userId))
            put("storage_path", JsonPrimitive(row.storagePath))
            put("file_name", JsonPrimitive(row.fileName))
            put("mime_type", JsonPrimitive(row.mimeType))
            put("file_size", JsonPrimitive(row.fileSize))
            put("media_type", JsonPrimitive(row.mediaType.value))
            put("encrypted", JsonPrimitive(true))
            put("encryption_alg", JsonPrimitive("AES-256-GCM"))
            put("checksum", JsonPrimitive(row.checksum))
            put("created_at", JsonPrimitive(row.createdAtIso))
            row.thumbnailPath?.let { put("thumbnail_path", JsonPrimitive(it)) }
            row.thumbnailSize?.let { put("thumbnail_size", JsonPrimitive(it)) }
            row.thumbnailChecksum?.let { put("thumbnail_checksum", JsonPrimitive(it)) }
        }.toString()

        // Idempotent insert: a retry after a crash mid-upload merges into the same row instead of
        // failing with a duplicate-key error (the media id is generated before the first attempt).
        val request = Request.Builder()
            .url("$url?on_conflict=id")
            .post(payload.toRequestBody(JSON))
            .authed(config, accessToken)
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .build()
        val outcome = execute(request)
        if (!outcome.isSuccessful) {
            return@withContext AppResult.Failure(CloudFailures.database(outcome, "save the media metadata"))
        }
        AppResult.Success(Unit)
    }

    suspend fun deleteMedia(
        config: CloudConfig,
        accessToken: String,
        mediaId: String,
        userId: String,
    ): AppResult<Unit> = withContext(dispatchers.io) {
        val builder = tableUrl(config, config.table)?.newBuilder() ?: return@withContext invalidUrl()
        builder.addQueryParameter("id", "eq.$mediaId")
        builder.addQueryParameter("user_id", "eq.$userId")
        val outcome = execute(
            Request.Builder().url(builder.build()).delete().authed(config, accessToken).build(),
        )
        if (!outcome.isSuccessful) {
            return@withContext AppResult.Failure(CloudFailures.database(outcome, "delete the media row"))
        }
        AppResult.Success(Unit)
    }

    /** Upload-job bookkeeping so the server knows what this device is working on. */
    suspend fun upsertUploadJob(
        config: CloudConfig,
        accessToken: String,
        jobId: String,
        userId: String,
        mediaId: String,
        status: String,
        retryCount: Int,
    ): AppResult<Unit> = withContext(dispatchers.io) {
        val url = tableUrl(config, AppConstants.UPLOAD_JOBS_TABLE) ?: return@withContext invalidUrl()
        val payload = buildJsonObject {
            put("id", JsonPrimitive(jobId))
            put("user_id", JsonPrimitive(userId))
            put("media_id", JsonPrimitive(mediaId))
            put("status", JsonPrimitive(status))
            put("retry_count", JsonPrimitive(retryCount))
            put("updated_at", JsonPrimitive(isoNow()))
        }.toString()
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON))
            .authed(config, accessToken)
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .build()
        val outcome = execute(request)
        if (!outcome.isSuccessful) {
            return@withContext AppResult.Failure(CloudFailures.database(outcome, "sync the upload job"))
        }
        AppResult.Success(Unit)
    }

    suspend fun deleteUploadJob(config: CloudConfig, accessToken: String, jobId: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            val builder = tableUrl(config, AppConstants.UPLOAD_JOBS_TABLE)?.newBuilder()
                ?: return@withContext invalidUrl()
            builder.addQueryParameter("id", "eq.$jobId")
            val outcome = execute(
                Request.Builder().url(builder.build()).delete().authed(config, accessToken).build(),
            )
            if (!outcome.isSuccessful) {
                return@withContext AppResult.Failure(CloudFailures.database(outcome, "remove the upload job"))
            }
            AppResult.Success(Unit)
        }

    /** Aggregate usage, computed client-side so it works on every PostgREST version. */
    suspend fun usage(config: CloudConfig, accessToken: String, userId: String): AppResult<RemoteUsage> =
        withContext(dispatchers.io) {
            var assets = 0
            var photos = 0
            var videos = 0
            var bytes = 0L
            var offset = 0
            while (true) {
                val builder = tableUrl(config, config.table)?.newBuilder() ?: return@withContext invalidUrl()
                builder.addQueryParameter("select", "file_size,media_type")
                builder.addQueryParameter("user_id", "eq.$userId")
                builder.addQueryParameter("limit", "1000")
                builder.addQueryParameter("offset", offset.toString())
                val outcome = execute(
                    Request.Builder().url(builder.build()).get().authed(config, accessToken).build(),
                )
                if (!outcome.isSuccessful) {
                    return@withContext AppResult.Failure(CloudFailures.database(outcome, "read the storage usage"))
                }
                val page = runCatching {
                    json.parseToJsonElement(outcome.body.orEmpty()).jsonArray
                }.getOrElse { return@withContext AppResult.Success(RemoteUsage()) }

                page.forEach { element ->
                    val obj = element as? JsonObject ?: return@forEach
                    val size = (obj["file_size"] as? JsonPrimitive)
                        ?.let { runCatching { it.content.toLong() }.getOrNull() } ?: 0L
                    val type = obj["media_type"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    assets++
                    bytes += size
                    if (type.equals("video", ignoreCase = true)) videos++ else photos++
                }
                if (page.size < 1000) break
                offset += 1000
                if (offset > 100_000) break
            }
            AppResult.Success(
                RemoteUsage(assetCount = assets, photoCount = photos, videoCount = videos, totalBytes = bytes),
            )
        }

    // ---------------------------------------------------------------- plumbing

    private fun JsonObject.toCloudMedia(): CloudMedia? {
        val id = string("id") ?: return null
        val path = string("storage_path") ?: return null
        return CloudMedia(
            id = id,
            userId = string("user_id").orEmpty(),
            storagePath = path,
            fileName = string("file_name") ?: path.substringAfterLast('/'),
            mimeType = string("mime_type"),
            fileSize = string("file_size")?.toLongOrNull() ?: 0L,
            mediaType = MediaKind.fromValue(string("media_type")),
            encrypted = (this["encrypted"] as? JsonPrimitive)?.let {
                runCatching { it.content.toBoolean() }.getOrDefault(true)
            } ?: true,
            checksum = string("checksum"),
            createdAtMillis = parseIso(string("created_at")),
            thumbnailPath = string("thumbnail_path"),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.let { runCatching { it.content }.getOrNull() }?.takeIf { it.isNotBlank() }

    private fun parseIso(value: String?): Long {
        if (value.isNullOrBlank()) return System.currentTimeMillis()
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        patterns.forEach { pattern ->
            runCatching {
                val format = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                format.parse(value)?.time
            }.getOrNull()?.let { return it }
        }
        return System.currentTimeMillis()
    }

    private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(java.util.Date())

    private fun tableUrl(config: CloudConfig, table: String): HttpUrl? {
        val base = config.projectUrl.toHttpUrlOrNull() ?: return null
        return base.newBuilder().addPathSegment("rest").addPathSegment("v1").addPathSegment(table).build()
    }

    private fun Request.Builder.authed(config: CloudConfig, accessToken: String): Request.Builder = apply {
        header("apikey", config.publishableKey)
        header("Authorization", "Bearer $accessToken")
        header("Accept", "application/json")
    }

    private suspend fun execute(request: Request): HttpOutcome = withContext(dispatchers.io) {
        try {
            client.newCall(request).execute().use { response ->
                HttpOutcome(
                    code = response.code,
                    body = runCatching { response.body?.string() }.getOrNull(),
                )
            }
        } catch (io: IOException) {
            HttpOutcome(code = 0, transportError = io.message ?: "Network error")
        } catch (t: Throwable) {
            HttpOutcome(code = 0, transportError = t.message ?: "Unexpected networking error")
        }
    }

    private fun invalidUrl(): AppResult.Failure = AppResult.Failure(
        AppFailure.unknown("The stored Supabase project URL is invalid. Re-save it in Settings."),
    )

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()
        const val SELECT_COLUMNS =
            "id,user_id,storage_path,file_name,mime_type,file_size,media_type,encrypted,checksum," +
                "created_at,thumbnail_path"
    }
}
