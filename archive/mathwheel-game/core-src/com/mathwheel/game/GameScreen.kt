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

class GameScreen(private val game: MathWheelGame) : ScreenAdapter() {

    private enum class Phase { PLAY, CORRECT, WRONG, TIMEOUT, SHIELD }

    private val VW = 720f
    private val VH = 1280f
    private val CX = 360f
    private val CY = 620f
    private val WHEEL_R = 290f
    private val HUB_R = 105f

    private val viewport = FitViewport(VW, VH)
    private val batch = SpriteBatch()
    private val shapes = ShapeRenderer()
    private val font = BitmapFont()
    private val touch = Vector2()
    private val particles = ParticleSystem()
    private val shake = ScreenShake()

    private lateinit var wheel: WheelModel
    private lateinit var equation: Equation
    private lateinit var config: LevelConfig

    private var section = 1
    private var level = 1
    private var streak = 0
    private var timeLeft = 10f
    private var phase = Phase.PLAY
    private var resultTimer = 0f
    private var resultText = ""
    private var lastReward = 0
    private var highlightActive = false
    private var shieldActive = false
    private var freezeTimer = 0f
    private var shakeX = 0f
    private var shakeY = 0f

    private val COST_HIGHLIGHT = 10
    private val COST_SHIELD = 15
    private val COST_FREEZE = 8

    fun start() {
        section = 1
        level = 1
        streak = 0
        highlightActive = false
        shieldActive = false
        particles.clear()
        newRound()
    }

    private fun newRound() {
        config = DifficultyManager.config(section, level)
        equation = MathGenerator.generate(section, level, config.optionCount, game.rng)
        wheel = WheelModel(config.optionCount)
        wheel.startSpin(config.spinSpeed)
        timeLeft = config.timeSeconds
        phase = Phase.PLAY
        resultTimer = 0f
        highlightActive = false
        freezeTimer = 0f
    }

    private fun correctIndex() = equation.options.indexOf(equation.answer)

    override fun render(delta: Float) {
        update(delta)
        ScreenUtils.clear(Palette.bg)
        viewport.apply()
        viewport.camera.translate(shakeX, shakeY, 0f)
        viewport.camera.update()

        drawShapes()
        drawTexts()
        handleInput()
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
    }

    private fun update(dt: Float) {
        if (phase == Phase.PLAY) {
            if (freezeTimer > 0f) {
                freezeTimer -= dt
            } else {
                timeLeft -= dt
            }
            if (timeLeft <= 0f) {
                timeLeft = 0f
                onFail(timeout = true)
            }
        }
        wheel.update(dt)
        if (phase == Phase.PLAY && wheel.phase == WheelPhase.RESOLVED) evaluate()
        if (phase != Phase.PLAY) {
            resultTimer -= dt
            if (resultTimer <= 0f) newRound()
        }
        particles.update(dt)
        val (sx, sy) = shake.update(dt)
        shakeX = sx
        shakeY = sy
    }

    private fun evaluate() {
        val sel = wheel.selectedIndex()
        if (sel == correctIndex()) onCorrect() else onFail(timeout = false)
    }

    private fun onCorrect() {
        phase = Phase.CORRECT
        resultTimer = 1.0f
        streak++
        val speedBonus = if (timeLeft / config.timeSeconds > 0.5f) 2 else 1
        lastReward = config.coinReward * speedBonus
        game.state.addCoins(lastReward)
        game.state.recordScore(game.state.progressScore(section, level))
        resultText = I18n.t("correct") + "  +$lastReward"
        particles.burst(CX, CY, 48, Palette.neonGreen, game.rng)
        shake.add(9f)
        game.services.haptics.heavy()
        if (streak > 0 && streak % 3 == 0) {
            section++
            level = 1
            resultText = I18n.t("section_cleared")
        } else {
            level++
        }
    }

