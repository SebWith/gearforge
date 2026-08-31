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
        // The hole bridge introduces exact duplicate vertices (the slit between outer
        // and hole is walked twice). Remove them so the emitted mesh carries no
        // duplicated vertex positions — a hard requirement for STL/3D-printing.
        return dedupeVertices(merged, tris)
    }

    /**
     * Collapses exactly-coincident vertices (from the hole-bridge slit) and remaps
     * triangle indices accordingly. Near-duplicates are intentionally left alone:
     * only bit-identical positions are merged.
     */
    private fun dedupeVertices(poly: List<Vec2>, tris: List<IntArray>): Pair<List<Vec2>, List<IntArray>> {
        val map = HashMap<Vec2, Int>(poly.size)
        val newPoly = ArrayList<Vec2>(poly.size)
        val remap = IntArray(poly.size)
        for (i in poly.indices) {
            val v = poly[i]
            val existing = map[v]
            if (existing != null) {
                remap[i] = existing
            } else {
                val ni = newPoly.size
                map[v] = ni
                newPoly.add(v)
                remap[i] = ni
            }
        }
        val newTris = ArrayList<IntArray>(tris.size)
        for (t in tris) {
            val a = remap[t[0]]; val b = remap[t[1]]; val c = remap[t[2]]
            if (a != b && b != c && a != c) newTris.add(intArrayOf(a, b, c))
        }
        return Pair(newPoly, newTris)
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

    /**
     * Merge holes into the outer polygon using the classic Eberly bridge method
     * (ear clipping with holes). Each hole is bridged from its rightmost vertex
     * to the closest visible vertex to its right — the larger-x endpoint of the
     * edge hit by a +x ray, on either the outer polygon or another hole. Holes
     * are processed in decreasing rightmost-x order, so a bridge always lands on
     * a hole/edge already to its right and never crosses a hole boundary.
     *
     * The previous incremental implementation bridged each hole against the
     * already-spliced polygon, so a later hole's ray could land on the slit of an
     * earlier hole and produce overlapping slits → non-manifold edges (audit L1).
     */
    private fun mergeHoles(outer: List<Vec2>, holes: List<List<Vec2>>): List<Vec2> {
        class HoleInfo(val verts: List<Vec2>, val hi: Int, val maxX: Double)
        val H = ArrayList<HoleInfo>()
        for (hole in holes) {
            if (hole.size < 3) continue
            var hi = 0
            for (i in hole.indices) if (hole[i].x > hole[hi].x) hi = i
            H.add(HoleInfo(hole, hi, hole[hi].x))
        }
        H.sortWith(compareByDescending<HoleInfo> { it.maxX }.thenBy { it.verts[it.hi].y })
        if (H.isEmpty()) return outer

        val k = H.size
        val isOuter = BooleanArray(k)
        val outerTarget = IntArray(k)  // valid when isOuter: index into outer
        val holeVertex = IntArray(k)   // valid when !isOuter: index into parent hole's vertex list
        val parentHole = IntArray(k)   // valid when !isOuter

        for (pi in 0 until k) {
            val m = H[pi].verts[H[pi].hi]
            var bestX = Double.MAX_VALUE
            var found = false
            var bestIsOuter = false
            var bestVertex = 0
            var bestParent = 0

            // Candidate bridges onto the outer boundary (vertex + edge hits).
            val outerHit = rayTarget(m, outer)
            if (outerHit != null && outerHit.first < bestX) {
                bestX = outerHit.first
                bestIsOuter = true
                bestVertex = outerHit.second
                found = true
            }
            // Candidate bridges onto holes already processed (those strictly to
            // the right — they sort before pi in the descending max-x order).
            for (pj in 0 until pi) {
                val hit = rayTarget(m, H[pj].verts)
                if (hit != null && hit.first < bestX) {
                    bestX = hit.first
                    bestIsOuter = false
                    bestParent = pj
                    bestVertex = hit.second
                    found = true
                }
            }
            if (!found) {
                // Fallback: nearest outer vertex to the right (degenerate inputs).
                bestIsOuter = true
                var bd = Double.MAX_VALUE
                for (i in outer.indices) {
                    val d = outer[i].dist(m)
                    if (outer[i].x >= m.x && d < bd) { bd = d; bestVertex = i }
                }
            }
            isOuter[pi] = bestIsOuter
            outerTarget[pi] = bestVertex
            holeVertex[pi] = bestVertex
            parentHole[pi] = bestParent
        }

        // Build the bridge tree: holes attached to each outer vertex and to each
        // (parent hole, vertex) pair.
        val outerChildrenAt = HashMap<Int, ArrayList<Int>>()
        val holeChildrenAt = Array(k) { HashMap<Int, ArrayList<Int>>() }
        for (pi in 0 until k) {
            if (isOuter[pi]) {
                outerChildrenAt.getOrPut(outerTarget[pi]) { ArrayList() }.add(pi)
            } else {
                holeChildrenAt[parentHole[pi]].getOrPut(holeVertex[pi]) { ArrayList() }.add(pi)
            }
        }

        // Recursively emit a hole walk starting/ending at its rightmost vertex,
        // splicing in child holes at their bridge vertices along the walk.
        val out = ArrayList<Vec2>(outer.size + H.sumOf { it.verts.size } + 2 * k)
        fun emitHole(pi: Int) {
            val h = H[pi].verts
            val hi = H[pi].hi
            val n = h.size
            for (t in 0 until n) {
                val idx = (hi + t) % n
                out.add(h[idx])
                holeChildrenAt[pi][idx]?.forEach { child ->
                    emitHole(child)
                    out.add(h[idx]) // bridge-up back to this vertex
                }
            }
            out.add(h[hi]) // bridge-up to our parent
        }

        // Walk the outer polygon once, splicing every hole that bridges to each vertex.
        for (i in outer.indices) {
            out.add(outer[i])
            outerChildrenAt[i]?.forEach { child ->
                emitHole(child)
                out.add(outer[i]) // bridge-up back to the outer vertex
            }
        }
        return out
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

    /**
     * Closest point of [poly] hit by the +x ray from [p], as (x, vertexIndex).
     * Prefers a vertex lying exactly on the ray (the ray-tangent / ray-through-
     * vertex case, which [rayHitX] would miss and which would otherwise let a
     * bridge slice through that vertex and make the combined polygon self-touch),
     * then falls back to the larger-x endpoint of the first straddling edge.
     */
    private fun rayTarget(p: Vec2, poly: List<Vec2>): Pair<Double, Int>? {
        var bestX = Double.MAX_VALUE
        var best = -1
        // Vertex exactly on the ray.
        for (i in poly.indices) {
            val v = poly[i]
            if (abs(v.y - p.y) < 1e-9 && v.x >= p.x - 1e-12 && v.x < bestX) {
                bestX = v.x
                best = i
            }
        }
        // Straddling edge (interior hit); strict < keeps a coincident vertex hit.
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val ix = rayHitX(p, a, b)
            if (ix != null && ix >= p.x - 1e-12 && ix < bestX) {
                bestX = ix
                best = if (a.x >= b.x) i else (i + 1) % poly.size
            }
        }
        return if (best >= 0) Pair(bestX, best) else null
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
