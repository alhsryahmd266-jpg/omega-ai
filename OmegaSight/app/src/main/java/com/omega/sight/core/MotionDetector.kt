package com.omega.sight.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds things that move on their own, with no model at all. The previous frame is moved by the camera movement (GlobalMotion) and
 * subtracted from the current one: what is left is not the camera, it is something moving in the world. Neighbouring leftovers are
 * joined into blobs. A frame taken while the camera whips round is skipped (nothing is reliable then).
 */
class MotionDetector(val w: Int, val h: Int) {
    private companion object {
        /**
         * how far (pixels of this picture) the shifted previous picture may be off before a change counts, while the camera moves.
         * Reading a sampled sharp edge at a fractional place is itself off by up to a quarter of its height, so a smaller number lets the
         * edges of lit windows through; the price is that, while the camera turns, something that moves against the world by much less
         * than a pixel per frame is not seen here (the far mode and the glow detector still see it, and a still camera sees all of it).
         */
        const val TOL = 0.25f

        /** with a moving camera, more leftover blobs than this in one frame are treated as edges, not movers */
        const val FLOOD = 8
    }

    private val diff = ByteArray(w * h)
    private val mask = ByteArray(w * h)
    private val tmp = ByteArray(w * h)
    private val stack = IntArray(w * h)
    private val hist = IntArray(256)

    /** the luma step a pixel had to change by to count as moving in the last frame (for the diagnostics screen) */
    var lastThreshold = 0f
        private set

    /** how much of the frame moved in the last call (0..1) */
    var lastMovingShare = 0f
        private set

