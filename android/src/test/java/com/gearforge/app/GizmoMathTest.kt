package com.gearforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the viewport-gizmo projection and hit-test math. The projection is
 * pure and JVM-testable because it only depends on [CameraState] and [Quat].
 */
class GizmoMathTest {

    /** Camera with the given orbit angles and a synthetic non-zero viewport. */
    private fun camera(rotX: Float = 0f, rotY: Float = 0f): CameraState =
        CameraState(
            rotXDeg = rotX,
            rotYDeg = rotY,
            rotationQuaternion = gizmoQuaternion(rotX, rotY),
            viewportWidth = 1080,
            viewportHeight = 1920
        )

    private fun node(nodes: List<GizmoMath.ProjectedNode>, view: GizmoView): GizmoMath.ProjectedNode =
        nodes.first { it.view == view }

    @Test
    fun identityOrientationProjectsAxesToExpectedCorners() {
        val nodes = GizmoMath.project(camera(), 36f, 36f, 26f)
        // +X → right of centre.
        assertEquals(62f, node(nodes, GizmoView.RIGHT).x, 1e-3f)
        assertEquals(36f, node(nodes, GizmoView.RIGHT).y, 1e-3f)
        // +Y → above centre (screen y flipped).
        assertEquals(36f, node(nodes, GizmoView.FRONT).x, 1e-3f)
        assertEquals(10f, node(nodes, GizmoView.FRONT).y, 1e-3f)
        // +Z and −Z project onto the centre, at opposite depths.
        assertEquals(1f, node(nodes, GizmoView.TOP).depth, 1e-3f)
        assertEquals(-1f, node(nodes, GizmoView.BOTTOM).depth, 1e-3f)
    }

    @Test
    fun quaternionRotatesWorldZAtDefaultOrbit() {
        // Default isometric orbit (35°, 45°): the world +Z axis appears at
        // (0.5792, −0.5736, 0.5792) in view space — right and below centre, facing the viewer.
        // The gear's own axis really does point down-screen at this orbit; a gizmo that showed it
        // up and to the left was showing the *inverse* rotation.
        val v = Quat.rotateVector(gizmoQuaternion(35f, 45f), floatArrayOf(0f, 0f, 1f))
        assertEquals(0.5792f, v[0], 1e-3f)
        assertEquals(-0.5736f, v[1], 1e-3f)
        assertEquals(0.5792f, v[2], 1e-3f)
    }

    /**
     * The property that pins the convention: a puck must sit where the renderer puts that axis.
     *
     * This is the invariant that was broken. [GizmoMath.project] rotated world axes by `R⁻¹`, so
     * every puck was drawn where its *opposite* axis was — the widget appeared to turn the wrong way
     * when the model was orbited, and the view puck the user had just tapped was the one drawn as
     * facing away. The expectation here is computed from the same rotation matrices the renderer
     * uses ([ViewportCamera.rotationX] / [ViewportCamera.rotationY], composed as `Ry · Rx`) rather
     * than from a second copy of the quaternion maths, so the two can only agree by being right.
     */
    @Test
    fun pucksMatchTheRenderersViewSpaceAxes() {
        val axes = listOf(
            GizmoView.RIGHT to floatArrayOf(1f, 0f, 0f),
            GizmoView.LEFT to floatArrayOf(-1f, 0f, 0f),
            GizmoView.FRONT to floatArrayOf(0f, 1f, 0f),
            GizmoView.BACK to floatArrayOf(0f, -1f, 0f),
            GizmoView.TOP to floatArrayOf(0f, 0f, 1f),
            GizmoView.BOTTOM to floatArrayOf(0f, 0f, -1f)
        )
        for ((rotX, rotY) in listOf(0f to 0f, 90f to 0f, 35f to 45f, 0f to -90f, -20f to 130f)) {
            val nodes = GizmoMath.project(camera(rotX, rotY), 36f, 36f, 26f)
            val r = ViewportCamera.mul(
                ViewportCamera.rotationY(rotY),
                ViewportCamera.rotationX(rotX)
            )
            for ((view, axis) in axes) {
                val v = ViewportCamera.transformDirection(r, axis[0], axis[1], axis[2])
                val at = node(nodes, view)
                assertEquals("$view x at ($rotX, $rotY)", 36f + v.x.toFloat() * 26f, at.x, 1e-3f)
                assertEquals("$view y at ($rotX, $rotY)", 36f - v.y.toFloat() * 26f, at.y, 1e-3f)
                assertEquals("$view depth at ($rotX, $rotY)", v.z.toFloat(), at.depth, 1e-3f)
            }
        }
    }

    @Test
    fun frontViewTurnsTheGearAxisDownScreen() {
        // FRONT (rotX = 90°) looks at the gear's +Y face, so the gear's own axis (+Z) runs
        // *downwards* on screen and lies in the view plane (depth 0).
        val nodes = GizmoMath.project(camera(rotX = 90f), 36f, 36f, 26f)
        val top = node(nodes, GizmoView.TOP)
        assertEquals(36f, top.x, 1e-3f)
        assertEquals(62f, top.y, 1e-3f)
        assertEquals(0f, top.depth, 1e-3f)
    }

    @Test
    fun hitTestReturnsFrontMostNodeWithinRadius() {
        val nodes = GizmoMath.project(camera(), 36f, 36f, 26f)
        // +X node sits at (62, 36); a tap there (24dp hit radius) hits RIGHT.
        assertEquals(GizmoView.RIGHT, GizmoMath.hitTest(62f, 36f, nodes, 24f))
        // +Y node sits at (36, 10).
        assertEquals(GizmoView.FRONT, GizmoMath.hitTest(36f, 10f, nodes, 24f))
        // A tap well outside every node misses.
        assertNull(GizmoMath.hitTest(-20f, -20f, nodes, 24f))
    }

