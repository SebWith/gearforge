package com.gearforge.core

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Regression tests for the involute spur profile, in particular the root-fillet
 * geometry: the fillet must connect the flank to the root circle cleanly without
 * bulging above the base circle (the former long-way arc produced a cylindrical
 * protrusion between the teeth) and the extruded mesh must stay watertight.
 */
class SpurProfileTest {

    @Test
    fun rootFilletNeverBulgesAboveBaseCircle() {
        for (z in intArrayOf(12, 20, 40, 80)) {
            val p = GearParams(gearType = GearType.SPUR, module = 1.0, teeth = z, pressureAngleDeg = 20.0).coerced()
            val poly = GearProfiles.involuteSpur(p)
            val rb = GearCalculator.baseRadius(p.module, p.teeth, p.pressureAngleDeg)
            val rf = GearCalculator.rootRadius(p.module, p.teeth)
            for (v in poly) {
                val r = hypot(v.x, v.y)
                // No vertex may dip below the root circle.
                assertTrue("z=$z vertex radius $r below root circle $rf", r >= rf - 1e-6)
                // Vertices below the tip must not exceed the base circle (the fillet
                // must never bulge up into the flank region).
                if (r < rb + 0.01) {
                    assertTrue("z=$z fillet bulges to $r above base circle $rb", r <= rb + 1e-6)
                }
            }
        }
    }

    @Test
    fun spurMeshRemainsWatertight() {
        for (z in intArrayOf(12, 20, 40, 80)) {
            val p = GearParams(gearType = GearType.SPUR, module = 1.0, teeth = z, pressureAngleDeg = 20.0).coerced()
            val mesh = GearBuilder.mesh(p)
            val v = MeshOps.validate(mesh)
            assertTrue("spur z=$z not watertight: ${v.issues}", v.isValid)
        }
    }
}
