package com.slimex.cloudcamera.security

/**
 * Minimal key/value contract for secrets (Supabase URL + publishable key).
 * Implementations must never write plaintext credentials to disk.
 */
interface SecureStore {
    /** False when the Keystore backed store could not be created on this device. */
    val isEncryptionAvailable: Boolean
    val storageWarning: String?

    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun remove(key: String)
    fun clear()
}
