package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.abs

class GearCoreTest {

    private fun spur(module: Double = 1.0, teeth: Int = 20) = GearParams(
        module = module, teeth = teeth, bore = BoreSpec(type = BoreType.ROUND, diameter = 5.0)
    )

    private fun noBore(module: Double = 1.0, teeth: Int = 20) = GearParams(
        module = module, teeth = teeth, bore = BoreSpec(type = BoreType.NONE)
    )

    private fun littleInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    // ---- GearCalculator math ---------------------------------------------

    @Test
    fun calculatorDiameters() {
        assertEquals(20.0, GearCalculator.pitchDiameter(1.0, 20), 1e-9)
        assertEquals(22.0, GearCalculator.outerDiameter(1.0, 20), 1e-9)
        assertEquals(17.5, GearCalculator.rootDiameter(1.0, 20), 1e-9)
        assertEquals(22.0, GearCalculator.centerDistance(1.0, 20, 24), 1e-9)
        assertEquals(2.0, GearCalculator.gearRatio(12, 24), 1e-9)
        assertEquals(36, GearCalculator.ringTeeth(12, 12))
        assertEquals(4.0, GearCalculator.planetaryRatioFixedRing(12, 36), 1e-9)
    }

    @Test
    fun calculatorDerivedRadii() {
        assertEquals(10.0, GearCalculator.pitchRadius(1.0, 20), 1e-9)
        assertEquals(11.0, GearCalculator.outerRadius(1.0, 20), 1e-9)
        assertEquals(8.75, GearCalculator.rootRadius(1.0, 20), 1e-9)
        val expectedBase = 20.0 * kotlin.math.cos(Math.toRadians(20.0)) / 2.0
        assertEquals(expectedBase, GearCalculator.baseRadius(1.0, 20, 20.0), 1e-9)
        // involute(0) = tan(0) - 0 = 0
        assertEquals(0.0, GearCalculator.involute(0.0), 1e-9)
        // pressure angle at the base radius is zero
        assertEquals(0.0, GearCalculator.pressureAngleAt(10.0, 10.0), 1e-9)
    }

    @Test
    fun unitConversionRoundTrip() {
        val dp = GearCalculator.moduleToDiametralPitch(2.0)
        assertEquals(25.4 / 2.0, dp, 1e-9)
        assertEquals(2.0, GearCalculator.diametralPitchToModule(dp), 1e-9)
    }

    @Test
    fun gearSpecSetGetNumberRoundTrip() {
        val p = GearSpec.setNumber(GearParams(), "module", 2.5)
        assertEquals(2.5, GearSpec.getNumber(p, "module"), 1e-9)
        val p2 = GearSpec.setNumber(p, "teeth", 32.0)
        assertEquals(32, p2.teeth)
        assertEquals(32.0, GearSpec.getNumber(p2, "teeth"), 1e-9)
    }

    // ---- GearSpec validation (point 3) ------------------------------------

    @Test
    fun validationDefaultsHaveNoWarnings() {
        GearType.entries.forEach { t ->
            val warnings = GearSpec.validate(GearSpec.defaults(t))
            assertTrue("default for $t should have no warnings but got $warnings", warnings.isEmpty())
        }
    }

    @Test
    fun validationPlanetaryRingTooSmall() {
        val p = GearParams(gearType = GearType.PLANETARY, teeth = 12, planetTeeth = 12, ringTeeth = 30)
        assertTrue(GearSpec.validate(p).any { it.code == GearSpec.WARN_RING_TEETH })
    }

    @Test
    fun validationModuleTooSmall() {
        val p = GearParams(module = 0.05, teeth = 20)
        assertTrue(GearSpec.validate(p).any { it.code == GearSpec.WARN_MODULE })
    }

    @Test
    fun validationPlanetOverlap() {
        val p = GearParams(gearType = GearType.PLANETARY, teeth = 12, planetTeeth = 30, ringTeeth = 72, planetCount = 6)
        assertTrue(GearSpec.validate(p).any { it.code == GearSpec.WARN_PLANET_OVERLAP })
    }

    @Test
    fun validationHelixTooSteep() {
        val p = GearParams(gearType = GearType.HELICAL, helixAngleDeg = 89.0, teeth = 16)
        assertTrue(GearSpec.validate(p).any { it.code == GearSpec.WARN_HELIX_ANGLE })
    }

    @Test
    fun validationBoreTooLarge() {
        val p = GearParams(teeth = 5, bore = BoreSpec(type = BoreType.ROUND, diameter = 10.0))
        assertTrue(GearSpec.validate(p).any { it.code == GearSpec.WARN_BORE })
    }

    @Test
    fun setNumberTriggersValidation() {
        val base = GearParams(gearType = GearType.PLANETARY, teeth = 12, planetTeeth = 12, ringTeeth = 44)
        val changed = GearSpec.setNumber(base, "ring_teeth", 20.0)
        assertEquals(20, changed.ringTeeth)
        assertTrue(GearSpec.validate(changed).any { it.code == GearSpec.WARN_RING_TEETH })
    }

    // ---- profile / mesh correctness ---------------------------------------

