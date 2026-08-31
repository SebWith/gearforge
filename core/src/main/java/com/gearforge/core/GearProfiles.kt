package com.gearforge.core

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Generates closed 2D boundary polygons for gear teeth.
 *
 * Spur/helical/bevel use the same transverse tooth profile (helical and bevel add
 * twist/taper at the mesh stage). The planetary ring uses an internal profile.
 */
object GearProfiles {

    fun outline(p: GearParams): List<Vec2> = when (p.gearType) {
        GearType.RACK -> rackOutline(p)
        else -> externalOutline(p)
    }

    fun externalOutline(p: GearParams): List<Vec2> = when (p.toothProfile) {
        ToothProfile.INVOLUTE -> involuteSpur(p)
        ToothProfile.CYCLOID -> cycloidSpur(p)
        ToothProfile.STRAIGHT -> straightSpur(p)
    }

    fun ringOutline(p: GearParams): List<Vec2> = internalRingOutline(p)

    private fun flankSteps(p: GearParams) = when (p.precision) {
        PrecisionLevel.HOBBY -> 10
        PrecisionLevel.STANDARD -> 22
        PrecisionLevel.HIGH -> 44
    }

    private fun arcSteps(p: GearParams) = max(3, flankSteps(p) / 3)

    // ---- INVOLUTE (external spur) ----
    fun involuteSpur(p: GearParams): List<Vec2> {
        val m = p.module
        val n = p.teeth
        val rp = m * n / 2.0
        val pitch = 2.0 * PI / n
        val steps = flankSteps(p)
        val rootSteps = arcSteps(p)

        val pts = ArrayList<Vec2>(n * (2 * steps + 2 * rootSteps + 4))
        for (i in 0 until n) {
            // Resolve per-tooth override; null fields inherit the global value.
            val o = p.toothOverrides[i]
            val alphaL = Math.toRadians(o?.leftPressureAngleDeg ?: p.pressureAngleDeg)
            val alphaR = Math.toRadians(o?.rightPressureAngleDeg ?: p.pressureAngleDeg)
            val add = o?.addendumCoef ?: p.addendumCoef
            val ded = o?.dedendumCoef ?: p.dedendumCoef
            // Profile shift moves the whole tooth radially (ISO 21771 / KHK):
            //   r_a = r_p + m·(h_a* + x),   r_f = r_p − m·(h_f* − x).
            // Positive x enlarges the tip and raises the root; negative x deepens it.
            val x = p.profileShift
            val ra = rp + (add + x) * m
            val rf = rp - (ded - x) * m
            val rbL = rp * cos(alphaL)
            val rbR = rp * cos(alphaR)
            val thick = o?.toothThickness ?: (PI * m / 2.0)
            // Tooth thickness at the pitch circle includes the profile shift:
            //   s = π·m/2 + 2·x·m·tan(α)
            // Backlash is applied as a uniform flank clearance: reducing the half-tooth
            // thickness angle shifts the involute flank inward along its normal (an
            // involute offset is equivalent to an angular shift of the generating
            // point). Asymmetric clearance subtracts a different amount on each flank.
            val s0 = thick + 2.0 * p.profileShift * m * tan(alphaL)
            val bSym = p.effectiveBacklashMm()
            val bL = (if (p.backlashLeftMm > 0.0) p.backlashLeftMm else bSym) / 2.0
            val bR = (if (p.backlashRightMm > 0.0) p.backlashRightMm else bSym) / 2.0
            val psiL = (s0 / 2.0 - bL) / rp
            val psiR = (s0 / 2.0 - bR) / rp
            val invL = tan(alphaL) - alphaL
            val invR = tan(alphaR) - alphaR
            val rStartL = max(rbL, rf)
            val rStartR = max(rbR, rf)
            val thL = flankAngle(rbL, psiL, invL, ra)
            val thR = flankAngle(rbR, psiR, invR, ra)
            val thStartL = flankAngle(rbL, psiL, invL, rStartL)
            val thStartR = flankAngle(rbR, psiR, invR, rStartR)
            val c = i * pitch

            // 45° tip chamfer (mm): the flank ends at ra−ch and the tip arc is
            // shortened by ch along the arc so the chamfer segment is 45° (audit M3).
            val tipCh = (o?.tipChamfer ?: p.tipChamfer).coerceIn(0.0, (ra - rf) * 0.5)
            val chAngle = if (tipCh > 0.0 && ra > 0.0) tipCh / ra else 0.0

            // Degenerate-tip guard: at very high pressure angles or deep negative shift
            // the involute flanks meet before the tip radius, which would self-intersect
            // the outline. Truncate the tooth at the crossing radius so it ends in a
            // point and the mesh stays watertight (audit H2).
            var raTip = ra - tipCh
            val tipSweep = thL + thR - 2.0 * chAngle
            if (tipSweep <= 1e-12) {
                val rCap = minOf(
                    flankCrossingRadius(rbL, psiL, invL, rStartL, ra),
                    flankCrossingRadius(rbR, psiR, invR, rStartR, ra)
                )
                raTip = rCap.coerceIn(rStartL, ra)
            }

            // left flank: root -> tip
            for (k in 0..steps) {
                val r = rStartL + (raTip - rStartL) * k / steps
                pts.add(Vec2.polar(r, c - flankAngle(rbL, psiL, invL, r)))
            }
            // tip arc: left -> right (with chamfer offsets); a degenerate tip collapses
            // to a single shared point.
            if (tipSweep > 1e-12) {
                for (k in 0..rootSteps) {
                    pts.add(Vec2.polar(ra, c - thL + chAngle + tipSweep * k / rootSteps))
                }
            } else {
                pts.add(Vec2.polar(raTip, c))
            }
            // right flank: tip -> root
            for (k in 0..steps) {
                val r = raTip - (raTip - rStartR) * k / steps
                pts.add(Vec2.polar(r, c + flankAngle(rbR, psiR, invR, r)))
            }
            // root gap between this tooth and the next, with a root fillet at each end
            // (audit M2/M3: rootFilletCoef replaces the old sharp radial drop). When the
            // fillet radius is too small to be tangent to both the root circle and the
            // flank start (deep undercut), rootFilletArc falls back to a straight chord
            // so the mesh stays watertight (audit L6: planetary m=0.5 shift=−0.5).
            val fr = (o?.rootFilletCoef ?: p.rootFilletCoef) * m
            // Root-gap arc bounds = the fillet root-tangency angles on each side.
            val centerR = if (rStartR > rf) filletCenter(rf, fr, rStartR, c + thStartR, +1) else null
            val centerL = if (rStartL > rf) filletCenter(rf, fr, rStartL, c + pitch - thStartL, -1) else null
            val thetaR = centerR?.let { atan2(it.second, it.first) } ?: (c + thStartR)
            val thetaL = centerL?.let { atan2(it.second, it.first) } ?: (c + pitch - thStartL)
            if (centerR != null) rootFilletArc(pts, rf, fr, rStartR, c + thStartR, +1)
            else pts.add(Vec2.polar(rf, c + thStartR))
            // Root-gap arc must take the SHORT way between the two fillet root-
            // tangency angles. atan2 wraps each angle into (−π, π], so a tooth
            // whose gap straddles the ±π branch cut yields thetaL − thetaR ≈ −2π
            // instead of the true small gap; normalizing to (−π, π] restores the
            // correct short arc (audit H3: profileShift=−1, z=17 self-intersection).
            var sweep = thetaL - thetaR
            while (sweep > PI) sweep -= 2.0 * PI
            while (sweep <= -PI) sweep += 2.0 * PI
            for (k in 1..rootSteps) pts.add(Vec2.polar(rf, thetaR + sweep * k / rootSteps))
            if (centerL != null) rootFilletArc(pts, rf, fr, rStartL, c + pitch - thStartL, -1, reverse = true)
            else pts.add(Vec2.polar(rf, c + pitch - thStartL))
        }
        return dedupe(pts)
    }

