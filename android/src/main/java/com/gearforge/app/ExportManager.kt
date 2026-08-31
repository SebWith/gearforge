package com.gearforge.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
     * SVG/DXF are 2D outlines and are unaffected by precision, but the flag is still applied
     * for consistency so a future vector-resolution path can honor it without API changes.
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
     * Cancellation is honoured at the phase boundaries so an aborted export never writes
     * a file; the CPU-bound mesh build itself is not interrupted mid-computation.
     *
     * Keeps the existing [bytes]/[saveToDownloads] paths intact so the Phase-1 gating
     * behaviour is unchanged.
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
        val result = withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            saveToDownloads(context, baseName, data, format.ext, format.mime)
        }
        onProgress(1f)
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
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
            val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return Result.failure(IOException("MediaStore insert returned null"))
                val stream = resolver.openOutputStream(uri)
                if (stream == null) {
                    resolver.delete(uri, null, null) // clean up ghost row
                    return Result.failure(IOException("MediaStore openOutputStream returned null"))
                }
                stream.use { it.write(bytes) }
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists() && !dir.mkdirs()) {
                    return Result.failure(IOException("Could not create Downloads directory"))
                }
                File(dir, name).writeBytes(bytes)
                true
            }
            if (written) Result.success(Unit) else Result.failure(IOException("Failed to write export"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
