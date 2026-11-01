package com.slimex.cloudcamera.security

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The key ring document must survive a JSON round trip without losing key material. */
class KeyringPayloadTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `payload round trips with photo and video entries`() {
        val payload = KeyringPayload(
            keys = mapOf(
                "media-photo" to MediaKeyEntry(k = "a2V5", n = "bm9uY2U=", tk = "dGh1bWI=", tn = "dG4="),
                "media-video" to MediaKeyEntry(k = "dmlkZW8=", n = "bm9uY2Uy"),
            ),
        )

        val decoded = json.decodeFromString(
            KeyringPayload.serializer(),
            json.encodeToString(KeyringPayload.serializer(), payload),
        )

        assertEquals(1, decoded.version)
        assertEquals(2, decoded.keys.size)
        assertEquals("a2V5", decoded.keys.getValue("media-photo").k)
        assertEquals("dGh1bWI=", decoded.keys.getValue("media-photo").tk)
        assertEquals(null, decoded.keys.getValue("media-video").tk)
        assertTrue(decoded.keys.getValue("media-video").isComplete)
    }

    @Test
    fun `an entry without key material is reported as incomplete`() {
        val entry = MediaKeyEntry(k = "", n = "")
        assertEquals(false, entry.isComplete)
    }
}
