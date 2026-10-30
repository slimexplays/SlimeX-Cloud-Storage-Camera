package com.slimex.cloudcamera.domain.repository

import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.LocalMedia
import com.slimex.cloudcamera.domain.model.LocalStorageSummary
import com.slimex.cloudcamera.domain.model.MediaKind
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** App-private media files (captures). Nothing here touches shared/public storage. */
interface LocalMediaRepository {
    val media: StateFlow<List<LocalMedia>>

    suspend fun createOutputFile(kind: MediaKind): AppResult<File>

    suspend fun refresh()

    suspend fun delete(media: LocalMedia): AppResult<Unit>

    suspend fun deleteAll(): AppResult<Int>

    suspend fun summary(): AppResult<LocalStorageSummary>

    suspend fun clearThumbnails(): AppResult<Long>
}
