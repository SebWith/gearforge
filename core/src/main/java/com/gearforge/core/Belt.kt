package com.gearforge.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Timing-belt profiles with their nominal pitch in millimetres. */
enum class BeltProfile(val pitchMm: Double, val label: String) {
    GT2(2.0, "GT2"),
    HTD3M(3.0, "HTD 3M"),
    HTD5M(5.0, "HTD 5M"),
    HTD8M(8.0, "HTD 8M"),
    T5(5.0, "T5"),
    AT5(5.0, "AT5")
}

/** An idler / tensioner pulley. */
data class BeltIdler(
    val teeth: Int = 20,
    val offsetX: Double = 0.0,   // centre offset from the driver centre (mm)
    val offsetY: Double = 0.0
)

/** A parametric timing-belt transmission (pulleys + belt spans). */
data class BeltTransmission(
    val profile: BeltProfile = BeltProfile.GT2,
    val beltWidthMm: Double = 6.0,
    val driverTeeth: Int = 20,
    val drivenTeeth: Int = 40,
    val centerDistanceMm: Double = 0.0,  // 0 = auto
    val beltTeeth: Int = 0,              // 0 = auto
    val idlers: List<BeltIdler> = emptyList(),
    val flangeCount: Int = 2,
    val flangeDiameterMm: Double = 0.0,  // 0 = auto
    val tensionN: Double = 50.0,
    val backlashMm: Double = 0.0,
    val toleranceClass: String = "ISO 7",
    val bore: BoreSpec = BoreSpec(type = BoreType.ROUND, diameter = 5.0)
)

/** Maps a [GearParams] of type BELT onto a [BeltTransmission] for geometry generation. */
fun GearParams.toBeltTransmission(): BeltTransmission = BeltTransmission(
    profile = BeltProfile.entries.firstOrNull { it.label == beltProfile } ?: BeltProfile.GT2,
    beltWidthMm = beltWidthMm,
    driverTeeth = beltDriverTeeth,
    drivenTeeth = beltDrivenTeeth,
    centerDistanceMm = beltCenterDistanceMm,
    idlers = emptyList(),   // audit C10: two-pulley model; the belt band wraps driver+driven only
    flangeCount = beltFlangeCount,
    tensionN = beltTensionN,
    backlashMm = beltBacklashMm,
    toleranceClass = toleranceClass,
    bore = bore
)

/** A resolved transmission with concrete centre distance, belt length and ratio. */
data class ResolvedBelt(
    val pitchMm: Double,
    val driverPitchDia: Double,
    val drivenPitchDia: Double,
    val centerDistanceMm: Double,
    val beltTeeth: Int,
    val beltLengthMm: Double,
    val ratio: Double,
    val minEngagedTeeth: Int
)

object BeltCalculator {

    fun pitchDiameter(profile: BeltProfile, teeth: Int): Double = profile.pitchMm * teeth / PI

    fun ratio(t: BeltTransmission): Double =
        if (t.driverTeeth > 0) t.drivenTeeth.toDouble() / t.driverTeeth else 0.0

    /** Open-belt length between two pulleys: L = 2C + π/2(D₁+D₂) + (D₂−D₁)²/(4C). */
    fun beltLength(t: BeltTransmission, centerDistance: Double): Double {
        val d1 = pitchDiameter(t.profile, t.driverTeeth)
        val d2 = pitchDiameter(t.profile, t.drivenTeeth)
        val c = maxOf(centerDistance, (d1 + d2) / 2.0 + t.profile.pitchMm)
        return 2.0 * c + PI / 2.0 * (d1 + d2) + (d2 - d1) * (d2 - d1) / (4.0 * c)
    }

    /**
     * Resolves auto values and rounds the belt length to a whole number of teeth.
     * Centre distance is solved for when [BeltTransmission.centerDistanceMm] is 0;
     * otherwise the nearest valid belt-tooth count for the given distance is chosen.
     */
    fun resolve(t: BeltTransmission): ResolvedBelt {
        val d1 = pitchDiameter(t.profile, t.driverTeeth)
        val d2 = pitchDiameter(t.profile, t.drivenTeeth)
        var c = t.centerDistanceMm
        if (c <= 0.0) {
            // Seed centre distance ≈ sum of radii + 4 pitches, then refine.
            c = (d1 + d2) / 2.0 + 8.0 * t.profile.pitchMm
        }
        // Snap belt length to a whole number of teeth.
        val rawTeeth = beltLength(t, c) / t.profile.pitchMm
        val teeth = maxOf(6, ceil(rawTeeth).toInt())
        val length = teeth * t.profile.pitchMm
        // Re-solve centre distance for the chosen integer belt length (two-pulley formula).
        val a = d1 + d2
        val delta = d2 - d1
        // L = 2C + π/2·a + Δ²/(4C) → solve quadratic in C.
        val A = 2.0
        val B = PI / 2.0 * a - length
        val Cc = delta * delta / 4.0
        val disc = B * B - 4.0 * A * Cc
        val solvedC = if (disc >= 0.0) (-B + kotlin.math.sqrt(disc)) / (2.0 * A) else c
        val minEngaged = minEngagedTeeth(t.profile, d1, d2, solvedC)
        return ResolvedBelt(
            pitchMm = t.profile.pitchMm,
            driverPitchDia = d1,
            drivenPitchDia = d2,
            centerDistanceMm = solvedC,
            beltTeeth = teeth,
            beltLengthMm = length,
            ratio = ratio(t),
            minEngagedTeeth = minEngaged
        )
    }

