package com.slimex.cloudcamera.data.repository

import com.slimex.cloudcamera.cloud.CloudContext
import com.slimex.cloudcamera.cloud.CloudSessionProvider
import com.slimex.cloudcamera.cloud.SupabaseDataApi
import com.slimex.cloudcamera.core.network.ConnectivityObserver
import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.result.FailureKind
import com.slimex.cloudcamera.core.util.DispatchersProvider
import com.slimex.cloudcamera.core.util.StoragePaths
import com.slimex.cloudcamera.data.local.UploadQueueStore
import com.slimex.cloudcamera.domain.model.AppSettings
import com.slimex.cloudcamera.domain.model.MediaKind
import com.slimex.cloudcamera.domain.model.UploadQueueSummary
import com.slimex.cloudcamera.domain.model.UploadStatus
import com.slimex.cloudcamera.domain.model.UploadTask
import com.slimex.cloudcamera.domain.repository.SettingsRepository
import com.slimex.cloudcamera.domain.repository.UploadRepository
import com.slimex.cloudcamera.upload.MediaUploader
import com.slimex.cloudcamera.upload.UploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable, offline-first upload queue.
 *
 * A task moves through ENCRYPTING → UPLOADING → VERIFYING → UPLOADED. The local capture is only
 * deleted after the bucket copy has been verified, and the queue itself is persisted so a reboot,
 * process death or lost connection never loses an upload.
 */
