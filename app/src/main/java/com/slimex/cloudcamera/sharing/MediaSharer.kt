package com.slimex.cloudcamera.sharing

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.slimex.cloudcamera.core.result.AppFailure
import com.slimex.cloudcamera.core.result.AppResult
import com.slimex.cloudcamera.domain.model.LocalMedia
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shares a captured file through the system chooser (WhatsApp, Instagram, Drive, …)
 * using a FileProvider content URI. The file never leaves app-private storage; Android
 * grants a one-shot read permission to the app the user picks.
 */
@Singleton
class MediaSharer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun share(media: LocalMedia): AppResult<Unit> {
        val file = File(media.filePath)
        if (!file.exists() || file.length() == 0L) {
            return AppResult.Failure(AppFailure.storage("That file is no longer on this device."))
        }
        return runCatching {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = media.kind.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, media.fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Share ${media.fileName}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            AppResult.Success(Unit)
        }.getOrElse { throwable ->
            AppResult.Failure(
                AppFailure.unknown("No app could share this file.", throwable),
            )
        }
    }
}
