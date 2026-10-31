package com.slimex.cloudcamera.core.util

import com.slimex.cloudcamera.core.constants.AppConstants
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the object paths used inside the private bucket.
 *
 * Required layout: `{user_id}/{year}/{month}/{unique_file_id}`.
 * The year/month come from the capture timestamp (UTC) so a library always sorts naturally.
 */
object StoragePaths {

    fun objectKey(userId: String, capturedAtMillis: Long, uniqueFileId: String): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = capturedAtMillis
        }
        val year = calendar.get(Calendar.YEAR)
        val month = String.format(Locale.US, "%02d", calendar.get(Calendar.MONTH) + 1)
        val safeUser = userId.trim().ifBlank { "unknown" }
        return "$safeUser/$year/$month/$uniqueFileId.${AppConstants.OBJECT_EXTENSION}"
    }

    fun thumbnailKey(objectKey: String): String =
        if (objectKey.endsWith(".${AppConstants.OBJECT_EXTENSION}")) {
            objectKey.removeSuffix(".${AppConstants.OBJECT_EXTENSION}") + ".thumb.${AppConstants.OBJECT_EXTENSION}"
        } else {
            "$objectKey.thumb.${AppConstants.OBJECT_EXTENSION}"
        }

    /** Folder prefix a user's objects live under (used for listing/deleting). */
    fun userPrefix(userId: String): String = userId.trim().trimEnd('/') + "/"

    fun uniqueId(): String = java.util.UUID.randomUUID().toString()
}
