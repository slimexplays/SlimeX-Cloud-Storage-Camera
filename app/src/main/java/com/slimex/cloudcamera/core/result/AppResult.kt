package com.slimex.cloudcamera.core.result

/**
 * A tiny, explicit result type used across repository boundaries so that no
 * layer has to throw for expected failures (network down, cloud not configured, ...).
 */
sealed interface AppResult<out T> {

    data class Success<T>(val data: T) : AppResult<T>

    data class Failure(val error: AppFailure) : AppResult<Nothing>

    val isSuccess: Boolean
        get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.data

    fun errorOrNull(): AppFailure? = (this as? Failure)?.error
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

inline fun <T> AppResult<T>.onFailure(action: (AppFailure) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}

/**
 * Human readable failure with a coarse classification, so the UI can decide
 * between "retry", "configure Supabase" or "check your connection".
 */
data class AppFailure(
    val message: String,
    val kind: FailureKind = FailureKind.UNKNOWN,
    val cause: Throwable? = null,
) {
    companion object {
        fun notConfigured(): AppFailure = AppFailure(
            message = "Supabase is not configured yet.",
            kind = FailureKind.NOT_CONFIGURED,
        )

        fun network(message: String = "Network unavailable. Check your connection.", cause: Throwable? = null) =
            AppFailure(message = message, kind = FailureKind.NETWORK, cause = cause)

        fun unauthorized(message: String) = AppFailure(message = message, kind = FailureKind.AUTH)

        fun storage(message: String, cause: Throwable? = null) =
            AppFailure(message = message, kind = FailureKind.STORAGE, cause = cause)

        fun unknown(message: String, cause: Throwable? = null) =
            AppFailure(message = message, kind = FailureKind.UNKNOWN, cause = cause)
    }
}

enum class FailureKind {
    NOT_CONFIGURED,
    NETWORK,
    AUTH,
    STORAGE,
    PERMISSION,
    INVALID_INPUT,
    UNKNOWN,
}

/** Maps a [Throwable] into an [AppFailure] without leaking stack traces to the UI. */
fun Throwable.toAppFailure(context: String? = null): AppFailure {
    val prefix = context?.let { "$it: " } ?: ""
    val message = this.message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
    return AppFailure(message = prefix + message, kind = FailureKind.UNKNOWN, cause = this)
}

inline fun <T> runCatchingResult(context: String, block: () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (t: Throwable) {
        AppResult.Failure(t.toAppFailure(context))
    }
