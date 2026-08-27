package com.gearforge.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF00658C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC2E8FF),
    secondary = Color(0xFF4E616D),
    tertiary = Color(0xFF5F5B7D),
    background = Color(0xFFF7F9FB),
    surface = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF82D1FF),
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