    private fun flankAngle(rb: Double, psi: Double, invAlpha: Double, r: Double): Double {
        // Defensive clamp: r ≥ rb by construction, but floating-point rounding at the
        // base-circle boundary can push rb/r marginally above 1, which would make
        // acos return NaN and poison the whole profile (audit: boundary stress).
        val phi = acos((rb / r).coerceIn(-1.0, 1.0))
        return psi + invAlpha - (tan(phi) - phi)
    }

    /**
     * Radius where an involute flank reaches the tooth centre line (flankAngle = 0),
     * found by bisecting tan(phi) − phi = psi + inv α for phi = acos(rb / r). Used to
     * cap a degenerate tooth tip so the outline never self-intersects (audit H2).
     */
    private fun flankCrossingRadius(rb: Double, psi: Double, invAlpha: Double, rMin: Double, rMax: Double): Double {
        val target = psi + invAlpha
        if (target <= 0.0) return rMax
        var lo = 0.0
        var hi = PI / 2.0 - 1e-9
        repeat(80) {
            val mid = (lo + hi) / 2.0
            if (tan(mid) - mid < target) lo = mid else hi = mid
        }
        val phi = (lo + hi) / 2.0
        return (rb / cos(phi)).coerceIn(rMin, rMax)
    }

    /**
     * Fillet-circle centre that is tangent to the root circle (radius rf + fr from the
     * origin) and passes exactly through the flank start at [thetaStart]/[rStart]. Returns
     * the centre and whether the exact tangent intersection existed; when [fr] is too small
     * to reach both, the nearest root-offset point is used (tangent = false).
     * [sign] selects the gap side (+1 toward +θ, −1 toward −θ).
     */
    private fun filletCenter(rf: Double, fr: Double, rStart: Double, thetaStart: Double, sign: Int): Triple<Double, Double, Boolean> {
        val rc = rf + fr
        val d = rStart
        val a = (d * d + rc * rc - fr * fr) / (2.0 * d)
        val hSq = rc * rc - a * a
        if (hSq > 0.0) {
            val h = sqrt(hSq)
            val px = a * cos(thetaStart)
            val py = a * sin(thetaStart)
            val ux = -sin(thetaStart) * sign
            val uy = cos(thetaStart) * sign
            return Triple(px + h * ux, py + h * uy, true)
        }
        val thetaC = thetaStart + sign * asin((fr / rc).coerceIn(-1.0, 1.0))
        return Triple(rc * cos(thetaC), rc * sin(thetaC), false)
    }

