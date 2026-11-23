package com.ahmedxg.gamebot

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A round HUD element: center as a fraction of the width/height, radius as a fraction of the HEIGHT. */
class Circle(val cx: Float, val cy: Float, val r: Float, val votes: Int)

/**
 * Finds the game's on-screen buttons without any model. While the camera moves, the 3D scene changes
 * every frame but the HUD does not: in the temporal mean image only the HUD keeps sharp outlines. A circle
 * Hough transform on those outlines gives the round buttons (move stick, fire, jump, ...). The functions
 * are then guessed from the usual touch-shooter layout (stick bottom-left, fire biggest on the right...).
 * It is a SUGGESTION: check it once in the layout editor. Pure Kotlin (testable on a plain JVM).
 */
object LayoutDetect {
    class Result(val circles: List<Circle>, val points: Map<String, FloatArray>, val note: String)

    /** @param frames luma grids (gw*gh) sampled over time while the camera was moving */
    fun detect(frames: List<IntArray>, gw: Int, gh: Int): Result {
        if (frames.size < 8) return Result(emptyList(), emptyMap(), "عدد اللقطات قليل")
        val n = gw * gh
        val energy = FloatArray(n)
        val mean = FloatArray(n)
        for (t in frames.indices) {
            val f = frames[t]
            for (i in 0 until n) {
                mean[i] += f[i].toFloat()
                if (t > 0) energy[i] += abs(f[i] - frames[t - 1][i]).toFloat()
            }
        }
        var global = 0f
        for (i in 0 until n) {
            mean[i] = mean[i] / frames.size
            energy[i] = energy[i] / (frames.size - 1)
            global += energy[i]
        }
        global /= n
        if (global < 2.0f) return Result(emptyList(), emptyMap(), "الكاميرا ما اتحركتش كفاية (الشاشة ثابتة)")

        // edges of the TEMPORAL MEAN image: while the camera moves, the 3D scene averages out into a smooth blur,
        // but HUD outlines (opaque or translucent) keep a consistent contrast and stay sharp
        val calmThr = 1.3f * global
        val edge = BooleanArray(n)
        var edgeCount = 0
        for (y in 1 until gh - 1) {
            for (x in 1 until gw - 1) {
                val i = y * gw + x
                if (energy[i] > calmThr) continue
                val gx = -mean[i - gw - 1] - 2 * mean[i - 1] - mean[i + gw - 1] + mean[i - gw + 1] + 2 * mean[i + 1] + mean[i + gw + 1]
                val gy = -mean[i - gw - 1] - 2 * mean[i - gw] - mean[i - gw + 1] + mean[i + gw - 1] + 2 * mean[i + gw] + mean[i + gw + 1]
                if (sqrt(gx * gx + gy * gy) > 60f) {
                    edge[i] = true
                    edgeCount++
                }
            }
        }
        if (edgeCount < 40) return Result(emptyList(), emptyMap(), "مالقيتش عناصر HUD ثابتة")

        val circles = hough(edge, gw, gh)
        return Result(circles, assign(circles), "لقيت ${circles.size} عنصر دايري")
    }

    private fun hough(edge: BooleanArray, gw: Int, gh: Int): List<Circle> {
        val rMin = max(4, (0.045f * gh).roundToInt())
        val rMax = max(rMin + 2, (0.28f * gh).roundToInt())
        val nr = rMax - rMin + 1
        val steps = 36
        val acc = IntArray(nr * gw * gh)
        val cs = FloatArray(steps) { cos(it * 2.0 * PI / steps).toFloat() }
        val sn = FloatArray(steps) { sin(it * 2.0 * PI / steps).toFloat() }
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                if (!edge[y * gw + x]) continue
                for (ri in 0 until nr) {
                    val r = rMin + ri
                    var lastCell = -1
                    for (s in 0 until steps) {
                        val cx = (x - r * cs[s]).roundToInt()
                        val cy = (y - r * sn[s]).roundToInt()
                        if (cx < 0 || cy < 0 || cx >= gw || cy >= gh) continue
                        val cell = ri * gw * gh + cy * gw + cx
                        if (cell != lastCell) {
                            acc[cell]++
                            lastCell = cell
                        }
                    }
                }
            }
        }
        // peaks: a ring needs a good share of its perimeter to be supported
        val found = ArrayList<Circle>()
        for (ri in 0 until nr) {
            val r = rMin + ri
            val need = max(14, (2.0 * PI * r * 0.45).toInt())
            for (cy in 0 until gh) {
                for (cx in 0 until gw) {
                    val v = acc[ri * gw * gh + cy * gw + cx]
                    if (v >= need) found.add(Circle(cx.toFloat() / gw, cy.toFloat() / gh, r.toFloat() / gh, v))
                }
            }
        }
        // strongest first (votes relative to the perimeter), drop circles that overlap a stronger one
        found.sortByDescending { it.votes / (2f * PI.toFloat() * it.r * gh) }
        val keep = ArrayList<Circle>()
        for (c in found) {
            var dup = false
            for (k in keep) {
                val dx = (c.cx - k.cx) * gw
                val dy = (c.cy - k.cy) * gh
                if (sqrt(dx * dx + dy * dy) < 0.8f * max(c.r, k.r) * gh) { dup = true; break }
            }
            if (!dup) keep.add(c)
            if (keep.size >= 80) break
        }
        return keep
    }

    /** Guess which circle is which, from the usual touch-shooter layout. */
    fun assign(all: List<Circle>): Map<String, FloatArray> {
        val out = LinkedHashMap<String, FloatArray>()
        val circles = all.filter { it.r >= 0.055f }
        val left = circles.filter { it.cx < 0.40f && it.cy > 0.55f }.sortedByDescending { it.r }
        if (left.isNotEmpty()) out["stick"] = floatArrayOf(left[0].cx, left[0].cy, left[0].r)
        val right = circles.filter { it.cx > 0.58f && it.cy > 0.20f && it.cy < 0.92f }.sortedByDescending { it.r }
        if (right.isNotEmpty()) {
            out["fire"] = floatArrayOf(right[0].cx, right[0].cy, right[0].r)
            val rest = right.drop(1).filter { it.cx > 0.70f }
            // jump: the next big one lower-right; crouch: the one below/left of it; reload: the remaining one
            val sorted = rest.sortedByDescending { it.cy }
            if (rest.isNotEmpty()) {
                val jump = rest.maxByOrNull { it.r * 2f + it.cx } ?: rest[0]
                out["jump"] = floatArrayOf(jump.cx, jump.cy, jump.r)
                val others = rest.filter { it !== jump }
                if (others.isNotEmpty()) {
                    val crouch = others.maxByOrNull { it.cy } ?: others[0]
                    out["crouch"] = floatArrayOf(crouch.cx, crouch.cy, crouch.r)
                    val third = others.filter { it !== crouch }
                    if (third.isNotEmpty()) out["reload"] = floatArrayOf(third[0].cx, third[0].cy, third[0].r)
                }
            }
            if (sorted.isEmpty()) out.remove("jump")
        }
        return out
    }

    /** Luma thumbnail helper shared by the app and the tests. */
    fun lumaOf(argb: Int): Int = (((argb shr 16) and 255) * 3 + ((argb shr 8) and 255) * 6 + (argb and 255)) / 10

    fun gridHeight(gw: Int, w: Int, h: Int): Int = min(max(8, gw * h / max(1, w)), 200)
}
