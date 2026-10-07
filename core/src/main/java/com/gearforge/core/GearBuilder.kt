package com.gearforge.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A planetary gear set (sun + planets + internal ring) as separate meshes. */
data class PlanetaryAssembly(
    val sun: Mesh,
    val ring: Mesh,
    val planets: List<Mesh>,
    val planetCenters: List<Vec2>,
    val sunTeeth: Int,
    val planetTeeth: Int,
    val ringTeeth: Int,
    val ratio: Double
)

/** Multiple meshes with placement offsets for a single parameter set. */
data class GearAssembly(val meshes: List<Mesh>, val offsets: List<Vec2>)

/** High-level entry point that turns [GearParams] into meshes. */
object GearBuilder {

    /**
     * The 2D profile the body is built from: the same contour [mesh] extrudes.
     *
     * Two families used to get a different answer here than in [mesh], which is a defect rather
     * than a simplification — the SVG and DXF exports, the scrub preview and the outline a tap is
     * resolved against all read this function, so they described a body the viewport never showed:
     *
     *  - **Crossed-helical (screw) gears** were generated in the normal module while [mesh] lofted
     *    them in the transverse one, so the 2D profile was `1/cos β` smaller than the solid.
     *  - **Internal rings** were returned as an *external* gear, while [mesh] extruded a rim whose
     *    inner boundary is the tooth profile.
     */
    fun shape(p: GearParams): PlanarShape = when (p.gearType) {
        GearType.RACK -> PlanarShape(GearProfiles.rackOutline(p), emptyList())
        GearType.BELT -> BeltBuilder.beltPath2D(p.toBeltTransmission())
        GearType.INTERNAL_RING -> ringShape(p)
        else -> {
            val q = profileParams(p)
            // Spokes and lightening holes both occupy the annulus between the bore
            // and the rim; when both are enabled the circular holes can intersect the
            // spoke wedges and break the hole triangulation. Spokes win (they define
            // the structural web), lightening holes are dropped in that case (audit L3).
            val spokes = Bore.spokeWedgeHoles(q)
            val lightening = if (spokes.isNotEmpty()) emptyList() else Bore.lighteningHoles(q)
            PlanarShape(
                GearProfiles.externalOutline(q),
                Bore.holes(q) + lightening + spokes + Bore.indexMarkHoles(q)
            )
        }
    }

    /**
     * The parameters the tooth profile is generated in.
     *
     * For the helical families the user-facing module is the *normal* one, so the profile in the
     * transverse plane needs `m_t = m_n / cos β`. [shape] and [mesh] both go through here, so the
     * conversion has one definition and can neither be applied twice nor forgotten.
     */
    private fun profileParams(p: GearParams): GearParams =
        if (p.gearType == GearType.HELICAL || p.gearType == GearType.SCREW_GEAR) helicalParams(p) else p

    fun mesh(p: GearParams): Mesh {
        // Defensive clamp so out-of-range parameters can never produce NaN/degenerate
        // geometry at the engine entry points (audit H1).
        val p = p.coerced()
        return when (p.gearType) {
        GearType.RACK -> MeshBuilder.extrude(PlanarShape(GearProfiles.rackOutline(p)), p.thickness)
        GearType.HELICAL -> Loft.loft(
            shape(p), p.thickness,
            twistRad = helicalTwist(p),
            scaleStart = 1.0, scaleEnd = 1.0, slices = sliceCount(p)
        )
        GearType.BEVEL, GearType.HYPOID -> Loft.loft(
            bevelShape(p), p.thickness,
            twistRad = 0.0,
            scaleStart = 1.0, scaleEnd = bevelScale(p), slices = sliceCount(p)
        )
        GearType.INTERNAL_RING -> ringMesh(p)
        GearType.WORM_PAIR -> wheelMesh(p)
        GearType.COMPOUND -> CompoundGearBuilder.mesh(p)
        GearType.SCREW_GEAR -> Loft.loft(
            shape(p), p.thickness,
            twistRad = helicalTwist(p),   // audit C7: honour the user's helix_angle
            scaleStart = 1.0, scaleEnd = 1.0, slices = sliceCount(p)
        )
        GearType.BELT -> merged(p)
        else -> {
            val s = shape(p)
            if (p.elephantFootChamferMm > 0.0) {
                Loft.loftWithBottomChamfer(s, p.thickness, p.elephantFootChamferMm)
            } else {
                MeshBuilder.extrude(s, p.thickness)
            }
        }
        }
    }

