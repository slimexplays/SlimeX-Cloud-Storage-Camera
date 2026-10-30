package com.slimex.cloudcamera.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.slimex.cloudcamera.cloud.SupabaseRestClient
import com.slimex.cloudcamera.core.network.ConnectivityObserver
import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.result.FailureKind
import com.slimex.cloudcamera.core.util.DispatchersProvider
import com.slimex.cloudcamera.data.local.UploadTaskEntity
import com.slimex.cloudcamera.data.local.toDomain
import com.slimex.cloudcamera.data.local.toEntity
import com.slimex.cloudcamera.domain.model.CloudConfig
import com.slimex.cloudcamera.domain.model.MediaKind
import com.slimex.cloudcamera.domain.model.UploadQueueSummary
import com.slimex.cloudcamera.domain.model.UploadStatus
import com.slimex.cloudcamera.domain.model.UploadTask
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import com.slimex.cloudcamera.domain.repository.SettingsRepository
import com.slimex.cloudcamera.domain.repository.UploadRepository
import com.slimex.cloudcamera.upload.UploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent upload queue + transfer engine.
 *
 * Jobs are written to DataStore so they survive process death; the actual transfer runs on
 * an application-scoped coroutine so the UI can show live progress. Failures are surfaced,
 * never swallowed, and network problems are retried through [UploadScheduler].
 */
