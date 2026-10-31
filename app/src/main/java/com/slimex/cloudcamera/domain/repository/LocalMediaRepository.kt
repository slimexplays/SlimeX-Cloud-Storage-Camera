package com.slimex.cloudcamera.domain.repository

import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.LocalMedia
import com.slimex.cloudcamera.domain.model.LocalStorageSummary
import kotlinx.coroutines.flow.StateFlow

/**
 * App-private staging area. Every file listed here is a capture that has **not** been verified
 * in the cloud yet, so nothing in this repository may be deleted by cache clean-up.
 */
interface LocalMediaRepository {
    val pending: StateFlow<List<LocalMedia>>

    suspend fun refresh()

    suspend fun summary(): AppResult<LocalStorageSummary>

    /** Frees evictable caches (thumbnails, viewer, share) and reports the bytes reclaimed. */
    suspend fun clearCache(): AppResult<Long>

    /** Moves Part 1 captures into the encrypted pipeline (one-time, idempotent). */
    suspend fun migrateLegacyCaptures(): AppResult<Int>
}
