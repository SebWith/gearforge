package com.gearforge.core

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
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
            val ra = rp + add * m
            val rf = rp - ded * m
            val rbL = rp * cos(alphaL)
            val rbR = rp * cos(alphaR)
            val thick = o?.toothThickness ?: (PI * m / 2.0)
            // Tooth + gap = π·m is enforced by validation; thickness only widens/narrows the flank.
            val s = thick + 2.0 * p.profileShift * m * tan(alphaL) - p.backlash
            val psi = s / (2.0 * rp)
            val invL = tan(alphaL) - alphaL
            val invR = tan(alphaR) - alphaR
            val rStartL = max(rbL, rf)
            val rStartR = max(rbR, rf)
            val thL = flankAngle(rbL, psi, invL, ra)
            val thR = flankAngle(rbR, psi, invR, ra)
            val thStartL = flankAngle(rbL, psi, invL, rStartL)
            val thStartR = flankAngle(rbR, psi, invR, rStartR)
            val c = i * pitch

            // left flank: root -> tip
            for (k in 0..steps) {
                val r = rStartL + (ra - rStartL) * k / steps
                pts.add(Vec2.polar(r, c - flankAngle(rbL, psi, invL, r)))
            }
            // tip arc: left -> right
            val tipSweep = thL + thR
            for (k in 0..rootSteps) {
                pts.add(Vec2.polar(ra, c - thL + tipSweep * k / rootSteps))
            }
            // right flank: tip -> root
            for (k in 0..steps) {
                val r = ra - (ra - rStartR) * k / steps
                pts.add(Vec2.polar(r, c + flankAngle(rbR, psi, invR, r)))
            }
            // root gap between this tooth and the next
            if (rStartR > rf) pts.add(Vec2.polar(rf, c + thStartR))
            val sweep = pitch - thStartR - thStartL
            for (k in 1..rootSteps) pts.add(Vec2.polar(rf, c + thStartR + sweep * k / rootSteps))
            if (rStartL > rf) pts.add(Vec2.polar(rbL, c + pitch - thStartL))
        }
        return dedupe(pts)
    }

    private fun flankAngle(rb: Double, psi: Double, invAlpha: Double, r: Double): Double {
        val phi = acos(rb / r)
        return psi + invAlpha - (tan(phi) - phi)
    }

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
            val ra = rp + add * m
            val rf = rp - ded * m
            val s = (o?.toothThickness ?: (PI * m / 2.0)) - p.backlash
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

    // ---- CYCLOID (simplified rounded-tooth approximation) ----
    fun cycloidSpur(p: GearParams): List<Vec2> {
        val m = p.module
        val n = p.teeth
        val rp = m * n / 2.0
        val pitch = 2.0 * PI / n
        val steps = flankSteps(p)
        val rootSteps = arcSteps(p)

        val pts = ArrayList<Vec2>()
        for (i in 0 until n) {
            val o = p.toothOverrides[i]
            val add = o?.addendumCoef ?: p.addendumCoef
            val ded = o?.dedendumCoef ?: p.dedendumCoef
            val ra = rp + add * m
            val rf = rp - ded * m
            val s = (o?.toothThickness ?: (PI * m / 2.0)) - p.backlash
            val psi = s / (2.0 * rp)
            val tipHalf = psi * 0.55
            val c = i * pitch
            // left flank: root -> tip (linear angle + radius sweep)
            for (k in 0..steps) {
                val t = k.toDouble() / steps
                pts.add(Vec2.polar(rf + (ra - rf) * t, c - psi + (psi - tipHalf) * t))
            }
            // rounded tip
            for (k in 0..rootSteps) {
                val a = c - tipHalf + 2.0 * tipHalf * k / rootSteps
                pts.add(Vec2.polar(ra, a))
            }
            // right flank: tip -> root
            for (k in 0..steps) {
                val t = k.toDouble() / steps
                pts.add(Vec2.polar(ra - (ra - rf) * t, c + tipHalf + (psi - tipHalf) * t))
            }
            // root gap
            val sweep = pitch - 2.0 * psi
            for (k in 1..rootSteps) pts.add(Vec2.polar(rf, c + psi + sweep * k / rootSteps))
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
