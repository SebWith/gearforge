package com.gearforge.app

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * True when the user has asked the system for less motion.
 *
 * All three scales are checked, not just the animator scale: "Remove animations" in Accessibility
 * sets all of them to 0, but a user can also zero a single scale in Developer options, and an
 * animation that only respects one of them still moves for that user.
 *
 * This lived as a private function in the landing page until the viewport grew its own animation
 * (the meshing playback). A second copy would have been a second definition of "reduced motion",
 * and the two would have drifted — so the question is asked in one place and answered for the whole
 * app. The value is read once per composition, not per frame: the setting does not change while the
 * app is in the foreground.
 */
internal fun reduceMotionEnabled(context: Context): Boolean {
    val resolver = context.contentResolver
    return try {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
            Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f ||
            Settings.Global.getFloat(resolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f) == 0f
    } catch (t: Throwable) {
        // Some OEM images throw on an unknown Settings key. Motion is the safe default: a static
        // preview is a worse product than a moving one, and this must never break the screen.
        false
    }
}

/** [reduceMotionEnabled] scoped to the composition. */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { reduceMotionEnabled(context) }
}
