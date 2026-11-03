package com.personal.fittrack.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFFD9480F), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0D0), onPrimaryContainer = Color(0xFF3B1100),
    secondary = Color(0xFF2F6F5E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEBE0), onSecondaryContainer = Color(0xFF00201A),
    tertiary = Color(0xFF3F5E9A),
    background = Color(0xFFFFFBF8), surface = Color(0xFFFFFBF8),
    surfaceVariant = Color(0xFFF0E4DE), onSurfaceVariant = Color(0xFF52443D),
    error = Color(0xFFBA1A1A)
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB68F), onPrimary = Color(0xFF591D00),
    primaryContainer = Color(0xFF7F2D00), onPrimaryContainer = Color(0xFFFFDBCB),
    secondary = Color(0xFF9FD0BF), onSecondary = Color(0xFF00382D),
    secondaryContainer = Color(0xFF144E41), onSecondaryContainer = Color(0xFFBBECDB),
    tertiary = Color(0xFFB0C6FF),
    background = Color(0xFF141211), surface = Color(0xFF141211),
    surfaceVariant = Color(0xFF52443D), onSurfaceVariant = Color(0xFFD7C2BA),
    error = Color(0xFFFFB4AB)
)

@Composable
fun FitTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
