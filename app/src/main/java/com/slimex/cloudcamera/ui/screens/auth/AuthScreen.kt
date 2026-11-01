package com.slimex.cloudcamera.ui.screens.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slimex.cloudcamera.domain.model.AuthStatus
import com.slimex.cloudcamera.ui.components.BrandMark
import com.slimex.cloudcamera.ui.components.InlineBanner
import com.slimex.cloudcamera.ui.components.PillTone
import com.slimex.cloudcamera.ui.components.SectionHeader
import com.slimex.cloudcamera.ui.components.SegmentedSelector
import com.slimex.cloudcamera.ui.components.SlimeCard
import com.slimex.cloudcamera.ui.components.SlimeXButton
import com.slimex.cloudcamera.ui.components.SlimeXButtonStyle
import com.slimex.cloudcamera.ui.components.SlimeXTextField
import com.slimex.cloudcamera.ui.components.SlimeXTopBar
import com.slimex.cloudcamera.ui.theme.Spacing
import com.slimex.cloudcamera.ui.theme.SuccessGreen
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * Sign in / create account against the user's own Supabase project.
 * The password is only sent to `auth/v1` — it is never stored anywhere on the device.
 */
@Composable
fun AuthScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenCloudSetup: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(3200)
            viewModel.consumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        SlimeXTopBar(
            title = "Account",
            subtitle = state.host ?: "Supabase not configured",
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            Spacer(modifier = Modifier.height(Spacing.md))
            BrandMark(size = 62.dp)
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = "Your private cloud library",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Sign in with an email and password created in your own Supabase project. " +
                    "Only you can read your files — the R2 bucket is private, signed URLs expire " +
                    "in minutes, and every metadata row is protected by RLS.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )

            Spacer(modifier = Modifier.height(Spacing.lg))

            when {
                !state.configured -> {
                    InlineBanner(
                        text = "Add your Supabase project URL and publishable key first.",
                        tone = PillTone.AMBER,
                        icon = Icons.Rounded.CloudOff,
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    SlimeXButton(
                        text = "Open cloud settings",
                        onClick = onOpenCloudSetup,
                        style = SlimeXButtonStyle.SECONDARY,
                    )
                }

                state.status is AuthStatus.SignedIn -> {
                    val signedIn = state.status as AuthStatus.SignedIn
                    SlimeCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = SuccessGreen,
                            )
                            Column(modifier = Modifier.padding(start = 10.dp)) {
                                Text(
                                    text = "Signed in",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary,
                                )
                                Text(
                                    text = signedIn.email,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(Spacing.md))
                    SlimeXButton(
                        text = "Sign out",
                        onClick = viewModel::signOut,
                        icon = Icons.Rounded.Logout,
                        style = SlimeXButtonStyle.DANGER,
                    )
                }

                else -> {
                    SegmentedSelector(
                        options = listOf("Sign in", "Create account"),
                        selectedIndex = if (state.createAccount) 1 else 0,
                        onSelected = { index ->
                            if ((index == 1) != state.createAccount) viewModel.toggleMode()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))

                    SlimeXTextField(
                        value = state.email,
                        onValueChange = viewModel::setEmail,
                        label = "Email",
                        placeholder = "you@example.com",
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    SlimeXTextField(
                        value = state.password,
                        onValueChange = viewModel::setPassword,
                        label = "Password",
                        helper = "At least 6 characters",
                        isPassword = true,
                        imeAction = ImeAction.Done,
                    )

                    if (state.error != null) {
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        InlineBanner(
                            text = state.error.orEmpty(),
                            tone = PillTone.RED,
                            onDismiss = viewModel::consumeError,
                        )
                    }
                    if (state.message != null) {
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        InlineBanner(
                            text = state.message.orEmpty(),
                            tone = PillTone.GREEN,
                            onDismiss = viewModel::consumeMessage,
                        )
                    }

                    Spacer(modifier = Modifier.height(Spacing.md))
                    SlimeXButton(
                        text = if (state.createAccount) "Create account" else "Sign in",
                        onClick = viewModel::submit,
                        enabled = state.canSubmit,
                        loading = state.busy,
                        icon = if (state.createAccount) Icons.Rounded.PersonAdd else Icons.Rounded.Lock,
                    )

                    if (state.createAccount) {
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        Text(
                            text = "If email confirmation is enabled in your Supabase project you will " +
                                "receive a link; confirm it, then sign in here.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.lg))
            SectionHeader(title = "Why sign in?")
            Spacer(modifier = Modifier.height(Spacing.sm))
            SlimeCard {
                Text(
                    text = "Storage policies use your signed-in identity, so uploads and downloads " +
                        "are scoped to your user id ({user_id}/{year}/{month}/{file}). An app-only key " +
                        "cannot read another person's media.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Spacer(modifier = Modifier.height(Spacing.xl))
        }
    }
}
