package com.mathwheel.game

data class LevelConfig(
    val section: Int,
    val level: Int,
    val optionCount: Int,
    val timeSeconds: Float,
    val coinReward: Int,
    val spinSpeed: Float
)

object DifficultyManager {
    fun config(section: Int, level: Int): LevelConfig {
        val optionCount = (3 + section + level / 3).coerceIn(3, 10)
        val timeSeconds = (14f - section * 1.1f - level * 0.5f).coerceIn(4f, 14f)
        val coinReward = 4 + section * 2 + level
        val spinSpeed = (160f + section * 18f + level * 6f).coerceAtMost(300f)
        return LevelConfig(section, level, optionCount, timeSeconds, coinReward, spinSpeed)
    }
}
