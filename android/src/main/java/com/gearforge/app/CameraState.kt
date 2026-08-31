package com.gearforge.app

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Immutable snapshot of the 3D preview camera, published by [GearGLView] on every
 * orbit / zoom / pan / frame change and consumed by the [ViewportGizmo] overlay.
 *
 * Coordinate-system convention (kept identical to [GearGLView], which this class
 * mirrors):
 *  - Right-handed. +X points right, +Y points up on screen, +Z points toward the
 *    viewer. The gear body is extruded along Z (its axis of rotation), so +Z is the
 *    "top" axis of the widget, +Y is "front" and +X is "right".
 *  - The orbit is implemented as a MODEL rotation `R = rotationY(rotY) * rotationX(rotX)`
 *    in front of a fixed camera (eye = (0, 0, eyeDist), target = (0, 0, centerZ),
 *    up = (0, 1, 0)). The equivalent CAMERA orientation is therefore `R⁻¹`, which is
 *    stored as the unit quaternion [rotationQuaternion] (x, y, z, w) and used by the
 *    gizmo to project the world axes into view space.
 *  - [viewMatrix] and [projectionMatrix] are column-major 4x4 matrices, byte-for-byte
 *    identical to the GL uniforms used by [GearGLView] (vertical fov 35°, aspect from
 *    the viewport). They are derived values kept on the snapshot for downstream use;
 *    the gizmo itself only needs [rotationQuaternion].
 *
 * Equality is defined on the scalar camera parameters only (the matrices and quaternion
 * are pure functions of those scalars), so [androidx.compose.runtime.State] equality and
 * `StateFlow` de-duplication behave correctly across publishes.
 */
data class CameraState(
    val rotXDeg: Float = 35f,
    val rotYDeg: Float = 45f,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val eye: FloatArray = floatArrayOf(0f, 0f, 30f),
    val target: FloatArray = floatArrayOf(0f, 0f, 0f),
    val rotationQuaternion: FloatArray = floatArrayOf(0f, 0f, 0f, 1f),
    val viewMatrix: FloatArray = FloatArray(16),
    val projectionMatrix: FloatArray = FloatArray(16),
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0,
    val frameRadius: Float = 20f
) {
    /** The gizmo is only meaningful once the GL surface has reported a non-zero size. */
    val isAvailable: Boolean get() = viewportWidth > 0 && viewportHeight > 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CameraState) return false
        return rotXDeg == other.rotXDeg &&
            rotYDeg == other.rotYDeg &&
            zoom == other.zoom &&
            panX == other.panX &&
            panY == other.panY &&
            viewportWidth == other.viewportWidth &&
            viewportHeight == other.viewportHeight &&
            frameRadius == other.frameRadius
    }

    override fun hashCode(): Int {
        var h = rotXDeg.hashCode()
        h = 31 * h + rotYDeg.hashCode()
        h = 31 * h + zoom.hashCode()
        h = 31 * h + panX.hashCode()
        h = 31 * h + panY.hashCode()
        h = 31 * h + viewportWidth
        h = 31 * h + viewportHeight
        h = 31 * h + frameRadius.hashCode()
        return h
    }
}

/** Minimal quaternion helpers shared by [GearGLView] and [GizmoMath] (kept allocation-light). */
internal object Quat {
    fun identity(): FloatArray = floatArrayOf(0f, 0f, 0f, 1f)

    /** Unit quaternion for a rotation of [angleDeg] degrees about the axis (ax, ay, az). */
    fun fromAxisAngleDeg(ax: Float, ay: Float, az: Float, angleDeg: Float): FloatArray {
        val half = Math.toRadians((angleDeg / 2.0).toDouble())
        val s = sin(half).toFloat()
        val len = sqrt(ax * ax + ay * ay + az * az)
        return floatArrayOf(ax / len * s, ay / len * s, az / len * s, cos(half).toFloat())
    }

    /** Hamilton product `a ⊗ b` (apply `b` first, then `a`). */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val ax = a[0]; val ay = a[1]; val az = a[2]; val aw = a[3]
        val bx = b[0]; val by = b[1]; val bz = b[2]; val bw = b[3]
        return floatArrayOf(
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz
        )
    }

    fun normalize(q: FloatArray): FloatArray {
        val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        return if (n < 1e-6f) identity() else floatArrayOf(q[0] / n, q[1] / n, q[2] / n, q[3] / n)
    }

    /** Rotates vector [v] by unit quaternion [q] (x, y, z, w order). */
    fun rotateVector(q: FloatArray, v: FloatArray): FloatArray {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        val vx = v[0]; val vy = v[1]; val vz = v[2]
        // t = cross(q.xyz, v)
        val tx = qy * vz - qz * vy
        val ty = qz * vx - qx * vz
        val tz = qx * vy - qy * vx
        // s = t + qw * v
        val sx = tx + qw * vx
        val sy = ty + qw * vy
        val sz = tz + qw * vz
        // r = cross(q.xyz, s)
        val rx = qy * sz - qz * sy
        val ry = qz * sx - qx * sz
        val rz = qx * sy - qy * sx
        // v' = v + 2 * r
        return floatArrayOf(vx + 2f * rx, vy + 2f * ry, vz + 2f * rz)
    }
}

/**
 * Unit quaternion for the equivalent camera orientation `R⁻¹` derived from the orbit
 * angles: `Q = qx(−rotX) ⊗ qy(−rotY)` where `qx`/`qy` are rotations about X/Y.
 */
internal fun gizmoQuaternion(rotXDeg: Float, rotYDeg: Float): FloatArray =
    Quat.normalize(
        Quat.multiply(
            Quat.fromAxisAngleDeg(1f, 0f, 0f, -rotXDeg),
            Quat.fromAxisAngleDeg(0f, 1f, 0f, -rotYDeg)
        )
    )
