package com.slimex.cloudcamera.ui.screens.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slimex.cloudcamera.camera.CaptureMode
import com.slimex.cloudcamera.camera.FlashSetting
import com.slimex.cloudcamera.camera.LensFacing
import com.slimex.cloudcamera.domain.model.MediaKind
import com.slimex.cloudcamera.domain.model.PhotoQuality
import com.slimex.cloudcamera.domain.model.VideoQualityPreference
import com.slimex.cloudcamera.domain.repository.CloudConfigRepository
import com.slimex.cloudcamera.domain.repository.LocalMediaRepository
import com.slimex.cloudcamera.domain.repository.SettingsRepository
import com.slimex.cloudcamera.domain.repository.UploadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val localMediaRepository: LocalMediaRepository,
    private val uploadRepository: UploadRepository,
    private val settingsRepository: SettingsRepository,
    private val cloudConfigRepository: CloudConfigRepository,
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
        val photoCount: Int = 0,
        val videoCount: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _state.update {
                    it.copy(
                        photoQuality = settings.photoQuality,
                        videoQuality = settings.videoQuality,
                        recordAudio = settings.recordVideoAudio,
                    )
                }
            }
        }
        viewModelScope.launch {
            cloudConfigRepository.config.collect { config ->
                _state.update { it.copy(cloudConfigured = config != null) }
            }
        }
        viewModelScope.launch {
            localMediaRepository.media.collect { media ->
                _state.update {
                    it.copy(
                        photoCount = media.count { item -> item.kind == MediaKind.PHOTO },
                        videoCount = media.count { item -> item.kind == MediaKind.VIDEO },
                    )
                }
            }
        }
        viewModelScope.launch { runCatching { localMediaRepository.refresh() } }
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
            if (allowed) it.copy(lensFacing = next) else it
        }
    }

    fun cycleFlash() {
        _state.update { it.copy(flash = it.flash.next()) }
    }

    fun onCameraBound(hasFlash: Boolean, hasFront: Boolean, hasBack: Boolean) {
        _state.update {
            it.copy(hasFlash = hasFlash, hasFrontCamera = hasFront, hasBackCamera = hasBack)
        }
    }

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
            return
        }
        if (file != null) {
            onCaptureSaved(file, MediaKind.VIDEO, durationMillis)
        }
    }

    suspend fun newOutputFile(kind: MediaKind): File? =
        localMediaRepository.createOutputFile(kind).getOrNull()

    /**
     * Persists state for a finished capture and enqueues the upload when a cloud project
     * is configured. Without a configuration the file simply stays on the device.
     */
    fun onCaptureSaved(file: File, kind: MediaKind, durationMillis: Long?) {
        _state.update {
            it.copy(
                busy = false,
                lastCapturePath = file.absolutePath,
                lastCaptureKind = kind,
            )
        }
        viewModelScope.launch {
            runCatching { localMediaRepository.refresh() }
            if (cloudConfigRepository.current() == null) {
                _state.update {
                    it.copy(message = "Saved on device · connect Supabase to upload")
                }
                return@launch
            }
            val result = uploadRepository.enqueue(
                filePath = file.absolutePath,
                kind = kind,
                durationMillis = durationMillis,
                capturedAt = System.currentTimeMillis(),
            )
            val text = when {
                result.isSuccess && kind == MediaKind.PHOTO -> "Photo saved · queued for upload"
                result.isSuccess -> "Video saved · queued for upload"
                else -> "Saved on device · ${result.errorOrNull()?.message.orEmpty()}"
            }
            _state.update { it.copy(message = text) }
        }
    }
}
