package com.sweeprunner.game

enum class Language { EN, SV }

object I18n {
    var language = Language.EN

    private val map: Map<String, Map<Language, String>> = mapOf(
        "title" to mapOf(Language.EN to "SWEEP RUNNER", Language.SV to "SWEEP RUNNER"),
        "play" to mapOf(Language.EN to "PLAY", Language.SV to "SPELA"),
        "shop" to mapOf(Language.EN to "SHOP", Language.SV to "BUTIK"),
        "settings" to mapOf(Language.EN to "SETTINGS", Language.SV to "INSTÄLLNINGAR"),
        "level" to mapOf(Language.EN to "LEVEL", Language.SV to "NIVÅ"),
        "score" to mapOf(Language.EN to "SCORE", Language.SV to "POÄNG"),
        "coins" to mapOf(Language.EN to "COINS", Language.SV to "MYNT"),
        "lives" to mapOf(Language.EN to "LIVES", Language.SV to "LIV"),
        "best" to mapOf(Language.EN to "BEST", Language.SV to "BÄSTA"),
        "level_clear" to mapOf(Language.EN to "LEVEL CLEAR!", Language.SV to "NIVÅ KLAR!"),
        "game_over" to mapOf(Language.EN to "GAME OVER", Language.SV to "SPELET SLUT"),
        "play_again" to mapOf(Language.EN to "PLAY AGAIN", Language.SV to "SPELA OM"),
        "next_level" to mapOf(Language.EN to "NEXT", Language.SV to "NÄSTA"),
        "menu" to mapOf(Language.EN to "MENU", Language.SV to "MENY"),
        "lang" to mapOf(Language.EN to "SVENSKA", Language.SV to "ENGLISH"),
        "swipe_hint" to mapOf(Language.EN to "Swipe away obstacles!", Language.SV to "Svep bort hinder!"),
        "perfect" to mapOf(Language.EN to "PERFECT!", Language.SV to "PERFEKT!"),
        "magnet" to mapOf(Language.EN to "MAGNET", Language.SV to "MAGNET"),
        "shield" to mapOf(Language.EN to "SHIELD", Language.SV to "SKÖLD"),
        "megasweep" to mapOf(Language.EN to "MEGASWEEP", Language.SV to "MEGASVEP"),
        "tap_play" to mapOf(Language.EN to "TAP TO PLAY", Language.SV to "TRYCK FÖR ATT SPELA")
    )

    fun t(key: String): String = map[key]?.get(language) ?: key
}
