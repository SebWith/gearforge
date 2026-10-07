package com.gearforge.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** A world-space ray: [origin] plus [dir]. [dir] need not be unit length, but must not be zero. */
data class Ray(val origin: Vec3, val dir: Vec3)

/**
 * Which tooth a tap lands on, without raycasting the mesh.
 *
 * `GearGLView` could already raycast every triangle of the mesh, but that costs O(triangles) on the
 * UI thread (a helical gear is tens of thousands of triangles) and it answers the wrong question.
 * A tooth index is a property of the *outline*, so it can be computed analytically: intersect the
 * tap ray with the gear body's mid-plane and see which angular sector the hit lands in. O(1), exact,
 * and — because it needs no GL context — testable on the JVM like the rest of the geometry.
 *
 * The sector centres are deliberately **not** derived from a phase constant. They are read back from
 * the generated outline ([toothCentreAngles]), so the index always refers to the tooth the user can
 * actually see, even if the profile's starting phase ever changes. If the tip detection does not find
 * exactly one tip per tooth the pick is refused rather than guessed: editing the wrong tooth is worse
 * than not editing at all.
 */
object ToothPick {

    /**
     * Radial depth of the band that counts as a tooth tip, in modules.
     *
     * Only a handful of outline points sit within 5 % of a module of the tip radius, and they are
     * symmetric about the tooth centre, so the mean angle of the band is the tooth centre. The exact
     * width therefore does not matter — as long as the band never reaches into a neighbouring tooth.
     */
    private const val TIP_BAND_IN_MODULES = 0.05

    /**
     * Gear types whose displayed body is a single external gear turning about Z.
     *
     * Everything else (rack, belt, ring, planetary, bevel, hypoid, worm) either has no rotational
     * symmetry about Z or shows more than one body, so "the Nth tooth" has no single meaning.
     */
    private val TURNS_ABOUT_Z: Set<GearType> = setOf(
        GearType.SPUR,
        GearType.HELICAL,
        GearType.CYCLOIDAL,
        GearType.COMPOUND,
        GearType.FACE_GEAR,
        GearType.SCREW_GEAR
    )

    /** True when [type] is a single external gear turning about Z, i.e. when picking is meaningful. */
    fun supports(type: GearType): Boolean = type in TURNS_ABOUT_Z

    /** Angular centre of every tooth (radians, atan2 convention), ordered by tooth number. */
    fun toothCentreAngles(p: GearParams): List<Double>? = body(p)?.centres

    /**
     * Tooth index under a world-space [ray], or null when the ray misses the gear body
     * (off the rim, down the bore, behind the gear, or parallel to the mid-plane).
     */
    fun indexAt(ray: Ray, p: GearParams): Int? {
        val plane = midPlaneOffset(p)
        if (abs(ray.dir.z) < 1e-9) return null   // looking edge-on: the tooth is not identifiable
        val t = (plane - ray.origin.z) / ray.dir.z
        if (t <= 0.0) return null                // the plane is behind the viewer
        val x = ray.origin.x + ray.dir.x * t
        val y = ray.origin.y + ray.dir.y * t
        return indexAtPoint(x, y, p)
    }

    /**
     * Tooth index for a point already known to lie on the gear body's mid-plane.
     *
     * [x] and [y] are millimetres in the gear's own frame, which is also the frame the mesh is
     * generated in (the single-body types all sit at the origin).
     */
    fun indexAtPoint(x: Double, y: Double, p: GearParams): Int? {
        val b = body(p) ?: return null
        val r = hypot(x, y)
        if (r > b.tipRadius) return null
        // A tap down the shaft hole is not a tooth. `displayDiameter` is null exactly when the
        // type cuts no bore or the bore is off, which is also when there is nothing to exclude.
        val boreRadius = (Bore.displayDiameter(p) ?: 0.0) / 2.0
        if (r < boreRadius) return null
        val angle = atan2(y, x)
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for (i in b.centres.indices) {
            val d = angularDistance(angle, b.centres[i])
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    /** The mid-plane of the gear body: the loft spans z in [0, thickness]. */
    fun midPlaneOffset(p: GearParams): Double = p.thickness / 2.0

    private class Body(val centres: List<Double>, val tipRadius: Double)

    private fun body(p: GearParams): Body? {
        if (!supports(p.gearType)) return null
        if (p.teeth < 1) return null
        val outline = runCatching { GearBuilder.shape(p).outer }.getOrNull() ?: return null
        val n = outline.size
        if (n < 3 * p.teeth) return null   // outline too coarse to resolve individual teeth

        val radii = DoubleArray(n) { hypot(outline[it].x, outline[it].y) }
        var tipRadius = 0.0
        for (v in radii) if (v > tipRadius) tipRadius = v
        if (tipRadius <= 0.0) return null

        val band = TIP_BAND_IN_MODULES * abs(p.module).coerceAtLeast(1e-6)
        val isTip = BooleanArray(n) { radii[it] >= tipRadius - band }

        // The outline is a closed loop, so tip points come in contiguous runs. Start the sweep at a
        // run boundary so no run is split by the wrap-around.
        var start = -1
        for (i in 0 until n) {
            if (isTip[i] && !isTip[(i - 1 + n) % n]) { start = i; break }
        }
        if (start < 0) return null

        val centres = ArrayList<Double>(p.teeth)
        var i = 0
        while (i < n) {
            val idx = (start + i) % n
            if (!isTip[idx]) { i++; continue }
            // Mean of the run as unit vectors, so a run straddling the +-PI seam still averages
            // to its true centre instead of to zero.
            var sx = 0.0
            var sy = 0.0
            var count = 0
            while (i < n && isTip[(start + i) % n]) {
                val v = outline[(start + i) % n]
                val len = hypot(v.x, v.y)
                if (len > 1e-9) { sx += v.x / len; sy += v.y / len; count++ }
                i++
            }
            if (count > 0) centres.add(atan2(sy, sx))
        }
        if (centres.size != p.teeth) return null
        return Body(centres, tipRadius)
    }

    /** Smallest angle between two directions, in radians. */
    private fun angularDistance(a: Double, b: Double): Double {
        val twoPi = 2.0 * PI
        val d = abs(a - b) % twoPi
        return if (d > PI) twoPi - d else d
    }
}
