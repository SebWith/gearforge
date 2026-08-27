package com.mathwheel.game

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.FitViewport
import kotlin.math.cos
import kotlin.math.sin

class MenuScreen(private val game: MathWheelGame) : ScreenAdapter() {

    private val VW = 720f
    private val VH = 1280f

    private val viewport = FitViewport(VW, VH)
    private val batch = SpriteBatch()
    private val shapes = ShapeRenderer()
    private val font = BitmapFont()
    private val touch = Vector2()
    private var time = 0f
    private var showScores = false
    private var cachedScores: List<ScoreEntry> = emptyList()

    private fun playRect() = floatArrayOf(VW / 2f - 170f, 430f, 340f, 140f)
    private fun langRect() = floatArrayOf(VW / 2f - 170f, 300f, 340f, 96f)
    private fun scoresRect() = floatArrayOf(VW / 2f - 170f, 170f, 340f, 96f)

    override fun render(delta: Float) {
        time += delta
        ScreenUtils.clear(Palette.bg)
        viewport.apply()
        handleInput()

        shapes.projectionMatrix = viewport.camera.combined
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        shapes.begin(ShapeRenderer.ShapeType.Filled)
        drawBackgroundGlow()
        drawDecorWheel()
        drawButton(playRect(), Palette.neonMagenta)
        drawButton(langRect(), Palette.neonPurple)
        drawButton(scoresRect(), Palette.neonCyan)
        shapes.end()

        batch.projectionMatrix = viewport.camera.combined
        batch.begin()
        font.data.setScale(7f)
        Ui.drawTextCenteredGlow(batch, font, I18n.t("title"), VW / 2f, 1090f, Palette.neonCyan, Palette.neonMagenta, 6f)
        font.data.setScale(2.4f)
        Ui.drawTextCentered(batch, font, "${I18n.t("best")}: ${game.state.highScore}", VW / 2f, 960f, Palette.text)
        font.data.setScale(2.8f)
        Ui.drawTextCentered(batch, font, I18n.t("tap_to_play"), playRect()[0] + playRect()[2] / 2f, playRect()[1] + playRect()[3] / 2f, Color.WHITE)
        font.data.setScale(2.4f)
        Ui.drawTextCentered(batch, font, I18n.t("lang"), langRect()[0] + langRect()[2] / 2f, langRect()[1] + langRect()[3] / 2f, Color.WHITE)
        Ui.drawTextCentered(batch, font, I18n.t("scores"), scoresRect()[0] + scoresRect()[2] / 2f, scoresRect()[1] + scoresRect()[3] / 2f, Color.WHITE)
        if (showScores) drawScoreOverlay()
        batch.end()
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
    }

    private fun handleInput() {
        if (!Gdx.input.justTouched()) return
        touch.set(Gdx.input.x.toFloat(), Gdx.input.y.toFloat())
        viewport.unproject(touch)

        if (showScores) {
            showScores = false
            return
        }
        when {
            contains(playRect(), touch.x, touch.y) -> game.goToGame()
            contains(langRect(), touch.x, touch.y) -> toggleLang()
            contains(scoresRect(), touch.x, touch.y) -> {
                showScores = true
                if (cachedScores.isEmpty()) {
                    game.services.leaderboard.fetchTopScores { cachedScores = it }
                }
            }
        }
    }

    private fun toggleLang() {
        game.state.language = if (game.state.language == Language.EN) Language.SV else Language.EN
        I18n.language = game.state.language
        game.state.save()
    }

    private fun drawBackgroundGlow() {
        shapes.setColor(Palette.bgGlow)
        shapes.circle(VW / 2f, 780f, 260f, 64)
        shapes.setColor(Color(0.1f, 0.05f, 0.18f, 1f))
        shapes.circle(VW / 2f, 780f, 220f, 64)
    }

    private fun drawDecorWheel() {
        val cx = VW / 2f
        val cy = 780f
        val r = 190f
        val seg = 360f / 8f
        val rad = MathUtils.degreesToRadians
        for (i in 0 until 8) {
            shapes.setColor(Palette.wheelColors[i])
            val a0 = (time * 24f + i * seg) * rad
            val a1 = a0 + seg * rad
            shapes.triangle(cx, cy,
                cx + cos(a0) * r, cy + sin(a0) * r,
                cx + cos(a1) * r, cy + sin(a1) * r)
        }
        shapes.setColor(Color(0.06f, 0.025f, 0.11f, 1f))
        shapes.circle(cx, cy, r * 0.42f, 48)
    }

    private fun drawButton(r: FloatArray, color: Color) {
        shapes.setColor(color)
        shapes.rect(r[0], r[1], r[2], r[3])
        shapes.setColor(Palette.bg)
        shapes.rect(r[0] + 6f, r[1] + 6f, r[2] - 12f, r[3] - 12f)
    }

    private fun drawScoreOverlay() {
        val x = 60f
        val w = VW - 120f
        val h = 440f
        val y = VH / 2f - h / 2f
        batch.end()

        shapes.projectionMatrix = viewport.camera.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.setColor(0f, 0f, 0f, 0.85f)
        shapes.rect(x - 10f, y - 10f, w + 20f, h + 20f)
        shapes.end()

        batch.begin()
        font.data.setScale(3f)
        Ui.drawTextCentered(batch, font, I18n.t("scores"), VW / 2f, y + h - 50f, Palette.neonCyan)
        val entries = if (cachedScores.isEmpty()) {
            listOf(ScoreEntry(1, "You", game.state.highScore, true))
        } else cachedScores
        font.data.setScale(2.2f)
        entries.take(5).forEachIndexed { i, e ->
            val yy = y + h - 110f - i * 62f
            val color = if (e.isLocal) Palette.neonYellow else Palette.text
            val line = "${e.rank}.  ${e.name}"
            Ui.drawTextCentered(batch, font, line, VW / 2f - 70f, yy, color)
            Ui.drawTextCentered(batch, font, e.score.toString(), VW / 2f + 170f, yy, color)
        }
        font.data.setScale(2f)
        Ui.drawTextCentered(batch, font, I18n.t("menu"), VW / 2f, y + 40f, Palette.dim)
    }

    private fun contains(r: FloatArray, x: Float, y: Float) =
        x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]
}
