package com.gearforge.core

import kotlin.math.abs

/**
 * Mesh-based engineering analysis: mass, moment of inertia, bounding box and an
 * estimated tooth-root bending stress. Used by the results panel and export
 * documentation paths.
 */
object GearAnalysis {

    data class Result(
        val volumeMm3: Double,
        val weightKg: Double,
        val momentOfInertia: Double,   // kg·m² about the Z axis
        val bounds: Vec3,
        val vertexCount: Int,
        val triangleCount: Int,
        val toothStressMpa: Double
    )

    fun analyze(mesh: Mesh, p: GearParams): Result {
        val vol = abs(MeshOps.signedVolume(mesh))
        val density = GearCalculator.densityKgM3(p.material)
        val weight = vol / 1e9 * density
        // Inertia about Z = Σ mᵢ·(xᵢ² + yᵢ²); approximate from vertex positions.
        val n = mesh.vertices.size
        var sumR2 = 0.0
        for (v in mesh.vertices) sumR2 += v.x * v.x + v.y * v.y
        val inertia = if (n > 0) weight / n * sumR2 / 1e6 else 0.0
        return Result(
            volumeMm3 = vol,
            weightKg = weight,
            momentOfInertia = inertia,
            bounds = MeshOps.bounds(mesh),
            vertexCount = n,
            triangleCount = mesh.triangles.size,
            toothStressMpa = estimateToothStress(p)
        )
    }

    /**
     * Lewis/AGMA-style tooth-root bending stress estimate (MPa).
     * Tangential force = torque / pitch radius; stress = F·SF / (face width · module · Y).
     */
    fun estimateToothStress(p: GearParams): Double {
        val moduleM = p.module / 1000.0
        val faceM = p.thickness / 1000.0
        if (moduleM <= 0.0 || faceM <= 0.0 || p.teeth <= 0) return 0.0
        val pitchRadiusM = GearCalculator.pitchRadius(p.module, p.teeth) / 1000.0
        val tangentialForce = if (pitchRadiusM > 0.0) p.loadNm / pitchRadiusM else 0.0
        val y = 0.30 // Lewis form factor (approximate)
        return tangentialForce * p.safetyFactor / (faceM * moduleM * y) / 1e6
    }
}
