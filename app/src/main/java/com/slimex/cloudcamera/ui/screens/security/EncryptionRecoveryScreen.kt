package com.slimex.cloudcamera.ui.screens.security

import android.content.Intent
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slimex.cloudcamera.core.util.Formatters
import com.slimex.cloudcamera.ui.components.BrandFooter
import com.slimex.cloudcamera.ui.components.InlineBanner
import com.slimex.cloudcamera.ui.components.InfoRow
import com.slimex.cloudcamera.ui.components.LoadingState
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
 * Encryption & Recovery: the single place where the user can see how their media is protected,
 * export a recovery code and restore one. Everything here runs on the device only.
 */
@Composable
fun EncryptionRecoveryScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: EncryptionRecoveryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        SlimeXTopBar(
            title = "Encryption & Recovery",
            subtitle = "AES-256-GCM · keys never leave this device",
            onBack = onBack,
        )

        if (state.loading) {
            LoadingState(message = "Checking the key vault…")
            return@Column
        }

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
            if (!state.vaultAvailable) {
                item {
                    InlineBanner(
                        text = "The Android Keystore is not available on this device, so new keys " +
                            "cannot be protected. Uploads stay disabled until secure storage works.",
                        tone = PillTone.RED,
                        icon = Icons.Rounded.Warning,
                    )
                }
            }

            state.error?.let { error ->
                item {
                    InlineBanner(
                        text = error,
                        tone = PillTone.RED,
                        icon = Icons.Rounded.Warning,
                        onDismiss = viewModel::dismissError,
                    )
                }
            }

            state.message?.let { message ->
                item {
                    InlineBanner(
                        text = message,
                        tone = PillTone.GREEN,
                        icon = Icons.Rounded.Lock,
                        onDismiss = viewModel::dismissMessage,
                    )
                }
            }

            item {
                SectionHeader(title = "Key vault")
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Status",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        StatusPill(
                            text = when {
                                !state.vaultAvailable -> "Unavailable"
                                state.hasMasterKey -> "Protected"
                                else -> "Not created"
                            },
                            tone = when {
                                !state.vaultAvailable -> PillTone.RED
                                state.hasMasterKey -> PillTone.GREEN
                                else -> PillTone.AMBER
                            },
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    InfoRow(label = "Encryption", value = "AES-256-GCM (authenticated)")
                    InfoRow(label = "Key length", value = "256-bit, unique per file")
                    InfoRow(label = "Nonce", value = "96-bit, never reused")
                    InfoRow(label = "Keys stored on device", value = state.keyCount.toString())
                    InfoRow(
                        label = "Last recovery code",
                        value = state.lastExportMillis?.let { Formatters.dateTime(it) } ?: "Never",
                    )
                    state.storeError?.let { error ->
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        InlineBanner(text = error, tone = PillTone.AMBER, icon = Icons.Rounded.Warning)
                    }
                }
            }

            item {
                SlimeCard {
                    Text(
                        text = "How your media is protected",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    BulletLine("Every capture is encrypted before it leaves the app, with a fresh random 256-bit key and a fresh 96-bit nonce.")
                    BulletLine("Only ciphertext is uploaded, straight to your private Cloudflare R2 bucket; the app never sends a key, a nonce or a password anywhere.")
                    BulletLine("Keys are sealed with a key that lives in the Android Keystore, so another app (or a copied file) cannot read them.")
                    BulletLine("Decryption happens only when you open or share a file, and only into private app cache.")
                }
            }

            item {
                SectionHeader(title = "Backup")
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard {
                    Text(
                        text = "Create a recovery code",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = "The code contains your vault key and every file key, sealed with the " +
                            "passphrase you choose (PBKDF2-HMAC-SHA256, 210 000 rounds). Keep both safe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    SlimeXTextField(
                        value = state.passphrase,
                        onValueChange = viewModel::onPassphraseChange,
                        label = "Recovery passphrase",
                        helper = "At least 8 characters. This is never stored or uploaded.",
                        isPassword = true,
                        enabled = !state.busy,
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    SlimeXTextField(
                        value = state.confirmPassphrase,
                        onValueChange = viewModel::onConfirmPassphraseChange,
                        label = "Repeat passphrase",
                        isPassword = true,
                        enabled = !state.busy,
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    SlimeXButton(
                        text = "Create recovery code",
                        onClick = viewModel::createRecoveryCode,
                        icon = Icons.Rounded.Key,
                        enabled = state.canExport,
                        loading = state.busy,
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    OutlineInfoBox(
                        text = "If you lose both the code and the passphrase, encrypted cloud media can " +
                            "never be decrypted again — not by you, and not by SlimeX. There is no " +
                            "server side copy of your keys.",
                    )
                }
            }

            val exportedCode = state.exportedCode
            if (exportedCode != null) {
                item {
                    SlimeCard {
                        Text(
                            text = "Your recovery code",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            text = "Save it now. It is shown again only if you create a new one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        SelectionContainer {
                            Text(
                                text = exportedCode,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = TextPrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Spacing.xs),
                            )
                        }
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            SlimeXButton(
                                text = "Copy",
                                onClick = { clipboard.setText(AnnotatedString(exportedCode)) },
                                icon = Icons.Rounded.ContentCopy,
                                style = SlimeXButtonStyle.SECONDARY,
                                fillWidth = false,
                                modifier = Modifier.weight(1f),
                            )
                            SlimeXButton(
                                text = "Share",
                                onClick = {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "SlimeX recovery code")
                                        putExtra(Intent.EXTRA_TEXT, exportedCode)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(intent, "Share recovery code"),
                                    )
                                },
                                icon = Icons.Rounded.Share,
                                style = SlimeXButtonStyle.SECONDARY,
                                fillWidth = false,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        SlimeXButton(
                            text = "Hide code",
                            onClick = viewModel::discardExportedCode,
                            style = SlimeXButtonStyle.GHOST,
                        )
                    }
                }
            }

            item {
                SectionHeader(title = "Restore")
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard {
                    Text(
                        text = "Restore from a recovery code",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = "Use this after reinstalling the app or moving to a new phone, so your " +
                            "existing cloud media can be opened again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    SlimeXTextField(
                        value = state.codeInput,
                        onValueChange = viewModel::onCodeInputChange,
                        label = "Recovery code",
                        placeholder = "SLIMEX-KEY-1:…",
                        singleLine = false,
                        keyboardType = KeyboardType.Text,
                        enabled = !state.busy,
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    SlimeXTextField(
                        value = state.restorePassphrase,
                        onValueChange = viewModel::onRestorePassphraseChange,
                        label = "Passphrase used when the code was created",
                        isPassword = true,
                        enabled = !state.busy,
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    SlimeXButton(
                        text = "Restore keys",
                        onClick = viewModel::restoreKeys,
                        icon = Icons.Rounded.Restore,
                        enabled = state.canRestore,
                        loading = state.busy,
                    )
                }
            }

            item {
                SectionHeader(title = "Danger zone")
                Spacer(modifier = Modifier.height(Spacing.sm))
                SlimeCard {
                    Text(
                        text = "Reset local keys",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = "Deletes the vault key and every file key on this device. Cloud files " +
                            "stay in your bucket, but they become unreadable unless you have a " +
                            "recovery code created before the reset.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    SlimeXButton(
                        text = "Reset keys",
                        onClick = viewModel::requestReset,
                        icon = Icons.Rounded.Warning,
                        style = SlimeXButtonStyle.DANGER,
                        enabled = !state.busy,
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(Spacing.sm))
                BrandFooter(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (state.showResetDialog) {
        SlimeXConfirmDialog(
            title = "Reset local keys?",
            message = "This cannot be undone. Any cloud photo or video uploaded from this device " +
                "will stay encrypted but unreadable, unless you restore a recovery code afterwards.",
            confirmLabel = "Reset keys",
            dismissLabel = "Keep keys",
            destructive = true,
            onConfirm = viewModel::confirmReset,
            onDismiss = viewModel::cancelReset,
        )
    }
}

@Composable
private fun BulletLine(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}
