package com.gearforge.core

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Logical grouping for the dynamic settings panel. */
enum class ParamGroup(val order: Int) {
    GEOMETRY(0), MATERIAL(1), TOLERANCES(2), LOAD(3), RESULTS(4),
    HUB(5), TEETH(6), LIGHTENING(7)
}

/** What kind of UI control a parameter maps to. */
enum class FieldKind { NUMBER, CHOICE, BOOLEAN, CALCULATED }

/** Scope of a parameter: global, per-tooth (pattern) or local (per-tooth exception). */
enum class ParamScope { GLOBAL, PER_TOOTH, LOCAL }

/**
 * Declarative description of one editable or calculated parameter.
 *
 * The settings panel is rendered from [GearSpec.fields] so each gear type only ever
 * shows the parameters that are relevant to it, with the right units, limits and
 * editable/locked state.
 */
data class ParamDef(
    val key: String,
    val label: String,
    val group: ParamGroup,
    val kind: FieldKind,
    val min: Double = 0.0,
    val max: Double = 0.0,
    val decimals: Int = 2,
    val unit: String = "",
    val editable: Boolean = true,
    val options: List<String> = emptyList(),
    val help: String = "",
    val scope: ParamScope = ParamScope.GLOBAL,
    val formula: ((GearParams) -> Double)? = null,
    val dependsOn: List<String> = emptyList(),
    val conflict: List<String> = emptyList()
)

/** Severity of a validation finding: hard errors block regeneration, warnings are advisory. */
enum class GearSeverity { ERROR, WARNING }

/** A single geometry warning produced by [GearSpec.validate]. */
data class GearWarning(
    /** Machine-readable code, mapped to a localized I18n key (`validation_<code>`) in the app. */
    val code: String,
    /** Optional non-localized detail for diagnostics; user-facing text comes from I18n. */
    val detail: String = "",
    /** ERROR blocks geometry generation; WARNING is advisory. */
    val severity: GearSeverity = GearSeverity.WARNING
)

/** Type-specific, grouped parameter registry plus read/write accessors and computed results. */
object GearSpec {

    // Sane module bounds (mm) used by [validate]; mirrors the UI field limits.
    private const val MIN_MODULE_MM = 0.2
    private const val MAX_MODULE_MM = 12.0

    // Warning codes (keys into I18n `validation_<code>`).
    const val WARN_MODULE = "module"
    const val WARN_RING_TEETH = "ring_teeth"
    const val WARN_PLANET_OVERLAP = "planet_overlap"
    const val WARN_PLANET_PHASE = "planet_phase"
    const val WARN_HELIX_ANGLE = "helix_angle"
    const val WARN_BORE = "bore"
    const val WARN_HUB_WALL = "hub_wall"
    const val WARN_HUB_CHAMFER = "hub_chamfer"
    const val WARN_HUB_COVERS_ROOT = "hub_covers_root"
    const val WARN_GRUB_NO_HUB = "grub_no_hub"
    const val WARN_TOOTH_THICK = "tooth_thick"
    const val WARN_BELT_TEETH = "belt_teeth"
    const val WARN_BELT_WIDTH = "belt_width"
    const val WARN_BELT_WRAP = "belt_wrap"
    const val WARN_BELT_MESH_TEETH = "belt_mesh_teeth"
    const val WARN_LIGHTENING_HOLE = "lightening_hole"
    const val WARN_BACKLASH = "backlash"
    const val WARN_UNDERCUT = "undercut"
    const val WARN_TOPLAND = "topland"
    const val WARN_TOOTH_OVERLAP = "tooth_overlap"
    const val WARN_SETSCREW = "setscrew"
    const val WARN_PROFILE_SHIFT = "profile_shift"
    const val WARN_TEETH = "teeth"
    const val WARN_SPACER = "spacer"

    /** Minimum hub wall thickness (mm) between the bore and the hub outer diameter. */
    const val MIN_HUB_WALL = 2.0

    val MATERIALS = listOf("Steel", "Aluminium", "Brass", "PLA", "PETG", "Nylon", "ABS")
    val TOLERANCES = listOf("ISO 5", "ISO 6", "ISO 7", "ISO 8", "ISO 9")
    val LUBRICATION = listOf("None", "Grease", "Oil", "Dry film")
    val UNITS = listOf("mm (module)", "inch (diametral pitch)")
    val TOOTH_PROFILES = listOf("Involute", "Cycloid", "Straight")
    val BORE_OPTIONS = listOf("None", "Round", "D-cut", "Keyway", "Hex", "Square")
    val SCREW_THREADS = listOf("M2.5", "M3", "M4", "M5", "M6")
    val BORE_TOLERANCES = listOf("H7", "H8", "F8", "G7")
    val KEYWAY_TOLERANCES = listOf("JS9", "N9", "P9")
    val INDEX_MARK_OPTIONS = listOf("None", "Slot", "Dot")

    /**
     * True for gear types that are a single gear body, where structural fields
     * (lightening, spokes, pockets, index marks) and per-tooth overrides apply.
     * Belt drives and racks are assemblies/bars whose meshes ignore these values,
     * so they are omitted to avoid dead, silently-ignored settings.
     */
    fun hasGearBody(type: GearType): Boolean = type != GearType.BELT && type != GearType.RACK

    // ---- small builders --------------------------------------------------
    private fun number(key: String, label: String, group: ParamGroup, min: Double, max: Double,
                       decimals: Int = 2, unit: String = "", editable: Boolean = true, help: String = "") =
        ParamDef(key, label, group, FieldKind.NUMBER, min, max, decimals, unit, editable, help = help)

    private fun choice(key: String, label: String, group: ParamGroup, options: List<String>, editable: Boolean = true, help: String = "") =
        ParamDef(key, label, group, FieldKind.CHOICE, options = options, editable = editable, help = help)

    private fun bool(key: String, label: String, group: ParamGroup, editable: Boolean = true, help: String = "") =
        ParamDef(key, label, group, FieldKind.BOOLEAN, editable = editable, help = help)

    // ---- defaults per type ----------------------------------------------
    fun defaults(type: GearType): GearParams = when (type) {
        GearType.SPUR -> GearParams(gearType = type, module = 1.0, teeth = 20)
        GearType.HELICAL -> GearParams(gearType = type, module = 1.0, teeth = 16, helixAngleDeg = 15.0)
        GearType.BEVEL -> GearParams(gearType = type, module = 1.0, teeth = 20, coneAngleDeg = 45.0, pitchConeDeg = 45.0)
        GearType.RACK -> GearParams(gearType = type, module = 1.0, teeth = 10, pinionTeeth = 20, rackLength = 60.0)
        GearType.PLANETARY -> GearParams(gearType = type, module = 1.0, teeth = 12, planetTeeth = 12, ringTeeth = 36, planetCount = 3)
        GearType.WORM_PAIR -> GearParams(gearType = type, module = 1.0, wormStarts = 1, wheelTeeth = 30, helixAngleDeg = 75.0)
        GearType.INTERNAL_RING -> GearParams(gearType = type, module = 1.0, teeth = 44)
        GearType.HYPOID -> GearParams(gearType = type, module = 1.0, teeth = 20, coneAngleDeg = 35.0, pitchConeDeg = 35.0)
        GearType.CYCLOIDAL -> GearParams(gearType = type, module = 1.0, teeth = 12, toothProfile = ToothProfile.CYCLOID)
        GearType.HARMONIC_DRIVE -> GearParams(gearType = type, module = 1.0, teeth = 160, toothProfile = ToothProfile.CYCLOID)
        GearType.FACE_GEAR -> GearParams(gearType = type, module = 1.0, teeth = 40)
        GearType.SCREW_GEAR -> GearParams(gearType = type, module = 1.0, teeth = 20, helixAngleDeg = 45.0)
        GearType.COMPOUND -> GearParams(gearType = type, module = 1.0, teeth = 20, stage2Module = 0.8, stage2Teeth = 16, stage2FaceWidth = 4.0)
        GearType.BELT -> GearParams(gearType = type, beltDriverTeeth = 20, beltDrivenTeeth = 40)
    }

