package com.gearforge.core

import kotlin.math.max
import kotlin.math.min

/** Mesh utilities: face normals, centroid, signed volume and orientation normalization. */
object MeshOps {

    fun faceNormal(a: Vec3, b: Vec3, c: Vec3): Vec3 = (b - a).cross(c - a).normalized()

    /** Axis-aligned bounding-box extents (width, height, depth) of a mesh. */
    fun bounds(mesh: Mesh): Vec3 {        if (mesh.vertices.isEmpty()) return Vec3(0.0, 0.0, 0.0)
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        for (v in mesh.vertices) {
            if (v.x < minX) minX = v.x
            if (v.y < minY) minY = v.y
            if (v.z < minZ) minZ = v.z
            if (v.x > maxX) maxX = v.x
            if (v.y > maxY) maxY = v.y
            if (v.z > maxZ) maxZ = v.z
        }
        return Vec3(maxX - minX, maxY - minY, maxZ - minZ)
    }

    fun centroid(mesh: Mesh): Vec3 {
        if (mesh.vertices.isEmpty()) return Vec3(0.0, 0.0, 0.0)
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        for (v in mesh.vertices) {
            sx += v.x
            sy += v.y
            sz += v.z
        }
        val n = mesh.vertices.size
        return Vec3(sx / n, sy / n, sz / n)
    }

    /**
     * Signed volume via the divergence theorem. Positive when all triangles are
     * oriented with outward normals (the construction guarantees this by using
     * CCW outer boundaries and CW holes).
     */
    fun signedVolume(mesh: Mesh): Double {
        var v = 0.0
        for (t in mesh.triangles) {
            val a = mesh.vertices[t[0]]
            val b = mesh.vertices[t[1]]
            val c = mesh.vertices[t[2]]
            v += (a.x * (b.y * c.z - b.z * c.y)
                - a.y * (b.x * c.z - b.z * c.x)
                + a.z * (b.x * c.y - b.y * c.x)) / 6.0
        }
        return v
    }

    /** Concatenates multiple meshes (with no placement offsets) into a single mesh. */
    fun merge(meshes: List<Mesh>): Mesh {
        val nonEmpty = meshes.filter { it.vertices.isNotEmpty() }
        if (nonEmpty.isEmpty()) return Mesh(emptyList(), emptyList())
        if (nonEmpty.size == 1) return nonEmpty[0]
        val verts = ArrayList<Vec3>()
        val tris = ArrayList<IntArray>()
        for (m in nonEmpty) {
            val base = verts.size
            verts.addAll(m.vertices)
            for (t in m.triangles) tris.add(intArrayOf(t[0] + base, t[1] + base, t[2] + base))
        }
        return Mesh(verts, tris)
    }

    /**
     * Re-orients a manifold triangle mesh so every face normal points outward.
     * Uses a BFS flood-fill across shared edges and finishes with a signed-volume
     * check. If the mesh is not manifold, it is returned unchanged.
     */
    fun orientOutward(mesh: Mesh): Mesh {
        val n = mesh.triangles.size
        if (n == 0) return mesh

        fun undirectedKey(a: Int, b: Int): Long {
            val lo = min(a, b).toLong()
            val hi = max(a, b).toLong()
            return (lo shl 32) or hi
        }

        val tris = mesh.triangles.map { it.copyOf() }
        val edgeMap = HashMap<Long, MutableList<Pair<Int, Int>>>()
        for (t in tris.indices) {
            for (e in 0 until 3) {
                val a = tris[t][e]
                val b = tris[t][(e + 1) % 3]
                edgeMap.getOrPut(undirectedKey(a, b)) { mutableListOf() }.add(t to e)
            }
        }

        // Manifold check: every edge shared by exactly two triangles.
        for (list in edgeMap.values) {
            if (list.size != 2) return mesh
        }

        val sign = IntArray(n) // 0 unvisited, 1 keep, -1 flip
        val queue = ArrayDeque<Int>()
        sign[0] = 1
        queue.add(0)
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            for (e in 0 until 3) {
                val a = tris[t][e]
                val b = tris[t][(e + 1) % 3]
                for ((tn, en) in edgeMap[undirectedKey(a, b)]!!) {
                    if (tn == t) continue
                    if (sign[tn] != 0) continue
                    val na = tris[tn][en]
                    val nb = tris[tn][(en + 1) % 3]
                    val sameDir = na == a && nb == b
                    sign[tn] = if (sameDir) -sign[t] else sign[t]
                    queue.add(tn)
                }
            }
        }

        for (t in tris.indices) {
            if (sign[t] == -1) {
                val tmp = tris[t][1]
                tris[t][1] = tris[t][2]
                tris[t][2] = tmp
            }
        }
        val oriented = Mesh(mesh.vertices, tris)
        return if (signedVolume(oriented) < 0.0) {
            Mesh(mesh.vertices, oriented.triangles.map { intArrayOf(it[0], it[2], it[1]) })
        } else {
            oriented
        }
    }
}
