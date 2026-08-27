package com.sweeprunner.game

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.FitViewport

class MenuScreen(private val game: SweepRunnerGame) : ScreenAdapter() {
    private val VW = 720f
    private val VH = 1280f
    private val viewport = FitViewport(VW, VH)
    private val batch = SpriteBatch()
    private val shapes = ShapeRenderer()
    private val font = BitmapFont()
    private val touch = Vector2()

    private val playBtn = Rectangle((VW - 360f) / 2f, VH * 0.40f, 360f, 130f)
    private val shopBtn = Rectangle((VW - 360f) / 2f, VH * 0.27f, 360f, 110f)
    private val settingsBtn = Rectangle((VW - 360f) / 2f, VH * 0.15f, 360f, 110f)

    override fun show() {
        Gdx.input.inputProcessor = null
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
    }

    override fun render(delta: Float) {
        ScreenUtils.clear(Palette.skyTop)
        viewport.apply()
        batch.projectionMatrix = viewport.camera.combined
        shapes.projectionMatrix = viewport.camera.combined

        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = Palette.skyBottom
        shapes.rect(0f, 0f, VW, VH)
        shapes.color = Palette.skyTop
        shapes.rect(0f, VH * 0.45f, VW, VH * 0.55f)
        drawCloud(140f, 1120f, 90f)
        drawCloud(560f, 1180f, 70f)
        drawCloud(360f, 940f, 58f)
        drawCloud(90f, 760f, 52f)
        drawButton(playBtn)
        drawButton(shopBtn)
        drawButton(settingsBtn)
        shapes.end()

        batch.begin()
        font.data.setScale(3f)
        Ui.drawTextCentered(batch, font, I18n.t("title"), VW / 2f, VH * 0.82f, Palette.white)
        font.data.setScale(1.5f)
        Ui.drawTextCentered(batch, font, I18n.t("play"), playBtn.x + playBtn.width / 2f, playBtn.y + playBtn.height / 2f, Palette.white)
        Ui.drawTextCentered(batch, font, I18n.t("shop"), shopBtn.x + shopBtn.width / 2f, shopBtn.y + shopBtn.height / 2f, Palette.white)
        Ui.drawTextCentered(batch, font, I18n.t("settings"), settingsBtn.x + settingsBtn.width / 2f, settingsBtn.y + settingsBtn.height / 2f, Palette.white)
        font.data.setScale(1.2f)
        Ui.drawTextCentered(batch, font, "${I18n.t("coins")}: ${game.state.coins}", VW / 2f, VH * 0.06f, Palette.text)
        font.data.setScale(1f)
        batch.end()

        if (Gdx.input.justTouched()) {
            val w = viewport.unproject(touch.set(Gdx.input.x.toFloat(), Gdx.input.y.toFloat()))
            when {
                playBtn.contains(w.x, w.y) -> game.goToGame(game.state.unlockedLevel)
                shopBtn.contains(w.x, w.y) -> Unit // butik byggs senare
                settingsBtn.contains(w.x, w.y) -> Unit // inställningar byggs senare
            }
        }
    }

    private fun drawButton(r: Rectangle) {
        shapes.color = Palette.button
        shapes.rect(r.x, r.y, r.width, r.height)
    }

    private fun drawCloud(x: Float, y: Float, s: Float) {
        shapes.color = Palette.cloud
        shapes.circle(x, y, s)
        shapes.circle(x - s * 0.8f, y - s * 0.2f, s * 0.7f)
        shapes.circle(x + s * 0.8f, y - s * 0.2f, s * 0.7f)
        shapes.circle(x, y + s * 0.35f, s * 0.75f)
    }
}
