package com.gearforge.app

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash reporting + basic log events (telemetry).
 *
 * The implementation is fully local: uncaught exceptions are written to Logcat and
 * appended to a crash-log file under the app's internal files directory, and
 * [logEvent] emits a timestamped diagnostic line. This keeps the signed release
 * buildable offline with no external Firebase project or `google-services.json`.
 *
 * Firebase Crashlytics (or any other backend) can later be attached through the
 * [Delegate] seam below without rearchitecting this layer: assign an implementation
 * to [firebaseDelegate] at startup and every local report is mirrored to the backend.
 * No Firebase Gradle plugins or dependencies are required here.
 */
object CrashReporting {

    private const val TAG = "GearForgeCrash"
    private const val CRASH_LOG = "crash.log"
    private const val EVENT_LOG = "events.log"
    private const val MAX_LOG_BYTES = 1_000_000L

    /**
     * Optional backend seam for crash reporting (e.g., Firebase Crashlytics).
     *
     * Leave null to run fully local. When set, [recordException] and [logEvent] are
     * forwarded in addition to the local Logcat/file handling.
     */
    interface Delegate {
        fun recordException(throwable: Throwable)
        fun logEvent(name: String, params: Map<String, String>)
    }

    /** Mirrors local reports to an external backend when assigned. Volatile for safe cross-thread reads. */
    @Volatile
    var firebaseDelegate: Delegate? = null

    @Volatile
    private var initialized = false

    private var crashFile: File? = null
    private var eventFile: File? = null

    /** Installs the uncaught-exception handler. Call once, early in [MainActivity.onCreate]. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        crashFile = File(context.filesDir, CRASH_LOG)
        eventFile = File(context.filesDir, EVENT_LOG)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handle(thread, throwable)
            // Preserve the platform default behaviour (process termination).
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Records a basic diagnostic event with optional string params. */
    fun logEvent(name: String, params: Map<String, String> = emptyMap()) {
        val line = buildString {
            append(timeStamp()).append(' ').append(name)
            if (params.isNotEmpty()) {
                append(' ')
                append(params.entries.joinToString(",") { "${it.key}=${it.value}" })
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, line)
        appendTo(eventFile, line)
        firebaseDelegate?.logEvent(name, params)
    }

    private fun handle(thread: Thread, throwable: Throwable) {
        val report = buildString {
            append("FATAL ").append(timeStamp())
                .append(" thread=").append(thread.name).append('\n')
            append(Log.getStackTraceString(throwable))
        }
        Log.e(TAG, report)
        appendTo(crashFile, report)
        firebaseDelegate?.recordException(throwable)
    }

    private fun appendTo(file: File?, text: String) {
        if (file == null) return
        try {
            if (file.length() > MAX_LOG_BYTES) file.writeText("")
            file.appendText(text + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to append to log file", e)
        }
    }

    private fun timeStamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date())
}
