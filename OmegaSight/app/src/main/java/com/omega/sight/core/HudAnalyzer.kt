package com.omega.sight.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Role { FIRE, STICK, OTHER }

/** A round on-screen control found by [HudAnalyzer], in the pixels of the analyzer's frame. [rings] > 1 = several circles around one centre. */
class ButtonCand(val cx: Float, val cy: Float, val r: Float, val score: Float, val rings: Int, val role: Role)

/**
 * Finds the game's own buttons and joystick without being told where they are, and keeps the "screen furniture" out of the motion
 * detection (the gun held in the player's hands, the ammo counter, a glowing fire button are not enemies).
 *
 * The idea: whatever is drawn on the glass stays put while the world moves. While the camera turns, most sharp edges of the picture
 * change from one frame to the next; the edges that do NOT change are the buttons. Votes are collected over many frames, and round
 * shapes are searched among the surviving edges (a Hough transform that lets every edge point vote for the centres it could belong to).
 * The first version of the controls the owner sees is only a guess, and he confirms it with a tap; nothing here taps or moves anything.
 */
class HudAnalyzer(val w: Int, val h: Int) {
    private val gx = ShortArray(w * h)
    private val gy = ShortArray(w * h)
    private val mag = ShortArray(w * h)
    private val edgeCnt = ShortArray(w * h)
    private val statCnt = ShortArray(w * h)
    private var pairs = 0
    private var cache: ByteArray? = null
    private var cacheAt = -1
    private var buttons: List<ButtonCand> = emptyList()

    /** how many frame pairs with a moving world have been counted so far */
    val pairsSeen: Int get() = pairs

    /** enough frames have been seen to trust the fixed-edge map */
    val ready: Boolean get() = pairs >= MIN_PAIRS

    fun reset() {
        java.util.Arrays.fill(edgeCnt, 0)
        java.util.Arrays.fill(statCnt, 0)
        pairs = 0
        cache = null
        cacheAt = -1
    }

    /** the buttons the owner confirmed (they are added to [mask] as discs) */
    fun setButtons(list: List<ButtonCand>) {
        buttons = list
        cache = null
        cacheAt = -1
    }

    /**
     * Counts one more frame pair. Only pairs where the world really moved count (most sharp edges changed).
     * @return true when this pair was counted
     */
    fun update(prev: Gray, cur: Gray): Boolean {
        gradient(cur, gx, gy, mag)
        var edges = 0
        var changed = 0
        val n = w * h
        for (i in 0 until n) {
            if (mag[i] >= EDGE_THR) edges++
            if (abs((cur.d[i].toInt() and 255) - (prev.d[i].toInt() and 255)) > 14) changed++
        }
        // the world moved when a fair share of ALL pixels changed (not of the edges: in a plain scene the buttons are most of the edges)
        if (edges < 150 || changed < 0.015f * n) return false
        for (i in 0 until n) {
            if (mag[i] < EDGE_THR) continue
            if (edgeCnt[i] < 30000) edgeCnt[i]++
            if (abs((cur.d[i].toInt() and 255) - (prev.d[i].toInt() and 255)) <= 6 && statCnt[i] < 30000) statCnt[i]++
        }
        pairs++
        return true
    }

    /** the sharp edges that stayed put while the world moved (1 = fixed to the glass) */
    fun fixedEdges(): ByteArray {
        val c = cache
        if (c != null && cacheAt == pairs) return c
        val out = ByteArray(w * h)
        if (pairs >= MIN_PAIRS) {
            val need = max(6, pairs / 2)
            for (i in 0 until w * h) {
                val e = edgeCnt[i].toInt()
                if (e >= need && statCnt[i] * 100 >= e * 85) out[i] = 1
            }
        }
        cache = out
        cacheAt = pairs
        return out
    }