    /** Approximate teeth in mesh on the smaller pulley. */
    fun minEngagedTeeth(profile: BeltProfile, d1: Double, d2: Double, c: Double): Int {
        val dSmall = minOf(d1, d2)
        val dBig = maxOf(d1, d2)
        if (dSmall <= 0.0 || c <= 0.0) return 0
        // Wrap angle on the small pulley = π − 2·asin((Dbig−Dsmall)/(2C)).
        val arg = ((dBig - dSmall) / (2.0 * c)).coerceIn(-1.0, 1.0)
        val wrap = PI - 2.0 * kotlin.math.asin(arg)
        val arc = wrap * dSmall / 2.0
        return (arc / profile.pitchMm).roundToInt()
    }

    fun validate(t: BeltTransmission): List<String> {
        val errors = ArrayList<String>()
        if (t.driverTeeth < 8 || t.drivenTeeth < 8) errors.add("pulleys need ≥ 8 teeth")
        if (t.beltWidthMm < t.profile.pitchMm) errors.add("belt narrower than pitch")
        if (t.driverTeeth == t.drivenTeeth && t.idlers.isEmpty()) errors.add("1:1 with no idler has low wrap")
        val r = resolve(t)
        if (r.minEngagedTeeth < 6) errors.add("only ${r.minEngagedTeeth} teeth in mesh (recommend ≥ 6)")
        return errors
    }
}

/** Builds pulley meshes and the 2D belt path for a transmission. */
object BeltBuilder {

    /** A pulley as a straight-tooth (grooved) cylinder whose tooth tops sit on the
     *  pitch circle, so the belt band can wrap the pitch line without penetration
     *  (audit H1). The grooves below the pitch line receive the belt teeth. */
    fun pulleyMesh(t: BeltTransmission, teeth: Int): Mesh {
        val module = t.profile.pitchMm / PI
        val params = GearParams(
            gearType = GearType.SPUR,
            toothProfile = ToothProfile.STRAIGHT,
            module = module,
            teeth = teeth,
            thickness = t.beltWidthMm,
            backlash = t.backlashMm,
            addendumCoef = 0.0,     // tooth top flush with the pitch circle
            dedendumCoef = 0.7,     // groove depth for the belt teeth
            bore = t.bore           // shaft bore (round, D-cut, keyway, hex or square)
        ).coerced()
        val body = GearBuilder.mesh(params)
        val flanges = flangeMeshes(t, params)
        return if (flanges.isEmpty()) body else MeshOps.merge(listOf(body) + flanges)
    }

    /**
     * Retaining flanges: thin annular discs at one or both pulley faces whose outer
     * radius exceeds the pulley's pitch radius so the belt is guided laterally. Each
     * disc carries the same shaft bore as the pulley body, so the bore profile runs
     * continuously through the flanges. The disc face coincides with the pulley end
     * face over the solid annulus (a touching union, like the hub boss), keeping the
     * merged pulley a closed 2-manifold.
     */
    private fun flangeMeshes(t: BeltTransmission, params: GearParams): List<Mesh> {
        val count = t.flangeCount.coerceIn(0, 2)
        if (count <= 0) return emptyList()
        val pitchR = GearCalculator.pitchRadius(params.module, params.teeth)
        val autoR = pitchR + max(2.0, t.profile.pitchMm)
        val flangeR = if (t.flangeDiameterMm > 0.0) max(pitchR + 1.0, t.flangeDiameterMm / 2.0) else autoR
        val thickness = max(0.8, t.profile.pitchMm * 0.4)
        val boreHole = Bore.holes(params).firstOrNull()
        val holes = if (boreHole != null) listOf(boreHole) else emptyList()
        fun disc(zBase: Double): Mesh {
            val m = MeshBuilder.extrude(PlanarShape(Bore.round(flangeR), holes), thickness)
            return Mesh(m.vertices.map { Vec3(it.x, it.y, it.z + zBase) }, m.triangles)
        }
        val result = ArrayList<Mesh>(2)
        if (count >= 1) result.add(disc(-thickness))
        if (count >= 2) result.add(disc(params.thickness))
        return result
    }

