package com.gearforge.app

import kotlin.math.hypot

/**
 * Screen-space projection for the measurement HUD.
 *
 * The HUD is a Compose overlay on top of the GL surface (the viewport itself must not render
 * text — see `.github/instructions/android-ui.instructions.md`), so the labels need the same
 * transform the renderer used. [CameraState] already publishes the matrices it feeds the GL
 * uniforms, so this object only has to reproduce the standard pipeline:
 *
 * ```
 * clip     = P · V · M · world   (the matrices come from CameraState, column-major as GL stores them)
 * ndc      = clip.xyz / clip.w
 * pixels   = (ndc.x·0.5 + 0.5)·width , (1 − (ndc.y·0.5 + 0.5))·height
 * ```
 *
 * **`M` is not optional.** The orbit is a *model* rotation in front of a fixed camera, and the pan
 * is part of the model too. Projecting `P · V · world` — as this object did at first — leaves the
 * labels frozen in place while the model turns and slides under them. The composition itself lives
 * in [ViewportCamera], the single definition the renderer and the picker also use.
 *
 * Everything here is deliberately free of Compose types (like [GizmoMath]) so it can be unit
 * tested on the JVM: a projection bug produces labels that look "almost right", which is the
 * hardest kind of defect to spot in a screenshot.
 */
internal object HudProjection {

    /** A world point projected into viewport pixels. */
    data class Projected(val x: Float, val y: Float, val depth: Float)