    // ---- geometry fields shared by most types ---------------------------
    private fun commonGeometry(
        p: GearParams,
        includeToothProfile: Boolean = true,
        includeTeeth: Boolean = true
    ): List<ParamDef> = buildList {
        add(choice("unit", "Units", ParamGroup.GEOMETRY, UNITS,
            help = "Switch between metric module and imperial diametral pitch."))
        if (includeToothProfile) {
            add(choice("tooth_profile", "Tooth profile", ParamGroup.GEOMETRY, TOOTH_PROFILES,
                help = "Flank shape: involute is standard, cycloid for clocks, straight for simple wheels."))
        }
        val moduleMin = if (p.unit == UnitSystem.INCH) 25.4 / 12.0 else 0.2
        val moduleMax = if (p.unit == UnitSystem.INCH) 25.4 / 0.2 else 12.0
        add(number("module", if (p.unit == UnitSystem.INCH) "Diametral pitch (1/in)" else "Module (mm)",
            ParamGroup.GEOMETRY, moduleMin, moduleMax, 3, if (p.unit == UnitSystem.INCH) "1/in" else "mm",
            help = if (p.unit == UnitSystem.INCH)
                "Diametral pitch = teeth per inch of pitch diameter."
            else "Module = pitch diameter ÷ teeth; a larger module means bigger teeth."))
        if (includeTeeth) {
            add(number("teeth", "Teeth", ParamGroup.GEOMETRY, 5.0, 200.0, 0,
                help = "Number of teeth; sets the pitch diameter together with the module."))
        }
        add(number("pressure_angle", "Pressure angle", ParamGroup.GEOMETRY, 14.0, 30.0, 2, "\u00b0",
            help = "Angle of the tooth flank, usually 14.5° or 20°."))
        add(number("thickness", "Face width", ParamGroup.GEOMETRY, 1.0, 50.0, 2, "mm",
            help = "Width of the gear face along its axis."))
        // Backlash is capped at a quarter of the tooth pitch so it can never invert or
        // self-intersect teeth at small module (audit C2).
        val backlashMax = max(0.05, 0.25 * PI * p.module)
        add(number("backlash", "Backlash", ParamGroup.GEOMETRY, 0.0, backlashMax, 3, "mm",
            help = "Clearance between mating teeth; keep it small for a tight mesh."))
    }

    private fun profileFields(p: GearParams): List<ParamDef> = buildList {
        add(number("profile_shift", "Profile shift", ParamGroup.GEOMETRY, -0.5, 0.5, 3,
            help = "Moves the tooth profile to avoid undercut or adjust strength."))
        add(number("addendum", "Addendum coeff.", ParamGroup.GEOMETRY, 0.5, 2.0, 3,
            help = "Tooth height above the pitch circle, as a multiple of the module."))
        add(number("dedendum", "Dedendum coeff.", ParamGroup.GEOMETRY, 0.5, 3.0, 3,
            help = "Tooth depth below the pitch circle, as a multiple of the module."))
    }

    private fun hubFields(p: GearParams): List<ParamDef> = buildList {
        add(number("hub_diameter", "Hub diameter", ParamGroup.HUB, 2.0, 60.0, 2, "mm",
            help = "Diameter of the central hub boss."))
        add(number("hub_length", "Hub length", ParamGroup.HUB, 0.0, 40.0, 2, "mm",
            help = "Legacy total hub length; used only when left/right are both 0."))
        add(number("hub_left_length", "Hub left length", ParamGroup.HUB, 0.0, 50.0, 2, "mm",
            help = "Hub protrusion on the left side of the face."))
        add(number("hub_right_length", "Hub right length", ParamGroup.HUB, 0.0, 50.0, 2, "mm",
            help = "Hub protrusion on the right side of the face."))
        val nonRoundBore = p.bore.type != BoreType.NONE && p.bore.type != BoreType.ROUND
        if (nonRoundBore && GearCalculator.effectiveHubLeft(p) > 0.0) {
            add(bool("hub_left_bore_follows", "Left hub follows shaft bore", ParamGroup.HUB,
                help = "When on, the left hub's bore uses the same profile (D-cut, keyway or hex) as the gear. When off, the left hub stays a round cylinder."))
        }
        if (nonRoundBore && GearCalculator.effectiveHubRight(p) > 0.0) {
            add(bool("hub_right_bore_follows", "Right hub follows shaft bore", ParamGroup.HUB,
                help = "When on, the right hub's bore uses the same profile (D-cut, keyway or hex) as the gear. When off, the right hub stays a round cylinder."))
        }
        add(number("hub_chamfer", "Hub chamfer", ParamGroup.HUB, 0.0, 20.0, 2, "mm",
            help = "45° chamfer of the hub's outer edge."))
        add(number("hub_fillet", "Hub fillet", ParamGroup.HUB, 0.0, 20.0, 2, "mm",
            help = "Transition radius between hub and gear body."))
        add(number("hub_draft_angle", "Hub draft angle", ParamGroup.HUB, 0.0, 5.0, 2, "\u00b0",
            help = "Casting draft angle of the hub."))
        add(number("set_screw_count", "Set screws", ParamGroup.HUB, 0.0, 2.0, 0,
            help = "Number of radial grub screws locking the shaft (0–2)."))
        if (p.setScrewCount > 0) {
            add(choice("set_screw_thread", "Screw thread", ParamGroup.HUB, SCREW_THREADS,
                help = "ISO metric thread size of the grub screw."))
            add(number("set_screw_angle", "Screw 1 angle", ParamGroup.HUB, 0.0, 360.0, 0, "\u00b0",
                help = "Angular position of the first screw."))
            add(number("set_screw_depth", "Screw depth", ParamGroup.HUB, 0.0, 20.0, 2, "mm",
                help = "Radial thread depth; 0 = through to the bore."))
            add(number("set_screw_axial_offset", "Screw axial offset", ParamGroup.HUB, -20.0, 20.0, 2, "mm",
                help = "Axial position of the screw relative to the hub centre."))
            if (p.setScrewCount >= 2) {
                add(number("set_screw_angle2", "Screw 2 angle", ParamGroup.HUB, 0.0, 360.0, 0, "\u00b0",
                    help = "Angular position of the second screw."))
            }
        }
    }

    private fun teethFields(): List<ParamDef> = listOf(
        number("root_fillet_coef", "Root fillet (×m)", ParamGroup.TEETH, 0.0, 0.6, 3,
            help = "Root fillet radius as a multiple of the module."),
        number("tip_chamfer", "Tip chamfer", ParamGroup.TEETH, 0.0, 2.0, 2, "mm",
            help = "45° chamfer at the tooth tip.")
    )

