package com.gearforge.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Generates one SVG asset per gear type (current default parameters) for use as UI
 * assets in the gear-selector and settings views. Output dir is configurable via the
 * `gearforge.assets` system property (defaults to `Assets/generated` relative to the
 * working directory).
 */
class AssetExportTest {

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
