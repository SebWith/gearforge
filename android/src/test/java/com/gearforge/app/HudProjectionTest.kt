package com.gearforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measurement HUD positions labels by projecting world points through the same matrices the
 * renderer uses. These tests pin the parts that are easy to get *almost* right — the
 * multiplication order, the model matrix that the orbit and the pan live in, the homogeneous
 * divide, and the near-plane rejection — because a wrong projection produces labels that look
 * plausible on one camera angle and absurd on the next.
 */
class HudProjectionTest {

    private val identity = ViewportCamera.identity()

    /**
     * A label is placed from its own width, and a label wider than its own viewport must be clamped
     * inside the window instead of running off it. This is the placement half of the defect where
     * the longest dimension labels lost their unit: the width was a constant that could not fit the
     * text, and nothing checked that the pill was still inside the viewport after placement.
     */
    @Test
    fun labelIsPlacedOnTheFarSideOfItsLeader() {
        // Leader tip left of centre: the text goes to its left, so it never covers the geometry.
        assertEquals(20f, HudProjection.labelLeft(toX = 200f, labelWidthPx = 180f, viewportWidth = 1000f), 1e-3f)
        // Leader tip right of centre: the text goes to its right.
        assertEquals(700f, HudProjection.labelLeft(toX = 700f, labelWidthPx = 180f, viewportWidth = 1000f), 1e-3f)
        assertTrue(HudProjection.labelOnLeft(200f, 1000f))
        assertTrue(!HudProjection.labelOnLeft(700f, 1000f))
    }

    @Test
    fun labelIsClampedInsideTheViewport() {
        // A long label whose leader points left would start at a negative x.
        val x = HudProjection.labelLeft(toX = 40f, labelWidthPx = 400f, viewportWidth = 1000f)
        assertTrue("expected x >= 0 but was $x", x >= 0f)

        // And one whose leader points right must not push its right edge past the window.
        val x2 = HudProjection.labelLeft(toX = 950f, labelWidthPx = 400f, viewportWidth = 1000f)
        assertTrue("expected the right edge inside the window but x=$x2", x2 + 400f <= 1000f + 1e-3f)
    }

    @Test
    fun aLabelWiderThanTheViewportStartsAtTheLeftEdgeAndIsNotPushedOff() {
        // The pill cannot be made to fit, so the honest result is a pill flush with the left edge;
        // anything else would put its start off-screen and hide the parameter's name.
        assertEquals(0f, HudProjection.labelLeft(toX = 900f, labelWidthPx = 1400f, viewportWidth = 1000f), 1e-3f)
        assertEquals(0f, HudProjection.labelLeft(toX = 100f, labelWidthPx = 1400f, viewportWidth = 1000f), 1e-3f)
    }

    /**
     * A label may not be placed over a panel that already owns that strip of the viewport.
     *
     * The assembly legend and the navigation gizmo both share a corner with the labels, so a label
     * anchored high on the model was drawn across the panel above it. The limit belongs to the
     * *slot* rather than to the call, because only some labels are in a panel's way: one on the left
     * has the legend above it, one on the right has the gizmo, and one in the middle has neither.
     */
    @Test
    fun labelsAreKeptBelowWhateverOwnsTheSpaceAboveThem() {
        val free = listOf(
            HudProjection.Slot("high", y = 20f, height = 40f),
            HudProjection.Slot("low", y = 30f, height = 40f)
        )
        assertEquals(
            "a label in open viewport keeps the row it projected onto",
            20f,
            HudProjection.distribute(free, gap = 4f).first().y,
            1e-3f
        )

        val blocked = listOf(
            HudProjection.Slot("high", y = 20f, height = 40f, topLimit = 300f),
            HudProjection.Slot("low", y = 30f, height = 40f)
        )
        val limited = HudProjection.distribute(blocked, gap = 4f).associateBy { it.id }
        val high = limited.getValue("high")
        assertTrue("the label must report that it was moved", high.shifted)
        assertTrue(
            "the label's top edge is at ${high.y - 20f}, inside the panel that ends at 300",
            high.y - 40f / 2f >= 300f
        )
        assertTrue(
            "the second label must still clear the first",
            limited.getValue("low").y >= high.y + 40f + 4f - 1e-3f
        )

        // A label with no limit must not be dragged down by a neighbour that has one.
        assertEquals(
            "an unblocked label keeps its own row",
            30f,
            HudProjection.distribute(
                listOf(HudProjection.Slot("only", y = 30f, height = 40f, topLimit = -Float.MAX_VALUE)),
                gap = 4f
            ).first().y,
            1e-3f
        )
    }

    /** The full `P · V · M` with a neutral camera, for the tests that only exercise the pipeline. */
    private fun plain(): FloatArray = ViewportCamera.viewProjection(identity, identity, identity)