    @Test
    fun profileShiftWidensTeeth() {
        val base = GearProfiles.involuteSpur(noBore(1.0, 20).copy(profileShift = 0.0))
        val shifted = GearProfiles.involuteSpur(noBore(1.0, 20).copy(profileShift = 0.4))
        assertEquals(base.size, shifted.size)
        // A positive shift must change the profile (thicker teeth), never crash.
        assertTrue(base.zip(shifted).any { (a, b) -> a.dist(b) > 1e-6 })
    }

    @Test
    fun involuteProfileBounds() {
        val outline = GearProfiles.involuteSpur(noBore(1.0, 20))
        assertTrue(outline.size > 100)
        val rMax = outline.maxOf { kotlin.math.hypot(it.x, it.y) }
        val rMin = outline.minOf { kotlin.math.hypot(it.x, it.y) }
        assertEquals(11.0, rMax, 0.05)
        assertEquals(8.75, rMin, 0.20)
        assertTrue(Triangulate.signedArea(outline) > 0.0)
    }

    @Test
    fun spurVolumeMatchesProfileArea() {
        val p = noBore(1.0, 20)
        val mesh = GearBuilder.mesh(p)
        assertWatertight(mesh)
        val expectedArea = Triangulate.signedArea(GearProfiles.externalOutline(p))
        val expectedVolume = expectedArea * p.thickness
        assertEquals(expectedVolume, MeshOps.signedVolume(mesh), expectedVolume * 0.01)
    }

    @Test
    fun spurWithBoreVolume() {
        val p = spur(1.0, 20)
        val mesh = GearBuilder.mesh(p)
        assertWatertight(mesh)
        val areaOuter = Triangulate.signedArea(GearProfiles.externalOutline(p))
        val areaBore = Triangulate.signedArea(Bore.holes(p).first())
        val expected = (areaOuter - areaBore) * p.thickness
        assertEquals(expected, MeshOps.signedVolume(mesh), expected * 0.02)
    }

    @Test
    fun spurWithKeywayWatertight() {
        val p = spur(1.0, 20).copy(bore = BoreSpec(type = BoreType.KEYWAY, diameter = 6.0))
        assertWatertight(GearBuilder.mesh(p))
    }

    @Test
    fun helicalWatertight() {
        val p = noBore(1.0, 16).copy(gearType = GearType.HELICAL, helixAngleDeg = 15.0)
        assertWatertight(GearBuilder.mesh(p))
    }

    @Test
    fun bevelWatertight() {
        val p = noBore(1.0, 20).copy(gearType = GearType.BEVEL)
        assertWatertight(GearBuilder.mesh(p))
    }

    @Test
    fun ringWatertight() {
        assertWatertight(GearBuilder.ringMesh(spur(1.0, 40)))
    }

    @Test
    fun rackWatertight() {
        assertWatertight(GearBuilder.mesh(noBore(1.0, 10).copy(gearType = GearType.RACK)))
    }

    @Test
    fun planetaryAssembly() {
        // A consistent planetary set: ring = sun + 2*planet (12 + 2*8 = 28).
        val a = GearBuilder.planetary(spur(1.0, 12).copy(planetTeeth = 8, ringTeeth = 28))
        assertEquals(28, a.ringTeeth)
        assertEquals(3, a.planets.size)
        assertWatertight(a.sun)
        assertWatertight(a.ring)
        a.planets.forEach { assertWatertight(it) }
    }

    // ---- file-format roundtrips (point 5) ---------------------------------

    @Test
    fun stlBinary() {
        val mesh = GearBuilder.mesh(spur(1.0, 20))
        val bytes = StlWriter.writeBinary(mesh)
        assertEquals(84 + mesh.triangles.size * 50, bytes.size)
        val n = littleInt(bytes, 80)
        assertEquals(mesh.triangles.size, n)
    }

    @Test
    fun stlRoundTripParseFirstTriangle() {
        val mesh = GearBuilder.mesh(spur(1.0, 20))
        val bytes = StlWriter.writeBinary(mesh)
        assertEquals(mesh.triangles.size, littleInt(bytes, 80))
        // First triangle: normal + three vertices = 12 little-endian floats from offset 84.
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        floats.position(84)
        for (i in 0 until 12) {
            assertTrue("STL vertex/normal must be finite", floats.getFloat().isFinite())
        }
    }

    @Test
    fun threeMfValidZip() {
        val mesh = GearBuilder.mesh(spur(1.0, 20))
        val bytes = ThreeMfWriter.write(mesh)
        assertEquals(0x50, bytes[0].toInt() and 0xff)
        assertEquals(0x4b, bytes[1].toInt() and 0xff)
        val f = File.createTempFile("gear", ".3mf")
        f.writeBytes(bytes)
        ZipFile(f).use { zf ->
            assertTrue(zf.getEntry("3D/3dmodel.model") != null)
            val model = zf.getInputStream(zf.getEntry("3D/3dmodel.model")).readBytes().decodeToString()
            assertTrue(model.contains("<mesh>"))
        }
        f.delete()
    }

