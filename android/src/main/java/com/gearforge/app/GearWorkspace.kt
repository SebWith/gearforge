package com.gearforge.app

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gearforge.core.FieldKind
import com.gearforge.core.GearAssembly
import com.gearforge.core.GearBuilder
import com.gearforge.core.GearParams
import com.gearforge.core.GearSeverity
import com.gearforge.core.GearSpec
import com.gearforge.core.GearType
import com.gearforge.core.MeshOps
import com.gearforge.core.ParamGroup
import com.gearforge.core.Presets
import com.gearforge.core.PrintAdvisor
import com.gearforge.core.ToothOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Snapshot of the mesh statistics shown in the export preview (point 12). */
private data class ExportPreview(val triangles: Int, val w: Double, val h: Double, val d: Double)

/** Debounce delay before a parameter change triggers a mesh rebuild (point 17). */
private const val MESH_DEBOUNCE_MS = 200L

/**
 * Collapse/expand state for the parameter sections. Held above the bottom sheet so it
 * survives switching between View and Parameters without losing the user's layout;
 * persisted across configuration changes via [androidx.compose.runtime.saveable.rememberSaveable].
 */
internal class SectionExpansion(initialCollapsed: String = "MATERIAL,TOLERANCES,LOAD,RESULTS,HUB,TEETH,LIGHTENING") {
    var collapsedNames by mutableStateOf(initialCollapsed)
        private set

    fun isExpanded(group: ParamGroup): Boolean =
        group.name !in collapsedNames.split(',').filter { it.isNotEmpty() }

    fun toggle(group: ParamGroup) {
        val names = collapsedNames.split(',').filter { it.isNotEmpty() }.toMutableSet()
        if (!names.add(group.name)) names.remove(group.name)
        collapsedNames = names.joinToString(",")
    }
}

