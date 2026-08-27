package com.mathwheel.game

enum class Language { EN, SV }

object I18n {
    var language = Language.EN

    private val map: Map<String, Map<Language, String>> = mapOf(
        "title" to mapOf(Language.EN to "MATH SPIN", Language.SV to "MATTE SPINN"),
        "tap_to_play" to mapOf(Language.EN to "TAP TO PLAY", Language.SV to "TRYCK FÖR ATT SPELA"),
        "level" to mapOf(Language.EN to "LEVEL", Language.SV to "NIVÅ"),
        "section" to mapOf(Language.EN to "SECTION", Language.SV to "SEKTION"),
        "time" to mapOf(Language.EN to "TIME", Language.SV to "TID"),
        "coins" to mapOf(Language.EN to "COINS", Language.SV to "MYNT"),
        "best" to mapOf(Language.EN to "BEST", Language.SV to "BÄSTA"),
        "correct" to mapOf(Language.EN to "CORRECT!", Language.SV to "RÄTT!"),
        "wrong" to mapOf(Language.EN to "WRONG", Language.SV to "FEL"),
        "time_up" to mapOf(Language.EN to "TIME'S UP!", Language.SV to "TIDEN ÄR SLUT!"),
        "new_highscore" to mapOf(Language.EN to "NEW HIGHSCORE!", Language.SV to "NYTT REKORD!"),
        "section_cleared" to mapOf(Language.EN to "SECTION CLEARED!", Language.SV to "SEKTION KLAR!"),
        "shield_saved" to mapOf(Language.EN to "SHIELD SAVED YOU!", Language.SV to "SKÖLDEN RÄDDADE DIG!"),
        "highlight" to mapOf(Language.EN to "HIGHLIGHT", Language.SV to "MARKERA"),
        "shield" to mapOf(Language.EN to "SHIELD", Language.SV to "SKÖLD"),
        "freeze" to mapOf(Language.EN to "FREEZE", Language.SV to "FRYS"),
        "scores" to mapOf(Language.EN to "SCORES", Language.SV to "POÄNG"),
        "menu" to mapOf(Language.EN to "MENU", Language.SV to "MENY"),
        "lang" to mapOf(Language.EN to "SVENSKA", Language.SV to "ENGLISH"),
        "streak" to mapOf(Language.EN to "STREAK", Language.SV to "SVIT"),
        "tap_to_stop" to mapOf(Language.EN to "TAP TO STOP ON THE ANSWER", Language.SV to "TRYCK FÖR ATT STANNA PÅ SVARET")
    )

    fun t(key: String): String = map[key]?.get(language) ?: key
}
