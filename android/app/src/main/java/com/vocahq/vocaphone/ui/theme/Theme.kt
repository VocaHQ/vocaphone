package com.vocahq.vocaphone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The light containers are deliberately a full step darker than a Material
 * default would put them.
 *
 * When every group of settings lived in a `surfaceVariant` card, a container only
 * had to be told apart from *that card*, so near-white values worked. Now the
 * groups sit on the page and the page is `surface` — white — and anything filled
 * has to be visible against white instead: at #F4F5F3 a chip was 1.09:1 against
 * the page, which is to say invisible. Measured against #FFFFFF, the containers
 * below sit at 1.24 (chips), 1.23 (notices) and 1.29 (tonal buttons).
 *
 * `outline` moved for a different reason: at #BEC5C0 it was 1.76:1 on white, and
 * it draws the *unfinished* half of the setup checklist — the icons that most
 * need to be seen were the ones that could not be.
 */
internal val VocaPhoneLightColors = lightColorScheme(
    primary = Color(0xFF0F6B57),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E9E1),
    onPrimaryContainer = Color(0xFF093E32),
    secondary = Color(0xFF53635D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E5DF),
    onSecondaryContainer = Color(0xFF28332E),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE3E8E4),
    surfaceContainerHigh = Color(0xFFE3E9E5),
    background = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1C1B),
    onSurfaceVariant = Color(0xFF5A615D),
    outline = Color(0xFF767F7A),
    outlineVariant = Color(0xFFC6CDC8),
    error = Color(0xFFB3261E),
)

internal val VocaPhoneDarkColors = darkColorScheme(
    primary = Color(0xFF77D0B2),
    onPrimary = Color(0xFF003827),
    primaryContainer = Color(0xFF0A4D3B),
    onPrimaryContainer = Color(0xFFC3F3E0),
    secondary = Color(0xFFB8CAC1),
    onSecondary = Color(0xFF24342C),
    secondaryContainer = Color(0xFF34463D),
    onSecondaryContainer = Color(0xFFD4E8DE),
    surface = Color(0xFF1B1C1B),
    // surface < surfaceVariant < surfaceContainerHigh, so a notice still reads as
    // sitting above a chip rather than beside it.
    surfaceVariant = Color(0xFF292C29),
    surfaceContainerHigh = Color(0xFF30332F),
    background = Color(0xFF1B1C1B),
    onSurface = Color(0xFFE3E4E1),
    onSurfaceVariant = Color(0xFFC2C8C3),
    outline = Color(0xFF89928C),
    outlineVariant = Color(0xFF3C403D),
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
