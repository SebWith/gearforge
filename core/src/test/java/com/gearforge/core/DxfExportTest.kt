package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * DXF structure and unit contract.
 *
 * The DXF used to be written without a HEADER, i.e. unitless, while its SVG sibling
 * (`SvgWriter`) declares `width="…mm"`. The same gear could therefore be imported as
 * millimetres from one file and as anonymous document units from the other, which changes
 * the cut/scale on a laser or in CAD. These tests pin the declared unit and, at the same
 * time, re-check that the ENTITIES section still describes a consistent closed outline.
 */
class DxfExportTest {

    private fun spurShape(): PlanarShape = GearBuilder.shape(GearSpec.defaults(GearType.SPUR))

    /** Splits DXF text into (group code, value) pairs. */
    private fun groups(dxf: String): List<Pair<Int, String>> {
        val lines = dxf.split('\n')
        val out = ArrayList<Pair<Int, String>>()
        var i = 0
        while (i + 1 < lines.size) {
            val code = lines[i].trim().toIntOrNull() ?: break
            out.add(code to lines[i + 1])
            i += 2
        }
        return out
    }

    /** Value of the group that directly follows the first `(9, name)` marker. */
    private fun headerValue(g: List<Pair<Int, String>>, name: String): String? {
        val at = g.indexOfFirst { it.first == 9 && it.second == name }
        if (at < 0 || at + 1 >= g.size) return null
        return g[at + 1].second
    }

    @Test
    fun declaresMillimetresInTheHeader() {
        val g = groups(DxfWriter.write(spurShape()))
        assertEquals("4 is the AutoCAD \$INSUNITS code for millimetres", "4", headerValue(g, "\$INSUNITS"))
        assertEquals("metric measurement system", "1", headerValue(g, "\$MEASUREMENT"))
    }

    @Test
    fun headerAdvertisesAnAcadVersionThatSupportsInsunits() {
        val g = groups(DxfWriter.write(spurShape()))
        // LWPOLYLINE needs R14 (AC1014); $INSUNITS was introduced with AC1015.
        assertEquals("AC1015", headerValue(g, "\$ACADVER"))
    }

    @Test
    fun keepsTheExpectedSectionOrder() {
        val g = groups(DxfWriter.write(spurShape()))
        val sections = g.filter { it.first == 2 }.map { it.second }
        assertEquals(listOf("HEADER", "ENTITIES"), sections)
        assertEquals(0, g.last().first)
        assertEquals("EOF", g.last().second)
    }

    @Test
    fun everyPolylineDeclaresTheVertexCountItActuallyWrites() {
        val dxf = DxfWriter.write(spurShape())
        val g = groups(dxf)
        val starts = g.withIndex().filter { it.value.first == 0 && it.value.second == "LWPOLYLINE" }
        assertTrue("expected at least the outer outline", starts.isNotEmpty())

        for ((n, start) in starts.withIndex()) {
            val end = starts.getOrNull(n + 1)?.index ?: g.size
            val body = g.subList(start.index + 1, end)
            val declared = body.first { it.first == 90 }.second.toInt()
            val written = body.count { it.first == 10 }
            assertEquals("group 90 must match the 10/20 pairs", declared, written)
            assertEquals("closed polyline flag", "1", body.first { it.first == 70 }.second)
            assertEquals("as many Y as X coordinates", written, body.count { it.first == 20 })
        }
    }

    @Test
    fun roundTripsEveryVertexOfTheOutline() {
        val shape = spurShape()
        val g = groups(DxfWriter.write(shape))
        val expected = shape.outer.size + shape.holes.sumOf { it.size }
        assertTrue("expected the outer outline plus the bore hole", expected > shape.outer.size)
        assertEquals(expected, g.count { it.first == 10 })
        // No coordinate may be NaN or an unparsable locale string.
        for ((code, value) in g) {
            if (code == 10 || code == 20) {
                value.toDoubleOrNull()?.let { assertTrue("coordinate must be finite", it.isFinite()) }
                    ?: error("unparsable DXF coordinate: '$value'")
            }
        }
    }

    @Test
    fun outlineRadiusMatchesTheCalculatedOuterRadiusInMillimetres() {
        // Catches a unit or scale regression — the reason the header now names the unit.
        val p = GearSpec.defaults(GearType.SPUR)
        val shape = spurShape()
        val tip = GearCalculator.outerRadius(p.module, p.teeth)
        val widest = shape.outer.maxOf { hypot(it.x, it.y) }
        assertEquals("tip circle in mm", tip, widest, 1e-6)

        val holeWidest = shape.holes.flatMap { it }.maxOf { hypot(it.x, it.y) }
        assertTrue("the bore must stay inside the tip circle", holeWidest < widest)
    }
}
