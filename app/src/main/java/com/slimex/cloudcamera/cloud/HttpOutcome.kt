package com.slimex.cloudcamera.cloud

/** Low level HTTP result. Never contains the API key. */
data class HttpOutcome(
    val code: Int,
    val body: String? = null,
    val transportError: String? = null,
    val latencyMs: Long = 0,
) {
    val isSuccessful: Boolean get() = transportError == null && code in 200..299
    val isUnauthorized: Boolean get() = code == 401 || code == 403
    val isNotFound: Boolean get() = code == 404
}
