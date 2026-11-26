package com.omega.sight

import com.omega.sight.core.*
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Screen furniture and far movers, in made-up scenes with known answers. */
object VisionTests {
    private const val HW = 320
    private const val HH = 144

    /** the "game": a world that scrolls under fixed buttons */
    private fun drawHud(g: Gray) {
        paintRing(g, 270f, 100f, 16f, 3f, 245f, 0.85f)
        paintDisc(g, 270f, 100f, 9f, 240f, 0.35f)
        paintRing(g, 52f, 104f, 27f, 3f, 245f, 0.85f)
        paintDisc(g, 52f, 104f, 11f, 240f, 0.5f)
        paintRect(g, 280f, 8f, 312f, 18f, 235f)
    }

    private fun hud() {
        val world = World(HW + 700, HH + 400, 31)
        val rnd = Random(3)
        val an = HudAnalyzer(HW, HH)
        var ox = 200f
        var oy = 150f
        var prev = world.frame(HW, HH, ox, oy, 0f, 1.5f, rnd)
        drawHud(prev)
        var counted = 0
        for (t in 1..30) {
            ox += (if (t % 2 == 0) 1 else -1) * (3f + rnd.nextFloat() * 5f)
            oy += (rnd.nextFloat() - 0.5f) * 5f
            val cur = world.frame(HW, HH, ox, oy, 0f, 1.5f, rnd)
            drawHud(cur)
            if (an.update(prev, cur)) counted++
            prev = cur
        }
        T.check("hud counts the pairs where the world moved ($counted)", counted >= 25 && an.ready)

        val c = an.circles()
        val fire = c.firstOrNull { hypot(it.cx - 270f, it.cy - 100f) < 2.5f }
        val stick = c.firstOrNull { hypot(it.cx - 52f, it.cy - 104f) < 2.5f }
        T.check("hud finds the fire button", fire != null && abs(fire.r - 16f) < 3.5f, { "found ${c.map { "(${it.cx},${it.cy},r${it.r})" }}" })
        T.check("hud finds the joystick", stick != null && abs(stick.r - 27f) < 4f, { "found ${c.map { "(${it.cx},${it.cy},r${it.r})" }}" })
        T.check("hud gives the roles", fire?.role == Role.FIRE && stick?.role == Role.STICK, { "fire ${fire?.role} stick ${stick?.role}" })
        T.check("hud does not invent buttons in the world (${c.size} found)", c.size <= 4)
        T.note("hud: ${c.map { "${it.role}(${it.cx},${it.cy},r${it.r},s${"%.2f".format(it.score)},rings${it.rings})" }}")

        // the mask covers the buttons and little else
        val m = an.fixedEdges()
        var covered = 0
        for (k in 0 until 40) {
            val a = 2.0 * PI * k / 40.0
            val x = (270f + 16f * cos(a)).toInt()
            val y = (100f + 16f * sin(a)).toInt()
            var any = false
            for (j in -1..1) for (i in -1..1) if (m[(y + j) * HW + x + i].toInt() != 0) any = true
            if (any) covered++
        }
        T.check("hud mask covers the fire ring ($covered/40)", covered >= 32)
        var world0 = 0
        var worldTotal = 0
        for (y in 20 until 80) for (x in 100 until 200) {
            worldTotal++
            if (m[y * HW + x].toInt() != 0) world0++
        }
        T.check("hud mask leaves the world alone ($world0/$worldTotal)", world0 < worldTotal * 0.01f)
        val small = an.maskFor(192, 86, 2)
        var onRing = false
        val rx = 286 * 192 / HW
        val ry = 100 * 86 / HH
        for (j in -1..1) for (i in -1..1) if (small[(ry + j) * 192 + rx + i].toInt() != 0) onRing = true
        T.check("hud mask resamples to another size", small.size == 192 * 86 && onRing)
        an.setButtons(listOf(ButtonCand(270f, 100f, 16f, 1f, 1, Role.FIRE)))
        val big = an.maskFor(HW, HH, 0)
        T.check("a confirmed button is filled in the mask", big[100 * HW + 270].toInt() != 0 && big[100 * HW + 262].toInt() != 0)

        // from one still picture
        val still = world.frame(HW, HH, 250f, 160f, 0f, 1.5f, rnd)
        drawHud(still)
        val single = HudAnalyzer(HW, HH).circlesOf(still)
        T.check("hud finds the fire button in a single picture too", single.any { hypot(it.cx - 270f, it.cy - 100f) < 3f }, { "found ${single.map { "(${it.cx},${it.cy},r${it.r})" }}" })

        // nothing moves: nothing is learnt
        val still2 = HudAnalyzer(HW, HH)
        val s1 = world.frame(HW, HH, 250f, 160f, 0f, 1.5f, rnd)
        val s2 = world.frame(HW, HH, 250f, 160f, 0f, 1.5f, rnd)
        T.check("hud learns nothing from a still scene", !still2.update(s1, s2) && !still2.ready && still2.circles().isEmpty())

        // where the camera can be dragged
        val zone = an.suggestZone(listOf(ButtonCand(270f, 100f, 16f, 1f, 1, Role.FIRE), ButtonCand(52f, 104f, 27f, 1f, 2, Role.STICK)))
        val area = (zone[2] - zone[0]) * (zone[3] - zone[1])
        T.check("zone is on the right side", zone[0] >= 0.4f * HW, { "zone ${zone.toList()}" })
        T.check("zone is big", area >= 0.15f * HW * HH, { "zone ${zone.toList()}" })
        T.check("zone does not cover the fire button", !(270 in zone[0]..zone[2] && 100 in zone[1]..zone[3]), { "zone ${zone.toList()}" })
    }

