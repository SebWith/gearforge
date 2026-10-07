package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot

/**
 * [`GearSpec.measures`] is the single source of truth for a gear's primary dimensions: the
 * results table and the 3D measurement HUD both render it.
 *
 * These tests pin two different things, deliberately:
 *
 *  1. **The physics** — the values must match the analytic diameters, including the
 *     transverse module for helical gears. That is independent of how the app consumes them.
 *  2. **The agreement** — the same quantity must not be reported differently by the table
 *     and by the HUD. Before this existed the three diameters were computed inline in
 *     `results()` and had no second consumer at all.
 */
class MeasureTest {

    private fun lengthOf(p: GearParams, key: String): Double? =
        GearSpec.measures(p)
            .firstOrNull { it.key == key && it.kind == GearSpec.MeasureKind.LENGTH }
            ?.value

    @Test
    fun everyGearTypeReportsFinitePositiveMeasurements() {
        for (type in GearType.entries) {
            val rows = GearSpec.measures(GearSpec.defaults(type))
            assertTrue("$type must report at least one row", rows.isNotEmpty())
            for (row in rows) {
                assertTrue("$type/${row.key} must be finite, was ${row.value}", row.value.isFinite())
                assertTrue("$type/${row.key} must not be negative, was ${row.value}", row.value >= 0.0)
            }
            assertTrue("$type must report a mass row", rows.any { it.key == "result_weight" })
            if (GearSpec.hasGearBody(type)) {
                val mass = rows.first { it.key == "result_weight" }
                assertTrue("$type mass must be positive, was ${mass.value}", mass.value > 0.0)
            }
        }
    }

    @Test
    fun spurDiametersMatchTheAnalyticValues() {
        // m = 1, z = 20, h_a* = 1.0, h_f* = 1.25, no shift → outer 22, pitch 20, root 17.5.
        val p = GearParams(gearType = GearType.SPUR, module = 1.0, teeth = 20)
        assertEquals(22.0, lengthOf(p, "result_outer_diameter")!!, 1e-9)
        assertEquals(20.0, lengthOf(p, "result_pitch_diameter")!!, 1e-9)
        assertEquals(17.5, lengthOf(p, "result_root_diameter")!!, 1e-9)
    }

    @Test
    fun helicalDiametersUseTheTransverseModule() {
        val beta = 20.0
        val p = GearParams(gearType = GearType.HELICAL, module = 1.0, teeth = 16, helixAngleDeg = beta)
        val mt = 1.0 / cos(Math.toRadians(beta))
        assertEquals(mt * 16, lengthOf(p, "result_pitch_diameter")!!, 1e-9)
        // Guard against the helper silently degrading to the normal module.
        assertTrue(
            "transverse pitch diameter must exceed the normal-module value",
            lengthOf(p, "result_pitch_diameter")!! > 16.0 + 1e-6
        )
    }

    /**
     * A screw gear is a pair of crossed helicals, so its teeth are measured in the transverse plane
     * exactly as a parallel helical's are — the plane [GearBuilder.mesh] lofts it in.
     *
     * Only the `HELICAL` case was converted here. At the app's own default (β = 45°, m = 1, z = 20)
     * that reported an outer diameter of 22.0 mm for a body whose tips are 31.1 mm apart, so the HUD's
     * leader line pointed inside the material and the results table agreed with the HUD while both
     * disagreed with the exporters — the same parameter set, two different gears.
     */
    @Test
    fun screwGearDiametersUseTheTransverseModuleAsWell() {
        for (beta in listOf(20.0, 45.0, 75.0)) {
            val p = GearParams(gearType = GearType.SCREW_GEAR, module = 1.0, teeth = 20, helixAngleDeg = beta)
            val mt = 1.0 / cos(Math.toRadians(beta))
            assertEquals("pitch diameter at β=$beta", mt * 20, lengthOf(p, "result_pitch_diameter")!!, 1e-9)
            assertEquals(
                "outer diameter at β=$beta",
                2.0 * mt * (20 / 2.0 + 1.0),
                lengthOf(p, "result_outer_diameter")!!,
                1e-9
            )
            assertTrue(
                "must exceed the normal-module value at β=$beta",
                lengthOf(p, "result_outer_diameter")!! > 22.0 + 1e-6
            )
        }
    }

