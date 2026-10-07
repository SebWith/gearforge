package com.gearforge.core

import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the playback's numbers are gear mathematics and not drawing choices.
 *
 * The strongest test here is [bodyOrderMatchesTheAssemblyForEveryGearType]: the motions are applied
 * positionally, so a mismatch between the list and the assembly would attach a planet's speed to
 * the ring — a defect that would look like a rendering bug and be debugged in the wrong file.
 */
class MeshKinematicsTest {

    private val base = 1.0

    /**
     * The base rate is a product decision, so it is pinned rather than left to drift.
     *
     * The base is the **fastest** rate the viewport offers, and the only one it selects by itself;
     * the slower chips are fractions of it (0.5× is one revolution every two seconds). It used to be
     * the slowest step of the ladder — one revolution every four seconds, multiplied by 0.5×…4× —
     * which put the app's own default a quarter of the way up its own range and made every chip's
     * label a fraction of a rate that was not on screen. One revolution every twelve seconds was
     * rejected earlier still: fewer than two teeth went past per second and the mesh read as
     * motionless.
     */
    @Test
    fun theBaseRateIsOneRevolutionPerSecond() {
        assertEquals(2.0 * PI / 1.0, MeshKinematics.DEFAULT_SPEED_RAD_PER_S, 1e-12)
        assertEquals(1.0, 2.0 * PI / MeshKinematics.DEFAULT_SPEED_RAD_PER_S, 1e-9)
    }

    /**
     * A speed multiplier has to scale **every** body by the same factor.
     *
     * The viewport's speed chips multiply the base rate; if any rate were clamped, offset or
     * otherwise non-linear, the train would come apart the moment the user changed the speed — which
     * is precisely the moment they are watching it.
     *
     * The factors are the viewport's own ladder, expressed relative to the base (see
     * `PLAYBACK_SPEEDS`): the fastest step is 1 and the rest are halvings of it.
     */
    @Test
    fun aSpeedMultiplierScalesEveryBodyByTheSameFactor() {
        for (type in GearType.entries) {
            val params = GearSpec.defaults(type)
            val baseMotions = MeshKinematics.motions(params, MeshKinematics.DEFAULT_SPEED_RAD_PER_S)
            for (factor in listOf(0.125, 0.25, 0.5)) {
                val scaled = MeshKinematics.motions(params, MeshKinematics.DEFAULT_SPEED_RAD_PER_S * factor)
                assertEquals("$type: body count changed with the speed", baseMotions.size, scaled.size)
                for (i in baseMotions.indices) {
                    assertEquals(
                        "$type body $i spin at ${factor}x",
                        baseMotions[i].spinSpeed * factor,
                        scaled[i].spinSpeed,
                        1e-12
                    )
                    assertEquals(
                        "$type body $i orbit at ${factor}x",
                        baseMotions[i].orbitSpeed * factor,
                        scaled[i].orbitSpeed,
                        1e-12
                    )
                    assertEquals(
                        "$type body $i slide at ${factor}x",
                        baseMotions[i].slideSpeed * factor,
                        scaled[i].slideSpeed,
                        1e-12
                    )
                }
            }
        }
    }

    @Test
    fun bodyOrderMatchesTheAssemblyForEveryGearType() {
        for (type in GearType.entries) {
            val params = GearSpec.defaults(type)
            val bodies = GearBuilder.assembly(params).meshes.size
            val motions = MeshKinematics.motions(params, base).size
            assertEquals(
                "$type: the assembly has $bodies bodies but the kinematics describes $motions",
                bodies,
                motions
            )
        }
    }

    @Test
    fun anAssembledGearAndItsHubTurnTogether() {
        // A hub is part of the same solid. Any relative speed between them would make the model
        // tear itself apart on screen.
        val withHub = GearSpec.defaults(GearType.SPUR).copy(hubDiameter = 20.0, hubLeftLength = 4.0, hubRightLength = 4.0)
        assertTrue("the fixture must actually have a hub", HubBuilder.hasHub(withHub))
        val motions = MeshKinematics.motions(withHub, base)
        assertEquals(2, motions.size)
        assertEquals(motions[0].spinSpeed, motions[1].spinSpeed, 0.0)
        assertTrue("the hub must not orbit", motions.all { it.orbitSpeed == 0.0 })
    }

