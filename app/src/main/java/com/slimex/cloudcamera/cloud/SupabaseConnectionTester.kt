package com.slimex.cloudcamera.cloud

import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.CloudConfig
import com.slimex.cloudcamera.domain.model.ConnectionReport
import com.slimex.cloudcamera.domain.model.ConnectionStep
import com.slimex.cloudcamera.domain.model.ConnectionStepStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a short, ordered diagnostic against a candidate configuration and reports
 * each step so the setup screen can show exactly what is wrong.
 */
@Singleton
class SupabaseConnectionTester @Inject constructor(
    private val client: SupabaseRestClient,
) {

    private val titles = listOf(
        STEP_ENDPOINT,
        STEP_API_KEY,
        STEP_BUCKET,
        STEP_TABLE,
    )

    suspend fun test(
        config: CloudConfig,
        onStep: (ConnectionReport) -> Unit = {},
    ): AppResult<ConnectionReport> {
        val started = System.currentTimeMillis()
        val steps = titles.map { ConnectionStep(title = it, status = ConnectionStepStatus.PENDING) }
            .toMutableList()

        fun emit() {
            onStep(
                ConnectionReport(
                    steps = steps.toList(),
                    success = steps.none { it.required && it.status == ConnectionStepStatus.FAILED },
                    latencyMs = System.currentTimeMillis() - started,
                ),
            )
        }

        emit()

        // 1. Project endpoint reachable
        steps[0] = steps[0].copy(status = ConnectionStepStatus.RUNNING)
        emit()
        val health = client.projectHealth(config)
        if (health.transportError != null) {
            steps[0] = steps[0].copy(
                status = ConnectionStepStatus.FAILED,
                detail = "Cannot reach ${config.host}: ${health.transportError}",
            )
            emit()
            return AppResult.Failure(
                AppFailure.network(
                    "Could not reach ${config.host}. Check the project URL and your internet connection.",
                ),
            )
        }
        if (health.code == 404) {
            steps[0] = steps[0].copy(
                status = ConnectionStepStatus.FAILED,
                detail = "No Supabase project responded at ${config.host} (HTTP 404).",
            )
            emit()
            return AppResult.Failure(
                AppFailure.unknown("No Supabase project found at that URL. Copy the Project URL from Settings → API."),
            )
        }
        steps[0] = steps[0].copy(
            status = if (health.isSuccessful) ConnectionStepStatus.PASSED else ConnectionStepStatus.WARNING,
            detail = "${health.latencyMs} ms · HTTP ${health.code}",
        )
        emit()

        // 2. Publishable key accepted by PostgREST
        steps[1] = steps[1].copy(status = ConnectionStepStatus.RUNNING)
        emit()
        val rest = client.restRoot(config)
        if (rest.transportError != null) {
            steps[1] = steps[1].copy(
                status = ConnectionStepStatus.FAILED,
                detail = "Network error: ${rest.transportError}",
            )
            emit()
            return AppResult.Failure(AppFailure.network("Could not validate the key: ${rest.transportError}"))
        }
        if (rest.isUnauthorized) {
            steps[1] = steps[1].copy(
                status = ConnectionStepStatus.FAILED,
                detail = "The project rejected this key (HTTP ${rest.code}).",
            )
            emit()
            return AppResult.Failure(
                AppFailure.unauthorized(
                    "The project rejected that key. Paste the publishable (anon) key from Settings → API.",
                ),
            )
        }
        steps[1] = steps[1].copy(
            status = if (rest.isSuccessful) ConnectionStepStatus.PASSED else ConnectionStepStatus.WARNING,
            detail = "Key accepted · HTTP ${rest.code}",
        )
        emit()

        // 3. Storage bucket (warning only — the SQL may not have been run yet)
        steps[2] = steps[2].copy(status = ConnectionStepStatus.RUNNING)
        emit()
        val bucket = client.probeBucket(config)
        steps[2] = bucketStep(bucket, config)
        emit()

        // 4. media_files table (warning only, same reasoning)
        steps[3] = steps[3].copy(status = ConnectionStepStatus.RUNNING)
        emit()
        val table = client.probeTable(config)
        steps[3] = when {
            table.transportError != null -> steps[3].copy(
                status = ConnectionStepStatus.WARNING,
                detail = "Could not check the media table: ${table.transportError}",
                required = false,
            )
            table.isSuccessful -> steps[3].copy(
                status = ConnectionStepStatus.PASSED,
                detail = "Table '${config.table}' is readable.",
                required = false,
            )
            table.code == 404 -> steps[3].copy(
                status = ConnectionStepStatus.WARNING,
                detail = "Table '${config.table}' missing — run supabase/schema.sql.",
                required = false,
            )
            table.isUnauthorized -> steps[3].copy(
                status = ConnectionStepStatus.WARNING,
                detail = "Table exists but requires a signed-in user (RLS) — sign in from Account.",
                required = false,
            )
            else -> steps[3].copy(
                status = ConnectionStepStatus.WARNING,
                detail = "HTTP ${table.code}",
                required = false,
            )
        }
        emit()

        val report = ConnectionReport(
            steps = steps.toList(),
            success = steps.none { it.required && it.status == ConnectionStepStatus.FAILED },
            latencyMs = System.currentTimeMillis() - started,
        )
        return AppResult.Success(report)
    }

    private fun bucketStep(outcome: HttpOutcome, config: CloudConfig): ConnectionStep {
        val base = ConnectionStep(title = STEP_BUCKET, required = false)
        return when {
            outcome.transportError != null -> base.copy(
                status = ConnectionStepStatus.WARNING,
                detail = "Could not check the bucket: ${outcome.transportError}",
            )
            outcome.isSuccessful -> base.copy(
                status = ConnectionStepStatus.PASSED,
                detail = "Bucket '${config.bucket}' is reachable.",
            )
            outcome.code == 404 || outcome.code == 400 -> base.copy(
                status = ConnectionStepStatus.WARNING,
                detail = "Bucket '${config.bucket}' missing — run supabase/storage.sql.",
            )
            outcome.isUnauthorized -> base.copy(
                status = ConnectionStepStatus.WARNING,
                detail = "Bucket is private and needs a signed-in user — expected. " +
                    "Sign in from Account, and run supabase/storage.sql if it is missing.",
            )
            else -> base.copy(
                status = ConnectionStepStatus.WARNING,
                detail = "HTTP ${outcome.code}",
            )
        }
    }

    private companion object {
        const val STEP_ENDPOINT = "Project endpoint reachable"
        const val STEP_API_KEY = "Publishable key accepted"
        const val STEP_BUCKET = "Storage bucket available"
        const val STEP_TABLE = "Media table available"
    }
}
