package com.slimex.cloudcamera.domain.model

/** A signed-in Supabase user. Tokens are held in Keystore-encrypted storage only. */
data class AuthSession(
    val userId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
) {
    /** Refresh a little early so a request never starts with a token that expires mid-flight. */
    val needsRefresh: Boolean
        get() = System.currentTimeMillis() >= expiresAtMillis - REFRESH_MARGIN_MILLIS

    private companion object {
        const val REFRESH_MARGIN_MILLIS = 60_000L
    }
}

sealed interface AuthStatus {
    data object Loading : AuthStatus
    data object SignedOut : AuthStatus
    data class SignedIn(val email: String, val userId: String) : AuthStatus
}

sealed interface SignUpOutcome {
    data class Authenticated(val session: AuthSession) : SignUpOutcome

    /** The Supabase project requires email confirmation before a session exists. */
    data class ConfirmationRequired(val email: String) : SignUpOutcome
}
