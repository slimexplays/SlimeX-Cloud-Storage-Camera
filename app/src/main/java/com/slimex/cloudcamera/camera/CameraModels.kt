package com.slimex.cloudcamera.camera

import androidx.camera.core.CameraSelector

enum class CaptureMode { PHOTO, VIDEO }

/** Flash modes exposed to the UI, mapped onto CameraX constants. */
enum class FlashSetting(val id: Int, val label: String) {
    AUTO(androidx.camera.core.ImageCapture.FLASH_MODE_AUTO, "Auto"),
    ON(androidx.camera.core.ImageCapture.FLASH_MODE_ON, "On"),
    OFF(androidx.camera.core.ImageCapture.FLASH_MODE_OFF, "Off");

    fun next(): FlashSetting = when (this) {
        AUTO -> ON
        ON -> OFF
        OFF -> AUTO
    }

    companion object {
        fun fromId(id: Int): FlashSetting = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

object LensFacing {
    const val BACK: Int = CameraSelector.LENS_FACING_BACK
    const val FRONT: Int = CameraSelector.LENS_FACING_FRONT
}
