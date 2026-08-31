package com.gearforge.core

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Builds a compound gear (dubbelkugghjul): two coaxial involute spur stages fused
 * along a shared Z axis with an optional inter-stage spacer, cut by one continuous
 * centre bore.
 *
 * Coordinate convention: stage 1 occupies `z ∈ [0, b1]`, the spacer `z ∈ [b1, b1+h]`,
 * stage 2 `z ∈ [b1+h, b1+h+b2]`. The bore profile (Round / DIN 6885 keyway / D-cut /
 * Double D-cut) is identical through the whole stack, so the keyway/flat orientation
 * stays consistent from bottom to top.
 *
 * Watertightness strategy: each constant cross-section is extruded as side walls with
 * shared ring indices; the bottom/top caps and the inter-stage step faces are emitted
 * as flat [PlanarShape] triangulations. Coincident vertices at the stage interfaces
 * (identical bore polygon, identical spacer circle) are welded by a position-exact
 * global de-duplication, then the mesh is re-oriented outward. No boolean CSG is used,
 * so the result is a guaranteed closed 2-manifold.
 */
object CompoundGearBuilder {

    private const val SPACER_SEGMENTS = 64

    fun mesh(p0: GearParams): Mesh {
        val p = p0.coerced()
        // Stage 1 uses the primary parameter fields; stage 2 uses the stage2_* fields.
        val s1 = p.copy(gearType = GearType.SPUR, helixAngleDeg = 0.0)
        val s2 = p.copy(
            gearType = GearType.SPUR,
            module = p.stage2Module,
            teeth = p.stage2Teeth,
            thickness = p.stage2FaceWidth,
            pressureAngleDeg = p.stage2PressureAngleDeg,
            helixAngleDeg = 0.0,
            profileShift = p.stage2ProfileShift
        )
        val b1 = s1.thickness
        val b2 = s2.thickness

        // ---- spacer disc resolution: keep it strictly inside BOTH stage roots so it
        // never intersects tooth geometry, and strictly outside the bore so it stays a ring.
        val r1 = GearCalculator.rootRadiusShifted(s1.module, s1.teeth, s1.dedendumCoef, s1.profileShift)
        val r2 = GearCalculator.rootRadiusShifted(s2.module, s2.teeth, s2.dedendumCoef, s2.profileShift)
        val rBore = Bore.boreOuterRadius(p)
        val clearance = maxOf(0.6, 0.4 * maxOf(s1.module, s2.module))
        val rAuto = minOf(r1, r2) - clearance
        val rDisc = (if (p.spacerDiameter > 0.0) p.spacerDiameter / 2.0 else rAuto)
            .coerceIn(rBore + 0.2, rAuto.coerceAtLeast(rBore + 0.2))

        val identical = sameOutline(s1, s2, p.stage2PhaseDeg)
        val s2InS1 = GearCalculator.tipRadiusShifted(s2.module, s2.teeth, s2.addendumCoef, s2.profileShift) <= r1 - 0.2
        val s1InS2 = GearCalculator.tipRadiusShifted(s1.module, s1.teeth, s1.addendumCoef, s1.profileShift) <= r2 - 0.2

        // A zero-height spacer is honoured only when the stages weld cleanly (identical
        // outlines) or nest inside each other's solid disc; otherwise a minimal clearance
        // spacer is inserted so the transition always closes into a manifold.
        val h = if (p.spacerHeight > 1e-9) p.spacerHeight
                else if (identical || s2InS1 || s1InS2) 0.0
                else clearance

        val z1top = b1
        val z2bottom = b1 + h
        val z2top = z2bottom + b2

        val out1 = GearProfiles.externalOutline(s1)
        val out2 = rotateOutline(GearProfiles.externalOutline(s2), p.stage2PhaseDeg)
        val boreHoles = Bore.holes(p)
        val disc = GearBuilder.circle(rDisc, SPACER_SEGMENTS)

        val shape1 = PlanarShape(out1, boreHoles)
        val shapeSpacer = PlanarShape(disc, boreHoles)
        val shape2 = PlanarShape(out2, boreHoles)

        val verts = ArrayList<Vec3>()
        val tris = ArrayList<IntArray>()

        // ---- side walls for each constant cross-section (shared ring indices) ----
        val seg1 = addSegment(verts, tris, shape1, 0.0, z1top)
        val segSpacer = if (h > 1e-9) addSegment(verts, tris, shapeSpacer, z1top, z2bottom) else null
        val seg2 = addSegment(verts, tris, shape2, z2bottom, z2top)

        // ---- bottom cap (stage 1 at z = 0, −Z) and top cap (stage 2 at z = z2top, +Z) ----
        for (t in seg1.geom.tris2d) {
            tris.add(intArrayOf(seg1.base + t[0], seg1.base + t[2], seg1.base + t[1]))
        }
        for (t in seg2.geom.tris2d) {
            tris.add(intArrayOf(seg2.topBase + t[0], seg2.topBase + t[1], seg2.topBase + t[2]))
        }

        // ---- inter-stage step faces ----
        // NOTE: the bore is intentionally NOT a hole in these faces — at the interface
        // planes the bore boundary is already shared by the bore side wall below and the
        // bore side wall above, so adding it again would make that edge non-manifold.
        if (h > 1e-9) {
            // The spacer is a real column: stage 1's face around the disc points up,
            // stage 2's face around the disc points down.
            addFace(verts, tris, PlanarShape(out1, listOf(disc)), z1top, +1)
            addFace(verts, tris, PlanarShape(out2, listOf(disc)), z2bottom, -1)
        } else if (!identical) {
            // Flush transition: true 2D set difference using the other outline as a hole.
            when {
                s2InS1 -> addFace(verts, tris, PlanarShape(out1, listOf(out2)), z1top, +1)
                s1InS2 -> addFace(verts, tris, PlanarShape(out2, listOf(out1)), z1top, -1)
            }
        }

        // Weld coincident interface vertices and finalise orientation.
        val (dv, dt) = dedupe(verts, tris)
        return MeshOps.orientOutward(Mesh(dv, dt))
    }

