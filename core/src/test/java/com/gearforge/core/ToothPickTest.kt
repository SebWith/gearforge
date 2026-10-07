package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The tooth index under a tap must match the tooth the user is looking at, so these tests check the
 * index against the generated outline rather than against a phase constant: every tooth's own tip
 * has to resolve to its own index.
 *
 * A miss is reported as -1 by [toothAt], so a failure message reads "expected 5, was -1" instead of
 * "expected 5, was null".
 */
class ToothPickTest {

    private val supportedTypes = listOf(
        GearType.SPUR,
        GearType.HELICAL,
        GearType.CYCLOIDAL,
        GearType.COMPOUND,
        GearType.FACE_GEAR,
        GearType.SCREW_GEAR
    )

    private val unsupportedTypes = listOf(
        GearType.RACK,
        GearType.BELT,
        GearType.INTERNAL_RING,
        GearType.PLANETARY,
        GearType.BEVEL,
        GearType.HYPOID,
        GearType.WORM_PAIR,
        GearType.HARMONIC_DRIVE
    )

    private fun centres(p: GearParams): List<Double> =
        requireNotNull(ToothPick.toothCentreAngles(p)) { "no tooth centres for ${p.gearType}" }

    private fun tipRadius(p: GearParams): Double =
        GearBuilder.shape(p).outer.maxOf { hypot(it.x, it.y) }

    private fun pointAt(angle: Double, radius: Double) = Vec2(radius * cos(angle), radius * sin(angle))

    private fun toothAt(p: GearParams, point: Vec2): Int = ToothPick.indexAtPoint(point.x, point.y, p) ?: -1

    private fun toothAt(p: GearParams, ray: Ray): Int = ToothPick.indexAt(ray, p) ?: -1

    @Test
    fun supportIsClaimedOnlyForBodiesThatTurnAboutZ() {
        for (t in supportedTypes) assertTrue("expected $t to support picking", ToothPick.supports(t))
        for (t in unsupportedTypes) assertFalse("expected $t to refuse picking", ToothPick.supports(t))
    }

    @Test
    fun findsExactlyOneTipPerTooth() {
        for (t in supportedTypes) {
            val p = GearSpec.defaults(t)
            assertEquals("tip count of $t", p.teeth, centres(p).size)
        }
    }

    @Test
    fun toothCentresAreEquallySpaced() {
        for (t in supportedTypes) {
            val p = GearSpec.defaults(t)
            val c = centres(p)
            val pitch = 2.0 * PI / p.teeth
            for (i in c.indices) {
                val next = c[(i + 1) % c.size]
                // The *shortest* angular distance: a tooth centre on either side of +-PI is one
                // pitch apart, not a full revolution minus a pitch apart.
                var d = (c[i] - next) % (2.0 * PI)
                if (d < 0) d += 2.0 * PI
                if (d > PI) d = 2.0 * PI - d
                assertEquals("spacing around tooth $i of $t", pitch, d, 1e-6)
            }
        }
    }

    @Test
    fun everyToothResolvesToItsOwnIndex() {
        for (t in supportedTypes) {
            val p = GearSpec.defaults(t)
            val c = centres(p)
            val r = tipRadius(p) * 0.9
            for (i in c.indices) {
                assertEquals("tooth $i of $t", i, toothAt(p, pointAt(c[i], r)))
            }
        }
    }

    @Test
    fun theWholeTipLandMapsToTheSameTooth() {
        val p = GearSpec.defaults(GearType.SPUR)
        val c = centres(p)
        val pitch = 2.0 * PI / p.teeth
        val r = tipRadius(p) * 0.9
        // Just inside the midpoint between two teeth the nearer tooth must still win, and the index
        // must not wander back and forth within one tooth's own land.
        for (i in c.indices) {
            for (f in listOf(-0.4, -0.2, 0.0, 0.2, 0.4)) {
                assertEquals("tooth $i at $f of a pitch", i, toothAt(p, pointAt(c[i] + f * pitch, r)))
            }
        }
    }

