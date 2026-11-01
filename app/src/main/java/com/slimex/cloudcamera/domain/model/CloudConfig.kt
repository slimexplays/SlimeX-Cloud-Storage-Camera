package com.slimex.cloudcamera.domain.model

import com.slimex.cloudcamera.core.constants.AppConstants

/**
 * Runtime Supabase configuration entered by the user in the Settings screen.
 *
 * The [publishableKey] is a public client key (Supabase publishable key or legacy
 * anon key). Service-role / secret keys are rejected at validation time and must
 * never ship inside the APK.
 */
data class CloudConfig(
    val projectUrl: String,
    val publishableKey: String,
    val table: String = AppConstants.MEDIA_TABLE,
    val savedAt: Long = System.currentTimeMillis(),
) {
    val host: String
        get() = projectUrl.removePrefix("https://").removePrefix("http://").substringBefore("/")

    /** Never expose the raw key in logs or UI copy. */
    val maskedKey: String
        get() = if (publishableKey.length <= 10) "••••" else publishableKey.take(6) + "…" + publishableKey.takeLast(4)
}

/** Connection state surfaced by the Supabase configuration screen and Home. */
sealed interface CloudSetupStatus {
    data object NotConfigured : CloudSetupStatus
    data class Configured(val host: String, val savedAt: Long) : CloudSetupStatus
    data object Loading : CloudSetupStatus
}
