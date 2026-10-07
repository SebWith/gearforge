package com.gearforge.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The nozzle, layer-height and bed values offered in Settings.
 *
 * A fixed set rather than free text: a mistyped nozzle size silently makes every print
 * recommendation wrong, and these values cover essentially every consumer FDM machine. The bed
 * sizes are the common square platforms.
 */
object PrinterPresets {
    val NOZZLES = listOf(0.25, 0.4, 0.6, 0.8)
    val LAYER_HEIGHTS = listOf(0.1, 0.15, 0.2, 0.3)
    val BED_SIZES = listOf(180.0, 220.0, 235.0, 256.0, 300.0)

    internal fun normalize(stored: Float, presets: List<Double>): Double =
        presets.firstOrNull { it.toFloat() == stored } ?: stored.toDouble()
}

/**
 * Playback speed multipliers for the meshing animation, slowest first.
 *
 * A discrete ladder rather than a slider: the value is a comparison aid ("is this train turning
 * about as fast as the real one?"), and a slider invites fiddling with a number that has no right
 * answer while hiding the ones that do.
 *
 * Every step is a fraction of the **fastest** one, and the fastest one is the base rate and the
 * default ([BASE_PLAYBACK_SPEED]). The ladder is a halving series downwards from the base —
 * 1×, 0.5×, 0.25×, 0.125× — so a chip that reads 0.25× means a quarter of the rate the user is
 * watching when the train first turns, which is the comparison the control exists to support. The
 * four rates are the ones the app already offered (one revolution per second down to one every eight
 * seconds); what changed is which end of that span is the base, and that the labels are now measured
 * against it instead of against a rate that was never on screen. See
 * [com.gearforge.core.MeshKinematics.DEFAULT_SPEED_RAD_PER_S] for why the base sits at the fast end.
 */
val PLAYBACK_SPEEDS = listOf(0.125f, 0.25f, 0.5f, 1f)

/** The base rate's own chip: the fastest step, and the one the app selects without being asked. */
val BASE_PLAYBACK_SPEED: Float = PLAYBACK_SPEEDS.last()

/**
 * Decimal places a chip needs to state [speed] exactly.
 *
 * Derived rather than tabulated, so a step cannot be added with a label that rounds it into a
 * *different* number: 0.125 at two decimals is 0.13, and 0.75 at one decimal is 0.8 — rates the app
 * does not offer, advertised by the chip that is supposed to name one it does.
 */
fun playbackSpeedDecimals(speed: Float): Int = when {
    speed % 1f == 0f -> 0
    speed * 10f % 1f == 0f -> 1
    speed * 100f % 1f == 0f -> 2
    else -> 3
}

/**
 * The offered multiplier closest to [stored].
 *
 * A value written by another build (or a hand-edited preference) must still select a chip: without
 * this, the control would render with nothing highlighted and no way back.
 */
fun nearestPlaybackSpeed(stored: Float): Float =
    PLAYBACK_SPEEDS.minByOrNull { kotlin.math.abs(it - stored) } ?: BASE_PLAYBACK_SPEED

/**
 * Persists lightweight preferences: theme, language, units, print profile, viewport options, tips
 * and Pro status.
 *
 * Every write goes through `edit { … }` from `androidx.core.content` (the KTX form). The explicit
 * `edit().put…().apply()` chain was correct, but lint flagged it at 14 sites: one obvious spelling of
 * "save a preference" is easier to keep consistent than two.
 */
