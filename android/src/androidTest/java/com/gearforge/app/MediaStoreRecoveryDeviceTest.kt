package com.gearforge.app

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device verification of the MediaStore branch of [ExportManager.recoverIncompleteDownloads].
 *
 * The JVM suite configures these code paths with a mocked [android.content.ContentResolver];
 * this test runs the real MediaStore provider on the device and, as the row's owner, builds
 * the exact crash state the recovery exists for: a pending Downloads row whose write never
 * finished, so its DATE_MODIFIED is 0 and its DATE_ADDED carries the age. It also proves the
 * opposite direction: a fresh zero-modified row survives recovery untouched.
 *
 * API 29+ only (MediaStore pending rows do not exist below Q; the legacy path is covered by
 * the JVM suite and was device-verified with SIGKILL runs on the API 26 emulator).
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreRecoveryDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    @Suppress("DEPRECATION") // MediaStore.setIncludePending is deprecated but is what the production path uses
    private val collection = MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
    private val journalDir = File(context.noBackupFilesDir, "export-recovery")

    @Before
    fun cleanSlate() {
        assumeTrue("MediaStore pending rows require API 29+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        deleteOwnRows()
        journalDir.listFiles()?.forEach { it.delete() }
    }

    @After
    fun cleanup() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        deleteOwnRows()
        journalDir.listFiles()?.forEach { it.delete() }
    }

    private fun insertPending(name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "model/stl")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection, values)
            ?: throw AssertionError("MediaStore insert returned null for $name")
    }

    private fun matchingPendingRows(name: String): Int {
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1"
        return resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, arrayOf(name), null)
            ?.use { it.count } ?: 0
    }

    private fun deleteOwnRows() {
        resolver.delete(
            collection,
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("gearforge-export-%")
        )
    }

    /**
     * Creates the exact state a process kill leaves behind and verifies the device really
     * produced it: a pending row whose write never finished — IS_PENDING=1 and
     * DATE_MODIFIED=0. Measured on API 36 (2026-10-02) before writing the tests: a freshly
     * inserted pending row ALREADY carries DATE_MODIFIED=0, and MediaProvider rejects owner
     * updates to DATE_ADDED/DATE_MODIFIED (the row kept its natural stamps, 1790967070,0,1,
     * after an update to (1, 0)). Aging therefore goes through the recovery API's injectable
     * nowMillis — the same seam the JVM suite uses — instead of rewriting the row.
     */
    private fun stageUnpublishedPendingRow(name: String): Pair<Long, Long> {
        val uri = insertPending(name)
        return resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.IS_PENDING),
            null, null, null
        )?.use { cursor ->
            assertTrue("row must be readable right after insert", cursor.moveToFirst())
            val added = cursor.getLong(0)
            val modified = cursor.getLong(1)
            val pending = cursor.getInt(2)
            assertEquals("precondition: the row is pending", 1, pending)
            assertTrue("precondition: it has an insert stamp", added > 0)
            assertEquals("precondition: the write never finished, so DATE_MODIFIED is 0", 0L, modified)
            added to modified
        } ?: throw AssertionError("could not read the staged row back")
    }

    @Test
    fun zeroModifiedStalePendingRowIsReclaimedOnDevice() {
        val now = System.currentTimeMillis()
        val journal = ExportManager.RecoveryJournal(journalDir)
        // createdAt is caller-supplied on purpose: it is both the journal's age and what
        // recovery compares against, and backdating it here is the deterministic way to
        // reach the stale state without touching the device clock.
        val entry = journal.begin(
            ExportManager.PendingKind.MEDIA_STORE, ".stl",
            now - ExportManager.RECOVERY_AGE_MILLIS - 60_000L
        )
        // The kill scenario means the NEXT process runs the recovery: its active set is empty.
        // begin() marks the record as active in THIS process to protect an in-flight export,
        // so the marker must be released to model the restart truthfully.
        journal.release(entry)

        val (added, _) = stageUnpublishedPendingRow(entry.pendingName)
        assertTrue("precondition: the insert stamp is real", added > 0)

        // Age without touching the clock or the row: recovery takes "now" as a parameter, so
        // a future now makes the real insert stamp old enough, exactly as 24 hours of real
        // time would. The journal record's createdAt is similarly caller-supplied.
        val result = ExportManager.recoverIncompleteDownloads(
            context,
            now + ExportManager.RECOVERY_AGE_MILLIS + 120_000L
        )
        assertTrue("recovery must not fail: $result", result.isSuccess)
        assertEquals("exactly one stale entry must be reclaimed", 1, result.getOrThrow())
        assertEquals("the pending row must be gone", 0, matchingPendingRows(entry.pendingName))
        assertTrue(
            "the journal record must be gone",
            !File(journalDir, "${entry.id}.properties").exists()
        )
    }

    @Test
    fun freshZeroModifiedPendingRowSurvivesRecoveryOnDevice() {
        val journal = ExportManager.RecoveryJournal(journalDir)
        val entry = journal.begin(ExportManager.PendingKind.MEDIA_STORE, ".stl", System.currentTimeMillis())
        // Same restart model as above: recovery runs with an empty active set.
        journal.release(entry)

        stageUnpublishedPendingRow(entry.pendingName)

        val result = ExportManager.recoverIncompleteDownloads(context)
        assertTrue("recovery must not fail: $result", result.isSuccess)
        assertEquals("nothing may be recovered for a fresh entry", 0, result.getOrThrow())
        assertEquals("a fresh zero-modified row must NOT be touched", 1, matchingPendingRows(entry.pendingName))
        assertTrue(
            "a fresh journal record must remain",
            File(journalDir, "${entry.id}.properties").exists()
        )
    }
}
