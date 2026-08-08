package com.vocahq.vocaphone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VocaPhoneLightColors = lightColorScheme(
    primary = Color(0xFF0F6B57),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5F3EE),
    onPrimaryContainer = Color(0xFF093E32),
    secondary = Color(0xFF53635D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8EEEA),
    onSecondaryContainer = Color(0xFF28332E),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF4F5F3),
    surfaceContainerHigh = Color(0xFFF0F1EF),
    background = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1C1B),
    onSurfaceVariant = Color(0xFF5A615D),
    outline = Color(0xFFBEC5C0),
    error = Color(0xFFB3261E),
)

private val VocaPhoneDarkColors = darkColorScheme(
    primary = Color(0xFF77D0B2),
    onPrimary = Color(0xFF003827),
    primaryContainer = Color(0xFF0A4D3B),
    onPrimaryContainer = Color(0xFFC3F3E0),
    secondary = Color(0xFFB8CAC1),
    onSecondary = Color(0xFF24342C),
    secondaryContainer = Color(0xFF34463D),
    onSecondaryContainer = Color(0xFFD4E8DE),
    surface = Color(0xFF1B1C1B),
    surfaceVariant = Color(0xFF252725),
    surfaceContainerHigh = Color(0xFF2A2C2A),
    background = Color(0xFF1B1C1B),
    onSurface = Color(0xFFE3E4E1),
    onSurfaceVariant = Color(0xFFC2C8C3),
    outline = Color(0xFF89928C),
    error = Color(0xFFFFB4AB),
)

/** A stable, quiet palette that does not inherit a device wallpaper's colours. */
@Composable
fun VocaPhoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) {
        VocaPhoneDarkColors
    } else {
        VocaPhoneLightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
