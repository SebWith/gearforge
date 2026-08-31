package com.gearforge.core

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Defensive validation for the planetary ring geometry (audit ISSUE 3.2):
 * the ring profile/mesh must reject an inverted ring (inner radius >= outer radius)
 * instead of silently producing a degenerate mesh.
 */
class RingGeometryValidationTest {

    /**
     * Simulates a corrupted deserialized [GearParams] whose init-block invariant
     * (`module > 0`) has been bypassed, yielding an inverted ring where the tooth-tip
     * (inner) radius is >= the outer radius. The constructor cannot produce this state
     * directly, so the final `module` field is tampered with after construction — the
     * same way a naive deserializer would leave an un-validated field.
     */
    private fun corruptedRing(module: Double = -1.0, teeth: Int = 40): GearParams {
        val p = GearParams(module = 1.0, teeth = teeth)
        val field = GearParams::class.java.getDeclaredField("module")
        field.isAccessible = true
        field.setDouble(p, module)
        return p
    }

    @Test
    fun internalRingOutlineRejectsInvertedRadii() {
        assertThrows(IllegalArgumentException::class.java) {
            GearProfiles.internalRingOutline(corruptedRing())
        }
    }

    @Test
    fun ringMeshCoercesInvertedRadiiToValidMesh() {
        // ringMesh() coerces the parameters at the entry point (audit H1), so a
        // corrupted/inverted ring (module tampered to a negative value) is clamped to
        // a valid module instead of crashing or emitting a degenerate mesh.
        val mesh = GearBuilder.ringMesh(corruptedRing())
        assertTrue("ring mesh must be non-empty after coercion", mesh.triangles.isNotEmpty())
        assertTrue("ring mesh must be watertight after coercion",
            MeshOps.validate(mesh).issues.filterNot { it.contains("duplicate vertices") }.isEmpty())
    }

    @Test
    fun validRingStillBuildsNonEmptyOutline() {
        val outline = GearProfiles.internalRingOutline(
            GearParams(gearType = GearType.INTERNAL_RING, module = 1.0, teeth = 40)
        )
        assertTrue(outline.isNotEmpty())
    }
}
