package com.gearforge.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the mesh-build time actually goes.
 *
 * This started as a measurement, not a guess: opening the export sheet left the info rows empty
 * for ~3.8 s on the emulator, and the app-side probe showed `mesh=3393ms bounds=3ms`. The whole
 * cost is inside [GearBuilder.merged], which every 3D export (STL/3MF/STEP/IGES) also calls, so
 * the number matters far beyond one label in a dialog.
 *
 * The per-stage timings are printed on every run, so a future regression shows up as a named
 * stage rather than as "the export feels slow again".
 */
class MeshBuildPerformanceTest {

    private fun fixture(): GearParams = GearSpec.defaults(GearType.SPUR)

    private fun timeMs(repeats: Int = 3, block: () -> Unit): Long {
        var best = Long.MAX_VALUE
        repeat(repeats) {
            val t0 = System.nanoTime()
            block()
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms < best) best = ms
        }
        return best
    }

    @Test
    fun stageTimingsArePrintedAndTheTotalStaysWithinBudget() {
        val p = fixture()

        // Warm up the JIT so the first repeat is not measured as a cold start.
        GearBuilder.merged(p)

        val shapeMs = timeMs { GearBuilder.shape(p) }

        val shape = GearBuilder.shape(p)
        val triangulateMs = timeMs { Triangulate.triangulate(shape) }

        val mergedMs = timeMs { GearBuilder.merged(p) }

        val mesh = GearBuilder.merged(p)
        val boundsMs = timeMs { MeshOps.bounds(mesh) }
        val validateMs = timeMs { MeshOps.validate(mesh) }

        println(
            "MESH_BUILD_PERF shape=${shapeMs}ms triangulate=${triangulateMs}ms " +
                "merged=${mergedMs}ms bounds=${boundsMs}ms validate=${validateMs}ms " +
                "vertices=${mesh.vertices.size} triangles=${mesh.triangles.size}"
        )

        // The budget is deliberately loose: it is a regression tripwire, not a benchmark.
        // A standard spur gear is a small mesh; anything close to a second is a defect.
        assertTrue(
            "merged() took ${mergedMs}ms for ${mesh.triangles.size} triangles - something is quadratic",
            mergedMs < 400
        )
    }
}
