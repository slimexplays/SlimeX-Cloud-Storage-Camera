package com.slimex.cloudcamera.security

import android.util.Base64
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.result.runCatchingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** AES-256-GCM key material for a single media file. Never leaves the device unencrypted. */
data class MediaKey(val keyBase64: String, val nonceBase64: String)

/** Result of encrypting a capture: the ciphertext plus everything needed to verify/decrypt it. */
data class EncryptedMedia(
    val file: File,
    val key: MediaKey,
    /** SHA-256 (Base64) of the *plaintext*, used to verify the upload after decrypting it back. */
    val checksum: String,
    val plaintextBytes: Long,
    val ciphertextBytes: Long,
)

/**
 * Streaming AES-256-GCM encryption/decryption for captured media.
 *
 * Files are processed through [CipherInputStream]/[CipherOutputStream] so a multi-gigabyte
 * video never has to be buffered in memory. Every file gets a fresh random 256-bit key and
 * a fresh 96-bit nonce (GCM never allows nonce reuse with the same key).
 */
interface MediaCrypto {
    /** Encrypts [source] into [destination] (which must not exist yet). */
    suspend fun encrypt(source: File, destination: File): AppResult<EncryptedMedia>

    /** Reverses [encrypt]; fails with a STORAGE failure if the GCM tag does not verify. */
    suspend fun decrypt(source: File, destination: File, key: MediaKey): AppResult<File>

    /** SHA-256 (Base64, no padding wrap) of the file's bytes. */
    suspend fun sha256(file: File): String

    /** Verification helper: does this file still hash to the expected digest? */
    suspend fun verify(file: File, expectedChecksum: String): Boolean
}

@Singleton
class AesGcmMediaCrypto @Inject constructor() : MediaCrypto {

    private val secureRandom = SecureRandom()

    override suspend fun encrypt(source: File, destination: File): AppResult<EncryptedMedia> =
        withContext(Dispatchers.IO) {
            runCatchingResult("Encrypt failed") {
                if (!source.exists() || source.length() == 0L) {
                    throw IOException("Nothing to encrypt")
                }
                val keyBytes = ByteArray(KEY_BYTES).also(secureRandom::nextBytes)
                val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)

                destination.parentFile?.mkdirs()
                if (destination.exists()) destination.delete()

                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(
                        Cipher.ENCRYPT_MODE,
                        SecretKeySpec(keyBytes, "AES"),
                        GCMParameterSpec(TAG_BITS, nonce),
                    )
                }

                val digest = MessageDigest.getInstance("SHA-256")

                FileInputStream(source).use { input ->
                    FileOutputStream(destination).use { output ->
                        CipherOutputStream(output, cipher).use { cipherOut ->
                            copyStream(input, cipherOut, digest::update)
                        }
                    }
                }

                if (destination.length() <= 0L) {
                    destination.delete()
                    throw IOException("Encryption produced an empty file")
                }

                EncryptedMedia(
                    file = destination,
                    key = MediaKey(encode(keyBytes), encode(nonce)),
                    checksum = encode(digest.digest()),
                    plaintextBytes = source.length(),
                    ciphertextBytes = destination.length(),
                )
            }.also { result ->
                if (result is AppResult.Failure) runCatching { destination.delete() }
            }
        }

    override suspend fun decrypt(
        source: File,
        destination: File,
        key: MediaKey,
    ): AppResult<File> = withContext(Dispatchers.IO) {
        val result = runCatchingResult("Decrypt failed") {
            if (!source.exists()) throw IOException("Missing encrypted file")

            val keyBytes = decode(key.keyBase64)
            val nonce = decode(key.nonceBase64)
            if (keyBytes.size != KEY_BYTES) throw IOException("Invalid key material")
            if (nonce.size != NONCE_BYTES) throw IOException("Invalid nonce")

            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(keyBytes, "AES"),
                    GCMParameterSpec(TAG_BITS, nonce),
                )
            }

            destination.parentFile?.mkdirs()
            if (destination.exists()) destination.delete()

            val plaintextBytes = FileInputStream(source).use { input ->
                CipherInputStream(input, cipher).use { cipherIn ->
                    FileOutputStream(destination).use { output ->
                        copyStream(cipherIn, output, null)
                    }
                }
            }

            if (destination.length() != plaintextBytes || plaintextBytes <= 0L) {
                throw IOException("Decrypted file is incomplete")
            }
            destination
        }

        if (result is AppResult.Failure) runCatching { destination.delete() }
        result
    }

    override suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            copyStream(input, null, digest::update)
        }
        encode(digest.digest())
    }

    override suspend fun verify(file: File, expectedChecksum: String): Boolean = runCatching {
        file.exists() && sha256(file).equals(expectedChecksum, ignoreCase = false)
    }.getOrDefault(false)

    /** Returns the number of bytes read. Also feeds the plaintext digest when [digestSink] is set. */
    private fun copyStream(
        input: java.io.InputStream,
        output: java.io.OutputStream?,
        digestSink: ((ByteArray, Int, Int) -> Unit)?,
    ): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digestSink?.invoke(buffer, 0, read)
            output?.write(buffer, 0, read)
            total += read
        }
        return total
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val BUFFER_BYTES = 64 * 1024
    }
}