    @Test
    fun oddToothCountsResolveAcrossTheAngleSeam() {
        // Nine teeth is the smallest count that reliably puts a tooth centre on each side of +-PI,
        // which is the only place a naive atan2 comparison can pick the neighbour instead.
        val p = GearSpec.defaults(GearType.SPUR).copy(teeth = 9)
        val c = centres(p)
        assertEquals("tooth count", 9, c.size)
        val r = tipRadius(p) * 0.9
        for (i in c.indices) {
            assertEquals("tooth $i across the seam", i, toothAt(p, pointAt(c[i], r)))
        }
        assertTrue("expected a tooth centre just below +PI", c.any { it > 2.0 && it <= PI })
        assertTrue("expected a tooth centre just above -PI", c.any { it < -2.0 && it >= -PI })
    }

    @Test
    fun rayIsIntersectedWithTheMidPlane() {
        val p = GearSpec.defaults(GearType.SPUR)
        val c = centres(p)
        val q = pointAt(c[5], tipRadius(p) * 0.9)
        val ray = Ray(Vec3(q.x, q.y, p.thickness * 3.0), Vec3(0.0, 0.0, -1.0))
        assertEquals("tooth under a ray from above", 5, toothAt(p, ray))
        assertEquals("mid-plane", p.thickness / 2.0, ToothPick.midPlaneOffset(p), 1e-12)
    }

    @Test
    fun tappingOffTheRimOrDownTheBoreYieldsNothing() {
        val p = GearSpec.defaults(GearType.SPUR)
        val angle = centres(p)[0]
        assertEquals(
            "a rim miss must not pick a tooth",
            -1,
            toothAt(p, pointAt(angle, tipRadius(p) * 1.25))
        )
        val drilled = p.copy(bore = BoreSpec(type = BoreType.ROUND, diameter = 8.0))
        assertEquals("a bore miss must not pick a tooth", -1, toothAt(drilled, pointAt(angle, 2.0)))
    }

    @Test
    fun raysThatCannotIdentifyAToothYieldNothing() {
        val p = GearSpec.defaults(GearType.SPUR)
        val mid = ToothPick.midPlaneOffset(p)
        // Looking exactly edge-on: the ray lies in the mid-plane and would touch every tooth at once.
        assertEquals(
            "an edge-on ray must not pick",
            -1,
            toothAt(p, Ray(Vec3(0.0, 0.0, mid), Vec3(1.0, 0.0, 0.0)))
        )
        // The mid-plane is behind the viewer.
        assertEquals(
            "a ray pointing away must not pick",
            -1,
            toothAt(p, Ray(Vec3(0.0, 0.0, -5.0), Vec3(0.0, 0.0, -1.0)))
        )
    }

    @Test
    fun unsupportedTypesNeverPick() {
        for (t in unsupportedTypes) {
            val p = GearSpec.defaults(t)
            assertNull("$t must not report tooth centres", ToothPick.toothCentreAngles(p))
            assertEquals(
                "$t must not pick a tooth",
                -1,
                toothAt(p, Ray(Vec3(0.0, 0.0, 50.0), Vec3(0.0, 0.0, -1.0)))
            )
            // A point at the pitch radius is inside every one of these bodies, so the refusal has to
            // come from the type gate and not from a radius test that happens to miss.
            val r = (p.module * p.teeth / 2.0).coerceAtLeast(5.0)
            assertEquals("$t must not pick a point", -1, toothAt(p, pointAt(0.0, r)))
        }
    }

    @Test
    fun pickingIsDeterministicAndIndependentOfRepeatedCalls() {
        val p = GearSpec.defaults(GearType.HELICAL).copy(helixAngleDeg = 25.0)
        val q = pointAt(centres(p)[3], tipRadius(p) * 0.95)
        // The same question, twenty times: the answer must not depend on internal state.
        for (attempt in 1..20) {
            assertEquals("attempt $attempt", 3, toothAt(p, q))
        }
    }
}
