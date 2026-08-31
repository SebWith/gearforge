package com.gearforge.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Systematic geometric matrix across every supported gear type and a wide set of
 * parameter combinations (audit X1). Each case must produce a closed 2-manifold
 * mesh with strictly positive volume, zero degenerate triangles and no
 * non-manifold/open edges. Duplicate vertices are permitted only where a hub boss
 * meets the gear face (two touching solids).
 *
 * Every failing combination is collected and reported with its gear type and
 * parameters before the test is failed, so a single run surfaces all deviations.
 */
class AllGearTypesMatrixTest {

    private fun assertSolid(mesh: Mesh, label: String, failures: MutableList<String>) {
        for (v in mesh.vertices) {
            if (!v.x.isFinite() || !v.y.isFinite() || !v.z.isFinite()) {
                failures.add("$label: non-finite vertex $v")
                return
            }
        }
        val r = MeshOps.validate(mesh)
        val fatal = r.issues.filterNot { it.contains("duplicate vertices") }
        if (fatal.isNotEmpty()) {
            failures.add("$label: ${fatal.joinToString("; ")}")
            return
        }
        val vol = MeshOps.signedVolume(mesh)
        if (!vol.isFinite() || vol <= 0.0) {
            failures.add("$label: non-positive volume $vol")
        }
    }

    private fun build(label: String, p: GearParams, failures: MutableList<String>): Mesh {
        val mesh = try {
            GearBuilder.merged(p.coerced())
        } catch (e: Exception) {
            failures.add("$label threw ${e::class.simpleName}: ${e.message}")
            return Mesh(emptyList(), emptyList())
        }
        assertSolid(mesh, label, failures)
        return mesh
    }

