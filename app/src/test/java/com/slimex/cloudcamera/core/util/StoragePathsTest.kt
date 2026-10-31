package com.slimex.cloudcamera.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class StoragePathsTest {

    private val userId = "2b0f6d8e-0c1a-4c5f-9a3e-8d7c6b5a4f31"

    private fun utc(year: Int, monthZeroBased: Int, day: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, monthZeroBased, day, 12, 0, 0)
        }.timeInMillis

    @Test
    fun `object key follows user year month file layout`() {
        val key = StoragePaths.objectKey(userId, utc(2026, 9, 31), "abc-123")
        assertEquals("$userId/2026/10/abc-123.enc", key)
    }

    @Test
    fun `months are zero padded`() {
        val key = StoragePaths.objectKey(userId, utc(2026, 0, 5), "id")
        assertEquals("$userId/2026/01/id.enc", key)
    }

    @Test
    fun `key starts with the owner id so storage policies match`() {
        val key = StoragePaths.objectKey(userId, utc(2026, 4, 5), "id")
        assertTrue(key.startsWith("$userId/"))
    }

    @Test
    fun `thumbnail key is a sidecar of the object`() {
        val key = StoragePaths.objectKey(userId, utc(2026, 4, 5), "id")
        assertEquals("$userId/2026/05/id.thumb.enc", StoragePaths.thumbnailKey(key))
    }

    @Test
    fun `unique ids are different`() {
        assertTrue(StoragePaths.uniqueId() != StoragePaths.uniqueId())
    }

    @Test
    fun `user prefix ends with a slash`() {
        assertEquals("$userId/", StoragePaths.userPrefix(userId))
    }
}
