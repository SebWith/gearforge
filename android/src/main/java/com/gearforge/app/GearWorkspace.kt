package com.gearforge.app

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import com.gearforge.core.FieldKind
import com.gearforge.core.GearAssembly
import com.gearforge.core.GearBuilder
import com.gearforge.core.GearPalette
import com.gearforge.core.GearParams
import com.gearforge.core.GearSeverity
import com.gearforge.core.GearSpec
import com.gearforge.core.GearType
import com.gearforge.core.MeshKinematics
import com.gearforge.core.MeshOps
import com.gearforge.core.ParamDef
import com.gearforge.core.ParamGroup
import com.gearforge.core.Presets
import com.gearforge.core.PrintAdvisor
import com.gearforge.core.ToothOverride
import com.gearforge.core.ToothPick
import com.gearforge.core.UnitSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Snapshot of the mesh statistics shown in the export preview (point 12). */
private data class ExportPreview(val triangles: Int, val w: Double, val h: Double, val d: Double)

/** Debounce delay before a parameter change triggers a mesh rebuild (point 17). */
private const val MESH_DEBOUNCE_MS = 200L

/** Maximum number of cached mesh assemblies (bounds memory during long sessions). */
private const val MAX_MESH_CACHE = 24

/**
 * Collapse/expand state for the parameter sections. Held above the bottom sheet so it
 * survives switching between View and Parameters without losing the user's layout;
 * persisted across configuration changes via [androidx.compose.runtime.saveable.rememberSaveable].
 */
internal class SectionExpansion(initialCollapsed: String = "MATERIAL,TOLERANCES,LOAD,HUB,TEETH,LIGHTENING") {
    var collapsedNames by mutableStateOf(initialCollapsed)
        private set

    fun isExpanded(group: ParamGroup): Boolean =
        group.name !in collapsedNames.split(',').filter { it.isNotEmpty() }

    fun toggle(group: ParamGroup) {
        val names = collapsedNames.split(',').filter { it.isNotEmpty() }.toMutableSet()
        if (!names.add(group.name)) names.remove(group.name)
        collapsedNames = names.joinToString(",")
    }

