package com.slimex.cloudcamera.data.repository

import com.slimex.cloudcamera.cloud.r2.R2ConnectionTester
import com.slimex.cloudcamera.cloud.r2.R2Validation
import com.slimex.cloudcamera.core.constants.AppConstants
import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.result.FailureKind
import com.slimex.cloudcamera.domain.model.ConnectionReport
import com.slimex.cloudcamera.domain.model.R2Config
import com.slimex.cloudcamera.domain.model.R2SetupStatus
import com.slimex.cloudcamera.domain.repository.R2ConfigRepository
import com.slimex.cloudcamera.security.SecureStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the Worker endpoint in Keystore-backed secure preferences.
 *
 * The endpoint is not a secret, but it is kept with the same protection as the Supabase
 * credentials so a single store holds the whole cloud configuration, and so a device backup can
 * never pick it up in plaintext (`allowBackup` is off anyway).
 *
 * Note what is **not** here: no R2 account id, access key, secret key, bucket name or S3 endpoint.
 * Those stay inside the Cloudflare Worker as environment secrets.
 */
@Singleton
class R2ConfigRepositoryImpl @Inject constructor(
    private val secureStore: SecureStore,
    private val tester: R2ConnectionTester,
) : R2ConfigRepository {

    private val cached = MutableStateFlow(read())

    override val config: Flow<R2Config?> = cached

    override val status: Flow<R2SetupStatus> = cached.map { value ->
        if (value == null) R2SetupStatus.NotConfigured
        else R2SetupStatus.Configured(host = value.host, savedAt = value.savedAt)
    }

    override suspend fun current(): R2Config? = cached.value ?: read()?.also { cached.value = it }

    override suspend fun save(workerUrl: String, providerName: String): AppResult<R2Config> {
        val validation = R2Validation.validate(workerUrl, providerName)
        if (validation is R2Validation.Outcome.Invalid) {
            return AppResult.Failure(AppFailure(validation.message, FailureKind.INVALID_INPUT))
        }
        val valid = validation as R2Validation.Outcome.Valid
        val candidate = R2Config(
            workerUrl = valid.normalizedUrl,
            providerName = valid.providerName,
            savedAt = System.currentTimeMillis(),
        )

        // Never persist an endpoint that is not answering the SlimeX Worker API.
        val report = tester.test(candidate).getOrNull()
        val endpointFailed = report?.steps?.firstOrNull()
            ?.status == com.slimex.cloudcamera.domain.model.ConnectionStepStatus.FAILED
        if (report == null || endpointFailed) {
            return AppResult.Failure(
                AppFailure(
                    message = "The Worker did not answer, so nothing was saved. Check the URL and that " +
                        "the Worker is deployed.",
                    kind = FailureKind.NOT_CONFIGURED,
                ),
            )
        }

        persist(candidate)
        cached.value = candidate
        return AppResult.Success(candidate)
    }

    override suspend fun clear() {
        secureStore.remove(KEY_URL)
        secureStore.remove(KEY_PROVIDER)
        secureStore.remove(KEY_SAVED_AT)
        cached.value = null
    }

    override suspend fun testConnection(
        workerUrl: String?,
        providerName: String?,
        onStep: (ConnectionReport) -> Unit,
    ): AppResult<ConnectionReport> {
        val existing = current()
        val url = workerUrl?.trim().takeUnless { it.isNullOrEmpty() } ?: existing?.workerUrl
        val name = providerName?.trim().takeUnless { it.isNullOrEmpty() }
            ?: existing?.providerName
            ?: AppConstants.DEFAULT_STORAGE_PROVIDER

        if (url.isNullOrEmpty()) {
            return AppResult.Failure(
                AppFailure(
                    message = "Enter your Cloudflare Worker endpoint first.",
                    kind = FailureKind.NOT_CONFIGURED,
                ),
            )
        }
        val validation = R2Validation.validate(url, name)
        if (validation is R2Validation.Outcome.Invalid) {
            return AppResult.Failure(AppFailure(validation.message, FailureKind.INVALID_INPUT))
        }
        val valid = validation as R2Validation.Outcome.Valid
        return tester.test(
            R2Config(workerUrl = valid.normalizedUrl, providerName = valid.providerName),
            onStep,
        )
    }

    // ---------------------------------------------------------------- storage

    private fun read(): R2Config? {
        val url = secureStore.getString(KEY_URL)?.takeIf { it.isNotBlank() } ?: return null
        val name = secureStore.getString(KEY_PROVIDER)?.takeIf { it.isNotBlank() }
            ?: AppConstants.DEFAULT_STORAGE_PROVIDER
        val savedAt = secureStore.getString(KEY_SAVED_AT)?.toLongOrNull() ?: 0L
        return R2Config(workerUrl = url, providerName = name, savedAt = savedAt)
    }

    private fun persist(config: R2Config) {
        secureStore.putString(KEY_URL, config.workerUrl)
        secureStore.putString(KEY_PROVIDER, config.providerName)
        secureStore.putString(KEY_SAVED_AT, config.savedAt.toString())
    }

    private companion object {
        const val KEY_URL = "r2_worker_url"
        const val KEY_PROVIDER = "r2_provider_name"
        const val KEY_SAVED_AT = "r2_saved_at"
    }
}