    private fun onFail(timeout: Boolean) {
        if (shieldActive) {
            shieldActive = false
            phase = Phase.SHIELD
            resultTimer = 1.3f
            resultText = I18n.t("shield_saved")
            game.services.haptics.vibrate(60)
            return
        }
        phase = if (timeout) Phase.TIMEOUT else Phase.WRONG
        resultTimer = 1.5f
        resultText = if (timeout) I18n.t("time_up") else I18n.t("wrong")
        streak = 0
        if (level > 1) level-- else level = 1
        particles.burst(CX, CY, 42, Palette.neonMagenta, game.rng)
        shake.add(7f)
        game.services.haptics.vibrate(130)
    }

    private fun handleInput() {
        if (!Gdx.input.justTouched()) return
        touch.set(Gdx.input.x.toFloat(), Gdx.input.y.toFloat())
        viewport.unproject(touch)

        if (phase != Phase.PLAY) {
            newRound()
            return
        }

        val rects = boostRects()
        when {
            contains(rects[0], touch.x, touch.y) -> buyHighlight()
            contains(rects[1], touch.x, touch.y) -> buyShield()
            contains(rects[2], touch.x, touch.y) -> buyFreeze()
            wheel.phase == WheelPhase.SPINNING -> {
                wheel.brake()
                game.services.haptics.light()
            }
        }
    }

    private fun buyHighlight() {
        if (phase != Phase.PLAY || highlightActive) return
        if (game.state.trySpend(COST_HIGHLIGHT)) {
            highlightActive = true
            game.services.haptics.light()
        }
    }

    private fun buyShield() {
        if (phase != Phase.PLAY || shieldActive) return
        if (game.state.trySpend(COST_SHIELD)) {
            shieldActive = true
            game.services.haptics.light()
        }
    }

    private fun buyFreeze() {
        if (phase != Phase.PLAY) return
        if (game.state.trySpend(COST_FREEZE)) {
            freezeTimer = 3f
            game.services.haptics.light()
        }
    }

    private fun boostRects(): List<FloatArray> {
        val w = 190f
        val h = 110f
        val y = 70f
        val gap = (VW - 3 * w) / 4f
        return (0 until 3).map { floatArrayOf(gap + it * (w + gap), y, w, h) }
    }

    // ---------------- RENDERING ----------------

    private fun drawShapes() {
        shapes.projectionMatrix = viewport.camera.combined
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.setColor(Palette.bgGlow)
        shapes.circle(CX, CY, WHEEL_R + 80f, 64)
        drawWheelSegments()
        drawPointer()
        drawTimerBar()
        drawBoostButtons()
        drawParticles()
        shapes.end()
    }

    private fun drawWheelSegments() {
        val seg = wheel.segmentAngle
        val rad = MathUtils.degreesToRadians

        shapes.setColor(Palette.bgGlow)
        shapes.circle(CX, CY, WHEEL_R + 14f, 64)

        for (i in 0 until wheel.segmentCount) {
            val a0 = (wheel.rotationDeg + i * seg) * rad
            val a1 = a0 + seg * rad
            val c = Palette.wheelColors[i % Palette.wheelColors.size]
            val col =
                if (highlightActive && i == correctIndex()) Color.WHITE
                else c
            shapes.setColor(col)
            shapes.triangle(
                CX, CY,
                CX + cos(a0) * WHEEL_R, CY + sin(a0) * WHEEL_R,
                CX + cos(a1) * WHEEL_R, CY + sin(a1) * WHEEL_R
            )
        }

        // hub ring (the "button")
        shapes.setColor(Palette.neonCyan)
        shapes.circle(CX, CY, HUB_R, 64)
        shapes.setColor(Color(0.05f, 0.03f, 0.08f, 1f))
        shapes.circle(CX, CY, HUB_R - 8f, 64)
    }

    private fun drawPointer() {
        val py = CY + WHEEL_R + 4f
        shapes.setColor(Palette.neonYellow)
        shapes.triangle(CX, py + 30f, CX - 22f, py, CX + 22f, py)
    }

    private fun drawTimerBar() {
        shapes.setColor(1f, 1f, 1f, 0.15f)
        shapes.rect(60f, 1040f, 600f, 18f)
        val frac = (timeLeft / config.timeSeconds).coerceIn(0f, 1f)
        shapes.setColor(if (frac > 0.4f) Palette.neonGreen else Palette.neonRed)
        shapes.rect(60f, 1040f, 600f * frac, 18f)
    }

