package com.gearforge.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-tooth marker the viewport draws for an overridden tooth — and for the tooth the user just
 * tapped, where it is the only feedback that the tap landed where they aimed.
 *
 * The marker used to be placed from the profile's *nominal* phase (`2π·i/n`) and the *unmodified*
 * radii. Neither is a property of the tooth it marks: the tip arc of a tooth runs from
 * `c − θ_L` to `c + θ_R`, so asymmetric flank angles move its centre by `(θ_R − θ_L)/2`, and a
 * profile shift or a raised addendum moves it radially. On a gear with one tooth given a 14°/30°
 * flank pair the nominal placement is 1.4° off centre — over a twentieth of a tooth pitch — so the
 * marker could sit beside the tooth, while [ToothPick] refuses to name a tooth it cannot read back
 * from the outline.
 *
 * These tests therefore compare the marker with the outline and with the picker, not with a constant.
 */
class ToothHighlightTest {

    /** Outermost radius of the marker for [index], and the mean direction of its outermost points. */
    private fun marker(p: GearParams, index: Int): Pair<Double, Double> {
        val mesh = GearBuilder.toothHighlightMesh(p, index)
        val radii = mesh.vertices.map { hypot(it.x, it.y) }
        val tip = radii.max()
        var sx = 0.0
        var sy = 0.0
        for (v in mesh.vertices) {
            val r = hypot(v.x, v.y)
            if (r >= tip - 1e-9) {
                sx += v.x / r
                sy += v.y / r
            }
        }
        return tip to atan2(sy, sx)
    }

    /** Smallest angle between two directions, in radians. */
    private fun separation(a: Double, b: Double): Double {
        val twoPi = 2.0 * PI
        val d = abs(a - b) % twoPi
        return if (d > PI) twoPi - d else d
    }

    /** Tip radius the generated outline actually reaches, or 0 when it has no such tooth. */
    private fun outlineTipRadius(p: GearParams): Double =
        GearBuilder.shape(p).outer.maxOf { hypot(it.x, it.y) }

    @Test
    fun theMarkerIsCentredOnTheToothThePickerNames() {
        val fixtures = listOf(
            GearParams(gearType = GearType.SPUR, module = 1.0, teeth = 20),
            GearParams(gearType = GearType.SPUR, module = 1.0, teeth = 17, profileShift = 0.6),
            GearParams(gearType = GearType.HELICAL, module = 1.0, teeth = 24, helixAngleDeg = 25.0),
            GearParams(
                gearType = GearType.SPUR,
                module = 1.0,
                teeth = 20,
                toothOverrides = mapOf(6 to ToothOverride(leftPressureAngleDeg = 14.0, rightPressureAngleDeg = 30.0))
            )
        )
        for (p in fixtures) {
            val centres = requireNotNull(ToothPick.toothCentreAngles(p)) { "no tooth centres for $p" }
            for (i in centres.indices) {
                val (_, markerAngle) = marker(p, i)
                assertEquals(
                    "${p.gearType} tooth $i: the marker must sit on the tooth the picker names",
                    centres[i],
                    markerAngle,
                    1e-6
                )
            }
        }
    }

    /**
     * The fixture above has to be one the nominal phase would have got *wrong*, or the test above
     * would pass on the old arithmetic too.
     */
    @Test
    fun anAsymmetricToothIsOneTheNominalPhaseWouldHaveMissed() {
        val teeth = 20
        val i = 6
        val p = GearParams(
            gearType = GearType.SPUR,
            module = 1.0,
            teeth = teeth,
            toothOverrides = mapOf(i to ToothOverride(leftPressureAngleDeg = 14.0, rightPressureAngleDeg = 30.0))
        )
        val picked = requireNotNull(ToothPick.toothCentreAngles(p))[i]
        val nominal = 2.0 * PI * i / teeth
        val offset = separation(picked, nominal)
        assertTrue(
            "the fixture must move the tooth centre measurably (offset was ${Math.toDegrees(offset)}°)",
            offset > Math.toRadians(0.5)
        )
        assertEquals("and the marker must follow the tooth, not the phase", picked, marker(p, i).second, 1e-6)
    }

    @Test
    fun theMarkerReachesTheTipTheProfileActuallyHas() {
        // A raised addendum and a positive shift both move the tip outwards; the marker has to be
        // built from the profile that results, or it stops short of the tooth it is marking.
        val fixtures = listOf(
            GearParams(gearType = GearType.SPUR, module = 1.0, teeth = 18, profileShift = 0.6),
            GearParams(gearType = GearType.SPUR, module = 1.0, teeth = 18, addendumCoef = 1.35),
            GearParams(gearType = GearType.SPUR, module = 1.0, teeth = 18, profileShift = -0.4, dedendumCoef = 1.4)
        )
        for (p in fixtures) {
            val tipRadius = outlineTipRadius(p)
            for (i in 0 until p.teeth) {
                // The marker is deliberately a little larger than the tooth (it is drawn over it),
                // so the invariant is one-sided: it must at least reach the tip.
                assertTrue(
                    "$p tooth $i: the marker reaches ${marker(p, i).first} but the tip is at $tipRadius",
                    marker(p, i).first >= tipRadius - 1e-9
                )
            }
        }
    }

    /**
     * A crossed-helical body is generated from the transverse module, so its marker must be too.
     *
     * Measured against the mesh rather than against a formula: the point of the check is that the
     * marker and the geometry the renderer uploads agree, whatever the module convention is called.
     */
    @Test
    fun aScrewGearsMarkerIsMeasuredInTheSamePlaneAsItsMesh() {
        val p = GearParams(gearType = GearType.SCREW_GEAR, module = 1.0, teeth = 20, helixAngleDeg = 45.0)
        val meshTip = GearBuilder.mesh(p).vertices.maxOf { hypot(it.x, it.y) }
        for (i in 0 until p.teeth) {
            val markerTip = marker(p, i).first
            // The marker is drawn half a millimetre past the tip; anything further means it was
            // measured in the normal module's plane instead of the transverse one.
            assertTrue(
                "tooth $i: marker at $markerTip, mesh tip at $meshTip",
                markerTip >= meshTip && markerTip <= meshTip + 0.5 + 1e-6
            )
        }
    }

    @Test
    fun aMarkerNeverSpillsOntoItsNeighbour() {
        for (p in listOf(
            GearParams(gearType = GearType.SPUR, module = 1.0, teeth = 9),
            GearParams(gearType = GearType.SPUR, module = 2.0, teeth = 40),
            GearParams(gearType = GearType.CYCLOIDAL, module = 1.0, teeth = 12)
        )) {
            val pitch = 2.0 * PI / p.teeth
            for (i in 0 until p.teeth) {
                val (_, angle) = marker(p, i)
                val centre = requireNotNull(ToothPick.toothCentreAngles(p))[i]
                assertTrue(
                    "${p.teeth} teeth: the marker for tooth $i reaches past half a pitch",
                    separation(angle, centre) < pitch / 2.0
                )
            }
        }
    }
}
