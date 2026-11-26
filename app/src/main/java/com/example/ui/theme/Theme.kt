package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val StudioDarkColorScheme = darkColorScheme(
    primary = TallyCrimson,
    onPrimary = StudioTextPrimary,
    primaryContainer = TallyCrimsonContainer,
    onPrimaryContainer = TallyCrimsonSoft,
    secondary = SignalCyan,
    onSecondary = StudioObsidian,
    secondaryContainer = SignalCyanContainer,
    onSecondaryContainer = SignalCyan,
    tertiary = LinkEmerald,
    onTertiary = StudioObsidian,
    tertiaryContainer = LinkEmeraldContainer,
    onTertiaryContainer = LinkEmerald,
    background = StudioObsidian,
    onBackground = StudioTextPrimary,
    surface = RackSlate,
    onSurface = StudioTextPrimary,
    surfaceVariant = RackSlateElevated,
    onSurfaceVariant = StudioTextSecondary,
    surfaceContainerLowest = StudioObsidian,
    surfaceContainerLow = RackSlate,
    surfaceContainer = RackSlateElevated,
    surfaceContainerHigh = RackSlateHigh,
    outline = RackBorder,
    outlineVariant = ColorTokens.SubtleDivider,
    error = TallyCrimson,
    onError = StudioTextPrimary,
    errorContainer = TallyCrimsonContainer,
    onErrorContainer = TallyCrimsonSoft
)

private object ColorTokens {
    val SubtleDivider = androidx.compose.ui.graphics.Color(0xFF202734)
}

val StudioShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun TallyLinkTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioDarkColorScheme,
        typography = Typography,
        shapes = StudioShapes,
        content = content
    )
}
