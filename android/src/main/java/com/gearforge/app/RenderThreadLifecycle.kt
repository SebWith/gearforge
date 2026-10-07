package com.gearforge.app

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class RenderThreadLifecycle<Surface : Any, Worker : Thread>(
    private val createWorker: (Surface, Int, Int, () -> Unit) -> Worker,
    private val shutdown: (Worker) -> Unit,
    private val dispatchCompletion: (() -> Unit) -> Unit,
    private val releaseSurface: (Surface) -> Unit
) {
    private data class Target<Surface>(val surface: Surface, val width: Int, val height: Int)

    private inner class Owner(val target: Target<Surface>) {
        lateinit var worker: Worker
        var stopping = false
        var releaseSurfaceOnCompletion = false
    }

    private var target: Target<Surface>? = null
    private var owner: Owner? = null
    private var attached = false
    private var paused = false
    private var disposed = false

    val worker: Worker? get() = synchronized(this) { owner?.worker }

    @Synchronized
    fun attach() {
        attached = true
        startIfReady()
    }

    @Synchronized
    fun detach() {
        attached = false
        target = null
        stopOwner()
    }

    @Synchronized
    fun dispose() {
        disposed = true
        detach()
    }

    @Synchronized
    fun pause() {
        paused = true
        stopOwner()
    }

    @Synchronized
    fun resume() {
        paused = false
        startIfReady()
    }

    @Synchronized
    fun surfaceAvailable(surface: Surface, width: Int, height: Int) {
        if (disposed) return
        target = Target(surface, width, height)
        if (owner?.target?.surface !== surface) stopOwner()
        startIfReady()
    }

    @Synchronized
    fun surfaceSizeChanged(surface: Surface, width: Int, height: Int): Boolean {
        if (target?.surface !== surface) return false
        target = Target(surface, width, height)
        return true
    }

    @Synchronized
    fun surfaceDestroyed(surface: Surface): Boolean {
        if (target?.surface === surface) target = null
        val current = owner ?: return true
        if (current.target.surface !== surface) return true
        current.releaseSurfaceOnCompletion = true
        stopOwner()
        return false
    }

    private fun stopOwner() {
        val current = owner ?: return
        if (current.stopping) return
        current.stopping = true
        shutdown(current.worker)
    }

    private fun startIfReady() {
        if (disposed || !attached || paused || owner != null) return
        val next = target ?: return
        val current = Owner(next)
        current.worker = createWorker(next.surface, next.width, next.height) {
            dispatchCompletion { released(current) }
        }
        owner = current
        try {
            current.worker.start()
        } catch (failure: Throwable) {
            owner = null
            throw failure
        }
    }

    @Synchronized
    private fun released(completed: Owner) {
        if (owner !== completed) return
        if (completed.releaseSurfaceOnCompletion) releaseSurface(completed.target.surface)
        owner = null
        if (completed.stopping) startIfReady()
    }
}

internal fun runRenderWorker(
    render: () -> Unit,
    release: () -> Unit,
    onReleased: () -> Unit,
    onFailure: (Throwable) -> Unit
) {
    var interrupted = false
    try {
        render()
    } catch (failure: InterruptedException) {
        interrupted = true
    } catch (failure: Throwable) {
        onFailure(failure)
    } finally {
        val interruptedBeforeRelease = Thread.interrupted()
        try {
            release()
            onReleased()
        } catch (failure: Throwable) {
            if (failure is InterruptedException) interrupted = true
            onFailure(failure)
        } finally {
            if (interrupted || interruptedBeforeRelease) Thread.currentThread().interrupt()
        }
    }
}

/**
 * Render-on-demand hand-off from the UI thread to the GL thread: frame and resize requests are
 * coalesced, the first frame is requested up front, and [shutdown] wakes a blocked [awaitFrame].
 */
internal class RenderRequests(width: Int, height: Int) {
    class Frame(val width: Int, val height: Int, val resized: Boolean)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var frameRequested = true
    private var resized = false
    private var width = width
    private var height = height

    @Volatile
    var running = true
        private set

    fun size(): Pair<Int, Int> = lock.withLock { width to height }

    fun requestFrame() = lock.withLock {
        frameRequested = true
        changed.signalAll()
    }

    fun requestResize(width: Int, height: Int) = lock.withLock {
        this.width = width
        this.height = height
        resized = true
        frameRequested = true
        changed.signalAll()
    }

    fun shutdown() {
        running = false
        lock.withLock { changed.signalAll() }
    }

    /** Blocks until a frame is requested; `null` once shut down. Interruption throws, as `Object.wait` did. */
    fun awaitFrame(): Frame? = lock.withLock {
        while (!frameRequested && running) changed.await()
        if (!running) return null
        frameRequested = false
        val frame = Frame(width, height, resized)
        resized = false
        frame
    }
}