    /**
     * Opens a section, without toggling it shut if it is already open. Used when something outside
     * the panel targets a section — tapping a tooth in the viewport has to land on the tooth form
     * whether or not the user had the section folded away.
     */
    fun expand(group: ParamGroup) {
        val names = collapsedNames.split(',').filter { it.isNotEmpty() }.toMutableSet()
        if (names.remove(group.name)) collapsedNames = names.joinToString(",")
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
    onBack: () -> Unit,
    tipStep: Int = Tips.DONE,
    onTipAdvance: () -> Unit = {},
    onRestartTips: () -> Unit = {}
) {
    val context = LocalContext.current

    val type = viewModel.gearType
    val params = viewModel.params ?: return

    var framed by remember { mutableStateOf(false) }

    // Tooth tapped in the viewport (0-based). Cleared on a type switch, because the index only
    // means something for a body that turns about Z at a known pitch.
    var tappedTooth by remember { mutableStateOf<Int?>(null) }
    // Set when the user asks to edit the tapped tooth: the sheet opens and the per-tooth form is
    // prefilled with that tooth, so pointing at a tooth replaces typing its number.
    var pendingToothEdit by remember { mutableStateOf<Int?>(null) }

    fun switchType(newType: GearType) {
        if (newType == type) return
        viewModel.switchType(newType)
        framed = false
        // A tooth index belongs to one tooth count and one axial convention; drop it on a type change.
        tappedTooth = null
        pendingToothEdit = null
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
        // Bound the cache (LinkedHashMap insertion order): evict the oldest entry so a
        // long editing session cannot retain every previously built mesh (audit H12).
        if (meshCache.size >= MAX_MESH_CACHE) {
            meshCache.keys.firstOrNull()?.let { meshCache.remove(it) }
        }
        meshCache[params] = built
        assembly = built
    }

    val glViewRef = remember { mutableStateOf<GearGLView?>(null) }
    val tapped = tappedTooth?.takeIf { ToothPick.supports(type) && it in 0 until params.teeth }
    // Meshing playback. Off by default: the viewport is a measuring instrument first, and a gear
    // that never stops turning is tiring to measure. Reduced motion hides the control entirely
    // rather than animating slowly.
    var playing by remember { mutableStateOf(false) }
    // Playback rate, mirrored into SettingsStore. Applied to the renderer rather than to the instance
    // list: a new instance list clears the spin phase and re-uploads the VBOs, so the gear would jump
    // back to its start orientation every time the user reached for the speed.
    var playbackSpeed by remember { mutableFloatStateOf(nearestPlaybackSpeed(settings.playbackSpeed)) }
    val reduceMotion = rememberReduceMotion()
    // Which body the legend is pointing at. The others are drawn dimmed while this is set, so the
    // legend answers "which shape is the ring?" in the viewport rather than only in a list.
    var focusedBody by remember { mutableStateOf<Int?>(null) }
    val darkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5
    // Read once, outside the helper: a local function is not a composable and cannot reach the theme
    // itself, and the dimmed colour has to be mixed towards the surface the bodies actually sit on.
    val surfaceColour = MaterialTheme.colorScheme.surface

    /**
     * Colour for body [index]: its own hue, or a washed-out version when another body is focused.
     * Mixing towards the surface is what dims it — no shader change, and the dimmed body stays
     * legible as a shape.
     */
    fun bodyColour(index: Int): Int {
        val base = Color(GearPalette.bodyArgb(index, darkSurface))
        return if (focusedBody == null || focusedBody == index) {
            base.toArgb()
        } else {
            lerp(base, surfaceColour, 0.72f).toArgb()
        }
    }

    // The bodies the viewport shows, in the assembly's own order. Read before the viewport itself
    // because two panels inside it need the same list: the legend names the bodies, and the
    // measurement HUD has to know how many rows that legend is occupying in the corner they share
    // (see `ViewportChrome` — they used to be drawn on top of each other).
    val bodyKeys = remember(params) { GearBuilder.bodyKeys(params) }

    // Keyed on what changes the *geometry*, never on `playing`. A new instance list clears the
    // renderer's spin phase and re-uploads the VBOs (see `GearGLView.playbackScale`), so expressing
    // "pause" by rebuilding this list snapped the mesh back to the orientation it was built with.
    // Pause travels as a rate instead — see the `playbackScale` assignment in the AndroidView below.
    val instances = remember(assembly, params, tapped, reduceMotion, focusedBody, darkSurface) {
        assembly?.let { a ->
            // Each body gets the speed its mesh relationship demands, computed in core where it can
            // be proved (MeshKinematicsTest) instead of being guessed in the renderer. The motions
            // are attached whether or not the train is running: whether time advances is the clock's
            // business, and a list that changed with the play button could not be.
            val motions = if (reduceMotion) {
                emptyList()
            } else {
                MeshKinematics.motions(params, MeshKinematics.DEFAULT_SPEED_RAD_PER_S)
            }
            val list = ArrayList<GearGLView.Instance>(a.meshes.size + params.toothOverrides.size)
            // One colour per body, and only when there is more than one: a single gear keeps the
            // plain material colour it has always had, because tinting it would claim a difference
            // that does not exist.
            val multiBody = a.meshes.size > 1
            a.meshes.forEachIndexed { i, m ->
                val motion = motions.getOrNull(i)
                list.add(
                    GearGLView.Instance(
                        mesh = m,
                        offsetX = a.offsets[i].x.toFloat(),
                        offsetY = a.offsets[i].y.toFloat(),
                        spinSpeed = motion?.spinSpeed?.toFloat() ?: 0f,
                        orbitSpeed = motion?.orbitSpeed?.toFloat() ?: 0f,
                        slideSpeed = motion?.slideSpeed?.toFloat() ?: 0f,
                        aboutX = motion?.aboutX ?: false,
                        colorArgb = if (multiBody) bodyColour(i) else null
                    )
                )
            }
            // Overlay a distinct-colour wedge over each overridden tooth so the edit is
            // visible live in the viewport (point 11: per-tooth override preview). The tooth the
            // user just tapped is marked the same way, which is the feedback that the tap landed
            // where they aimed.
            if (GearSpec.hasGearBody(params.gearType)) {
                val marked = params.toothOverrides.keys + listOfNotNull(tapped)
                // A wedge marks a tooth, so it has to turn with the body that tooth belongs to.
                // Left still while the gear spins, it would slide off the tooth it is marking.
                val first = motions.firstOrNull()
                marked.sorted().distinct().forEach { idx ->
                    list.add(
                        GearGLView.Instance(
                            GearBuilder.toothHighlightMesh(params, idx),
                            offsetX = 0f,
                            offsetY = 0f,
                            spinSpeed = first?.spinSpeed?.toFloat() ?: 0f,
                            highlight = true,
                            orbitSpeed = first?.orbitSpeed?.toFloat() ?: 0f,
                            slideSpeed = first?.slideSpeed?.toFloat() ?: 0f,
                            aboutX = first?.aboutX ?: false
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

    // The viewport renders on demand, so an animation has to ask for frames. Every other display
    // frame is plenty for a gear and leaves the GPU idle the rest of the time.
    LaunchedEffect(playing, reduceMotion) {
        if (!playing || reduceMotion) return@LaunchedEffect
        var frame = 0
        while (true) {
            withFrameNanos { }
            if (frame++ % 2 == 0) glViewRef.value?.requestFrame()
        }
    }

    var showExport by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAdvice by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var showOpen by remember { mutableStateOf(false) }
    // Units. Only the gear types whose geometry is quoted in module or diametral pitch have a unit
    // field, so the switcher is offered only there. It also writes SettingsStore.useInch, which is
    // what the export preview reads — before this, the two notions of "inch mode" could disagree.
    val hasUnitField = GearSpec.fields(params).any { it.key == "unit" }
    val inchMode = params.unit == UnitSystem.INCH
    var showTypeMenu by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var selectedMode by remember { mutableStateOf("view") }
    // Measurement HUD state, mirrored into SettingsStore so the choice survives a restart.
    var hudEnabled by remember { mutableStateOf(settings.hudEnabled) }
    var hudScaleBar by remember { mutableStateOf(settings.hudScaleBar) }
    // Print-bed overlay, also mirrored into SettingsStore. Toggling it re-frames the camera, because
    // the whole point of a true-scale platen is to see the gear sitting on it.
    var showBed by remember { mutableStateOf(settings.showBed) }
    // Re-frame whenever the platen appears or changes size.
    //
    // The frame has to be computed *after* the view knows the bed size, and the view is only told in
    // the `AndroidView` update lambda further down. Framing from the menu's click handler ran too
    // early: the camera stayed framed on the gear, so a 220 mm platen was drawn roughly four times
    // wider than the viewport — the bed edges, which are the entire point of showing it, ended up
    // off screen and the platen read as "some grid" instead of "a 220 mm bed".
    LaunchedEffect(showBed, settings.bedSizeMm) {
        val v = glViewRef.value ?: return@LaunchedEffect
        v.bedSizeMm = if (showBed) settings.bedSizeMm.toFloat() else 0f
        v.autoFrame()
    }
    // Parameter-section expand/collapse state lives here (not inside the bottom sheet) so it
    // survives switching between View and Parameters without losing the user's layout.
    val sectionExpansion = rememberSaveable(
        saver = listSaver(
            save = { listOf(it.collapsedNames) },
            restore = { SectionExpansion(it[0]) }
        )
    ) { SectionExpansion() }

    // Undo affordance for destructive edits in the parameter sheet. The host lives inside the
    // sheet's own content: a host in the main layout would sit *behind* the modal sheet, where
    // the user could never reach it.
    val sheetSnackbar = remember { SnackbarHostState() }
    val sheetScope = rememberCoroutineScope()
    // Scrub preview of the value under a slider thumb. View-only: it is deliberately not part of
    // EditorViewModel, because mutate() records an undo step and a single drag would push dozens of
    // snapshots and evict the user's real history.
    var scrubPreview by remember { mutableStateOf<GearParams?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
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
                        Box(Modifier.weight(1f)) {
                            // Named as what it is: a menu of gear types. The bare type name read as a
                            // label, not as the control that changes it.
                            TextButton(
                                onClick = { showTypeMenu = true },
                                modifier = Modifier.semantics {
                                    contentDescription = I18n.t(lang, "gear_type_button", typeLabel(type, lang))
                                }
                            ) {
                                Text(
                                    typeLabel(type, lang),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    // Already in the button's name; read twice otherwise.
                                    modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics { }
                                )
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(expanded = showTypeMenu, onDismissRequest = { showTypeMenu = false }) {
                                GearType.entries.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text(typeLabel(t, lang)) },
                                        onClick = { showTypeMenu = false; switchType(t) },
                                        trailingIcon = if (t == type) {
                                            { Icon(Icons.Filled.Check, contentDescription = null) }
                                        } else null,
                                        modifier = Modifier.semantics { selected = t == type }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { viewModel.undo() }, enabled = viewModel.canUndo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, I18n.t(lang, "undo"))
                        }
                        IconButton(onClick = { viewModel.redo() }, enabled = viewModel.canRedo) {
                            Icon(Icons.AutoMirrored.Filled.Redo, I18n.t(lang, "redo"))
                        }
                        IconButton(onClick = {
                            val name = "Gear ${System.currentTimeMillis() / 1000}"
                            val result = SavedConfigs.save(context, name, params)
                            val message = if (result.isSuccess) "saved" else "save_failed"
                            Toast.makeText(context, I18n.t(lang, message), Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Filled.Save, I18n.t(lang, "save")) }
                        Box {
                            IconButton(onClick = { showOverflow = true }) { Icon(Icons.Filled.MoreVert, I18n.t(lang, "more")) }
                            DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                                DropdownMenuItem(text = { Text(I18n.t(lang, "reset_view")) }, onClick = { showOverflow = false; glViewRef.value?.resetView() })
                                DropdownMenuItem(
                                    text = { Text(I18n.t(lang, if (hudEnabled) "hud_hide" else "hud_show")) },
                                    onClick = {
                                        showOverflow = false
                                        hudEnabled = !hudEnabled
                                        settings.hudEnabled = hudEnabled
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(I18n.t(lang, if (showBed) "bed_hide" else "bed_show")) },
                                    onClick = {
                                        showOverflow = false
                                        showBed = !showBed
                                        settings.showBed = showBed
                                        // The re-frame happens in a LaunchedEffect keyed on showBed:
                                        // autoFrame includes the platen, and it has to run after the
                                        // view has been told the bed size.
                                    }
                                )
                                if (hasUnitField) {
                                    DropdownMenuItem(
                                        text = { Text(I18n.t(lang, if (inchMode) "units_to_metric" else "units_to_inch")) },
                                        onClick = {
                                            showOverflow = false
                                            val next = if (inchMode) UnitSystem.MM else UnitSystem.INCH
                                            settings.useInch = next == UnitSystem.INCH
                                            viewModel.mutate(GearSpec.setUnit(params, next))
                                        }
                                    )
                                }
                                if (hudEnabled) {
                                    // A switch in a menu: its state is shown and announced, not only its name.
                                    DropdownMenuItem(
                                        text = { Text(I18n.t(lang, "hud_scale_bar")) },
                                        onClick = {
                                            showOverflow = false
                                            hudScaleBar = !hudScaleBar
                                            settings.hudScaleBar = hudScaleBar
                                        },
                                        trailingIcon = if (hudScaleBar) {
                                            { Icon(Icons.Filled.Check, contentDescription = null) }
                                        } else null,
                                        modifier = Modifier.semantics { toggleableState = ToggleableState(hudScaleBar) }
                                    )
                                }
                                DropdownMenuItem(text = { Text(I18n.t(lang, "print_advice")) }, onClick = { showOverflow = false; showAdvice = true })
                                DropdownMenuItem(text = { Text(I18n.t(lang, "presets")) }, onClick = { showOverflow = false; showPresets = true })
                                DropdownMenuItem(text = { Text(I18n.t(lang, "open_saved")) }, onClick = { showOverflow = false; showOpen = true })
                                DropdownMenuItem(text = { Text(I18n.t(lang, "reset")) }, onClick = { showOverflow = false; viewModel.resetToDefault(type) })
                                DropdownMenuItem(text = { Text(I18n.t(lang, "settings")) }, onClick = { showOverflow = false; showSettings = true })
                            }
                        }
                    }
                    // Three modes. Export belongs here, next to Parameters: it is a thing you do to
                    // the model you are looking at, and the viewport is where you are looking at it.
                    // It was briefly promoted to a floating button in the middle of the viewport,
                    // where it sat on top of the model it was exporting and covered the platen.
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
                            SelectChip(
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
                    // Playback speed, as chrome rather than as a control floating over the model. A
                    // chip row inside the viewport lands wherever the model happens to be after an
                    // orbit — on a dimension label or on the bed ruler — and the viewport belongs to
                    // the model and its measurements. It appears only while the train is moving:
                    // that is the only moment a rate means anything, and the moment the user wants
                    // to compare two of them. FilterChip (not TextButton) so the current rate is
                    // exposed as a selection, which is what it is.
                    if (!reduceMotion && playing) {
                        // Scrolls rather than squeezes: at a large font scale four chips and the label
                        // are wider than a phone.
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                I18n.t(lang, "playback_speed"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            for (speed in PLAYBACK_SPEEDS) {
                                SelectChip(
                                    selected = speed == playbackSpeed,
                                    onClick = {
                                        playbackSpeed = speed
                                        settings.playbackSpeed = speed
                                    },
                                    label = {
                                        Text(
                                            Format.decimal(
                                                speed.toDouble(),
                                                playbackSpeedDecimals(speed),
                                                lang
                                            ) + "\u00D7",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                )
                            }
                        }
                    }
                    // The walkthrough's editor tips (see Tips), in the top bar's own strip rather
                    // than floating over the viewport: a hint about the model must never be the thing
                    // that hides the model.
                    Tips.editorTip(tipStep)?.let { tip ->
                        TipStrip(
                            text = I18n.t(lang, "coach_" + (tip - Tips.EDITOR_ORBIT + 1)),
                            lang = lang,
                            isLast = tip == Tips.EDITOR_TOOTH,
                            onNext = onTipAdvance,
                            onDismiss = onTipAdvance
                        )
                    }
                }
            }

            // 3D viewport takes the full remaining area (Prio 6), with a Blender-style
            // navigation gizmo overlaid at its top-trailing corner. The gizmo consumes
            // only taps on its 72x72 area; drags/pinches are forwarded back to the GL
            // view so orbit/pan/zoom keep working as if the gesture started on the mesh.
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Accessibility actions registered on the GL view, re-registered only when the language
                // changes so their labels follow it.
                val viewportActionIds = remember { mutableListOf<Int>() }
                val viewportActionLang = remember { arrayOfNulls<I18n.Lang>(1) }
                AndroidView(
                    factory = remember {
                        { ctx: Context ->
                            GearGLView(ctx).apply {
                                // A TextureView is not announced by default, so a screen reader skipped the
                                // model entirely. It is described, but never focusable: no focus trap.
                                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) { v ->
                    glViewRef.value = v
                    v.contentDescription = I18n.t(lang, "viewport_desc", typeLabel(type, lang))
                    if (viewportActionLang[0] != lang) {
                        viewportActionIds.forEach { ViewCompat.removeAccessibilityAction(v, it) }
                        viewportActionIds.clear()
                        for (step in CameraStep.entries) {
                            viewportActionIds += ViewCompat.addAccessibilityAction(v, I18n.t(lang, step.labelKey)) { view, _ ->
                                val gl = view as GearGLView
                                if (step.zoom != 1f) gl.zoomByScale(step.zoom) else gl.orbitBy(step.orbitDxPx, step.orbitDyPx)
                                true
                            }
                        }
                        viewportActionIds += ViewCompat.addAccessibilityAction(v, I18n.t(lang, "reset_view")) { view, _ ->
                            (view as GearGLView).resetView()
                            true
                        }
                        viewportActionLang[0] = lang
                    }
                    // The bed follows the printer profile, and 0 means hidden.
                    v.bedSizeMm = if (showBed) settings.bedSizeMm.toFloat() else 0f
                    // A parked rate while the train is stopped. Pause is sent here rather than as a
                    // new instance list, so the mesh keeps the angle it had reached and resumes from
                    // it — the clock holds its coordinate at a rate of zero (PlaybackClock).
                    v.playbackScale = if (playing && !reduceMotion) playbackSpeed else 0f
                    // Pointing at a tooth is how the number gets chosen: the index is computed from
                    // the tap angle against the outline (core.ToothPick), not by raycasting the mesh.
                    v.onPick = { px, py, _ ->
                        tappedTooth = if (ToothPick.supports(params.gearType)) {
                            ToothPick.indexAtPoint(px.toDouble(), py.toDouble(), params)
                        } else null
                    }
                }
                val glView = glViewRef.value
                val cameraState by remember(glView) {
                    glView?.cameraState ?: MutableStateFlow(CameraState())
                }.collectAsState()
                // Print-bed labels: what the grid is and how big the platen is. Drawn before the
                // gizmo and the HUD so those two stay on top, and it takes no touch input.
                BedOverlay(cameraState = cameraState, lang = lang)
                ViewportGizmo(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp),
                    cameraState = cameraState,
                    lang = lang,
                    onSnapToView = { view -> glViewRef.value?.snapToView(view) },
                    onOrbit = { dx, dy -> glViewRef.value?.orbitBy(dx, dy) },
                    onZoom = { factor -> glViewRef.value?.zoomByScale(factor) },
                    onPan = { dx, dy -> glViewRef.value?.panByPx(dx, dy) }
                )
                // Measurement HUD: a Compose overlay, so the viewport keeps rendering no text.
                // It carries no pointer input, so orbit/pinch/pan/gizmo keep working.
                if (hudEnabled) {
                    MeasurementHud(
                        cameraState = cameraState,
                        params = params,
                        lang = lang,
                        showScaleBar = hudScaleBar,
                        legendRows = bodyKeys.size
                    )
                }
                // Assembly legend (D4). Only when there is more than one body, because that is the
                // only case where "which shape is which" is a question. It takes the top-start corner,
                // and it is the panel that owns it: the HUD's block of unanchored measurements is
                // pushed below it (`hudCornerTopDp`) instead of being drawn underneath it. The other
                // corners are the gizmo's (top-end), the tooth chip's and scale bar's (bottom-start)
                // and the zoom and play buttons' (bottom-end).
                if (bodyKeys.size > 1) {
                    AssemblyLegend(
                        labels = bodyKeys.map { I18n.t(lang, it) },
                        colours = bodyKeys.indices.map { Color(GearPalette.bodyArgb(it, darkSurface)) },
                        focused = focusedBody,
                        onFocus = { index ->
                            focusedBody = if (focusedBody == index) null else index
                        },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 12.dp, top = 12.dp)
                    )
                }
                // The bottom row: the tapped tooth at the start, zoom and playback at the end, clear of
                // the gizmo (top-end) and the sheet handle (bottom-centre). One row, so a tooth chip
                // grown by a large font scale wraps instead of sliding under the buttons. The row
                // itself takes no pointer input: a drag between its children still reaches the model.
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 84.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.weight(1f)) {
                        tapped?.let { idx ->
                            Surface(
                                onClick = {
                                    pendingToothEdit = idx
                                    sectionExpansion.expand(ParamGroup.TEETH)
                                    selectedMode = "params"
                                    showSheet = true
                                },
                                modifier = Modifier.heightIn(min = MinTouchTarget),
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.inverseSurface,
                                contentColor = MaterialTheme.colorScheme.inverseOnSurface
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        I18n.t(lang, "tooth_chip", (idx + 1).toString()),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Text(
                                        "  ·  " + I18n.t(lang, "edit"),
                                        style = MaterialTheme.typography.labelLarge,
                                        // inversePrimary is the accent made for an inverse surface;
                                        // primary on it measured 2.0:1 (light) and 1.3:1 (dark).
                                        color = MaterialTheme.colorScheme.inversePrimary
                                    )
                                }
                            }
                        }
                    }
                    // Zoom: pinch is a two-finger gesture, and WCAG 2.5.1 asks for a single-pointer
                    // way to do the same. Same step as the viewport's accessibility actions.
                    for (step in listOf(CameraStep.ZOOM_OUT, CameraStep.ZOOM_IN)) {
                        FilledTonalIconButton(
                            onClick = { glViewRef.value?.zoomByScale(step.zoom) },
                            modifier = Modifier.size(MinTouchTarget)
                        ) {
                            Icon(
                                imageVector = if (step == CameraStep.ZOOM_IN) Icons.Filled.ZoomIn else Icons.Filled.ZoomOut,
                                contentDescription = I18n.t(lang, step.labelKey),
                                modifier = Modifier.size(IconVisualSize)
                            )
                        }
                    }
                    // Playback. Hidden when the system asks for less motion — the honest answer to
                    // that request is no animation, not a slower one.
                    if (!reduceMotion) {
                        FilledTonalIconButton(
                            onClick = { playing = !playing },
                            modifier = Modifier.size(MinTouchTarget)
                        ) {
                            Icon(
                                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = I18n.t(lang, if (playing) "playback_pause" else "playback_play"),
                                modifier = Modifier.size(IconVisualSize)
                            )
                        }
                    }
                }
            }
        }
    }

    // Settings panel in a bottom sheet (Prio 3).
    if (showSheet) {
        val parameterSheetState = rememberModalBottomSheetState()
        val parameterWindow = LocalView.current
        ModalBottomSheet(
            onDismissRequest = { showSheet = false; selectedMode = "view" },
            sheetState = parameterSheetState
        ) {
            Box(Modifier.fillMaxWidth()) {
                SettingsPanel(
                    params = params,
                    onNumber = { key, v -> viewModel.mutate(GearSpec.setNumber(params, key, v.toDouble())) },
                    onChoice = { key, v -> viewModel.mutate(GearSpec.setChoice(params, key, v)) },
                    onBool = { key, v -> viewModel.mutate(GearSpec.setBool(params, key, v)) },
                    lang = lang,
                    sectionExpansion = sectionExpansion,
                    modifier = Modifier.fillMaxWidth().layout { measurable, constraints ->
                        val offset = if (parameterSheetState.targetValue == SheetValue.Expanded) 0f
                            else parameterWindow.height / 2f
                        val height = settingsPanelViewportHeight(constraints.maxHeight, offset)
                        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = height))
                        layout(placeable.width, constraints.maxHeight) {
                            placeable.placeRelative(0, 0)
                        }
                    },
                    onToothOverrides = { ov -> viewModel.mutate(params.copy(toothOverrides = ov)) },
                    initialToothEdit = pendingToothEdit,
                    onScrub = { preview -> scrubPreview = preview },
                    onToothRemoved = { idx, removed ->
                        sheetScope.launch {
                            val outcome = sheetSnackbar.showSnackbar(
                                message = I18n.t(lang, "override_removed"),
                                actionLabel = I18n.t(lang, "undo"),
                                duration = SnackbarDuration.Short
                            )
                            if (outcome == SnackbarResult.ActionPerformed) {
                                // Read the live parameter set when the undo is accepted: the user
                                // may have edited other values while the message was on screen, and
                                // restoring a snapshot captured at removal time would discard them.
                                viewModel.params?.let { current ->
                                    viewModel.mutate(
                                        current.copy(toothOverrides = current.toothOverrides + (idx to removed))
                                    )
                                }
                            }
                        }
                    }
                )
                SnackbarHost(
                    hostState = sheetSnackbar,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                )
                scrubPreview?.let { preview ->
                    ScrubStrip(
                        params = preview,
                        lang = lang,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }
    }

    if (showExport) {
        ExportSheet(
            activity = activity,
            params = params,
            settings = settings,
            adManager = adManager,
            lang = lang,
            onDismiss = { showExport = false }
        )
    }

    if (showSettings) {
        SettingsDialog(
            activity = activity,
            darkTheme = darkTheme,
            onThemeChange = onThemeChange,
            lang = lang,
            onLangChange = onLangChange,
            settings = settings,
            billingManager = billingManager,
            onDismiss = { showSettings = false },
            onShowTipsAgain = onRestartTips
        )
    }

    if (showAdvice) {
        AdviceSheet(
            params = params,
            settings = settings,
            lang = lang,
            onApplyBacklash = { value -> viewModel.mutate(GearSpec.setNumber(params, "backlash", value)) },
            onDismiss = { showAdvice = false }
        )
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

/**
 * The scrub preview: a live 2D outline of the parameter set currently under the user's thumb.
 *
 * A drag has to feel immediate (NN/g's response-time guidance is ≤ 0.1 s), but a full mesh rebuild
 * is debounced at 200 ms on purpose — it triangulates, lofts and re-uploads GL buffers. The outline
 * is the same geometry SVG and DXF export, generated as a polygon, so the change is visible at once
 * and the 3D view then confirms it at full fidelity.
 */
@Composable
private fun ScrubStrip(params: GearParams, lang: I18n.Lang, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().height(56.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
        tonalElevation = 4.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            GearOutline(params, Modifier.size(44.dp), MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                I18n.t(lang, "scrub_preview"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One labelled, horizontally scrolling row of printer presets.
 *
 * Chips instead of a text field: a mistyped nozzle diameter would silently make every print
 * recommendation wrong, and there are only a handful of sensible values per setting.
 */
@Composable
private fun PrinterChipRow(
    labelKey: String,
    values: List<Double>,
    selected: Double,
    decimals: Int,
    lang: I18n.Lang,
    onSelect: (Double) -> Unit
) {
    Text(
        I18n.t(lang, labelKey),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.semantics { heading() }
    )
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (v in values) {
            SelectChip(
                selected = selected == v,
                onClick = { onSelect(v) },
                label = { Text(Format.decimal(v, decimals, lang) + " " + I18n.t(lang, "mm")) }
            )
        }
    }
}

@Composable
private fun AdviceSheet(
    params: GearParams,
    settings: SettingsStore,
    lang: I18n.Lang,
    onApplyBacklash: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    val advice = PrintAdvisor.advice(
        params,
        nozzleMm = settings.nozzleMm,
        layerHeightMm = settings.layerHeightMm,
        material = params.material
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(I18n.t(lang, "print_advice")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // State the assumptions. Advice that silently depends on a 0.4 mm nozzle is wrong
                // for anyone else, so the dialog says which printer it was computed for.
                Text(
                    I18n.t(lang, "advice_assumes") + ": " +
                        Format.decimal(settings.nozzleMm, 2, lang) + " " + I18n.t(lang, "mm") + " · " +
                        Format.decimal(settings.layerHeightMm, 2, lang) + " " + I18n.t(lang, "mm") + " · " +
                        Format.decimal(settings.bedSizeMm, 0, lang) + " × " +
                        Format.decimal(settings.bedSizeMm, 0, lang) + " " + I18n.t(lang, "mm"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    // The backlash recommendation is the one piece of advice that is a concrete
                    // parameter change, so it gets an action instead of a message the user has to
                    // retype. It goes through the ViewModel, which makes it undoable.
                    if (a.key == PrintAdvisor.KEY_BACKLASH_RECOMMENDED) {
                        val recommended = a.args.getOrNull(1) as? Double
                        if (recommended != null) {
                            TextButton(onClick = { onApplyBacklash(recommended); onDismiss() }) {
                                Text(I18n.t(lang, "advice_apply") + " " + Format.decimal(recommended, 3, lang) + " " + I18n.t(lang, "mm"))
                            }
                        }
                    }
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
        title = { DialogTitle(I18n.t(lang, "presets")) },
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
        title = { DialogTitle(I18n.t(lang, "open_saved")) },
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
private fun ToothOverridePanel(
    params: GearParams,
    lang: I18n.Lang,
    onChange: (Map<Int, ToothOverride>) -> Unit,
    /**
     * Removes one override. Supplied by the screen rather than performed inside the panel so the
     * panel stays a pure editor and the undo affordance lives where it can actually be shown.
     */
    onRemove: (Int) -> Unit,
    /** Tooth index to prefill the form with, used when the viewport was tapped (0-based). */
    initialEditIndex: Int? = null
) {
    val teeth = params.teeth
    var toothText by rememberSaveable { mutableStateOf("") }
    var leftText by rememberSaveable { mutableStateOf("") }
    var rightText by rememberSaveable { mutableStateOf("") }
    var thickText by rememberSaveable { mutableStateOf("") }
    var editingIdx by rememberSaveable { mutableStateOf<Int?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var appliedInitialEdit by rememberSaveable { mutableStateOf<Int?>(null) }
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

    /**
     * Prefills the form for a tooth tapped in the viewport. Unlike the list rows this also accepts a
     * tooth that has no override yet — pointing at a tooth is a request to *create* one, and the user
     * should only have to type the values, not the number they just pointed at.
     */
    fun prefillForTooth(idx: Int) {
        val existing = params.toothOverrides[idx]
        toothText = (idx + 1).toString()
        leftText = existing?.leftPressureAngleDeg?.let { Format.decimal(it, 2, lang) } ?: ""
        rightText = existing?.rightPressureAngleDeg?.let { Format.decimal(it, 2, lang) } ?: ""
        thickText = existing?.toothThickness?.let { Format.decimal(it, 2, lang) } ?: ""
        editingIdx = if (existing != null) idx else null
        error = null
    }

    fun startEdit(idx: Int) {
        if (params.toothOverrides[idx] == null) return
        prefillForTooth(idx)
    }

    // A tap in the viewport arrives as an index; translate it into a filled-in form once.
    LaunchedEffect(initialEditIndex) {
        initialEditIndex?.let {
            if (appliedInitialEdit != it) {
                prefillForTooth(it)
                appliedInitialEdit = it
            }
        }
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
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() }
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
                val toothNumber = (idx + 1).toString()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The whole row height is the tap target: the summary text alone was 16 dp tall.
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = MinTouchTarget)
                            .clickable(onClickLabel = I18n.t(lang, "edit_tooth", toothNumber)) { startEdit(idx) },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            overrideSummary(idx, o, lang),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { startEdit(idx) }, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(Icons.Filled.Edit, contentDescription = I18n.t(lang, "edit_tooth", toothNumber), modifier = Modifier.size(IconVisualSize))
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { onRemove(idx) }, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(Icons.Filled.Delete, contentDescription = I18n.t(lang, "remove_override_tooth", toothNumber), modifier = Modifier.size(IconVisualSize))
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
            modifier = Modifier.fillMaxWidth().commitOnEnter { submit() },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() })
        )
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        warning?.let {
            Text(
                "\u26A0 $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
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

/**
 * Names and colours for the bodies of an assembly (D4).
 *
 * Shown only when there is more than one body, because that is the only case where "which shape is
 * which" is a question. Tapping a row focuses that body: the others are drawn dimmed in the
 * viewport, which answers the question where it is being asked instead of only in a list.
 */
@Composable
private fun AssemblyLegend(
    labels: List<String>,
    colours: List<Color>,
    focused: Int?,
    onFocus: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = VIEWPORT_PANEL_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(vertical = 2.dp)) {
            labels.forEachIndexed { index, label ->
                val isFocused = focused == index
                // Selectable: the focused body is announced as selected and drawn bold, not only as the
                // one row whose text colour did not change.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = MinTouchTarget)
                        .selectable(selected = isFocused, onClick = { onFocus(index) })
                        .padding(horizontal = 10.dp)
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(
                                colours.getOrElse(index) { MaterialTheme.colorScheme.primary },
                                RoundedCornerShape(2.dp)
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isFocused) FontWeight.Bold else null,
                        // The focused body is the one that is *not* dimmed in the viewport, so the
                        // row has to read the same way round.
                        color = if (focused == null || isFocused) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

/**
 * Section header: an accent stripe, the localized title, an override count and a chevron.
 *
 * It used to carry a second "Advanced" badge next to the count. That badge changed nothing — it
 * did not hide, gate or reorder anything — so on a seven-section panel it was six rows of noise
 * competing with the one badge that does carry information (how many teeth are overridden) and
 * with the accent that identifies the section. The hint line inside each section already explains
 * what is uncommon, which is where a reader looks for it.
 */
@Composable
private fun GroupHeader(
    group: ParamGroup,
    lang: I18n.Lang,
    expanded: Boolean,
    onToggle: () -> Unit,
    count: Int = 0,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // 48 dp: 24 dp of content and 10 dp of padding above and below came to 44.
            .heightIn(min = MinTouchTarget)
            .clickable(
                onClickLabel = I18n.t(lang, if (expanded) "collapse_section" else "expand_section"),
                onClick = onToggle
            )
            .semantics {
                heading()
                stateDescription = I18n.t(lang, if (expanded) "state_expanded" else "state_collapsed")
            }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(20.dp)
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
                    modifier = Modifier
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                        .semantics { contentDescription = I18n.t(lang, "override_count", count.toString()) }
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun settingsPanelViewportHeight(availableHeight: Int, sheetOffset: Float?): Int =
    (availableHeight - (sheetOffset?.takeIf { it.isFinite() }?.toInt() ?: (availableHeight / 2)))
        .coerceIn(1, availableHeight.coerceAtLeast(1))

internal sealed class SettingsPanelRow(val key: String, val group: ParamGroup) {
    class Header(group: ParamGroup, val expanded: Boolean) : SettingsPanelRow("header:${group.name}", group)
    class Hint(group: ParamGroup) : SettingsPanelRow("hint:${group.name}", group)
    class Field(val def: ParamDef) : SettingsPanelRow("field:${def.group.name}:${def.key}", def.group)
    class Teeth : SettingsPanelRow("teeth:editor", ParamGroup.TEETH)
    class Result(val resultKey: String) : SettingsPanelRow("result:$resultKey", ParamGroup.RESULTS)
    class End(group: ParamGroup) : SettingsPanelRow("end:${group.name}", group)
}

internal fun settingsPanelRows(
    defs: List<ParamDef>,
    expanded: Set<ParamGroup>,
    resultKeys: List<String>,
    includeTeeth: Boolean
): List<SettingsPanelRow> = buildList {
    for (group in ParamGroup.entries) {
        val groupDefs = defs.filter { it.group == group }
        if (groupDefs.isEmpty()) continue
        add(SettingsPanelRow.Header(group, group in expanded))
        if (group !in expanded) continue
        add(SettingsPanelRow.Hint(group))
        groupDefs.forEach { add(SettingsPanelRow.Field(it)) }
        if (group == ParamGroup.TEETH && includeTeeth) add(SettingsPanelRow.Teeth())
        add(SettingsPanelRow.End(group))
    }
    if (resultKeys.isNotEmpty()) {
        add(SettingsPanelRow.Header(ParamGroup.RESULTS, ParamGroup.RESULTS in expanded))
        if (ParamGroup.RESULTS in expanded) {
            add(SettingsPanelRow.Hint(ParamGroup.RESULTS))
            resultKeys.forEach { add(SettingsPanelRow.Result(it)) }
            add(SettingsPanelRow.End(ParamGroup.RESULTS))
        }
    }
}

@Composable
internal fun SettingsPanel(
    params: GearParams,
    onNumber: (String, Float) -> Unit,
    onChoice: (String, String) -> Unit,
    onBool: (String, Boolean) -> Unit,
    lang: I18n.Lang,
    modifier: Modifier = Modifier,
    sectionExpansion: SectionExpansion? = null,
    onToothOverrides: ((Map<Int, ToothOverride>) -> Unit)? = null,
    /**
     * Invoked while a numeric field is being dragged, with the parameter set the drag would produce,
     * and with `null` once the drag ends. Purely a preview: it must not be recorded as an edit.
     */
    onScrub: ((GearParams?) -> Unit)? = null,
    /**
     * Invoked after an override is removed, with its index and the removed value, so the caller
     * can offer an undo. Required here: a delete that cannot be undone is a data-loss path.
     */
    onToothRemoved: ((Int, ToothOverride) -> Unit)? = null,
    /** Tooth index to prefill the per-tooth form with, set when a tooth was tapped in the viewport. */
    initialToothEdit: Int? = null
) {
    val defs = GearSpec.fields(params)
    val results = GearSpec.results(params.gearType, params) { v, d -> Format.decimal(v, d, lang) }
    val warnings = remember(params) { GearSpec.validate(params) }
    val focus = LocalFocusManager.current
    val expansion = sectionExpansion ?: rememberSaveable(
        saver = listSaver(
            save = { listOf(it.collapsedNames) },
            restore = { SectionExpansion(it[0]) }
        )
    ) { SectionExpansion() }
    val errors = warnings.filter { it.severity == GearSeverity.ERROR }
    val soft = warnings.filter { it.severity != GearSeverity.ERROR }
    val rows = settingsPanelRows(
        defs,
        ParamGroup.entries.filter { expansion.isExpanded(it) }.toSet(),
        results.map { it.first },
        onToothOverrides != null && GearSpec.hasGearBody(params.gearType)
    )
    val scrollState = rememberLazyListState()
    LaunchedEffect(initialToothEdit) {
        if (initialToothEdit != null) {
            val target = rows.indexOfFirst { it is SettingsPanelRow.Header && it.group == ParamGroup.TEETH }
            if (target >= 0) {
                val warningRows = (if (errors.isNotEmpty()) 1 else 0) + (if (soft.isNotEmpty()) 1 else 0)
                scrollState.scrollToItem(target + warningRows)
            }
        }
    }
    LazyColumn(modifier, state = scrollState) {
        if (errors.isNotEmpty()) {
            item(key = "warnings:errors", contentType = "warnings") {
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
        }
        if (soft.isNotEmpty()) {
            item(key = "warnings:soft", contentType = "warnings") {
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
        }
        items(rows, key = { it.key }, contentType = {
            if (it is SettingsPanelRow.Field) it.def.kind else it::class
        }) { row ->
            val first = row is SettingsPanelRow.Header
            val last = row is SettingsPanelRow.End || (row is SettingsPanelRow.Header && !row.expanded)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                shape = RoundedCornerShape(
                    topStart = if (first) 12.dp else 0.dp,
                    topEnd = if (first) 12.dp else 0.dp,
                    bottomStart = if (last) 12.dp else 0.dp,
                    bottomEnd = if (last) 12.dp else 0.dp
                ),
                modifier = Modifier.fillMaxWidth().padding(
                    start = 8.dp, end = 8.dp,
                    top = if (first) 4.dp else 0.dp,
                    bottom = if (last) 4.dp else 0.dp
                )
            ) {
                when (row) {
                    is SettingsPanelRow.Header -> GroupHeader(
                        group = row.group,
                        lang = lang,
                        expanded = row.expanded,
                        onToggle = {
                            focus.clearFocus()
                            expansion.toggle(row.group)
                        },
                        count = if (row.group == ParamGroup.TEETH) params.toothOverrides.size else 0,
                        accent = groupAccent(row.group)
                    )
                    is SettingsPanelRow.Hint -> {
                        val hintKey = if (row.group == ParamGroup.RESULTS) "results_hint"
                            else "group_" + row.group.name.lowercase() + "_hint"
                        val hint = I18n.t(lang, hintKey).takeUnless { it == hintKey }
                        if (hint != null) {
                            Text(
                                hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = 16.dp, end = 16.dp,
                                    bottom = if (row.group == ParamGroup.RESULTS) 0.dp else 4.dp
                                )
                            )
                        }
                    }
                    is SettingsPanelRow.Field -> {
                        val def = row.def
                        when (def.kind) {
                            FieldKind.NUMBER -> NumberRow(
                                def = def,
                                value = GearSpec.getNumber(params, def.key).toFloat(),
                                context = params,
                                lang = lang,
                                onChange = { onNumber(def.key, it) },
                                onPreview = { value ->
                                    onScrub?.invoke(GearSpec.setNumber(params, def.key, value.toDouble()))
                                },
                                onPreviewEnd = { onScrub?.invoke(null) }
                            )
                            FieldKind.CHOICE -> ChoiceRow(def, GearSpec.getChoice(params, def.key), lang) { onChoice(def.key, it) }
                            FieldKind.BOOLEAN -> ToggleRow(def, GearSpec.getBool(params, def.key), lang) { onBool(def.key, it) }
                            else -> {}
                        }
                    }
                    is SettingsPanelRow.Teeth -> {
                        val toothChange = onToothOverrides
                        if (toothChange != null) {
                            ToothOverridePanel(
                                params = params,
                                lang = lang,
                                onChange = toothChange,
                                initialEditIndex = initialToothEdit,
                                onRemove = { index ->
                                    val removed = params.toothOverrides[index]
                                    if (removed != null) {
                                        toothChange(params.toothOverrides - index)
                                        onToothRemoved?.invoke(index, removed)
                                    }
                                }
                            )
                        }
                    }
                    is SettingsPanelRow.Result -> {
                        val key = row.resultKey
                        val label = if (params.unit == UnitSystem.INCH && key in GearSpec.METRIC_ONLY_RESULT_KEYS) {
                            I18n.t(lang, key) + " " + I18n.t(lang, "si_units")
                        } else I18n.t(lang, key)
                        CalculatedRow(label, results.first { it.first == key }.second)
                    }
                    is SettingsPanelRow.End -> Spacer(Modifier.height(if (row.group == ParamGroup.RESULTS) 8.dp else 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ExportSheet(
    activity: Activity,
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
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var confirmErrors by remember { mutableStateOf(false) }
    // Remembers what the blocked export was going to do, so the "export anyway" override resumes the
    // right action instead of quietly turning a share into a download.
    var pendingShare by remember { mutableStateOf(false) }

    // Synchronous guard that closes the window between the export tap and the moment
    // [doExport] sets `exporting`. The legacy-storage permission request is ASYNC, so
    // during the system dialog `exporting` is still false and a second tap would start
    // a second export — which is exactly how two identical STL files ended up in
    // Downloads during emulator verification.
    var exportPending by remember { mutableStateOf(false) }

    // Legacy external-storage gate. On API 24..28 the export is written straight to
    // public Downloads, which needs a runtime WRITE_EXTERNAL_STORAGE grant; without it
    // saveToDownloads throws SecurityException and the user only sees "export failed".
    val requestStorageAccess = rememberLegacyStoragePermission(
        onDenied = {
            exportPending = false   // release the guard so the user can retry
            gateMessage = I18n.t(lang, "export_permission_denied")
        }
    )

    // Reactive monetization state so the gate updates after a purchase or consumed export.
    var isPro by remember { mutableStateOf(settings.isPro) }
    var freeLeft by remember { mutableIntStateOf(settings.freeAdvancedExports) }

    val highQuality = isPro && settings.highQuality
    // Timing probe kept permanently: the info rows are computed off the main thread, and a
    // measurement (not a guess) is what tells us whether a lag is the mesh build, the bounds
    // pass, or the effect not starting until after the dialog's entrance animation.
    val composedAt = remember { System.currentTimeMillis() }
    var preview by remember(params, highQuality) { mutableStateOf<ExportPreview?>(null) }
    LaunchedEffect(params, highQuality) {
        val effectStart = System.currentTimeMillis()
        preview = withContext(Dispatchers.Default) {
            val meshStart = System.currentTimeMillis()
            val mesh = ExportManager.mesh(params, highQuality)
            val boundsStart = System.currentTimeMillis()
            val b = MeshOps.bounds(mesh)
            Log.d(
                "GF_EXPORT",
                "preview compute: mesh=${boundsStart - meshStart}ms bounds=${System.currentTimeMillis() - boundsStart}ms"
            )
            ExportPreview(mesh.triangles.size, b.x, b.y, b.z)
        }
        Log.d(
            "GF_EXPORT",
            "preview ready: effectStartLag=${effectStart - composedAt}ms total=${System.currentTimeMillis() - composedAt}ms"
        )
    }

    // The filename uses a locale-neutral decimal so the actual saved file is predictable.
    val base = "gear_${params.teeth}t_m${String.format(java.util.Locale.US, "%.2f", params.module)}"

    // Point 18: export runs off the UI thread with progress + cancel. A share is the same work with
    // a different destination and runs through this same function rather than a parallel path: a
    // second path would be a second place to get the free-export accounting wrong.
    fun doExport(consumeFree: Boolean, share: Boolean = false) {
        if (exporting) return
        exporting = true
        exportProgress = 0f
        gateMessage = null
        exportJob = scope.launch {
            try {
                val result = if (share) {
                    ExportManager.prepareShare(context, params, format, highQuality, base) {
                        exportProgress = it
                    }.mapCatching { uri ->
                        // A device with nothing installed that accepts this format has no chooser.
                        // That is a fact about the device, so it becomes a message, not a crash.
                        if (!ExportManager.share(context, uri, format.mime, I18n.t(lang, "share_chooser"))) {
                            throw IllegalStateException("no app accepts " + format.mime)
                        }
                    }
                } else {
                    ExportManager.export(context, params, format, highQuality, base) {
                        exportProgress = it
                    }
                }
                if (result.isSuccess) {
                    // Consume the free export only after the file is actually written so
                    // a failed export never burns the user's entitlement.
                    if (consumeFree) freeLeft = settings.consumeAdvancedExport()
                    // A share ends in the chooser, which is its own confirmation; a toast on top of
                    // it would arrive after the user had already left the app.
                    if (!share) {
                        Toast.makeText(context, I18n.t(lang, "export_done"), Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                    // Tester feedback, opportunity 4: ask for a rating at the one moment the
                    // user has just finished something real. Fires at most once per install,
                    // never blocks and never fails the export (see InAppReview).
                    InAppReview.maybeRequestAfterExport(activity, settings)
                } else {
                    if (share) gateMessage = I18n.t(lang, "share_failed") else exportFailed = true
                }
            } finally {
                exporting = false
                exportJob = null
                exportProgress = 0f
            }
        }
    }

    // Phase-1 gating (Pro / free exports / rewarded ad). The ad flow never navigates
    // away or crashes: a failed/dismissed ad only surfaces a message and stays put.
    //
    // NOTE: declared before launchExport() on purpose — Kotlin local functions cannot
    // be forward-referenced inside the same block.
    fun performExport(share: Boolean = false) {
        exportPending = false   // the guard has done its job; doExport takes over
        when {
            isPro -> doExport(consumeFree = false, share = share)
            freeLeft > 0 -> doExport(consumeFree = true, share = share)
            else -> adManager.showRewarded(
                onReward = { doExport(consumeFree = false, share = share) },
                onDismissed = { gateMessage = I18n.t(lang, "ad_dismissed") },
                onUnavailable = { gateMessage = I18n.t(lang, "ad_unavailable") }
            )
        }
    }

    fun launchExport(share: Boolean = false) {
        // Guard covers BOTH entry points (startExport and the "export anyway" override
        // dialog) and the whole asynchronous permission window.
        if (exporting || exportPending) return
        exportPending = true
        // The permission gate lives HERE, not in startExport(): the hard-validation
        // override dialog calls launchExport() directly, so gating one level up would
        // leave that path writing to external storage without a runtime grant.
        // A share writes into the app's own cache, which needs no permission at all — asking for
        // one before a share would be a request the user pays for and the share never uses.
        if (share) {
            performExport(share = true)
            return
        }
        requestStorageAccess { performExport(share = false) }
    }

    fun startExport(share: Boolean = false) {
        if (exporting) return
        // Hard validation failures block export until the user explicitly overrides
        // (audit C4) — a physical impossibility should not become a file by accident.
        if (GearSpec.validate(params).any { it.severity == GearSeverity.ERROR }) {
            pendingShare = share
            confirmErrors = true
            return
        }
        launchExport(share)
    }

    fun cancelExport() {
        exportJob?.cancel()
    }

    AlertDialog(
        onDismissRequest = { if (!exporting) onDismiss() },
        title = { DialogTitle(I18n.t(lang, "export")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ExportManager.Format.entries.forEach { f ->
                    SelectChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
                }
                Text(
                    "${I18n.t(lang, "export_filename")}: $base${format.ext}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                // These rows are computed off the main thread, so their values arrive a moment
                // after the sheet opens. They are composed unconditionally - with an empty value
                // until the result lands - so the sheet's height, and with it the position of
                // every format chip and button, does not change while the user is reaching for
                // one. Measured before the fix: the DXF chip moved 1299 -> 1244 px the moment
                // the values landed, because the block only existed once `preview` was non-null.
                if (format == ExportManager.Format.STL || format == ExportManager.Format.THREE_MF) {
                    Text(
                        "${I18n.t(lang, "export_triangles")}: ${preview?.triangles ?: ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text(
                    "${I18n.t(lang, "export_dimensions")}: " + (
                        preview?.let { Format.dims(it.w, it.h, it.d, 1, lang, settings.useInch) } ?: ""
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
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
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
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
        dismissButton = if (exporting) {
            // While an export runs the confirm slot already shows "Cancel". A second,
            // disabled "Cancel" next to it is dead UI and reads as two different ways
            // to do the same thing.
            null
        } else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Share produces the same file as Download, so it goes through the same gating —
                    // a share must not be a way around the free-export count.
                    TextButton(onClick = { startExport(share = true) }) {
                        Text(I18n.t(lang, "share"))
                    }
                    TextButton(onClick = onDismiss) { Text(I18n.t(lang, "cancel")) }
                }
            }
        }
    )

    if (confirmErrors) {
        val hardErrors = GearSpec.validate(params).filter { it.severity == GearSeverity.ERROR }
        AlertDialog(
            onDismissRequest = { confirmErrors = false },
            title = { DialogTitle(I18n.t(lang, "export_errors_title")) },
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
                Button(onClick = { confirmErrors = false; launchExport(pendingShare) }) {
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
            title = { DialogTitle(I18n.t(lang, "export_failed")) },
            confirmButton = { TextButton(onClick = { exportFailed = false }) { Text(I18n.t(lang, "ok")) } }
        )
    }
}

@Composable
fun SettingsDialog(
    activity: Activity,
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
    lang: I18n.Lang,
    onLangChange: (I18n.Lang) -> Unit,
    settings: SettingsStore,
    billingManager: BillingManager,
    onDismiss: () -> Unit,
    onShowTipsAgain: () -> Unit
) {
    var isPro by remember { mutableStateOf(settings.isPro) }
    var proMessage by remember { mutableStateOf<String?>(null) }
    var showPrivacy by remember { mutableStateOf(false) }
    // Mirrors SettingsStore.highQuality so the switch is reactive. The flag already drove
    // every export (`ExportSheet`: highQuality = isPro && settings.highQuality) but had no
    // control anywhere in the UI, so a Pro user could neither see nor change it.
    var highQuality by remember { mutableStateOf(settings.highQuality) }

    // Keep the Pro badge in sync with purchase/restore results that arrive asynchronously.
    DisposableEffect(billingManager) {
        billingManager.onProChanged = { isPro = it }
        onDispose { billingManager.onProChanged = null }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(I18n.t(lang, "settings")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(I18n.t(lang, "theme"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.semantics { heading() })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectChip(selected = darkTheme, onClick = { onThemeChange(true) }, label = { Text(I18n.t(lang, "dark")) })
                    SelectChip(selected = !darkTheme, onClick = { onThemeChange(false) }, label = { Text(I18n.t(lang, "light")) })
                }
                Spacer(Modifier.padding(4.dp))
                Text(I18n.t(lang, "language"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.semantics { heading() })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectChip(selected = lang == I18n.Lang.EN, onClick = { onLangChange(I18n.Lang.EN) }, label = { Text(I18n.t(lang, "english")) })
                    SelectChip(selected = lang == I18n.Lang.SV, onClick = { onLangChange(I18n.Lang.SV) }, label = { Text(I18n.t(lang, "swedish")) })
                }
                Spacer(Modifier.padding(8.dp))
                Text(I18n.t(lang, "pro_section"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
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
                // Pro-only by design: non-Pro exports are forced to standard precision in
                // ExportManager, so the control would be a lie for them.
                if (isPro) {
                    // One toggleable row, so the switch is announced with its name.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = highQuality, role = Role.Switch) { checked ->
                                highQuality = checked
                                settings.highQuality = checked
                            }
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(I18n.t(lang, "high_quality_export"), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                I18n.t(lang, "high_quality_export_help"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = highQuality, onCheckedChange = null)
                    }
                }
                Spacer(Modifier.padding(8.dp))
                // Price-model clarity: what is free vs Pro vs rewarded ad (point 16).
                Text(I18n.t(lang, "free_plan_title"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Text(I18n.t(lang, "free_plan_body"), style = MaterialTheme.typography.bodySmall)
                Text(I18n.t(lang, "pro_plan_body"), style = MaterialTheme.typography.bodySmall)
                Text(I18n.t(lang, "ad_plan_body"), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.padding(8.dp))
                // The print advice is only true for the printer it assumes. Feeding these values
                // into PrintAdvisor is what turns generic guidance into advice for this machine —
                // the nozzle diameter alone decides the smallest printable module.
                Text(I18n.t(lang, "printer_section"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                var nozzle by remember { mutableDoubleStateOf(settings.nozzleMm) }
                var layerHeight by remember { mutableDoubleStateOf(settings.layerHeightMm) }
                var bedSize by remember { mutableDoubleStateOf(settings.bedSizeMm) }
                PrinterChipRow(
                    labelKey = "nozzle",
                    values = PrinterPresets.NOZZLES,
                    selected = nozzle,
                    decimals = 2,
                    lang = lang,
                    onSelect = { nozzle = it; settings.nozzleMm = it }
                )
                PrinterChipRow(
                    labelKey = "layer_height",
                    values = PrinterPresets.LAYER_HEIGHTS,
                    selected = layerHeight,
                    decimals = 2,
                    lang = lang,
                    onSelect = { layerHeight = it; settings.layerHeightMm = it }
                )
                PrinterChipRow(
                    labelKey = "bed_size",
                    values = PrinterPresets.BED_SIZES,
                    selected = bedSize,
                    decimals = 0,
                    lang = lang,
                    onSelect = { bedSize = it; settings.bedSizeMm = it }
                )
                Spacer(Modifier.padding(8.dp))
                // The walkthrough used to be once-per-session with no way back, so a user who
                // tapped through it lost it forever. The tips are persisted (SettingsStore.tipsStep)
                // and this is the way to ask for them again — without it, "once" would be a promise
                // the app cannot keep.
                TextButton(onClick = onShowTipsAgain) { Text(I18n.t(lang, "tips_show_again")) }
                TextButton(onClick = { InAppReview.openStoreListing(activity) }) {
                    Text(I18n.t(lang, "rate_app"))
                }
                // UMP privacy options: required in regulated regions. Shown only when the
                // consent state says an entry point must exist, so the row is never a dead end.
                val consent = remember(activity) { ConsentManager(activity) }
                var privacyOptionsRequired by remember { mutableStateOf(consent.privacyOptionsRequired()) }
                if (privacyOptionsRequired) {
                    TextButton(onClick = {
                        consent.showPrivacyOptions { shown ->
                            if (shown) privacyOptionsRequired = consent.privacyOptionsRequired()
                        }
                    }) { Text(I18n.t(lang, "privacy_options")) }
                }
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
        title = { DialogTitle(I18n.t(lang, "privacy_policy")) },
        text = { Text(I18n.t(lang, "privacy_summary")) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(I18n.t(lang, "ok")) } }
    )
}
