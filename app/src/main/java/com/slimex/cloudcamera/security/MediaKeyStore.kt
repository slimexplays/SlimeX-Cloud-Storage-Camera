package com.slimex.cloudcamera.security

import android.content.Context
import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.util.DispatchersProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM key material for one cloud object.
 *
 * [k]/[n] are the object key and its 96-bit nonce; [tk]/[tn] belong to the optional encrypted
 * thumbnail sidecar. These values live **only** on this device and are never inserted into
 * Supabase — the cloud copy of a file is useless without the local key ring.
 */
@Serializable
data class MediaKeyEntry(
    val k: String,
    val n: String,
    val tk: String? = null,
    val tn: String? = null,
) {
    val isComplete: Boolean get() = k.isNotBlank() && n.isNotBlank()
}

@Serializable
data class KeyringPayload(
    val version: Int = 1,
    val keys: Map<String, MediaKeyEntry> = emptyMap(),
)

/** Local, device bound store for per-media encryption keys. */
interface MediaKeyStore {

    suspend fun put(mediaId: String, entry: MediaKeyEntry): AppResult<Unit>

    suspend fun get(mediaId: String): MediaKeyEntry?

    suspend fun remove(mediaId: String)

    suspend fun count(): Int

    suspend fun snapshot(): Map<String, MediaKeyEntry>

    suspend fun replaceAll(keys: Map<String, MediaKeyEntry>): AppResult<Unit>

    /** Non-null when the key ring file was unreadable and had to be moved aside. */
    suspend fun lastError(): String?
}

/**
 * Key ring persisted as one small JSON document sealed with the Keystore protected vault master
 * key (`files/keys/keyring.v1`). Writes are atomic (temp file + rename) so a crash can never
 * leave a half written key ring behind, and a corrupt file is moved aside instead of deleted.
 */
@Singleton
class EncryptedMediaKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vault: VaultCrypto,
    private val json: Json,
    private val dispatchers: DispatchersProvider,
) : MediaKeyStore {

    private val mutex = Mutex()
    private var cache: MutableMap<String, MediaKeyEntry>? = null
    private var error: String? = null

    private val file: File
        get() = File(File(context.filesDir, KEYS_DIR), KEYRING_FILE)

    override suspend fun put(mediaId: String, entry: MediaKeyEntry): AppResult<Unit> = mutex.withLock {
        val current = load()
        val next = current.toMutableMap()
        next[mediaId] = entry
        persist(next)
    }

    override suspend fun get(mediaId: String): MediaKeyEntry? = mutex.withLock {
        load()[mediaId]
    }

    override suspend fun remove(mediaId: String) {
        mutex.withLock {
            val current = load()
            if (!current.containsKey(mediaId)) return@withLock
            val next = current.toMutableMap()
            next.remove(mediaId)
            persist(next)
        }
    }

    override suspend fun count(): Int = mutex.withLock { load().size }

    override suspend fun snapshot(): Map<String, MediaKeyEntry> = mutex.withLock { load().toMap() }

    override suspend fun replaceAll(keys: Map<String, MediaKeyEntry>): AppResult<Unit> =
        mutex.withLock { persist(keys.toMutableMap()) }

    override suspend fun lastError(): String? = mutex.withLock { error }

    // ------------------------------------------------------------- internals

    /** Caller must hold [mutex]. */
    private suspend fun load(): MutableMap<String, MediaKeyEntry> {
        cache?.let { return it }
        val loaded = withContext(dispatchers.io) {
            val target = file
            if (!target.exists() || target.length() == 0L) {
                mutableMapOf()
            } else {
                when (val opened = vault.open(target.readBytes())) {
                    is AppResult.Success -> {
                        val payload = runCatching {
                            json.decodeFromString(KeyringPayload.serializer(), opened.data.decodeToString())
                        }.getOrNull()
                        if (payload == null) {
                            quarantine(target, "The local key ring could not be parsed.")
                            mutableMapOf()
                        } else {
                            payload.keys.toMutableMap()
                        }
                    }

                    is AppResult.Failure -> {
                        quarantine(target, opened.error.message)
                        mutableMapOf()
                    }
                }
            }
        }
        cache = loaded
        return loaded
    }

    /** Caller must hold [mutex]. */
    private suspend fun persist(keys: MutableMap<String, MediaKeyEntry>): AppResult<Unit> =
        withContext(dispatchers.io) {
            val payload = KeyringPayload(keys = keys.toMap())
            val sealed = when (val result = vault.seal(json.encodeToString(KeyringPayload.serializer(), payload).encodeToByteArray())) {
                is AppResult.Success -> result.data
                is AppResult.Failure -> return@withContext AppResult.Failure(result.error)
            }
            val target = file
            try {
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, "$KEYRING_FILE.tmp")
                temp.writeBytes(sealed)
                if (target.exists() && !target.delete()) {
                    return@withContext AppResult.Failure(
                        AppFailure.storage("Could not replace the local key ring."),
                    )
                }
                if (!temp.renameTo(target)) {
                    runCatching { temp.delete() }
                    return@withContext AppResult.Failure(
                        AppFailure.storage("Could not save the local key ring."),
                    )
                }
                cache = keys
                error = null
                AppResult.Success(Unit)
            } catch (t: Throwable) {
                AppResult.Failure(AppFailure.storage("Could not save the local key ring.", t))
            }
        }

    /** Keeps unreadable key material for manual recovery instead of deleting it. */
    private fun quarantine(target: File, reason: String) {
        error = reason
        runCatching {
            val aside = File(target.parentFile, "$KEYRING_FILE.corrupt-${System.currentTimeMillis()}")
            target.renameTo(aside)
        }
    }

    private companion object {
        const val KEYS_DIR = "keys"
        const val KEYRING_FILE = "keyring.v1"
    }
}
