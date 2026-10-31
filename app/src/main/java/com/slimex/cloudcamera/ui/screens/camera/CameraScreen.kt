package com.slimex.cloudcamera.ui.screens.camera

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.slimex.cloudcamera.camera.CameraController
import com.slimex.cloudcamera.camera.CaptureMode
import com.slimex.cloudcamera.camera.FlashSetting
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.core.util.Formatters
import com.slimex.cloudcamera.domain.model.MediaKind
import com.slimex.cloudcamera.ui.AppPermissions
import com.slimex.cloudcamera.ui.components.BrandMark
import com.slimex.cloudcamera.ui.components.CircularActionButton
import com.slimex.cloudcamera.ui.components.SlimeXButton
import com.slimex.cloudcamera.ui.components.SlimeXButtonStyle
import com.slimex.cloudcamera.ui.rememberPermissionState
import com.slimex.cloudcamera.ui.theme.ErrorRed
import com.slimex.cloudcamera.ui.theme.PureBlack
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Full-screen, pure-black camera. No decorative purple surfaces behind the preview:
 * only the live CameraX surface plus minimal overlay controls.
 */
@Composable
fun CameraScreen(
    onBack: () -> Unit,
    onOpenGallery: () -> Unit,
    viewModel: CameraViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissions = rememberPermissionState(listOf(AppPermissions.CAMERA, AppPermissions.MICROPHONE))
    val cameraGranted = permissions.isGranted(AppPermissions.CAMERA)
    val scope = rememberCoroutineScope()

    val controller = remember(lifecycleOwner) { CameraController(context, lifecycleOwner) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(controller) {
        controller.listener = object : CameraController.Listener {
            override fun onCameraBound(hasFlash: Boolean, hasFrontCamera: Boolean, hasBackCamera: Boolean) {
                viewModel.onCameraBound(hasFlash, hasFrontCamera, hasBackCamera)
            }

            override fun onRecordingStarted() = viewModel.onRecordingStarted()

            override fun onRecordingProgress(durationMillis: Long) =
                viewModel.onRecordingProgress(durationMillis)

            override fun onRecordingFinalized(file: File?, durationMillis: Long, error: String?) =
                viewModel.onRecordingFinalized(file, durationMillis, error)

            override fun onCameraError(message: String) = viewModel.onError(message)
        }
        onDispose {
            controller.listener = null
            controller.unbind()
        }
    }

    LaunchedEffect(
        previewView,
        state.mode,
        state.lensFacing,
        state.photoQuality,
        state.videoQuality,
        cameraGranted,
    ) {
        val view = previewView
        if (cameraGranted && view != null) {
            when (val result = controller.bind(
                previewView = view,
                mode = state.mode,
                lensFacing = state.lensFacing,
                photoQuality = state.photoQuality,
                videoQuality = state.videoQuality,
            )) {
                is AppResult.Failure -> viewModel.onError(result.error.message)
                is AppResult.Success -> viewModel.setZoom(
                    fraction = 0f,
                    ratioLabel = "1.0x",
                    maxRatio = controller.maxZoomRatio(),
                )
            }
        }
    }

    LaunchedEffect(state.mode, state.flash, state.hasFlash) {
        if (state.mode == CaptureMode.VIDEO) {
            controller.setTorch(state.hasFlash && state.flash != FlashSetting.OFF)
        } else {
            controller.stopTorch()
        }
    }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(2600)
            viewModel.consumeMessage()
        }
    }

    val capturePhoto: () -> Unit = {
        if (state.captureBlocked) {
            viewModel.onError("Offline: turn off zero local storage mode to capture, or reconnect.")
        } else if (!state.busy && !state.isRecording) {
            scope.launch {
                viewModel.setBusy(true)
                val file = try {
                    viewModel.newOutputFile(MediaKind.PHOTO)
                } catch (t: Throwable) {
                    viewModel.onError("Could not create a file for the photo.")
                    return@launch
                }
                when (val result = controller.capturePhoto(file, state.flash)) {
                    is AppResult.Success ->
                        viewModel.onCaptureSaved(result.data, MediaKind.PHOTO, null)
                    is AppResult.Failure -> viewModel.onError(result.error.message)
                }
            }
        }
    }

    val toggleRecording: () -> Unit = {
        if (state.captureBlocked) {
            viewModel.onError("Offline: turn off zero local storage mode to capture, or reconnect.")
        } else if (state.isRecording) {
            controller.stopRecording()
        } else if (!state.busy) {
            val wantsAudio = state.recordAudio
            if (wantsAudio && !permissions.isGranted(AppPermissions.MICROPHONE)) {
                // Ask contextually, then start muted for this take.
                permissions.request(AppPermissions.MICROPHONE)
            }
            scope.launch {
                val file = try {
                    viewModel.newOutputFile(MediaKind.VIDEO)
                } catch (t: Throwable) {
                    viewModel.onError("Could not create a file for the video.")
                    return@launch
                }
                val withAudio = wantsAudio && controller.hasAudioPermission
                when (val result = controller.startRecording(file, withAudio)) {
                    is AppResult.Failure -> viewModel.onError(result.error.message)
                    is AppResult.Success -> Unit
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack),
    ) {
        if (!cameraGranted) {
            CameraPermissionPanel(
                onRequest = { permissions.request(AppPermissions.CAMERA) },
                onBack = onBack,
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                update = { view -> previewView = view },
                modifier = Modifier.fillMaxSize(),
            )

            // Tap-to-focus / tap-to-meter with a short-lived focus ring.
            var focusPoint by remember { mutableStateOf<Offset?>(null) }
            var previewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            LaunchedEffect(state.focusToken) {
                if (focusPoint != null) {
                    delay(700)
                    focusPoint = null
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { previewSize = it }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            if (previewSize.width > 0 && previewSize.height > 0) {
                                controller.focusAt(offset.x, offset.y)
                                focusPoint = offset
                                viewModel.onFocusRequested()
                            }
                        }
                    },
            )

            focusPoint?.let { point ->
                val density = LocalDensity.current
                Box(
                    modifier = Modifier
                        .offset(
                            x = with(density) { point.x.toDp() } - 32.dp,
                            y = with(density) { point.y.toDp() } - 32.dp,
                        )
                        .size(64.dp)
                        .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape),
                )
            }

            // Top scrim keeps the controls legible over bright scenes without hiding the frame.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent),
                        ),
                    ),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularActionButton(
                    onClick = onBack,
                    icon = Icons.Rounded.Close,
                    contentDescription = "Close camera",
                )
                Spacer(modifier = Modifier.weight(1f))
                if (state.hasFlash) {
                    CircularActionButton(
                        onClick = viewModel::cycleFlash,
                        icon = when (state.flash) {
                            FlashSetting.AUTO -> Icons.Rounded.FlashAuto
                            FlashSetting.ON -> Icons.Rounded.FlashOn
                            FlashSetting.OFF -> Icons.Rounded.FlashOff
                        },
                        contentDescription = "Flash ${state.flash.label}",
                        tint = if (state.flash == FlashSetting.OFF) TextSecondary else Color.White,
                    )
                }
            }

            if (state.isRecording) {
                RecordingIndicator(
                    durationMillis = state.recordingMillis,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 62.dp),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                        ),
                    )
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp, top = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state.message != null) {
                    MessagePill(text = state.message.orEmpty())
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (state.maxZoom > 1.05f) {
                    ZoomControl(
                        value = state.zoom,
                        maxZoomRatio = state.maxZoom,
                        onChange = { fraction ->
                            controller.setLinearZoom(fraction)
                            val ratio = 1f + fraction * (state.maxZoom - 1f).coerceAtLeast(0f)
                            viewModel.setZoom(
                                fraction = fraction,
                                ratioLabel = String.format(java.util.Locale.US, "%.1fx", ratio),
                                maxRatio = state.maxZoom,
                            )
                        },
                        modifier = Modifier
                            .width(230.dp)
                            .padding(bottom = 10.dp),
                    )
                }

                CameraModeSelector(
                    selectedIndex = if (state.mode == CaptureMode.PHOTO) 0 else 1,
                    onSelected = { index ->
                        viewModel.setMode(if (index == 0) CaptureMode.PHOTO else CaptureMode.VIDEO)
                    },
                    modifier = Modifier.width(210.dp),
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    GalleryShortcut(
                        lastCapturePath = state.lastCapturePath,
                        lastCaptureKind = state.lastCaptureKind,
                        onClick = onOpenGallery,
                    )

                    ShutterButton(
                        isRecording = state.isRecording,
                        busy = state.busy,
                        enabled = !state.captureBlocked,
                        isVideoMode = state.mode == CaptureMode.VIDEO,
                        onClick = {
                            if (state.mode == CaptureMode.PHOTO) capturePhoto() else toggleRecording()
                        },
                    )

                    CircularActionButton(
                        onClick = viewModel::switchLens,
                        icon = Icons.Rounded.Cameraswitch,
                        contentDescription = "Switch camera",
                        enabled = !state.isRecording && (state.hasFrontCamera || state.hasBackCamera),
                        size = 52.dp,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = state.statusLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.captureBlocked) ErrorRed else TextSecondary,
                    textAlign = TextAlign.Center,
                )
                if (state.pendingPhotos + state.pendingVideos > 0 || state.queuedUploads > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${state.pendingPhotos + state.pendingVideos} waiting locally · " +
                            "${state.queuedUploads} in the upload queue",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
            }
        }

        if (state.error != null) {
            ErrorBanner(
                message = state.error.orEmpty(),
                onDismiss = viewModel::consumeError,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 72.dp, start = 16.dp, end = 16.dp),
            )
            LaunchedEffect(state.error) {
                delay(4000)
                viewModel.consumeError()
            }
        }
    }
}

