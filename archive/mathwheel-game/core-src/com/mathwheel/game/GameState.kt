package com.mathwheel.game

import com.badlogic.gdx.Gdx

class GameState {
    var coins = 0
        private set
    var highScore = 0
        private set
    var language = Language.EN
    var soundOn = true
    var hapticsOn = true

    private val prefs = Gdx.app.getPreferences("mathwheel")

    init { load() }

    fun addCoins(n: Int) {
        coins += n
        save()
    }

    fun trySpend(n: Int): Boolean {
        if (coins < n) return false
        coins -= n
        save()
        return true
    }

    fun progressScore(section: Int, level: Int) = section * 100 + level

    fun recordScore(score: Int) {
        if (score > highScore) {
            highScore = score
            save()
        }
    }

    fun save() {
        prefs.putInteger("coins", coins)
        prefs.putInteger("highScore", highScore)
        prefs.putString("language", language.name)
        prefs.putBoolean("soundOn", soundOn)
        prefs.putBoolean("hapticsOn", hapticsOn)
        prefs.flush()
    }

    fun load() {
        coins = prefs.getInteger("coins", 0)
        highScore = prefs.getInteger("highScore", 0)
        language = runCatching { Language.valueOf(prefs.getString("language", "EN")) }
            .getOrDefault(Language.EN)
        soundOn = prefs.getBoolean("soundOn", true)
        hapticsOn = prefs.getBoolean("hapticsOn", true)
        I18n.language = language
    }
}
