package com.example.limbuszhcn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LimbusDarkColorScheme = darkColorScheme(
    primary = LimbusRed,
    onPrimary = LimbusInk,
    primaryContainer = Color(0xFF43151A),
    onPrimaryContainer = Color(0xFFFFD9D8),
    secondary = LimbusGold,
    onSecondary = LimbusInk,
    secondaryContainer = Color(0xFF29241A),
    onSecondaryContainer = Color(0xFFF1E2B9),
    tertiary = LimbusSuccess,
    onTertiary = LimbusInk,
    background = LimbusInk,
    onBackground = Color(0xFFECE3D9),
    surface = LimbusSurface,
    onSurface = Color(0xFFF0E7DD),
    surfaceVariant = Color(0xFF29242B),
    onSurfaceVariant = Color(0xFFCFC4C1),
    outline = Color(0xFF5B5055)
)

@Composable
fun LimbusZhCNTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LimbusDarkColorScheme,
        typography = Typography,
        content = content
    )
}
