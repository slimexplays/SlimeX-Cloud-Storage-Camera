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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slimex.cloudcamera.core.util.Formatters
import com.slimex.cloudcamera.domain.model.ConnectionStep
import com.slimex.cloudcamera.domain.model.ConnectionStepStatus
import com.slimex.cloudcamera.ui.components.IconBadge
import com.slimex.cloudcamera.ui.components.InlineBanner
import com.slimex.cloudcamera.ui.components.PillTone
import com.slimex.cloudcamera.ui.components.SectionHeader
import com.slimex.cloudcamera.ui.components.SettingRow
import com.slimex.cloudcamera.ui.components.SlimeCard
import com.slimex.cloudcamera.ui.components.SlimeXButton
import com.slimex.cloudcamera.ui.components.SlimeXButtonStyle
import com.slimex.cloudcamera.ui.components.SlimeXConfirmDialog
import com.slimex.cloudcamera.ui.components.SlimeXTextField
import com.slimex.cloudcamera.ui.components.SlimeXTopBar
import com.slimex.cloudcamera.ui.components.StepRow
import com.slimex.cloudcamera.ui.components.StatusPill
import com.slimex.cloudcamera.ui.theme.Spacing
import com.slimex.cloudcamera.ui.theme.SuccessGreen
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary
import com.slimex.cloudcamera.ui.theme.TextTertiary
import kotlinx.coroutines.delay

