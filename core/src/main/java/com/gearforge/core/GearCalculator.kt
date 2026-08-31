package com.gearforge.core

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/** Pure gear mathematics: derived diameters, ratios and assembly geometry. */
object GearCalculator {

    const val STANDARD_ADDENDUM = 1.0       // * module
    const val STANDARD_DEDENDUM = 1.25      // * module

    fun pitchRadius(module: Double, teeth: Int) = module * teeth / 2.0
    fun pitchDiameter(module: Double, teeth: Int) = module * teeth
    fun outerRadius(module: Double, teeth: Int) = module * (teeth + 2.0 * STANDARD_ADDENDUM) / 2.0
    fun outerDiameter(module: Double, teeth: Int) = module * (teeth + 2.0 * STANDARD_ADDENDUM)
    fun rootRadius(module: Double, teeth: Int) = module * (teeth - 2.0 * STANDARD_DEDENDUM) / 2.0
    fun rootDiameter(module: Double, teeth: Int) = module * (teeth - 2.0 * STANDARD_DEDENDUM)
    fun baseRadius(module: Double, teeth: Int, pressureAngleDeg: Double) =
        module * teeth / 2.0 * cos(Math.toRadians(pressureAngleDeg))

    /**
     * Pitch diameter of a gear body. For helical gears the user-facing module is the
     * NORMAL module m_n, so the transverse pitch diameter is used (ISO 21771):
     * d = m_t·z = m_n·z / cos β.
     */
    fun pitchDiameter(p: GearParams): Double =
        if (p.gearType == GearType.HELICAL && p.helixAngleDeg != 0.0)
            p.module * p.teeth / cos(Math.toRadians(p.helixAngleDeg))
        else p.module * p.teeth

    /**
     * Tip (addendum) radius in the PROFILE plane with profile shift x:
     * r_a = m·z/2 + m·(h_a* + x). [module] is the module of the plane being generated
     * (the transverse module m_t for helical gears, which is what the profile
     * generator feeds in after converting m_n → m_t).
     */
    fun tipRadiusShifted(module: Double, teeth: Int, addendumCoef: Double, shift: Double): Double =
        module * teeth / 2.0 + module * (addendumCoef + shift)

    /**
     * Root (dedendum) radius in the PROFILE plane with profile shift x:
     * r_f = m·z/2 − m·(h_f* − x). A positive shift raises the root; a negative shift
     * deepens it (undercut).
     */
    fun rootRadiusShifted(module: Double, teeth: Int, dedendumCoef: Double, shift: Double): Double =
        module * teeth / 2.0 - module * (dedendumCoef - shift)

    /** Distance between the centres of two meshing external gears. */
    fun centerDistance(module: Double, teethA: Int, teethB: Int) =
        module * (teethA + teethB) / 2.0

    /** Angular-velocity ratio: driven / driver. */
    fun gearRatio(driverTeeth: Int, drivenTeeth: Int) = drivenTeeth.toDouble() / driverTeeth.toDouble()

    // ---- Planetary (epicyclic) ----
    fun ringTeeth(sunTeeth: Int, planetTeeth: Int) = sunTeeth + 2 * planetTeeth

    /** Reduction ratio with a fixed ring, sun input, planet-carrier output. */
    fun planetaryRatioFixedRing(sunTeeth: Int, ringTeeth: Int) =
        (ringTeeth + sunTeeth).toDouble() / sunTeeth.toDouble()

    // ---- Unit conversion ----
    fun moduleToDiametralPitch(module: Double) = 25.4 / module
    fun diametralPitchToModule(dp: Double) = 25.4 / dp

    /** Involute function inv(phi) = tan(phi) - phi. */
    fun involute(phi: Double) = tan(phi) - phi

    /**
     * Minimum tooth count before the involute undercuts a full-depth gear
     * (ISO 21771 / KHK): z_min = 2 / sin²(α). For α = 20°, z_min ≈ 17.1.
     */
    fun undercutThresholdTeeth(pressureAngleDeg: Double): Double {
        val s = sin(Math.toRadians(pressureAngleDeg))
        return 2.0 / (s * s)
    }

