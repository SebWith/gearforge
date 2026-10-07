package com.gearforge.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderRequestsTest {
    @Test
    fun firstFrameIsRequestedUpFrontWithoutAResize() {
        val requests = RenderRequests(10, 20)
        val frame = requests.awaitFrame()
        assertNotNull(frame)
        assertFalse(frame!!.resized)
        assertEquals(10 to 20, requests.size())
    }

    @Test
    fun waiterBlocksUntilAFrameIsRequested() {
        val requests = RenderRequests(1, 1)
        requests.awaitFrame()
        val woke = CountDownLatch(1)
        val frames = AtomicReference<RenderRequests.Frame?>()
        val waiter = Thread {
            frames.set(requests.awaitFrame())
            woke.countDown()
        }
        waiter.start()
        assertFalse("render on demand: no frame without a request", woke.await(200, TimeUnit.MILLISECONDS))
        requests.requestFrame()
        assertTrue(woke.await(5, TimeUnit.SECONDS))
        waiter.join(5000L)
        assertNotNull(frames.get())
    }

    @Test
    fun requestsMadeBeforeOneWakeAreOneFrame() {
        val requests = RenderRequests(1, 1)
        requests.awaitFrame()
        requests.requestFrame()
        requests.requestFrame()
        assertNotNull(requests.awaitFrame())
        val again = CountDownLatch(1)
        val second = Thread { requests.awaitFrame(); again.countDown() }
        second.start()
        assertFalse("two requests before one wake are one frame", again.await(200, TimeUnit.MILLISECONDS))
        requests.shutdown()
        assertTrue(again.await(5, TimeUnit.SECONDS))
        second.join(5000L)
    }

    @Test
    fun resizeIsHandedOverOnceWithTheLatestSize() {
        val requests = RenderRequests(100, 200)
        requests.requestResize(300, 400)
        requests.requestResize(500, 600)
        val frame = requests.awaitFrame()!!
        assertTrue(frame.resized)
        assertEquals(500, frame.width)
        assertEquals(600, frame.height)
        requests.requestFrame()
        assertFalse(requests.awaitFrame()!!.resized)
    }

    @Test
    fun shutdownWakesABlockedWaiterAndEndsTheLoop() {
        val requests = RenderRequests(1, 1)
        requests.awaitFrame()
        val result = AtomicReference<Any?>("pending")
        val done = CountDownLatch(1)
        val waiter = Thread {
            result.set(requests.awaitFrame())
            done.countDown()
        }
        waiter.start()
        assertFalse(done.await(100, TimeUnit.MILLISECONDS))
        requests.shutdown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        waiter.join(5000L)
        assertNull(result.get())
        assertFalse(requests.running)
        requests.requestFrame()
        assertNull("a pending request after shutdown draws nothing", requests.awaitFrame())
    }

    @Test
    fun interruptWhileWaitingThrowsInterruptedExceptionLikeObjectWait() {
        val requests = RenderRequests(1, 1)
        requests.awaitFrame()
        val thrown = AtomicReference<Throwable?>()
        val waiter = Thread {
            try {
                requests.awaitFrame()
            } catch (t: Throwable) {
                thrown.set(t)
            }
        }
        waiter.start()
        Thread.sleep(100)
        waiter.interrupt()
        waiter.join(5000L)
        assertTrue(thrown.get() is InterruptedException)
    }
}
