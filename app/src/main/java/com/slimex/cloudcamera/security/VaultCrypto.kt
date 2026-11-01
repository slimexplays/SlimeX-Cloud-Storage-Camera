package com.slimex.cloudcamera.security

import android.util.Base64
import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** State of the device bound key vault, as shown on the Encryption & Recovery screen. */
data class VaultStatus(
    val available: Boolean,
    val hasMasterKey: Boolean,
    val detail: String? = null,
)

/**
 * Device bound protection for the local media key ring.
 *
 * A random 256-bit *vault master key* protects every key this app stores on the device. The
 * master key itself is never written to disk in the clear: it is wrapped with a non exportable
 * AES-256-GCM key that lives inside the Android Keystore, so reading the key ring requires the
 * hardware backed key of this exact device/install.
 *
 * The master key is only exported when the user explicitly creates a recovery code, and even
 * then it is sealed with a key derived from their passphrase (PBKDF2-HMAC-SHA256) — it is never
 * transmitted to Supabase or any other server.
 */
interface VaultCrypto {

    fun status(): VaultStatus

    /** Seals bytes with the vault master key. Output is `nonce || ciphertext || tag`. */
    fun seal(plaintext: ByteArray): AppResult<ByteArray>

    /** Reverses [seal]. Fails when the GCM tag does not verify (tampered or wrong key). */
    fun open(sealed: ByteArray): AppResult<ByteArray>

    /** Returns the raw 32 byte master key so a recovery code can be built from it. */
    fun exportMaster(): AppResult<ByteArray>

    /** Replaces the master key (used by recovery import). A fresh key is generated if needed. */
    fun installMaster(master: ByteArray): AppResult<Unit>

    /** Destroys the master key and everything wrapped with it. Irreversible. */
    fun reset()
}

