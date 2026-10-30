package com.slimex.cloudcamera.ui.screens.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.slimex.cloudcamera.core.util.Formatters
import com.slimex.cloudcamera.domain.model.CloudAsset
import com.slimex.cloudcamera.domain.model.LocalMedia
import com.slimex.cloudcamera.domain.model.MediaKind
import com.slimex.cloudcamera.ui.components.EmptyState
import com.slimex.cloudcamera.ui.components.ErrorState
import com.slimex.cloudcamera.ui.components.LoadingState
import com.slimex.cloudcamera.ui.components.SegmentedSelector
import com.slimex.cloudcamera.ui.components.SlimeXButton
import com.slimex.cloudcamera.ui.components.SlimeXButtonStyle
import com.slimex.cloudcamera.ui.components.SlimeXConfirmDialog
import com.slimex.cloudcamera.ui.components.SlimeXTopBar
import com.slimex.cloudcamera.ui.theme.CharcoalElevated
import com.slimex.cloudcamera.ui.theme.ErrorRed
import com.slimex.cloudcamera.ui.theme.OutlineSoft
import com.slimex.cloudcamera.ui.theme.SlimePurple
import com.slimex.cloudcamera.ui.theme.Spacing
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.io.File

/**
 * Unified gallery: media that lives in the user's Supabase bucket and media that is still
 * on the device. Cloud thumbnails load through short-lived signed URLs on a private bucket.
 */
