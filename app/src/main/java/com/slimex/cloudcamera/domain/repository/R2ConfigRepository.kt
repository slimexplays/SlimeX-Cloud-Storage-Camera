package com.slimex.cloudcamera.domain.repository

import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.ConnectionReport
import com.slimex.cloudcamera.domain.model.R2Config
import com.slimex.cloudcamera.domain.model.R2SetupStatus
import kotlinx.coroutines.flow.Flow

/**
 * Stores the Worker endpoint the app talks to for media storage.
 *
 * Nothing here references R2 credentials: those live only as Cloudflare Worker secrets.
 */
interface R2ConfigRepository {
    val config: Flow<R2Config?>
    val status: Flow<R2SetupStatus>

    suspend fun current(): R2Config?

    /** Validates, probes `/health` and persists the endpoint. */
    suspend fun save(workerUrl: String, providerName: String): AppResult<R2Config>

    suspend fun clear()

    /** Multi-step diagnostic used by "Test connection" on the media card. */
    suspend fun testConnection(
        workerUrl: String? = null,
        providerName: String? = null,
        onStep: (ConnectionReport) -> Unit = {},
    ): AppResult<ConnectionReport>
}