    /**
     * Minimum profile shift that fully avoids undercut for a full-depth gear
     * (h_a* = 1, ISO 21771): x_min = 1 − z·sin²(α) / 2.
     */
    fun minimumShiftNoUndercut(teeth: Int, pressureAngleDeg: Double): Double {
        val s = sin(Math.toRadians(pressureAngleDeg))
        return 1.0 - teeth * s * s / 2.0
    }

    /**
     * Tooth thickness (arc length) at an arbitrary radius (ISO 21771 / KHK):
     * s_r = r·(s_p/r_p − 2·(inv α_r − inv α)) with α_r = acos(r_b / r).
     * The pitch thickness s_p includes profile shift and subtracts the effective
     * backlash, so this returns the generated (clearance-adjusted) thickness.
     */
    fun toothThicknessAtRadius(p: GearParams, radius: Double): Double {
        val rp = pitchRadius(p.module, p.teeth)
        val sP = PI * p.module / 2.0 +
            2.0 * p.profileShift * p.module * tan(Math.toRadians(p.pressureAngleDeg)) -
            p.effectiveBacklashMm()
        val rb = baseRadius(p.module, p.teeth, p.pressureAngleDeg)
        val invA = involute(Math.toRadians(p.pressureAngleDeg))
        val invAr = involute(acos((rb / radius).coerceIn(-1.0, 1.0)))
        return radius * (sP / rp - 2.0 * (invAr - invA))
    }

    /** Top-land (tooth tip) width; 0 when the tip would degenerate. */
    fun topLandWidth(p: GearParams): Double =
        max(0.0, toothThicknessAtRadius(p, tipRadiusShifted(p.module, p.teeth, p.addendumCoef, p.profileShift)))

    /** Polar angle (rad) on the involute of a base circle at a given radius. */
    fun involutePolarAngle(baseRadius: Double, radius: Double, pressureAngleAtRadius: Double): Double {
        return involute(pressureAngleAtRadius)
    }

    /** Convenience: roll angle of an involute at radius r given base radius rb. */
    fun pressureAngleAt(baseRadius: Double, radius: Double): Double {
        val r = maxOf(baseRadius, radius)
        return kotlin.math.acos(baseRadius / r)
    }

    // ---- hub / boss (asymmetric) -----------------------------------------

    /** Effective left hub protrusion: hubLeftLength, or half of legacy hubLength when 0. */
    fun effectiveHubLeft(p: GearParams): Double =
        if (p.hubLeftLength > 0.0 || p.hubRightLength > 0.0) p.hubLeftLength else p.hubLength / 2.0

    /** Effective right hub protrusion: hubRightLength, or half of legacy hubLength when 0. */
    fun effectiveHubRight(p: GearParams): Double =
        if (p.hubLeftLength > 0.0 || p.hubRightLength > 0.0) p.hubRightLength else p.hubLength / 2.0

    /** True total width of the part including asymmetric hub protrusions. */
    fun totalWidth(p: GearParams): Double = p.thickness + effectiveHubLeft(p) + effectiveHubRight(p)

    // ---- mass / inertia (analytical) -------------------------------------

    /** Material density in kg/m³ for a material label. */
    fun densityKgM3(material: String): Double = when (material) {
        "Steel" -> 7850.0
        "Aluminium" -> 2700.0
        "Brass" -> 8470.0
        "PLA" -> 1240.0
        "PETG" -> 1270.0
        "Nylon" -> 1140.0
        "ABS" -> 1040.0
        else -> 7850.0
    }

