package com.slimex.cloudcamera.domain.repository

import com.slimex.cloudcamera.domain.model.AppSettings
import com.slimex.cloudcamera.domain.model.PhotoQuality
import com.slimex.cloudcamera.domain.model.VideoQualityPreference
import kotlinx.coroutines.flow.Flow

/** Single source of truth for user preferences (DataStore backed). */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun current(): AppSettings

    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setAutoUpload(enabled: Boolean)
    suspend fun setWifiOnlyUpload(enabled: Boolean)
    suspend fun setPhotoQuality(quality: PhotoQuality)
    suspend fun setVideoQuality(quality: VideoQualityPreference)
    suspend fun setRecordVideoAudio(enabled: Boolean)
    suspend fun setNotificationsEnabled(enabled: Boolean)
    suspend fun setRemoteThumbnails(enabled: Boolean)

    /** Stable per-install identifier used to tag rows in Supabase. Generated on first use. */
    suspend fun deviceId(): String
}
