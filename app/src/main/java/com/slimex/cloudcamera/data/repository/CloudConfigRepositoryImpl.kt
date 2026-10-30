package com.slimex.cloudcamera.data.repository

import com.slimex.cloudcamera.cloud.SupabaseConnectionTester
import com.slimex.cloudcamera.cloud.SupabaseValidation
import com.slimex.cloudcamera.core.constants.AppConstants
import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.CloudConfig
import com.slimex.cloudcamera.domain.model.CloudSetupStatus
import com.slimex.cloudcamera.domain.model.ConnectionReport
import com.slimex.cloudcamera.domain.model.ConnectionStepStatus
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import com.slimex.cloudcamera.security.SecureStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudConfigRepositoryImpl @Inject constructor(
    private val secureStore: SecureStore,
    private val tester: SupabaseConnectionTester,
) : CloudConfigRepository {

    private val cached = MutableStateFlow(readFromDisk())

    override val config: Flow<CloudConfig?> = cached

    override val status: Flow<CloudSetupStatus> = cached.map { value ->
        if (value == null) CloudSetupStatus.NotConfigured
        else CloudSetupStatus.Configured(host = value.host, savedAt = value.savedAt)
    }

    override suspend fun current(): CloudConfig? = cached.value ?: readFromDisk()?.also { cached.value = it }

    override suspend fun save(projectUrl: String, publishableKey: String): AppResult<CloudConfig> {
        val validation = SupabaseValidation.validate(projectUrl, publishableKey)
        if (validation is SupabaseValidation.Invalid) {
            return AppResult.Failure(AppFailure(validation.message, com.slimex.cloudcamera.core.result.FailureKind.INVALID_INPUT))
        }
        val value = validation as SupabaseValidation.Valid
        val candidate = CloudConfig(
            projectUrl = value.value.normalizedUrl,
            publishableKey = value.value.normalizedKey,
            bucket = readBucket(),
            table = readTable(),
            savedAt = System.currentTimeMillis(),
        )

        // Probe first: never persist credentials the project has already rejected.
        val report = tester.test(candidate).getOrNull()
        val authRejected = report?.steps?.any {
            it.title.contains("key", ignoreCase = true) && it.status == ConnectionStepStatus.FAILED
        } == true
        if (authRejected) {
            return AppResult.Failure(
                AppFailure.unauthorized(
                    "The project rejected that key, so nothing was saved. Double-check Settings → API.",
                ),
            )
        }

        persist(candidate)
        cached.value = candidate
        return AppResult.Success(candidate)
    }

    override suspend fun clear() {
        secureStore.remove(KEY_URL)
        secureStore.remove(KEY_API)
        secureStore.remove(KEY_SAVED_AT)
        cached.value = null
    }

    override suspend fun testConnection(
        projectUrl: String?,
        publishableKey: String?,
        onStep: (ConnectionReport) -> Unit,
    ): AppResult<ConnectionReport> {
        val existing = current()
        val url = projectUrl?.trim().takeUnless { it.isNullOrEmpty() } ?: existing?.projectUrl
        val key = publishableKey?.trim().takeUnless { it.isNullOrEmpty() } ?: existing?.publishableKey

        if (url.isNullOrEmpty() || key.isNullOrEmpty()) {
            return AppResult.Failure(
                AppFailure(
                    message = "Enter the project URL and publishable key first.",
                    kind = com.slimex.cloudcamera.core.result.FailureKind.NOT_CONFIGURED,
                ),
            )
        }
        val validation = SupabaseValidation.validate(url, key)
        if (validation is SupabaseValidation.Invalid) {
            return AppResult.Failure(
                AppFailure(validation.message, com.slimex.cloudcamera.core.result.FailureKind.INVALID_INPUT),
            )
        }
        val value = validation as SupabaseValidation.Valid
        val candidate = CloudConfig(
            projectUrl = value.value.normalizedUrl,
            publishableKey = value.value.normalizedKey,
            bucket = readBucket(),
            table = readTable(),
        )
        return tester.test(candidate, onStep)
    }

    // ---------------------------------------------------------------- storage

    private fun persist(config: CloudConfig) {
        secureStore.putString(KEY_URL, config.projectUrl)
        secureStore.putString(KEY_API, config.publishableKey)
        secureStore.putString(KEY_BUCKET, config.bucket)
        secureStore.putString(KEY_TABLE, config.table)
        secureStore.putString(KEY_SAVED_AT, config.savedAt.toString())
    }

    private fun readFromDisk(): CloudConfig? {
        val url = secureStore.getString(KEY_URL)?.takeIf { it.isNotBlank() } ?: return null
        val key = secureStore.getString(KEY_API)?.takeIf { it.isNotBlank() } ?: return null
        return CloudConfig(
            projectUrl = url,
            publishableKey = key,
            bucket = readBucket(),
            table = readTable(),
            savedAt = secureStore.getString(KEY_SAVED_AT)?.toLongOrNull() ?: 0L,
        )
    }

    private fun readBucket(): String =
        secureStore.getString(KEY_BUCKET)?.takeIf { it.isNotBlank() } ?: AppConstants.DEFAULT_BUCKET

    private fun readTable(): String =
        secureStore.getString(KEY_TABLE)?.takeIf { it.isNotBlank() } ?: AppConstants.MEDIA_TABLE

    private companion object {
        const val KEY_URL = "cloud_project_url"
        const val KEY_API = "cloud_publishable_key"
        const val KEY_BUCKET = "cloud_bucket"
        const val KEY_TABLE = "cloud_table"
        const val KEY_SAVED_AT = "cloud_saved_at"
    }
}