    /**
     * Appends a circular root-fillet arc of radius [fr] that connects the flank start
     * (at radius [rStart], angle [thetaStart]) to the root circle, tangent to the root
     * circle. When the fillet circle can reach both the root circle and the flank start,
     * the arc passes exactly through the flank start (no gap) and takes the SHORT way
     * around the fillet circle (≤ 180°), which keeps it inside the root gap instead of
     * bulging out into the flanks (the former long-way sweep produced a cylindrical
     * protrusion between the teeth). For degenerate undercut profiles where the fillet
     * radius is too small to reach both, the previous connecting arc is preserved.
     * Returns the root-tangency angle so the caller can size the root gap arc.
     * [sign] = +1 for the right flank (+θ gap), −1 for the left flank;
     * [reverse] walks the arc root→flank instead of flank→root.
     */
    private fun rootFilletArc(
        pts: ArrayList<Vec2>,
        rf: Double,
        fr: Double,
        rStart: Double,
        thetaStart: Double,
        sign: Int,
        reverse: Boolean = false
    ): Double {
        if (fr <= 1e-9 || rStart <= rf + 1e-9) {
            pts.add(Vec2.polar(rf, thetaStart))
            return thetaStart
        }
        val p1x = rStart * cos(thetaStart)
        val p1y = rStart * sin(thetaStart)
        val (cx, cy, tangent) = filletCenter(rf, fr, rStart, thetaStart, sign)
        val rootAngle = atan2(cy, cx)
        if (!tangent) {
            // Fillet radius too small to be tangent to both the root circle and the
            // flank start (deep undercut): connect them with a straight chord. The
            // chord always stays inside the tooth gap and keeps the polygon simple; it
            // is a straight-wall approximation of the true trochoid undercut curve.
            val rx = rf * cos(rootAngle)
            val ry = rf * sin(rootAngle)
            val steps = 5
            val order = if (reverse) (steps downTo 1) else (1..steps)
            for (k in order) {
                val t = k / steps.toDouble()
                pts.add(Vec2(p1x + (rx - p1x) * t, p1y + (ry - p1y) * t))
            }
            return rootAngle
        }
        val a1 = atan2(p1y - cy, p1x - cx)
        val a2 = atan2(-cy, -cx)
        var sweep = a2 - a1
        // Short-way sweep from the flank (a1) to the root tangency (a2), in (−π, π].
        while (sweep > PI) sweep -= 2.0 * PI
        while (sweep <= -PI) sweep += 2.0 * PI
        val steps = 5
        val order = if (reverse) (steps downTo 1) else (1..steps)
        for (k in order) {
            val ang = a1 + sweep * k / steps
            pts.add(Vec2(cx + fr * cos(ang), cy + fr * sin(ang)))
        }
        // Root-tangency angle (polar angle of T2 from the origin).
        return rootAngle
    }

