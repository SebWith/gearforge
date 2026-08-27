package com.gearforge.app

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Locale-aware number and dimension formatting (point 15).
 *
 * Swedish uses a comma decimal separator and English a point; [decimal] centralizes
 * that so displayed values are consistent everywhere. [length] and [dims] additionally
 * apply the user's `useInch` preference and a localized unit suffix.
 */
object Format {

    private val SV_LOCALE = Locale("sv", "SE")

    /** Formats [value] with [decimals] using the active language's locale. */
    fun decimal(value: Double, decimals: Int, lang: I18n.Lang): String {
        if (decimals == 0) return value.roundToInt().toString()
        val locale = if (lang == I18n.Lang.SV) SV_LOCALE else Locale.US
        return String.format(locale, "%.${decimals}f", value)
    }

    /** Formats a length given in millimetres, converting to inches when requested. */
    fun length(valueMm: Double, decimals: Int, lang: I18n.Lang, useInch: Boolean): String {
        val v = if (useInch) valueMm / 25.4 else valueMm
        val unit = I18n.t(lang, if (useInch) "inch" else "mm")
        return "${decimal(v, decimals, lang)} $unit"
    }

    /** Formats a width × height × depth triple (each in millimetres). */
    fun dims(wMm: Double, hMm: Double, dMm: Double, decimals: Int, lang: I18n.Lang, useInch: Boolean): String =
        "${length(wMm, decimals, lang, useInch)} × ${length(hMm, decimals, lang, useInch)} × ${length(dMm, decimals, lang, useInch)}"
}
