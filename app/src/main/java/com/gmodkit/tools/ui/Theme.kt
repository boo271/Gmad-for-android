package com.gmodkit.tools.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GmadColors = darkColorScheme(
    primary = Color(0xFFFF9800),
    onPrimary = Color(0xFF1A1200),
    primaryContainer = Color(0xFF3A2A00),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFFFB74D),
    onSecondary = Color(0xFF1A1200),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF3A3A3A),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF1A0000)
)

@Composable
fun GmadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GmadColors,
        content = content
    )
}
