package com.slimex.cloudcamera.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slimex.cloudcamera.core.util.Formatters
import com.slimex.cloudcamera.ui.components.BrandFooter
import com.slimex.cloudcamera.ui.components.ConnectionReportCard
import com.slimex.cloudcamera.ui.components.IconBadge
import com.slimex.cloudcamera.ui.components.InlineBanner
import com.slimex.cloudcamera.ui.components.InfoRow
import com.slimex.cloudcamera.ui.components.OutlineInfoBox
import com.slimex.cloudcamera.ui.components.PillTone
import com.slimex.cloudcamera.ui.components.SectionHeader
import com.slimex.cloudcamera.ui.components.SlimeCard
import com.slimex.cloudcamera.ui.components.SlimeXButton
import com.slimex.cloudcamera.ui.components.SlimeXButtonStyle
import com.slimex.cloudcamera.ui.components.SlimeXConfirmDialog
import com.slimex.cloudcamera.ui.components.SlimeXTextField
import com.slimex.cloudcamera.ui.components.SlimeXTopBar
import com.slimex.cloudcamera.ui.components.StatusPill
import com.slimex.cloudcamera.ui.theme.Spacing
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary
import com.slimex.cloudcamera.ui.theme.TextTertiary

/**
 * Cloud configuration: two independent cards (Supabase, Cloudflare R2) plus the storage status.
 *
 * Supabase authenticates the user and stores metadata. Cloudflare R2 stores the encrypted photos
 * and videos, and is only ever reached through the user's Worker — the app never sees an R2 key.
 */
