package com.gearforge.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import com.gearforge.core.GearBuilder
import com.gearforge.core.GearSpec
import com.gearforge.core.GearType
import com.gearforge.core.PlanarShape
import com.gearforge.core.Vec2
import kotlin.math.max
import kotlin.math.min

/**
 * Renders the actual 2D gear outline (the app's own export geometry) as a theme-tinted
 * vector. This is the same outline used for SVG/DXF export, so every thumbnail is an
 * accurate, consistent representation of the gear type — clearly better than abstract
 * letter glyphs and always in sync with the real geometry.
 */
@Composable
fun GearOutline(
    type: GearType,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    val tint = if (color == Color.Unspecified) {
        androidx.compose.material3.MaterialTheme.colorScheme.primary
    } else {
        color
    }
    val shape = remember(type) { GearBuilder.shape(GearSpec.defaults(type)) }
    Canvas(modifier) {
        drawPath(buildPlanarPath(shape, size.width, size.height), tint)
    }
}

/** Fits the planar outline (outer + holes) into the canvas, centered and uniformly scaled. */
private fun buildPlanarPath(shape: PlanarShape, w: Float, h: Float): Path {
    val all = ArrayList<Vec2>(shape.outer.size + shape.holes.sumOf { it.size })
    all.addAll(shape.outer)
    shape.holes.forEach { all.addAll(it) }
    if (all.isEmpty()) return Path()

    var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
    for (p in all) {
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.x > maxX) maxX = p.x
        if (p.y > maxY) maxY = p.y
    }
    val span = max(maxX - minX, maxY - minY).toFloat()
    val scale = if (span > 0f) min(w, h) / span * 0.86f else 1f
    val cx = (minX + maxX).toFloat() / 2f
    val cy = (minY + maxY).toFloat() / 2f
    val ox = w / 2f
    val oy = h / 2f

    val path = Path().apply { fillType = PathFillType.EvenOdd }

    fun addLoop(loop: List<Vec2>) {
        if (loop.isEmpty()) return
        path.moveTo(ox + (loop[0].x.toFloat() - cx) * scale, oy - (loop[0].y.toFloat() - cy) * scale)
        for (i in 1 until loop.size) {
            path.lineTo(ox + (loop[i].x.toFloat() - cx) * scale, oy - (loop[i].y.toFloat() - cy) * scale)
        }
        path.close()
    }

    addLoop(shape.outer)
    shape.holes.forEach { addLoop(it) }
    return path
}
