package com.slimex.cloudcamera.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.slimex.cloudcamera.core.constants.AppConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keystore backed credential storage (AES-256-GCM values, AES-256-SIV keys).
 *
 * If the platform Keystore rejects the operation (a handful of OEM ROMs do this on
 * older builds) we degrade to an in-memory store instead of crashing: the app still
 * works for the session, but credentials are never written to disk in plaintext.
 */
@Singleton
class EncryptedSecureStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SecureStore {

    private val memoryFallback = mutableMapOf<String, String>()

    private val prefs: SharedPreferences? by lazy { createPrefs() }

    override var isEncryptionAvailable: Boolean = true
        private set

    override var storageWarning: String? = null
        private set

    private fun createPrefs(): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            AppConstants.SECURE_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        Log.w(TAG, "Encrypted storage unavailable, falling back to session-only storage", t)
        isEncryptionAvailable = false
        storageWarning =
            "Secure storage is unavailable on this device, so the Supabase configuration " +
                "will only last until the app is closed."
        null
    }

    override fun getString(key: String): String? =
        prefs?.let { runCatching { it.getString(key, null) }.getOrNull() } ?: memoryFallback[key]

    override fun putString(key: String, value: String?) {
        if (value == null) {
            remove(key)
            return
        }
        val store = prefs
        if (store == null) {
            memoryFallback[key] = value
            return
        }
        val ok = runCatching { store.edit().putString(key, value).commit() }.getOrDefault(false)
        if (!ok) {
            isEncryptionAvailable = false
            storageWarning =
                "Secure storage rejected the write, so the configuration is kept in memory only."
            memoryFallback[key] = value
        }
    }

    override fun remove(key: String) {
        memoryFallback.remove(key)
        prefs?.let { runCatching { it.edit().remove(key).apply() } }
    }

    override fun clear() {
        memoryFallback.clear()
        prefs?.let { runCatching { it.edit().clear().apply() } }
    }

    private companion object {
        const val TAG = "EncryptedSecureStore"
    }
}
