package com.anaalarm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E6E8E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6F5),
    onPrimaryContainer = Color(0xFF0B2A38),
    secondary = Color(0xFFE08A3C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDB8),
    onSecondaryContainer = Color(0xFF4A2B0A),
    background = Color(0xFFFFF8F2),
    onBackground = Color(0xFF241A10),
    surface = Color.White,
    onSurface = Color(0xFF241A10),
    surfaceVariant = Color(0xFFF4E7DA),
    onSurfaceVariant = Color(0xFF5C5042),
    error = Color(0xFFB3261E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FC4E8),
    onPrimary = Color(0xFF0B2A38),
    primaryContainer = Color(0xFF235065),
    onPrimaryContainer = Color(0xFFC8E6F5),
    secondary = Color(0xFFFFB36B),
    onSecondary = Color(0xFF4A2B0A),
    secondaryContainer = Color(0xFF6B4518),
    onSecondaryContainer = Color(0xFFFFDDB8),
    background = Color(0xFF14181D),
    onBackground = Color(0xFFE9E2D8),
    surface = Color(0xFF1C222A),
    onSurface = Color(0xFFE9E2D8),
    surfaceVariant = Color(0xFF2E343C),
    onSurfaceVariant = Color(0xFFBDB4A8),
    error = Color(0xFFF2B8B5)
)

@Composable
fun AnaAlarmTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
