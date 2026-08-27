package com.gearforge.core

/**
 * Rule-based guidance that maps printer settings to gear parameter adjustments.
 *
 * Core is Android-free: each [Advice] carries a localization KEY plus raw parameter
 * values (numeric values as [Double], string values as [String]). The Android layer
 * resolves the key through its I18n catalog and performs locale-aware number
 * formatting, so no [String.format] with a fixed [java.util.Locale.US] is needed here.
 */
object PrintAdvisor {

    /** Canonical localization keys emitted by [advice], resolved by the app's I18n catalog. */
    const val KEY_MODULE_BELOW_MIN = "advice_module_below_min"
    const val KEY_MODULE_NEAR_LIMIT = "advice_module_near_limit"
    const val KEY_LAYER_TOO_COARSE = "advice_layer_too_coarse"
    const val KEY_UNDERCUT_RISK = "advice_undercut_risk"
    const val KEY_BACKLASH_RECOMMENDED = "advice_backlash_recommended"
    const val KEY_MATERIAL_PLA = "advice_material_pla"
    const val KEY_MATERIAL_PETG = "advice_material_petg"
    const val KEY_MATERIAL_ABS_ASA = "advice_material_abs_asa"
    const val KEY_MATERIAL_NYLON = "advice_material_nylon"

    /** The complete set of keys [advice] can emit; kept in sync with the I18n catalog. */
    val ADVICE_KEYS: Set<String> = setOf(
        KEY_MODULE_BELOW_MIN,
        KEY_MODULE_NEAR_LIMIT,
        KEY_LAYER_TOO_COARSE,
        KEY_UNDERCUT_RISK,
        KEY_BACKLASH_RECOMMENDED,
        KEY_MATERIAL_PLA,
        KEY_MATERIAL_PETG,
        KEY_MATERIAL_ABS_ASA,
        KEY_MATERIAL_NYLON
    )

    data class Advice(val severity: Severity, val key: String, val args: List<Any>)
    enum class Severity { INFO, WARNING, CRITICAL }

    fun recommendBacklash(p: GearParams, nozzleMm: Double): Double {
        val base = when (p.precision) {
            PrecisionLevel.HOBBY -> 0.25
            PrecisionLevel.STANDARD -> 0.15
            PrecisionLevel.HIGH -> 0.10
        }
        // add a nozzle-size term: finer nozzles need less clearance
        return maxOf(p.backlash, base + nozzleMm * 0.05)
    }

    fun minimumPrintableModule(nozzleMm: Double): Double = nozzleMm * 2.0

    fun advice(p: GearParams, nozzleMm: Double, layerHeightMm: Double, material: String): List<Advice> {
        val out = ArrayList<Advice>()
        val minModule = minimumPrintableModule(nozzleMm)

        if (p.module < minModule) {
            out.add(
                Advice(
                    Severity.CRITICAL,
                    KEY_MODULE_BELOW_MIN,
                    listOf(p.module, minModule, nozzleMm)
                )
            )
        } else if (p.module < minModule * 1.4) {
            out.add(Advice(Severity.WARNING, KEY_MODULE_NEAR_LIMIT, emptyList()))
        }

        if (layerHeightMm > p.module / 4.0) {
            out.add(
                Advice(
                    Severity.WARNING,
                    KEY_LAYER_TOO_COARSE,
                    listOf(p.module / 4.0)
                )
            )
        }

        if (p.teeth < 14 && p.toothProfile == ToothProfile.INVOLUTE) {
            out.add(Advice(Severity.WARNING, KEY_UNDERCUT_RISK, emptyList()))
        }

        val recBacklash = recommendBacklash(p, nozzleMm)
        if (recBacklash > p.backlash) {
            out.add(
                Advice(
                    Severity.INFO,
                    KEY_BACKLASH_RECOMMENDED,
                    listOf(nozzleMm, recBacklash, p.backlash)
                )
            )
        }

        when (material.lowercase()) {
            "pla" -> out.add(Advice(Severity.INFO, KEY_MATERIAL_PLA, emptyList()))
            "petg" -> out.add(Advice(Severity.INFO, KEY_MATERIAL_PETG, emptyList()))
            "abs", "asa" -> out.add(Advice(Severity.INFO, KEY_MATERIAL_ABS_ASA, emptyList()))
            "nylon", "pa" -> out.add(Advice(Severity.INFO, KEY_MATERIAL_NYLON, emptyList()))
        }

        return out
    }
}
