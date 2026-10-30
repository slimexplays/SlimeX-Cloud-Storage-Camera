package com.slimex.cloudcamera.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.PhotoQuality
import com.slimex.cloudcamera.domain.model.VideoQualityPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin wrapper around CameraX. It owns nothing but the use cases: the ViewModel keeps the
 * UI state, this class keeps the hardware honest, and every failure is reported back
 * instead of crashing the process.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {

    interface Listener {
        fun onCameraBound(hasFlash: Boolean, hasFrontCamera: Boolean, hasBackCamera: Boolean)
        fun onRecordingStarted()
        fun onRecordingProgress(durationMillis: Long)
        fun onRecordingFinalized(file: File?, durationMillis: Long, error: String?)
        fun onCameraError(message: String)
    }

    var listener: Listener? = null

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var recordingStartNanos: Long = 0L

    var isRecording: Boolean = false
        private set

    val hasAudioPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * (Re)binds the requested use cases. Safe to call on mode / lens / quality changes.
     */
    suspend fun bind(
        previewView: PreviewView,
        mode: CaptureMode,
        lensFacing: Int,
        photoQuality: PhotoQuality,
        videoQuality: VideoQualityPreference,
    ): AppResult<Unit> = withContext(Dispatchers.Main.immediate) {
        try {
            val provider = obtainProvider()

            val requested = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            val hasRequested = runCatching { provider.hasCamera(requested) }.getOrDefault(false)
            val fallbackLens = if (lensFacing == LensFacing.BACK) LensFacing.FRONT else LensFacing.BACK
            val fallback = CameraSelector.Builder().requireLensFacing(fallbackLens).build()
            val hasFallback = runCatching { provider.hasCamera(fallback) }.getOrDefault(false)

            val selector = when {
                hasRequested -> requested
                hasFallback -> fallback
                else -> return@withContext AppResult.Failure(
                    AppFailure("No camera is available on this device."),
                )
            }

            provider.unbindAll()

            val newPreview = Preview.Builder()
                .setTargetRotation(currentRotation(previewView))
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            preview = newPreview

            val boundCamera = when (mode) {
                CaptureMode.PHOTO -> {
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(
                            if (photoQuality == PhotoQuality.HIGH) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                            else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
                        )
                        .setTargetRotation(currentRotation(previewView))
                        .build()
                    imageCapture = capture
                    videoCapture = null
                    provider.bindToLifecycle(lifecycleOwner, selector, newPreview, capture)
                }

                CaptureMode.VIDEO -> {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(qualitySelector(videoQuality))
                        .setExecutor(mainExecutor)
                        .build()
                    val capture = VideoCapture.withOutput(recorder)
                    videoCapture = capture
                    imageCapture = null
                    provider.bindToLifecycle(lifecycleOwner, selector, newPreview, capture)
                }
            }

            val hasFlash = runCatching { boundCamera.cameraInfo.hasFlashUnit() }.getOrDefault(false)
            listener?.onCameraBound(
                hasFlash = hasFlash,
                hasFrontCamera = runCatching {
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                }.getOrDefault(false),
                hasBackCamera = runCatching {
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                }.getOrDefault(false),
            )
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            val message = t.message ?: "The camera could not be started."
            listener?.onCameraError(message)
            AppResult.Failure(AppFailure("Camera unavailable: $message", cause = t))
        }
    }

    suspend fun capturePhoto(outputFile: File, flash: FlashSetting): AppResult<File> {
        val capture = imageCapture
            ?: return AppResult.Failure(AppFailure("The camera is not ready yet."))
        return suspendCancellableCoroutine { continuation ->
            val options = ImageCapture.OutputFileOptions.Builder(outputFile).build()
            // A device may reject a flash mode; the capture still proceeds with the previous one.
            runCatching { capture.flashMode = flash.id }
            capture.takePicture(
                options,
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (continuation.isActive) continuation.resume(AppResult.Success(outputFile))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (continuation.isActive) {
                            continuation.resume(
                                AppResult.Failure(
                                    AppFailure(
                                        message = exception.message ?: "Photo capture failed.",
                                        cause = exception,
                                    ),
                                ),
                            )
                        }
                    }
                },
            )
        }
    }

    fun startRecording(outputFile: File, withAudio: Boolean): AppResult<Unit> {
        val video = videoCapture
            ?: return AppResult.Failure(AppFailure("The camera is not ready yet."))
        if (activeRecording != null) {
            return AppResult.Failure(AppFailure("A recording is already in progress."))
        }
        return try {
            val pending = video.output.prepareRecording(context, FileOutputOptions.Builder(outputFile).build())
            val configured = if (withAudio && hasAudioPermission) {
                runCatching { pending.withAudioEnabled() }.getOrDefault(pending)
            } else {
                pending
            }
            recordingStartNanos = System.nanoTime()
            activeRecording = configured.start(mainExecutor) { event -> handleRecordEvent(event, outputFile) }
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            activeRecording = null
            AppResult.Failure(AppFailure(t.message ?: "Recording could not start.", cause = t))
        }
    }

    fun stopRecording() {
        runCatching { activeRecording?.stop() }
    }

    fun unbind() {
        runCatching { activeRecording?.stop() }
        activeRecording = null
        isRecording = false
        runCatching { cameraProvider?.unbindAll() }
        imageCapture = null
        videoCapture = null
        preview = null
    }

    private fun handleRecordEvent(event: VideoRecordEvent, file: File) {
        when (event) {
            is VideoRecordEvent.Start -> {
                isRecording = true
                listener?.onRecordingStarted()
            }

            is VideoRecordEvent.Status -> {
                val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                listener?.onRecordingProgress(durationMs)
            }

            is VideoRecordEvent.Finalize -> {
                isRecording = false
                activeRecording = null
                val durationMs = if (recordingStartNanos > 0L) {
                    (System.nanoTime() - recordingStartNanos) / 1_000_000L
                } else {
                    event.recordingStats.recordedDurationNanos / 1_000_000L
                }
                val error = if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                    describeRecordError(event.error)
                } else {
                    null
                }
                val savedFile = if (error == null && file.exists() && file.length() > 0) file else null
                listener?.onRecordingFinalized(savedFile, durationMs, error)
            }
        }
    }

    private fun describeRecordError(code: Int): String = when (code) {
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "Not enough storage to save the video."
        VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "The video reached the size limit."
        VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED -> "The recording was cancelled."
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "The camera was closed during recording."
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "No video data was written."
        VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "The video encoder failed."
        else -> "Recording failed (code $code)."
    }

    private suspend fun obtainProvider(): ProcessCameraProvider {
        cameraProvider?.let { return it }
        val provider = suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        continuation.resume(future.get())
                    } catch (t: Throwable) {
                        continuation.resumeWithException(t)
                    }
                },
                mainExecutor,
            )
        }
        cameraProvider = provider
        return provider
    }

    private fun currentRotation(previewView: PreviewView): Int =
        previewView.display?.rotation ?: Surface.ROTATION_0

    private fun qualitySelector(preference: VideoQualityPreference): QualitySelector = when (preference) {
        VideoQualityPreference.AUTO -> QualitySelector.fromOrderedList(
            listOf(Quality.FHD, Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
        )

        VideoQualityPreference.FHD_1080 -> QualitySelector.from(
            Quality.FHD,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
        )

        VideoQualityPreference.HD_720 -> QualitySelector.from(
            Quality.HD,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
        )

        VideoQualityPreference.HIGHEST -> QualitySelector.fromOrderedList(
            listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
        )
    }
}
