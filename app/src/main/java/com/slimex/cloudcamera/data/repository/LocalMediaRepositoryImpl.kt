package com.slimex.cloudcamera.data.repository

import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.util.DispatchersProvider
import com.slimex.cloudcamera.domain.model.LocalMedia
import com.slimex.cloudcamera.domain.model.LocalStorageSummary
import com.slimex.cloudcamera.domain.model.MediaKind
import com.slimex.cloudcamera.domain.repository.LocalMediaRepository
import com.slimex.cloudcamera.storage.LocalStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMediaRepositoryImpl @Inject constructor(
    private val storage: LocalStorageManager,
    private val dispatchers: DispatchersProvider,
) : LocalMediaRepository {

    private val _media = MutableStateFlow<List<LocalMedia>>(emptyList())
    override val media: StateFlow<List<LocalMedia>> = _media.asStateFlow()

    override suspend fun createOutputFile(kind: MediaKind): AppResult<File> = withContext(dispatchers.io) {
        runCatching {
            val file = if (kind == MediaKind.PHOTO) storage.newPhotoFile() else storage.newVideoFile()
            file.parentFile?.mkdirs()
            AppResult.Success(file)
        }.getOrElse {
            AppResult.Failure(AppFailure.storage("Could not create a local file for the capture.", it))
        }
    }

    override suspend fun refresh() {
        val items = withContext(dispatchers.io) { storage.listMedia() }
        _media.value = items
    }

    override suspend fun delete(media: LocalMedia): AppResult<Unit> = withContext(dispatchers.io) {
        val deleted = storage.deleteMedia(media)
        refresh()
        if (deleted) AppResult.Success(Unit)
        else AppResult.Failure(AppFailure.storage("The file could not be deleted."))
    }

    override suspend fun deleteAll(): AppResult<Int> = withContext(dispatchers.io) {
        val count = storage.deleteAllMedia()
        refresh()
        AppResult.Success(count)
    }

    override suspend fun summary(): AppResult<LocalStorageSummary> = withContext(dispatchers.io) {
        runCatching { AppResult.Success(storage.summary()) }
            .getOrElse { AppResult.Failure(AppFailure.storage("Could not read storage usage.", it)) }
    }

    override suspend fun clearThumbnails(): AppResult<Long> = withContext(dispatchers.io) {
        runCatching { AppResult.Success(storage.clearThumbnails()) }
            .getOrElse { AppResult.Failure(AppFailure.storage("Could not clear cached thumbnails.", it)) }
    }
}
