package com.slimex.cloudcamera.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the passphrase envelope used by the recovery code.
 *
 * The iteration count is lowered here only to keep the test fast; production always uses
 * [RecoveryEnvelope.DEFAULT_ITERATIONS].
 */
class RecoveryEnvelopeTest {

    private val passphrase = "correct horse battery".toCharArray()
    private val iterations = 1_000
    private val payload = "{\"master\":\"abc\",\"keys\":{}}".toByteArray()

    @Test
    fun `seal and open round trip the payload`() {
        val sealed = RecoveryEnvelope.seal(payload, passphrase, iterations)
        val opened = RecoveryEnvelope.open(sealed, passphrase, iterations)
        assertEquals(String(payload), String(opened!!))
    }

    @Test
    fun `wrong passphrase is rejected`() {
        val sealed = RecoveryEnvelope.seal(payload, passphrase, iterations)
        assertNull(RecoveryEnvelope.open(sealed, "wrong passphrase".toCharArray(), iterations))
    }

    @Test
    fun `a single flipped byte is rejected`() {
        val sealed = RecoveryEnvelope.seal(payload, passphrase, iterations)
        val tampered = sealed.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0x01).toByte()
        assertNull(RecoveryEnvelope.open(tampered, passphrase, iterations))
    }

    @Test
    fun `every envelope uses a fresh salt and nonce`() {
        val first = RecoveryEnvelope.seal(payload, passphrase, iterations)
        val second = RecoveryEnvelope.seal(payload, passphrase, iterations)
        // magic(4) + salt(16) + nonce(12) must differ between two seals of the same input.
        assertFalse(first.copyOfRange(0, 32).contentEquals(second.copyOfRange(0, 32)))
        assertNotEquals(first.size, 0)
        // The ciphertext must differ as well (different nonce + key).
        assertFalse(first.copyOfRange(32, first.size).contentEquals(second.copyOfRange(32, second.size)))
    }

    @Test
    fun `code carries the documented prefix and survives whitespace`() {
        val sealed = RecoveryEnvelope.seal(payload, passphrase, iterations)
        val code = RecoveryEnvelope.encode(sealed)
        assertTrue(code.startsWith(RecoveryEnvelope.PREFIX))

        val wrapped = code.chunked(48).joinToString("\n")
        val decoded = RecoveryEnvelope.decode(wrapped)
        assertEquals(String(payload), String(RecoveryEnvelope.open(decoded!!, passphrase, iterations)!!))
    }

    @Test
    fun `foreign or truncated codes decode to null`() {
        assertNull(RecoveryEnvelope.decode("not-a-code"))
        assertNull(RecoveryEnvelope.decode(RecoveryEnvelope.PREFIX + "!!!not-base64!!!"))
        assertNull(RecoveryEnvelope.open(ByteArray(8), passphrase, iterations))
    }

    @Test
    fun `empty passphrase is refused`() {
        var failed = false
        try {
            RecoveryEnvelope.seal(payload, CharArray(0), iterations)
        } catch (t: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