@Composable
fun SupabaseConfigScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: SupabaseConfigViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(3800)
            viewModel.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        SlimeXTopBar(
            title = "Supabase setup",
            subtitle = "Settings → API",
            onBack = onBack,
            actions = {
                if (state.isConfigured) {
                    IconButton(onClick = viewModel::requestRemove) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Remove configuration",
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
            item {
                SlimeCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(
                            icon = if (state.isConfigured) Icons.Rounded.CloudDone else Icons.Rounded.Cloud,
                            size = 42.dp,
                            tint = if (state.isConfigured) SuccessGreen else com.slimex.cloudcamera.ui.theme.SlimePurple,
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (state.isConfigured) "Connected" else "Not configured",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                            )
                            Text(
                                text = state.savedHost ?: "Paste the project URL and publishable key below",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }
                        StatusPill(
                            text = if (state.isConfigured) "Active" else "Setup",
                            tone = if (state.isConfigured) PillTone.GREEN else PillTone.AMBER,
                        )
                    }
                    if (state.savedAt != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Saved ${Formatters.dateTime(state.savedAt ?: 0L)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary,
                        )
                    }
                }
            }

            if (state.storageWarning != null) {
                item {
                    InlineBanner(
                        text = state.storageWarning.orEmpty(),
                        tone = PillTone.AMBER,
                        icon = Icons.Rounded.Lock,
                    )
                }
            }

            if (state.message != null) {
                item {
                    InlineBanner(
                        text = state.message.orEmpty(),
                        tone = PillTone.PURPLE,
                        icon = Icons.Rounded.CloudDone,
                    )
                }
            }

            item {
                SectionHeader(title = "Credentials")
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard {
                    SlimeXTextField(
                        value = state.url,
                        onValueChange = viewModel::onUrlChange,
                        label = "Supabase project URL",
                        placeholder = "https://your-project.supabase.co",
                        helper = "Project Settings → Data API → Project URL",
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                        enabled = !state.saving && !state.testing,
                    )
                    Spacer(modifier = Modifier.height(Spacing.lg))
                    SlimeXTextField(
                        value = state.key,
                        onValueChange = viewModel::onKeyChange,
                        label = "Publishable key",
                        placeholder = "sb_publishable_… or legacy anon key",
                        helper = "Project Settings → API Keys → Publishable / anon key",
                        isPassword = !state.keyVisible,
                        error = state.fieldError,
                        imeAction = ImeAction.Done,
                        enabled = !state.saving && !state.testing,
                        trailing = {
                            IconButton(onClick = viewModel::toggleKeyVisibility) {
                                Icon(
                                    imageVector = if (state.keyVisible) {
                                        Icons.Rounded.VisibilityOff
                                    } else {
                                        Icons.Rounded.Visibility
                                    },
                                    contentDescription = if (state.keyVisible) "Hide key" else "Show key",
                                    tint = TextSecondary,
                                )
                            }
                        },
                    )
                }
            }

            item {
                SlimeCard {
                    SlimeXButton(
                        text = if (state.testing) "Testing connection…" else "Test connection",
                        onClick = viewModel::testConnection,
                        style = SlimeXButtonStyle.SECONDARY,
                        loading = state.testing,
                        enabled = state.url.isNotBlank() && state.key.isNotBlank() && !state.saving,
                        icon = Icons.Rounded.Cloud,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    SlimeXButton(
                        text = if (state.saving) "Saving…" else "Save configuration",
                        onClick = viewModel::save,
                        loading = state.saving,
                        enabled = state.canSubmit,
                        icon = Icons.Rounded.Lock,
                    )
                    if (state.isConfigured) {
                        Spacer(modifier = Modifier.height(10.dp))
                        SlimeXButton(
                            text = "Remove configuration",
                            onClick = viewModel::requestRemove,
                            style = SlimeXButtonStyle.DANGER,
                            icon = Icons.Rounded.DeleteOutline,
                        )
                    }
                }
            }

            val report = state.report
            if (report != null) {
                item {
                    SectionHeader(
                        title = "Connection checks",
                        subtitle = if (report.success) "All required checks passed" else "Review the failing step",
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    SlimeCard {
                        report.steps.forEachIndexed { index, step ->
                            StepRow(
                                title = step.title,
                                detail = step.detail,
                                tone = step.pillTone(),
                                statusLabel = step.label(),
                            )
                            if (index != report.steps.lastIndex) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }

            item {
                SectionHeader(title = "Project setup")
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard(contentPadding = PaddingValues(0.dp)) {
                    SettingRow(
                        title = "Storage bucket",
                        subtitle = "Private bucket that holds your files",
                        icon = Icons.Rounded.Cloud,
                        trailing = {
                            Text(
                                text = state.bucket,
                                style = MaterialTheme.typography.labelMedium,
                                color = com.slimex.cloudcamera.ui.theme.SlimePurple,
                            )
                        },
                        showDivider = true,
                    )
                    SettingRow(
                        title = "Media table",
                        subtitle = "Metadata rows used by the gallery",
                        icon = Icons.Rounded.CloudDone,
                        trailing = {
                            Text(
                                text = state.table,
                                style = MaterialTheme.typography.labelMedium,
                                color = com.slimex.cloudcamera.ui.theme.SlimePurple,
                            )
                        },
                    )
                }
            }

            item {
                SlimeCard {
                    Text(
                        text = "One-time SQL setup",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "1. Open the Supabase SQL editor.\n" +
                            "2. Run supabase/schema.sql (table, indexes, row level security).\n" +
                            "3. Run supabase/storage.sql (private bucket + policies).\n" +
                            "4. Return here and tap Test connection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Both scripts ship with this project — see the supabase/ folder in the repository.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Keys are encrypted with the Android Keystore. Service role and secret " +
                            "keys are rejected: this app only ever uses the public client key.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                }
            }
        }
    }

    if (state.showRemoveDialog) {
        SlimeXConfirmDialog(
            title = "Remove configuration?",
            message = "The stored project URL and key are erased from this device. " +
                "Media already uploaded stays in your Supabase project.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = viewModel::removeConfiguration,
            onDismiss = viewModel::dismissRemove,
        )
    }
}

private fun ConnectionStep.pillTone(): PillTone = when (status) {
    ConnectionStepStatus.PASSED -> PillTone.GREEN
    ConnectionStepStatus.FAILED -> PillTone.RED
    ConnectionStepStatus.WARNING -> PillTone.AMBER
    ConnectionStepStatus.RUNNING -> PillTone.PURPLE
    ConnectionStepStatus.SKIPPED -> PillTone.NEUTRAL
    ConnectionStepStatus.PENDING -> PillTone.NEUTRAL
}

private fun ConnectionStep.label(): String = when (status) {
    ConnectionStepStatus.PASSED -> "Passed"
    ConnectionStepStatus.FAILED -> "Failed"
    ConnectionStepStatus.WARNING -> "Warning"
    ConnectionStepStatus.RUNNING -> "Testing"
    ConnectionStepStatus.SKIPPED -> "Skipped"
    ConnectionStepStatus.PENDING -> "Pending"
}
