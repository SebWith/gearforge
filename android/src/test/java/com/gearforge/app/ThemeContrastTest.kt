package com.gearforge.app

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.gearforge.core.GearPalette
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.1 AA contrast for the colour pairs the screens actually draw, in both themes.
 *
 * The pairs are read from the compiled [LightColors]/[DarkColors] — including every role the app
 * leaves to Material's defaults — so the table measures what ships, not a copy of it. Each pair names
 * the place it is drawn. Surfaces are the ones the Material 3 components use: AlertDialog on
 * surfaceContainerHigh, ModalBottomSheet on surfaceContainerLow, Card on surfaceContainerHighest,
 * DropdownMenu on surfaceContainer, and a `Surface(color = surface, tonalElevation = n)` on the
 * primary-tinted surface at that elevation.
 *
 * Thresholds: 4.5:1 for text (SC 1.4.3; every pair here is body-sized), 3:1 for a component boundary
 * or state indicator (SC 1.4.11). Translucent viewport panels are measured over both a black and a
 * white pixel, because whatever the 3D view happens to draw behind them is not under the panel's
 * control.
 */
class ThemeContrastTest {

    private data class Pair(val where: String, val fg: Color, val bg: Color, val min: Double)

    private fun ratio(fg: Color, bg: Color): Double =
        GearPalette.contrastRatio(fg.compositeOver(bg).toArgb(), bg.toArgb())

    private fun pairs(s: ColorScheme): List<Pair> {
        val rows = s.surfaceColorAtElevation(1.dp)
        val topBar = s.surfaceColorAtElevation(4.dp)
        val dialog = s.surfaceContainerHigh
        val sheet = s.surfaceContainerLow
        val card = s.surfaceContainerHighest
        val menu = s.surfaceContainer
        val text = 4.5
        val ui = 3.0
        val list = mutableListOf(
            Pair("wizard title, step label (onBackground / background)", s.onBackground, s.background, text),
            Pair("wizard hint (onSurfaceVariant / background)", s.onSurfaceVariant, s.background, text),
            Pair("wizard text and outlined buttons (primary / background)", s.primary, s.background, text),
            Pair("outlined button border (outline / background)", s.outline, s.background, ui),
            Pair("panel row label (onSurface / row surface)", s.onSurface, rows, text),
            Pair("panel hint, range caption (onSurfaceVariant / row surface)", s.onSurfaceVariant, rows, text),
            Pair("calculated value, reset link (primary / row surface)", s.primary, rows, text),
            Pair("clamp warning (tertiary / row surface)", s.tertiary, rows, text),
            Pair("override error (error / row surface)", s.error, rows, text),
            Pair("sheet text (onSurface / sheet)", s.onSurface, sheet, text),
            Pair("sheet caption (onSurfaceVariant / sheet)", s.onSurfaceVariant, sheet, text),
            Pair("dialog title (onSurface / dialog)", s.onSurface, dialog, text),
            Pair("dialog body (onSurfaceVariant / dialog)", s.onSurfaceVariant, dialog, text),
            Pair("dialog text button (primary / dialog)", s.primary, dialog, text),
            Pair("export gate message (error / dialog)", s.error, dialog, text),
            Pair("menu item (onSurface / menu)", s.onSurface, menu, text),
            Pair("wizard card title (onSurface / card)", s.onSurface, card, text),
            Pair("wizard card description (onSurfaceVariant / card)", s.onSurfaceVariant, card, text),
            Pair("preset use line (primary / card)", s.primary, card, text),
            Pair("top bar type button (primary / top bar)", s.primary, topBar, text),
            Pair("top bar icons, chip labels (onSurfaceVariant / top bar)", s.onSurfaceVariant, topBar, text),
            Pair("unselected chip label (onSurfaceVariant / dialog)", s.onSurfaceVariant, dialog, text),
            Pair("selected chip label (onSecondaryContainer / secondaryContainer)", s.onSecondaryContainer, s.secondaryContainer, text),
            Pair("tip strip (onSecondaryContainer / secondaryContainer)", s.onSecondaryContainer, s.secondaryContainer, text),
            Pair("custom card, count badge (onPrimaryContainer / primaryContainer)", s.onPrimaryContainer, s.primaryContainer, text),
            Pair("error banner (onErrorContainer / errorContainer)", s.onErrorContainer, s.errorContainer, text),
            Pair("warning banner (onTertiaryContainer / tertiaryContainer)", s.onTertiaryContainer, s.tertiaryContainer, text),
            Pair("meta chip (onSurfaceVariant / surfaceVariant)", s.onSurfaceVariant, s.surfaceVariant, text),
            Pair("tooth chip, scrub bubble (inverseOnSurface / inverseSurface)", s.inverseOnSurface, s.inverseSurface, text),
            Pair("tooth chip action, snackbar Undo (inversePrimary / inverseSurface)", s.inversePrimary, s.inverseSurface, text),
            Pair("text field border (outline / row surface)", s.outline, rows, ui),
            Pair("text field border in dialog (outline / dialog)", s.outline, dialog, ui),
            Pair("slider thumb, switch track, focus (primary / row surface)", s.primary, rows, ui),
            Pair("selected chip check mark (onSecondaryContainer / secondaryContainer)", s.onSecondaryContainer, s.secondaryContainer, ui)
        )
        // Viewport panels: translucent surface over whatever the 3D view draws.
        for ((under, underName) in listOf(Color.Black to "black", Color.White to "white")) {
            val panel = s.surface.copy(alpha = VIEWPORT_PANEL_ALPHA).compositeOver(under)
            val where = "HUD, scale bar, bed ruler and caption, legend"
            list += Pair("$where (onSurface / panel over $underName)", s.onSurface, panel, text)
            list += Pair("$where (onSurfaceVariant / panel over $underName)", s.onSurfaceVariant, panel, text)
        }
        return list
    }

    private fun audit(name: String, s: ColorScheme): List<String> {
        val failures = mutableListOf<String>()
        println("== $name theme")
        for (p in pairs(s)) {
            val r = ratio(p.fg, p.bg)
            val ok = r + 1e-9 >= p.min
            println(
                String.format(
                    java.util.Locale.ROOT, "%-5s %5.2f:1 (min %.1f) fg=#%08X bg=#%08X  %s",
                    if (ok) "PASS" else "FAIL", r, p.min, p.fg.toArgb(), p.bg.toArgb(), p.where
                )
            )
            if (!ok) failures += String.format(java.util.Locale.ROOT, "%s: %s %.2f:1 < %.1f", name, p.where, r, p.min)
        }
        return failures
    }

    @Test
    fun everyPairTheLightThemeDrawsMeetsWcagAa() {
        val failures = audit("light", LightColors)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun everyPairTheDarkThemeDrawsMeetsWcagAa() {
        val failures = audit("dark", DarkColors)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun theLandingCallToActionMeetsWcagAa() {
        val r = ratio(HeroOnAccent, HeroAccent)
        println(String.format(java.util.Locale.ROOT, "landing CTA %.2f:1", r))
        assertTrue("landing CTA label measures $r:1", r >= 4.5)
    }
}
