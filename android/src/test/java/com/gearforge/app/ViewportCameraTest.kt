package com.gearforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera definition the renderer, the overlays and the picker all share.
 *
 * The defect these tests exist for: the renderer drew `P · V · M`, the HUD projected `P · V`, and
 * the picker assumed an eye at `(0, 0, 40 · zoom)`. Three answers to one question, and the two wrong
 * ones looked plausible on screen. The tests below pin the *invariants between* the answers — that
 * the ray through a pixel is the inverse of the projection to that pixel — rather than any single
 * number, because it is the disagreement that broke.
 */
class ViewportCameraTest {

    private val width = 1080
    private val height = 1920

    @Test
    fun eyeDistanceFitsTheFrameRadiusAndScalesWithZoom() {
        // A square viewport: the narrower field of view is the vertical one (35°), so the eye sits
        // frameRadius / sin(17.5°) away.
        val expected = (20f / kotlin.math.sin(Math.toRadians(17.5))).toFloat()
        assertEquals(expected, ViewportCamera.eyeDistance(20f, 1000, 1000, 1f), 1e-3f)
        assertEquals(2f * expected, ViewportCamera.eyeDistance(20f, 1000, 1000, 2f), 1e-2f)
        // A radius of zero means "not measured yet", and must fall back rather than divide by zero.
        assertEquals(
            ViewportCamera.eyeDistance(ViewportCamera.DEFAULT_FRAME_RADIUS, 1000, 1000, 1f),
            ViewportCamera.eyeDistance(0f, 1000, 1000, 1f),
            1e-3f
        )
    }

    @Test
    fun theOrbitModelIsTheRotationTheRendererDrawsWith() {
        // Pan alone is a translation; there is no rotation to confuse it with.
        val panned = ViewportCamera.orbitModel(0f, 0f, 3f, -4f)
        val moved = ViewportCamera.transformPoint(panned, 1f, 2f, 5f)
        assertEquals(4.0, moved.x, 1e-5)
        assertEquals(-2.0, moved.y, 1e-5)
        assertEquals(5.0, moved.z, 1e-5)

        // Ry(90°) must send +X to −Z: the axis points away from the fixed camera.
        val turned = ViewportCamera.orbitModel(0f, 90f, 0f, 0f)
        val xAxis = ViewportCamera.transformDirection(turned, 1f, 0f, 0f)
        assertEquals(0.0, xAxis.x, 1e-5)
        assertEquals(0.0, xAxis.y, 1e-5)
        assertEquals(-1.0, xAxis.z, 1e-5)
    }

    @Test
    fun theBedModelNeverTakesTheOrbit() {
        // A print bed is a table: whatever the orbit does to the model, the platen's plane keeps its
        // height and only follows the pan.
        val bed = ViewportCamera.bedModel(7f, -3f, -5f)
        val corner = ViewportCamera.transformPoint(bed, 110f, -110f, 0f)
        assertEquals(117.0, corner.x, 1e-4)
        assertEquals(-113.0, corner.y, 1e-4)
        assertEquals(-5.0, corner.z, 1e-4)
    }

    @Test
    fun invertRigidUndoesTheModelMatrixExactly() {
        val model = ViewportCamera.orbitModel(35f, 45f, 3f, -2f)
        val inverse = ViewportCamera.invertRigid(model)
        val product = ViewportCamera.mul(inverse, model)
        val identity = ViewportCamera.identity()
        for (i in 0 until 16) {
            assertEquals("element $i of M⁻¹ · M", identity[i], product[i], 1e-5f)
        }
    }

