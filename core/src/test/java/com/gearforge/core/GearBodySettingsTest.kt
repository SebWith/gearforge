package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Covers the settings-panel unification: dead rack fields, gear-body predicate and tooth highlights. */
class GearBodySettingsTest {

    @Test
    fun hasGearBodyIsFalseForBeltAndRack() {
        assertTrue(GearSpec.hasGearBody(GearType.SPUR))
        assertTrue(GearSpec.hasGearBody(GearType.HELICAL))
        assertFalse(GearSpec.hasGearBody(GearType.BELT))
        assertFalse(GearSpec.hasGearBody(GearType.RACK))
    }

    @Test
    fun rackFieldsOmitStructuralAndToothGroups() {
        val rack = GearSpec.fields(GearSpec.defaults(GearType.RACK))
        assertTrue(rack.isNotEmpty())
        assertFalse("rack must not expose per-tooth fields", rack.any { it.group == ParamGroup.TEETH })
        assertFalse("rack must not expose lightening fields", rack.any { it.group == ParamGroup.LIGHTENING })

        val spur = GearSpec.fields(GearSpec.defaults(GearType.SPUR))
        assertTrue(spur.any { it.group == ParamGroup.TEETH })
        assertTrue(spur.any { it.group == ParamGroup.LIGHTENING })
    }

    @Test
    fun deadFieldsRemovedForRackWormRing() {
        val rack = GearSpec.fields(GearSpec.defaults(GearType.RACK))
        assertFalse("rack must not expose teeth (rack_length is authoritative)", rack.any { it.key == "teeth" })
        assertTrue("rack exposes rack_length", rack.any { it.key == "rack_length" })

        val worm = GearSpec.fields(GearSpec.defaults(GearType.WORM_PAIR))
        assertFalse("worm must not expose teeth (wheel_teeth is authoritative)", worm.any { it.key == "teeth" })

        val ring = GearSpec.fields(GearSpec.defaults(GearType.INTERNAL_RING))
        assertFalse("ring must not expose tooth_profile", ring.any { it.key == "tooth_profile" })
        assertFalse("ring must not expose profile_shift", ring.any { it.key == "profile_shift" })
    }

    @Test
    fun toothHighlightMeshIsThinWedgeAroundTooth() {
        val p = GearParams(module = 1.0, teeth = 20, thickness = 6.0)
        val m = GearBuilder.toothHighlightMesh(p, 3)
        assertTrue(m.vertices.isNotEmpty())
        assertTrue(m.triangles.isNotEmpty())
        val tip = GearCalculator.outerRadius(1.0, 20) + 0.5
        for (v in m.vertices) {
            val r = hypot(v.x, v.y)
            assertTrue("highlight radius bounded", r <= tip + 1e-6)
            assertTrue("highlight z lower bound", v.z >= -0.3 - 1e-6)
            assertTrue("highlight z upper bound", v.z <= p.thickness + 0.3 + 1e-6)
        }
    }

    @Test
    fun lighteningHolesAreGeneratedWhenDiameterIsZero() {
        val p = GearParams(module = 2.0, teeth = 24, thickness = 6.0)
            .copy(lighteningHoleCount = 4, lighteningHoleDiameter = 0.0)
        val plan = Bore.lighteningPlan(p)
        assertEquals("auto-size should produce the requested count", 4, plan.count)
        assertTrue("auto radius positive", plan.radius > 0.0)
        assertEquals(4, plan.holes.size)
    }

    @Test
    fun lighteningHolesStayInsideAnnulus() {
        val p = GearParams(module = 2.0, teeth = 24, thickness = 6.0)
            .copy(lighteningHoleCount = 4, lighteningHoleDiameter = 10.0)
        val plan = Bore.lighteningPlan(p)
        assertTrue(plan.holes.isNotEmpty())
        val rRoot = GearCalculator.rootRadius(2.0, 24)
        val boreR = p.bore.diameter / 2.0
        for (hole in plan.holes) {
            for (v in hole) {
                val r = hypot(v.x, v.y)
                assertTrue("hole overlaps bore", r >= boreR + 0.5)
                assertTrue("hole reaches teeth", r <= rRoot - 0.5)
            }
        }
    }

    @Test
    fun oversizedLighteningHolesAreClampedWithWarning() {
        val p = GearParams(module = 1.0, teeth = 12, thickness = 4.0)
            .copy(lighteningHoleCount = 6, lighteningHoleDiameter = 20.0)
        val plan = Bore.lighteningPlan(p)
        val rRoot = GearCalculator.rootRadius(1.0, 12)
        for (hole in plan.holes) {
            for (v in hole) {
                assertTrue("hole exceeds root", hypot(v.x, v.y) <= rRoot - 0.5)
            }
        }
        val warnings = GearSpec.validate(p)
        assertTrue(
            "should warn about corrected lightening holes",
            warnings.any { it.code == GearSpec.WARN_LIGHTENING_HOLE }
        )
    }

    @Test
    fun rackLengthDrivesGeometry() {
        val p = GearParams(gearType = GearType.RACK, module = 1.0, rackLength = 100.0, teeth = 10)
        val outline = GearProfiles.rackOutline(p)
        val maxX = outline.maxOf { it.x }
        val expectedTeeth = GearProfiles.rackTeeth(p)
        val expectedLength = expectedTeeth * Math.PI * p.module
        assertTrue("rack teeth derived from length", expectedTeeth >= 30)
        assertTrue("rack bar honours rack_length", Math.abs(maxX - expectedLength) < 1e-6)
    }

    @Test
    fun backlashIsCappedByModuleAndWarned() {
        val bad = GearParams(module = 0.2, teeth = 12, backlash = 2.0)
        val ok = bad.coerced()
        assertTrue("backlash clamped for small module", ok.backlash <= 0.25 * Math.PI * 0.2 + 1e-9)
        assertTrue(
            "backlash warning surfaced",
            GearSpec.validate(bad).any { it.code == GearSpec.WARN_BACKLASH }
        )
    }

    @Test
    fun coercedCapsCountsAndModule() {
        val bad = GearParams(teeth = 2_000_000, planetCount = 999, lighteningHoleCount = 999, beltDriverTeeth = 999_999)
        val ok = bad.coerced()
        assertTrue("teeth capped", ok.teeth <= 300)
        assertTrue("planetCount capped", ok.planetCount <= 12)
        assertTrue("lighteningHoleCount capped", ok.lighteningHoleCount <= 12)
        assertTrue("beltDriverTeeth capped", ok.beltDriverTeeth <= 500)
        assertTrue("valid module preserved", ok.module == 1.0)
    }

    @Test
    fun setNumberNeverBypassesInvariants() {
        val p = GearParams(module = 1.0, teeth = 20)
        assertTrue("module 0 coerced to > 0", GearSpec.setNumber(p, "module", 0.0).module > 0.0)
        assertTrue("teeth floored", GearSpec.setNumber(p, "teeth", 2.0).teeth >= 3)
        assertTrue("NaN module ignored", GearSpec.setNumber(p, "module", Double.NaN).module == p.module)
    }

    @Test
    fun exprDivisionByZeroReturnsNull() {
        assertTrue(Expr.eval("1/0", GearParams()) == null)
        assertTrue(Expr.eval("2*3", GearParams()) == 6.0)
    }
}
