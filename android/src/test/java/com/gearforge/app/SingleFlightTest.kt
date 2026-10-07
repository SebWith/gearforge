package com.gearforge.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFlightTest {
    private fun withCache(block: suspend (SingleFlightCache<String, String>) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking { withTimeout(10_000) { block(SingleFlightCache(scope)) } }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concurrentRequestsForOneKeyComputeOnce() = withCache { cache ->
        val computations = AtomicInteger()
        val entered = AtomicInteger()
        val gate = CountDownLatch(1)
        val results = coroutineScope {
            val requests = (1..8).map {
                async(Dispatchers.Default) {
                    entered.incrementAndGet()
                    cache.get("spur") {
                        computations.incrementAndGet()
                        gate.await(5, TimeUnit.SECONDS)
                        "bitmap"
                    }
                }
            }
            while (entered.get() < 8 || computations.get() == 0) delay(5)
            delay(50)
            gate.countDown()
            requests.awaitAll()
        }
        assertEquals(1, computations.get())
        assertEquals(List(8) { "bitmap" }, results)
        assertEquals("bitmap", cache.cached("spur"))
    }

    @Test
    fun completedValueIsServedWithoutRecomputing() = withCache { cache ->
        val computations = AtomicInteger()
        repeat(3) { cache.get("bevel") { computations.incrementAndGet(); "b" } }
        assertEquals(1, computations.get())
    }

    @Test
    fun distinctKeysComputeIndependently() = withCache { cache ->
        assertEquals("light", cache.get("spur:light") { "light" })
        assertEquals("dark", cache.get("spur:dark") { "dark" })
    }

    @Test
    fun nullResultIsNotKeptSoTheNextRequestRetries() = withCache { cache ->
        val computations = AtomicInteger()
        assertNull(cache.get("ring") { computations.incrementAndGet(); null })
        assertNull(cache.cached("ring"))
        assertEquals("r", cache.get("ring") { computations.incrementAndGet(); "r" })
        assertEquals(2, computations.get())
    }

    @Test
    fun cancelledCallerDoesNotCancelTheSharedComputation() = withCache { cache ->
        val computations = AtomicInteger()
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val compute: () -> String? = {
            computations.incrementAndGet()
            started.countDown()
            gate.await(5, TimeUnit.SECONDS)
            "worm"
        }
        coroutineScope {
            val leaving = launch(Dispatchers.Default) { cache.get("worm", compute) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            leaving.cancelAndJoin()
            val staying = async(Dispatchers.Default) { cache.get("worm", compute) }
            gate.countDown()
            assertEquals("worm", staying.await())
        }
        assertEquals(1, computations.get())
        assertEquals("worm", cache.cached("worm"))
    }

    @Test
    fun requestAbandonedBeforeItStartedIsNeverComputed() {
        val executor = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        val cache = SingleFlightCache<String, String>(scope)
        val busy = CountDownLatch(1)
        val computations = AtomicInteger()
        try {
            // The only render thread is occupied, so the request below is still queued when it is abandoned.
            scope.launch { busy.await(5, TimeUnit.SECONDS) }
            runBlocking {
                withTimeout(10_000) {
                    val leaving = launch(Dispatchers.Default) {
                        cache.get("preset") { computations.incrementAndGet(); "abandoned" }
                    }
                    delay(100)
                    leaving.cancelAndJoin()
                    busy.countDown()
                    assertEquals("fresh", cache.get("preset") { computations.incrementAndGet(); "fresh" })
                }
            }
            assertEquals(1, computations.get())
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
    }
}
