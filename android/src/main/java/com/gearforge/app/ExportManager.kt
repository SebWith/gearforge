package com.gearforge.app

import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.gearforge.core.DxfWriter
import com.gearforge.core.GearBuilder
import com.gearforge.core.GearParams
import com.gearforge.core.IgesWriter
import com.gearforge.core.Mesh
import com.gearforge.core.MeshOps
import com.gearforge.core.PrecisionLevel
import com.gearforge.core.StepWriter
import com.gearforge.core.StlWriter
import com.gearforge.core.SvgWriter
import com.gearforge.core.ThreeMfWriter
import com.gearforge.core.Vec3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Properties
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Builds and saves/shares exported gear files. */
object ExportManager {

    enum class Format(val label: String, val ext: String, val mime: String) {
        STL("STL", ".stl", "model/stl"),
        THREE_MF("3MF", ".3mf", "model/3mf"),
        STEP("STEP", ".step", "application/step"),
        IGES("IGES", ".igs", "application/iges"),
        SVG("SVG", ".svg", "image/svg+xml"),
        DXF("DXF", ".dxf", "application/dxf")
    }

    /**
     * Builds the export bytes for [params].
     *
     * [highQuality] is the monetization "quality" flag from [SettingsStore.highQuality]
     * (already AND-ed with Pro status by the caller so non-Pro users are forced to low).
     * It maps onto the core precision path [GearParams.precision], which drives tooth-flank
     * sampling in GearProfiles.flankSteps and loft slice count in GearBuilder.sliceCount.
     *
     * SVG/DXF are 2D outlines, but they are **not** unaffected by the flag: they are built
     * through [GearBuilder.shape], which reaches `GearProfiles.externalOutline` and samples
     * the flank with the same `flankSteps`. A high-quality export therefore writes a finer
     * 2D polygon too (audit L4 — an earlier version of this comment claimed the flag was a
     * no-op for the vector formats).
     */
    fun bytes(params: GearParams, format: Format, highQuality: Boolean = true): ByteArray {
        return when (format) {
            Format.STL -> StlWriter.writeBinary(validatedMesh(params, highQuality))
            Format.THREE_MF -> ThreeMfWriter.write(validatedMesh(params, highQuality))
            Format.STEP -> StepWriter.write(validatedMesh(params, highQuality)).toByteArray(Charsets.UTF_8)
            Format.IGES -> IgesWriter.write(validatedMesh(params, highQuality)).toByteArray(Charsets.UTF_8)
            Format.SVG -> {
                val effective = effective(params, highQuality)
                SvgWriter.write(GearBuilder.shape(effective)).toByteArray(Charsets.UTF_8)
            }
            Format.DXF -> {
                val effective = effective(params, highQuality)
                DxfWriter.write(GearBuilder.shape(effective)).toByteArray(Charsets.UTF_8)
            }
        }
    }

    /** Builds the merged 3D mesh used for STL/3MF export and the export preview (point 12). */
    fun mesh(params: GearParams, highQuality: Boolean = true): Mesh =
        GearBuilder.merged(effective(params, highQuality))

    /**
     * Pre-flight mesh-integrity validation for the 3D formats (audit H4). Returns the
     * list of defects, or an empty list when the mesh is a closed manifold solid that is
     * safe to export. The app surfaces these before writing a file.
     */
    fun validateMesh(params: GearParams, highQuality: Boolean = true): List<String> =
        MeshOps.validate(mesh(params, highQuality)).issues

    /** Builds and validates the mesh for a 3D export, throwing a descriptive error if broken. */
    private fun validatedMesh(params: GearParams, highQuality: Boolean): Mesh {
        val m = mesh(params, highQuality)
        // Duplicate vertices are expected where the hub boss meets the gear face
        // (two watertight solids that share a boundary — a printable union, not a
        // defect). Real blockers are open edges, non-manifold edges, inverted
        // volume, out-of-range indices and degenerate triangles (audit L4).
        val issues = MeshOps.validate(m).issues.filterNot { it.contains("duplicate vertices") }
        if (issues.isNotEmpty()) {
            throw IllegalStateException("Mesh validation failed: ${issues.joinToString("; ")}")
        }
        return m
    }

