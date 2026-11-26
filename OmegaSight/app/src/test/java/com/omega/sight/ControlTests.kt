package com.omega.sight

import com.omega.sight.core.*
import java.util.ArrayDeque
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The aim loop against a made-up game: the picture shifts by `rel * finger / screenPerFrame` frame pixels, some frames after the finger
 * moved, and the measured shift is noisy. The controller knows none of these numbers.
 */
object ControlTests {
    private class Sim(val errs: List<Float>, val ctl: AimController, val restarts: Int)

    private const val SPF = 12f
    private const val DT = 1f / 30f

    /** the controller does not chase errors smaller than this many frame pixels (a tenth of the target's height, at most 2) */
    private const val DEAD = 2.0f

    private fun simulate(
        relX: Float,
        relY: Float,
        lag: Int,
        start: Vec2,
        v: Vec2 = Vec2.ZERO,
        frames: Int = 80,
        tuning: Tuning = Tuning(),
        egoNoise: Float = 0.08f,
        seed: Long = 7,
        preset: ((AimController) -> Unit)? = null,
    ): Sim {
        val ctl = AimController()
        ctl.screenPerFrame = SPF
        ctl.frameH = 90f
        ctl.zoneHalfW = 300f
        ctl.zoneHalfH = 250f
        preset?.invoke(ctl)
        val picker = TargetPicker()
        val cross = Vec2(96f, 45f)
        val rnd = Random(seed)
        var tx = cross.x + start.x
        var ty = cross.y + start.y
        val q = ArrayDeque<Vec2>()
        var last = Vec2.ZERO
        val errs = ArrayList<Float>()
        var restarts = 0
        repeat(frames) {
            val ego = Ego(
                last.x + egoNoise * rnd.nextGaussian().toFloat(), last.y + egoNoise * rnd.nextGaussian().toFloat(),
                0f, 96f, 45f, 0.1f, 1f, true,
            )
            ctl.observe(ego)
            // a "motion" track is aimed at its middle, so the box centre is the aim point
            val trk = Track(1, Box.ofCenter(tx, ty, 10f, 24f), Kind.MOTION, 0.9f)
            trk.vx = v.x
            trk.vy = v.y
            trk.hits = 5
            val pick = picker.pick(listOf(trk), cross, 90f, tuning, ctl.latency(DT))
            val cmd = ctl.step(pick, cross, tuning, DT)
            if (cmd.restart) restarts++
            q.addLast(Vec2(cmd.dx, cmd.dy))
            val act = if (q.size > lag) q.removeFirst() else Vec2.ZERO
            val sx = relX * act.x / SPF
            val sy = relY * act.y / SPF
            last = Vec2(sx, sy)
            tx += sx + v.x * DT
            ty += sy + v.y * DT
            errs.add(hypot(tx - cross.x, ty - cross.y))
        }
        return Sim(errs, ctl, restarts)
    }

    private fun tail(s: Sim, n: Int = 10) = s.errs.takeLast(n).average().toFloat()

