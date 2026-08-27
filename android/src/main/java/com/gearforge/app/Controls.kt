package com.gearforge.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gearforge.core.FieldKind
import com.gearforge.core.GearType
import com.gearforge.core.ParamDef
import com.gearforge.core.ParamGroup

internal fun typeLabel(t: GearType, lang: I18n.Lang): String =
    I18n.t(lang, "type_" + t.name.lowercase())

internal fun groupLabel(g: ParamGroup, lang: I18n.Lang): String =
    I18n.t(lang, "group_" + g.name.lowercase())

/** Per-section accent colour used to visually distinguish parameter groups. */
internal fun groupAccent(g: ParamGroup): Color = when (g) {
    ParamGroup.GEOMETRY -> Color(0xFF1E88E5)
    ParamGroup.MATERIAL -> Color(0xFF00897B)
    ParamGroup.TOLERANCES -> Color(0xFFF57C00)
    ParamGroup.LOAD -> Color(0xFF8E24AA)
    ParamGroup.HUB -> Color(0xFF6D4C41)
    ParamGroup.TEETH -> Color(0xFFD81B60)
    ParamGroup.LIGHTENING -> Color(0xFF546E7A)
    ParamGroup.RESULTS -> Color(0xFF43A047)
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
        else -> null
    } ?: return option
    val localized = I18n.t(lang, key)
    return if (localized != key) localized else option
}

internal fun fmtNum(v: Float, decimals: Int, lang: I18n.Lang): String =
    Format.decimal(v.toDouble(), decimals, lang)

/** Compact number input: editable text field (primary) plus an optional drag slider. */
@Composable
internal fun NumberRow(
    def: ParamDef,
    value: Float,
    context: com.gearforge.core.GearParams,
    lang: I18n.Lang,
    onChange: (Float) -> Unit
) {
    var text by remember(value) { mutableStateOf(fmtNum(value, def.decimals, lang)) }
    var committed by remember(value) { mutableStateOf(value) }
    var clampWarning by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    // Tracks the thumb during a drag so the slider follows the finger instead of snapping
    // back to the committed value on every recomposition.
    var sliderValue by remember(value) { mutableStateOf(value.coerceIn(def.min.toFloat(), def.max.toFloat())) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

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
        if (v != null && v != committed.toDouble()) {
            committed = v.toFloat()
            onChange(v.toFloat())
        }
    }

    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fieldLabel(def, lang), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (def.help.isNotEmpty()) {
                IconButton(onClick = { showHelp = !showHelp }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Info, contentDescription = I18n.t(lang, "help_tooltip"))
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .width(96.dp)
                    .onFocusChanged { if (!it.isFocused) apply() },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (def.decimals == 0) KeyboardType.Number else KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    apply()
                    focus.clearFocus()
                    keyboard?.hide()
                })
            )
            if (def.unit.isNotEmpty()) {
                Text(unitLabel(def.unit, lang), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (def.max > def.min) {
            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = it
                    text = fmtNum(it, def.decimals, lang)
                },
                onValueChangeFinished = { apply() },
                valueRange = def.min.toFloat()..def.max.toFloat()
            )
        }
        clampWarning?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFF9A825),
                modifier = Modifier.padding(start = 12.dp, top = 4.dp)
            )
        }
        if (showHelp) {
            HelpText(def, lang)
        }
        RangeCaption(def, lang)
    }
}

/** Compact choice input rendered as horizontally scrolling filter chips. */
@Composable
internal fun ChoiceRow(def: ParamDef, selected: String, lang: I18n.Lang, onSelect: (String) -> Unit) {
    var showHelp by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fieldLabel(def, lang), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (def.help.isNotEmpty()) {
                IconButton(onClick = { showHelp = !showHelp }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.Info, contentDescription = I18n.t(lang, "help_tooltip"))
                }
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            def.options.forEach { o ->
                FilterChip(
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
