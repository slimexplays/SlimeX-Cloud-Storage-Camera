package com.slimex.cloudcamera.ui.screens.uploads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slimex.cloudcamera.core.util.Formatters
import com.slimex.cloudcamera.domain.model.MediaKind
import com.slimex.cloudcamera.domain.model.UploadStatus
import com.slimex.cloudcamera.domain.model.UploadTask
import com.slimex.cloudcamera.ui.components.EmptyState
import com.slimex.cloudcamera.ui.components.IconBadge
import com.slimex.cloudcamera.ui.components.InlineBanner
import com.slimex.cloudcamera.ui.components.PillTone
import com.slimex.cloudcamera.ui.components.SectionHeader
import com.slimex.cloudcamera.ui.components.SettingRow
import com.slimex.cloudcamera.ui.components.SlimeCard
import com.slimex.cloudcamera.ui.components.SlimeXButton
import com.slimex.cloudcamera.ui.components.SlimeXButtonStyle
import com.slimex.cloudcamera.ui.components.SlimeXChip
import com.slimex.cloudcamera.ui.components.SlimeXTopBar
import com.slimex.cloudcamera.ui.components.StatusPill
import com.slimex.cloudcamera.ui.components.UploadProgressBar
import com.slimex.cloudcamera.ui.theme.CharcoalElevated
import com.slimex.cloudcamera.ui.theme.OutlineSoft
import com.slimex.cloudcamera.ui.theme.SlimePurple
import com.slimex.cloudcamera.ui.theme.Spacing
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun UploadQueueScreen(
    contentPadding: PaddingValues,
    onOpenCloudSetup: () -> Unit,
    onOpenAccount: () -> Unit,
    viewModel: UploadQueueViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(2600)
            viewModel.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        SlimeXTopBar(
            title = "Upload Queue",
            subtitle = "${state.summary.active} active · ${state.summary.failed} failed",
            actions = {
                IconButton(
                    onClick = { viewModel.resumeAll() },
                    enabled = state.summary.hasWork || state.summary.failed > 0,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Resume queue",
                        tint = SlimePurple,
                    )
                }
            },
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = Spacing.cardPadding, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                state.blockedReason?.let { reason ->
                    InlineBanner(
                        text = reason,
                        tone = PillTone.AMBER,
                        icon = if (!state.online) Icons.Rounded.CloudOff else Icons.Rounded.Lock,
                        actionLabel = when {
                            !state.cloudConfigured -> "Set up"
                            !state.signedIn -> "Sign in"
                            else -> null
                        },
                        onAction = {
                            if (!state.cloudConfigured) onOpenCloudSetup() else onOpenAccount()
                        },
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                }
            }

            if (state.message != null) {
                item {
                    InlineBanner(
                        text = state.message.orEmpty(),
                        tone = PillTone.GREEN,
                        onDismiss = viewModel::consumeMessage,
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                }
            }

            item {
                SlimeCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        QueueMetric(
                            label = "Pending",
                            value = state.summary.pending.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        QueueMetric(
                            label = "Uploading",
                            value = state.summary.uploading.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        QueueMetric(
                            label = "Uploaded",
                            value = state.summary.uploaded.toString(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.md))
                    Text(
                        text = "${Formatters.bytes(state.summary.uploadedBytes)} of " +
                            "${Formatters.bytes(state.summary.totalBytes)} verified in the cloud",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }

            item {
                SlimeCard(contentPadding = PaddingValues(0.dp)) {
                    SettingRow(
                        title = "Automatic uploads",
                        subtitle = "Queue captures and upload as soon as a connection is available",
                        icon = Icons.Rounded.CloudUpload,
                        trailing = {
                            Switch(
                                checked = state.autoUpload,
                                onCheckedChange = viewModel::setAutoUpload,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = TextPrimary,
                                    checkedTrackColor = SlimePurple,
                                ),
                            )
                        },
                    )
                    SettingRow(
                        title = "Wi-Fi only",
                        subtitle = "Wait for an unmetered connection before uploading",
                        icon = Icons.Rounded.CloudQueue,
                        showDivider = true,
                        trailing = {
                            Switch(
                                checked = state.wifiOnly,
                                onCheckedChange = viewModel::setWifiOnly,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = TextPrimary,
                                    checkedTrackColor = SlimePurple,
                                ),
                            )
                        },
                    )
                }
            }

            item {
                SectionHeader(
                    title = "Jobs",
                    subtitle = "Local files are deleted only after the cloud copy is verified",
                    trailing = {
                        SlimeXChip(
                            text = "Clear finished",
                            icon = Icons.Rounded.DeleteSweep,
                            onClick = viewModel::clearFinished,
                            enabled = state.summary.uploaded > 0 || state.summary.cancelled > 0,
                        )
                    },
                )
            }

            if (state.tasks.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.CloudQueue,
                        title = "Queue is empty",
                        message = "Take a photo or record a video — it is encrypted and queued automatically.",
                    )
                }
            } else {
                items(state.tasks, key = { it.id }) { task ->
                    UploadTaskCard(
                        task = task,
                        onRetry = { viewModel.retry(task.id) },
                        onCancel = { viewModel.cancel(task.id) },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(Spacing.xl)) }
        }
    }
}

