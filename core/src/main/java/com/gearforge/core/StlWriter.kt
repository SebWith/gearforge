package com.gearforge.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Binary STL export. */
object StlWriter {

    fun writeBinary(mesh: Mesh): ByteArray {
        val n = mesh.triangles.size
        val buf = ByteBuffer.allocate(84 + n * 50).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(80)
        buf.putInt(n)
        for (t in mesh.triangles) {
            val a = mesh.vertices[t[0]]
            val b = mesh.vertices[t[1]]
            val c = mesh.vertices[t[2]]
            val norm = MeshOps.faceNormal(a, b, c)
            putF(buf, norm.x); putF(buf, norm.y); putF(buf, norm.z)
            putF(buf, a.x); putF(buf, a.y); putF(buf, a.z)
            putF(buf, b.x); putF(buf, b.y); putF(buf, b.z)
            putF(buf, c.x); putF(buf, c.y); putF(buf, c.z)
            buf.putShort(0)
        }
        return buf.array()
    }

    private fun putF(b: ByteBuffer, v: Double) {
        b.putFloat(v.toFloat())
    }
}
