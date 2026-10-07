package com.gearforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The names, states and actions a screen reader announces.
 *
 * A missing key does not fail the build: `I18n.t` falls back to English and then to the key itself, so
 * a Swedish TalkBack user would hear "view_top". These tests pin every key the accessibility layer
 * builds at runtime, in both languages, and the arithmetic behind the gesture-free camera actions.
 */
class A11yLabelsTest {

    private val langs = I18n.Lang.entries

    private fun assertTranslated(key: String, placeholder: Boolean = false) {
        val en = I18n.t(I18n.Lang.EN, key)
        val sv = I18n.t(I18n.Lang.SV, key)
        assertNotEquals("$key is missing in English", key, en)
        assertNotEquals("$key is missing in Swedish (it fell back to English)", en, sv)
        if (placeholder) {
            for (lang in langs) assertTrue("$key lacks {0} in $lang", I18n.t(lang, key).contains("{0}"))
        }
    }

    @Test
    fun everyGizmoViewHasADistinctSpokenNameInBothLanguages() {
        val keys = GizmoView.entries.map { it.labelKey() }
        assertEquals(GizmoView.entries.size, keys.toSet().size)
        keys.forEach { assertTranslated(it) }
        assertTranslated("gizmo_desc")
        assertTranslated("gizmo_show", placeholder = true)
    }

    @Test
    fun everyCameraStepHasASpokenNameInBothLanguages() {
        CameraStep.entries.forEach { assertTranslated(it.labelKey) }
        assertTranslated("viewport_desc", placeholder = true)
        assertNotEquals("reset_view", I18n.t(I18n.Lang.SV, "reset_view"))
    }

    @Test
    fun cameraStepsMoveOneAxisByAFixedAmount() {
        for (step in CameraStep.entries) {
            val moves = listOf(step.orbitDxPx != 0f, step.orbitDyPx != 0f, step.zoom != 1f).count { it }
            assertEquals("$step must change exactly one thing", 1, moves)
        }
        // GearGLView.orbitBy turns 0.5° per pixel.
        assertEquals(30f, CameraStep.ROTATE_RIGHT.orbitDxPx * 0.5f, 1e-6f)
        assertEquals(-CameraStep.ROTATE_RIGHT.orbitDxPx, CameraStep.ROTATE_LEFT.orbitDxPx, 1e-6f)
        assertEquals(-CameraStep.TILT_DOWN.orbitDyPx, CameraStep.TILT_UP.orbitDyPx, 1e-6f)
        assertEquals(1f, CameraStep.ZOOM_IN.zoom * CameraStep.ZOOM_OUT.zoom, 1e-6f)
        assertTrue(CameraStep.ZOOM_IN.zoom > 1f)
    }

    @Test
    fun stateAndControlNamesExistInBothLanguages() {
        listOf("state_expanded", "state_collapsed", "expand_section", "collapse_section", "reset_field")
            .forEach { assertTranslated(it) }
        listOf(
            "help_for", "expr_mode_for", "standard_value", "gear_type_button", "edit_tooth",
            "remove_override_tooth", "delete_saved", "override_count"
        ).forEach { assertTranslated(it, placeholder = true) }
    }

    @Test
    fun aFieldNameCarriesItsUnitExactlyOnce() {
        assertEquals("Face width (mm)", numberRowLabel("Face width", "mm"))
        // The label already names its unit: appending it again read "Module (mm) (mm)".
        assertEquals("Module (mm)", numberRowLabel("Module (mm)", "mm"))
        assertEquals("Teeth", numberRowLabel("Teeth", ""))
    }
}
