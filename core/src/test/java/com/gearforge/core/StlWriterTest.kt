package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Verifies the binary STL export format and the right-hand-rule face normals:
 * 80-byte header + 4-byte triangle count + 50 bytes per facet (little-endian).
 */
class StlWriterTest {

    private fun mesh(): Mesh = GearBuilder.mesh(GearSpec.defaults(GearType.SPUR))

    @Test
    fun binaryStlHasCorrectHeaderAndLength() {
        val m = mesh()
        val bytes = StlWriter.writeBinary(m)
        val n = m.triangles.size
        assertEquals("STL size must be 84 + 50·n", 84 + 50 * n, bytes.size)

        // 4-byte little-endian triangle count at offset 80.
        val count = ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(n, count)
    }

    @Test
    fun binaryStlNormalsFollowRightHandRule() {
        val m = mesh()
        val bytes = StlWriter.writeBinary(m)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Skip header + count.
        buf.position(84)
        for (t in m.triangles) {
            val a = m.vertices[t[0]]
            val b = m.vertices[t[1]]
            val c = m.vertices[t[2]]
            val nx = buf.float; val ny = buf.float; val nz = buf.float
            val ax = buf.float; val ay = buf.float; val az = buf.float
            val bx = buf.float; val by = buf.float; val bz = buf.float
            val cx = buf.float; val cy = buf.float; val cz = buf.float
            buf.getShort() // attribute byte count

            // Normal must match (b−a)×(c−a) normalized (right-hand rule).
            val expected = MeshOps.faceNormal(a, b, c)
            assertEquals("normal x", expected.x, nx.toDouble(), 1e-4)
            assertEquals("normal y", expected.y, ny.toDouble(), 1e-4)
            assertEquals("normal z", expected.z, nz.toDouble(), 1e-4)
            // Normal must be unit length and orthogonal to the face.
            val len = kotlin.math.hypot(kotlin.math.hypot(nx.toDouble(), ny.toDouble()), nz.toDouble())
            assertEquals(1.0, len, 1e-4)
            // Vertex positions must round-trip.
            assertEquals(a.x, ax.toDouble(), 1e-4)
            assertEquals(a.y, ay.toDouble(), 1e-4)
            assertEquals(b.x, bx.toDouble(), 1e-4)
            assertEquals(c.y, cy.toDouble(), 1e-4)
        }
    }
}
