package com.gearforge.core

import java.util.Locale

/**
 * DXF export of the 2D profile (AutoCAD 2000 / AC1015, `LWPOLYLINE`).
 *
 * The HEADER declares the drawing unit. A DXF without `$INSUNITS` is unitless and the
 * importer has to guess, while the SVG sibling ([SvgWriter]) writes `width="…mm"` — so the
 * same gear could be read as millimetres in one format and as document units in the other.
 * `$INSUNITS` = 4 is millimetres and `$MEASUREMENT` = 1 is metric: the same unit `SvgWriter`
 * declares.
 *
 * `LWPOLYLINE` requires R14 (AC1014) or later; `$INSUNITS` was introduced with AC1015, so
 * the header advertises AC1015 (a superset of AC1014, so R14 readers still accept it).
 */
object DxfWriter {

    private const val ACADVER = "AC1015"

    /** AutoCAD `$INSUNITS` code for millimetres. */
    private const val INSUNITS_MM = 4

    fun write(shape: PlanarShape): String {
        val sb = StringBuilder()
        sb.append("0\nSECTION\n2\nHEADER\n")
        sb.append("9\n\$ACADVER\n1\n").append(ACADVER).append("\n")
        sb.append("9\n\$INSUNITS\n70\n").append(INSUNITS_MM).append("\n")
        sb.append("9\n\$MEASUREMENT\n70\n1\n")
        sb.append("0\nENDSEC\n")
        sb.append("0\nSECTION\n2\nENTITIES\n")
        appendPolyline(sb, shape.outer)
        for (h in shape.holes) appendPolyline(sb, h)
        sb.append("0\nENDSEC\n0\nEOF\n")
        return sb.toString()
    }

    private fun appendPolyline(sb: StringBuilder, loop: List<Vec2>) {
        if (loop.isEmpty()) return
        sb.append("0\nLWPOLYLINE\n8\n0\n90\n").append(loop.size).append("\n70\n1\n")
        for (p in loop) {
            sb.append("10\n").append(fmt(p.x)).append("\n20\n").append(fmt(p.y)).append("\n")
        }
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.4f", v)
}