@Singleton
class KeystoreVaultCrypto @Inject constructor(
    private val secureStore: SecureStore,
) : VaultCrypto {

    private val random = SecureRandom()

    override fun status(): VaultStatus {
        if (keystore() == null) {
            return VaultStatus(available = false, hasMasterKey = false, detail = KEYSTORE_UNAVAILABLE)
        }
        val hasMaster = !secureStore.getString(KEY_MASTER).isNullOrBlank()
        return VaultStatus(available = true, hasMasterKey = hasMaster, detail = null)
    }

    override fun seal(plaintext: ByteArray): AppResult<ByteArray> {
        val master = masterKeyBytes()
            ?: return AppResult.Failure(AppFailure.storage(SECURE_STORAGE_UNAVAILABLE))
        return try {
            val nonce = nextNonce()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(master, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(AAD)
            }
            AppResult.Success(nonce + cipher.doFinal(plaintext))
        } catch (t: Throwable) {
            AppResult.Failure(AppFailure.storage("Could not protect local key material.", t))
        } finally {
            master.fill(0)
        }
    }

    override fun open(sealed: ByteArray): AppResult<ByteArray> {
        if (sealed.size <= NONCE_BYTES) {
            return AppResult.Failure(AppFailure.storage("The stored key material is incomplete."))
        }
        val master = masterKeyBytes()
            ?: return AppResult.Failure(AppFailure.storage(SECURE_STORAGE_UNAVAILABLE))
        return try {
            val nonce = sealed.copyOfRange(0, NONCE_BYTES)
            val body = sealed.copyOfRange(NONCE_BYTES, sealed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(master, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(AAD)
            }
            AppResult.Success(cipher.doFinal(body))
        } catch (t: Throwable) {
            AppResult.Failure(
                AppFailure.storage(
                    "The local key material could not be opened. It may have been created on another " +
                        "install or the secure keystore entry was reset.",
                    t,
                ),
            )
        } finally {
            master.fill(0)
        }
    }

    override fun exportMaster(): AppResult<ByteArray> {
        val master = masterKeyBytes()
            ?: return AppResult.Failure(AppFailure.storage(SECURE_STORAGE_UNAVAILABLE))
        return AppResult.Success(master)
    }

    override fun installMaster(master: ByteArray): AppResult<Unit> {
        if (master.size != MASTER_BYTES) {
            return AppResult.Failure(AppFailure.storage("That recovery code does not contain a valid key."))
        }
        val wrapped = wrapWithKeystore(master)
            ?: return AppResult.Failure(AppFailure.storage(SECURE_STORAGE_UNAVAILABLE))
        secureStore.putString(KEY_MASTER, encode(wrapped))
        return AppResult.Success(Unit)
    }

    override fun reset() {
        secureStore.remove(KEY_MASTER)
    }

    // ------------------------------------------------------------- master key

    private fun masterKeyBytes(): ByteArray? {
        val blob = secureStore.getString(KEY_MASTER)
        if (blob.isNullOrBlank()) {
            // First run: create the master key and wrap it with the keystore key.
            val fresh = ByteArray(MASTER_BYTES).also(random::nextBytes)
            val wrapped = wrapWithKeystore(fresh)
                ?: run {
                    fresh.fill(0)
                    return null
                }
            secureStore.putString(KEY_MASTER, encode(wrapped))
            return fresh
        }
        return unwrapWithKeystore(runCatching { decode(blob) }.getOrNull() ?: return null)
    }

    private fun wrapWithKeystore(master: ByteArray): ByteArray? {
        val key = keystoreKey() ?: return null
        return runCatching {
            val nonce = nextNonce()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            }
            nonce + cipher.doFinal(master)
        }.getOrNull()
    }

    private fun unwrapWithKeystore(blob: ByteArray): ByteArray? {
        if (blob.size <= NONCE_BYTES) return null
        val key = keystoreKey() ?: return null
        return runCatching {
            val nonce = blob.copyOfRange(0, NONCE_BYTES)
            val body = blob.copyOfRange(NONCE_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            }
            cipher.doFinal(body)
        }.getOrNull()
    }

    private fun keystore(): KeyStore? = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }.getOrNull()

    /** Loads the non exportable AES-256-GCM key, creating it on first use. */
    private fun keystoreKey(): SecretKey? {
        val store = keystore() ?: return null
        return runCatching {
            (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        }.getOrNull() ?: runCatching {
            val generator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
            generator.init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(false)
                    .build(),
            )
            generator.generateKey()
        }.getOrNull()
    }

    // ----------------------------------------------------------------- nonces

    /**
     * GCM never allows the same nonce twice under one key, so instead of relying on luck we use
     * a persisted counter: `4 random bytes (fixed per install) || 8 byte big endian counter`.
     * The counter is stored *before* the nonce is handed out, so a crash cannot repeat one.
     */
    @Synchronized
    private fun nextNonce(): ByteArray {
        val storedPrefix = secureStore.getString(KEY_NONCE_PREFIX)
        val prefix = if (storedPrefix.isNullOrBlank()) {
            val generated = ByteArray(PREFIX_BYTES).also(random::nextBytes)
            secureStore.putString(KEY_NONCE_PREFIX, encode(generated))
            generated
        } else {
            runCatching { decode(storedPrefix) }.getOrNull()
                ?: ByteArray(PREFIX_BYTES).also(random::nextBytes)
        }
        var counter = secureStore.getString(KEY_NONCE_COUNTER)?.toLongOrNull() ?: 0L
        if (counter < 0L) counter = 0L
        secureStore.putString(KEY_NONCE_COUNTER, (counter + 1).toString())

        val nonce = ByteArray(NONCE_BYTES)
        System.arraycopy(prefix, 0, nonce, 0, minOf(prefix.size, PREFIX_BYTES))
        for (index in 0 until 8) {
            nonce[PREFIX_BYTES + index] = ((counter ushr (8 * (7 - index))) and 0xFF).toByte()
        }
        return nonce
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "slimex_vault_ks_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MASTER_BYTES = 32
        const val NONCE_BYTES = 12
        const val PREFIX_BYTES = 4
        const val TAG_BITS = 128
        val AAD = "slimex-keyring-v1".toByteArray(Charsets.UTF_8)

        const val KEY_MASTER = "vault_master_wrapped"
        const val KEY_NONCE_PREFIX = "vault_nonce_prefix"
        const val KEY_NONCE_COUNTER = "vault_nonce_counter"

        const val KEYSTORE_UNAVAILABLE =
            "This device does not expose a usable Android Keystore, so encryption keys cannot be " +
                "protected. Uploads stay disabled to keep your media from becoming unrecoverable."
        const val SECURE_STORAGE_UNAVAILABLE =
            "Secure key storage is unavailable on this device right now."
    }
}