    /**
     * @param sensitivity 0..1 (1 = reacts to small changes)
     * @param minBlob 0..1 (0 = tiny far movers count)
     * @param hud optional full-size mask of the fixed buttons / bars, never counted as movement
     */
    fun detect(prev: Gray, cur: Gray, ego: Ego, hud: ByteArray?, sensitivity: Float, minBlob: Float): List<Det> {
        lastMovingShare = 0f
        if (ego.valid && (ego.speed > 0.12f * w || abs(ego.s) > 0.12f)) return emptyList()

        // 1. current minus the previous frame moved along with the camera
        java.util.Arrays.fill(hist, 0)
        val cx = ego.cx
        val cy = ego.cy
        val still = !ego.valid || (abs(ego.tx) < 0.03f && abs(ego.ty) < 0.03f && abs(ego.s) < 0.0005f)
        // The camera shift is measured to a fraction of a pixel, and a sharp edge (a lit window on a dark wall) that is off by a tenth of
        // a pixel changes by many luma steps: it looks like movement all over the picture while the camera turns. So with a moving camera
        // the previous picture is read at the shifted place and a little around it (+-TOL pixel), and a pixel only counts as changed when
        // it falls outside the range found there. A still camera has no such error and keeps the full sensitivity.
        val tol = if (still) 0f else TOL
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val c = cur.d[row + x].toInt() and 255
                val d: Int
                if (still) {
                    d = min(255, abs(c - (prev.d[row + x].toInt() and 255)))
                } else {
                    val px = x - (ego.tx + ego.s * (x - cx))
                    val py = y - (ego.ty + ego.s * (y - cy))
                    if (px < tol || py < tol || px > w - 1f - tol || py > h - 1f - tol) {
                        // the strip that the turning camera just brought in at the edge: the previous picture has nothing to compare with
                        d = 0
                    } else if (hud != null && hud[(py + 0.5f).toInt() * w + (px + 0.5f).toInt()].toInt() != 0) {
                        // the world that was behind a fixed button a moment ago and has come out from under it: nothing to compare with either
                        d = 0
                    } else {
                        var lo = Float.MAX_VALUE
                        var hi = -Float.MAX_VALUE
                        for (j in -1..1) {
                            for (k in -1..1) {
                                val v = prev.sample(px + k * tol, py + j * tol)
                                if (v < lo) lo = v
                                if (v > hi) hi = v
                            }
                        }
                        val gap = if (c > hi) c - hi else if (c < lo) lo - c else 0f
                        d = min(255, gap.roundToInt())
                    }
                }
                diff[row + x] = d.toByte()
                hist[d]++
            }
        }

        // 2. the noise level of this frame and the threshold above it
        val half = w * h / 2
        var acc = 0
        var median = 0
        while (median < 255) {
            acc += hist[median]
            if (acc >= half) break
            median++
        }
        val sigma = max(1.2f, 1.4826f * median)
        val sens = sensitivity.coerceIn(0f, 1f)
        val kSigma = 7f + (3f - 7f) * sens
        val absMin = 14f + (7f - 14f) * sens
        val thr = max(absMin, kSigma * sigma) * (1f + min(0.8f, ego.speed * 0.12f))
        lastThreshold = thr

        // 3. binary mask, without the fixed screen furniture and the frame border (where the shift has nothing to read)
        java.util.Arrays.fill(mask, 0)
        var moving = 0
        for (y in 2 until h - 2) {
            val row = y * w
            for (x in 2 until w - 2) {
                val i = row + x
                if ((diff[i].toInt() and 255) > thr && (hud == null || hud[i].toInt() == 0)) {
                    mask[i] = 1
                    moving++
                }
            }
        }
        lastMovingShare = moving / (w * h).toFloat()
        // when most of the picture "moves" the shift estimate failed: nothing to report
        if (lastMovingShare > 0.35f) return emptyList()

        // 4. lone pixels out, the rest grown by one pixel so the parts of one body join up
        java.util.Arrays.fill(tmp, 0)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                if (mask[i].toInt() == 0) continue
                var n = 0
                for (j in -1..1) for (k in -1..1) if ((j != 0 || k != 0) && mask[i + j * w + k].toInt() != 0) n++
                if (n >= 2) tmp[i] = 1
            }
        }
        java.util.Arrays.fill(mask, 0)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                if (tmp[i].toInt() == 0) continue
                for (j in -1..1) for (k in -1..1) mask[i + j * w + k] = 1
            }
        }

        // 5. blobs (8-connected), each with its box, size and how hard it changed
        val minArea = 9f + 45f * minBlob.coerceIn(0f, 1f)
        val maxArea = 0.30f * w * h
        val out = ArrayList<Det>()
        java.util.Arrays.fill(tmp, 0)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val seed = y * w + x
                if (mask[seed].toInt() == 0 || tmp[seed].toInt() != 0) continue
                var sp = 0
                stack[sp++] = seed
                tmp[seed] = 1
                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                var area = 0
                var sum = 0L
                while (sp > 0) {
                    val i = stack[--sp]
                    val py = i / w
                    val px = i - py * w
                    area++
                    sum += diff[i].toInt() and 255
                    if (px < minX) minX = px
                    if (px > maxX) maxX = px
                    if (py < minY) minY = py
                    if (py > maxY) maxY = py
                    for (j in -1..1) for (k in -1..1) {
                        val nx = px + k
                        val ny = py + j
                        if (nx < 1 || ny < 1 || nx >= w - 1 || ny >= h - 1) continue
                        val ni = ny * w + nx
                        if (mask[ni].toInt() != 0 && tmp[ni].toInt() == 0) {
                            tmp[ni] = 1
                            stack[sp++] = ni
                        }
                    }
                }
                if (area < minArea || area > maxArea) continue
                // the one-pixel growth of step 4 is taken off again
                val box = Box(minX + 1f, minY + 1f, maxX.toFloat(), maxY.toFloat())
                if (box.w < 2f || box.h < 2f) continue
                val aspect = box.w / box.h
                if (aspect < 0.12f || aspect > 6f) continue
                val meanDiff = sum / area.toFloat()
                val score = (0.3f + 0.4f * min(1f, area / (5f * minArea)) + 0.3f * min(1f, meanDiff / (3f * thr))).coerceIn(0f, 1f)
                out.add(Det(box, score, Kind.MOTION))
            }
        }
        // A turning camera in front of a picture full of sharp, regular edges (a wall of lit windows, a fence, a grid of tiles) leaves a
        // crowd of small leftovers that are really the edges, not movers. Real movers are a few; a crowd means the leftovers cannot be
        // trusted, and aiming at one of them would turn the camera more and so make more of them. So report nothing from such a frame.
        if (!still && out.size > FLOOD) return emptyList()
        out.sortByDescending { it.score }
        return if (out.size > 16) out.subList(0, 16).toList() else out
    }
}
