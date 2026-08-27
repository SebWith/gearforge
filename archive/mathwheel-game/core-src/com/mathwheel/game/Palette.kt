package com.mathwheel.game

import com.badlogic.gdx.graphics.Color

object Palette {
    val bg = Color(0.06f, 0.025f, 0.11f, 1f)
    val bgGlow = Color(0.18f, 0.07f, 0.30f, 1f)
    val neonCyan = Color(0.0f, 0.9f, 1.0f, 1f)
    val neonMagenta = Color(1.0f, 0.18f, 0.6f, 1f)
    val neonYellow = Color(1.0f, 0.85f, 0.1f, 1f)
    val neonGreen = Color(0.2f, 1.0f, 0.55f, 1f)
    val neonPurple = Color(0.62f, 0.3f, 1.0f, 1f)
    val neonOrange = Color(1.0f, 0.45f, 0.2f, 1f)
    val neonRed = Color(1.0f, 0.2f, 0.3f, 1f)
    val text = Color(0.96f, 0.97f, 1.0f, 1f)
    val dim = Color(1f, 1f, 1f, 0.35f)

    val wheelColors = arrayOf(
        neonCyan, neonMagenta, neonYellow, neonGreen, neonPurple,
        neonOrange, Color(0.3f, 0.7f, 1f, 1f), neonRed,
        Color(0.4f, 1f, 0.9f, 1f), Color(0.9f, 0.5f, 1f, 1f)
    )
}
