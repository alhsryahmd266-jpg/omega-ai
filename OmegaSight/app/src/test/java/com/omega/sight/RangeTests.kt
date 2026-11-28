package com.omega.sight

import com.omega.sight.core.*
import java.util.Random
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * How wide is the range of things the motion eyes can see? A body of every size from one pixel to a big part of the screen, moving at
 * every speed from a creep to a dash, with the camera still and with the camera turning. The numbers are the share of the frames in
 * which something was reported where the body was (the coarse motion detector and the sharp far mode together, as the engine uses
 * them), and how many reports were somewhere else.
 */
object RangeTests {
    private const val W = 480
    private const val H = 216
    private const val K = 4

    class Cell(val size: Float, val speed: Float, val pan: Boolean, val rate: Float, val stray: Int, val evaluated: Int)

    private fun down(src: Gray, out: Gray) {
        val cw = src.w / K
        val ch = src.h / K
        for (y in 0 until ch) for (x in 0 until cw) {
            var s = 0
            for (j in 0 until K) for (i in 0 until K) s += src.d[(y * K + j) * src.w + x * K + i].toInt() and 255
            out.d[y * cw + x] = (s / (K * K)).toByte()
        }
    }

    /**
     * One body of [size] fine pixels, moving at [speed] fine px per frame against the world (to the right), value [value]; the camera
     * turns by (2.3, 0.7) fine px per frame when [pan].
     */
    fun cell(size: Float, speed: Float, pan: Boolean, value: Float = 250f, seed: Long = 5): Cell {
        val world = World(W + 420, H + 120, seed)
        val rnd = Random(seed)
        val fm = FarMotion(W, H)
        val md = MotionDetector(W / K, H / K)
        val gm = GlobalMotion(W / K, H / K)
        var prevC = Gray(W / K, H / K)
        var curC = Gray(W / K, H / K)
        val camX = if (pan) 2.3f else 0f
        val camY = if (pan) 0.7f else 0f
        val frames = min(26, 6 + ((W - 120f) / max(speed, 0.5f)).toInt())
        var evaluated = 0
        var hits = 0
        var stray = 0
        for (t in 0 until frames) {
            val cur = world.frame(W, H, 60f + camX * t, 40f + camY * t, 0f, 1.5f, rnd)
            // the body's place on the screen: it moves by (speed - camera) against the picture
            val x = 40f + (speed - camX) * t + (if (pan) 2.3f * 0f else 0f)
            val y = 100f - camY * t
            paintRect(cur, x, y, x + size, y + size, value)
            down(cur, curC)
            val dets = ArrayList<Det>()
            var ego = Ego.none(W / K, H / K)
            if (t > 0) {
                ego = gm.estimate(prevC, curC)
                for (d in md.detect(prevC, curC, ego, null, 0.6f, 0.35f)) {
                    dets.add(Det(Box(d.box.l * K, d.box.t * K, d.box.r * K, d.box.b * K), d.score, d.kind))
                }
            }
            dets.addAll(fm.process(cur, if (ego.valid) ego else null, K.toFloat(), null, null, 0.6f, 1))
            if (t >= 6) {
                evaluated++
                val cx = x + size / 2
                val cy = y + size / 2
                val gate = max(4f, size * 0.8f + speed * 1.6f)
                if (dets.any { hypot(it.box.cx - cx, it.box.cy - cy) <= gate }) hits++
                stray += dets.count { hypot(it.box.cx - cx, it.box.cy - cy) > gate * 1.5f + 6f }
            }
            val sw = prevC
            prevC = curC
            curC = sw
        }
        return Cell(size, speed, pan, if (evaluated == 0) 0f else hits.toFloat() / evaluated, stray, evaluated)
    }

    val sizes = floatArrayOf(1f, 2f, 4f, 8f, 24f, 80f)
    val speeds = floatArrayOf(0.05f, 0.2f, 1f, 4f, 15f, 40f)

    fun run() {
        val grid = HashMap<String, Cell>()
        for (pan in listOf(false, true)) {
            val sb = StringBuilder()
            sb.append(if (pan) "camera turning  " else "camera still    ")
            sb.append("speed px/frame:  ")
            for (v in speeds) sb.append(String.format("%6.2f ", v))
            T.note(sb.toString())
            for (s in sizes) {
                val row = StringBuilder(String.format("      size %3.0f px:           ", s))
                for (v in speeds) {
                    val c = cell(s, v, pan)
                    grid["$s/$v/$pan"] = c
                    row.append(String.format("%3.0f%%/%-2d ", c.rate * 100f, c.stray))
                }
                T.note(row.toString())
            }
        }
        report(grid)
    }

    private fun report(grid: Map<String, Cell>) {
        fun rate(s: Float, v: Float, pan: Boolean) = grid["$s/$v/$pan"]!!.rate
        // the middle of the range must work; the edges are reported above
        for (pan in listOf(false, true)) {
            val tag = if (pan) "camera turning" else "camera still"
            T.check("range: a 8 px body at 4 px/frame is seen, $tag (${(rate(8f, 4f, pan) * 100).toInt()}%)", rate(8f, 4f, pan) >= 0.8f)
            T.check("range: a 24 px body at 1 px/frame is seen, $tag (${(rate(24f, 1f, pan) * 100).toInt()}%)", rate(24f, 1f, pan) >= 0.8f)
        }
    }
}