@Singleton
class UploadRepositoryImpl @Inject constructor(
    private val store: UploadQueueStore,
    private val uploader: MediaUploader,
    private val cloud: CloudSessionProvider,
    private val data: SupabaseDataApi,
    private val settingsRepository: SettingsRepository,
    private val connectivity: ConnectivityObserver,
    private val scheduler: UploadScheduler,
    private val dispatchers: DispatchersProvider,
) : UploadRepository {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val engineMutex = Mutex()
    private val persistChannel = Channel<List<UploadTask>>(Channel.CONFLATED)

    private val _tasks = MutableStateFlow<List<UploadTask>>(emptyList())
    override val tasks: StateFlow<List<UploadTask>> = _tasks.asStateFlow()

    private val _summary = MutableStateFlow(UploadQueueSummary())
    override val summary: StateFlow<UploadQueueSummary> = _summary.asStateFlow()

    private var loaded = false

    init {
        scope.launch {
            for (snapshot in persistChannel) {
                runCatching { store.write(snapshot) }
            }
        }
        scope.launch {
            _tasks.value = store.read().map { it.recovered() }
            // Ciphertext from a previous process can never be decrypted again (its AES key only
            // ever lived in memory), so it is discarded and re-created with a fresh key.
            _tasks.value.forEach { task -> runCatching { uploader.discardScratch(task) } }
            loaded = true
            publish()
            // A reconnect or a configuration change should pick the queue back up automatically.
            connectivity.status.collectLatest { status ->
                if (status.online && _tasks.value.any { it.status == UploadStatus.WAITING_FOR_NETWORK }) {
                    resumePending(retryFailed = false)
                }
            }
        }
    }

    // ---------------------------------------------------------------- queue api

    override suspend fun enqueue(
        filePath: String,
        kind: MediaKind,
        durationMillis: Long?,
        capturedAt: Long,
        startImmediately: Boolean,
    ): AppResult<String> {
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) {
            return AppResult.Failure(AppFailure.storage("The capture could not be written to private storage."))
        }
        awaitLoaded()

        val duplicate = _tasks.value.firstOrNull {
            it.filePath == filePath && it.status != UploadStatus.CANCELLED && it.status != UploadStatus.FAILED
        }
        if (duplicate != null) return AppResult.Success(duplicate.id)

        val task = UploadTask(
            id = StoragePaths.uniqueId(),
            mediaId = StoragePaths.uniqueId(),
            filePath = file.absolutePath,
            fileName = file.name,
            kind = kind,
            sizeBytes = file.length(),
            mimeType = kind.mimeType,
            status = UploadStatus.QUEUED,
            capturedAt = capturedAt,
            durationMillis = durationMillis,
        )

        _tasks.update { it + task }
        publish(persist = true)

        if (startImmediately) scheduleWork()
        return AppResult.Success(task.id)
    }

    override suspend fun retry(taskId: String) {
        updateTask(taskId) { current ->
            when {
                current.status == UploadStatus.UPLOADED -> current
                else -> current.copy(
                    status = UploadStatus.QUEUED,
                    progress = 0f,
                    // A manual retry gets a fresh retry budget.
                    attempts = 0,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
        publish(persist = true)
        scheduleWork()
    }

    override suspend fun cancel(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        updateTask(taskId) { current ->
            current.copy(
                status = UploadStatus.CANCELLED,
                progress = 0f,
                errorMessage = "Cancelled. The original file is still on this device.",
                updatedAt = System.currentTimeMillis(),
            )
        }
        uploader.discardScratch(task)
        publish(persist = true)
    }

    override suspend fun clearFinished() {
        val finished = _tasks.value.filter {
            it.status == UploadStatus.UPLOADED || it.status == UploadStatus.CANCELLED
        }
        if (finished.isEmpty()) return
        _tasks.update { list -> list.filter { it.status != UploadStatus.UPLOADED && it.status != UploadStatus.CANCELLED } }
        publish(persist = true)
    }

    override suspend fun resumePending(retryFailed: Boolean) {
        awaitLoaded()
        _tasks.update { list ->
            list.map { task ->
                when {
                    task.status == UploadStatus.WAITING_FOR_NETWORK ||
                        task.status == UploadStatus.WAITING_FOR_CONFIG ||
                        task.status == UploadStatus.WAITING_FOR_AUTH ->
                        task.copy(status = UploadStatus.QUEUED, updatedAt = System.currentTimeMillis())

                    retryFailed && task.status == UploadStatus.FAILED && task.attempts < MAX_ATTEMPTS ->
                        task.copy(status = UploadStatus.QUEUED, updatedAt = System.currentTimeMillis())

                    else -> task
                }
            }
        }
        publish(persist = true)
        scheduleWork()
    }

    /** Used by the WorkManager worker; processes everything that can be processed right now. */
    suspend fun drain(): Int {
        awaitLoaded()
        val settings = settingsRepository.current()
        if (!settings.autoUpload) return 0
        val remaining = drainQueue(settings)
        publish(persist = true)
        return remaining
    }

    // ---------------------------------------------------------------- engine

    private suspend fun scheduleWork() {
        val settings = settingsRepository.current()
        if (!settings.autoUpload) return
        scheduler.schedule(wifiOnly = settings.wifiOnlyUpload)
        scope.launch { drainQueue(settings) }
    }

    private suspend fun drainQueue(settings: AppSettings): Int = engineMutex.withLock {
        var guard = 0
        while (guard < MAX_TASKS_PER_DRAIN) {
            val next = _tasks.value.firstOrNull { it.status == UploadStatus.QUEUED } ?: break
            val outcome = runTask(next.id, settings)
            guard++
            if (!outcome) break
        }
        _tasks.value.count { it.status == UploadStatus.QUEUED }
    }

    /** Returns true when the queue can continue with the next task. */
    private suspend fun runTask(taskId: String, settings: AppSettings): Boolean {
        val snapshot = _tasks.value.firstOrNull { it.id == taskId } ?: return false
        if (snapshot.status != UploadStatus.QUEUED) return false

        val network = connectivity.current()
        if (!network.online) {
            setStatus(taskId, UploadStatus.WAITING_FOR_NETWORK, "Waiting for a network connection.")
            return false
        }
        if (settings.wifiOnlyUpload && !network.wifi) {
            setStatus(taskId, UploadStatus.WAITING_FOR_NETWORK, "Waiting for Wi-Fi (mobile data is not allowed).")
            return false
        }

        val context = when (val result = cloud.require()) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> {
                markBlocked(taskId, result.error)
                return false
            }
        }

        if (!sourceExists(taskId)) {
            setFailed(taskId, "The captured file is missing from this device.")
            return true
        }

        setStatus(taskId, UploadStatus.ENCRYPTING, "Encrypting with AES-256-GCM…")
        syncJob(context, taskId, "processing")

        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return false
        val prepared = when (val result = uploader.prepare(task, context.userId)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> {
                handleFailure(taskId, result.error, context)
                return true
            }
        }

        if (isCancelled(taskId)) {
            uploader.discardScratch(task)
            return true
        }

        setStatus(taskId, UploadStatus.UPLOADING, "Uploading encrypted file…")
        val transfer = uploader.transfer(context, prepared) { progress ->
            updateProgress(taskId, progress)
        }
        if (transfer is AppResult.Failure) {
            handleFailure(taskId, transfer.error, context)
            return true
        }

        if (isCancelled(taskId)) {
            uploader.discardScratch(task)
            return true
        }

        setStatus(taskId, UploadStatus.VERIFYING, "Verifying the cloud copy…")
        val verification = uploader.verify(context, prepared)
        if (verification is AppResult.Failure) {
            handleFailure(taskId, verification.error, context)
            return true
        }

        val metadata = uploader.saveMetadata(context, prepared, task)
        if (metadata is AppResult.Failure) {
            // The object is already in the bucket; a duplicate row means a previous attempt got
            // far enough to insert it, which is a success for our purposes.
            val existing = data.findMediaByPath(context.config, context.accessToken, prepared.objectKey)
            val alreadyStored = existing is AppResult.Success && existing.data != null
            if (!alreadyStored) {
                handleFailure(taskId, metadata.error, context)
                return true
            }
        }

        if (isCancelled(taskId)) {
            // The bucket copy exists but the user cancelled; keep the local original and stop here.
            uploader.discardScratch(task)
            return true
        }

        // Verified: the local plaintext and ciphertext can go now.
        uploader.deleteLocalCopies(prepared)

        updateTask(taskId) { current ->
            current.copy(
                status = UploadStatus.UPLOADED,
                progress = 1f,
                attempts = current.attempts,
                errorMessage = null,
                remotePath = prepared.objectKey,
                remoteThumbnailPath = prepared.thumbnailObjectKey,
                encryptedPath = null,
                encryptedSizeBytes = prepared.encryptedSize,
                checksum = prepared.plaintextChecksum,
                userId = context.userId,
                updatedAt = System.currentTimeMillis(),
            )
        }
        publish(persist = true)
        syncJob(context, taskId, "completed", delete = true)
        return true
    }

    private suspend fun handleFailure(taskId: String, failure: AppFailure, context: CloudContext) {
        when (failure.kind) {
            FailureKind.NETWORK -> {
                setStatus(taskId, UploadStatus.WAITING_FOR_NETWORK, failure.message)
                syncJob(context, taskId, "queued")
            }

            FailureKind.AUTH -> {
                setStatus(taskId, UploadStatus.WAITING_FOR_AUTH, failure.message)
                syncJob(context, taskId, "queued")
            }

            FailureKind.NOT_CONFIGURED -> {
                setStatus(taskId, UploadStatus.WAITING_FOR_CONFIG, failure.message)
                syncJob(context, taskId, "queued")
            }

            else -> {
                setFailed(taskId, failure.message)
                syncJob(context, taskId, "failed")
            }
        }
    }

    private suspend fun markBlocked(taskId: String, failure: AppFailure) {
        val status = when (failure.kind) {
            FailureKind.AUTH -> UploadStatus.WAITING_FOR_AUTH
            FailureKind.NOT_CONFIGURED -> UploadStatus.WAITING_FOR_CONFIG
            else -> UploadStatus.WAITING_FOR_NETWORK
        }
        setStatus(taskId, status, failure.message)
    }

    private suspend fun syncJob(
        context: CloudContext,
        taskId: String,
        status: String,
        delete: Boolean = false,
    ) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        runCatching {
            if (delete) {
                data.deleteUploadJob(context.config, context.accessToken, taskId)
            } else {
                data.upsertUploadJob(
                    config = context.config,
                    accessToken = context.accessToken,
                    jobId = taskId,
                    userId = context.userId,
                    mediaId = task.mediaId,
                    status = status,
                    retryCount = task.attempts,
                )
            }
        }
    }

    // ---------------------------------------------------------------- state helpers

    private fun setStatus(taskId: String, status: UploadStatus, message: String?) {
        updateTask(taskId) { current ->
            current.copy(
                status = status,
                progress = if (status == UploadStatus.QUEUED) 0f else current.progress,
                errorMessage = message,
                updatedAt = System.currentTimeMillis(),
            )
        }
        publish(persist = status.isFinished || status == UploadStatus.WAITING_FOR_NETWORK ||
            status == UploadStatus.WAITING_FOR_AUTH || status == UploadStatus.WAITING_FOR_CONFIG)
    }

    private fun setFailed(taskId: String, message: String) {
        updateTask(taskId) { current ->
            current.copy(
                status = UploadStatus.FAILED,
                attempts = current.attempts + 1,
                progress = 0f,
                errorMessage = message,
                updatedAt = System.currentTimeMillis(),
            )
        }
        publish(persist = true)
    }

    private fun updateProgress(taskId: String, progress: Float) {
        updateTask(taskId) { current ->
            current.copy(progress = progress.coerceIn(0f, 1f), updatedAt = System.currentTimeMillis())
        }
        publish(persist = false)
    }

    private fun updateTask(taskId: String, transform: (UploadTask) -> UploadTask) {
        _tasks.update { list ->
            list.map { task -> if (task.id == taskId) transform(task) else task }
        }
    }

    private fun publish(persist: Boolean = false) {
        _summary.value = buildSummary(_tasks.value)
        if (persist) persistChannel.trySend(_tasks.value)
    }

    private fun buildSummary(tasks: List<UploadTask>): UploadQueueSummary {
        val uploaded = tasks.filter { it.status == UploadStatus.UPLOADED }
        return UploadQueueSummary(
            pending = tasks.count {
                it.status == UploadStatus.QUEUED || it.status == UploadStatus.WAITING_FOR_NETWORK ||
                    it.status == UploadStatus.WAITING_FOR_CONFIG || it.status == UploadStatus.WAITING_FOR_AUTH
            },
            uploading = tasks.count { it.status.isRunning },
            uploaded = uploaded.size,
            failed = tasks.count { it.status == UploadStatus.FAILED },
            paused = tasks.count {
                it.status == UploadStatus.WAITING_FOR_NETWORK || it.status == UploadStatus.WAITING_FOR_AUTH ||
                    it.status == UploadStatus.WAITING_FOR_CONFIG
            },
            cancelled = tasks.count { it.status == UploadStatus.CANCELLED },
            totalBytes = tasks.sumOf { it.sizeBytes },
            uploadedBytes = uploaded.sumOf { it.sizeBytes },
        )
    }

    private fun UploadTask.recovered(): UploadTask = when (status) {
        UploadStatus.ENCRYPTING, UploadStatus.UPLOADING, UploadStatus.VERIFYING ->
            copy(status = UploadStatus.QUEUED, progress = 0f, updatedAt = System.currentTimeMillis())
        else -> copy(progress = if (status == UploadStatus.UPLOADED) 1f else 0f)
    }

    private fun isCancelled(taskId: String): Boolean =
        _tasks.value.firstOrNull { it.id == taskId }?.status == UploadStatus.CANCELLED

    private fun sourceExists(taskId: String): Boolean =
        _tasks.value.firstOrNull { it.id == taskId }?.let { File(it.filePath).exists() } ?: false

    private suspend fun awaitLoaded() {
        var spins = 0
        while (!loaded && spins < 100) {
            kotlinx.coroutines.delay(20)
            spins++
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val MAX_TASKS_PER_DRAIN = 20
    }
}
