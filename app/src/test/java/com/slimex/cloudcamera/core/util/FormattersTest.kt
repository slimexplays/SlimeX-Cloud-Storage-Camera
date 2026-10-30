package com.slimex.cloudcamera.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `bytes formats small values`() {
        assertEquals("0 B", Formatters.bytes(0))
        assertEquals("512 B", Formatters.bytes(512))
    }

    @Test
    fun `bytes formats kilobytes and megabytes`() {
        assertEquals("2 KB", Formatters.bytes(2_048))
        assertEquals("5.0 MB", Formatters.bytes(5L * 1024 * 1024))
    }

    @Test
    fun `bytes rejects negative input`() {
        assertEquals("—", Formatters.bytes(-1))
    }

    @Test
    fun `duration formats minutes and seconds`() {
        assertEquals("00:05", Formatters.duration(5_000))
        assertEquals("01:05", Formatters.duration(65_000))
        assertEquals("00:00", Formatters.duration(0))
    }

    @Test
    fun `relative time switches units`() {
        val now = 1_700_000_000_000L
        assertEquals("Just now", Formatters.relative(now - 15_000, now))
        assertEquals("5m ago", Formatters.relative(now - 5 * 60_000, now))
        assertEquals("2h ago", Formatters.relative(now - 2 * 3_600_000, now))
        assertEquals("3d ago", Formatters.relative(now - 3 * 86_400_000L, now))
    }

    @Test
    fun `host strips scheme and path`() {
        assertEquals("demo.supabase.co", Formatters.hostOf("https://demo.supabase.co/rest/v1"))
    }
}
