package com.gearforge.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where the fixed panels inside the 3D viewport are allowed to sit.
 *
 * Three overlays share the viewport, and each one is composed by different code: the assembly legend
 * by `GearWorkspace`, the measurement HUD by `MeasurementHud`, the platen's labels by `BedOverlay`.
 * Each of them used to pick its own corner and inset, and two of them picked the *same* corner —
 * the legend and the HUD's block of unanchored measurements were both drawn at 12 dp from the
 * top-start edge, one on top of the other, for every type with more than one body (a hub is enough,
 * and `result_weight` has no anchor, so the block is always drawn). Two translucent panels over each
 * other at the same coordinates is not a cosmetic problem: the body names and the mass row were
 * mutually unreadable, and the comment claiming the corner was free was checking a different
 * overlay's corner.
 *
 * The numbers live here, once, so the next panel that needs a corner has something to ask.
 */
internal val VIEWPORT_INSET = 12.dp

/** Distance between two panels stacked in the same corner. */
internal val VIEWPORT_PANEL_GAP = 8.dp

/** Vertical padding inside the legend's own column, above its first row and below its last. */
private val LEGEND_VERTICAL_PADDING = 4.dp

/**
 * Height the assembly legend occupies, given how many rows it lists.
 *
 * A row is a tap target (`AssemblyLegend` gives every row `MinTouchTarget`), so the height is
 * `rows × rowHeight` plus the column's own padding — the number is derived from the control's own
 * measurements instead of being guessed, because it decides whether the HUD below it is readable.
 *
 * One row returns zero: the legend is shown only when there is more than one body, so a single row
 * is the *absence* of a legend, not a short one.
 */
internal fun legendHeightDp(rows: Int, rowHeight: Dp = MinTouchTarget): Dp =
    if (rows <= 1) 0.dp else rowHeight * rows + LEGEND_VERTICAL_PADDING

/** The legend's bottom edge, measured from the viewport's top edge; 0 dp when there is no legend. */
internal fun legendBottomDp(rows: Int): Dp =
    if (rows <= 1) 0.dp else VIEWPORT_INSET + legendHeightDp(rows)

/**
 * Distance from the viewport's top edge at which the HUD's block of unanchored measurements starts.
 *
 * Below the legend when one is present, and at the standard inset when one is not — so the two never
 * share a pixel, and the HUD does not lose 60 dp of its corner on the types that have no legend.
 */
internal fun hudCornerTopDp(legendRows: Int): Dp {
    val legendBottom = legendBottomDp(legendRows)
    return if (legendBottom > 0.dp) legendBottom + VIEWPORT_PANEL_GAP else VIEWPORT_INSET
}

/**
 * The navigation gizmo's widget size and the margin it keeps from the viewport's edges.
 *
 * `ViewportGizmo` lays itself out in exactly these numbers. Naming them here — rather than in the
 * HUD, which used to ignore the gizmo entirely — is what lets the label placement ask "would this
 * pill be drawn under the gizmo?" without importing the widget, and leaves one definition for the
 * two files to agree on.
 */
internal val GIZMO_WIDGET_SIZE = 72.dp
internal val GIZMO_INSET = 12.dp

/**
 * The gizmo's bottom edge, measured from the viewport's top edge.
 *
 * A label whose leader reaches into the gizmo's column has to start below this: the HUD is drawn
 * *after* the gizmo, so an anchored label that landed there was drawn over the pucks and made the
 * navigation control unreadable while looking like part of the model's measurements.
 */
internal fun gizmoBottomDp(): Dp = GIZMO_INSET + GIZMO_WIDGET_SIZE

/**
 * Opacity of the surface behind every text panel drawn over the 3D view (HUD labels, measurement list,
 * scale bar, bed ruler and caption, assembly legend).
 *
 * The panels sit on whatever the model happens to be drawing, so their contrast has to hold over the
 * lightest pixel as well as the darkest. At the HUD's former 0.72, the dark theme's secondary text
 * measured 3.97:1 over a white pixel — under the 4.5:1 of SC 1.4.3. `ThemeContrastTest` measures every
 * text colour over this alpha on black and on white.
 */
internal const val VIEWPORT_PANEL_ALPHA = 0.85f

/**
 * One gesture-free camera step, offered as an accessibility action on the 3D view.
 *
 * Orbit is a drag, zoom a pinch and pan a two-finger drag — none of which a screen-reader or switch
 * user can perform (SC 2.5.1). The steps reuse the gizmo's entry points, so they move the camera
 * exactly as the equivalent gesture would: [orbitDxPx]/[orbitDyPx] go to `GearGLView.orbitBy`
 * (0.5° per pixel), [zoom] to `GearGLView.zoomByScale` (a pinch factor).
 */
internal enum class CameraStep(
    val labelKey: String,
    val orbitDxPx: Float = 0f,
    val orbitDyPx: Float = 0f,
    val zoom: Float = 1f
) {
    ROTATE_LEFT("view_rotate_left", orbitDxPx = -CAMERA_STEP_PX),
    ROTATE_RIGHT("view_rotate_right", orbitDxPx = CAMERA_STEP_PX),
    TILT_UP("view_tilt_up", orbitDyPx = -CAMERA_STEP_PX),
    TILT_DOWN("view_tilt_down", orbitDyPx = CAMERA_STEP_PX),
    ZOOM_IN("view_zoom_in", zoom = CAMERA_ZOOM_STEP),
    ZOOM_OUT("view_zoom_out", zoom = 1f / CAMERA_ZOOM_STEP)
}

/** 60 px at the orbit's 0.5° per pixel: a 30° step, so twelve steps go once around. */
internal const val CAMERA_STEP_PX = 60f

/** A zoom step the size of a moderate pinch; in and out are exact inverses. */
internal const val CAMERA_ZOOM_STEP = 1.25f

/** I18n key naming a gizmo snap target, for its state and its accessibility action. */
internal fun GizmoView.labelKey(): String = when (this) {
    GizmoView.TOP -> "view_top"
    GizmoView.BOTTOM -> "view_bottom"
    GizmoView.FRONT -> "view_front"
    GizmoView.BACK -> "view_rear"
    GizmoView.RIGHT -> "view_right"
    GizmoView.LEFT -> "view_left"
    GizmoView.HOME -> "view_home"
}