    data class BeltAssembly(
        val driver: Mesh,
        val driven: Mesh,
        val idlers: List<Mesh>,
        val driverCenter: Vec2,
        val drivenCenter: Vec2,
        val idlerCenters: List<Vec2>
    )

    fun assembly(t: BeltTransmission): BeltAssembly {
        val r = BeltCalculator.resolve(t)
        val driver = pulleyMesh(t, t.driverTeeth)
        val driven = pulleyMesh(t, t.drivenTeeth)
        val driverCenter = Vec2(0.0, 0.0)
        val drivenCenter = Vec2(r.centerDistanceMm, 0.0)
        val idlerMeshes = ArrayList<Mesh>()
        val idlerCenters = ArrayList<Vec2>()
        for (i in t.idlers) {
            idlerMeshes.add(pulleyMesh(t, i.teeth))
            idlerCenters.add(Vec2(i.offsetX, i.offsetY))
        }
        return BeltAssembly(driver, driven, idlerMeshes, driverCenter, drivenCenter, idlerCenters)
    }

    /** 2D belt path (outer band) for SVG/DXF visualisation. */
    fun beltPath2D(t: BeltTransmission): PlanarShape {
        val r = BeltCalculator.resolve(t)
        val backing = max(1.0, t.profile.pitchMm)
        val outer = beltLoop(
            Vec2(0.0, 0.0), r.driverPitchDia / 2.0 + backing,
            Vec2(r.centerDistanceMm, 0.0), r.drivenPitchDia / 2.0 + backing
        )
        return PlanarShape(outer, emptyList())
    }

    /** 3D belt band: the smooth back sits at pitch radius + backing, and the inner
     *  face carries trapezoidal teeth that project INWARD (toward the pulley centres)
     *  so they seat cleanly in the pulley grooves. The tooth tips stay above the
     *  groove bottom, so the band never penetrates the pulley bodies (audit H1/T6). */
    fun beltBandMesh(t: BeltTransmission): Mesh {
        val r = BeltCalculator.resolve(t)
        val backing = max(1.0, t.profile.pitchMm)
        val c1 = Vec2(0.0, 0.0)
        val c2 = Vec2(r.centerDistanceMm, 0.0)
        val r1 = r.driverPitchDia / 2.0
        val r2 = r.drivenPitchDia / 2.0
        val outer = beltLoop(c1, r1 + backing, c2, r2 + backing)
        // Belt tooth height matches the pulley groove depth (dedendum 0.7·m) minus a
        // small clearance, so the teeth seat in the grooves without bottoming out.
        val toothHeight = 0.6 * t.profile.pitchMm / PI
        val inner = toothedBeltInner(c1, r1, c2, r2, t.profile.pitchMm, toothHeight)
        if (outer.size < 3 || inner.size < 3) return Mesh(emptyList(), emptyList())
        return MeshBuilder.extrude(PlanarShape(outer, listOf(inner)), t.beltWidthMm)
    }

