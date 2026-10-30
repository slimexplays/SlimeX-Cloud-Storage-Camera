package com.slimex.cloudcamera.ui.screens.uploads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Refresh
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
import com.slimex.cloudcamera.ui.components.MetricRow
import com.slimex.cloudcamera.ui.components.PillTone
import com.slimex.cloudcamera.ui.components.SectionHeader
import com.slimex.cloudcamera.ui.components.SlimeCard
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
import com.slimex.cloudcamera.ui.theme.TextTertiary
import kotlinx.coroutines.delay

@Composable
fun UploadQueueScreen(
    contentPadding: PaddingValues,
    onOpenCloudSetup: () -> Unit,
    viewModel: UploadQueueViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(2400)
            viewModel.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        SlimeXTopBar(
            title = "Upload queue",
            subtitle = subtitleFor(state.summary),
            actions = {
                IconButton(onClick = viewModel::resumeAll) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Resume queue",
                        tint = TextSecondary,
                    )
                }
                if (state.summary.uploaded > 0 || state.tasks.any { it.status.isFinished }) {
                    IconButton(onClick = viewModel::clearFinished) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = "Clear finished",
                            tint = TextSecondary,
                        )
                    }
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.screenPadding,
                end = Spacing.screenPadding,
                top = Spacing.sm,
                bottom = Spacing.xxxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (!state.cloudConfigured) {
                item {
                    InlineBanner(
                        text = "Supabase is not configured, so uploads wait in the queue.",
                        tone = PillTone.AMBER,
                        icon = Icons.Rounded.CloudOff,
                        actionLabel = "Configure",
                        onAction = onOpenCloudSetup,
                    )
                }
            }

            if (state.message != null) {
                item {
                    InlineBanner(
                        text = state.message.orEmpty(),
                        tone = PillTone.PURPLE,
                        icon = Icons.Rounded.CloudUpload,
                    )
                }
            }

            item { QueueSummaryCard(state = state, onAutoUploadChange = viewModel::setAutoUpload) }

            if (state.tasks.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.CloudQueue,
                        title = "The queue is empty",
                        message = "Captures are added here automatically and uploaded to your own " +
                            "Supabase bucket.",
                    )
                }
            } else {
                item {
                    SectionHeader(
                        title = "Jobs",
                        subtitle = "${state.tasks.size} in this queue",
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
                items(state.tasks, key = { it.id }) { task ->
                    UploadTaskCard(
                        task = task,
                        onRetry = { viewModel.retry(task.id) },
                        onCancel = { viewModel.cancel(task.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueSummaryCard(
    state: UploadQueueViewModel.State,
    onAutoUploadChange: (Boolean) -> Unit,
) {
    SlimeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = Icons.Rounded.CloudUpload, size = 38.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Automatic upload",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                )
                Text(
                    text = if (state.autoUpload) "New captures are queued instantly" else "Captures wait until you start the queue",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Switch(
                checked = state.autoUpload,
                onCheckedChange = onAutoUploadChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TextPrimary,
                    checkedTrackColor = SlimePurple,
                    uncheckedThumbColor = TextTertiary,
                    uncheckedTrackColor = CharcoalElevated,
                    uncheckedBorderColor = OutlineSoft,
                ),
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        MetricRow("Waiting", state.summary.pending.toString())
        Spacer(modifier = Modifier.height(6.dp))
        MetricRow("Uploading", state.summary.uploading.toString(), accent = SlimePurple)
        Spacer(modifier = Modifier.height(6.dp))
        MetricRow("Uploaded", state.summary.uploaded.toString(), accent = com.slimex.cloudcamera.ui.theme.SuccessGreen)
        if (state.summary.failed > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            MetricRow("Failed", state.summary.failed.toString(), accent = com.slimex.cloudcamera.ui.theme.ErrorRed)
        }
    }
}

@Composable
private fun UploadTaskCard(
    task: UploadTask,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    SlimeCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = if (task.kind == MediaKind.VIDEO) Icons.Rounded.Movie else Icons.Rounded.Photo,
                size = 36.dp,
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
                    color = TextTertiary,
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            StatusPill(text = statusLabel(task.status), tone = statusTone(task.status))
        }

        if (task.status == UploadStatus.UPLOADING) {
            Spacer(modifier = Modifier.height(12.dp))
            UploadProgressBar(progress = task.progress)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${(task.progress * 100).toInt()}% uploaded",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }

        if (!task.errorMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = task.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (task.status == UploadStatus.FAILED) {
                    com.slimex.cloudcamera.ui.theme.ErrorRed
                } else {
                    TextSecondary
                },
            )
        }

        if (task.status == UploadStatus.FAILED ||
            task.status == UploadStatus.WAITING_FOR_NETWORK ||
            task.status == UploadStatus.WAITING_FOR_CONFIG
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlimeXChip(text = "Retry", onClick = onRetry, selected = true)
                SlimeXChip(text = "Cancel", onClick = onCancel)
            }
        }

        if (task.status == UploadStatus.UPLOADED && !task.remotePath.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = task.remotePath,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun subtitleFor(summary: com.slimex.cloudcamera.domain.model.UploadQueueSummary): String = when {
    summary.uploading > 0 -> "${summary.uploading} uploading · ${summary.pending} waiting"
    summary.pending > 0 -> "${summary.pending} waiting to upload"
    summary.failed > 0 -> "${summary.failed} failed · tap retry"
    summary.uploaded > 0 -> "${summary.uploaded} uploaded"
    else -> "Nothing to upload"
}

private fun statusLabel(status: UploadStatus): String = when (status) {
    UploadStatus.QUEUED -> "Queued"
    UploadStatus.UPLOADING -> "Uploading"
    UploadStatus.UPLOADED -> "Uploaded"
    UploadStatus.FAILED -> "Failed"
    UploadStatus.CANCELLED -> "Cancelled"
    UploadStatus.WAITING_FOR_NETWORK -> "Waiting"
    UploadStatus.WAITING_FOR_CONFIG -> "Needs setup"
}

private fun statusTone(status: UploadStatus): PillTone = when (status) {
    UploadStatus.UPLOADED -> PillTone.GREEN
    UploadStatus.FAILED -> PillTone.RED
    UploadStatus.UPLOADING -> PillTone.PURPLE
    UploadStatus.WAITING_FOR_CONFIG -> PillTone.AMBER
    UploadStatus.WAITING_FOR_NETWORK -> PillTone.AMBER
    else -> PillTone.NEUTRAL
}
