package com.mathwheel.game

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch

object Ui {
    private val layout = GlyphLayout()

    fun drawTextCentered(batch: SpriteBatch, font: BitmapFont, text: String, cx: Float, cy: Float, color: Color) {
        layout.setText(font, text)
        val old = font.color.cpy()
        font.color = color
        font.draw(batch, text, cx - layout.width / 2f, cy + layout.height / 2f)
        font.color = old
    }

    fun drawTextCenteredGlow(
        batch: SpriteBatch, font: BitmapFont, text: String,
        cx: Float, cy: Float, color: Color, glow: Color, glowSize: Float = 3f
    ) {
        layout.setText(font, text)
        val x = cx - layout.width / 2f
        val y = cy + layout.height / 2f
        val old = font.color.cpy()
        font.color = glow
        for (dx in floatArrayOf(-glowSize, glowSize)) {
            for (dy in floatArrayOf(-glowSize, glowSize)) {
                font.draw(batch, text, x + dx, y + dy)
            }
        }
        font.color = color
        font.draw(batch, text, x, y)
        font.color = old
    }
}
