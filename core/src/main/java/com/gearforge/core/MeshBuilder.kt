package com.gearforge.core

import kotlin.math.max
import kotlin.math.min

/**
 * Builds a watertight extruded mesh from a planar shape.
 *
 * The triangulation guarantees that every boundary edge of the planar region is
 * shared by exactly one triangle; the side walls are then built along those
 * boundary edges, producing a closed 2-manifold.
 */
object MeshBuilder {

    fun extrude(shape: PlanarShape, thickness: Double): Mesh {
        val (poly, tris2d) = Triangulate.triangulate(shape)
        return extrude(poly, tris2d, thickness)
    }

    /** Extrude a pre-triangulated planar region into a watertight mesh. */
    fun extrude(poly: List<Vec2>, tris2d: List<IntArray>, thickness: Double): Mesh {
        require(thickness > 0.0) { "thickness must be > 0" }
        require(poly.size >= 3) { "degenerate shape" }

        val vertices = ArrayList<Vec3>(poly.size * 2)
        for (p in poly) {
            vertices.add(Vec3(p.x, p.y, 0.0))
            vertices.add(Vec3(p.x, p.y, thickness))
        }

        val triangles = ArrayList<IntArray>()
        // caps
        for (t in tris2d) {
            val a = t[0]
            val b = t[1]
            val c = t[2]
            triangles.add(intArrayOf(2 * a, 2 * c, 2 * b))        // front (normal -Z)
            triangles.add(intArrayOf(2 * a + 1, 2 * b + 1, 2 * c + 1)) // back (normal +Z)
        }

        // side walls from boundary edges (edges used exactly once by triangles)
        val boundary = boundaryEdges(tris2d)
        for ((u, v) in boundary) {
            triangles.add(intArrayOf(2 * u, 2 * v, 2 * v + 1))
            triangles.add(intArrayOf(2 * u, 2 * v + 1, 2 * u + 1))
        }

        return MeshOps.orientOutward(Mesh(vertices, triangles))
    }

    /** Returns edges that appear exactly once in the triangulation (boundary). */
    fun boundaryEdges(triangles: List<IntArray>): List<Pair<Int, Int>> {
        val count = HashMap<Long, Int>()
        fun key(a: Int, b: Int): Long {
            val lo = min(a, b).toLong()
            val hi = max(a, b).toLong()
            return (lo shl 32) or hi
        }
        for (t in triangles) {
            for (e in 0 until 3) {
                val a = t[e]
                val b = t[(e + 1) % 3]
                val k = key(a, b)
                count[k] = (count[k] ?: 0) + 1
            }
        }
        val result = ArrayList<Pair<Int, Int>>()
        // Preserve orientation by re-deriving each boundary edge from the triangle it belongs to.
        val seen = HashSet<Long>()
        for (t in triangles) {
            for (e in 0 until 3) {
                val a = t[e]
                val b = t[(e + 1) % 3]
                val k = key(a, b)
                if (count[k] == 1 && seen.add(k)) {
                    result.add(a to b)
                }
            }
        }
        return result
    }
}
