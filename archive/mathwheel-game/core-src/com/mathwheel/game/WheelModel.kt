package com.mathwheel.game

import kotlin.math.abs
import kotlin.math.min

enum class WheelPhase { SPINNING, BRAKING, SNAPPING, RESOLVED }

/**
 * Model of the lucky wheel. Rotates at constant speed, then on brake() it
 * decelerates smoothly and "snaps" to the nearest segment to the pointer.
 */
class WheelModel(var segmentCount: Int = 6) {
    var rotationDeg = 0f
    var angularVelocity = 0f
    var phase = WheelPhase.RESOLVED
        private set

    private var snapStart = 0f
    private var snapTarget = 0f
    private var snapT = 0f

    val segmentAngle: Float get() = 360f / segmentCount

    fun startSpin(velocity: Float) {
        angularVelocity = velocity
        phase = WheelPhase.SPINNING
    }

    fun brake() {
        if (phase == WheelPhase.SPINNING) phase = WheelPhase.BRAKING
    }

    fun update(dt: Float) {
        when (phase) {
            WheelPhase.SPINNING -> rotationDeg += angularVelocity * dt
            WheelPhase.BRAKING -> {
                angularVelocity -= DECEL * dt
                rotationDeg += angularVelocity * dt
                if (angularVelocity <= SNAP_THRESHOLD) beginSnap()
            }
            WheelPhase.SNAPPING -> {
                snapT = min(1f, snapT + dt / SNAP_DURATION)
                val t = smoothstep(snapT)
                rotationDeg = snapStart + (snapTarget - snapStart) * t
                if (snapT >= 1f) phase = WheelPhase.RESOLVED
            }
            WheelPhase.RESOLVED -> Unit
        }
        rotationDeg = ((rotationDeg % 360f) + 360f) % 360f
    }

    /** Segment currently under the pointer (top of the wheel). */
    fun selectedIndex(): Int {
        val local = ((POINTER_DEG - rotationDeg) % 360f + 360f) % 360f
        return (local / segmentAngle).toInt().coerceIn(0, segmentCount - 1)
    }

    private fun beginSnap() {
        val seg = segmentAngle
        val localPointer = ((POINTER_DEG - rotationDeg) % 360f + 360f) % 360f
        val base = (localPointer / seg).toInt()
        var best = base
        var bestDist = Float.MAX_VALUE
        for (k in -1..1) {
            val cand = ((base + k) % segmentCount + segmentCount) % segmentCount
            val centerLocal = cand * seg + seg / 2f
            var d = centerLocal - localPointer
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            if (abs(d) < bestDist) {
                bestDist = abs(d)
                best = cand
            }
        }
        val centerWorld = rotationDeg + best * seg + seg / 2f
        var delta = (POINTER_DEG - centerWorld) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        snapStart = rotationDeg
        snapTarget = rotationDeg + delta
        snapT = 0f
        phase = WheelPhase.SNAPPING
    }

    private fun smoothstep(x: Float): Float = x * x * (3f - 2f * x)

    companion object {
        const val POINTER_DEG = 90f
        const val DECEL = 210f
        const val SNAP_THRESHOLD = 24f
        const val SNAP_DURATION = 0.30f
    }
}
