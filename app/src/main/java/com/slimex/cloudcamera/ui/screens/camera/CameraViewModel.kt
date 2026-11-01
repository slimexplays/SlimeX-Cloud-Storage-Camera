package com.slimex.cloudcamera.ui.screens.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slimex.cloudcamera.camera.CaptureMode
import com.slimex.cloudcamera.camera.FlashSetting
import com.slimex.cloudcamera.camera.LensFacing
import com.slimex.cloudcamera.core.network.ConnectivityObserver
import com.slimex.cloudcamera.core.util.Formatters
import com.slimex.cloudcamera.domain.model.AppSettings
import com.slimex.cloudcamera.domain.model.MediaKind
import com.slimex.cloudcamera.domain.model.PhotoQuality
import com.slimex.cloudcamera.domain.model.UploadStatus
import com.slimex.cloudcamera.domain.model.VideoQualityPreference
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import com.slimex.cloudcamera.domain.repository.LocalMediaRepository
import com.slimex.cloudcamera.domain.repository.SettingsRepository
import com.slimex.cloudcamera.domain.repository.UploadRepository
import com.slimex.cloudcamera.storage.StagingStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val staging: StagingStore,
    private val localMediaRepository: LocalMediaRepository,
    private val uploadRepository: UploadRepository,
    private val settingsRepository: SettingsRepository,
    private val cloudConfigRepository: CloudConfigRepository,
    private val connectivity: ConnectivityObserver,
) : ViewModel() {

    data class State(
        val mode: CaptureMode = CaptureMode.PHOTO,
        val lensFacing: Int = LensFacing.BACK,
        val flash: FlashSetting = FlashSetting.AUTO,
        val hasFlash: Boolean = true,
        val hasFrontCamera: Boolean = true,
        val hasBackCamera: Boolean = true,
        val isRecording: Boolean = false,
        val recordingMillis: Long = 0L,
        val busy: Boolean = false,
        val lastCapturePath: String? = null,
        val lastCaptureKind: MediaKind? = null,
        val photoQuality: PhotoQuality = PhotoQuality.BALANCED,
        val videoQuality: VideoQualityPreference = VideoQualityPreference.AUTO,
        val recordAudio: Boolean = true,
        val cloudConfigured: Boolean = false,
        val message: String? = null,
        val error: String? = null,
        val pendingPhotos: Int = 0,
        val pendingVideos: Int = 0,
        val queuedUploads: Int = 0,
        val zoom: Float = 0f,
        val zoomRatioLabel: String = "1.0x",
        val maxZoom: Float = 1f,
        val strictZeroLocalStorage: Boolean = false,
        val online: Boolean = true,
        val focusToken: Int = 0,
    ) {
        /** In strict zero-local-storage mode nothing may be captured while offline. */
        val captureBlocked: Boolean
            get() = strictZeroLocalStorage && !online && !isRecording

        val statusLine: String
            get() = when {
                isRecording -> "Recording · tap the square to stop"
                captureBlocked -> "Offline · zero local storage mode is on"
                mode == CaptureMode.VIDEO -> "Tap the circle to start recording"
                else -> "Tap the shutter to capture a JPEG"
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings -> applySettings(settings) }
        }
        viewModelScope.launch {
            cloudConfigRepository.config.collect { config ->
                _state.update { it.copy(cloudConfigured = config != null) }
            }
        }
        viewModelScope.launch {
            localMediaRepository.pending.collect { media ->
                _state.update {
                    it.copy(
                        pendingPhotos = media.count { item -> item.kind == MediaKind.PHOTO },
                        pendingVideos = media.count { item -> item.kind == MediaKind.VIDEO },
                    )
                }
            }
        }
        viewModelScope.launch {
            uploadRepository.tasks.collect { tasks ->
                _state.update {
                    it.copy(
                        queuedUploads = tasks.count { task ->
                            task.status != UploadStatus.UPLOADED && task.status != UploadStatus.CANCELLED
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            connectivity.status.collectLatest { status ->
                _state.update { it.copy(online = status.online) }
            }
        }
        viewModelScope.launch { runCatching { localMediaRepository.refresh() } }
    }

    private fun applySettings(settings: AppSettings) {
        _state.update {
            it.copy(
                photoQuality = settings.photoQuality,
                videoQuality = settings.videoQuality,
                recordAudio = settings.recordVideoAudio,
                strictZeroLocalStorage = settings.strictZeroLocalStorage,
            )
        }
    }

    fun toggleMode() {
        if (_state.value.isRecording) return
        _state.update {
            it.copy(mode = if (it.mode == CaptureMode.PHOTO) CaptureMode.VIDEO else CaptureMode.PHOTO)
        }
    }

    fun setMode(mode: CaptureMode) {
        if (_state.value.isRecording) return
        _state.update { it.copy(mode = mode) }
    }

    fun switchLens() {
        if (_state.value.isRecording) return
        _state.update {
            val next = if (it.lensFacing == LensFacing.BACK) LensFacing.FRONT else LensFacing.BACK
            val allowed = when (next) {
                LensFacing.FRONT -> it.hasFrontCamera
                else -> it.hasBackCamera
            }
            if (allowed) it.copy(lensFacing = next, zoom = 0f) else it
        }
    }

    fun cycleFlash() {
        _state.update { it.copy(flash = it.flash.next()) }
    }

    fun onCameraBound(hasFlash: Boolean, hasFront: Boolean, hasBack: Boolean) {
        _state.update {
            it.copy(
                hasFlash = hasFlash,
                hasFrontCamera = hasFront,
                hasBackCamera = hasBack,
                flash = if (hasFlash) it.flash else FlashSetting.OFF,
            )
        }
    }

    fun setZoom(fraction: Float, ratioLabel: String, maxRatio: Float) {
        _state.update {
            it.copy(zoom = fraction.coerceIn(0f, 1f), zoomRatioLabel = ratioLabel, maxZoom = maxRatio)
        }
    }

    /** Bumped on every tap-to-focus so the screen can animate a focus ring. */
    fun onFocusRequested() = _state.update { it.copy(focusToken = it.focusToken + 1) }

    fun setBusy(busy: Boolean) = _state.update { it.copy(busy = busy) }

    fun onError(message: String) {
        _state.update { it.copy(error = message, busy = false) }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun onRecordingStarted() {
        _state.update { it.copy(isRecording = true, recordingMillis = 0L, busy = false) }
    }

    fun onRecordingProgress(durationMillis: Long) {
        _state.update { it.copy(recordingMillis = durationMillis) }
    }

    fun onRecordingFinalized(file: File?, durationMillis: Long, error: String?) {
        _state.update { it.copy(isRecording = false, recordingMillis = 0L, busy = false) }
        if (error != null) {
            _state.update { it.copy(error = error) }
            if (file != null && file.exists() && file.length() == 0L) runCatching { file.delete() }
            return
        }
        if (file != null) onCaptureSaved(file, MediaKind.VIDEO, durationMillis)
    }

    fun newOutputFile(kind: MediaKind): File = staging.newCaptureFile(kind)

    /**
     * Reserves a private file for the next capture.
     *
     * Refuses when the device is nearly full (a 4K video can otherwise fill the volume and take
     * the app data with it) and reports the problem through [State.error] instead of throwing.
     */
    fun requestOutputFile(kind: MediaKind): File? {
        val free = staging.freeBytes()
        if (free < MIN_FREE_BYTES) {
            _state.update {
                it.copy(
                    error = "Only ${Formatters.bytes(free)} free. Free up space before capturing so " +
                        "nothing is lost.",
                )
            }
            return null
        }
        return runCatching { staging.newCaptureFile(kind) }.getOrNull()?.also { file ->
            if (file.parentFile?.let { dir -> !dir.exists() || !dir.canWrite() } == true) {
                _state.update { it.copy(error = "Could not create a private file for this capture.") }
            }
        }
    }

    /**
     * Registers a finished capture in the encrypted upload queue.
     *
     * The file never leaves app-private storage until the cloud copy is verified; when the
     * project is not configured or the user is signed out the job simply waits in the queue.
     */
    fun onCaptureSaved(file: File, kind: MediaKind, durationMillis: Long?) {
        _state.update {
            it.copy(busy = false, lastCapturePath = file.absolutePath, lastCaptureKind = kind)
        }
        if (!file.exists() || file.length() == 0L) {
            // Corrupt/aborted capture: drop the empty file and tell the user instead of queueing it.
            runCatching { file.delete() }
            _state.update {
                it.copy(
                    busy = false,
                    error = "The capture was empty or could not be written. Please try again.",
                )
            }
            return
        }
        viewModelScope.launch {
            runCatching { localMediaRepository.refresh() }
            val settings = settingsRepository.current()
            val configured = cloudConfigRepository.current() != null

            val result = uploadRepository.enqueue(
                filePath = file.absolutePath,
                kind = kind,
                durationMillis = durationMillis,
                capturedAt = System.currentTimeMillis(),
                startImmediately = settings.autoUpload && configured,
            )

            val noun = if (kind == MediaKind.PHOTO) "Photo" else "Video"
            val text = when {
                result.isFailure -> "$noun saved privately · ${result.errorOrNull()?.message.orEmpty()}"
                !configured -> "$noun encrypted · set up Supabase to upload"
                !settings.autoUpload -> "$noun encrypted · uploads are paused in Settings"
                else -> "$noun encrypted · upload queued"
            }
            _state.update { it.copy(message = text) }
        }
    }

    private companion object {
        /** Never start a capture with less than this much room left on the volume. */
        const val MIN_FREE_BYTES = 250L * 1024 * 1024
    }
}