    @Test
    fun allGearTypesAtDefaults() {
        val failures = ArrayList<String>()
        for (type in GearType.entries) {
            build("defaults $type", GearSpec.defaults(type), failures)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun allGearTypesAtParametricExtremes() {
        val failures = ArrayList<String>()
        for (type in GearType.entries) {
            val base = GearSpec.defaults(type)
            // Module extremes, tooth extremes, steep helix / lead angle.
            for (module in doubleArrayOf(0.5, 1.0, 5.0, 10.0)) {
                val p = base.copy(
                    module = module,
                    teeth = if (GearSpec.hasGearBody(type)) 40 else base.teeth,
                    helixAngleDeg = if (type == GearType.HELICAL || type == GearType.SCREW_GEAR) 45.0
                    else if (type == GearType.WORM_PAIR) 80.0 else base.helixAngleDeg,
                    thickness = 8.0
                )
                build("extreme $type m=$module", p, failures)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun boreTypeMatrix() {
        val failures = ArrayList<String>()
        val boreTypes = listOf(
            "none" to BoreSpec(type = BoreType.NONE, diameter = 0.0),
            "round" to BoreSpec(type = BoreType.ROUND, diameter = 8.0),
            "dcut" to BoreSpec(type = BoreType.D_CUT, diameter = 8.0),
            "keyway" to BoreSpec(type = BoreType.KEYWAY, diameter = 8.0, keywayWidth = 2.0, keywayDepth = 1.0),
            "hex" to BoreSpec(type = BoreType.HEX, hexAcrossFlats = 8.0),
            "square" to BoreSpec(type = BoreType.SQUARE, squareAcrossFlats = 8.0)
        )
        for (type in listOf(GearType.SPUR, GearType.HELICAL, GearType.BEVEL)) {
            for ((name, bore) in boreTypes) {
                val p = GearSpec.defaults(type).copy(module = 2.0, teeth = 20, bore = bore)
                build("bore $type/$name", p, failures)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun hubAndWebMatrix() {
        val failures = ArrayList<String>()
        val base = GearSpec.defaults(GearType.SPUR).copy(module = 2.0, teeth = 20, bore = BoreSpec(type = BoreType.ROUND, diameter = 6.0))
        // Hub configurations.
        build("hub none", base, failures)
        build("hub single", base.copy(hubDiameter = 12.0, hubLength = 8.0), failures)
        build("hub double", base.copy(hubDiameter = 12.0, hubLeftLength = 4.0, hubRightLength = 6.0), failures)
        // Spoked web: 3..8 spokes at varying widths.
        for (count in 3..8) {
            for (width in doubleArrayOf(3.0, 6.0)) {
                build("spokes c=$count w=$width", base.copy(spokeCount = count, spokeWidth = width), failures)
            }
        }
        // Lightening holes: 3..8 holes.
        for (count in 3..8) {
            build("lightening c=$count", base.copy(lighteningHoleCount = count), failures)
        }
        // Hub + spokes + keyed bore (complex web).
        build(
            "hub+spokes+keyway",
            base.copy(hubDiameter = 12.0, hubLength = 8.0, spokeCount = 6, spokeWidth = 5.0,
                bore = BoreSpec(type = BoreType.KEYWAY, diameter = 6.0, keywayWidth = 2.0, keywayDepth = 1.0)),
            failures
        )
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun profileShiftAndBacklashExtremes() {
        val failures = ArrayList<String>()
        for (type in listOf(GearType.SPUR, GearType.HELICAL)) {
            for (shift in doubleArrayOf(-0.5, -0.25, 0.0, 0.25, 0.5)) {
                for (backlash in doubleArrayOf(0.0, 0.1, 0.2)) {
                    val p = GearSpec.defaults(type).copy(module = 1.5, teeth = 24, profileShift = shift, backlash = backlash)
                    build("shift/backlash $type s=$shift b=$backlash", p, failures)
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun faceWidthAndToothCountExtremes() {
        val failures = ArrayList<String>()
        for (type in listOf(GearType.SPUR, GearType.HELICAL, GearType.BEVEL)) {
            for (thickness in doubleArrayOf(1.0, 6.0, 25.0, 50.0)) {
                build("face width $type t=$thickness", GearSpec.defaults(type).copy(module = 1.0, teeth = 20, thickness = thickness), failures)
            }
        }
        // High tooth-count ratio (planetary) and high tooth count (spur).
        build("spur z=200", GearSpec.defaults(GearType.SPUR).copy(module = 0.5, teeth = 200), failures)
        build("planetary high ratio", GearSpec.defaults(GearType.PLANETARY).copy(module = 1.0, teeth = 12, planetTeeth = 40, planetCount = 4), failures)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun squareBoreIsWatertight() {
        val failures = ArrayList<String>()
        // Plain square bore across every single-body gear type, at several sizes.
        for (type in listOf(GearType.SPUR, GearType.HELICAL, GearType.BEVEL, GearType.CYCLOIDAL)) {
            for (size in doubleArrayOf(4.0, 8.0, 12.0)) {
                val p = GearSpec.defaults(type).copy(
                    module = 2.0, teeth = 24,
                    bore = BoreSpec(type = BoreType.SQUARE, squareAcrossFlats = size)
                )
                build("square bore $type s=$size", p, failures)
            }
        }
        // Square bore combined with hub boss and spokes stays a closed manifold.
        build(
            "square bore + hub + spokes",
            GearSpec.defaults(GearType.SPUR).copy(
                module = 2.0, teeth = 24,
                bore = BoreSpec(type = BoreType.SQUARE, squareAcrossFlats = 6.0),
                hubDiameter = 14.0, hubLength = 8.0, spokeCount = 6, spokeWidth = 4.0
            ),
            failures
        )
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun requiredGearTypesExhaustiveCoverage() {
        val failures = ArrayList<String>()
        val required = listOf(
            GearType.SPUR, GearType.HELICAL, GearType.BEVEL, GearType.INTERNAL_RING,
            GearType.RACK, GearType.PLANETARY, GearType.WORM_PAIR, GearType.BELT
        )
        for (type in required) {
            build("required $type defaults", GearSpec.defaults(type), failures)
            when (type) {
                GearType.BELT -> {
                    // Two belt profiles with a high tooth-count ratio and a narrow belt.
                    build(
                        "required BELT GT2 8:80",
                        GearSpec.defaults(GearType.BELT).copy(
                            beltProfile = "GT2", beltDriverTeeth = 8, beltDrivenTeeth = 80, beltWidthMm = 2.0
                        ),
                        failures
                    )
                    build(
                        "required BELT HTD 5M 8:60",
                        GearSpec.defaults(GearType.BELT).copy(
                            beltProfile = "HTD 5M", beltDriverTeeth = 8, beltDrivenTeeth = 60, beltWidthMm = 5.0
                        ),
                        failures
                    )
                }
                GearType.RACK -> build(
                    "required RACK extremes",
                    GearSpec.defaults(GearType.RACK).copy(module = 10.0, rackLength = 200.0, pinionTeeth = 12),
                    failures
                )
                GearType.WORM_PAIR -> build(
                    "required WORM extremes",
                    GearSpec.defaults(GearType.WORM_PAIR).copy(
                        module = 3.0, wormStarts = 8, wheelTeeth = 120, helixAngleDeg = 85.0
                    ),
                    failures
                )
                else -> {
                    // Module × profile-shift boundary pair for every single gear body
                    // (module 10 for all types is also swept by allGearTypesAtParametricExtremes).
                    build(
                        "required $type m=0.5 s=-0.5",
                        GearSpec.defaults(type).copy(module = 0.5, profileShift = -0.5),
                        failures
                    )
                    build(
                        "required $type m=10.0 s=0.5",
                        GearSpec.defaults(type).copy(module = 10.0, profileShift = 0.5),
                        failures
                    )
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
