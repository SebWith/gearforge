package com.gearforge.app

import android.content.Context
import android.content.SharedPreferences

/** Persists lightweight preferences: theme, language, units and Pro status. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gearforge", Context.MODE_PRIVATE)

    var darkTheme: Boolean
        get() = prefs.getBoolean("darkTheme", true)
        set(value) = prefs.edit().putBoolean("darkTheme", value).apply()

    var lang: I18n.Lang
        get() = if (prefs.getString("lang", "en") == "sv") I18n.Lang.SV else I18n.Lang.EN
        set(value) = prefs.edit().putString("lang", if (value == I18n.Lang.SV) "sv" else "en").apply()

    var useInch: Boolean
        get() = prefs.getBoolean("useInch", false)
        set(value) = prefs.edit().putBoolean("useInch", value).apply()

    var isPro: Boolean
        get() = prefs.getBoolean("isPro", false)
        set(value) = prefs.edit().putBoolean("isPro", value).apply()

    var freeAdvancedExports: Int
        get() = prefs.getInt("freeAdvancedExports", 3)
        set(value) = prefs.edit().putInt("freeAdvancedExports", value).apply()

    var highQuality: Boolean
        get() = prefs.getBoolean("highQuality", true)
        set(value) = prefs.edit().putBoolean("highQuality", value).apply()

    fun consumeAdvancedExport(): Int {
        val left = (freeAdvancedExports - 1).coerceAtLeast(0)
        freeAdvancedExports = left
        return left
    }
}
