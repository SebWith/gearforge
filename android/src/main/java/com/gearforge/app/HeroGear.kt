package com.gearforge.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.gearforge.core.BoreSpec
import com.gearforge.core.BoreType
import com.gearforge.core.GearAssembly
import com.gearforge.core.GearBuilder
import com.gearforge.core.GearParams
import com.gearforge.core.GearType
import com.gearforge.core.ToothProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp

/** True when the user has disabled animations (system "Remove animations" or a 0 scale). */
private fun reduceMotionEnabled(context: Context): Boolean {
    val resolver = context.contentResolver
    return try {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
            Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f ||
            Settings.Global.getFloat(resolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f) == 0f
    } catch (t: Throwable) {
        false
    }
}

/** A premium-looking spur gear: substantial width, a machined hub and a clean bore. */
private fun heroGearParams(): GearParams = GearParams(
    gearType = GearType.SPUR,
    toothProfile = ToothProfile.INVOLUTE,
    module = 2.2,
    teeth = 18,
    pressureAngleDeg = 20.0,
    thickness = 11.0,
    backlash = 0.05,
    bore = BoreSpec(type = BoreType.ROUND, diameter = 9.0),
    hubDiameter = 16.0,
    hubLeftLength = 4.0,
    hubRightLength = 4.0,
    hubChamfer = 0.6,
    material = "Steel"
)

/**
 * Landing hero: the real gear mesh rendered in the app's PBR viewport with a soft
 * ground shadow, a gentle idle spin and a damped gyro-driven parallax orbit.
 * Falls back to a static, auto-framed view when the system reduces motion.
 */
@Composable
fun HeroGear(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reduceMotion = remember { reduceMotionEnabled(context) }
    var assembly by remember { mutableStateOf<GearAssembly?>(null) }
    var view by remember { mutableStateOf<GearGLView?>(null) }

    // Build the gear mesh off the main thread so the landing page never stutters.
    LaunchedEffect(Unit) {
        assembly = withContext(Dispatchers.Default) { GearBuilder.assembly(heroGearParams()) }
    }

    val instances = remember(assembly, reduceMotion) {
        assembly?.let { a ->
            a.meshes.mapIndexed { i, m ->
                GearGLView.Instance(
                    mesh = m,
                    offsetX = a.offsets[i].x.toFloat(),
                    offsetY = a.offsets[i].y.toFloat(),
                    spinSpeed = if (reduceMotion) 0f else 0.10f
                )
            }
        } ?: emptyList()
    }

    AndroidView(
        factory = { GearGLView(it).apply { interactive = false; renderBackground = false } },
        modifier = modifier
    ) { v ->
        view = v
        if (instances.isNotEmpty() && v.instances !== instances) {
            v.instances = instances
            v.autoFrame()
        }
    }

    // Idle spin: request a redraw once per display frame (throttled to ~30 fps)
    // so the slow spin stays smooth without a continuous, battery-hungry loop.
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        var frame = 0
        while (true) {
            withFrameNanos { }
            if (frame++ % 2 == 0) view?.requestFrame()
        }
    }

    if (!reduceMotion) {
        GyroParallax(view)
    }
}

/** Reads the rotation-vector sensor, low-pass filters it and orbits the hero gear. */
@Composable
private fun GyroParallax(view: GearGLView?) {
    val context = LocalContext.current
    DisposableEffect(context, view) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || sensor == null) return@DisposableEffect onDispose {}
        var filteredPitch = 0f
        var filteredRoll = 0f
        var lastNs = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val rotationMatrix = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, e.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val now = e.timestamp
                val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.1f)
                lastNs = now
                // Frame-rate-independent low-pass smoothing (~140 ms time constant).
                val alpha = 1f - exp(-dt / 0.14f).toFloat()
                filteredPitch += (orientation[1] - filteredPitch) * alpha
                filteredRoll += (orientation[2] - filteredRoll) * alpha
                val pitchDeg = Math.toDegrees(filteredPitch.toDouble()).toFloat().coerceIn(-40f, 40f)
                val rollDeg = Math.toDegrees(filteredRoll.toDouble()).toFloat().coerceIn(-40f, 40f)
                // Subtle parallax: ±~12° around the framed 3/4 view.
                view?.setOrbit(35f + pitchDeg * 0.30f, 45f - rollDeg * 0.30f)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
}