    private fun lighteningFields(p: GearParams): List<ParamDef> = buildList {
        add(number("lightening_hole_count", "Lightening holes", ParamGroup.LIGHTENING, 0.0, 12.0, 0,
            help = "Number of circular lightening holes."))
        if (p.lighteningHoleCount > 0) {
            add(number("lightening_hole_diameter", "Hole diameter", ParamGroup.LIGHTENING, 0.0, 60.0, 2, "mm",
                help = "Diameter of each lightening hole; 0 = automatic."))
            add(number("lightening_hole_pcd", "Hole PCD", ParamGroup.LIGHTENING, 0.0, 200.0, 2, "mm",
                help = "Pitch-circle diameter of the holes; 0 = automatic."))
        }
        add(number("spoke_count", "Spokes", ParamGroup.LIGHTENING, 0.0, 12.0, 0,
            help = "Number of spokes (replaces a solid disc)."))
        if (p.spokeCount > 0) {
            add(number("spoke_width", "Spoke width", ParamGroup.LIGHTENING, 1.0, 60.0, 2, "mm",
                help = "Width of each spoke."))
        }
        add(choice("index_mark", "Index mark", ParamGroup.LIGHTENING, INDEX_MARK_OPTIONS,
            help = "Index or sensor position marker on the gear body."))
        if (p.indexMarkType != "None") {
            add(number("index_mark_angle", "Index mark angle", ParamGroup.LIGHTENING, 0.0, 360.0, 0, "\u00b0",
                help = "Angular position of the index mark."))
        }
    }

    private fun boreFields(p: GearParams): List<ParamDef> = buildList {
        add(choice("bore_type", "Shaft bore", ParamGroup.GEOMETRY, BORE_OPTIONS,
            help = "Shape of the shaft hole cut through the centre."))
        if (p.bore.type != BoreType.NONE) {
            add(number("bore_diameter", "Bore diameter", ParamGroup.GEOMETRY, 1.0, 50.0, 2, "mm",
                help = "Diameter of the shaft hole."))
        }
        when (p.bore.type) {
            BoreType.D_CUT -> add(number("dcut_offset", "D-cut offset", ParamGroup.GEOMETRY, 0.0, 20.0, 2, "mm",
                help = "Distance of the flat from the bore centre."))
            BoreType.KEYWAY -> {
                add(number("keyway_width", "Keyway width", ParamGroup.GEOMETRY, 1.0, 12.0, 2, "mm",
                    help = "Width of the key slot in the bore."))
                add(number("keyway_depth", "Keyway depth", ParamGroup.GEOMETRY, 0.5, 8.0, 2, "mm",
                    help = "Depth of the key slot measured from the bore wall."))
            }
            BoreType.HEX -> add(number("hex_flats", "Hex across flats", ParamGroup.GEOMETRY, 2.0, 30.0, 2, "mm",
                help = "Distance across the flats of the hexagonal bore."))
            BoreType.SQUARE -> add(number("square_flats", "Square across flats", ParamGroup.GEOMETRY, 2.0, 30.0, 2, "mm",
                help = "Distance across the flats of the square bore."))
            else -> {}
        }
    }

    private fun materialFields(): List<ParamDef> = listOf(
        choice("material", "Material", ParamGroup.MATERIAL, MATERIALS,
            help = "Material affects strength and how well the gear prints."),
        choice("lubrication", "Lubrication", ParamGroup.MATERIAL, LUBRICATION,
            help = "Lubrication method used when estimating wear.")
    )

    private fun toleranceFields(): List<ParamDef> = listOf(
        choice("tolerance", "Tolerance class", ParamGroup.TOLERANCES, TOLERANCES,
            help = "Manufacturing tolerance class (ISO grade); lower numbers are tighter."),
        number("surface_finish", "Surface finish", ParamGroup.TOLERANCES, 0.4, 12.5, 2, "\u00b5m",
            help = "Target surface roughness in micrometres (Ra)."),
        choice("bore_hole_tolerance", "Bore fit", ParamGroup.TOLERANCES, BORE_TOLERANCES,
            help = "Bore tolerance class (H7 press, F8 sliding)."),
        choice("keyway_tolerance", "Keyway fit", ParamGroup.TOLERANCES, KEYWAY_TOLERANCES,
            help = "Keyway tolerance class (ISO 7738).")
    )

    private fun loadFields(): List<ParamDef> = listOf(
        number("load", "Torque load", ParamGroup.LOAD, 0.1, 1000.0, 2, "Nm",
            help = "Torque applied to the gear."),
        number("speed", "Speed", ParamGroup.LOAD, 1.0, 20000.0, 0, "rpm",
            help = "Rotational speed in revolutions per minute."),
        number("lifetime", "Design lifetime", ParamGroup.LOAD, 100.0, 100000.0, 0, "h",
            help = "Required operating life in hours."),
        number("safety_factor", "Safety factor", ParamGroup.LOAD, 1.0, 5.0, 2,
            help = "Safety margin added on top of calculated stresses.")
    )

    private fun structuralFields(p: GearParams): List<ParamDef> = teethFields() + lighteningFields(p)

    /** Two-stage compound gear: Stage 1, Stage 2, the spacer and the shared bore. */
    private fun compoundFields(p: GearParams): List<ParamDef> = buildList {
        add(choice("unit", "Units", ParamGroup.GEOMETRY, UNITS,
            help = "Switch between metric module and imperial diametral pitch."))
        val moduleMin = if (p.unit == UnitSystem.INCH) 25.4 / 12.0 else 0.2
        val moduleMax = if (p.unit == UnitSystem.INCH) 25.4 / 0.2 else 12.0
        val moduleLabel = if (p.unit == UnitSystem.INCH) "Diametral pitch (1/in)" else "Module (mm)"
        // ---- Stage 1 ----
        add(number("module", "Stage 1 " + moduleLabel.lowercase(), ParamGroup.GEOMETRY, moduleMin, moduleMax, 3,
            if (p.unit == UnitSystem.INCH) "1/in" else "mm",
            help = "Module of the first (primary) stage."))
        add(number("teeth", "Stage 1 teeth", ParamGroup.GEOMETRY, 5.0, 200.0, 0,
            help = "Number of teeth on the first stage."))
        add(number("thickness", "Stage 1 face width", ParamGroup.GEOMETRY, 1.0, 50.0, 2, "mm",
            help = "Face width of the first stage."))
        add(number("pressure_angle", "Pressure angle", ParamGroup.GEOMETRY, 14.0, 30.0, 2, "\u00b0",
            help = "Flank pressure angle shared by both stages."))
        val backlashMax = max(0.05, 0.25 * PI * p.module)
        add(number("backlash", "Backlash", ParamGroup.GEOMETRY, 0.0, backlashMax, 3, "mm",
            help = "Clearance between mating teeth."))
        // ---- Stage 2 ----
        add(number("stage2_module", "Stage 2 " + moduleLabel.lowercase(), ParamGroup.GEOMETRY, moduleMin, moduleMax, 3,
            if (p.unit == UnitSystem.INCH) "1/in" else "mm",
            help = "Module of the second stage; may differ from stage 1."))
        add(number("stage2_teeth", "Stage 2 teeth", ParamGroup.GEOMETRY, 5.0, 200.0, 0,
            help = "Number of teeth on the second stage."))
        add(number("stage2_face_width", "Stage 2 face width", ParamGroup.GEOMETRY, 1.0, 50.0, 2, "mm",
            help = "Face width of the second stage."))
        add(number("stage2_phase", "Stage 2 tooth phase", ParamGroup.GEOMETRY, -360.0, 360.0, 0, "\u00b0",
            help = "Rotation of the second stage's teeth relative to the first stage."))
        // ---- Spacer ----
        add(number("spacer_height", "Spacer height", ParamGroup.GEOMETRY, 0.0, 50.0, 2, "mm",
            help = "Height of the hub between the two stages; 0 = flush transition."))
        add(number("spacer_diameter", "Spacer diameter", ParamGroup.GEOMETRY, 0.0, 200.0, 2, "mm",
            help = "Outer diameter of the inter-stage hub; 0 = automatic clearance."))
        addAll(boreFields(p))
        addAll(materialFields())
        addAll(toleranceFields())
    }

