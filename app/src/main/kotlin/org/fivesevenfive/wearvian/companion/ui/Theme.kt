package org.fivesevenfive.wearvian.companion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The companion's Material 3 theme. Follows the system light/dark setting; the dark
 * scheme is pure-black (#000000) for OLED, matching the watch app's aesthetic. The
 * Rivian-yellow accent ([Gold]) is the through-line in both modes — a filled button is
 * gold with black text either way — to tie the two apps together visually.
 */
private val Gold = Color(0xFFFEDD5C)
private val GoldDeep = Color(0xFFB89A2E) // darker gold for hairlines/links on white

private val DarkColors = darkColorScheme(
    primary = Gold,
    onPrimary = Color.Black,
    secondary = Gold,
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFECECEC),
    surface = Color.Black,
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFF9A9A9A),
    outline = Color(0xFF3A3A3A),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Gold,
    onPrimary = Color.Black,
    secondary = GoldDeep,
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF5A5A5A),
    outline = Color(0xFFBDBDBD),
    error = Color(0xFFC62828),
)

@Composable
fun WearvianTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