    /**
     * Analytical solid volume (mm³) as a stack of cylinders: gear body, bore hole,
     * hub boss and lightening holes. Matches the mesh volume closely for standard gears.
     */
    fun volumeApprox(p: GearParams): Double {
        val rOut = outerRadius(p.module, p.teeth)
        val body = PI * rOut * rOut * p.thickness
        val boreR = p.bore.diameter / 2.0
        var bore = 0.0
        if (p.bore.type != BoreType.NONE) {
            val boreLen = totalWidth(p)
            bore = PI * boreR * boreR * boreLen
        }
        val hubL = effectiveHubLeft(p)
        val hubR = effectiveHubRight(p)
        val hub = PI * (p.hubDiameter / 2.0) * (p.hubDiameter / 2.0) * (hubL + hubR)
        val nHoles = p.lighteningHoleCount
        var holes = 0.0
        if (nHoles > 0) {
            // Use the resolved (auto-corrected) hole plan so the mass estimate
            // matches the actually generated geometry.
            val plan = Bore.lighteningPlan(p)
            if (plan.count > 0 && plan.radius > 0.0) {
                holes = plan.count * PI * plan.radius * plan.radius * p.thickness
            }
        }
        return max(0.0, body - bore + hub - holes)
    }

    /** Approximate mass in kilograms. */
    fun weightKg(p: GearParams): Double =
        if (p.gearType == GearType.BELT) beltWeightKg(p)
        else volumeApprox(p) / 1e9 * densityKgM3(p.material)

    /** Rough timing-belt tooth height (radial) used for mass estimates. */
    private fun beltToothHeight(p: GearParams): Double =
        max(0.6, p.toBeltTransmission().profile.pitchMm * 0.7)

    /** Approximate mass (kg) of a belt drive: driver, driven, idlers and the belt band. */
    fun beltWeightKg(p: GearParams): Double {
        val t = p.toBeltTransmission()
        val r = BeltCalculator.resolve(t)
        val dens = densityKgM3(p.material)
        val h = beltToothHeight(p)
        fun pulley(teeth: Int): Double {
            val rr = BeltCalculator.pitchDiameter(t.profile, teeth) / 2.0 + h
            return PI * rr * rr * t.beltWidthMm / 1e9 * dens
        }
        var mass = pulley(t.driverTeeth) + pulley(t.drivenTeeth)
        t.idlers.forEach { mass += pulley(it.teeth) }
        mass += r.beltLengthMm * t.beltWidthMm * h / 1e9 * dens
        return mass
    }

    /** Approximate moment of inertia (kg·m²) about the axis, as a solid disc. */
    fun momentOfInertia(p: GearParams): Double {
        if (p.gearType == GearType.BELT) return beltInertiaKgM2(p)
        val m = weightKg(p)
        val r = outerRadius(p.module, p.teeth) / 1000.0
        return 0.5 * m * r * r
    }

    /** Approximate inertia (kg·m²) of a belt drive reflected to the driver axis. */
    fun beltInertiaKgM2(p: GearParams): Double {
        val t = p.toBeltTransmission()
        val dens = densityKgM3(p.material)
        val h = beltToothHeight(p)
        fun pulley(teeth: Int): Double {
            val rr = BeltCalculator.pitchDiameter(t.profile, teeth) / 2.0 + h
            val m = PI * rr * rr * t.beltWidthMm / 1e9 * dens
            return 0.5 * m * (rr / 1000.0) * (rr / 1000.0)
        }
        val k = t.driverTeeth.toDouble() / t.drivenTeeth
        return pulley(t.driverTeeth) + pulley(t.drivenTeeth) * k * k +
            t.idlers.sumOf { pulley(it.teeth) }
    }

    /** Effective backlash accounting for the (potentially overridden) tooth thickness. */
    fun effectiveBacklash(p: GearParams): Double {
        if (p.gearType == GearType.BELT) return p.beltBacklashMm
        // Nominal tooth + gap = π·m; a thicker tooth (override) shrinks the gap.
        val nominalThick = PI * p.module / 2.0
        val maxThick = p.toothOverrides.values.mapNotNull { it.toothThickness }.maxOrNull() ?: nominalThick
        return max(0.0, p.effectiveBacklashMm() + (nominalThick - maxThick))
    }
}