    /**
     * Runs the full export off the UI thread with coarse progress and coroutine
     * cancellation support (point 18).
     *
     * Progress is reported in two phases: 0.0 → 0.5 is mesh build + serialization
     * (CPU bound, [Dispatchers.Default]), 0.5 → 1.0 is the file write ([Dispatchers.IO]).
     * The CPU-bound phase is cancellable and is checked at both its boundaries, so an
     * export cancelled while the mesh is being built never writes a file.
     *
     * The write phase is deliberately **not** cancellable. Previously it was an ordinary
     * `withContext(Dispatchers.IO)`, and `withContext` re-checks the parent job when it
     * resumes — so cancelling while the bytes were being written threw
     * [CancellationException] *after* a complete file had already reached Downloads. Two
     * things then went wrong at the same time: the user got a file with no feedback, and
     * `ExportSheet` never saw a successful [Result], so it never decremented the free-export
     * counter (it is consumed only for `Result.success`). Cancelling at the right moment
     * therefore produced unlimited free exports.
     *
     * [NonCancellable] makes the write atomic with respect to cancellation, which is what
     * the entitlement decision depends on: the truthful outcome now always reaches the
     * caller, so "file written" and "free export consumed" can no longer disagree.
     */
    suspend fun export(
        context: Context,
        params: GearParams,
        format: Format,
        highQuality: Boolean,
        baseName: String,
        onProgress: (Float) -> Unit
    ): Result<Unit> = try {
        onProgress(0f)
        val data = withContext(Dispatchers.Default) {
            coroutineContext.ensureActive()
            bytes(params, format, highQuality).also { coroutineContext.ensureActive() }
        }
        onProgress(0.5f)
        val result = commitWrite {
            saveToDownloads(context, baseName, data, format.ext, format.mime)
        }
        onProgress(1f)
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Builds the same bytes as [export] and writes them into a cache folder this app alone can
     * reach, returning a [FileProvider] uri for them.
     *
     * A share is not a second export path — it produces the same file from the same code and the
     * caller runs it through the same gating. Only the destination differs, and it has to: the
     * public Downloads folder is the wrong place to put something the user is about to send to
     * another app, and a `file://` uri cannot be handed out at all on API 24+
     * ([android.os.FileUriExposedException]).
     *
    * Every share gets a distinct private file, retained for at least seven days before cleanup
    * on a subsequent share. Android cache eviction cannot remove a pending recipient's file.
     */
    suspend fun prepareShare(
        context: Context,
        params: GearParams,
        format: Format,
        highQuality: Boolean,
        baseName: String,
        onProgress: (Float) -> Unit
    ): Result<Uri> = try {
        onProgress(0f)
        val data = withContext(Dispatchers.Default) {
            coroutineContext.ensureActive()
            bytes(params, format, highQuality).also { coroutineContext.ensureActive() }
        }
        onProgress(0.5f)
        // The write is not cancellable for the same reason as in [export]: a cancellation that
        // arrives after the bytes are on disk must still be reported as a success to the caller,
        // which is what keeps "file written" and "free export consumed" in agreement.
        val result = commitWrite {
            writeShareCopy(context, baseName, data, format.ext)
        }
        onProgress(1f)
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    internal suspend fun <T> commitWrite(write: () -> Result<T>): Result<T> {
        coroutineContext.ensureActive()
        return withContext(NonCancellable) {
            withContext(Dispatchers.IO) { write() }
        }
    }

    private const val SHARE_DIR = "exports"
    internal const val SHARE_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
    internal const val RECOVERY_AGE_MILLIS = 24L * 60 * 60 * 1000
    internal const val MAX_RECOVERY_ENTRIES = 32

    internal enum class PendingKind { LEGACY, MEDIA_STORE }

    internal data class PendingExport(
        val id: String,
        val kind: PendingKind,
        val createdAt: Long,
        val extension: String
    ) {
        val temporaryName get() = "gearforge-export-$id.tmp"
        val pendingName get() = "gearforge-export-$id$extension"
    }

    internal class RecoveryJournal(directory: File) {
        private val directory = directory.canonicalFile

        companion object {
            private const val PARTIAL_SUFFIX = ".part"
            private val lock = Any()
            private val active = mutableSetOf<String>()
        }

        private fun record(entry: PendingExport) = File(directory, "${entry.id}.properties")

        fun begin(kind: PendingKind, extension: String, nowMillis: Long): PendingExport = synchronized(lock) {
            require(nowMillis > 0 && Format.entries.any { it.ext == extension })
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create export journal")
            val records = directory.listFiles() ?: throw IOException("Could not read export journal")
            if (records.size >= MAX_RECOVERY_ENTRIES) throw IOException("Export recovery journal is full")
            val entry = PendingExport(UUID.randomUUID().toString(), kind, nowMillis, extension)
            val file = record(entry)
            // Write-then-rename: a crash between create and store would otherwise leave a
            // half-written record that read() rejects, so recovery could never reclaim it and
            // 32 of them would permanently fill the journal and fail every export (begin()
            // throws at the cap).
            val partial = File(directory, file.name + PARTIAL_SUFFIX)
            if (!partial.createNewFile()) throw IOException("Export journal temporary already exists")
            try {
                val properties = Properties().apply {
                    setProperty("version", "1")
                    setProperty("kind", kind.name)
                    setProperty("createdAt", nowMillis.toString())
                    setProperty("extension", extension)
                }
                FileOutputStream(partial).use { stream ->
                    properties.store(stream, null)
                    stream.fd.sync()
                }
                if (!partial.renameTo(file)) throw IOException("Could not finalize export journal entry")
            } catch (failure: Throwable) {
                partial.delete()
                throw failure
            }
            active.add(file.absolutePath)
            entry
        }

        fun release(entry: PendingExport) = synchronized(lock) {
            active.remove(record(entry).absolutePath)
        }

        fun forget(entry: PendingExport): Boolean = synchronized(lock) {
            runCatching { record(entry).delete() }.getOrDefault(false)
        }

        private fun read(file: File): PendingExport? = runCatching {
            if (!file.isFile || file.canonicalFile != file.absoluteFile || file.length() !in 1..1024) return null
            val id = file.name.removeSuffix(".properties")
            if (file.name != "$id.properties" || UUID.fromString(id).toString() != id) return null
            val properties = Properties().apply { file.inputStream().use { load(it) } }
            if (properties.getProperty("version") != "1") return null
            val extension = properties.getProperty("extension")
            if (Format.entries.none { it.ext == extension }) return null
            PendingExport(
                id, PendingKind.valueOf(properties.getProperty("kind")),
                properties.getProperty("createdAt").toLong(), extension
            )
        }.getOrNull()

        fun recover(nowMillis: Long, cleanup: (PendingExport) -> Boolean): Result<Int> = runCatching {
            synchronized(lock) {
                if (!directory.exists()) return@synchronized 0
                val records = directory.listFiles() ?: throw IOException("Could not read export journal")
                var recovered = 0
                for (file in records.sortedBy { it.name }.take(MAX_RECOVERY_ENTRIES)) {
                    if (file.absolutePath in active) continue
                    val entry = read(file)
                    if (entry == null) {
                        // Unreadable records cannot be reclaimed by reading them: drop the
                        // stale ones (crash-truncated entries or leftovers from an older
                        // format) so they do not accumulate until the journal is full.
                        if (file.isFile && isStale(file.lastModified(), nowMillis)) {
                            runCatching { file.delete() }
                        }
                        continue
                    }
                    // Debug-only breadcrumb: makes it possible to tell "not stale" apart from
                    // "cleanup refused" on a device (the release build stays silent). Wrapped in
                    // runCatching because plain JVM unit tests have no android.util.Log.
                    val stale = isStale(entry.createdAt, nowMillis)
                    val cleaned = if (stale) cleanup(entry) else false
                    debugLog("recover id=${entry.id} kind=${entry.kind} stale=$stale cleaned=$cleaned")
                    if (stale && cleaned && file.delete()) recovered++
                }
                recovered
            }
        }
    }

    private fun isStale(timestamp: Long, nowMillis: Long): Boolean =
        timestamp > 0 && nowMillis > RECOVERY_AGE_MILLIS && timestamp < nowMillis - RECOVERY_AGE_MILLIS

    /** Debug-build-only breadcrumb for recovery decisions; silent in release and test-safe. */
    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) runCatching { android.util.Log.i("ExportManager", message) }
    }

