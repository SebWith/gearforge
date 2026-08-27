package com.gearforge.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.gearforge.core.GearParams
import com.gearforge.core.GearSpec
import com.gearforge.core.GearType
import org.json.JSONObject

/** Stage of the main screen flow, lifted out of MainActivity so the ViewModel can persist it. */
enum class Stage { LANDING, WIZARD, EDITOR }

/**
 * Holds editor state (selected gear [type] + current [params]) in a [SavedStateHandle] so it
 * survives rotation and process death, plus an in-memory undo/redo stack that survives rotation.
 *
 * Parameters are serialised as JSON via [SavedConfigs] (GearParams is not Parcelable), which is
 * the serialisation the rest of the app already uses for saved configurations.
 */
class EditorViewModel(private val handle: SavedStateHandle) : ViewModel() {

    companion object {
        private const val KEY_STAGE = "editor_stage"
        private const val KEY_TYPE = "editor_type"
        private const val KEY_PARAMS = "editor_params"
        private const val KEY_SAVED = "editor_saved_params"

        /** Maximum depth of the undo/redo history (point 7). */
        const val MAX_UNDO = 20
    }

    var stage by mutableStateOf(
        runCatching { Stage.valueOf(handle.get<String>(KEY_STAGE) ?: "") }.getOrDefault(Stage.LANDING)
    )
        private set

    var gearType by mutableStateOf(
        runCatching { GearType.valueOf(handle.get<String>(KEY_TYPE) ?: "") }.getOrDefault(GearType.SPUR)
    )
        private set

    var params by mutableStateOf(handle.get<String>(KEY_PARAMS)?.let { SavedConfigs.fromJson(it) })
        private set

    /** Per-type parameter sets preserved when switching between gear types (survives process death). */
    private val savedParams: MutableMap<GearType, GearParams> = readSavedParams()

    // Undo/redo history (point 7). Backed by Compose-observable state so buttons enable/disable
    // reactively; kept in-memory so it survives rotation but is intentionally not persisted.
    private val undoStack = mutableStateListOf<GearParams>()
    private val redoStack = mutableStateListOf<GearParams>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun updateStage(s: Stage) {
        stage = s
        handle[KEY_STAGE] = s.name
    }

    /** Enters the editor with a fresh parameter set (from the wizard). */
    fun startEditor(p: GearParams) {
        savedParams.clear()
        savedParams[p.gearType] = p
        persistSavedParams()
        gearType = p.gearType
        handle[KEY_TYPE] = p.gearType.name
        params = p
        handle[KEY_PARAMS] = SavedConfigs.toJson(p)
        undoStack.clear()
        redoStack.clear()
    }

    /** Leaves the editor; the current parameters are intentionally discarded. */
    fun clearEditor() {
        params = null
        handle[KEY_PARAMS] = null
        undoStack.clear()
        redoStack.clear()
    }

    /** Switches gear type, preserving each type's parameters. Clears undo history (point 7). */
    fun switchType(newType: GearType) {
        if (newType == gearType) return
        val current = params
        if (current != null) savedParams[current.gearType] = current
        gearType = newType
        handle[KEY_TYPE] = newType.name
        val next = savedParams[newType] ?: GearSpec.defaults(newType)
        savedParams[newType] = next
        params = next
        handle[KEY_PARAMS] = SavedConfigs.toJson(next)
        persistSavedParams()
        undoStack.clear()
        redoStack.clear()
    }

    /**
     * Applies a parameter mutation, pushing the previous snapshot onto the undo stack
     * (capped at [MAX_UNDO]) and clearing the redo stack.
     */
    fun mutate(next: GearParams) {
        val current = params ?: return
        if (next == current) return
        undoStack.add(current)
        while (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
        redoStack.clear()
        applyParams(next)
    }

    fun undo() {
        val current = params ?: return
        if (undoStack.isEmpty()) return
        val prev = undoStack.removeAt(undoStack.size - 1)
        redoStack.add(current)
        applyParams(prev)
    }

    fun redo() {
        val current = params ?: return
        if (redoStack.isEmpty()) return
        val next = redoStack.removeAt(redoStack.size - 1)
        undoStack.add(current)
        while (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
        applyParams(next)
    }

    /** Resets the current type to its defaults, pushing a snapshot for undo (point 8). */
    fun resetToDefault(type: GearType) {
        mutate(GearSpec.defaults(type))
    }

    /** Applies a loaded saved configuration (replaces type + params, clears history). */
    fun applyLoaded(p: GearParams) {
        savedParams[p.gearType] = p
        gearType = p.gearType
        handle[KEY_TYPE] = p.gearType.name
        params = p
        handle[KEY_PARAMS] = SavedConfigs.toJson(p)
        persistSavedParams()
        undoStack.clear()
        redoStack.clear()
    }

    private fun applyParams(next: GearParams) {
        params = next
        handle[KEY_PARAMS] = SavedConfigs.toJson(next)
        if (next.gearType != gearType) {
            gearType = next.gearType
            handle[KEY_TYPE] = next.gearType.name
        }
    }

    private fun readSavedParams(): MutableMap<GearType, GearParams> {
        val raw = handle.get<String>(KEY_SAVED) ?: return mutableMapOf()
        val out = mutableMapOf<GearType, GearParams>()
        return try {
            val o = JSONObject(raw)
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val type = GearType.valueOf(k)
                SavedConfigs.fromJson(o.optString(k))?.let { out[type] = it }
            }
            out
        } catch (e: Exception) {
            android.util.Log.w("EditorViewModel", "Failed to restore per-type params", e) // audit H11
            mutableMapOf()
        }
    }

    private fun persistSavedParams() {
        val o = JSONObject()
        savedParams.forEach { (t, p) -> o.put(t.name, SavedConfigs.toJson(p)) }
        handle[KEY_SAVED] = o.toString()
    }
}