    /**
     * Builds the full assembly (one or more meshes plus placement offsets) for a
     * parameter set. Single gears return one mesh; rack/worm/planetary return the
     * relevant multiple meshes positioned relative to each other.
     */
    fun assembly(p: GearParams): GearAssembly {
        val p = p.coerced()
        return when (p.gearType) {
        GearType.RACK -> {
            val rack = mesh(p)
            val pinion = mesh(p.copy(gearType = GearType.SPUR, teeth = p.pinionTeeth))
            val pr = GearCalculator.pitchRadius(p.module, p.pinionTeeth)
            val teeth = GearProfiles.rackTeeth(p)
            val pitch = Math.PI * p.module
            // Shift the rack so a tooth GAP sits exactly under the pinion (x = 0),
            // and rotate the pinion so a pinion TOOTH points down into that gap.
            // This makes the rack and pinion mesh instead of collide (audit H2).
            val rackOffsetX = -(teeth / 2) * pitch
            val pinionRotated = MeshOps.rotateZ(pinion, -Math.PI / 2.0)
            GearAssembly(listOf(rack, pinionRotated), listOf(Vec2(rackOffsetX, 0.0), Vec2(0.0, pr)))
        }
        GearType.PLANETARY -> {
            val a = planetary(p)
            GearAssembly(
                listOf(a.sun, a.ring) + a.planets,
                listOf(Vec2(0.0, 0.0), Vec2(0.0, 0.0)) + a.planetCenters
            )
        }
        GearType.WORM_PAIR -> {
            // The worm's axis is perpendicular to the wheel's axis (a real worm pair):
            // rotate the worm 90° about Y so its screw axis runs along X, then sit its
            // pitch circle tangent to the wheel's throat pitch circle at the top. The
            // throated wheel is built centred on Z so its central plane (z = 0) lines up
            // with the worm's axis plane, where the throat radius equals the worm pitch
            // radius exactly.
            val worm = MeshOps.rotateY(wormMesh(p), Math.PI / 2.0)
            val wheel = wheelMesh(p)
            val rWheel = GearCalculator.pitchRadius(p.module, p.wheelTeeth)
            val rWorm = wormPitchRadius(p)
            val wormLen = max(8.0, p.module * 8.0)
            GearAssembly(
                listOf(worm, wheel),
                listOf(Vec2(-wormLen / 2.0, rWheel + rWorm), Vec2(0.0, 0.0))
            )
        }
        GearType.BELT -> {
            val t = p.toBeltTransmission()
            val a = BeltBuilder.assembly(t)
            val belt = BeltBuilder.beltBandMesh(t)
            // The belt band wraps only driver+driven, so idler pulleys are omitted to keep
            // the geometry consistent with the band (audit C10: two-pulley model).
            GearAssembly(
                listOf(belt, a.driver, a.driven),
                listOf(Vec2(0.0, 0.0), a.driverCenter, a.drivenCenter)
            )
        }
        else -> {
            val meshes = ArrayList<Mesh>()
            val offsets = ArrayList<Vec2>()
            meshes.add(mesh(p))
            offsets.add(Vec2(0.0, 0.0))
            if (HubBuilder.hasHub(p)) {
                meshes.add(HubBuilder.build(p))
                offsets.add(Vec2(0.0, 0.0))
            }
            GearAssembly(meshes, offsets)
        }
        }
    }

