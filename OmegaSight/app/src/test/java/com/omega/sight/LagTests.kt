package com.omega.sight

import com.omega.sight.core.*
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The aim loop against a game that behaves like the real one measured on the emulator: the picture does not answer a finger movement
 * at one moment but spread over several frames, the delay wanders from movement to movement, and the measurement of the picture's
 * shift fails when the picture jumps farther than a few percent of the screen in one frame (it reads "no movement" then, with full
 * confidence). The controller knows none of this.
 */
object LagTests {
    private class Res(val errs: List<Float>, val ctl: AimController, val worstShift: Float, val failed: Int)

    private const val SPF = 8.33f
    private const val DEAD = 2.0f

    private fun sim(
        gain: Vec2,
        taps: FloatArray,
        start: Vec2,
        dt: Float,
        frames: Int = 160,
        failAbove: Float = 13f,
        jitter: Boolean = false,
        v: Vec2 = Vec2.ZERO,
        noise: Float = 0.12f,
        seed: Long = 11,
    ): Res {
        val ctl = AimController()
        ctl.screenPerFrame = SPF
        ctl.frameH = 86f
        ctl.zoneHalfW = 330f
        ctl.zoneHalfH = 230f
        val picker = TargetPicker()
        val tuning = Tuning()
        val cross = Vec2(96f, 43f)
        val rnd = Random(seed)
        val sched = Array(frames + 40) { Vec2.ZERO }
        var tx = cross.x + start.x
        var ty = cross.y + start.y
        val errs = ArrayList<Float>()
        var worst = 0f
        var failed = 0
        for (n in 0 until frames) {
            val s = sched[n]
            val truthX = s.x / SPF
            val truthY = s.y / SPF
            worst = maxOf(worst, hypot(truthX, truthY))
            var mx = truthX + noise * rnd.nextGaussian().toFloat()
            var my = truthY + noise * rnd.nextGaussian().toFloat()
            if (hypot(truthX, truthY) > failAbove) {
                // the measurement cannot follow: it says "nothing moved"
                mx = 0f
                my = 0f
                failed++
            }
            ctl.observe(Ego(mx, my, 0f, 96f, 43f, 0.1f, 1f, true))
            tx += truthX + v.x * dt
            ty += truthY + v.y * dt
            val trk = Track(1, Box.ofCenter(tx, ty, 10f, 24f), Kind.MOTION, 0.9f)
            trk.vx = v.x
            trk.vy = v.y
            trk.hits = 6
            val pick = picker.pick(listOf(trk), cross, 86f, tuning, ctl.latency(dt))
            val cmd = ctl.step(pick, cross, tuning, dt)
            val extra = if (jitter && rnd.nextFloat() < 0.4f) 1 else 0
            for (l in taps.indices) {
                val at = n + 1 + l + extra
                if (at < sched.size) sched[at] = Vec2(sched[at].x + gain.x * taps[l] * cmd.dx, sched[at].y + gain.y * taps[l] * cmd.dy)
            }
            errs.add(hypot(tx - cross.x, ty - cross.y))
        }
        return Res(errs, ctl, worst, failed)
    }

    private fun tail(r: Res, n: Int = 12) = r.errs.takeLast(n).average().toFloat()

