package com.maxrave.simpmusic.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * Black and white, like the phone app's monochrome theme: filled buttons, the progress ring and the
 * volume arc are white with black content, list rows dark grey with white text. No accent colour.
 */
private val SimpMusicWearColors =
    ColorScheme(
        primary = Color.White,
        primaryDim = Color(0xFFE6E6E6),
        primaryContainer = Color(0xFF2E2E2E),
        onPrimary = Color.Black,
        onPrimaryContainer = Color.White,
        secondary = Color.White,
        secondaryDim = Color(0xFFE6E6E6),
        secondaryContainer = Color(0xFF2E2E2E),
        onSecondary = Color.Black,
        onSecondaryContainer = Color.White,
        tertiary = Color.White,
        tertiaryDim = Color(0xFFE6E6E6),
        tertiaryContainer = Color(0xFF2E2E2E),
        onTertiary = Color.Black,
        onTertiaryContainer = Color.White,
        surfaceContainerLow = Color(0xFF141414),
        surfaceContainer = Color(0xFF1F1F1F),
        surfaceContainerHigh = Color(0xFF2B2B2B),
        onSurface = Color.White,
        onSurfaceVariant = Color(0xFFBDBDBD),
        outline = Color(0xFF8A8A8A),
        outlineVariant = Color(0xFF444444),
        background = Color.Black,
        onBackground = Color.White,
    )

@Composable
fun SimpMusicWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SimpMusicWearColors, content = content)
}
