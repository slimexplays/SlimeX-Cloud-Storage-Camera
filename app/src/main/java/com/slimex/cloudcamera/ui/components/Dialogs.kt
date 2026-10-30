package com.slimex.cloudcamera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.slimex.cloudcamera.ui.theme.Charcoal
import com.slimex.cloudcamera.ui.theme.CharcoalElevated
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary

/**
 * One dialog component for every confirmation in the app. Destructive actions are
 * visually distinct and never auto-confirmed.
 */
@Composable
fun SlimeXConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Charcoal,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
        },
        text = {
            Column {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                if (extraContent != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    extraContent()
                }
            }
        },
        confirmButton = {
            SlimeXButton(
                text = confirmLabel,
                onClick = onConfirm,
                style = if (destructive) SlimeXButtonStyle.DANGER else SlimeXButtonStyle.PRIMARY,
                fillWidth = false,
                height = 44.dp,
            )
        },
        dismissButton = {
            SlimeXButton(
                text = dismissLabel,
                onClick = onDismiss,
                style = SlimeXButtonStyle.GHOST,
                fillWidth = false,
                height = 44.dp,
            )
        },
    )
}

/** Informational dialog (privacy, permission rationale, connection details). */
@Composable
fun SlimeXInfoDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String = "Got it",
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Charcoal,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
        },
        text = {
            Column(content = content)
        },
        confirmButton = {
            SlimeXButton(
                text = confirmLabel,
                onClick = onDismiss,
                style = SlimeXButtonStyle.PRIMARY,
                fillWidth = false,
                height = 44.dp,
            )
        },
    )
}

/** Compact row used inside dialogs / cards to describe a single diagnostic step. */
@Composable
fun StepRow(
    title: String,
    detail: String,
    tone: PillTone,
    statusLabel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CharcoalElevated, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
            )
            if (detail.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        StatusPill(text = statusLabel, tone = tone, showDot = false)
    }
}
