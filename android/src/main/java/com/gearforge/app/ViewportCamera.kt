package com.gearforge.app

import com.gearforge.core.Vec3
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The one definition of the viewport camera: the projection, the view, the orbit model matrix and
 * the inverse pick ray.
 *
 * **Why this object exists.** The renderer, the measurement HUD, the platen overlay and the tooth
 * picker all have to agree on where the model is on screen. They used to compute that separately,
 * and the copies drifted: the renderer drew `P · V · R · T` (the orbit rotation and the pan live in
 * the model matrix), while the HUD projected `P · V` and therefore ignored both. The result was a
 * measurement overlay whose labels and leader lines stayed put while the model turned under them —
 * a bug that no screenshot of a single camera angle can show.
 *
 * Everything here is pure Kotlin with no Android or GL types, so the JVM tests can pin the parts
 * that are easy to get *almost* right: the multiplication order, the model/view/projection
 * composition, and the inverse of a rigid transform.
 */
internal object ViewportCamera {

    /** Vertical field of view in degrees. The projection and the pick ray both use it. */
    const val FOVY_DEG = 35f

    /** Framing radius used before the first [com.gearforge.core.Mesh] has been measured. */
    const val DEFAULT_FRAME_RADIUS = 20f

    /** Column-major identity matrix. */
    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    /** Column-major 4x4 multiply: `a · b`, the same layout `android.opengl.Matrix` and the GL uniforms use. */
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                r[i * 4 + j] =
                    a[j] * b[i * 4] + a[4 + j] * b[i * 4 + 1] + a[8 + j] * b[i * 4 + 2] + a[12 + j] * b[i * 4 + 3]
            }
        }
        return r
    }

    /** Column-major translation. */
    fun translation(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        x, y, z, 1f
    )

    fun rotationX(degrees: Float): FloatArray {
        val r = Math.toRadians(degrees.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return floatArrayOf(1f, 0f, 0f, 0f, 0f, c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f)
    }

    fun rotationY(degrees: Float): FloatArray {
        val r = Math.toRadians(degrees.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f)
    }

    fun rotationZ(degrees: Float): FloatArray {
        val r = Math.toRadians(degrees.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return floatArrayOf(c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    }

    /**
     * The orbit-and-pan transform the bodies are drawn with: `R = Ry(rotY) · Rx(rotX) · T(pan)`.
     *
     * The orbit is a **model** rotation in front of a fixed camera, not a camera orbit — the eye
     * stays on the +Z axis. That is why every consumer of "where is the model on screen" has to
     * apply this matrix, and why omitting it leaves the overlay sitting still.
     */
    fun orbitModel(rotXDeg: Float, rotYDeg: Float, panX: Float, panY: Float): FloatArray =
        mul(rotationY(rotYDeg), mul(rotationX(rotXDeg), translation(panX, panY, 0f)))

    /**
     * The platen's transform: translation only.
     *
     * A print bed is a table, so it deliberately does **not** take the orbit rotation — it must
     * stay flat while the model is turned above it. It does follow the pan, because panning moves
     * the whole scene across the table.
     */
    fun bedModel(panX: Float, panY: Float, z: Float): FloatArray = translation(panX, panY, z)

    /** Framing radius clamped to the value the first frame uses before a mesh has been measured. */
    fun radiusOr(frameRadius: Float): Float =
        if (frameRadius > 0f) frameRadius else DEFAULT_FRAME_RADIUS

    /** Half the smaller field-of-view angle for a viewport of the given aspect ratio. */
    private fun minHalfFov(aspect: Float): Double {
        val halfFovY = Math.toRadians((FOVY_DEG / 2f).toDouble())
        val halfFovX = atan(tan(halfFovY) * aspect)
        return minOf(halfFovY, halfFovX)
    }

    /**
     * Distance from the eye to the origin that fits [frameRadius] inside the narrower of the two
     * field-of-view angles, scaled by [zoom].
     */
    fun eyeDistance(frameRadius: Float, width: Int, height: Int, zoom: Float): Float {
        val aspect = if (height > 0) width.toFloat() / height else 1f
        return (radiusOr(frameRadius) / sin(minHalfFov(aspect))).toFloat() * zoom
    }

    /** Near plane for the given eye distance — the same expression [view] and [projection] assert. */
    fun nearPlane(eyeDistance: Float, frameRadius: Float): Float =
        maxOf(0.01f, eyeDistance - radiusOr(frameRadius) * 4f)

    fun farPlane(eyeDistance: Float, frameRadius: Float): Float =
        eyeDistance + radiusOr(frameRadius) * 8f

    fun perspective(fovyDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = (1f / tan(Math.toRadians(fovyDeg.toDouble()) / 2.0)).toFloat()
        return floatArrayOf(
            f / aspect, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, (far + near) / (near - far), -1f,
            0f, 0f, (2f * far * near) / (near - far), 0f
        )
    }

    fun lookAt(eye: FloatArray, center: FloatArray, up: FloatArray): FloatArray {
        val z = floatArrayOf(eye[0] - center[0], eye[1] - center[1], eye[2] - center[2])
        val zl = kotlin.math.sqrt(z[0] * z[0] + z[1] * z[1] + z[2] * z[2])
        z[0] /= zl; z[1] /= zl; z[2] /= zl
        val x = floatArrayOf(up[1] * z[2] - up[2] * z[1], up[2] * z[0] - up[0] * z[2], up[0] * z[1] - up[1] * z[0])
        val xl = kotlin.math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2])
        x[0] /= xl; x[1] /= xl; x[2] /= xl
        val y = floatArrayOf(z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0])
        return floatArrayOf(
            x[0], y[0], z[0], 0f,
            x[1], y[1], z[1], 0f,
            x[2], y[2], z[2], 0f,
            -(x[0] * eye[0] + x[1] * eye[1] + x[2] * eye[2]),
            -(y[0] * eye[0] + y[1] * eye[1] + y[2] * eye[2]),
            -(z[0] * eye[0] + z[1] * eye[1] + z[2] * eye[2]), 1f
        )
    }

    /** The fixed camera's view matrix: the eye sits on +Z and looks at `(0, 0, centerZ)`. */
    fun view(centerZ: Float, eyeDistance: Float): FloatArray = lookAt(
        floatArrayOf(0f, 0f, eyeDistance),
        floatArrayOf(0f, 0f, centerZ),
        floatArrayOf(0f, 1f, 0f)
    )

    /** Projection matrix for the current framing — identical to the one the renderer uploads. */
    fun projection(frameRadius: Float, width: Int, height: Int, zoom: Float): FloatArray {
        val aspect = if (height > 0) width.toFloat() / height else 1f
        val eyeDist = eyeDistance(frameRadius, width, height, zoom)
        return perspective(
            FOVY_DEG,
            aspect,
            nearPlane(eyeDist, frameRadius),
            farPlane(eyeDist, frameRadius)
        )
    }

    /** The clip-space transform `P · V · M` — projection times view times model. */
    fun viewProjection(view: FloatArray, projection: FloatArray, model: FloatArray): FloatArray =
        mul(projection, mul(view, model))

    /** Transforms a point by a column-major 4x4 matrix. */
    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): Vec3 = Vec3(
        (m[0] * x + m[4] * y + m[8] * z + m[12]).toDouble(),
        (m[1] * x + m[5] * y + m[9] * z + m[13]).toDouble(),
        (m[2] * x + m[6] * y + m[10] * z + m[14]).toDouble()
    )

    /** Transforms a direction by a column-major 4x4 matrix (the translation column is ignored). */
    fun transformDirection(m: FloatArray, x: Float, y: Float, z: Float): Vec3 = Vec3(
        (m[0] * x + m[4] * y + m[8] * z).toDouble(),
        (m[1] * x + m[5] * y + m[9] * z).toDouble(),
        (m[2] * x + m[6] * y + m[10] * z).toDouble()
    )

    /**
     * Inverse of a rigid transform (rotation plus translation): the transpose of the rotation block
     * with the translation rotated back.
     *
     * A general 4x4 inverse would also work, but it accumulates error; this form stays exactly
     * orthogonal, which matters when the inverse is what turns a screen tap into a world point.
     */
    fun invertRigid(m: FloatArray): FloatArray {
        val out = FloatArray(16)
        out[0] = m[0]; out[1] = m[4]; out[2] = m[8]
        out[4] = m[1]; out[5] = m[5]; out[6] = m[9]
        out[8] = m[2]; out[9] = m[6]; out[10] = m[10]
        val tx = m[12]; val ty = m[13]; val tz = m[14]
        out[12] = -(out[0] * tx + out[4] * ty + out[8] * tz)
        out[13] = -(out[1] * tx + out[5] * ty + out[9] * tz)
        out[14] = -(out[2] * tx + out[6] * ty + out[10] * tz)
        out[15] = 1f
        return out
    }

    /**
     * The ray through viewport pixel ([screenX], [screenY]), expressed in the **model's own frame**.
     *
     * Built in view space and pushed through `M⁻¹ · V⁻¹`, so it starts at the real eye, undoes the
     * orbit *and* the pan, and can be intersected directly with a plane in the mesh's own
     * coordinates (`GearGLView.pick` slices it with the gear's local mid-plane and hands the result
     * to `ToothPick`, which works in that frame too).
     *
     * The previous version started the ray at `(0, 0, 40 · zoom)` — a distance that matched no
     * camera the renderer ever built — and left the pan in, so a tap resolved several millimetres
     * from where it landed and could select the neighbouring tooth. A pan of 3 mm in the model frame
     * is 3 mm of error in the answer.
     *
     * Returns null when the viewport has no size.
     */
    fun modelRay(
        rotXDeg: Float,
        rotYDeg: Float,
        panX: Float,
        panY: Float,
        frameRadius: Float,
        zoom: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        screenX: Float,
        screenY: Float
    ): Pair<Vec3, Vec3>? {
        if (viewportWidth <= 0 || viewportHeight <= 0) return null
        val aspect = viewportWidth.toFloat() / viewportHeight
        // The tangent of the half field of view is the one place the ray needs double precision:
        // `tan` takes and returns a Double, and the product below is a direction that has to match
        // the projection matrix to the last float.
        val halfFovTan = tan(Math.toRadians((FOVY_DEG / 2f).toDouble())).toFloat()
        val ndcX = 2f * screenX / viewportWidth - 1f
        val ndcY = 1f - 2f * screenY / viewportHeight
        val dirCamX = ndcX * halfFovTan * aspect
        val dirCamY = ndcY * halfFovTan
        val eyeDist = eyeDistance(frameRadius, viewportWidth, viewportHeight, zoom)
        val local = invertRigid(orbitModel(rotXDeg, rotYDeg, panX, panY))
        val origin = transformPoint(local, 0f, 0f, eyeDist)
        val dir = transformDirection(local, dirCamX, dirCamY, -1f)
        return origin to dir
    }
}
