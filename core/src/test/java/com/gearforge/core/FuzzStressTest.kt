package com.gearforge.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fuzzes extreme (but coerced) parameter combinations across every gear type and
 * asserts that no geometry generation throws or emits NaN/Infinite vertices.
 */
class FuzzStressTest {

    private fun assertFinite(mesh: Mesh, label: String) {
        for (v in mesh.vertices) {
            assertTrue(
                "$label produced non-finite vertex ($v)",
                v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
            )
        }
        val n = mesh.vertices.size
        for (t in mesh.triangles) {
            assertTrue("$label triangle index out of range", t.all { it in 0 until n })
        }
    }

    private fun build(label: String, p: GearParams): Mesh {
        val m = try {
            GearBuilder.mesh(p)
        } catch (e: Exception) {
            throw AssertionError("$label threw: ${e::class.simpleName}: ${e.message}", e)
        }
        assertFinite(m, label)
        return m
    }

    @Test
    fun spurExtremesNeverFail() {
        val modules = doubleArrayOf(0.2, 0.5, 1.0, 5.0, 12.0)
        val teeth = intArrayOf(5, 20, 60, 150)
        val pressures = doubleArrayOf(14.5, 20.0, 30.0)
        for (m in modules) for (z in teeth) for (pa in pressures) {
            val p = GearParams(
                gearType = GearType.SPUR, module = m, teeth = z, pressureAngleDeg = pa
            ).coerced()
            build("spur m=$m z=$z pa=$pa", p)
        }
    }

    @Test
    fun boreNearRootNeverFails() {
        // Bore diameter just under the root circle (thin wall).
        val p = GearParams(teeth = 20, module = 1.0, bore = BoreSpec(type = BoreType.ROUND, diameter = 17.0)).coerced()
        build("bore near root", p)
    }

    @Test
    fun lighteningHolesWithBoreAreWatertight() {
        // The multi-hole bridge triangulation must stay a closed 2-manifold when a
        // centre bore coexists with a ring of lightening holes (audit L1: the old
        // incremental splicing let a later hole's bridge land on an earlier hole's
        // slit, producing non-manifold edges and visible holes in the model).
        for (count in 1..12) {
            for (dia in doubleArrayOf(0.0, 2.0, 4.0, 8.0)) {
                for (pcd in doubleArrayOf(0.0, 10.0, 20.0, 40.0)) {
                    val p = GearParams(
                        gearType = GearType.SPUR, module = 1.0, teeth = 20,
                        lighteningHoleCount = count,
                        lighteningHoleDiameter = dia,
                        lighteningHolePCD = pcd
                    ).coerced()
                    val mesh = build("lightening c=$count d=$dia p=$pcd", p)
                    val r = MeshOps.validate(mesh)
                    if (Bore.lighteningPlan(p).count > 0) {
                        assertTrue("c=$count d=$dia p=$pcd not watertight: ${r.issues}", r.isValid)
                    }
                }
            }
        }
    }

    @Test
    fun spokeMeshesAreManifold() {
        // Spoke wedges must leave a watertight mesh for every spoke count, with or
        // without a hub boss (audit L2/L3). Duplicate vertices are expected where
        // the hub meets the gear face (two touching solids); everything else is a
        // real defect.
        for (count in intArrayOf(3, 4, 5, 6, 8, 12)) {
            for (hub in booleanArrayOf(false, true)) {
                val p = GearParams(
                    gearType = GearType.SPUR, module = 1.0, teeth = 20,
                    spokeCount = count, spokeWidth = 4.0,
                    hubDiameter = if (hub) 10.0 else 0.0,
                    hubLength = if (hub) 6.0 else 0.0
                ).coerced()
                val mesh = build("spoke c=$count hub=$hub", p)
                val r = MeshOps.validate(mesh)
                val fatal = r.issues.filterNot { it.contains("duplicate vertices") }
                assertTrue("spoke c=$count hub=$hub: $fatal", fatal.isEmpty())
            }
        }
        // Spokes + lightening holes are mutually exclusive: lightening is dropped so
        // the spoke webs stay clean (audit L3).
        val both = GearParams(
            gearType = GearType.SPUR, module = 1.0, teeth = 20,
            spokeCount = 6, spokeWidth = 4.0,
            lighteningHoleCount = 4
        ).coerced()
        val mesh = build("spokes+lightening", both)
        val r = MeshOps.validate(mesh)
        assertTrue("spokes+lightening: ${r.issues}", r.issues.filterNot { it.contains("duplicate vertices") }.isEmpty())
    }

