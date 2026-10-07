package com.gearforge.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderThreadLifecycleTest {
    @Test
    fun stoppedWorkerStillOwnsRendererAfterJoinTimeout() = withHarness { harness ->
        val worker = harness.start()
        harness.lifecycle.pause()
        assertTrue(worker.releasing.await(5, TimeUnit.SECONDS))
        assertFalse(worker.finished.await(1100, TimeUnit.MILLISECONDS))
        assertTrue(worker.isAlive)
        harness.lifecycle.resume()
        assertEquals("A worker waiting for EGL release must retain ownership", 1, harness.workers.size)
        assertSame(worker, harness.lifecycle.worker)
        harness.finish(worker).invoke()
        assertEquals(2, harness.workers.size)
    }

    @Test
    fun pauseReturnsWithoutWaitingForWorkerCleanup() = withHarness { harness ->
        val worker = harness.start()
        val returned = CountDownLatch(1)
        val caller = Thread {
            harness.lifecycle.pause()
            returned.countDown()
        }
        caller.start()
        try {
            assertTrue("UI stop must not join the blocked worker", returned.await(500, TimeUnit.MILLISECONDS))
            assertTrue(worker.isAlive)
            assertEquals(1L, worker.allowRelease.count)
        } finally {
            worker.allowRelease.countDown()
            caller.join(5000L)
        }
    }

    @Test
    fun rapidPauseResumeAndSurfaceReplacementStartsOnlyLatestTarget() = withHarness { harness ->
        val worker = harness.start()
        repeat(100) {
            harness.lifecycle.pause()
            harness.lifecycle.resume()
        }
        val discarded = Any()
        val latest = Any()
        harness.lifecycle.surfaceAvailable(discarded, 10, 20)
        harness.lifecycle.surfaceAvailable(latest, 30, 40)
        assertTrue(harness.lifecycle.surfaceDestroyed(discarded))
        assertTrue(harness.lifecycle.surfaceSizeChanged(latest, 50, 60))
        assertFalse(harness.lifecycle.surfaceSizeChanged(discarded, 999, 999))
        assertEquals(1, worker.shutdownRequests.get())
        assertEquals(1, harness.workers.size)
        harness.finish(worker).invoke()
        val replacement = harness.workers.last()
        assertSame(latest, replacement.surface)
        assertEquals(50, replacement.width)
        assertEquals(60, replacement.height)
    }

    @Test
    fun ownedSurfaceReleaseIsDeferredUntilEglCleanupAndPrecedesRestart() = withHarness { harness ->
        val worker = harness.start()
        assertFalse(harness.lifecycle.surfaceDestroyed(worker.surface))
        val latest = Any()
        harness.lifecycle.surfaceAvailable(latest, 200, 300)
        assertTrue(harness.releasedSurfaces.isEmpty())
        val completion = harness.finish(worker)
        assertTrue(harness.releasedSurfaces.isEmpty())
        completion()
        assertEquals(listOf(worker.surface), harness.releasedSurfaces)
        assertEquals(listOf("start", "surfaceReleased", "start"), harness.events)
        assertSame(latest, harness.workers.last().surface)
    }

    @Test
    fun destroyingPendingSurfacePreventsStaleRestart() = withHarness { harness ->
        val worker = harness.start()
        harness.lifecycle.pause()
        harness.lifecycle.resume()
        val pending = Any()
        harness.lifecycle.surfaceAvailable(pending, 200, 300)
        assertTrue(harness.lifecycle.surfaceDestroyed(pending))
        assertFalse(harness.lifecycle.surfaceDestroyed(worker.surface))
        harness.finish(worker).invoke()
        assertEquals(1, harness.workers.size)
        assertNull(harness.lifecycle.worker)
        assertEquals(listOf(worker.surface), harness.releasedSurfaces)
    }

    @Test
    fun staleDestructionDoesNotStopReplacementSurface() = withHarness { harness ->
        val worker = harness.start()
        val latest = Any()
        harness.lifecycle.surfaceAvailable(latest, 200, 300)
        harness.finish(worker).invoke()
        val replacement = harness.workers.last()
        assertTrue(harness.lifecycle.surfaceDestroyed(worker.surface))
        assertSame(replacement, harness.lifecycle.worker)
        assertEquals(0, replacement.shutdownRequests.get())
    }

    @Test
    fun delayedCompletionRechecksPauseAndDuplicateCannotClearNewOwner() = withHarness { harness ->
        val worker = harness.start()
        harness.lifecycle.pause()
        harness.lifecycle.resume()
        val completion = harness.finish(worker)
        harness.lifecycle.pause()
        completion()
        assertEquals(1, harness.workers.size)
        assertNull(harness.lifecycle.worker)
        harness.lifecycle.resume()
        val replacement = harness.workers.last()
        completion()
        assertSame(replacement, harness.lifecycle.worker)
        assertEquals(2, harness.workers.size)
    }

    @Test
    fun detachedViewDoesNotRestartFromLateCompletionOrResume() = withHarness { harness ->
        val worker = harness.start()
        harness.lifecycle.pause()
        harness.lifecycle.resume()
        val completion = harness.finish(worker)
        harness.lifecycle.detach()
        harness.lifecycle.resume()
        completion()
        assertEquals(1, harness.workers.size)
        assertNull(harness.lifecycle.worker)
    }

    @Test
    fun reattachedViewWaitsForOldOwnerAndUsesNewSurface() = withHarness { harness ->
        val worker = harness.start()
        harness.lifecycle.detach()
        harness.lifecycle.attach()
        harness.lifecycle.resume()
        assertFalse(harness.lifecycle.surfaceDestroyed(worker.surface))
        val latest = Any()
        harness.lifecycle.surfaceAvailable(latest, 200, 300)
        assertEquals(1, harness.workers.size)
        harness.finish(worker).invoke()
        assertSame(latest, harness.workers.last().surface)
    }

    @Test
    fun disposedCoordinatorRejectsAllLateRestartsButReleasesOwnedSurface() = withHarness { harness ->
        val worker = harness.start()
        harness.lifecycle.pause()
        harness.lifecycle.resume()
        val completion = harness.finish(worker)
        harness.lifecycle.dispose()
        assertFalse(harness.lifecycle.surfaceDestroyed(worker.surface))
        harness.lifecycle.attach()
        harness.lifecycle.surfaceAvailable(Any(), 200, 300)
        harness.lifecycle.resume()
        completion()
        completion()
        assertNull(harness.lifecycle.worker)
        assertEquals(1, harness.workers.size)
        assertEquals(listOf(worker.surface), harness.releasedSurfaces)
    }

    @Test
    fun surfaceAvailableWhilePausedWaitsForResume() = withHarness { harness ->
        harness.lifecycle.pause()
        harness.lifecycle.surfaceAvailable(Any(), 100, 100)
        assertTrue(harness.workers.isEmpty())
        harness.lifecycle.resume()
        assertEquals(1, harness.workers.size)
    }

    @Test
    fun interruptedUiStopPreservesInterruptAndOwner() = withHarness { harness ->
        val worker = harness.start()
        Thread.currentThread().interrupt()
        try {
            harness.lifecycle.pause()
            harness.lifecycle.resume()
            assertTrue(Thread.currentThread().isInterrupted)
            assertSame(worker, harness.lifecycle.worker)
            assertEquals(1, harness.workers.size)
        } finally {
            Thread.interrupted()
        }
        harness.finish(worker).invoke()
        assertEquals(2, harness.workers.size)
    }

    @Test
    fun interruptedWorkerStillCleansUpBeforeCompletionAndPreservesInterrupt() = withHarness { harness ->
        val worker = harness.start()
        worker.interrupt()
        assertTrue(worker.releasing.await(5, TimeUnit.SECONDS))
        assertSame(worker, harness.lifecycle.worker)
        assertTrue(harness.completions.isEmpty())
        harness.finish(worker).invoke()
        assertTrue(worker.isInterrupted)
        assertTrue(harness.failures.isEmpty())
        assertNull(harness.lifecycle.worker)
    }

    @Test
    fun renderFailureCleansUpWithoutAutomaticFailureLoop() = withHarness { harness ->
        val worker = harness.start()
        val failure = IllegalStateException("render failure")
        worker.renderFailure = failure
        worker.stopRequested.countDown()
        val completion = harness.finish(worker)
        assertSame(failure, harness.failures.single())
        assertSame(worker, harness.lifecycle.worker)
        completion()
        assertNull(harness.lifecycle.worker)
        assertEquals(1, harness.workers.size)
        harness.lifecycle.resume()
        assertEquals(2, harness.workers.size)
    }

    @Test
    fun cleanupFailureRetainsOwnershipAndPreventsReplacement() = withHarness { harness ->
        val worker = harness.start()
        val failure = IllegalStateException("EGL release failure")
        worker.releaseFailure = failure
        harness.lifecycle.pause()
        harness.lifecycle.resume()
        worker.allowRelease.countDown()
        worker.join(5000L)
        assertFalse(worker.isAlive)
        assertSame(failure, harness.failures.single())
        assertTrue(harness.completions.isEmpty())
        assertSame(worker, harness.lifecycle.worker)
        harness.lifecycle.resume()
        assertEquals(1, harness.workers.size)
    }

    @Test
    fun failedThreadStartDoesNotReserveOwner() {
        val lifecycle = RenderThreadLifecycle<Any, Thread>(
            createWorker = { _, _, _, _ ->
                object : Thread() {
                    override fun start() { throw IllegalStateException("start failure") }
                }
            },
            shutdown = { },
            dispatchCompletion = { it() },
            releaseSurface = { }
        )
        lifecycle.attach()
        var failure: Throwable? = null
        try {
            lifecycle.surfaceAvailable(Any(), 100, 100)
        } catch (caught: IllegalStateException) {
            failure = caught
        }
        assertEquals("start failure", failure?.message)
        assertNull(lifecycle.worker)
    }

    private fun withHarness(block: (Harness) -> Unit) {
        val harness = Harness()
        try {
            block(harness)
        } finally {
            harness.close()
        }
    }

    private class Harness {
        val completions = LinkedBlockingQueue<() -> Unit>()
        val failures = LinkedBlockingQueue<Throwable>()
        val workers = mutableListOf<ControlledWorker>()
        val releasedSurfaces = mutableListOf<Any>()
        val events = mutableListOf<String>()
        val activeOwners = AtomicInteger()
        val peakOwners = AtomicInteger()
        val lifecycle = RenderThreadLifecycle<Any, ControlledWorker>(
            createWorker = { surface, width, height, released ->
                events.add("start")
                ControlledWorker(surface, width, height, this, released).also { workers.add(it) }
            },
            shutdown = {
                it.shutdownRequests.incrementAndGet()
                it.stopRequested.countDown()
            },
            dispatchCompletion = { completions.add(it) },
            releaseSurface = {
                releasedSurfaces.add(it)
                events.add("surfaceReleased")
            }
        )

        init { lifecycle.attach() }

        fun start(): ControlledWorker {
            lifecycle.surfaceAvailable(Any(), 100, 100)
            return workers.single().also { assertTrue(it.entered.await(5, TimeUnit.SECONDS)) }
        }

        fun finish(worker: ControlledWorker): () -> Unit {
            worker.allowRelease.countDown()
            val completion = requireNotNull(completions.poll(5, TimeUnit.SECONDS))
            worker.join(5000L)
            assertFalse(worker.isAlive)
            return completion
        }

        fun close() {
            lifecycle.dispose()
            workers.forEach {
                it.stopRequested.countDown()
                it.allowRelease.countDown()
                it.join(5000L)
                assertFalse("Test worker must terminate", it.isAlive)
            }
            assertTrue("Shared renderer owners must never overlap", peakOwners.get() <= 1)
        }
    }

    private class ControlledWorker(
        val surface: Any,
        val width: Int,
        val height: Int,
        private val harness: Harness,
        private val released: () -> Unit
    ) : Thread("TestGearGLRenderer") {
        val entered = CountDownLatch(1)
        val stopRequested = CountDownLatch(1)
        val releasing = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val shutdownRequests = AtomicInteger()
        @Volatile var renderFailure: Throwable? = null
        @Volatile var releaseFailure: Throwable? = null

        init { isDaemon = true }

        override fun run() {
            try {
                runRenderWorker(
                    render = {
                        val owners = harness.activeOwners.incrementAndGet()
                        harness.peakOwners.updateAndGet { maxOf(it, owners) }
                        entered.countDown()
                        stopRequested.await()
                        renderFailure?.let { throw it }
                    },
                    release = {
                        releasing.countDown()
                        allowRelease.await()
                        releaseFailure?.let { throw it }
                        harness.activeOwners.decrementAndGet()
                    },
                    onReleased = released,
                    onFailure = { harness.failures.add(it) }
                )
            } finally {
                finished.countDown()
            }
        }
    }
}