@Composable
private fun QueueMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(CharcoalElevated)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
private fun UploadTaskCard(
    task: UploadTask,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    SlimeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = if (task.kind == MediaKind.PHOTO) Icons.Rounded.Photo else Icons.Rounded.Movie,
                tint = SlimePurple,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${Formatters.bytes(task.sizeBytes)} · ${Formatters.relative(task.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            StatusPill(text = statusLabel(task.status), tone = statusTone(task.status))
        }

        if (task.status.isRunning || task.status == UploadStatus.QUEUED) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            UploadProgressBar(progress = task.progress)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${(task.progress * 100).toInt()}% · ${task.statusName()}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }

        if (task.errorMessage != null && task.status != UploadStatus.UPLOADED) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = task.errorMessage.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            when (task.status) {
                UploadStatus.FAILED,
                UploadStatus.CANCELLED,
                UploadStatus.WAITING_FOR_NETWORK,
                UploadStatus.WAITING_FOR_AUTH,
                UploadStatus.WAITING_FOR_CONFIG -> SlimeXButton(
                    text = "Retry",
                    onClick = onRetry,
                    icon = Icons.Rounded.Replay,
                    fillWidth = false,
                    height = 40.dp,
                    style = SlimeXButtonStyle.SECONDARY,
                )

                UploadStatus.UPLOADED -> StatusPill(text = "Verified", tone = PillTone.GREEN)
                else -> Unit
            }

            if (!task.status.isFinished) {
                SlimeXButton(
                    text = "Cancel",
                    onClick = onCancel,
                    icon = Icons.Rounded.Cancel,
                    fillWidth = false,
                    height = 40.dp,
                    style = SlimeXButtonStyle.DANGER,
                )
            }
        }
    }
}

private fun UploadTask.statusName(): String = when (status) {
    UploadStatus.ENCRYPTING -> "Encrypting"
    UploadStatus.UPLOADING -> "Uploading"
    UploadStatus.VERIFYING -> "Verifying"
    UploadStatus.QUEUED -> "Queued"
    UploadStatus.UPLOADED -> "Uploaded"
    UploadStatus.FAILED -> "Failed"
    UploadStatus.CANCELLED -> "Cancelled"
    UploadStatus.WAITING_FOR_NETWORK -> "Waiting for network"
    UploadStatus.WAITING_FOR_AUTH -> "Waiting for sign-in"
    UploadStatus.WAITING_FOR_CONFIG -> "Waiting for setup"
}

private fun statusLabel(status: UploadStatus): String = when (status) {
    UploadStatus.QUEUED -> "Queued"
    UploadStatus.ENCRYPTING -> "Encrypting"
    UploadStatus.UPLOADING -> "Uploading"
    UploadStatus.VERIFYING -> "Verifying"
    UploadStatus.UPLOADED -> "Done"
    UploadStatus.FAILED -> "Failed"
    UploadStatus.CANCELLED -> "Cancelled"
    UploadStatus.WAITING_FOR_NETWORK -> "Offline"
    UploadStatus.WAITING_FOR_AUTH -> "Sign in"
    UploadStatus.WAITING_FOR_CONFIG -> "Setup"
}

private fun statusTone(status: UploadStatus): PillTone = when (status) {
    UploadStatus.UPLOADED -> PillTone.GREEN
    UploadStatus.FAILED -> PillTone.RED
    UploadStatus.CANCELLED -> PillTone.NEUTRAL
    UploadStatus.WAITING_FOR_NETWORK, UploadStatus.WAITING_FOR_AUTH, UploadStatus.WAITING_FOR_CONFIG ->
        PillTone.AMBER
    else -> PillTone.PURPLE
}
