package com.gearforge.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** ARGB of the theme accents, shared with the 3D preview cache pre-warmer so the
 *  cached thumbnails always match the active theme's primary colour. */
internal val LightPrimaryArgb: Int = Color(0xFF00658C).toArgb()
internal val DarkPrimaryArgb: Int = Color(0xFF82D1FF).toArgb()

private val LightColors = lightColorScheme(
    primary = Color(LightPrimaryArgb),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC2E8FF),
    secondary = Color(0xFF4E616D),
    tertiary = Color(0xFF5F5B7D),
    background = Color(0xFFF7F9FB),
    surface = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(DarkPrimaryArgb),
    onPrimary = Color(0xFF00344C),
    primaryContainer = Color(0xFF004B68),
    secondary = Color(0xFFB5C9D6),
    tertiary = Color(0xFFC9C3F5),
    background = Color(0xFF0E1418),
    surface = Color(0xFF161D22)
)

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
