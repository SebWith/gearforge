package com.gearforge.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gearforge.core.FieldKind
import com.gearforge.core.GearPalette
import com.gearforge.core.GearParams
import com.gearforge.core.GearSpec
import com.gearforge.core.GearType
import com.gearforge.core.ParamDef
import kotlin.math.abs
import com.gearforge.core.ParamGroup

internal fun typeLabel(t: GearType, lang: I18n.Lang): String =
    I18n.t(lang, "type_" + t.name.lowercase())

internal fun groupLabel(g: ParamGroup, lang: I18n.Lang): String =
    I18n.t(lang, "group_" + g.name.lowercase())

/**
 * Per-section accent colour.
 *
 * The *hue* identifies the section; the lightness and saturation are derived from how bright the
 * theme's surface is, so the same accent stays legible in both themes. The previous fixed palette
 * was picked on the dark theme only; on white the amber stripe measured 2.71:1, under the 3:1 that
 * WCAG asks of a graphical object. `GearPaletteTest` pins that floor for every section and theme.
 */
@Composable
internal fun groupAccent(g: ParamGroup): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5
    return Color(GearPalette.accentArgb(g, dark))
}

/** Localized field label, with a special case for the module field's inch variant. */
private fun fieldLabel(def: ParamDef, lang: I18n.Lang): String {
    val key = if (def.key == "module" && def.unit == "1/in") "diametral_pitch" else def.key
    return I18n.label(lang, key, def.label)
}

/** Localized unit suffix (most units are language-neutral symbols). */
private fun unitLabel(unit: String, lang: I18n.Lang): String = when (unit) {
    "1/in" -> if (lang == I18n.Lang.SV) "1/tum" else "1/in"
    else -> unit
}

/** Localized choice-option label; falls back to the raw option when no key exists. */
private fun optionLabel(def: ParamDef, option: String, lang: I18n.Lang): String {
    val key = when (def.key) {
        "material" -> "material_" + option.lowercase()
        "lubrication" -> "lubrication_" + option.lowercase().replace(' ', '_')
        "tooth_profile" -> "profile_" + option.lowercase()
        "index_mark" -> "mark_" + option.lowercase()
        "unit" -> when (option) {
            "mm (module)" -> "unit_mm_module"
            else -> "unit_inch_diametral_pitch"
        }
        "bore_type" -> when (option) {
            "None" -> "bore_none"
            "Round" -> "bore_round"
            "D-cut" -> "bore_dcut"
            "Keyway" -> "bore_keyway"
            "Hex" -> "bore_hex"
            "Square" -> "bore_square"
            else -> null
        }
        else -> null
    } ?: return option
    val localized = I18n.t(lang, key)
    return if (localized != key) localized else option
}

/**
 * Minimum interactive size for an icon-only control.
 *
 * WCAG 2.2 SC 2.5.8 requires 24 × 24 CSS px and Android's own guidance is 48 dp. Two controls
 * placed side by side cannot rely on the criterion's spacing exception, so an icon keeps its
 * small visual size *inside* a full-size touch target instead of the target shrinking to match
 * the glyph.
 */
internal val MinTouchTarget = 48.dp

/** Visual size of the glyph drawn inside a [MinTouchTarget]-sized icon button. */
internal val IconVisualSize = 20.dp

internal fun fmtNum(v: Float, decimals: Int, lang: I18n.Lang): String =
    Format.decimal(v.toDouble(), decimals, lang)

/** The label a numeric row shows, and the name its text field announces: the unit is appended once. */
internal fun numberRowLabel(label: String, unit: String): String =
    if (unit.isEmpty() || label.contains(unit)) label else "$label ($unit)"

/**
 * Hardware Enter in a single-line field: [onEnter] runs on the key-up, and both halves are consumed.
 * Left to the field, Enter ran the Done action on the key-down; Done clears the focus, which in
 * keyboard mode lands on the first item of the screen, and the key-up then clicked that item — the
 * Geometry header collapsed under the user. The on-screen keyboard's Done key is not a key event and
 * still goes through `keyboardActions`.
 */
internal fun Modifier.commitOnEnter(onEnter: () -> Unit): Modifier = onPreviewKeyEvent { e ->
    if (e.key == Key.Enter || e.key == Key.NumPadEnter) {
        if (e.type == KeyEventType.KeyUp) onEnter()
        true
    } else false
}

