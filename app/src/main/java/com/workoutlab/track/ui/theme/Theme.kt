package com.workoutlab.track.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Forest = Color(0xFF0B6E4F)
private val ForestDark = Color(0xFF7DCEA0)
private val StopRed = Color(0xFFB42318)
private val VehicleAmber = Color(0xFFB45309)

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    secondary = VehicleAmber,
    error = StopRed,
    background = Color(0xFFF6F7F4),
    surface = Color.White,
    onBackground = Color(0xFF1A1C19),
    onSurface = Color(0xFF1A1C19),
)

private val DarkColors = darkColorScheme(
    primary = ForestDark,
    onPrimary = Color(0xFF003822),
    secondary = Color(0xFFE3A857),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF121412),
    surface = Color(0xFF1C1F1C),
    onBackground = Color(0xFFE2E3DE),
    onSurface = Color(0xFFE2E3DE),
)

@Composable
fun WorkoutLabTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
