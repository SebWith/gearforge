package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies that multi-gear assemblies are phase-aligned so teeth mesh instead of collide. */
class AssemblyPhaseTest {

    @Test
    fun planetRingSatisfiesToothConstraint() {
        val p = GearSpec.defaults(GearType.PLANETARY)
        val a = GearBuilder.planetary(p)
        // Hard constraint Zr = Zs + 2·Zp.
        assertEquals(a.sunTeeth + 2 * a.planetTeeth, a.ringTeeth)
    }

    @Test
    fun planetsAreRotatedIntoMesh() {
        val p = GearSpec.defaults(GearType.PLANETARY) // Zs=12, Zp=12, N=3
        val a = GearBuilder.planetary(p)
        val n = a.planetCenters.size
        assertTrue(n >= 2)
        val zs = a.sunTeeth.toDouble()
        val zp = a.planetTeeth.toDouble()
        for (i in 1 until n) {
            // φᵢ − φ₀ = −2πi/N · (Zs/Zp); planet i must be planet 0 rotated by that amount.
            val delta = -2.0 * Math.PI * i / n * (zs / zp)
            val expected = MeshOps.rotateZ(a.planets[0], delta)
            val actual = a.planets[i]
            for (k in 0 until 20) {
                assertEquals("vertex $k x", expected.vertices[k].x, actual.vertices[k].x, 1e-9)
                assertEquals("vertex $k y", expected.vertices[k].y, actual.vertices[k].y, 1e-9)
            }
        }
    }

    @Test
    fun planetsDifferFromEachOther() {
        val p = GearSpec.defaults(GearType.PLANETARY)
        val a = GearBuilder.planetary(p)
        assertFalse("planets must be rotated individually", a.planets[0].vertices == a.planets[1].vertices)
    }

    @Test
    fun rackPinionStillBuildsAndIsPlaced() {
        val p = GearSpec.defaults(GearType.RACK)
        val a = GearBuilder.assembly(p)
        assertEquals(2, a.meshes.size)
        // Pinion pitch radius above the rack pitch line.
        val pr = GearCalculator.pitchRadius(p.module, p.pinionTeeth)
        assertEquals(0.0, a.offsets[1].x, 1e-9)
        assertEquals(pr, a.offsets[1].y, 1e-9)
        // Pinion must be rotated (its tooth 0 no longer points purely +x).
        val pinion = a.meshes[1]
        val unrotated = GearBuilder.mesh(p.copy(gearType = GearType.SPUR, teeth = p.pinionTeeth))
        assertFalse("pinion must be rotated into the rack gap", pinion.vertices == unrotated.vertices)
    }
}
