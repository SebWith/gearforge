package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for profile-shift and undercut mathematics (ISO 21771 / KHK):
 *  - r_a = r_p + m·(h_a* + x), r_f = r_p − m·(h_f* − x)
 *  - z_min = 2/sin²α, x_min = 1 − z·sin²α/2
 *  - a negatively shifted small gear must still produce a watertight mesh.
 */
class ProfileShiftMathTest {

    @Test
    fun profileShiftAdjustsTipAndRootRadii() {
        // z=20, m=1, x=+0.5: r_p=10, r_a=10+(1+0.5)=11.5, r_f=10−(1.25−0.5)=9.25
        val p = GearParams(gearType = GearType.SPUR, module = 1.0, teeth = 20, profileShift = 0.5)
        assertEquals(11.5, GearCalculator.tipRadiusShifted(1.0, 20, p.addendumCoef, 0.5), 1e-9)
        assertEquals(9.25, GearCalculator.rootRadiusShifted(1.0, 20, p.dedendumCoef, 0.5), 1e-9)
        assertEquals(20.0, GearCalculator.pitchDiameter(p), 1e-9)
        // Negative shift: r_a=10+(1−0.5)=10.5, r_f=10−(1.25+0.5)=8.25
        assertEquals(10.5, GearCalculator.tipRadiusShifted(1.0, 20, p.addendumCoef, -0.5), 1e-9)
        assertEquals(8.25, GearCalculator.rootRadiusShifted(1.0, 20, p.dedendumCoef, -0.5), 1e-9)
    }

    @Test
    fun helicalPitchDiameterUsesTransverseModule() {
        // m_n=1, z=20, β=20°: d = m_n·z/cos β = 20/cos 20° ≈ 21.283
        val p = GearParams(gearType = GearType.HELICAL, module = 1.0, teeth = 20, helixAngleDeg = 20.0)
        assertEquals(20.0 / kotlin.math.cos(Math.toRadians(20.0)), GearCalculator.pitchDiameter(p), 1e-9)
        // A spur gear is unaffected by the helix-angle branch.
        assertEquals(20.0, GearCalculator.pitchDiameter(p.copy(gearType = GearType.SPUR)), 1e-9)
    }

    @Test
    fun undercutThresholdMatchesIsoFormula() {
        // z_min = 2/sin²20° ≈ 17.097
        assertEquals(2.0 / (Math.sin(Math.toRadians(20.0)) * Math.sin(Math.toRadians(20.0))),
            GearCalculator.undercutThresholdTeeth(20.0), 1e-9)
        // x_min = 1 − z·sin²20°/2: for z=12 it is positive (undercut without shift).
        val xMin12 = GearCalculator.minimumShiftNoUndercut(12, 20.0)
        assertTrue("z=12 must need positive shift, got $xMin12", xMin12 > 0.0)
        // For z=20 the minimum shift is negative → no undercut at x=0.
        assertTrue(GearCalculator.minimumShiftNoUndercut(20, 20.0) < 0.0)
    }

    @Test
    fun undercutWarningSurfacesOnlyWhenNeeded() {
        val undercut = GearParams(gearType = GearType.SPUR, module = 1.0, teeth = 12, profileShift = 0.0)
        assertTrue("z=12 x=0 should warn about undercut",
            GearSpec.validate(undercut).any { it.code == GearSpec.WARN_UNDERCUT })
        val shifted = GearParams(gearType = GearType.SPUR, module = 1.0, teeth = 12, profileShift = 0.5)
        assertFalse("z=12 x=0.5 should not warn about undercut",
            GearSpec.validate(shifted).any { it.code == GearSpec.WARN_UNDERCUT })
        val big = GearParams(gearType = GearType.SPUR, module = 1.0, teeth = 20, profileShift = 0.0)
        assertFalse("z=20 x=0 should not warn about undercut",
            GearSpec.validate(big).any { it.code == GearSpec.WARN_UNDERCUT })
    }

    @Test
    fun negativeShiftSmallGearStaysWatertight() {
        // The previously failing combination (planetary m=0.5 s=−0.5) boiled down to a
        // 12-tooth spur at m=0.5 with a deep negative shift. The shifted root + the
        // tangent root-fillet clamp must keep every part closed and manifold.
        for (x in doubleArrayOf(-0.5, -0.25, 0.0, 0.25, 0.5)) {
            for (m in doubleArrayOf(0.5, 1.0, 2.0)) {
                val p = GearParams(gearType = GearType.SPUR, module = m, teeth = 12, profileShift = x).coerced()
                val mesh = GearBuilder.mesh(p)
                val v = MeshOps.validate(mesh)
                assertTrue("spur m=$m x=$x not watertight: ${v.issues}", v.isValid)
            }
        }
    }
}
