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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import com.slimex.cloudcamera.domain.model.PhotoQuality
import com.slimex.cloudcamera.domain.model.VideoQualityPreference
import com.slimex.cloudcamera.ui.components.OutlineInfoBox
import com.slimex.cloudcamera.ui.components.RadioOptionRow
import com.slimex.cloudcamera.ui.components.SectionHeader
import com.slimex.cloudcamera.ui.components.SlimeCard
import com.slimex.cloudcamera.ui.components.SlimeXTopBar
import com.slimex.cloudcamera.ui.theme.CharcoalElevated
import com.slimex.cloudcamera.ui.theme.OutlineSoft
import com.slimex.cloudcamera.ui.theme.SlimePurple
import com.slimex.cloudcamera.ui.theme.Spacing
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary

@Composable
fun CameraPreferencesScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: CameraPreferencesViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        SlimeXTopBar(
            title = "Camera quality",
            subtitle = "Photo & video capture preferences",
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
            item {
                SectionHeader(title = "Photo")
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard(contentPadding = PaddingValues(6.dp)) {
                    PhotoQuality.entries.forEach { quality ->
                        RadioOptionRow(
                            title = quality.label,
                            description = quality.description,
                            selected = settings.photoQuality == quality,
                            onClick = { viewModel.setPhotoQuality(quality) },
                        )
                    }
                }
            }

            item {
                SectionHeader(title = "Video")
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard(contentPadding = PaddingValues(6.dp)) {
                    VideoQualityPreference.entries.forEach { quality ->
                        RadioOptionRow(
                            title = quality.label,
                            description = quality.description,
                            selected = settings.videoQuality == quality,
                            onClick = { viewModel.setVideoQuality(quality) },
                        )
                    }
                }
            }

            item {
                SlimeCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Record audio",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                            )
                            Text(
                                text = "Uses the microphone while recording video",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = settings.recordVideoAudio,
                            onCheckedChange = viewModel::setRecordAudio,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TextPrimary,
                                checkedTrackColor = SlimePurple,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = CharcoalElevated,
                                uncheckedBorderColor = OutlineSoft,
                            ),
                        )
                    }
                }
            }

            item {
                OutlineInfoBox(
                    text = "Photos are always saved as JPEG at the best resolution the sensor " +
                        "supports for the selected mode — SlimeX never re-compresses an image it " +
                        "did not create.",
                )
            }

            item {
                OutlineInfoBox(
                    text = "Auto video quality asks the device for 1080p first and silently falls " +
                        "back to 720p (or the best supported size) when the camera or encoder " +
                        "cannot deliver it. The choice applies the next time the camera opens.",
                )
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = "Current selection: ${settings.photoQuality.label} photos · " +
                            "${settings.videoQuality.label} video",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}
