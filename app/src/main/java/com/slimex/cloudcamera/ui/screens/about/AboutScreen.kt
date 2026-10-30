package com.slimex.cloudcamera.ui.screens.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.slimex.cloudcamera.BuildConfig
import com.slimex.cloudcamera.core.constants.AppConstants
import com.slimex.cloudcamera.ui.components.BrandFooter
import com.slimex.cloudcamera.ui.components.BrandMark
import com.slimex.cloudcamera.ui.components.InfoRow
import com.slimex.cloudcamera.ui.components.SectionHeader
import com.slimex.cloudcamera.ui.components.SlimeCard
import com.slimex.cloudcamera.ui.components.SlimeXTopBar
import com.slimex.cloudcamera.ui.theme.Spacing
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary
import com.slimex.cloudcamera.ui.theme.TextTertiary

@Composable
fun AboutScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        SlimeXTopBar(title = "About", onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.screenPadding,
                end = Spacing.screenPadding,
                top = Spacing.md,
                bottom = Spacing.xxxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BrandMark(size = 84.dp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = AppConstants.APP_NAME,
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    BrandFooter()
                }
            }

            item {
                SlimeCard {
                    InfoRow(label = "Package", value = BuildConfig.APPLICATION_ID)
                    InfoRow(label = "Developer", value = "Slime Plays")
                    InfoRow(label = "Camera engine", value = "CameraX")
                    InfoRow(label = "Cloud backend", value = "Supabase (your own project)")
                    InfoRow(label = "Minimum Android", value = "8.0 (API 26)")
                }
            }

            item {
                SectionHeader(title = "What this app does")
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard {
                    Text(
                        text = "SlimeX Cloud Camera captures photos and video with a pure black, " +
                            "distraction free interface and uploads them to a Supabase project you " +
                            "control. There is no SlimeX server, no account and no telemetry.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }

            item {
                SectionHeader(title = "Permissions used")
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard {
                    Text(
                        text = "• Camera — live preview and capture\n" +
                            "• Microphone — only for video with sound\n" +
                            "• Notifications (Android 13+) — upload status\n" +
                            "• Internet & network state — Supabase uploads",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No storage, contacts, location or background-location permissions are requested.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                }
            }

            item {
                SlimeCard {
                    Text(
                        text = "Credentials live in Keystore-encrypted storage on this device and can " +
                            "be removed at any time from the Supabase setup screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
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
                        text = AppConstants.DEVELOPER_CREDIT,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextTertiary,
                    )
                }
            }
        }
    }
}