    /**
     * The invariant in its strongest form: the reported diameter is the circle the shipped mesh
     * reaches. Stated against the mesh rather than against a formula, because the two agreeing with
     * each other is the whole point — a number that only agrees with its own derivation can still be
     * the wrong number, which is what the screw gear was.
     */
    @Test
    fun theReportedOuterDiameterIsTheCircleTheMeshReaches() {
        // Every single-gear type, because the four bugs this caught were each specific to a family:
        // the transverse module (helical, screw), the back-cone scale (bevel, hypoid) and the ring's
        // rim. A formula-only test compares a number with its own derivation and stays green through
        // all of them.
        val singleBodies = listOf(
            GearType.SPUR, GearType.HELICAL, GearType.CYCLOIDAL, GearType.FACE_GEAR,
            GearType.SCREW_GEAR, GearType.BEVEL, GearType.HYPOID, GearType.HARMONIC_DRIVE,
            GearType.COMPOUND
        )
        for (type in singleBodies) {
            val p = GearSpec.defaults(type)
            val tipRadius = GearBuilder.mesh(p).vertices.maxOf { hypot(it.x, it.y) }
            assertEquals(
                "$type: the reported outer diameter must be the mesh's own tip circle",
                tipRadius * 2.0,
                lengthOf(p, "result_outer_diameter")!!,
                1e-6
            )
        }

        // The ring names its outer diameter differently, because its outer edge is the rim.
        val ring = GearSpec.defaults(GearType.INTERNAL_RING)
        val ringOuter = GearBuilder.mesh(ring).vertices.maxOf { hypot(it.x, it.y) }
        assertEquals(
            "the ring's outer diameter is the circle its rim reaches",
            ringOuter * 2.0,
            lengthOf(ring, "result_outer_dia")!!,
            1e-6
        )

        // Fixtures the defaults do not exercise: a shifted profile and a steeper helix.
        for (p in listOf(
            GearParams(gearType = GearType.SPUR, module = 1.0, teeth = 20, profileShift = 0.5),
            GearParams(gearType = GearType.HELICAL, module = 1.0, teeth = 20, helixAngleDeg = 45.0)
        )) {
            val tipRadius = GearBuilder.mesh(p).vertices.maxOf { hypot(it.x, it.y) }
            assertEquals(
                "${p.gearType} (x=${p.profileShift}, β=${p.helixAngleDeg})",
                tipRadius * 2.0,
                lengthOf(p, "result_outer_diameter")!!,
                1e-6
            )
        }
    }

    @Test
    fun axialSizeIsFaceWidthForBodiesAndBeltWidthForBelts() {
        assertEquals(6.0, lengthOf(GearParams(gearType = GearType.SPUR), "hud_face_width")!!, 1e-9)
        val belt = GearSpec.defaults(GearType.BELT)
        assertEquals(belt.beltWidthMm, lengthOf(belt, "hud_belt_width")!!, 1e-9)
        assertNull("a belt drive has no gear face width", lengthOf(belt, "hud_face_width"))
    }

    @Test
    fun boreRowReportsTheAcrossFlatsDimensionForHexAndSquare() {
        val round = GearParams(gearType = GearType.SPUR, bore = BoreSpec(type = BoreType.ROUND, diameter = 8.0))
        assertEquals(8.0, lengthOf(round, "hud_bore")!!, 1e-9)

        val hex = round.copy(bore = BoreSpec(type = BoreType.HEX, hexAcrossFlats = 7.0))
        assertEquals(7.0, lengthOf(hex, "hud_bore")!!, 1e-9)

        val square = round.copy(bore = BoreSpec(type = BoreType.SQUARE, squareAcrossFlats = 6.0))
        assertEquals(6.0, lengthOf(square, "hud_bore")!!, 1e-9)

        assertNull("a disabled bore has no row", lengthOf(round.copy(bore = BoreSpec(type = BoreType.NONE)), "hud_bore"))
    }

    @Test
    fun typesThatCutNoBoreReportNoneEvenWhenOneIsDeclared() {
        for (type in listOf(GearType.RACK, GearType.BELT, GearType.INTERNAL_RING, GearType.WORM_PAIR)) {
            val p = GearSpec.defaults(type).copy(bore = BoreSpec(type = BoreType.ROUND, diameter = 5.0))
            assertNull("$type must not report a bore it does not cut", Bore.displayDiameter(p))
            assertNull("$type must not report a bore row", lengthOf(p, "hud_bore"))
        }
        assertTrue("a spur gear does cut a bore", Bore.cutsBore(GearSpec.defaults(GearType.SPUR)))
    }

    @Test
    fun compoundReportsItsTotalAxialHeight() {
        val p = GearSpec.defaults(GearType.COMPOUND)
        val expected = p.thickness + p.spacerHeight + p.stage2FaceWidth
        assertEquals(expected, lengthOf(p, "result_total_height")!!, 1e-9)
    }

    /**
     * The invariant the HUD depends on: for every key the two consumers share, the number on
     * the model and the number in the table must be the same number. Both round to three
     * decimals, hence the tolerance.
     */
    @Test
    fun hudRowsAndResultTableAgreeOnSharedNumbers() {
        var compared = 0
        for (type in GearType.entries) {
            val p = GearSpec.defaults(type)
            val table = GearSpec.results(type, p).toMap()
            for (row in GearSpec.measures(p)) {
                val printed = table[row.key] ?: continue
                val numeric = printed.substringBefore(' ').replace(',', '.').toDoubleOrNull() ?: continue
                assertEquals("table and HUD disagree for $type/${row.key}", row.value, numeric, 1e-3)
                compared++
            }
        }
        assertTrue("the comparison must actually have run", compared >= 10)
    }