@Singleton
class UploadRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cloudConfigRepository: CloudConfigRepository,
    private val settingsRepository: SettingsRepository,
    private val client: SupabaseRestClient,
    private val connectivity: ConnectivityObserver,
    private val json: Json,
    private val dispatchers: DispatchersProvider,
    private val scheduler: UploadScheduler,
) : UploadRepository {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val signal = Channel<String>(Channel.UNLIMITED)

    private val _tasks = MutableStateFlow<List<UploadTask>>(emptyList())
    override val tasks: StateFlow<List<UploadTask>> = _tasks.asStateFlow()

    override val summary: StateFlow<UploadQueueSummary> = _tasks
        .map { list ->
            UploadQueueSummary(
                pending = list.count { it.status.isActive && it.status != UploadStatus.UPLOADING },
                uploading = list.count { it.status == UploadStatus.UPLOADING },
                uploaded = list.count { it.status == UploadStatus.UPLOADED },
                failed = list.count { it.status == UploadStatus.FAILED },
                totalBytes = list.filter { !it.status.isFinished }.sumOf { it.sizeBytes },
                uploadedBytes = list.filter { it.status == UploadStatus.UPLOADED }.sumOf { it.sizeBytes },
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, UploadQueueSummary())

    private var processorJob: kotlinx.coroutines.Job? = null

    init {
        scope.launch {
            restore()
            val settings = runCatching { settingsRepository.current() }.getOrNull()
            if (settings == null || settings.autoUpload) {
                resumePending()
            }
        }
        // A freshly saved configuration unblocks everything that was waiting for setup.
        scope.launch {
            var previousProject: String? = null
            cloudConfigRepository.config.collect { config ->
                val project = config?.projectUrl
                if (project != null && project != previousProject) {
                    previousProject = project
                    resumePending()
                }
            }
        }
    }

    // ---------------------------------------------------------------- public API

    override suspend fun enqueue(
        filePath: String,
        kind: MediaKind,
        durationMillis: Long?,
        capturedAt: Long,
        startImmediately: Boolean,
    ): AppResult<String> = withContext(dispatchers.io) {
        val file = File(filePath)
        if (!file.exists()) {
            return@withContext AppResult.Failure(AppFailure.storage("The captured file could not be found."))
        }
        val task = UploadTask(
            id = UUID.randomUUID().toString(),
            filePath = file.absolutePath,
            fileName = file.name,
            kind = kind,
            sizeBytes = file.length(),
            mimeType = kind.mimeType,
            capturedAt = capturedAt,
            durationMillis = durationMillis,
        )
        _tasks.update { it + task }
        persist()
        val settings = runCatching { settingsRepository.current() }.getOrNull()
        if (startImmediately && (settings?.autoUpload ?: true)) {
            ensureProcessor()
            signal.trySend(task.id)
        }
        AppResult.Success(task.id)
    }

    override suspend fun retry(taskId: String) {
        update(taskId) {
            it.copy(status = UploadStatus.QUEUED, errorMessage = null, progress = 0f)
        }
        persist()
        ensureProcessor()
        signal.trySend(taskId)
    }

    override suspend fun cancel(taskId: String) {
        update(taskId) { it.copy(status = UploadStatus.CANCELLED, progress = 0f) }
        persist()
    }

    override suspend fun clearFinished() {
        _tasks.update { list -> list.filterNot { it.status.isFinished } }
        persist()
    }

    override suspend fun resumePending() {
        _tasks.update { list ->
            list.map { if (it.status == UploadStatus.UPLOADING) it.copy(status = UploadStatus.QUEUED) else it }
        }
        persist()
        ensureProcessor()
        _tasks.value.filter { it.status.isActive }.forEach { signal.trySend(it.id) }
    }

    // ---------------------------------------------------------------- engine

    private fun ensureProcessor() {
        if (processorJob?.isActive == true) return
        processorJob = scope.launch {
            for (taskId in signal) {
                runCatching { process(taskId) }
            }
        }
    }

    private suspend fun process(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (!task.status.isActive || task.status == UploadStatus.UPLOADING) return

        val config = cloudConfigRepository.current()
        if (config == null) {
            update(taskId) {
                it.copy(
                    status = UploadStatus.WAITING_FOR_CONFIG,
                    errorMessage = "Add your Supabase project in Settings to upload.",
                )
            }
            persist()
            return
        }

        val settings = settingsRepository.current()
        val network = connectivity.current()
        if (!network.online) {
            markWaiting(taskId, "Waiting for an internet connection.")
            scheduler.scheduleRetry(60)
            return
        }
        if (settings.wifiOnlyUpload && !network.wifi) {
            markWaiting(taskId, "Waiting for Wi-Fi (Wi-Fi only uploads are enabled).")
            scheduler.scheduleRetry(120)
            return
        }

        val file = File(task.filePath)
        if (!file.exists() || file.length() == 0L) {
            update(taskId) {
                it.copy(status = UploadStatus.FAILED, errorMessage = "The local file is missing or empty.")
            }
            persist()
            return
        }

        update(taskId) {
            it.copy(
                status = UploadStatus.UPLOADING,
                progress = 0f,
                attempts = it.attempts + 1,
                errorMessage = null,
            )
        }

        val remotePath = buildRemotePath(task, config)
        val result = client.uploadObject(
            config = config,
            objectPath = remotePath,
            file = file,
            contentType = task.mimeType,
        ) { progress -> publishProgress(taskId, progress) }

        when (result) {
            is AppResult.Success -> {
                val metadata = insertMetadata(config, task.copy(remotePath = remotePath), settings.deviceId())
                val metadataWarning = metadata.errorOrNull()?.let { failure ->
                    "Uploaded, but the media row could not be saved: ${failure.message}"
                }
                update(taskId) {
                    it.copy(
                        status = UploadStatus.UPLOADED,
                        progress = 1f,
                        remotePath = result.data,
                        errorMessage = metadataWarning,
                    )
                }
                persist()
            }

            is AppResult.Failure -> {
                val failure = result.error
                val transient = failure.kind == FailureKind.NETWORK
                update(taskId) {
                    it.copy(
                        status = if (transient) UploadStatus.WAITING_FOR_NETWORK else UploadStatus.FAILED,
                        errorMessage = failure.message,
                    )
                }
                persist()
                if (transient) {
                    scheduler.scheduleRetry(60)
                }
            }
        }
    }

    private suspend fun markWaiting(taskId: String, message: String) {
        update(taskId) { it.copy(status = UploadStatus.WAITING_FOR_NETWORK, errorMessage = message) }
        persist()
    }

    private suspend fun insertMetadata(config: CloudConfig, task: UploadTask, deviceId: String): AppResult<Unit> {
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "device_id" to JsonPrimitive(deviceId.ifBlank { "unknown-device" }),
            "kind" to JsonPrimitive(task.kind.value),
            "storage_path" to JsonPrimitive(task.remotePath ?: ""),
            "bucket" to JsonPrimitive(config.bucket),
            "file_name" to JsonPrimitive(task.fileName),
            "mime_type" to JsonPrimitive(task.mimeType),
            "size_bytes" to JsonPrimitive(task.sizeBytes),
            "captured_at" to JsonPrimitive(Instant.ofEpochMilli(task.capturedAt).toString()),
        )
        task.durationMillis?.let { duration -> fields["duration_ms"] = JsonPrimitive(duration) }
        val payload = JsonObject(fields).toString()
        return client.insertRow(config, payload)
    }

    // ---------------------------------------------------------------- helpers

    private fun update(taskId: String, transform: (UploadTask) -> UploadTask) {
        _tasks.update { list ->
            list.map { current ->
                if (current.id == taskId) {
                    transform(current).copy(updatedAt = System.currentTimeMillis())
                } else {
                    current
                }
            }
        }
    }

    private fun publishProgress(taskId: String, progress: Float) {
        _tasks.update { list ->
            list.map { current ->
                if (current.id == taskId && current.status == UploadStatus.UPLOADING) {
                    val clamped = progress.coerceIn(0f, 1f)
                    if (clamped - current.progress < 0.01f && clamped < 1f) current
                    else current.copy(progress = clamped)
                } else {
                    current
                }
            }
        }
    }

    private fun buildRemotePath(task: UploadTask, config: CloudConfig): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy/MM").withZone(ZoneOffset.UTC)
        val folder = formatter.format(Instant.ofEpochMilli(task.capturedAt))
        return "${task.kind.storageFolder}/$folder/${task.id}.${task.kind.extension}"
    }

    private suspend fun restore() {
        val raw = dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { it[QUEUE_KEY] }
            .first()
        val decoded = raw?.let { stored ->
            runCatching { json.decodeFromString<List<UploadTaskEntity>>(stored) }.getOrNull()
        } ?: emptyList()
        _tasks.value = decoded
            .map { it.toDomain() }
            .takeLast(MAX_PERSISTED_TASKS)
    }

    private suspend fun persist() {
        val snapshot = _tasks.value.takeLast(MAX_PERSISTED_TASKS)
        val encoded = runCatching { json.encodeToString(snapshot.map { it.toEntity() }) }.getOrNull() ?: return
        dataStore.edit { it[QUEUE_KEY] = encoded }
    }

    private companion object {
        val QUEUE_KEY = stringPreferencesKey("upload_queue_v1")
        const val MAX_PERSISTED_TASKS = 200
    }
}
