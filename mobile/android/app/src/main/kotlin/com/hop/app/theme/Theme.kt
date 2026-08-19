package com.hop.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// HOP's first real color system (supersedes the single-placeholder-blue
// scheme). Indigo-violet accent -- distinct from the primary-blue every other
// social app defaults to, calm enough to sit behind a full-screen photo/video
// feed rather than compete with it. Full light + dark token sets (not just
// `primary`) so every screen gets consistent contrast, not just whichever
// happened to reference MaterialTheme.colorScheme.primary directly.
private val LightColors = lightColorScheme(
    primary = Color(0xFF5B4FE8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E1FF),
    onPrimaryContainer = Color(0xFF1B1464),
    background = Color(0xFFFAFAFB),
    onBackground = Color(0xFF17171F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17171F),
    surfaceVariant = Color(0xFFF0F0F4),
    onSurfaceVariant = Color(0xFF55555F),
    outline = Color(0xFFD8D8E0),
    outlineVariant = Color(0xFFE8E8ED),
    error = Color(0xFFDC3545),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9C93FF),
    onPrimary = Color(0xFF1B1464),
    primaryContainer = Color(0xFF362F80),
    onPrimaryContainer = Color(0xFFE4E1FF),
    background = Color(0xFF121218),
    onBackground = Color(0xFFF2F2F5),
    surface = Color(0xFF1A1A22),
    onSurface = Color(0xFFF2F2F5),
    surfaceVariant = Color(0xFF26262F),
    onSurfaceVariant = Color(0xFFA9A9B4),
    outline = Color(0xFF3A3A44),
    outlineVariant = Color(0xFF2C2C36),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0000),
)

@Composable
fun HopTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (useDarkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
