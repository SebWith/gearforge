package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.PI
import kotlin.math.sin

/** Tests for the advanced parametric features: hub, grub screw, per-tooth, belt, analysis, export. */
class GearAdvancedTest {

    // ---- hub / boss / collar --------------------------------------------

    @Test
    fun hubLegacyFallback() {
        // Legacy hubLength only → split evenly left/right.
        val p = GearParams(hubLength = 10.0, hubDiameter = 14.0)
        assertEquals(5.0, GearCalculator.effectiveHubLeft(p), 1e-9)
        assertEquals(5.0, GearCalculator.effectiveHubRight(p), 1e-9)
        // Asymmetric overrides win over legacy.
        val q = p.copy(hubLeftLength = 2.0, hubRightLength = 6.0)
        assertEquals(2.0, GearCalculator.effectiveHubLeft(q), 1e-9)
        assertEquals(6.0, GearCalculator.effectiveHubRight(q), 1e-9)
    }

    @Test
    fun totalWidthIncludesHub() {
        val p = GearParams(thickness = 6.0, hubLeftLength = 2.0, hubRightLength = 4.0)
        assertEquals(12.0, GearCalculator.totalWidth(p), 1e-9)
    }

    @Test
    fun hubBuilderBuildsWhenPresent() {
        val p = GearParams(hubDiameter = 14.0, hubLeftLength = 4.0, hubRightLength = 4.0,
            bore = BoreSpec(type = BoreType.ROUND, diameter = 5.0))
        assertTrue(HubBuilder.hasHub(p))
        val hub = HubBuilder.build(p)
        assertTrue(hub.vertices.isNotEmpty())
        assertTrue(hub.triangles.isNotEmpty())
        // Assembly now carries the hub as a second mesh.
        val a = GearBuilder.assembly(p)
        assertTrue(a.meshes.size >= 2)
    }

    @Test
    fun hubBuilderEmptyWhenNoHub() {
        val p = GearParams(bore = BoreSpec(type = BoreType.ROUND, diameter = 5.0))
        assertFalse(HubBuilder.hasHub(p))
        assertTrue(HubBuilder.build(p).vertices.isEmpty())
        assertEquals(1, GearBuilder.assembly(p).meshes.size)
    }

    @Test
    fun hubChamferCutsOnlyTheFreeEndAtFortyFiveDegrees() {
        val base = GearParams(hubDiameter = 14.0, thickness = 6.0, hubChamfer = 1.0,
            bore = BoreSpec(type = BoreType.ROUND, diameter = 5.0))
        for (left in listOf(true, false)) {
            val params = if (left) base.copy(hubLeftLength = 4.0) else base.copy(hubRightLength = 4.0)
            val hub = HubBuilder.build(params)
            val freeZ = if (left) -4.0 else 10.0
            val baseZ = if (left) 0.0 else 6.0
            val shoulderZ = if (left) -3.0 else 9.0
            for ((height, radius) in listOf(freeZ to 6.0, shoulderZ to 7.0, baseZ to 7.0)) {
                val ring = hub.vertices.filter { abs(it.z - height) < 1e-9 }
                assertTrue("missing hub ring at $height", ring.isNotEmpty())
                assertEquals("outer radius at z=$height, left=$left", radius,
                    ring.maxOf { hypot(it.x, it.y) }, 1e-9)
                assertEquals("shaft bore at z=$height", 2.5, ring.minOf { hypot(it.x, it.y) }, 1e-9)
            }
            val plain = HubBuilder.build(params.copy(hubChamfer = 0.0))
            assertEquals(plain.vertices.minOf { it.z }, hub.vertices.minOf { it.z }, 1e-9)
            assertEquals(plain.vertices.maxOf { it.z }, hub.vertices.maxOf { it.z }, 1e-9)
            val removedVolume = 24.0 * sin(PI / 24.0) * (7.0 - 1.0 / 3.0)
            assertEquals(removedVolume, MeshOps.signedVolume(plain) - MeshOps.signedVolume(hub), 1e-8)
            assertEquals(hub.vertices, GearBuilder.assembly(params).meshes.last().vertices)
        }
    }

