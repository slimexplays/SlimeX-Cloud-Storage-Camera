package com.slimex.cloudcamera.cloud

import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.AuthSession
import com.slimex.cloudcamera.domain.model.CloudConfig
import com.slimex.cloudcamera.domain.repository.AuthRepository
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Everything a cloud call needs: the project credentials plus a valid user session. */
data class CloudContext(val config: CloudConfig, val session: AuthSession) {
    val userId: String get() = session.userId
    val accessToken: String get() = session.accessToken
}

/**
 * Resolves the runtime configuration + a non-expired session in one step, so repositories
 * never have to juggle "not configured" and "signed out" separately.
 */
@Singleton
class CloudSessionProvider @Inject constructor(
    private val configRepository: CloudConfigRepository,
    private val authRepository: AuthRepository,
) {
    suspend fun require(): AppResult<CloudContext> {
        val config = configRepository.current()
            ?: return AppResult.Failure(AppFailure.notConfigured())
        val session = when (val result = authRepository.requireSession()) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return result
        }
        return AppResult.Success(CloudContext(config = config, session = session))
    }
}
