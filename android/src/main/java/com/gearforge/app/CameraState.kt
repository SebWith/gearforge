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
 *    up = (0, 1, 0)). That same `R` is what takes a world axis into view space, so it is
 *    stored as the unit quaternion [rotationQuaternion] (x, y, z, w) and used by the
 *    gizmo to project the world axes onto the screen.
 *  - [viewMatrix], [projectionMatrix], [modelMatrix] and [bedMatrix] are column-major
 *    4x4 matrices, byte-for-byte identical to the GL uniforms used by [GearGLView]
 *    (vertical fov 35°, aspect from the viewport). They are derived values kept on the
 *    snapshot for downstream use. The gizmo itself only needs [rotationQuaternion];
 *    the measurement HUD and the platen labels need [modelMatrix] as well, because the
 *    renderer draws `P · V · M` — an overlay that projects `P · V · world` without `M`
 *    sits still while the model turns.
 *
 * Equality is defined on the scalar camera parameters only (the matrices and quaternion
 * are pure functions of those scalars), so [androidx.compose.runtime.State] equality and
 * `StateFlow` de-duplication behave correctly across publishes. Every scalar that feeds a
 * matrix is therefore listed in [equals] — including the platen's, or a bed that changed
 * size under a running composition would never republish.
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
    /**
     * World → orbit-space transform `Ry(rotY) · Rx(rotX) · T(pan)`, exactly what the
     * renderer draws the bodies with. Picking and every overlay must apply it; see
     * [ViewportCamera.orbitModel].
     */
    val modelMatrix: FloatArray = IDENTITY,
    /**
     * World → platen transform. Translation only — the bed does not take the orbit
     * rotation, so it stays flat while the model turns above it.
     */
    val bedMatrix: FloatArray = IDENTITY,
    /** Platen side in millimetres, or 0 when the bed is hidden. */
    val bedSizeMm: Float = 0f,
    /** Platen grid pitch in millimetres, or 0 when the bed is hidden. */
    val bedGridMm: Float = 0f,
    /** Platen plane in world Z, so an overlay can place a label on the bed it draws. */
    val bedZ: Float = 0f,
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0,
    val frameRadius: Float = 20f
) {
    /** The gizmo is only meaningful once the GL surface has reported a non-zero size. */
    val isAvailable: Boolean get() = viewportWidth > 0 && viewportHeight > 0

    /** True when the renderer is drawing a platen this snapshot can label. */
    val bedVisible: Boolean get() = bedSizeMm > 0f

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
            frameRadius == other.frameRadius &&
            bedSizeMm == other.bedSizeMm &&
            bedGridMm == other.bedGridMm &&
            bedZ == other.bedZ
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
        h = 31 * h + bedSizeMm.hashCode()
        h = 31 * h + bedGridMm.hashCode()
        h = 31 * h + bedZ.hashCode()
        return h
    }

    private companion object {
        /**
         * A neutral matrix for the default snapshot. The HUD returns early until the GL
         * surface has a size, so this value is never projected; it exists so a default
         * `CameraState` multiplies the label positions by something harmless rather than
         * by a zero matrix, which would collapse every label onto one pixel.
         */
        val IDENTITY = ViewportCamera.identity()
    }
}

/** Minimal quaternion helpers shared by [GearGLView] and [GizmoMath] (kept allocation-light). */
internal object Quat {
    fun identity(): FloatArray = floatArrayOf(0f, 0f, 0f, 1f)

    /** Unit quaternion for a rotation of [angleDeg] degrees about the axis (ax, ay, az). */
    fun fromAxisAngleDeg(ax: Float, ay: Float, az: Float, angleDeg: Float): FloatArray {
        val half = Math.toRadians(angleDeg / 2.0)
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
 * Unit quaternion of the world → view rotation `R = Ry(rotY) · Rx(rotX)` — the orientation the
 * renderer gives the model in front of its fixed camera.
 *
 * **It is `R`, not `R⁻¹`.** The gizmo asks this quaternion "which way does world +X point on
 * screen?", and the answer is `R · x̂`. Rotating a world axis by the inverse puts it on the
 * opposite side of the screen, which mirrors the whole navigation widget: every puck moves to
 * where its *opposite* axis is, the puck you just tapped dims to the far side, and the widget turns
 * the wrong way when you orbit. The renderer, the pick ray and the snap angles all agree on `R`;
 * this quaternion was the one that disagreed.
 *
 * A rotation about X followed by a rotation about Y composes as `qY ⊗ qX` — [Quat.multiply]
 * applies its right-hand operand first.
 */
internal fun gizmoQuaternion(rotXDeg: Float, rotYDeg: Float): FloatArray =
    Quat.normalize(
        Quat.multiply(
            Quat.fromAxisAngleDeg(0f, 1f, 0f, rotYDeg),
            Quat.fromAxisAngleDeg(1f, 0f, 0f, rotXDeg)
        )
    )
