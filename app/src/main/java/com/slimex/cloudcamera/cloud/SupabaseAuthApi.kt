package com.slimex.cloudcamera.cloud

import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.domain.model.AuthSession
import com.slimex.cloudcamera.domain.model.CloudConfig
import com.slimex.cloudcamera.core.util.DispatchersProvider
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a GoTrue (Supabase Auth) call. */
sealed interface AuthCall {
    data class SignedIn(val session: AuthSession) : AuthCall
    data class ConfirmationRequired(val email: String) : AuthCall
    data class Failure(val failure: AppFailure) : AuthCall
}

/**
 * Supabase Auth (GoTrue) over plain REST.
 *
 * Only the runtime-configured publishable key is sent as `apikey`; the user's access token
 * is sent as a Bearer token. Nothing is cached here — [SessionManager] owns persistence.
 */
@Singleton
class SupabaseAuthApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val dispatchers: DispatchersProvider,
) {

    suspend fun signUp(config: CloudConfig, email: String, password: String): AuthCall =
        postAuth(
            config = config,
            path = listOf("signup"),
            payload = buildJsonObject {
                put("email", JsonPrimitive(email))
                put("password", JsonPrimitive(password))
            }.toString(),
            email = email,
        )

    suspend fun signIn(config: CloudConfig, email: String, password: String): AuthCall =
        postAuth(
            config = config,
            path = listOf("token"),
            query = mapOf("grant_type" to "password"),
            payload = buildJsonObject {
                put("email", JsonPrimitive(email))
                put("password", JsonPrimitive(password))
            }.toString(),
            email = email,
        )

    suspend fun refresh(config: CloudConfig, refreshToken: String): AuthCall =
        postAuth(
            config = config,
            path = listOf("token"),
            query = mapOf("grant_type" to "refresh_token"),
            payload = buildJsonObject {
                put("refresh_token", JsonPrimitive(refreshToken))
            }.toString(),
            email = "",
        )

    /** Best-effort server-side revoke. The local session is cleared either way. */
    suspend fun signOut(config: CloudConfig, accessToken: String) {
        withContext(dispatchers.io) {
            val url = url(config, listOf("logout")) ?: return@withContext
            val request = Request.Builder()
                .url(url)
                .post("{}".toRequestBody(JSON))
                .header("apikey", config.publishableKey)
                .header("Authorization", "Bearer $accessToken")
                .build()
            runCatching { client.newCall(request).execute().close() }
        }
    }

    // ---------------------------------------------------------------- plumbing

    private suspend fun postAuth(
        config: CloudConfig,
        path: List<String>,
        payload: String,
        email: String,
        query: Map<String, String> = emptyMap(),
    ): AuthCall = withContext(dispatchers.io) {
        val base = url(config, path) ?: return@withContext AuthCall.Failure(
            AppFailure.unknown("The stored Supabase URL is not valid. Re-save it in Settings."),
        )
        val url = if (query.isEmpty()) {
            base
        } else {
            base.newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON))
            .header("apikey", config.publishableKey)
            .header("Authorization", "Bearer ${config.publishableKey}")
            .header("Accept", "application/json")
            .header("X-Client-Info", "slimex-cloud-camera-android")
            .build()

        val outcome = try {
            client.newCall(request).execute().use { response ->
                HttpOutcome(
                    code = response.code,
                    body = runCatching { response.body?.string() }.getOrNull(),
                )
            }
        } catch (io: IOException) {
            HttpOutcome(code = 0, transportError = io.message ?: "Network error")
        } catch (t: Throwable) {
            HttpOutcome(code = 0, transportError = t.message ?: "Unexpected networking error")
        }

        when {
            outcome.transportError != null -> AuthCall.Failure(
                AppFailure.network("Could not reach Supabase Auth: ${outcome.transportError}"),
            )

            outcome.code in 200..299 -> parseSuccess(outcome.body, email)

            else -> AuthCall.Failure(authFailure(outcome, path))
        }
    }

    private fun parseSuccess(body: String?, email: String): AuthCall {
        if (body.isNullOrBlank()) return AuthCall.Failure(AppFailure.unknown("Supabase returned an empty response."))
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return AuthCall.Failure(AppFailure.unknown("Could not read the Supabase Auth response."))

        val accessToken = root.string("access_token")
        val refreshToken = root.string("refresh_token")
        val userId = root["user"]?.let { userElement ->
            runCatching { userElement.jsonObject }.getOrNull()?.string("id")
        } ?: root.string("id")

        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank() || userId.isNullOrBlank()) {
            // Email confirmation is enabled on the project: the account exists but has no session.
            val pendingEmail = root.string("email")
                ?: root["user"]?.let { runCatching { it.jsonObject }.getOrNull()?.string("email") }
                ?: email
            return if (pendingEmail.isNotBlank()) {
                AuthCall.ConfirmationRequired(pendingEmail)
            } else {
                AuthCall.Failure(
                    AppFailure.unknown("Supabase did not return a session. Check Auth settings in your project."),
                )
            }
        }

        val expiresIn = root["expires_in"]?.let { runCatching { it.jsonPrimitive.content.toLong() }.getOrNull() }
            ?: 3600L
        val resolvedEmail = root["user"]?.let { runCatching { it.jsonObject }.getOrNull()?.string("email") }
            ?: email

        return AuthCall.SignedIn(
            AuthSession(
                userId = userId,
                email = resolvedEmail,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtMillis = System.currentTimeMillis() + expiresIn * 1000L,
            ),
        )
    }

    private fun authFailure(outcome: HttpOutcome, path: List<String>): AppFailure {
        val message = CloudFailures.serverMessage(outcome, "sign in") ?: "Authentication failed."
        val friendly = when {
            message.contains("Invalid login credentials", ignoreCase = true) -> "Incorrect email or password."
            message.contains("Email not confirmed", ignoreCase = true) ->
                "Confirm your email address first, then sign in."
            message.contains("already registered", ignoreCase = true) ||
                message.contains("already been registered", ignoreCase = true) ->
                "That email already has an account. Try signing in instead."
            message.contains("Password should be", ignoreCase = true) -> message
            message.contains("rate limit", ignoreCase = true) ->
                "Too many attempts. Wait a moment and try again."
            outcome.code == 422 && message.isBlank() -> "Check the email address and password."
            else -> message
        }
        val kind = if (outcome.code == 400 || outcome.code == 422) {
            com.slimex.cloudcamera.core.result.FailureKind.INVALID_INPUT
        } else {
            com.slimex.cloudcamera.core.result.FailureKind.AUTH
        }
        return AppFailure(message = friendly, kind = kind)
    }

    private fun url(config: CloudConfig, path: List<String>): okhttp3.HttpUrl? {
        val base = config.projectUrl.toHttpUrlOrNull() ?: return null
        val builder = base.newBuilder().addPathSegment("auth").addPathSegment("v1")
        path.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.let { runCatching { it.content }.getOrNull() }?.takeIf { it.isNotBlank() }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