    /**
     * The belt's toothed inner boundary: the pitch-line loop around the two pulleys
     * with inward trapezoidal teeth at uniform pitch intervals. Teeth wrap the pulley
     * arcs and run along the straight spans, projecting toward the pulley centres.
     */
    fun toothedBeltInner(
        c1: Vec2, r1: Double, c2: Vec2, r2: Double,
        pitch: Double, toothHeight: Double
    ): List<Vec2> {
        val C = c1.dist(c2)
        if (C <= abs(r1 - r2) + 1e-9) {
            val center = if (r1 >= r2) c1 else c2
            return toothedCircle(center, max(r1, r2), pitch, toothHeight)
        }
        val theta = atan2(c2.y - c1.y, c2.x - c1.x)
        val beta = asin(((r2 - r1) / C).coerceIn(-1.0, 1.0))
        val aUp = theta + PI / 2.0 + beta
        val aLo = theta - PI / 2.0 - beta

        // Tangent points on the pitch line.
        fun arc(c: Vec2, r: Double, a: Double) = Vec2(c.x + r * cos(a), c.y + r * sin(a))
        val t2Up = arc(c2, r2, aUp)
        val t2Lo = arc(c2, r2, aLo)
        val t1Up = arc(c1, r1, aUp)
        val t1Lo = arc(c1, r1, aLo)

        // Loop traversal: driven arc (t2Up→t2Lo) → lower span (t2Lo→t1Lo) →
        // driver arc (t1Lo→t1Up) → upper span (t1Up→t2Up), then closes.
        val sweep2 = aLo - aUp                 // clockwise (negative)
        val arc2Len = abs(sweep2) * r2
        val sweep1 = (aUp - aLo) - 2.0 * PI    // the far/long way around c1 (negative)
        val arc1Len = abs(sweep1) * r1
        val spanLoLen = t1Lo.dist(t2Lo)
        val spanUpLen = t2Up.dist(t1Up)
        val total = arc2Len + spanLoLen + arc1Len + spanUpLen

        // Point + inward normal at arc-length s along the loop (starting at t2Up).
        fun pointAt(s: Double): Pair<Vec2, Vec2> {
            var rem = s
            if (rem < arc2Len) {
                val a = aUp + sweep2 * (rem / arc2Len)
                val p = arc(c2, r2, a)
                return p to Vec2(-cos(a), -sin(a))
            }
            rem -= arc2Len
            if (rem < spanLoLen) {
                val d = (t1Lo - t2Lo) / spanLoLen
                return (t2Lo + d * rem) to Vec2(d.y, -d.x)
            }
            rem -= spanLoLen
            if (rem < arc1Len) {
                val a = aLo + sweep1 * (rem / arc1Len)
                val p = arc(c1, r1, a)
                return p to Vec2(-cos(a), -sin(a))
            }
            rem -= arc1Len
            val d = (t2Up - t1Up) / spanUpLen
            return (t1Up + d * rem) to Vec2(d.y, -d.x)
        }

        val wTip = pitch * 0.45
        val wBase = pitch * 0.70
        val n = max(1, (total / pitch).roundToInt())
        val step = total / n
        val pts = ArrayList<Vec2>(n * 4)
        for (i in 0 until n) {
            val (p, inward) = pointAt((i + 0.5) * step)
            val t = Vec2(-inward.y, inward.x)          // tangent perpendicular to inward
            val bl = p - t * (wBase / 2.0)
            val br = p + t * (wBase / 2.0)
            val tl = p + inward * toothHeight - t * (wTip / 2.0)
            val tr = p + inward * toothHeight + t * (wTip / 2.0)
            pts.add(bl); pts.add(tl); pts.add(tr); pts.add(br)
        }
        return pts
    }

    /** Single toothed circle (degenerate belt loop where one pulley nests the other). */
    private fun toothedCircle(c: Vec2, r: Double, pitch: Double, toothHeight: Double): List<Vec2> {
        val n = max(1, (2.0 * PI * r / pitch).roundToInt())
        val wTip = pitch * 0.45
        val wBase = pitch * 0.70
        val pts = ArrayList<Vec2>(n * 4)
        for (i in 0 until n) {
            val a = 2.0 * PI * (i + 0.5) / n
            val p = Vec2(c.x + r * cos(a), c.y + r * sin(a))
            val inward = Vec2(-cos(a), -sin(a))
            val t = Vec2(-inward.y, inward.x)
            pts.add(p - t * (wBase / 2.0))
            pts.add(p + inward * toothHeight - t * (wTip / 2.0))
            pts.add(p + inward * toothHeight + t * (wTip / 2.0))
            pts.add(p + t * (wBase / 2.0))
        }
        return pts
    }

    /**
     * Closed belt-loop outline around two pulleys: two circular arcs joined by their
     * external tangent spans. Degenerate (nested) circles fall back to a single circle.
     */
    fun beltLoop(c1: Vec2, r1: Double, c2: Vec2, r2: Double, segs: Int = 72): List<Vec2> {
        val C = c1.dist(c2)
        if (C <= abs(r1 - r2) + 1e-9) {
            val big = max(r1, r2)
            val center = if (r1 >= r2) c1 else c2
            return (0 until segs).map { k ->
                val a = 2.0 * PI * k / segs
                Vec2(center.x + big * cos(a), center.y + big * sin(a))
            }
        }
        val theta = atan2(c2.y - c1.y, c2.x - c1.x)
        val beta = asin(((r2 - r1) / C).coerceIn(-1.0, 1.0))
        val aUp = theta + PI / 2.0 + beta
        val aLo = theta - PI / 2.0 - beta

        val pts = ArrayList<Vec2>(2 * segs + 2)
        // Arc around the second pulley: upper tangent → lower tangent (the far, outer side).
        val sweep2 = aLo - aUp
        for (k in 0..segs) {
            val a = aUp + sweep2 * k / segs
            pts.add(Vec2(c2.x + r2 * cos(a), c2.y + r2 * sin(a)))
        }
        // Arc around the first pulley: lower tangent → upper tangent (the far, outer side).
        val sweep1 = (aUp - aLo) - 2.0 * PI
        for (k in 0..segs) {
            val a = aLo + sweep1 * k / segs
            pts.add(Vec2(c1.x + r1 * cos(a), c1.y + r1 * sin(a)))
        }
        return pts
    }
}
