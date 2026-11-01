package com.slimex.cloudcamera.domain.model

import com.slimex.cloudcamera.core.constants.AppConstants

/**
 * Runtime configuration of the media backend.
 *
 * Only the **Worker endpoint** is stored on the device. The Worker is the only component that
 * knows the R2 account id, access key id, secret access key, bucket name and S3 endpoint — those
 * values are Cloudflare Worker secrets and never exist in this app, its settings or its database.
 */
data class R2Config(
    val workerUrl: String,
    val providerName: String = AppConstants.DEFAULT_STORAGE_PROVIDER,
    val savedAt: Long = System.currentTimeMillis(),
) {
    /** Host shown in the UI (never the full path, which can contain a routing slug). */
    val host: String
        get() = workerUrl.removePrefix("https://").removePrefix("http://").substringBefore("/")

    /** Endpoint preview that never exposes query strings or credentials. */
    val displayEndpoint: String
        get() = if (workerUrl.length <= 42) workerUrl else workerUrl.take(42) + "…"
}

/** Connection state of the media backend, surfaced by the cloud setup screen and Home. */
sealed interface R2SetupStatus {
    data object NotConfigured : R2SetupStatus
    data class Configured(val host: String, val savedAt: Long) : R2SetupStatus
    data object Loading : R2SetupStatus
}

/** `GET /health` — public, returns no user data. */
data class WorkerHealth(
    val ok: Boolean,
    val provider: String?,
    val bucketReady: Boolean?,
    val version: String?,
    val maxUploadBytes: Long?,
    val latencyMs: Long,
)

/** `POST /v1/uploads/authorize` — a short-lived, single-object PUT URL. */
data class UploadAuthorization(
    val uploadUrl: String,
    val objectKey: String,
    val expiresInSeconds: Int,
)

/** `GET /v1/downloads/authorize` — a short-lived, single-object GET URL. */
data class DownloadAuthorization(
    val downloadUrl: String,
    val sizeBytes: Long?,
    val expiresInSeconds: Int,
)

/** Result of a HEAD/stat call on an object in the private bucket. */
data class StoredObject(
    val objectKey: String,
    val sizeBytes: Long,
    val etag: String?,
    val uploadedAtIso: String?,
)

/** Verified usage computed by the Worker (it lists the caller's own prefix). */
data class ProviderUsage(
    val objectCount: Int,
    val totalBytes: Long,
    val truncated: Boolean = false,
    val checkedAtMillis: Long = System.currentTimeMillis(),
)

/**
 * Everything the "Cloud Storage Status" card shows.
 *
 * [verifiedUsage] comes from the storage provider itself, [metadataUsage] from Supabase rows.
 * They are deliberately kept apart so the UI can label one "verified" and the other "recorded".
 * R2 reports no quota, so [freeQuotaBytes] stays null and the card says so instead of inventing a
 * number.
 */
data class CloudStorageStatus(
    val providerLabel: String,
    val providerConfigured: Boolean,
    val workerReachable: Boolean? = null,
    val bucketReady: Boolean? = null,
    val bucketName: String? = null,
    val maxUploadBytes: Long? = null,
    val verifiedUsage: ProviderUsage? = null,
    val metadataUsage: RemoteUsage? = null,
    val freeQuotaBytes: Long? = null,
    val checkedAtMillis: Long? = null,
    val error: String? = null,
)
