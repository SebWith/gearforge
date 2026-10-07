package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Guards the two things [GearPalette] promises: every section accent is legible on the surface the
 * app actually draws it on, and no two sections share a colour.
 *
 * JUnit 4, like the rest of the core suite (the module uses `useJUnit()`), so the message — where
 * one helps — comes first: `assertEquals(message, expected, actual)`.
 */
class GearPaletteTest {

    private val groups = ParamGroup.entries

    @Test
    fun everyAccentClearsTheGraphicalContrastFloorOnItsOwnSurface() {
        for (group in groups) {
            val onLight = GearPalette.contrastRatio(
                GearPalette.accentArgb(group, dark = false),
                GearPalette.LIGHT_SURFACE_ARGB
            )
            val onDark = GearPalette.contrastRatio(
                GearPalette.accentArgb(group, dark = true),
                GearPalette.DARK_SURFACE_ARGB
            )
            assertTrue(
                "$group accent is $onLight:1 on the light surface, below the 3:1 floor",
                onLight >= GearPalette.MIN_GRAPHICAL_CONTRAST
            )
            assertTrue(
                "$group accent is $onDark:1 on the dark surface, below the 3:1 floor",
                onDark >= GearPalette.MIN_GRAPHICAL_CONTRAST
            )
        }
    }

    /**
     * The reason the palette is derived at all. If this ever passes, the light-theme amber stopped
     * being a defect and the derivation is no longer justified by the light theme.
     */
    @Test
    fun theLegacyAmberStripeWasaContrastFailureOnTheLightSurface() {
        val legacyAmber = 0xFFF57C00.toInt()
        val ratio = GearPalette.contrastRatio(legacyAmber, GearPalette.LIGHT_SURFACE_ARGB)
        assertTrue(
            "legacy amber measured $ratio:1 on white; the documented 2.71:1 regression no longer holds",
            ratio < GearPalette.MIN_GRAPHICAL_CONTRAST
        )
        assertEquals("the recorded measurement", 2.71, ratio, 0.01)
    }

    @Test
    fun theLegacyAmberWasFineOnTheDarkSurface() {
        // It is a light-theme defect, not a bad colour: on the dark surface the same amber is
        // legible, which is why it survived review until the light theme was tested.
        val legacyAmber = 0xFFF57C00.toInt()
        val ratio = GearPalette.contrastRatio(legacyAmber, GearPalette.DARK_SURFACE_ARGB)
        assertTrue("legacy amber measured $ratio:1 on the dark surface", ratio >= 3.0)
    }

    @Test
    fun noTwoSectionsShareAHue() {
        for (i in groups.indices) {
            for (j in i + 1 until groups.size) {
                val a = GearPalette.hue(groups[i])
                val b = GearPalette.hue(groups[j])
                val separation = minOf(abs(a - b), 360.0 - abs(a - b))
                assertTrue(
                    "${groups[i]} and ${groups[j]} are ${separation}° apart; two sections that close " +
                        "read as the same colour",
                    separation >= 25.0
                )
            }
        }
    }

    @Test
    fun theSameHueIsLightOnDarkAndDarkOnLight() {
        for (group in groups) {
            val onLight = GearPalette.relativeLuminance(GearPalette.accentArgb(group, dark = false))
            val onDark = GearPalette.relativeLuminance(GearPalette.accentArgb(group, dark = true))
            assertTrue(
                "$group is not lighter on the dark surface (light=$onLight dark=$onDark)",
                onDark > onLight
            )
        }
    }

    @Test
    fun accentsAreOpaque() {
        for (group in groups) {
            val argb = GearPalette.accentArgb(group, dark = true)
            assertEquals("$group alpha", 0xFF, (argb ushr 24) and 0xFF)
        }
    }

    @Test
    fun bodyColoursStayApartAndLegibleUpToEightBodies() {
        // Eight is the most a planetary with a hub-less ring train can have (sun, ring, six
        // planets), so that is the length the legend has to survive.
        for (dark in listOf(true, false)) {
            val surface = if (dark) GearPalette.DARK_SURFACE_ARGB else GearPalette.LIGHT_SURFACE_ARGB
            val colours = (0 until 8).map { GearPalette.bodyArgb(it, dark) }
            assertEquals("two bodies share a colour on ${if (dark) "dark" else "light"}", 8, colours.distinct().size)
            for ((index, argb) in colours.withIndex()) {
                val ratio = GearPalette.contrastRatio(argb, surface)
                assertTrue(
                    "body $index is $ratio:1 on the ${if (dark) "dark" else "light"} surface, " +
                        "below the 3:1 floor",
                    ratio >= GearPalette.MIN_GRAPHICAL_CONTRAST
                )
            }
        }
    }

    @Test
    fun bodyColoursDifferBetweenThemes() {
        for (index in 0 until 8) {
            val light = GearPalette.bodyArgb(index, dark = false)
            val dark = GearPalette.bodyArgb(index, dark = true)
            assertTrue("body $index did not adapt to the theme", light != dark)
        }
    }

    @Test
    fun contrastRatioMatchesItsDefinition() {
        assertEquals("a colour against itself", 1.0, GearPalette.contrastRatio(0xFF3366CC.toInt(), 0xFF3366CC.toInt()), 1e-9)
        assertEquals("black on white", 21.0, GearPalette.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        // Symmetric: the order of the pair cannot change the answer.
        val a = 0xFF112233.toInt()
        val b = 0xFFEEDDCC.toInt()
        assertEquals(GearPalette.contrastRatio(a, b), GearPalette.contrastRatio(b, a), 1e-12)
    }

    @Test
    fun relativeLuminanceIsAnchoredAtBlackAndWhite() {
        assertEquals(0.0, GearPalette.relativeLuminance(0xFF000000.toInt()), 1e-9)
        assertEquals(1.0, GearPalette.relativeLuminance(0xFFFFFFFF.toInt()), 1e-9)
    }

    @Test
    fun hslConversionHitsThePrimaryAndSecondaryAnchors() {
        assertEquals("red", 0xFFFF0000.toInt(), GearPalette.hslToArgb(0.0, 1.0, 0.5))
        assertEquals("green", 0xFF00FF00.toInt(), GearPalette.hslToArgb(120.0, 1.0, 0.5))
        assertEquals("blue", 0xFF0000FF.toInt(), GearPalette.hslToArgb(240.0, 1.0, 0.5))
        assertEquals("white", 0xFFFFFFFF.toInt(), GearPalette.hslToArgb(200.0, 1.0, 1.0))
        assertEquals("black", 0xFF000000.toInt(), GearPalette.hslToArgb(200.0, 1.0, 0.0))
        // Hues are taken modulo 360, so −145° is the same colour as 215°.
        assertEquals(
            GearPalette.hslToArgb(215.0, 0.5, 0.5),
            GearPalette.hslToArgb(-145.0, 0.5, 0.5)
        )
    }
}
