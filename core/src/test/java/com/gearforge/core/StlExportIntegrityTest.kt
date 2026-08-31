package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * STL export integrity (audit X2): builds complex parameter setups at HIGH
 * precision, validates the merged mesh exactly as the export path does, writes a
 * binary STL, verifies the format, and checks the bounding box against the
 * theoretical pitch/outer diameters. Assemblies additionally verify that the
 * mating parts align to the expected centre distances and clearances.
 *
 * Representation notes:
 *  - "bevel gear pair" is represented by a single bevel gear with a hub boss:
 *    the generator models one bevel body (a mated pair is not an assembly).
 *  - an internal ring's "outer flange" is its solid outer rim beyond the tooth
 *    root, which [GearBuilder.ringMesh] sizes from the module.
 */
class StlExportIntegrityTest {

    private fun high(p: GearParams): GearParams = p.copy(precision = PrecisionLevel.HIGH).coerced()

    /** Replicates ExportManager.validatedMesh (mesh build + fatal-issue gate). */
    private fun validated(p: GearParams): Mesh {
        val m = GearBuilder.merged(high(p))
        val fatal = MeshOps.validate(m).issues.filterNot { it.contains("duplicate vertices") }
        assertTrue("export validation failed: $fatal", fatal.isEmpty())
        return m
    }

    private fun assertStlFormat(mesh: Mesh, bytes: ByteArray) {
        assertEquals("STL length", 84 + 50 * mesh.triangles.size, bytes.size)
        val n = ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals("triangle count", mesh.triangles.size, n)
    }

    private fun bboxTolerance(theoretical: Double): Double = minOf(0.1, 0.005 * theoretical)

    private fun assertDiameter(label: String, mesh: Mesh, theoretical: Double, tol: Double) {
        val b = MeshOps.bounds(mesh)
        assertEquals("$label bbox x vs theoretical", theoretical, b.x, tol)
        assertEquals("$label bbox y vs theoretical", theoretical, b.y, tol)
    }

    @Test
    fun spokedHelicalKeyedBore() {
        val p = GearParams(
            gearType = GearType.HELICAL, module = 1.5, teeth = 24, helixAngleDeg = 20.0,
            thickness = 8.0, spokeCount = 6, spokeWidth = 4.0,
            bore = BoreSpec(type = BoreType.KEYWAY, diameter = 6.0, keywayWidth = 2.0, keywayDepth = 1.0)
        )
        val mesh = validated(p)
        val bytes = StlWriter.writeBinary(mesh)
        assertStlFormat(mesh, bytes)
        // Helical module is the NORMAL module m_n; the tooth profile is generated in
        // the transverse plane at m_t = m_n / cos β (audit L1). The generator treats
        // the transverse module as the profile module, so the theoretical outer
        // diameter follows m_t.
        val mt = p.module / kotlin.math.cos(Math.toRadians(p.helixAngleDeg))
        val outerDia = mt * (p.teeth + 2.0)
        assertDiameter("spoked helical", mesh, outerDia, bboxTolerance(outerDia))
    }

    @Test
    fun bevelWithHubBoss() {
        val p = GearParams(
            gearType = GearType.BEVEL, module = 1.5, teeth = 20,
            coneAngleDeg = 45.0, pitchConeDeg = 45.0, thickness = 6.0,
            hubDiameter = 12.0, hubLength = 8.0,
            bore = BoreSpec(type = BoreType.ROUND, diameter = 6.0)
        )
        val mesh = validated(p)
        val bytes = StlWriter.writeBinary(mesh)
        assertStlFormat(mesh, bytes)
        val outerDia = GearCalculator.outerDiameter(p.module, p.teeth)
        // Bevel back cone is the full-size profile; bbox must sit at/below the
        // theoretical outer diameter (taper only reduces the front face).
        val b = MeshOps.bounds(mesh)
        assertTrue("bevel bbox x ${b.x} <= $outerDia", b.x <= outerDia + 0.1)
        assertTrue("bevel bbox x ${b.x} > 0", b.x > 0.0)
    }

