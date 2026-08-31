package com.gearforge.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** The kinds of gears the generator can produce. */
enum class GearType {
    SPUR, HELICAL, BEVEL, RACK, PLANETARY,
    WORM_PAIR, INTERNAL_RING, HYPOID, CYCLOIDAL,
    HARMONIC_DRIVE, FACE_GEAR, SCREW_GEAR, COMPOUND, BELT
}

/** Tooth profile standards. */
enum class ToothProfile { INVOLUTE, CYCLOID, STRAIGHT }

/** Center bore / shaft attachment types. */
enum class BoreType { NONE, ROUND, D_CUT, KEYWAY, HEX, SQUARE }

/** Display / export unit systems. */
enum class UnitSystem { MM, INCH }

/** Print fidelity presets that drive mesh resolution and clearance defaults. */
enum class PrecisionLevel { HOBBY, STANDARD, HIGH }

/** Parameters describing the center bore of a gear. All lengths in millimetres. */
data class BoreSpec(
    val type: BoreType = BoreType.ROUND,
    val diameter: Double = 5.0,
    val dCutFlatOffset: Double = 1.0,   // distance from centre to the flat of a D-cut
    val keywayWidth: Double = 2.0,
    val keywayDepth: Double = 1.0,      // depth measured from the bore wall
    val hexAcrossFlats: Double = 6.0,
    val squareAcrossFlats: Double = 6.0,
    val keywayStandard: Boolean = false,  // DIN 6885 keyway: derive width/depth from bore diameter
    val dCutSecondFlat: Boolean = false   // Double-D shaft: two flats (D_CUT only)
)

/**
 * Per-tooth override of the flank profile. Null fields inherit the gear's global
 * value, so an override only has to specify the fields it changes. Enables
 * asymmetric left/right flanks and local per-tooth exceptions.
 */
data class ToothOverride(
    val leftPressureAngleDeg: Double? = null,   // left flank pressure angle (°)
    val rightPressureAngleDeg: Double? = null,  // right flank pressure angle (°)
    val toothThickness: Double? = null,         // arc thickness at pitch circle (mm); default π·m/2
    val addendumCoef: Double? = null,           // × module
    val dedendumCoef: Double? = null,           // × module
    val rootFilletCoef: Double? = null,         // root fillet radius, × module
    val tipChamfer: Double? = null,             // 45° tip chamfer (mm)
    val tipRelief: Double? = null,              // tip relief along flank (mm)
    val rootRelief: Double? = null,             // root relief along flank (mm)
    val transitionCoef: Double? = null          // flank→root transition radius, × module
) {
    /** Drops non-finite or non-physical values (per-tooth angles must be < 90°). */
    fun coerced(): ToothOverride = copy(
        leftPressureAngleDeg = leftPressureAngleDeg?.takeIf { it.isFinite() && it > 0.0 && it < 89.0 },
        rightPressureAngleDeg = rightPressureAngleDeg?.takeIf { it.isFinite() && it > 0.0 && it < 89.0 },
        toothThickness = toothThickness?.takeIf { it.isFinite() && it > 0.0 },
        addendumCoef = addendumCoef?.takeIf { it.isFinite() && it > 0.0 },
        dedendumCoef = dedendumCoef?.takeIf { it.isFinite() && it > 0.0 },
        rootFilletCoef = rootFilletCoef?.takeIf { it.isFinite() && it >= 0.0 },
        tipChamfer = tipChamfer?.takeIf { it.isFinite() && it >= 0.0 },
        tipRelief = tipRelief?.takeIf { it.isFinite() && it >= 0.0 },
        rootRelief = rootRelief?.takeIf { it.isFinite() && it >= 0.0 },
        transitionCoef = transitionCoef?.takeIf { it.isFinite() && it >= 0.0 }
    )
}

/**
 * Full parameter set for a single gear. Metric module is the canonical unit;
 * inch users convert diametral pitch via [GearCalculator].
 */
