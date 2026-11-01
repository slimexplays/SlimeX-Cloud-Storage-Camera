package com.slimex.cloudcamera.cloud.r2

import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.result.FailureKind
import com.slimex.cloudcamera.core.util.Formatters
import com.slimex.cloudcamera.domain.model.ConnectionReport
import com.slimex.cloudcamera.domain.model.ConnectionStep
import com.slimex.cloudcamera.domain.model.ConnectionStepStatus
import com.slimex.cloudcamera.domain.model.R2Config
import com.slimex.cloudcamera.domain.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ordered diagnostic for the media backend.
 *
 *   1. the Worker endpoint answers the SlimeX API
 *   2. the Worker validates the signed-in Supabase user (skipped when signed out)
 *   3. R2 answers through the Worker (bucket readiness, checked with the Worker's own credentials)
 *   4. the bucket can be listed for the caller's prefix (verified usage)
 *
 * Step 3 is the only place that proves the R2 secret is configured correctly, and it never
 * involves the app holding that secret.
 */
@Singleton
class R2ConnectionTester @Inject constructor(
    private val worker: R2WorkerClient,
    private val authRepository: AuthRepository,
) {

    suspend fun test(
        config: R2Config,
        onStep: (ConnectionReport) -> Unit = {},
    ): AppResult<ConnectionReport> {
        val started = System.currentTimeMillis()
        val steps = mutableListOf(
            ConnectionStep(title = STEP_ENDPOINT),
            ConnectionStep(title = STEP_SESSION, required = false),
            ConnectionStep(title = STEP_BUCKET),
            ConnectionStep(title = STEP_USAGE, required = false),
        )

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

        // 1. Endpoint
        steps[0] = steps[0].copy(status = ConnectionStepStatus.RUNNING)
        emit()
        val info = when (val health = worker.health(config)) {
            is AppResult.Failure -> {
                steps[0] = steps[0].copy(
                    status = ConnectionStepStatus.FAILED,
                    detail = health.error.message,
                )
                emit()
                return AppResult.Failure(health.error)
            }

            is AppResult.Success -> health.data
        }
        steps[0] = steps[0].copy(
            status = ConnectionStepStatus.PASSED,
            detail = "SlimeX Worker API responded in ${info.latencyMs} ms" +
                (info.version?.let { " · v$it" } ?: ""),
        )
        emit()

        val session = when (val result = authRepository.requireSession()) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> null
        }

        // 2. Supabase JWT accepted by the Worker
        if (session == null) {
            steps[1] = steps[1].copy(
                status = ConnectionStepStatus.SKIPPED,
                detail = "Sign in from Account to test authenticated storage access.",
            )
            emit()
        } else {
            steps[1] = steps[1].copy(status = ConnectionStepStatus.RUNNING)
            emit()
            when (val result = worker.verifySession(config, session.accessToken)) {
                is AppResult.Success -> steps[1] = steps[1].copy(
                    status = ConnectionStepStatus.PASSED,
                    detail = "The Worker verified your Supabase session.",
                )

                is AppResult.Failure -> {
                    steps[1] = steps[1].copy(status = ConnectionStepStatus.FAILED, detail = result.error.message)
                    emit()
                    return AppResult.Failure(result.error)
                }
            }
            emit()
        }

        // 3. R2 reachable with the Worker's server-side credentials
        steps[2] = steps[2].copy(status = ConnectionStepStatus.RUNNING)
        emit()
        steps[2] = when {
            info.bucketReady == true -> steps[2].copy(
                status = ConnectionStepStatus.PASSED,
                detail = "Bucket is reachable from the Worker." + (info.provider?.let { " · $it" } ?: ""),
            )

            info.bucketReady == false -> {
                val failure = AppFailure(
                    message = "The Worker reached R2 but the bucket check failed. " +
                        "Check R2_BUCKET, the R2 keys and the bucket permissions in Cloudflare.",
                    kind = FailureKind.STORAGE,
                )
                steps[2] = steps[2].copy(status = ConnectionStepStatus.FAILED, detail = failure.message)
                emit()
                return AppResult.Failure(failure)
            }

            else -> steps[2].copy(
                status = ConnectionStepStatus.WARNING,
                detail = "The Worker did not report bucket state. Redeploy a current Worker build.",
            )
        }
        emit()

        // 4. Verified usage (list permission + ownership scoping)
        if (session == null) {
            steps[3] = steps[3].copy(
                status = ConnectionStepStatus.SKIPPED,
                detail = "Sign in to verify that your own objects are listable.",
            )
            emit()
        } else {
            steps[3] = steps[3].copy(status = ConnectionStepStatus.RUNNING)
            emit()
            steps[3] = when (val usage = worker.usage(config, session.accessToken)) {
                is AppResult.Success -> steps[3].copy(
                    status = ConnectionStepStatus.PASSED,
                    detail = "${usage.data.objectCount} object(s) · ${Formatters.bytes(usage.data.totalBytes)} in your prefix",
                )

                is AppResult.Failure -> steps[3].copy(
                    status = ConnectionStepStatus.WARNING,
                    detail = usage.error.message,
                )
            }
            emit()
        }

        val report = ConnectionReport(
            steps = steps.toList(),
            success = steps.none { it.required && it.status == ConnectionStepStatus.FAILED },
            latencyMs = System.currentTimeMillis() - started,
        )
        return AppResult.Success(report)
    }

    private companion object {
        const val STEP_ENDPOINT = "Worker endpoint reachable"
        const val STEP_SESSION = "Supabase session accepted"
        const val STEP_BUCKET = "Cloudflare R2 reachable"
        const val STEP_USAGE = "Storage access verified"
    }
}
