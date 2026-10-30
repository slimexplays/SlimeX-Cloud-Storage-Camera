package com.slimex.cloudcamera.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.slimex.cloudcamera.domain.model.AppSettings
import com.slimex.cloudcamera.domain.model.PhotoQuality
import com.slimex.cloudcamera.domain.model.VideoQualityPreference
import com.slimex.cloudcamera.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs -> prefs.toSettings() }

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun setOnboardingCompleted(completed: Boolean) = put(Keys.ONBOARDING, completed)

    override suspend fun setAutoUpload(enabled: Boolean) = put(Keys.AUTO_UPLOAD, enabled)

    override suspend fun setWifiOnlyUpload(enabled: Boolean) = put(Keys.WIFI_ONLY, enabled)

    override suspend fun setPhotoQuality(quality: PhotoQuality) = put(Keys.PHOTO_QUALITY, quality.name)

    override suspend fun setVideoQuality(quality: VideoQualityPreference) = put(Keys.VIDEO_QUALITY, quality.name)

    override suspend fun setRecordVideoAudio(enabled: Boolean) = put(Keys.RECORD_AUDIO, enabled)

    override suspend fun setNotificationsEnabled(enabled: Boolean) = put(Keys.NOTIFICATIONS, enabled)

    override suspend fun setRemoteThumbnails(enabled: Boolean) = put(Keys.REMOTE_THUMBS, enabled)

    override suspend fun deviceId(): String {
        val existing = dataStore.data
            .catch { emit(emptyPreferences()) }
            .map { it[Keys.DEVICE_ID].orEmpty() }
            .first()
        if (existing.isNotBlank()) return existing
        val generated = UUID.randomUUID().toString()
        dataStore.edit { it[Keys.DEVICE_ID] = generated }
        return generated
    }

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { it[key] = value }
    }

    private suspend fun put(key: Preferences.Key<String>, value: String) {
        dataStore.edit { it[key] = value }
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        onboardingCompleted = this[Keys.ONBOARDING] ?: false,
        autoUpload = this[Keys.AUTO_UPLOAD] ?: true,
        wifiOnlyUpload = this[Keys.WIFI_ONLY] ?: false,
        photoQuality = PhotoQuality.fromName(this[Keys.PHOTO_QUALITY]),
        videoQuality = VideoQualityPreference.fromName(this[Keys.VIDEO_QUALITY]),
        recordVideoAudio = this[Keys.RECORD_AUDIO] ?: true,
        notificationsEnabled = this[Keys.NOTIFICATIONS] ?: true,
        remoteThumbnailsInGallery = this[Keys.REMOTE_THUMBS] ?: true,
        deviceId = this[Keys.DEVICE_ID].orEmpty(),
    )

    private object Keys {
        val ONBOARDING = booleanPreferencesKey("onboarding_completed")
        val AUTO_UPLOAD = booleanPreferencesKey("auto_upload")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_upload")
        val PHOTO_QUALITY = stringPreferencesKey("photo_quality")
        val VIDEO_QUALITY = stringPreferencesKey("video_quality")
        val RECORD_AUDIO = booleanPreferencesKey("record_video_audio")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val REMOTE_THUMBS = booleanPreferencesKey("remote_thumbnails")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }
}
