package com.slimex.cloudcamera.domain.repository

import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.CloudAsset
import com.slimex.cloudcamera.domain.model.RemoteUsage

/** Read access to the assets already uploaded to the user's Supabase project. */
interface CloudGalleryRepository {
    suspend fun listAssets(limit: Int = 60): AppResult<List<CloudAsset>>

    /** Short-lived signed URL for a private object (bucket is never public). */
    suspend fun signedUrl(asset: CloudAsset, expiresInSeconds: Long = 3_600): AppResult<String>

    suspend fun deleteAsset(asset: CloudAsset): AppResult<Unit>

    suspend fun remoteUsage(): AppResult<RemoteUsage>
}