    /** Column-major translation by (tx, ty, tz). */
    private fun translation(tx: Float, ty: Float, tz: Float) = identity.copyOf().also {
        it[12] = tx
        it[13] = ty
        it[14] = tz
    }

    /** Column-major uniform scale. */
    private fun scale(s: Float) = FloatArray(16).also { it[0] = s; it[5] = s; it[10] = s; it[15] = 1f }

    @Test
    fun identityTransformMapsWorldToViewportCentreAndEdges() {
        val vp = plain()

        val centre = HudProjection.project(vp, 200, 100, 0f, 0f, 0f)
        assertNotNull(centre)
        assertEquals(100f, centre!!.x, 1e-4f)
        assertEquals(50f, centre.y, 1e-4f)

        // +x is right, +y is up (so −screen y), covering the full extent at ndc ±0.5.
        val upperRight = HudProjection.project(vp, 200, 100, 0.5f, 0.5f, 0f)!!
        assertEquals(150f, upperRight.x, 1e-4f)
        assertEquals(25f, upperRight.y, 1e-4f)

        val lowerLeft = HudProjection.project(vp, 200, 100, -0.5f, -0.5f, 0f)!!
        assertEquals(50f, lowerLeft.x, 1e-4f)
        assertEquals(75f, lowerLeft.y, 1e-4f)
    }

    @Test
    fun viewProjectionComposesProjectionTimesViewNotTheOtherWayRound() {
        val projection = scale(2f)
        val view = translation(-5f, 0f, 0f)

        // Correct: P · V · (5,0,0) → V maps it to the origin → screen centre.
        val correct = HudProjection.project(
            ViewportCamera.viewProjection(view, projection, identity), 200, 100, 5f, 0f, 0f
        )!!
        assertEquals(100f, correct.x, 1e-3f)
        assertEquals(50f, correct.y, 1e-3f)

        // Wrong order (V · P) would scale first and land far off the viewport.
        val wrong = HudProjection.project(
            ViewportCamera.viewProjection(projection, view, identity), 200, 100, 5f, 0f, 0f
        )!!
        assertTrue("the reversed order must not also land on the centre", wrong.x > 150f)
    }

    /**
     * The orbit is a model rotation, so it belongs *inside* the projection.
     *
     * With the model turned 90° about Y, the world +X axis points straight away from the camera and
     * its label must land on the screen centre — the point on the +X axis is now behind the origin.
     * Leaving the model matrix out of the transform (which is what the HUD used to do) shows it to
     * the right of centre, frozen where the model was before it was orbited.
     */
    @Test
    fun theOrbitRotationIsPartOfTheProjection() {
        val projection = ViewportCamera.perspective(35f, 200f / 100f, 0.01f, 200f)
        val view = ViewportCamera.view(0f, 50f)
        val orbit = ViewportCamera.orbitModel(0f, 90f, 0f, 0f)

        val withModel = HudProjection.project(
            ViewportCamera.viewProjection(view, projection, orbit), 200, 100, 10f, 0f, 0f
        )!!
        assertEquals("a point rotated onto the view axis lands on the centre", 100f, withModel.x, 1e-2f)

        val withoutModel = HudProjection.project(
            ViewportCamera.viewProjection(view, projection, identity), 200, 100, 10f, 0f, 0f
        )!!
        assertTrue("without the model matrix the point would still be to the right", withoutModel.x > 110f)
    }

    /** The pan lives in the model matrix too: dragging the scene must move its labels with it. */
    @Test
    fun thePanIsPartOfTheProjection() {
        val projection = ViewportCamera.perspective(35f, 200f / 100f, 0.01f, 200f)
        val view = ViewportCamera.view(0f, 50f)
        val centred = ViewportCamera.orbitModel(0f, 0f, 0f, 0f)
        val panned = ViewportCamera.orbitModel(0f, 0f, 10f, 0f)

        val before = HudProjection.project(
            ViewportCamera.viewProjection(view, projection, centred), 200, 100, 0f, 0f, 0f
        )!!
        val after = HudProjection.project(
            ViewportCamera.viewProjection(view, projection, panned), 200, 100, 0f, 0f, 0f
        )!!
        assertEquals(100f, before.x, 1e-2f)
        assertTrue("panning the model must move the label off the centre", after.x > 110f)
    }

    @Test
    fun pointsWithoutAScreenPositionAreRejected() {
        // w = −z, so a point in front of the eye (z negative) has w > 0 and one behind has w ≤ 0.
        val wFromNegativeZ = FloatArray(16).also {
            it[0] = 1f; it[5] = 1f; it[10] = 1f; it[11] = -1f
        }
        assertNotNull(HudProjection.project(wFromNegativeZ, 200, 100, 0f, 0f, -5f))
        assertNull("a point behind the camera must not be placed", HudProjection.project(wFromNegativeZ, 200, 100, 0f, 0f, 5f))
        assertNull("a point on the eye plane must not be placed", HudProjection.project(wFromNegativeZ, 200, 100, 0f, 0f, 0f))
    }