/** A dialog title that a screen reader can jump to: Material's AlertDialog does not mark it as a heading. */
@Composable
internal fun DialogTitle(text: String) {
    Text(text, Modifier.semantics { heading() })
}

/**
 * A filter chip that draws its selected state as a check mark as well as a fill.
 *
 * The selected fill (secondaryContainer) measured 1.1:1 against the light dialog surface, so a chip's
 * state was told by a colour difference nobody can see (SC 1.4.1 and 1.4.11). The check mark is the
 * Material filter-chip indicator and measures 13:1 / 7:1 against the fill in the light / dark theme.
 */
@Composable
internal fun SelectChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Done, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else null
    )
}

/** The (i) button that shows a field's help: named after the field, and announcing whether the help is open. */
@Composable
private fun HelpToggle(fieldLabel: String, open: Boolean, lang: I18n.Lang, onToggle: () -> Unit) {
    val state = I18n.t(lang, if (open) "state_expanded" else "state_collapsed")
    IconButton(
        onClick = onToggle,
        modifier = Modifier
            .size(MinTouchTarget)
            .semantics { stateDescription = state }
    ) {
        Icon(Icons.Filled.Info, contentDescription = I18n.t(lang, "help_for", fieldLabel), modifier = Modifier.size(IconVisualSize))
    }
}

internal fun numberRowValueChanged(value: Double, committed: Float): Boolean =
    value.toFloat() != committed

