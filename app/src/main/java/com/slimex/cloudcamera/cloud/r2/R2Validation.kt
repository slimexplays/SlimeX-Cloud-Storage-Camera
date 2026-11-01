package com.slimex.cloudcamera.cloud.r2

import com.slimex.cloudcamera.core.constants.AppConstants
import java.net.URI
import java.util.Locale

/**
 * Validation for the Cloudflare Worker endpoint entered in Settings.
 *
 * Pure Kotlin (no Android types) so it can be unit tested on the JVM. It is deliberately strict:
 * the app only ever talks HTTPS, never accepts credentials embedded in the URL and never points at
 * a Supabase project by mistake (a very common copy/paste error).
 */
object R2Validation {

    sealed interface Outcome {
        data class Valid(val normalizedUrl: String, val providerName: String) : Outcome
        data class Invalid(val message: String) : Outcome
    }

    private const val MAX_URL_LENGTH = 300
    private const val MAX_PROVIDER_LENGTH = 48

    fun validate(workerUrl: String, providerName: String): Outcome {
        val rawUrl = workerUrl.trim()
        if (rawUrl.isEmpty()) {
            return Outcome.Invalid("Enter the Cloudflare Worker endpoint (for example https://slimex-media.example.workers.dev).")
        }
        if (rawUrl.length > MAX_URL_LENGTH) {
            return Outcome.Invalid("That endpoint is too long to be a Worker URL.")
        }

        val withScheme = when {
            rawUrl.startsWith("https://", ignoreCase = true) -> rawUrl
            rawUrl.startsWith("http://", ignoreCase = true) -> rawUrl
            else -> "https://$rawUrl"
        }

        val uri = runCatching { URI(withScheme) }.getOrNull()
            ?: return Outcome.Invalid("That is not a valid URL.")

        val scheme = uri.scheme?.lowercase(Locale.US) ?: return Outcome.Invalid("That is not a valid URL.")
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        if (host.isBlank()) return Outcome.Invalid("The URL needs a host name, for example slimex-media.example.workers.dev.")

        val isLocalDev = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"
        if (scheme != "https" && !(scheme == "http" && isLocalDev)) {
            return Outcome.Invalid("Use an https:// endpoint. Plain http is only allowed for a local Worker during development.")
        }
        if (!uri.userInfo.isNullOrBlank()) {
            return Outcome.Invalid("Remove the username and password from the URL — the Worker never needs them.")
        }
        if (uri.query != null || uri.fragment != null) {
            return Outcome.Invalid("Use only the base endpoint, without a query string or #fragment.")
        }
        if (host.endsWith(".supabase.co")) {
            return Outcome.Invalid("This is the Supabase project URL. Paste the Cloudflare Worker endpoint here instead.")
        }
        if (host == "workers.dev" || host.endsWith(".r2.cloudflarestorage.com")) {
            return Outcome.Invalid("That is not your Worker URL. Copy the workers.dev (or custom domain) address of your deployed Worker.")
        }
        if (!host.contains('.') && !isLocalDev) {
            return Outcome.Invalid("That host name looks incomplete. Copy the full Worker address.")
        }

        val path = uri.path.orEmpty().trimEnd('/')
        val normalized = buildString {
            append(scheme).append("://").append(host)
            uri.port.takeIf { it > 0 && it != 443 && it != 80 }?.let { append(':').append(it) }
            if (path.isNotEmpty()) append(path)
        }

        val name = providerName.trim().ifBlank { AppConstants.DEFAULT_STORAGE_PROVIDER }
        if (name.length > MAX_PROVIDER_LENGTH) {
            return Outcome.Invalid("Keep the provider name under $MAX_PROVIDER_LENGTH characters.")
        }

        return Outcome.Valid(normalizedUrl = normalized, providerName = name)
    }
}
