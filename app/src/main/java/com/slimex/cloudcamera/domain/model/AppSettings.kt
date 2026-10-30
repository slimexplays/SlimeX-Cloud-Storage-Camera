package com.slimex.cloudcamera.domain.model

/** User-selectable photo quality. JPEG is always used — no unnecessary re-compression. */
enum class PhotoQuality(val label: String, val description: String) {
    BALANCED("Balanced", "Fast shutter, optimised JPEG, great everyday quality"),
    HIGH("High", "Maximum supported sensor quality, slightly slower capture");

    companion object {
        fun fromName(name: String?): PhotoQuality =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

/**
 * Video quality preference. AUTO prefers 1080p when the device supports it and
 * falls back to 720p / the highest suitable quality automatically.
 */
enum class VideoQualityPreference(val label: String, val description: String) {
    AUTO("Auto", "Prefer 1080p, fall back to 720p or best available"),
    FHD_1080("1080p", "Full HD, fall back to 720p if unsupported"),
    HD_720("720p", "HD, smaller files and faster uploads"),
    HIGHEST("Highest", "Best resolution the camera supports");

    companion object {
        fun fromName(name: String?): VideoQualityPreference =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** Everything the app persists about user preferences (never credentials). */
data class AppSettings(
    val onboardingCompleted: Boolean = false,
    val autoUpload: Boolean = true,
    val wifiOnlyUpload: Boolean = false,
    val photoQuality: PhotoQuality = PhotoQuality.BALANCED,
    val videoQuality: VideoQualityPreference = VideoQualityPreference.AUTO,
    val recordVideoAudio: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val remoteThumbnailsInGallery: Boolean = true,
    val deviceId: String = "",
)
