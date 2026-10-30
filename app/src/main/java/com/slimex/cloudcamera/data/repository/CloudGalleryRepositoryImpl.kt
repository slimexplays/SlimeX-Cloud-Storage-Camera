package com.slimex.cloudcamera.data.repository

import com.slimex.cloudcamera.cloud.SupabaseRestClient
import com.slimex.cloudcamera.core.constants.AppConstants
import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.result.map
import com.slimex.cloudcamera.domain.model.CloudAsset
import com.slimex.cloudcamera.domain.model.MediaKind
import com.slimex.cloudcamera.domain.model.RemoteUsage
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import com.slimex.cloudcamera.domain.repository.CloudGalleryRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudGalleryRepositoryImpl @Inject constructor(
    private val configRepository: CloudConfigRepository,
    private val client: SupabaseRestClient,
) : CloudGalleryRepository {

    override suspend fun listAssets(limit: Int): AppResult<List<CloudAsset>> {
        val config = configRepository.current()
            ?: return AppResult.Failure(AppFailure.notConfigured())
        return client
            .selectRows(config, query = "deleted_at=is.null&order=captured_at.desc", limit = limit)
            .map { rows -> rows.mapNotNull(::toAsset) }
    }

    override suspend fun signedUrl(asset: CloudAsset, expiresInSeconds: Long): AppResult<String> {
        val config = configRepository.current()
            ?: return AppResult.Failure(AppFailure.notConfigured())
        return client.createSignedUrl(
            config = config.copy(bucket = asset.bucket.ifBlank { config.bucket }),
            objectPath = asset.storagePath,
            expiresInSeconds = expiresInSeconds,
        )
    }

    override suspend fun deleteAsset(asset: CloudAsset): AppResult<Unit> {
        val config = configRepository.current()
            ?: return AppResult.Failure(AppFailure.notConfigured())
        val objectResult = client.deleteObject(config.copy(bucket = asset.bucket.ifBlank { config.bucket }), asset.storagePath)
        if (objectResult is AppResult.Failure) return objectResult
        return client.deleteRow(config, asset.id)
    }

    override suspend fun remoteUsage(): AppResult<RemoteUsage> {
        val assets = listAssets(limit = 1_000)
        return assets.map { list ->
            RemoteUsage(
                assetCount = list.size,
                photoCount = list.count { it.kind == MediaKind.PHOTO },
                videoCount = list.count { it.kind == MediaKind.VIDEO },
                totalBytes = list.sumOf { it.sizeBytes },
            )
        }
    }

    private fun toAsset(row: JsonObject): CloudAsset? {
        val id = row.string("id") ?: return null
        val path = row.string("storage_path") ?: return null
        return CloudAsset(
            id = id,
            kind = MediaKind.fromValue(row.string("kind")),
            bucket = row.string("bucket") ?: AppConstants.DEFAULT_BUCKET,
            storagePath = path,
            fileName = row.string("file_name") ?: path.substringAfterLast('/'),
            mimeType = row.string("mime_type"),
            sizeBytes = row.long("size_bytes") ?: 0L,
            durationMillis = row.long("duration_ms"),
            capturedAtMillis = row.string("captured_at")?.let(::parseIso),
            width = row.int("width"),
            height = row.int("height"),
        )
    }

    private fun parseIso(value: String): Long? =
        runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching { java.time.Instant.parse(value).toEpochMilli() }
            .getOrNull()

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.long(key: String): Long? =
        runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()
}
