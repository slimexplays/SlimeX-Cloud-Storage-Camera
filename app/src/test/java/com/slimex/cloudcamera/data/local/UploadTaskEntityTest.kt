package com.slimex.cloudcamera.data.local

import com.slimex.cloudcamera.domain.model.MediaKind
import com.slimex.cloudcamera.domain.model.UploadStatus
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadTaskEntityTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val entity = UploadTaskEntity(
        id = "task-1",
        mediaId = "media-1",
        filePath = "/data/user/0/com.slimex.cloudcamera/files/media/photos/a.jpg",
        fileName = "a.jpg",
        kind = "photo",
        sizeBytes = 1_024L,
        mimeType = "image/jpeg",
        status = "UPLOADING",
        progress = 0.42f,
        attempts = 2,
        createdAt = 1_000L,
        updatedAt = 2_000L,
        capturedAt = 900L,
    )

    @Test
    fun `entity survives a json round trip`() {
        val decoded = json.decodeFromString<UploadTaskEntity>(json.encodeToString(entity))
        assertEquals(entity, decoded)
    }

    @Test
    fun `entity maps to a domain task`() {
        val domain = entity.toDomain()
        assertEquals("task-1", domain.id)
        assertEquals("media-1", domain.mediaId)
        assertEquals(MediaKind.PHOTO, domain.kind)
        assertEquals(UploadStatus.UPLOADING, domain.status)
        assertEquals(0.42f, domain.progress, 0.0001f)
        assertNull(domain.errorMessage)
    }

    @Test
    fun `unknown status falls back to queued`() {
        val domain = entity.copy(status = "SOMETHING_NEW").toDomain()
        assertEquals(UploadStatus.QUEUED, domain.status)
    }

    @Test
    fun `video kind maps from storage value`() {
        val domain = entity.copy(kind = "video").toDomain()
        assertEquals(MediaKind.VIDEO, domain.kind)
        assertEquals("video/mp4", domain.kind.mimeType)
    }
}