    private class FarResult(val hits: Int, val evaluated: Int, val stray: Int)

    private fun far(size: Float, value: Float, withObject: Boolean, seed: Long, frames: Int = 40, noise: Float = 1.5f): FarResult {
        val w = 480
        val h = 216
        val world = World(w + 300, h + 200, seed)
        val rnd = Random(seed)
        val fm = FarMotion(w, h)
        var hits = 0
        var evaluated = 0
        var stray = 0
        for (t in 0 until frames) {
            val cur = world.frame(w, h, 100f + 1.3f * t, 80f - 0.6f * t, 0f, noise, rnd)
            val x = 200f + 0.8f * t
            val y = 90f + 0.3f * t
            if (withObject) paintRect(cur, x, y, x + size, y + size, value)
            val dets = fm.process(cur, null, 1f, null, null, 0.6f, 1)
            if (t >= 6) {
                evaluated++
                val ox = x + size / 2
                val oy = y + size / 2
                if (withObject && dets.any { hypot(it.box.cx - ox, it.box.cy - oy) < 2.5f }) hits++
                stray += dets.count { !withObject || hypot(it.box.cx - ox, it.box.cy - oy) > 6f }
            }
        }
        return FarResult(hits, evaluated, stray)
    }

    private fun farMotion() {
        // nothing there: silence
        var strayTotal = 0
        for (seed in 21L..25L) strayTotal += far(3f, 250f, false, seed).stray
        // lone noise pixels; the tracker asks for three sightings in the same spot, so they never become targets
        T.check("far mode is nearly silent on empty scenes with a moving camera ($strayTotal in 5 runs)", strayTotal <= 6)

        for ((size, minRate) in listOf(6f to 0.85f, 3f to 0.8f, 2f to 0.65f)) {
            var hits = 0
            var ev = 0
            var stray = 0
            for (seed in 21L..25L) {
                val r = far(size, 250f, true, seed)
                hits += r.hits
                ev += r.evaluated
                stray += r.stray
            }
            val rate = hits.toFloat() / ev
            T.check("far mode finds a ${size.toInt()}x${size.toInt()} px mover (${(rate * 100).toInt()}%)", rate >= minRate, { "rate $rate stray $stray" })
            T.check("far mode adds few false ones next to a ${size.toInt()}x${size.toInt()} mover ($stray)", stray <= 6)
        }
        // one pixel: report what really happens (it is at the noise floor)
        run {
            var hits = 0
            var ev = 0
            var stray = 0
            for (seed in 21L..25L) {
                val r = far(1f, 255f, true, seed)
                hits += r.hits
                ev += r.evaluated
                stray += r.stray
            }
            val rate = hits.toFloat() / ev
            T.note("far mode, a single bright pixel: found in ${(rate * 100).toInt()}% of frames, $stray stray detections in 5 runs")
            T.check("far mode sees a single bright pixel at least sometimes (${(rate * 100).toInt()}%)", rate >= 0.25f)
        }
        // a dark small mover
        run {
            var hits = 0
            var ev = 0
            for (seed in 21L..25L) {
                val r = far(3f, 5f, true, seed)
                hits += r.hits
                ev += r.evaluated
            }
            T.check("far mode finds a dark 3x3 px mover (${hits * 100 / ev}%)", hits.toFloat() / ev >= 0.75f)
        }
        // noisier footage
        run {
            var hits = 0
            var ev = 0
            var stray = 0
            for (seed in 21L..23L) {
                val r = far(3f, 250f, true, seed, noise = 4f)
                hits += r.hits
                ev += r.evaluated
                stray += r.stray
            }
            T.check("far mode copes with noisier footage (${hits * 100 / ev}%, $stray stray)", hits.toFloat() / ev >= 0.6f && stray <= 10)
        }
        // a still camera
        run {
            val w = 480
            val h = 216
            val world = World(w + 100, h + 100, 41)
            val rnd = Random(41)
            val fm = FarMotion(w, h)
            var hits = 0
            var ev = 0
            for (t in 0 until 30) {
                val cur = world.frame(w, h, 50f, 40f, 0f, 1.5f, rnd)
                val x = 200f + 0.5f * t
                paintRect(cur, x, 90f, x + 4f, 94f, 250f)
                val dets = fm.process(cur, null, 1f, null, null, 0.6f, 1)
                if (t >= 6) {
                    ev++
                    if (dets.any { hypot(it.box.cx - (x + 2f), it.box.cy - 92f) < 2.5f }) hits++
                }
            }
            T.check("far mode finds a slow 4x4 px mover with a still camera ($hits/$ev)", hits >= ev * 0.8f)
        }
        // the coarse picture cannot see what the far pass sees
        run {
            val w = 480
            val h = 216
            val world = World(w + 100, h + 100, 42)
            val rnd = Random(42)
            val small = Gray(w / 4, h / 4)
            val md = MotionDetector(w / 4, h / 4)
            var prev: Gray? = null
            var seen = 0
            for (t in 0 until 20) {
                val cur = world.frame(w, h, 50f, 40f, 0f, 1.5f, rnd)
                val x = 200f + 0.8f * t
                paintRect(cur, x, 90f, x + 3f, 93f, 250f)
                Resample.half(cur, Gray(w / 2, h / 2).also { })
                // box-average down by 4 into the small frame
                for (yy in 0 until h / 4) for (xx in 0 until w / 4) {
                    var s = 0
                    for (j in 0 until 4) for (i in 0 until 4) s += cur[xx * 4 + i, yy * 4 + j]
                    small.d[yy * (w / 4) + xx] = (s / 16).toByte()
                }
                val p = prev
                if (p != null && t >= 3) {
                    val e = Ego(0f, 0f, 0f, small.w / 2f, small.h / 2f, 0.1f, 1f, true)
                    seen += md.detect(p, small, e, null, 0.6f, 0.0f).count { hypot(it.box.cx * 4 - (x + 1.5f), it.box.cy * 4 - 91.5f) < 8f }
                }
                prev = Gray(small.w, small.h).also { it.copyFrom(small) }
            }
            T.note("the small picture alone saw the 3x3 px mover $seen times in 17 frames (far mode finds it much more often)")
        }
    }

    fun run() {
        hud()
        farMotion()
    }
}