    /** True when an involute profile has an undercut (base circle lies above the root). */
    fun hasUndercut(p: GearParams): Boolean =
        p.gearType !in setOf(GearType.RACK, GearType.BELT) &&
            p.toothProfile == ToothProfile.INVOLUTE &&
            GearCalculator.baseRadius(p.module, p.teeth, p.pressureAngleDeg) >
            GearCalculator.rootRadiusShifted(p.module, p.teeth, p.dedendumCoef, p.profileShift)

    // ---- STRAIGHT (trapezoid external spur) ----
    fun straightSpur(p: GearParams): List<Vec2> {
        val m = p.module
        val n = p.teeth
        val rp = m * n / 2.0
        val pitch = 2.0 * PI / n
        val rootSteps = arcSteps(p)

        val pts = ArrayList<Vec2>()
        for (i in 0 until n) {
            val o = p.toothOverrides[i]
            val add = o?.addendumCoef ?: p.addendumCoef
            val ded = o?.dedendumCoef ?: p.dedendumCoef
            // Profile shift (ISO 21771 / KHK): r_a = r_p + m·(h_a* + x),
            // r_f = r_p − m·(h_f* − x); the tooth also widens by 2·x·m·tan(α).
            val x = p.profileShift
            val ra = rp + (add + x) * m
            val rf = rp - (ded - x) * m
            val s = (o?.toothThickness ?: (PI * m / 2.0)) +
                2.0 * x * m * tan(Math.toRadians(p.pressureAngleDeg)) - p.backlash
            val psi = s / (2.0 * rp)
            val tipHalf = psi * 0.7
            val c = i * pitch
            pts.add(Vec2.polar(rf, c - psi))      // left root
            pts.add(Vec2.polar(ra, c - tipHalf))  // left tip
            pts.add(Vec2.polar(ra, c + tipHalf))  // right tip
            pts.add(Vec2.polar(rf, c + psi))      // right root
            val sweep = pitch - 2.0 * psi
            for (k in 1..rootSteps) pts.add(Vec2.polar(rf, c + psi + sweep * k / rootSteps))
        }
        return dedupe(pts)
    }

