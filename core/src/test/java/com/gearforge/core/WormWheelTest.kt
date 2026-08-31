package com.gearforge.core

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Verifies the throated (globoid) worm wheel: a closed 2-manifold whose rim follows
 * a concave arc of radius = worm pitch radius, so the wheel wraps around the screw.
 */
class WormWheelTest {

    private fun wormPair(params: GearParams = GearSpec.defaults(GearType.WORM_PAIR)): GearAssembly {
        val a = GearBuilder.assembly(params.coerced())
        assertTrue("worm pair must produce 2 meshes", a.meshes.size == 2)
        return a
    }

    @Test
    fun wormWheelIsWatertight() {
        val a = wormPair()
        val wheel = a.meshes[1]
        val worm = a.meshes[0]
        val vw = MeshOps.validate(wheel)
        assertTrue("throated wheel not watertight: ${vw.issues}", vw.isValid)
        val vo = MeshOps.validate(worm)
        assertTrue("worm not watertight: ${vo.issues}", vo.isValid)
        assertTrue("wheel volume must be positive", MeshOps.signedVolume(wheel) > 0.0)
    }

    @Test
    fun throatRadiusMatchesWormPitchRadius() {
        val m = 1.0
        val wormStarts = 1
        val wheelTeeth = 30
        val p = GearSpec.defaults(GearType.WORM_PAIR)
            .copy(module = m, wormStarts = wormStarts, wheelTeeth = wheelTeeth, thickness = 6.0)
            .coerced()
        val a = wormPair(p)
        val wheel = a.meshes[1]

        val rp = m * wheelTeeth / 2.0                 // wheel pitch radius at throat
        val rWorm = m * (wormStarts * 4) / 2.0        // worm pitch radius = throat arc radius

        // The wheel is centred on z = 0. At the central plane the outermost radius
        // (tooth tip) must equal (rp + addendum) at scale 1; away from the centre it
        // must bulge to (rp + rWorm − √(rWorm² − z²) + addendum·scale). Verify the
        // pitch arc instead: sample the max radius at several z and compare against
        // rp + rWorm − √(rWorm² − z²) (the arc the pitch circle follows).
        for (z in doubleArrayOf(0.0, 0.5, 1.0, 1.5, 2.0)) {
            val expected = rp + rWorm - kotlin.math.sqrt(rWorm * rWorm - z * z)
            val actual = maxRadiusAtZ(wheel, z)
            // The max radius is the tooth tip (pitch + addendum·scale); compare the
            // tip-to-tip offset which is constant = addendum at the same scale. Simply
            // check the ratio: actual should equal expected + addendum.
            val add = m
            val tol = 0.35 // tooth tip vs pitch arc offset (addendum) plus sampling tolerance
            assertTrue(
                "throat at z=$z: expected ~${expected + add}, got $actual",
                abs(actual - (expected + add)) < tol
            )
        }

        // Concavity: the rim is narrower at the centre than at the faces.
        val rMid = maxRadiusAtZ(wheel, 0.0)
        val rFace = maxRadiusAtZ(wheel, p.thickness / 2.0)
        assertTrue("throat ($rMid) must be smaller than face rim ($rFace)", rMid < rFace)
    }

    @Test
    fun wormWheelValidAcrossParameterRange() {
        val cases = listOf(
            GearParams(gearType = GearType.WORM_PAIR, module = 1.0, wormStarts = 1, wheelTeeth = 30, helixAngleDeg = 75.0),
            GearParams(gearType = GearType.WORM_PAIR, module = 0.5, wormStarts = 2, wheelTeeth = 40, helixAngleDeg = 80.0),
            GearParams(gearType = GearType.WORM_PAIR, module = 2.0, wormStarts = 1, wheelTeeth = 20, helixAngleDeg = 70.0),
            GearParams(gearType = GearType.WORM_PAIR, module = 1.0, wormStarts = 1, wheelTeeth = 30, helixAngleDeg = 0.0),
            GearParams(gearType = GearType.WORM_PAIR, module = 1.0, wormStarts = 3, wheelTeeth = 30, helixAngleDeg = -75.0)
        )
        for (raw in cases) {
            val p = raw.coerced()
            val a = GearBuilder.assembly(p)
            for ((i, mesh) in a.meshes.withIndex()) {
                val v = MeshOps.validate(mesh)
                assertTrue("mesh $i (${p.wormStarts} start, ${p.wheelTeeth} teeth) not watertight: ${v.issues}", v.isValid)
            }
        }
    }

    private fun maxRadiusAtZ(mesh: Mesh, z: Double): Double {
        var maxR = 0.0
        for (v in mesh.vertices) {
            if (abs(v.z - z) < 0.25) {
                val r = kotlin.math.hypot(v.x, v.y)
                if (r > maxR) maxR = r
            }
        }
        return maxR
    }
}
