package com.slimex.cloudcamera.ui.screens.gallery

import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.slimex.cloudcamera.core.util.Formatters
import com.slimex.cloudcamera.domain.model.CloudMedia
import com.slimex.cloudcamera.domain.model.MediaFilter
import com.slimex.cloudcamera.domain.model.MediaKind
import com.slimex.cloudcamera.domain.model.MediaSort
import com.slimex.cloudcamera.ui.components.SlimeXConfirmDialog
import com.slimex.cloudcamera.ui.components.EmptyState
import com.slimex.cloudcamera.ui.components.InlineBanner
import com.slimex.cloudcamera.ui.components.PillTone
import com.slimex.cloudcamera.ui.components.SlimeXChip
import com.slimex.cloudcamera.ui.components.SlimeXTextField
import com.slimex.cloudcamera.ui.components.SlimeXTopBar
import com.slimex.cloudcamera.ui.components.StatusPill
import com.slimex.cloudcamera.ui.theme.CharcoalElevated
import com.slimex.cloudcamera.ui.theme.ErrorRed
import com.slimex.cloudcamera.ui.theme.OutlineSoft
import com.slimex.cloudcamera.ui.theme.PureBlack
import com.slimex.cloudcamera.ui.theme.SlimePurple
import com.slimex.cloudcamera.ui.theme.Spacing
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.io.File

/**
 * Private cloud gallery: all / photos / videos, thumbnail grid, search, sort, full-screen viewer,
 * video playback, details, delete and direct sharing. Thumbnails are decrypted into the app cache
 * — nothing is ever written to a public folder.
 */
@Composable
fun CloudGalleryScreen(
    contentPadding: PaddingValues,
    onOpenCloudSetup: () -> Unit,
    onOpenAccount: () -> Unit,
    viewModel: CloudGalleryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<CloudMedia?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        if (state.error != null) {
            delay(5000)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(3200)
            viewModel.consumeMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            SlimeXTopBar(
                title = "Cloud Gallery",
                subtitle = when {
                    state.needsSetup -> "Supabase not configured"
                    state.needsSignIn -> "Not signed in"
                    else -> "${state.assets.size} item${if (state.assets.size == 1) "" else "s"}"
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.loading) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh",
                            tint = if (state.loading) TextSecondary else SlimePurple,
                        )
                    }
                },
            )

            when {
                state.needsSetup -> EmptyState(
                    icon = Icons.Rounded.CloudOff,
                    title = "No cloud project yet",
                    message = "Connect your Supabase project to see the photos and videos stored in " +
                        "your private bucket — configure Supabase and the Cloudflare Worker first.",
                    actionLabel = "Cloud settings",
                    onAction = onOpenCloudSetup,
                    modifier = Modifier.padding(top = 40.dp),
                )

                state.needsSignIn -> EmptyState(
                    icon = Icons.Rounded.Login,
                    title = "Sign in to continue",
                    message = "Your media is private: the gallery uses your Supabase session to " +
                        "read only your own files.",
                    actionLabel = "Sign in",
                    onAction = onOpenAccount,
                    modifier = Modifier.padding(top = 40.dp),
                )

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    if (state.error != null) {
                        InlineBanner(
                            text = state.error.orEmpty(),
                            tone = PillTone.RED,
                            onDismiss = viewModel::consumeError,
                            modifier = Modifier.padding(horizontal = Spacing.cardPadding),
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                    }
                    if (state.message != null) {
                        InlineBanner(
                            text = state.message.orEmpty(),
                            tone = PillTone.GREEN,
                            onDismiss = viewModel::consumeMessage,
                            modifier = Modifier.padding(horizontal = Spacing.cardPadding),
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                    }

                    SlimeXTextField(
                        value = state.search,
                        onValueChange = viewModel::setSearch,
                        label = "Search by file name",
                        placeholder = "SLX_2026…",
                        modifier = Modifier.padding(horizontal = Spacing.cardPadding),
                        trailing = {
                            if (state.search.isNotBlank()) {
                                IconButton(onClick = viewModel::clearSearch) {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = "Clear search",
                                        tint = TextSecondary,
                                    )
                                }
                            }
                        },
                    )

                    Spacer(modifier = Modifier.height(Spacing.sm))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.cardPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        MediaFilter.entries.forEach { filter ->
                            SlimeXChip(
                                text = filter.label,
                                selected = state.filter == filter,
                                onClick = { viewModel.setFilter(filter) },
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Box {
                            SlimeXChip(
                                text = "Sort",
                                icon = Icons.Rounded.Sort,
                                onClick = { sortMenuOpen = true },
                            )
                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { sortMenuOpen = false },
                            ) {
                                MediaSort.entries.forEach { sort ->
                                    DropdownMenuItem(
                                        text = { Text(text = sort.label, color = TextPrimary) },
                                        onClick = {
                                            sortMenuOpen = false
                                            viewModel.setSort(sort)
                                        },
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.sm))

                    Box(modifier = Modifier.fillMaxSize()) {
                        when {
                            state.loading && state.assets.isEmpty() -> Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(color = SlimePurple)
                            }

                            state.assets.isEmpty() -> EmptyState(
                                icon = Icons.Rounded.PhotoLibrary,
                                title = "Nothing here yet",
                                message = if (state.search.isNotBlank()) {
                                    "No file matches \"${state.search}\"."
                                } else {
                                    "Captures appear here after their upload has been verified."
                                },
                                actionLabel = "Refresh",
                                onAction = { viewModel.refresh() },
                            )

                            else -> LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                contentPadding = PaddingValues(Spacing.cardPadding),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(state.assets, key = { it.id }) { media ->
                                    GalleryTile(
                                        media = media,
                                        thumbnailPath = state.thumbnails[media.id],
                                        onEnsureThumbnail = { viewModel.ensureThumbnail(media) },
                                        onClick = { viewModel.openViewer(media) },
                                    )
                                }
                            }
                        }

                        if (state.loading && state.assets.isNotEmpty()) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter),
                                color = SlimePurple,
                            )
                        }
                    }
                }
            }
        }

        state.viewer?.let { viewer ->
            MediaViewer(
                viewer = viewer,
                sharing = state.sharingId == viewer.media.id,
                onClose = viewModel::closeViewer,
                onShare = { viewModel.share(viewer.media) },
                onDelete = { pendingDelete = viewer.media },
            )
        }
    }

    pendingDelete?.let { media ->
        SlimeXConfirmDialog(
            title = "Delete from cloud?",
            message = "This removes \"${media.fileName}\" from your private bucket permanently. " +
                "Uploads that are still pending are not affected.",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                viewModel.delete(media)
            },
        )
    }
}

