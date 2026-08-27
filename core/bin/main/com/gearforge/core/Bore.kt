package com.gearforge.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Generates center-bore hole polygons (cut into the gear). */
object Bore {

    fun holes(p: GearParams): List<List<Vec2>> {
        val b = p.bore
        if (b.type == BoreType.NONE) return emptyList()
        val hole = when (b.type) {
            BoreType.ROUND -> round(b.diameter / 2.0)
            BoreType.D_CUT -> dCut(b.diameter / 2.0, b.dCutFlatOffset)
            BoreType.KEYWAY -> keyway(b.diameter / 2.0, b.keywayWidth, b.keywayDepth)
            BoreType.HEX -> hex(b.hexAcrossFlats / 2.0)
            BoreType.NONE -> emptyList()
        }
        return if (hole.isEmpty()) emptyList() else listOf(hole)
    }

    fun round(r: Double, segments: Int = 48): List<Vec2> =
        (0 until segments).map { k -> Vec2.polar(r, 2.0 * PI * k / segments) }

    fun dCut(r: Double, flatOffset: Double): List<Vec2> {
        val off = flatOffset.coerceIn(-r, r)
        val beta = asin(off / r)
        val x0 = sqrt(max(0.0, r * r - off * off))
        val pts = ArrayList<Vec2>()
        pts.add(Vec2(x0, off))
        pts.add(Vec2(-x0, off))
        val n = 48
        val sweep = PI + 2.0 * beta
        for (k in 0..n) {
            val th = (PI - beta) + sweep * k / n
            pts.add(Vec2(r * cos(th), r * sin(th)))
        }
        return pts
    }

    fun keyway(r: Double, width: Double, depth: Double): List<Vec2> {
        val half = width / 2.0
        val beta = asin((half / r).coerceIn(-1.0, 1.0))
        val pts = ArrayList<Vec2>()
        val n = 48
        val sweep = 2.0 * PI - 2.0 * beta
        for (k in 0..n) {
            val th = beta + sweep * k / n
            pts.add(Vec2.polar(r, th))
        }
        pts.add(Vec2(r + depth, -half))
        pts.add(Vec2(r + depth, half))
        return pts
    }

    fun hex(apothem: Double): List<Vec2> {
        val r = apothem / cos(PI / 6.0)
        return (0 until 6).map { k -> Vec2.polar(r, PI / 6.0 + 2.0 * PI * k / 6.0) }
    }

    /**
     * Resolved lightening-hole plan: the hole polygons plus the correction flags
     * used by [GearSpec.validate] to surface warnings instead of silently dropping
     * or resizing holes.
     */
    data class LighteningPlan(
        val holes: List<List<Vec2>> = emptyList(),
        val count: Int = 0,
        val requestedCount: Int = 0,
        val radius: Double = 0.0,
        val requestedDiameter: Double = 0.0,
        val pcdRadius: Double = 0.0
    )

    /**
     * Plans circular lightening holes on a pitch circle, kept inside the solid
     * annulus between the centre bore and the tooth root. The hole size, pitch
     * circle and count are auto-corrected so the holes never overlap each other,
     * the bore or the teeth; the caller is told when correction occurred.
     */
    fun lighteningPlan(p: GearParams): LighteningPlan {
        val requestedCount = p.lighteningHoleCount
        if (requestedCount <= 0) return LighteningPlan()
        val rRoot = GearCalculator.rootRadius(p.module, p.teeth)
        if (rRoot <= 0.0) return LighteningPlan(requestedCount = requestedCount)

        // Keep a thin web of material next to the bore and next to the tooth root.
        val web = max(0.6, p.module * 0.4)
        val boreR = if (p.bore.type == BoreType.NONE) 0.0 else p.bore.diameter / 2.0
        val rInner = boreR + web
        val rOuter = rRoot - web
        if (rOuter - rInner <= 1.0) {
            return LighteningPlan(requestedCount = requestedCount, requestedDiameter = p.lighteningHoleDiameter)
        }

        // Hole radius: automatic when the diameter is unset, otherwise clamped to
        // the available annulus so the hole always fits.
        val requestedR = if (p.lighteningHoleDiameter > 0.0) p.lighteningHoleDiameter / 2.0 else 0.0
        val maxR = (rOuter - rInner) / 2.0
        val radius = if (requestedR > 0.0) min(requestedR, maxR) else maxR * 0.8
        if (radius < 0.4) {
            return LighteningPlan(requestedCount = requestedCount, requestedDiameter = p.lighteningHoleDiameter)
        }

        // Pitch circle: user value or centred in the annulus, clamped to stay inside.
        val pcdRadius = if (p.lighteningHolePCD > 0.0) {
            (p.lighteningHolePCD / 2.0).coerceIn(rInner + radius, rOuter - radius)
        } else {
            (rInner + rOuter) / 2.0
        }

        // Limit the count so adjacent holes keep a small gap.
        val maxCount = if (pcdRadius > radius + 1e-9) {
            (PI / asin(radius / pcdRadius)).toInt().coerceAtLeast(1)
        } else 1
        val count = min(requestedCount, maxCount)
        if (count <= 0) {
            return LighteningPlan(requestedCount = requestedCount, requestedDiameter = p.lighteningHoleDiameter)
        }

        val circle = round(radius)
        val holes = (0 until count).map { k ->
            val a = 2.0 * PI * k / count
            circle.map { Vec2(it.x + pcdRadius * cos(a), it.y + pcdRadius * sin(a)) }
        }
        return LighteningPlan(
            holes = holes,
            count = count,
            requestedCount = requestedCount,
            radius = radius,
            requestedDiameter = p.lighteningHoleDiameter,
            pcdRadius = pcdRadius
        )
    }

