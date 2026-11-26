package com.omega.sight.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How the whole picture moved from one frame to the next: content that was at (x, y) is now at
 * (x + tx + s*(x-cx), y + ty + s*(y-cy)). Turning the camera is a shift (tx, ty); walking forwards or backwards is a zoom (s)
 * about the middle of the screen.
 */
class Ego(
    val tx: Float,
    val ty: Float,
    val s: Float,
    val cx: Float,
    val cy: Float,
    /** typical leftover error of the patches after the fit, in px (small = a clean fit) */
    val mad: Float,
    /** share of the patches that agree with the fit */
    val inliers: Float,
    val valid: Boolean,
) {
    fun dx(x: Float, y: Float) = tx + s * (x - cx)
    fun dy(x: Float, y: Float) = ty + s * (y - cy)

    /** how far the picture moved at the middle of the screen */
    val speed: Float get() = sqrt(tx * tx + ty * ty)

    companion object {
        fun none(w: Int, h: Int) = Ego(0f, 0f, 0f, w / 2f, h / 2f, 99f, 0f, false)
    }
}

/**
 * Finds the movement of the whole picture (the camera) between two frames by matching small patches on a coarse level first and
 * refining on the full level, then fitting one shift + zoom to all patches with a robust, outlier-ignoring fit. The things that move
 * on their own (enemies) disagree with the fit and so do not disturb it; what is left over after the fit is what MotionDetector sees.
 */
class GlobalMotion(val w: Int, val h: Int) {
    private val w2 = w / 2
    private val h2 = h / 2
    private val a2 = Gray(w2, h2)
    private val b2 = Gray(w2, h2)

    // the patches (coarse level): top-left corner and size
    private val pw = 12
    private val ph = 10
    private val xs: IntArray
    private val ys: IntArray

    // one entry per usable patch
    private val sx = FloatArray(64)
    private val sy = FloatArray(64)
    private val mx = FloatArray(64)
    private val my = FloatArray(64)
    private val cw = FloatArray(64)
    private val grid = IntArray(17 * 17)
    private val out = FloatArray(3)

    private var guessX = 0f
    private var guessY = 0f

    init {
        val cols = 7
        val rows = 4
        val m = 6
        xs = IntArray(cols) { m + it * (w2 - 2 * m - pw) / (cols - 1) }
        ys = IntArray(rows) { m + it * (h2 - 2 * m - ph) / (rows - 1) }
    }

    /** forget the last measured picture shift (the starting point of the next search) */
    fun reset() {
        guessX = 0f
        guessY = 0f
    }

    /** start the next search around this shift (in this instance's own pixels), e.g. what a coarser instance measured */
    fun hint(tx: Float, ty: Float) {
        guessX = tx
        guessY = ty
    }

    /**
     * @param hud optional full-size mask (1 = a fixed on-screen button / bar that never moves with the world); patches on it are skipped
     */
    fun estimate(prev: Gray, cur: Gray, hud: ByteArray? = null): Ego {
        Resample.half(prev, a2)
        Resample.half(cur, b2)
        var e = pass(prev, cur, hud, 4, (guessX / 2).roundToInt(), (guessY / 2).roundToInt())
        if (!e.valid && (guessX != 0f || guessY != 0f)) e = pass(prev, cur, hud, 4, 0, 0)
        if (!e.valid) e = pass(prev, cur, hud, 8, 0, 0)
        if (e.valid) {
            guessX = e.tx
            guessY = e.ty
        } else {
            guessX = 0f
            guessY = 0f
        }
        return e
    }