    @Test
    fun internalRingWithOuterFlange() {
        val p = GearParams(
            gearType = GearType.INTERNAL_RING, module = 1.5, teeth = 44, thickness = 6.0
        )
        val mesh = validated(p)
        val bytes = StlWriter.writeBinary(mesh)
        assertStlFormat(mesh, bytes)
        // The outer flange is the solid rim beyond the tooth root.
        val rim = maxOf(2.0, 2.0 * p.module)
        val outerDia = GearCalculator.pitchDiameter(p.module, p.teeth) + 2.5 * p.module + 2.0 * rim
        assertDiameter("internal ring", mesh, outerDia, bboxTolerance(outerDia))
    }

    @Test
    fun planetaryAssembly() {
        val p = GearParams(
            gearType = GearType.PLANETARY, module = 1.0, teeth = 12, planetTeeth = 12,
            planetCount = 4, ringTeeth = 36, thickness = 6.0
        )
        val mesh = validated(p)
        val bytes = StlWriter.writeBinary(mesh)
        assertStlFormat(mesh, bytes)
        // Parts must stay inside the ring and planets orbit at the meshing radius.
        val a = GearBuilder.planetary(high(p))
        val planetDist = GearCalculator.centerDistance(p.module, a.sunTeeth, a.planetTeeth)
        for (c in a.planetCenters) {
            assertEquals("planet centre radius", planetDist, kotlin.math.hypot(c.x, c.y), 1e-6)
        }
        val rim = maxOf(2.0, 2.0 * p.module)
        val ringOuterR = GearCalculator.pitchRadius(p.module, a.ringTeeth) + 1.25 * p.module + rim
        assertTrue("planets inside ring", planetDist + GearCalculator.outerRadius(p.module, a.planetTeeth) <= ringOuterR + 1e-6)
    }

    @Test
    fun throatedWormPair() {
        val p = GearParams(
            gearType = GearType.WORM_PAIR, module = 1.0, wormStarts = 1, wheelTeeth = 30,
            helixAngleDeg = 75.0, thickness = 10.0
        )
        val mesh = validated(p)
        val bytes = StlWriter.writeBinary(mesh)
        assertStlFormat(mesh, bytes)
        // Worm axis sits tangent to the wheel throat pitch circle.
        val a = GearBuilder.assembly(high(p))
        val rWheel = GearCalculator.pitchRadius(p.module, p.wheelTeeth)
        val wormTeeth = maxOf(4, p.wormStarts * 4)
        val rWorm = GearCalculator.pitchRadius(p.module, wormTeeth)
        val wormOffset = a.offsets[0]
        assertEquals("worm tangent y", rWheel + rWorm, wormOffset.y, 1e-6)
    }

    @Test
    fun timingBeltPulleyWithFlangesAndDBore() {
        val p = GearParams(
            gearType = GearType.BELT, beltProfile = "GT2",
            beltDriverTeeth = 20, beltDrivenTeeth = 40, beltWidthMm = 6.0,
            beltFlangeCount = 2,
            bore = BoreSpec(type = BoreType.D_CUT, diameter = 6.0, dCutFlatOffset = 1.0)
        )
        val mesh = validated(p)
        val bytes = StlWriter.writeBinary(mesh)
        assertStlFormat(mesh, bytes)
        val t = p.toBeltTransmission()
        val r = BeltCalculator.resolve(t)
        // Driver and driven are separated by the resolved centre distance
        // (assembly order: belt, driver, driven → offsets[2] is the driven centre).
        val a = GearBuilder.assembly(high(p))
        assertEquals("belt centre distance", r.centerDistanceMm, a.offsets[2].x, 1e-6)
        // The transmission carries the D-cut bore through to both pulleys …
        assertEquals("belt bore type", BoreType.D_CUT, t.bore.type)
        assertEquals("belt bore diameter", 6.0, t.bore.diameter, 1e-9)
        // … and the two retaining flanges widen each pulley beyond the belt face
        // width, so the merged assembly's Z extent must exceed the nominal width.
        val b = MeshOps.bounds(mesh)
        assertTrue("flanged pulley Z extent ${b.z} > belt width ${p.beltWidthMm}", b.z > p.beltWidthMm + 1.0)
    }
}