    private fun beltFields(): List<ParamDef> = buildList {
        add(choice("belt_profile", "Belt profile", ParamGroup.GEOMETRY,
            BeltProfile.entries.map { it.label },
            help = "Timing-belt tooth profile; the pitch determines the pulley diameter."))
        add(number("belt_width", "Belt width", ParamGroup.GEOMETRY, 2.0, 50.0, 1, "mm",
            help = "Width of the belt and of each pulley face."))
        add(number("belt_driver_teeth", "Driver teeth", ParamGroup.GEOMETRY, 8.0, 200.0, 0,
            help = "Number of teeth on the driving pulley."))
        add(number("belt_driven_teeth", "Driven teeth", ParamGroup.GEOMETRY, 8.0, 200.0, 0,
            help = "Number of teeth on the driven pulley."))
        add(number("belt_center_distance", "Centre distance", ParamGroup.GEOMETRY, 0.0, 1000.0, 1, "mm",
            help = "Distance between pulley centres; 0 = choose automatically."))
        add(number("belt_backlash", "Backlash", ParamGroup.GEOMETRY, 0.0, 2.0, 3, "mm",
            help = "Clearance between the belt teeth and the pulley grooves."))
        add(number("belt_flanges", "Flanges", ParamGroup.GEOMETRY, 0.0, 4.0, 0,
            help = "Number of retaining flanges on the pulleys."))
        add(number("belt_tension", "Belt tension", ParamGroup.LOAD, 1.0, 500.0, 1, "N",
            help = "Static belt tension used for the load estimate."))
        addAll(materialFields())
        addAll(toleranceFields())
    }

    // ---- per-type field list --------------------------------------------
    fun fields(p: GearParams): List<ParamDef> {
        val base: List<ParamDef> = when (p.gearType) {
        GearType.SPUR -> commonGeometry(p) + profileFields(p) + boreFields(p) + hubFields(p) +
            materialFields() + toleranceFields() + loadFields()

        GearType.HELICAL -> commonGeometry(p) +
            listOf(number("helix_angle", "Helix angle", ParamGroup.GEOMETRY, 0.0, 45.0, 2, "\u00b0",
                help = "Angle of the teeth relative to the gear axis.")) +
            profileFields(p) + boreFields(p) + hubFields(p) + materialFields() + toleranceFields() + loadFields()

        GearType.BEVEL -> commonGeometry(p) +
            listOf(
                number("cone_angle", "Cone angle", ParamGroup.GEOMETRY, 10.0, 80.0, 2, "\u00b0",
                    help = "Half-angle of the gear cone."),
                number("pitch_cone", "Pitch cone angle", ParamGroup.GEOMETRY, 10.0, 80.0, 2, "\u00b0",
                    help = "Angle of the pitch cone."),
                number("mounting_distance", "Mounting distance", ParamGroup.GEOMETRY, 1.0, 200.0, 2, "mm",
                    help = "Distance from the cone apex to the mounting face.")
            ) + profileFields(p) + boreFields(p) + hubFields(p) + materialFields() + toleranceFields() + loadFields()

        GearType.RACK -> commonGeometry(p, includeTeeth = false) +
            listOf(
                number("pinion_teeth", "Pinion teeth", ParamGroup.GEOMETRY, 3.0, 120.0, 0,
                    help = "Number of teeth on the mating pinion."),
                number("rack_length", "Rack length", ParamGroup.GEOMETRY, 10.0, 400.0, 1, "mm",
                    help = "Length of the rack bar; the tooth count is derived from it.")
            ) + profileFields(p) + materialFields() + toleranceFields() + loadFields()

        GearType.PLANETARY -> commonGeometry(p) +
            listOf(
                number("planet_count", "Planet count", ParamGroup.GEOMETRY, 2.0, 6.0, 0,
                    help = "Number of planet gears in the set."),
                number("planet_teeth", "Planet teeth", ParamGroup.GEOMETRY, 8.0, 60.0, 0,
                    help = "Teeth on each planet gear."),
                number("ring_teeth", "Ring teeth", ParamGroup.GEOMETRY, 20.0, 200.0, 0,
                    help = "Teeth on the internal ring gear.")
            ) + profileFields(p) + materialFields() + toleranceFields() + loadFields()

        GearType.WORM_PAIR -> commonGeometry(p, includeTeeth = false) +
            listOf(
                number("worm_starts", "Worm starts", ParamGroup.GEOMETRY, 1.0, 6.0, 0,
                    help = "Number of thread starts on the worm."),
                number("wheel_teeth", "Wheel teeth", ParamGroup.GEOMETRY, 10.0, 200.0, 0,
                    help = "Teeth on the worm wheel."),
                number("helix_angle", "Helix angle", ParamGroup.GEOMETRY, 5.0, 85.0, 2, "\u00b0",
                    help = "Angle of the worm thread relative to the worm axis (measured from the axis).")
            ) + materialFields() + toleranceFields() + loadFields()

        GearType.INTERNAL_RING -> commonGeometry(p, includeToothProfile = false) + hubFields(p) +
            materialFields() + toleranceFields() + loadFields()

        GearType.HYPOID -> commonGeometry(p) +
            listOf(
                number("cone_angle", "Pinion cone angle", ParamGroup.GEOMETRY, 10.0, 80.0, 2, "\u00b0",
                    help = "Cone angle of the pinion."),
                number("pitch_cone", "Gear cone angle", ParamGroup.GEOMETRY, 10.0, 80.0, 2, "\u00b0",
                    help = "Cone angle of the gear.")
            ) + profileFields(p) + boreFields(p) + materialFields() + toleranceFields() + loadFields()

        GearType.CYCLOIDAL -> commonGeometry(p) + profileFields(p) + boreFields(p) + hubFields(p) +
            materialFields() + toleranceFields() + loadFields()

        GearType.HARMONIC_DRIVE -> commonGeometry(p) + profileFields(p) + hubFields(p) +
            materialFields() + toleranceFields() + loadFields()

        GearType.FACE_GEAR -> commonGeometry(p) + profileFields(p) + boreFields(p) + hubFields(p) +
            materialFields() + toleranceFields() + loadFields()

        GearType.SCREW_GEAR -> commonGeometry(p) +
            listOf(number("helix_angle", "Helix angle", ParamGroup.GEOMETRY, 15.0, 75.0, 2, "\u00b0",
                help = "Angle of the teeth relative to the gear axis.")) +
            profileFields(p) + boreFields(p) + materialFields() + toleranceFields() + loadFields()

        GearType.COMPOUND -> compoundFields(p)

        GearType.BELT -> beltFields()
        }
        // Belt drives and racks are not single gear bodies; their structural fields
        // (lightening, spokes, pockets, index marks) and per-tooth overrides do not
        // apply to the generated geometry, so they are omitted entirely. Compound gears
        // have their own self-contained field set and skip the shared structural fields.
        return if (hasGearBody(p.gearType) && p.gearType != GearType.COMPOUND) base + structuralFields(p) else base
    }