data class GearParams(
    val gearType: GearType = GearType.SPUR,
    val toothProfile: ToothProfile = ToothProfile.INVOLUTE,
    val module: Double = 1.0,               // mm
    val teeth: Int = 20,
    val pressureAngleDeg: Double = 20.0,
    val thickness: Double = 6.0,            // face width, mm
    val backlash: Double = 0.1,             // circumferential clearance, mm
    val backlashPercent: Double = 0.0,      // clearance as % of module; >0 overrides [backlash]
    val backlashLeftMm: Double = 0.0,       // asymmetric left-flank clearance (mm); 0 = symmetric
    val backlashRightMm: Double = 0.0,      // asymmetric right-flank clearance (mm); 0 = symmetric
    val minimumTopLandWidth: Double = 0.0,  // min top-land width (mm); 0 = auto (0.2·m)
    val profileShift: Double = 0.0,         // involute profile shift coefficient
    val helixAngleDeg: Double = 0.0,        // helical only
    val bore: BoreSpec = BoreSpec(),
    val precision: PrecisionLevel = PrecisionLevel.STANDARD,
    val unit: UnitSystem = UnitSystem.MM,
    // ---- geometry extras ----
    val addendumCoef: Double = 1.0,         // * module
    val dedendumCoef: Double = 1.25,        // * module
    val hubDiameter: Double = 10.0,         // mm
    val hubLength: Double = 0.0,            // mm (legacy symmetric hub length; 0 = no hub)
    // ---- bevel ----
    val coneAngleDeg: Double = 45.0,        // pitch cone half angle
    val pitchConeDeg: Double = 45.0,
    val mountingDistance: Double = 25.0,    // mm
    // ---- rack pair ----
    val pinionTeeth: Int = 20,
    val rackLength: Double = 60.0,          // mm
    // ---- planetary ----
    val planetCount: Int = 3,
    val planetTeeth: Int = 12,
    val ringTeeth: Int = 44,
    // ---- worm pair ----
    val wormStarts: Int = 1,
    val wheelTeeth: Int = 30,
    // ---- material, tolerances, load & life ----
    val material: String = "Steel",
    val surfaceFinishUm: Double = 1.6,
    val toleranceClass: String = "ISO 7",
    val lubrication: String = "Grease",
    val loadNm: Double = 10.0,
    val speedRpm: Double = 500.0,
    val lifetimeHours: Double = 10000.0,
    val safetyFactor: Double = 1.5,
    // ---- asymmetric hub / boss / collar (hubLength kept as backward-compatible fallback) ----
    val hubLeftLength: Double = 0.0,      // axial protrusion left of the face (mm); 0 → fall back to hubLength/2
    val hubRightLength: Double = 0.0,     // axial protrusion right of the face (mm); 0 → fall back to hubLength/2
    val hubLeftBoreFollowsShaft: Boolean = true,  // left hub bore uses the gear's bore profile (D-cut/keyway/hex) instead of round
    val hubRightBoreFollowsShaft: Boolean = true, // right hub bore uses the gear's bore profile instead of round
    val hubChamfer: Double = 0.0,         // 45° outer-edge chamfer (mm)
    val hubFillet: Double = 0.0,          // hub↔body transition radius (mm)
    val hubDraftAngleDeg: Double = 0.0,   // casting draft angle (deg)
    // ---- grub / set screw (radial, through the hub into the bore) ----
    val setScrewCount: Int = 0,           // 0..2 radial screws
    val setScrewThread: String = "M3",    // ISO M2.5..M6
    val setScrewAngleDeg: Double = 90.0,  // angular position of screw 1 (°)
    val setScrewAngle2Deg: Double = 270.0,// angular position of screw 2 (°)
    val setScrewDepth: Double = 0.0,      // radial thread depth (mm); 0 = auto (through to bore)
    val setScrewAxialOffset: Double = 0.0,// axial position relative to hub centre (mm)
    // ---- per-tooth overrides (global defaults → per-tooth → local) ----
    val toothOverrides: Map<Int, ToothOverride> = emptyMap(),
    // ---- global tooth advanced defaults (overridable per tooth) ----
    val rootFilletCoef: Double = 0.38,    // root fillet radius, × module
    val transitionCoef: Double = 0.15,    // flank→root transition radius, × module
    val tipChamfer: Double = 0.0,         // 45° tip chamfer (mm)
    val tipRelief: Double = 0.0,          // tip relief (mm)
    val rootRelief: Double = 0.0,         // root relief (mm)
    val elephantFootChamferMm: Double = 0.0, // 45° bottom-edge chamfer (first-layer comp.); 0 = off
    // ---- lightening / structure ----
    val lighteningHoleCount: Int = 0,
    val lighteningHoleDiameter: Double = 0.0,
    val lighteningHolePCD: Double = 0.0,  // pitch circle of holes; 0 = auto (midway root↔hub)
    val spokeCount: Int = 0,
    val spokeWidth: Double = 0.0,
    // ---- markers ----
    val indexMarkType: String = "None",   // None | Slot | Dot
    val indexMarkAngleDeg: Double = 0.0,
    // ---- tolerances ----
    val boreHoleTolerance: String = "H7",
    val keywayTolerance: String = "JS9",
    // ---- timing belt transmission (GearType.BELT) ----
    val beltProfile: String = "GT2",          // GT2 | HTD 3M | HTD 5M | HTD 8M | T5 | AT5
    val beltWidthMm: Double = 6.0,
    val beltDriverTeeth: Int = 20,
    val beltDrivenTeeth: Int = 40,
    val beltCenterDistanceMm: Double = 0.0,   // 0 = auto
    val beltTensionN: Double = 50.0,
    val beltBacklashMm: Double = 0.0,
    val beltFlangeCount: Int = 2,
    val beltIdlerCount: Int = 0,
    // ---- compound gear (dubbelkugghjul): two coaxial stages + optional spacer ----
    val stage2Teeth: Int = 16,             // teeth on the second stage (z2)
    val stage2Module: Double = 0.8,        // module of the second stage (m2), mm
    val stage2FaceWidth: Double = 4.0,     // face width of the second stage (b2), mm
    val stage2PressureAngleDeg: Double = 20.0,
    val stage2ProfileShift: Double = 0.0,
    val stage2HelixAngleDeg: Double = 0.0, // reserved; compound stages are spur (coerced to 0)
    val stage2PhaseDeg: Double = 0.0,      // relative tooth alignment of stage 2 vs stage 1 (°)
    val spacerHeight: Double = 2.0,        // inter-stage spacer height (mm); 0 = flush transition
    val spacerDiameter: Double = 0.0       // spacer outer diameter (mm); 0 = automatic clearance
) {
    init {
        require(teeth >= 3) { "teeth must be >= 3" }
        require(module > 0.0) { "module must be > 0" }
        require(thickness > 0.0) { "thickness must be > 0" }
    }

    /**
     * Effective circumferential backlash in mm. When [backlashPercent] > 0 the
     * percentage of module wins; otherwise the absolute [backlash] value is used.
     */
    fun effectiveBacklashMm(): Double =
        if (backlashPercent > 0.0) backlashPercent / 100.0 * module else backlash

    /**
     * Returns a sanitized copy that caps loop-driving counts/dimensions so crafted
     * deserialized values cannot hang or exhaust memory (audit C6). The constructor's
     * [init] still enforces module > 0 / teeth >= 3 / thickness > 0 (copy re-runs it),
     * but the upper bounds and cross-field limits are only enforced here.
     */
    fun coerced(): GearParams {
        val m = module.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(0.2, 12.0) ?: 1.0
        // Minimum tooth count by profile: involute flanks below 8 teeth are severely
        // undercut and the circular root fillet degenerates (audit T7); cycloidal flanks
        // cannot fit below 6 teeth with the R/4 generating circle; straight teeth are
        // trapezoids and stay valid down to 5.
        val minTeeth = when (toothProfile) {
            ToothProfile.CYCLOID -> 6
            ToothProfile.STRAIGHT -> 5
            ToothProfile.INVOLUTE -> 8
        }
        val n = teeth.coerceIn(minTeeth, 300)
        // Profile shift beyond ±1 produces degenerate (negative-thickness or wildly
        // undercut) teeth; clamp to the printable range.
        val x = profileShift.takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0) ?: 0.0
        // Backlash is capped at 0.2·m (spec): larger values can invert teeth or
        // leave a flank thinner than printable at small modules.
        val maxBacklash = (0.2 * m).coerceAtLeast(0.001)
        // The centre bore must never overrun the root circle, otherwise the hole
        // polygon would intersect the tooth flanks and break the triangulation
        // (audit T1: non-manifold mesh for small gears with the default 5 mm bore).
        val cutsBore = gearType != GearType.RACK && gearType != GearType.BELT &&
            gearType != GearType.INTERNAL_RING && gearType != GearType.WORM_PAIR
        val bore = if (cutsBore && bore.type != BoreType.NONE) {
            // The bore must clear the SHIFTED root radius r_f = m·z/2 − m·(h_f* − x):
            // a negative shift deepens the root, so using the unshifted radius would
            // let the hole overrun the undercut flanks and break the triangulation.
            val rootR = GearCalculator.rootRadiusShifted(m, n, dedendumCoef, x)
            val minWall = maxOf(0.6, 0.4 * m)
            val maxBoreR = rootR - minWall
            if (maxBoreR > 0.0) {
                when (bore.type) {
                    BoreType.ROUND, BoreType.D_CUT ->
                        bore.copy(diameter = minOf(bore.diameter, 2.0 * maxBoreR).coerceAtLeast(0.5))
                    BoreType.KEYWAY -> {
                        // The key slot extends depth beyond the bore wall.
                        val maxD = 2.0 * (maxBoreR - bore.keywayDepth.coerceAtLeast(0.0))
                        bore.copy(diameter = minOf(bore.diameter, maxD).coerceAtLeast(0.5))
                    }
                    BoreType.HEX -> {
                        // Hex corners reach acrossFlats/2 ÷ cos(30°) from centre.
                        val maxAf = 2.0 * maxBoreR * cos(PI / 6.0)
                        bore.copy(hexAcrossFlats = minOf(bore.hexAcrossFlats, maxAf).coerceAtLeast(0.5))
                    }
                    BoreType.SQUARE -> {
                        // Square corners reach acrossFlats/2 · √2 from centre.
                        val maxSide = 2.0 * maxBoreR / sqrt(2.0)
                        bore.copy(squareAcrossFlats = minOf(bore.squareAcrossFlats, maxSide).coerceAtLeast(0.5))
                    }
                    BoreType.NONE -> bore
                }
            } else bore
        } else bore
        return copy(
            module = m,
            teeth = n,
            pressureAngleDeg = pressureAngleDeg.takeIf { it.isFinite() }?.coerceIn(1.0, 89.0) ?: 20.0,
            thickness = thickness.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(500.0) ?: 6.0,
            backlash = backlash.takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(maxBacklash) ?: 0.1,
            backlashPercent = backlashPercent.takeIf { it.isFinite() }?.coerceIn(0.0, 50.0) ?: 0.0,
            backlashLeftMm = backlashLeftMm.takeIf { it.isFinite() }?.coerceIn(0.0, 2.0 * m) ?: 0.0,
            backlashRightMm = backlashRightMm.takeIf { it.isFinite() }?.coerceIn(0.0, 2.0 * m) ?: 0.0,
            minimumTopLandWidth = minimumTopLandWidth.takeIf { it.isFinite() }?.coerceIn(0.0, 2.0 * m) ?: 0.0,
            helixAngleDeg = helixAngleDeg.takeIf { it.isFinite() }?.coerceIn(-85.0, 85.0) ?: 0.0,
            profileShift = x,
            pinionTeeth = pinionTeeth.coerceIn(3, 300),
            planetCount = planetCount.coerceIn(2, 12),
            planetTeeth = planetTeeth.coerceIn(8, 300),
            ringTeeth = ringTeeth.coerceIn(20, 600),
            wormStarts = wormStarts.coerceIn(1, 8),
            wheelTeeth = wheelTeeth.coerceIn(10, 300),
            lighteningHoleCount = lighteningHoleCount.coerceIn(0, 12),
            lighteningHoleDiameter = lighteningHoleDiameter.takeIf { it.isFinite() }?.coerceIn(0.0, 60.0) ?: 0.0,
            lighteningHolePCD = lighteningHolePCD.takeIf { it.isFinite() }?.coerceIn(0.0, 200.0) ?: 0.0,
            spokeCount = spokeCount.coerceIn(0, 12),
            spokeWidth = spokeWidth.takeIf { it.isFinite() }?.coerceIn(0.0, 60.0) ?: 0.0,
            elephantFootChamferMm = elephantFootChamferMm.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
            setScrewCount = setScrewCount.coerceIn(0, 2),
            beltDriverTeeth = beltDriverTeeth.coerceIn(8, 500),
            beltDrivenTeeth = beltDrivenTeeth.coerceIn(8, 500),
            beltWidthMm = beltWidthMm.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(100.0) ?: 6.0,
            beltCenterDistanceMm = beltCenterDistanceMm.takeIf { it.isFinite() }?.coerceIn(0.0, 1000.0) ?: 0.0,
            beltFlangeCount = beltFlangeCount.coerceIn(0, 4),
            beltIdlerCount = beltIdlerCount.coerceIn(0, 4),
            bore = bore,
            stage2Teeth = stage2Teeth.coerceIn(minTeeth, 300),
            stage2Module = stage2Module.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(0.2, 12.0) ?: 0.8,
            stage2FaceWidth = stage2FaceWidth.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(500.0) ?: 4.0,
            stage2PressureAngleDeg = stage2PressureAngleDeg.takeIf { it.isFinite() }?.coerceIn(1.0, 89.0) ?: 20.0,
            stage2ProfileShift = stage2ProfileShift.takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0) ?: 0.0,
            // Compound stages are straight-cut (spur) only; the reserved helix field is
            // clamped to 0 so the watertight stacking path is always used.
            stage2HelixAngleDeg = 0.0,
            stage2PhaseDeg = stage2PhaseDeg.takeIf { it.isFinite() }?.coerceIn(-360.0, 360.0) ?: 0.0,
            spacerHeight = spacerHeight.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 2.0,
            spacerDiameter = spacerDiameter.takeIf { it.isFinite() }?.coerceIn(0.0, 500.0) ?: 0.0,
            toothOverrides = toothOverrides
                .filterKeys { it in 0 until n }
                .mapValues { (_, o) -> o.coerced() }
        )
    }
}

/** A closed planar outline plus optional holes (in the same plane). */
data class PlanarShape(
    val outer: List<Vec2>,
    val holes: List<List<Vec2>> = emptyList()
)

/** A triangle mesh defined by indexed triangles with explicit vertex positions. */
data class Mesh(
    val vertices: List<Vec3>,
    val triangles: List<IntArray>
)
