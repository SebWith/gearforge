package com.gearforge.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * Snap targets exposed by the Blender-style navigation gizmo. The axis → view mapping
 * follows the convention documented on [CameraState]: +Z is the gear's axis (top),
 * +Y is front, +X is right.
 */
enum class GizmoView { TOP, BOTTOM, FRONT, BACK, RIGHT, LEFT, HOME }

/** Blender-like axis palette: X red, Y green, Z blue. */
private val XRed = Color(0xFFFF5252)
private val YGreen = Color(0xFF4CAF50)
private val ZBlue = Color(0xFF2196F3)
private val CenterGray = Color(0xFFB0BEC5)

/** Base axis colour for a snap target (positive and negative share the axis colour). */
internal fun GizmoView.axisColor(): Color = when (this) {
    GizmoView.RIGHT, GizmoView.LEFT -> XRed
    GizmoView.FRONT, GizmoView.BACK -> YGreen
    GizmoView.TOP, GizmoView.BOTTOM -> ZBlue
    GizmoView.HOME -> CenterGray
}

/**
 * Target orbit angles (rotX, rotY) that align the requested view with the fixed camera.
 * See [CameraState] for the derivation: the model rotation `R` must map the tapped axis
 * to camera-space +Z (toward the viewer), i.e. `R · axis = (0, 0, 1)`.
 */
internal fun GizmoView.targetOrbit(): Pair<Float, Float> = when (this) {
    GizmoView.TOP -> 0f to 0f        // +Z faces the camera
    GizmoView.BOTTOM -> 0f to 180f   // −Z faces the camera
    GizmoView.FRONT -> 90f to 0f     // +Y faces the camera
    GizmoView.BACK -> -90f to 0f     // −Y faces the camera
    GizmoView.RIGHT -> 0f to -90f    // +X faces the camera
    GizmoView.LEFT -> 0f to 90f      // −X faces the camera
    GizmoView.HOME -> 35f to 45f     // isometric 3/4 view
}

/**
 * Pure, allocation-conscious projection + hit-testing math for the gizmo.
 * Kept free of Compose types so it can be unit-tested on the JVM.
 */
object GizmoMath {
    /** World axis directions in order: +X, −X, +Y, −Y, +Z, −Z. */
    private val AXES = arrayOf(
        floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
        floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f),
        floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f)
    )
    private val AXIS_VIEWS = arrayOf(
        GizmoView.RIGHT, GizmoView.LEFT,
        GizmoView.FRONT, GizmoView.BACK,
        GizmoView.TOP, GizmoView.BOTTOM
    )

    /** A world axis projected into the gizmo's 2D canvas space. */
    data class ProjectedNode(
        val view: GizmoView,
        val x: Float,
        val y: Float,
        /** Camera-space z: +1 fully facing the viewer, −1 pointing away. */
        val depth: Float,
        val isPositive: Boolean
    )

    /**
     * Projects the six world axis directions into view space with an orthographic mapping centred
     * on ([centerX], [centerY]) with node orbit radius [radiusPx]. Screen y is flipped (view +Y is
     * up, canvas +y is down).
     *
     * The node for a world axis `a` lands at `R · a` — the same rotation the renderer applies to the
     * model ([CameraState.modelMatrix] without the pan, [CameraState.rotationQuaternion]). See
     * `GizmoMathTest.pucksMatchTheRenderersViewSpaceAxes`, which pins exactly that: a gizmo built on
     * `R⁻¹` is a mirror image of the scene, and it looks plausible enough in a screenshot that only
     * the comparison catches it.
     */
    fun project(camera: CameraState, centerX: Float, centerY: Float, radiusPx: Float): List<ProjectedNode> {
        val q = camera.rotationQuaternion
        val out = ArrayList<ProjectedNode>(AXES.size)
        for (i in AXES.indices) {
            val v = Quat.rotateVector(q, AXES[i])
            out.add(
                ProjectedNode(
                    view = AXIS_VIEWS[i],
                    x = centerX + v[0] * radiusPx,
                    y = centerY - v[1] * radiusPx,
                    depth = v[2],
                    isPositive = i % 2 == 0
                )
            )
        }
        return out
    }

    /**
     * Returns the snap target whose node is nearest to ([x], [y]) within [hitRadiusPx], or `null`
     * when the tap falls on the gizmo background.
     *
     * **Proximity first, depth only to break an exact tie.** The nodes sit 26 dp from the centre
     * with a 24 dp hit radius, so their hit circles cover nearly the whole 72 dp widget and
     * overlap heavily. Selecting by depth alone made a tap that visually landed on the +X puck
     * snap to whichever axis happened to face the camera. Depth is still needed for the one real
     * ambiguity: when the camera looks straight down an axis, that axis and its opposite project
     * onto the *same* point and only the facing one is visible.
     */
    fun hitTest(x: Float, y: Float, nodes: List<ProjectedNode>, hitRadiusPx: Float): GizmoView? {
        var best: GizmoView? = null
        var bestD2 = Float.MAX_VALUE
        var bestDepth = -Float.MAX_VALUE
        val r2 = hitRadiusPx * hitRadiusPx
        for (n in nodes) {
            val dx = x - n.x
            val dy = y - n.y
            val d2 = dx * dx + dy * dy
            if (d2 > r2) continue
            val nearer = d2 < bestD2 - TIE_EPSILON_PX2
            val tied = d2 <= bestD2 + TIE_EPSILON_PX2
            if (best == null || nearer || (tied && n.depth > bestDepth)) {
                bestD2 = d2
                bestDepth = n.depth
                best = n.view
            }
        }
        return best
    }

    /**
     * Resolves a tap into the view to snap to, including the centre reset. Never returns null.
     *
     * The centre puck is an **explicit target**, not "whatever the background happens to be".
     * It is drawn at [homeRadiusPx], but the axis hit circles reach inwards to
     * `orbitRadius − hitRadiusPx`, which is *inside* that puck — so with the old ordering a user
     * aiming at the visible dot hit an axis instead of HOME, and the drawn affordance lied.
     *
     * Both the press feedback and the release action call this, so the highlight can never
     * announce a different target than the one the release performs.
     */
    fun resolveTap(
        x: Float,
        y: Float,
        nodes: List<ProjectedNode>,
        centerX: Float,
        centerY: Float,
        homeRadiusPx: Float,
        hitRadiusPx: Float
    ): GizmoView {
        val dx = x - centerX
        val dy = y - centerY
        if (dx * dx + dy * dy <= homeRadiusPx * homeRadiusPx) return GizmoView.HOME
        return hitTest(x, y, nodes, hitRadiusPx) ?: GizmoView.HOME
    }

    /** Squared-pixel tolerance for treating two nodes as equidistant from a tap. */
    private const val TIE_EPSILON_PX2 = 1e-3f
}