    private fun drawBoostButtons() {
        val rects = boostRects()
        val colors = listOf(Palette.neonYellow, Palette.neonCyan, Palette.neonPurple)
        for (i in rects.indices) {
            val r = rects[i]
            shapes.setColor(colors[i])
            shapes.rect(r[0], r[1], r[2], r[3])
            shapes.setColor(Palette.bg)
            shapes.rect(r[0] + 5f, r[1] + 5f, r[2] - 10f, r[3] - 10f)
        }
    }

    private fun drawParticles() {
        for (p in particles.particles) {
            val a = (p.life / p.maxLife).coerceIn(0f, 1f)
            shapes.setColor(p.color.r, p.color.g, p.color.b, a)
            shapes.circle(p.x, p.y, 5f, 8)
        }
    }

    private fun drawTexts() {
        batch.projectionMatrix = viewport.camera.combined
        batch.begin()

        // segment labels
        val seg = wheel.segmentAngle
        val rad = MathUtils.degreesToRadians
        val labelR = WHEEL_R * 0.68f
        font.data.setScale(2.1f)
        for (i in 0 until wheel.segmentCount) {
            val a = (wheel.rotationDeg + i * seg + seg / 2f) * rad
            val x = CX + cos(a) * labelR
            val y = CY + sin(a) * labelR
            val col = if (highlightActive && i == correctIndex()) Color.BLACK else Color.WHITE
            Ui.drawTextCentered(batch, font, equation.options[i].toString(), x, y, col)
        }

        // equation
        font.data.setScale(4.2f)
        Ui.drawTextCenteredGlow(batch, font, equation.text, CX, 1100f, Palette.text, Palette.neonCyan, 4f)

        // HUD
        font.data.setScale(2.2f)
        Ui.drawTextCentered(batch, font, "S$section  \u00b7  L$level", 120f, 1230f, Palette.text)
        val coinsText = "\u25cf ${game.state.coins}"
        Ui.drawTextCentered(batch, font, coinsText, VW - 120f, 1230f, Palette.neonYellow)
        val timeText = "%.1f".format(timeLeft)
        Ui.drawTextCentered(batch, font, timeText, CX, 990f, Palette.text)

        if (streak >= 2) {
            font.data.setScale(2f)
            Ui.drawTextCentered(batch, font, "${I18n.t("streak")} x$streak", CX, 1200f, Palette.neonMagenta)
        }

        if (phase == Phase.PLAY && wheel.phase == WheelPhase.SPINNING) {
            font.data.setScale(2f)
            Ui.drawTextCentered(batch, font, I18n.t("tap_to_stop"), CX, CY + HUB_R * 0.0f, Palette.dim)
        }

        // result banner
        if (phase != Phase.PLAY) {
            val color = when (phase) {
                Phase.CORRECT -> Palette.neonGreen
                Phase.SHIELD -> Palette.neonCyan
                else -> Palette.neonMagenta
            }
            font.data.setScale(4.4f)
            Ui.drawTextCenteredGlow(batch, font, resultText, CX, CY, color, color, 4f)
        }

        // boost labels
        val rects = boostRects()
        val boostNames = listOf(
            "${I18n.t("highlight")} $COST_HIGHLIGHT",
            "${I18n.t("shield")} $COST_SHIELD",
            "${I18n.t("freeze")} $COST_FREEZE"
        )
        font.data.setScale(1.9f)
        for (i in rects.indices) {
            val r = rects[i]
            val col = when (i) {
                0 -> if (highlightActive) Palette.dim else Color.WHITE
                1 -> if (shieldActive) Palette.dim else Color.WHITE
                else -> Color.WHITE
            }
            Ui.drawTextCentered(batch, font, boostNames[i], r[0] + r[2] / 2f, r[1] + r[3] / 2f, col)
        }

        batch.end()
    }

    private fun contains(r: FloatArray, x: Float, y: Float) =
        x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]
}
