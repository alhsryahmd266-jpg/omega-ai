package com.omega.sight.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * "Far mode": finds very small and slow things that move on their own, at the full resolution of the capture. MotionDetector works on a
 * small picture where something far away is less than one pixel and fades into the average; this class keeps the sharp one.
 *
 * How it keeps false alarms down while the threshold is that low:
 *  - the camera movement is measured again at this resolution (a finer GlobalMotion, started from the coarse answer), so the old frames
 *    can be laid over the new one to a fraction of a pixel;
 *  - a pixel counts only when the NEW frame differs from two older frames (1 and 3 frames back) by clearly more than the noise, with the
 *    same sign. Noise has to hit twice in the same place; a mover is new in both comparisons;
 *  - next to a sharp edge the threshold rises, because a tiny leftover error in the alignment shows up there first;
 *  - the Tracker still asks for three sightings in a row before anything is aimed at.
 * A single pixel is at the noise floor of any screen capture, so this can only report one when it differs strongly from what is
 * around it; it cannot be promised.
 */
class FarMotion(val w: Int, val h: Int) {
    private val ring = Array(R) { Gray(w, h) }
    private var cur = -1
    private var stored = 0
    private val gm = GlobalMotion(w, h)

    // how the picture moved from k frames ago to the newest frame: shift (tx, ty) and zoom s
    private val mt = FloatArray(R)
    private val mty = FloatArray(R)
    private val ms = FloatArray(R)
    private var valid = 0

    private val dA = ByteArray(w * h)
    private val dB = ByteArray(w * h)

    // the older frames laid over the newest one
    private val wA = ByteArray(w * h)
    private val wB = ByteArray(w * h)
    private val mask = ByteArray(w * h)
    private val str = ByteArray(w * h)
    private val tmp = ByteArray(w * h)
    private val stack = IntArray(w * h)
    private val hist = IntArray(256)

    /** the camera movement measured at this resolution in the last call */
    var egoFine: Ego = Ego.none(w, h)
        private set

    /** noise level (luma steps) and flagged pixels of the last call, for the diagnostics screen */
    var lastSigma = 0f
        private set
    var lastFlagged = 0
        private set

    fun reset() {
        cur = -1
        stored = 0
        valid = 0
        gm.reset()
    }

