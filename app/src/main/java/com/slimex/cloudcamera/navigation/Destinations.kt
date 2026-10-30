package com.slimex.cloudcamera.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Storage
import com.slimex.cloudcamera.ui.components.SlimeXBottomItem

/**
 * Single flat navigation graph shared by the entire application.
 * Parts 2 and 3 extend this list — they never introduce a second navigator.
 */
object Destinations {
    const val ONBOARDING = "onboarding"

    const val HOME = "home"
    const val CAMERA = "camera"
    const val GALLERY = "gallery"
    const val UPLOADS = "uploads"
    const val STORAGE = "storage"
    const val SETTINGS = "settings"

    const val CLOUD_SETUP = "settings/cloud"
    const val CAMERA_PREFERENCES = "settings/camera"
    const val ABOUT = "settings/about"
}

/** Tabs rendered in the bottom bar (Camera is intentionally full-screen). */
val bottomTabs: List<SlimeXBottomItem> = listOf(
    SlimeXBottomItem(Destinations.HOME, "Home", Icons.Rounded.Home),
    SlimeXBottomItem(Destinations.GALLERY, "Cloud", Icons.Rounded.Cloud),
    SlimeXBottomItem(Destinations.CAMERA, "Camera", Icons.Rounded.PhotoCamera),
    SlimeXBottomItem(Destinations.UPLOADS, "Uploads", Icons.Rounded.CloudUpload),
    SlimeXBottomItem(Destinations.STORAGE, "Storage", Icons.Rounded.Storage),
)

/** Routes that draw the bottom navigation bar. */
val bottomBarRoutes: Set<String> = setOf(
    Destinations.HOME,
    Destinations.GALLERY,
    Destinations.UPLOADS,
    Destinations.STORAGE,
    Destinations.SETTINGS,
)
