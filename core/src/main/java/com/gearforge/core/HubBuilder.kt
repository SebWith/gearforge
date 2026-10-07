package com.gearforge.core

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.PI
import kotlin.math.sin

/**
 * Builds the asymmetric hub/boss/collar as one or two annular protrusions (left and
 * right of the gear face). Each protrusion is an extruded annulus carrying the shaft
 * bore hole plus the radial grub-screw through-holes, so the hub is watertight and
 * the screws reach radially from the hub outside into the bore.
 */
object HubBuilder {

    /** ISO metric thread minor (tap drill) diameter in mm. */
    fun screwMinorRadius(thread: String): Double = when (thread) {
        "M2.5" -> 2.05
        "M4" -> 3.3
        "M5" -> 4.2
        "M6" -> 5.0
        else -> 2.5 // M3 default
    }

    fun hasHub(p: GearParams): Boolean =
        GearCalculator.effectiveHubLeft(p) > 0.0 || GearCalculator.effectiveHubRight(p) > 0.0

    internal const val MIN_CHAMFER_WALL = 0.2

    internal fun chamferLimit(params: GearParams): Double {
        val sides = listOf(
            GearCalculator.effectiveHubLeft(params) to params.hubLeftBoreFollowsShaft,
            GearCalculator.effectiveHubRight(params) to params.hubRightBoreFollowsShaft
        ).filter { it.first > 0.0 }
        return sides.minOfOrNull { (height, follows) ->
            val shape = hubShape(params, follows)
            val holeRadius = shape.holes.flatten().maxOfOrNull { hypot(it.x, it.y) } ?: 0.0
            val radialLimit = params.hubDiameter / 2.0 -
                (holeRadius + MIN_CHAMFER_WALL) / cos(PI / shape.outer.size)
            minOf(height, radialLimit).coerceAtLeast(0.0)
        }?.takeIf { it.isFinite() } ?: 0.0
    }

    private fun hubShape(params: GearParams, follows: Boolean): PlanarShape {
        val outerRadius = params.hubDiameter / 2.0
        val boreRadius = params.bore.diameter / 2.0
        val holes = ArrayList<List<Vec2>>()
        if (params.bore.type != BoreType.NONE) {
            holes.add(if (follows) (Bore.holes(params).firstOrNull() ?: Bore.round(boreRadius))
                else Bore.round(boreRadius))
        }
        val screwRadius = screwMinorRadius(params.setScrewThread) / 2.0
        for (index in 0 until params.setScrewCount) {
            val angle = Math.toRadians(if (index == 0) params.setScrewAngleDeg else params.setScrewAngle2Deg)
            val middleRadius = (outerRadius + boreRadius) / 2.0
            val center = Vec2(middleRadius * cos(angle), middleRadius * sin(angle))
            if (screwRadius > 0.0) holes.add(Bore.round(screwRadius).map { it + center })
        }
        return PlanarShape(Bore.round(outerRadius), holes)
    }

    fun build(p: GearParams): Mesh {
        val hubL = GearCalculator.effectiveHubLeft(p)
        val hubR = GearCalculator.effectiveHubRight(p)
        val rOuter = p.hubDiameter / 2.0
        val boreR = if (p.bore.type == BoreType.NONE) 0.0 else p.bore.diameter / 2.0
        if (!hasHub(p) || rOuter <= boreR) return Mesh(emptyList(), emptyList())

        val chamfer = if (p.hubChamfer.isFinite()) p.hubChamfer.coerceIn(0.0, chamferLimit(p)) else 0.0

        fun protrusion(height: Double, zBase: Double, follows: Boolean, atStart: Boolean): Mesh {
            if (height <= 0.0) return Mesh(emptyList(), emptyList())
            val mesh = Loft.loftWithOuterChamfer(hubShape(p, follows), height, chamfer, atStart)
            val used = BooleanArray(mesh.vertices.size)
            for (triangle in mesh.triangles) for (index in triangle) used[index] = true
            val remap = IntArray(mesh.vertices.size)
            val vertices = ArrayList<Vec3>()
            for ((index, vertex) in mesh.vertices.withIndex()) {
                if (used[index]) {
                    remap[index] = vertices.size
                    vertices.add(Vec3(vertex.x, vertex.y, vertex.z + zBase))
                }
            }
            return Mesh(vertices, mesh.triangles.map { triangle -> IntArray(3) { remap[triangle[it]] } })
        }

        return MeshOps.merge(listOf(
            protrusion(hubL, -hubL, p.hubLeftBoreFollowsShaft, atStart = true),
            protrusion(hubR, p.thickness, p.hubRightBoreFollowsShaft, atStart = false)
        ))
    }
}