    @Test
    fun hitTestBreaksAnExactDistanceTieByDepth() {
        // At identity the +Z (depth 1) and −Z (depth −1) nodes both project onto the centre, so the
        // distances are exactly equal and only depth can decide. The facing axis must win.
        val nodes = GizmoMath.project(camera(), 36f, 36f, 26f)
        assertEquals(GizmoView.TOP, GizmoMath.hitTest(36f, 36f, nodes, 24f))
    }

    @Test
    fun hitTestPrefersTheNearestNodeOverAMoreFrontFacingFartherOne() {
        // Both nodes are inside the hit radius; the nearer one faces *away* from the camera.
        // Proximity must win: the user aimed at the near puck.
        val nodes = listOf(
            GizmoMath.ProjectedNode(GizmoView.RIGHT, 40f, 36f, depth = 0f, isPositive = true),
            GizmoMath.ProjectedNode(GizmoView.TOP, 50f, 36f, depth = 1f, isPositive = true)
        )
        assertEquals(GizmoView.RIGHT, GizmoMath.hitTest(41f, 36f, nodes, 24f))
    }

    @Test
    fun resolveTapResetsFromTheCentrePuckEvenWhenAxisCirclesCoverIt() {
        val nodes = GizmoMath.project(camera(), 36f, 36f, 26f)
        // The axis hit circles reach inwards past the centre, so a raw hit test reports an axis.
        assertEquals(GizmoView.TOP, GizmoMath.hitTest(36f, 36f, nodes, 24f))
        // The puck is an explicit target: inside its radius HOME must win regardless.
        assertEquals(GizmoView.HOME, GizmoMath.resolveTap(36f, 36f, nodes, 36f, 36f, 9f, 24f))
        assertEquals(GizmoView.HOME, GizmoMath.resolveTap(44f, 36f, nodes, 36f, 36f, 9f, 24f))
    }

    @Test
    fun resolveTapHitsAnAxisJustOutsideTheCentrePuck() {
        val nodes = GizmoMath.project(camera(), 36f, 36f, 26f)
        // 16 px right of centre: outside the 9 px puck and nearer to +X (62, 36) than to the
        // +Z/−Z pair projected on the centre (16 vs 20/20).
        assertEquals(GizmoView.RIGHT, GizmoMath.resolveTap(52f, 36f, nodes, 36f, 36f, 9f, 24f))
        // A tap beyond every hit radius falls back to HOME rather than doing nothing.
        assertEquals(GizmoView.HOME, GizmoMath.resolveTap(96f, 96f, nodes, 36f, 36f, 9f, 24f))
    }

    /**
     * Reachability sweep: every target must be obtainable by tapping somewhere in the widget.
     *
     * This is the property that a hit-test change can silently break — an unreachable target is
     * invisible in a screenshot and only shows up as "the view button does nothing". The default
     * isometric orbit is used because it is the state the editor opens in, and because at identity
     * the +Z/−Z pair collapses onto the centre and cannot both be reachable.
     */
    @Test
    fun everyGizmoTargetIsReachableAcrossTheWidget() {
        val nodes = GizmoMath.project(camera(rotX = 35f, rotY = 45f), 36f, 36f, 26f)
        val reached = HashSet<GizmoView>()
        var y = 0f
        while (y <= 72f) {
            var x = 0f
            while (x <= 72f) {
                reached.add(GizmoMath.resolveTap(x, y, nodes, 36f, 36f, 9f, 24f))
                x += 1f
            }
            y += 1f
        }
        assertEquals(GizmoView.entries.toSet(), reached)
    }

    @Test
    fun targetOrbitMapsEveryViewToSnapAngles() {
        assertEquals(0f to 0f, GizmoView.TOP.targetOrbit())
        assertEquals(0f to 180f, GizmoView.BOTTOM.targetOrbit())
        assertEquals(90f to 0f, GizmoView.FRONT.targetOrbit())
        assertEquals(-90f to 0f, GizmoView.BACK.targetOrbit())
        assertEquals(0f to -90f, GizmoView.RIGHT.targetOrbit())
        assertEquals(0f to 90f, GizmoView.LEFT.targetOrbit())
        assertEquals(35f to 45f, GizmoView.HOME.targetOrbit())
    }

    @Test
    fun quaternionIsUnitAndRotatesBackToIdentityWhenInverted() {
        // Rotating +Z by the camera quaternion and then by its inverse must round-trip.
        val q = gizmoQuaternion(35f, 45f)
        val len = kotlin.math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        assertEquals(1f, len, 1e-4f)
        val fwd = Quat.rotateVector(q, floatArrayOf(0f, 0f, 1f))
        val inv = Quat.normalize(floatArrayOf(-q[0], -q[1], -q[2], q[3])) // conjugate
        val back = Quat.rotateVector(inv, fwd)
        assertEquals(0f, back[0], 1e-4f)
        assertEquals(0f, back[1], 1e-4f)
        assertEquals(1f, back[2], 1e-4f)
    }

    @Test
    fun cameraUnavailableUntilViewportKnown() {
        val noViewport = CameraState(viewportWidth = 0, viewportHeight = 0)
        assertEquals(false, noViewport.isAvailable)
        assertNotNull(camera()) // a non-zero viewport is available
        assertEquals(true, camera().isAvailable)
    }
}