    @Test
    fun oversizeBoreIsClampedAndWatertight() {
        // Small gears with the default 5 mm bore would cut through the teeth; the
        // coerced bore clamp must shrink it into the root circle so the mesh stays
        // watertight (regression for the cycloid z=6 non-manifold mesh).
        val cases = listOf(
            GearParams(gearType = GearType.SPUR, module = 1.0, teeth = 5, toothProfile = ToothProfile.INVOLUTE),
            GearParams(gearType = GearType.CYCLOIDAL, module = 1.0, teeth = 6, toothProfile = ToothProfile.CYCLOID),
            GearParams(gearType = GearType.SPUR, module = 0.5, teeth = 8, toothProfile = ToothProfile.INVOLUTE)
        )
        for (raw in cases) {
            val p = raw.coerced()
            val mesh = build("oversize bore ${p.gearType} z=${p.teeth}", p)
            val v = MeshOps.validate(mesh)
            assertTrue("${p.gearType} z=${p.teeth} not watertight: ${v.issues}", v.isValid)
        }
    }

    @Test
    fun helicalAtMaxHelixNeverFails() {
        val p = GearParams(gearType = GearType.HELICAL, module = 0.5, teeth = 30, helixAngleDeg = 45.0).coerced()
        build("helical 45°", p)
    }

    @Test
    fun allGearTypesSurviveExtremes() {
        val extremes = GearParams(module = 12.0, teeth = 150, pressureAngleDeg = 30.0)
            .copy(bore = BoreSpec(type = BoreType.KEYWAY, diameter = 40.0))
        for (type in GearType.entries) {
            val p = try {
                GearSpec.defaults(type).let { d ->
                    d.copy(
                        module = 12.0,
                        teeth = if (GearSpec.hasGearBody(type)) 150 else d.teeth,
                        pressureAngleDeg = 30.0,
                        thickness = 12.0
                    )
                }.coerced()
            } catch (e: Exception) {
                // Rack/belt have no direct teeth field; keep their defaults.
                GearSpec.defaults(type).coerced()
            }
            build("type $type", p)
        }
    }

    @Test
    fun cycloidExtremesNeverFail() {
        for (z in intArrayOf(6, 8, 12, 40, 150)) {
            val p = GearParams(
                gearType = GearType.CYCLOIDAL, module = 1.0, teeth = z, toothProfile = ToothProfile.CYCLOID
            ).coerced()
            val mesh = build("cycloid z=$z", p)
            val v = MeshOps.validate(mesh)
            assertTrue("cycloid z=$z not watertight: ${v.issues}", v.isValid)
        }
    }

    @Test
    fun cycloidBelowMinimumDefensivelyClamped() {
        // Raw (uncoerced) z=5 is geometrically impossible for a cycloid with the
        // R/4 generating circle; the profile generator must defensively clamp to 6
        // teeth rather than emit a degenerate, non-watertight mesh.
        val p = GearParams(
            gearType = GearType.CYCLOIDAL, module = 1.0, teeth = 5, toothProfile = ToothProfile.CYCLOID,
            bore = BoreSpec(type = BoreType.NONE)
        )
        val mesh = build("cycloid raw z=5", p)
        val v = MeshOps.validate(mesh)
        assertTrue("raw cycloid z=5 not watertight: ${v.issues}", v.isValid)
    }
}