    @Test
    fun hubChamferPreservesAllBoreProfilesAtEverySlice() {
        for (bore in hubBoreCases()) {
            for ((leftFollows, rightFollows) in listOf(true to true, true to false, false to true, false to false)) {
                val params = GearParams(module = 2.0, teeth = 30, hubDiameter = 24.0,
                    hubLeftLength = 2.0, hubRightLength = 5.0, hubChamfer = 1.0, bore = bore,
                    hubLeftBoreFollowsShaft = leftFollows, hubRightBoreFollowsShaft = rightFollows)
                val hub = HubBuilder.build(params)
                assertClosedHub(hub)
                assertHubBoreProfiles(params, hub)
                val plain = HubBuilder.build(params.copy(hubChamfer = 0.0))
                assertEquals(48.0 * sin(PI / 24.0) * (12.0 - 1.0 / 3.0),
                    MeshOps.signedVolume(plain) - MeshOps.signedVolume(hub), 1e-7)
            }
        }
    }

    @Test
    fun hubChamferPreservesBoundsAtLengthAndDiameterLimits() {
        val base = GearParams(module = 3.0, teeth = 30, hubDiameter = 14.0)
        val cases = listOf(
            base.copy(hubLeftLength = 2.0),
            base.copy(hubRightLength = 3.0),
            base.copy(hubLeftLength = 2.0, hubRightLength = 5.0),
            base.copy(hubLength = 8.0),
            base.copy(hubLength = 40.0, hubLeftLength = 2.0, hubRightLength = 5.0),
            base.copy(hubDiameter = 60.0, hubLeftLength = 50.0, hubRightLength = 50.0),
            base.copy(hubDiameter = 60.0, hubLength = 40.0, bore = BoreSpec(diameter = 50.0)),
            base.copy(hubDiameter = 2.0, hubLeftLength = 0.01, bore = BoreSpec(type = BoreType.NONE)),
            base.copy(hubDiameter = 5.0, hubRightLength = 0.01, bore = BoreSpec(diameter = 1.0))
        )
        for (params in cases) {
            val left = GearCalculator.effectiveHubLeft(params)
            val right = GearCalculator.effectiveHubRight(params)
            val plain = HubBuilder.build(params)
            val maximum = minOf(20.0, HubBuilder.chamferLimit(params))
            for (chamfer in listOf(0.0, 0.01, maximum - 0.00001, maximum).distinct()) {
                val hub = HubBuilder.build(params.copy(hubChamfer = chamfer))
                assertClosedHub(hub)
                assertHubBoreProfiles(params, hub)
                val bounds = MeshOps.bounds(hub)
                assertEquals(params.hubDiameter, bounds.x, 1e-9)
                assertEquals(params.hubDiameter, bounds.y, 1e-9)
                assertEquals(plain.vertices.minOf { it.z }, hub.vertices.minOf { it.z }, 1e-9)
                assertEquals(plain.vertices.maxOf { it.z }, hub.vertices.maxOf { it.z }, 1e-9)
                val sideCount = listOf(left, right).count { it > 0.0 }
                val removedVolume = sideCount * 24.0 * sin(PI / 24.0) *
                    (params.hubDiameter / 2.0 * chamfer * chamfer - chamfer * chamfer * chamfer / 3.0)
                assertEquals(removedVolume, MeshOps.signedVolume(plain) - MeshOps.signedVolume(hub), 1e-7)
                for (freeZ in listOfNotNull((-left).takeIf { left > 0.0 },
                    (params.thickness + right).takeIf { right > 0.0 })) {
                    assertEquals(params.hubDiameter / 2.0 - chamfer,
                        hub.vertices.filter { abs(it.z - freeZ) < 1e-9 }.maxOf { hypot(it.x, it.y) }, 1e-9)
                }
            }
            assertEquals(left + params.thickness + right,
                MeshOps.bounds(GearBuilder.merged(params.copy(hubChamfer = maximum))).z, 1e-9)
        }
    }

