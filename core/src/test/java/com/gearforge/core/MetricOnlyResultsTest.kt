package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [GearSpec.METRIC_ONLY_RESULT_KEYS] to reality.
 *
 * The set exists so the panel can label mass, moment of inertia and backlash as metric while the
 * editor is in inch mode. That labelling is only honest if those keys are still produced by
 * [GearSpec.results] and if their values still carry a metric unit — a renamed result would leave
 * the panel labelling nothing, and a converted value would leave the label lying.
 */
class MetricOnlyResultsTest {

    @Test
    fun everyMetricOnlyKeyIsProducedForEveryGearTypeThatShowsResults() {
        for (type in GearType.entries) {
            val params = GearSpec.defaults(type)
            val keys = GearSpec.results(type, params).map { it.first }.toSet()
            for (key in GearSpec.METRIC_ONLY_RESULT_KEYS) {
                assertTrue(
                    "$type does not produce \"$key\", so labelling it in inch mode does nothing",
                    key in keys
                )
            }
        }
    }

    @Test
    fun theLabeledValuesReallyCarryMetricUnits() {
        for (type in GearType.entries) {
            val params = GearSpec.defaults(type)
            val results = GearSpec.results(type, params).toMap()
            for (key in GearSpec.METRIC_ONLY_RESULT_KEYS) {
                val value = results.getValue(key)
                assertTrue(
                    "$type: \"$key\" is \"$value\", which no longer states a metric unit",
                    value.contains("kg") || value.contains("mm")
                )
            }
        }
    }

    @Test
    fun switchingToInchDoesNotChangeThem() {
        // The whole reason for the label: these values ignore the unit system while the lengths
        // around them do not. If a future change converts them, this test fails and the label has
        // to be removed rather than left to contradict the number.
        for (type in GearType.entries) {
            val mm = GearSpec.results(type, GearSpec.defaults(type)).toMap()
            val inchParams = GearSpec.defaults(type).copy(unit = UnitSystem.INCH)
            val inch = GearSpec.results(type, inchParams).toMap()
            for (key in GearSpec.METRIC_ONLY_RESULT_KEYS) {
                assertTrue(
                    "$type: \"$key\" changed with the unit system (${mm.getValue(key)} → " +
                        "${inch.getValue(key)}); it must stay metric or lose its label",
                    mm.getValue(key) == inch.getValue(key)
                )
            }
        }
    }

    @Test
    fun theUnitSwitchKeepsTheEnumAndTheChoiceFieldInStep() {
        // The panel renders the unit as a choice field derived from the enum, and the export preview
        // reads SettingsStore.useInch. The switcher writes both; this proves the panel reads back
        // what the switcher set, so the two cannot drift apart silently.
        val metric = GearSpec.defaults(GearType.SPUR)
        assertEquals(UnitSystem.MM, metric.unit)
        val inch = GearSpec.setUnit(metric, UnitSystem.INCH)
        assertEquals(UnitSystem.INCH, inch.unit)
        assertTrue(
            "the panel would still show \"${GearSpec.getChoice(inch, "unit")}\"",
            GearSpec.getChoice(inch, "unit").startsWith("inch")
        )
        assertEquals(UnitSystem.MM, GearSpec.setUnit(inch, UnitSystem.MM).unit)
    }
}