    // ---- internals -------------------------------------------------------

    private class Geom(val poly: List<Vec2>, val tris2d: List<IntArray>, val boundary: List<Pair<Int, Int>>)

    private class Segment(val geom: Geom, val base: Int, val topBase: Int)

    private fun geometry(shape: PlanarShape): Geom {
        val (poly, tris2d) = Triangulate.triangulate(shape)
        return Geom(poly, tris2d, MeshBuilder.boundaryEdges(tris2d))
    }

    /** Extrudes [shape] as side walls from [zBottom] to [zTop] (no caps). */
    private fun addSegment(
        verts: ArrayList<Vec3>,
        tris: ArrayList<IntArray>,
        shape: PlanarShape,
        zBottom: Double,
        zTop: Double
    ): Segment {
        val g = geometry(shape)
        val base = verts.size
        val n = g.poly.size
        for (v in g.poly) verts.add(Vec3(v.x, v.y, zBottom))
        for (v in g.poly) verts.add(Vec3(v.x, v.y, zTop))
        for ((u, v) in g.boundary) {
            tris.add(intArrayOf(base + u, base + v, base + n + v))
            tris.add(intArrayOf(base + u, base + n + v, base + n + u))
        }
        return Segment(g, base, base + n)
    }

    /** Emits the flat triangulated [shape] at height [z]; [dir] = +1 faces +Z, −1 faces −Z. */
    private fun addFace(verts: ArrayList<Vec3>, tris: ArrayList<IntArray>, shape: PlanarShape, z: Double, dir: Int) {
        val (poly, tris2d) = Triangulate.triangulate(shape)
        val base = verts.size
        for (v in poly) verts.add(Vec3(v.x, v.y, z))
        for (t in tris2d) {
            tris.add(
                if (dir > 0) intArrayOf(base + t[0], base + t[1], base + t[2])
                else intArrayOf(base + t[0], base + t[2], base + t[1])
            )
        }
    }

    private fun rotateOutline(outline: List<Vec2>, deg: Double): List<Vec2> {
        if (deg == 0.0) return outline
        val a = Math.toRadians(deg)
        val ca = cos(a)
        val sa = sin(a)
        return outline.map { Vec2(it.x * ca - it.y * sa, it.x * sa + it.y * ca) }
    }

    private fun sameOutline(s1: GearParams, s2: GearParams, phaseDeg: Double): Boolean =
        phaseDeg == 0.0 &&
            s1.module == s2.module &&
            s1.teeth == s2.teeth &&
            s1.pressureAngleDeg == s2.pressureAngleDeg &&
            s1.profileShift == s2.profileShift &&
            s1.addendumCoef == s2.addendumCoef &&
            s1.dedendumCoef == s2.dedendumCoef &&
            s1.rootFilletCoef == s2.rootFilletCoef &&
            s1.tipChamfer == s2.tipChamfer

    /** Welds bit-identical vertices (exact `Vec3` equality) and drops degenerate triangles. */
    private fun dedupe(verts: List<Vec3>, tris: List<IntArray>): Pair<List<Vec3>, List<IntArray>> {
        val map = HashMap<Vec3, Int>(verts.size)
        val newVerts = ArrayList<Vec3>(verts.size)
        val remap = IntArray(verts.size)
        for (i in verts.indices) {
            val v = verts[i]
            val existing = map[v]
            if (existing != null) {
                remap[i] = existing
            } else {
                remap[i] = newVerts.size
                map[v] = newVerts.size
                newVerts.add(v)
            }
        }
        val newTris = ArrayList<IntArray>(tris.size)
        for (t in tris) {
            val a = remap[t[0]]
            val b = remap[t[1]]
            val c = remap[t[2]]
            if (a != b && b != c && a != c) newTris.add(intArrayOf(a, b, c))
        }
        return Pair(newVerts, newTris)
    }
}