/**
 * Blender-style 3D navigation gizmo overlay for the main 3D viewport.
 *
 * The widget is exactly 72x72 dp and draws nothing behind itself (fully transparent).
 * It projects the world axes through the current camera orientation each time
 * [cameraState] changes, so it stays in perfect sync with the orbit. A tap snaps to the
 * nearest axis view; the centre puck is an explicit HOME target drawn at the same radius that
 * is actually hit, and a tap beyond every hit circle also resets to [GizmoView.HOME].
 *
 * Touch handling owns the 72x72 area: taps are consumed (so they never reach the GL
 * surface and never trigger a mesh pick), while drags/pinches that exceed touch slop
 * are forwarded to [onOrbit]/[onZoom]/[onPan] so the underlying viewport keeps
 * orbiting exactly as if the gesture had started on the mesh.
 *
 * @param cameraState live camera snapshot from [GearGLView.cameraState].
 * @param lang language of the spoken name, state and actions.
 * @param onSnapToView invoked with the tapped snap target (including HOME).
 * @param onOrbit forward single-finger drags (dx, dy in px) to the camera controller.
 * @param onZoom forward pinch scale factors to the camera controller.
 * @param onPan forward two-finger pan (dx, dy in px) to the camera controller.
 */
@Composable
fun ViewportGizmo(
    modifier: Modifier = Modifier,
    cameraState: CameraState,
    lang: I18n.Lang,
    onSnapToView: (GizmoView) -> Unit,
    onOrbit: (Float, Float) -> Unit = { _, _ -> },
    onZoom: (Float) -> Unit = {},
    onPan: (Float, Float) -> Unit = { _, _ -> }
) {
    if (!cameraState.isAvailable) return

    val density = LocalDensity.current
    val context = LocalContext.current
    // The widget's own size, from `ViewportChrome`: the measurement HUD keeps its labels out of this
    // rectangle, so the two files have to agree on where it is rather than each carry a 72.
    val sizePx = with(density) { GIZMO_WIDGET_SIZE.toPx() }
    val centerPx = sizePx / 2f
    val orbitRadiusPx = with(density) { 26.dp.toPx() }
    val posRadiusPx = with(density) { 6.dp.toPx() }
    val negRadiusPx = with(density) { 4.5.dp.toPx() }
    // The puck is the HOME target, so it is drawn at the radius that is actually hit. Drawing it
    // smaller than its hit area invited taps on the visible dot that resolved to an axis node.
    val homeRadiusPx = with(density) { 9.dp.toPx() }
    val hitRadiusPx = with(density) { 24.dp.toPx() }
    val touchSlopPx = remember { android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat() }

    // Callbacks captured through state so pointerInput is NOT restarted when the caller
    // passes a fresh lambda each recomposition.
    val currentSnap by rememberUpdatedState(onSnapToView)
    val currentOrbit by rememberUpdatedState(onOrbit)
    val currentZoom by rememberUpdatedState(onZoom)
    val currentPan by rememberUpdatedState(onPan)

    val nodes = remember(cameraState, centerPx, orbitRadiusPx) {
        GizmoMath.project(cameraState, centerPx, centerPx, orbitRadiusPx)
    }
    var pressedView by remember { mutableStateOf<GizmoView?>(null) }
    val frontmost = remember(nodes) { nodes.maxByOrNull { it.depth }?.view }

    Canvas(
        modifier = modifier
            .size(GIZMO_WIDGET_SIZE)
            // The pucks are tap targets only for a finger. A screen reader gets the same snaps as actions
            // (the gizmo used to be a named but inert node, in English in every language).
            .semantics {
                contentDescription = I18n.t(lang, "gizmo_desc")
                stateDescription = frontmost?.let { I18n.t(lang, it.labelKey()) } ?: ""
                onClick(label = I18n.t(lang, "gizmo_show", I18n.t(lang, GizmoView.HOME.labelKey()))) {
                    currentSnap(GizmoView.HOME)
                    true
                }
                customActions = GizmoView.entries.filter { it != GizmoView.HOME }.map { view ->
                    CustomAccessibilityAction(I18n.t(lang, "gizmo_show", I18n.t(lang, view.labelKey()))) {
                        currentSnap(view)
                        true
                    }
                }
            }
            .pointerInput(cameraState) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // The gizmo owns its small area; taps are fully consumed so the GL
                    // surface never fires a mesh pick for a gizmo tap.
                    down.consume()
                    val downPos = down.position
                    var dragged = false
                    // Resolve once, at press time, and reuse the result for the release. Recomputing
                    // at release could pick a different target than the one highlighted (the nodes
                    // move when the camera moves), and the press-time resolution already includes
                    // the centre-puck rule.
                    val target = GizmoMath.resolveTap(
                        downPos.x, downPos.y, nodes, centerPx, centerPx, homeRadiusPx, hitRadiusPx
                    )
                    pressedView = target

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount == 0) {
                            if (!dragged) {
                                currentSnap(target)
                            }
                            pressedView = null
                            break
                        }
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (pressedCount >= 2) {
                            if (zoomChange != 1f) {
                                dragged = true
                                currentZoom(zoomChange)
                            }
                            if (panChange != Offset.Zero) {
                                dragged = true
                                currentPan(panChange.x, panChange.y)
                            }
                        } else if (dragged) {
                            currentOrbit(panChange.x, panChange.y)
                        } else if (panChange.getDistance() > touchSlopPx) {
                            dragged = true
                            currentOrbit(panChange.x, panChange.y)
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            }
    ) {
        // Subtle axis spokes from the centre to each node.
        for (n in nodes) {
            drawLine(
                color = n.view.axisColor().copy(alpha = 0.22f),
                start = Offset(centerPx, centerPx),
                end = Offset(n.x, n.y),
                strokeWidth = 1.5f
            )
        }
        // Centre reset point (drawn first so a facing axis node can cover it).
        drawCircle(
            color = CenterGray.copy(alpha = 0.6f),
            radius = homeRadiusPx,
            center = Offset(centerPx, centerPx)
        )
        // Painter's order: far nodes first so near nodes draw on top when they overlap.
        for (n in nodes.sortedBy { it.depth }) {
            val facing = if (n.depth >= 0f) 1f else 0.35f
            val pressedBoost = if (n.view == pressedView) 1.3f else 1f
            val color = n.view.axisColor()
            val c = Offset(n.x, n.y)
            if (n.isPositive) {
                // Filled node (sphere) for positive axes.
                drawCircle(color = color.copy(alpha = facing), radius = posRadiusPx * pressedBoost, center = c)
            } else {
                // Hollow ring for negative axes (desaturated/outlined).
                drawCircle(
                    color = color.copy(alpha = facing * 0.85f),
                    radius = negRadiusPx * pressedBoost,
                    center = c,
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round)
                )
            }
        }
    }
}
