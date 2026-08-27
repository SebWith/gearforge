package com.gearforge.core

import kotlin.math.cos
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

    fun build(p: GearParams): Mesh {
        val hubL = GearCalculator.effectiveHubLeft(p)
        val hubR = GearCalculator.effectiveHubRight(p)
        val rOuter = p.hubDiameter / 2.0
        val boreR = p.bore.diameter / 2.0
        if (!hasHub(p) || rOuter <= boreR) return Mesh(emptyList(), emptyList())

        val screwR = screwMinorRadius(p.setScrewThread) / 2.0
        val screwPositions = (0 until p.setScrewCount).map { i ->
            val angle = Math.toRadians(if (i == 0) p.setScrewAngleDeg else p.setScrewAngle2Deg)
            val rMid = (rOuter + boreR) / 2.0
            Vec2(rMid * cos(angle), rMid * sin(angle))
        }

        fun protrusion(height: Double, zBase: Double): Mesh {
            if (height <= 0.0) return Mesh(emptyList(), emptyList())
            val holes = ArrayList<List<Vec2>>()
            if (p.bore.type != BoreType.NONE) holes.add(Bore.round(boreR))
            for (s in screwPositions) {
                if (screwR > 0.0) holes.add(Bore.round(screwR).map { Vec2(it.x + s.x, it.y + s.y) })
            }
            val mesh = MeshBuilder.extrude(PlanarShape(Bore.round(rOuter), holes), height)
            return Mesh(mesh.vertices.map { Vec3(it.x, it.y, it.z + zBase) }, mesh.triangles)
        }

        return MeshOps.merge(listOf(protrusion(hubL, -hubL), protrusion(hubR, p.thickness)))
    }
}