    internal fun recoverLegacyDownload(entry: PendingExport, directory: File, nowMillis: Long): Boolean {
        if (!directory.isDirectory || !directory.canRead()) return false
        val temporary = File(directory.canonicalFile, entry.temporaryName)
        if (temporary.canonicalFile != temporary.absoluteFile) return false
        if (!temporary.exists()) return true
        return temporary.isFile && isStale(temporary.lastModified(), nowMillis) && temporary.delete()
    }

    internal fun writeLegacyDownload(
        journal: RecoveryJournal,
        directory: File,
        name: String,
        bytes: ByteArray,
        extension: String,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        require(name.isNotBlank() && name == File(name).name && name != "." && name != "..")
        val entry = journal.begin(PendingKind.LEGACY, extension, nowMillis)
        try {
            val temporary = File(directory, entry.temporaryName)
            if (!temporary.createNewFile()) throw IOException("Export temporary file already exists")
            writeAtomicContents(File(directory, name), temporary, { journal.forget(entry) }) { it.write(bytes) }
        } finally {
            journal.release(entry)
        }
    }

    private fun recoveryJournal(context: Context) = RecoveryJournal(File(context.noBackupFilesDir, "export-recovery"))

    fun recoverIncompleteDownloads(context: Context, nowMillis: Long = System.currentTimeMillis()): Result<Int> = runCatching {
        recoveryJournal(context).recover(nowMillis) { entry ->
            when (entry.kind) {
                PendingKind.LEGACY -> {
                    @Suppress("DEPRECATION")
                    val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    recoverLegacyDownload(entry, directory, nowMillis)
                }
                PendingKind.MEDIA_STORE -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) false else {
                        @Suppress("DEPRECATION")
                        val collection = MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
                        recoverPendingDownload(context.contentResolver, collection, context.packageName, entry, nowMillis)
                    }
                }
            }
        }.getOrThrow()
    }

    internal fun recoverPendingDownload(
        resolver: ContentResolver,
        collection: Uri,
        packageName: String,
        entry: PendingExport,
        nowMillis: Long
    ): Boolean {
        if (!isStale(entry.createdAt, nowMillis)) return false
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED
        )
        val identity = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1"
        val arguments = arrayOf(entry.pendingName, packageName, "${Environment.DIRECTORY_DOWNLOADS}/")
        val cursor = resolver.query(collection, projection, identity, arguments, null)
        if (cursor == null) {
            debugLog("recoverPending: query returned null for ${entry.id}")
            return false
        }
        return cursor.use {
            if (!it.moveToFirst()) {
                debugLog("recoverPending: no pending row for ${entry.id}")
                return@use true
            }
            val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            val name = it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
            val owner = it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME))
            val path = it.getString(it.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
            val pending = it.getInt(it.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING))
            val added = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
            val modified = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED))
            val cutoffSeconds = (nowMillis - RECOVERY_AGE_MILLIS) / 1000
            // modified == 0 is the normal state of a row whose write never finished (the app
            // died between insert and publication), so age is decided by DATE_ADDED alone in
            // that case: this is exactly the crash the journal exists for. A row that WAS
            // modified must be old by both stamps.
            val modifiedOld = modified <= 0 || modified < cutoffSeconds
            if (id <= 0 || name != arguments[0] || owner != arguments[1] || path != arguments[2] || pending != 1 ||
                added <= 0 || added >= cutoffSeconds || !modifiedOld || it.moveToNext()
            ) {
                debugLog(
                    "recoverPending: mismatch for ${entry.id} id=$id name=$name owner=$owner path=$path " +
                        "pending=$pending added=$added modified=$modified"
                )
                return@use false
            }
            val selection = if (modified > 0) {
                "$identity AND ${MediaStore.MediaColumns.DATE_ADDED}=? AND ${MediaStore.MediaColumns.DATE_MODIFIED}=?"
            } else {
                "$identity AND ${MediaStore.MediaColumns.DATE_ADDED}=?"
            }
            val selectionArgs = if (modified > 0) {
                arguments + arrayOf(added.toString(), modified.toString())
            } else {
                arguments + arrayOf(added.toString())
            }
            val deleted = resolver.delete(ContentUris.withAppendedId(collection, id), selection, selectionArgs) == 1
            debugLog("recoverPending: delete row $id -> $deleted")
            deleted
        }
    }

    internal fun writeMediaStoreDownload(
        resolver: ContentResolver,
        collection: Uri,
        journal: RecoveryJournal,
        name: String,
        bytes: ByteArray,
        extension: String,
        mime: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<Unit> = runCatching {
        val entry = journal.begin(PendingKind.MEDIA_STORE, extension, nowMillis)
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, entry.pendingName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert returned null")
            writePendingDownload(resolver, uri, bytes, name) { journal.forget(entry) }.getOrThrow()
        } finally {
            journal.release(entry)
        }
    }

    private fun writeShareCopy(
        context: Context,
        baseName: String,
        bytes: ByteArray,
        ext: String
    ): Result<Uri> {
        return try {
            val file = createShareCopy(context, baseName, bytes, ext)
            Result.success(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun createShareCopy(
        context: Context,
        baseName: String,
        bytes: ByteArray,
        ext: String,
        nowMillis: Long = System.currentTimeMillis()
    ): File {
        val dir = File(context.filesDir, SHARE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create the $SHARE_DIR folder")
        require(baseName.isNotBlank() && baseName == File(baseName).name && baseName != "." && baseName != "..")
        listOf(dir, File(context.cacheDir, SHARE_DIR)).forEach { directory ->
            directory.listFiles()?.forEach { file ->
                val modified = file.lastModified()
                if (file.isFile && modified > 0 && modified < nowMillis - SHARE_RETENTION_MILLIS) {
                    file.delete()
                }
            }
        }
        return File(dir, "$baseName-${UUID.randomUUID()}$ext").also { file ->
            writeFileAtomically(file) { it.write(bytes) }
        }
    }

    /**
     * Hands [uri] to whichever app the user picks, and reports whether the hand-off even started.
     *
     * The uri is attached **twice** on purpose: as [Intent.EXTRA_STREAM], which is what receiving
     * apps look for, and in [android.content.ClipData], which is what carries the read grant across
     * the process boundary. With the extra alone the sharesheet cannot read the file to build its
     * preview — the emulator logs `Permission Denial: opening provider androidx.core.content
     * .FileProvider ... that is not exported` and `ChooserPreview: Could not read ... stream types`
     * — and a target that only looks at the clip data would receive an unreadable uri. This was
     * found by running a share on a device, not by reading the API docs.
     */
    fun share(context: Context, uri: Uri, mime: String, chooserTitle: String): Boolean = try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, uri.lastPathSegment ?: "gear", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        false
    }

    private fun effective(params: GearParams, highQuality: Boolean): GearParams =
        params.copy(precision = if (highQuality) PrecisionLevel.HIGH else PrecisionLevel.STANDARD)
            .coerced() // audit C3: keep geometry invariants even through copy()

    /**
     * Saves export bytes to the public Downloads folder. Returns a [Result] instead of
     * throwing so callers can surface a localized failure message (point 2). A failed
     * MediaStore insert/write is converted into a failed [Result] rather than an exception.
     */
    fun saveToDownloads(context: Context, baseName: String, bytes: ByteArray, ext: String, mime: String): Result<Unit> {
        return try {
            val name = "$baseName-${System.currentTimeMillis()}$ext"
            val journal = recoveryJournal(context)
            val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeMediaStoreDownload(
                    context.contentResolver, MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    journal, name, bytes, ext, mime
                ).getOrThrow()
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists() && !dir.mkdirs()) {
                    return Result.failure(IOException("Could not create Downloads directory"))
                }
                writeLegacyDownload(journal, dir, name, bytes, ext)
                true
            }
            if (written) Result.success(Unit) else Result.failure(IOException("Failed to write export"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun writePendingDownload(
        resolver: ContentResolver,
        uri: Uri,
        bytes: ByteArray,
        publishedName: String? = null,
        onResolved: () -> Unit = {}
    ): Result<Unit> = runCatching {
        try {
            val stream = resolver.openOutputStream(uri)
                ?: throw IOException("MediaStore openOutputStream returned null")
            stream.use { it.write(bytes) }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                if (publishedName != null) put(MediaStore.MediaColumns.DISPLAY_NAME, publishedName)
            }
            if (resolver.update(uri, values, null, null) != 1) {
                throw IOException("MediaStore could not publish export")
            }
            onResolved()
        } catch (exception: Exception) {
            runCatching {
                if (resolver.delete(uri, null, null) == 1) onResolved()
            }.exceptionOrNull()?.let(exception::addSuppressed)
            throw exception
        }
    }

    internal fun writeFileAtomically(file: File, write: (OutputStream) -> Unit) {
        val temporary = File.createTempFile("gearforge-export-", ".tmp", file.absoluteFile.parentFile)
        writeAtomicContents(file, temporary, {}, write)
    }

    private fun writeAtomicContents(
        file: File,
        temporary: File,
        onResolved: () -> Unit,
        write: (OutputStream) -> Unit
    ) {
        try {
            temporary.outputStream().use(write)
            if (!temporary.renameTo(file)) throw IOException("Could not publish export")
            onResolved()
        } finally {
            if (temporary.delete()) onResolved()
        }
    }
}
