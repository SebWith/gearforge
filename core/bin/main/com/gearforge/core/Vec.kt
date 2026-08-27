package com.gearforge.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Immutable 2D vector used for planar geometry. */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    fun dist(o: Vec2) = hypot(x - o.x, y - o.y)
    fun angle() = atan2(y, x)

    companion object {
        fun polar(r: Double, angle: Double) = Vec2(r * cos(angle), r * sin(angle))
    }
}

/** Immutable 3D vector used for mesh geometry. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)

    fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x
    )

    fun normalized(): Vec3 {
        val len = hypot(hypot(x, y), z)
        return if (len < 1e-12) Vec3(0.0, 0.0, 1.0) else Vec3(x / len, y / len, z / len)
    }
}