    @Test
    fun aZeroSizedViewportProducesNoLabels() {
        val vp = plain()
        assertNull(HudProjection.project(vp, 0, 100, 1f, 1f, 0f))
        assertNull(HudProjection.project(vp, 200, 0, 1f, 1f, 0f))
    }

    @Test
    fun pixelsPerMillimetreDerivesFromFocalLengthAndDepth() {
        // f = 2, viewport height 400, depth 10 → (400/2)·2/10 = 40 px/mm.
        val projection = FloatArray(16).also { it[5] = 2f }
        val ppm = HudProjection.pixelsPerMm(identity, projection, identity, 400, 0f, 0f, -10f)
        assertNotNull(ppm)
        assertEquals(40f, ppm!!, 1e-3f)

        // Twice as far away → half the pixels per millimetre.
        assertEquals(20f, HudProjection.pixelsPerMm(identity, projection, identity, 400, 0f, 0f, -20f)!!, 1e-3f)
    }

    /**
     * The bar is calibrated at a point on the *model*, so the model matrix has to be applied before
     * the depth is read. Orbiting that point away from the camera must shorten the bar instead of
     * leaving it calibrated for where the point used to be.
     *
     * (The pan cannot be used for this: it moves the scene sideways, and the camera looks straight
     * down Z, so a pan never changes a point's depth.)
     */
    @Test
    fun pixelsPerMillimetreFollowsTheModelThroughTheOrbit() {
        // f = 2, height 400 → a point 50 mm from the eye reads 8 px/mm.
        val projection = FloatArray(16).also { it[5] = 2f }
        val view = ViewportCamera.view(0f, 50f)
        val facing = ViewportCamera.orbitModel(0f, 0f, 0f, 0f)
        // Ry(90°): the +X point swings onto −Z, i.e. 10 mm further from the eye.
        val turned = ViewportCamera.orbitModel(0f, 90f, 0f, 0f)

        val near = HudProjection.pixelsPerMm(view, projection, facing, 400, 10f, 0f, 0f)!!
        val far = HudProjection.pixelsPerMm(view, projection, turned, 400, 10f, 0f, 0f)!!
        assertEquals(8f, near, 1e-3f)
        assertTrue("turning the point away from the camera must shrink the scale", far < near)
        assertEquals(400f * 0.5f * 2f / 60f, far, 1e-3f)
    }

    @Test
    fun pixelsPerMillimetreRejectsDegenerateInput() {
        val identityProjection = identity
        assertNull("a point behind the camera has no scale", HudProjection.pixelsPerMm(identity, identityProjection, identity, 400, 0f, 0f, 5f))
        assertNull("a zero-height viewport has no scale", HudProjection.pixelsPerMm(identity, identityProjection, identity, 0, 0f, 0f, -10f))
        assertNull("a degenerate focal length has no scale", HudProjection.pixelsPerMm(identity, FloatArray(16), identity, 400, 0f, 0f, -10f))
    }

    @Test
    fun distributeSeparatesOverlappingLabelsAndKeepsTheirOrder() {
        val slots = listOf(
            HudProjection.Slot("outer", 100f, 20f),
            HudProjection.Slot("pitch", 105f, 20f),
            HudProjection.Slot("root", 200f, 20f)
        )
        val placed = HudProjection.distribute(slots, gap = 4f)
        assertEquals(listOf("outer", "pitch", "root"), placed.map { it.id })

        val outer = placed.first { it.id == "outer" }
        val pitch = placed.first { it.id == "pitch" }
        val root = placed.first { it.id == "root" }
        assertTrue("the two overlapping labels must be separated", pitch.y >= outer.y + 20f)
        assertTrue("a label with room must not move", !outer.shifted)
        assertTrue("the shifted label must be reported as shifted", pitch.shifted)
        assertEquals(200f, root.y, 1e-4f)
    }

    @Test
    fun distributeIsAnIdentityForNonOverlappingLabels() {
        val slots = listOf(HudProjection.Slot("a", 10f, 12f), HudProjection.Slot("b", 40f, 12f))
        val placed = HudProjection.distribute(slots, gap = 4f)
        assertEquals(listOf(10f, 40f), placed.map { it.y })
        assertTrue(placed.none { it.shifted })
    }

    @Test
    fun distancePxMeasuresTheProjectedSpan() {
        val vp = plain()
        val a = HudProjection.project(vp, 200, 100, 0f, 0f, 0f)
        val b = HudProjection.project(vp, 200, 100, 0.5f, 0f, 0f)
        assertEquals(50f, HudProjection.distancePx(a, b)!!, 1e-3f)
        assertNull("a missing endpoint has no distance", HudProjection.distancePx(a, null))
    }
}
