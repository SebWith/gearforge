package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps [GearBuilder.bodyKeys] tied to the thing it describes.
 *
 * The viewport legend is positional: it walks the assembly and prints the key at the same index. If
 * the two lists drift, the app will confidently call the ring gear "Sun" — a wrong label that looks
 * like a rendering problem and would be debugged in the wrong file. So the counts and the names are
 * both checked against the real assembly.
 */
class AssemblyBodyKeysTest {

    @Test
    fun theKeyListHasOneEntryPerBodyForEveryGearType() {
        for (type in GearType.entries) {
            val params = GearSpec.defaults(type)
            val bodies = GearBuilder.assembly(params).meshes.size
            val keys = GearBuilder.bodyKeys(params).size
            assertEquals(
                "$type: the assembly has $bodies bodies but there are $keys names",
                bodies,
                keys
            )
        }
    }

    @Test
    fun everyKeyIsDistinctWhereTheBodiesAre() {
        // A hub is not a second kind of gear, so a gear with a hub has one name per *body* and the
        // names must still be usable as labels rather than repeating.
        val withHub = GearSpec.defaults(GearType.SPUR)
            .copy(hubDiameter = 20.0, hubLeftLength = 4.0, hubRightLength = 4.0)
        val keys = GearBuilder.bodyKeys(withHub)
        assertEquals(2, keys.size)
        assertEquals(listOf("body_gear", "body_hub"), keys)
    }

    @Test
    fun theHubAddsExactlyOneBodyAndItIsTheSecond() {
        val noHub = GearSpec.defaults(GearType.HELICAL).copy(hubDiameter = 0.0)
        val hub = GearSpec.defaults(GearType.HELICAL)
            .copy(hubDiameter = 25.0, hubLeftLength = 6.0, hubRightLength = 6.0)
        assertTrue("the fixture must not have a hub", !HubBuilder.hasHub(noHub))
        assertTrue("the fixture must have a hub", HubBuilder.hasHub(hub))
        assertEquals(1, GearBuilder.bodyKeys(noHub).size)
        assertEquals(2, GearBuilder.bodyKeys(hub).size)
        assertEquals("body_hub", GearBuilder.bodyKeys(hub)[1])
    }

    @Test
    fun theAssemblyFamiliesAreNamedByRole() {
        assertEquals(
            listOf("body_rack", "body_pinion"),
            GearBuilder.bodyKeys(GearSpec.defaults(GearType.RACK))
        )
        assertEquals(
            listOf("body_worm", "body_wheel"),
            GearBuilder.bodyKeys(GearSpec.defaults(GearType.WORM_PAIR))
        )
        assertEquals(
            listOf("body_belt", "body_driver", "body_driven"),
            GearBuilder.bodyKeys(GearSpec.defaults(GearType.BELT))
        )
        val planetary = GearBuilder.bodyKeys(
            GearSpec.defaults(GearType.PLANETARY).copy(planetCount = 4)
        )
        assertEquals(listOf("body_sun", "body_ring"), planetary.take(2))
        assertEquals(List(4) { "body_planet" }, planetary.drop(2))
    }
}