    /**
     * Localization keys for the bodies of [assembly], in the same order.
     *
     * The viewport legend names each body, and it can only do that if something knows what the
     * bodies *are*. That knowledge belongs next to the code that decides their order — a legend is
     * positional, so a mismatch would label the ring as the sun. `AssemblyBodyKeysTest` checks the
     * two lists against each other for every gear type so the two cannot drift apart.
     */
    fun bodyKeys(p: GearParams): List<String> {
        val q = p.coerced()
        return when (q.gearType) {
            GearType.RACK -> listOf("body_rack", "body_pinion")
            GearType.PLANETARY -> listOf("body_sun", "body_ring") +
                List(q.planetCount.coerceIn(2, 6)) { "body_planet" }
            GearType.WORM_PAIR -> listOf("body_worm", "body_wheel")
            GearType.BELT -> listOf("body_belt", "body_driver", "body_driven")
            else -> listOf("body_gear") + if (HubBuilder.hasHub(q)) listOf("body_hub") else emptyList()
        }
    }

    /** Merges an assembly into one mesh with placement offsets applied (for single-file export). */
    fun merged(p: GearParams): Mesh {
        val a = assembly(p)
        if (a.meshes.size == 1) return a.meshes[0]
        val verts = ArrayList<Vec3>()
        val tris = ArrayList<IntArray>()
        for (i in a.meshes.indices) {
            val m = a.meshes[i]
            val ox = a.offsets[i].x
            val oy = a.offsets[i].y
            val base = verts.size
            for (v in m.vertices) verts.add(Vec3(v.x + ox, v.y + oy, v.z))
            for (t in m.triangles) tris.add(intArrayOf(t[0] + base, t[1] + base, t[2] + base))
        }
        return Mesh(verts, tris)
    }

    /** Worm tooth count: the worm is built as a helical spline with 4 teeth per start. */
    private fun wormTeeth(p: GearParams): Int = max(4, p.wormStarts * 4)

    /**
     * Worm pitch radius: the circle the worm is seated on, and the throat arc's radius.
     *
     * Public because it is a reported dimension: `GearSpec` prints the worm's pitch diameter and
     * the pair's centre distance (`rWheel + rWorm`, the distance `assembly` actually places them
     * at) from this number, and the rule that a worm is modelled with four threads per start must
     * not have a second home.
     */
    fun wormPitchRadius(p: GearParams): Double =
        GearCalculator.pitchRadius(p.module, wormTeeth(p))

    private fun wormMesh(p: GearParams): Mesh {
        val teeth = wormTeeth(p)
        // WORM_PAIR has no bore control; the worm is a solid blank (audit C8).
        val worm = p.copy(
            gearType = GearType.SPUR, teeth = teeth,
            thickness = max(8.0, p.module * 8.0), bore = BoreSpec(type = BoreType.NONE)
        )
        return Loft.loft(
            shape(worm), worm.thickness,
            twistRad = helicalTwist(worm), scaleStart = 1.0, scaleEnd = 1.0, slices = sliceCount(worm)
        )
    }

    /**
     * True throated (globoid) worm wheel. The rim follows a concave arc of radius
     * [wormPitchRadius] centred on the worm axis, so the wheel wraps around the screw:
     * at the central plane the pitch radius is rp and toward both faces it bulges out
     * to rp + rWorm − √(rWorm² − z²). The teeth stay straight in the axial direction
     * (globoid teeth are radial, not helical) while their cross-section scales with the
     * local throat radius, so the tooth flanks and root envelope the worm thread. The
     * loft keeps a closed 2-manifold.
     */
    private fun wheelMesh(p: GearParams): Mesh {
        val m = p.module
        val n = max(10, p.wheelTeeth)
        val rp = m * n / 2.0               // wheel pitch radius at the throat (central plane)
        val rWorm = wormPitchRadius(p)     // throat arc radius = worm pitch radius
        val thickness = p.thickness
        // WORM_PAIR has no bore control; the wheel is a solid blank (audit C8).
        val wheel = p.copy(gearType = GearType.SPUR, teeth = n, bore = BoreSpec(type = BoreType.NONE))
        val slices = max(24, sliceCount(wheel))
        val halfT = thickness / 2.0

        val mesh = Loft.loftProfiled(
            shape(wheel), thickness, slices,
            scaleAt = { t ->
                val dz = t * thickness - halfT               // −halfT .. +halfT about the mid-plane
                val zc = dz.coerceIn(-rWorm, rWorm)
                (rp + rWorm - sqrt(max(0.0, rWorm * rWorm - zc * zc))) / rp
            },
            twistAt = { 0.0 }
        )
        // Centre the wheel on Z so the throat (mid-plane, z = 0) lines up with the
        // worm's axis plane, where the throat radius equals the worm pitch radius.
        return Mesh(mesh.vertices.map { Vec3(it.x, it.y, it.z - halfT) }, mesh.triangles)
    }

