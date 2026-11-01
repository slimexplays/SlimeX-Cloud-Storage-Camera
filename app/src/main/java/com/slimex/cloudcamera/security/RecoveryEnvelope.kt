package com.slimex.cloudcamera.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase envelope used by the recovery code.
 *
 * Layout: `PREFIX + base64( MAGIC | salt(16) | nonce(12) | AES-256-GCM( ciphertext | tag ) )`
 *
 * The passphrase is stretched with PBKDF2-HMAC-SHA256 (210 000 iterations by default) and the
 * payload is authenticated, so a wrong passphrase or a single changed character fails cleanly
 * instead of producing garbage. This class is deliberately free of Android dependencies so the
 * format can be unit tested on the JVM.
 */
object RecoveryEnvelope {

    const val PREFIX = "SLIMEX-KEY-1:"
    const val DEFAULT_ITERATIONS = 210_000

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PBKDF2 = "PBKDF2WithHmacSHA256"
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'K'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte())
    private val AAD = "slimex-recovery-v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    /** Seals [plaintext] and returns the raw envelope bytes (no prefix, not base64). */
    fun seal(
        plaintext: ByteArray,
        passphrase: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = derive(passphrase, salt, iterations)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(AAD)
            }
            return MAGIC + salt + nonce + cipher.doFinal(plaintext)
        } finally {
            key.fill(0)
        }
    }

    /** Base64 form with the human recognisable prefix. */
    fun encode(envelope: ByteArray): String =
        PREFIX + Base64.getEncoder().encodeToString(envelope)

    /** Parses a recovery code (whitespace and line breaks are ignored). */
    fun decode(code: String): ByteArray? {
        val cleaned = code.filterNot { it.isWhitespace() }
        if (!cleaned.startsWith(PREFIX, ignoreCase = true)) return null
        val body = cleaned.substring(PREFIX.length)
        return runCatching { Base64.getDecoder().decode(body) }.getOrNull()
    }

    /** Opens an envelope. Returns null when the passphrase is wrong or the data was altered. */
    fun open(
        envelope: ByteArray,
        passphrase: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray? {
        val header = MAGIC.size + SALT_BYTES + NONCE_BYTES
        if (envelope.size <= header) return null
        if (!envelope.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val salt = envelope.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val nonce = envelope.copyOfRange(MAGIC.size + SALT_BYTES, MAGIC.size + SALT_BYTES + NONCE_BYTES)
        val ciphertext = envelope.copyOfRange(header, envelope.size)
        val key = derive(passphrase, salt, iterations)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(AAD)
            }
            cipher.doFinal(ciphertext)
        } catch (t: Throwable) {
            null
        } finally {
            key.fill(0)
        }
    }

    private fun derive(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(PBKDF2).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