    @Test
    fun threeMfRoundTripCounts() {
        val mesh = GearBuilder.mesh(spur(1.0, 20))
        val bytes = ThreeMfWriter.write(mesh)
        val f = File.createTempFile("gear", ".3mf")
        f.writeBytes(bytes)
        ZipFile(f).use { zf ->
            val entry = zf.getEntry("3D/3dmodel.model")
            assertTrue(entry != null)
            val model = zf.getInputStream(entry).readBytes().decodeToString()
            assertEquals(mesh.vertices.size, Regex("<vertex ").findAll(model).count())
            assertEquals(mesh.triangles.size, Regex("<triangle ").findAll(model).count())
        }
        f.delete()
    }

    @Test
    fun svgAndDxf() {
        val shape = GearBuilder.shape(spur(1.0, 20))
        val svg = SvgWriter.write(shape)
        assertTrue(svg.contains("<svg"))
        assertTrue(svg.contains("evenodd"))
        val dxf = DxfWriter.write(shape)
        assertTrue(dxf.contains("LWPOLYLINE"))
        assertTrue(dxf.contains("EOF"))
    }

    @Test
    fun svgRoundTripStructure() {
        val shape = GearBuilder.shape(spur(1.0, 20))
        val svg = SvgWriter.write(shape)
        assertTrue(svg.startsWith("<?xml"))
        assertTrue(svg.contains("<svg"))
        assertTrue(svg.contains("viewBox=\"0 0"))
        assertTrue(svg.contains("fill-rule=\"evenodd\""))
        val d = Regex("d=\"([^\"]+)\"").find(svg)?.groupValues?.get(1) ?: ""
        assertTrue("path must contain a move command", d.contains('M'))
        assertTrue("path must contain line commands", d.contains('L'))
        assertTrue("path must close subpaths", d.contains('Z'))
    }

    @Test
    fun dxfRoundTripStructure() {
        val shape = GearBuilder.shape(spur(1.0, 20))
        val dxf = DxfWriter.write(shape)
        assertTrue(dxf.contains("SECTION"))
        assertTrue(dxf.contains("ENTITIES"))
        assertTrue(dxf.contains("LWPOLYLINE"))
        assertTrue(dxf.contains("0\nEOF"))
        // Vertex coordinates use group codes 10 (x) and 20 (y).
        assertTrue(dxf.contains("\n10\n"))
        assertTrue(dxf.contains("\n20\n"))
    }

    // ---- PrintAdvisor -----------------------------------------------------

    @Test
    fun printAdvisorWarnsSmallModule() {
        val a = PrintAdvisor.advice(GearParams(module = 0.5, teeth = 20), nozzleMm = 0.4, layerHeightMm = 0.2, material = "PLA")
        assertTrue(a.any { it.severity == PrintAdvisor.Severity.WARNING })
    }

    @Test
    fun holeTriangulationIsManifoldAndOriented() {
        // Spur: the triangulated planar region (outer outline + bore hole) must have
        // no duplicate directed edges, i.e. the hole bridge produces a manifold 2D mesh.
        val p = spur(1.0, 20)
        val shape = GearBuilder.shape(p)
        val (_, tris) = Triangulate.triangulate(shape)
        val directed = HashSet<Long>()
        for (t in tris) {
            for (e in 0 until 3) {
                val a = t[e]
                val b = t[(e + 1) % 3]
                val k = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
                assertTrue("spur 2D triangulation has duplicate directed edge $a->$b", directed.add(k))
            }
        }
        assertTrue("spur 2D triangulation is empty", tris.isNotEmpty())

        // Internal ring: outer circle + toothed inner hole must also triangulate
        // without duplicate directed edges. The outer circle is the rim radius used
        // by ringMesh (root radius 21.25 + 2.0 mm rim); the toothed hole's tips reach
        // 21.25, so the hole stays strictly inside the outer boundary.
        val ringShape = PlanarShape(GearBuilder.circle(23.25, 96), listOf(GearProfiles.internalRingOutline(spur(1.0, 40))))
        val (_, rt2) = Triangulate.triangulate(ringShape)
        val rd = HashSet<Long>()
        for (t in rt2) for (e in 0 until 3) {
            val a = t[e]; val b = t[(e + 1) % 3]
            val k = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
            assertTrue("ring 2D triangulation has duplicate directed edge $a->$b", rd.add(k))
        }

        // Extruded mesh orientation: the top cap faces +Z and the bore wall is
        // radial (its normal has no Z component and points away from the axis).
        val m = GearBuilder.mesh(p)
        val top = m.triangles.first { t -> m.vertices[t[0]].z > 5.9 && m.vertices[t[1]].z > 5.9 && m.vertices[t[2]].z > 5.9 }
        val nt = MeshOps.faceNormal(m.vertices[top[0]], m.vertices[top[1]], m.vertices[top[2]])
        assertEquals("top cap normal +Z", 1.0, nt.z, 1e-9)
        val wall = m.triangles.first { t ->
            val rs = t.map { kotlin.math.hypot(m.vertices[it].x, m.vertices[it].y) }
            rs.all { it < 3.0 } && m.vertices[t[0]].z != m.vertices[t[1]].z
        }
        val nw = MeshOps.faceNormal(m.vertices[wall[0]], m.vertices[wall[1]], m.vertices[wall[2]])
        assertEquals("bore wall normal is radial (no Z)", 0.0, nw.z, 1e-9)
    }

