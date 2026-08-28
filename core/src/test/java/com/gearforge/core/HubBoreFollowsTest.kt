package com.gearforge.core

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the hub-bore-follow feature: when the shaft bore is non-round (e.g. D-cut)
 * and a hub is defined, the hub can optionally reuse the gear's bore profile instead
 * of staying a plain round cylinder.
 */
class HubBoreFollowsTest {

    private fun dcutHub(): GearParams = GearParams(
        gearType = GearType.SPUR,
        module = 1.0,
        teeth = 20,
        bore = BoreSpec(type = BoreType.D_CUT, diameter = 5.0, dCutFlatOffset = 1.0),
        hubDiameter = 12.0,
        hubLeftLength = 5.0,
        hubRightLength = 0.0
    )

    @Test
    fun toggleExposedForDcutWithHub() {
        val defs = GearSpec.fields(dcutHub())
        val keys = defs.filter { it.group == ParamGroup.HUB && it.kind == FieldKind.BOOLEAN }.map { it.key }
        assertTrue("Left hub toggle should be exposed: $keys", keys.contains("hub_left_bore_follows"))
        assertTrue("Right hub toggle should be hidden when right hub is 0: $keys", !keys.contains("hub_right_bore_follows"))
    }

    @Test
    fun toggleHiddenForRoundBore() {
        val defs = GearSpec.fields(dcutHub().copy(bore = BoreSpec(type = BoreType.ROUND, diameter = 5.0)))
        val keys = defs.filter { it.group == ParamGroup.HUB && it.kind == FieldKind.BOOLEAN }.map { it.key }
        assertTrue("No hub toggle for a round bore: $keys", keys.isEmpty())
    }

    @Test
    fun boolAccessorsRoundTrip() {
        val p = dcutHub()
        assertTrue(GearSpec.getBool(p, "hub_left_bore_follows"))
        val off = GearSpec.setBool(p, "hub_left_bore_follows", false)
        assertTrue(!GearSpec.getBool(off, "hub_left_bore_follows"))
    }

    @Test
    fun followsUsesProfileNotRound() {
        val follows = HubBuilder.build(dcutHub())
        val notFollows = HubBuilder.build(dcutHub().copy(hubLeftBoreFollowsShaft = false))
        // The D-cut hub hole carries the flat (more boundary vertices) while the
        // round hub hole is a plain circle, so the two meshes must differ.
        assertNotEquals(follows.vertices.size, notFollows.vertices.size)
    }
}
