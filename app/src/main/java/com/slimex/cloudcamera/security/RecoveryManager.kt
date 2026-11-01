package com.slimex.cloudcamera.security

import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.result.FailureKind
import com.slimex.cloudcamera.core.util.DispatchersProvider
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Contents sealed inside a recovery code. Never leaves the device in the clear. */
@Serializable
private data class RecoveryPayload(
    val version: Int = 1,
    val createdAtMillis: Long = 0L,
    val masterBase64: String,
    val keys: Map<String, MediaKeyEntry> = emptyMap(),
)

/** Result of building a recovery code. The code itself is the only copy of the key ring. */
data class RecoveryExport(
    val code: String,
    val keyCount: Int,
    val createdAtMillis: Long,
)

/** Snapshot for the Encryption & Recovery screen. */
data class RecoveryStatus(
    val vaultAvailable: Boolean,
    val hasMasterKey: Boolean,
    val keyCount: Int,
    val lastExportMillis: Long?,
    val storeError: String?,
)

interface RecoveryManager {
    suspend fun status(): RecoveryStatus

    /** Builds a passphrase protected recovery code containing the vault key and key ring. */
    suspend fun export(passphrase: CharArray): AppResult<RecoveryExport>

    /** Restores the vault key and key ring from a recovery code. Returns the restored key count. */
    suspend fun restore(code: String, passphrase: CharArray): AppResult<Int>

    /** Destroys local key material. Cloud files stay, but they can no longer be decrypted. */
    suspend fun resetLocalKeys(): AppResult<Unit>
}

/**
 * Recovery / export of the local key material.
 *
 * A recovery code is a self contained, passphrase encrypted blob (see [RecoveryEnvelope]):
 * `SLIMEX-KEY-1:<base64>` carrying the 256-bit vault master key plus every per-media key. It lets
 * a reinstalled app — or a new phone — open the existing cloud library again.
 *
 * Nothing here is transmitted to Supabase or anywhere else: the code is shown to the user once
 * and only they hold it. Losing both the code and the passphrase makes encrypted media
 * permanently unreadable, which the UI warns about before and after export.
 */
@Singleton
class PassphraseRecoveryManager @Inject constructor(
    private val vault: VaultCrypto,
    private val keyStore: MediaKeyStore,
    private val secureStore: SecureStore,
    private val json: Json,
    private val dispatchers: DispatchersProvider,
) : RecoveryManager {

    override suspend fun status(): RecoveryStatus {
        val vaultStatus = vault.status()
        return RecoveryStatus(
            vaultAvailable = vaultStatus.available,
            hasMasterKey = vaultStatus.hasMasterKey,
            keyCount = keyStore.count(),
            lastExportMillis = secureStore.getString(KEY_LAST_EXPORT)?.toLongOrNull(),
            storeError = keyStore.lastError(),
        )
    }

    override suspend fun export(passphrase: CharArray): AppResult<RecoveryExport> {
        if (passphrase.size < MIN_PASSPHRASE) {
            return AppResult.Failure(
                AppFailure(
                    "Use a recovery passphrase with at least $MIN_PASSPHRASE characters.",
                    FailureKind.INVALID_INPUT,
                ),
            )
        }
        val master = when (val result = vault.exportMaster()) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return result
        }
        return try {
            val keys = keyStore.snapshot()
            val createdAt = System.currentTimeMillis()
            val payload = RecoveryPayload(
                createdAtMillis = createdAt,
                masterBase64 = Base64.getEncoder().encodeToString(master),
                keys = keys,
            )
            val plaintext = json.encodeToString(RecoveryPayload.serializer(), payload).encodeToByteArray()
            val envelope = withContext(dispatchers.io) {
                runCatching { RecoveryEnvelope.seal(plaintext, passphrase) }.getOrNull()
            } ?: return AppResult.Failure(AppFailure.storage("Could not build the recovery code."))

            secureStore.putString(KEY_LAST_EXPORT, createdAt.toString())
            AppResult.Success(
                RecoveryExport(
                    code = RecoveryEnvelope.encode(envelope),
                    keyCount = keys.size,
                    createdAtMillis = createdAt,
                ),
            )
        } catch (t: Throwable) {
            AppResult.Failure(AppFailure.storage("Could not build the recovery code.", t))
        } finally {
            master.fill(0)
        }
    }

    override suspend fun restore(code: String, passphrase: CharArray): AppResult<Int> {
        if (passphrase.isEmpty()) {
            return AppResult.Failure(
                AppFailure("Enter the passphrase you used when the code was created.", FailureKind.INVALID_INPUT),
            )
        }
        val envelope = RecoveryEnvelope.decode(code) ?: return AppResult.Failure(
            AppFailure(
                "That does not look like a SlimeX recovery code. It should start with " +
                    "${RecoveryEnvelope.PREFIX}",
                FailureKind.INVALID_INPUT,
            ),
        )
        val plaintext = withContext(dispatchers.io) {
            RecoveryEnvelope.open(envelope, passphrase)
        } ?: return AppResult.Failure(
            AppFailure(
                "Could not open the recovery code. Check the passphrase and that the whole code was pasted.",
                FailureKind.INVALID_INPUT,
            ),
        )

        val payload = runCatching {
            json.decodeFromString(RecoveryPayload.serializer(), plaintext.decodeToString())
        }.getOrNull() ?: return AppResult.Failure(
            AppFailure("The recovery code does not contain readable key material.", FailureKind.INVALID_INPUT),
        )

        val master = runCatching { Base64.getDecoder().decode(payload.masterBase64) }.getOrNull()
        if (master == null || master.size != MASTER_BYTES) {
            return AppResult.Failure(
                AppFailure("The recovery code has no usable vault key.", FailureKind.INVALID_INPUT),
            )
        }

        return when (val installed = vault.installMaster(master)) {
            is AppResult.Failure -> installed
            is AppResult.Success -> when (val saved = keyStore.replaceAll(payload.keys)) {
                is AppResult.Failure -> saved
                is AppResult.Success -> AppResult.Success(payload.keys.size)
            }
        }
    }

    override suspend fun resetLocalKeys(): AppResult<Unit> {
        val cleared = keyStore.replaceAll(emptyMap())
        vault.reset()
        secureStore.remove(KEY_LAST_EXPORT)
        return cleared
    }

    private companion object {
        const val MIN_PASSPHRASE = 8
        const val MASTER_BYTES = 32
        const val KEY_LAST_EXPORT = "recovery_last_export"
    }
}