    /**
     * @param frame the newest luma frame at full capture size (copied, the caller may reuse it)
     * @param hint what the coarse GlobalMotion measured, and the size ratio fine/coarse
     * @param hud optional full-size mask of fixed screen furniture
     * @param roi optional [x0, y0, x1, y1) region to search in (fine pixels), null = everything
     * @param sensitivity 0..1
     * @param minArea the smallest blob to report, in pixels (1 = even a single pixel)
     * @return detections in pixels of this frame size
     */
    fun process(frame: Gray, hint: Ego?, hintScale: Float, hud: ByteArray?, roi: IntArray?, sensitivity: Float, minArea: Int): List<Det> {
        val prevIdx = cur
        cur = (cur + 1) % R
        System.arraycopy(frame.d, 0, ring[cur].d, 0, w * h)
        if (stored < R) stored++
        lastFlagged = 0
        if (prevIdx < 0) return emptyList()

        if (hint != null && hint.valid) gm.hint(hint.tx * hintScale, hint.ty * hintScale)
        val ego = gm.estimate(ring[prevIdx], ring[cur], hud)
        egoFine = ego
        if (!ego.valid) {
            valid = 0
            return emptyList()
        }
        for (k in R - 1 downTo 2) {
            val sPrev = ms[k - 1]
            mt[k] = (1f + ego.s) * mt[k - 1] + ego.tx
            mty[k] = (1f + ego.s) * mty[k - 1] + ego.ty
            ms[k] = (1f + sPrev) * (1f + ego.s) - 1f
        }
        mt[1] = ego.tx
        mty[1] = ego.ty
        ms[1] = ego.s
        valid = min(valid + 1, R - 1)
        val avail = min(valid, stored - 1)
        if (avail < 2) return emptyList()
        // a whipping camera blurs everything; nothing reliable then
        if (ego.speed > 0.10f * w || abs(ego.s) > 0.10f) return emptyList()

        val x0 = max(2, roi?.get(0) ?: 2)
        val y0 = max(2, roi?.get(1) ?: 2)
        val x1 = min(w - 2, roi?.get(2) ?: (w - 2))
        val y1 = min(h - 2, roi?.get(3) ?: (h - 2))
        if (x1 - x0 < 8 || y1 - y0 < 8) return emptyList()

        val kA = 1
        val kB = if (avail >= 3) 3 else 2
        val newest = ring[cur]
        // one pixel wider than the search area: the gradients below look at the neighbours
        warpDiff(newest, ring[(cur - kA + R) % R], mt[kA], mty[kA], ms[kA], dA, wA, x0 - 1, y0 - 1, x1 + 1, y1 + 1)
        warpDiff(newest, ring[(cur - kB + R) % R], mt[kB], mty[kB], ms[kB], dB, wB, x0 - 1, y0 - 1, x1 + 1, y1 + 1)

        val sigA = noise(dA, x0, y0, x1, y1)
        val sigB = noise(dB, x0, y0, x1, y1)
        lastSigma = 0.5f * (sigA + sigB)
        val sens = sensitivity.coerceIn(0f, 1f)
        val kS = 6.5f + (4.0f - 6.5f) * sens
        val absMin = 16f + (9f - 16f) * sens
        val baseA = max(absMin, kS * sigA)
        val baseB = max(absMin, kS * sigB)
        val delta = 0.15f + 0.35f * min(1f, ego.mad)
        val deltaA = delta
        val deltaB = delta * (1f + 0.4f * (kB - 1))
        // next to a sharp edge a tiny alignment error shows up first, so the threshold rises there; the gradient counted is the smaller of
        // the newest frame's and the old frame's, so the leading edge of a mover (new in the newest frame only) does not raise it
        val kgA = 0.8f
        val kgB = 2f

        // pixels that are new in the newest frame against both older ones
        java.util.Arrays.fill(mask, 0)
        val c = newest.d
        var flagged = 0
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                val i = row + x
                val a = dA[i].toInt()
                val b = dB[i].toInt()
                val aa = if (a < 0) -a else a
                val ab = if (b < 0) -b else b
                if (aa <= baseA || ab <= baseB) continue
                if ((a xor b) < 0) continue
                if (hud != null && hud[i].toInt() != 0) continue
                val gC = grad(c, i)
                val tA = baseA + kgA * min(gC, grad(wA, i)) * deltaA
                val tB = baseB + kgB * min(gC, grad(wB, i)) * deltaB
                if (aa > tA && ab > tB) {
                    mask[i] = 1
                    str[i] = (min(aa / tA, ab / tB) * 32f).coerceIn(1f, 255f).toInt().toByte()
                    flagged++
                }
            }
        }
        lastFlagged = flagged
        val roiArea = (x1 - x0) * (y1 - y0)
        // most of the picture "new": the alignment failed or the scene cut; nothing to report
        if (flagged == 0 || flagged > 0.02f * roiArea) return emptyList()

        // join the pieces of one body: grow by one pixel, then label the 8-connected groups
        java.util.Arrays.fill(tmp, 0)
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                if (mask[row + x].toInt() == 0) continue
                for (j in -1..1) for (k in -1..1) tmp[row + x + j * w + k] = 1
            }
        }
        val maxArea = (0.003f * w * h).toInt()
        val out = ArrayList<Det>()
        for (y in y0 - 1 until y1 + 1) {
            for (x in x0 - 1 until x1 + 1) {
                val seed = y * w + x
                if (tmp[seed].toInt() != 1) continue
                var sp = 0
                stack[sp++] = seed
                tmp[seed] = 2
                var minX = Int.MAX_VALUE
                var maxX = -1
                var minY = Int.MAX_VALUE
                var maxY = -1
                var area = 0
                var sw = 0f
                var sxw = 0f
                var syw = 0f
                while (sp > 0) {
                    val i = stack[--sp]
                    val py = i / w
                    val px = i - py * w
                    if (mask[i].toInt() != 0) {
                        area++
                        val wgt = (str[i].toInt() and 255).toFloat()
                        sw += wgt
                        sxw += wgt * px
                        syw += wgt * py
                        if (px < minX) minX = px
                        if (px > maxX) maxX = px
                        if (py < minY) minY = py
                        if (py > maxY) maxY = py
                    }
                    for (j in -1..1) for (k in -1..1) {
                        val nx = px + k
                        val ny = py + j
                        if (nx < 1 || ny < 1 || nx >= w - 1 || ny >= h - 1) continue
                        val ni = ny * w + nx
                        if (tmp[ni].toInt() == 1) {
                            tmp[ni] = 2
                            stack[sp++] = ni
                        }
                    }
                }
                if (area < minArea || area > maxArea || sw <= 0f) continue
                val bw = (maxX - minX + 1).toFloat()
                val bh = (maxY - minY + 1).toFloat()
                val cx = sxw / sw + 0.5f
                val cy = syw / sw + 0.5f
                val meanS = (sw / area) / 32f
                // a lone pixel with no support around it has to stand out clearly; noise tails only just clear the threshold
                if (area == 1 && meanS < 1.3f) continue
                val score = (0.25f + 0.30f * min(1f, area / 6f) + 0.45f * min(1f, (meanS - 1f) / 2f)).coerceIn(0.2f, 1f)
                out.add(Det(Box.ofCenter(cx, cy, max(1f, bw), max(1f, bh)), score, Kind.MOTION))
            }
        }
        out.sortByDescending { it.score }
        return if (out.size > 24) out.subList(0, 24).toList() else out
    }

    /** newest minus the older frame laid over it (the old frame shifted by what the camera did); signed, clipped to +-127 */
    private fun warpDiff(newest: Gray, older: Gray, tx: Float, ty: Float, s: Float, out: ByteArray, warped: ByteArray, x0: Int, y0: Int, x1: Int, y1: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val inv = 1f / (1f + s)
        val c = newest.d
        val p = older.d
        var ya = y0
        while (ya < y1) {
            val yb = min(y1, ya + T)
            var xa = x0
            while (xa < x1) {
                val xb = min(x1, xa + T)
                // where the content of this tile was in the older frame, measured at the tile's middle
                val xm = (xa + xb) * 0.5f
                val ym = (ya + yb) * 0.5f
                val ox = (cx + (xm - cx - tx) * inv) - xm
                val oy = (cy + (ym - cy - ty) * inv) - ym
                val ix = floor(ox).toInt()
                val iy = floor(oy).toInt()
                val fxq = ((ox - ix) * 256f).toInt().coerceIn(0, 256)
                val fyq = ((oy - iy) * 256f).toInt().coerceIn(0, 256)
                val w00 = (256 - fxq) * (256 - fyq)
                val w01 = fxq * (256 - fyq)
                val w10 = (256 - fxq) * fyq
                val w11 = fxq * fyq
                val inside = xa + ix >= 0 && xb + ix + 1 < w && ya + iy >= 0 && yb + iy + 1 < h
                for (y in ya until yb) {
                    val row = y * w
                    for (x in xa until xb) {
                        val i = row + x
                        val pv: Int = if (inside) {
                            val q = (y + iy) * w + x + ix
                            ((p[q].toInt() and 255) * w00 + (p[q + 1].toInt() and 255) * w01 +
                                (p[q + w].toInt() and 255) * w10 + (p[q + w + 1].toInt() and 255) * w11 + 32768) shr 16
                        } else {
                            (older.at(x + ix, y + iy) * w00 + older.at(x + ix + 1, y + iy) * w01 +
                                older.at(x + ix, y + iy + 1) * w10 + older.at(x + ix + 1, y + iy + 1) * w11 + 32768) shr 16
                        }
                        warped[i] = pv.toByte()
                        val d = (c[i].toInt() and 255) - pv
                        out[i] = (if (d > 127) 127 else if (d < -127) -127 else d).toByte()
                    }
                }
                xa = xb
            }
            ya = yb
        }
    }

    /** how sharp the picture is at pixel [i]: average step to the neighbours on both axes */
    private fun grad(d: ByteArray, i: Int): Float {
        val gx = abs((d[i + 1].toInt() and 255) - (d[i - 1].toInt() and 255))
        val gy = abs((d[i + w].toInt() and 255) - (d[i - w].toInt() and 255))
        return (gx + gy) * 0.5f
    }

    /** the noise of a difference image: 1.4826 x the median of the absolute values */
    private fun noise(d: ByteArray, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        java.util.Arrays.fill(hist, 0)
        var n = 0
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                val v = d[row + x].toInt()
                hist[if (v < 0) -v else v]++
                n++
            }
        }
        var acc = 0
        var m = 0
        while (m < 127) {
            acc += hist[m]
            if (acc * 2 >= n) break
            m++
        }
        return max(1f, 1.4826f * m)
    }

    companion object {
        /** the newest frame and three older ones */
        private const val R = 4

        /** the alignment is worked out per tile of this many pixels */
        private const val T = 16
    }
}