/**
 * The main editor: a 3D viewport on top and a dynamic, type-specific settings panel
 * below. Each gear type has its own parameter set that is preserved when switching.
 *
 * Editor state (selected type + params) is backed by [EditorViewModel] so it survives
 * rotation and process death, with an undo/redo history and per-type reset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GearWorkspaceScreen(
    activity: Activity,
    settings: SettingsStore,
    adManager: AdManager,
    billingManager: BillingManager,
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
    lang: I18n.Lang,
    onLangChange: (I18n.Lang) -> Unit,
    viewModel: EditorViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val type = viewModel.gearType
    val params = viewModel.params ?: return

    var framed by remember { mutableStateOf(false) }

    fun switchType(newType: GearType) {
        if (newType == type) return
        viewModel.switchType(newType)
        framed = false
    }

    var assembly by remember { mutableStateOf<GearAssembly?>(null) }

    // Point 17: debounce + params-hash cache for mesh rebuilds.
    //
    // The cache is keyed by the full [GearParams] value; its data-class hashCode/equals
    // cover the gear type, precision (the "highQuality" equivalent for the live viewport)
    // and every parameter, so returning to a previously built parameter set reuses the
    // cached assembly instead of regenerating it. The viewport mesh is always built from
    // `params` directly (precision is an explicit field), so no separate highQuality key
    // is needed here.
    val meshCache = remember { mutableMapOf<GearParams, GearAssembly>() }
    LaunchedEffect(params) {
        // Debounce so a rapid slider drag only triggers one rebuild on the final value.
        delay(MESH_DEBOUNCE_MS)
        meshCache[params]?.let {
            assembly = it
            return@LaunchedEffect
        }
        // LaunchedEffect cancels the previous coroutine when `params` changes again, and
        // withContext(Dispatchers.Default) is cancellable, so an in-flight build for a
        // stale parameter set is discarded before it is ever published.
        val built = withContext(Dispatchers.Default) { GearBuilder.assembly(params) }
        meshCache[params] = built
        assembly = built
    }

    val glViewRef = remember { mutableStateOf<GearGLView?>(null) }
    val instances = remember(assembly, params) {
        assembly?.let { a ->
            val list = ArrayList<GearGLView.Instance>(a.meshes.size + params.toothOverrides.size)
            a.meshes.forEachIndexed { i, m ->
                list.add(GearGLView.Instance(m, a.offsets[i].x.toFloat(), a.offsets[i].y.toFloat(), 0f))
            }
            // Overlay a distinct-colour wedge over each overridden tooth so the edit is
            // visible live in the viewport (point 11: per-tooth override preview).
            if (GearSpec.hasGearBody(params.gearType) && params.toothOverrides.isNotEmpty()) {
                params.toothOverrides.keys.sorted().forEach { idx ->
                    list.add(
                        GearGLView.Instance(
                            GearBuilder.toothHighlightMesh(params, idx),
                            offsetX = 0f,
                            offsetY = 0f,
                            spinSpeed = 0f,
                            highlight = true
                        )
                    )
                }
            }
            list
        } ?: emptyList()
    }

    LaunchedEffect(instances) {
        val v = glViewRef.value ?: return@LaunchedEffect
        v.instances = instances
        if (!framed && instances.isNotEmpty()) {
            framed = true
            v.autoFrame()
        }
    }

    var showExport by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAdvice by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var showOpen by remember { mutableStateOf(false) }
    var showTypeMenu by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var selectedMode by remember { mutableStateOf("view") }
    // Parameter-section expand/collapse state lives here (not inside the bottom sheet) so it
    // survives switching between View and Parameters without losing the user's layout.
    val sectionExpansion = rememberSaveable(
        saver = listSaver(
            save = { listOf(it.collapsedNames) },
            restore = { SectionExpansion(it[0]) }
        )
    ) { SectionExpansion() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Top bar: back + type (left), undo/redo/save/overflow (right), and a
            // horizontally swipeable mode switcher underneath (Prio 4).
            Surface(tonalElevation = 4.dp) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, I18n.t(lang, "back")) }
                        Box {
                            TextButton(onClick = { showTypeMenu = true }) {
                                Text(typeLabel(type, lang), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = showTypeMenu, onDismissRequest = { showTypeMenu = false }) {
                                GearType.entries.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text(typeLabel(t, lang)) },
                                        onClick = { showTypeMenu = false; switchType(t) }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { viewModel.undo() }, enabled = viewModel.canUndo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, I18n.t(lang, "undo"))
                        }
                        IconButton(onClick = { viewModel.redo() }, enabled = viewModel.canRedo) {
                            Icon(Icons.AutoMirrored.Filled.Redo, I18n.t(lang, "redo"))
                        }
                        IconButton(onClick = {
                            val name = "Gear ${System.currentTimeMillis() / 1000}"
                            SavedConfigs.save(context, name, params)
                            Toast.makeText(context, I18n.t(lang, "saved"), Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Filled.Save, I18n.t(lang, "save")) }
                        Box {
                            IconButton(onClick = { showOverflow = true }) { Icon(Icons.Filled.MoreVert, I18n.t(lang, "more")) }
                            DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                                DropdownMenuItem(text = { Text(I18n.t(lang, "reset_view")) }, onClick = { showOverflow = false; glViewRef.value?.resetView() })
                                DropdownMenuItem(text = { Text(I18n.t(lang, "print_advice")) }, onClick = { showOverflow = false; showAdvice = true })
                                DropdownMenuItem(text = { Text(I18n.t(lang, "presets")) }, onClick = { showOverflow = false; showPresets = true })
                                DropdownMenuItem(text = { Text(I18n.t(lang, "open_saved")) }, onClick = { showOverflow = false; showOpen = true })
                                DropdownMenuItem(text = { Text(I18n.t(lang, "reset")) }, onClick = { showOverflow = false; viewModel.resetToDefault(type) })
                                DropdownMenuItem(text = { Text(I18n.t(lang, "settings")) }, onClick = { showOverflow = false; showSettings = true })
                            }
                        }
                    }
                    // Swipeable mode switcher (View / Parameters / Export).
                    val modes = listOf(
                        "view" to { selectedMode = "view"; showSheet = false },
                        "params" to { selectedMode = "params"; showSheet = true },
                        "export" to { selectedMode = "export"; showSheet = false; showExport = true }
                    )
                    LazyRow(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(modes.size) { i ->
                            val (key, action) = modes[i]
                            FilterChip(
                                selected = selectedMode == key,
                                onClick = action,
                                label = {
                                    Text(
                                        I18n.t(
                                            lang,
                                            when (key) {
                                                "view" -> "mode_view"
                                                "params" -> "mode_params"
                                                else -> "export"
                                            }
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // 3D viewport takes the full remaining area (Prio 6).
            AndroidView(
                factory = remember { { ctx: Context -> GearGLView(ctx) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { v ->
                glViewRef.value = v
            }
        }

        // Floating handle that opens the settings sheet.
        SmallFloatingActionButton(
            onClick = { selectedMode = "params"; showSheet = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, I18n.t(lang, "mode_params"))
        }
    }

    // Settings panel in a bottom sheet (Prio 3).
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false; selectedMode = "view" },
            sheetState = rememberModalBottomSheetState()
        ) {
            SettingsPanel(
                params = params,
                onNumber = { key, v -> viewModel.mutate(GearSpec.setNumber(params, key, v.toDouble())) },
                onChoice = { key, v -> viewModel.mutate(GearSpec.setChoice(params, key, v)) },
                lang = lang,
                sectionExpansion = sectionExpansion,
                modifier = Modifier.fillMaxWidth(),
                onToothOverrides = { ov -> viewModel.mutate(params.copy(toothOverrides = ov)) }
            )
        }
    }

    if (showExport) {
        ExportSheet(
            params = params,
            settings = settings,
            adManager = adManager,
            lang = lang,
            onDismiss = { showExport = false }
        )
    }

    if (showSettings) {
        SettingsDialog(
            darkTheme = darkTheme,
            onThemeChange = onThemeChange,
            lang = lang,
            onLangChange = onLangChange,
            settings = settings,
            billingManager = billingManager,
            onDismiss = { showSettings = false }
        )
    }

    if (showAdvice) {
        AdviceSheet(params, lang, onDismiss = { showAdvice = false })
    }

    if (showPresets) {
        PresetSheet(
            type = type,
            lang = lang,
            onSelect = { preset -> viewModel.mutate(preset.copy(unit = params.unit)) },
            onDismiss = { showPresets = false }
        )
    }

    if (showOpen) {
        OpenSheet(
            context = context,
            lang = lang,
            onSelect = { saved ->
                viewModel.applyLoaded(saved)
                framed = false
            },
            onDismiss = { showOpen = false }
        )
    }
}

@Composable
private fun AdviceSheet(params: GearParams, lang: I18n.Lang, onDismiss: () -> Unit) {
    val advice = PrintAdvisor.advice(params, nozzleMm = 0.4, layerHeightMm = 0.2, material = params.material)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t(lang, "print_advice")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (advice.isEmpty()) Text(I18n.t(lang, "no_specific_advice"))
                for (a in advice) {
                    // Core emits raw values (Doubles for numbers, Strings for text); format
                    // numeric placeholders locale-aware so Swedish shows a decimal comma.
                    val args = a.args.map { arg ->
                        when (arg) {
                            is Double -> Format.decimal(arg, 2, lang)
                            is Int -> arg.toString()
                            else -> arg.toString()
                        }
                    }.toTypedArray()
                    Text("\u2022 " + I18n.t(lang, a.key, *args), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(I18n.t(lang, "ok")) } }
    )
}

@Composable
private fun PresetSheet(type: GearType, lang: I18n.Lang, onSelect: (GearParams) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t(lang, "presets")) },
        text = {
            Column {
                for (p in Presets.forType(type)) {
                    TextButton(onClick = { onSelect(p.params); onDismiss() }) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                            Text(if (lang == I18n.Lang.SV) p.nameSv else p.nameEn)
                            Text(
                                if (lang == I18n.Lang.SV) p.descriptionSv else p.descriptionEn,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(I18n.t(lang, "close")) } }
    )
}

@Composable
private fun OpenSheet(context: android.content.Context, lang: I18n.Lang, onSelect: (GearParams) -> Unit, onDismiss: () -> Unit) {
    val saved = remember { SavedConfigs.list(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t(lang, "open_saved")) },
        text = {
            Column {
                if (saved.isEmpty()) Text(I18n.t(lang, "no_saved_files"))
                for ((name, p) in saved) {
                    TextButton(onClick = { onSelect(p); onDismiss() }) {
                        Text("$name \u2014 ${p.teeth}${I18n.t(lang, "unit_teeth_short")} ${I18n.t(lang, "unit_module_short")}${Format.decimal(p.module, 2, lang)}")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(I18n.t(lang, "close")) } }
    )
}

/** One-line localized summary of a per-tooth override (1-based tooth number). */
private fun overrideSummary(idx: Int, o: ToothOverride, lang: I18n.Lang): String {
    val parts = ArrayList<String>()
    o.leftPressureAngleDeg?.let { parts.add("\u03B1L ${Format.decimal(it, 2, lang)}\u00B0") }
    o.rightPressureAngleDeg?.let { parts.add("\u03B1R ${Format.decimal(it, 2, lang)}\u00B0") }
    o.toothThickness?.let { parts.add("${Format.decimal(it, 2, lang)} mm") }
    val body = if (parts.isEmpty()) "\u2014" else parts.joinToString(" · ")
    return "${I18n.t(lang, "tooth")} ${idx + 1} · $body"
}