    private fun pass(prev: Gray, cur: Gray, hud: ByteArray?, r: Int, gx: Int, gy: Int): Ego {
        var n = 0
        for (y0 in ys) {
            for (x0 in xs) {
                if (n >= 60) break
                if (texture(a2, x0, y0) < 2.5f) continue
                if (hud != null && onHud(hud, x0 * 2, y0 * 2, pw * 2, ph * 2)) continue
                if (!match(a2, b2, x0, y0, pw, ph, gx.coerceIn(-12, 12), gy.coerceIn(-12, 12), r, out)) continue
                // refine on the full-size frames around twice the coarse answer
                val rx = (2 * out[0]).roundToInt()
                val ry = (2 * out[1]).roundToInt()
                var dx = 2 * out[0]
                var dy = 2 * out[1]
                var c = out[2]
                if (match(prev, cur, x0 * 2, y0 * 2, pw * 2, ph * 2, rx, ry, 1, out)) {
                    dx = out[0]
                    dy = out[1]
                    c = max(c, out[2])
                }
                sx[n] = (x0 * 2 + pw) - w / 2f
                sy[n] = (y0 * 2 + ph) - h / 2f
                mx[n] = dx
                my[n] = dy
                cw[n] = c
                n++
            }
        }
        return fit(n)
    }

    private fun onHud(hud: ByteArray, x: Int, y: Int, pw: Int, ph: Int): Boolean {
        var hits = 0
        for (j in 0..2) for (i in 0..2) {
            val xx = (x + i * (pw - 1) / 2).coerceIn(0, w - 1)
            val yy = (y + j * (ph - 1) / 2).coerceIn(0, h - 1)
            if (hud[yy * w + xx].toInt() != 0) hits++
        }
        return hits >= 3
    }

    /** average gradient of a patch: flat sky or a plain wall cannot be matched */
    private fun texture(g: Gray, x0: Int, y0: Int): Float {
        var s = 0
        for (j in 0 until ph - 1) {
            val row = (y0 + j) * g.w + x0
            for (i in 0 until pw - 1) {
                val v = g.d[row + i].toInt() and 255
                s += abs(v - (g.d[row + i + 1].toInt() and 255)) + abs(v - (g.d[row + g.w + i].toInt() and 255))
            }
        }
        return s / ((pw - 1) * (ph - 1)).toFloat()
    }

    /**
     * Where the patch (x0, y0, pw, ph) of [a] went in [b]: tries every shift within +-r of (gx, gy), keeps the one with the smallest
     * sum of absolute differences and refines it below one pixel with a parabola. out = dx, dy, confidence (0..1).
     */
    private fun match(a: Gray, b: Gray, x0: Int, y0: Int, pw: Int, ph: Int, gx: Int, gy: Int, r: Int, out: FloatArray): Boolean {
        val side = 2 * r + 1
        var best = Int.MAX_VALUE
        var bi = -1
        var total = 0L
        var count = 0
        for (j in 0 until side) {
            val dy = gy - r + j
            val by = y0 + dy
            for (i in 0 until side) {
                val dx = gx - r + i
                val bx = x0 + dx
                if (bx < 0 || by < 0 || bx + pw > b.w || by + ph > b.h || x0 + pw > a.w || y0 + ph > a.h) {
                    grid[j * side + i] = -1
                    continue
                }
                var s = 0
                for (k in 0 until ph) {
                    val ra = (y0 + k) * a.w + x0
                    val rb = (by + k) * b.w + bx
                    for (m in 0 until pw) s += abs((a.d[ra + m].toInt() and 255) - (b.d[rb + m].toInt() and 255))
                }
                grid[j * side + i] = s
                total += s
                count++
                if (s < best) {
                    best = s
                    bi = j * side + i
                }
            }
        }
        if (bi < 0 || count < 5) return false
        val mean = total.toFloat() / count
        // the best shift must be clearly better than the average one, and the patches must really look alike
        if (best > 0.72f * mean || best > 38 * pw * ph) return false
        val bj = bi / side
        val bk = bi % side
        var fx = 0f
        var fy = 0f
        if (bk in 1 until side - 1 && grid[bi - 1] >= 0 && grid[bi + 1] >= 0) fx = parabola(grid[bi - 1], best, grid[bi + 1])
        if (bj in 1 until side - 1 && grid[bi - side] >= 0 && grid[bi + side] >= 0) fy = parabola(grid[bi - side], best, grid[bi + side])
        out[0] = gx - r + bk + fx
        out[1] = gy - r + bj + fy
        out[2] = (1f - best / mean).coerceIn(0f, 1f)
        return true
    }