/** Compact number input: editable text field (primary) plus an optional drag slider. */
@Composable
internal fun NumberRow(
    def: ParamDef,
    value: Float,
    context: com.gearforge.core.GearParams,
    lang: I18n.Lang,
    onChange: (Float) -> Unit,
    /**
     * Called while the thumb is being dragged, with the value under the finger.
     *
     * Deliberately separate from [onChange]: committing on every frame would push one undo step per
     * frame and evict the user's real history within a single drag. A preview is a view-only value
     * that the caller may render cheaply and must drop when [onPreviewEnd] arrives.
     */
    onPreview: ((Float) -> Unit)? = null,
    /** Called once when the drag ends, after the committed value has been published. */
    onPreviewEnd: (() -> Unit)? = null
) {
    var text by rememberSaveable(value) { mutableStateOf(fmtNum(value, def.decimals, lang)) }
    var committed by rememberSaveable(value) { mutableFloatStateOf(value) }
    var clampWarning by rememberSaveable { mutableStateOf<String?>(null) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    // True while the field has focus: the valid range and the reason a typed value was clamped are
    // only useful while a value is being entered or chosen.
    var focused by remember { mutableStateOf(false) }
    // Expression input. `apply()` has always evaluated expressions; without this switch there was
    // no way to discover that, and the decimal keyboard cannot produce "*", "(" or a letter.
    var exprMode by rememberSaveable { mutableStateOf(false) }
    // Tracks the thumb during a drag so the slider follows the finger instead of snapping
    // back to the committed value on every recomposition.
    var sliderValue by remember(value) { mutableFloatStateOf(value.coerceIn(def.min.toFloat(), def.max.toFloat())) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val fieldFocus = remember { FocusRequester() }

    // A drag emits one event per frame. Rebuilding the preview outline 60 times a second is waste,
    // so previews are emitted at most once per 1/200th of the range; integer fields step by at
    // least one whole unit. Feedback stays immediate because a real drag crosses many steps.
    val previewStep = remember(def.min, def.max, def.decimals) {
        val span = (def.max - def.min).toFloat()
        when {
            span <= 0f -> 1f
            def.decimals == 0 -> (span / 200f).coerceAtLeast(1f)
            else -> (span / 200f).coerceAtLeast(1e-4f)
        }
    }
    var lastPreview by remember(value) { mutableFloatStateOf(value) }
    var scrubbing by remember { mutableStateOf(false) }

    /** Where [v] sits in the value range, as a 0..1 fraction (used to place the drag bubble). */
    fun fractionOf(v: Float): Float =
        ((v - def.min.toFloat()) / (def.max - def.min).toFloat()).coerceIn(0f, 1f)

    // The value this field starts at for the current gear type. The key is guaranteed to exist in
    // getNumber: the row was rendered from [GearSpec.fields], so there is no "unknown key returns
    // 0.0" case to guard against. Offered as an exact reset rather than a tick on the track — a
    // marker positioned by layout weight would sit a thumb-padding away from the true position and
    // would therefore misreport where the standard value is.
    val defaultValue = remember(context.gearType, def.key) {
        GearSpec.getNumber(GearSpec.defaults(context.gearType), def.key)
    }.takeIf { it >= def.min && it <= def.max && abs(it - value.toDouble()) > 1e-9 }

    fun apply() {
        // Expression-driven fields (e.g. "0.38*m", "pi*m/2") fall back to plain numbers.
        val parsed = com.gearforge.core.Expr.eval(text, context)
            ?: text.trim().replace(',', '.').toDoubleOrNull()
        val min = def.min
        val max = def.max
        val v = parsed?.coerceIn(min, max)
        text = fmtNum((v ?: committed).toFloat(), def.decimals, lang)
        if (v != null) sliderValue = v.toFloat()
        clampWarning = when {
            parsed == null -> null
            parsed < min -> I18n.t(lang, "clamped_to_min")
            parsed > max -> I18n.t(lang, "clamped_to_max")
            else -> null
        }
        if (v != null && numberRowValueChanged(v, committed)) {
            committed = v.toFloat()
            onChange(v.toFloat())
        }
    }

    // The unit belongs in the label: as a suffix beside the field it reserved a fixed width
    // in every row to repeat a fact that never changes. Several labels already carry their
    // unit ("Module (mm)"), and appending it blindly produced "Module (mm) (mm)" — which the
    // emulator showed before this check existed.
    val unit = unitLabel(def.unit, lang)
    val label = numberRowLabel(fieldLabel(def, lang), unit)

    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (def.help.isNotEmpty()) {
                HelpToggle(label, showHelp, lang) { showHelp = !showHelp }
            }
            if (def.max > def.min) {
                // A toggle, so it says whether it is on; the visible "fx" alone told a screen reader nothing.
                TextButton(
                    onClick = { exprMode = !exprMode },
                    modifier = Modifier
                        .size(MinTouchTarget)
                        .semantics {
                            contentDescription = I18n.t(lang, "expr_mode_for", label)
                            toggleableState = ToggleableState(exprMode)
                        },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        "fx",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (exprMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                // The field's name. Its visible label is a sibling Text, which a screen reader cannot tie to
                // the field, so touching the field announced only "20, edit box". Material's own search
                // field names its BasicTextField the same way.
                modifier = Modifier
                    .width(104.dp)
                    .height(48.dp)
                    .semantics { contentDescription = label }
                    .commitOnEnter { apply() }
                    .focusRequester(fieldFocus)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (!it.isFocused) apply()
                    },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when {
                        exprMode -> KeyboardType.Text
                        def.decimals == 0 -> KeyboardType.Number
                        else -> KeyboardType.Decimal
                    },
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    apply()
                    focus.clearFocus()
                    keyboard?.hide()
                })
            )
        }
        if (exprMode) {
            Text(
                I18n.t(lang, "expr_hint"),
                Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (def.max > def.min) {
            if (scrubbing) {
                // M3's slider shows no value, and during a drag the user is looking at the thumb,
                // not at the text field in the row above.
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(fractionOf(sliderValue).coerceIn(0.001f, 0.999f)))
                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            fmtNum(sliderValue, def.decimals, lang),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.weight((1f - fractionOf(sliderValue)).coerceIn(0.001f, 0.999f)))
                }
            }
            Slider(
                value = sliderValue,
                onValueChange = { v ->
                    sliderValue = v
                    text = fmtNum(v, def.decimals, lang)
                    scrubbing = true
                    if (onPreview != null && abs(v - lastPreview) >= previewStep) {
                        lastPreview = v
                        onPreview(v)
                    }
                },
                onValueChangeFinished = {
                    scrubbing = false
                    lastPreview = sliderValue
                    apply()
                    onPreviewEnd?.invoke()
                    // NN/g's coarse+fine pattern: hand the fine control the focus so the exact
                    // value can be typed immediately after a rough drag.
                    fieldFocus.requestFocus()
                },
                valueRange = def.min.toFloat()..def.max.toFloat(),
                // Named after its field, and announcing the value: on its own it read "7 percent" for
                // a module of 1.000 mm (the position in a 0.2-12 range), with no name at all.
                modifier = Modifier.semantics {
                    contentDescription = label
                    stateDescription = (fmtNum(sliderValue, def.decimals, lang) + " " + unit).trim()
                }
            )
        }
        clampWarning?.let {
            // The theme's tertiary is the "attention, not an error" role; the previous literal
            // amber was chosen against the dark theme and was unreadable on the light one.
            // Polite live region: the value the user typed was replaced, and that has to be heard, not only seen.
            Text(
                "\u26A0 " + it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .padding(start = 12.dp, top = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        if (showHelp) {
            HelpText(def, lang)
        }
        // The range caption used to sit under every numeric field, which cost about 24 dp for each
        // of them — around a fifth of a panel the user has to scroll. It now appears with the
        // focus, exactly when an out-of-range entry is possible, and the row is skipped entirely
        // when it has nothing to show.
        val showRange = focused || scrubbing
        if (showRange || defaultValue != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showRange) RangeCaption(def, lang)
                if (defaultValue != null) {
                    Spacer(Modifier.weight(1f))
                    val standard = fmtNum(defaultValue.toFloat(), def.decimals, lang)
                    Text(
                        "\u21BA " + standard,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        // 48 dp like every other control: the link was a 20 dp high text run.
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(4.dp))
                            .semantics { contentDescription = I18n.t(lang, "standard_value", standard) }
                            .clickable(onClickLabel = I18n.t(lang, "reset_field"), role = Role.Button) {
                                val target = defaultValue.toFloat()
                                text = fmtNum(target, def.decimals, lang)
                                sliderValue = target
                                lastPreview = target
                                clampWarning = null
                                committed = target
                                onChange(target)
                            }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/** Compact choice input rendered as horizontally scrolling filter chips. */
@Composable
internal fun ChoiceRow(def: ParamDef, selected: String, lang: I18n.Lang, onSelect: (String) -> Unit) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val label = fieldLabel(def, lang)
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (def.help.isNotEmpty()) {
                HelpToggle(label, showHelp, lang) { showHelp = !showHelp }
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            def.options.forEach { o ->
                SelectChip(
                    selected = o == selected,
                    onClick = { onSelect(o) },
                    label = { Text(optionLabel(def, o, lang), style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        if (showHelp) {
            HelpText(def, lang)
        }
    }
}

/**
 * Boolean toggle rendered as a Material switch with label and optional help.
 *
 * The label and the switch are one toggleable node: a bare Switch next to a Text was announced as
 * "On, switch" with no name, because nothing tied the two together.
 */
@Composable
internal fun ToggleRow(def: ParamDef, value: Boolean, lang: I18n.Lang, onChange: (Boolean) -> Unit) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        val label = fieldLabel(def, lang)
        // The help button stays a separate control inside the toggleable row; a tap on it never toggles.
        Row(
            Modifier
                .heightIn(min = MinTouchTarget)
                .toggleable(value = value, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (def.help.isNotEmpty()) {
                HelpToggle(label, showHelp, lang) { showHelp = !showHelp }
            }
            Switch(checked = value, onCheckedChange = null)
        }
        if (showHelp) {
            HelpText(def, lang)
        }
    }
}

/** Localized tooltip/glossary explanation for a field (point 11). */
@Composable
private fun HelpText(def: ParamDef, lang: I18n.Lang) {
    Text(
        I18n.help(lang, def.key, def.help),
        Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Localized "valid range" caption shown under numeric controls. */
@Composable
private fun RangeCaption(def: ParamDef, lang: I18n.Lang) {
    if (def.kind != FieldKind.NUMBER || def.max <= def.min) return
    val min = fmtNum(def.min.toFloat(), def.decimals, lang)
    val max = fmtNum(def.max.toFloat(), def.decimals, lang)
    val unit = if (def.unit.isNotEmpty()) " ${unitLabel(def.unit, lang)}" else ""
    Text(
        "${I18n.t(lang, "valid_range")} $min–$max$unit.",
        Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Read-only calculated result row. */
@Composable
internal fun CalculatedRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