class SettingsStore internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.getSharedPreferences("gearforge", Context.MODE_PRIVATE)
    )

    var darkTheme: Boolean
        get() = prefs.getBoolean("darkTheme", true)
        set(value) = prefs.edit { putBoolean("darkTheme", value) }

    var lang: I18n.Lang
        get() = if (prefs.getString("lang", "en") == "sv") I18n.Lang.SV else I18n.Lang.EN
        set(value) = prefs.edit { putString("lang", if (value == I18n.Lang.SV) "sv" else "en") }

    var useInch: Boolean
        get() = prefs.getBoolean("useInch", false)
        set(value) = prefs.edit { putBoolean("useInch", value) }

    var isPro: Boolean
        get() = prefs.getBoolean("isPro", false)
        set(value) = prefs.edit { putBoolean("isPro", value) }

    var freeAdvancedExports: Int
        get() = prefs.getInt("freeAdvancedExports", 3)
        set(value) = prefs.edit { putInt("freeAdvancedExports", value) }

    var highQuality: Boolean
        get() = prefs.getBoolean("highQuality", true)
        set(value) = prefs.edit { putBoolean("highQuality", value) }

    /**
     * True when the measurement HUD is overlaid on the 3D viewport.
     *
     * Defaults to on: the dimensions are the answer to the question the editor exists to ask
     * ("what have I built?" and "does it fit?"), and before the HUD they were only reachable
     * through the parameters sheet with the RESULTS section expanded. Off is for users who
     * want an unobstructed model.
     */
    var hudEnabled: Boolean
        get() = prefs.getBoolean("hudEnabled", true)
        set(value) = prefs.edit { putBoolean("hudEnabled", value) }

    /** True when the calibrated scale bar is drawn in the measurement HUD. */
    var hudScaleBar: Boolean
        get() = prefs.getBoolean("hudScaleBar", true)
        set(value) = prefs.edit { putBoolean("hudScaleBar", value) }

    /**
     * Whether the print bed is drawn in the viewport. Off by default: the standard view is framed on
     * the model, and framing a 220 mm platen around a small gear is a deliberate choice.
     */
    var showBed: Boolean
        get() = prefs.getBoolean("showBed", false)
        set(value) = prefs.edit { putBoolean("showBed", value) }

    /**
     * Nozzle diameter in millimetres.
     *
     * [com.gearforge.core.PrintAdvisor] derives the smallest printable module from this
     * (`minimumPrintableModule = 2 × nozzle`), so a wrong value makes every print recommendation
     * wrong. It used to be hard-coded to 0.4 mm at the call site.
     */
    var nozzleMm: Double
        get() = PrinterPresets.normalize(prefs.getFloat("nozzleMm", 0.4f), PrinterPresets.NOZZLES)
        set(value) = prefs.edit { putFloat("nozzleMm", value.toFloat()) }

    /** Layer height in millimetres, used to judge whether the flanks are too coarse. */
    var layerHeightMm: Double
        get() = PrinterPresets.normalize(prefs.getFloat("layerHeightMm", 0.2f), PrinterPresets.LAYER_HEIGHTS)
        set(value) = prefs.edit { putFloat("layerHeightMm", value.toFloat()) }

    /** Square print bed side in millimetres, used by the viewport's bed reference. */
    var bedSizeMm: Double
        get() = prefs.getFloat("bedSizeMm", 220.0f).toDouble()
        set(value) = prefs.edit { putFloat("bedSizeMm", value.toFloat()) }

    /**
     * Playback speed multiplier for the meshing animation, from [PLAYBACK_SPEEDS].
     *
     * Persisted because the reason to change it does not expire with the session: a planetary train
     * is watched at a different rate than a single gear, and re-picking the speed on every launch
     * would make the control feel broken. The default is [BASE_PLAYBACK_SPEED] — the fastest step,
     * which is the base rate the labels are fractions of.
     */
    var playbackSpeed: Float
        get() = prefs.getFloat("playbackSpeed", BASE_PLAYBACK_SPEED)
        set(value) = prefs.edit { putFloat("playbackSpeed", value) }

    /**
     * Index of the next new-user tip to show; `Tips.DONE` means the walkthrough is finished.
     *
     * The tips used to be "once per session", which is not what a first-run explainer should be: a
     * returning user met the same three hints on every launch, and a rotation (or a process death)
     * brought them back mid-flow. Storing the *position* rather than a boolean is what lets the
     * walkthrough span the wizard and the editor — each screen asks "is it my turn?" — and Settings
     * offers to start it over for anyone who dismissed it too quickly.
     */
    var tipsStep: Int
        get() = prefs.getInt("tipsStep", Tips.FIRST)
        set(value) = prefs.edit { putInt("tipsStep", value.coerceIn(Tips.FIRST, Tips.DONE)) }

    /**
     * True once the one-off post-export Play In-App Review card has been *requested*.
     *
     * The review flow is quota-limited and may silently show nothing, so the flag is
     * set when we ask rather than when something is displayed — the user is never
     * prompted twice for the same install.
     */
    var reviewRequested: Boolean
        get() = prefs.getBoolean("reviewRequested", false)
        set(value) = prefs.edit { putBoolean("reviewRequested", value) }

    fun consumeAdvancedExport(): Int {
        val left = (freeAdvancedExports - 1).coerceAtLeast(0)
        freeAdvancedExports = left
        return left
    }
}
