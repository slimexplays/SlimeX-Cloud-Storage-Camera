package com.slimex.cloudcamera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadTaskTest {

    private fun task(status: UploadStatus, size: Long = 100L) = UploadTask(
        id = "t",
        mediaId = "m",
        filePath = "/tmp/a.jpg",
        fileName = "a.jpg",
        kind = MediaKind.PHOTO,
        sizeBytes = size,
        mimeType = "image/jpeg",
        status = status,
    )

    @Test
    fun `uploaded and cancelled are finished`() {
        assertTrue(task(UploadStatus.UPLOADED).status.isFinished)
        assertTrue(task(UploadStatus.CANCELLED).status.isFinished)
        assertFalse(task(UploadStatus.QUEUED).status.isFinished)
    }

    @Test
    fun `running states are reported as running`() {
        assertTrue(task(UploadStatus.ENCRYPTING).status.isRunning)
        assertTrue(task(UploadStatus.UPLOADING).status.isRunning)
        assertTrue(task(UploadStatus.VERIFYING).status.isRunning)
        assertFalse(task(UploadStatus.QUEUED).status.isRunning)
    }

    @Test
    fun `blocked states are not active work`() {
        assertFalse(task(UploadStatus.WAITING_FOR_NETWORK).status.isActive)
        assertTrue(task(UploadStatus.UPLOADING).status.isActive)
        assertTrue(task(UploadStatus.QUEUED).status.isActive)
    }

    @Test
    fun `summary counts by state and sums bytes`() {
        val tasks = listOf(
            task(UploadStatus.QUEUED, 10),
            task(UploadStatus.UPLOADING, 20),
            task(UploadStatus.UPLOADED, 30),
            task(UploadStatus.FAILED, 40),
            task(UploadStatus.WAITING_FOR_AUTH, 50),
        )
        val summary = UploadQueueSummary(
            pending = tasks.count { it.status == UploadStatus.QUEUED || it.status == UploadStatus.WAITING_FOR_AUTH },
            uploading = tasks.count { it.status.isRunning },
            uploaded = tasks.count { it.status == UploadStatus.UPLOADED },
            failed = tasks.count { it.status == UploadStatus.FAILED },
            totalBytes = tasks.sumOf { it.sizeBytes },
            uploadedBytes = tasks.filter { it.status == UploadStatus.UPLOADED }.sumOf { it.sizeBytes },
        )
        assertEquals(2, summary.pending)
        assertEquals(1, summary.uploading)
        assertEquals(1, summary.uploaded)
        assertEquals(1, summary.failed)
        assertEquals(150L, summary.totalBytes)
        assertEquals(30L, summary.uploadedBytes)
        assertTrue(summary.hasWork)
    }

    @Test
    fun `media kind exposes storage folder and mime`() {
        assertEquals("photos", MediaKind.PHOTO.storageFolder)
        assertEquals("image/jpeg", MediaKind.PHOTO.mimeType)
        assertEquals("videos", MediaKind.VIDEO.storageFolder)
        assertEquals("video/mp4", MediaKind.VIDEO.mimeType)
    }
}
