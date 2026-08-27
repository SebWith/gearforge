package com.gearforge.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Ear-clipping triangulation with hole support (bridge method).
 * The outer boundary is oriented counter-clockwise, holes clockwise.
 */
object Triangulate {

    /** Returns (vertices, triangles) where triangles reference vertices by index. */
    fun triangulate(shape: PlanarShape): Pair<List<Vec2>, List<IntArray>> {
        val outer = normalize(shape.outer, ccw = true)
        val holes = shape.holes.map { normalize(it, ccw = false) }
        if (holes.isEmpty()) {
            val tris = earClip(outer)
            return Pair(outer, tris)
        }
        val merged = mergeHoles(outer, holes)
        val tris = earClip(merged).filter { area(merged, it) > 1e-12 }
        return Pair(merged, tris)
    }

    private fun normalize(poly: List<Vec2>, ccw: Boolean): List<Vec2> {
        if (poly.size < 3) return poly
        val area = signedArea(poly)
        val needsReverse = if (ccw) area < 0.0 else area > 0.0
        return if (needsReverse) poly.reversed() else poly
    }

    fun signedArea(poly: List<Vec2>): Double {
        var s = 0.0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            s += a.x * b.y - b.x * a.y
        }
        return s / 2.0
    }

    fun area(poly: List<Vec2>, tri: IntArray): Double {
        val a = poly[tri[0]]
        val b = poly[tri[1]]
        val c = poly[tri[2]]
        return abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2.0
    }

    /** Merge holes into the outer polygon using bridge edges from each hole's rightmost vertex. */
    private fun mergeHoles(outer: List<Vec2>, holes: List<List<Vec2>>): List<Vec2> {
        var poly = outer
        for (hole in holes) {
            if (hole.size < 3) continue
            // rightmost vertex of hole
            var hi = 0
            for (i in hole.indices) if (hole[i].x > hole[hi].x) hi = i
            val m = hole[hi]
            // find outer edge intersected by the ray from m toward +x, closest to m
            var bestX = Double.MAX_VALUE
            var bestEnd = -1
            for (i in poly.indices) {
                val a = poly[i]
                val b = poly[(i + 1) % poly.size]
                val ix = rayHitX(m, a, b)
                if (ix != null && ix >= m.x - 1e-12 && ix < bestX) {
                    // choose the endpoint of the edge with the larger x
                    bestX = ix
                    bestEnd = if (a.x >= b.x) i else (i + 1) % poly.size
                }
            }
            if (bestEnd < 0) {
                // fallback: nearest outer vertex to the right
                var bd = Double.MAX_VALUE
                for (i in poly.indices) {
                    val d = poly[i].dist(m)
                    if (poly[i].x >= m.x && d < bd) {
                        bd = d
                        bestEnd = i
                    }
                }
                if (bestEnd < 0) bestEnd = 0
            }
            poly = splice(poly, hole, hi, bestEnd)
        }
        return poly
    }

    /** Horizontal ray (from p toward +x) intersection x with segment a-b, or null. */
    private fun rayHitX(p: Vec2, a: Vec2, b: Vec2): Double? {
        if ((a.y > p.y) == (b.y > p.y)) return null
        val dy = b.y - a.y
        if (abs(dy) < 1e-15) return null
        val t = (p.y - a.y) / dy
        val x = a.x + t * (b.x - a.x)
        return if (x >= p.x - 1e-12) x else null
    }

    /** Insert hole starting at index [hi] bridged into outer at index [end]. */
    private fun splice(outer: List<Vec2>, hole: List<Vec2>, hi: Int, end: Int): List<Vec2> {
        val n = hole.size
        val out = ArrayList<Vec2>(outer.size + n + 2)
        for (i in 0..end) out.add(outer[i])
        for (i in 0 until n) out.add(hole[(hi + i) % n])
        out.add(hole[hi])
        out.add(outer[end])
        for (i in end + 1 until outer.size) out.add(outer[i])
        return out
    }

    private fun earClip(poly: List<Vec2>): List<IntArray> {
        val n = poly.size
        if (n < 3) return emptyList()
        val indices = (0 until n).toMutableList()
        val tris = ArrayList<IntArray>()
        var guard = 0
        val maxGuard = n * n * 4 + 100
        while (indices.size > 3 && guard < maxGuard) {
            guard++
            var clipped = false
            for (ii in indices.indices) {
                val i0 = indices[ii]
                val i1 = indices[(ii + 1) % indices.size]
                val i2 = indices[(ii + 2) % indices.size]
                val a = poly[i0]
                val b = poly[i1]
                val c = poly[i2]
                if (isEar(poly, indices, ii, a, b, c)) {
                    tris.add(intArrayOf(i0, i1, i2))
                    indices.removeAt((ii + 1) % indices.size)
                    clipped = true
                    break
                }
            }
            if (!clipped) break
        }
        if (indices.size == 3) {
            tris.add(intArrayOf(indices[0], indices[1], indices[2]))
        }
        return tris
    }

    private fun isEar(poly: List<Vec2>, indices: List<Int>, ii: Int, a: Vec2, b: Vec2, c: Vec2): Boolean {
        if (cross(a, b, c) <= 1e-12) return false
        val skip0 = indices[ii]
        val skip1 = indices[(ii + 1) % indices.size]
        val skip2 = indices[(ii + 2) % indices.size]
        // any other vertex inside triangle abc? (skip coincident points, e.g. bridge duplicates)
        for (j in indices) {
            if (j == skip0 || j == skip1 || j == skip2) continue
            val p = poly[j]
            if (p.dist(a) < 1e-9 || p.dist(b) < 1e-9 || p.dist(c) < 1e-9) continue
            if (pointInTriangle(p, a, b, c)) return false
        }
        return true
    }

    private fun cross(a: Vec2, b: Vec2, c: Vec2): Double =
        (b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)

    private fun pointInTriangle(p: Vec2, a: Vec2, b: Vec2, c: Vec2): Boolean {
        val d1 = cross(a, b, p)
        val d2 = cross(b, c, p)
        val d3 = cross(c, a, p)
        val hasNeg = d1 < -1e-12 || d2 < -1e-12 || d3 < -1e-12
        val hasPos = d1 > 1e-12 || d2 > 1e-12 || d3 > 1e-12
        return !(hasNeg && hasPos)
    }
}