    // ---- number read/write ----------------------------------------------
    fun getNumber(p: GearParams, key: String): Double = when (key) {
        "module" -> if (p.unit == UnitSystem.INCH) GearCalculator.moduleToDiametralPitch(p.module) else p.module
        "teeth" -> p.teeth.toDouble()
        "pressure_angle" -> p.pressureAngleDeg
        "profile_shift" -> p.profileShift
        "helix_angle" -> p.helixAngleDeg
        "thickness" -> p.thickness
        "backlash" -> p.backlash
        "addendum" -> p.addendumCoef
        "dedendum" -> p.dedendumCoef
        "hub_diameter" -> p.hubDiameter
        "hub_length" -> p.hubLength
        "hub_left_length" -> p.hubLeftLength
        "hub_right_length" -> p.hubRightLength
        "hub_chamfer" -> p.hubChamfer
        "hub_fillet" -> p.hubFillet
        "hub_draft_angle" -> p.hubDraftAngleDeg
        "set_screw_angle" -> p.setScrewAngleDeg
        "set_screw_angle2" -> p.setScrewAngle2Deg
        "set_screw_depth" -> p.setScrewDepth
        "set_screw_axial_offset" -> p.setScrewAxialOffset
        "root_fillet_coef" -> p.rootFilletCoef
        "transition_coef" -> p.transitionCoef
        "tip_chamfer" -> p.tipChamfer
        "tip_relief" -> p.tipRelief
        "root_relief" -> p.rootRelief
        "lightening_hole_diameter" -> p.lighteningHoleDiameter
        "lightening_hole_pcd" -> p.lighteningHolePCD
        "lightening_hole_count" -> p.lighteningHoleCount.toDouble()
        "spoke_count" -> p.spokeCount.toDouble()
        "spoke_width" -> p.spokeWidth
        "set_screw_count" -> p.setScrewCount.toDouble()
        "index_mark_angle" -> p.indexMarkAngleDeg
        "bore_diameter" -> p.bore.diameter
        "dcut_offset" -> p.bore.dCutFlatOffset
        "keyway_width" -> p.bore.keywayWidth
        "keyway_depth" -> p.bore.keywayDepth
        "hex_flats" -> p.bore.hexAcrossFlats
        "square_flats" -> p.bore.squareAcrossFlats
        "cone_angle" -> p.coneAngleDeg
        "pitch_cone" -> p.pitchConeDeg
        "mounting_distance" -> p.mountingDistance
        "pinion_teeth" -> p.pinionTeeth.toDouble()
        "rack_length" -> p.rackLength
        "planet_count" -> p.planetCount.toDouble()
        "planet_teeth" -> p.planetTeeth.toDouble()
        "ring_teeth" -> p.ringTeeth.toDouble()
        "worm_starts" -> p.wormStarts.toDouble()
        "wheel_teeth" -> p.wheelTeeth.toDouble()
        "surface_finish" -> p.surfaceFinishUm
        "load" -> p.loadNm
        "speed" -> p.speedRpm
        "lifetime" -> p.lifetimeHours
        "safety_factor" -> p.safetyFactor
        "belt_width" -> p.beltWidthMm
        "belt_driver_teeth" -> p.beltDriverTeeth.toDouble()
        "belt_driven_teeth" -> p.beltDrivenTeeth.toDouble()
        "belt_center_distance" -> p.beltCenterDistanceMm
        "belt_tension" -> p.beltTensionN
        "belt_backlash" -> p.beltBacklashMm
        "belt_flanges" -> p.beltFlangeCount.toDouble()
        "belt_idler_count" -> p.beltIdlerCount.toDouble()
        "stage2_module" -> if (p.unit == UnitSystem.INCH) GearCalculator.moduleToDiametralPitch(p.stage2Module) else p.stage2Module
        "stage2_teeth" -> p.stage2Teeth.toDouble()
        "stage2_face_width" -> p.stage2FaceWidth
        "stage2_pressure_angle" -> p.stage2PressureAngleDeg
        "stage2_phase" -> p.stage2PhaseDeg
        "spacer_height" -> p.spacerHeight
        "spacer_diameter" -> p.spacerDiameter
        else -> 0.0
    }

    fun setNumber(p: GearParams, key: String, v: Double): GearParams {
        // Ignore non-finite input and pre-clamp values that would make copy() re-run
        // the init guards (module > 0, teeth >= 3, thickness > 0) and throw.
        if (!v.isFinite()) return p
        return (when (key) {
        "module" -> p.copy(module = (if (p.unit == UnitSystem.INCH) GearCalculator.diametralPitchToModule(v) else v).coerceIn(0.2, 12.0))
        "teeth" -> p.copy(teeth = v.roundToInt().coerceIn(3, 300))
        "pressure_angle" -> p.copy(pressureAngleDeg = v)
        "profile_shift" -> p.copy(profileShift = v)
        "helix_angle" -> p.copy(helixAngleDeg = v)
        "thickness" -> p.copy(thickness = v.coerceIn(0.1, 500.0))
        "backlash" -> p.copy(backlash = v)
        "addendum" -> p.copy(addendumCoef = v)
        "dedendum" -> p.copy(dedendumCoef = v)
        "hub_diameter" -> p.copy(hubDiameter = v)
        "hub_length" -> p.copy(hubLength = v)
        "hub_left_length" -> p.copy(hubLeftLength = v)
        "hub_right_length" -> p.copy(hubRightLength = v)
        "hub_chamfer" -> p.copy(hubChamfer = v)
        "hub_fillet" -> p.copy(hubFillet = v)
        "hub_draft_angle" -> p.copy(hubDraftAngleDeg = v)
        "set_screw_angle" -> p.copy(setScrewAngleDeg = v)
        "set_screw_angle2" -> p.copy(setScrewAngle2Deg = v)
        "set_screw_depth" -> p.copy(setScrewDepth = v)
        "set_screw_axial_offset" -> p.copy(setScrewAxialOffset = v)
        "root_fillet_coef" -> p.copy(rootFilletCoef = v)
        "transition_coef" -> p.copy(transitionCoef = v)
        "tip_chamfer" -> p.copy(tipChamfer = v)
        "tip_relief" -> p.copy(tipRelief = v)
        "root_relief" -> p.copy(rootRelief = v)
        "lightening_hole_diameter" -> p.copy(lighteningHoleDiameter = v)
        "lightening_hole_pcd" -> p.copy(lighteningHolePCD = v)
        "lightening_hole_count" -> p.copy(lighteningHoleCount = v.roundToInt().coerceIn(0, 12))
        "spoke_count" -> p.copy(spokeCount = v.roundToInt().coerceIn(0, 12))
        "spoke_width" -> p.copy(spokeWidth = v)
        "set_screw_count" -> p.copy(setScrewCount = v.roundToInt().coerceIn(0, 2))
        "index_mark_angle" -> p.copy(indexMarkAngleDeg = v)
        "bore_diameter" -> p.copy(bore = p.bore.copy(diameter = v))
        "dcut_offset" -> p.copy(bore = p.bore.copy(dCutFlatOffset = v))
        "keyway_width" -> p.copy(bore = p.bore.copy(keywayWidth = v))
        "keyway_depth" -> p.copy(bore = p.bore.copy(keywayDepth = v))
        "hex_flats" -> p.copy(bore = p.bore.copy(hexAcrossFlats = v))
        "square_flats" -> p.copy(bore = p.bore.copy(squareAcrossFlats = v))
        "cone_angle" -> p.copy(coneAngleDeg = v)
        "pitch_cone" -> p.copy(pitchConeDeg = v)
        "mounting_distance" -> p.copy(mountingDistance = v)
        "pinion_teeth" -> p.copy(pinionTeeth = maxOf(3, v.roundToInt()))
        "rack_length" -> p.copy(rackLength = v)
        "planet_count" -> p.copy(planetCount = maxOf(2, v.roundToInt()))
        "planet_teeth" -> p.copy(planetTeeth = maxOf(8, v.roundToInt()))
        "ring_teeth" -> p.copy(ringTeeth = maxOf(20, v.roundToInt()))
        "worm_starts" -> p.copy(wormStarts = maxOf(1, v.roundToInt()))
        "wheel_teeth" -> p.copy(wheelTeeth = maxOf(10, v.roundToInt()))
        "surface_finish" -> p.copy(surfaceFinishUm = v)
        "load" -> p.copy(loadNm = v)
        "speed" -> p.copy(speedRpm = v)
        "lifetime" -> p.copy(lifetimeHours = v)
        "safety_factor" -> p.copy(safetyFactor = v)
        "belt_width" -> p.copy(beltWidthMm = v)
        "belt_driver_teeth" -> p.copy(beltDriverTeeth = maxOf(8, v.roundToInt()))
        "belt_driven_teeth" -> p.copy(beltDrivenTeeth = maxOf(8, v.roundToInt()))
        "belt_center_distance" -> p.copy(beltCenterDistanceMm = v)
        "belt_tension" -> p.copy(beltTensionN = v)
        "belt_backlash" -> p.copy(beltBacklashMm = v)
        "belt_flanges" -> p.copy(beltFlangeCount = v.roundToInt().coerceIn(0, 4))
        "belt_idler_count" -> p.copy(beltIdlerCount = v.roundToInt().coerceIn(0, 4))
        "stage2_module" -> p.copy(stage2Module = (if (p.unit == UnitSystem.INCH) GearCalculator.diametralPitchToModule(v) else v).coerceIn(0.2, 12.0))
        "stage2_teeth" -> p.copy(stage2Teeth = v.roundToInt().coerceIn(3, 300))
        "stage2_face_width" -> p.copy(stage2FaceWidth = v.coerceIn(0.1, 500.0))
        "stage2_pressure_angle" -> p.copy(stage2PressureAngleDeg = v)
        "stage2_phase" -> p.copy(stage2PhaseDeg = v)
        "spacer_height" -> p.copy(spacerHeight = v)
        "spacer_diameter" -> p.copy(spacerDiameter = v)
        else -> p
        }).coerced() // cap loop-driving counts/dimensions consistently
    }

