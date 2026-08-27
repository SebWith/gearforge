package com.gearforge.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
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
        else -> PlanarShape(
            GearProfiles.externalOutline(p),
            Bore.holes(p) + Bore.lighteningHoles(p) + Bore.spokeWedgeHoles(p) + Bore.indexMarkHoles(p)
        )
    }

    fun mesh(p: GearParams): Mesh = when (p.gearType) {
        GearType.RACK -> MeshBuilder.extrude(PlanarShape(GearProfiles.rackOutline(p)), p.thickness)
        GearType.HELICAL -> Loft.loft(
            shape(p), p.thickness,
            twistRad = helicalTwist(p),
            scaleStart = 1.0, scaleEnd = 1.0, slices = sliceCount(p)
        )
        GearType.BEVEL, GearType.HYPOID -> Loft.loft(
            shape(p), p.thickness,
            twistRad = 0.0,
            scaleStart = 1.0, scaleEnd = bevelScale(p), slices = sliceCount(p)
        )
        GearType.INTERNAL_RING -> ringMesh(p)
        GearType.WORM_PAIR -> wheelMesh(p)
        GearType.SCREW_GEAR -> Loft.loft(
            shape(p), p.thickness,
            twistRad = helicalTwist(p),   // audit C7: honour the user's helix_angle
            scaleStart = 1.0, scaleEnd = 1.0, slices = sliceCount(p)
        )
        GearType.BELT -> merged(p)
        else -> MeshBuilder.extrude(shape(p), p.thickness)
    }

    /**
     * Builds the full assembly (one or more meshes plus placement offsets) for a
     * parameter set. Single gears return one mesh; rack/worm/planetary return the
     * relevant multiple meshes positioned relative to each other.
     */
    fun assembly(p: GearParams): GearAssembly = when (p.gearType) {
        GearType.RACK -> {
            val rack = mesh(p)
            val pinion = mesh(p.copy(gearType = GearType.SPUR, teeth = p.pinionTeeth))
            val pr = GearCalculator.pitchRadius(p.module, p.pinionTeeth)
            // Rack teeth point +y; the pinion meshes above the rack pitch line. Center on
            // the actual whole-tooth bar length rather than the raw rack_length field (C1).
            val rackLen = GearProfiles.rackTeeth(p) * Math.PI * p.module
            GearAssembly(listOf(rack, pinion), listOf(Vec2(-rackLen / 2.0, 0.0), Vec2(0.0, pr)))
        }
        GearType.PLANETARY -> {
            val a = planetary(p)
            GearAssembly(
                listOf(a.sun, a.ring) + a.planets,
                listOf(Vec2(0.0, 0.0), Vec2(0.0, 0.0)) + a.planetCenters
            )
        }
        GearType.WORM_PAIR -> {
            val worm = wormMesh(p)
            val wheel = wheelMesh(p)
            val dist = GearCalculator.pitchRadius(p.module, p.wheelTeeth) + p.module
            GearAssembly(listOf(worm, wheel), listOf(Vec2(0.0, dist), Vec2(0.0, 0.0)))
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

    private fun wormMesh(p: GearParams): Mesh {
        val teeth = max(4, p.wormStarts * 4)
        // WORM_PAIR has no bore control; the worm is a solid blank (audit C8).
        val worm = p.copy(
            gearType = GearType.SPUR, teeth = teeth,
            thickness = max(8.0, p.module * 8.0), bore = BoreSpec(type = BoreType.NONE)
        )
        return Loft.loft(shape(worm), worm.thickness, twistRad = helicalTwist(worm), scaleStart = 1.0, scaleEnd = 1.0, slices = sliceCount(worm))
    }

    private fun wheelMesh(p: GearParams): Mesh {
        // WORM_PAIR has no bore control; the wheel is a solid blank (audit C8).
        val wheel = p.copy(
            gearType = GearType.SPUR, teeth = max(10, p.wheelTeeth), bore = BoreSpec(type = BoreType.NONE)
        )
        return MeshBuilder.extrude(shape(wheel), p.thickness)
    }

    fun ringMesh(p: GearParams): Mesh {
        val rp = p.module * p.teeth / 2.0
        val rOut = rp + 1.25 * p.module
        val rIn = rp - p.module
        require(rIn < rOut) { "Ring inner radius ($rIn) must be < outer radius ($rOut)" }
        val inner = GearProfiles.internalRingOutline(p)
        val n = inner.size
        val outer = circle(rOut, n)
        val poly = ArrayList<Vec2>(2 * n)
        poly.addAll(outer)
        poly.addAll(inner)
        val tris = ArrayList<IntArray>(2 * n)
        for (i in 0 until n) {
            val j = (i + 1) % n
            tris.add(intArrayOf(i, j, n + j))
            tris.add(intArrayOf(i, n + j, n + i))
        }
        return MeshBuilder.extrude(poly, tris, p.thickness)
    }

    fun planetary(p: GearParams): PlanetaryAssembly {
        val m = p.module
        val sunTeeth = max(3, p.teeth)
        val planetTeeth = max(8, p.planetTeeth)
        val ringTeeth = max(sunTeeth + 2 * planetTeeth, p.ringTeeth)
        val planetCount = p.planetCount.coerceIn(2, 6)

        val sun = mesh(p.copy(gearType = GearType.SPUR, teeth = sunTeeth))
        val planetParams = p.copy(
            gearType = GearType.SPUR,
            teeth = planetTeeth,
            bore = p.bore.copy(diameter = max(2.0, p.module * 2.0))
        )
        val planet = mesh(planetParams)
        val ring = ringMesh(p.copy(teeth = ringTeeth))

        val planetDist = GearCalculator.centerDistance(m, sunTeeth, planetTeeth)
        val centers = (0 until planetCount).map { i ->
            val a = 2.0 * PI * i / planetCount
            Vec2(planetDist * cos(a), planetDist * sin(a))
        }
        val ratio = GearCalculator.planetaryRatioFixedRing(sunTeeth, ringTeeth)

        return PlanetaryAssembly(sun, ring, List(planetCount) { planet }, centers, sunTeeth, planetTeeth, ringTeeth, ratio)
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

    private fun helicalTwist(p: GearParams): Double {
        if (p.helixAngleDeg == 0.0) return 0.0
        val rp = p.module * p.teeth / 2.0
        return p.thickness * tan(Math.toRadians(p.helixAngleDeg)) / rp
    }

    private fun bevelScale(p: GearParams): Double {
        val rp = p.module * p.teeth / 2.0
        return max(0.2, 1.0 - p.thickness / (4.0 * rp))
    }

    private fun sliceCount(p: GearParams): Int = when (p.precision) {
        PrecisionLevel.HOBBY -> 16
        PrecisionLevel.STANDARD -> 32
        PrecisionLevel.HIGH -> 64
    }
}
