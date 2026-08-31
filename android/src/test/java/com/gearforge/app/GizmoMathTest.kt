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
        // (−0.7071, 0.4056, 0.5792) in camera space.
        val v = Quat.rotateVector(gizmoQuaternion(35f, 45f), floatArrayOf(0f, 0f, 1f))
        assertEquals(-0.7071f, v[0], 1e-3f)
        assertEquals(0.4056f, v[1], 1e-3f)
        assertEquals(0.5792f, v[2], 1e-3f)
    }

    @Test
    fun frontViewShowsTopAxisPointingUp() {
        // FRONT (rotX = 90°): the world +Z axis (the gear's own axis) maps to camera
        // +Y, so the +Z node sits ABOVE centre — the gear stays upright in front view.
        val nodes = GizmoMath.project(camera(rotX = 90f), 36f, 36f, 26f)
        val top = node(nodes, GizmoView.TOP)
        assertEquals(36f, top.x, 1e-3f)
        assertEquals(10f, top.y, 1e-3f)
    }

    @Test
    fun hitTestReturnsFrontMostNodeWithinRadius() {
        val nodes = GizmoMath.project(camera(), 36f, 36f, 26f)
        // +X node sits at (62, 36); a tap there (24dp hit radius) hits RIGHT.
        assertEquals(GizmoView.RIGHT, GizmoMath.hitTest(62f, 36f, nodes, 24f))
        // +Y node sits at (36, 10).
        assertEquals(GizmoView.FRONT, GizmoMath.hitTest(36f, 10f, nodes, 24f))
        // A tap well outside every node misses.
        assertNull(GizmoMath.hitTest(0f, 0f, nodes, 24f))
    }

    @Test
    fun hitTestPrefersTheFacingAxisWhenBothProjectToCentre() {
        // At identity the +Z (depth 1) and −Z (depth −1) nodes both sit at the centre;
        // the one facing the camera must win.
        val nodes = GizmoMath.project(camera(), 36f, 36f, 26f)
        assertEquals(GizmoView.TOP, GizmoMath.hitTest(36f, 36f, nodes, 24f))
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