    /** Circular lightening holes on a pitch circle (inside the tooth root). */
    fun lighteningHoles(p: GearParams): List<List<Vec2>> = lighteningPlan(p).holes

    /** Resolved hole radius (mm) used by mass/volume estimates. */
    fun lighteningHoleRadius(p: GearParams): Double = lighteningPlan(p).radius

    /**
     * Wedge-shaped cutouts between spokes: a ring of removed sectors leaves
     * [p.spokeCount] webs of width [p.spokeWidth] between the hub and the rim.
     */
    fun spokeWedgeHoles(p: GearParams): List<List<Vec2>> {
        if (p.spokeCount < 3 || p.spokeWidth <= 0.0) return emptyList()
        val rOuter = GearCalculator.rootRadius(p.module, p.teeth)
        // Spokes start just outside the bore; a hub boss (when present) widens the web.
        val boreR = if (p.bore.type == BoreType.NONE) 0.0 else p.bore.diameter / 2.0
        val hubR = if (HubBuilder.hasHub(p)) p.hubDiameter / 2.0 else 0.0
        val rInner = max(boreR + 1.0, hubR)
        if (rInner >= rOuter) return emptyList()
        val n = p.spokeCount
        val half = min(PI / n * 0.45, p.spokeWidth / (2.0 * rInner))
        val segs = 12
        return (0 until n).map { k ->
            val a1 = 2.0 * PI * k / n + half
            val a2 = 2.0 * PI * (k + 1) / n - half
            if (a2 <= a1) return@map emptyList<Vec2>()
            val loop = ArrayList<Vec2>()
            for (s in 0..segs) loop.add(Vec2.polar(rInner, a1 + (a2 - a1) * s / segs))
            for (s in segs downTo 0) loop.add(Vec2.polar(rOuter, a1 + (a2 - a1) * s / segs))
            loop
        }.filter { it.isNotEmpty() }
    }

    /** Small index/sensor marker on the rim (dot or radial slot). */
    fun indexMarkHoles(p: GearParams): List<List<Vec2>> {
        if (p.indexMarkType == "None") return emptyList()
        val rOuter = GearCalculator.outerRadius(p.module, p.teeth)
        val a = Math.toRadians(p.indexMarkAngleDeg)
        val r = 0.6
        return when (p.indexMarkType) {
            "Dot" -> listOf(round(r).map { Vec2(it.x + (rOuter - 0.8) * cos(a), it.y + (rOuter - 0.8) * sin(a)) })
            "Slot" -> listOf(
                (0 until 2).map { s -> Vec2((rOuter - 1.6) * cos(a), (rOuter - 1.6) * sin(a)).let { base ->
                    Vec2(base.x + (if (s == 0) -0.6 else 0.6) * cos(a + PI / 2), base.y + (if (s == 0) -0.6 else 0.6) * sin(a + PI / 2))
                } } + listOf(
                    Vec2((rOuter) * cos(a) + 0.6 * cos(a + PI / 2), (rOuter) * sin(a) + 0.6 * sin(a + PI / 2)),
                    Vec2((rOuter) * cos(a) - 0.6 * cos(a + PI / 2), (rOuter) * sin(a) - 0.6 * sin(a + PI / 2))
                )
            )
            else -> emptyList()
        }
    }
}
