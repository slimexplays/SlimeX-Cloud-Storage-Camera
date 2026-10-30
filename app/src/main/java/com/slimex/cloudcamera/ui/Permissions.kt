package com.slimex.cloudcamera.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Runtime permissions the app can ever ask for. Nothing broader is declared. */
object AppPermissions {
    const val CAMERA = Manifest.permission.CAMERA
    const val MICROPHONE = Manifest.permission.RECORD_AUDIO
    const val NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

    /** Microphone is optional — video can be recorded without sound. */
    val onboarding: List<String>
        get() = buildList {
            add(CAMERA)
            add(MICROPHONE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(NOTIFICATIONS)
        }

    fun isNotificationPermissionRequired(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Small, dependency free permission helper: tracks granted state for a set of
 * permissions and re-reads it on every resume (the user may change it in Settings).
 */
class PermissionState internal constructor(
    private val granted: Map<String, Boolean>,
    private val launch: (List<String>) -> Unit,
) {
    fun isGranted(permission: String): Boolean = granted[permission] == true

    fun allGranted(permissions: List<String>): Boolean = permissions.all { isGranted(it) }

    fun request(vararg permissions: String) {
        launch(permissions.toList())
    }
}

@Composable
fun rememberPermissionState(permissions: List<String>): PermissionState {
    val context = LocalContext.current
    var granted by remember(permissions) {
        mutableStateOf(permissions.associateWith { context.hasPermission(it) })
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = granted + result
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, permissions) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = permissions.associateWith { context.hasPermission(it) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return remember(granted) {
        PermissionState(granted) { requested -> launcher.launch(requested.toTypedArray()) }
    }
}
