package com.gearforge.core

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Generates one SVG asset per gear type (current default parameters) for use as UI
 * assets in the gear-selector and settings views. Output dir is configurable via the
 * `gearforge.assets` system property (defaults to `Assets/generated` relative to the
 * working directory).
 */
class AssetExportTest {

    @Test
    fun exportVerificationFixtures() {
        val params = GearParams(
            gearType = GearType.SPUR, module = 1.0, teeth = 20, thickness = 6.0,
            bore = BoreSpec(type = BoreType.ROUND, diameter = 4.0),
            precision = PrecisionLevel.STANDARD
        )
        val mesh = GearBuilder.merged(params)
        val shape = GearBuilder.shape(params)
        val bounds = MeshOps.bounds(mesh)
        assertEquals(22.0, bounds.x, 0.05)
        assertEquals(22.0, bounds.y, 0.05)
        assertEquals(6.0, bounds.z, 1e-9)
        val exports = linkedMapOf(
            "stl" to StlWriter.writeBinary(mesh),
            "3mf" to withoutZipTimestamps(ThreeMfWriter.write(mesh)),
            "step" to StepWriter.write(mesh).toByteArray(Charsets.UTF_8),
            "iges" to IgesWriter.write(mesh).toByteArray(Charsets.UTF_8),
            "dxf" to DxfWriter.write(shape).toByteArray(Charsets.UTF_8),
            "svg" to SvgWriter.write(shape).toByteArray(Charsets.UTF_8)
        )
        val directory = File("build/export-fixtures")
        assertTrue(directory.isDirectory || directory.mkdirs())
        exports.forEach { (extension, bytes) ->
            assertTrue("$extension fixture is nonempty", bytes.isNotEmpty())
            File(directory, "spur.$extension").writeBytes(bytes)
        }
    }

    private fun withoutZipTimestamps(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            ZipInputStream(bytes.inputStream()).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    zip.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                    input.copyTo(zip)
                    zip.closeEntry()
                    entry = input.nextEntry
                }
            }
        }
        return output.toByteArray()
    }

    @Test
    fun stepCadVerificationFixtures() {
        val directory = File("build/step-cad-fixtures")
        assertTrue(directory.isDirectory || directory.mkdirs())
        for (type in listOf(GearType.HELICAL, GearType.RACK, GearType.PLANETARY)) {
            val params = GearSpec.defaults(type).copy(precision = PrecisionLevel.HOBBY)
            val mesh = GearBuilder.merged(params)
            val name = type.name.lowercase()
            val step = StepWriter.write(mesh)
            assertEquals("$name STEP must be deterministic", step, StepWriter.write(mesh))
            File(directory, "$name.step").writeText(step, Charsets.UTF_8)
            File(directory, "$name.stl").writeBytes(StlWriter.writeBinary(mesh))
        }
    }

    @Test
    fun exportSvgAssets() {
        val dir = File(System.getProperty("gearforge.assets", "Assets/generated"))
        dir.mkdirs()
        var count = 0
        GearType.entries.forEach { type ->
            val params = GearSpec.defaults(type)
            val svg = SvgWriter.writeUniform(GearBuilder.shape(params), boxSize = 26.0)
            if (svg.isBlank()) return@forEach
            File(dir, "gear_" + type.name.lowercase() + ".svg").writeText(svg)
            count++
        }
        assertTrue("wrote $count svg assets", count >= GearType.entries.size - 1)
    }
}
