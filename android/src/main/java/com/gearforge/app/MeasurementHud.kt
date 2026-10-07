package com.gearforge.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gearforge.core.GearParams
import com.gearforge.core.GearSpec
import com.gearforge.core.UnitSystem
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The measurement HUD: the primary dimensions of [params] drawn as labels anchored to the
 * geometry they describe, over the 3D viewport.
 *
 * Why an overlay and not part of the renderer: `GearGLView` owns a `TextureView` and an EGL
 * render thread, and `.github/instructions/android-ui.instructions.md` forbids rendering text
 * there. Everything the overlay needs is already published by [CameraState] — the view and
 * projection matrices the renderer feeds the GL uniforms — so labels can be positioned without
 * touching a single line of the portable GL code.
 *
 * Three deliberate properties:
 *
 *  - **The numbers are the same numbers as the results table.** Both render
 *    [`GearSpec.measures`], which is the single definition of the dimensions.
 *  - **Labels point at something.** A row that describes a circle carries an
 *    `anchorRadiusMm`; the label is drawn at the projection of that circle, with a leader line
 *    running radially outwards. Rows that describe nothing positional (mass, centre distance,
 *    planet count) are listed in a corner block instead of being attached to a misleading
 *    point.
 *  - **Nothing here consumes a touch.** A `Canvas` and `Text` carry no pointer input, so
 *    orbit, pinch, pan and the gizmo keep working underneath.
 *  - **Labels follow the camera, not the animation.** Every label is projected through the model
 *    matrix the renderer publishes, so orbiting, panning and zooming move them exactly with the
 *    geometry. While *playback* is running the renderer spins the model without republishing
 *    [CameraState] (see `GearGLView`), so the labels stay on the radii they name — which is
 *    correct, because the dimensions of a gear do not change as it turns, and the radii are drawn
 *    on circles that are rotationally invariant about the gear's own axis. Republishing on every
 *    frame would move every label several times a second to say the same thing, and cost a
 *    recomposition per frame for the privilege. If a tip radius is ever drawn as a *point* rather
 *    than as a circle, that choice has to be revisited: a point on a spinning body really does
 *    move.
 *  - **A label is as wide as its own text.** There is no label width constant, on purpose. A fixed
 *    136 dp box silently ate the unit off the longest dimensions — "Pitch diameter  20.000 mm" was
 *    laid out 374 px wide (136 dp at 1080x2400/2.75), drew as "Pitch diameter  20.000", and threw
 *    nothing: no ellipsis, no overflow warning, no lint. It took a `uiautomator` dump to see it
 *    (the node's width was exactly the clamp for the three longest labels and below it for every
 *    shorter one). Measuring instead removes the constants that made it possible, and a label that
 *    would not fit the viewport is shrunk rather than clipped.
 */
