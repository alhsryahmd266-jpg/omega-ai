package com.omega.sight

import com.omega.sight.core.Box
import com.omega.sight.core.GlobalMotion
import com.omega.sight.core.Gray
import com.omega.sight.core.MotionDetector
import java.util.Random
import kotlin.math.abs

/**
 * A night city of lit windows (hard, bright, regular edges everywhere: the worst case for telling "the camera turned" from "something
 * moved") drawn at six times the size of the analysis picture and cut down by averaging, so a camera shift of a sixth of a pixel is
 * exact in the big picture but lands between pixels in the small one, like on a real screen. The first emulator runs showed the app
 * turning the camera in circles after such a scene fed it dozens of false movers per frame while the camera was turning.
 */
private class City(seed: Long) {
    val k = 6
    val w = 192 * k * 3
    val h = 86 * k
    private val px = FloatArray(w * h) { 26f + 10f * (it / w) / h }

    init {
        val rnd = Random(seed)
        var x = 0
        while (x < w) {
            val bw = 200 + rnd.nextInt(220)
            val bh = 150 + rnd.nextInt(190)
            val top = h - 18 - bh
            val wall = 38f + rnd.nextInt(10)
            for (y in top until h - 18) for (xx in x until minOf(w, x + bw)) px[y * w + xx] = wall
            var wy = top + 14
            while (wy + 15 < h - 22) {
                var wx = x + 12
                while (wx + 13 < minOf(w, x + bw) - 8) {
                    val v = if (rnd.nextFloat() < 0.35f) 172f else 34f
                    for (j in 0 until 15) for (i in 0 until 13) px[(wy + j) * w + wx + i] = v
                    wx += 26
                }
                wy += 30
            }
            x += bw + 30 + rnd.nextInt(60)
        }
        repeat(60) {
            val sx = rnd.nextInt(w - 3)
            val sy = rnd.nextInt(h / 3)
            for (j in 0 until 3) for (i in 0 until 3) px[(sy + j) * w + sx + i] = 130f
        }
    }

    /** the small picture when the camera has moved by (sx, sy) big-picture pixels; [sprite] = a box painted on the big picture */
    fun frame(sx: Int, sy: Int, sprite: IntArray? = null, value: Float = 20f): Gray {
        val g = Gray(192, 86)
        val ox = ((sx % w) + w) % w
        val oy = ((sy % h) + h) % h
        for (y in 0 until 86) for (x in 0 until 192) {
            var s = 0f
            for (j in 0 until k) {
                val yy = ((y * k + j - oy) % h + h) % h
                for (i in 0 until k) {
                    val xx = ((x * k + i - ox) % w + w) % w
                    var v = px[yy * w + xx]
                    if (sprite != null) {
                        val gx = x * k + i
                        val gy = y * k + j
                        if (gx >= sprite[0] && gx < sprite[2] && gy >= sprite[1] && gy < sprite[3]) v = value
                    }
                    s += v
                }
            }
            g.d[y * 192 + x] = (s / (k * k)).toInt().coerceIn(0, 255).toByte()
        }
        return g
    }
}

object PanTests {
    fun run() {
        val city = City(11)
        val k = city.k
        // 1) nothing moves on its own: whatever is reported while the camera turns is false
        var frames = 0
        var dets = 0
        var worstEgo = 0f
        for ((sx, sy) in listOf(3 to 0, 8 to 0, 16 to 0, 27 to 0, 43 to 0, 16 to 9, 27 to 4, 0 to 5, 0 to 14)) {
            val gm = GlobalMotion(192, 86)
            val md = MotionDetector(192, 86)
            var prev = city.frame(0, 0)
            var cd = 0
            var ce = 0f
            for (f in 1..14) {
                val cur = city.frame(f * sx, f * sy)
                val e = gm.estimate(prev, cur)
                val d = md.detect(prev, cur, e, null, 0.6f, 0.35f)
                if (f > 3) {
                    frames++
                    dets += d.size
                    cd += d.size
                    ce = maxOf(ce, abs(e.tx - sx / k.toFloat()), abs(e.ty - sy / k.toFloat()))
                    worstEgo = maxOf(worstEgo, abs(e.tx - sx / k.toFloat()), abs(e.ty - sy / k.toFloat()))
                }
                prev = cur
            }
            T.note("   camera shift (${"%.2f".format(sx / k.toFloat())}, ${"%.2f".format(sy / k.toFloat())}) px/frame: ${"%.2f".format(cd / 11f)} false movers per frame, shift error at most ${"%.2f".format(ce)}")
        }
        T.note("lit-window city, camera turning at fractional speeds: ${"%.2f".format(dets / frames.toFloat())} false movers per frame, camera shift error at most ${"%.2f".format(worstEgo)} px")
        T.check("a lit-window city does not fill with false movers while the camera turns (${dets / frames.toFloat()} per frame)", dets <= 0.6f * frames)
        T.check("the camera shift is read to a third of a pixel or better (worst error $worstEgo px)", worstEgo < 0.35f)

        // 2) a body that really walks across it is still found
        run {
            val gm = GlobalMotion(192, 86)
            val md = MotionDetector(192, 86)
            val sx = 16 // the camera turns by 2.67 px per frame
            var prev = city.frame(0, 0, intArrayOf(100 * k, 40 * k, 106 * k, 52 * k))
            var hit = 0
            var tries = 0
            for (f in 1..14) {
                // the body moves on the screen by -1.2 px per frame while the world moves by -2.67: it walks against the camera turn
                val bx = ((100 - 1.2f * f) * k).toInt()
                val sprite = intArrayOf(bx, 40 * k, bx + 6 * k, 52 * k)
                val cur = city.frame(f * sx, 0, sprite)
                val e = gm.estimate(prev, cur)
                val d = md.detect(prev, cur, e, null, 0.6f, 0.35f)
                if (f >= 3) {
                    tries++
                    val truth = Box(bx / k.toFloat(), 40f, bx / k.toFloat() + 6f, 52f)
                    if (d.any { it.box.iou(truth) >= 0.15f }) hit++
                }
                prev = cur
            }
            T.note("a body walking through the lit-window city while the camera turns: found in $hit of $tries frames")
            // it moves against the world by only 1.5 px per frame and crosses the lit windows, where a sharp edge of equal brightness hides it
            // from a frame difference that has to ignore the shift error; the far mode, the glow detector and the model are the other eyes
            T.check("a body walking through the lit-window city is found while the camera turns ($hit/$tries)", hit >= tries * 0.6f)
        }
    }
}
