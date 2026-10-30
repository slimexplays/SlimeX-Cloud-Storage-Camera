package com.slimex.cloudcamera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SlimeXDarkScheme = darkColorScheme(
    primary = SlimePurple,
    onPrimary = Color.White,
    primaryContainer = SlimePurpleDeep,
    onPrimaryContainer = Color.White,
    inversePrimary = SlimePurpleLight,
    secondary = SlimePurpleLight,
    onSecondary = PureBlack,
    secondaryContainer = SlimePurpleMuted,
    onSecondaryContainer = SlimePurpleLight,
    tertiary = InfoBlue,
    onTertiary = PureBlack,
    background = PureBlack,
    onBackground = TextPrimary,
    surface = Charcoal,
    onSurface = TextPrimary,
    surfaceVariant = CharcoalElevated,
    onSurfaceVariant = TextSecondary,
    surfaceTint = SlimePurple,
    surfaceContainer = Charcoal,
    surfaceContainerHigh = CharcoalElevated,
    surfaceContainerHighest = CharcoalSoft,
    outline = OutlineSoft,
    outlineVariant = OutlineMedium,
    error = ErrorRed,
    onError = PureBlack,
    errorContainer = Color(0xFF3A1318),
    onErrorContainer = ErrorRed,
    scrim = PureBlack,
)

/**
 * The app is dark-only by design (pure black + purple). There is deliberately no light
 * theme: the camera surface and OLED display both need true black.
 */
@Composable
fun SlimeXTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SlimeXDarkScheme,
        typography = SlimeXTypography,
        shapes = SlimeXShapes,
        content = content,
    )
}
