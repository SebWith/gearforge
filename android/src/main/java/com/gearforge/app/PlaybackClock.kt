package com.gearforge.app

/**
 * The playback clock: turns wall-clock nanoseconds into the animation's time coordinate.
 *
 * Extracted from the renderer because the interesting part is arithmetic, not GL. Three properties
 * have to hold at once, and none of them is visible in a screenshot (a symmetric gear looks
 * identical at many angles):
 *
 *  - The rate can change *while* the train is moving, and the obvious implementation —
 *    `elapsed · scale` — snaps the model back to its starting orientation at the exact moment the
 *    user reaches for the speed control, because the whole elapsed time is suddenly measured at the
 *    new rate.
 *  - Pressing pause must not move the model either. Pausing is a property of the *rate*, so the
 *    coordinate it produces must not depend on whether the play button is down: a renderer that
 *    resets its phase whenever the button changes turns "pause" into "jump back to the start".
 *  - The wall time that passes while paused is not animation time. If the reference stamp were not
 *    advanced while parked, a train paused for a minute would resume a minute's worth of rotation
 *    further on.
 *
 * The clock is deliberately not thread-safe. The renderer owns it on the GL thread and crosses the
 * thread boundary with the *requested* rate only.
 */
internal class PlaybackClock {

    /** Animation time accumulated so far, in model seconds (already multiplied by the rate). */
    private var accumulatedSeconds = 0.0

    /** Wall-clock stamp of the last frame that asked for a coordinate. */
    private var lastNanos = 0L

    private var appliedScale = 1f

    /**
     * Restarts the clock at zero, running at [scale].
     *
     * Called when a new mesh is uploaded: a new model starts its rotation from the orientation the
     * geometry was built with, not from wherever the previous one happened to be. Deliberately *not*
     * called when playback starts or stops — see the class comment. The scale is taken here rather
     * than on the first [timeSeconds] call, so a user who chose a speed before pressing play gets
     * that speed from the first frame instead of one frame at the base rate.
     */
    fun reset(nowNanos: Long, scale: Float = 1f) {
        accumulatedSeconds = 0.0
        lastNanos = nowNanos
        appliedScale = safeScale(scale)
    }

    /** The rate the clock is currently running at; [PARKED_SCALE] when it is parked. */
    val scale: Float get() = appliedScale

    /** True when the clock is parked: [timeSeconds] returns the same coordinate on every call. */
    val isParked: Boolean get() = appliedScale == PARKED_SCALE

    /**
     * The animation's time coordinate in seconds for the frame at [nowNanos], running at [scale].
     *
     * The coordinate is accumulated — `coordinate += Δt · rate` — rather than recomputed from the
     * start. That is what makes a rate change a *rate* change: the frame that ends at [nowNanos] ran
     * at the previous rate, so the requested one governs the frames after it and nothing that has
     * already been drawn moves.
     *
     * A parked clock ([PARKED_SCALE]) accumulates nothing and returns what it holds, so the model
     * freezes where it is and resumes from there. A clamped scale (see [MIN_SCALE] / [MAX_SCALE]) is
     * applied before the accumulation, so a nonsensical request cannot move the model either.
     */
    fun timeSeconds(nowNanos: Long, scale: Float): Float {
        if (appliedScale != PARKED_SCALE) {
            val delta = nowNanos - lastNanos
            if (delta > 0L) accumulatedSeconds += delta / 1e9 * appliedScale
        }
        // Advanced even while parked (and while no frame is drawn at all), which is what discards
        // the wall time between the pause and the resume.
        lastNanos = nowNanos
        appliedScale = safeScale(scale)
        return accumulatedSeconds.toFloat()
    }

    private fun safeScale(scale: Float): Float =
        if (scale <= PARKED_SCALE) PARKED_SCALE else scale.coerceIn(MIN_SCALE, MAX_SCALE)

    companion object {
        /** Slowest *running* rate the control can ask for. */
        const val MIN_SCALE = 0.05f

        /** Fastest rate the control can ask for. */
        const val MAX_SCALE = 20f

        /**
         * The rate that parks the clock: the animation holds its coordinate.
         *
         * Zero is not "infinitely slow" as a special case of the rate ladder — it is the one request
         * that means *stop advancing*, and the renderer sends it while the play button is up.
         */
        const val PARKED_SCALE = 0f
    }
}