// --------------------------------------------------------------------------- pieces

@Composable
private fun CameraPermissionPanel(onRequest: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(size = 76.dp)
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Camera access needed",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "SlimeX needs the camera to show a live preview and capture photos or video. " +
                "Nothing is recorded until you press the shutter.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(26.dp))
        SlimeXButton(
            text = "Allow camera",
            onClick = onRequest,
            icon = Icons.Rounded.PhotoCamera,
        )
        Spacer(modifier = Modifier.height(10.dp))
        SlimeXButton(
            text = "Back",
            onClick = onBack,
            style = SlimeXButtonStyle.GHOST,
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "If no prompt appears, enable Camera for SlimeX in the system app settings.",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ShutterButton(
    isRecording: Boolean,
    busy: Boolean,
    enabled: Boolean,
    isVideoMode: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(78.dp)
            .clip(CircleShape)
            .border(3.dp, Color.White.copy(alpha = 0.9f), CircleShape)
            .clickable(enabled = !busy && enabled, onClick = onClick)
            .padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "…",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(if (isRecording) RoundedCornerShape(9.dp) else CircleShape)
                    .background(if (isRecording) ErrorRed else Color.White),
            )
            if (isVideoMode && !isRecording) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(ErrorRed),
                )
            }
        }
    }
}

@Composable
private fun GalleryShortcut(
    lastCapturePath: String?,
    lastCaptureKind: MediaKind?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val path = lastCapturePath?.takeIf { File(it).exists() }
        if (path != null && lastCaptureKind == MediaKind.PHOTO) {
            AsyncImage(
                model = File(path),
                contentDescription = "Open gallery",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = if (path != null) Icons.Rounded.Movie else Icons.Rounded.PhotoLibrary,
                contentDescription = "Open gallery",
                tint = TextPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** White-on-black mode switch — the camera screen deliberately uses no purple surfaces. */
@Composable
private fun CameraModeSelector(
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        listOf("Photo", "Video").forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable { onSelected(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) Color.Black else Color.White,
                )
            }
        }
    }
}

@Composable
private fun ZoomControl(
    value: Float,
    maxZoomRatio: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "1x",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.9f),
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = String.format(java.util.Locale.US, "%.1fx", maxZoomRatio),
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
    }
}

@Composable
private fun RecordingIndicator(durationMillis: Long, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(ErrorRed),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = Formatters.duration(durationMillis),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}

@Composable
private fun MessagePill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary,
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.85f))
            .border(1.dp, ErrorRed.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
        )
    }
}
