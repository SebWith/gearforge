package com.gearforge.core

import kotlin.math.PI

/**
 * Motion of one body in an assembly, as the viewport's playback needs it.
 *
 * [spinSpeed] is the body's own rotation about its own axis (rad/s) and [orbitSpeed] the rotation
 * of its *placed centre* about the assembly origin — the two are independent, and a planetary
 * carrier is exactly the case where they differ: a planet spins about its own axis while its centre
 * revolves around the sun.
 *
 * **Phases are not part of this model.** The geometry already bakes the meshing: the planetary
 * builder rotates each planet by `φᵢ = −θᵢ·Zs/Zp + π/Zp` for its placement angle, and the rack is
 * shifted so a tooth gap sits under the pinion. Those orientations *are* the initial phase, so the
 * only thing an animation has to get right is the rates — and rigid-body kinematics preserves an
 * alignment that holds at t = 0.
 */
data class BodyMotion(
    val spinSpeed: Double,
    val orbitSpeed: Double = 0.0,
    /** Linear travel along +X in mm/s, for a rack. */
    val slideSpeed: Double = 0.0,
    /** True when the body turns about X rather than Z — the worm, whose screw axis runs along X. */
    val aboutX: Boolean = false
)

/**
 * Kinematics of the assemblies [GearBuilder.assembly] produces: how fast each body turns when the
 * driving member turns at [baseSpeed].
 *
 * Every family is handled here rather than in the renderer, because the *ratios* are gear
 * mathematics and can be proved — the belt's two pulleys must turn the same way, the rack must
 * travel exactly `ω·r`, and a planetary's carrier speed follows from the ring being held. Those are
 * equations, not drawing decisions, so they live next to [GearCalculator] with tests, and the
 * viewport only applies the numbers.
 */
object MeshKinematics {

    /**
     * The base playback rate: one revolution per second.
     *
     * The base is the **fastest** rate the speed control offers, and the only rate the app ever
     * selects on its own. The slower steps in the viewport's ladder are fractions of this number
     * (0.5× is one revolution every two seconds, 0.25× every four, 0.125× every eight), so the
     * labels are measured against the rate a user sees by default — a chip that says 0.5× means half
     * of what is on screen, not half of an invisible reference.
     *
     * The four rates are the ones the app already offered. When the base was the *slowest* step
     * (one revolution every four seconds, multiplied by 0.5×…4×) the same four rates were reachable,
     * and this number was a quarter of the way up its own ladder: the app's own default was a
     * quarter-speed, and every chip's label was a fraction of a rate that was never displayed. The
     * fast end is the honest base, because a mesh is judged by watching teeth pass — the old
     * twelve-second base had already been rejected for that reason, as fewer than two teeth went past
     * per second and the mesh read as *nothing is happening*.
     */
    const val DEFAULT_SPEED_RAD_PER_S: Double = 2.0 * PI / 1.0

    /**
     * Motions for [p]'s bodies, in the same order as `GearBuilder.assembly(p).meshes`.
     *
     * The order is part of the contract, so [MeshKinematicsTest] checks it against the real
     * assembly for every gear type instead of trusting a comment.
     */
    fun motions(p: GearParams, baseSpeed: Double): List<BodyMotion> {
        val q = p.coerced()
        return when (q.gearType) {
            GearType.RACK -> {
                val r = GearCalculator.pitchRadius(q.module, q.pinionTeeth)
                // The pinion turns and the rack travels at the pitch line's speed, ω·r. The rack
                // does not rotate: a rack is a gear unrolled, and translating it is what keeps the
                // teeth in the same relationship they have in the round.
                listOf(
                    BodyMotion(spinSpeed = 0.0, slideSpeed = baseSpeed * r),
                    BodyMotion(spinSpeed = baseSpeed)
                )
            }

            GearType.PLANETARY -> {
                val sunTeeth = maxOf(5, q.teeth)
                val planetTeeth = maxOf(8, q.planetTeeth)
                // Zr = Zs + 2·Zp is the meshing constraint the builder applies, taken from the one
                // definition of it so a ring tooth count that changed there cannot be assumed here.
                val ringTeeth = GearCalculator.planetaryRingTeeth(q.teeth, q.planetTeeth)
                val planetCount = q.planetCount.coerceIn(2, 6)
                val carrier = carrierSpeed(baseSpeed, sunTeeth, ringTeeth)
                // Ring held, sun driving: the planets are carried around at the carrier speed and
                // spin about their own axes at (1 − Zr/Zp) times it. Both mesh equations then hold
                // at once — that is what the test asserts, and it is why the sun and the ring can
                // be trusted to stay in step while the train turns.
                val planetSpin = carrier * (1.0 - ringTeeth.toDouble() / planetTeeth)
                listOf(BodyMotion(spinSpeed = baseSpeed), BodyMotion(spinSpeed = 0.0)) +
                    List(planetCount) { BodyMotion(spinSpeed = planetSpin, orbitSpeed = carrier) }
            }

            GearType.WORM_PAIR -> {
                // A worm drive's ratio is wheel teeth per *start*, not per modelled tooth: the worm
                // is drawn as a helical spline with several teeth per start.
                val starts = maxOf(1, q.wormStarts)
                val wheelTeeth = maxOf(8, q.wheelTeeth)
                listOf(
                    BodyMotion(spinSpeed = baseSpeed, aboutX = true),
                    BodyMotion(spinSpeed = baseSpeed * starts / wheelTeeth.toDouble())
                )
            }

            GearType.BELT -> {
                val t = q.toBeltTransmission()
                val driver = maxOf(1, t.driverTeeth)
                val driven = maxOf(1, t.drivenTeeth)
                // An open belt turns both pulleys the *same* way, at the inverse teeth ratio. This
                // is the one family where the two rotating bodies do not reverse — the belt is not
                // a gear pair, and animating it like one would teach the wrong lesson.
                listOf(
                    BodyMotion(spinSpeed = 0.0),
                    BodyMotion(spinSpeed = baseSpeed),
                    BodyMotion(spinSpeed = baseSpeed * driver / driven.toDouble())
                )
            }

            else -> {
                // One body — a gear and, when it has one, its hub. The hub is part of the same
                // solid, so the two must turn together or the model would tear itself apart.
                val bodies = 1 + if (HubBuilder.hasHub(q)) 1 else 0
                List(bodies) { BodyMotion(spinSpeed = baseSpeed) }
            }
        }
    }

    /**
     * Speed of the carrier when the sun drives and the ring is held: `ω_c = ω_s·Zs/(Zs+Zr)`.
     *
     * Public because it is the number a reader of the planetary panel wants, and because the test
     * uses it to check the planet speeds against both meshes.
     */
    fun carrierSpeed(sunSpeed: Double, sunTeeth: Int, ringTeeth: Int): Double =
        sunSpeed * sunTeeth / (sunTeeth + ringTeeth).toDouble()

    /** A planet's own spin when the sun drives and the ring is held. */
    fun planetSpinSpeed(carrier: Double, planetTeeth: Int, ringTeeth: Int): Double =
        carrier * (1.0 - ringTeeth.toDouble() / planetTeeth)
}
