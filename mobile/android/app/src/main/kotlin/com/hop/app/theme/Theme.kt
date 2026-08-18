package com.hop.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Infrastructure-grade Material3 color scheme, not final visual design --
// intentionally minimal so this doesn't need reworking once real product
// design lands. HOP has no brand palette decided yet.
private val HopPrimary = Color(0xFF3D5AFE)
private val HopPrimaryDark = Color(0xFF8C9EFF)

private val LightColors = lightColorScheme(
    primary = HopPrimary,
)

private val DarkColors = darkColorScheme(
    primary = HopPrimaryDark,
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
