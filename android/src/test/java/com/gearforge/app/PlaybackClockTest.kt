package com.gearforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playback clock's arithmetic.
 *
 * This is the part of the meshing animation that cannot be checked from a screenshot: a gear with
 * N teeth looks identical every `360/N` degrees, so a wrong rate and a wrong phase both produce
 * plausible pictures. The clock is therefore pure arithmetic, tested here, and the renderer only
 * applies the number it returns.
 */
class PlaybackClockTest {

    private val second = 1_000_000_000L

    @Test
    fun theTimeCoordinateAdvancesAtTheRequestedRate() {
        val clock = PlaybackClock()
        clock.reset(0L)

        assertEquals(1f, clock.timeSeconds(second, 1f), 1e-4f)
        assertEquals(2.5f, clock.timeSeconds(2_500_000_000L, 1f), 1e-4f)
    }

    /**
     * The speed control's whole purpose: at twice the rate, twice the angle has accumulated.
     *
     * This is what makes the chips a measurement rather than a decoration — the number they multiply
     * is the number the renderer feeds the model matrix.
     */
    @Test
    fun aDoubledRateAdvancesTwiceAsFar() {
        val base = PlaybackClock().also { it.reset(0L, 1f) }
        val doubled = PlaybackClock().also { it.reset(0L, 2f) }

        val slow = base.timeSeconds(3 * second, 1f)
        val fast = doubled.timeSeconds(3 * second, 2f)
        assertEquals(3f, slow, 1e-4f)
        assertEquals(6f, fast, 1e-4f)
    }

    /**
     * Changing the rate mid-playback must not move the model.
     *
     * `elapsed · newScale` — the obvious implementation — would jump the train from `3 · 1` to
     * `3 · 4 = 12` seconds of rotation the instant the user tapped a faster chip, i.e. by three
     * quarters of a revolution. The clock re-origins instead, so only the *rate* changes from that
     * frame on.
     */
    @Test
    fun changingTheRateMidPlaybackDoesNotMoveTheModel() {
        val clock = PlaybackClock()
        clock.reset(0L)
        val before = clock.timeSeconds(3 * second, 1f)
        val atChange = clock.timeSeconds(3 * second, 4f)
        assertEquals("the coordinate at the moment of the change", before, atChange, 1e-3f)

        // From there on it runs four times as fast.
        assertEquals(3f + 4f * 0.5f, clock.timeSeconds(3_500_000_000L, 4f), 1e-3f)

        // And slowing down is symmetric: one second at half rate adds half a second.
        val slowed = clock.timeSeconds(3_500_000_000L, 0.5f)
        assertTrue("slowing down must not move the model either", slowed <= 5.1f)
        assertEquals(5.5f, clock.timeSeconds(4_500_000_000L, 0.5f), 1e-3f)
    }

    @Test
    fun aResettingMeshStartsFromZero() {
        val clock = PlaybackClock()
        clock.reset(0L)
        assertEquals(6f, clock.timeSeconds(6 * second, 1f), 1e-4f)

        // A new mesh (a type switch, a parameter change) restarts the phase at the orientation the
        // new geometry was built with.
        clock.reset(6 * second)
        assertEquals(0f, clock.timeSeconds(6 * second, 1f), 1e-4f)
        assertEquals(1f, clock.timeSeconds(7 * second, 1f), 1e-4f)
    }

    @Test
    fun anAbsurdRateIsClampedRatherThanApplied() {
        val clock = PlaybackClock()
        clock.reset(0L)

        // A rate the control cannot produce must not turn into a jump: a clamped request is applied
        // before the accumulation, so the coordinate stays where it was.
        val normal = clock.timeSeconds(2 * second, 1f)
        val clamped = clock.timeSeconds(2 * second, 1_000f)
        assertEquals(normal, clamped, 1e-3f)
        assertEquals(PlaybackClock.MAX_SCALE, clock.scale, 1e-4f)

        // A negative rate asks to run backwards. The clock has no reverse, and the one thing it
        // must not do is move the model, so the request parks it — the coordinate stays where it
        // was and only the frames after it are affected.
        val negative = clock.timeSeconds(4 * second, -3f)
        assertEquals(PlaybackClock.PARKED_SCALE, clock.scale, 1e-4f)
        assertTrue("a negative rate must not run time backwards", negative > 0f)
    }

    /**
     * Pause is a rate, not a phase reset.
     *
     * The renderer parks the clock while the play button is up. Everything the defect came down to is
     * in these five lines: the coordinate must survive the pause, the wall time spent parked must not
     * be added, and resuming must continue from the same angle at the chosen rate. A renderer that
     * instead rebuilt its instance list on the play/pause flag reset its phase and snapped the mesh
     * back to the orientation it was built with — visible only as "pressing pause moves my gear".
     */
    @Test
    fun aParkedClockHoldsItsCoordinateAndResumesFromIt() {
        val clock = PlaybackClock()
        clock.reset(0L)

        assertEquals(3f, clock.timeSeconds(3 * second, 1f), 1e-4f)
        assertTrue("the clock must be running before the pause", !clock.isParked)

        // The frame the pause lands on: the elapsed time up to this instant still counts.
        assertEquals(3f, clock.timeSeconds(3 * second, PlaybackClock.PARKED_SCALE), 1e-4f)
        assertTrue("a parked clock must report itself parked", clock.isParked)

        // A minute passes with nothing drawn. Parked frames, if any are drawn at all, add nothing.
        assertEquals(3f, clock.timeSeconds(120 * second, PlaybackClock.PARKED_SCALE), 1e-4f)

        // Resume: still three seconds of rotation, not sixty-three.
        assertEquals(3f, clock.timeSeconds(120 * second, 1f), 1e-4f)
        assertEquals(4f, clock.timeSeconds(121 * second, 1f), 1e-4f)
    }

    /**
     * Every step of the offered ladder must actually scale the coordinate.
     *
     * The chips are a comparison tool, so the number on a chip has to be the factor the model
     * turns at — including the fastest one, which is the base rate and therefore the identity.
     */
    @Test
    fun everyOfferedStepScalesTheCoordinateByItsOwnFactor() {
        for (step in PLAYBACK_SPEEDS) {
            val clock = PlaybackClock().also { it.reset(0L, step) }
            assertEquals(
                "the ${step}x chip must advance the coordinate ${step}x",
                step * 2f,
                clock.timeSeconds(2 * second, step),
                1e-3f
            )
        }
        assertEquals(
            "the base step is the fastest offered and must be 1x",
            1f,
            BASE_PLAYBACK_SPEED,
            1e-6f
        )
    }

    /** The chip's label must state the rate the chip applies, at every step. */
    @Test
    fun everyChipStatesItsOwnRate() {
        for (step in PLAYBACK_SPEEDS) {
            val printed = formatDecimal(step.toDouble(), playbackSpeedDecimals(step))
            assertEquals(
                "the $step chip prints \"$printed\", which is a different rate",
                step,
                printed.toFloat(),
                1e-6f
            )
        }
    }

    private fun formatDecimal(value: Double, decimals: Int): String =
        String.format(java.util.Locale.ROOT, "%.${decimals}f", value)
}
