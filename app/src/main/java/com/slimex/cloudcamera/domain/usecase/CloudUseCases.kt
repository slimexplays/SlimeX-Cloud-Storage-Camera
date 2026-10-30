package com.slimex.cloudcamera.domain.usecase

import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.CloudConfig
import com.slimex.cloudcamera.domain.model.ConnectionReport
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import javax.inject.Inject

/** Validates + probes + persists Supabase credentials. */
class SaveSupabaseConfigUseCase @Inject constructor(
    private val repository: CloudConfigRepository,
) {
    suspend operator fun invoke(projectUrl: String, publishableKey: String): AppResult<CloudConfig> =
        repository.save(projectUrl, publishableKey)
}

/** Runs the connection diagnostics used by the setup screen and Home status card. */
class TestSupabaseConnectionUseCase @Inject constructor(
    private val repository: CloudConfigRepository,
) {
    suspend operator fun invoke(
        projectUrl: String,
        publishableKey: String,
        onStep: (ConnectionReport) -> Unit = {},
    ): AppResult<ConnectionReport> = repository.testConnection(projectUrl, publishableKey, onStep)
}
