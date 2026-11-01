package com.slimex.cloudcamera.cloud

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * Client-side validation for the two fields the user types in Settings.
 * Keeps bad input (and dangerous keys) away from the network layer.
 */
object SupabaseValidation {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Result(val normalizedUrl: String, val normalizedKey: String)

    sealed interface Validation {
        data class Valid(val value: Result) : Validation
        data class Invalid(val message: String) : Validation
    }

    fun validate(projectUrl: String, publishableKey: String): Validation {
        val urlResult = normalizeUrl(projectUrl)
        if (urlResult is Validation.Invalid) return urlResult
        val url = (urlResult as Validation.Valid).value.normalizedUrl

        val key = publishableKey.trim()
        if (key.isEmpty()) {
            return Validation.Invalid("Enter your Supabase publishable key (Settings → API).")
        }
        if (key.length < 20) {
            return Validation.Invalid("That key looks too short to be a Supabase publishable key.")
        }
        if (key.startsWith("sb_secret_", ignoreCase = true)) {
            return Validation.Invalid(
                "This is a secret key. Never use secret or service_role keys in the app — " +
                    "paste the publishable (anon) key instead.",
            )
        }
        if (key.lowercase(Locale.US).contains("service_role")) {
            return Validation.Invalid(
                "Service role keys bypass Row Level Security and must never be used in a mobile app. " +
                    "Use the publishable / anon key.",
            )
        }
        val jwtRole = jwtRole(key)
        if (jwtRole == "service_role") {
            return Validation.Invalid(
                "This key belongs to the service_role. Paste the public anon / publishable key instead.",
            )
        }
        return Validation.Valid(Result(url, key))
    }

    private fun normalizeUrl(raw: String): Validation {
        var value = raw.trim()
        if (value.isEmpty()) {
            return Validation.Invalid("Enter your Supabase project URL, e.g. https://abcdefgh.supabase.co")
        }
        if (value.contains(" ")) {
            return Validation.Invalid("The project URL must not contain spaces.")
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://$value"
        }
        while (value.endsWith("/")) value = value.dropLast(1)
        if (value.contains("/dashboard") || value.contains("/project/")) {
            return Validation.Invalid("Use the project URL from Settings → API, not the dashboard link.")
        }
        val authority = value.removePrefix("https://").removePrefix("http://").substringBefore("/")
        if (!authority.contains(".") || authority.length < 8) {
            return Validation.Invalid("That doesn't look like a valid project URL (…supabase.co).")
        }
        if (!value.startsWith("https://")) {
            return Validation.Invalid("Supabase projects must be reached over https.")
        }
        return Validation.Valid(Result(normalizedUrl = value, normalizedKey = ""))
    }

    /** Best-effort, non-verifying peek at a JWT payload to catch service_role keys. */
    private fun jwtRole(token: String): String? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        return runCatching {
            val payload = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val element = json.parseToJsonElement(String(payload, Charsets.UTF_8)).jsonObject
            element["role"]?.jsonPrimitive?.content
        }.getOrNull()
    }
}
