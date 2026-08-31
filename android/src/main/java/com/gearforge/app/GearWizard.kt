package com.gearforge.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gearforge.core.GearParams
import com.gearforge.core.GearSpec
import com.gearforge.core.GearType
import com.gearforge.core.Presets
import kotlinx.coroutines.delay

/**
 * Guided start: choose a gear type, then pick a recommended preset from a library or
 * go the custom route to tune every parameter by hand. The custom page is pre-filled
 * with the type's standard default so all parameters are visible and editable.
 */
@Composable
fun GearWizard(lang: I18n.Lang, onDone: (GearParams) -> Unit, onCancel: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    var type by remember { mutableStateOf(GearType.SPUR) }

    fun selectType(t: GearType) {
        type = t
        step = 1
    }

    when (step) {
        0 -> WizardTypeStep(lang = lang, onSelect = ::selectType, onCancel = onCancel)
        1 -> PresetLibraryPage(
            type = type,
            lang = lang,
            onPreset = { preset -> onDone(preset.params) },
            onCustom = { step = 2 },
            onBack = { step = 0 },
            onCancel = onCancel
        )
        2 -> CustomParamsPage(
            type = type,
            lang = lang,
            onDone = onDone,
            onBack = { step = 1 }
        )
    }
}

private val PRIMARY_TYPES = listOf(
    GearType.SPUR, GearType.BEVEL, GearType.RACK, GearType.PLANETARY,
    GearType.HELICAL, GearType.BELT
)

@Composable
private fun WizardTypeStep(lang: I18n.Lang, onSelect: (GearType) -> Unit, onCancel: () -> Unit) {
    var showMore by remember { mutableStateOf(false) }
    val extra = GearType.entries - PRIMARY_TYPES.toSet()
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(I18n.t(lang, "step_1_of_3"), style = MaterialTheme.typography.labelLarge)
        Text(I18n.t(lang, "choose_gear_type"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            I18n.t(lang, "choose_gear_hint"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        TypeGrid(PRIMARY_TYPES, lang, onSelect)
        TextButton(onClick = { showMore = !showMore }) {
            Text(if (showMore) I18n.t(lang, "fewer_gear_types") else I18n.t(lang, "more_gear_types"))
        }
        if (showMore) {
            TypeGrid(extra, lang, onSelect)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(I18n.t(lang, "cancel")) }
    }
}

/** Two-column grid of type cards. */
@Composable
private fun TypeGrid(types: List<GearType>, lang: I18n.Lang, onSelect: (GearType) -> Unit) {
    types.chunked(2).forEach { rowTypes ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowTypes.forEach { t ->
                TypeCard(t, lang, Modifier.weight(1f)) { onSelect(t) }
            }
            if (rowTypes.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun TypeCard(type: GearType, lang: I18n.Lang, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GearPreview3D(type = type, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(8.dp))
            Text(typeLabel(type, lang), style = MaterialTheme.typography.titleSmall)
            Text(
                shortDesc(type, lang),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---- Preset library (step 2) --------------------------------------------

@Composable
private fun PresetLibraryPage(
    type: GearType,
    lang: I18n.Lang,
    onPreset: (Presets.Preset) -> Unit,
    onCustom: () -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit
) {
    val presets = remember(type) { Presets.forType(type) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Text(I18n.t(lang, "step_2_of_3"), style = MaterialTheme.typography.labelLarge)
            Text(
                I18n.t(lang, "choose_preset_title"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                I18n.t(lang, "choose_preset_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 320.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(presets, key = { it.id }) { preset ->
                PresetCard(preset = preset, lang = lang, onClick = { onPreset(preset) })
            }
            item(key = "custom", span = { GridItemSpan(maxLineSpan) }) {
                CustomCard(lang = lang, onClick = onCustom)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(I18n.t(lang, "back"))
            }
            TextButton(onClick = onCancel) { Text(I18n.t(lang, "cancel")) }
        }
    }
}

/** A recommended preset card: 3D thumbnail, name, typical use, description and metadata chips. */
@Composable
private fun PresetCard(preset: Presets.Preset, lang: I18n.Lang, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GearPreview3D(params = preset.params, modifier = Modifier.size(84.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (lang == I18n.Lang.SV) preset.nameSv else preset.nameEn,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (lang == I18n.Lang.SV) preset.useSv else preset.useEn,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (lang == I18n.Lang.SV) preset.descriptionSv else preset.descriptionEn,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presetMeta(preset.params, lang).forEach { meta ->
                        MetaChip(meta)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaChip(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Compact, language-neutral metadata chips; belt drives show belt-relevant facts. */
private fun presetMeta(p: GearParams, lang: I18n.Lang): List<String> {
    if (p.gearType == GearType.BELT) {
        return listOf(
            p.beltProfile,
            p.beltDriverTeeth.toString() + ":" + p.beltDrivenTeeth,
            Format.decimal(p.beltWidthMm, 1, lang) + " mm"
        )
    }
    val mod = "M " + moduleShort(p.module, lang)
    val teeth = p.teeth.toString() + " " + I18n.t(lang, "teeth")
    val pa = Format.decimal(p.pressureAngleDeg, 0, lang) + "\u00B0"
    return listOf(mod, teeth, pa, p.material)
}

private fun moduleShort(m: Double, lang: I18n.Lang): String {
    var s = Format.decimal(m, 2, lang)
    s = s.trimEnd('0')
    s = s.trimEnd(if (lang == I18n.Lang.SV) ',' else '.')
    return s
}

/** The clearly-separated custom entry point. */
@Composable
private fun CustomCard(lang: I18n.Lang, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    I18n.t(lang, "custom_title"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    I18n.t(lang, "custom_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

// ---- Custom parameter editor (step 3) ------------------------------------

@Composable
private fun CustomParamsPage(
    type: GearType,
    lang: I18n.Lang,
    onDone: (GearParams) -> Unit,
    onBack: () -> Unit
) {
    var params by remember(type) { mutableStateOf(GearSpec.defaults(type)) }

    // Debounced live 3D preview: updates after the user pauses, mirroring the final mesh.
    var previewParams by remember(type) { mutableStateOf(params) }
    LaunchedEffect(params) {
        delay(300)
        previewParams = params
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(I18n.t(lang, "step_3_of_3"), style = MaterialTheme.typography.labelLarge)
                Text(
                    I18n.t(lang, "custom_title"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    I18n.t(lang, "custom_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            GearPreview3D(params = previewParams, modifier = Modifier.size(96.dp))
        }
        SettingsPanel(
            params = params,
            onNumber = { key, v -> params = GearSpec.setNumber(params, key, v.toDouble()) },
            onChoice = { key, v -> params = GearSpec.setChoice(params, key, v) },
            onBool = { key, v -> params = GearSpec.setBool(params, key, v) },
            lang = lang,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onToothOverrides = { ov -> params = params.copy(toothOverrides = ov) }
        )
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(I18n.t(lang, "back"))
            }
            // Primary CTA: carry the current parameter set into the real 3D view
            // (the editor), preserving every parameter without resetting settings.
            Button(onClick = { onDone(params) }, modifier = Modifier.weight(1.6f)) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(I18n.t(lang, "continue_to_3d"))
            }
        }
    }
}

private fun shortDesc(t: GearType, lang: I18n.Lang): String =
    I18n.t(lang, "type_desc_" + t.name.lowercase())