/**
 * Per-tooth override editor: a focused form with validation, 1-based tooth
 * numbering, per-tooth edit/reset and a live 3D preview. Placed under the
 * "Tooth" section so it is clearly tied to the tooth being edited.
 */
@Composable
private fun ToothOverridePanel(params: GearParams, lang: I18n.Lang, onChange: (Map<Int, ToothOverride>) -> Unit) {
    val teeth = params.teeth
    var toothText by remember { mutableStateOf("") }
    var leftText by remember { mutableStateOf("") }
    var rightText by remember { mutableStateOf("") }
    var thickText by remember { mutableStateOf("") }
    var editingIdx by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    val left = leftText.trim().replace(',', '.').toDoubleOrNull()
    val right = rightText.trim().replace(',', '.').toDoubleOrNull()
    val thick = thickText.trim().replace(',', '.').toDoubleOrNull()
    val tooth1 = toothText.trim().toIntOrNull()
    val toothIdx = tooth1?.minus(1)
    val toothValid = tooth1 != null && tooth1 in 1..teeth
    val hasValue = left != null || right != null || thick != null
    // Domain limits (point 11): pressure angle is physically bounded to (0°, 90°);
    // tooth thickness must fit within one pitch (π·m) so teeth never overlap.
    val pitch = Math.PI * params.module
    val leftOk = left == null || (left > 0.0 && left < 89.0)
    val rightOk = right == null || (right > 0.0 && right < 89.0)
    val thickOk = thick == null || (thick > 0.0 && thick < pitch)
    val paWarn = (left != null && (left < 5.0 || left > 45.0)) ||
        (right != null && (right < 5.0 || right > 45.0))
    val thickWarn = thick != null && thick > pitch / 2.0
    val warning = when {
        paWarn -> I18n.t(lang, "override_pa_warn")
        thickWarn -> I18n.t(lang, "override_thick_warn")
        else -> null
    }
    val canSubmit = toothValid && hasValue && leftOk && rightOk && thickOk

    fun resetForm() {
        toothText = ""; leftText = ""; rightText = ""; thickText = ""
        editingIdx = null; error = null
    }

    fun startEdit(idx: Int) {
        val o = params.toothOverrides[idx] ?: return
        toothText = (idx + 1).toString()
        leftText = o.leftPressureAngleDeg?.let { Format.decimal(it, 2, lang) } ?: ""
        rightText = o.rightPressureAngleDeg?.let { Format.decimal(it, 2, lang) } ?: ""
        thickText = o.toothThickness?.let { Format.decimal(it, 2, lang) } ?: ""
        editingIdx = idx
        error = null
    }

    fun submit() {
        error = when {
            !toothValid -> I18n.t(lang, "override_invalid_tooth") + " 1\u2013$teeth."
            !hasValue -> I18n.t(lang, "override_empty")
            !leftOk || !rightOk -> I18n.t(lang, "override_pa_range")
            !thickOk -> I18n.t(lang, "override_thick_range")
            else -> null
        }
        if (error != null) return
        val idx = toothIdx ?: return
        val o = ToothOverride(
            leftPressureAngleDeg = left,
            rightPressureAngleDeg = right,
            toothThickness = thick
        )
        // Adding an override already clears the form, so a second Done/Enter (or a double
        // tap on Add) is a no-op and can never register the same override twice.
        onChange(params.toothOverrides + (idx to o))
        resetForm()
        focus.clearFocus()
        keyboard?.hide()
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            if (params.toothOverrides.isEmpty()) I18n.t(lang, "per_tooth")
            else I18n.t(lang, "per_tooth") + " · ${params.toothOverrides.size}",
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            I18n.t(lang, "per_tooth_hint"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (params.toothOverrides.isNotEmpty()) {
            Text(
                I18n.t(lang, "override_edit_hint"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            params.toothOverrides.entries.sortedBy { it.key }.forEach { (idx, o) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        overrideSummary(idx, o, lang),
                        Modifier
                            .weight(1f)
                            .clickable { startEdit(idx) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = { startEdit(idx) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = I18n.t(lang, "update"))
                    }
                    IconButton(onClick = { onChange(params.toothOverrides - idx) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = I18n.t(lang, "remove_override"))
                    }
                }
            }
            TextButton(onClick = { onChange(emptyMap()); resetForm() }) { Text(I18n.t(lang, "clear")) }
        }

        OutlinedTextField(
            value = toothText,
            onValueChange = { toothText = it },
            label = { Text(I18n.t(lang, "tooth_number")) },
            supportingText = { Text("${I18n.t(lang, "valid_range")} 1\u2013$teeth") },
            singleLine = true,
            isError = toothText.isNotEmpty() && !toothValid,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
        )
        OutlinedTextField(
            value = leftText,
            onValueChange = { leftText = it },
            label = { Text(I18n.t(lang, "left_pressure")) },
            suffix = { Text("\u00B0") },
            singleLine = true,
            isError = left != null && !leftOk,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
        )
        OutlinedTextField(
            value = rightText,
            onValueChange = { rightText = it },
            label = { Text(I18n.t(lang, "right_pressure")) },
            suffix = { Text("\u00B0") },
            singleLine = true,
            isError = right != null && !rightOk,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
        )
        OutlinedTextField(
            value = thickText,
            onValueChange = { thickText = it },
            label = { Text(I18n.t(lang, "tooth_thickness")) },
            suffix = { Text(I18n.t(lang, "mm")) },
            singleLine = true,
            isError = thick != null && !thickOk,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() })
        )
        error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        warning?.let {
            Text(
                "\u26A0 $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (editingIdx != null) {
                TextButton(onClick = { resetForm() }) { Text(I18n.t(lang, "cancel")) }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { submit() }, enabled = canSubmit) {
                Text(if (editingIdx != null) I18n.t(lang, "update") else I18n.t(lang, "add"))
            }
        }
    }
}