    // ---- CYCLOID (true epicycloid/hypocycloid flanks) ----
    /**
     * True cycloidal tooth profile: the addendum flank is an epicycloid (a generating
     * circle of radius R/4 rolling outside the pitch circle) and the dedendum flank is a
     * hypocycloid (rolling inside). The tooth is widest at the pitch circle and narrows
     * toward both tip and root, as in clockwork gearing.
     */
    fun cycloidSpur(p: GearParams): List<Vec2> {
        val m = p.module
        // Cycloidal flanks (R/4 generating circle + full addendum) cannot exist below
        // 6 teeth: the flank would overrun the tooth space. Clamp defensively so a raw
        // (uncoerced) call can never emit a degenerate profile.
        val n = max(6, p.teeth)
        val R = m * n / 2.0          // pitch radius
        val rGen = R / 4.0           // generating-circle radius
        val ra = R + m               // tip (addendum)
        val rf = R - 1.25 * m        // root (dedendum)
        val pitch = 2.0 * PI / n
        val steps = flankSteps(p)
        val rootSteps = arcSteps(p)

        // Half tooth thickness at the pitch circle (tooth = gap).
        val psi = pitch / 4.0
        val maxExtent = psi * 0.9   // flank angular extent must stay inside the tooth

        val kE = (R + rGen) / rGen
        val kH = (R - rGen) / rGen

        fun epiX(t: Double) = (R + rGen) * cos(t) - rGen * cos(kE * t)
        fun epiY(t: Double) = (R + rGen) * sin(t) - rGen * sin(kE * t)
        fun hypoX(t: Double) = (R - rGen) * cos(t) + rGen * cos(kH * t)
        fun hypoY(t: Double) = (R - rGen) * sin(t) - rGen * sin(kH * t)

        // Parameter where the epicycloid reaches the tip radius (analytical).
        val tEraw = (2.0 * rGen / R) * asin(
            sqrt(((ra * ra - R * R) / (4.0 * (R + rGen) * rGen)).coerceIn(0.0, 1.0))
        )
        val tHraw = (rGen / R) * acos(
            ((rf * rf - (R - rGen) * (R - rGen) - rGen * rGen) / (2.0 * (R - rGen) * rGen)).coerceIn(-1.0, 1.0)
        )
        // Clamp the parameter so the flank's angular extent never exceeds the tooth
        // (necessary for very low tooth counts where R/4 is too large a generator).
        fun angleE(t: Double) = atan2(epiY(t), epiX(t))
        fun angleH(t: Double) = atan2(hypoY(t), hypoX(t))
        fun clamp(tMax: Double, angleAt: (Double) -> Double): Double {
            if (angleAt(tMax) <= maxExtent) return tMax
            var lo = 0.0
            var hi = tMax
            repeat(60) {
                val mid = (lo + hi) / 2.0
                if (angleAt(mid) < maxExtent) lo = mid else hi = mid
            }
            return (lo + hi) / 2.0
        }
        val tE = clamp(tEraw, ::angleE)
        val tH = clamp(tHraw, ::angleH)

        // Sample the epicycloid (pitch → tip) and hypocycloid (root → pitch).
        val epiAngles = DoubleArray(steps + 1)
        val epiRadii = DoubleArray(steps + 1)
        for (k in 0..steps) {
            val t = tE * k / steps
            epiRadii[k] = hypot(epiX(t), epiY(t))
            epiAngles[k] = atan2(epiY(t), epiX(t))
        }
        val hypAngles = DoubleArray(steps + 1)
        val hypRadii = DoubleArray(steps + 1)
        for (k in 0..steps) {
            val t = tH * (1.0 - k.toDouble() / steps) // root (tH) → pitch (0)
            hypRadii[k] = hypot(hypoX(t), hypoY(t))
            hypAngles[k] = atan2(hypoY(t), hypoX(t))
        }
        val phiE = minOf(epiAngles[steps], maxExtent)
        val phiH = minOf(hypAngles[0], maxExtent)
        // Actual tip/root radii after any clamping (must match the flank endpoints).
        val raEff = epiRadii[steps]
        val rfEff = hypRadii[0]

        val pts = ArrayList<Vec2>(n * (2 * steps + 2 * rootSteps + 4))
        for (i in 0 until n) {
            val c = i * pitch
            // left dedendum: root → pitch
            for (k in 0..steps) pts.add(Vec2.polar(hypRadii[k], c - psi + hypAngles[k]))
            // left addendum: pitch → tip
            for (k in 0..steps) pts.add(Vec2.polar(epiRadii[k], c - psi + epiAngles[k]))
            // tip arc
            val tipStart = c - psi + phiE
            for (k in 1..rootSteps) pts.add(Vec2.polar(raEff, tipStart + 2.0 * (psi - phiE) * k / rootSteps))
            // right addendum: tip → pitch (mirrored)
            for (k in steps downTo 0) pts.add(Vec2.polar(epiRadii[k], c + psi - epiAngles[k]))
            // right dedendum: pitch → root (mirrored)
            for (k in steps downTo 0) pts.add(Vec2.polar(hypRadii[k], c + psi - hypAngles[k]))
            // root arc
            val rootStart = c + psi - phiH
            val sweep = (c + pitch - psi + phiH) - rootStart
            for (k in 1..rootSteps) pts.add(Vec2.polar(rfEff, rootStart + sweep * k / rootSteps))
        }
        return dedupe(pts)
    }