@Composable
fun CloudGalleryScreen(
    contentPadding: PaddingValues,
    onOpenCloudSetup: () -> Unit,
    viewModel: CloudGalleryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<CloudAsset?>(null) }

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
            title = "Gallery",
            subtitle = when (state.tab) {
                CloudGalleryViewModel.Tab.CLOUD -> state.cloudHost ?: "Cloud not configured"
                CloudGalleryViewModel.Tab.DEVICE -> "${state.localMedia.size} files on this device"
            },
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

        SegmentedSelector(
            options = listOf("Cloud", "On device"),
            selectedIndex = if (state.tab == CloudGalleryViewModel.Tab.CLOUD) 0 else 1,
            onSelected = { index ->
                viewModel.selectTab(
                    if (index == 0) CloudGalleryViewModel.Tab.CLOUD else CloudGalleryViewModel.Tab.DEVICE,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenPadding, vertical = Spacing.sm),
        )

        when (state.tab) {
            CloudGalleryViewModel.Tab.CLOUD -> CloudTab(
                state = state,
                onOpenCloudSetup = onOpenCloudSetup,
                onRetry = viewModel::refresh,
                onPreview = viewModel::openPreview,
                onLoadThumbnail = viewModel::ensureSignedUrl,
                onDeleteRequest = { asset -> pendingDelete = asset },
            )

            CloudGalleryViewModel.Tab.DEVICE -> DeviceTab(
                media = state.localMedia,
                onShare = viewModel::shareLocal,
            )
        }
    }

    state.preview?.let { asset ->
        CloudPreviewDialog(
            asset = asset,
            imageUrl = state.signedUrls[asset.id],
            deleting = state.deleting,
            onDismiss = viewModel::closePreview,
            onDelete = { pendingDelete = asset },
        )
    }

    pendingDelete?.let { asset ->
        SlimeXConfirmDialog(
            title = "Delete from cloud?",
            message = "This removes ${asset.fileName} from your Supabase bucket and the media table. " +
                "The file on this device is not touched.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                pendingDelete = null
                viewModel.deleteAsset(asset)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun CloudTab(
    state: CloudGalleryViewModel.State,
    onOpenCloudSetup: () -> Unit,
    onRetry: () -> Unit,
    onPreview: (CloudAsset) -> Unit,
    onLoadThumbnail: (CloudAsset) -> Unit,
    onDeleteRequest: (CloudAsset) -> Unit,
) {
    when {
        !state.cloudConfigured -> EmptyState(
            icon = Icons.Rounded.Cloud,
            title = "No cloud connected",
            message = "Add your Supabase project URL and publishable key to see the media " +
                "you have uploaded here.",
            actionLabel = "Configure Supabase",
            onAction = onOpenCloudSetup,
            modifier = Modifier.padding(top = Spacing.xl),
        )

        state.loading && state.assets.isEmpty() -> LoadingState(message = "Loading your cloud media…")

        state.error != null && state.assets.isEmpty() -> ErrorState(
            message = state.error,
            onRetry = onRetry,
            secondaryLabel = "Open cloud settings",
            onSecondary = onOpenCloudSetup,
        )

        state.assets.isEmpty() -> EmptyState(
            icon = Icons.Rounded.PhotoLibrary,
            title = "Nothing in the cloud yet",
            message = "Captures you upload appear here. Take a photo and it will be queued automatically.",
        )

        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(
                start = Spacing.screenPadding,
                end = Spacing.screenPadding,
                top = Spacing.sm,
                bottom = Spacing.xxxl,
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(state.assets, key = { it.id }) { asset ->
                CloudTile(
                    asset = asset,
                    imageUrl = state.signedUrls[asset.id],
                    onVisible = { onLoadThumbnail(asset) },
                    onClick = { onPreview(asset) },
                    onLongClick = { onDeleteRequest(asset) },
                )
            }
        }
    }
}

@Composable
private fun CloudTile(
    asset: CloudAsset,
    imageUrl: String?,
    onVisible: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    LaunchedEffect(asset.id) { onVisible() }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(CharcoalElevated)
            .border(1.dp, OutlineSoft, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
    ) {
        if (imageUrl != null && asset.kind == MediaKind.PHOTO) {
            AsyncImage(
                model = imageUrl,
                contentDescription = asset.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = if (asset.kind == MediaKind.VIDEO) Icons.Rounded.Movie else Icons.Rounded.PhotoLibrary,
                    contentDescription = null,
                    tint = SlimePurple,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = asset.fileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                text = Formatters.bytes(asset.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(22.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = "Delete",
                tint = ErrorRed,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun DeviceTab(
    media: List<LocalMedia>,
    onShare: (LocalMedia) -> Unit,
) {
    if (media.isEmpty()) {
        EmptyState(
            icon = Icons.Rounded.PhotoLibrary,
            title = "Nothing captured yet",
            message = "Photos and videos you capture are stored privately inside the app.",
            modifier = Modifier.padding(top = Spacing.xl),
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(
            start = Spacing.screenPadding,
            end = Spacing.screenPadding,
            top = Spacing.sm,
            bottom = Spacing.xxxl,
        ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(media, key = { it.filePath }) { item ->
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CharcoalElevated)
                    .border(1.dp, OutlineSoft, RoundedCornerShape(6.dp)),
            ) {
                if (item.kind == MediaKind.PHOTO) {
                    AsyncImage(
                        model = File(item.filePath),
                        contentDescription = item.fileName,
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
                            contentDescription = item.fileName,
                            tint = SlimePurple,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = Formatters.bytes(item.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable { onShare(item) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "Share",
                        tint = SlimePurple,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudPreviewDialog(
    asset: CloudAsset,
    imageUrl: String?,
    deleting: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF0C0C10))
                .navigationBarsPadding()
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = asset.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(Formatters.bytes(asset.sizeBytes))
                            asset.capturedAtMillis?.let { append(" · ").append(Formatters.dateTime(it)) }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = TextSecondary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            if (asset.kind == MediaKind.PHOTO) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = asset.fileName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.75f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black),
                    )
                } else {
                    LoadingState(message = "Fetching a signed URL…")
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CharcoalElevated)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Movie,
                        contentDescription = null,
                        tint = SlimePurple,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Video preview is not part of this build",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "The file is stored safely in your bucket. Inline playback arrives " +
                            "with the media player update.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            SlimeXButton(
                text = if (deleting) "Deleting…" else "Delete from cloud",
                onClick = onDelete,
                style = SlimeXButtonStyle.DANGER,
                icon = Icons.Rounded.Delete,
                loading = deleting,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
