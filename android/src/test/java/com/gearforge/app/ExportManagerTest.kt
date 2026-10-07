package com.gearforge.app

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ExportManagerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun shareContext(): Context = mock(Context::class.java).also { context ->
        `when`(context.filesDir).thenReturn(temporaryFolder.newFolder())
        `when`(context.cacheDir).thenReturn(temporaryFolder.newFolder())
    }

    private val recoveryNow = 2_000_000_000_000L
    private val ownerPackage = "com.gearforge.geargenerator"

    private fun abandonedMediaEntry(journal: ExportManager.RecoveryJournal): ExportManager.PendingExport =
        journal.begin(ExportManager.PendingKind.MEDIA_STORE, ".stl", 1000).also(journal::release)

    private fun pendingCursor(
        entry: ExportManager.PendingExport,
        name: String = entry.pendingName,
        owner: String = ownerPackage,
        path: String = "${Environment.DIRECTORY_DOWNLOADS}/",
        pending: Int = 1,
        added: Long = 1,
        modified: Long = 1
    ): Cursor = mock(Cursor::class.java).also { cursor ->
        val columns = listOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED
        )
        `when`(cursor.getColumnIndexOrThrow(anyString())).thenAnswer { columns.indexOf(it.getArgument<String>(0)) }
        `when`(cursor.moveToFirst()).thenReturn(true)
        `when`(cursor.getLong(0)).thenReturn(42)
        `when`(cursor.getString(1)).thenReturn(name)
        `when`(cursor.getString(2)).thenReturn(owner)
        `when`(cursor.getString(3)).thenReturn(path)
        `when`(cursor.getInt(4)).thenReturn(pending)
        `when`(cursor.getLong(5)).thenReturn(added)
        `when`(cursor.getLong(6)).thenReturn(modified)
    }

    private fun queryReturns(resolver: ContentResolver, collection: Uri, cursor: Cursor?) {
        `when`(resolver.query(eq(collection), any(Array<String>::class.java), anyString(), any(Array<String>::class.java), isNull()))
            .thenReturn(cursor)
    }

    @Test
    fun stalePendingRecoveryUsesExactOwnerAndRechecksStateOnDelete() {
        val journal = ExportManager.RecoveryJournal(temporaryFolder.newFolder())
        val entry = abandonedMediaEntry(journal)
        val resolver = mock(ContentResolver::class.java)
        val collection = mock(Uri::class.java)
        val item = mock(Uri::class.java)
        val cursor = pendingCursor(entry)
        queryReturns(resolver, collection, cursor)
        `when`(resolver.delete(eq(item), anyString(), any(Array<String>::class.java))).thenAnswer { invocation ->
            val selection = invocation.getArgument<String>(1)
            listOf("_display_name=?", "owner_package_name=?", "relative_path=?", "is_pending=1", "date_added=?", "date_modified=?")
                .forEach { assertTrue(selection.contains(it)) }
            assertEquals(listOf(entry.pendingName, ownerPackage, "${Environment.DIRECTORY_DOWNLOADS}/", "1", "1"), invocation.getArgument<Array<String>>(2).toList())
            1
        }
        mockStatic(ContentUris::class.java).use { uris ->
            uris.`when`<Uri> { ContentUris.withAppendedId(collection, 42) }.thenReturn(item)
            assertEquals(1, journal.recover(recoveryNow) {
                ExportManager.recoverPendingDownload(resolver, collection, ownerPackage, it, recoveryNow)
            }.getOrThrow())
        }
        verify(cursor).close()
        verify(resolver).delete(eq(item), anyString(), any(Array<String>::class.java))
    }

    @Test
    fun recoveryNeverDeletesForeignPublishedFreshFutureOrAmbiguousRows() {
        val journal = ExportManager.RecoveryJournal(temporaryFolder.newFolder())
        val entry = abandonedMediaEntry(journal)
        val resolver = mock(ContentResolver::class.java)
        val collection = mock(Uri::class.java)
        val cutoff = (recoveryNow - ExportManager.RECOVERY_AGE_MILLIS) / 1000
        val cursors = listOf(
            pendingCursor(entry, owner = "other.app"),
            pendingCursor(entry, name = "unrelated.stl"),
            pendingCursor(entry, path = "Documents/"),
            pendingCursor(entry, pending = 0),
            pendingCursor(entry, added = cutoff),
            pendingCursor(entry, modified = cutoff),
            pendingCursor(entry, added = recoveryNow / 1000 + 1),
            pendingCursor(entry, modified = 0, added = cutoff),
            pendingCursor(entry).also { `when`(it.moveToNext()).thenReturn(true) }
        )
        cursors.forEach { cursor ->
            queryReturns(resolver, collection, cursor)
            assertEquals(0, journal.recover(recoveryNow) {
                ExportManager.recoverPendingDownload(resolver, collection, ownerPackage, it, recoveryNow)
            }.getOrThrow())
            verify(cursor).close()
        }
        verify(resolver, never()).delete(any(), any(), any())
    }

    @Test
    fun nullAndDeniedQueriesKeepTheJournalForRetry() {
        val directory = temporaryFolder.newFolder()
        val journal = ExportManager.RecoveryJournal(directory)
        abandonedMediaEntry(journal)
        val resolver = mock(ContentResolver::class.java)
        val collection = mock(Uri::class.java)
        queryReturns(resolver, collection, null)
        fun recover() = journal.recover(recoveryNow) {
            ExportManager.recoverPendingDownload(resolver, collection, ownerPackage, it, recoveryNow)
        }
        assertEquals(0, recover().getOrThrow())
        `when`(resolver.query(eq(collection), any(Array<String>::class.java), anyString(), any(Array<String>::class.java), isNull()))
            .thenThrow(SecurityException("denied"))
        assertTrue(recover().exceptionOrNull() is SecurityException)
        assertEquals(1, directory.listFiles()!!.size)
        verify(resolver, never()).delete(any(), any(), any())
    }

    @Test
    fun deleteFailureOrPublicationRaceKeepsJournalAndClosesCursor() {
        listOf(false, true).forEach { denied ->
            val directory = temporaryFolder.newFolder()
            val journal = ExportManager.RecoveryJournal(directory)
            val entry = abandonedMediaEntry(journal)
            val resolver = mock(ContentResolver::class.java)
            val collection = mock(Uri::class.java)
            val item = mock(Uri::class.java)
            val cursor = pendingCursor(entry)
            queryReturns(resolver, collection, cursor)
            if (denied) `when`(resolver.delete(eq(item), anyString(), any(Array<String>::class.java)))
                .thenThrow(SecurityException("denied"))
            mockStatic(ContentUris::class.java).use { uris ->
                uris.`when`<Uri> { ContentUris.withAppendedId(collection, 42) }.thenReturn(item)
                val result = journal.recover(recoveryNow) {
                    ExportManager.recoverPendingDownload(resolver, collection, ownerPackage, it, recoveryNow)
                }
                if (denied) assertTrue(result.isFailure) else assertEquals(0, result.getOrThrow())
            }
            assertEquals(1, directory.listFiles()!!.size)
            verify(cursor).close()
        }
    }

    @Test
    fun zeroModifiedPendingRowIsReclaimedWhenItsAddedStampIsStale() {
        // The most common crash window: the row was inserted but the write never happened, so
        // DATE_MODIFIED is still 0. Age must then be decided by DATE_ADDED, and the delete must
        // not require a DATE_MODIFIED match.
        val journal = ExportManager.RecoveryJournal(temporaryFolder.newFolder())
        val entry = abandonedMediaEntry(journal)
        val resolver = mock(ContentResolver::class.java)
        val collection = mock(Uri::class.java)
        val item = mock(Uri::class.java)
        val cursor = pendingCursor(entry, added = 1, modified = 0)
        queryReturns(resolver, collection, cursor)
        `when`(resolver.delete(eq(item), anyString(), any(Array<String>::class.java))).thenAnswer { invocation ->
            val selection = invocation.getArgument<String>(1)
            listOf("_display_name=?", "owner_package_name=?", "relative_path=?", "is_pending=1", "date_added=?")
                .forEach { assertTrue(selection.contains(it)) }
            assertFalse(selection.contains("date_modified=?"))
            assertEquals(
                listOf(entry.pendingName, ownerPackage, "${Environment.DIRECTORY_DOWNLOADS}/", "1"),
                invocation.getArgument<Array<String>>(2).toList()
            )
            1
        }
        mockStatic(ContentUris::class.java).use { uris ->
            uris.`when`<Uri> { ContentUris.withAppendedId(collection, 42) }.thenReturn(item)
            assertEquals(1, journal.recover(recoveryNow) {
                ExportManager.recoverPendingDownload(resolver, collection, ownerPackage, it, recoveryNow)
            }.getOrThrow())
        }
        verify(cursor).close()
        verify(resolver).delete(eq(item), anyString(), any(Array<String>::class.java))
    }

    @Test
    fun absentPendingRowRetiresJournalWithoutDeletingPublishedDownload() {
        val journal = ExportManager.RecoveryJournal(temporaryFolder.newFolder())
        abandonedMediaEntry(journal)
        val resolver = mock(ContentResolver::class.java)
        val collection = mock(Uri::class.java)
        val cursor = mock(Cursor::class.java)
        queryReturns(resolver, collection, cursor)
        assertEquals(1, journal.recover(recoveryNow) {
            ExportManager.recoverPendingDownload(resolver, collection, ownerPackage, it, recoveryNow)
        }.getOrThrow())
        verify(resolver, never()).delete(any(), any(), any())
        verify(cursor).close()
    }

    @Test
    fun mediaWriteJournalsBeforeInsertAndRenamesOnlyAtPublication() {
        val directory = temporaryFolder.newFolder()
        val journal = ExportManager.RecoveryJournal(directory)
        val resolver = mock(ContentResolver::class.java)
        val collection = mock(Uri::class.java)
        val item = mock(Uri::class.java)
        var marker = ""
        `when`(resolver.insert(eq(collection), any(ContentValues::class.java))).thenAnswer {
            marker = "gearforge-export-${directory.listFiles()!!.single().name.removeSuffix(".properties")}.stl"
            assertEquals(0, ExportManager.RecoveryJournal(directory).recover(recoveryNow) {
                error("Active insertion must not be recovered")
            }.getOrThrow())
            item
        }
        `when`(resolver.openOutputStream(item)).thenReturn(ByteArrayOutputStream())
        `when`(resolver.update(eq(item), any(ContentValues::class.java), isNull(), isNull())).thenReturn(1)
        mockConstruction(ContentValues::class.java).use { values ->
            assertTrue(ExportManager.writeMediaStoreDownload(
                resolver, collection, journal, "gear.stl", byteArrayOf(1), ".stl", "model/stl", 1000
            ).isSuccess)
            verify(values.constructed()[0]).put(MediaStore.MediaColumns.DISPLAY_NAME, marker)
            verify(values.constructed()[0]).put(MediaStore.MediaColumns.IS_PENDING, 1)
            verify(values.constructed()[1]).put(MediaStore.MediaColumns.DISPLAY_NAME, "gear.stl")
            verify(values.constructed()[1]).put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        assertTrue(directory.listFiles()!!.isEmpty())
        verify(resolver, never()).delete(any(), any(), any())
    }

    @Test
    fun insertWithoutReturnedUriRemainsRecoverableFromJournalMarker() {
        val directory = temporaryFolder.newFolder()
        val journal = ExportManager.RecoveryJournal(directory)
        val resolver = mock(ContentResolver::class.java)
        val collection = mock(Uri::class.java)
        val item = mock(Uri::class.java)
        `when`(resolver.insert(eq(collection), any(ContentValues::class.java))).thenThrow(IllegalStateException("lost insert response"))
        mockConstruction(ContentValues::class.java).use { values ->
            assertTrue(ExportManager.writeMediaStoreDownload(
                resolver, collection, journal, "gear.stl", byteArrayOf(1), ".stl", "model/stl", 1000
            ).isFailure)
            mockStatic(ContentUris::class.java).use { uris ->
                uris.`when`<Uri> { ContentUris.withAppendedId(collection, 42) }.thenReturn(item)
                `when`(resolver.delete(eq(item), anyString(), any(Array<String>::class.java))).thenReturn(1)
                assertEquals(1, ExportManager.RecoveryJournal(directory).recover(recoveryNow) { entry ->
                    verify(values.constructed().single()).put(MediaStore.MediaColumns.DISPLAY_NAME, entry.pendingName)
                    assertEquals(ExportManager.PendingKind.MEDIA_STORE, entry.kind)
                    queryReturns(resolver, collection, pendingCursor(entry))
                    ExportManager.recoverPendingDownload(resolver, collection, ownerPackage, entry, recoveryNow)
                }.getOrThrow())
            }
        }
        verify(resolver).delete(eq(item), anyString(), any(Array<String>::class.java))
    }

    @Test
    fun rollbackExceptionPreservesOriginalFailureAndRecoveryJournal() {
        val directory = temporaryFolder.newFolder()
        val journal = ExportManager.RecoveryJournal(directory)
        val resolver = mock(ContentResolver::class.java)
        val collection = mock(Uri::class.java)
        val item = mock(Uri::class.java)
        val writeFailure = FileNotFoundException("cannot open")
        val deleteFailure = SecurityException("cannot delete")
        `when`(resolver.insert(eq(collection), any(ContentValues::class.java))).thenReturn(item)
        `when`(resolver.openOutputStream(item)).thenThrow(writeFailure)
        `when`(resolver.delete(item, null, null)).thenThrow(deleteFailure)
        mockConstruction(ContentValues::class.java).use {
            val result = ExportManager.writeMediaStoreDownload(
                resolver, collection, journal, "gear.stl", byteArrayOf(1), ".stl", "model/stl", 1000
            )
            assertSame(writeFailure, result.exceptionOrNull())
            assertEquals(listOf(deleteFailure), writeFailure.suppressed.toList())
        }
        assertEquals(1, directory.listFiles()!!.size)
        assertEquals(1, ExportManager.RecoveryJournal(directory).recover(recoveryNow) { true }.getOrThrow())
    }

    @Test
    fun rollbackForgetsOnlyConfirmedDeletion() {
        listOf(0, 1).forEach { deleted ->
            val directory = temporaryFolder.newFolder()
            val journal = ExportManager.RecoveryJournal(directory)
            val resolver = mock(ContentResolver::class.java)
            val collection = mock(Uri::class.java)
            val item = mock(Uri::class.java)
            `when`(resolver.insert(eq(collection), any(ContentValues::class.java))).thenReturn(item)
            `when`(resolver.openOutputStream(item)).thenThrow(FileNotFoundException("full"))
            `when`(resolver.delete(item, null, null)).thenReturn(deleted)
            mockConstruction(ContentValues::class.java).use {
                assertTrue(ExportManager.writeMediaStoreDownload(
                    resolver, collection, journal, "gear.stl", byteArrayOf(1), ".stl", "model/stl", 1000
                ).isFailure)
            }
            assertEquals(1 - deleted, directory.listFiles()!!.size)
        }
    }

    @Test
    fun newSharePreservesEarlierBytesAndUsesDistinctFile() {
        val context = shareContext()
        val first = ExportManager.createShareCopy(context, "gear", byteArrayOf(1), ".stl")
        val second = ExportManager.createShareCopy(context, "gear", byteArrayOf(2), ".stl")
        assertTrue(first != second)
        assertEquals(listOf<Byte>(1), first.readBytes().toList())
        assertEquals(listOf<Byte>(2), second.readBytes().toList())
        assertTrue(!second.name.contains("..stl"))
    }

    @Test
    fun sharesAreNotStoredInEvictableCache() {
        val context = shareContext()
        val share = ExportManager.createShareCopy(context, "gear", byteArrayOf(1), ".stl")
        assertEquals(File(context.filesDir, "exports"), share.parentFile)
    }

    @Test
    fun cleanupExpiresOnlySharesOlderThanRetention() {
        val context = shareContext()
        val now = System.currentTimeMillis()
        val expired = ExportManager.createShareCopy(context, "old", byteArrayOf(1), ".stl", now)
        assertTrue(expired.setLastModified(now - ExportManager.SHARE_RETENTION_MILLIS - 1000))
        val retained = ExportManager.createShareCopy(context, "recent", byteArrayOf(2), ".stl", now)
        assertTrue(retained.setLastModified(now - ExportManager.SHARE_RETENTION_MILLIS + 1000))
        val future = ExportManager.createShareCopy(context, "future", byteArrayOf(3), ".stl", now)
        assertTrue(future.setLastModified(now + 60_000))
        ExportManager.createShareCopy(context, "new", byteArrayOf(4), ".stl", now)
        assertTrue(!expired.exists())
        assertEquals(listOf<Byte>(2), retained.readBytes().toList())
        assertEquals(listOf<Byte>(3), future.readBytes().toList())
    }

    @Test
    fun journalPathAliasesShareActiveProtectionAndRecoverAfterRelease() {
        val directory = temporaryFolder.newFolder()
        val writer = ExportManager.RecoveryJournal(directory)
        val recovery = ExportManager.RecoveryJournal(File(directory, "."))
        val entry = writer.begin(ExportManager.PendingKind.LEGACY, ".stl", 1000)
        try {
            assertEquals(0, recovery.recover(recoveryNow) { error("Active alias was not protected") }.getOrThrow())
        } finally {
            writer.release(entry)
        }
        assertEquals(1, recovery.recover(recoveryNow) { true }.getOrThrow())
    }

    @Test
    fun inaccessibleLegacyStorageKeepsItsJournalForLaterRecovery() {
        val journalDirectory = temporaryFolder.newFolder()
        val journal = ExportManager.RecoveryJournal(journalDirectory)
        journal.release(journal.begin(ExportManager.PendingKind.LEGACY, ".stl", 1000))
        val unavailable = File(temporaryFolder.root, "unmounted-downloads")
        assertEquals(0, journal.recover(recoveryNow) {
            ExportManager.recoverLegacyDownload(it, unavailable, recoveryNow)
        }.getOrThrow())
        assertEquals(1, journalDirectory.listFiles()!!.size)
    }

    @Test
    fun startupRecoveryReportsJournalAccessFailureInsteadOfThrowing() {
        val context = mock(Context::class.java)
        val failure = SecurityException("private storage denied")
        `when`(context.noBackupFilesDir).thenThrow(failure)
        assertSame(failure, ExportManager.recoverIncompleteDownloads(context, recoveryNow).exceptionOrNull())
    }

    @Test
    fun startupRecoveryDoesNotCreateAnUnusedJournal() {
        val context = mock(Context::class.java)
        val directory = temporaryFolder.newFolder()
        `when`(context.noBackupFilesDir).thenReturn(directory)
        assertEquals(0, ExportManager.recoverIncompleteDownloads(context, recoveryNow).getOrThrow())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun restartRecoversOnlyJournaledStaleLegacyTemporaryFile() {
        val journalDirectory = temporaryFolder.newFolder()
        val downloads = temporaryFolder.newFolder()
        val journal = ExportManager.RecoveryJournal(journalDirectory)
        val now = 2_000_000_000_000L
        val stale = now - ExportManager.RECOVERY_AGE_MILLIS - 1000
        val entry = journal.begin(ExportManager.PendingKind.LEGACY, ".stl", stale)
        val temporary = File(downloads, entry.temporaryName).apply { writeText("partial") }
        assertTrue(temporary.setLastModified(stale))
        journal.release(entry)
        val foreign = File(downloads, "export-123.tmp").apply { writeText("foreign") }
        val unjournaled = File(downloads, "gearforge-export-untracked.tmp").apply { writeText("unknown") }
        val successful = File(downloads, "gear.stl").apply { writeText("complete") }
        val restarted = ExportManager.RecoveryJournal(journalDirectory)

        assertEquals(1, restarted.recover(now) {
            ExportManager.recoverLegacyDownload(it, downloads, now)
        }.getOrThrow())
        assertTrue(!temporary.exists())
        assertEquals("foreign", foreign.readText())
        assertEquals("unknown", unjournaled.readText())
        assertEquals("complete", successful.readText())
        assertTrue(journalDirectory.listFiles()!!.isEmpty())
    }

    @Test
    fun staleUnreadableJournalRecordsAreReclaimedWhileFreshOnesAreKept() {
        val directory = temporaryFolder.newFolder()
        val journal = ExportManager.RecoveryJournal(directory)
        val staleEmpty = File(directory, UUID.randomUUID().toString() + ".properties").apply { createNewFile() }
        val stalePartial = File(directory, UUID.randomUUID().toString() + ".properties").apply { writeText("versio") }
        val stalePart = File(directory, UUID.randomUUID().toString() + ".properties.part").apply { createNewFile() }
        val freshEmpty = File(directory, UUID.randomUUID().toString() + ".properties").apply { createNewFile() }
        val stale = recoveryNow - ExportManager.RECOVERY_AGE_MILLIS - 60_000
        assertTrue(staleEmpty.setLastModified(stale))
        assertTrue(stalePartial.setLastModified(stale))
        assertTrue(stalePart.setLastModified(stale))
        assertTrue(freshEmpty.setLastModified(recoveryNow - 1000))

        assertEquals(0, journal.recover(recoveryNow) { false }.getOrThrow())

        assertFalse(staleEmpty.exists())
        assertFalse(stalePartial.exists())
        assertFalse(stalePart.exists())
        assertTrue(freshEmpty.exists())
    }

    @Test
    fun journalBeginCommitsExactlyOneReadableRecordWithoutPartialLeftover() {
        val directory = temporaryFolder.newFolder()
        val journal = ExportManager.RecoveryJournal(directory)
        val entry = journal.begin(ExportManager.PendingKind.LEGACY, ".stl", recoveryNow - 1000)
        journal.release(entry)
        val records = directory.listFiles()!!
        assertEquals(listOf(entry.id + ".properties"), records.map { it.name })
        assertTrue(records.single().length() > 0)
    }

    @Test
    fun recoveryPreservesFreshBoundaryFutureAndActiveJournalEntries() {
        val journal = ExportManager.RecoveryJournal(temporaryFolder.newFolder())
        val now = 2_000_000_000_000L
        val boundary = now - ExportManager.RECOVERY_AGE_MILLIS
        listOf(now, boundary, now + 1000).forEach { timestamp ->
            journal.release(journal.begin(ExportManager.PendingKind.LEGACY, ".stl", timestamp))
        }
        val active = journal.begin(ExportManager.PendingKind.LEGACY, ".stl", boundary - 1000)
        try {
            assertEquals(0, journal.recover(now) { error("Protected entry reached cleanup") }.getOrThrow())
        } finally {
            journal.release(active)
        }
        assertEquals(1, journal.recover(now) { it == active }.getOrThrow())
    }

    @Test
    fun staleJournalDoesNotDeleteFreshOrFutureLegacyFile() {
        val journalDirectory = temporaryFolder.newFolder()
        val journal = ExportManager.RecoveryJournal(journalDirectory)
        val downloads = temporaryFolder.newFolder()
        val now = 2_000_000_000_000L
        val stale = now - ExportManager.RECOVERY_AGE_MILLIS - 1000
        val files = listOf(now, now + 1000, now - ExportManager.RECOVERY_AGE_MILLIS).map { modified ->
            val entry = journal.begin(ExportManager.PendingKind.LEGACY, ".stl", stale)
            journal.release(entry)
            File(downloads, entry.temporaryName).apply {
                writeText("keep")
                assertTrue(setLastModified(modified))
            }
        }
        assertEquals(0, journal.recover(now) {
            ExportManager.recoverLegacyDownload(it, downloads, now)
        }.getOrThrow())
        assertTrue(files.all { it.readText() == "keep" })
        assertEquals(3, journalDirectory.listFiles()!!.size)
    }

    @Test
    fun recoveryRetriesUnresolvedAndDeniedCleanupWithoutLosingJournal() {
        val directory = temporaryFolder.newFolder()
        val journal = ExportManager.RecoveryJournal(directory)
        val now = 2_000_000_000_000L
        val entry = journal.begin(ExportManager.PendingKind.LEGACY, ".stl", now - ExportManager.RECOVERY_AGE_MILLIS - 1)
        journal.release(entry)
        assertEquals(0, journal.recover(now) { false }.getOrThrow())
        assertEquals(1, directory.listFiles()!!.size)
        assertTrue(journal.recover(now) { throw SecurityException("denied") }.isFailure)
        assertEquals(1, directory.listFiles()!!.size)
        assertEquals(1, ExportManager.RecoveryJournal(directory).recover(now) { true }.getOrThrow())
    }

    @Test
    fun journalIsBoundedAndNeverAllocatesAnUntrackedOverflowWrite() {
        val directory = temporaryFolder.newFolder()
        val journal = ExportManager.RecoveryJournal(directory)
        val now = 2_000_000_000_000L
        repeat(ExportManager.MAX_RECOVERY_ENTRIES) {
            journal.release(journal.begin(ExportManager.PendingKind.LEGACY, ".stl", 1000))
        }
        val downloads = temporaryFolder.newFolder()
        assertTrue(runCatching {
            ExportManager.writeLegacyDownload(journal, downloads, "gear.stl", byteArrayOf(1), ".stl", now)
        }.exceptionOrNull() is IOException)
        assertTrue(downloads.listFiles()!!.isEmpty())
        var visits = 0
        assertEquals(ExportManager.MAX_RECOVERY_ENTRIES, journal.recover(now) { visits++; true }.getOrThrow())
        assertEquals(ExportManager.MAX_RECOVERY_ENTRIES, visits)
    }

    @Test
    fun malformedJournalCannotAuthorizeDeletion() {
        val directory = temporaryFolder.newFolder()
        File(directory, "not-an-id.properties").writeText("version=1\nkind=LEGACY\ncreatedAt=1\nextension=.stl")
        File(directory, "00000000-0000-0000-0000-000000000000.properties").writeText("version=1\nkind=")
        assertEquals(0, ExportManager.RecoveryJournal(directory).recover(2_000_000_000_000L) {
            error("Malformed entry reached cleanup")
        }.getOrThrow())
    }

    @Test
    fun legacyPublicationAndPreAllocationDeathLeaveSuccessfulFilesAlone() {
        val directory = temporaryFolder.newFolder()
        val downloads = temporaryFolder.newFolder()
        val journal = ExportManager.RecoveryJournal(directory)
        val now = 2_000_000_000_000L
        ExportManager.writeLegacyDownload(journal, downloads, "gear.stl", byteArrayOf(1, 2), ".stl", now)
        assertTrue(directory.listFiles()!!.isEmpty())
        assertEquals(listOf<Byte>(1, 2), File(downloads, "gear.stl").readBytes().toList())
        journal.release(journal.begin(ExportManager.PendingKind.LEGACY, ".stl", 1000))
        assertEquals(1, journal.recover(now) {
            ExportManager.recoverLegacyDownload(it, downloads, now)
        }.getOrThrow())
        assertEquals(listOf("gear.stl"), downloads.listFiles()!!.map { it.name })
    }

    @Test
    fun temporaryWriteUsesAppSpecificMarker() {
        val directory = temporaryFolder.newFolder()
        ExportManager.writeFileAtomically(File(directory, "gear.stl")) { stream ->
            val temporary = directory.listFiles()!!.single()
            assertTrue(temporary.name.startsWith("gearforge-export-"))
            assertTrue(temporary.name.endsWith(".tmp"))
            stream.write(1)
        }
    }

    @Test
    fun failedFileWriteLeavesNoPartialExport() {
        val directory = temporaryFolder.newFolder()
        val target = File(directory, "gear.stl")
        val failure = IOException("disk full")
        val result = runCatching {
            ExportManager.writeFileAtomically(target) {
                it.write(byteArrayOf(1, 2))
                throw failure
            }
        }
        assertSame(failure, result.exceptionOrNull())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun failedFileWritePreservesPreviousExport() {
        val target = temporaryFolder.newFile("gear.stl")
        target.writeText("original")
        val result = runCatching {
            ExportManager.writeFileAtomically(target) {
                it.write(byteArrayOf(1, 2))
                throw IOException("disk full")
            }
        }
        assertTrue(result.isFailure)
        assertEquals("original", target.readText())
        assertEquals(listOf(target), target.parentFile!!.listFiles()!!.toList())
    }

    @Test
    fun completedFileWritePublishesExactBytes() {
        val directory = temporaryFolder.newFolder()
        val target = File(directory, "gear.stl")
        ExportManager.writeFileAtomically(target) { it.write(byteArrayOf(1, 2, 3)) }
        assertEquals(listOf<Byte>(1, 2, 3), target.readBytes().toList())
        assertEquals(listOf(target), directory.listFiles()!!.toList())
    }

    @Test
    fun cancellationBeforeWriteDoesNotCreateAFile() = runBlocking {
        val writes = AtomicInteger()
        val job = launch(Dispatchers.Default) {
            coroutineContext[Job]!!.cancel()
            ExportManager.commitWrite {
                writes.incrementAndGet()
                Result.success(Unit)
            }
        }
        withTimeout(10_000) { job.join() }
        assertEquals(0, writes.get())
    }

    @Test
    fun thrownOpenFailureRemovesPendingDownload() {
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        val failure = FileNotFoundException("cannot open")
        `when`(resolver.openOutputStream(uri)).thenThrow(failure)
        val result = ExportManager.writePendingDownload(resolver, uri, byteArrayOf(1))
        assertSame(failure, result.exceptionOrNull())
        verify(resolver).delete(uri, null, null)
    }

    @Test
    fun closeFailureRemovesPendingDownload() {
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        val failure = IOException("cannot flush")
        val stream = object : ByteArrayOutputStream() {
            override fun close() { throw failure }
        }
        `when`(resolver.openOutputStream(uri)).thenReturn(stream)
        val result = ExportManager.writePendingDownload(resolver, uri, byteArrayOf(1))
        assertSame(failure, result.exceptionOrNull())
        verify(resolver).delete(uri, null, null)
    }

    @Test
    fun successfulWritePublishesOnlyAfterStreamCloses() {
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        var closed = false
        val stream = object : ByteArrayOutputStream() {
            override fun close() { closed = true }
        }
        `when`(resolver.openOutputStream(uri)).thenReturn(stream)
        `when`(resolver.update(eq(uri), any(ContentValues::class.java), isNull(), isNull())).thenAnswer {
            assertTrue(closed)
            1
        }
        mockConstruction(ContentValues::class.java).use { values ->
            assertTrue(ExportManager.writePendingDownload(resolver, uri, byteArrayOf(1, 2)).isSuccess)
            verify(resolver).update(eq(uri), any(ContentValues::class.java), isNull(), isNull())
            verify(values.constructed().single()).put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        assertEquals(listOf<Byte>(1, 2), stream.toByteArray().toList())
        verify(resolver, never()).delete(uri, null, null)
    }

    @Test
    fun failedPublicationRemovesPendingDownload() {
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        `when`(resolver.openOutputStream(uri)).thenReturn(ByteArrayOutputStream())
        mockConstruction(ContentValues::class.java).use {
            assertTrue(ExportManager.writePendingDownload(resolver, uri, byteArrayOf(1)).isFailure)
        }
        verify(resolver).delete(uri, null, null)
    }

    @Test
    fun cancellationDuringWriteStillDeliversSuccessExactlyOnce() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        val consumed = AtomicInteger()
        val job = launch(Dispatchers.Default) {
            val result = ExportManager.commitWrite {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                writes.incrementAndGet()
                Result.success(Unit)
            }
            if (result.isSuccess) consumed.incrementAndGet()
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            job.cancel()
        } finally {
            release.countDown()
        }
        withTimeout(10_000) { job.join() }
        assertEquals(1, writes.get())
        assertEquals(1, consumed.get())
    }

    @Test
    fun cancellationDuringFailedWriteStillDeliversFailure() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = IOException("disk full")
        val delivered = AtomicReference<Throwable>()
        val job = launch(Dispatchers.Default) {
            val result = ExportManager.commitWrite<Unit> {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                Result.failure(failure)
            }
            delivered.set(result.exceptionOrNull())
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            job.cancel()
        } finally {
            release.countDown()
        }
        withTimeout(10_000) { job.join() }
        assertSame(failure, delivered.get())
    }
}