    @Test
    fun hubChamferZeroPreservesTheOriginalExtrusion() {
        for (bore in hubBoreCases()) {
            val params = GearParams(hubDiameter = 24.0, hubLeftLength = 2.0, hubRightLength = 5.0,
                bore = bore, hubRightBoreFollowsShaft = false)
            val leftShape = PlanarShape(Bore.round(12.0), Bore.holes(params))
            val rightShape = PlanarShape(Bore.round(12.0),
                if (bore.type == BoreType.NONE) emptyList() else listOf(Bore.round(bore.diameter / 2.0)))
            val left = MeshBuilder.extrude(leftShape, 2.0)
            val right = MeshBuilder.extrude(rightShape, 5.0)
            val expected = MeshOps.merge(listOf(
                Mesh(left.vertices.map { Vec3(it.x, it.y, it.z - 2.0) }, left.triangles),
                Mesh(right.vertices.map { Vec3(it.x, it.y, it.z + params.thickness) }, right.triangles)
            ))
            val actual = HubBuilder.build(params)
            assertEquals(expected.triangles.map { triangle -> triangle.map { expected.vertices[it] } },
                actual.triangles.map { triangle -> triangle.map { actual.vertices[it] } })
            assertClosedHub(actual)
        }
        assertTrue(HubBuilder.build(GearParams(hubChamfer = 1.0)).vertices.isEmpty())
    }

    @Test
    fun hubChamferNormalsPointOutwardAtBothFreeEnds() {
        val params = GearParams(hubDiameter = 14.0, hubLeftLength = 2.0, hubRightLength = 5.0,
            hubChamfer = 1.0)
        val hub = HubBuilder.build(params)
        assertClosedHub(hub)
        for (triangle in hub.triangles) {
            val vertices = triangle.map { hub.vertices[it] }
            val normal = MeshOps.faceNormal(vertices[0], vertices[1], vertices[2])
            val center = (vertices[0] + vertices[1] + vertices[2]) * (1.0 / 3.0)
            if (vertices.all { abs(it.z - center.z) < 1e-9 }) {
                val expectedSign = if (abs(center.z + 2.0) < 1e-9 || abs(center.z - params.thickness) < 1e-9) -1.0 else 1.0
                assertEquals(expectedSign, normal.z, 1e-9)
            } else {
                val boreWall = vertices.all { abs(hypot(it.x, it.y) - 2.5) < 1e-9 }
                val radialNormal = normal.x * center.x + normal.y * center.y
                assertTrue("radial normal at $center", if (boreWall) radialNormal < 0.0 else radialNormal > 0.0)
                if (boreWall) assertEquals(0.0, normal.z, 1e-9)
                else if (abs(normal.z) > 1e-9) {
                    assertTrue("free-end normal at $center", if (center.z < 0.0) normal.z < 0.0 else normal.z > 0.0)
                }
            }
        }
    }

    @Test
    fun hubChamferCannotCutThroughExistingSetScrewHoles() {
        val base = GearParams(module = 2.0, teeth = 30, hubDiameter = 24.0,
            hubLeftLength = 12.0, hubRightLength = 12.0, setScrewCount = 2,
            setScrewAngleDeg = 17.0, setScrewAngle2Deg = 197.0)
        val limit = HubBuilder.chamferLimit(base)
        assertTrue(limit < HubBuilder.chamferLimit(base.copy(setScrewCount = 0)))
        assertFalse(GearSpec.validate(base.copy(hubChamfer = limit)).any { it.code == GearSpec.WARN_HUB_CHAMFER })
        assertTrue(GearSpec.validate(base.copy(hubChamfer = limit + 0.001))
            .any { it.code == GearSpec.WARN_HUB_CHAMFER && it.severity == GearSeverity.ERROR })
        for (chamfer in listOf(0.0, 0.5, limit, limit + 0.001)) {
            assertClosedHub(HubBuilder.build(base.copy(hubChamfer = chamfer)))
        }
    }

