package com.sweeprunner.game

/** Abstraction over Android-specific capabilities so core stays platform-independent. */
interface Haptics {
    fun vibrate(ms: Long)
    fun heavy()
    fun light()
}

object NoopHaptics : Haptics {
    override fun vibrate(ms: Long) {}
    override fun heavy() {}
    override fun light() {}
}

data class ScoreEntry(val rank: Int, val name: String, val score: Int, val isLocal: Boolean)

interface LeaderboardService {
    fun submitScore(score: Int)
    fun showLeaderboard()
    fun fetchTopScores(callback: (List<ScoreEntry>) -> Unit)
}

interface PlatformServices {
    val haptics: Haptics
    val leaderboard: LeaderboardService
}

/** Local fallback leaderboard with simulated "ghost" competitors. */
class LocalLeaderboard : LeaderboardService {
    private val ghosts = listOf(
        ScoreEntry(1, "Bolt", 1280, false),
        ScoreEntry(2, "Comet", 970, false),
        ScoreEntry(3, "Pixel", 650, false),
        ScoreEntry(4, "Nova", 420, false),
        ScoreEntry(5, "You", 0, true)
    )

    override fun submitScore(score: Int) { /* GPGS wiring lands here later */ }
    override fun showLeaderboard() { /* GPGS wiring lands here later */ }

    override fun fetchTopScores(callback: (List<ScoreEntry>) -> Unit) {
        callback(ghosts.map {
            if (it.isLocal) it.copy(score = 0) else it
        })
    }
}
