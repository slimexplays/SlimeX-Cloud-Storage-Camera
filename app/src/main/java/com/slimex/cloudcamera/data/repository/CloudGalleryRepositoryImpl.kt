package com.slimex.cloudcamera.data.repository

import com.slimex.cloudcamera.cloud.CloudSessionProvider
import com.slimex.cloudcamera.cloud.SupabaseDataApi
import com.slimex.cloudcamera.domain.repository.MediaStorageGateway
import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.util.DispatchersProvider
import com.slimex.cloudcamera.domain.model.CloudMedia
import com.slimex.cloudcamera.domain.model.MediaFilter
import com.slimex.cloudcamera.domain.model.MediaSort
import com.slimex.cloudcamera.domain.model.RemoteUsage
import com.slimex.cloudcamera.domain.repository.CloudGalleryRepository
import com.slimex.cloudcamera.media.ThumbnailFactory
import com.slimex.cloudcamera.security.MediaCrypto
import com.slimex.cloudcamera.security.MediaKey
import com.slimex.cloudcamera.security.MediaKeyStore
import com.slimex.cloudcamera.storage.StagingStore
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudGalleryRepositoryImpl @Inject constructor(
    private val cloud: CloudSessionProvider,
    private val data: SupabaseDataApi,
    private val storage: MediaStorageGateway,
    private val crypto: MediaCrypto,
    private val staging: StagingStore,
    private val thumbnails: ThumbnailFactory,
    private val keyStore: MediaKeyStore,
    private val dispatchers: DispatchersProvider,
) : CloudGalleryRepository {

    override suspend fun list(
        filter: MediaFilter,
        sort: MediaSort,
        search: String?,
        limit: Int,
        offset: Int,
    ): AppResult<List<CloudMedia>> {
        val context = when (val result = cloud.require()) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return result
        }
        return data.listMedia(
            config = context.config,
            accessToken = context.accessToken,
            userId = context.userId,
            filter = filter,
            sort = sort,
            search = search,
            limit = limit,
            offset = offset,
        )
    }

    override suspend fun usage(): AppResult<RemoteUsage> {
        val context = when (val result = cloud.require()) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return result
        }
        return data.usage(context.config, context.accessToken, context.userId)
    }

    override suspend fun delete(media: CloudMedia): AppResult<Unit> {
        val context = when (val result = cloud.require()) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return result
        }

        val keys = buildList {
            add(media.objectKey)
            media.thumbnailPath?.takeIf { it.isNotBlank() }?.let { add(it.trimStart('/')) }
        }

        // A missing object is fine — the goal is "gone", not "we deleted something", and the
        // gateway already reports HTTP 404 from R2 as success.
        keys.forEach { key ->
            when (val removed = storage.delete(key)) {
                is AppResult.Failure -> return removed
                is AppResult.Success -> Unit
            }
        }

        val row = data.deleteMedia(context.config, context.accessToken, media.id, context.userId)
        if (row is AppResult.Failure) return row

        withContext(dispatchers.io) {
            runCatching { staging.thumbnailFile(media.id).delete() }
            runCatching { staging.viewerFile(media.id, media.mediaType).delete() }
            runCatching { staging.shareFile(media.id, media.mediaType).delete() }
        }
        // The row is gone, so its local key entry would only be dead weight.
        keyStore.remove(media.id)
        return AppResult.Success(Unit)
    }

    override suspend fun thumbnail(media: CloudMedia): AppResult<File?> {
        val cached = staging.thumbnailFile(media.id)
        if (cached.exists() && cached.length() > 0) return AppResult.Success(cached)

        val thumbPath = media.thumbnailPath?.takeIf { it.isNotBlank() }
        val entry = keyStore.get(media.id)
        val key = entry?.tk
        val nonce = entry?.tn

        if (thumbPath != null && key != null && nonce != null) {
            when (cloud.require()) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> return AppResult.Success(null)
            }
            val scratch = staging.scratchFile("thumb")
            val result = storage.download(thumbPath, scratch)
            if (result is AppResult.Success) {
                val decrypted = crypto.decrypt(scratch, cached, MediaKey(key, nonce))
                runCatching { scratch.delete() }
                if (decrypted is AppResult.Success) return AppResult.Success(cached)
            } else {
                runCatching { scratch.delete() }
            }
        }

        // No sidecar preview (e.g. a row created elsewhere): build one from the media itself,
        // but only for photos so opening the gallery never downloads a whole video.
        if (media.mediaType == com.slimex.cloudcamera.domain.model.MediaKind.PHOTO) {
            return when (val opened = open(media)) {
                is AppResult.Success -> when (val created = thumbnails.create(opened.data, media.mediaType, cached)) {
                    is AppResult.Success -> AppResult.Success(created.data)
                    is AppResult.Failure -> AppResult.Success(null)
                }

                is AppResult.Failure -> AppResult.Success(null)
            }
        }
        return AppResult.Success(null)
    }

    override suspend fun open(media: CloudMedia, onProgress: (Float) -> Unit): AppResult<File> {
        val cached = staging.viewerFile(media.id, media.mediaType)
        if (cached.exists() && cached.length() > 0) {
            onProgress(1f)
            return AppResult.Success(cached)
        }

        // The storage gateway resolves the Worker endpoint and the session itself, so a missing
        // configuration surfaces as a ready-to-show failure from the download call below.
        if (!media.encrypted) {
            return AppResult.Failure(
                AppFailure.storage("This file was stored unencrypted, so it cannot be opened safely."),
            )
        }
        val entry = keyStore.get(media.id)
        if (entry == null || !entry.isComplete) {
            return AppResult.Failure(
                AppFailure.storage(
                    "The encryption key for this file is not on this device. Restore your recovery " +
                        "code from Settings > Encryption & Recovery to open it again.",
                ),
            )
        }
        val key = entry.k
        val nonce = entry.n

        val scratch = staging.scratchFile("download")
        val downloaded = storage.download(
            objectKey = media.objectKey,
            target = scratch,
            onProgress = { fraction -> onProgress(fraction * 0.9f) },
        )
        if (downloaded is AppResult.Failure) {
            runCatching { scratch.delete() }
            return downloaded
        }

        val decrypted = when (val result = crypto.decrypt(scratch, cached, MediaKey(key, nonce))) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> {
                runCatching { scratch.delete() }
                return AppResult.Failure(
                    AppFailure.storage(
                        "The cloud copy could not be decrypted. It may be corrupt, or this key ring " +
                            "does not belong to it.",
                        result.error.cause,
                    ),
                )
            }
        }
        runCatching { scratch.delete() }

        media.checksum?.takeIf { it.isNotBlank() }?.let { expected ->
            if (!crypto.verify(decrypted, expected)) {
                runCatching { decrypted.delete() }
                return AppResult.Failure(
                    AppFailure.storage("The decrypted file did not match its recorded checksum."),
                )
            }
        }

        onProgress(1f)
        return AppResult.Success(decrypted)
    }

    override suspend fun prepareForShare(media: CloudMedia): AppResult<File> {
        val opened = when (val result = open(media)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return result
        }
        val target = staging.shareFile(media.id, media.mediaType)
        return withContext(dispatchers.io) {
            try {
                target.parentFile?.mkdirs()
                if (target.exists()) target.delete()
                opened.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                }
                if (!target.exists() || target.length() == 0L) {
                    AppResult.Failure(AppFailure.storage("Could not prepare the file for sharing."))
                } else {
                    AppResult.Success(target)
                }
            } catch (t: Throwable) {
                runCatching { target.delete() }
                AppResult.Failure(AppFailure.storage(t.message ?: "Could not prepare the file for sharing.", t))
            }
        }
    }
}