@Composable
private fun GalleryTile(
    media: CloudMedia,
    thumbnailPath: String?,
    onEnsureThumbnail: () -> Unit,
    onClick: () -> Unit,
) {
    LaunchedEffect(media.id) { onEnsureThumbnail() }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(CharcoalElevated)
            .border(1.dp, OutlineSoft, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        if (thumbnailPath != null) {
            AsyncImage(
                model = File(thumbnailPath),
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
                    imageVector = if (media.mediaType == MediaKind.VIDEO) Icons.Rounded.Movie
                    else Icons.Rounded.PhotoLibrary,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        if (media.mediaType == MediaKind.VIDEO) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(11.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = Formatters.bytes(media.fileSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun MediaViewer(
    viewer: CloudGalleryViewModel.ViewerState,
    sharing: Boolean,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    BackHandler { onClose() }

    var showDetails by remember { mutableStateOf(true) }
    val media = viewer.media

    Box(modifier = Modifier.fillMaxSize().background(PureBlack)) {
        when {
            viewer.filePath != null && media.mediaType == MediaKind.PHOTO -> AsyncImage(
                model = File(viewer.filePath),
                contentDescription = media.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            viewer.filePath != null -> VideoPlayer(filePath = viewer.filePath, onTap = {
                showDetails = !showDetails
            })

            else -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (viewer.error != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.CloudOff,
                            contentDescription = null,
                            tint = ErrorRed,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = viewer.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                        )
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = SlimePurple)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Downloading & decrypting… ${(viewer.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(
                icon = Icons.Rounded.Visibility,
                contentDescription = "Close viewer",
                onClick = onClose,
            )
            Spacer(modifier = Modifier.weight(1f))
            CircleIconButton(
                icon = Icons.Rounded.Share,
                contentDescription = "Share",
                enabled = viewer.filePath != null && !sharing,
                onClick = onShare,
            )
            Spacer(modifier = Modifier.width(10.dp))
            CircleIconButton(
                icon = Icons.Rounded.Delete,
                contentDescription = "Delete",
                tint = ErrorRed,
                onClick = onDelete,
            )
        }

        if (showDetails) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Text(
                    text = media.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${media.mediaType.value.replaceFirstChar { it.uppercase() }} · " +
                        "${Formatters.bytes(media.fileSize)} · uploaded ${Formatters.dateTime(media.createdAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(text = "AES-256-GCM", tone = PillTone.PURPLE)
                    StatusPill(text = "Private bucket", tone = PillTone.NEUTRAL)
                }
            }
        }
    }
}

@Composable
private fun VideoPlayer(filePath: String, onTap: () -> Unit) {
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                setVideoPath(filePath)
                val controller = MediaController(context)
                controller.setAnchorView(this)
                setMediaController(controller)
                setOnPreparedListener { player ->
                    player.isLooping = false
                    start()
                }
                setOnClickListener { onTap() }
            }
        },
        update = { view ->
            if (!view.isPlaying && view.currentPosition == 0) {
                runCatching { view.start() }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Color.White,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}