    fun run() {
        // the picture answers over three or four frames, a little late, like the emulator
        val smear = floatArrayOf(0f, 0.15f, 0.4f, 0.3f, 0.15f)
        run {
            val r = sim(Vec2(-1f, -1f), smear, Vec2(32f, 12f), 0.1f)
            T.check("aim converges when the picture answers spread over several frames", tail(r) < DEAD + 0.6f, { "tail ${tail(r)}" })
            T.check("aim learns the whole sensitivity of a spread answer, not a part of it", abs(r.ctl.relX + 1f) < 0.3f && abs(r.ctl.relY + 1f) < 0.3f, { "rel ${r.ctl.relX} ${r.ctl.relY}" })
            T.check("aim keeps the picture within what the shift measurement can follow", r.worstShift < 13f, { "worst shift ${r.worstShift}" })
            T.check("aim never made the measurement fail", r.failed == 0, { "failed ${r.failed}" })
        }
        // a fast phone: many frames per second, so the same delay is many frames
        run {
            val slow = floatArrayOf(0f, 0f, 0f, 0f, 0.1f, 0.2f, 0.25f, 0.2f, 0.15f, 0.1f)
            val r = sim(Vec2(-1.2f, -1.2f), slow, Vec2(32f, 10f), 1f / 60f, frames = 400)
            T.check("aim converges with an answer eight frames away", tail(r, 20) < DEAD + 0.6f, { "tail ${tail(r, 20)}" })
            T.check("aim never runs away with a long delay", (r.errs.maxOrNull() ?: 0f) <= 1.3f * hypot(32f, 10f), { "max ${r.errs.maxOrNull()}" })
        }
        // the delay wanders
        run {
            val r = sim(Vec2(-1f, -1.3f), smear, Vec2(30f, 10f), 0.08f, jitter = true, frames = 220)
            T.check("aim converges when the delay wanders", tail(r) < DEAD + 1.0f, { "tail ${tail(r)}" })
            T.check("aim keeps the measurement alive when the delay wanders", r.failed == 0, { "failed ${r.failed}" })
        }
        // a touchy game: every finger pixel moves the picture three pixels
        run {
            val r = sim(Vec2(-3f, -3f), smear, Vec2(30f, 12f), 0.1f, frames = 200)
            T.check("aim converges in a touchy game that answers late", tail(r) < DEAD + 1.0f, { "tail ${tail(r)}" })
            T.check("aim stays within the measurable shift in a touchy game", r.worstShift < 13f, { "worst shift ${r.worstShift}" })
        }
        // a target that walks across the view
        run {
            val r = sim(Vec2(-1f, -1f), smear, Vec2(30f, 5f), 0.06f, v = Vec2(25f, 0f), frames = 240)
            T.check("aim follows a walking target through a delayed answer", tail(r) < 4f, { "tail ${tail(r)}" })
        }
        // the sensitivity is learnt even though the first shift readings that fail are wrong
        run {
            val r = sim(Vec2(-1f, -1f), smear, Vec2(34f, 14f), 0.1f, failAbove = 7f, frames = 240)
            T.check("aim recovers when some shift readings fail", tail(r) < DEAD + 1.5f, { "tail ${tail(r)} failed ${r.failed}" })
            T.check("aim does not learn a wrong sensitivity from failed readings", abs(r.ctl.relX + 1f) < 0.45f, { "relX ${r.ctl.relX}" })
        }
        // the search hint: where the picture should have moved to, from the movements already ordered
        run {
            val ctl = AimController()
            ctl.screenPerFrame = SPF
            ctl.loadCalibration(-1f, -1f, 0f, 1f)
            val out = FloatArray(2)
            ctl.expectedShift(out)
            T.check("nothing ordered, no shift expected", out[0] == 0f && out[1] == 0f)
            val picker = TargetPicker()
            val trk = Track(1, Box.ofCenter(96f + 30f, 43f, 10f, 24f), Kind.MOTION, 0.9f)
            trk.hits = 6
            ctl.observe(Ego(0f, 0f, 0f, 96f, 43f, 0.1f, 1f, true))
            val pick = picker.pick(listOf(trk), Vec2(96f, 43f), 86f, Tuning(), ctl.latency(0.05f))
            val cmd = ctl.step(pick, Vec2(96f, 43f), Tuning(), 0.05f)
            ctl.expectedShift(out)
            T.check("a finger movement to the right makes the expected shift point left", cmd.dx > 0f && out[0] < 0f, { "cmd ${cmd.dx} shift ${out[0]}" })
        }
    }
}
