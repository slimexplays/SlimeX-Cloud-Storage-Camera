package com.slimex.cloudcamera.domain.repository

import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.CloudMedia
import com.slimex.cloudcamera.domain.model.MediaFilter
import com.slimex.cloudcamera.domain.model.MediaSort
import com.slimex.cloudcamera.domain.model.RemoteUsage
import java.io.File

/**
 * The private cloud library: list, preview, open, share and delete the owner's own media.
 *
 * Objects are always fetched with the signed-in user's token; thumbnails are decrypted into the
 * app cache, never into shared storage.
 */
interface CloudGalleryRepository {
    suspend fun list(
        filter: MediaFilter = MediaFilter.ALL,
        sort: MediaSort = MediaSort.NEWEST,
        search: String? = null,
        limit: Int = 100,
        offset: Int = 0,
    ): AppResult<List<CloudMedia>>

    suspend fun usage(): AppResult<RemoteUsage>

    suspend fun delete(media: CloudMedia): AppResult<Unit>

    /** Decrypts a cached thumbnail for the grid (null when no preview exists yet). */
    suspend fun thumbnail(media: CloudMedia): AppResult<File?>

    /** Downloads + decrypts the full media into private cache for the viewer. */
    suspend fun open(media: CloudMedia, onProgress: (Float) -> Unit = {}): AppResult<File>

    /** Copies the decrypted media into the share cache; returns the file handed to FileProvider. */
    suspend fun prepareForShare(media: CloudMedia): AppResult<File>
}
