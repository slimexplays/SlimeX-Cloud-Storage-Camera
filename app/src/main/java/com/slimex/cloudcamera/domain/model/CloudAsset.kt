package com.slimex.cloudcamera.domain.model

/** A row of `public.media_assets` in the user's Supabase project. */
data class CloudAsset(
    val id: String,
    val kind: MediaKind,
    val bucket: String,
    val storagePath: String,
    val fileName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val durationMillis: Long?,
    val capturedAtMillis: Long?,
    val width: Int? = null,
    val height: Int? = null,
)