    /** Transforms a homogeneous point by a column-major 4×4 matrix. */
    fun transform(m: FloatArray, x: Float, y: Float, z: Float, w: Float): FloatArray = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12] * w,
        m[1] * x + m[5] * y + m[9] * z + m[13] * w,
        m[2] * x + m[6] * y + m[10] * z + m[14] * w,
        m[3] * x + m[7] * y + m[11] * z + m[15] * w
    )

    /**
     * Projects a world point to viewport pixels through the complete `P · V · M` transform
     * (see [ViewportCamera.viewProjection] — the model matrix has to be part of it, or the
     * labels ignore the orbit and the pan).
     *
     * Returns `null` when the point has no screen position: outside the near plane, on the eye
     * plane, or behind the camera (`w ≤ 0`). A naive `clip.xy / clip.w` would place such a point
     * mirrored on the far side of the screen, so the caller must drop the label instead.
     */
    fun project(vp: FloatArray, width: Int, height: Int, x: Float, y: Float, z: Float): Projected? {
        if (width <= 0 || height <= 0) return null
        val clip = transform(vp, x, y, z, 1f)
        val w = clip[3]
        if (w <= 1e-6f) return null
        val ndcX = clip[0] / w
        val ndcY = clip[1] / w
        return Projected(
            x = (ndcX * 0.5f + 0.5f) * width,
            y = (1f - (ndcY * 0.5f + 0.5f)) * height,
            depth = clip[2] / w
        )
    }

    /**
     * Viewport pixels per millimetre at the world point (`x`, `y`, `z`) — the scale bar's
     * calibration.
     *
     * Derived from the projection matrix and the point's camera-space depth rather than by
     * projecting two points a millimetre apart: the two-point method gives a different answer
     * depending on which world axis the offset happens to follow once the camera is orbited,
     * so the bar would silently change meaning as the user rotates the model.
     *
     * The point is pushed through the model matrix first. Skipping that step leaves the bar
     * calibrated for a point where the model used to be, so its length drifts as the model is
     * orbited away from the camera.
     *
     * `projectionMatrix[5]` is `1 / tan(fovY / 2)`, so a world unit at depth `d` covers
     * `(height / 2) · f / d` pixels.
     */
    fun pixelsPerMm(
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        modelMatrix: FloatArray,
        height: Int,
        x: Float,
        y: Float,
        z: Float
    ): Float? {
        if (height <= 0) return null
        val world = ViewportCamera.transformPoint(modelMatrix, x, y, z)
        val eye = transform(viewMatrix, world.x.toFloat(), world.y.toFloat(), world.z.toFloat(), 1f)
        val depth = -eye[2] // GL cameras look down −Z, so a visible point has a negative z.
        if (depth <= 1e-4f) return null
        val f = projectionMatrix[5]
        if (f <= 0f) return null
        return (height * 0.5f) * f / depth
    }

    /**
     * A label's requested vertical slot before de-collision.
     *
     * [topLimit] is the bottom edge of whatever already occupies the space above this label — the
     * legend, the navigation gizmo, or nothing at all. It stays per-slot rather than per-call
     * because only some of the labels are in a panel's way: one on the left of the viewport has the
     * legend above it, one on the right has the gizmo, and a label in the middle has neither.
     */
    data class Slot(
        val id: String,
        val y: Float,
        val height: Float,
        val topLimit: Float = Float.NEGATIVE_INFINITY
    )

    /** A label's resolved vertical position. */
    data class Placed(val id: String, val y: Float, val shifted: Boolean)

    /**
     * Pushes vertically overlapping labels apart while keeping their order.
     *
     * Two diameter labels anchored on opposite sides of a gear project onto nearly the same
     * row when the camera looks straight down the axis, and stacked text is unreadable. This
     * is a deterministic sweep rather than a physics relaxation so the layout does not jitter
     * as the camera moves: it walks the labels in ascending `y` and moves each one just far
     * enough to clear the previous one.
     *
     * [topLimit][Slot.topLimit] is where each label's own obstacle ends — in practice the assembly
     * legend or the navigation gizmo, both of which share a corner with the labels. Without it a
     * label anchored high on the model was drawn across the panel above it. A label is placed by its
     * own centre, so the first free centre sits half a label below its limit; the default per slot
     * leaves the sweep unlimited, which is what a label in open viewport wants.
     */
    fun distribute(slots: List<Slot>, gap: Float): List<Placed> {
        if (slots.isEmpty()) return emptyList()
        val ordered = slots.sortedBy { it.y }
        val out = ArrayList<Placed>(ordered.size)
        // `floorY + gap` is `-Infinity` until the first label is placed, so the sweep needs no
        // special case for it.
        var floorY = Float.NEGATIVE_INFINITY
        for (s in ordered) {
            val needed = maxOf(s.y, floorY + gap, s.topLimit + s.height / 2f + gap)
            out.add(Placed(s.id, needed, needed != s.y))
            floorY = needed + s.height
        }
        return out
    }

    /** Distance in pixels between two projected points, or `null` if either has no position. */
    fun distancePx(a: Projected?, b: Projected?): Float? {
        if (a == null || b == null) return null
        val d = hypot(a.x - b.x, a.y - b.y)
        return if (d > 0f) d else null
    }

    /**
     * Left edge of a label whose leader line ends at [toX], sized [labelWidthPx], inside a viewport
     * [viewportWidth] wide.
     *
     * The label sits on the opposite side of the leader's direction: a leader pointing away from
     * the centre to the right gets its text to the right of the tip, and one pointing left gets the
     * text to its left, so the text never covers the geometry it names. The result is clamped to
     * the viewport so a long dimension ("Outer diameter  22.000 mm") cannot be pushed off-screen.
     *
     * `HudProjection` owns this rather than the composable because it is arithmetic, and arithmetic
     * that decides whether a number is readable is worth a test.
     */
    fun labelLeft(toX: Float, labelWidthPx: Float, viewportWidth: Float): Float {
        val width = labelWidthPx.coerceAtMost(maxOf(viewportWidth, 0f))
        val onLeft = toX < viewportWidth / 2f
        val x = if (onLeft) toX - width else toX
        return x.coerceIn(0f, maxOf(0f, viewportWidth - width))
    }

    /**
     * True when the label's line of sight points away from the viewport centre, i.e. the label is
     * drawn on the left of its leader tip. Kept next to [labelLeft] so the two cannot disagree
     * about which side a label is on — a mismatch there draws the pill over its own text anchor.
     */
    fun labelOnLeft(toX: Float, viewportWidth: Float): Boolean = toX < viewportWidth / 2f
}
