package com.gearforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wizard's back behaviour.
 *
 * It exists because the visible Back button and the system back gesture used to disagree: the button
 * walked one step back, the gesture left the wizard from any step. That is a data-loss bug (the
 * chosen type and every entered parameter go with the wizard), and enabling predictive back on
 * Android 13+ turns it into something the user watches happening before it commits.
 */
class WizardFlowTest {

    @Test
    fun backWalksOneStepAtATime() {
        assertEquals(WizardFlow.PRESET, WizardFlow.backFrom(WizardFlow.CUSTOM))
        assertEquals(WizardFlow.TYPE, WizardFlow.backFrom(WizardFlow.PRESET))
    }

    @Test
    fun backFromTheFirstStepLeavesTheWizard() {
        // Not "step -1": the caller turns null into a stage change. Encoding that as a step number
        // would invent a step that does not exist and that no forward transition could reach.
        assertNull(WizardFlow.backFrom(WizardFlow.TYPE))
    }

    @Test
    fun backAlwaysTerminatesAndNeverLoops() {
        // Every step must reach the exit in a finite number of presses, whatever the caller does
        // with the steps it is given — the property that makes "back" safe to press repeatedly.
        for (start in listOf(WizardFlow.TYPE, WizardFlow.PRESET, WizardFlow.CUSTOM)) {
            var step: Int? = start
            var presses = 0
            while (step != null) {
                step = WizardFlow.backFrom(step)
                presses++
                assertTrue("back from $start must terminate", presses <= 8)
            }
            assertEquals("back from $start must take one press per step", start + 1, presses)
        }
    }

    @Test
    fun theStepsAreContiguousSoForwardAndBackAgree() {
        // A gap would make some step unreachable going forward while still reachable going back.
        assertEquals(listOf(0, 1, 2), listOf(WizardFlow.TYPE, WizardFlow.PRESET, WizardFlow.CUSTOM).sorted())
    }
}
