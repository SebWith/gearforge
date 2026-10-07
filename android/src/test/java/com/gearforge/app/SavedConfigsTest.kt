package com.gearforge.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.gearforge.core.GearParams
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*

class SavedConfigsTest {
    private val context = mock(Context::class.java)
    private val preferences = mock(SharedPreferences::class.java)
    private val editor = mock(SharedPreferences.Editor::class.java)

    init {
        `when`(context.getSharedPreferences("saved_configs", Context.MODE_PRIVATE)).thenReturn(preferences)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
    }

    @Test
    fun corruptCollectionIsPreservedAndSaveReportsFailure() {
        `when`(preferences.getString("map", "{}")).thenReturn("{\"original\":")
        mockStatic(Log::class.java).use {
            assertTrue(SavedConfigs.save(context, "new", GearParams()).isFailure)
        }
        verify(preferences, never()).edit()
        verifyNoInteractions(editor)
    }

    @Test
    fun firstSaveCreatesAReadableConfiguration() {
        assertTrue(SavedConfigs.save(context, "first", GearParams()).isSuccess)
        val json = ArgumentCaptor.forClass(String::class.java)
        verify(editor).putString(eq("map"), json.capture())
        verify(editor).apply()
        val stored = JSONObject(json.value)
        assertEquals(1, stored.length())
        assertEquals(GearParams(), SavedConfigs.fromJson(stored.getString("first")))
    }

    @Test
    fun repeatedNamePreservesExistingConfiguration() {
        val original = SavedConfigs.toJson(GearParams())
        `when`(preferences.getString("map", "{}")).thenReturn(
            JSONObject().put("gear", original).toString()
        )
        val updated = GearParams(teeth = 30)
        assertTrue(SavedConfigs.save(context, "gear", updated).isSuccess)
        val json = ArgumentCaptor.forClass(String::class.java)
        verify(editor).putString(eq("map"), json.capture())
        val stored = JSONObject(json.value)
        assertEquals(2, stored.length())
        assertEquals(original, stored.getString("gear"))
        assertEquals(updated, SavedConfigs.fromJson(stored.getString("gear (2)")))
    }
}