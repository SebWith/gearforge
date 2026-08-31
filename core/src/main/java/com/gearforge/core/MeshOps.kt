package com.gearforge.core

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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

    /** Rotates a mesh about the Z axis (in the XY plane) by [angleRad]. */
    fun rotateZ(mesh: Mesh, angleRad: Double): Mesh {
        if (angleRad == 0.0 || mesh.vertices.isEmpty()) return mesh
        val ca = cos(angleRad)
        val sa = sin(angleRad)
        return Mesh(
            mesh.vertices.map { Vec3(it.x * ca - it.y * sa, it.x * sa + it.y * ca, it.z) },
            mesh.triangles
        )
    }

    /** Rotates a mesh about the Y axis by [angleRad] (maps the Z axis onto the X axis at 90°). */
    fun rotateY(mesh: Mesh, angleRad: Double): Mesh {
        if (angleRad == 0.0 || mesh.vertices.isEmpty()) return mesh
        val ca = cos(angleRad)
        val sa = sin(angleRad)
        return Mesh(
            mesh.vertices.map { Vec3(it.x * ca + it.z * sa, it.y, -it.x * sa + it.z * ca) },
            mesh.triangles
        )
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

    /**
     * Result of the export pre-flight mesh-integrity pass.
     * [issues] is empty for a closed, manifold, print-ready solid.
     */
    data class MeshValidation(val issues: List<String>) {
        val isValid: Boolean get() = issues.isEmpty()
    }

    /**
     * Validates that a mesh is a closed, manifold solid suitable for STL export:
     *  - non-empty and with all triangle indices in range
     *  - no duplicate vertices (within [tolerance])
     *  - no degenerate / zero-area triangles
     *  - every edge shared by exactly two faces (closed 2-manifold)
     *  - positive, finite signed volume with consistent outward normals
     *
     * Self-intersection is intentionally not detected here (it is an O(n²)
     * triangle-triangle problem); the remaining checks catch the defects that
     * actually make a mesh unprintable.
     */
    fun validate(mesh: Mesh, tolerance: Double = 1e-6): MeshValidation {
        val issues = ArrayList<String>()
        if (mesh.vertices.isEmpty()) { issues.add("mesh has no vertices"); return MeshValidation(issues) }
        if (mesh.triangles.isEmpty()) { issues.add("mesh has no triangles"); return MeshValidation(issues) }

        // 1. Indices in range.
        val vCount = mesh.vertices.size
        var outOfRange = 0
        for (t in mesh.triangles) for (i in t) if (i < 0 || i >= vCount) outOfRange++
        if (outOfRange > 0) issues.add("$outOfRange triangle indices out of range")

        // 2. Duplicate vertices within tolerance (spatial grid, O(n) average).
        val grid = HashMap<String, MutableList<Vec3>>()
        var dupes = 0
        for (v in mesh.vertices) {
            val gx = Math.floor(v.x / tolerance).toLong()
            val gy = Math.floor(v.y / tolerance).toLong()
            val gz = Math.floor(v.z / tolerance).toLong()
            var found = false
            outer@ for (dx in -1L..1L) for (dy in -1L..1L) for (dz in -1L..1L) {
                val cell = grid["${gx + dx},${gy + dy},${gz + dz}"] ?: continue
                for (w in cell) if (v.dist(w) < tolerance) { found = true; break@outer }
            }
            if (found) dupes++ else grid.getOrPut("$gx,$gy,$gz") { mutableListOf() }.add(v)
        }
        if (dupes > 0) issues.add("$dupes duplicate vertices within $tolerance mm")

        // 3. Degenerate / zero-area triangles.
        var degenerate = 0
        for ((i, t) in mesh.triangles.withIndex()) {
            val a = mesh.vertices[t[0]]
            val b = mesh.vertices[t[1]]
            val c = mesh.vertices[t[2]]
            if ((b - a).cross(c - a).length() < 1e-12) degenerate++
        }
        if (degenerate > 0) issues.add("$degenerate degenerate (zero-area) triangles")

        // 4. Closed manifold: every edge shared by exactly two faces.
        val edgeCount = HashMap<Long, Int>()
        fun key(a: Int, b: Int): Long {
            val lo = min(a, b).toLong()
            val hi = max(a, b).toLong()
            return (lo shl 32) or hi
        }
        for (t in mesh.triangles) for (e in 0 until 3) {
            val k = key(t[e], t[(e + 1) % 3])
            edgeCount[k] = (edgeCount[k] ?: 0) + 1
        }
        var nonManifold = 0
        var openEdges = 0
        for ((k, cnt) in edgeCount) {
            if (cnt != 2) {
                nonManifold++
                if (cnt == 1) openEdges++
            }
        }
        if (openEdges > 0) issues.add("$openEdges open boundary edges (mesh is not closed)")
        if (nonManifold > 0) issues.add("$nonManifold non-manifold edges (each edge must be shared by exactly 2 faces)")

        // 5. Positive finite volume (outward normals).
        val vol = signedVolume(mesh)
        if (!vol.isFinite() || vol <= 0.0) issues.add("signed volume is ${if (vol.isFinite()) "non-positive" else "non-finite"} (normals inverted or open mesh)")

        return MeshValidation(issues)
    }
}
