package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `teeth` field in the settings panel and the model must agree on the minimum.
 *
 * Regression guard for the "silently changed value" class of bug: the UI clamps against
 * [`ParamDef.min`], the model clamps inside [`GearParams.coerced`], and when the two numbers
 * drift apart nothing warns the user. The clamp warning in `NumberRow` compares the typed
 * value against the *field's* bounds, and [`GearSpec.validate`] inspects the value *after*
 * `coerced()` has already raised it — so a disagreement is invisible in both directions.
 *
 * Before this test the field declared a flat minimum of 5 while `coerced()` enforced 8 for
 * involute profiles: typing 5 was accepted, became 8, and said nothing.
 */
class TeethFieldBoundTest {

    private fun teethFieldDef(profile: ToothProfile): ParamDef =
        GearSpec.fields(GearParams(gearType = GearType.SPUR, toothProfile = profile))
            .first { it.key == "teeth" }

    @Test
    fun fieldMinimumEqualsTheCoercedFloorForEveryProfile() {
        for (profile in ToothProfile.entries) {
            val def = teethFieldDef(profile)
            assertEquals(
                "teeth field min must equal ToothProfile.minTeeth for $profile",
                profile.minTeeth.toDouble(),
                def.min,
                0.0
            )
        }
    }

    @Test
    fun belowMinimumInputLandsExactlyOnTheAdvertisedMinimum() {
        for (profile in ToothProfile.entries) {
            val def = teethFieldDef(profile)
            val typed = def.min - 1.0
            val applied = GearSpec.setNumber(
                GearParams(gearType = GearType.SPUR, toothProfile = profile),
                "teeth",
                typed
            )
            assertEquals(
                "typing ${typed.toInt()} teeth on $profile must land on the advertised minimum",
                def.min.toInt(),
                applied.teeth
            )
        }
    }

    @Test
    fun compoundStageOneUsesTheSameFloorAsASingleGear() {
        val def = GearSpec.fields(
            GearParams(gearType = GearType.COMPOUND, toothProfile = ToothProfile.INVOLUTE)
        ).first { it.key == "teeth" }
        assertEquals(ToothProfile.INVOLUTE.minTeeth.toDouble(), def.min, 0.0)
    }

    @Test
    fun profilesKeepTheirDocumentedFloors() {
        // The physical reasoning in ToothProfile.minTeeth: involute is the most demanding
        // (undercut + degenerating root fillet), straight trapezoids the least.
        assertEquals(8, ToothProfile.INVOLUTE.minTeeth)
        assertEquals(6, ToothProfile.CYCLOID.minTeeth)
        assertEquals(5, ToothProfile.STRAIGHT.minTeeth)
        assertTrue(ToothProfile.CYCLOID.minTeeth < ToothProfile.INVOLUTE.minTeeth)
    }

    @Test
    fun validToothCountInTheMiddleOfTheRangeIsUntouched() {
        for (profile in ToothProfile.entries) {
            val applied = GearSpec.setNumber(
                GearParams(gearType = GearType.SPUR, toothProfile = profile),
                "teeth",
                24.0
            )
            assertEquals(24, applied.teeth)
        }
    }

    @Test
    fun outOfRangeAndNonFiniteInputStaysInsideTheSupportedRange() {
        for (profile in ToothProfile.entries) {
            val base = GearParams(gearType = GearType.SPUR, toothProfile = profile)
            val def = GearSpec.fields(base).first { it.key == "teeth" }

            for (typed in listOf(0.0, -12.0, def.max + 5.0)) {
                val applied = GearSpec.setNumber(base, "teeth", typed)
                assertTrue(
                    "$typed teeth must clamp into the model's supported range, got ${applied.teeth}",
                    applied.teeth in profile.minTeeth..300
                )
            }

            // NaN is rejected outright rather than clamped — setNumber returns the input set.
            assertEquals(base.teeth, GearSpec.setNumber(base, "teeth", Double.NaN).teeth)
        }
    }
}
