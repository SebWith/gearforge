package com.gearforge.core

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Builds a swept/extruded mesh by lofting a planar shape along a straight path
 * with optional twist (helical) and scale taper (bevel).
 */
object Loft {

    fun loft(
        shape: PlanarShape,
        thickness: Double,
        twistRad: Double,
        scaleStart: Double,
        scaleEnd: Double,
        slices: Int
    ): Mesh = loftProfiled(
        shape, thickness, slices,
        scaleAt = { t -> scaleStart + (scaleEnd - scaleStart) * t },
        twistAt = { t -> twistRad * t }
    )

    /**
     * Lofts a planar shape along Z with a per-slice scale and twist profile. This
     * generalises [loft] to non-linear extrusions such as a throated (globoid) worm
     * wheel, whose rim follows a concave arc while its teeth lean to match the worm
     * helix. [scaleAt] and [twistAt] both receive t in [0, 1] (0 = front face,
     * 1 = back face) and return the local scale factor and the cumulative rotation
     * angle in radians. The cap triangulation and the side walls are built from the
     * same boundary-edge set, so the output is a closed 2-manifold for any continuous
     * profile as long as consecutive slices do not self-intersect.
     */
    fun loftProfiled(
        shape: PlanarShape,
        thickness: Double,
        slices: Int,
        scaleAt: (Double) -> Double,
        twistAt: (Double) -> Double
    ): Mesh {
        require(thickness > 0.0) { "thickness must be > 0" }
        require(slices >= 2) { "slices must be >= 2" }
        val (poly, tris2d) = Triangulate.triangulate(shape)
        val boundary = MeshBuilder.boundaryEdges(tris2d)
        val vCount = poly.size

        val vertices = ArrayList<Vec3>(vCount * (slices + 1))
        for (k in 0..slices) {
            val t = k.toDouble() / slices
            val ang = twistAt(t)
            val sc = scaleAt(t)
            val ca = cos(ang)
            val sa = sin(ang)
            val z = thickness * t
            for (p in poly) {
                val x = sc * (p.x * ca - p.y * sa)
                val y = sc * (p.x * sa + p.y * ca)
                vertices.add(Vec3(x, y, z))
            }
        }

        val triangles = ArrayList<IntArray>()
        for (t2 in tris2d) {
            triangles.add(intArrayOf(t2[0], t2[2], t2[1])) // front cap
            triangles.add(intArrayOf(slices * vCount + t2[0], slices * vCount + t2[1], slices * vCount + t2[2])) // back cap
        }
        for ((u, v) in boundary) {
            for (k in 0 until slices) {
                val a = k * vCount + u
                val b = k * vCount + v
                val c = (k + 1) * vCount + u
                val d = (k + 1) * vCount + v
                triangles.add(intArrayOf(a, b, d))
                triangles.add(intArrayOf(a, d, c))
            }
        }

        return MeshOps.orientOutward(Mesh(vertices, triangles))
    }

    /**
     * Straight extrusion with a 45° bottom-edge chamfer (first-layer / elephant-foot
     * compensation). The bottom face is inset by [chamfer] on the outer boundary and
     * each bore hole is enlarged by [chamfer], then lofted to the full profile at
     * z = chamfer; the remaining height is a straight extrusion. Falls back to a plain
     * extrusion when the inset would change the hole-bridge topology (e.g. an enlarged
     * bore touching the inset root), so the output is always a closed 2-manifold.
     */
    fun loftWithBottomChamfer(shape: PlanarShape, thickness: Double, chamfer: Double): Mesh {
        if (chamfer <= 0.0 || thickness <= chamfer + 0.2) {
            return MeshBuilder.extrude(shape, thickness)
        }
        val inset = insetShape(shape, chamfer)
        val full = Triangulate.triangulate(shape)
        val ins = Triangulate.triangulate(inset)
        val n = full.first.size
        if (ins.first.size != n) return MeshBuilder.extrude(shape, thickness)
        val boundary = MeshBuilder.boundaryEdges(full.second)
        val insBoundary = MeshBuilder.boundaryEdges(ins.second).toSet()
        if (boundary.any { (u, v) -> !insBoundary.contains(u to v) }) {
            return MeshBuilder.extrude(shape, thickness)
        }

        val verts = ArrayList<Vec3>(n * 3)
        for (v in ins.first) verts.add(Vec3(v.x, v.y, 0.0))              // slice 0: inset
        for (v in full.first) verts.add(Vec3(v.x, v.y, chamfer))         // slice 1: full at chamfer
        for (v in full.first) verts.add(Vec3(v.x, v.y, thickness))       // slice 2: full at top

        val tris = ArrayList<IntArray>()
        for (t in ins.second) tris.add(intArrayOf(t[0], t[2], t[1]))                        // bottom cap (−Z)
        for (t in full.second) tris.add(intArrayOf(2 * n + t[0], 2 * n + t[1], 2 * n + t[2])) // top cap (+Z)
        for ((u, v) in boundary) {
            // 45° chamfer: inset(u,v) → full(u,v) at z = chamfer
            tris.add(intArrayOf(u, v, n + v))
            tris.add(intArrayOf(u, n + v, n + u))
            // vertical wall: full at chamfer → full at top
            tris.add(intArrayOf(n + u, n + v, 2 * n + v))
            tris.add(intArrayOf(n + u, 2 * n + v, 2 * n + u))
        }
        return MeshOps.orientOutward(Mesh(verts, tris))
    }

    /** Offsets the outer boundary inward and every hole outward by [amount] (radially). */
    private fun insetShape(shape: PlanarShape, amount: Double): PlanarShape =
        PlanarShape(
            shape.outer.map { radialOffset(it, -amount) },
            shape.holes.map { h -> h.map { radialOffset(it, amount) } }
        )

    private fun radialOffset(v: Vec2, delta: Double): Vec2 {
        val r = hypot(v.x, v.y)
        if (r < 1e-9) return Vec2(0.0, 0.0)
        val r2 = (r + delta).coerceAtLeast(0.2)
        return v * (r2 / r)
    }
}
