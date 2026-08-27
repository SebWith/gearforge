package com.gearforge.core

import java.util.Locale

/** SVG export of the 2D profile (outer + holes) with even-odd fill. */
object SvgWriter {

    fun write(shape: PlanarShape): String {
        val all = ArrayList<Vec2>(shape.outer.size + shape.holes.sumOf { it.size })
        all.addAll(shape.outer)
        shape.holes.forEach { all.addAll(it) }
        if (all.isEmpty()) return ""

        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (p in all) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val pad = 2.0
        val width = maxX - minX + 2 * pad
        val height = maxY - minY + 2 * pad

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
          .append(fmt(width)).append(' ').append(fmt(height))
          .append("\" width=\"").append(fmt(width)).append("mm\" height=\"").append(fmt(height)).append("mm\">\n")
        sb.append("  <path fill=\"currentColor\" fill-rule=\"evenodd\" d=\"")
        appendLoop(sb, shape.outer, minX - pad, minY - pad)
        for (h in shape.holes) appendLoop(sb, h, minX - pad, minY - pad)
        sb.append("\"/>\n</svg>\n")
        return sb.toString()
    }

    private fun appendLoop(sb: StringBuilder, loop: List<Vec2>, ox: Double, oy: Double) {
        if (loop.isEmpty()) return
        sb.append('M').append(fmt(loop[0].x - ox)).append(' ').append(fmt(loop[0].y - oy))
        for (i in 1 until loop.size) {
            sb.append('L').append(fmt(loop[i].x - ox)).append(' ').append(fmt(loop[i].y - oy))
        }
        sb.append('Z')
    }

    /**
     * Uniform SVG export for UI asset sets: every gear is centred and uniformly scaled
     * into the same fixed square viewBox (default 26×26), so all files share identical
     * dimensions. Y is flipped so the asset matches the in-app Compose outline rendering.
     */
    fun writeUniform(shape: PlanarShape, boxSize: Double = 26.0): String {
        val all = ArrayList<Vec2>(shape.outer.size + shape.holes.sumOf { it.size })
        all.addAll(shape.outer)
        shape.holes.forEach { all.addAll(it) }
        if (all.isEmpty()) return ""

        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (p in all) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val span = maxOf(maxX - minX, maxY - minY)
        val pad = boxSize * 0.06
        val scale = if (span > 0.0) (boxSize - 2.0 * pad) / span else 1.0
        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
          .append(fmtInt(boxSize)).append(' ').append(fmtInt(boxSize))
          .append("\" width=\"").append(fmtInt(boxSize)).append("mm\" height=\"").append(fmtInt(boxSize)).append("mm\">\n")
        sb.append("  <path fill=\"currentColor\" fill-rule=\"evenodd\" d=\"")
        appendUniformLoop(sb, shape.outer, cx, cy, scale, boxSize)
        for (h in shape.holes) appendUniformLoop(sb, h, cx, cy, scale, boxSize)
        sb.append("\"/>\n</svg>\n")
        return sb.toString()
    }

    private fun appendUniformLoop(sb: StringBuilder, loop: List<Vec2>, cx: Double, cy: Double, scale: Double, boxSize: Double) {
        if (loop.isEmpty()) return
        sb.append('M').append(fmt(boxSize / 2.0 + (loop[0].x - cx) * scale))
          .append(' ').append(fmt(boxSize / 2.0 - (loop[0].y - cy) * scale))
        for (i in 1 until loop.size) {
            sb.append('L').append(fmt(boxSize / 2.0 + (loop[i].x - cx) * scale))
              .append(' ').append(fmt(boxSize / 2.0 - (loop[i].y - cy) * scale))
        }
        sb.append('Z')
    }

    private fun fmtInt(v: Double): String = String.format(Locale.US, "%.0f", v)

    private fun fmt(v: Double): String = String.format(Locale.US, "%.3f", v)
}