    // ---- manufacturability: bore profiles, backlash, elephant-foot chamfer ----

    @Test
    fun din6885KeywayDimensions() {
        assertEquals(2.0, Bore.KeywayStandard.spec(6.0).width, 1e-9)
        assertEquals(1.0, Bore.KeywayStandard.spec(6.0).depth, 1e-9)
        assertEquals(5.0, Bore.KeywayStandard.spec(15.0).width, 1e-9)
        assertEquals(2.3, Bore.KeywayStandard.spec(15.0).depth, 1e-9)
        assertEquals(8.0, Bore.KeywayStandard.spec(25.0).width, 1e-9)
        assertEquals(3.3, Bore.KeywayStandard.spec(25.0).depth, 1e-9)
    }

    @Test
    fun standardKeywayWatertight() {
        val p = spur(2.0, 24).copy(bore = BoreSpec(type = BoreType.KEYWAY, diameter = 15.0, keywayStandard = true))
        val mesh = GearBuilder.mesh(p)
        assertWatertight(mesh)
        val maxR = mesh.vertices.maxOf { kotlin.math.hypot(it.x, it.y) }
        assertTrue("DIN keyway must extend past the bore radius", maxR > 7.5 + 2.0)
    }

    @Test
    fun doubleDBoreWatertightAndFlat() {
        val p = spur(1.5, 20).copy(bore = BoreSpec(type = BoreType.D_CUT, diameter = 6.35, dCutFlatOffset = 2.5, dCutSecondFlat = true))
        assertWatertight(GearBuilder.mesh(p))
        val hole = Bore.holes(p).first()
        assertTrue("top flat present", hole.any { kotlin.math.abs(it.y - 2.5) < 1e-9 })
        assertTrue("bottom flat present", hole.any { kotlin.math.abs(it.y + 2.5) < 1e-9 })
    }

    @Test
    fun setScrewHoleWatertight() {
        val p = spur(2.0, 24).copy(
            hubDiameter = 16.0, hubLength = 8.0,
            setScrewCount = 1, setScrewThread = "M3", setScrewAngleDeg = 90.0
        )
        val mesh = GearBuilder.merged(p)
        val issues = MeshOps.validate(mesh).issues.filterNot { it.contains("duplicate vertices") }
        assertTrue("hub + set screw must be watertight: $issues", issues.isEmpty())
    }

    @Test
    fun setScrewInsufficientHubWarns() {
        val p = GearParams(teeth = 24, module = 2.0, bore = BoreSpec(type = BoreType.ROUND, diameter = 12.0),
            hubDiameter = 14.0, hubLength = 8.0, setScrewCount = 1)
        assertTrue(GearSpec.validate(p).any { it.code == GearSpec.WARN_SETSCREW || it.code == GearSpec.WARN_HUB_WALL })
    }

    @Test
    fun backlashPercentOfModule() {
        val p = GearParams(module = 2.0, teeth = 20, backlashPercent = 5.0)
        assertEquals(0.1, p.effectiveBacklashMm(), 1e-9) // 5% of 2 mm
        val tight = GearProfiles.involuteSpur(p.copy(backlashPercent = 0.0, backlash = 0.0))
        val loose = GearProfiles.involuteSpur(p)
        assertTrue("percent backlash must change the profile", tight.zip(loose).any { (a, b) -> a.dist(b) > 1e-6 })
    }

    @Test
    fun asymmetricBacklashShiftsFlanksAndStaysWatertight() {
        val sym = GearProfiles.involuteSpur(GearParams(module = 1.0, teeth = 20, backlash = 0.2))
        val asym = GearProfiles.involuteSpur(GearParams(module = 1.0, teeth = 20, backlashLeftMm = 0.4))
        assertTrue("asymmetric clearance must change the outline", sym.zip(asym).any { (a, b) -> a.dist(b) > 1e-6 })
        assertWatertight(GearBuilder.mesh(GearParams(module = 1.0, teeth = 20, backlashLeftMm = 0.4)))
    }

    @Test
    fun excessiveBacklashWarns() {
        val p = GearParams(module = 1.0, teeth = 20, backlashPercent = 25.0)
        assertTrue(GearSpec.validate(p).any { it.code == GearSpec.WARN_BACKLASH || it.code == GearSpec.WARN_TOPLAND })
    }

    @Test
    fun elephantFootChamferWatertightAndInset() {
        val p = spur(1.0, 20).copy(elephantFootChamferMm = 0.4)
        val mesh = GearBuilder.mesh(p)
        assertWatertight(mesh)
        assertTrue(MeshOps.signedVolume(mesh) > 0.0)
        val bottom = mesh.vertices.filter { it.z < 0.01 }
        val top = mesh.vertices.filter { it.z > p.thickness - 0.01 }
        // Elephant foot: the bore is enlarged at the bottom, so the smallest bottom
        // radius exceeds the smallest top radius (which is the nominal bore radius).
        val rBottomMin = bottom.minOf { kotlin.math.hypot(it.x, it.y) }
        val rTopMin = top.minOf { kotlin.math.hypot(it.x, it.y) }
        assertTrue("bore should be enlarged at the bottom ($rBottomMin > $rTopMin)", rBottomMin > rTopMin)
    }

