package com.slimex.cloudcamera.domain.repository

import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.AuthSession
import com.slimex.cloudcamera.domain.model.AuthStatus
import com.slimex.cloudcamera.domain.model.SignUpOutcome
import kotlinx.coroutines.flow.StateFlow

/**
 * Email/password authentication against the user's own Supabase project.
 *
 * The session (access + refresh token) is persisted in Keystore-encrypted storage only;
 * it is never written to preferences, logs or the database.
 */
interface AuthRepository {
    val status: StateFlow<AuthStatus>
    val session: StateFlow<AuthSession?>

    suspend fun signIn(email: String, password: String): AppResult<AuthSession>

    suspend fun signUp(email: String, password: String): AppResult<SignUpOutcome>

    suspend fun signOut()

    suspend fun restore()

    /** Returns a session with a valid access token, refreshing it when it is about to expire. */
    suspend fun requireSession(): AppResult<AuthSession>

    /** Drops the cached tokens (e.g. when the Supabase project is removed or changed). */
    suspend fun clearSession()
}
