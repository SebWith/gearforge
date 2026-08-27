package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BeltGeometryTest {

    private fun belt() = GearParams(
        gearType = GearType.BELT,
        beltProfile = "GT2",
        beltWidthMm = 6.0,
        beltDriverTeeth = 20,
        beltDrivenTeeth = 40
    )

    @Test
    fun beltLoopIsClosedAndBounded() {
        val t = belt().toBeltTransmission()
        val r = BeltCalculator.resolve(t)
        val loop = BeltBuilder.beltLoop(
            Vec2(0.0, 0.0), r.driverPitchDia / 2.0,
            Vec2(r.centerDistanceMm, 0.0), r.drivenPitchDia / 2.0
        )
        assertTrue(loop.size >= 4)
        // The polyline is implicitly closed: its last→first edge is the upper tangent
        // span, so the two tangent end points must lie above both pulley centres,
        // one on each side of the midpoint.
        val first = loop.first()
        val last = loop.last()
        val mid = r.centerDistanceMm / 2.0
        assertTrue("first tangent point is on the driven side", first.x > mid)
        assertTrue("last tangent point is on the driver side", last.x < mid)
        assertTrue("tangent end points sit above the centres", first.y > 0.0 && last.y > 0.0)
        // The loop must enclose both pulley centres at its widest extent.
        val minX = loop.minOf { it.x }
        val maxX = loop.maxOf { it.x }
        assertTrue("loop spans driver", minX < 0.0 && maxX > r.centerDistanceMm)
    }

    @Test
    fun beltBandMeshIsSolid() {
        val t = belt().toBeltTransmission()
        val band = BeltBuilder.beltBandMesh(t)
        assertTrue("band has vertices", band.vertices.isNotEmpty())
        assertTrue("band has triangles", band.triangles.isNotEmpty())
        // Every triangle references valid vertex indices.
        val n = band.vertices.size
        band.triangles.forEach { tri ->
            tri.forEach { idx -> assertTrue("index $idx in [0,$n)", idx in 0 until n) }
        }
    }

    @Test
    fun mergedBeltContainsBeltAndPulleys() {
        val p = belt()
        val merged = GearBuilder.merged(p)
        assertTrue(merged.vertices.isNotEmpty())
        assertTrue(merged.triangles.isNotEmpty())
        // The belt band plus two pulleys produce a wide X extent.
        val minX = merged.vertices.minOf { it.x }
        val maxX = merged.vertices.maxOf { it.x }
        assertTrue("merged belt spans both pulleys", maxX - minX > 10.0)
        // Extruded band + two pulleys are solid: multiple separate triangles.
        assertTrue(merged.triangles.size > 100)
    }

    @Test
    fun beltSpecDefaultsAndValidation() {
        val p = GearSpec.defaults(GearType.BELT)
        assertEquals(GearType.BELT, p.gearType)
        // Default 20:40 resolves to a 2:1 ratio with a sane belt tooth count.
        val t = p.toBeltTransmission()
        val r = BeltCalculator.resolve(t)
        assertEquals(2.0, r.ratio, 1e-9)
        assertTrue(r.beltTeeth > 40)
        // No warnings for the sane default.
        assertTrue(GearSpec.validate(p).none { it.severity == GearSeverity.ERROR })
        // An undersized pulley must surface the belt-teeth warning.
        val bad = p.copy(beltDriverTeeth = 5)
        assertTrue(GearSpec.validate(bad).any { it.code == GearSpec.WARN_BELT_TEETH })
    }
}