    @Test
    fun elephantFootChamferWithKeywayWatertight() {
        val p = spur(2.0, 24).copy(
            bore = BoreSpec(type = BoreType.KEYWAY, diameter = 8.0, keywayStandard = true),
            elephantFootChamferMm = 0.6
        )
        assertWatertight(GearBuilder.mesh(p))
    }

    @Test
    fun eulerCharacteristicClosedMesh() {
        // V − E + F = 2 for a closed, connected, genus-0 mesh (solid gear, no bore).
        val mesh = GearBuilder.mesh(noBore(1.0, 20))
        val v = mesh.vertices.size
        val e = 3 * mesh.triangles.size / 2
        val f = mesh.triangles.size
        assertEquals(2L, (v - e + f).toLong())
    }

    // ---- audit: mathematical boundary & stress matrix ----------------------

    @Test
    fun calculatorBoundariesAreFinite() {
        val zs = intArrayOf(4, 5, 6, 12, 17, 20, 30, 50, 100, 200, 500)
        val ms = doubleArrayOf(0.05, 0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 25.0, 50.0, 100.0)
        val alphas = doubleArrayOf(5.0, 10.0, 14.5, 20.0, 25.0, 30.0, 35.0, 40.0, 45.0)
        for (z in zs) for (m in ms) for (a in alphas) {
            assertTrue("pitch finite z=$z m=$m", GearCalculator.pitchDiameter(m, z).isFinite() && GearCalculator.pitchDiameter(m, z) > 0.0)
            assertTrue("outer finite", GearCalculator.outerDiameter(m, z).isFinite())
            assertTrue("root finite", GearCalculator.rootDiameter(m, z).isFinite())
            assertTrue("base finite", GearCalculator.baseRadius(m, z, a).isFinite() && GearCalculator.baseRadius(m, z, a) >= 0.0)
            assertTrue("undercut finite", GearCalculator.undercutThresholdTeeth(a).isFinite() && GearCalculator.undercutThresholdTeeth(a) > 0.0)
            assertTrue("min-shift finite", GearCalculator.minimumShiftNoUndercut(z, a).isFinite())
            // involute must never be NaN for these angles
            assertTrue("involute finite", GearCalculator.involute(Math.toRadians(a)).isFinite())
        }
    }

    @Test
    fun parameterMatrixCoercesDeterministically() {
        val zs = intArrayOf(4, 5, 6, 12, 17, 20, 30, 50, 100, 200, 500)
        val ms = doubleArrayOf(0.05, 0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 25.0, 50.0, 100.0)
        val alphas = doubleArrayOf(5.0, 10.0, 14.5, 20.0, 25.0, 30.0, 35.0, 40.0, 45.0)
        val xs = doubleArrayOf(-3.0, -2.0, -1.0, -0.5, 0.0, 0.5, 1.0, 2.0, 3.0)
        for (z in zs) for (m in ms) for (a in alphas) for (x in xs) {
            val p = GearParams(module = m, teeth = z, pressureAngleDeg = a, profileShift = x).coerced()
            assertTrue("module clamped", p.module in 0.2..12.0)
            assertTrue("teeth clamped", p.teeth in 8..300)
            assertTrue("angle clamped", p.pressureAngleDeg in 1.0..89.0)
            assertTrue("shift clamped", p.profileShift in -1.0..1.0)
            assertTrue("all finite", p.module.isFinite() && p.teeth.toDouble().isFinite() &&
                p.pressureAngleDeg.isFinite() && p.profileShift.isFinite())
            // validate() must never throw, even on raw out-of-range inputs.
            val warnings = GearSpec.validate(GearParams(module = m, teeth = z, pressureAngleDeg = a, profileShift = x))
            assertTrue("warnings well-formed", warnings.all { it.code.isNotBlank() })
        }
    }

    @Test
    fun warningsForCoercions() {
        assertTrue(GearSpec.validate(GearParams(module = 0.05, teeth = 20)).any { it.code == GearSpec.WARN_MODULE })
        assertTrue(GearSpec.validate(GearParams(module = 50.0, teeth = 20)).any { it.code == GearSpec.WARN_MODULE })
        assertTrue(GearSpec.validate(GearParams(teeth = 4)).any { it.code == GearSpec.WARN_TEETH })
        assertTrue(GearSpec.validate(GearParams(teeth = 500)).any { it.code == GearSpec.WARN_TEETH })
        assertTrue(GearSpec.validate(GearParams(profileShift = 2.0)).any { it.code == GearSpec.WARN_PROFILE_SHIFT })
        assertTrue(GearSpec.validate(GearParams(profileShift = -2.0)).any { it.code == GearSpec.WARN_PROFILE_SHIFT })
        assertTrue(GearSpec.validate(GearParams(gearType = GearType.HELICAL, helixAngleDeg = 86.0)).any { it.code == GearSpec.WARN_HELIX_ANGLE })
        assertTrue(GearSpec.validate(GearParams(backlash = 0.5, module = 1.0)).any { it.code == GearSpec.WARN_BACKLASH })
    }