    @Test
    fun measuresAreDeterministicAcrossCalls() {
        val p = GearSpec.defaults(GearType.HELICAL)
        assertEquals(GearSpec.measures(p), GearSpec.measures(p))
    }

    @Test
    fun circleRowsCarryAWorldSpaceAnchorRadius() {
        val p = GearParams(
            gearType = GearType.SPUR, module = 1.0, teeth = 20,
            bore = BoreSpec(type = BoreType.ROUND, diameter = 8.0)
        )
        fun anchor(key: String) = GearSpec.measures(p).first { it.key == key }.anchorRadiusMm

        assertEquals("outer radius", 11.0, anchor("result_outer_diameter")!!, 1e-9)
        assertEquals("pitch radius", 10.0, anchor("result_pitch_diameter")!!, 1e-9)
        assertEquals("root radius", 8.75, anchor("result_root_diameter")!!, 1e-9)
        assertEquals("bore radius", 4.0, anchor("hud_bore")!!, 1e-9)
        assertEquals("face width is drawn at the flank radius", 8.75, anchor("hud_face_width")!!, 1e-9)
        assertNull("mass has no circle to point at", anchor("result_weight"))
    }

    @Test
    fun anchorsStayInMillimetresWhenTheDisplayedValueIsInInches() {
        val p = GearParams(gearType = GearType.SPUR, module = 1.0, teeth = 20, unit = UnitSystem.INCH)
        val outer = GearSpec.measures(p).first { it.key == "result_outer_diameter" }
        assertEquals("the displayed value follows the unit system", 22.0 / 25.4, outer.value, 1e-9)
        assertEquals("the anchor must not, it is world geometry", 11.0, outer.anchorRadiusMm!!, 1e-9)
    }

    @Test
    fun nonCircularRowsDoNotClaimAnAnchor() {
        val rack = GearSpec.measures(GearSpec.defaults(GearType.RACK))
        assertNull("a rack bar has no circle", rack.first { it.key == "hud_face_width" }.anchorRadiusMm)
        assertNull(rack.first { it.key == "result_rack_length" }.anchorRadiusMm)

        val belt = GearSpec.measures(GearSpec.defaults(GearType.BELT))
        assertNull("the belt width is not on a circle", belt.first { it.key == "hud_belt_width" }.anchorRadiusMm)
    }

    /**
     * The 2D profile has to be the section the solid is lofted from.
     *
     * `shape` and `mesh` disagreed for the two helical families and for the internal ring, and the
     * consumers of `shape` are not decorative: the SVG and DXF exports, the scrub preview and the
     * outline a tap is resolved against all read it. So the app could export a screw gear 41 % smaller
     * than the one it displayed, and a ring as an external gear.
     */
    @Test
    fun theTwoDimensionalProfileIsTheSectionTheSolidIsLoftedFrom() {
        val fixtures = listOf(
            GearParams(gearType = GearType.SCREW_GEAR, module = 1.0, teeth = 20, helixAngleDeg = 45.0),
            GearParams(gearType = GearType.HELICAL, module = 1.0, teeth = 20, helixAngleDeg = 30.0),
            GearParams(gearType = GearType.INTERNAL_RING, module = 1.0, teeth = 44)
        )
        for (p in fixtures) {
            val profileTip = GearBuilder.shape(p).outer.maxOf { hypot(it.x, it.y) }
            val meshTip = GearBuilder.mesh(p).vertices.maxOf { hypot(it.x, it.y) }
            assertEquals(
                "${p.gearType}: the profile's outer radius must be the solid's",
                meshTip,
                profileTip,
                1e-6
            )
        }
    }

    /** A ring's 2D shape is a rim with the tooth profile as its hole — not an external gear. */
    @Test
    fun aRingsTwoDimensionalShapeIsARing() {
        val shape = GearBuilder.shape(GearSpec.defaults(GearType.INTERNAL_RING))
        val radii = shape.outer.map { hypot(it.x, it.y) }
        assertEquals("the rim is one circle", 0.0, radii.max() - radii.min(), 1e-9)
        assertEquals("the tooth profile is the shape's only hole", 1, shape.holes.size)
        val holeRadii = shape.holes[0].map { hypot(it.x, it.y) }
        assertTrue(
            "the hole must be the toothed contour, not a circle",
            holeRadii.max() - holeRadii.min() > 1.0
        )
    }

