package com.gearforge.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Labels the print bed: what the squares are and how big the platen is.
 *
 * The platen is drawn at true scale so the answer to "does this fit on my printer?" is a comparison
 * rather than a claim — but an unlabelled grid answers nothing: a square could be 5 mm or 20 mm, and
 * the frame could be any platen on the market. This overlay states both numbers on the bed itself.
 *
 * Why an overlay: the GL viewport renders no text (`.github/instructions/android-ui.instructions.md`),
 * and [CameraState] already publishes the exact matrix the platen was drawn with ([CameraState.bedMatrix]
 * — a translation, because a print bed is a table and must not tilt with the orbit). Projecting the
 * bed's own corners is therefore the same transform the GPU used, not a second guess at it.
 *
 * Distances are always millimetres, deliberately: the bed size is chosen in millimetres in Settings
 * (`PrinterPresets.BED_SIZES`), and a platen quoted in inches while the model is set to inches would
 * be a second source of truth for the same number.
 *
 * Nothing here consumes a touch, so orbit, pinch, pan and the gizmo keep working underneath.
 */
@Composable
internal fun BedOverlay(
    cameraState: CameraState,
    lang: I18n.Lang,
    modifier: Modifier = Modifier
) {
    if (!cameraState.isAvailable || !cameraState.bedVisible) return

    val width = cameraState.viewportWidth
    val height = cameraState.viewportHeight
    val density = LocalDensity.current
    val half = cameraState.bedSizeMm / 2f
    val bedZ = cameraState.bedZ

    // `P · V · M_bed` — the transform the platen is drawn with, so every label lands on the bed.
    val viewProjection = remember(cameraState) {
        ViewportCamera.viewProjection(
            cameraState.viewMatrix,
            cameraState.projectionMatrix,
            cameraState.bedMatrix
        )
    }

    val tickLengthPx: Float = with(density) { TICK_LENGTH.toPx() }
    val labelPadPx: Float = with(density) { LABEL_PAD.toPx() }
    val captionPadPx: Float = with(density) { CAPTION_PAD.toPx() }
    val labelStyle = MaterialTheme.typography.labelSmall

    // The ruler runs along the **back** (+Y) edge with its ticks pointing inwards, and the numbers
    // sit just inside the platen.
    //
    // That edge is chosen for one reason: the front edge of a framed platen always lands around
    // three quarters of the viewport height, which is exactly the band the playback controls live
    // in — a ruler there was half-covered by the speed chips the moment someone pressed play. The
    // back edge is the one strip of the viewport nothing else claims (the gizmo ends above the bed).
    //
    // Every tick is a projected world point, so perspective spacing is reproduced rather than
    // assumed to be even. The first tick is the first multiple of the step *inside* the bed:
    // starting at `floor(-half / step)` labelled a point 40 mm beyond the platen, which is a ruler
    // that measures something that is not there.
    val ruler: List<BedTick> = remember(viewProjection, width, height, half, bedZ, tickLengthPx) {
        val ticks = ArrayList<BedTick>()
        var value = ceil(-half / TICK_STEP_MM) * TICK_STEP_MM
        while (value <= half + 1e-3f) {
            val edge = HudProjection.project(viewProjection, width, height, value, half, bedZ)
            val inner = HudProjection.project(viewProjection, width, height, value, half - TICK_MM, bedZ)
            if (edge != null && inner != null) {
                val dx = inner.x - edge.x
                val dy = inner.y - edge.y
                val len = maxOf(hypot(dx, dy), 1e-3f)
                ticks.add(
                    BedTick(
                        value = value,
                        from = Offset(edge.x, edge.y),
                        to = Offset(
                            edge.x + dx / len * tickLengthPx,
                            edge.y + dy / len * tickLengthPx
                        )
                    )
                )
            }
            value += TICK_STEP_MM
        }
        ticks
    }

    val marker = remember(viewProjection, width, height, half, bedZ) {
        // The front-left corner of the bed: on screen the platen's nearest visible corner, and the
        // one corner the play button, the gizmo and the HUD do not use.
        HudProjection.project(viewProjection, width, height, -half, -half, bedZ)
    }

    // Every label on the platen is measured, never given a size. A fixed 54x16 dp tick label and a
    // caption clamped with a guessed 168 dp width are the same defect as the HUD's fixed 136 dp
    // pill: the first clips its text silently, the second clamps against a number the text does
    // not have, so the block can still run off the right edge. Nothing here depends on the camera,
    // so it is measured once per language and viewport size rather than once per orbit frame.
    val measurer = rememberTextMeasurer()
    val rulerBox: Pair<Dp, Dp> = remember(measurer, labelStyle, lang, width, labelPadPx) {
        // The widest tick the ruler can produce sets the box for all of them, so the numbers line
        // up in a column instead of shifting sideways with their digit count.
        val widest = fitLabel(measurer, "000 ${I18n.t(lang, "mm")}", labelStyle, width.toFloat(), labelPadPx)
        with(density) { widest.widthPx.toDp() to widest.heightPx.toDp() }
    }
    val tickLabelWidth = rulerBox.first
    val tickLabelHeight = rulerBox.second

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val line = Color.White.copy(alpha = 0.55f)
            for (t in ruler) {
                drawLine(color = line, start = t.from, end = t.to, strokeWidth = 1.5f, cap = StrokeCap.Round)
            }
        }

        for (t in ruler) {
            val text = "${Format.decimal(t.value.toDouble(), 0, lang)} ${I18n.t(lang, "mm")}"
            // The widest tick on the ruler sets the box for all of them, so the numbers line up and
            // a shorter one is centred in the same frame instead of shifting sideways.
            val box = fitLabel(measurer, text, labelStyle, width.toFloat(), labelPadPx)
            val boxW = maxOf(box.widthPx, with(density) { tickLabelWidth.toPx() })
            val boxH = maxOf(box.heightPx, with(density) { tickLabelHeight.toPx() })
            val x = (t.to.x - boxW / 2f).coerceIn(0f, maxOf(0f, width - boxW))
            val y = (t.to.y + 2f).coerceIn(0f, maxOf(0f, height - boxH))
            Box(
                Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .width(with(density) { boxW.toDp() })
                    .height(with(density) { boxH.toDp() })
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = VIEWPORT_PANEL_ALPHA)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    style = box.style,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center
                )
            }
        }

        marker?.let { corner ->
            // The bed's front-left corner, with its caption 12 dp above it so the block sits on the
            // platen rather than hanging off it.
            val side = Format.decimal(cameraState.bedSizeMm.toDouble(), 0, lang)
            val line1 = I18n.t(lang, "bed_label") + " " + side + " \u00D7 " + side + " " + I18n.t(lang, "mm")
            val line2 = I18n.t(lang, "bed_grid", Format.decimal(cameraState.bedGridMm.toDouble(), 0, lang))
            val cap1 = fitLabel(measurer, line1, labelStyle, width.toFloat(), captionPadPx)
            val cap2 = fitLabel(measurer, line2, labelStyle, width.toFloat(), captionPadPx)
            // The clamp uses the size the block will actually take. Each measured line carries the
            // padding on all four sides, but the Column applies it once around both lines, so one
            // band is subtracted - a clamp against a box that is larger than the real one leaves
            // the block hanging off the edge it was supposed to stay inside.
            val captionWidthPx = maxOf(cap1.widthPx, cap2.widthPx)
            val captionHeightPx = cap1.heightPx + cap2.heightPx - 2f * captionPadPx
            val x = (corner.x + 12f).coerceIn(0f, maxOf(0f, width - captionWidthPx))
            val y = (corner.y - 12f - captionHeightPx).coerceIn(0f, maxOf(0f, height - captionHeightPx))
            Column(
                Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .width(with(density) { captionWidthPx.toDp() })
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = VIEWPORT_PANEL_ALPHA))
                    .padding(CAPTION_PAD)
            ) {
                Text(
                    line1,
                    style = cap1.style,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    line2,
                    style = cap2.style,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/** One ruler tick: the millimetre value it marks and the screen segment that marks it. */
private data class BedTick(val value: Float, val from: Offset, val to: Offset)

/** Spacing between ruler ticks. Five grid squares, so the numbers stay readable on a 180 mm bed. */
private const val TICK_STEP_MM = 50f

/** Length of the world-space stub the ruler uses to derive the outward screen direction. */
private const val TICK_MM = 5f

private val TICK_LENGTH = 10.dp

/** Padding between a label's text and the edge of its background, on all four sides. */
private val LABEL_PAD = 4.dp

/** Same, for the two-line caption block on the platen's corner. */
private val CAPTION_PAD = 6.dp
