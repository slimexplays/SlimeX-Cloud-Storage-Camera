package com.slimex.cloudcamera.domain.repository

import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.CloudConfig
import com.slimex.cloudcamera.domain.model.CloudSetupStatus
import com.slimex.cloudcamera.domain.model.ConnectionReport
import kotlinx.coroutines.flow.Flow

/**
 * Stores the user supplied Supabase credentials in Keystore-encrypted storage.
 * Nothing is bundled with the APK: the app is fully usable (local capture) without it.
 */
interface CloudConfigRepository {
    val config: Flow<CloudConfig?>
    val status: Flow<CloudSetupStatus>

    suspend fun current(): CloudConfig?

    /** Validates, probes and persists the configuration. */
    suspend fun save(projectUrl: String, publishableKey: String): AppResult<CloudConfig>

    suspend fun clear()

    /** Runs the multi-step connectivity check against the given (or stored) credentials. */
    suspend fun testConnection(
        projectUrl: String? = null,
        publishableKey: String? = null,
        onStep: (ConnectionReport) -> Unit = {},
    ): AppResult<ConnectionReport>
}
