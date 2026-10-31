package com.slimex.cloudcamera.data.repository

import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.result.runCatchingResult
import com.slimex.cloudcamera.core.util.DispatchersProvider
import com.slimex.cloudcamera.domain.model.LocalMedia
import com.slimex.cloudcamera.domain.model.LocalStorageSummary
import com.slimex.cloudcamera.domain.repository.LocalMediaRepository
import com.slimex.cloudcamera.storage.StagingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMediaRepositoryImpl @Inject constructor(
    private val staging: StagingStore,
    private val dispatchers: DispatchersProvider,
) : LocalMediaRepository {

    private val _pending = MutableStateFlow<List<LocalMedia>>(emptyList())
    override val pending: StateFlow<List<LocalMedia>> = _pending.asStateFlow()

    init {
        _pending.value = staging.listPending()
    }

    override suspend fun refresh() {
        withContext(dispatchers.io) {
            _pending.value = staging.listPending()
        }
    }

    override suspend fun summary(): AppResult<LocalStorageSummary> = withContext(dispatchers.io) {
        runCatchingResult("Storage summary failed") {
            staging.migrateLegacyCaptures()
            val pending = staging.listPending()
            _pending.value = pending
            staging.pruneScratch()
            staging.summary(pending)
        }
    }

    override suspend fun clearCache(): AppResult<Long> = withContext(dispatchers.io) {
        runCatchingResult("Cache clean-up failed") {
            // Pending (un-uploaded) captures live in files/, not cache/, and are never touched.
            staging.clearCache()
        }
    }

    override suspend fun migrateLegacyCaptures(): AppResult<Int> = withContext(dispatchers.io) {
        runCatchingResult("Migration failed") {
            val moved = staging.migrateLegacyCaptures()
            _pending.value = staging.listPending()
            moved
        }
    }
}
