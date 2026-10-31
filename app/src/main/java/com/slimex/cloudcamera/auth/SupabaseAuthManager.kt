package com.slimex.cloudcamera.auth

import com.slimex.cloudcamera.cloud.AuthCall
import com.slimex.cloudcamera.cloud.SupabaseAuthApi
import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.result.FailureKind
import com.slimex.cloudcamera.domain.model.AuthSession
import com.slimex.cloudcamera.domain.model.AuthStatus
import com.slimex.cloudcamera.domain.model.SignUpOutcome
import com.slimex.cloudcamera.domain.repository.AuthRepository
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import com.slimex.cloudcamera.security.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class StoredSession(
    val userId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
)

/**
 * Owns the auth session: restore on start, refresh before use, clear on sign-out.
 *
 * A single [Mutex] serialises refreshes so parallel uploads never race the same refresh token
 * (Supabase rotates refresh tokens — a double spend invalidates the session).
 */
@Singleton
class SupabaseAuthManager @Inject constructor(
    private val api: SupabaseAuthApi,
    private val configRepository: CloudConfigRepository,
    private val secureStore: SecureStore,
    private val json: Json,
) : AuthRepository {

    private val _status = MutableStateFlow<AuthStatus>(AuthStatus.Loading)
    override val status: StateFlow<AuthStatus> = _status.asStateFlow()

    private val _session = MutableStateFlow<AuthSession?>(null)
    override val session: StateFlow<AuthSession?> = _session.asStateFlow()

    private val refreshMutex = Mutex()

    override suspend fun signIn(email: String, password: String): AppResult<AuthSession> {
        val validation = validateCredentials(email, password)
        if (validation != null) return AppResult.Failure(validation)

        val config = configRepository.current()
            ?: return AppResult.Failure(AppFailure.notConfigured())

        return when (val result = api.signIn(config, email.trim(), password)) {
            is AuthCall.SignedIn -> {
                persist(result.session)
                AppResult.Success(result.session)
            }

            is AuthCall.ConfirmationRequired -> AppResult.Failure(
                AppFailure(
                    message = "Confirm your email address first, then sign in.",
                    kind = FailureKind.AUTH,
                ),
            )

            is AuthCall.Failure -> AppResult.Failure(result.failure)
        }
    }

    override suspend fun signUp(email: String, password: String): AppResult<SignUpOutcome> {
        val validation = validateCredentials(email, password)
        if (validation != null) return AppResult.Failure(validation)

        val config = configRepository.current()
            ?: return AppResult.Failure(AppFailure.notConfigured())

        return when (val result = api.signUp(config, email.trim(), password)) {
            is AuthCall.SignedIn -> {
                persist(result.session)
                AppResult.Success(SignUpOutcome.Authenticated(result.session))
            }

            is AuthCall.ConfirmationRequired ->
                AppResult.Success(SignUpOutcome.ConfirmationRequired(result.email))

            is AuthCall.Failure -> AppResult.Failure(result.failure)
        }
    }

    override suspend fun signOut() {
        val current = _session.value
        val config = configRepository.current()
        if (current != null && config != null) {
            runCatching { api.signOut(config, current.accessToken) }
        }
        clearSession()
    }

    override suspend fun restore() {
        if (_session.value != null) return
        val stored = readStored()
        if (stored == null) {
            _status.value = AuthStatus.SignedOut
            return
        }
        _session.value = stored.toSession()
        _status.value = AuthStatus.SignedIn(email = stored.email, userId = stored.userId)
    }

    override suspend fun requireSession(): AppResult<AuthSession> = refreshMutex.withLock {
        val stored = _session.value ?: readStored()?.toSession()

        if (stored == null) {
            _status.value = AuthStatus.SignedOut
            return@withLock AppResult.Failure(
                AppFailure(message = "Sign in to your Supabase project to continue.", kind = FailureKind.AUTH),
            )
        }

        if (!stored.needsRefresh) {
            _session.value = stored
            return@withLock AppResult.Success(stored)
        }

        val config = configRepository.current()
            ?: return@withLock AppResult.Failure(AppFailure.notConfigured())

        when (val result = api.refresh(config, stored.refreshToken)) {
            is AuthCall.SignedIn -> {
                // A refresh response may omit the profile fields; keep the known email.
                val refreshed = result.session.copy(
                    email = result.session.email.ifBlank { stored.email },
                )
                persist(refreshed)
                AppResult.Success(refreshed)
            }

            is AuthCall.ConfirmationRequired -> AppResult.Failure(
                AppFailure("Your session expired. Sign in again.", FailureKind.AUTH),
            )

            is AuthCall.Failure -> {
                // A rejected refresh token means the session is dead: clear it so the UI asks
                // for a fresh sign-in instead of retrying forever.
                if (result.failure.kind == FailureKind.AUTH) clearSession()
                AppResult.Failure(result.failure)
            }
        }
    }

    override suspend fun clearSession() {
        _session.value = null
        secureStore.remove(KEY_SESSION)
        _status.value = AuthStatus.SignedOut
    }

    // ---------------------------------------------------------------- storage

    private fun persist(session: AuthSession) {
        _session.value = session
        _status.value = AuthStatus.SignedIn(email = session.email, userId = session.userId)
        val payload = StoredSession(
            userId = session.userId,
            email = session.email,
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            expiresAtMillis = session.expiresAtMillis,
        )
        secureStore.putString(KEY_SESSION, json.encodeToString(StoredSession.serializer(), payload))
    }

    private fun readStored(): StoredSession? {
        val raw = secureStore.getString(KEY_SESSION)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { json.decodeFromString(StoredSession.serializer(), raw) }.getOrNull()
    }

    private fun StoredSession.toSession(): AuthSession = AuthSession(
        userId = userId,
        email = email,
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtMillis = expiresAtMillis,
    )

    private fun validateCredentials(email: String, password: String): AppFailure? {
        val trimmed = email.trim()
        if (trimmed.isEmpty() || !trimmed.contains('@') || trimmed.startsWith('@') || trimmed.endsWith('@')) {
            return AppFailure("Enter a valid email address.", FailureKind.INVALID_INPUT)
        }
        if (password.length < 6) {
            return AppFailure("Passwords must be at least 6 characters.", FailureKind.INVALID_INPUT)
        }
        return null
    }

    private companion object {
        const val KEY_SESSION = "auth_session_json"
    }
}
