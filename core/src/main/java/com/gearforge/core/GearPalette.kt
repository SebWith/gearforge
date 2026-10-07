package com.gearforge.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Section-accent colours for the parameter panel, derived instead of hard-coded.
 *
 * The palette used to be eight literal colours chosen while looking at the dark theme. On the light
 * theme at least one of them failed outright: the amber `0xFFF57C00` of the TOLERANCES stripe has a
 * contrast of **2.71:1** against `0xFFFFFFFF`, below the 3:1 that WCAG 2.1 SC 1.4.11 requires of a
 * non-text graphical object — the stripe that is supposed to separate the sections became the least
 * visible thing on the row. [GearPaletteTest] pins that number so the reason for deriving the
 * palette cannot be forgotten.
 *
 * Here the *hue* is the brand decision and the lightness and saturation are a function of the
 * backdrop: accents on the dark surface are light and slightly desaturated, accents on the light
 * surface are dark and saturated. [accentArgb] therefore has one answer per theme, and
 * [GearPaletteTest](../test) proves both answers clear 3:1 against the surfaces the app really
 * draws on — which are declared here so there is exactly one definition of them.
 */
object GearPalette {

    /** The light-theme surface the panel cards sit on; see `AppTheme`. */
    val LIGHT_SURFACE_ARGB: Int = 0xFFFFFFFF.toInt()

    /** The dark-theme surface the panel cards sit on; see `AppTheme`. */
    val DARK_SURFACE_ARGB: Int = 0xFF161D22.toInt()

    /** Minimum contrast WCAG 2.1 SC 1.4.11 asks of a non-text graphical object. */
    const val MIN_GRAPHICAL_CONTRAST: Double = 3.0

    /**
     * Hue step between bodies drawn in the viewport.
     *
     * 47° is not a multiple of anything else here, so even the eight bodies of a planetary ring
     * train land on hues that a reader can tell apart — and every one of them keeps the same
     * theme-driven lightness as the section accents, so the 3:1 floor holds for them too.
     */
    private const val BODY_HUE_STEP = 47.0
    private const val BODY_FIRST_HUE = 20.0

    /**
     * Colour for the body at [index] of an assembly.
     *
     * One colour per body is what turns "two grey shapes" into "the sun and the ring": the legend
     * names them and this makes the names point at something. Bodies are only coloured when there is
     * more than one — a single gear keeps the plain material colour it has always had, because
     * tinting it would say something that is not true.
     */
    fun bodyArgb(index: Int, dark: Boolean): Int =
        accentArgbForHue(BODY_FIRST_HUE + index * BODY_HUE_STEP, dark)

    private const val DARK_SATURATION = 0.50
    private const val DARK_LIGHTNESS = 0.75
    private const val LIGHT_SATURATION = 0.72
    private const val LIGHT_LIGHTNESS = 0.30

    /**
     * Hue in degrees for each section.
     *
     * Every neighbour pair is at least 30° apart so two sections never read as the same colour,
     * and a hue has to clear the contrast floor in both themes to keep its place.
     */
    fun hue(group: ParamGroup): Double = when (group) {
        ParamGroup.GEOMETRY -> 205.0
        ParamGroup.MATERIAL -> 175.0
        ParamGroup.TOLERANCES -> 45.0
        ParamGroup.LOAD -> 285.0
        ParamGroup.HUB -> 15.0
        ParamGroup.TEETH -> 335.0
        ParamGroup.LIGHTENING -> 245.0
        ParamGroup.RESULTS -> 140.0
    }

    /** Accent for [group] on the light ([dark] = false) or dark surface. */
    fun accentArgb(group: ParamGroup, dark: Boolean): Int = accentArgbForHue(hue(group), dark)

    /** Accent for an arbitrary hue, using the same theme-driven lightness as [accentArgb]. */
    fun accentArgbForHue(hueDeg: Double, dark: Boolean): Int = hslToArgb(
        hueDeg,
        if (dark) DARK_SATURATION else LIGHT_SATURATION,
        if (dark) DARK_LIGHTNESS else LIGHT_LIGHTNESS
    )

    /** WCAG 2.1 contrast ratio between two opaque sRGB colours, from 1.0 (equal) upwards. */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** WCAG 2.1 relative luminance of an opaque sRGB colour. */
    fun relativeLuminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** HSL → opaque ARGB. Pure arithmetic: it has to be assertable without a device. */
    fun hslToArgb(hueDeg: Double, saturation: Double, lightness: Double): Int {
        val h = (((hueDeg % 360.0) + 360.0) % 360.0) / 360.0
        val s = saturation.coerceIn(0.0, 1.0)
        val l = lightness.coerceIn(0.0, 1.0)
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q

        fun channel(t0: Double): Double {
            var t = t0
            if (t < 0.0) t += 1.0
            if (t > 1.0) t -= 1.0
            return when {
                t < 1.0 / 6.0 -> p + (q - p) * 6 * t
                t < 1.0 / 2.0 -> q
                t < 2.0 / 3.0 -> p + (q - p) * (2.0 / 3.0 - t) * 6
                else -> p
            }
        }

        val r = channel(h + 1.0 / 3.0)
        val g = channel(h)
        val b = channel(h - 1.0 / 3.0)
        return (0xFF shl 24) or (byteOf(r) shl 16) or (byteOf(g) shl 8) or byteOf(b)
    }

    private fun byteOf(v: Double): Int = (v * 255.0).roundToInt().coerceIn(0, 255)
}