    /** Structural rim thickness beyond the tooth root, in millimetres. */
    private fun ringRimMm(p: GearParams): Double = max(2.0, 2.0 * p.module)

    /**
     * The internal ring's outer radius: the tooth root circle plus the structural rim.
     *
     * Public because it is a reported dimension. `GearSpec` used to print `m·z/2 + 1.25·m` — the
     * *root* circle — as the ring's "Outer dia.", which is where the teeth end and the rim begins,
     * on a part whose outer edge is `max(2 mm, 2·m)` further out. The HUD's anchor sat on the same
     * inner circle, so the label pointed at a boundary that is not the part's edge.
     */
    fun ringOuterRadius(p: GearParams): Double {
        val q = p.coerced()
        return q.module * q.teeth / 2.0 + 1.25 * q.module + ringRimMm(q)
    }

    /**
     * The internal ring's 2D shape: a solid rim whose inner boundary is the tooth profile.
     *
     * Shared with [shape], because a ring's DXF and SVG export has to be a ring. It used to be an
     * external gear profile, so the 2D files described a different part from the model.
     */
    private fun ringShape(p: GearParams): PlanarShape {
        val q = p.coerced()
        val rp = q.module * q.teeth / 2.0
        val rRoot = rp + 1.25 * q.module
        val rIn = rp - q.module
        require(rIn < rRoot) { "Ring inner radius ($rIn) must be < root radius ($rRoot)" }
        // A solid rim beyond the tooth root, with the toothed inner boundary as a
        // hole. Proper triangulation (hole bridging + vertex dedupe) replaces the
        // previous manual radial pairing that produced zero rim and skewed walls
        // (audit M4).
        val outer = circle(ringOuterRadius(q), 96)
        val toothHole = GearProfiles.internalRingOutline(q)
        return PlanarShape(outer, listOf(toothHole))
    }

    fun ringMesh(p: GearParams): Mesh =
        MeshBuilder.extrude(ringShape(p), p.coerced().thickness)

    fun planetary(p: GearParams): PlanetaryAssembly {
        val p = p.coerced()
        val m = p.module
        val sunTeeth = max(5, p.teeth)
        val planetTeeth = max(8, p.planetTeeth)
        // Zr = Zs + 2·Zp is a hard meshing constraint; the ring must have exactly this
        // many teeth. A mismatched user value is overridden (with a validate() warning).
        // One definition, shared with the kinematics and with what `GearSpec` reports.
        val ringTeeth = GearCalculator.planetaryRingTeeth(p.teeth, p.planetTeeth)
        val planetCount = p.planetCount.coerceIn(2, 6)

        val sun = mesh(p.copy(gearType = GearType.SPUR, teeth = sunTeeth))
        val planetParams = p.copy(
            gearType = GearType.SPUR,
            teeth = planetTeeth,
            bore = p.bore.copy(diameter = max(2.0, p.module * 2.0))
        )
        val basePlanet = mesh(planetParams)
        val ring = ringMesh(p.copy(teeth = ringTeeth))

        val planetDist = GearCalculator.centerDistance(m, sunTeeth, planetTeeth)
        // Each planet revolves to angle θᵢ and rotates about its own axis so a planet
        // tooth always sits in the sun's gap: φᵢ = −θᵢ·(Zs/Zp) + π/Zp. With Zr = Zs + 2Zp
        // this simultaneously seats a ring tooth into the planet's outward gap whenever
        // the equally-spaced condition (Zs+Zr)/N is an integer.
        val planets = (0 until planetCount).map { i ->
            val theta = 2.0 * PI * i / planetCount
            val phi = -theta * sunTeeth / planetTeeth + PI / planetTeeth
            MeshOps.rotateZ(basePlanet, phi)
        }
        val centers = (0 until planetCount).map { i ->
            val a = 2.0 * PI * i / planetCount
            Vec2(planetDist * cos(a), planetDist * sin(a))
        }
        val ratio = GearCalculator.planetaryRatioFixedRing(sunTeeth, ringTeeth)

        return PlanetaryAssembly(sun, ring, planets, centers, sunTeeth, planetTeeth, ringTeeth, ratio)
    }