    @Test
    fun meshBoundaryMatrixIsSolid() {
        for (a in doubleArrayOf(5.0, 14.5, 20.0, 30.0, 45.0)) {
            for (m in doubleArrayOf(0.5, 2.0, 10.0)) {
                assertSolid("a=$a m=$m", GearBuilder.mesh(GearParams(module = m, teeth = 20, pressureAngleDeg = a).coerced()))
            }
        }
        for (x in doubleArrayOf(-1.0, 0.0, 1.0)) {
            assertSolid("x=$x z=17", GearBuilder.mesh(GearParams(module = 1.0, teeth = 17, profileShift = x).coerced()))
        }
        assertSolid("z=100", GearBuilder.mesh(GearParams(module = 1.0, teeth = 100).coerced()))
    }

    @Test
    fun outlinePolygonIsSimple() {
        // The extruded mesh is watertight only when the 2D outline is a simple
        // polygon (no self-intersections). Regression for the root-gap arc that
        // swept the long way across the ±π branch cut (audit H3: x=−1, z=17).
        // α=45° at z=20 is excluded here: the teeth physically overlap at the root
        // (geometrically invalid), which is surfaced as WARN_TOOTH_OVERLAP instead.
        for (a in doubleArrayOf(5.0, 14.5, 20.0, 30.0)) {
            for (m in doubleArrayOf(0.5, 2.0, 10.0)) {
                val poly = GearProfiles.externalOutline(GearParams(module = m, teeth = 20, pressureAngleDeg = a).coerced())
                assertSimplePolygon("a=$a m=$m", poly)
            }
        }
        for (x in doubleArrayOf(-1.0, 0.0, 1.0)) {
            val poly = GearProfiles.externalOutline(GearParams(module = 1.0, teeth = 17, profileShift = x).coerced())
            assertSimplePolygon("x=$x z=17", poly)
        }
        assertSimplePolygon("z=100", GearProfiles.externalOutline(GearParams(module = 1.0, teeth = 100).coerced()))
    }

    @Test
    fun toothOverlapWarning() {
        // High pressure angle + low tooth count makes the involute tooth wider than
        // the tooth space at the root, so adjacent teeth overlap (audit H3).
        assertTrue(GearSpec.validate(GearParams(module = 0.5, teeth = 20, pressureAngleDeg = 45.0))
            .any { it.code == GearSpec.WARN_TOOTH_OVERLAP })
        assertTrue(GearSpec.validate(GearParams(module = 1.0, teeth = 20, pressureAngleDeg = 35.0))
            .none { it.code == GearSpec.WARN_TOOTH_OVERLAP })
        assertTrue(GearSpec.validate(GearParams(module = 1.0, teeth = 20, pressureAngleDeg = 20.0))
            .none { it.code == GearSpec.WARN_TOOTH_OVERLAP })
    }

    private fun assertSimplePolygon(label: String, poly: List<Vec2>) {
        assertTrue("$label: too few points", poly.size >= 3)
        val n = poly.size
        for (i in 0 until n) {
            val a = poly[i]; val b = poly[(i + 1) % n]
            for (j in i + 1 until n) {
                if (j == i || (j + 1) % n == i || j == (i + 1) % n || i == (j + 1) % n) continue
                val c = poly[j]; val d = poly[(j + 1) % n]
                val t = segmentIntersection(a, b, c, d)
                assertTrue("$label: self-intersection seg $i x seg $j at (${t?.get(0)}, ${t?.get(1)})", t == null)
            }
        }
    }

    private fun segmentIntersection(a: Vec2, b: Vec2, c: Vec2, d: Vec2): DoubleArray? {
        val r = b - a; val s = d - c
        val denom = r.x * s.y - r.y * s.x
        if (abs(denom) < 1e-14) return null
        val t = ((c.x - a.x) * s.y - (c.y - a.y) * s.x) / denom
        val u = ((c.x - a.x) * r.y - (c.y - a.y) * r.x) / denom
        if (t in 1e-9..(1.0 - 1e-9) && u in 1e-9..(1.0 - 1e-9)) return doubleArrayOf(a.x + t * r.x, a.y + t * r.y)
        return null
    }

