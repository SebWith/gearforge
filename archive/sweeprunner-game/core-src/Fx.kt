package com.sweeprunner.game

import com.badlogic.gdx.graphics.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class Particle(
    var x: Float, var y: Float,
    var vx: Float, var vy: Float,
    var life: Float, val maxLife: Float,
    val color: Color
)

class ParticleSystem {
    val particles = ArrayList<Particle>()

    fun burst(x: Float, y: Float, count: Int, color: Color, rng: Random) {
        repeat(count) {
            val ang = rng.nextFloat() * 6.2832f
            val speed = 120f + rng.nextFloat() * 420f
            particles.add(Particle(x, y, cos(ang) * speed, sin(ang) * speed, 0.7f, 0.7f, color))
        }
    }

    fun update(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) { it.remove(); continue }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy -= 520f * dt
        }
    }

    fun clear() = particles.clear()
}

class ScreenShake {
    private var magnitude = 0f

    fun add(amount: Float) { magnitude = maxOf(magnitude, amount) }

    fun update(dt: Float): Pair<Float, Float> {
        magnitude *= 0.86f
        if (magnitude < 0.05f) magnitude = 0f
        val ox = (Random.nextFloat() * 2f - 1f) * magnitude
        val oy = (Random.nextFloat() * 2f - 1f) * magnitude
        return ox to oy
    }
}
