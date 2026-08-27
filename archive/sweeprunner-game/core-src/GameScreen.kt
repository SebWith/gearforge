package com.sweeprunner.game

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.FitViewport
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class GameScreen(private val game: SweepRunnerGame) : ScreenAdapter() {

    private enum class Phase { PLAY, LEVEL_CLEAR, GAME_OVER }
    private enum class Kind { BLOCK, BALL, TRIANGLE }

    private class Entity(
        var kind: Kind,
        var lane: Float,
        var depth: Float,
        var size: Float,
        var color: Color
    )

    private val VW = 720f
    private val VH = 1280f
    private val CX = 360f
    private val HORIZON = 560f
    private val PLAYER_Y = 230f
    private val ROAD_HALF_BOTTOM = 360f
    private val ROAD_HALF_TOP = 10f

    private val viewport = FitViewport(VW, VH)
    private val batch = SpriteBatch()
    private val shapes = ShapeRenderer()
    private val font = BitmapFont()
    private val touch = Vector2()

    private val obstacles = ArrayList<Entity>()
    private val coins = ArrayList<Entity>()
    private val particles = ParticleSystem()
    private val shake = ScreenShake()

    private var phase = Phase.PLAY
    private var level = 1
    private var elapsed = 0f
    private var score = 0
    private var lives = 3
    private var playerLane = 0f
    private var speed = 0.12f
    private var spawnTimer = 0f
    private var invulnTimer = 0f
    private var shieldTimer = 0f
    private var magnetTimer = 0f
    private var shieldCd = 0f
    private var magnetCd = 0f
    private var megasweepCd = 0f
    private var combo = 0
    private var comboTimer = 0f
    private var bob = 0f
    private var lean = 0f
    private var time = 0f

    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0f
    private var lastX = 0f

    private val btnY = 130f
    private val btnW = 190f
    private val btnH = 110f
    private val magnetBtn = Rectangle(30f, btnY, btnW, btnH)
    private val shieldBtn = Rectangle(265f, btnY, btnW, btnH)
    private val sweepBtn = Rectangle(500f, btnY, btnW, btnH)

    private val nextBtn = Rectangle(90f, 500f, 240f, 120f)
    private val menuBtn = Rectangle(390f, 500f, 240f, 120f)

    fun start(level: Int) {
        this.level = level.coerceIn(1, 10)
        phase = Phase.PLAY
        elapsed = 0f
        score = 0
        lives = 3
        playerLane = 0f
        speed = 0.11f + this.level * 0.012f
        spawnTimer = 0.5f
        invulnTimer = 0f
        shieldTimer = 0f
        magnetTimer = 0f
        shieldCd = 0f
        magnetCd = 0f
        megasweepCd = 0f
        combo = 0
        comboTimer = 0f
        lean = 0f
        obstacles.clear()
        coins.clear()
        particles.clear()
    }

    override fun show() {
        Gdx.input.inputProcessor = object : InputAdapter() {
            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                if (phase != Phase.PLAY) return false
                val w = worldTouch(screenX, screenY)
                if (magnetBtn.contains(w.x, w.y)) { activateMagnet(); return true }
                if (shieldBtn.contains(w.x, w.y)) { activateShield(); return true }
                if (sweepBtn.contains(w.x, w.y)) { activateMegasweep(); return true }
                dragging = true
                downX = w.x
                downY = w.y
                downTime = time
                lastX = w.x
                return true
            }

            override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
                if (!dragging) return false
                val w = worldTouch(screenX, screenY)
                val dx = w.x - lastX
                if (level >= 3) {
                    playerLane = clamp(playerLane + dx * 0.0035f, -0.88f, 0.88f)
                    lean = clamp(dx * 0.012f, -0.3f, 0.3f)
                }
                lastX = w.x
                return true
            }

            override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                if (!dragging) return false
                dragging = false
                val w = worldTouch(screenX, screenY)
                val dur = time - downTime
                val dist = Vector2(w.x - downX, w.y - downY).len()
                if (dur < 0.28f && dist > 45f) {
                    removeAt(w.x, w.y)
                } else if (dist < 30f) {
                    removeAt(downX, downY)
                }
                lean = 0f
                return true
            }
        }
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
    }

    override fun render(delta: Float) {
        time += delta
        val (shakeX, shakeY) = shake.update(delta)

        ScreenUtils.clear(Palette.skyTop)
        viewport.apply()
        viewport.camera.position.set(VW / 2f + shakeX, VH / 2f + shakeY, 0f)
        viewport.camera.update()
        batch.projectionMatrix = viewport.camera.combined
        shapes.projectionMatrix = viewport.camera.combined

        if (phase == Phase.PLAY) {
            update(delta)
            handleKeyboard(delta)
        }

        drawWorld()
        drawHud()

        if (phase != Phase.PLAY) {
            drawOverlay()
            if (Gdx.input.justTouched()) {
                val w = worldTouch(Gdx.input.x, Gdx.input.y)
                if (nextBtn.contains(w.x, w.y)) {
                    if (phase == Phase.LEVEL_CLEAR) start(min(level + 1, 10)) else start(level)
                } else if (menuBtn.contains(w.x, w.y)) {
                    game.goToMenu()
                }
            }
        }
    }

    private fun update(delta: Float) {
        elapsed += delta
        bob += delta * 10f
        lean += (0f - lean) * 6f * delta
        if (!dragging) {
            playerLane = clamp(playerLane + (0f - playerLane) * 1.7f * delta, -0.88f, 0.88f)
        }

        invulnTimer = max(0f, invulnTimer - delta)
        shieldTimer = max(0f, shieldTimer - delta)
        magnetTimer = max(0f, magnetTimer - delta)
        shieldCd = max(0f, shieldCd - delta)
        magnetCd = max(0f, magnetCd - delta)
        megasweepCd = max(0f, megasweepCd - delta)
        comboTimer -= delta
        if (comboTimer <= 0f) combo = 0

        spawnTimer -= delta
        if (spawnTimer <= 0f) {
            spawnTimer = max(0.55f, 1.15f - level * 0.05f) * (0.8f + game.rng.nextFloat() * 0.4f)
            val kind = Kind.values()[game.rng.nextInt(Kind.values().size)]
            obstacles.add(
                Entity(
                    kind,
                    game.rng.nextFloat() * 2f - 1f,
                    1f,
                    0.85f + game.rng.nextFloat() * 0.7f,
                    Palette.obstacleColors[game.rng.nextInt(Palette.obstacleColors.size)]
                )
            )
            if (game.rng.nextFloat() < 0.45f) {
                coins.add(Entity(Kind.BALL, game.rng.nextFloat() * 2f - 1f, 1f, 0.35f, Palette.coin))
            }
        }

        val oi = obstacles.iterator()
        while (oi.hasNext()) {
            val o = oi.next()
            o.depth -= speed * delta
            if (o.depth <= 0f) {
                if (abs(o.lane - playerLane) < 0.26f) {
                    if (shieldTimer > 0f || invulnTimer > 0f) {
                        particles.burst(xOf(o), yOf(o), 8, o.color, game.rng)
                    } else {
                        val dir = if (playerLane >= o.lane) 1f else -1f
                        playerLane = clamp(playerLane + dir * 0.34f, -1.1f, 1.1f)
                        if (abs(playerLane) > 1.0f) {
                            lives--
                            playerLane = 0f
                            invulnTimer = 1.4f
                            shake.add(14f)
                            if (game.state.hapticsOn) game.services.haptics.heavy()
                            if (lives <= 0) {
                                phase = Phase.GAME_OVER
                                game.state.recordScore(score)
                            }
                        } else {
                            shake.add(5f)
                            if (game.state.hapticsOn) game.services.haptics.light()
                        }
                    }
                }
                oi.remove()
            }
        }

        val ci = coins.iterator()
        while (ci.hasNext()) {
            val c = ci.next()
            c.depth -= speed * delta
            if (c.depth <= 0f) {
                game.state.addCoins(1)
                particles.burst(xOf(c), yOf(c), 6, Palette.coin, game.rng)
                ci.remove()
            }
        }

        particles.update(delta)

        if (elapsed >= levelTime()) {
            phase = Phase.LEVEL_CLEAR
            game.state.recordScore(score)
            game.state.unlockLevel(level + 1)
            game.state.setStars(level, lives)
        }
    }

    private fun handleKeyboard(delta: Float) {
        if (Gdx.input.isKeyPressed(Input.Keys.A) || Gdx.input.isKeyPressed(Input.Keys.LEFT)) {
            playerLane = clamp(playerLane - 2.2f * delta, -0.88f, 0.88f)
            lean = clamp(lean - 6f * delta, -0.3f, 0.3f)
        }
        if (Gdx.input.isKeyPressed(Input.Keys.D) || Gdx.input.isKeyPressed(Input.Keys.RIGHT)) {
            playerLane = clamp(playerLane + 2.2f * delta, -0.88f, 0.88f)
            lean = clamp(lean + 6f * delta, -0.3f, 0.3f)
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_1)) activateMagnet()
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_2)) activateShield()
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_3)) activateMegasweep()
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) || Gdx.input.isKeyJustPressed(Input.Keys.BACK)) {
            game.goToMenu()
        }
    }

    private fun drawWorld() {
        val blX = CX - ROAD_HALF_BOTTOM
        val brX = CX + ROAD_HALF_BOTTOM
        val tlX = CX - ROAD_HALF_TOP
        val trX = CX + ROAD_HALF_TOP

        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = Palette.skyTop
        shapes.rect(0f, 0f, VW, VH)
        drawCloud(120f, 980f, 84f)
        drawCloud(580f, 1060f, 66f)
        drawCloud(360f, 820f, 54f)
        drawCloud(80f, 120f, 46f)
        drawCloud(650f, 90f, 44f)

        // road (trapezoid)
        shapes.color = Palette.roadBottom
        shapes.triangle(blX, 0f, brX, 0f, tlX, HORIZON)
        shapes.triangle(brX, 0f, trX, HORIZON, tlX, HORIZON)
        shapes.end()

        // lane lines
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.color = Palette.roadEdge
        shapes.line(blX, 0f, tlX, HORIZON)
        shapes.line(brX, 0f, trX, HORIZON)
        shapes.color = Palette.laneLine
        shapes.line(CX, 0f, CX, HORIZON)
        shapes.line(CX - ROAD_HALF_BOTTOM / 2f, 0f, CX - ROAD_HALF_TOP / 2f, HORIZON)
        shapes.line(CX + ROAD_HALF_BOTTOM / 2f, 0f, CX + ROAD_HALF_TOP / 2f, HORIZON)
        shapes.end()

        shapes.begin(ShapeRenderer.ShapeType.Filled)
        // obstacles
        for (o in obstacles) {
            val x = xOf(o)
            val y = yOf(o)
            val s = scaleOf(o.depth) * 46f * o.size
            shapes.color = o.color
            when (o.kind) {
                Kind.BLOCK -> shapes.rect(x - s / 2f, y - s / 2f, s, s)
                Kind.BALL -> shapes.circle(x, y, s / 2f)
                Kind.TRIANGLE -> shapes.triangle(x - s / 2f, y - s / 2f, x + s / 2f, y - s / 2f, x, y + s / 2f)
            }
        }
        // coins
        for (c in coins) {
            val x = xOf(c)
            val y = yOf(c)
            val s = scaleOf(c.depth) * 30f
            shapes.color = Palette.coin
            shapes.circle(x, y, s)
            shapes.color = Color(1f, 0.9f, 0.45f, 1f)
            shapes.circle(x, y, s * 0.55f)
        }
        // character
        drawCharacter()
        // particles
        for (p in particles.particles) {
            shapes.color = p.color
            shapes.circle(p.x, p.y, 5f)
        }
        // hearts
        for (i in 0 until 3) {
            val hx = VW - 50f - i * 55f
            val hy = VH - 55f
            shapes.color = if (i < lives) Palette.heart else Color(0.6f, 0.6f, 0.65f, 0.5f)
            drawHeart(hx, hy, 16f)
        }
        // ability buttons
        drawAbilityButton(magnetBtn, magnetCd, 8f)
        drawAbilityButton(shieldBtn, shieldCd, 6f)
        drawAbilityButton(sweepBtn, megasweepCd, 10f)
        shapes.end()
    }

    private fun drawHud() {
        batch.begin()
        font.data.setScale(1.4f)
        font.setColor(Palette.text)
        font.draw(batch, "${I18n.t("level")} $level", 30f, VH - 30f)
        font.draw(batch, "${I18n.t("score")} $score", 30f, VH - 78f)
        font.draw(batch, "${I18n.t("coins")} ${game.state.coins}", 30f, VH - 126f)
        Ui.drawTextCentered(batch, font, I18n.t("magnet"), magnetBtn.x + magnetBtn.width / 2f, magnetBtn.y + magnetBtn.height / 2f, Palette.white)
        Ui.drawTextCentered(batch, font, I18n.t("shield"), shieldBtn.x + shieldBtn.width / 2f, shieldBtn.y + shieldBtn.height / 2f, Palette.white)
        Ui.drawTextCentered(batch, font, I18n.t("megasweep"), sweepBtn.x + sweepBtn.width / 2f, sweepBtn.y + sweepBtn.height / 2f, Palette.white)
        if (level <= 2) {
            font.data.setScale(1.1f)
            Ui.drawTextCentered(batch, font, I18n.t("swipe_hint"), VW / 2f, HORIZON + 60f, Palette.white)
        }
        font.data.setScale(1f)
        font.setColor(Color.WHITE)
        batch.end()
    }

    private fun drawOverlay() {
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = Palette.dim
        shapes.rect(0f, 0f, VW, VH)
        shapes.color = Palette.button
        shapes.rect(nextBtn.x, nextBtn.y, nextBtn.width, nextBtn.height)
        shapes.rect(menuBtn.x, menuBtn.y, menuBtn.width, menuBtn.height)
        shapes.end()

        batch.begin()
        val title = if (phase == Phase.LEVEL_CLEAR) I18n.t("level_clear") else I18n.t("game_over")
        font.data.setScale(2.4f)
        Ui.drawTextCentered(batch, font, title, VW / 2f, VH * 0.72f, Palette.white)
        font.data.setScale(1.4f)
        Ui.drawTextCentered(batch, font, "${I18n.t("score")}: $score", VW / 2f, VH * 0.64f, Palette.white)
        Ui.drawTextCentered(batch, font, "${I18n.t("coins")}: ${game.state.coins}", VW / 2f, VH * 0.59f, Palette.white)
        val btnLabel = if (phase == Phase.LEVEL_CLEAR) I18n.t("next_level") else I18n.t("play_again")
        Ui.drawTextCentered(batch, font, btnLabel, nextBtn.x + nextBtn.width / 2f, nextBtn.y + nextBtn.height / 2f, Palette.white)
        Ui.drawTextCentered(batch, font, I18n.t("menu"), menuBtn.x + menuBtn.width / 2f, menuBtn.y + menuBtn.height / 2f, Palette.white)
        font.data.setScale(1f)
        batch.end()
    }

    private fun drawCharacter() {
        val x = CX + playerLane * ROAD_HALF_BOTTOM
        val y = PLAYER_Y + sin(bob) * 7f
        val r = 46f
        val leanShift = lean * 22f

        // legs
        val legSwing = sin(bob) * 8f
        shapes.color = Palette.bodyShade
        shapes.circle(x - r * 0.45f, y - r * 1.35f - legSwing, r * 0.32f)
        shapes.circle(x + r * 0.45f, y - r * 1.35f + legSwing, r * 0.32f)
        // body (seen from behind)
        shapes.color = Palette.body
        shapes.circle(x - leanShift, y, r)
        // backpack
        shapes.color = Palette.backpack
        shapes.rect(x - leanShift - r * 0.45f, y - r * 0.5f, r * 0.9f, r * 1.1f)
        // head
        shapes.color = Palette.skin
        shapes.circle(x - leanShift * 0.6f, y + r * 1.15f, r * 0.55f)
    }

    private fun drawAbilityButton(r: Rectangle, cd: Float, cdMax: Float) {
        val ready = cd <= 0f
        shapes.color = if (ready) Palette.button else Palette.buttonDim
        shapes.rect(r.x, r.y, r.width, r.height)
        if (cd > 0f) {
            shapes.color = Color(0f, 0f, 0f, 0.35f)
            shapes.rect(r.x, r.y, r.width, r.height * (cd / cdMax))
        }
    }

    private fun drawHeart(x: Float, y: Float, s: Float) {
        shapes.circle(x - s * 0.35f, y + s * 0.3f, s * 0.4f)
        shapes.circle(x + s * 0.35f, y + s * 0.3f, s * 0.4f)
        shapes.triangle(x - s * 0.7f, y + s * 0.2f, x + s * 0.7f, y + s * 0.2f, x, y - s * 0.7f)
    }

    private fun drawCloud(x: Float, y: Float, s: Float) {
        shapes.color = Palette.cloud
        shapes.circle(x, y, s)
        shapes.circle(x - s * 0.8f, y - s * 0.2f, s * 0.7f)
        shapes.circle(x + s * 0.8f, y - s * 0.2f, s * 0.7f)
        shapes.circle(x, y + s * 0.35f, s * 0.75f)
    }

    private fun removeAt(x: Float, y: Float) {
        var best: Entity? = null
        var bestD = 140f
        for (o in obstacles) {
            val d = Vector2(x - xOf(o), y - yOf(o)).len()
            if (d < bestD) {
                bestD = d
                best = o
            }
        }
        if (best != null) {
            obstacles.remove(best)
            combo = min(combo + 1, 5)
            comboTimer = 1.6f
            score += 10 * combo
            particles.burst(xOf(best), yOf(best), 10, best.color, game.rng)
            if (game.state.hapticsOn) game.services.haptics.light()
        }
    }

    private fun activateShield() {
        if (shieldCd > 0f) return
        shieldCd = 6f
        shieldTimer = 4f
    }

    private fun activateMagnet() {
        if (magnetCd > 0f) return
        magnetCd = 8f
        magnetTimer = 4f
        for (c in coins) {
            game.state.addCoins(1)
            particles.burst(xOf(c), yOf(c), 5, Palette.coin, game.rng)
        }
        coins.clear()
    }

    private fun activateMegasweep() {
        if (megasweepCd > 0f) return
        megasweepCd = 10f
        val it = obstacles.iterator()
        while (it.hasNext()) {
            val o = it.next()
            if (o.depth < 0.7f) {
                score += 5
                particles.burst(xOf(o), yOf(o), 6, o.color, game.rng)
                it.remove()
            }
        }
    }

    private fun levelTime() = 20f + level * 2f

    private fun xOf(o: Entity) = CX + o.lane * roadHalf(o.depth)
    private fun yOf(o: Entity) = PLAYER_Y + (HORIZON - PLAYER_Y) * o.depth
    private fun roadHalf(d: Float) = ROAD_HALF_BOTTOM + (ROAD_HALF_TOP - ROAD_HALF_BOTTOM) * d
    private fun scaleOf(d: Float) = 1f - 0.88f * d
    private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
    private fun worldTouch(screenX: Int, screenY: Int): Vector2 =
        viewport.unproject(touch.set(screenX.toFloat(), screenY.toFloat()))
}