    @Test
    fun stlLargeMeshStressTest() {
        // A high-resolution helical gear yields > 1M triangles at HIGH precision
        // (44 flank samples × 64 loft slices).
        val p = GearParams(gearType = GearType.HELICAL, module = 1.0, teeth = 70,
            helixAngleDeg = 15.0, thickness = 10.0, precision = PrecisionLevel.HIGH)
        val mesh = GearBuilder.mesh(p.coerced())
        assertTrue("expected ≥1M triangles, got ${mesh.triangles.size}", mesh.triangles.size >= 1_000_000)
        val v = MeshOps.validate(mesh)
        assertTrue("large mesh not watertight: ${v.issues}", v.issues.filterNot { it.contains("duplicate vertices") }.isEmpty())
        val bytes = StlWriter.writeBinary(mesh)
        assertEquals("STL length", 84 + 50 * mesh.triangles.size, bytes.size)
        assertEquals("STL triangle count", mesh.triangles.size, littleInt(bytes, 80))
        // Sample first and last triangles: 12 little-endian floats must all be finite.
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (off in intArrayOf(84, bytes.size - 50)) {
            buf.position(off)
            for (i in 0 until 12) {
                assertTrue("STL float must be finite", buf.getFloat().isFinite())
            }
        }
    }

    // ---- compound gear (dubbelkugghjul) ----------------------------------

    private fun compound(
        m1: Double = 1.0, z1: Int = 20, b1: Double = 6.0,
        m2: Double = 1.0, z2: Int = 16, b2: Double = 4.0,
        h: Double = 2.0, phase: Double = 0.0,
        boreType: BoreType = BoreType.ROUND, boreD: Double = 5.0
    ) = GearParams(
        gearType = GearType.COMPOUND,
        module = m1, teeth = z1, thickness = b1,
        stage2Module = m2, stage2Teeth = z2, stage2FaceWidth = b2,
        spacerHeight = h, stage2PhaseDeg = phase,
        bore = BoreSpec(type = boreType, diameter = boreD)
    )

    @Test
    fun compoundMeshIsWatertight() {
        assertSolid("compound round", GearBuilder.mesh(compound()))
        assertSolid("compound unequal", GearBuilder.mesh(compound(m1 = 2.0, z1 = 24, m2 = 0.8, z2 = 12)))
        assertSolid("compound no bore", GearBuilder.mesh(compound(boreType = BoreType.NONE)))
        assertSolid("compound keyway", GearBuilder.mesh(compound(boreType = BoreType.KEYWAY, boreD = 8.0)))
        assertSolid("compound dcut", GearBuilder.mesh(compound(boreType = BoreType.D_CUT, boreD = 8.0)))
        assertSolid("compound zero-spacer identical", GearBuilder.mesh(compound(h = 0.0, m2 = 1.0, z2 = 20)))
        assertSolid("compound zero-spacer nested", GearBuilder.mesh(compound(h = 0.0, m1 = 2.0, z1 = 24, m2 = 0.8, z2 = 12)))
    }

    @Test
    fun compoundEulerCharacteristic() {
        // χ = V − E + F: 2 for a solid (no through-hole), 0 for a genus-1 solid with one bore.
        val withBore = GearBuilder.mesh(compound())
        assertEquals("compound with bore should be genus 1", 0, eulerCharacteristic(withBore))
        val noBore = GearBuilder.mesh(compound(boreType = BoreType.NONE))
        assertEquals("compound without bore should be genus 0", 2, eulerCharacteristic(noBore))
    }

    @Test
    fun compoundTotalHeightAndStagePositions() {
        val b1 = 6.0; val h = 2.5; val b2 = 4.0
        val mesh = GearBuilder.mesh(compound(b1 = b1, b2 = b2, h = h))
        val bounds = MeshOps.bounds(mesh)
        assertEquals("total height", b1 + h + b2, bounds.z, 1e-6)
        assertEquals("bottom at z=0", 0.0, mesh.vertices.minOf { it.z }, 1e-6)
        assertEquals("top at total", b1 + h + b2, mesh.vertices.maxOf { it.z }, 1e-6)
    }

    @Test
    fun compoundToothPhaseIsApplied() {
        val phase = 15.0
        val b1 = 6.0; val h = 2.0; val b2 = 4.0
        val mesh = GearBuilder.mesh(compound(z1 = 12, z2 = 12, b1 = b1, b2 = b2, h = h, phase = phase))
        val zMin = b1 + h
        val stage2 = mesh.vertices.filter { it.z >= zMin - 1e-9 }
        val rMax = stage2.maxOf { kotlin.math.hypot(it.x, it.y) }
        val tips = stage2
            .filter { kotlin.math.abs(kotlin.math.hypot(it.x, it.y) - rMax) < 1e-6 }
            .map { normalizeDeg(Math.toDegrees(kotlin.math.atan2(it.y, it.x))) }
        assertTrue(
            "stage 2 tooth reference angle should be $phase°, got $tips",
            tips.any { kotlin.math.abs(normalizeDeg(it - phase)) < 0.5 }
        )
    }

    @Test
    fun compoundBoreIsContinuousThroughSpacer() {
        val b1 = 6.0; val h = 2.0; val b2 = 4.0; val boreD = 5.0
        val mesh = GearBuilder.mesh(compound(b1 = b1, b2 = b2, h = h, boreD = boreD))
        val boreR = boreD / 2.0
        // The bore wall ring must be present at every segment boundary plane.
        for (z in doubleArrayOf(0.0, b1, b1 + h, b1 + h + b2)) {
            assertTrue(
                "bore wall missing at z=$z",
                mesh.vertices.any {
                    kotlin.math.abs(it.z - z) < 1e-6 &&
                        kotlin.math.abs(kotlin.math.hypot(it.x, it.y) - boreR) < 0.05
                }
            )
        }
    }

