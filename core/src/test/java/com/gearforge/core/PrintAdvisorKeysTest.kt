package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the localization surface of [PrintAdvisor]: every emitted advice is keyed
 * (no hardcoded English in core) and uses only the canonical [PrintAdvisor.ADVICE_KEYS],
 * with numeric placeholders carried as raw [Double] values so the Android layer can
 * format them locale-aware.
 */
class PrintAdvisorKeysTest {

    private fun keys(params: GearParams, nozzleMm: Double, layerHeightMm: Double, material: String): Set<String> =
        PrintAdvisor.advice(params, nozzleMm, layerHeightMm, material).map { it.key }.toSet()

    @Test
    fun everyEmittedKeyIsCanonical() {
        val base = GearSpec.defaults(GearType.SPUR)
        val variants = listOf(
            base.copy(module = 0.5),                    // below printable minimum
            base.copy(module = 0.9),                    // near print limit
            base.copy(module = 1.0),                    // default module
            base.copy(module = 2.0, teeth = 13),        // undercut risk
            base.copy(backlash = 0.0)                   // recommended backlash
        )
        val materials = listOf("Steel", "PLA", "PETG", "ABS", "ASA", "Nylon", "PA")
        for (v in variants) {
            for (material in materials) {
                val emitted = keys(v, nozzleMm = 0.4, layerHeightMm = 0.3, material = material)
                assertTrue("Non-canonical keys emitted: $emitted", PrintAdvisor.ADVICE_KEYS.containsAll(emitted))
            }
        }
    }

    @Test
    fun allCanonicalKeysAreReachable() {
        val base = GearSpec.defaults(GearType.SPUR)
        val reached = HashSet<String>()
        reached += keys(base.copy(module = 0.5), 0.4, 0.2, "Steel")             // module below min
        reached += keys(base.copy(module = 0.9), 0.4, 0.2, "Steel")             // module near limit
        reached += keys(base.copy(module = 1.0), 0.4, 0.3, "Steel")             // layer too coarse
        reached += keys(base.copy(module = 2.0, teeth = 13), 0.4, 0.2, "Steel") // undercut
        reached += keys(base.copy(backlash = 0.0), 0.4, 0.2, "Steel")           // backlash
        reached += keys(base, 0.4, 0.2, "PLA")
        reached += keys(base, 0.4, 0.2, "PETG")
        reached += keys(base, 0.4, 0.2, "ABS")
        reached += keys(base, 0.4, 0.2, "Nylon")
        assertEquals(PrintAdvisor.ADVICE_KEYS, reached)
    }

    @Test
    fun numericArgumentsRemainRawDoubles() {
        val advice = PrintAdvisor.advice(
            GearSpec.defaults(GearType.SPUR).copy(module = 0.5),
            nozzleMm = 0.4,
            layerHeightMm = 0.2,
            material = "Steel"
        )
        val belowMin = advice.first { it.key == PrintAdvisor.KEY_MODULE_BELOW_MIN }
        assertEquals(3, belowMin.args.size)
        assertTrue("Expected raw Double args, got: ${belowMin.args}", belowMin.args.all { it is Double })
    }
}