    private fun assertClosedHub(hub: Mesh) {
        assertTrue(hub.vertices.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
        val validation = MeshOps.validate(hub)
        assertTrue("hub mesh: ${validation.issues}", validation.isValid)
        val capTriangles = hub.triangles.map { triangle -> triangle.map { hub.vertices[it] } }
            .filter { vertices -> vertices.all { abs(it.z - vertices[0].z) < 1e-9 } }
        val capHeights = capTriangles.map { it[0].z }.distinct().sorted()
        for (vertices in capTriangles) {
            val expectedSign = if (capHeights.indexOf(vertices[0].z) % 2 == 0) -1.0 else 1.0
            assertEquals("cap normal at ${vertices[0].z}", expectedSign,
                MeshOps.faceNormal(vertices[0], vertices[1], vertices[2]).z, 1e-9)
        }
        val directedEdges = linkedMapOf<Pair<Int, Int>, Int>()
        for (triangle in hub.triangles) {
            for (index in 0..2) {
                val edge = triangle[index] to triangle[(index + 1) % 3]
                directedEdges[edge] = (directedEdges[edge] ?: 0) + 1
            }
        }
        for ((edge, count) in directedEdges) {
            assertEquals(1, count)
            assertEquals("opposite edge $edge", 1, directedEdges[edge.second to edge.first])
        }
    }

    private fun assertHubBoreProfiles(params: GearParams, hub: Mesh) {
        for ((height, ring) in hub.vertices.groupBy { it.z }) {
            val follows = if (height <= 0.0) params.hubLeftBoreFollowsShaft else params.hubRightBoreFollowsShaft
            val expected = if (params.bore.type == BoreType.NONE) emptyList()
                else if (follows) Bore.holes(params).flatten() else Bore.round(params.bore.diameter / 2.0)
            val maximumRadius = expected.maxOfOrNull { hypot(it.x, it.y) } ?: 0.0
            val actual = ring.filter { hypot(it.x, it.y) <= maximumRadius + 1e-9 }.map { Vec2(it.x, it.y) }
            assertTrue("missing bore at $height: ${params.bore}", expected.all { point -> actual.any { it.dist(point) < 1e-9 } })
            assertTrue("changed bore at $height: ${params.bore}", actual.all { point -> expected.any { it.dist(point) < 1e-9 } })
        }
    }

    // ---- grub screw -----------------------------------------------------

    @Test
    fun grubScrewAddsHoles() {
        val base = GearParams(hubDiameter = 14.0, hubLeftLength = 4.0, hubRightLength = 4.0,
            bore = BoreSpec(type = BoreType.ROUND, diameter = 5.0))
        val withScrew = base.copy(setScrewCount = 1, setScrewThread = "M3")
        // A screw through-hole adds boundary vertices to the extruded annulus.
        assertTrue(HubBuilder.build(withScrew).vertices.size > HubBuilder.build(base).vertices.size)
    }

    // ---- per-tooth overrides --------------------------------------------

    @Test
    fun perToothOverrideProducesValidMesh() {
        val p = GearParams(
            module = 1.0, teeth = 20,
            toothOverrides = mapOf(
                0 to ToothOverride(leftPressureAngleDeg = 14.5, rightPressureAngleDeg = 20.0, toothThickness = 1.2),
                7 to ToothOverride(addendumCoef = 1.4, dedendumCoef = 1.6)
            )
        )
        val mesh = GearBuilder.mesh(p)
        assertTrue(mesh.vertices.isNotEmpty())
        assertTrue(mesh.triangles.isNotEmpty())
        // The merged solid must still be watertight-ish (non-zero signed volume).
        assertTrue(abs(MeshOps.signedVolume(mesh)) > 0.0)
    }

    @Test
    fun toothThicknessCondition() {
        // A thicker tooth shrinks the gap, so effective backlash is reduced.
        val p = GearParams(module = 1.0, teeth = 20, backlash = 0.1,
            toothOverrides = mapOf(0 to ToothOverride(toothThickness = 1.8)))
        assertTrue(GearCalculator.effectiveBacklash(p) < 0.1)
    }

    // ---- validation -----------------------------------------------------

    @Test
    fun hubWallError() {
        val p = GearParams(hubDiameter = 6.0, hubLength = 6.0, bore = BoreSpec(type = BoreType.ROUND, diameter = 5.0))
        val warnings = GearSpec.validate(p)
        assertTrue(warnings.any { it.code == GearSpec.WARN_HUB_WALL && it.severity == GearSeverity.ERROR })
    }

    @Test
    fun hubChamferLimitUsesOnlyPresentProtrusions() {
        val base = GearParams(hubDiameter = 14.0)
        val cases = listOf(
            base.copy(hubLeftLength = 4.0) to 4.0,
            base.copy(hubRightLength = 4.0) to 4.0,
            base.copy(hubLeftLength = 2.0, hubRightLength = 4.0) to 2.0,
            base.copy(hubLength = 6.0) to 3.0,
            base.copy(hubLeftLength = 10.0) to (7.0 - 2.7 / cos(PI / 48.0))
        )
        for ((params, limit) in cases) {
            for (chamfer in listOf(0.0, 0.5, limit)) {
                assertFalse(
                    "chamfer $chamfer should fit the present hub: $params",
                    GearSpec.validate(params.copy(hubChamfer = chamfer))
                        .any { it.code == GearSpec.WARN_HUB_CHAMFER }
                )
            }
            assertTrue(
                "chamfer above $limit must remain an error: $params",
                GearSpec.validate(params.copy(hubChamfer = limit + 0.001))
                    .any { it.code == GearSpec.WARN_HUB_CHAMFER && it.severity == GearSeverity.ERROR }
            )
        }
    }

    @Test
    fun hubChamferRejectsBoreBreakthroughAndInvalidValues() {
        for (bore in hubBoreCases()) {
            for (follows in listOf(true, false)) {
                val params = GearParams(module = 2.0, teeth = 30, hubDiameter = 24.0,
                    hubLeftLength = 20.0, bore = bore, hubLeftBoreFollowsShaft = follows)
                val hole = if (bore.type == BoreType.NONE) emptyList()
                    else if (follows) Bore.holes(params).flatten() else Bore.round(bore.diameter / 2.0)
                val holeRadius = hole.maxOfOrNull { hypot(it.x, it.y) } ?: 0.0
                val limit = 12.0 - (holeRadius + 0.2) / cos(PI / 48.0)
                for (chamfer in listOf(0.0, 0.01, limit - 0.00001, limit)) {
                    assertFalse("valid chamfer $chamfer: $bore, follows=$follows",
                        GearSpec.validate(params.copy(hubChamfer = chamfer))
                            .any { it.code == GearSpec.WARN_HUB_CHAMFER })
                    val hub = HubBuilder.build(params.copy(hubChamfer = chamfer))
                    assertClosedHub(hub)
                    assertHubBoreProfiles(params, hub)
                }
                for (chamfer in listOf(limit + 0.00001, 12.0, -0.001, Double.NaN,
                    Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                    assertTrue("unsafe chamfer $chamfer: $bore, follows=$follows",
                        GearSpec.validate(params.copy(hubChamfer = chamfer))
                            .any { it.code == GearSpec.WARN_HUB_CHAMFER && it.severity == GearSeverity.ERROR })
                    assertClosedHub(HubBuilder.build(params.copy(hubChamfer = chamfer)))
                }
            }
        }
    }

    private fun hubBoreCases(): List<BoreSpec> = listOf(
        BoreSpec(type = BoreType.NONE),
        BoreSpec(type = BoreType.ROUND),
        BoreSpec(type = BoreType.D_CUT),
        BoreSpec(type = BoreType.D_CUT, dCutSecondFlat = true),
        BoreSpec(type = BoreType.KEYWAY),
        BoreSpec(type = BoreType.KEYWAY, diameter = 10.0, keywayStandard = true, keywayDepth = 9.0),
        BoreSpec(type = BoreType.HEX),
        BoreSpec(type = BoreType.SQUARE)
    )

    @Test
    fun hubCoversRootWarning() {
        val p = GearParams(module = 1.0, teeth = 20, hubDiameter = 20.0, hubLength = 6.0) // root Ø 17.5
        val warnings = GearSpec.validate(p)
        assertTrue(warnings.any { it.code == GearSpec.WARN_HUB_COVERS_ROOT })
    }

    @Test
    fun grubScrewRequiresHub() {
        val p = GearParams(setScrewCount = 1, hubDiameter = 0.0)
        val warnings = GearSpec.validate(p)
        assertTrue(warnings.any { it.code == GearSpec.WARN_GRUB_NO_HUB && it.severity == GearSeverity.ERROR })
    }

    @Test
    fun toothThicknessViolation() {
        val p = GearParams(module = 1.0, teeth = 20,
            toothOverrides = mapOf(3 to ToothOverride(toothThickness = 5.0))) // > π·m
        val warnings = GearSpec.validate(p)
        assertTrue(warnings.any { it.code == GearSpec.WARN_TOOTH_THICK })
    }

    // ---- analysis -------------------------------------------------------

    @Test
    fun analysisComputesMassAndInertia() {
        val p = GearParams(module = 1.0, teeth = 20, material = "Steel")
        val mesh = GearBuilder.merged(p)
        val r = GearAnalysis.analyze(mesh, p)
        assertTrue(r.weightKg > 0.0)
        assertTrue(r.momentOfInertia > 0.0)
        assertTrue(r.triangleCount > 0)
    }

    // ---- lightening / spokes --------------------------------------------

    @Test
    fun lighteningHolesAdded() {
        val p = GearParams(module = 2.0, teeth = 24, lighteningHoleCount = 6, lighteningHoleDiameter = 6.0)
        val holes = Bore.lighteningHoles(p)
        assertEquals(6, holes.size)
    }

    @Test
    fun spokeWedgesAdded() {
        val p = GearParams(module = 2.0, teeth = 24, hubDiameter = 14.0, spokeCount = 5, spokeWidth = 6.0)
        assertEquals(5, Bore.spokeWedgeHoles(p).size)
    }

    // ---- results registry -----------------------------------------------

    @Test
    fun resultsIncludeWeightInertiaBacklash() {
        val r = GearSpec.results(GearType.SPUR, GearParams(module = 1.0, teeth = 20))
        assertTrue(r.any { it.first == "result_weight" })
        assertTrue(r.any { it.first == "result_inertia" })
        assertTrue(r.any { it.first == "result_backlash" })
    }

    // ---- belt transmission ----------------------------------------------

    @Test
    fun beltPitchDiameterAndRatio() {
        assertEquals(20.0 * 2.0 / kotlin.math.PI, BeltCalculator.pitchDiameter(BeltProfile.GT2, 20), 1e-9)
        val t = BeltTransmission(driverTeeth = 20, drivenTeeth = 40)
        assertEquals(2.0, BeltCalculator.ratio(t), 1e-9)
    }

    @Test
    fun beltResolveProducesWholeTeeth() {
        val t = BeltTransmission(profile = BeltProfile.GT2, driverTeeth = 20, drivenTeeth = 40)
        val r = BeltCalculator.resolve(t)
        assertTrue(r.beltTeeth > 0)
        assertEquals(r.beltTeeth * r.pitchMm, r.beltLengthMm, 1e-6)
        assertTrue(r.centerDistanceMm > 0.0)
    }

    @Test
    fun beltPulleysBuild() {
        val t = BeltTransmission(profile = BeltProfile.GT2, driverTeeth = 20, drivenTeeth = 40)
        val a = BeltBuilder.assembly(t)
        assertTrue(a.driver.vertices.isNotEmpty())
        assertTrue(a.driven.vertices.isNotEmpty())
    }

    // ---- STEP / IGES export ---------------------------------------------

    @Test
    fun stepWriterProducesValidHeader() {
        val mesh = GearBuilder.merged(GearParams(module = 1.0, teeth = 12))
        val step = StepWriter.write(mesh)
        assertTrue(step.startsWith("ISO-10303-21;"))
        assertTrue(step.contains("END-ISO-10303-21;"))
        assertTrue(step.contains("CLOSED_SHELL"))
    }

    @Test
    fun igesWriterProducesSections() {
        val mesh = GearBuilder.merged(GearParams(module = 1.0, teeth = 12))
        val iges = IgesWriter.write(mesh)
        assertTrue(iges.contains("S      1"))
        assertTrue(iges.contains("T      1"))
        assertTrue(iges.contains("106"))
    }

    /**
     * The invariant the previous test could not see.
     *
     * `contains("106")` passes for a file whose records are the wrong length, which is how an
     * export with 16 834 overlong records shipped. IGES is a fixed-format interchange: every
     * record is exactly 80 columns, column 73 is the section letter and columns 74-80 are the
     * right-justified sequence number, restarting at 1 in every section. A file that breaks
     * this is rejected by CAD programs with no readable error, so the columns ARE the contract.
     */
    @Test
    fun igesRecordsAreExactlyEightyColumns() {
        val mesh = GearBuilder.merged(GearParams(module = 1.0, teeth = 12))
        val lines = IgesWriter.write(mesh).split("\n").filter { it.isNotEmpty() }
        assertTrue("the file must contain records", lines.isNotEmpty())

        lines.forEachIndexed { i, line ->
            assertEquals(
                "record ${i + 1} is ${line.length} columns; IGES requires exactly 80",
                80,
                line.length
            )
        }

        assertEquals(
            "column 73 must be a section letter",
            listOf('D', 'G', 'P', 'S', 'T'),
            lines.map { it[72] }.distinct().sorted()
        )

        for (section in listOf('S', 'G', 'D', 'P', 'T')) {
            val seqs = lines.filter { it[72] == section }.map { it.substring(73).trim().toInt() }
            assertEquals(
                "the $section section must be numbered 1..n with no gaps",
                (1..seqs.size).toList(),
                seqs
            )
        }
    }

    /** A parameter record longer than 72 columns must be continued, not stretched. */
    @Test
    fun igesSplitsLongParameterRecordsAcrossRecords() {
        val mesh = GearBuilder.merged(GearParams(module = 1.0, teeth = 12))
        val lines = IgesWriter.write(mesh).split("\n").filter { it.isNotEmpty() }
        val parameterRecords = lines.filter { it[72] == 'P' }
        val directoryRecords = lines.filter { it[72] == 'D' }

        // Two directory records per entity, and every entity's declared record count must add up
        // to the parameter section: the counts in the terminate line are what a reader trusts.
        val entityCount = directoryRecords.size / 2
        val declared = directoryRecords.filterIndexed { i, _ -> i % 2 == 1 }
            .sumOf { it.substring(24, 32).trim().toInt() }
        assertEquals(
            "the Directory Entries' record counts must match the parameter section exactly",
            parameterRecords.size,
            declared
        )

        // The terminate record is "S{7}G{7}D{7}P{7}": each count occupies its own 7 columns.
        val term = lines.last()
        assertEquals("T", term[72].toString())
        assertEquals(
            "the terminate line's P count must match the parameter section",
            parameterRecords.size,
            term.substring(25, 32).trim().toInt()
        )
        assertEquals(
            "the terminate line's D count must match the directory section",
            directoryRecords.size,
            term.substring(17, 24).trim().toInt()
        )
        assertTrue("a gear mesh has entities", entityCount > 0)
    }
}