@Composable
internal fun MeasurementHud(
    cameraState: CameraState,
    params: GearParams,
    lang: I18n.Lang,
    showScaleBar: Boolean,
    modifier: Modifier = Modifier,
    /**
     * Rows the assembly legend is listing in this same top-start corner, or 0 when there is none.
     *
     * The legend lists one row per body, and it is composed by the caller — so the caller is the only
     * one that knows whether this corner has already been claimed. Passing the count instead of a
     * pre-computed offset keeps the arithmetic in [hudCornerTopDp], where it can be tested and where
     * the legend's own row height is the one definition of it.
     */
    legendRows: Int = 0
) {
    if (!cameraState.isAvailable) return

    val width = cameraState.viewportWidth
    val height = cameraState.viewportHeight
    val density = LocalDensity.current
    val unitKey = if (params.unit == UnitSystem.INCH) "inch" else "mm"

    // The full `P · V · M`, not just `P · V`: the orbit and the pan live in the model matrix, and
    // leaving it out is what made the labels and their leader lines stand still while the model
    // turned underneath them. The key is the whole snapshot, whose `equals` is defined on the
    // scalar camera parameters — so a new orbit angle invalidates this block.
    val viewProjection = remember(cameraState) {
        ViewportCamera.viewProjection(
            cameraState.viewMatrix,
            cameraState.projectionMatrix,
            cameraState.modelMatrix
        )
    }
    val rows = remember(params) { GearSpec.measures(params) }

    val leaderPx: Float = with(density) { LEADER_LENGTH.toPx() }
    val labelPadPx: Float = with(density) { LABEL_PAD.toPx() }
    val labelGapPx: Float = with(density) { LABEL_GAP.toPx() }
    val zMid = params.thickness.toFloat() / 2f

    val anchored: List<AnchoredLabel> =
        remember(viewProjection, rows, lang, unitKey, zMid, width, height, leaderPx) {        val out = ArrayList<AnchoredLabel>()
        var index = 0
        for (row in rows) {
            val radius = row.anchorRadiusMm ?: continue
            val angle = Math.toRadians((ANCHOR_ANGLES[index % ANCHOR_ANGLES.size]).toDouble())
            index++
            val r = radius.toFloat()
            // The height at which this circle exists. A straight body's circles are the same circle
            // at every height, so the mid-plane is right for them; a tapered body's tip circle only
            // exists at its own end of the face (`Measure.anchorZMm`).
            val z = row.anchorZMm?.toFloat() ?: zMid
            val point = HudProjection.project(
                viewProjection, width, height,
                (r * cos(angle)).toFloat(), (r * sin(angle)).toFloat(), z
            ) ?: continue
            val centre = HudProjection.project(viewProjection, width, height, 0f, 0f, z) ?: continue
            val dx = point.x - centre.x
            val dy = point.y - centre.y
            val len = maxOf(hypot(dx, dy), 1e-3f)
            out.add(
                AnchoredLabel(
                    key = row.key,
                    text = labelText(row, lang, unitKey),
                    from = Offset(point.x, point.y),
                    to = Offset(point.x + dx / len * leaderPx, point.y + dy / len * leaderPx)
                )
            )
        }
        out
    }

    // Each label is measured once per parameter set, not once per orbit frame: the text depends on
    // the parameters, the language and the unit system - never on the camera.
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val fitted: Map<String, FittedLabel> = remember(rows, lang, unitKey, labelStyle, width) {
        rows.filter { it.anchorRadiusMm != null }.associate { row ->
            row.key to fitLabel(
                measurer = measurer,
                text = labelText(row, lang, unitKey),
                style = labelStyle,
                maxWidthPx = width.toFloat(),
                padPx = labelPadPx
            )
        }
    }

    // Two labels anchored on nearly the same screen row (common when looking straight down the
    // axis) would overprint each other; push them apart deterministically. Each label's own
    // measured height is used, so a large system font scale separates them by more, not less.
    val placedY: Map<String, Float> = remember(anchored, fitted, labelGapPx, legendRows, width) {
        // The panels that share a corner with the labels. A label's own side of the viewport decides
        // which of them is in its way — see HudProjection.Slot.topLimit.
        val legendBottomPx = with(density) { legendBottomDp(legendRows).toPx() }
        val gizmoBottomPx = with(density) { gizmoBottomDp().toPx() }
        val gizmoLeftPx = width - with(density) { (GIZMO_INSET + GIZMO_WIDGET_SIZE).toPx() }
        val slots = anchored.mapNotNull { a ->
            val box = fitted[a.key] ?: return@mapNotNull null
            val labelX = HudProjection.labelLeft(a.to.x, box.widthPx, width.toFloat())
            val underGizmo = box.widthPx > 0f && labelX + box.widthPx > gizmoLeftPx
            val limit = when {
                underGizmo -> maxOf(gizmoBottomPx, legendBottomPx)
                else -> legendBottomPx
            }
            HudProjection.Slot(
                id = a.key,
                y = a.to.y,
                height = box.heightPx,
                topLimit = if (limit > 0f) limit else Float.NEGATIVE_INFINITY
            )
        }
        HudProjection.distribute(slots, gap = labelGapPx).associate { it.id to it.y }
    }

    val centreX = width / 2f
    val listed = rows.filter { it.anchorRadiusMm == null }

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val line = Color.White.copy(alpha = 0.55f)
            for (a in anchored) {
                drawLine(
                    color = line,
                    start = a.from,
                    end = a.to,
                    strokeWidth = 1.5f,
                    cap = StrokeCap.Round
                )
                drawCircle(color = line, radius = 2.5f, center = a.from)
            }
        }

        for (a in anchored) {
            val box = fitted[a.key] ?: continue
            val y = placedY[a.key] ?: a.to.y
            // The side comes from HudProjection, the same function that clamps the pill to the
            // viewport, so the text alignment and the placement cannot disagree.
            val onLeft = HudProjection.labelOnLeft(a.to.x, centreX * 2f)
            val x = HudProjection.labelLeft(a.to.x, box.widthPx, width.toFloat())
            val clampedY = (y - box.heightPx / 2f)
                .coerceIn(0f, maxOf(0f, height - box.heightPx))
            Box(
                Modifier
                    .offset { IntOffset(x.roundToInt(), clampedY.roundToInt()) }
                    .width(with(density) { box.widthPx.toDp() })
                    .height(with(density) { box.heightPx.toDp() })
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = VIEWPORT_PANEL_ALPHA)),
                contentAlignment = if (onLeft) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Text(
                    text = a.text,
                    style = box.style,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = if (onLeft) TextAlign.End else TextAlign.Start
                )
            }
        }

        if (listed.isNotEmpty()) {
            MeasurementList(rows = listed, lang = lang, unitKey = unitKey, topDp = hudCornerTopDp(legendRows))
        }

        if (showScaleBar) {
            ScaleBar(
                pixelsPerMm = HudProjection.pixelsPerMm(
                    cameraState.viewMatrix,
                    cameraState.projectionMatrix,
                    cameraState.modelMatrix,
                    height, 0f, 0f, zMid
                ),
                lang = lang,
                useInch = params.unit == UnitSystem.INCH,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp)
            )
        }
    }
}