/** Section header: accent stripe, localized title, badges and an expand/collapse chevron. */
@Composable
private fun GroupHeader(
    group: ParamGroup,
    lang: I18n.Lang,
    expanded: Boolean,
    onToggle: () -> Unit,
    count: Int = 0,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    val advanced = group == ParamGroup.TEETH || group == ParamGroup.LIGHTENING
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(22.dp)
                .background(accent, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(10.dp))
        Text(groupLabel(group, lang), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (count > 0) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        if (advanced) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Text(
                    I18n.t(lang, "advanced"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = I18n.t(lang, if (expanded) "collapse_section" else "expand_section"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun SettingsPanel(
    params: GearParams,
    onNumber: (String, Float) -> Unit,
    onChoice: (String, String) -> Unit,
    lang: I18n.Lang,
    modifier: Modifier = Modifier,
    sectionExpansion: SectionExpansion? = null,
    onToothOverrides: ((Map<Int, ToothOverride>) -> Unit)? = null
) {
    val defs = GearSpec.fields(params)
    val results = GearSpec.results(params.gearType, params) { v, d -> Format.decimal(v, d, lang) }
    val warnings = remember(params) { GearSpec.validate(params) }
    val expansion = sectionExpansion ?: rememberSaveable(
        saver = listSaver(
            save = { listOf(it.collapsedNames) },
            restore = { SectionExpansion(it[0]) }
        )
    ) { SectionExpansion() }
    Column(modifier.verticalScroll(rememberScrollState())) {
        val errors = warnings.filter { it.severity == GearSeverity.ERROR }
        val soft = warnings.filter { it.severity != GearSeverity.ERROR }
        if (errors.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(Modifier.padding(8.dp)) {
                    for (w in errors) {
                        Text(
                            "\u2716 " + I18n.t(lang, "validation_" + w.code),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
        if (soft.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(Modifier.padding(8.dp)) {
                    for (w in soft) {
                        Text(
                            "\u26A0 " + I18n.t(lang, "validation_" + w.code),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }
        ParamGroup.entries.forEach { group ->
            val groupDefs = defs.filter { it.group == group }
            if (groupDefs.isNotEmpty()) {
                val expanded = expansion.isExpanded(group)
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Column {
                        GroupHeader(
                            group = group,
                            lang = lang,
                            expanded = expanded,
                            onToggle = { expansion.toggle(group) },
                            count = if (group == ParamGroup.TEETH) params.toothOverrides.size else 0,
                            accent = groupAccent(group)
                        )
                        AnimatedVisibility(
                            visible = expanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column {
                                val hintKey = "group_" + group.name.lowercase() + "_hint"
                                val hint = I18n.t(lang, hintKey).takeUnless { it == hintKey }
                                if (hint != null) {
                                    Text(
                                        hint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                                    )
                                }
                                groupDefs.forEach { def ->
                                    when (def.kind) {
                                        FieldKind.NUMBER -> NumberRow(def, GearSpec.getNumber(params, def.key).toFloat(), params, lang) { onNumber(def.key, it) }
                                        FieldKind.CHOICE -> ChoiceRow(def, GearSpec.getChoice(params, def.key), lang) { onChoice(def.key, it) }
                                        else -> {}
                                    }
                                }
                                if (group == ParamGroup.TEETH && onToothOverrides != null && GearSpec.hasGearBody(params.gearType)) {
                                    ToothOverridePanel(params = params, lang = lang, onChange = onToothOverrides)
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }
        if (results.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Box(
                            Modifier
                                .width(4.dp)
                                .height(22.dp)
                                .background(groupAccent(ParamGroup.RESULTS), RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(groupLabel(ParamGroup.RESULTS, lang), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        I18n.t(lang, "results_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    results.forEach { (key, value) -> CalculatedRow(I18n.t(lang, key), value) }
                }
            }
        }
    }
}

@Composable
private fun ExportSheet(
    params: GearParams,
    settings: SettingsStore,
    adManager: AdManager,
    lang: I18n.Lang,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(ExportManager.Format.STL) }
    var gateMessage by remember { mutableStateOf<String?>(null) }
    var exportFailed by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0f) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var confirmErrors by remember { mutableStateOf(false) }

    // Reactive monetization state so the gate updates after a purchase or consumed export.
    var isPro by remember { mutableStateOf(settings.isPro) }
    var freeLeft by remember { mutableStateOf(settings.freeAdvancedExports) }

    val highQuality = isPro && settings.highQuality
    var preview by remember(params, highQuality) { mutableStateOf<ExportPreview?>(null) }
    LaunchedEffect(params, highQuality) {
        preview = withContext(Dispatchers.Default) {
            val mesh = ExportManager.mesh(params, highQuality)
            val b = MeshOps.bounds(mesh)
            ExportPreview(mesh.triangles.size, b.x, b.y, b.z)
        }
    }

    // The filename uses a locale-neutral decimal so the actual saved file is predictable.
    val base = "gear_${params.teeth}t_m${String.format(java.util.Locale.US, "%.2f", params.module)}"

    // Point 18: export runs off the UI thread with progress + cancel.
    fun doExport(consumeFree: Boolean) {
        if (exporting) return
        exporting = true
        exportProgress = 0f
        gateMessage = null
        exportJob = scope.launch {
            try {
                val result = ExportManager.export(context, params, format, highQuality, base) {
                    exportProgress = it
                }
                if (result.isSuccess) {
                    // Consume the free export only after the file is actually written so
                    // a failed export never burns the user's entitlement.
                    if (consumeFree) freeLeft = settings.consumeAdvancedExport()
                    Toast.makeText(context, I18n.t(lang, "export_done"), Toast.LENGTH_SHORT).show()
                    onDismiss()
                } else {
                    exportFailed = true
                }
            } finally {
                exporting = false
            }
        }
    }

    // Phase-1 gating (Pro / free exports / rewarded ad). The ad flow never navigates
    // away or crashes: a failed/dismissed ad only surfaces a message and stays put.
    fun launchExport() {
        when {
            isPro -> doExport(consumeFree = false)
            freeLeft > 0 -> doExport(consumeFree = true)
            else -> adManager.showRewarded(
                onReward = { doExport(consumeFree = false) },
                onDismissed = { gateMessage = I18n.t(lang, "ad_dismissed") },
                onUnavailable = { gateMessage = I18n.t(lang, "ad_unavailable") }
            )
        }
    }

    fun startExport() {
        if (exporting) return
        // Hard validation failures block export until the user explicitly overrides
        // (audit C4) — a physical impossibility should not become a file by accident.
        if (GearSpec.validate(params).any { it.severity == GearSeverity.ERROR }) {
            confirmErrors = true
            return
        }
        launchExport()
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        exporting = false
        exportProgress = 0f
    }

    AlertDialog(
        onDismissRequest = { if (!exporting) onDismiss() },
        title = { Text(I18n.t(lang, "export")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ExportManager.Format.entries.forEach { f ->
                    FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
                }
                Text(
                    "${I18n.t(lang, "export_filename")}: $base${format.ext}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                preview?.let { p ->
                    if (format == ExportManager.Format.STL || format == ExportManager.Format.THREE_MF) {
                        Text(
                            "${I18n.t(lang, "export_triangles")}: ${p.triangles}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Text(
                        "${I18n.t(lang, "export_dimensions")}: ${Format.dims(p.w, p.h, p.d, 1, lang, settings.useInch)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text(
                    I18n.t(lang, "export_downloads_hint"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (!isPro) {
                    Text(
                        if (freeLeft > 0) "${I18n.t(lang, "free_exports")}: $freeLeft"
                        else I18n.t(lang, "free_exports_used"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                gateMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (exporting) {
                    Text(
                        "${I18n.t(lang, "exporting")} ${(exportProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    LinearProgressIndicator(
                        progress = { exportProgress },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (exporting) {
                Button(onClick = { cancelExport() }) { Text(I18n.t(lang, "cancel")) }
            } else {
                Button(onClick = { startExport() }) {
                    Text(if (isPro || freeLeft > 0) I18n.t(lang, "download") else I18n.t(lang, "watch_ad"))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !exporting) { Text(I18n.t(lang, "cancel")) }
        }
    )

    if (confirmErrors) {
        val hardErrors = GearSpec.validate(params).filter { it.severity == GearSeverity.ERROR }
        AlertDialog(
            onDismissRequest = { confirmErrors = false },
            title = { Text(I18n.t(lang, "export_errors_title")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(I18n.t(lang, "export_errors_body"), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    for (w in hardErrors) {
                        Text(
                            "\u2022 " + I18n.t(lang, "validation_" + w.code),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { confirmErrors = false; launchExport() }) {
                    Text(I18n.t(lang, "export_anyway"))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmErrors = false }) { Text(I18n.t(lang, "cancel")) }
            }
        )
    }

    if (exportFailed) {
        AlertDialog(
            onDismissRequest = { exportFailed = false },
            title = { Text(I18n.t(lang, "export_failed")) },
            confirmButton = { TextButton(onClick = { exportFailed = false }) { Text(I18n.t(lang, "ok")) } }
        )
    }
}

@Composable
fun SettingsDialog(
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
    lang: I18n.Lang,
    onLangChange: (I18n.Lang) -> Unit,
    settings: SettingsStore,
    billingManager: BillingManager,
    onDismiss: () -> Unit
) {
    var isPro by remember { mutableStateOf(settings.isPro) }
    var proMessage by remember { mutableStateOf<String?>(null) }
    var showPrivacy by remember { mutableStateOf(false) }

    // Keep the Pro badge in sync with purchase/restore results that arrive asynchronously.
    DisposableEffect(billingManager) {
        billingManager.onProChanged = { isPro = it }
        onDispose { billingManager.onProChanged = null }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t(lang, "settings")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(I18n.t(lang, "theme"), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = darkTheme, onClick = { onThemeChange(true) }, label = { Text(I18n.t(lang, "dark")) })
                    FilterChip(selected = !darkTheme, onClick = { onThemeChange(false) }, label = { Text(I18n.t(lang, "light")) })
                }
                Spacer(Modifier.padding(4.dp))
                Text(I18n.t(lang, "language"), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = lang == I18n.Lang.EN, onClick = { onLangChange(I18n.Lang.EN) }, label = { Text(I18n.t(lang, "english")) })
                    FilterChip(selected = lang == I18n.Lang.SV, onClick = { onLangChange(I18n.Lang.SV) }, label = { Text(I18n.t(lang, "swedish")) })
                }
                Spacer(Modifier.padding(8.dp))
                Text(I18n.t(lang, "pro_section"), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (isPro) I18n.t(lang, "pro_status_active") else I18n.t(lang, "pro_status_free"),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (isPro) {
                    Text(I18n.t(lang, "pro_thanks"), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(I18n.t(lang, "remove_ads"), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        proMessage = null
                        billingManager.purchasePro { started ->
                            if (!started) proMessage = I18n.t(lang, "purchase_failed")
                        }
                    }) { Text(I18n.t(lang, "upgrade")) }
                }
                TextButton(onClick = {
                    proMessage = null
                    billingManager.restorePurchases { restored ->
                        isPro = settings.isPro
                        proMessage = I18n.t(lang, if (restored) "restore_success" else "restore_none")
                    }
                }) { Text(I18n.t(lang, "restore_purchases")) }
                proMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.padding(8.dp))
                // Price-model clarity: what is free vs Pro vs rewarded ad (point 16).
                Text(I18n.t(lang, "free_plan_title"), style = MaterialTheme.typography.titleSmall)
                Text(I18n.t(lang, "free_plan_body"), style = MaterialTheme.typography.bodySmall)
                Text(I18n.t(lang, "pro_plan_body"), style = MaterialTheme.typography.bodySmall)
                Text(I18n.t(lang, "ad_plan_body"), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.padding(8.dp))
                TextButton(onClick = { showPrivacy = true }) { Text(I18n.t(lang, "privacy_policy")) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(I18n.t(lang, "ok")) } }
    )

    if (showPrivacy) {
        PrivacyPolicyDialog(lang = lang, onDismiss = { showPrivacy = false })
    }
}

@Composable
private fun PrivacyPolicyDialog(lang: I18n.Lang, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t(lang, "privacy_policy")) },
        text = { Text(I18n.t(lang, "privacy_summary")) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(I18n.t(lang, "ok")) } }
    )
}
