package com.gearforge.core

/** The kinds of gears the generator can produce. */
enum class GearType {
    SPUR, HELICAL, BEVEL, RACK, PLANETARY,
    WORM_PAIR, INTERNAL_RING, HYPOID, CYCLOIDAL,
    HARMONIC_DRIVE, FACE_GEAR, SCREW_GEAR, BELT
}

/** Tooth profile standards. */
enum class ToothProfile { INVOLUTE, CYCLOID, STRAIGHT }

/** Center bore / shaft attachment types. */
enum class BoreType { NONE, ROUND, D_CUT, KEYWAY, HEX }

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
    val hexAcrossFlats: Double = 6.0
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
    val beltIdlerCount: Int = 0
) {
    init {
        require(teeth >= 3) { "teeth must be >= 3" }
        require(module > 0.0) { "module must be > 0" }
        require(thickness > 0.0) { "thickness must be > 0" }
    }

    /**
     * Returns a sanitized copy that caps loop-driving counts/dimensions so crafted
     * deserialized values cannot hang or exhaust memory (audit C6). The constructor's
     * [init] still enforces module > 0 / teeth >= 3 / thickness > 0 (copy re-runs it),
     * but the upper bounds and cross-field limits are only enforced here.
     */
    fun coerced(): GearParams {
        val m = module.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(0.2, 12.0) ?: 1.0
        val n = teeth.coerceIn(3, 300)
        val maxBacklash = (Math.PI * m * 0.25).coerceAtLeast(0.001)
        return copy(
            module = m,
            teeth = n,
            pressureAngleDeg = pressureAngleDeg.takeIf { it.isFinite() }?.coerceIn(1.0, 89.0) ?: 20.0,
            thickness = thickness.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(500.0) ?: 6.0,
            backlash = backlash.takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(maxBacklash) ?: 0.1,
            helixAngleDeg = helixAngleDeg.takeIf { it.isFinite() }?.coerceIn(-85.0, 85.0) ?: 0.0,
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
            setScrewCount = setScrewCount.coerceIn(0, 2),
            beltDriverTeeth = beltDriverTeeth.coerceIn(8, 500),
            beltDrivenTeeth = beltDrivenTeeth.coerceIn(8, 500),
            beltWidthMm = beltWidthMm.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(100.0) ?: 6.0,
            beltCenterDistanceMm = beltCenterDistanceMm.takeIf { it.isFinite() }?.coerceIn(0.0, 1000.0) ?: 0.0,
            beltFlangeCount = beltFlangeCount.coerceIn(0, 4),
            beltIdlerCount = beltIdlerCount.coerceIn(0, 4),
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
