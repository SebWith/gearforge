package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
}