    // ---- choice read/write ----------------------------------------------
    fun getChoice(p: GearParams, key: String): String = when (key) {
        "unit" -> if (p.unit == UnitSystem.INCH) UNITS[1] else UNITS[0]
        "bore_type" -> when (p.bore.type) {
            BoreType.NONE -> "None"
            BoreType.ROUND -> "Round"
            BoreType.D_CUT -> "D-cut"
            BoreType.KEYWAY -> "Keyway"
            BoreType.HEX -> "Hex"
            BoreType.SQUARE -> "Square"
        }
        "material" -> p.material
        "tolerance" -> p.toleranceClass
        "lubrication" -> p.lubrication
        "tooth_profile" -> when (p.toothProfile) {
            ToothProfile.INVOLUTE -> "Involute"
            ToothProfile.CYCLOID -> "Cycloid"
            ToothProfile.STRAIGHT -> "Straight"
        }
        "precision" -> p.precision.name
        "set_screw_thread" -> p.setScrewThread
        "index_mark" -> p.indexMarkType
        "bore_hole_tolerance" -> p.boreHoleTolerance
        "keyway_tolerance" -> p.keywayTolerance
        "belt_profile" -> p.beltProfile
        else -> ""
    }

    fun setChoice(p: GearParams, key: String, v: String): GearParams = (when (key) {
        "unit" -> p.copy(unit = if (v.startsWith("inch")) UnitSystem.INCH else UnitSystem.MM)
        "bore_type" -> p.copy(bore = p.bore.copy(type = when (v) {
            "None" -> BoreType.NONE
            "Round" -> BoreType.ROUND
            "D-cut" -> BoreType.D_CUT
            "Keyway" -> BoreType.KEYWAY
            "Hex" -> BoreType.HEX
            "Square" -> BoreType.SQUARE
            else -> BoreType.NONE
        }))
        "material" -> p.copy(material = v)
        "tolerance" -> p.copy(toleranceClass = v)
        "lubrication" -> p.copy(lubrication = v)
        "tooth_profile" -> p.copy(toothProfile = when (v) {
            "Cycloid" -> ToothProfile.CYCLOID
            "Straight" -> ToothProfile.STRAIGHT
            else -> ToothProfile.INVOLUTE
        })
        "precision" -> p.copy(precision = runCatching { PrecisionLevel.valueOf(v) }.getOrDefault(PrecisionLevel.STANDARD))
        "set_screw_thread" -> p.copy(setScrewThread = v)
        "index_mark" -> p.copy(indexMarkType = v)
        "bore_hole_tolerance" -> p.copy(boreHoleTolerance = v)
        "keyway_tolerance" -> p.copy(keywayTolerance = v)
        "belt_profile" -> p.copy(beltProfile = v)
        else -> p
    }).coerced() // audit C3: keep choices in a sanitized state

    fun getBool(p: GearParams, key: String): Boolean = when (key) {
        "hub_left_bore_follows" -> p.hubLeftBoreFollowsShaft
        "hub_right_bore_follows" -> p.hubRightBoreFollowsShaft
        else -> false
    }

    fun setBool(p: GearParams, key: String, v: Boolean): GearParams = when (key) {
        "hub_left_bore_follows" -> p.copy(hubLeftBoreFollowsShaft = v)
        "hub_right_bore_follows" -> p.copy(hubRightBoreFollowsShaft = v)
        else -> p
    }

    // ---- computed results ------------------------------------------------
    /**
     * Computed result rows. Labels are localized keys (`result_*`) resolved by the app;
     * values are formatted with the caller-supplied [fmt] so the decimal separator can
     * follow the active locale (point 15).
     */
    fun results(
        type: GearType,
        p: GearParams,
        fmt: (Double, Int) -> String = { v, d -> String.format("%.${d}f", v) }
    ): List<Pair<String, String>> {
        val m = p.module
        val z = p.teeth
        val unit = if (p.unit == UnitSystem.INCH) "in" else "mm"
        fun d(v: Double) = "${fmt(p.conv(v), 3)} $unit"
        // Transverse module for helical gears (the profile is generated in the
        // transverse plane): m_t = m_n / cos β (ISO 21771).
        val mt = if (p.gearType == GearType.HELICAL && p.helixAngleDeg != 0.0)
            m / Math.cos(Math.toRadians(p.helixAngleDeg)) else m
        val base = when (type) {
            GearType.SPUR, GearType.HELICAL, GearType.CYCLOIDAL, GearType.FACE_GEAR, GearType.SCREW_GEAR -> listOf(
                "result_pitch_diameter" to d(mt * z),
                "result_outer_diameter" to d(2.0 * GearCalculator.tipRadiusShifted(mt, z, p.addendumCoef, p.profileShift)),
                "result_root_diameter" to d(2.0 * GearCalculator.rootRadiusShifted(mt, z, p.dedendumCoef, p.profileShift)),
                "result_base_diameter" to d(mt * z * Math.cos(Math.toRadians(p.pressureAngleDeg)))
            )
            GearType.BEVEL, GearType.HYPOID -> listOf(
                "result_pitch_diameter" to d(mt * z),
                "result_outer_diameter" to d(2.0 * GearCalculator.tipRadiusShifted(mt, z, p.addendumCoef, p.profileShift)),
                "result_cone_angle" to "${fmt(p.coneAngleDeg, 2)}\u00b0"
            )
            GearType.RACK -> listOf(
                "result_rack_length" to d(GearProfiles.rackTeeth(p) * PI * m),
                "result_pinion_pitch_dia" to d(GearCalculator.pitchDiameter(m, p.pinionTeeth)),
                "result_teeth_on_rack" to GearProfiles.rackTeeth(p).toString()
            )
            GearType.PLANETARY -> listOf(
                "result_ratio_fixed_ring" to fmt(GearCalculator.planetaryRatioFixedRing(z, p.ringTeeth), 3),
                "result_planet_center_dist" to d(GearCalculator.centerDistance(m, z, p.planetTeeth)),
                "result_ring_pitch_dia" to d(GearCalculator.pitchDiameter(m, p.ringTeeth))
            )
            GearType.WORM_PAIR -> listOf(
                "result_ratio" to "${p.wheelTeeth}:${p.wormStarts}",
                "result_wheel_pitch_dia" to d(GearCalculator.pitchDiameter(m, p.wheelTeeth))
            )
            GearType.INTERNAL_RING -> listOf(
                "result_ring_pitch_dia" to d(GearCalculator.pitchDiameter(m, z)),
                "result_inner_dia" to d(m * z - 2.0 * m),
                "result_outer_dia" to d(m * z + 2.5 * m)
            )
            GearType.HARMONIC_DRIVE -> listOf(
                "result_flexspline_teeth" to z.toString(),
                "result_pitch_diameter" to d(GearCalculator.pitchDiameter(m, z))
            )
            GearType.COMPOUND -> listOf(
                "result_pitch_diameter" to d(mt * z),
                "result_stage2_pitch_dia" to d(p.stage2Module * p.stage2Teeth),
                "result_ratio" to fmt(p.stage2Teeth.toDouble() / z, 3),
                "result_total_height" to d(p.thickness + p.spacerHeight + p.stage2FaceWidth)
            )
            GearType.BELT -> {
                val t = p.toBeltTransmission()
                val r = BeltCalculator.resolve(t)
                listOf(
                    "result_ratio" to fmt(r.ratio, 3),
                    "result_belt_teeth" to r.beltTeeth.toString(),
                    "result_belt_length" to d(r.beltLengthMm),
                    "result_center_distance" to d(r.centerDistanceMm),
                    "result_min_engaged_teeth" to r.minEngagedTeeth.toString(),
                    "result_driver_pitch_dia" to d(r.driverPitchDia),
                    "result_driven_pitch_dia" to d(r.drivenPitchDia)
                )
            }
        }
        return base + listOf(
            "result_weight" to "${fmt(GearCalculator.weightKg(p), 3)} kg",
            "result_inertia" to "${fmt(GearCalculator.momentOfInertia(p), 6)} kg·m²",
            "result_backlash" to "${fmt(GearCalculator.effectiveBacklash(p), 3)} mm"
        )
    }

