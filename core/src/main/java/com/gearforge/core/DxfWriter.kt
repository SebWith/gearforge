package com.gearforge.core

import java.util.Locale

/** DXF (R14 LWPOLYLINE) export of the 2D profile. */
object DxfWriter {

    fun write(shape: PlanarShape): String {
        val sb = StringBuilder()
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
