package com.gearforge.app

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the viewport's own panels are allowed to start.
 *
 * The assembly legend and the measurement HUD's block of unanchored measurements both belong in the
 * viewport's top-start corner, and each of them was picking that corner on its own — so on every
 * multi-body type they were drawn at the same 12 dp inset, one translucent panel over the other, and
 * the body names and the mass row were mutually unreadable. Neither composable can see the other, so
 * the height one of them occupies is arithmetic, and arithmetic that decides whether a number can be
 * read belongs in a test.
 */
class ViewportChromeTest {

    @Test
    fun aSingleRowIsTheAbsenceOfALegendRatherThanAShortOne() {
        // The legend is shown from two bodies upwards; asking for one row must not reserve space the
        // viewport never gives away.
        assertEquals(0.dp, legendHeightDp(0))
        assertEquals(0.dp, legendHeightDp(1))
        assertEquals(0.dp, legendBottomDp(1))
    }

    @Test
    fun theLegendIsAsTallAsItsRowsAndItsOwnPadding() {
        val row = 48.dp
        assertEquals(row * 2 + 4.dp, legendHeightDp(2, row))
        assertEquals(row * 3 + 4.dp, legendHeightDp(3, row))
        // A full planetary ring train: sun, ring and six planets.
        assertEquals(row * 8 + 4.dp, legendHeightDp(8, row))
    }

    @Test
    fun theHudStartsBelowTheLegendAndNeverInsideIt() {
        for (rows in 2..8) {
            val legendBottom = legendBottomDp(rows)
            val hudTop = hudCornerTopDp(rows)
            assertTrue(
                "$rows legend rows end at $legendBottom but the HUD starts at $hudTop",
                hudTop >= legendBottom
            )
        }
    }

    @Test
    fun theHudKeepsTheStandardInsetWhenNothingIsAboveIt() {
        assertEquals(VIEWPORT_INSET, hudCornerTopDp(0))
        assertEquals(VIEWPORT_INSET, hudCornerTopDp(1))
    }

    /** The default row height has to be the legend's own, because a row is a tap target. */
    @Test
    fun theDefaultRowHeightIsTheLegendsRowHeight() {
        assertEquals(legendHeightDp(3, MinTouchTarget), legendHeightDp(3))
        assertEquals(MinTouchTarget, 48.dp)
    }

    /**
     * The HUD keeps its labels out of the gizmo's rectangle, so that rectangle needs one definition.
     *
     * `ViewportGizmo` lays itself out with [GIZMO_WIDGET_SIZE] and [GIZMO_INSET] and reserves
     * [gizmoBottomDp] at the top-trailing corner; a label whose pill reaches into that column starts
     * below it instead of being drawn across the pucks.
     */
    @Test
    fun theGizmoReservesItsOwnCorner() {
        assertEquals(72.dp, GIZMO_WIDGET_SIZE)
        assertEquals(12.dp, GIZMO_INSET)
        assertEquals(GIZMO_INSET + GIZMO_WIDGET_SIZE, gizmoBottomDp())
        assertTrue(
            "the gizmo's corner must sit below the viewport's own inset",
            gizmoBottomDp() > VIEWPORT_INSET
        )
    }
}
