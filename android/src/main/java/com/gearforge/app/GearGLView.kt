package com.gearforge.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.animation.PathInterpolator
import com.gearforge.core.Mesh
import com.gearforge.core.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A lit, orbitable OpenGL ES viewport with PBR materials and a soft ground shadow.
 *
 * Renders into a [TextureView] instead of a SurfaceView-based `GLSurfaceView` so the
 * OpenGL output goes through the normal hardware-accelerated view compositing path.
 * This avoids the SurfaceView hole-punching/z-order failures that show an empty/black
 * viewport on many GPUs when the view is embedded in Compose with an edge-to-edge
 * translucent window.
 */
/** Maximum gap between the two taps of a double tap. */
private const val DOUBLE_TAP_MS = 300L

/** A double tap may wander this far (px) and still count: fingers are not micrometers. */
private const val DOUBLE_TAP_SLOP_PX = 24f

/**
 * Platen grid pitch in millimetres.
 *
 * Named rather than written into the builder because the viewport labels the grid with it: a
 * hard-coded 10 in the geometry and a second hard-coded 10 in the label would be two definitions
 * of the same fact, and the label would keep lying after the grid changed.
 */
private const val BED_GRID_MM = 10f

/** The platen sits this far below the floor so the shadow is never rejected as coincident. */
private const val BED_PLANE_OFFSET = 0.05f

private val liveRendererOwners = AtomicInteger()