@Composable
fun CloudConfigurationScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    viewModel: CloudConfigurationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        SlimeXTopBar(
            title = "Cloud configuration",
            subtitle = "Supabase + Cloudflare R2",
            onBack = onBack,
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
            state.secureStorageWarning?.let { warning ->
                item {
                    InlineBanner(
                        text = warning,
                        tone = PillTone.AMBER,
                        icon = Icons.Rounded.Lock,
                    )
                }
            }

            state.message?.let { message ->
                item {
                    InlineBanner(
                        text = message,
                        tone = PillTone.GREEN,
                        icon = Icons.Rounded.Cloud,
                        onDismiss = viewModel::consumeMessage,
                    )
                }
            }

            item {
                SlimeCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(icon = Icons.Rounded.Shield)
                        Spacer(modifier = Modifier.size(Spacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Two services, two jobs",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Supabase signs you in and keeps the media metadata. " +
                                    "Cloudflare R2 stores the encrypted photos and videos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }
                    }
                }
            }

            // ------------------------------------------------------- Supabase
            item {
                SectionHeader(title = "Supabase", subtitle = "Authentication + PostgreSQL metadata")
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Project",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        StatusPill(
                            text = if (state.supabase.configured) "Connected" else "Not configured",
                            tone = if (state.supabase.configured) PillTone.GREEN else PillTone.AMBER,
                        )
                    }
                    if (state.supabase.savedHost != null) {
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            text = state.supabase.savedHost.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.md))
                    SlimeXTextField(
                        value = state.supabase.url,
                        onValueChange = viewModel::onSupabaseUrlChange,
                        label = "Project URL",
                        placeholder = "https://your-project.supabase.co",
                        keyboardType = KeyboardType.Uri,
                        error = state.supabase.fieldError,
                        enabled = !state.supabase.saving && !state.supabase.testing,
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    SlimeXTextField(
                        value = state.supabase.key,
                        onValueChange = viewModel::onSupabaseKeyChange,
                        label = "Publishable key (or legacy anon key)",
                        helper = "Never paste a service_role or secret key here.",
                        isPassword = !state.supabase.keyVisible,
                        enabled = !state.supabase.saving && !state.supabase.testing,
                        trailing = {
                            IconButton(onClick = viewModel::toggleSupabaseKeyVisibility) {
                                Icon(
                                    imageVector = if (state.supabase.keyVisible) {
                                        Icons.Rounded.VisibilityOff
                                    } else {
                                        Icons.Rounded.Visibility
                                    },
                                    contentDescription = if (state.supabase.keyVisible) "Hide key" else "Show key",
                                    tint = TextTertiary,
                                )
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SlimeXButton(
                            text = "Test connection",
                            onClick = viewModel::testSupabase,
                            style = SlimeXButtonStyle.SECONDARY,
                            icon = Icons.Rounded.Refresh,
                            loading = state.supabase.testing,
                            enabled = !state.supabase.saving,
                            fillWidth = false,
                            modifier = Modifier.weight(1f),
                        )
                        SlimeXButton(
                            text = "Save",
                            onClick = viewModel::saveSupabase,
                            icon = Icons.Rounded.Cloud,
                            loading = state.supabase.saving,
                            enabled = state.supabase.canSubmit,
                            fillWidth = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (state.supabase.configured) {
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        SlimeXButton(
                            text = "Disconnect",
                            onClick = viewModel::requestSupabaseDisconnect,
                            style = SlimeXButtonStyle.DANGER,
                            icon = Icons.Rounded.DeleteOutline,
                            enabled = !state.supabase.saving && !state.supabase.testing,
                        )
                    }
                }
            }

            state.supabase.report?.let { report ->
                item {
                    SectionHeader(
                        title = "Supabase checks",
                        subtitle = if (report.success) {
                            "All required checks passed in ${report.latencyMs} ms"
                        } else {
                            "Review the failing step"
                        },
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    ConnectionReportCard(report = report)
                }
            }

            // -------------------------------------------------- Cloudflare R2
            item {
                SectionHeader(title = "Cloudflare R2", subtitle = "Private bucket for encrypted media")
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Media provider",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        StatusPill(
                            text = if (state.r2.configured) "Connected" else "Not configured",
                            tone = if (state.r2.configured) PillTone.GREEN else PillTone.AMBER,
                        )
                    }
                    if (state.r2.savedHost != null) {
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            text = state.r2.savedHost.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.md))
                    SlimeXTextField(
                        value = state.r2.workerUrl,
                        onValueChange = viewModel::onWorkerUrlChange,
                        label = "Worker API endpoint",
                        placeholder = "https://slimex-media.your-name.workers.dev",
                        keyboardType = KeyboardType.Uri,
                        error = state.r2.fieldError,
                        enabled = !state.r2.saving && !state.r2.testing,
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    SlimeXTextField(
                        value = state.r2.providerName,
                        onValueChange = viewModel::onProviderNameChange,
                        label = "Storage provider name",
                        helper = "Shown in the app as the media provider label.",
                        enabled = !state.r2.saving && !state.r2.testing,
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SlimeXButton(
                            text = "Test connection",
                            onClick = viewModel::testR2,
                            style = SlimeXButtonStyle.SECONDARY,
                            icon = Icons.Rounded.Refresh,
                            loading = state.r2.testing,
                            enabled = !state.r2.saving,
                            fillWidth = false,
                            modifier = Modifier.weight(1f),
                        )
                        SlimeXButton(
                            text = "Save",
                            onClick = viewModel::saveR2,
                            icon = Icons.Rounded.CloudQueue,
                            loading = state.r2.saving,
                            enabled = state.r2.canSubmit,
                            fillWidth = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (state.r2.configured) {
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        SlimeXButton(
                            text = "Disconnect",
                            onClick = viewModel::requestR2Disconnect,
                            style = SlimeXButtonStyle.DANGER,
                            icon = Icons.Rounded.CloudOff,
                            enabled = !state.r2.saving && !state.r2.testing,
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.md))
                    OutlineInfoBox(
                        text = "Only this endpoint is stored on your phone. The R2 account id, access " +
                            "key, secret key and bucket name live inside the Cloudflare Worker as " +
                            "encrypted secrets — they are never in the APK, the app settings or the " +
                            "database.",
                    )
                }
            }

            state.r2.report?.let { report ->
                item {
                    SectionHeader(
                        title = "R2 checks",
                        subtitle = if (report.success) {
                            "All required checks passed in ${report.latencyMs} ms"
                        } else {
                            "Review the failing step"
                        },
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    ConnectionReportCard(report = report)
                }
            }

            // ------------------------------------------- Cloud storage status
            item {
                SectionHeader(
                    title = "Cloud storage status",
                    subtitle = "Locally recorded metadata vs. verified provider usage",
                    trailing = {
                        IconButton(onClick = viewModel::refreshStatus) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Refresh storage status",
                                tint = TextTertiary,
                            )
                        }
                    },
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard {
                    val status = state.status
                    InfoRow(label = "Provider", value = status.providerLabel)
                    InfoRow(
                        label = "Connection",
                        value = when {
                            !status.configured -> "Not configured"
                            status.loading -> "Checking…"
                            status.workerReachable == true -> "Reachable"
                            status.workerReachable == false -> "Unreachable"
                            else -> "Unknown"
                        },
                    )
                    InfoRow(
                        label = "Bucket",
                        value = when {
                            !status.configured -> "—"
                            status.bucketReady == true -> "Ready (private)"
                            status.bucketReady == false -> "Not reachable"
                            else -> "Unknown"
                        },
                    )
                    InfoRow(
                        label = "Verified usage",
                        value = status.verified?.let { Formatters.bytes(it.totalBytes) } ?: "—",
                    )
                    InfoRow(
                        label = "Recorded metadata",
                        value = status.metadata?.let { Formatters.bytes(it.totalBytes) } ?: "—",
                    )
                    InfoRow(
                        label = "Free quota",
                        value = status.freeQuotaBytes?.let { Formatters.bytes(it) }
                            ?: "Not reported by provider",
                    )
                    InfoRow(
                        label = "Files",
                        value = status.metadata?.let { "${it.assetCount} (${it.photoCount} photos · ${it.videoCount} videos)" }
                            ?: "—",
                    )
                    InfoRow(label = "Pending uploads", value = status.pendingUploads.toString())
                    InfoRow(label = "Failed uploads", value = status.failedUploads.toString())
                    InfoRow(
                        label = "Checked",
                        value = status.checkedAtMillis?.let { Formatters.relative(it) } ?: "Not yet",
                    )

                    if (status.requiresSignIn) {
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        InlineBanner(
                            text = "Sign in to your Supabase account to read verified storage usage.",
                            tone = PillTone.AMBER,
                            icon = Icons.Rounded.Key,
                            actionLabel = "Account",
                            onAction = onOpenAccount,
                        )
                    }
                    status.error?.let { error ->
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        InlineBanner(
                            text = error,
                            tone = PillTone.RED,
                            icon = Icons.Rounded.CloudOff,
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = "Verified usage is listed from your own bucket prefix by the Worker. " +
                            "Recorded metadata is what Supabase says you uploaded. They can differ " +
                            "when an upload is still finishing or a delete is in flight.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(Spacing.sm))
                BrandFooter(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (state.supabase.confirmDisconnect) {
        SlimeXConfirmDialog(
            title = "Disconnect Supabase?",
            message = "The project URL and key are erased from this device and you are signed out. " +
                "Metadata already stored in Supabase is untouched.",
            confirmLabel = "Disconnect",
            destructive = true,
            onConfirm = viewModel::disconnectSupabase,
            onDismiss = viewModel::cancelSupabaseDisconnect,
        )
    }

    if (state.r2.confirmDisconnect) {
        SlimeXConfirmDialog(
            title = "Disconnect Cloudflare R2?",
            message = "The Worker endpoint is erased from this device. Uploads and downloads stop " +
                "until you configure it again; nothing is deleted from your bucket.",
            confirmLabel = "Disconnect",
            destructive = true,
            onConfirm = viewModel::disconnectR2,
            onDismiss = viewModel::cancelR2Disconnect,
        )
    }
}