    // ---- INTERNAL RING (trapezoid inward teeth, for planetary) ----
    fun internalRingOutline(p: GearParams): List<Vec2> {
        val m = p.module
        val n = p.teeth
        val rp = m * n / 2.0
        val rOut = rp + 1.25 * m
        val rIn = rp - m
        require(rIn < rOut) { "Ring inner radius ($rIn) must be < outer radius ($rOut)" }
        val s = PI * m / 2.0 - p.backlash
        val psi = s / (2.0 * rp)
        val tipHalf = psi * 0.7
        val pitch = 2.0 * PI / n
        val rootSteps = arcSteps(p)

        val pts = ArrayList<Vec2>()
        for (i in 0 until n) {
            val c = i * pitch
            pts.add(Vec2.polar(rOut, c - psi))
            pts.add(Vec2.polar(rIn, c - tipHalf))
            pts.add(Vec2.polar(rIn, c + tipHalf))
            pts.add(Vec2.polar(rOut, c + psi))
            val sweep = pitch - 2.0 * psi
            for (k in 1..rootSteps) pts.add(Vec2.polar(rOut, c + psi + sweep * k / rootSteps))
        }
        return dedupe(pts)
    }

    // ---- RACK (straight-flank teeth along +x) ----
    /** Number of whole teeth that fit in the rack's authoritative [GearParams.rackLength]. */
    fun rackTeeth(p: GearParams): Int =
        if (p.module > 0.0) max(1, (p.rackLength / (PI * p.module)).roundToInt()) else 3

    fun rackOutline(p: GearParams): List<Vec2> {
        val m = p.module
        val alpha = Math.toRadians(p.pressureAngleDeg)
        val pitch = PI * m
        val addendum = m
        val dedendum = 1.25 * m
        // rack_length is authoritative (audit C1): snap the bar to a whole number of teeth.
        val teeth = rackTeeth(p)
        val length = teeth * pitch
        val barDepth = 6.0
        val yTop = addendum
        val yRoot = -dedendum
        val yBottom = yRoot - barDepth
        val halfAtPitch = pitch / 4.0 - p.backlash / 2.0

        val pts = ArrayList<Vec2>()
        pts.add(Vec2(0.0, yBottom))
        pts.add(Vec2(0.0, yRoot))
        for (k in 0 until teeth) {
            val xc = k * pitch + pitch / 2.0
            pts.add(Vec2(xc - halfAtPitch - dedendum * tan(alpha), yRoot))
            pts.add(Vec2(xc - halfAtPitch + addendum * tan(alpha), yTop))
            pts.add(Vec2(xc + halfAtPitch - addendum * tan(alpha), yTop))
            pts.add(Vec2(xc + halfAtPitch + dedendum * tan(alpha), yRoot))
        }
        pts.add(Vec2(length, yRoot))
        pts.add(Vec2(length, yBottom))
        return dedupe(pts)
    }

    fun dedupe(pts: List<Vec2>, eps: Double = 1e-9): List<Vec2> {
        val out = ArrayList<Vec2>(pts.size)
        for (pt in pts) {
            if (out.isEmpty() || out.last().dist(pt) > eps) out.add(pt)
        }
        if (out.size > 1 && out.first().dist(out.last()) <= eps) out.removeAt(out.size - 1)
        return out
    }
}
