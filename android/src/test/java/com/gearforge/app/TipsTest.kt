package com.gearforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walkthrough's contract: an ordered sequence with no gaps, a monotonic cursor that clamps,
 * and a rule for which screen shows which tip.
 *
 * These are the properties the UI relies on and cannot check for itself. [Tips.editorTip] returning
 * null for the wizard's step, for instance, is what keeps the wizard's hint from reappearing over
 * the finished model — a bug that would look like "the tip came back" and would be blamed on the
 * persistence.
 */
class TipsTest {

    @Test
    fun `steps are contiguous starting at zero`() {
        val steps = listOf(Tips.WIZARD_TYPE, Tips.EDITOR_ORBIT, Tips.EDITOR_EXPORT, Tips.EDITOR_TOOTH)
        assertEquals((0 until Tips.DONE).toList(), steps)
        assertEquals(Tips.WIZARD_TYPE, Tips.FIRST)
    }

    @Test
    fun `next advances one step and stops at done`() {
        assertEquals(Tips.EDITOR_ORBIT, Tips.next(Tips.WIZARD_TYPE))
        assertEquals(Tips.EDITOR_EXPORT, Tips.next(Tips.EDITOR_ORBIT))
        assertEquals(Tips.EDITOR_TOOTH, Tips.next(Tips.EDITOR_EXPORT))
        assertEquals(Tips.DONE, Tips.next(Tips.EDITOR_TOOTH))
        // Clamped: a double-tap on "Got it" must not walk the cursor past the end into a state that
        // no screen can render.
        assertEquals(Tips.DONE, Tips.next(Tips.DONE))
        assertEquals(Tips.DONE, Tips.next(Tips.DONE + 7))
    }

    @Test
    fun `walking from first reaches done without looping`() {
        var step = Tips.FIRST
        val visited = mutableListOf(step)
        repeat(Tips.DONE + 3) {
            val next = Tips.next(step)
            if (next == step) return@repeat
            step = next
            visited += step
        }
        assertEquals(Tips.DONE, step)
        assertEquals(visited.distinct(), visited)
        assertEquals((0..Tips.DONE).toList(), visited)
    }

    @Test
    fun `only the first wizard step carries a tip`() {
        assertEquals(Tips.WIZARD_TYPE, Tips.wizardTip(Tips.WIZARD_TYPE))
        assertNull(Tips.wizardTip(Tips.EDITOR_ORBIT))
        assertNull(Tips.wizardTip(Tips.DONE))
    }

    @Test
    fun `the editor shows its own tips and never the wizard's`() {
        assertNull(Tips.editorTip(Tips.WIZARD_TYPE))
        assertEquals(Tips.EDITOR_ORBIT, Tips.editorTip(Tips.EDITOR_ORBIT))
        assertEquals(Tips.EDITOR_EXPORT, Tips.editorTip(Tips.EDITOR_EXPORT))
        assertEquals(Tips.EDITOR_TOOTH, Tips.editorTip(Tips.EDITOR_TOOTH))
        assertNull(Tips.editorTip(Tips.DONE))
        assertNull(Tips.editorTip(Tips.DONE + 1))
    }

    @Test
    fun `an unknown negative step shows nothing rather than crashing`() {
        // SettingsStore coerces on write, but a corrupted or hand-edited preference file reaches
        // here as-is. Both accessors are total, so no screen has to defend itself.
        assertNull(Tips.wizardTip(-1))
        assertNull(Tips.editorTip(-1))
        assertFalse(Tips.done(-1))
    }

    @Test
    fun `done is the boundary`() {
        assertFalse(Tips.done(Tips.WIZARD_TYPE))
        assertFalse(Tips.done(Tips.EDITOR_TOOTH))
        assertTrue(Tips.done(Tips.DONE))
        assertTrue(Tips.done(Tips.DONE + 1))
    }
}