    @Test
    fun bothPlanetaryMeshesHoldAtOnce() {
        // Zs = 20, Zp = 12 → Zr = Zs + 2·Zp = 44, which is the constraint the builder applies.
        val params = GearSpec.defaults(GearType.PLANETARY).copy(teeth = 20, planetTeeth = 12, planetCount = 3)
        val sunTeeth = 20
        val planetTeeth = 12
        val ringTeeth = sunTeeth + 2 * planetTeeth
        val motions = MeshKinematics.motions(params, base)
        assertEquals(2 + 3, motions.size)

        val sun = motions[0].spinSpeed
        val ring = motions[1].spinSpeed
        val carrier = motions[2].orbitSpeed
        val planet = motions[2].spinSpeed

        assertEquals("the ring is held", 0.0, ring, 1e-12)
        assertEquals("carrier ω = ωs·Zs/(Zs+Zr)", base * sunTeeth / 64.0, carrier, 1e-12)

        // Sun–planet is an external mesh: the surface speeds are equal and opposite.
        val sunSide = (sun - carrier) * sunTeeth
        val planetSide = -(planet - carrier) * planetTeeth
        assertEquals("sun/planet mesh", sunSide, planetSide, 1e-9)

        // Planet–ring is an internal mesh: the surface speeds are equal in sign.
        val ringSide = (ring - carrier) * ringTeeth
        val planetOuterSide = (planet - carrier) * planetTeeth
        assertEquals("planet/ring mesh", ringSide, planetOuterSide, 1e-9)
    }

    @Test
    fun everyPlanetGetsTheSameMotion() {
        val params = GearSpec.defaults(GearType.PLANETARY).copy(teeth = 20, planetTeeth = 12, planetCount = 4)
        val planets = MeshKinematics.motions(params, base).drop(2)
        assertEquals("one motion per planet", 4, planets.size)
        assertEquals("planets must not differ from each other", 1, planets.distinct().size)
        assertTrue("a planet must be carried around the sun", planets[0].orbitSpeed != 0.0)
        assertTrue("a planet must also spin about its own axis", planets[0].spinSpeed != 0.0)
    }

    @Test
    fun anOpenBeltTurnsBothPulleysTheSameWay() {
        val params = GearSpec.defaults(GearType.BELT)
        val t = params.toBeltTransmission()
        val motions = MeshKinematics.motions(params, base)
        assertEquals(3, motions.size)
        assertEquals("the band does not rotate", 0.0, motions[0].spinSpeed, 0.0)

        val driver = motions[1].spinSpeed
        val driven = motions[2].spinSpeed
        assertTrue(
            "a crossed belt is not what this app models: both pulleys must turn the same way",
            driver * driven > 0.0
        )
        assertEquals(
            "the ratio is the inverse of the teeth ratio",
            t.driverTeeth.toDouble() / t.drivenTeeth,
            driven / driver,
            1e-12
        )
    }

    @Test
    fun aRackTravelsAtThePitchLineSpeed() {
        val params = GearSpec.defaults(GearType.RACK)
        val motions = MeshKinematics.motions(params, base)
        assertEquals(2, motions.size)
        val rack = motions[0]
        val pinion = motions[1]
        assertEquals("a rack does not rotate", 0.0, rack.spinSpeed, 0.0)
        assertEquals("the pinion drives", base, pinion.spinSpeed, 1e-12)
        assertEquals(
            "v = ω·r at the pitch line",
            pinion.spinSpeed * GearCalculator.pitchRadius(params.module, params.pinionTeeth),
            rack.slideSpeed,
            1e-12
        )
    }

    @Test
    fun aWormDriveTurnsAboutItsOwnAxisAndTheWheelFollowsTheStarts() {
        val params = GearSpec.defaults(GearType.WORM_PAIR).copy(wormStarts = 2, wheelTeeth = 30)
        val motions = MeshKinematics.motions(params, base)
        assertEquals(2, motions.size)
        assertTrue("the worm's screw axis runs along X", motions[0].aboutX)
        assertTrue("the wheel turns about Z", !motions[1].aboutX)
        assertEquals(
            "a two-start worm advances the wheel by two teeth per revolution",
            2.0 / 30.0,
            motions[1].spinSpeed / motions[0].spinSpeed,
            1e-12
        )
    }

    @Test
    fun aStoppedPlaybackProducesNoMotionAtAll() {
        // Guards the pause path: pause has to reach every body, including an orbit or a sliding
        // rack, or a single body would keep creeping while the rest stand still.
        for (type in GearType.entries) {
            val motions = MeshKinematics.motions(GearSpec.defaults(type), 0.0)
            assertTrue(
                "$type keeps moving at zero base speed: $motions",
                motions.all { it.spinSpeed == 0.0 && it.orbitSpeed == 0.0 && it.slideSpeed == 0.0 }
            )
        }
    }

    @Test
    fun aSingleGearTurnsInOnePiece() {
        val motions = MeshKinematics.motions(GearSpec.defaults(GearType.SPUR), base)
        assertEquals(1, motions.size)
        assertEquals(base, motions[0].spinSpeed, 1e-12)
        assertTrue(motions[0].orbitSpeed == 0.0 && motions[0].slideSpeed == 0.0)
    }
}
