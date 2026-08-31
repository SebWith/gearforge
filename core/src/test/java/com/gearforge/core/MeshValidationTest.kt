package com.gearforge.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the export pre-flight mesh-integrity pass: a real generated gear must
 * validate as a closed manifold solid, while deliberately broken meshes must be
 * flagged with the corresponding defect.
 */
class MeshValidationTest {

    private fun spurMesh(): Mesh = GearBuilder.mesh(GearSpec.defaults(GearType.SPUR))

    @Test
    fun validGearValidatesClean() {
        val r = MeshOps.validate(spurMesh())
        assertTrue("generated spur should be watertight, got: ${r.issues}", r.isValid)
    }

    @Test
    fun validGearHasPositiveVolume() {
        val v = MeshOps.signedVolume(spurMesh())
        assertTrue("volume must be positive, was $v", v > 0.0 && v.isFinite())
    }

    @Test
    fun internalRingValidatesClean() {
        val mesh = GearBuilder.ringMesh(GearSpec.defaults(GearType.INTERNAL_RING))
        val r = MeshOps.validate(mesh)
        assertTrue("internal ring should be watertight, got: ${r.issues}", r.isValid)
    }

    @Test
    fun openMeshIsFlagged() {
        val mesh = spurMesh()
        // Remove the last triangle → one open boundary edge.
        val broken = Mesh(mesh.vertices, mesh.triangles.dropLast(1))
        val r = MeshOps.validate(broken)
        assertFalse("open mesh must not validate", r.isValid)
        assertTrue("should report non-manifold/open edge: ${r.issues}", r.issues.any { it.contains("non-manifold") || it.contains("not closed") })
    }

    @Test
    fun degenerateTriangleIsFlagged() {
        val vertices = listOf(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
        val triangles = listOf(intArrayOf(0, 1, 2), intArrayOf(0, 1, 3))
        val r = MeshOps.validate(Mesh(vertices, triangles))
        assertTrue("should report degenerate triangle: ${r.issues}", r.issues.any { it.contains("degenerate") })
    }

    @Test
    fun duplicateVerticesAreFlagged() {
        val v = Vec3(0.0, 0.0, 0.0)
        val vertices = listOf(v, v, Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0))
        val triangles = listOf(intArrayOf(0, 2, 3))
        val r = MeshOps.validate(Mesh(vertices, triangles))
        assertTrue("should report duplicate vertices: ${r.issues}", r.issues.any { it.contains("duplicate") })
    }

    @Test
    fun invertedNormalsAreFlagged() {
        val mesh = spurMesh()
        val inverted = Mesh(mesh.vertices, mesh.triangles.map { intArrayOf(it[0], it[2], it[1]) })
        val r = MeshOps.validate(inverted)
        assertFalse("inverted mesh must not validate", r.isValid)
        assertTrue("should report non-positive volume: ${r.issues}", r.issues.any { it.contains("volume") })
    }
}