    private fun parabola(m: Int, c: Int, p: Int): Float {
        val d = (m - 2 * c + p).toFloat()
        if (d <= 1e-3f) return 0f
        return (0.5f * (m - p) / d).coerceIn(-0.5f, 0.5f)
    }

    /** one shift + zoom through all patches, with the patches that move on their own given little weight */
    private fun fit(n: Int): Ego {
        if (n < 5) return Ego.none(w, h)
        val wt = FloatArray(n) { cw[it] + 0.05f }
        var tx = 0.0
        var ty = 0.0
        var s = 0.0
        var sigma = 1f
        val res = FloatArray(n)
        for (iter in 0 until 5) {
            var a00 = 0.0
            var a02 = 0.0
            var a12 = 0.0
            var a22 = 0.0
            var b0 = 0.0
            var b1 = 0.0
            var b2 = 0.0
            for (i in 0 until n) {
                val q = wt[i].toDouble()
                val rx = sx[i].toDouble()
                val ry = sy[i].toDouble()
                a00 += q
                a02 += q * rx
                a12 += q * ry
                a22 += q * (rx * rx + ry * ry)
                b0 += q * mx[i]
                b1 += q * my[i]
                b2 += q * (rx * mx[i] + ry * my[i])
            }
            val sol = solve3(doubleArrayOf(a00, 0.0, a02, 0.0, a00, a12, a02, a12, a22), doubleArrayOf(b0, b1, b2)) ?: break
            tx = sol[0]
            ty = sol[1]
            s = sol[2].coerceIn(-0.25, 0.25)
            for (i in 0 until n) {
                val ex = mx[i] - tx - s * sx[i]
                val ey = my[i] - ty - s * sy[i]
                res[i] = sqrt((ex * ex + ey * ey).toFloat())
            }
            val sorted = res.copyOf(n).also { it.sort() }
            sigma = max(0.2f, 1.4826f * sorted[n / 2])
            for (i in 0 until n) {
                val k = res[i] / (2f * sigma)
                wt[i] = (cw[i] + 0.05f) / (1f + k * k)
            }
        }
        var inl = 0
        for (i in 0 until n) if (res[i] < 2.5f * sigma) inl++
        val share = inl / n.toFloat()
        return Ego(tx.toFloat(), ty.toFloat(), s.toFloat(), w / 2f, h / 2f, sigma, share, share >= 0.5f && sigma < 3f)
    }

    private fun solve3(a: DoubleArray, b: DoubleArray): DoubleArray? {
        val m = a.copyOf()
        val v = b.copyOf()
        for (c in 0 until 3) {
            var p = c
            for (r in c + 1 until 3) if (abs(m[r * 3 + c]) > abs(m[p * 3 + c])) p = r
            if (abs(m[p * 3 + c]) < 1e-9) return null
            if (p != c) {
                for (k in 0 until 3) {
                    val t = m[c * 3 + k]
                    m[c * 3 + k] = m[p * 3 + k]
                    m[p * 3 + k] = t
                }
                val t = v[c]
                v[c] = v[p]
                v[p] = t
            }
            for (r in c + 1 until 3) {
                val f = m[r * 3 + c] / m[c * 3 + c]
                for (k in c until 3) m[r * 3 + k] -= f * m[c * 3 + k]
                v[r] -= f * v[c]
            }
        }
        val x = DoubleArray(3)
        for (r in 2 downTo 0) {
            var s = v[r]
            for (k in r + 1 until 3) s -= m[r * 3 + k] * x[k]
            x[r] = s / m[r * 3 + r]
        }
        return x
    }
}
