package com.gearforge.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import com.gearforge.core.Mesh
import com.gearforge.core.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A lit, orbitable OpenGL ES viewport with PBR materials and a soft ground shadow.
 *
 * Renders into a [TextureView] instead of a SurfaceView-based `GLSurfaceView` so the
 * OpenGL output goes through the normal hardware-accelerated view compositing path.
 * This avoids the SurfaceView hole-punching/z-order failures that show an empty/black
 * viewport on many GPUs when the view is embedded in Compose with an edge-to-edge
 * translucent window.
 */
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
        val highlight: Boolean = false
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
        val glVendor: String = ""
    )

    private val renderer = GearRenderer(context)

    /** Snapshot of GL-thread diagnostics merged with view-state info for the overlay. */
    fun snapshotDiag(): Diag = renderer.diag.copy(
        viewAttached = isAttachedToWindow,
        viewWidth = width,
        viewHeight = height
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

    var onPick: ((Float, Float, Float) -> Unit)? = null

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
        requestRender()
    }

    /** Requests a single redraw (used by the hero's idle-spin frame loop). */
    fun requestFrame() {
        requestRender()
    }

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    // ---- TextureView / EGL plumbing -------------------------------------

    private var renderThread: RenderThread? = null

    private var currentSurface: SurfaceTexture? = null
    private var currentWidth = 0
    private var currentHeight = 0

    private fun requestRender() {
        renderThread?.requestRender()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        currentSurface = surface
        currentWidth = width
        currentHeight = height
        startRenderThread()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        currentWidth = width
        currentHeight = height
        renderThread?.requestSurfaceChanged(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopRenderThread()
        currentSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // Rendered on demand; nothing to do here.
    }

    private fun startRenderThread() {
        val s = currentSurface ?: return
        if (renderThread != null) return
        renderThread = RenderThread(s, currentWidth, currentHeight).also { it.start() }
    }

    private fun stopRenderThread() {
        val t = renderThread
        renderThread = null
        t?.shutdown()
        // Join so EGL teardown finishes before a new thread (started on resume) touches
        // the shared renderer/surface. A short timeout keeps the main thread responsive.
        if (t != null && t !== Thread.currentThread()) {
            try {
                t.join(1000L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Explicitly releases the EGL surface/context on pause so rotation/view switches
     * do not leak GL resources (point 6). The render thread is recreated on [onResume].
     */
    fun onPause() {
        stopRenderThread()
    }

    /** Recreates the render thread on resume if a surface is currently available (point 6). */
    fun onResume() {
        startRenderThread()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        GearGLViewBridge.register(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        GearGLViewBridge.unregister(this)
        stopRenderThread()
        currentSurface = null
    }

    /** Owns the EGL context/surface and drives on-demand frames for the [GearRenderer]. */
    private inner class RenderThread(
        private val surface: SurfaceTexture,
        private val initialWidth: Int,
        private val initialHeight: Int
    ) : Thread("GearGLRenderer") {

        private val lock = Object()
        @Volatile private var renderRequested = true
        @Volatile private var running = true
        @Volatile private var sizeChanged = false
        @Volatile private var pendingW = initialWidth
        @Volatile private var pendingH = initialHeight

        private var display: EGLDisplay? = null
        private var context: EGLContext? = null
        private var eglSurface: EGLSurface? = null

        fun requestRender() {
            synchronized(lock) {
                renderRequested = true
                lock.notifyAll()
            }
        }

        fun requestSurfaceChanged(width: Int, height: Int) {
            synchronized(lock) {
                pendingW = width
                pendingH = height
                sizeChanged = true
                renderRequested = true
                lock.notifyAll()
            }
        }

        fun shutdown() {
            running = false
            synchronized(lock) { lock.notifyAll() }
        }

        override fun run() {
            if (!initEgl()) {
                running = false
                return
            }
            var drawW = pendingW
            var drawH = pendingH
            renderer.onSurfaceCreated()
            renderer.onSurfaceChanged(drawW, drawH)
            while (running) {
                var needSizeChange = false
                synchronized(lock) {
                    // Render on demand (point 20): block until a frame is explicitly
                    // requested; there is no timer/continuous redraw loop.
                    while (!renderRequested && running) {
                        lock.wait()
                    }
                    if (running) {
                        needSizeChange = sizeChanged
                        if (sizeChanged) {
                            drawW = pendingW
                            drawH = pendingH
                            sizeChanged = false
                        }
                        renderRequested = false
                    }
                }
                if (!running) break
                if (needSizeChange) renderer.onSurfaceChanged(drawW, drawH)
                if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                    renderer.reportFatal("eglMakeCurrent failed in render loop")
                    break
                }
                renderer.onDrawFrame()
                EGL14.eglSwapBuffers(display, eglSurface)
            }
            releaseEgl()
        }

        private fun initEgl(): Boolean = try {
            val d: EGLDisplay? = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (d == null || d == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
            display = d
            val version = IntArray(2)
            if (!EGL14.eglInitialize(d, version, 0, version, 1)) {
                throw RuntimeException("eglInitialize failed")
            }
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
            releaseEgl()
            false
        }

        private fun releaseEgl() {
            val d = display
            val ctx = context
            val surf = eglSurface
            display = null
            context = null
            eglSurface = null
            if (d != null) {
                EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surf != null) EGL14.eglDestroySurface(d, surf)
                if (ctx != null) EGL14.eglDestroyContext(d, ctx)
                EGL14.eglTerminate(d)
                EGL14.eglReleaseThread()
            }
        }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                renderer.zoom = (renderer.zoom / detector.scaleFactor).coerceIn(0.1f, 20f)
                requestRender()
                return true
            }
        }
    )

    private var lastX = 0f
    private var lastY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    renderer.panBy(event.x - lastX, event.y - lastY)
                    requestRender()
                } else if (event.pointerCount == 1) {
                    renderer.rotY += (event.x - lastX) * 0.5f
                    renderer.rotX += (event.y - lastY) * 0.5f
                    requestRender()
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (event.eventTime - event.downTime < 300 && abs(event.x - lastX) < 10 && abs(event.y - lastY) < 10) {
                    pick(event.x, event.y)
                }
            }
        }
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
        renderer.panX = (-(minX + maxX) / 2.0).toFloat()
        renderer.panY = (-(minY + maxY) / 2.0).toFloat()
        renderer.rotX = 35f
        renderer.rotY = 45f
        renderer.zoom = 1f
        renderer.frameRadius = radius.toFloat()
        renderer.centerZ = centerZ.toFloat()
        renderer.shadowRadius = radius.toFloat() * 1.15f
        renderer.floorZ = (minZ - radius * 0.25).toFloat()
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
        var bestT = Float.MAX_VALUE
        var best: Vec3? = null
        for (inst in instances) {
            for (t in inst.mesh.triangles) {
                val a = inst.mesh.vertices[t[0]]
                val b = inst.mesh.vertices[t[1]]
                val c = inst.mesh.vertices[t[2]]
                val hit = rayTriangle(ray.first, ray.second, a, b, c)
                if (hit != null && hit.t in 0f..bestT) {
                    bestT = hit.t
                    best = hit.point
                }
            }
        }
        best?.let { onPick?.invoke(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) }
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
        private var spinBase = 0L
        private val spinOffsets = mutableListOf<Float>()

        private var shadowVbo = 0
        private var shadowCount = 0

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
            val r = if (frameRadius > 0f) frameRadius else 20f
            val scale = 2f * r / viewH * zoom
            panX += dxPx * scale
            panY -= dyPx * scale
        }

        fun setSpin(index: Int, speed: Float) {
            if (index in instances.indices) {
                instances[index] = instances[index].copy(spinSpeed = speed)
                spinBase = System.nanoTime()
            }
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

        fun onSurfaceCreated() {
            if (renderBackground) {
                GLES20.glClearColor(0.09f, 0.11f, 0.13f, 1f)
            } else {
                GLES20.glClearColor(0f, 0f, 0f, 0f)
            }
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            GLES20.glCullFace(GLES20.GL_BACK)
            GLES20.glFrontFace(GLES20.GL_CCW)
            simpleProgram = createProgram(SIMPLE_VS, SIMPLE_FS)
            pbrProgram = createProgram(SIMPLE_VS, PBR_FS)
            shadowProgram = createProgram(SHADOW_VS, SHADOW_FS)
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
            spinBase = System.nanoTime()
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

        fun onSurfaceChanged(width: Int, height: Int) {
            viewW = width
            viewH = height
            GLES20.glViewport(0, 0, width, height)
            diag = diag.copy(surfaceWidth = width, surfaceHeight = height)
            if (BuildConfig.DEBUG) android.util.Log.d("GearGLView", "surfaceChanged " + width + "x" + height)
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
                spinBase = System.nanoTime()
            }
            if (instances.isEmpty()) return

            val aspect = if (viewH > 0) viewW.toFloat() / viewH else 1f
            val fovy = 35f
            val halfFovY = Math.toRadians((fovy / 2f).toDouble())
            val halfFovX = Math.atan(Math.tan(halfFovY) * aspect)
            val minHalf = minOf(halfFovY, halfFovX)
            val radius = if (frameRadius > 0f) frameRadius else 20f
            val eyeDist = (radius / Math.sin(minHalf)).toFloat() * zoom
            val near = maxOf(0.01f, eyeDist - radius * 4f)
            val far = eyeDist + radius * 8f
            val proj = perspective(fovy, aspect, near, far)
            val eye = floatArrayOf(0f, 0f, eyeDist)
            val view = lookAt(eye, floatArrayOf(0f, 0f, centerZ), floatArrayOf(0f, 1f, 0f))

            var program = if (quality == Quality.HIGH) pbrProgram else simpleProgram
            if (program == 0) program = if (pbrProgram != 0) pbrProgram else simpleProgram
            if (program == 0) {
                android.util.Log.e("GearGLView", "no usable shader program, cannot draw")
                return
            }
            GLES20.glUseProgram(program)
            val usePbr = program == pbrProgram

            val t = (System.nanoTime() - spinBase) / 1e9f
            for (i in instances.indices) {
                val inst = instances[i]
                val spin = if (inst.spinSpeed != 0f) spinOffsets[i] + t * inst.spinSpeed else spinOffsets[i]
                val model = mul(
                    rotationY(rotY),
                    mul(rotationX(rotX), mul(translation(inst.offsetX + panX, inst.offsetY + panY, 0f), rotationZ(spin)))
                )
                val mvp = mul(proj, mul(view, model))
                val u = if (usePbr) pbrU else simpleU
                GLES20.glUniformMatrix4fv(u.uMvp, 1, false, mvp, 0)
                GLES20.glUniformMatrix4fv(u.uModel, 1, false, model, 0)
                GLES20.glUniform3f(
                    u.uColor,
                    if (inst.highlight) 1.0f else 0.72f,
                    if (inst.highlight) 0.55f else 0.76f,
                    if (inst.highlight) 0.12f else 0.80f
                )
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
                        " near=" + near + " far=" + far + " aspect=" + aspect + " usePbr=" + usePbr
                )
            }
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
            val mvp = mul(proj, mul(view, model))
            GLES20.glUniformMatrix4fv(sUvp, 1, false, mvp, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, shadowVbo)
            GLES20.glEnableVertexAttribArray(sAPos)
            GLES20.glVertexAttribPointer(sAPos, 2, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, shadowCount)

            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        fun rayFromScreen(x: Float, y: Float, width: Int, height: Int): Pair<Vec3, Vec3>? {
            if (width <= 0 || height <= 0) return null
            val aspect = width.toFloat() / height
            val tanFov = tan(Math.toRadians(17.5))
            val ndcX = (2f * x / width - 1f)
            val ndcY = (1f - 2f * y / height)
            val dir = Vec3((ndcX * tanFov * aspect).toDouble(), (ndcY * tanFov).toDouble(), -1.0)
            val origin = Vec3(0.0, 0.0, 40.0 * zoom)
            return invRotate(origin) to invRotate(dir)
        }

        private fun invRotate(v: Vec3): Vec3 {
            val ry = Math.toRadians(rotY.toDouble())
            val cy = cos(ry); val sy = sin(ry)
            val v1 = Vec3(v.x * cy - v.z * sy, v.y, v.x * sy + v.z * cy)
            val rx = Math.toRadians(rotX.toDouble())
            val cx = cos(rx); val sx = sin(rx)
            return Vec3(v1.x, v1.y * cx + v1.z * sx, -v1.y * sx + v1.z * cx)
        }

        private fun rotationX(a: Float): FloatArray {
            val r = Math.toRadians(a.toDouble())
            val c = cos(r).toFloat(); val s = sin(r).toFloat()
            return floatArrayOf(1f, 0f, 0f, 0f, 0f, c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f)
        }
        private fun rotationY(a: Float): FloatArray {
            val r = Math.toRadians(a.toDouble())
            val c = cos(r).toFloat(); val s = sin(r).toFloat()
            return floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f)
        }
        private fun rotationZ(a: Float): FloatArray {
            val r = Math.toRadians(a.toDouble())
            val c = cos(r).toFloat(); val s = sin(r).toFloat()
            return floatArrayOf(c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        }
        private fun translation(x: Float, y: Float, z: Float): FloatArray =
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f)

        private fun perspective(fovy: Float, aspect: Float, near: Float, far: Float): FloatArray {
            val f = (1f / tan(Math.toRadians(fovy.toDouble()) / 2.0)).toFloat()
            return floatArrayOf(
                f / aspect, 0f, 0f, 0f,
                0f, f, 0f, 0f,
                0f, 0f, (far + near) / (near - far), -1f,
                0f, 0f, (2f * far * near) / (near - far), 0f
            )
        }

        private fun lookAt(eye: FloatArray, center: FloatArray, up: FloatArray): FloatArray {
            val z = floatArrayOf(eye[0] - center[0], eye[1] - center[1], eye[2] - center[2])
            val zl = sqrt(z[0] * z[0] + z[1] * z[1] + z[2] * z[2])
            z[0] /= zl; z[1] /= zl; z[2] /= zl
            val x = floatArrayOf(up[1] * z[2] - up[2] * z[1], up[2] * z[0] - up[0] * z[2], up[0] * z[1] - up[1] * z[0])
            val xl = sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2])
            x[0] /= xl; x[1] /= xl; x[2] /= xl
            val y = floatArrayOf(z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0])
            return floatArrayOf(
                x[0], y[0], z[0], 0f,
                x[1], y[1], z[1], 0f,
                x[2], y[2], z[2], 0f,
                -(x[0] * eye[0] + x[1] * eye[1] + x[2] * eye[2]),
                -(y[0] * eye[0] + y[1] * eye[1] + y[2] * eye[2]),
                -(z[0] * eye[0] + z[1] * eye[1] + z[2] * eye[2]), 1f
            )
        }

        private fun mul(a: FloatArray, b: FloatArray): FloatArray {
            // Column-major 4x4 multiply: result = A * B.
            val r = FloatArray(16)
            for (i in 0 until 4) {
                for (j in 0 until 4) {
                    r[i * 4 + j] =
                        a[j] * b[i * 4] + a[4 + j] * b[i * 4 + 1] + a[8 + j] * b[i * 4 + 2] + a[12 + j] * b[i * 4 + 3]
                }
            }
            return r
        }

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

private data class Hit(val t: Float, val point: Vec3)

private fun rayTriangle(origin: Vec3, dir: Vec3, a: Vec3, b: Vec3, c: Vec3): Hit? {
    val e1 = b - a
    val e2 = c - a
    val p = dir.cross(e2)
    val det = e1.x * p.x + e1.y * p.y + e1.z * p.z
    if (abs(det) < 1e-9) return null
    val inv = 1.0 / det
    val tvec = origin - a
    val u = (tvec.x * p.x + tvec.y * p.y + tvec.z * p.z) * inv
    if (u < 0.0 || u > 1.0) return null
    val q = tvec.cross(e1)
    val v = (dir.x * q.x + dir.y * q.y + dir.z * q.z) * inv
    if (v < 0.0 || u + v > 1.0) return null
    val t = (e2.x * q.x + e2.y * q.y + e2.z * q.z) * inv
    if (t < 0.0) return null
    return Hit(t.toFloat(), origin + dir * t)
}
