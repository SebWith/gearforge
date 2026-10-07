package com.gearforge.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The new-user walkthrough.
 *
 * Four things a first-time user has to know, in the order they meet them, each shown on the screen
 * where it applies. The progression is one integer in [SettingsStore]: the index of the next tip, so
 * "have I seen this?" survives a restart, a rotation and a process death without any per-screen
 * bookkeeping.
 *
 * Why it is shaped like this:
 *
 *  - **It spans the flow.** The tips used to live only in the editor, so nothing explained the
 *    two-step wizard the user meets first.
 *  - **It is first-run, not every-run.** The old guard was "once per session", which meant a
 *    returning user met the same hints on every launch and could not turn them off. Settings now
 *    offers to show them again, which is what makes showing them once honest rather than lossy.
 *  - **It never covers the model.** A tip is a strip in the screen's own chrome (the wizard header,
 *    the editor's top bar), never a bubble floating over the viewport — the same rule the coach marks
 *    were built on.
 *
 * The order is the contract: [WIZARD_TYPE] happens before the editor exists, and the editor's three
 * follow in the order the user needs them (get the model into view, then get it out, then edit it).
 */
internal object Tips {

    /** Step 1 of the wizard: pick the gear kind. */
    const val WIZARD_TYPE = 0

    /** Editor: get the model into a view you can judge. */
    const val EDITOR_ORBIT = 1

    /** Editor: where the export lives, and that it can share as well as save. */
    const val EDITOR_EXPORT = 2

    /** Editor: tap a tooth to edit that tooth. */
    const val EDITOR_TOOTH = 3

    /** One past the last tip: the walkthrough is finished. */
    const val DONE = 4

    /** True when there is nothing left to show. */
    fun done(step: Int): Boolean = step >= DONE

    /**
     * The tip the wizard's first step should show for [step], or null.
     *
     * Only the first step: the later steps are about the gear the user just picked, and a hint there
     * would compete with the thing they are reading.
     */
    fun wizardTip(step: Int): Int? = WIZARD_TYPE.takeIf { step == WIZARD_TYPE }

    /**
     * The tip the editor should show for [step], or null when the editor's part is over.
     *
     * The wizard's tip is deliberately not re-shown here: a user who reached the editor has already
     * answered the question it asks.
     */
    fun editorTip(step: Int): Int? = step.takeIf { step in EDITOR_ORBIT until DONE }

    /** The step that follows [tip]. Monotonic and clamped, so it can be advanced from anywhere. */
    fun next(tip: Int): Int = (tip + 1).coerceAtMost(DONE)

    /** Where the walkthrough starts, including when Settings asks for it again. */
    const val FIRST = WIZARD_TYPE
}

/**
 * One tip, as a strip in the screen's own chrome.
 *
 * Dismissible as well as steppable: a hint the user cannot get rid of is worse than no hint, and
 * dismissing it here marks the whole walkthrough as read — the alternative (one flag per tip) would
 * bring the remaining ones back on the next launch, which is the behaviour being fixed.
 */
@Composable
internal fun TipStrip(
    text: String,
    lang: I18n.Lang,
    isLast: Boolean,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
        ) {
            Text(
                text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            TextButton(onClick = onNext) {
                Text(I18n.t(lang, if (isLast) "coach_done" else "coach_next"))
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(MinTouchTarget)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = I18n.t(lang, "close"),
                    modifier = Modifier.size(IconVisualSize)
                )
            }
        }
    }
}