    @Test
    fun compoundValidationWarnsOnInvalidParams() {
        // Spacer diameter that cannot clear the teeth.
        val tooBig = GearParams(gearType = GearType.COMPOUND, module = 1.0, teeth = 20,
            stage2Module = 1.0, stage2Teeth = 20, spacerDiameter = 100.0)
        assertTrue(GearSpec.validate(tooBig).any { it.code == GearSpec.WARN_SPACER })
        // Stage 2 tooth count below the involute minimum.
        val fewTeeth = GearParams(gearType = GearType.COMPOUND, stage2Teeth = 4)
        assertTrue(GearSpec.validate(fewTeeth).any { it.code == GearSpec.WARN_TEETH })
    }

    @Test
    fun presetValuesAreCorrect() {
        // screw-2to1 must be the 40-tooth gear of a crossed 2:1 pair.
        assertEquals(40, Presets.byId("screw-2to1")!!.params.teeth)
        // Planetary preset ratios must match their labels (fixed-ring formula).
        assertEquals(3.0, GearCalculator.planetaryRatioFixedRing(16, 32), 1e-9)
        assertEquals(5.0, GearCalculator.planetaryRatioFixedRing(12, 48), 1e-9)
        assertEquals(7.0, GearCalculator.planetaryRatioFixedRing(12, 72), 1e-9)
        assertEquals(4.0, GearCalculator.planetaryRatioFixedRing(12, 36), 1e-9)
        // Every gear type exposes at least one preset, and compound at least three.
        for (type in GearType.entries) {
            assertTrue("no presets for $type", Presets.forType(type).isNotEmpty())
        }
        assertTrue("compound needs ≥3 presets", Presets.forType(GearType.COMPOUND).size >= 3)
    }

    @Test
    fun allPresetsBuildWatertightMeshes() {
        val failures = ArrayList<String>()
        for (preset in Presets.all()) {
            val label = "${preset.type.name}/${preset.id}"
            try {
                val p = preset.params.coerced()
                val assembly = GearBuilder.assembly(p)
                assertTrue("$label produced no meshes", assembly.meshes.isNotEmpty())
                for ((i, mesh) in assembly.meshes.withIndex()) {
                    try {
                        assertSolid("$label mesh[$i]", mesh)
                    } catch (t: AssertionError) {
                        failures.add("$label mesh[$i]: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                failures.add("$label threw ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        assertTrue("presets failed to build: ${failures.joinToString(" | ")}", failures.isEmpty())
    }

    private fun eulerCharacteristic(mesh: Mesh): Int {
        val v = mesh.vertices.size
        val f = mesh.triangles.size
        val edges = HashSet<Long>()
        for (t in mesh.triangles) for (e in 0 until 3) {
            val a = t[e]
            val b = t[(e + 1) % 3]
            val lo = minOf(a, b).toLong()
            val hi = maxOf(a, b).toLong()
            edges.add((lo shl 32) or hi)
        }
        return v - edges.size + f
    }

    private fun normalizeDeg(x: Double): Double = ((x % 360.0) + 360.0) % 360.0

    private fun assertSolid(label: String, mesh: Mesh) {
        for (vtx in mesh.vertices) {
            assertTrue("$label non-finite vertex", vtx.x.isFinite() && vtx.y.isFinite() && vtx.z.isFinite())
        }
        val n = mesh.vertices.size
        for (t in mesh.triangles) {
            assertTrue("$label index out of range", t.all { it in 0 until n })
            val a = mesh.vertices[t[0]]
            val b = mesh.vertices[t[1]]
            val c = mesh.vertices[t[2]]
            assertTrue("$label degenerate triangle", (b - a).cross(c - a).length() > 1e-12)
        }
        assertWatertight(label, mesh)
        assertTrue("$label non-positive volume", MeshOps.signedVolume(mesh) > 0.0)
    }

    private fun assertWatertight(mesh: Mesh) {
        assertWatertight("", mesh)
    }

    private fun assertWatertight(label: String, mesh: Mesh) {
        val prefix = if (label.isEmpty()) "" else "$label: "
        assertTrue("${prefix}no triangles", mesh.triangles.isNotEmpty())
        val directed = HashSet<Long>()
        for (t in mesh.triangles) {
            for (e in 0 until 3) {
                val a = t[e]
                val b = t[(e + 1) % 3]
                val k = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
                assertTrue("${prefix}duplicate directed edge $a->$b", directed.add(k))
            }
        }
        assertEquals("${prefix}directed edge count", 3L * mesh.triangles.size, directed.size.toLong())
        for (k in directed) {
            val a = (k ushr 32).toInt()
            val b = k.toInt()
            val rev = (b.toLong() shl 32) or (a.toLong() and 0xffffffffL)
            assertTrue("${prefix}open boundary edge $b->$a", directed.contains(rev))
        }
    }
}
