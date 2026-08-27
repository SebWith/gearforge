package com.sweeprunner.game

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
}
