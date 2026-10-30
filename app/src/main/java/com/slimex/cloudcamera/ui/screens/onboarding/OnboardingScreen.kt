package com.slimex.cloudcamera.ui.screens.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slimex.cloudcamera.ui.AppPermissions
import com.slimex.cloudcamera.ui.PermissionState
import com.slimex.cloudcamera.ui.components.BrandFooter
import com.slimex.cloudcamera.ui.components.BrandMark
import com.slimex.cloudcamera.ui.components.IconBadge
import com.slimex.cloudcamera.ui.components.OutlineInfoBox
import com.slimex.cloudcamera.ui.components.PermissionGrantedBadge
import com.slimex.cloudcamera.ui.components.SlimeCard
import com.slimex.cloudcamera.ui.components.SlimeXButton
import com.slimex.cloudcamera.ui.components.SlimeXButtonStyle
import com.slimex.cloudcamera.ui.components.SlimeXChip
import com.slimex.cloudcamera.ui.rememberPermissionState
import com.slimex.cloudcamera.ui.theme.PureBlack
import com.slimex.cloudcamera.ui.theme.SlimePurple
import com.slimex.cloudcamera.ui.theme.Spacing
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary
import com.slimex.cloudcamera.ui.theme.TextTertiary

/**
 * First-launch experience: brand → privacy → contextual permissions → optional cloud setup.
 * Nothing here can crash the app: every step is skippable and permissions are optional
 * (except camera, which the capture flow requests again when needed).
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onOpenCloudSetup: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val finishing by viewModel.finishing.collectAsStateWithLifecycle()
    val permissions = rememberPermissionState(AppPermissions.onboarding)

    LaunchedEffect(Unit) {
        viewModel.completed.collect { onFinished() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = Spacing.xxl),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.lg, bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandMark(size = 30.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "SlimeX Cloud",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (step < OnboardingViewModel.STEP_CLOUD) {
                    Text(
                        text = "Skip",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextTertiary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { viewModel.finish() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            AnimatedContent(
                targetState = step,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                transitionSpec = {
                    (fadeIn() + slideInHorizontally { it / 6 }) togetherWith
                        (fadeOut() + slideOutHorizontally { -it / 6 })
                },
                label = "onboarding-step",
            ) { current ->
                when (current) {
                    OnboardingViewModel.STEP_WELCOME -> WelcomeStep(onNext = viewModel::next)
                    OnboardingViewModel.STEP_PRIVACY -> PrivacyStep(onNext = viewModel::next)
                    OnboardingViewModel.STEP_PERMISSIONS -> PermissionsStep(
                        permissions = permissions,
                        onNext = viewModel::next,
                    )
                    else -> CloudStep(
                        onConfigure = { viewModel.goTo(OnboardingViewModel.STEP_CLOUD); onOpenCloudSetup() },
                        onSkip = viewModel::finish,
                        finishing = finishing,
                    )
                }
            }

            StepIndicator(
                total = OnboardingViewModel.TOTAL_STEPS,
                current = step,
                modifier = Modifier.padding(vertical = Spacing.md),
            )
            BrandFooter(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(Spacing.lg))
        }
    }
}


// --------------------------------------------------------------------------- steps

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(Spacing.xxxl))
        BrandMark(size = 96.dp)
        Spacer(modifier = Modifier.height(Spacing.xxl))
        Text(
            text = "SlimeX Cloud Camera",
            style = MaterialTheme.typography.displayMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Shoot in pure black. Keep every frame in your own cloud.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Spacing.xxxl))

        FeatureRow(
            icon = Icons.Rounded.PhotoCamera,
            title = "Real camera, real files",
            description = "CameraX capture with auto 1080p video and balanced JPEG photos.",
        )
        FeatureRow(
            icon = Icons.Rounded.CloudUpload,
            title = "Your Supabase, your storage",
            description = "You connect your own project after installing — no keys in the APK.",
        )
        FeatureRow(
            icon = Icons.Rounded.Lock,
            title = "Private by default",
            description = "Captures stay in app-private storage until you choose to upload.",
        )

        Spacer(modifier = Modifier.height(Spacing.xxl))
        SlimeXButton(
            text = "Get started",
            onClick = onNext,
            icon = Icons.Rounded.PhotoCamera,
        )
        Spacer(modifier = Modifier.height(Spacing.lg))
    }
}

@Composable
private fun PrivacyStep(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(Spacing.xxl))
        IconBadge(icon = Icons.Rounded.PrivacyTip, size = 52.dp)
        Spacer(modifier = Modifier.height(Spacing.lg))
        Text(
            text = "Privacy, in plain words",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = "This app was built to keep you in control of your footage. " +
                "There is no account, no analytics SDK and no third-party ad network.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )
        Spacer(modifier = Modifier.height(Spacing.xl))

        SlimeCard {
            Text(
                text = "What stays on your device",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Photos and videos are written to app-private internal storage. " +
                    "They are only visible to SlimeX and are removed when you uninstall the app.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.md))
        SlimeCard {
            Text(
                text = "What leaves your device",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Only files you upload, straight to the Supabase project you configure. " +
                    "Requests go to your project URL and nowhere else.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.md))
        SlimeCard {
            Text(
                text = "How credentials are stored",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Your project URL and publishable key are encrypted with the Android " +
                    "Keystore. Service keys are rejected, because they must never live in an app.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xxl))
        SlimeXButton(text = "I understand", onClick = onNext)
        Spacer(modifier = Modifier.height(Spacing.lg))
    }
}

@Composable
private fun PermissionsStep(permissions: PermissionState, onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(Spacing.xxl))
        IconBadge(icon = Icons.Rounded.PhotoCamera, size = 52.dp)
        Spacer(modifier = Modifier.height(Spacing.lg))
        Text(
            text = "Permissions, explained",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = "Each permission is requested only when it is needed. You can grant them " +
                "later from Settings and the app will still open without them.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )
        Spacer(modifier = Modifier.height(Spacing.xl))

        PermissionCard(
            icon = Icons.Rounded.PhotoCamera,
            title = "Camera",
            description = "Required to show the live preview and take photos or record video.",
            granted = permissions.isGranted(AppPermissions.CAMERA),
            onRequest = { permissions.request(AppPermissions.CAMERA) },
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        PermissionCard(
            icon = Icons.Rounded.Mic,
            title = "Microphone",
            description = "Optional. Only used when you record video with sound enabled.",
            granted = permissions.isGranted(AppPermissions.MICROPHONE),
            onRequest = { permissions.request(AppPermissions.MICROPHONE) },
        )
        if (AppPermissions.isNotificationPermissionRequired()) {
            Spacer(modifier = Modifier.height(Spacing.md))
            PermissionCard(
                icon = Icons.Rounded.Notifications,
                title = "Notifications",
                description = "Optional. Shows upload progress and failures in the shade.",
                granted = permissions.isGranted(AppPermissions.NOTIFICATIONS),
                onRequest = { permissions.request(AppPermissions.NOTIFICATIONS) },
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xl))
        OutlineInfoBox(
            text = "SlimeX never asks for storage permissions: captures live in app-private " +
                "storage, so no access to your photos or files is needed.",
        )

        Spacer(modifier = Modifier.height(Spacing.xxl))
        SlimeXButton(text = "Continue", onClick = onNext)
        Spacer(modifier = Modifier.height(Spacing.lg))
    }
}

@Composable
private fun CloudStep(
    onConfigure: () -> Unit,
    onSkip: () -> Unit,
    finishing: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(Spacing.xxl))
        IconBadge(icon = Icons.Rounded.CloudUpload, size = 52.dp, tint = SlimePurple)
        Spacer(modifier = Modifier.height(Spacing.lg))
        Text(
            text = "Connect your cloud",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = "SlimeX stores your media in a Supabase project that belongs to you. " +
                "You can configure it now or at any time later from Settings.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )
        Spacer(modifier = Modifier.height(Spacing.xl))

        SlimeCard {
            Text(
                text = "What you will need",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(10.dp))
            RequirementLine("Supabase project URL", "https://your-project.supabase.co")
            RequirementLine("Publishable (anon) key", "Settings → API → Publishable key")
            RequirementLine("SQL setup", "Run supabase/schema.sql and supabase/storage.sql once")
        }

        Spacer(modifier = Modifier.height(Spacing.lg))
        OutlineInfoBox(
            text = "Without a configuration the app still captures, previews and manages media " +
                "on this device. Uploads simply wait in the queue.",
        )

        Spacer(modifier = Modifier.height(Spacing.xxl))
        SlimeXButton(
            text = "Configure Supabase",
            onClick = onConfigure,
            icon = Icons.Rounded.CloudUpload,
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        SlimeXButton(
            text = if (finishing) "Finishing…" else "Skip for now",
            onClick = onSkip,
            style = SlimeXButtonStyle.SECONDARY,
            loading = finishing,
        )
        Spacer(modifier = Modifier.height(Spacing.lg))
    }
}

// --------------------------------------------------------------------------- pieces

@Composable
private fun FeatureRow(icon: ImageVector, title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconBadge(icon = icon, size = 40.dp)
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    SlimeCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = icon, size = 38.dp)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            if (granted) {
                PermissionGrantedBadge()
            } else {
                SlimeXChip(text = "Allow", onClick = onRequest)
            }
        }
    }
}

@Composable
private fun RequirementLine(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .background(SlimePurple, CircleShape),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
        }
    }
}

@Composable
private fun StepIndicator(total: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(width = if (active) 22.dp else 7.dp, height = 7.dp)
                    .background(
                        color = if (active) SlimePurple else TextTertiary.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(50),
                    ),
            )
        }
    }
}
