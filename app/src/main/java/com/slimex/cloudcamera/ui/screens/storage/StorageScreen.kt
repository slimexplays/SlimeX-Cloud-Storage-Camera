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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slimex.cloudcamera.core.util.Formatters
import com.slimex.cloudcamera.ui.components.IconBadge
import com.slimex.cloudcamera.ui.components.InlineBanner
import com.slimex.cloudcamera.ui.components.MetricRow
import com.slimex.cloudcamera.ui.components.PillTone
import com.slimex.cloudcamera.ui.components.SectionHeader
import com.slimex.cloudcamera.ui.components.SlimeCard
import com.slimex.cloudcamera.ui.components.SlimeXButton
import com.slimex.cloudcamera.ui.components.SlimeXButtonStyle
import com.slimex.cloudcamera.ui.components.SlimeXConfirmDialog
import com.slimex.cloudcamera.ui.components.SlimeXTopBar
import com.slimex.cloudcamera.ui.components.UsageBar
import com.slimex.cloudcamera.ui.theme.ErrorRed
import com.slimex.cloudcamera.ui.theme.InfoBlue
import com.slimex.cloudcamera.ui.theme.SlimePurple
import com.slimex.cloudcamera.ui.theme.Spacing
import com.slimex.cloudcamera.ui.theme.SuccessGreen
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary
import com.slimex.cloudcamera.ui.theme.TextTertiary
import kotlinx.coroutines.delay

@Composable
fun StorageScreen(
    contentPadding: PaddingValues,
    onOpenCloudSetup: () -> Unit,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmClearThumbs by remember { mutableStateOf(false) }

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
            title = "Storage",
            subtitle = "App-private media and your cloud bucket",
            actions = {
                IconButton(onClick = viewModel::refresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh",
                        tint = TextSecondary,
                    )
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
            if (state.message != null) {
                item {
                    InlineBanner(
                        text = state.message.orEmpty(),
                        tone = PillTone.PURPLE,
                        icon = Icons.Rounded.Storage,
                    )
                }
            }

            item {
                SlimeCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(icon = Icons.Rounded.Storage, size = 38.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Device storage",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                            )
                            Text(
                                text = "${Formatters.bytes(state.summary.deviceFreeBytes)} free of " +
                                    Formatters.bytes(state.summary.deviceTotalBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    UsageBar(fraction = state.summary.usedFraction, color = SlimePurple)
                }
            }

            item {
                SectionHeader(
                    title = "Captured media",
                    subtitle = "Stored privately inside the app sandbox",
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }

            item {
                SlimeCard {
                    MetricRow(
                        label = "Photos · ${state.summary.photoCount}",
                        value = Formatters.bytes(state.summary.photoBytes),
                        accent = SlimePurple,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    UsageBar(
                        fraction = fraction(
                            part = state.summary.photoBytes,
                            total = state.summary.totalMediaBytes,
                        ),
                        color = SlimePurple,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    MetricRow(
                        label = "Videos · ${state.summary.videoCount}",
                        value = Formatters.bytes(state.summary.videoBytes),
                        accent = InfoBlue,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    UsageBar(
                        fraction = fraction(
                            part = state.summary.videoBytes,
                            total = state.summary.totalMediaBytes,
                        ),
                        color = InfoBlue,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    MetricRow(
                        label = "Cached previews",
                        value = Formatters.bytes(state.summary.thumbnailBytes),
                        accent = TextTertiary,
                    )
                }
            }

            item {
                SectionHeader(
                    title = "Cloud storage",
                    subtitle = "Your Supabase bucket",
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }

            item {
                SlimeCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(icon = Icons.Rounded.Cloud, size = 38.dp, tint = SuccessGreen)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (state.cloudConfigured) "Connected" else "Not configured",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                            )
                            Text(
                                text = when {
                                    !state.cloudConfigured -> "Add a project to track uploaded media"
                                    state.remoteLoading -> "Reading bucket usage…"
                                    state.remoteError != null -> state.remoteError.orEmpty()
                                    else -> "${state.remoteUsage?.assetCount ?: 0} uploaded files"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.remoteError != null) ErrorRed else TextSecondary,
                            )
                        }
                    }
                    if (state.cloudConfigured && state.remoteUsage != null) {
                        Spacer(modifier = Modifier.height(14.dp))
                        MetricRow("Cloud size", Formatters.bytes(state.remoteUsage?.totalBytes ?: 0L), accent = SuccessGreen)
                        Spacer(modifier = Modifier.height(6.dp))
                        MetricRow("Photos", (state.remoteUsage?.photoCount ?: 0).toString(), accent = SlimePurple)
                        Spacer(modifier = Modifier.height(6.dp))
                        MetricRow("Videos", (state.remoteUsage?.videoCount ?: 0).toString(), accent = InfoBlue)
                    }
                    if (!state.cloudConfigured) {
                        Spacer(modifier = Modifier.height(14.dp))
                        SlimeXButton(
                            text = "Configure Supabase",
                            onClick = onOpenCloudSetup,
                            style = SlimeXButtonStyle.SECONDARY,
                            icon = Icons.Rounded.Cloud,
                            height = 46.dp,
                        )
                    }
                }
            }

            item {
                SectionHeader(
                    title = "Maintenance",
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }

            item {
                SlimeCard {
                    SlimeXButton(
                        text = "Clear cached previews",
                        onClick = { confirmClearThumbs = true },
                        style = SlimeXButtonStyle.SECONDARY,
                        icon = Icons.Rounded.PhotoLibrary,
                        height = 46.dp,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    SlimeXButton(
                        text = "Delete all local captures",
                        onClick = { confirmDeleteAll = true },
                        style = SlimeXButtonStyle.DANGER,
                        icon = Icons.Rounded.DeleteForever,
                        height = 46.dp,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Deleting local captures never touches files already uploaded to your bucket.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                }
            }

            item {
                SlimeCard {
                    MetricRow(
                        label = "Photo count",
                        value = state.summary.photoCount.toString(),
                        accent = SlimePurple,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    MetricRow(
                        label = "Video count",
                        value = state.summary.videoCount.toString(),
                        accent = InfoBlue,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    MetricRow(
                        label = "Total local media",
                        value = Formatters.bytes(state.summary.totalMediaBytes),
                        accent = TextSecondary,
                    )
                }
            }
        }
    }

    if (confirmClearThumbs) {
        SlimeXConfirmDialog(
            title = "Clear cached previews?",
            message = "Only cached preview images are removed. Your photos and videos are untouched.",
            confirmLabel = "Clear",
            onConfirm = {
                confirmClearThumbs = false
                viewModel.clearThumbnails()
            },
            onDismiss = { confirmClearThumbs = false },
        )
    }

    if (confirmDeleteAll) {
        SlimeXConfirmDialog(
            title = "Delete all local captures?",
            message = "This permanently removes every photo and video stored inside the app on " +
                "this device. Uploaded copies stay in your Supabase bucket.",
            confirmLabel = "Delete everything",
            destructive = true,
            onConfirm = {
                confirmDeleteAll = false
                viewModel.deleteAllLocal()
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }
}

private fun fraction(part: Long, total: Long): Float =
    if (total <= 0L) 0f else (part.toFloat() / total.toFloat()).coerceIn(0f, 1f)
