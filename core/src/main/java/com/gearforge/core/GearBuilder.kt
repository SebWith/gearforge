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

    fun shape(p: GearParams): PlanarShape = when (p.gearType) {
        GearType.RACK -> PlanarShape(GearProfiles.rackOutline(p), emptyList())
        GearType.BELT -> BeltBuilder.beltPath2D(p.toBeltTransmission())
        else -> {
            // Spokes and lightening holes both occupy the annulus between the bore
            // and the rim; when both are enabled the circular holes can intersect the
            // spoke wedges and break the hole triangulation. Spokes win (they define
            // the structural web), lightening holes are dropped in that case (audit L3).
            val spokes = Bore.spokeWedgeHoles(p)
            val lightening = if (spokes.isNotEmpty()) emptyList() else Bore.lighteningHoles(p)
            PlanarShape(
                GearProfiles.externalOutline(p),
                Bore.holes(p) + lightening + spokes + Bore.indexMarkHoles(p)
            )
        }
    }

    fun mesh(p: GearParams): Mesh {
        // Defensive clamp so out-of-range parameters can never produce NaN/degenerate
        // geometry at the engine entry points (audit H1).
        val p = p.coerced()
        return when (p.gearType) {
        GearType.RACK -> MeshBuilder.extrude(PlanarShape(GearProfiles.rackOutline(p)), p.thickness)
        GearType.HELICAL -> Loft.loft(
            shape(helicalParams(p)), p.thickness,
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
            shape(helicalParams(p)), p.thickness,
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

    /** Worm pitch radius used to seat the worm tangent to the wheel and to size the throat arc. */
    private fun wormPitchRadius(p: GearParams): Double =
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

    fun ringMesh(p: GearParams): Mesh {
        val p = p.coerced()
        val rp = p.module * p.teeth / 2.0
        val rRoot = rp + 1.25 * p.module
        val rIn = rp - p.module
        require(rIn < rRoot) { "Ring inner radius ($rIn) must be < root radius ($rRoot)" }
        // A solid rim beyond the tooth root, with the toothed inner boundary as a
        // hole. Proper triangulation (hole bridging + vertex dedupe) replaces the
        // previous manual radial pairing that produced zero rim and skewed walls
        // (audit M4).
        val rim = max(2.0, 2.0 * p.module)
        val outer = circle(rRoot + rim, 96)
        val toothHole = GearProfiles.internalRingOutline(p)
        return MeshBuilder.extrude(PlanarShape(outer, listOf(toothHole)), p.thickness)
    }

    fun planetary(p: GearParams): PlanetaryAssembly {
        val p = p.coerced()
        val m = p.module
        val sunTeeth = max(5, p.teeth)
        val planetTeeth = max(8, p.planetTeeth)
        // Zr = Zs + 2·Zp is a hard meshing constraint; the ring must have exactly this
        // many teeth. A mismatched user value is overridden (with a validate() warning).
        val ringTeeth = sunTeeth + 2 * planetTeeth
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
     */
    fun toothHighlightMesh(p: GearParams, toothIndex: Int): Mesh {
        val n = p.teeth
        val idx = ((toothIndex % n) + n) % n
        val rTip = GearCalculator.outerRadius(p.module, n) + 0.5
        val rRoot = GearCalculator.rootRadius(p.module, n).coerceAtLeast(0.4) - 0.4
        val center = 2.0 * PI * idx / n
        val half = PI / n * 0.72
        val a1 = center - half
        val a2 = center + half
        val poly = listOf(
            Vec2.polar(rRoot, a1),
            Vec2.polar(rTip, a1),
            Vec2.polar(rTip, a2),
            Vec2.polar(rRoot, a2)
        )
        val t = p.thickness + 0.6
        val m = MeshBuilder.extrude(PlanarShape(poly, emptyList()), t)
        return Mesh(m.vertices.map { Vec3(it.x, it.y, it.z - 0.3) }, m.triangles)
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
     * Bevel/hypoid tooth profile on the back cone: the tooth count is the virtual
     * count z_v = z/cos δ (the spur profile that is correct for the cone), scaled by
     * cos δ so the pitch radius stays at the gear's own pitch radius. The bore and
     * structural holes stay at full size (a straight, circular bore).
     */
    private fun bevelShape(p: GearParams): PlanarShape {
        val delta = Math.toRadians(p.pitchConeDeg.coerceIn(5.0, 85.0))
        val zv = max(5, (p.teeth / cos(delta)).roundToInt())
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