    /**
     * The invariant that ties the overlay and the picker together: a world point projected to a pixel
     * must be on the ray that the same pixel produces.
     *
     * This is where the two conventions met and disagreed. The projection applies `M` (orbit *and*
     * pan); the ray has to undo all of it, from the eye the renderer actually uses.
     */
    @Test
    fun theRayThroughAPixelPassesThroughThePointThatProjectsThere() {
        val rotX = 35f
        val rotY = 45f
        val panX = 3f
        val panY = -2f
        val frameRadius = 18f
        val zoom = 1.35f

        val projection = ViewportCamera.projection(frameRadius, width, height, zoom)
        val eyeDist = ViewportCamera.eyeDistance(frameRadius, width, height, zoom)
        val view = ViewportCamera.view(0f, eyeDist)
        val model = ViewportCamera.orbitModel(rotX, rotY, panX, panY)
        val vp = ViewportCamera.viewProjection(view, projection, model)

        // Points in the *model's* frame, which is the frame the picker works in.
        val localPoints = listOf(
            Triple(8f, 3f, 4f),
            Triple(-6f, -7f, 4f),
            Triple(0f, 12f, 4f),
            Triple(12f, -12f, 0f)
        )
        for ((lx, ly, lz) in localPoints) {
            val screen = HudProjection.project(vp, width, height, lx, ly, lz)
            assertNotNull("($lx, $ly, $lz) must be on screen", screen)
            val ray = ViewportCamera.modelRay(
                rotX, rotY, panX, panY, frameRadius, zoom, width, height, screen!!.x, screen.y
            )
            assertNotNull(ray)
            val (origin, dir) = ray!!
            // Slice the ray with the plane the point lies in and expect the point back.
            assertTrue("the ray must not lie in the slice plane", kotlin.math.abs(dir.z) > 1e-6)
            val t = (lz - origin.z) / dir.z
            assertEquals("x of ($lx, $ly, $lz)", lx.toDouble(), origin.x + dir.x * t, 1e-3)
            assertEquals("y of ($lx, $ly, $lz)", ly.toDouble(), origin.y + dir.y * t, 1e-3)
        }
    }

    @Test
    fun theRayStartsAtTheEyeTheCameraHas() {
        // The eye is on the model frame's +Z axis at the eye distance, rotated back, and must not be
        // the old hard-coded 40 · zoom.
        val frameRadius = 18f
        val zoom = 1f
        val (origin, _) = ViewportCamera.modelRay(
            0f, 0f, 0f, 0f, frameRadius, zoom, width, height, width / 2f, height / 2f
        )!!
        assertEquals(
            ViewportCamera.eyeDistance(frameRadius, width, height, zoom).toDouble(),
            origin.z,
            1e-3
        )
        assertTrue(
            "the eye must not be at the old hard-coded 40 mm",
            kotlin.math.abs(origin.z - 40.0) > 1.0
        )
    }

    @Test
    fun aPannedSceneMovesThePickedPoint() {
        // The pan belongs to the model, so undoing it is part of turning a tap into a model point:
        // the same pixel must resolve 25 mm away once the scene has been panned 25 mm.
        val centred = ViewportCamera.modelRay(
            0f, 0f, 0f, 0f, 20f, 1f, width, height, 700f, 900f
        )!!
        val panned = ViewportCamera.modelRay(
            0f, 0f, 25f, -25f, 20f, 1f, width, height, 700f, 900f
        )!!
        assertEquals(-25.0, panned.first.x - centred.first.x, 1e-3)
        assertEquals(25.0, panned.first.y - centred.first.y, 1e-3)
        // The direction is unaffected: panning moves the scene, it does not turn the camera.
        assertEquals(0.0, panned.second.x - centred.second.x, 1e-6)
        assertEquals(0.0, panned.second.y - centred.second.y, 1e-6)
    }

    @Test
    fun aDegenerateViewportHasNoRay() {
        assertNull(ViewportCamera.modelRay(0f, 0f, 0f, 0f, 20f, 1f, 0, 100, 10f, 10f))
        assertNull(ViewportCamera.modelRay(0f, 0f, 0f, 0f, 20f, 1f, 100, 0, 10f, 10f))
    }

    @Test
    fun viewProjectionMultipliesInTheOrderTheRendererUses() {
        // P · V · M, not any permutation: with M = translate(5,0,0) and V = translate(−5,0,0) the
        // point at the origin must land on the centre.
        val p = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        val view = ViewportCamera.translation(-5f, 0f, 0f)
        val model = ViewportCamera.translation(5f, 0f, 0f)
        val vp = ViewportCamera.viewProjection(view, p, model)
        val centre = HudProjection.project(vp, 200, 100, 0f, 0f, 0f)!!
        assertEquals(100f, centre.x, 1e-3f)
        assertEquals(50f, centre.y, 1e-3f)
    }
}
