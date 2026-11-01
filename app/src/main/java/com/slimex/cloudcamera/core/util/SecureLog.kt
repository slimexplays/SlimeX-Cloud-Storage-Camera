package com.slimex.cloudcamera.core.util

import android.util.Log
import com.slimex.cloudcamera.BuildConfig

/**
 * The only logging entry point of the app.
 *
 * Release builds emit **nothing** — not even warnings — so a stray message can never leak a
 * password, an API key, an access token, an encryption key, a bucket path or media content into
 * logcat, a bug report or a crash collector. Debug builds keep short, non-sensitive messages to
 * make local diagnosis possible.
 */
object SecureLog {

    /** True only for debug builds. */
    val enabled: Boolean
        get() = BuildConfig.DEBUG

    fun d(tag: String, message: String) {
        if (enabled) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (enabled) Log.i(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (!enabled) return
        if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (!enabled) return
        if (error != null) Log.e(tag, message, error) else Log.e(tag, message)
    }

    /** Replaces a secret with a fixed mask; use it when a value must be shown as present/absent. */
    fun mask(value: String?): String =
        if (value.isNullOrBlank()) "(none)" else "••••••"
}
