package com.sweeprunner.game

import com.badlogic.gdx.Gdx

class GameState {
    var coins = 0
        private set
    var highScore = 0
        private set
    var unlockedLevel = 1
        private set
    var soundOn = true
    var hapticsOn = true
    var language = Language.EN
    var selectedSkin = "default"

    private val ownedAbilities = mutableSetOf<String>()
    private val stars = IntArray(10)

    private val prefs = Gdx.app.getPreferences("sweeprunner")

    init { load() }

    fun addCoins(n: Int) {
        if (n <= 0) return
        coins += n
        save()
    }

    fun trySpend(n: Int): Boolean {
        if (coins < n) return false
        coins -= n
        save()
        return true
    }

    fun recordScore(score: Int) {
        if (score > highScore) {
            highScore = score
            save()
        }
    }

    fun unlockLevel(l: Int) {
        if (l in (unlockedLevel + 1)..10) {
            unlockedLevel = l
            save()
        }
    }

    fun setStars(level: Int, s: Int) {
        if (level in 1..10) {
            stars[level - 1] = maxOf(stars[level - 1], s)
            save()
        }
    }

    fun starsFor(level: Int): Int = if (level in 1..10) stars[level - 1] else 0

    fun ownsAbility(id: String) = ownedAbilities.contains(id)

    fun buyAbility(id: String, cost: Int): Boolean {
        if (ownedAbilities.contains(id)) return false
        if (!trySpend(cost)) return false
        ownedAbilities.add(id)
        save()
        return true
    }

    fun buySkin(id: String, cost: Int): Boolean {
        if (id == "default" || selectedSkin == id) return false
        if (!trySpend(cost)) return false
        selectedSkin = id
        save()
        return true
    }

    fun save() {
        prefs.putInteger("coins", coins)
        prefs.putInteger("highScore", highScore)
        prefs.putInteger("unlockedLevel", unlockedLevel)
        prefs.putString("language", language.name)
        prefs.putBoolean("soundOn", soundOn)
        prefs.putBoolean("hapticsOn", hapticsOn)
        prefs.putString("skin", selectedSkin)
        prefs.putString("abilities", ownedAbilities.joinToString(","))
        for (i in 1..10) prefs.putInteger("stars$i", stars[i - 1])
        prefs.flush()
    }

    fun load() {
        coins = prefs.getInteger("coins", 0)
        highScore = prefs.getInteger("highScore", 0)
        unlockedLevel = prefs.getInteger("unlockedLevel", 1)
        language = runCatching { Language.valueOf(prefs.getString("language", "EN")) }
            .getOrDefault(Language.EN)
        soundOn = prefs.getBoolean("soundOn", true)
        hapticsOn = prefs.getBoolean("hapticsOn", true)
        selectedSkin = prefs.getString("skin", "default")
        ownedAbilities.clear()
        prefs.getString("abilities", "").split(",").filter { it.isNotBlank() }
            .forEach { ownedAbilities.add(it) }
        for (i in 1..10) stars[i - 1] = prefs.getInteger("stars$i", 0)
        I18n.language = language
    }
}