/** Fixed angular ladder, in degrees in the gear's XY plane, for the anchored labels. */
private val ANCHOR_ANGLES = listOf(60f, 105f, 150f, 240f, 285f)

/** Padding between a label's text and the edge of its pill, on each side and top/bottom. */
private val LABEL_PAD = 6.dp

private val LEADER_LENGTH = 36.dp

/** Extra vertical distance between two labels that would otherwise touch. */
private val LABEL_GAP = 2.dp

/** One anchored label: the world point it names and the point the text is placed at. */
private data class AnchoredLabel(
    val key: String,
    val text: String,
    val from: Offset,
    val to: Offset
)

/** The one place the label's text is composed, so the measured string is the drawn string. */
private fun labelText(row: GearSpec.Measure, lang: I18n.Lang, unitKey: String): String =
    "${I18n.t(lang, row.key)}  ${formatMeasure(row, lang, unitKey)}"

/** Length of the scale bar in the active unit system: 10 mm, or half an inch. */
private fun scaleBarLengthMm(useInch: Boolean): Double = if (useInch) 12.7 else 10.0

/** Formats one measurement in the active unit system, with the locale's decimal separator. */
private fun formatMeasure(row: GearSpec.Measure, lang: I18n.Lang, unitKey: String): String {
    val number = Format.decimal(row.value, row.decimals, lang)
    return when (row.kind) {
        GearSpec.MeasureKind.LENGTH -> "$number ${I18n.t(lang, unitKey)}"
        // A mass unit symbol is language-neutral; the app's own results rows print it the same way.
        GearSpec.MeasureKind.MASS -> "$number kg"
    }
}

/**
 * The corner block: every measurement that does not describe a circle. Listing them is honest —
 * attaching "centre distance" to a point on the model would claim it means something there.
 *
 * [topDp] is the block's distance from the viewport's top edge; it is computed by the caller because
 * the assembly legend may already be using the top of this corner — see [hudCornerTopDp].
 */
@Composable
private fun MeasurementList(
    rows: List<GearSpec.Measure>,
    lang: I18n.Lang,
    unitKey: String,
    topDp: Dp
) {
    Column(
        Modifier
            .padding(top = topDp, start = VIEWPORT_INSET)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = VIEWPORT_PANEL_ALPHA))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        for (row in rows) {
            // One spoken item per measurement: the name and its value were read as two unrelated texts.
            Row(Modifier.semantics(mergeDescendants = true) {}) {
                Text(
                    I18n.t(lang, row.key),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "  " + formatMeasure(row, lang, unitKey),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * A calibrated scale bar.
 *
 * The pixel length comes from [`HudProjection.pixelsPerMm`], which is derived from the
 * projection matrix and the model's camera depth, so the bar stays true while zooming — a
 * hard-coded pixels-per-millimetre would be a lie the moment the camera moved.
 */
@Composable
private fun ScaleBar(
    pixelsPerMm: Float?,
    lang: I18n.Lang,
    useInch: Boolean,
    modifier: Modifier = Modifier
) {
    val lengthMm = scaleBarLengthMm(useInch)
    // `Float.dp` is a unit constructor with no Density receiver, so the bar's length needs no
    // lambda nesting here. (A `pixelsPerMm?.let { with(density) { … .toDp() } }` chain leaves
    // Kotlin unable to infer the return type of two nested lambdas.)
    val ppm = pixelsPerMm ?: return
    val barWidth: Dp = (ppm.toDouble() * lengthMm).toFloat().dp
    if (barWidth < 6.dp || barWidth > 220.dp) return

    val label = "${Format.decimal(lengthMm, if (useInch) 2 else 0, lang)} " +
        I18n.t(lang, if (useInch) "inch" else "mm")

    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = VIEWPORT_PANEL_ALPHA))
                .padding(horizontal = 4.dp)
        )
        Canvas(Modifier.width(barWidth).height(6.dp)) {
            val y = size.height - 1.5f
            drawLine(
                color = Color.White.copy(alpha = 0.85f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
        }
    }
}
