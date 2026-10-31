package com.slimex.cloudcamera.ui.screens.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slimex.cloudcamera.core.util.Formatters
import com.slimex.cloudcamera.ui.components.InlineBanner
import com.slimex.cloudcamera.ui.components.MetricRow
import com.slimex.cloudcamera.ui.components.PillTone
import com.slimex.cloudcamera.ui.components.SectionHeader
import com.slimex.cloudcamera.ui.components.SlimeCard
import com.slimex.cloudcamera.ui.components.SlimeXButton
import com.slimex.cloudcamera.ui.components.SlimeXButtonStyle
import com.slimex.cloudcamera.ui.components.SlimeXTopBar
import com.slimex.cloudcamera.ui.components.StatTile
import com.slimex.cloudcamera.ui.components.StatusPill
import com.slimex.cloudcamera.ui.theme.SlimePurple
import com.slimex.cloudcamera.ui.theme.Spacing
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * Storage dashboard: cloud usage (where the project allows it), photo/video counts, pending and
 * failed uploads plus local cache figures. Clean-up only ever touches evictable caches — files that
 * have not been verified in the cloud are never removed from here.
 */
@Composable
fun StorageScreen(
    contentPadding: PaddingValues,
    onOpenCloudSetup: () -> Unit,
    onOpenAccount: () -> Unit,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(3200)
            viewModel.consumeMessage()
        }
    }

    val summary = state.summary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        SlimeXTopBar(
            title = "Storage",
            subtitle = if (state.cloudConfigured) "Private bucket + this device" else "This device",
            actions = {
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh storage",
                        tint = SlimePurple,
                    )
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.cardPadding),
        ) {
            if (state.message != null) {
                InlineBanner(
                    text = state.message.orEmpty(),
                    tone = PillTone.GREEN,
                    onDismiss = viewModel::consumeMessage,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
            }

            Spacer(modifier = Modifier.height(Spacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StatTile(
                    label = "Photos",
                    value = summary.stagedPhotoCount.toString(),
                    caption = "waiting locally",
                    icon = Icons.Rounded.Photo,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Videos",
                    value = summary.stagedVideoCount.toString(),
                    caption = "waiting locally",
                    icon = Icons.Rounded.Movie,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            SlimeCard {
                Text(
                    text = "Cloud library",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                when {
                    !state.cloudConfigured -> {
                        Text(
                            text = "Connect your Supabase project to see cloud usage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        SlimeXButton(
                            text = "Open cloud setup",
                            onClick = onOpenCloudSetup,
                            style = SlimeXButtonStyle.SECONDARY,
                            height = 44.dp,
                        )
                    }

                    !state.signedIn -> {
                        Text(
                            text = "Sign in to read the usage of your private bucket.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        SlimeXButton(
                            text = "Sign in",
                            onClick = onOpenAccount,
                            style = SlimeXButtonStyle.SECONDARY,
                            height = 44.dp,
                        )
                    }

                    state.remoteLoading && state.remoteUsage == null -> {
                        Text(
                            text = "Reading the bucket…",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }

                    else -> {
                        val usage = state.remoteUsage
                        MetricRow(
                            label = "Used in your bucket",
                            value = Formatters.bytes(usage?.totalBytes ?: 0L),
                        )
                        MetricRow(
                            label = "Files stored",
                            value = (usage?.assetCount ?: 0).toString(),
                        )
                        MetricRow(
                            label = "Photos / videos",
                            value = "${usage?.photoCount ?: 0} / ${usage?.videoCount ?: 0}",
                        )
                        if (state.remoteError != null) {
                            Spacer(modifier = Modifier.height(Spacing.sm))
                            Text(
                                text = state.remoteError.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            SlimeCard {
                StatusPill(
                    text = "${state.queue.active} pending",
                    tone = if (state.queue.failed > 0) PillTone.AMBER else PillTone.PURPLE,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                MetricRow(label = "Pending uploads", value = state.queue.pending.toString())
                MetricRow(label = "Failed uploads", value = state.queue.failed.toString())
                MetricRow(
                    label = "Uploaded this session",
                    value = Formatters.bytes(state.queue.uploadedBytes),
                )
                Text(
                    text = "Pending captures stay on this device until the upload is verified — " +
                        "they are never cleared by cache clean-up.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            SectionHeader(
                title = "This device",
                subtitle = "App-private storage only — nothing is written to the public gallery",
            )
            Spacer(modifier = Modifier.height(Spacing.sm))

            SlimeCard {
                Text(
                    text = "Device storage",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                LinearProgressIndicator(
                    progress = { summary.usedFraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = SlimePurple,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = "${Formatters.bytes(summary.deviceFreeBytes)} free of " +
                        "${Formatters.bytes(summary.deviceTotalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(Spacing.md))
                MetricRow(label = "Waiting to upload", value = Formatters.bytes(summary.pendingBytes))
                MetricRow(label = "Encrypted copies", value = Formatters.bytes(summary.encryptedBytes))
                MetricRow(label = "Thumbnails", value = Formatters.bytes(summary.thumbnailBytes))
                MetricRow(label = "Viewer cache", value = Formatters.bytes(summary.viewerCacheBytes))
                MetricRow(label = "Share cache", value = Formatters.bytes(summary.shareCacheBytes))
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeXButton(
                    text = "Clear caches (${Formatters.bytes(summary.cacheBytes)})",
                    onClick = { viewModel.clearCache() },
                    icon = Icons.Rounded.DeleteSweep,
                    style = SlimeXButtonStyle.SECONDARY,
                    enabled = summary.cacheBytes > 0,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = "Clearing removes thumbnails, viewer and share copies only. Files that are " +
                        "still waiting to be uploaded are kept.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))
        }
    }
}
