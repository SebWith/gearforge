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
    fun diagnoseHoleTriangulation() {
        val p = spur(1.0, 20)
        val shape = GearBuilder.shape(p)
        val (poly, tris) = Triangulate.triangulate(shape)
        val directed = HashSet<Long>()
        var dups = 0
        for (t in tris) {
            for (e in 0 until 3) {
                val a = t[e]
                val b = t[(e + 1) % 3]
                val k = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
                if (!directed.add(k)) dups++
            }
        }
        println("DIAG outer=${shape.outer.size} hole=${shape.holes[0].size} merged=${poly.size} tris2d=${tris.size} dup2d=$dups boundary=${MeshBuilder.boundaryEdges(tris).size}")

        val ringShape = PlanarShape(GearBuilder.circle(21.25, 192), listOf(GearProfiles.internalRingOutline(spur(1.0, 40))))
        val (rp2, rt2) = Triangulate.triangulate(ringShape)
        var rdups = 0
        val rd = HashSet<Long>()
        for (t in rt2) for (e in 0 until 3) {
            val a = t[e]; val b = t[(e + 1) % 3]
            val k = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
            if (!rd.add(k)) rdups++
        }
        println("DIAG-RING outer=${ringShape.outer.size} hole=${ringShape.holes[0].size} merged=${rp2.size} tris2d=${rt2.size} dup2d=$rdups")

        val pm = spur(1.0, 20)
        val m = GearBuilder.mesh(pm)
        val top = m.triangles.first { t -> m.vertices[t[0]].z > 5.9 && m.vertices[t[1]].z > 5.9 && m.vertices[t[2]].z > 5.9 }
        val nt = MeshOps.faceNormal(m.vertices[top[0]], m.vertices[top[1]], m.vertices[top[2]])
        println("DIAG-NORM top=$nt")
        val wall = m.triangles.first { t ->
            val rs = t.map { kotlin.math.hypot(m.vertices[it].x, m.vertices[it].y) }
            rs.all { it < 3.0 } && m.vertices[t[0]].z != m.vertices[t[1]].z
        }
        val nw = MeshOps.faceNormal(m.vertices[wall[0]], m.vertices[wall[1]], m.vertices[wall[2]])
        val rc = kotlin.math.hypot((m.vertices[wall[0]].x + m.vertices[wall[1]].x + m.vertices[wall[2]].x) / 3, (m.vertices[wall[0]].y + m.vertices[wall[1]].y + m.vertices[wall[2]].y) / 3)
        println("DIAG-NORM boreWall=$nw wallCentroidRadius=$rc")
    }

    private fun assertWatertight(mesh: Mesh) {
        assertTrue("no triangles", mesh.triangles.isNotEmpty())
        val directed = HashSet<Long>()
        for (t in mesh.triangles) {
            for (e in 0 until 3) {
                val a = t[e]
                val b = t[(e + 1) % 3]
                val k = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
                assertTrue("duplicate directed edge $a->$b", directed.add(k))
            }
        }
        assertEquals(3L * mesh.triangles.size, directed.size.toLong())
        for (k in directed) {
            val a = (k ushr 32).toInt()
            val b = k.toInt()
            val rev = (b.toLong() shl 32) or (a.toLong() and 0xffffffffL)
            assertTrue("open boundary edge $b->$a", directed.contains(rev))
        }
    }
}