    fun run() {
        // the usual game: the picture follows the finger the other way round
        run {
            val s = simulate(-1.3f, -0.8f, 2, Vec2(25f, 10f))
            val e0 = hypot(25f, 10f)
            T.check("aim converges (static target)", tail(s) < DEAD + 0.2f, { "tail ${tail(s)}" })
            T.check("aim never runs away", (s.errs.maxOrNull() ?: 0f) <= 1.3f * e0, { "max ${s.errs.maxOrNull()} start $e0" })
            T.check("aim learns the horizontal sensitivity", abs(s.ctl.relX + 1.3f) < 0.4f, { "relX ${s.ctl.relX}" })
            T.check("aim learns the vertical sensitivity", abs(s.ctl.relY + 0.8f) < 0.3f, { "relY ${s.ctl.relY}" })
            T.check("aim learns the delay", abs(s.ctl.lag - 2f) < 1.2f, { "lag ${s.ctl.lag}" })
            T.check("aim becomes confident", s.ctl.calibrated, { "conf ${s.ctl.confidence}" })
            T.note("aim: error after 10/20/30 frames = ${s.errs[9]} / ${s.errs[19]} / ${s.errs[29]}; relX=${s.ctl.relX} relY=${s.ctl.relY} lag=${s.ctl.lag}")
        }
        // other delays
        for (lag in listOf(0, 1, 3, 4)) {
            val s = simulate(-1.3f, -1.0f, lag, Vec2(25f, 10f))
            T.check("aim converges with a delay of $lag frames", tail(s) < DEAD + 0.3f && (s.errs.maxOrNull() ?: 0f) <= 1.5f * hypot(25f, 10f), { "tail ${tail(s)} max ${s.errs.maxOrNull()}" })
        }
        // a touchy game and a sluggish one
        run {
            val s = simulate(-3f, -3f, 2, Vec2(25f, 10f))
            T.check("aim converges in a touchy game", tail(s) < DEAD + 0.3f && (s.errs.maxOrNull() ?: 0f) <= 1.6f * hypot(25f, 10f), { "tail ${tail(s)} max ${s.errs.maxOrNull()}" })
            T.check("aim learns a touchy game", abs(s.ctl.relX + 3f) < 0.9f, { "relX ${s.ctl.relX}" })
        }
        run {
            val s = simulate(-0.4f, -0.4f, 2, Vec2(25f, 10f), frames = 120)
            T.check("aim converges in a sluggish game", tail(s) < DEAD + 0.3f, { "tail ${tail(s)}" })
            T.check("aim restarts the stroke when it runs out of room", s.restarts >= 1, { "restarts ${s.restarts}" })
        }
        // a game that inverts the vertical axis
        run {
            val s = simulate(-1.2f, 0.9f, 2, Vec2(25f, 12f), frames = 120)
            T.check("aim recovers from an inverted vertical axis", tail(s) < DEAD + 0.3f, { "tail ${tail(s)}" })
            T.check("aim learns the sign of the vertical axis", s.ctl.relY > 0f, { "relY ${s.ctl.relY}" })
        }
        // a target that walks
        run {
            val s = simulate(-1.3f, -0.8f, 2, Vec2(20f, 0f), v = Vec2(30f, 0f), frames = 100)
            T.check("aim keeps up with a walking target", tail(s) < 3.0f, { "tail ${tail(s)}" })
        }
        // noisy measurements
        run {
            val s = simulate(-1.3f, -0.8f, 2, Vec2(25f, 10f), egoNoise = 0.35f, frames = 100)
            T.check("aim copes with noisy shift measurements", tail(s) < 2.0f, { "tail ${tail(s)}" })
        }
        // saved sensitivity from an earlier session: quicker
        run {
            val cold = simulate(-2f, -2f, 2, Vec2(25f, 10f))
            val warm = simulate(-2f, -2f, 2, Vec2(25f, 10f), preset = { it.loadCalibration(-2f, -2f, 2f, 0.9f) })
            val wm = warm.errs.take(10).average()
            val cm = cold.errs.take(10).average()
            T.check("saved calibration makes the first frames better", wm < cm, { "warm $wm cold $cm" })
        }
        // small things
        run {
            val ctl = AimController()
            ctl.screenPerFrame = SPF
            ctl.frameH = 90f
            ctl.observe(Ego.none(192, 90))
            T.check("no target, no movement", ctl.step(null, Vec2(96f, 45f), Tuning(), DT) === AimCmd.NONE)
            val trk = Track(1, Box.ofCenter(96.1f, 45.05f, 10f, 24f), Kind.MOTION, 0.9f)
            val pick = Pick(trk, Vec2(96.1f, 45.05f), 0.1f)
            T.check("on target, no movement", !ctl.step(pick, Vec2(96f, 45f), Tuning(), DT).active)
            val far = Pick(trk, Vec2(130f, 45f), 34f)
            val c = ctl.step(far, Vec2(96f, 45f), Tuning(), DT)
            T.check("a target to the right moves the finger right in the usual game", c.active && c.dx > 0f, { "dx ${c.dx}" })
            T.check("the first steps are small probes", c.dx <= 0.07f * 300f + 0.01f, { "dx ${c.dx}" })
        }
        run {
            val s = simulate(-1.3f, -0.8f, 2, Vec2(25f, 10f), tuning = Tuning(maxSpeed = 0.3f))
            T.check("speed limit slows the correction", s.errs[5] > simulate(-1.3f, -0.8f, 2, Vec2(25f, 10f)).errs[5] - 0.01f)
        }
        run {
            val ctl = AimController()
            T.check("latency grows with the lag", ctl.latency(DT) > DT)
            ctl.loadCalibration(-1f, -1f, 3f, 1f)
            T.check("loaded calibration is used", ctl.calibrated && abs(ctl.lag - 3f) < 1e-6f)
            ctl.loadCalibration(0f, 0f, 99f, 2f)
            T.check("silly calibration values are not taken", ctl.relX == -1f && ctl.lag <= 6f && ctl.confidence <= 1f)
        }
        val worst = max(0f, 0f)
        T.check("control suite ran", worst == 0f)
    }
}
