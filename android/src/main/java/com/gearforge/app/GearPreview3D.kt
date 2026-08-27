package com.gearforge.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import com.gearforge.core.GearBuilder
import com.gearforge.core.GearParams
import com.gearforge.core.GearSpec
import com.gearforge.core.GearType
import com.gearforge.core.Vec3
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders the real 3D gear mesh (the app's own [GearBuilder.assembly] geometry) into a
 * small software-rasterised bitmap using exactly the same isometric camera and flat
 * lighting as the editor's OpenGL viewport: rotX = 35 deg, rotY = 45 deg, auto-framed,
 * light direction (0.35, 0.55, 0.75) and the same `0.35 + diff * 0.8` Lambert shading.
 *
 * This gives the wizard type cards and the annotated gear page a true 3D thumbnail at
 * the standard angle the user sees when opening each gear type, without spawning an
 * OpenGL context per card. Bitmaps are cached per (type, base color) and rendered once
 * off the main thread.
 */
object GearPreviewRenderer {

    private const val SIZE = 320
    private val cache = ConcurrentHashMap<String, Bitmap>()

    /** Returns (creating/caching if needed) the 3D preview bitmap for [type] tinted [baseArgb]. */
    fun preview(type: GearType, baseArgb: Int): Bitmap? =
        preview(GearSpec.defaults(type), baseArgb)

    /** Returns (creating/caching if needed) the 3D preview bitmap for a concrete [params] set. */
    fun preview(params: GearParams, baseArgb: Int): Bitmap? {
        val key = "${params.gearType.name}:${params.hashCode()}:$baseArgb"
        cache[key]?.let { return it }
        // Render outside any lock so different gear types rasterise in parallel on
        // separate dispatcher workers instead of serialising behind a single monitor.
        val rendered = render(params, baseArgb) ?: return null
        return cache.putIfAbsent(key, rendered) ?: rendered
    }

    private fun render(params: GearParams, baseArgb: Int): Bitmap? {
        val assembly = runCatching { GearBuilder.assembly(params) }.getOrNull()
            ?: return null
        if (assembly.meshes.isEmpty()) return null

        // Flatten all meshes with their placement offsets into a single vertex/triangle soup.
        val verts = ArrayList<Vec3>()
        val tris = ArrayList<IntArray>()
        assembly.meshes.forEachIndexed { i, mesh ->
            val ox = assembly.offsets.getOrNull(i)?.x ?: 0.0
            val oy = assembly.offsets.getOrNull(i)?.y ?: 0.0
            val base = verts.size
            mesh.vertices.forEach { v -> verts.add(Vec3(v.x + ox, v.y + oy, v.z)) }
            mesh.triangles.forEach { t -> tris.add(intArrayOf(base + t[0], base + t[1], base + t[2])) }
        }
        if (verts.isEmpty() || tris.isEmpty()) return null

        // Auto-frame bounds (identical to GearGLView.autoFrame).
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (v in verts) {
            if (v.x < minX) minX = v.x
            if (v.y < minY) minY = v.y
            if (v.z < minZ) minZ = v.z
            if (v.x > maxX) maxX = v.x
            if (v.y > maxY) maxY = v.y
            if (v.z > maxZ) maxZ = v.z
        }
        val radius = hypot(hypot(maxX - minX, maxY - minY), maxZ - minZ) / 2.0
        if (radius <= 0.0) return null
        val panX = -(minX + maxX) / 2.0
        val panY = -(minY + maxY) / 2.0

        // Camera (identical to GearGLView.onDrawFrame with aspect = 1, zoom = 1).
        val fovy = 35.0
        val halfFov = Math.toRadians(fovy / 2.0)
        val eyeDist = radius / sin(halfFov)
        val f = 1.0 / tan(halfFov)

        val rotX = Math.toRadians(35.0)
        val rotY = Math.toRadians(45.0)
        val cx = cos(rotX); val sx = sin(rotX)
        val cy = cos(rotY); val sy = sin(rotY)

        // Normalised light direction.
        val lx = 0.35; val ly = 0.55; val lz = 0.75
        val ll = sqrt(lx * lx + ly * ly + lz * lz)

        // Base colour as RGB floats.
        val r0 = ((baseArgb shr 16) and 0xFF) / 255f
        val g0 = ((baseArgb shr 8) and 0xFF) / 255f
        val b0 = (baseArgb and 0xFF) / 255f

        val n = SIZE
        val depth = FloatArray(n * n) { Float.NEGATIVE_INFINITY }
        val pixels = IntArray(n * n)

        // Projects a world-space point (already offset) to screen space; returns [sx, sy, viewZ].
        fun proj(x: Double, y: Double, z: Double): FloatArray {
            val tx = x + panX
            val ty = y + panY
            val tz = z
            // R_x
            val xa = tx
            val ya = ty * cx - tz * sx
            val za = ty * sx + tz * cx
            // R_y
            val xb = xa * cy + za * sy
            val yb = ya
            val zb = -xa * sy + za * cy
            val w = eyeDist - zb // = -zv, positive in front of the camera
            val ndcX = (f * xb) / w
            val ndcY = (f * yb) / w
            val sxPx = ((ndcX + 1.0) * 0.5 * n).toFloat()
            val syPx = ((1.0 - ndcY) * 0.5 * n).toFloat()
            return floatArrayOf(sxPx, syPx, zb.toFloat())
        }

        for (t in tris) {
            val a = verts[t[0]]; val b = verts[t[1]]; val c = verts[t[2]]

            // Face normal (local space), then rotated into world space for lighting.
            val e1x = b.x - a.x; val e1y = b.y - a.y; val e1z = b.z - a.z
            val e2x = c.x - a.x; val e2y = c.y - a.y; val e2z = c.z - a.z
            var nx = e1y * e2z - e1z * e2y
            var ny = e1z * e2x - e1x * e2z
            var nz = e1x * e2y - e1y * e2x
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            if (nl < 1e-12) continue
            nx /= nl; ny /= nl; nz /= nl
            // R_x then R_y on the normal.
            val nxa = nx
            val nya = ny * cx - nz * sx
            val nza = ny * sx + nz * cx
            val nxb = nxa * cy + nza * sy
            val nyb = nya
            val nzb = -nxa * sy + nza * cy

            // Flat shading, same formula as the GL simple shader.
            val diff = max(0.0, (nxb * lx + nyb * ly + nzb * lz) / ll)
            val shade = (0.35 + diff * 0.8).toFloat().coerceIn(0f, 1f)
            val col = android.graphics.Color.rgb(
                (r0 * shade * 255f).toInt().coerceIn(0, 255),
                (g0 * shade * 255f).toInt().coerceIn(0, 255),
                (b0 * shade * 255f).toInt().coerceIn(0, 255)
            )

            val pa = proj(a.x, a.y, a.z)
            val pb = proj(b.x, b.y, b.z)
            val pc = proj(c.x, c.y, c.z)
            rasterize(pa, pb, pc, col, depth, pixels, n)
        }

        val bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)

        // Soft elliptical ground shadow under the gear for depth.
        val floorZ = minZ - radius * 0.25
        val shadowR = radius * 1.15
        val sc = proj(0.0, 0.0, floorZ)
        val sp = proj(shadowR, 0.0, floorZ)
        val sq = proj(0.0, shadowR, floorZ)
        val rxS = abs(sp[0] - sc[0])
        val ryS = abs(sq[1] - sc[1])
        if (rxS > 1f && ryS > 1f) {
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x26000000
                style = Paint.Style.FILL
            }
            Canvas(bitmap).drawOval(
                sc[0] - rxS, sc[1] - ryS, sc[0] + rxS, sc[1] + ryS, shadowPaint
            )
        }

        bitmap.setPixels(pixels, 0, n, 0, 0, n, n)
        return bitmap
    }

    /**
     * Fills the triangle defined by three screen-space points using barycentric coordinates
     * and a per-pixel depth test ([depth] stores view-space z, larger = closer).
     */
    private fun rasterize(
        p0: FloatArray, p1: FloatArray, p2: FloatArray,
        color: Int, depth: FloatArray, pixels: IntArray, n: Int
    ) {
        val x0 = p0[0]; val y0 = p0[1]; val z0 = p0[2]
        val x1 = p1[0]; val y1 = p1[1]; val z1 = p1[2]
        val x2 = p2[0]; val y2 = p2[1]; val z2 = p2[2]

        val minX = max(0, min(min(x0, x1), x2).toInt())
        val maxX = min(n - 1, max(max(x0, x1), x2).toInt())
        val minY = max(0, min(min(y0, y1), y2).toInt())
        val maxY = min(n - 1, max(max(y0, y1), y2).toInt())
        if (minX > maxX || minY > maxY) return

        val denom = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if (denom == 0f) return
        val inv = 1f / denom

        for (py in minY..maxY) {
            val fy = py.toFloat()
            var idx = py * n + minX
            for (px in minX..maxX) {
                val fx = px.toFloat()
                val w0 = ((y1 - y2) * (fx - x2) + (x2 - x1) * (fy - y2)) * inv
                val w1 = ((y2 - y0) * (fx - x2) + (x0 - x2) * (fy - y2)) * inv
                val w2 = 1f - w0 - w1
                if (w0 < 0f || w1 < 0f || w2 < 0f) {
                    idx++
                    continue
                }
                val z = w0 * z0 + w1 * z1 + w2 * z2
                if (z > depth[idx]) {
                    depth[idx] = z
                    pixels[idx] = color
                }
                idx++
            }
        }
    }
}

/**
 * Theme-aware 3D gear thumbnail. Shows the real 3D mesh at the standard isometric angle;
 * falls back to an empty box while the bitmap renders on a background thread.
 */
@Composable
fun GearPreview3D(
    type: GearType,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    GearPreview3D(params = GearSpec.defaults(type), modifier = modifier, color = color)
}

/**
 * Theme-aware 3D gear thumbnail for a concrete parameter set. Shows the real 3D mesh at
 * the standard isometric angle; falls back to an empty box while the bitmap renders off
 * the main thread.
 */
@Composable
fun GearPreview3D(
    params: GearParams,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val baseArgb = color.toArgb()
    var bitmap by remember(params, baseArgb) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(params, baseArgb) {
        val result = withContext(Dispatchers.Default) { GearPreviewRenderer.preview(params, baseArgb) }
        bitmap = result
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    } else {
        Box(modifier)
    }
}
