package com.slimex.cloudcamera.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.slimex.cloudcamera.ui.theme.CharcoalElevated
import com.slimex.cloudcamera.ui.theme.ErrorRed
import com.slimex.cloudcamera.ui.theme.OutlineMedium
import com.slimex.cloudcamera.ui.theme.PurpleGradient
import com.slimex.cloudcamera.ui.theme.SlimePurple
import com.slimex.cloudcamera.ui.theme.TextPrimary
import com.slimex.cloudcamera.ui.theme.TextSecondary

enum class SlimeXButtonStyle { PRIMARY, SECONDARY, GHOST, DANGER, SUCCESS }

/**
 * The single button used across the app. Supports an inline spinner so no screen ever
 * needs a fake or dead button while work is in flight.
 */
@Composable
fun SlimeXButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    style: SlimeXButtonStyle = SlimeXButtonStyle.PRIMARY,
    fillWidth: Boolean = true,
    height: Dp = 52.dp,
) {
    val shape = RoundedCornerShape(16.dp)
    val interactive = enabled && !loading

    val background: Modifier = when (style) {
        SlimeXButtonStyle.PRIMARY -> Modifier.background(Brush.horizontalGradient(PurpleGradient))
        SlimeXButtonStyle.SECONDARY -> Modifier.background(CharcoalElevated)
        SlimeXButtonStyle.GHOST -> Modifier
        SlimeXButtonStyle.DANGER -> Modifier.background(ErrorRed.copy(alpha = 0.14f))
        SlimeXButtonStyle.SUCCESS -> Modifier.background(SlimePurple.copy(alpha = 0.16f))
    }

    val border: Modifier = when (style) {
        SlimeXButtonStyle.SECONDARY -> Modifier.border(BorderStroke(1.dp, OutlineMedium), shape)
        SlimeXButtonStyle.DANGER -> Modifier.border(BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f)), shape)
        else -> Modifier
    }

    val contentColor = when (style) {
        SlimeXButtonStyle.PRIMARY -> Color.White
        SlimeXButtonStyle.SECONDARY -> TextPrimary
        SlimeXButtonStyle.GHOST -> SlimePurple
        SlimeXButtonStyle.DANGER -> ErrorRed
        SlimeXButtonStyle.SUCCESS -> SlimePurple
    }

    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .height(height)
            .clip(shape)
            .then(background)
            .then(border)
            .clickable(enabled = interactive, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(17.dp),
                    color = contentColor,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(10.dp))
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor.copy(alpha = if (interactive) 1f else 0.45f),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** Small pill shaped action (Used inside cards, dialogs and list rows). */
@Composable
fun SlimeXChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(50)
    val background = if (selected) SlimePurple.copy(alpha = 0.18f) else CharcoalElevated
    val border = if (selected) SlimePurple.copy(alpha = 0.55f) else OutlineMedium
    val contentColor = when {
        !enabled -> TextSecondary.copy(alpha = 0.4f)
        selected -> SlimePurple
        else -> TextSecondary
    }
    Row(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** Circular action used inside the camera overlay. */
@Composable
fun CircularActionButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = TextPrimary,
    background: Color = Color.Black.copy(alpha = 0.45f),
    size: Dp = 44.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.48f),
        )
    }
}

@Composable
fun PermissionGrantedBadge(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = SlimePurple,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "Granted",
            style = MaterialTheme.typography.labelSmall,
            color = SlimePurple,
        )
    }
}