    /**
     * Validates geometric relationships that can produce NaN or degenerate geometry.
     *
     * Returns a (possibly empty) list of [GearWarning]s. The UI surfaces these as
     * localized warnings; the builder also clamps/corrects the worst cases so a warning
     * is advisory rather than a hard error.
     */
    fun validate(p: GearParams): List<GearWarning> = buildList {
        // Module outside the sane printable range (degenerately small or absurdly large).
        if (p.module < MIN_MODULE_MM || p.module > MAX_MODULE_MM) {
            add(GearWarning(WARN_MODULE))
        }

        // Tooth count outside the supported range is clamped by coerced() (involute ≥ 8,
        // cycloid ≥ 6, straight ≥ 5, max 300); surface the clamp.
        if (hasGearBody(p.gearType)) {
            val minTeeth = when (p.toothProfile) {
                ToothProfile.CYCLOID -> 6
                ToothProfile.STRAIGHT -> 5
                ToothProfile.INVOLUTE -> 8
            }
            if (p.teeth < minTeeth || p.teeth > 300) {
                add(GearWarning(WARN_TEETH, detail = "$minTeeth..300"))
            }
        }

        // Profile shift outside ±1 is clamped by coerced() (degenerate teeth beyond).
        if (p.profileShift < -1.0 || p.profileShift > 1.0) {
            add(GearWarning(WARN_PROFILE_SHIFT))
        }

        // Helix angle beyond ±85° is clamped by coerced(); warn before the loft degenerates.
        val usesHelix = p.gearType == GearType.HELICAL ||
            p.gearType == GearType.WORM_PAIR ||
            p.gearType == GearType.SCREW_GEAR
        if (usesHelix && kotlin.math.abs(p.helixAngleDeg) > 85.0) {
            add(GearWarning(WARN_HELIX_ANGLE))
        }

        // Undercut: an involute flank below z_min = 2/sin²α cuts into the root unless
        // a positive profile shift x ≥ 1 − z·sin²α/2 is applied (ISO 21771 / KHK).
        // A 0.1 shift margin suppresses the warning for negligible undercut (e.g. the
        // 16-tooth helical default sits only 0.06 below the threshold).
        val involuteBody = p.gearType in setOf(GearType.SPUR, GearType.HELICAL) &&
            p.toothProfile == ToothProfile.INVOLUTE
        if (involuteBody) {
            val xMin = GearCalculator.minimumShiftNoUndercut(p.teeth, p.pressureAngleDeg)
            if (p.profileShift < xMin - 0.1) {
                add(GearWarning(WARN_UNDERCUT, detail = "x_min = " + String.format("%.2f", xMin)))
            }
        }

        // Backlash must stay below 0.2·m (spec), otherwise it can invert or
        // self-intersect teeth at small module (audit C2). The percentage-of-module
        // form is honoured through [GearParams.effectiveBacklashMm].
        val effBacklash = p.effectiveBacklashMm()
        if (effBacklash > 0.2 * p.module) {
            add(GearWarning(WARN_BACKLASH))
        }

        // Tooth top-land thinning: excessive backlash or negative profile shift can
        // thin the tip below the printable/strength limit. The minimum width is
        // configurable and defaults to 0.2·m (audit B2).
        val extInvolute = p.toothProfile == ToothProfile.INVOLUTE &&
            p.gearType !in setOf(GearType.RACK, GearType.BELT, GearType.INTERNAL_RING, GearType.WORM_PAIR)
        if (extInvolute) {
            val minTopLand = if (p.minimumTopLandWidth > 0.0) p.minimumTopLandWidth else 0.2 * p.module
            if (GearCalculator.topLandWidth(p) < minTopLand) {
                add(GearWarning(WARN_TOPLAND, detail = "min " + String.format("%.2f", minTopLand)))
            }
        }

        // Tooth overlap: at high pressure angles the involute flank is so flat that
        // the tooth is wider at the root than the tooth space, so adjacent teeth
        // physically overlap and the outline self-intersects (audit H3: α=45°, z=20).
        // Flag it when the generated tooth arc thickness at the flank start radius
        // (max of base and root circle) reaches the arc pitch.
        if (involuteBody) {
            val rb = GearCalculator.baseRadius(p.module, p.teeth, p.pressureAngleDeg)
            val rf = GearCalculator.rootRadiusShifted(p.module, p.teeth, p.dedendumCoef, p.profileShift)
            val rStart = max(rb, rf)
            if (rStart > 0.0) {
                val arcPitch = 2.0 * PI * rStart / p.teeth
                if (GearCalculator.toothThicknessAtRadius(p, rStart) >= arcPitch) {
                    add(GearWarning(WARN_TOOTH_OVERLAP))
                }
            }
        }

        // Compound gear: both stages must fit the bore, and the spacer must stay clear
        // of the teeth and of the bore wall (audit: dubbelkugghjul).
        if (p.gearType == GearType.COMPOUND) {
            if (p.stage2Teeth < 8 || p.stage2Teeth > 300) {
                add(GearWarning(WARN_TEETH, detail = "stage 2: 8..300"))
            }
            if (p.stage2FaceWidth <= 0.0) {
                add(GearWarning(WARN_TEETH, detail = "stage 2 face width must be > 0"))
            }
            val r1 = GearCalculator.rootRadiusShifted(p.module, p.teeth, p.dedendumCoef, p.profileShift)
            val r2 = GearCalculator.rootRadiusShifted(p.stage2Module, p.stage2Teeth, p.dedendumCoef, p.stage2ProfileShift)
            if (p.bore.type != BoreType.NONE) {
                val minWall = max(0.6, 0.4 * maxOf(p.module, p.stage2Module))
                if (Bore.boreOuterRadius(p) >= minOf(r1, r2) - minWall) {
                    add(GearWarning(WARN_BORE))
                }
            }
            if (p.spacerDiameter > 0.0) {
                val rDisc = p.spacerDiameter / 2.0
                if (rDisc <= Bore.boreOuterRadius(p) + 0.2 || rDisc >= minOf(r1, r2) - 0.2) {
                    add(GearWarning(WARN_SPACER, detail = "spacer must clear the bore and both tooth roots"))
                }
            }
        }

        // Bore larger than the gear body cuts away the teeth entirely. Only single-gear
        // bodies actually cut the bore; ring/worm/rack/belt do not (audit C9).
        val cutsBore = p.gearType != GearType.RACK && p.gearType != GearType.BELT &&
            p.gearType != GearType.INTERNAL_RING && p.gearType != GearType.WORM_PAIR
        if (p.bore.type != BoreType.NONE && cutsBore) {
            val rootD = 2.0 * GearCalculator.rootRadiusShifted(p.module, p.teeth, p.dedendumCoef, p.profileShift)
            val boreD = when (p.bore.type) {
                BoreType.HEX -> p.bore.hexAcrossFlats
                BoreType.SQUARE -> p.bore.squareAcrossFlats
                else -> p.bore.diameter
            }
            // Keep a printable wall of material between the bore and the tooth root
            // (twice the minimum wall thickness).
            val minWall = max(0.6, 0.4 * p.module)
            if (rootD > 0.0 && boreD >= rootD - 2.0 * minWall) {
                add(GearWarning(WARN_BORE))
            }
        }

        // Planetary assembly relationships.
        if (p.gearType == GearType.PLANETARY) {
            val minRing = p.teeth + 2 * p.planetTeeth
            // The builder forces Zr = Zs + 2·Zp; warn when the user value differs.
            if (p.ringTeeth != minRing) {
                add(GearWarning(WARN_RING_TEETH, detail = "ring forced to $minRing teeth"))
            }
            // Equally-spaced planets require (Zs + Zr) / N to be an integer; otherwise
            // the planets cannot mesh with both sun and ring simultaneously.
            val n = p.planetCount
            if (n >= 2 && (p.teeth + minRing) % n != 0) {
                add(GearWarning(WARN_PLANET_PHASE, detail = "uneven planet phasing with $n planets"))
            }
            // Adjacent planet gears must not overlap.
            if (n >= 2) {
                val centerDist = GearCalculator.centerDistance(p.module, p.teeth, p.planetTeeth)
                val planetOuter = GearCalculator.outerRadius(p.module, p.planetTeeth)
                if (centerDist * sin(PI / n) <= planetOuter) {
                    add(GearWarning(WARN_PLANET_OVERLAP))
                }
            }
        }

        // Belt transmission checks (delegated to the belt engine).
        if (p.gearType == GearType.BELT) {
            for (e in BeltCalculator.validate(p.toBeltTransmission())) {
                when {
                    e.startsWith("pulleys") -> add(GearWarning(WARN_BELT_TEETH, detail = e))
                    e.startsWith("belt narrower") -> add(GearWarning(WARN_BELT_WIDTH, detail = e))
                    e.startsWith("1:1") -> add(GearWarning(WARN_BELT_WRAP, detail = e))
                    else -> add(GearWarning(WARN_BELT_MESH_TEETH, detail = e))
                }
            }
        }

        // Lightening holes: warn when they were auto-corrected or omitted so the
        // user sees feedback instead of a silently unchanged gear.
        if (p.lighteningHoleCount > 0) {
            val plan = Bore.lighteningPlan(p)
            val resized = plan.requestedDiameter > 0.0 &&
                plan.radius * 2.0 < plan.requestedDiameter - 1e-9
            when {
                plan.count == 0 -> add(GearWarning(WARN_LIGHTENING_HOLE, detail = "omitted"))
                plan.count < plan.requestedCount -> add(GearWarning(WARN_LIGHTENING_HOLE, detail = "count reduced"))
                resized -> add(GearWarning(WARN_LIGHTENING_HOLE, detail = "diameter reduced"))
            }
        }

        // ---- hub / boss / collar ----
        val hubL = GearCalculator.effectiveHubLeft(p)
        val hubR = GearCalculator.effectiveHubRight(p)
        val hasHub = hubL > 0.0 || hubR > 0.0
        if (hasHub) {
            val boreD = when (p.bore.type) {
                BoreType.HEX -> p.bore.hexAcrossFlats
                BoreType.SQUARE -> p.bore.squareAcrossFlats
                else -> p.bore.diameter
            }
            // Hard error: hub wall thinner than the minimum.
            if (p.hubDiameter < boreD + 2.0 * MIN_HUB_WALL) {
                add(GearWarning(WARN_HUB_WALL, severity = GearSeverity.ERROR))
            }
            // Hard error: chamfer larger than the available material.
            val maxChamfer = minOf(p.hubDiameter / 2.0, hubL, hubR)
            if (p.hubChamfer > maxChamfer) {
                add(GearWarning(WARN_HUB_CHAMFER, severity = GearSeverity.ERROR))
            }
            // Warning: hub covers the tooth root (cuts the teeth).
            val rootD = 2.0 * GearCalculator.rootRadiusShifted(p.module, p.teeth, p.dedendumCoef, p.profileShift)
            if (rootD > 0.0 && p.hubDiameter >= rootD) {
                add(GearWarning(WARN_HUB_COVERS_ROOT))
            }
        }
        // Grub screws require hub material.
        if (p.setScrewCount > 0 && !hasHub) {
            add(GearWarning(WARN_GRUB_NO_HUB, severity = GearSeverity.ERROR))
        }
        // Set screws need enough hub wall for the radial clearance hole plus a printable
        // wall; hard error when the hub diameter is insufficient (audit B3).
        if (p.setScrewCount > 0 && hasHub) {
            val wall = p.hubDiameter / 2.0 - Bore.boreOuterRadius(p)
            val need = HubBuilder.screwMinorRadius(p.setScrewThread) + MIN_HUB_WALL
            if (wall < need) {
                add(GearWarning(WARN_SETSCREW, detail = "needs " + String.format("%.1f", need) + " mm wall", severity = GearSeverity.ERROR))
            }
        }
        // Per-tooth overrides: thickness must keep a positive gap and angles must stay < 90°.
        val nominalThick = PI * p.module / 2.0
        for ((idx, o) in p.toothOverrides) {
            val badAngle =
                (o.leftPressureAngleDeg?.let { it <= 0.0 || it >= 89.0 } == true) ||
                (o.rightPressureAngleDeg?.let { it <= 0.0 || it >= 89.0 } == true)
            if (badAngle) {
                add(GearWarning(WARN_TOOTH_THICK, detail = "tooth $idx angle", severity = GearSeverity.ERROR))
            }
            val t = o.toothThickness
            if (t != null) {
                if (t <= 0.0 || t >= PI * p.module) {
                    add(GearWarning(WARN_TOOTH_THICK, detail = "tooth $idx", severity = GearSeverity.ERROR))
                } else if (t > 0.7 * PI * p.module) {
                    add(GearWarning(WARN_TOOTH_THICK, detail = "tooth $idx too thick"))
                }
            }
        }
        if (nominalThick <= 0.0) {
            add(GearWarning(WARN_TOOTH_THICK, severity = GearSeverity.ERROR))
        }
    }

    private fun GearParams.conv(v: Double): Double =
        if (unit == UnitSystem.INCH) v / 25.4 else v
}
