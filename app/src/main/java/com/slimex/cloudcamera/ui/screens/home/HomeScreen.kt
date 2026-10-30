package com.slimex.cloudcamera.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.slimex.cloudcamera.core.util.Formatters
import com.slimex.cloudcamera.domain.model.CloudSetupStatus
import com.slimex.cloudcamera.domain.model.LocalMedia
import com.slimex.cloudcamera.ui.components.BrandFooter
import com.slimex.cloudcamera.ui.components.EmptyState
import com.slimex.cloudcamera.ui.components.IconBadge
import com.slimex.cloudcamera.ui.components.SectionHeader
import com.slimex.cloudcamera.ui.components.SlimeCard
import com.slimex.cloudcamera.ui.components.SlimeXButton
import com.slimex.cloudcamera.ui.components.SlimeXButtonStyle
import com.slimex.cloudcamera.ui.components.SlimeXTopBar
import com.slimex.cloudcamera.ui.components.StatTile
import com.slimex.cloudcamera.ui.components.StatusPill
import com.slimex.cloudcamera.ui.components.PillTone
import com.slimex.cloudcamera.ui.theme.CharcoalElevated
import com.slimex.cloudcamera.ui.theme.OutlineSoft
import com.slimex.cloudcamera.ui.theme.PurpleGradient
import com.slimex.cloudcamera.ui.theme.SlimePurple
import com.slimex.cloudcamera.ui.theme.Spacing
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary
import java.io.File

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onOpenCamera: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenUploads: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCloudSetup: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        SlimeXTopBar(
            title = "SlimeX Cloud",
            subtitle = "Camera · Your own storage",
            showBrandDot = true,
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "Settings",
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
            item { CloudStatusCard(state.cloudStatus, onOpenCloudSetup) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StatTile(
                        label = "Photos",
                        value = state.summary.photoCount.toString(),
                        icon = Icons.Rounded.Image,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Videos",
                        value = state.summary.videoCount.toString(),
                        icon = Icons.Rounded.Movie,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "On device",
                        value = Formatters.bytes(state.summary.totalMediaBytes),
                        icon = Icons.Rounded.Storage,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                SectionHeader(
                    title = "Quick actions",
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }

            item { CameraShortcutCard(onOpenCamera) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ShortcutCard(
                        title = "Cloud gallery",
                        subtitle = "Uploaded media",
                        icon = Icons.Rounded.Cloud,
                        onClick = onOpenGallery,
                        modifier = Modifier.weight(1f),
                    )
                    ShortcutCard(
                        title = "Upload queue",
                        subtitle = when {
                            state.queue.uploading > 0 -> "${state.queue.uploading} uploading"
                            state.queue.pending > 0 -> "${state.queue.pending} waiting"
                            state.queue.failed > 0 -> "${state.queue.failed} failed"
                            else -> "Nothing pending"
                        },
                        icon = Icons.Rounded.CloudUpload,
                        onClick = onOpenUploads,
                        modifier = Modifier.weight(1f),
                        highlight = state.queue.hasWork || state.queue.failed > 0,
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(
                        title = "Recent captures",
                        modifier = Modifier.weight(1f),
                    )
                    if (state.recent.isNotEmpty()) {
                        Text(
                            text = "View all",
                            style = MaterialTheme.typography.labelMedium,
                            color = SlimePurple,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(onClick = onOpenGallery)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            item {
                if (state.recent.isEmpty()) {
                    SlimeCard {
                        EmptyState(
                            icon = Icons.Rounded.PhotoCamera,
                            title = "No captures yet",
                            message = "Open the camera and take your first shot. " +
                                "Everything you capture shows up here instantly.",
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        items(state.recent, key = { it.filePath }) { media ->
                            RecentThumbnail(media)
                        }
                    }
                }
            }

            item {
                SlimeXButton(
                    text = "Storage overview",
                    onClick = onOpenStorage,
                    style = SlimeXButtonStyle.SECONDARY,
                    icon = Icons.Rounded.Storage,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BrandFooter()
                }
            }
        }
    }
}

@Composable
private fun CloudStatusCard(status: CloudSetupStatus, onOpenCloudSetup: () -> Unit) {
    when (status) {
        is CloudSetupStatus.Configured -> SlimeCard(onClick = onOpenCloudSetup) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = Icons.Rounded.Cloud, size = 42.dp)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cloud connected",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = status.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(text = "Configured", tone = PillTone.GREEN)
            }
        }

        is CloudSetupStatus.NotConfigured -> SlimeCard(
            onClick = onOpenCloudSetup,
            borderColor = SlimePurple.copy(alpha = 0.45f),
            background = Brush.linearGradient(
                listOf(SlimePurple.copy(alpha = 0.16f), Color(0xFF121216)),
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = Icons.Rounded.CloudOff, size = 42.dp, tint = SlimePurple)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Connect your Supabase project",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Uploads stay queued until your cloud is configured.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = SlimePurple,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        CloudSetupStatus.Loading -> SlimeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = Icons.Rounded.Cloud, size = 42.dp)
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = "Checking configuration…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun CameraShortcutCard(onOpenCamera: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(PurpleGradient))
            .clickable(onClick = onOpenCamera)
            .padding(Spacing.xl),
    ) {
        Column(modifier = Modifier.align(Alignment.CenterStart)) {
            Text(
                text = "Open camera",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Photo & 1080p video · pure black UI",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
        Icon(
            imageVector = Icons.Rounded.PhotoCamera,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(38.dp),
        )
    }
}

@Composable
private fun ShortcutCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CharcoalElevated)
            .border(
                width = 1.dp,
                color = if (highlight) SlimePurple.copy(alpha = 0.45f) else OutlineSoft,
                shape = RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick)
            .padding(Spacing.lg),
    ) {
        IconBadge(icon = icon, size = 34.dp)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}

@Composable
private fun RecentThumbnail(media: LocalMedia) {
    Box(
        modifier = Modifier
            .size(width = 104.dp, height = 104.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CharcoalElevated)
            .border(1.dp, OutlineSoft, RoundedCornerShape(16.dp)),
    ) {
        if (media.kind == com.slimex.cloudcamera.domain.model.MediaKind.PHOTO) {
            AsyncImage(
                model = File(media.filePath),
                contentDescription = media.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Movie,
                    contentDescription = media.fileName,
                    tint = SlimePurple,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = Formatters.relative(media.createdAtMillis),
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary,
            )
        }
    }
}