class GearGLView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    enum class Quality { LOW, HIGH }

    data class Instance(
        val mesh: Mesh,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
        val spinSpeed: Float = 0f,
        val highlight: Boolean = false,
        /**
         * Rotation of the body's *placed centre* about the assembly origin, in rad/s.
         *
         * Distinct from [spinSpeed], which is the body turning about its own axis. A planet in a
         * planetary train does both at once, and they are different numbers — that is the whole
         * reason this field exists instead of a single "speed".
         */
        val orbitSpeed: Float = 0f,
        /** Travel along +X in mm/s. A rack does not rotate, it slides. */
        val slideSpeed: Float = 0f,
        /** True when the body turns about X: the worm, whose screw axis runs along X. */
        val aboutX: Boolean = false,
        /**
         * Colour for this body, or null for the default material.
         *
         * Only set when an assembly has more than one body: tinting a single gear would claim a
         * difference that does not exist. The value comes from `GearPalette.bodyArgb`, so the body
         * colours inherit the same contrast guarantee as the panel's section accents.
         */
        val colorArgb: Int? = null
    )

    /** Runtime diagnostics captured on the GL thread, exposed to the Compose overlay. */
    data class Diag(
        val surfaceCreated: Boolean = false,
        val firstFrame: Boolean = false,
        val lastGlError: String = "",
        val vertexCount: Int = 0,
        val triangleCount: Int = 0,
        val bufferCount: Int = 0,
        val instanceCount: Int = 0,
        val surfaceWidth: Int = 0,
        val surfaceHeight: Int = 0,
        val programOk: Boolean = false,
        val programInfo: String = "not created",
        val viewAttached: Boolean = false,
        val viewWidth: Int = 0,
        val viewHeight: Int = 0,
        val glVersion: String = "",
        val glRenderer: String = "",
        val glVendor: String = "",
        val rendererOwnerRetained: Boolean = false,
        val liveRendererOwners: Int = 0
    )

    private val renderer = GearRenderer(context)

    /** Snapshot of GL-thread diagnostics merged with view-state info for the overlay. */
    fun snapshotDiag(): Diag = renderer.diag.copy(
        viewAttached = isAttachedToWindow,
        viewWidth = width,
        viewHeight = height,
        rendererOwnerRetained = renderThread != null,
        liveRendererOwners = liveRendererOwners.get()
    )

    var instances: List<Instance> = emptyList()
        set(value) {
            // Point 20 (render on demand): only invalidate when the scene actually
            // changed. Compose may re-assign the same list reference; skipping that
            // avoids a redundant buffer rebuild + draw.
            if (value === field) return
            field = value
            renderer.requestInstances(value)
            requestRender()
            if (BuildConfig.DEBUG) android.util.Log.d("GearGLView", "instances set: " + value.size)
        }

    var quality: Quality = Quality.HIGH
        set(value) {
            field = value
            renderer.quality = value
            requestRender()
        }

    /**
     * Speed multiplier for the meshing playback: 1 is `MeshKinematics.DEFAULT_SPEED_RAD_PER_S`.
     *
     * This is deliberately a renderer setting rather than a change to the instance list. Changing
     * the instance list would clear the spin phase and upload fresh VBOs — so the gear would snap
     * back to its starting orientation the moment the user reached for the speed control, which is
     * precisely when they are watching it turn.
     *
     * **Zero parks the clock** ([PlaybackClock.PARKED_SCALE]) and is how pause is expressed: the
     * animation holds the coordinate it has reached. Pause therefore travels the same path as a
     * speed change, which is the only reason it cannot move the model — an instance list rebuilt
     * from a `playing` flag would reset the phase instead, in the same class of bug the speed
     * control already had.
     */
    var playbackScale: Float = 1f
        set(value) {
            val safe = if (value <= 0f) {
                PlaybackClock.PARKED_SCALE
            } else {
                value.coerceIn(PlaybackClock.MIN_SCALE, PlaybackClock.MAX_SCALE)
            }
            if (field == safe) return
            field = safe
            renderer.requestPlaybackScale(safe)
            requestRender()
        }

    /**
     * Called with the world-space point where a tap meets the primary body's mid-plane.
     *
     * The point is exact in the XY plane (it *is* the plane), so a consumer can compare its angle
     * to the gear's outline; it is not necessarily on a mesh surface. A tap that cannot be resolved
     * (edge-on view, the plane behind the camera) calls nothing.
     */
    var onPick: ((Float, Float, Float) -> Unit)? = null

    /**
     * Print bed edge length in millimetres, or 0 to hide the bed.
     *
     * The bed is drawn at true scale, because the honest way to answer "does this fit on my
     * printer?" is to show the real platen: a smaller gear then sits visibly inside it and a larger
     * one visibly overhangs. Hidden by default, for two reasons: the standard view stays framed on
     * the model, and re-framing to include a 220 mm platen around a 20 mm gear is a choice the user
     * should make. Set [bedSizeMm] and call [autoFrame] together to see the platen.
     */
    var bedSizeMm: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            renderer.bedSizeMm = value
            // The platen's size is part of the published snapshot: the overlay labels the bed it
            // can see, and a size that changed without a publish would leave the old number on it.
            publishCameraState()
            requestRender()
        }

    /** When false, touch is passed through (used by the landing hero so the gear
     *  is driven only by the gyro/parallax and never by the user's fingers). */
    var interactive: Boolean = true

    /** When false the GL surface clears to transparent and skips its internal background,
     *  so a Compose layer behind the view shows through — used by the landing hero so the
     *  hero artwork is drawn exactly once (no doubled/offset background). */
    var renderBackground: Boolean = true
        set(value) {
            field = value
            renderer.renderBackground = value
            isOpaque = value
        }

    /** Sets the orbit angles directly (hero gyro parallax) and schedules a redraw. */
    fun setOrbit(rotX: Float, rotY: Float) {
        renderer.rotX = rotX
        renderer.rotY = rotY
        publishCameraState()
        requestRender()
    }

    /** Requests a single redraw (used by the hero's idle-spin frame loop). */
    fun requestFrame() {
        requestRender()
    }

    // ---- Camera state + viewport gizmo (Blender-style navigation) ----------

    /** Live camera snapshot consumed by [ViewportGizmo]; updated on every camera change. */
    private val _cameraState = MutableStateFlow(CameraState())
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private var snapAnimator: ValueAnimator? = null

    /** Publishes the current renderer camera as a [CameraState] (no-op when unchanged). */
    private fun publishCameraState() {
        val next = renderer.snapshotCameraState()
        val prev = _cameraState.value
        if (prev == next) return
        _cameraState.value = next
    }

    /** Forwards a single-finger gizmo drag to the orbit camera (same scale as the GL touch handler). */
    fun orbitBy(dxPx: Float, dyPx: Float) {
        snapAnimator?.cancel()
        renderer.rotY += dxPx * 0.5f
        renderer.rotX += dyPx * 0.5f
        publishCameraState()
        requestRender()
    }

    /** Forwards a gizmo pinch to the zoom (inverse scale, same as the GL scale detector). */
    fun zoomByScale(scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        snapAnimator?.cancel()
        renderer.zoom = (renderer.zoom / scaleFactor).coerceIn(0.1f, 20f)
        publishCameraState()
        requestRender()
    }

    /** Forwards a two-finger gizmo pan to the scene translation. */
    fun panByPx(dxPx: Float, dyPx: Float) {
        snapAnimator?.cancel()
        renderer.panBy(dxPx, dyPx)
        publishCameraState()
        requestRender()
    }

    /**
     * Smoothly animates the orbit to the axis-aligned view [view] (or the isometric
     * [GizmoView.HOME]), keeping the current target/pivot, zoom and pan. The rotation
     * takes the shortest path and eases with Material FastOutSlowIn. A new snap or any
     * manual orbit/zoom/pan cancels an in-flight transition.
     */
    fun snapToView(view: GizmoView, durationMs: Int = 250) {
        val (targetX, targetY) = view.targetOrbit()
        val startX = renderer.rotX
        val startY = renderer.rotY
        val deltaX = shortestAngleDelta(startX, targetX)
        val deltaY = shortestAngleDelta(startY, targetY)
        if (deltaX == 0f && deltaY == 0f) return
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs.toLong()
            interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f) // FastOutSlowInEasing
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                renderer.rotX = startX + deltaX * t
                renderer.rotY = startY + deltaY * t
                publishCameraState()
                requestRender()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (snapAnimator === animation) snapAnimator = null
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    if (snapAnimator === animation) snapAnimator = null
                }
            })
        }.also { it.start() }
    }

    /** Shortest signed angle delta from [from] to [to], normalised to (−180°, 180°]. */
    private fun shortestAngleDelta(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    // ---- TextureView / EGL plumbing -------------------------------------

    private val completionHandler = Handler(Looper.getMainLooper())
    private val renderLifecycle = RenderThreadLifecycle<SurfaceTexture, RenderThread>(
        createWorker = { surface, width, height, released -> RenderThread(surface, width, height, released) },
        shutdown = { it.shutdown() },
        dispatchCompletion = { completion -> completionHandler.post { completion() } },
        releaseSurface = { it.release() }
    )
    private val renderThread: RenderThread? get() = renderLifecycle.worker

    private fun requestRender() {
        renderThread?.requestRender()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        renderer.setViewportSize(width, height)
        publishCameraState()
        renderLifecycle.surfaceAvailable(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (!renderLifecycle.surfaceSizeChanged(surface, width, height)) return
        renderThread?.requestSurfaceChanged(width, height)
        renderer.setViewportSize(width, height)
        publishCameraState()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean =
        renderLifecycle.surfaceDestroyed(surface)

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // Rendered on demand; nothing to do here.
    }

    /**
     * Explicitly releases the EGL surface/context on pause so rotation/view switches
     * do not leak GL resources (point 6). The render thread is recreated on [onResume].
     */
    fun onPause() {
        renderLifecycle.pause()
    }

    /** Recreates the render thread on resume if a surface is currently available (point 6). */
    fun onResume() {
        renderLifecycle.resume()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderLifecycle.attach()
        GearGLViewBridge.register(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        GearGLViewBridge.unregister(this)
        renderLifecycle.detach()
    }

    /** Owns the EGL context/surface and drives on-demand frames for the [GearRenderer]. */
    private inner class RenderThread(
        private val surface: SurfaceTexture,
        private val initialWidth: Int,
        private val initialHeight: Int,
        private val onReleased: () -> Unit
    ) : Thread("GearGLRenderer") {

        private val requests = RenderRequests(initialWidth, initialHeight)

        private var display: EGLDisplay? = null
        private var displayInitialized = false
        private var context: EGLContext? = null
        private var eglSurface: EGLSurface? = null

        fun requestRender() = requests.requestFrame()

        fun requestSurfaceChanged(width: Int, height: Int) = requests.requestResize(width, height)

        fun shutdown() = requests.shutdown()

        override fun run() {
            val viewId = System.identityHashCode(this@GearGLView)
            if (BuildConfig.DEBUG) {
                android.util.Log.d("GearGLLifecycle", "acquired view=$viewId liveOwners=${liveRendererOwners.incrementAndGet()}")
            }
            runRenderWorker(
                render = { if (requests.running && initEgl() && requests.running) renderFrames() },
                release = {
                    requests.shutdown()
                    val resources = if (BuildConfig.DEBUG) renderer.resourceSummary() else ""
                    releaseEgl()
                    renderer.onSurfaceReleased()
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "GearGLLifecycle",
                            "released view=$viewId liveOwners=${liveRendererOwners.decrementAndGet()} " +
                                "before=[$resources] retained=[${renderer.resourceSummary()}]"
                        )
                    }
                },
                onReleased = onReleased,
                onFailure = { failure ->
                    android.util.Log.e("GearGLView", "Render worker or EGL release failed", failure)
                    renderer.reportFatal("Render worker or EGL release failed: " + failure.message)
                }
            )
        }

        private fun renderFrames() {
            val (initialW, initialH) = requests.size()
            renderer.onSurfaceCreated()
            renderer.onSurfaceChanged(initialW, initialH)
            while (true) {
                // Render on demand (point 20): block until a frame is explicitly
                // requested; there is no timer/continuous redraw loop.
                val frame = requests.awaitFrame() ?: break
                if (frame.resized) renderer.onSurfaceChanged(frame.width, frame.height)
                if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                    renderer.reportFatal("eglMakeCurrent failed in render loop")
                    break
                }
                renderer.onDrawFrame()
                EGL14.eglSwapBuffers(display, eglSurface)
            }
        }

        private fun initEgl(): Boolean = try {
            val d: EGLDisplay? = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (d == null || d == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
            display = d
            val version = IntArray(2)
            if (!EGL14.eglInitialize(d, version, 0, version, 1)) {
                throw RuntimeException("eglInitialize failed")
            }
            displayInitialized = true
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(d, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0) {
                throw RuntimeException("eglChooseConfig failed")
            }
            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val ctx: EGLContext? = EGL14.eglCreateContext(d, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) throw RuntimeException("eglCreateContext failed")
            context = ctx
            surface.setDefaultBufferSize(initialWidth, initialHeight)
            val surf: EGLSurface? = EGL14.eglCreateWindowSurface(d, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (surf == null || surf == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface failed")
            eglSurface = surf
            if (!EGL14.eglMakeCurrent(d, surf, surf, ctx)) throw RuntimeException("eglMakeCurrent failed")
            true
        } catch (t: Throwable) {
            android.util.Log.e("GearGLView", "EGL init failed", t)
            renderer.reportFatal("EGL init failed: " + t.message)
            false
        }

        private fun releaseEgl() {
            val d = display
            val ctx = context
            val surf = eglSurface
            try {
                if (d != null && displayInitialized) {
                    val unbound = EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    val surfaceDestroyed = surf == null || EGL14.eglDestroySurface(d, surf)
                    val contextDestroyed = ctx == null || EGL14.eglDestroyContext(d, ctx)
                    val terminated = EGL14.eglTerminate(d)
                    check(unbound && surfaceDestroyed && contextDestroyed && terminated) {
                        "EGL release incomplete: unbound=$unbound surface=$surfaceDestroyed context=$contextDestroyed terminated=$terminated"
                    }
                }
            } finally {
                check(EGL14.eglReleaseThread()) { "eglReleaseThread failed" }
            }
            display = null
            displayInitialized = false
            context = null
            eglSurface = null
        }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                snapAnimator?.cancel()
                renderer.zoom = (renderer.zoom / detector.scaleFactor).coerceIn(0.1f, 20f)
                publishCameraState()
                requestRender()
                return true
            }
        }
    )

    private var lastX = 0f
    private var lastY = 0f

    /** Time and place of the previous tap, so the second one can be recognised as a double tap. */
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                snapAnimator?.cancel()
                if (event.pointerCount >= 2) {
                    renderer.panBy(event.x - lastX, event.y - lastY)
                    requestRender()
                } else if (event.pointerCount == 1) {
                    renderer.rotY += (event.x - lastX) * 0.5f
                    renderer.rotX += (event.y - lastY) * 0.5f
                    requestRender()
                }
                publishCameraState()
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (event.eventTime - event.downTime < 300 && abs(event.x - lastX) < 10 && abs(event.y - lastY) < 10) {
                    val now = event.eventTime
                    if (now - lastTapTime < DOUBLE_TAP_MS &&
                        abs(event.x - lastTapX) < DOUBLE_TAP_SLOP_PX &&
                        abs(event.y - lastTapY) < DOUBLE_TAP_SLOP_PX
                    ) {
                        // Two taps in the same place means "frame this", not "select this tooth".
                        // Getting back to a usable view is the gesture people already know, and
                        // picking a tooth as a side effect of it would be noise — so the second tap
                        // is swallowed and the pick never runs.
                        lastTapTime = 0L
                        autoFrame()
                    } else {
                        lastTapTime = now
                        lastTapX = event.x
                        lastTapY = event.y
                        pick(event.x, event.y)
                        performClick()
                    }
                } else {
                    // A drag is never the first half of a double tap.
                    lastTapTime = 0L
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setSpin(instanceIndex: Int, speed: Float) {
        renderer.setSpin(instanceIndex, speed)
        requestRender()
    }

    /** Auto-frames all instances (with offsets) so the whole assembly is visible in a 3/4 isometric view. */
    fun autoFrame() {
        if (instances.isEmpty()) return
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (inst in instances) {
            val ox = inst.offsetX.toDouble()
            val oy = inst.offsetY.toDouble()
            for (v in inst.mesh.vertices) {
                val x = v.x + ox; val y = v.y + oy; val z = v.z
                if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
            }
        }
        val radius = hypot(hypot(maxX - minX, maxY - minY), maxZ - minZ) / 2.0
        val centerZ = (minZ + maxZ) / 2.0
        // A visible platen has to be inside the frame, otherwise switching it on would show a model
        // floating in a grid that runs off screen and the bed edge — the thing the user is looking
        // for — would never be visible.
        val bedHalf = bedSizeMm / 2f * 1.15f
        val framedRadius = maxOf(radius.toFloat(), bedHalf)
        renderer.panX = (-(minX + maxX) / 2.0).toFloat()
        renderer.panY = (-(minY + maxY) / 2.0).toFloat()
        renderer.rotX = 35f
        renderer.rotY = 45f
        renderer.zoom = 1f
        renderer.frameRadius = framedRadius
        renderer.centerZ = centerZ.toFloat()
        renderer.shadowRadius = framedRadius * 1.15f
        renderer.floorZ = (minZ - radius * 0.25).toFloat()
        publishCameraState()
        requestRender()
    }

    /** Resets orbit, zoom and pan to the framed default view. */
    fun resetView() {
        renderer.panX = 0f
        renderer.panY = 0f
        autoFrame()
    }

    private fun pick(x: Float, y: Float) {
        val ray = renderer.rayFromScreen(x, y, width, height) ?: return
        val origin = ray.first
        val dir = ray.second
        // Edge-on: the ray lies in the mid-plane itself, so there is no single point to report.
        if (abs(dir.z) < 1e-9) return
        val body = instances.firstOrNull() ?: return
        // Mid-plane of the primary body, in world space.
        var minZ = Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        for (v in body.mesh.vertices) {
            if (v.z < minZ) minZ = v.z
            if (v.z > maxZ) maxZ = v.z
        }
        if (minZ > maxZ) return
        val plane = (minZ + maxZ) / 2.0
        val t = (plane - origin.z) / dir.z
        if (t <= 0.0) return
        onPick?.invoke(
            (origin.x + dir.x * t).toFloat(),
            (origin.y + dir.y * t).toFloat(),
            plane.toFloat()
        )
    }

    private class GearRenderer(private val context: Context) {

        var renderBackground = true

        var rotX = 35f
        var rotY = 45f
        var zoom = 1f
        var frameRadius = 0f
        var centerZ = 0f
        var shadowRadius = 0f
        var floorZ = 0f
        var panX = 0f
        var panY = 0f
        var quality = Quality.HIGH
        private var logged = false
        @Volatile
        var diag = GearGLView.Diag()
            private set

        private class Uniforms {
            var uMvp = 0
            var uModel = 0
            var uColor = 0
            var uLightDir = 0
            var uCamPos = 0
            var uMetal = 0
            var uRough = 0
        }

        private var simpleProgram = 0
        private var pbrProgram = 0
        private var shadowProgram = 0

        private var aPos = 0
        private var aNormal = 0
        private val simpleU = Uniforms()
        private val pbrU = Uniforms()
        private var sUvp = 0
        private var sAPos = 0

        private val instances = mutableListOf<Instance>()
        private data class GpuMesh(val vbo: Int, val vertexCount: Int)
        private val buffers = mutableListOf<GpuMesh>()
        @Volatile
        private var pendingInstances: List<Instance>? = null
        private val spinOffsets = mutableListOf<Float>()

        /**
         * The playback clock, owned by the GL thread.
         *
         * Written from the UI thread: only the *request* crosses threads, because the clock's
         * re-origin has to happen between two frames rather than in the middle of one. The maths
         * itself lives in [PlaybackClock], where it is unit tested.
         */
        private val playback = PlaybackClock()

        @Volatile
        private var requestedScale = 1f

        fun requestPlaybackScale(scale: Float) {
            requestedScale = scale
        }
        private var shadowVbo = 0
        private var shadowCount = 0

        // Print bed: a 10 mm grid plus a platen frame, in world millimetres, uploaded once per
        // size change. Kept apart from the shadow disc because a platen is square, not radial.
        private var bedProgram = 0
        private var bedUvp = 0
        private var bedUColor = 0
        private var bedAPos = 0
        private var bedVbo = 0
        private var bedVertexCount = 0
        private var bedGridVbo = 0
        private var bedGridVertexCount = 0
        @Volatile
        private var pendingBed: FloatArray? = null
        @Volatile
        private var pendingBedGrid: FloatArray? = null

        // Background texture + program (Prio 5: hero background behind the 3D model).
        private var bgProgram = 0
        private var bgUTex = 0
        private var bgAPos = 0
        private var bgATex = 0
        private var bgVbo = 0
        private var bgTexture = 0

        fun requestInstances(list: List<Instance>) {
            pendingInstances = list
        }

        /** Translate the scene in world units from a screen-space drag delta (pixels). */
        fun panBy(dxPx: Float, dyPx: Float) {
            // The same framing radius the projection was built from — a second fallback of 20 here
            // would make the pan ratio disagree with the eye distance at the first frame.
            val r = ViewportCamera.radiusOr(frameRadius)
            val scale = 2f * r / viewH * zoom
            panX += dxPx * scale
            panY -= dyPx * scale
        }

        fun setSpin(index: Int, speed: Float) {
            if (index in instances.indices) {
                instances[index] = instances[index].copy(spinSpeed = speed)
                playback.reset(System.nanoTime(), requestedScale)
            }
        }

        /** Hiding the bed (0) is the same request as having no platen geometry to draw. */
        var bedSizeMm: Float = 0f
            set(value) {
                field = value
                setBedSize(value)
            }

        /**
         * Builds the platen geometry for [sizeMm], or clears it when [sizeMm] is 0 or less.
         *
         * Drawn at true scale on purpose: a gear that is smaller than the bed has to look smaller,
         * and one that is bigger has to visibly overhang. Scaling the platen to "fit the screen"
         * would make the answer meaningless.
         */
        fun setBedSize(sizeMm: Float) {
            if (sizeMm <= 0f) {
                pendingBed = FloatArray(0)
                pendingBedGrid = FloatArray(0)
                return
            }
            val half = sizeMm / 2f
            val t = (sizeMm * 0.004f).coerceAtLeast(0.4f)

            // Platen frame: four bars, so the edge that decides "does it fit" stays crisp while the
            // grid stays faint.
            val frame = ArrayList<Float>(4 * 6 * 2)
            fun bar(x0: Float, y0: Float, x1: Float, y1: Float) {
                frame.add(x0); frame.add(y0)
                frame.add(x1); frame.add(y0)
                frame.add(x1); frame.add(y1)
                frame.add(x0); frame.add(y0)
                frame.add(x1); frame.add(y1)
                frame.add(x0); frame.add(y1)
            }
            val inner = half - t
            bar(-half, -half, half, -inner)      // bottom
            bar(-half, inner, half, half)        // top
            bar(-half, -inner, -inner, inner)    // left
            bar(inner, -inner, half, inner)      // right

            val grid = ArrayList<Float>(256)
            var y = -half
            while (y <= half + 1e-3f) {
                grid.add(-half); grid.add(y); grid.add(half); grid.add(y)
                y += BED_GRID_MM
            }
            var x = -half
            while (x <= half + 1e-3f) {
                grid.add(x); grid.add(-half); grid.add(x); grid.add(half)
                x += BED_GRID_MM
            }
            pendingBed = frame.toFloatArray()
            pendingBedGrid = grid.toFloatArray()
        }

        private fun rebuildBuffers() {
            // Point 21: dispose the previous type's VBOs before uploading the new mesh
            // so switching gear type (or any parameter change) does not accumulate
            // GL buffer resources over a long session.
            for (b in buffers) {
                GLES20.glDeleteBuffers(1, intArrayOf(b.vbo), 0)
            }
            buffers.clear()
            for (inst in instances) {
                val (positions, normals) = buildGeometry(inst.mesh)
                // Flat shading: every triangle corner is a distinct vertex that carries the
                // triangle's face normal, so flat faces stay visually flat under lighting.
                val flatVertexCount = inst.mesh.triangles.size * 3

                val vboArr = IntArray(1)
                GLES20.glGenBuffers(1, vboArr, 0)
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboArr[0])
                val data = ByteBuffer.allocateDirect((positions.size + normals.size) * 4).order(ByteOrder.nativeOrder())
                val fb = data.asFloatBuffer()
                fb.put(positions); fb.put(normals); fb.flip()
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, (positions.size + normals.size) * 4, fb, GLES20.GL_STATIC_DRAW)

                buffers.add(GpuMesh(vboArr[0], flatVertexCount))
            }
            android.util.Log.i(
                "GearGLView",
                "rebuildBuffers instances=" + instances.size + " buffers=" + buffers.size +
                    " verts=" + (instances.firstOrNull()?.mesh?.vertices?.size ?: 0) +
                    " tris=" + (instances.firstOrNull()?.mesh?.triangles?.size ?: 0) +
                    " flatVerts=" + (buffers.firstOrNull()?.vertexCount ?: 0)
            )
            diag = diag.copy(
                instanceCount = instances.size,
                vertexCount = instances.firstOrNull()?.mesh?.vertices?.size ?: 0,
                triangleCount = instances.firstOrNull()?.mesh?.triangles?.size ?: 0,
                bufferCount = buffers.size
            )
            checkGlError("rebuildBuffers")
            buildShadowDisc()
        }

        private fun buildShadowDisc() {
            if (shadowVbo != 0) {
                GLES20.glDeleteBuffers(1, intArrayOf(shadowVbo), 0)
                shadowVbo = 0
            }
            val segments = 48
            val verts = FloatArray((segments + 1) * 2)
            for (i in 0..segments) {
                val a = (2f * Math.PI.toFloat() * i / segments)
                verts[2 * i] = cos(a)
                verts[2 * i + 1] = sin(a)
            }
            val arr = IntArray(1)
            GLES20.glGenBuffers(1, arr, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, arr[0])
            val data = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            val fb = data.asFloatBuffer()
            fb.put(verts); fb.flip()
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, fb, GLES20.GL_STATIC_DRAW)
            shadowVbo = arr[0]
            shadowCount = segments + 1
        }

        private fun buildGeometry(mesh: Mesh): Pair<FloatArray, FloatArray> {
            val triCount = mesh.triangles.size
            val positions = FloatArray(triCount * 9)
            val normals = FloatArray(triCount * 9)
            var out = 0
            for (t in mesh.triangles) {
                val a = mesh.vertices[t[0]]
                val b = mesh.vertices[t[1]]
                val c = mesh.vertices[t[2]]
                val n = (b - a).cross(c - a).normalized()
                val nx = n.x.toFloat()
                val ny = n.y.toFloat()
                val nz = n.z.toFloat()
                positions[out] = a.x.toFloat(); positions[out + 1] = a.y.toFloat(); positions[out + 2] = a.z.toFloat()
                normals[out] = nx; normals[out + 1] = ny; normals[out + 2] = nz
                out += 3
                positions[out] = b.x.toFloat(); positions[out + 1] = b.y.toFloat(); positions[out + 2] = b.z.toFloat()
                normals[out] = nx; normals[out + 1] = ny; normals[out + 2] = nz
                out += 3
                positions[out] = c.x.toFloat(); positions[out + 1] = c.y.toFloat(); positions[out + 2] = c.z.toFloat()
                normals[out] = nx; normals[out + 1] = ny; normals[out + 2] = nz
                out += 3
            }
            return positions to normals
        }

        fun resourceSummary(): String {
            val bufferCount = buffers.count { it.vbo != 0 } +
                listOf(shadowVbo, bedVbo, bedGridVbo, bgVbo).count { it != 0 }
            val programCount = listOf(simpleProgram, pbrProgram, shadowProgram, bedProgram, bgProgram).count { it != 0 }
            return "buffers=$bufferCount programs=$programCount textures=${if (bgTexture != 0) 1 else 0}"
        }

        fun onSurfaceReleased() {
            buffers.clear()
            shadowVbo = 0
            shadowCount = 0
            bedVbo = 0
            bedVertexCount = 0
            bedGridVbo = 0
            bedGridVertexCount = 0
            bgVbo = 0
            bgTexture = 0
            simpleProgram = 0
            pbrProgram = 0
            shadowProgram = 0
            bedProgram = 0
            bgProgram = 0
            diag = diag.copy(surfaceCreated = false, firstFrame = false, bufferCount = 0, programOk = false)
        }

        fun onSurfaceCreated() {
            onSurfaceReleased()
            setBedSize(bedSizeMm)
            if (renderBackground) {
                GLES20.glClearColor(0.09f, 0.11f, 0.13f, 1f)
            } else {
                GLES20.glClearColor(0f, 0f, 0f, 0f)
            }
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            // Two-sided rendering (audit R1): mixed-winding meshes from arbitrary
            // CAD sources would render half-black under back-face culling. Culling is
            // disabled and the fragment shader flips the normal for back-facing
            // fragments, so both sides are lit consistently.
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            simpleProgram = createProgram(SIMPLE_VS, SIMPLE_FS)
            pbrProgram = createProgram(SIMPLE_VS, PBR_FS)
            shadowProgram = createProgram(SHADOW_VS, SHADOW_FS)
            bedProgram = createProgram(BED_VS, BED_FS)
            if (renderBackground) {
                bgProgram = createProgram(BG_VS, BG_FS)
                bgUTex = GLES20.glGetUniformLocation(bgProgram, "uTexture")
                bgAPos = GLES20.glGetAttribLocation(bgProgram, "aPosition")
                bgATex = GLES20.glGetAttribLocation(bgProgram, "aTexCoord")
                bgTexture = loadBackgroundTexture()
                buildBackgroundQuad()
            }
            aPos = 0
            aNormal = 1
            simpleU.uMvp = GLES20.glGetUniformLocation(simpleProgram, "uMvp")
            simpleU.uModel = GLES20.glGetUniformLocation(simpleProgram, "uModel")
            simpleU.uColor = GLES20.glGetUniformLocation(simpleProgram, "uColor")
            simpleU.uLightDir = GLES20.glGetUniformLocation(simpleProgram, "uLightDir")
            pbrU.uMvp = GLES20.glGetUniformLocation(pbrProgram, "uMvp")
            pbrU.uModel = GLES20.glGetUniformLocation(pbrProgram, "uModel")
            pbrU.uColor = GLES20.glGetUniformLocation(pbrProgram, "uColor")
            pbrU.uLightDir = GLES20.glGetUniformLocation(pbrProgram, "uLightDir")
            pbrU.uCamPos = GLES20.glGetUniformLocation(pbrProgram, "uCamPos")
            pbrU.uMetal = GLES20.glGetUniformLocation(pbrProgram, "uMetal")
            pbrU.uRough = GLES20.glGetUniformLocation(pbrProgram, "uRough")
            sUvp = GLES20.glGetUniformLocation(shadowProgram, "uMvp")
            sAPos = GLES20.glGetAttribLocation(shadowProgram, "aPosition")
            bedUvp = GLES20.glGetUniformLocation(bedProgram, "uMvp")
            bedUColor = GLES20.glGetUniformLocation(bedProgram, "uColor")
            bedAPos = GLES20.glGetAttribLocation(bedProgram, "aPosition")
            playback.reset(System.nanoTime(), requestedScale)
            rebuildBuffers()
            checkGlError("onSurfaceCreated")
            val glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: "?"
            val glRenderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "?"
            val glVendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "?"
            android.util.Log.i("GearGLView", "GL_VERSION=" + glVersion)
            android.util.Log.i("GearGLView", "GL_RENDERER=" + glRenderer)
            val glExt = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
            android.util.Log.i("GearGLView", "element_index_uint=" + glExt.contains("GL_OES_element_index_uint"))

            val pbrOk = pbrProgram != 0 && pbrU.uMvp >= 0
            val simpleOk = simpleProgram != 0 && simpleU.uMvp >= 0
            diag = diag.copy(
                surfaceCreated = true,
                programOk = pbrOk || simpleOk,
                programInfo = "simple=$simpleProgram pbr=$pbrProgram shadow=$shadowProgram uMvp(pbr)=${pbrU.uMvp} uMvp(simple)=${simpleU.uMvp}",
                glVersion = glVersion,
                glRenderer = glRenderer,
                glVendor = glVendor
            )
            android.util.Log.i(
                "GearGLView",
                "onSurfaceCreated simple=$simpleProgram pbr=$pbrProgram shadow=$shadowProgram pbrOk=$pbrOk simpleOk=$simpleOk"
            )
        }

        private var viewW = 1
        private var viewH = 1

        /** Sets the viewport size for camera snapshots without touching the GL state. */
        fun setViewportSize(width: Int, height: Int) {
            viewW = width
            viewH = height
        }

        fun onSurfaceChanged(width: Int, height: Int) {
            viewW = width
            viewH = height
            GLES20.glViewport(0, 0, width, height)
            diag = diag.copy(surfaceWidth = width, surfaceHeight = height)
            if (BuildConfig.DEBUG) android.util.Log.d("GearGLView", "surfaceChanged " + width + "x" + height)
        }

        /**
         * Builds a [CameraState] from the current camera fields using exactly the matrices
         * [onDrawFrame] draws with. The rotation quaternion is the equivalent camera orientation
         * `R⁻¹` of the orbit.
         *
         * The matrices come from [ViewportCamera] rather than from a copy of its maths. That is the
         * point of the extraction: this snapshot used to re-derive the projection and the view
         * while forgetting the model rotation and the pan, so every overlay that trusted it drew
         * in the wrong place. One definition means they cannot disagree again.
         */
        fun snapshotCameraState(): CameraState {
            val radius = ViewportCamera.radiusOr(frameRadius)
            val eyeDist = ViewportCamera.eyeDistance(radius, viewW, viewH, zoom)
            val bedPlane = floorZ - BED_PLANE_OFFSET
            return CameraState(
                rotXDeg = rotX,
                rotYDeg = rotY,
                zoom = zoom,
                panX = panX,
                panY = panY,
                eye = floatArrayOf(0f, 0f, eyeDist),
                target = floatArrayOf(0f, 0f, centerZ),
                rotationQuaternion = gizmoQuaternion(rotX, rotY),
                viewMatrix = ViewportCamera.view(centerZ, eyeDist),
                projectionMatrix = ViewportCamera.projection(radius, viewW, viewH, zoom),
                modelMatrix = ViewportCamera.orbitModel(rotX, rotY, panX, panY),
                bedMatrix = ViewportCamera.bedModel(panX, panY, bedPlane),
                bedSizeMm = if (bedSizeMm > 0f) bedSizeMm else 0f,
                bedGridMm = if (bedSizeMm > 0f) BED_GRID_MM else 0f,
                bedZ = bedPlane,
                viewportWidth = viewW,
                viewportHeight = viewH,
                frameRadius = radius
            )
        }

        fun onDrawFrame() {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            if (renderBackground) drawBackground()
            pendingInstances?.let { list ->
                instances.clear()
                instances.addAll(list)
                spinOffsets.clear()
                repeat(list.size) { spinOffsets.add(0f) }
                rebuildBuffers()
                pendingInstances = null
                playback.reset(System.nanoTime(), requestedScale)
            }
            if (instances.isEmpty()) return

            // The camera comes from ViewportCamera, the same object that fills CameraState for the
            // overlays and the pick ray. One definition, so the drawings and the labels agree.
            val radius = ViewportCamera.radiusOr(frameRadius)
            val eyeDist = ViewportCamera.eyeDistance(radius, viewW, viewH, zoom)
            val proj = ViewportCamera.projection(radius, viewW, viewH, zoom)
            val eye = floatArrayOf(0f, 0f, eyeDist)
            val view = ViewportCamera.view(centerZ, eyeDist)

            var program = if (quality == Quality.HIGH) pbrProgram else simpleProgram
            if (program == 0) program = if (pbrProgram != 0) pbrProgram else simpleProgram
            if (program == 0) {
                android.util.Log.e("GearGLView", "no usable shader program, cannot draw")
                return
            }

            // Upload a changed platen before anything is drawn, on the GL thread.
            pendingBed?.let { verts ->
                val grid = pendingBedGrid ?: FloatArray(0)
                pendingBed = null
                pendingBedGrid = null
                val frame = uploadBed(verts, bedVbo)
                bedVertexCount = frame.first
                bedVbo = frame.second
                val lines = uploadBed(grid, bedGridVbo)
                bedGridVertexCount = lines.first
                bedGridVbo = lines.second
            }
            drawBed(proj, view)

            GLES20.glUseProgram(program)
            val usePbr = program == pbrProgram

            // Re-origin the playback clock when the speed changed: scaling the elapsed time
            // directly would jump the train to a different orientation, and the user changes the
            // speed *while* watching it move. [PlaybackClock] keeps the accumulated angle
            // continuous, and `PlaybackClockTest` proves it.
            val t = playback.timeSeconds(System.nanoTime(), requestedScale)
            for (i in instances.indices) {
                val inst = instances[i]
                val spin = if (inst.spinSpeed != 0f) spinOffsets[i] + t * inst.spinSpeed else spinOffsets[i]
                // The placement is not always static: a planet is carried around the sun and a rack
                // slides along the pitch line. Both belong to the placement, applied before the
                // body's own rotation, so that rotation stays about the body's own axis. Pan is
                // added afterwards: dragging the scene must not move the bodies relative to each
                // other, which is what orbiting the pan offset would do.
                val placedX = inst.offsetX + t * inst.slideSpeed
                val placedY = inst.offsetY
                val orbit = t * inst.orbitSpeed
                val x = if (orbit != 0f) placedX * cos(orbit) - placedY * sin(orbit) else placedX
                val y = if (orbit != 0f) placedX * sin(orbit) + placedY * cos(orbit) else placedY
                val turn = if (inst.aboutX) ViewportCamera.rotationX(spin) else ViewportCamera.rotationZ(spin)
                val model = ViewportCamera.mul(
                    ViewportCamera.rotationY(rotY),
                    ViewportCamera.mul(
                        ViewportCamera.rotationX(rotX),
                        ViewportCamera.mul(ViewportCamera.translation(x + panX, y + panY, 0f), turn)
                    )
                )
                val mvp = ViewportCamera.mul(proj, ViewportCamera.mul(view, model))
                val u = if (usePbr) pbrU else simpleU
                GLES20.glUniformMatrix4fv(u.uMvp, 1, false, mvp, 0)
                GLES20.glUniformMatrix4fv(u.uModel, 1, false, model, 0)
                val base = inst.colorArgb
                val r = if (inst.highlight) 1.0f else base?.let { ((it shr 16) and 0xFF) / 255f } ?: 0.72f
                val g = if (inst.highlight) 0.55f else base?.let { ((it shr 8) and 0xFF) / 255f } ?: 0.76f
                val b = if (inst.highlight) 0.12f else base?.let { (it and 0xFF) / 255f } ?: 0.80f
                GLES20.glUniform3f(u.uColor, r, g, b)
                GLES20.glUniform3f(u.uLightDir, 0.35f, 0.55f, 0.75f)
                if (usePbr) {
                    GLES20.glUniform3f(u.uCamPos, eye[0], eye[1], eye[2])
                    GLES20.glUniform1f(u.uMetal, 0.85f)
                    GLES20.glUniform1f(u.uRough, 0.28f)
                }

                val gpu = buffers.getOrNull(i) ?: continue
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gpu.vbo)
                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, 0)
                GLES20.glEnableVertexAttribArray(aNormal)
                GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 0, gpu.vertexCount * 12)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, gpu.vertexCount)
            }

            if (quality == Quality.HIGH) drawShadow(proj, view)
            checkGlError("onDrawFrame")
            if (!logged) {
                logged = true
                val inst = instances.first()
                diag = diag.copy(firstFrame = true)
                android.util.Log.i(
                    "GearGLView",
                    "FIRST_FRAME verts=" + inst.mesh.vertices.size + " tris=" + inst.mesh.triangles.size +
                        " buffers=" + buffers.size + " instances=" + instances.size +
                        " pbr=" + pbrProgram + " simple=" + simpleProgram +
                        " view=" + viewW + "x" + viewH + " eyeDist=" + eyeDist +
                        " near=" + ViewportCamera.nearPlane(eyeDist, radius) +
                        " far=" + ViewportCamera.farPlane(eyeDist, radius) +
                        " aspect=" + (if (viewH > 0) viewW.toFloat() / viewH else 1f) +
                        " usePbr=" + usePbr
                )
            }
        }

        /**
         * Uploads a 2D vertex array into [vbo] (created when 0) and returns (vertexCount, vbo).
         * An empty array releases the buffer and reports zero vertices.
         */
        private fun uploadBed(verts: FloatArray, vbo: Int): Pair<Int, Int> {
            if (verts.isEmpty()) {
                if (vbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
                return 0 to 0
            }
            var id = vbo
            if (id == 0) {
                val arr = IntArray(1)
                GLES20.glGenBuffers(1, arr, 0)
                id = arr[0]
            }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)
            val data = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            val fb = data.asFloatBuffer()
            fb.put(verts); fb.flip()
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, fb, GLES20.GL_STATIC_DRAW)
            return verts.size / 2 to id
        }

        /**
         * Draws the platen in world millimetres, on the same horizontal plane as the shadow.
         *
         * The plane is not rotated with the orbit: a print bed is a table, so it must stay flat when
         * the model is orbited above it, exactly like the shadow it already casts. It does follow the
         * pan, because panning moves the whole scene across the table.
         */
        private fun drawBed(proj: FloatArray, view: FloatArray) {
            // A missing uniform or attribute would make every call below a silent GL error.
            if (bedProgram == 0 || bedAPos < 0 || bedUvp < 0) return
            if (bedVertexCount == 0 && bedGridVertexCount == 0) return
            // A hair below the shadow's plane so the shadow is never rejected as coincident geometry.
            val bedZ = floorZ - BED_PLANE_OFFSET
            val model = ViewportCamera.bedModel(panX, panY, bedZ)
            val mvp = ViewportCamera.viewProjection(view, proj, model)
            GLES20.glUseProgram(bedProgram)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            // The grid lies exactly on the platen, so the default LESS test would reject it as
            // coincident with the frame; LEQUAL lets the later draw win instead of z-fighting.
            GLES20.glDepthFunc(GLES20.GL_LEQUAL)
            GLES20.glUniformMatrix4fv(bedUvp, 1, false, mvp, 0)
            GLES20.glEnableVertexAttribArray(bedAPos)

            if (bedGridVertexCount > 0) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bedGridVbo)
                GLES20.glVertexAttribPointer(bedAPos, 2, GLES20.GL_FLOAT, false, 0, 0)
                GLES20.glUniform4f(bedUColor, 0.62f, 0.68f, 0.75f, 0.20f)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, bedGridVertexCount)
            }
            if (bedVertexCount > 0) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bedVbo)
                GLES20.glVertexAttribPointer(bedAPos, 2, GLES20.GL_FLOAT, false, 0, 0)
                GLES20.glUniform4f(bedUColor, 0.78f, 0.84f, 0.94f, 0.55f)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, bedVertexCount)
            }

            GLES20.glDepthFunc(GLES20.GL_LESS)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        private fun drawShadow(proj: FloatArray, view: FloatArray) {
            if (shadowRadius <= 0f) return
            GLES20.glUseProgram(shadowProgram)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(false)

            val s = shadowRadius
            val model = floatArrayOf(
                s, 0f, 0f, 0f,
                0f, s, 0f, 0f,
                0f, 0f, s, 0f,
                0f, 0f, floorZ, 1f
            )
            val mvp = ViewportCamera.viewProjection(view, proj, model)
            GLES20.glUniformMatrix4fv(sUvp, 1, false, mvp, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, shadowVbo)
            GLES20.glEnableVertexAttribArray(sAPos)
            GLES20.glVertexAttribPointer(sAPos, 2, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, shadowCount)

            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        /**
         * The ray through the viewport pixel ([x], [y]), in the model's own frame.
         *
         * Delegates to [ViewportCamera.modelRay], which starts at the eye the camera actually has
         * and undoes the pan as well as the orbit. This used to assume the eye sat at
         * `(0, 0, 40 · zoom)` — a number that matched no camera the renderer ever built — and left
         * the pan in, so a tap resolved to a point several millimetres from where it landed and
         * could select the neighbouring tooth.
         */
        fun rayFromScreen(x: Float, y: Float, width: Int, height: Int): Pair<Vec3, Vec3>? =
            ViewportCamera.modelRay(
                rotXDeg = rotX,
                rotYDeg = rotY,
                panX = panX,
                panY = panY,
                frameRadius = frameRadius,
                zoom = zoom,
                viewportWidth = width,
                viewportHeight = height,
                screenX = x,
                screenY = y
            )

        private fun createProgram(vs: String, fs: String): Int {
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, v)
                GLES20.glAttachShader(it, f)
                GLES20.glBindAttribLocation(it, 0, "aPosition")
                GLES20.glBindAttribLocation(it, 1, "aNormal")
                GLES20.glLinkProgram(it)
                val status = IntArray(1)
                GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, status, 0)
                if (status[0] == 0) {
                    android.util.Log.e("GearGLView", "Program link failed: " + GLES20.glGetProgramInfoLog(it))
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("GearGLView", "Program link OK id=$it")
                }
            }
        }

        private fun compile(type: Int, src: String): Int =
            GLES20.glCreateShader(type).also { sh ->
                GLES20.glShaderSource(sh, src)
                GLES20.glCompileShader(sh)
                val status = IntArray(1)
                GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, status, 0)
                val kind = if (type == GLES20.GL_VERTEX_SHADER) "VS" else "FS"
                if (status[0] == 0) {
                    android.util.Log.e("GearGLView", "$kind compile failed: " + GLES20.glGetShaderInfoLog(sh))
                } else {
                    if (BuildConfig.DEBUG) android.util.Log.d("GearGLView", "$kind compile OK")
                }
            }

        private fun loadBackgroundTexture(): Int {
            return try {
                val bmp = android.graphics.BitmapFactory.decodeResource(
                    context.resources, R.drawable.bg_hero
                )
                val tex = IntArray(1)
                GLES20.glGenTextures(1, tex, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                bmp.recycle()
                tex[0]
            } catch (e: Exception) {
                android.util.Log.e("GearGLView", "failed to load background texture", e)
                0
            }
        }

        private fun buildBackgroundQuad() {
            if (bgVbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(bgVbo), 0)
            // Fullscreen triangle strip: position + texcoord, V flipped so the image is upright.
            val verts = floatArrayOf(
                -1f, -1f, 0f, 1f,
                 1f, -1f, 1f, 1f,
                -1f,  1f, 0f, 0f,
                 1f,  1f, 1f, 0f
            )
            val arr = IntArray(1)
            GLES20.glGenBuffers(1, arr, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, arr[0])
            val data = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            val fb = data.asFloatBuffer()
            fb.put(verts); fb.flip()
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, fb, GLES20.GL_STATIC_DRAW)
            bgVbo = arr[0]
        }

        private fun drawBackground() {
            if (bgProgram == 0 || bgTexture == 0 || bgVbo == 0) return
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glUseProgram(bgProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTexture)
            GLES20.glUniform1i(bgUTex, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bgVbo)
            GLES20.glEnableVertexAttribArray(bgAPos)
            GLES20.glVertexAttribPointer(bgAPos, 2, GLES20.GL_FLOAT, false, 16, 0)
            GLES20.glEnableVertexAttribArray(bgATex)
            GLES20.glVertexAttribPointer(bgATex, 2, GLES20.GL_FLOAT, false, 16, 8)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(bgAPos)
            GLES20.glDisableVertexAttribArray(bgATex)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }

        private fun checkGlError(tag: String) {
            var err = GLES20.glGetError()
            var first = true
            while (err != GLES20.GL_NO_ERROR) {
                val msg = "0x" + err.toString(16)
                android.util.Log.e("GearGLView", "$tag: glGetError=$msg")
                if (first) {
                    diag = diag.copy(lastGlError = "$tag: $msg")
                    first = false
                }
                err = GLES20.glGetError()
            }
        }

        fun reportFatal(msg: String) {
            android.util.Log.e("GearGLView", msg)
            diag = diag.copy(lastGlError = msg, programOk = false)
        }

        companion object {
            private val SIMPLE_VS = """
                attribute vec3 aPosition;
                attribute vec3 aNormal;
                uniform mat4 uMvp;
                uniform mat4 uModel;
                varying vec3 vNormal;
                varying vec3 vWorldPos;
                void main() {
                    gl_Position = uMvp * vec4(aPosition, 1.0);
                    vNormal = mat3(uModel) * aNormal;
                    vWorldPos = (uModel * vec4(aPosition, 1.0)).xyz;
                }
            """.trimIndent()

            private val SIMPLE_FS = """
                precision mediump float;
                uniform vec3 uColor;
                uniform vec3 uLightDir;
                varying vec3 vNormal;
                void main() {
                    vec3 n = normalize(vNormal);
                    if (!gl_FrontFacing) n = -n;
                    vec3 l = normalize(uLightDir);
                    float diff = max(dot(n, l), 0.0);
                    vec3 color = uColor * (0.35 + diff * 0.8);
                    gl_FragColor = vec4(color, 1.0);
                }
            """.trimIndent()

            private val PBR_FS = """
                precision mediump float;
                uniform vec3 uColor;
                uniform vec3 uLightDir;
                uniform vec3 uCamPos;
                uniform float uMetal;
                uniform float uRough;
                varying vec3 vNormal;
                varying vec3 vWorldPos;
                const float PI = 3.14159265359;
                float distributionGGX(vec3 N, vec3 H, float roughness) {
                    float a = roughness * roughness;
                    float a2 = a * a;
                    float NdotH = max(dot(N, H), 0.0);
                    float denom = (NdotH * NdotH * (a2 - 1.0) + 1.0);
                    return a2 / (PI * denom * denom);
                }
                float geometrySchlickGGX(float NdotV, float roughness) {
                    float r = roughness + 1.0;
                    float k = (r * r) / 8.0;
                    return NdotV / (NdotV * (1.0 - k) + k);
                }
                float geometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
                    return geometrySchlickGGX(max(dot(N, V), 0.0), roughness) *
                           geometrySchlickGGX(max(dot(N, L), 0.0), roughness);
                }
                vec3 fresnelSchlick(float cosTheta, vec3 F0) {
                    return F0 + (1.0 - F0) * pow(1.0 - cosTheta, 5.0);
                }
                void main() {
                    vec3 N = normalize(vNormal);
                    if (!gl_FrontFacing) N = -N;
                    vec3 V = normalize(uCamPos - vWorldPos);
                    vec3 L = normalize(uLightDir);
                    vec3 H = normalize(V + L);
                    vec3 F0 = mix(vec3(0.04), uColor, uMetal);
                    vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);
                    float NDF = distributionGGX(N, H, uRough);
                    float G = geometrySmith(N, V, L, uRough);
                    vec3 specular = (NDF * G * F) / (4.0 * max(dot(N, V), 0.0) * max(dot(N, L), 0.0) + 0.001);
                    vec3 kD = (1.0 - F) * (1.0 - uMetal);
                    vec3 diffuse = kD * uColor / PI;
                    float NdotL = max(dot(N, L), 0.0);
                    vec3 env = mix(vec3(0.12, 0.14, 0.18), vec3(0.30, 0.34, 0.40), N.y * 0.5 + 0.5);
                    vec3 ambient = env * uColor;
                    vec3 color = ambient + (diffuse + specular) * vec3(1.0, 0.97, 0.92) * NdotL * 2.2;
                    color = color / (color + vec3(1.0));
                    color = pow(color, vec3(1.0 / 2.2));
                    gl_FragColor = vec4(color, 1.0);
                }
            """.trimIndent()

            private val SHADOW_VS = """
                attribute vec2 aPosition;
                uniform mat4 uMvp;
                varying vec2 vPos;
                void main() {
                    vPos = aPosition;
                    gl_Position = uMvp * vec4(aPosition, 0.0, 1.0);
                }
            """.trimIndent()

            private val SHADOW_FS = """
                precision mediump float;
                varying vec2 vPos;
                void main() {
                    float d = length(vPos);
                    float a = smoothstep(1.0, 0.0, d);
                    gl_FragColor = vec4(0.0, 0.0, 0.0, a * 0.45);
                }
            """.trimIndent()

            // The print bed: flat colour over a world-space XY mesh, so the platen keeps its true
            // size on screen instead of being a screen-space decoration.
            private val BED_VS = """
                attribute vec2 aPosition;
                uniform mat4 uMvp;
                void main() {
                    gl_Position = uMvp * vec4(aPosition, 0.0, 1.0);
                }
            """.trimIndent()

            private val BED_FS = """
                precision mediump float;
                uniform vec4 uColor;
                void main() {
                    gl_FragColor = uColor;
                }
            """.trimIndent()

            private val BG_VS = """
                attribute vec2 aPosition;
                attribute vec2 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    vTexCoord = aTexCoord;
                    gl_Position = vec4(aPosition, 0.0, 1.0);
                }
            """.trimIndent()

            private val BG_FS = """
                precision mediump float;
                uniform sampler2D uTexture;
                varying vec2 vTexCoord;
                void main() {
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
            """.trimIndent()
        }
    }
}


/**
 * Routes Activity onPause/onResume to all live [GearGLView]s so rotation/view switches
 * release and recreate GL surfaces without leaking EGL resources (point 6). MainActivity
 * calls [GearGLViewBridge.onPause]/[GearGLViewBridge.onResume]; each view registers itself
 * on attach and unregisters on detach. Views are weakly referenced so a missed unregister
 * cannot leak memory.
 */
object GearGLViewBridge {
    private val views: MutableSet<GearGLView> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<GearGLView, Boolean>())

    @Synchronized
    fun register(view: GearGLView) {
        views.add(view)
    }

    @Synchronized
    fun unregister(view: GearGLView) {
        views.remove(view)
    }

    fun onPause() {
        snapshot().forEach { it.onPause() }
    }

    fun onResume() {
        snapshot().forEach { it.onResume() }
    }

    private fun snapshot(): List<GearGLView> = synchronized(this) { views.filterNotNull() }
}
