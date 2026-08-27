package com.sweeprunner.game

import com.badlogic.gdx.Game
import kotlin.random.Random

class SweepRunnerGame(val services: PlatformServices) : Game() {
    lateinit var state: GameState
    lateinit var menuScreen: MenuScreen
    lateinit var gameScreen: GameScreen
    val rng = Random(System.nanoTime())

    override fun create() {
        state = GameState()
        I18n.language = state.language
        menuScreen = MenuScreen(this)
        gameScreen = GameScreen(this)
        setScreen(menuScreen)
    }

    fun goToGame(level: Int) {
        gameScreen.start(level)
        setScreen(gameScreen)
    }

    fun goToMenu() {
        state.save()
        setScreen(menuScreen)
    }

    override fun dispose() {
        state.save()
        super.dispose()
    }
}
