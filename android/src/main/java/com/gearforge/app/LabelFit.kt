package com.gearforge.app

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * Text fitted to a viewport, measured rather than guessed at.
 *
 * This exists because of one defect, found twice. The measurement HUD drew every dimension label
 * into a fixed 136 dp box, so "Pitch diameter  20.000 mm" was laid out 374 px wide (136 dp at
 * 1080x2400 / 2.75), drew as "Pitch diameter  20.000", and reported nothing: no ellipsis, no
 * overflow warning, no lint, no log line. The unit simply was not on screen — in a print-oriented
 * app where millimetres against inches is the whole question. The print bed's ruler labels had the
 * same shape (a fixed 54x16 dp box) and the same silent loss waiting at a larger font scale.
 *
 * A `Text` with `maxLines = 1` clips its tail silently. Any box narrower than its content therefore
 * loses information with no signal at all, and the only defence that holds is to stop guessing the
 * size: measure the string that will be drawn, and give it that much room.
 *
 * Two dimensions are measured, not one:
 *
 *  - width, because that is where the hour was lost, and
 *  - height, because a fixed height fails the same way the moment the user raises the system font
 *    scale — and a raised font scale is a setting the user chose on purpose.
 *
 * The style is returned along with the size so the box and the text inside it are laid out with the
 * same font size. Sizing a box from the text and then drawing the text in another size is the same
 * defect in a new costume.
 */
internal data class FittedLabel(val widthPx: Float, val heightPx: Float, val style: TextStyle)

/**
 * Measures [text] and returns the box size and text style that will not clip it.
 *
 * A label too wide for [maxWidthPx] is *shrunk* rather than clipped, down to 35 % of the original
 * size. Below that the honest answer is that the viewport is too narrow to carry the label, and a
 * caller that needs a hard guarantee should clamp the box position as well (see
 * [HudProjection.labelLeft]).
 */
internal fun fitLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    maxWidthPx: Float,
    padPx: Float
): FittedLabel {
    fun layoutOf(s: TextStyle) = measurer.measure(text = text, style = s, maxLines = 1, softWrap = false)

    val available = maxOf(maxWidthPx - 2f * padPx, 1f)
    val natural = layoutOf(style).size
    if (natural.width.toFloat() <= available) {
        return FittedLabel(natural.width + 2f * padPx, natural.height + 2f * padPx, style)
    }

    // labelSmall is 11 sp in the Material scale, and is the fallback for a style that carries no
    // font size of its own rather than a hard-coded choice of size.
    val base = if (style.fontSize.isSpecified) style.fontSize else 11.sp
    val scaled = (available / natural.width.toFloat()).coerceIn(0.35f, 1f)
    val shrunk = style.copy(fontSize = base * scaled)
    val size = layoutOf(shrunk).size
    return FittedLabel(size.width + 2f * padPx, size.height + 2f * padPx, shrunk)
}
