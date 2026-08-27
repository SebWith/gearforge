package com.gearforge.core

import kotlin.math.cos
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
    ): Mesh {
        require(thickness > 0.0) { "thickness must be > 0" }
        require(slices >= 2) { "slices must be >= 2" }
        val (poly, tris2d) = Triangulate.triangulate(shape)
        val boundary = MeshBuilder.boundaryEdges(tris2d)
        val vCount = poly.size

        val vertices = ArrayList<Vec3>(vCount * (slices + 1))
        for (k in 0..slices) {
            val t = k.toDouble() / slices
            val ang = twistRad * t
            val sc = scaleStart + (scaleEnd - scaleStart) * t
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
}