    /**
     * The screen furniture as a mask of [outW] x [outH] (1 = fixed): the fixed edges grown by [grow] pixels of this analyzer, plus a disc
     * for every confirmed button.
     */
    fun maskFor(outW: Int, outH: Int, grow: Int): ByteArray {
        val m = fixedEdges().copyOf()
        for (b in buttons) {
            val r = b.r * 1.1f
            val y0 = max(0, (b.cy - r).toInt())
            val y1 = min(h - 1, (b.cy + r).toInt() + 1)
            val x0 = max(0, (b.cx - r).toInt())
            val x1 = min(w - 1, (b.cx + r).toInt() + 1)
            for (y in y0..y1) for (x in x0..x1) if (hypot(x - b.cx, y - b.cy) <= r) m[y * w + x] = 1
        }
        val g = grown(m, grow)
        if (outW == w && outH == h) return g
        val out = ByteArray(outW * outH)
        for (y in 0 until outH) {
            val sy = min(h - 1, y * h / outH)
            for (x in 0 until outW) out[y * outW + x] = g[sy * w + min(w - 1, x * w / outW)]
        }
        return out
    }

    /** box growth by [r] pixels, one direction after the other */
    private fun grown(src: ByteArray, r: Int): ByteArray {
        if (r <= 0) return src
        val tmp = ByteArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var last = -1000000
            // distance to the nearest set pixel on the left, then on the right
            val near = IntArray(w)
            for (x in 0 until w) {
                if (src[row + x].toInt() != 0) last = x
                near[x] = x - last
            }
            last = 1000000
            for (x in w - 1 downTo 0) {
                if (src[row + x].toInt() != 0) last = x
                if (last - x < near[x]) near[x] = last - x
                if (near[x] <= r) tmp[row + x] = 1
            }
        }
        val out = ByteArray(w * h)
        val near = IntArray(h)
        for (x in 0 until w) {
            var last = -1000000
            for (y in 0 until h) {
                if (tmp[y * w + x].toInt() != 0) last = y
                near[y] = y - last
            }
            last = 1000000
            for (y in h - 1 downTo 0) {
                if (tmp[y * w + x].toInt() != 0) last = y
                if (last - y < near[y]) near[y] = last - y
                if (near[y] <= r) out[y * w + x] = 1
            }
        }
        return out
    }

    /** round controls among the fixed edges (needs [ready]); best first */
    fun circles(): List<ButtonCand> {
        if (!ready) return emptyList()
        return assignRoles(hough(fixedEdges(), gx, gy))
    }

    /**
     * The same from one still picture: every round thing counts, so some of them are not buttons; the owner picks the right ones.
     * Used when no movement has been seen yet.
     */
    fun circlesOf(g: Gray): List<ButtonCand> {
        val lx = ShortArray(w * h)
        val ly = ShortArray(w * h)
        val lm = ShortArray(w * h)
        gradient(g, lx, ly, lm)
        val edge = ByteArray(w * h)
        for (i in 0 until w * h) if (lm[i] >= EDGE_THR) edge[i] = 1
        return assignRoles(hough(edge, lx, ly))
    }

    private fun gradient(g: Gray, ox: ShortArray, oy: ShortArray, om: ShortArray) {
        val d = g.d
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val a = d[i - w - 1].toInt() and 255
                val b = d[i - w].toInt() and 255
                val c = d[i - w + 1].toInt() and 255
                val dl = d[i - 1].toInt() and 255
                val dr = d[i + 1].toInt() and 255
                val e = d[i + w - 1].toInt() and 255
                val f = d[i + w].toInt() and 255
                val k = d[i + w + 1].toInt() and 255
                val sx = (c + 2 * dr + k) - (a + 2 * dl + e)
                val sy = (e + 2 * f + k) - (a + 2 * b + c)
                ox[i] = sx.toShort()
                oy[i] = sy.toShort()
                om[i] = ((abs(sx) + abs(sy)) / 4).toShort()
            }
        }
    }

    private fun hough(edge: ByteArray, ex: ShortArray, ey: ShortArray): List<ButtonCand> {
        val rmin = max(4, (0.03f * min(w, h)).toInt())
        val rmax = max(rmin + 2, (0.22f * min(w, h)).toInt())
        var ne = 0
        for (i in 0 until w * h) if (edge[i].toInt() != 0) ne++
        if (ne < 20) return emptyList()
        val step = if (ne > 14000) 2 else 1
        val idx = IntArray(ne)
        var m = 0
        var skip = 0
        for (i in 0 until w * h) {
            if (edge[i].toInt() == 0) continue
            if (skip++ % step != 0) continue
            idx[m++] = i
        }
        val acc = IntArray(w * h)
        for (q in 0 until m) {
            val i = idx[q]
            val ux = ex[i].toFloat()
            val uy = ey[i].toFloat()
            val gm = sqrt(ux * ux + uy * uy)
            if (gm < 1f) continue
            val dx = ux / gm
            val dy = uy / gm
            val x = i % w
            val y = i / w
            for (r in rmin..rmax) {
                val xa = (x + r * dx + 0.5f).toInt()
                val ya = (y + r * dy + 0.5f).toInt()
                if (xa in 0 until w && ya in 0 until h) acc[ya * w + xa]++
                val xb = (x - r * dx + 0.5f).toInt()
                val yb = (y - r * dy + 0.5f).toInt()
                if (xb in 0 until w && yb in 0 until h) acc[yb * w + xb]++
            }
        }
        // peaks of the vote map
        val peaks = ArrayList<IntArray>()
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val v = acc[y * w + x]
                if (v < MIN_VOTES / step) continue
                var isMax = true
                loop@ for (j in -2..2) for (k in -2..2) {
                    if ((j != 0 || k != 0) && acc[(y + j) * w + x + k] > v) {
                        isMax = false
                        break@loop
                    }
                }
                if (isMax) peaks.add(intArrayOf(x, y, v))
            }
        }
        peaks.sortByDescending { it[2] }
        val found = ArrayList<ButtonCand>()
        val hist = IntArray(rmax + 4)
        val sum3 = IntArray(rmax + 4)
        for (pk in peaks.take(40)) {
            val px = pk[0]
            val py = pk[1]
            java.util.Arrays.fill(hist, 0)
            for (q in 0 until m) {
                val i = idx[q]
                val x = i % w
                val y = i / w
                val dx = x - px
                val dy = y - py
                val d2 = dx * dx + dy * dy
                if (d2 < (rmin - 1) * (rmin - 1) || d2 > (rmax + 1) * (rmax + 1)) continue
                val gxv = ex[i].toFloat()
                val gyv = ey[i].toFloat()
                val gm = sqrt(gxv * gxv + gyv * gyv)
                if (gm < 1f) continue
                val d = sqrt(d2.toFloat())
                if (abs(gxv * dx + gyv * dy) / (gm * d) < 0.8f) continue
                hist[(d + 0.5f).toInt()]++
            }
            var best = -1
            var bestSup = 0
            for (r in rmin..rmax) {
                sum3[r] = hist[r - 1] + hist[r] + hist[r + 1]
                if (sum3[r] > bestSup) {
                    bestSup = sum3[r]
                    best = r
                }
            }
            if (best < 0 || bestSup * step < 12) continue
            val score = bestSup / (2f * Math.PI.toFloat() * best)
            if (score < 0.38f) continue
            // other strong rings round the same centre (a joystick has an outer ring and an inner knob)
            var outer = best
            var rings = 1
            for (r in rmin + 1 until rmax) {
                if (abs(r - best) < 3 || sum3[r] < 0.5f * bestSup || sum3[r] < sum3[r - 1] || sum3[r] < sum3[r + 1]) continue
                rings++
                if (r > outer) outer = r
            }
            val cand = ButtonCand(px.toFloat(), py.toFloat(), outer.toFloat(), min(1.2f, score), min(rings, 3), Role.OTHER)
            // the same button seen from another peak: keep one
            var dup = false
            for (f in found) {
                if (hypot(f.cx - cand.cx, f.cy - cand.cy) <= 0.35f * max(f.r, cand.r)) {
                    dup = true
                    break
                }
            }
            if (!dup) found.add(cand)
        }
        // the strongest first; a weaker circle whose centre lies inside a stronger one is an echo of its edges, not another button
        found.sortByDescending { it.score }
        val kept = ArrayList<ButtonCand>()
        for (c in found) {
            var inside = false
            for (k in kept) {
                if (hypot(k.cx - c.cx, k.cy - c.cy) < 0.9f * k.r) {
                    inside = true
                    break
                }
            }
            if (!inside) kept.add(c)
        }
        return kept
    }

    /** a guess at which circle is what: the joystick is on the left, the fire button is the strongest round control on the right */
    private fun assignRoles(list: List<ButtonCand>): List<ButtonCand> {
        if (list.isEmpty()) return list
        val left = list.filter { it.cx <= 0.5f * w && it.cy >= 0.35f * h }
        val right = list.filter { it.cx > 0.5f * w && it.cy >= 0.25f * h }
        val stick = left.maxByOrNull { it.score + 0.1f * it.rings + 0.01f * it.r }
        val fire = right.maxByOrNull { it.score * it.r }
        val out = list.map {
            ButtonCand(it.cx, it.cy, it.r, it.score, it.rings, if (it === fire) Role.FIRE else if (it === stick) Role.STICK else Role.OTHER)
        }
        return out.sortedWith(compareBy<ButtonCand> { if (it.role == Role.FIRE) 0 else if (it.role == Role.STICK) 1 else 2 }.thenByDescending { it.score })
    }

    /**
     * The biggest free rectangle on the right side of the screen that no confirmed button covers: where a finger can drag to turn the
     * camera. [l, t, r, b] in this analyzer's pixels.
     */
    fun suggestZone(confirmed: List<ButtonCand>): IntArray {
        val gw = 64
        val gh = 32
        val cw = w / gw.toFloat()
        val ch = h / gh.toFloat()
        val heights = IntArray(gw)
        val stIdx = IntArray(gw + 1)
        val stH = IntArray(gw + 1)
        var bestArea = 0
        var bl = 0
        var bt = 0
        var br = 0
        var bb = 0
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val px = (x + 0.5f) * cw
                val py = (y + 0.5f) * ch
                var ok = px >= 0.42f * w && py >= 0.06f * h && py <= 0.94f * h
                if (ok) for (b in confirmed) {
                    if (hypot(px - b.cx, py - b.cy) <= 1.25f * b.r + 0.02f * w) {
                        ok = false
                        break
                    }
                }
                heights[x] = if (ok) heights[x] + 1 else 0
            }
            var sp = 0
            for (x in 0..gw) {
                val hh = if (x < gw) heights[x] else 0
                var start = x
                while (sp > 0 && stH[sp - 1] >= hh) {
                    sp--
                    val area = stH[sp] * (x - stIdx[sp])
                    if (area > bestArea) {
                        bestArea = area
                        bl = stIdx[sp]
                        br = x
                        bt = y - stH[sp] + 1
                        bb = y + 1
                    }
                    start = stIdx[sp]
                }
                stIdx[sp] = start
                stH[sp] = hh
                sp++
            }
        }
        if (bestArea == 0) return intArrayOf((0.55f * w).toInt(), (0.15f * h).toInt(), (0.95f * w).toInt(), (0.85f * h).toInt())
        return intArrayOf((bl * cw).toInt(), (bt * ch).toInt(), (br * cw).toInt(), (bb * ch).toInt())
    }

    companion object {
        private const val EDGE_THR = 20
        private const val MIN_PAIRS = 8
        private const val MIN_VOTES = 14
    }
}