    /**
     * A tapered body's circles exist at one end of its face, and the HUD has to be told which.
     *
     * The anchor is projected at `anchorZMm`, so a bevel's tip circle drawn in the mid-plane lands
     * inside the material: the profile is generated on the back cone and scaled along the face, and at
     * mid-face the radius is smaller.
     */
    @Test
    fun taperedAnchorsArePlacedWhereTheirCircleExists() {
        for (type in listOf(GearType.BEVEL, GearType.HYPOID)) {
            val p = GearSpec.defaults(type)
            val rows = GearSpec.measures(p).filter { it.anchorRadiusMm != null && it.key.startsWith("result_") }
            assertTrue("$type must anchor something", rows.isNotEmpty())
            for (row in rows) {
                assertEquals(
                    "$type/${row.key} must be anchored on the front face, where the profile is",
                    0.0,
                    row.anchorZMm ?: Double.NaN,
                    1e-12
                )
            }
            val outer = rows.first { it.key == "result_outer_diameter" }
            val meshTip = GearBuilder.mesh(p).vertices.maxOf { hypot(it.x, it.y) }
            assertEquals(
                "$type: the outer anchor must sit on the tip circle the mesh actually has",
                meshTip,
                outer.anchorRadiusMm!!,
                1e-6
            )
        }

        // A straight body's circles are the same at every height, so it keeps the mid-plane.
        val spur = GearSpec.measures(GearParams(gearType = GearType.SPUR, module = 1.0, teeth = 20))
            .first { it.key == "result_outer_diameter" }
        assertNull("a spur's tip circle needs no height", spur.anchorZMm)
    }

    /**
     * A type that draws more than one body must measure more than one.
     *
     * These rows were missing, so the viewport showed a rack without its pinion, a planetary without
     * its sun, a worm pair without the worm, and a compound gear without its second stage — the
     * numbers beside the model described one body of two.
     */
    @Test
    fun everyBodyOfAnAssemblyIsMeasured() {
        val expected = mapOf(
            GearType.RACK to "result_pinion_pitch_dia",
            GearType.PLANETARY to "result_sun_pitch_dia",
            GearType.WORM_PAIR to "result_worm_pitch_dia",
            GearType.BELT to "result_center_distance",
            GearType.COMPOUND to "result_stage2_pitch_dia"
        )
        for ((type, key) in expected) {
            val p = GearSpec.defaults(type)
            assertTrue("$type must report $key on the model", GearSpec.measures(p).any { it.key == key })
            assertTrue("$type must report $key in the table", GearSpec.results(type, p).any { it.first == key })
        }
        // The planetary's planets are off the model's axis: they can be listed, never anchored.
        val planets = GearSpec.measures(GearSpec.defaults(GearType.PLANETARY))
            .first { it.key == "result_planet_pitch_dia" }
        assertNull("a planet's pitch circle is not the model's axis", planets.anchorRadiusMm)
    }

    /**
     * A hole the geometry cuts must be a hole the user can see and change.
     *
     * `HARMONIC_DRIVE` cut the model's default bore and reported it in the HUD, but its field list
     * omitted `boreFields`, so the hole was neither visible nor editable in the panel.
     */
    @Test
    fun everyTypeThatCutsABoreOffersItsField() {
        val checked = mutableListOf<GearType>()
        for (type in GearType.entries) {
            val p = GearSpec.defaults(type)
            if (!Bore.cutsBore(p)) continue
            checked += type
            assertTrue(
                "$type cuts a ${Bore.displayDiameter(p)} mm bore but its panel has no bore field",
                GearSpec.fields(p).any { it.key == "bore_type" }
            )
        }
        assertTrue("the check must have covered the boring types", checked.size >= 5)
    }

    /**
     * The reported ring is the ring that is built.
     *
     * `Zr = Zs + 2·Zp` is a meshing constraint, so the builder overrides a field value that violates
     * it. Reporting the field put the ring's pitch circle — and the HUD's leader line — on a circle
     * belonging to no ring in the scene.
     */
    @Test
    fun theReportedRingIsTheRingTheBuilderDraws() {
        val p = GearSpec.defaults(GearType.PLANETARY).copy(teeth = 20, planetTeeth = 12, ringTeeth = 40)
        val drawn = GearBuilder.planetary(p)
        assertEquals(
            "the constraint has one definition",
            GearCalculator.planetaryRingTeeth(20, 12),
            drawn.ringTeeth
        )
        val reported = GearSpec.measures(p).first { it.key == "result_ring_pitch_dia" }
        assertEquals(
            "the reported ring pitch diameter must be the built ring's",
            GearCalculator.pitchDiameter(p.module, drawn.ringTeeth),
            reported.value,
            1e-9
        )
        assertEquals(
            "and the anchor must be on that same circle",
            reported.value / 2.0,
            reported.anchorRadiusMm!!,
            1e-9
        )
    }
}