    /**
     * A thin, slightly oversized radial wedge that marks a single overridden tooth
     * in the 3D viewport. It is extruded a little beyond the gear faces so the
     * renderer can draw it as a distinct-colour overlay without changing the
     * underlying gear geometry.
     *
     * The wedge is placed from the **generated outline**, not from the nominal tooth phase. The
     * nominal phase (`2π·i/n`) is the angle tooth 0 happens to be built at; it is not a property of
     * the tooth. A per-tooth asymmetric pressure angle moves the tip centre sideways, and a profile
     * shift or a modified addendum coefficient moves it radially — so a nominal wedge could sit
     * beside the tooth it claims to mark, while [ToothPick] refuses to name a tooth it cannot read
     * back from the outline. The tap that set `toothOverrides` and the wedge that shows it would
     * then disagree about which tooth is meant, and the marker is the only feedback there is.
     *
     * Radii come from the same outline, so a shifted or shifted-addendum tooth is marked out to its
     * real tip rather than to the unmodified one. Crossed-helical and helical bodies are generated
     * from the transverse module (see [mesh]), so the wedge is measured in the same plane.
     */
    fun toothHighlightMesh(p: GearParams, toothIndex: Int): Mesh {
        val q = p.coerced()
        val n = q.teeth
        val idx = ((toothIndex % n) + n) % n
        val pitch = 2.0 * PI / n
        val centre = ToothPick.toothCentreAngles(q)?.getOrNull(idx) ?: (pitch * idx)
        val outline = highlightOutline(q)
        var rTip = -1.0
        var rRoot = Double.MAX_VALUE
        for (v in outline) {
            val r = sqrt(v.x * v.x + v.y * v.y)
            if (angleBetween(v.x, v.y, centre) <= pitch / 2.0) {
                if (r > rTip) rTip = r
                if (r < rRoot) rRoot = r
            }
        }
        // A type whose outline is not one gear (a belt path, a profile too coarse to resolve teeth)
        // falls back to the analytic radii rather than to an empty marker.
        val module = profileParams(q).module
        if (rTip <= 0.0) rTip = GearCalculator.outerRadius(module, n)
        if (rRoot >= rTip || rRoot == Double.MAX_VALUE) rRoot = GearCalculator.rootRadius(module, n)

        val half = pitch * 0.36
        val a1 = centre - half
        val a2 = centre + half
        val poly = listOf(
            Vec2.polar((rRoot - 0.4).coerceAtLeast(0.4), a1),
            Vec2.polar(rTip + 0.5, a1),
            Vec2.polar(rTip + 0.5, a2),
            Vec2.polar((rRoot - 0.4).coerceAtLeast(0.4), a2)
        )
        val t = p.thickness + 0.6
        val m = MeshBuilder.extrude(PlanarShape(poly, emptyList()), t)
        return Mesh(m.vertices.map { Vec3(it.x, it.y, it.z - 0.3) }, m.triangles)
    }

    /** Smallest angle between a direction and [angle], in radians. */
    private fun angleBetween(x: Double, y: Double, angle: Double): Double {
        val twoPi = 2.0 * PI
        val d = Math.abs(Math.atan2(y, x) - angle) % twoPi
        return if (d > PI) twoPi - d else d
    }

    /**
     * Single-entry memo for the outline [toothHighlightMesh] measures against.
     *
     * One composition pass asks for one wedge per overridden tooth, and every one of them needs the
     * same outline. Generating it per marker would triangulate the whole profile N times on the
     * composition thread, which is exactly the kind of work the 200-tooth types cannot afford.
     * `GearParams` is a data class, so the key is structural: a parameter change misses and the
     * stale entry is replaced rather than kept.
     */
    private var outlineMemoKey: GearParams? = null
    private var outlineMemo: List<Vec2> = emptyList()

