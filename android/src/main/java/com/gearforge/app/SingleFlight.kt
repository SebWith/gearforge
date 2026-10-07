package com.gearforge.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.job

/**
 * Cache in which concurrent requests for one key share one computation, run in [scope] so a
 * cancelled caller stops waiting without discarding a running result. A computation whose last
 * caller leaves before it has started is dropped instead. `null` results are not kept.
 */
internal class SingleFlightCache<K : Any, V : Any>(private val scope: CoroutineScope) {
    private class Flight<V>(val result: Deferred<V?>, val started: AtomicBoolean) {
        var waiters = 0
    }

    private val values = ConcurrentHashMap<K, V>()
    private val running = HashMap<K, Flight<V>>()

    fun cached(key: K): V? = values[key]

    suspend fun get(key: K, compute: () -> V?): V? {
        val flight = synchronized(running) {
            values[key]?.let { return it }
            running.getOrPut(key) { startFlight(key, compute) }.also { it.waiters++ }
        }
        flight.result.start()
        try {
            return flight.result.await()
        } finally {
            val abandoned = synchronized(running) {
                val last = --flight.waiters == 0 && !flight.started.get() && running[key] === flight
                if (last) running.remove(key)
                last
            }
            if (abandoned) flight.result.cancel()
        }
    }

    private fun startFlight(key: K, compute: () -> V?): Flight<V> {
        val started = AtomicBoolean(false)
        val result = scope.async(start = CoroutineStart.LAZY) {
            started.set(true)
            try {
                compute()?.also { values[key] = it }
            } finally {
                forget(key, coroutineContext.job)
            }
        }
        // A flight cancelled before it ran never reaches its own finally.
        result.invokeOnCompletion { forget(key, result) }
        return Flight(result, started)
    }

    private fun forget(key: K, job: Job) {
        synchronized(running) {
            if (running[key]?.result === job) running.remove(key)
        }
    }
}
