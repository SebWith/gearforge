package com.gearforge.app

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStoreTest {
    @Test
    fun nozzlePresetsRemainSelectedAfterReload() {
        val prefs = preferences()
        for (preset in PrinterPresets.NOZZLES) {
            SettingsStore(prefs).nozzleMm = preset
            assertEquals(preset, SettingsStore(prefs).nozzleMm, 0.0)
        }
    }

    @Test
    fun layerHeightPresetsRemainSelectedAfterReload() {
        val prefs = preferences()
        for (preset in PrinterPresets.LAYER_HEIGHTS) {
            SettingsStore(prefs).layerHeightMm = preset
            assertEquals(preset, SettingsStore(prefs).layerHeightMm, 0.0)
        }
    }

    @Test
    fun defaultsSelectExistingChips() {
        val settings = SettingsStore(preferences())
        assertEquals(0.4, settings.nozzleMm, 0.0)
        assertEquals(0.2, settings.layerHeightMm, 0.0)
    }

    @Test
    fun nonPresetValuesAreNotChanged() {
        val settings = SettingsStore(preferences())
        settings.nozzleMm = 0.42
        settings.layerHeightMm = 0.18
        assertEquals(0.42f.toDouble(), settings.nozzleMm, 0.0)
        assertEquals(0.18f.toDouble(), settings.layerHeightMm, 0.0)
    }

    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, Float>()
        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putFloat" -> {
                    values[args!![0] as String] = args[1] as Float
                    proxy
                }
                "apply" -> null
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getFloat" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences
    }
}