    private fun highlightOutline(p: GearParams): List<Vec2> {
        outlineMemoKey?.let { if (it == p) return outlineMemo }
        val outline = if (p.gearType == GearType.INTERNAL_RING) {
            // A ring's teeth are its *hole*. `shape` answers with the rim disc, whose only outline is
            // a constant-radius circle, so a marker measured against it would be a blob on the rim
            // rather than on the tooth the override names.
            runCatching { GearProfiles.internalRingOutline(p) }.getOrNull().orEmpty()
        } else {
            runCatching { shape(p).outer }.getOrNull().orEmpty()
        }
        outlineMemoKey = p
        outlineMemo = outline
        return outline
    }

    fun circle(radius: Double, segments: Int = 96): List<Vec2> =
        (0 until segments).map { k -> Vec2.polar(radius, 2.0 * PI * k / segments) }

    /**
     * For helical / crossed-helical (screw) gears the user-facing [GearParams.module] is
     * the NORMAL module m_n. The transverse module is m_t = m_n / cos β, and the tooth
     * profile and pitch diameter are computed in the transverse plane (audit L1).
     */
    private fun helicalParams(p: GearParams): GearParams {
        val beta = Math.toRadians(p.helixAngleDeg.coerceIn(-85.0, 85.0))
        if (beta == 0.0) return p
        return p.copy(module = p.module / cos(beta))
    }

    private fun helicalTwist(p: GearParams): Double {
        if (p.helixAngleDeg == 0.0) return 0.0
        val beta = Math.toRadians(p.helixAngleDeg)
        // Pitch radius in the transverse plane: r_p = m_t · z / 2 = m_n · z / (2 cos β).
        val rp = p.module / cos(beta) * p.teeth / 2.0
        return p.thickness * tan(beta) / rp
    }

    /**
     * Virtual tooth count of a back-cone profile: `round(z / cos δ)`, floored at 5.
     *
     * The spur profile that is correct on a cone has `z_v = z / cos δ` teeth, and that count is
     * *rounded* because a profile needs a whole number. Public because it is also a reported
     * quantity: `GearSpec` has to report the diameters of the rounded profile, or the number beside
     * the model disagrees with the mesh by `m·Δz·cos δ` — 0.1 mm on a 20-tooth bevel at δ = 45°.
     */
    fun bevelVirtualTeeth(p: GearParams): Int {
        val delta = Math.toRadians(p.pitchConeDeg.coerceIn(5.0, 85.0))
        return max(5, (p.teeth / cos(delta)).roundToInt())
    }

    /**
     * Bevel/hypoid tooth profile on the back cone: the tooth count is the virtual
     * count z_v = z/cos δ (the spur profile that is correct for the cone), scaled by
     * cos δ so the pitch radius stays at the gear's own pitch radius. The bore and
     * structural holes stay at full size (a straight, circular bore).
     */
    private fun bevelShape(p: GearParams): PlanarShape {
        val delta = Math.toRadians(p.pitchConeDeg.coerceIn(5.0, 85.0))
        val zv = bevelVirtualTeeth(p)
        val cs = cos(delta)
        val back = GearProfiles.externalOutline(p.copy(teeth = zv))
        val outer = back.map { Vec2(it.x * cs, it.y * cs) }
        val holes = Bore.holes(p) + Bore.lighteningHoles(p) + Bore.spokeWedgeHoles(p) + Bore.indexMarkHoles(p)
        return PlanarShape(outer, holes)
    }

    private fun bevelScale(p: GearParams): Double {
        val delta = Math.toRadians(p.pitchConeDeg.coerceIn(5.0, 85.0))
        val rp = p.module * p.teeth / 2.0
        // Taper toward the cone apex: the face width reduces the radius by b·sin δ.
        return max(0.2, 1.0 - p.thickness * sin(delta) / rp)
    }

    private fun sliceCount(p: GearParams): Int = when (p.precision) {
        PrecisionLevel.HOBBY -> 16
        PrecisionLevel.STANDARD -> 32
        PrecisionLevel.HIGH -> 64
    }
}
