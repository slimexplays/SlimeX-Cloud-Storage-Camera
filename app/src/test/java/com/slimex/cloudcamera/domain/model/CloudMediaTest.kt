package com.slimex.cloudcamera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudMediaTest {

    private fun media(thumbnail: String? = null) = CloudMedia(
        id = "id",
        userId = "user",
        storagePath = "/user/2026/10/id.enc",
        fileName = "id.enc",
        mimeType = "image/jpeg",
        fileSize = 2048L,
        mediaType = MediaKind.PHOTO,
        encrypted = true,
        checksum = "abc",
        createdAtMillis = 1L,
        thumbnailPath = thumbnail,
    )

    @Test
    fun `object key never starts with a slash`() {
        assertEquals("user/2026/10/id.enc", media().objectKey)
    }

    @Test
    fun `thumbnail presence is derived from the path`() {
        assertFalse(media().hasThumbnail)
        assertTrue(media("/user/2026/10/id.thumb.enc").hasThumbnail)
    }

    @Test
    fun `filter maps to postgrest values`() {
        assertEquals(null, MediaFilter.ALL.mediaType)
        assertEquals("photo", MediaFilter.PHOTOS.mediaType)
        assertEquals("video", MediaFilter.VIDEOS.mediaType)
    }

    @Test
    fun `sort maps to an order clause`() {
        assertEquals("created_at.desc", MediaSort.NEWEST.order)
        assertEquals("file_size.asc", MediaSort.SMALLEST.order)
    }
}
