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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VideoSettings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slimex.cloudcamera.BuildConfig
import com.slimex.cloudcamera.domain.model.CloudSetupStatus
import com.slimex.cloudcamera.domain.model.R2SetupStatus
import com.slimex.cloudcamera.ui.AppPermissions
import com.slimex.cloudcamera.ui.components.BrandFooter
import com.slimex.cloudcamera.ui.components.InlineBanner
import com.slimex.cloudcamera.ui.components.PillTone
import com.slimex.cloudcamera.ui.components.SectionHeader
import com.slimex.cloudcamera.ui.components.SettingRow
import com.slimex.cloudcamera.ui.components.SlimeCard
import com.slimex.cloudcamera.ui.components.SlimeXTopBar
import com.slimex.cloudcamera.ui.components.StatusPill
import com.slimex.cloudcamera.ui.rememberPermissionState
import com.slimex.cloudcamera.ui.theme.CharcoalElevated
import com.slimex.cloudcamera.ui.theme.OutlineSoft
import com.slimex.cloudcamera.ui.theme.SlimePurple
import com.slimex.cloudcamera.ui.theme.Spacing
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary
import com.slimex.cloudcamera.ui.theme.TextTertiary

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onOpenCloudSetup: () -> Unit,
    onOpenCameraPreferences: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenSecurity: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permissions = rememberPermissionState(listOf(AppPermissions.NOTIFICATIONS))

    val notificationEnabled = state.settings.notificationsEnabled &&
        (!AppPermissions.isNotificationPermissionRequired() ||
            permissions.isGranted(AppPermissions.NOTIFICATIONS))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        SlimeXTopBar(
            title = "Settings",
            subtitle = "Version ${BuildConfig.VERSION_NAME}",
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
            if (state.secureStorageWarning != null) {
                item {
                    InlineBanner(
                        text = state.secureStorageWarning,
                        tone = PillTone.AMBER,
                        icon = Icons.Rounded.Lock,
                    )
                }
            }

            item {
                SectionHeader(title = "Cloud")
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard(contentPadding = PaddingValues(0.dp)) {
                    SettingRow(
                        title = "Account",
                        subtitle = state.signedInEmail ?: "Sign in to upload and browse your library",
                        icon = Icons.Rounded.Person,
                        onClick = onOpenAccount,
                        showDivider = true,
                        trailing = {
                            StatusPill(
                                text = if (state.signedInEmail != null) "Signed in" else "Sign in",
                                tone = if (state.signedInEmail != null) PillTone.GREEN else PillTone.AMBER,
                            )
                        },
                    )
                    SettingRow(
                        title = "Cloud configuration",
                        subtitle = buildString {
                            append(
                                when (val status = state.cloudStatus) {
                                    is CloudSetupStatus.Configured -> "Supabase: ${status.host}"
                                    CloudSetupStatus.NotConfigured -> "Supabase: not configured"
                                    CloudSetupStatus.Loading -> "Supabase: checking…"
                                },
                            )
                            append(" · ")
                            append(
                                when (val status = state.r2Status) {
                                    is R2SetupStatus.Configured -> "R2: ${status.host}"
                                    R2SetupStatus.NotConfigured -> "R2: not configured"
                                    R2SetupStatus.Loading -> "R2: checking…"
                                },
                            )
                        },
                        icon = Icons.Rounded.Cloud,
                        onClick = onOpenCloudSetup,
                        showDivider = true,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusPill(
                                    text = if (state.cloudStatus is CloudSetupStatus.Configured &&
                                        state.r2Status is R2SetupStatus.Configured
                                    ) {
                                        "Ready"
                                    } else {
                                        "Setup"
                                    },
                                    tone = if (state.cloudStatus is CloudSetupStatus.Configured &&
                                        state.r2Status is R2SetupStatus.Configured
                                    ) {
                                        PillTone.GREEN
                                    } else {
                                        PillTone.AMBER
                                    },
                                )
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = TextTertiary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                    SettingRow(
                        title = "Automatic upload",
                        subtitle = "Queue every capture as soon as it is saved",
                        icon = Icons.Rounded.CloudUpload,
                        showDivider = true,
                        trailing = {
                            SlimeXSwitch(
                                checked = state.settings.autoUpload,
                                onCheckedChange = viewModel::setAutoUpload,
                            )
                        },
                    )
                    SettingRow(
                        title = "Wi-Fi only",
                        subtitle = "Hold uploads until you are on Wi-Fi",
                        icon = Icons.Rounded.Wifi,
                        showDivider = true,
                        trailing = {
                            SlimeXSwitch(
                                checked = state.settings.wifiOnlyUpload,
                                onCheckedChange = viewModel::setWifiOnly,
                            )
                        },
                    )
                    SettingRow(
                        title = "Zero local storage",
                        subtitle = "Block capture while offline so nothing waits on the device",
                        icon = Icons.Rounded.CloudOff,
                        trailing = {
                            SlimeXSwitch(
                                checked = state.settings.strictZeroLocalStorage,
                                onCheckedChange = viewModel::setStrictZeroLocalStorage,
                            )
                        },
                    )
                }
            }

            item {
                SectionHeader(title = "Security")
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard(contentPadding = PaddingValues(0.dp)) {
                    SettingRow(
                        title = "Encryption & recovery",
                        subtitle = "AES-256-GCM key vault and recovery code",
                        icon = Icons.Rounded.Key,
                        onClick = onOpenSecurity,
                        trailing = {
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }

            item {
                SectionHeader(title = "Camera")
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard(contentPadding = PaddingValues(0.dp)) {
                    SettingRow(
                        title = "Photo quality",
                        subtitle = state.settings.photoQuality.description,
                        icon = Icons.Rounded.PhotoCamera,
                        onClick = onOpenCameraPreferences,
                        showDivider = true,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = state.settings.photoQuality.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SlimePurple,
                                )
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = TextTertiary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                    SettingRow(
                        title = "Video quality",
                        subtitle = state.settings.videoQuality.description,
                        icon = Icons.Rounded.VideoSettings,
                        onClick = onOpenCameraPreferences,
                        showDivider = true,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = state.settings.videoQuality.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = SlimePurple,
                                )
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = TextTertiary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                    SettingRow(
                        title = "Record audio in video",
                        subtitle = "Requires the microphone permission",
                        icon = Icons.Rounded.Mic,
                        trailing = {
                            SlimeXSwitch(
                                checked = state.settings.recordVideoAudio,
                                onCheckedChange = viewModel::setRecordAudio,
                            )
                        },
                    )
                }
            }

            item {
                SectionHeader(title = "Device")
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard(contentPadding = PaddingValues(0.dp)) {
                    SettingRow(
                        title = "Storage",
                        subtitle = "Local media and cloud storage usage",
                        icon = Icons.Rounded.Storage,
                        onClick = onOpenStorage,
                        showDivider = true,
                        trailing = {
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    SettingRow(
                        title = "Upload notifications",
                        subtitle = if (AppPermissions.isNotificationPermissionRequired()) {
                            "Android 13+ requires the notification permission"
                        } else {
                            "Show upload progress in the notification shade"
                        },
                        icon = Icons.Rounded.Notifications,
                        trailing = {
                            SlimeXSwitch(
                                checked = notificationEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.setNotifications(enabled)
                                    if (enabled && AppPermissions.isNotificationPermissionRequired() &&
                                        !permissions.isGranted(AppPermissions.NOTIFICATIONS)
                                    ) {
                                        permissions.request(AppPermissions.NOTIFICATIONS)
                                    }
                                },
                            )
                        },
                    )
                }
            }

            item {
                SectionHeader(title = "About")
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard(contentPadding = PaddingValues(0.dp)) {
                    SettingRow(
                        title = "About SlimeX Cloud Camera",
                        subtitle = "Version ${BuildConfig.VERSION_NAME} · ${BuildConfig.APPLICATION_ID}",
                        icon = Icons.Rounded.Info,
                        onClick = onOpenAbout,
                        trailing = {
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (state.cloudStatus is CloudSetupStatus.Configured) {
                            "Connected to ${(state.cloudStatus as CloudSetupStatus.Configured).host}"
                        } else {
                            "No cloud project connected"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BrandFooter()
                }
            }
        }
    }
}

@Composable
private fun SlimeXSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = TextPrimary,
            checkedTrackColor = SlimePurple,
            uncheckedThumbColor = TextSecondary,
            uncheckedTrackColor = CharcoalElevated,
            uncheckedBorderColor = OutlineSoft,
        ),
    )
}
