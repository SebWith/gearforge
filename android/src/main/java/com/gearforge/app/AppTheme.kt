package com.gearforge.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.gearforge.core.GearPalette

/** ARGB of the theme accents, shared with the 3D preview cache pre-warmer so the
 *  cached thumbnails always match the active theme's primary colour. */
internal val LightPrimaryArgb: Int = Color(0xFF00658C).toArgb()
internal val DarkPrimaryArgb: Int = Color(0xFF82D1FF).toArgb()

// The surfaces come from core.GearPalette, which also owns the section accents drawn on them.
// That is deliberate: the accent contrast test measures the real pair, so it has to read the
// same constants the theme compiles in — a copy here would have made the test measure a
// surface the app does not use.
internal val LightColors = lightColorScheme(
    primary = Color(LightPrimaryArgb),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC2E8FF),
    // The other theme's primary: Material's default here was a lavender unrelated to the brand, and the
    // tooth chip draws its action in this role on inverseSurface (ThemeContrastTest).
    inversePrimary = Color(DarkPrimaryArgb),
    secondary = Color(0xFF4E616D),
    tertiary = Color(0xFF5F5B7D),
    background = Color(0xFFF7F9FB),
    surface = Color(GearPalette.LIGHT_SURFACE_ARGB)
)

internal val DarkColors = darkColorScheme(
    primary = Color(DarkPrimaryArgb),
    onPrimary = Color(0xFF00344C),
    primaryContainer = Color(0xFF004B68),
    inversePrimary = Color(LightPrimaryArgb),
    secondary = Color(0xFFB5C9D6),
    tertiary = Color(0xFFC9C3F5),
    background = Color(0xFF0E1418),
    surface = Color(GearPalette.DARK_SURFACE_ARGB)
)

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors) {
        // The window background is transparent (themes.xml), so a screen that is not itself a Surface
        // drew on black in LocalContentColor's default black: the wizard's titles measured 1.0:1 on device.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background,
            contentColor = colors.onBackground,
            content = content
        )
    }
}
