package com.tacticai.bot.detect

import android.graphics.Bitmap
import android.graphics.Rect
import com.tacticai.bot.Detection
import kotlin.math.max
import kotlin.math.min

/**
 * Finds button-LIKE regions in a screenshot without needing a trained model or hand-placed coordinates,
 * so it keeps working if the game's HUD moves, resizes, or is rearranged (e.g. "left-handed" layout).
 *
 * How: most mobile-game buttons are compact, high-contrast icons on a comparatively flat background.
 * This downsamples the frame to a coarse grid, scores each cell by local edge density (how much
 * brightness changes between neighbouring pixels), keeps the cells that stand out from their
 * surroundings, and merges touching "busy" cells into bounding boxes.
 *
 * This is a genuinely automatic FIRST PASS — it finds "something icon-like is here", not WHICH button
 * it is (fire vs. jump vs. reload). Once real gameplay screenshots are available, each detection's
 * [Detection.label] can be filled in by template-matching against small reference crops saved from
 * those screenshots (see README) — the region-finding logic below does not need to change for that.
 */
object ButtonFinder {

    private const val GRID_COLS = 36
    private const val GRID_ROWS = 64
    private const val ANALYSIS_WIDTH = 360 // downsample target; keeps the per-frame cost small enough for a tight loop

    fun find(frame: Bitmap): List<Detection> {
        val scale = ANALYSIS_WIDTH.toFloat() / frame.width
        val w = ANALYSIS_WIDTH
        val h = max(1, (frame.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(frame, w, h, true)
        val gray = IntArray(w * h)
        small.getPixels(gray, 0, w, 0, 0, w, h)
        for (i in gray.indices) gray[i] = luma(gray[i])
        if (small !== frame) small.recycle()

        val cellW = max(1, w / GRID_COLS)
        val cellH = max(1, h / GRID_ROWS)
        val cols = (w + cellW - 1) / cellW
        val rows = (h + cellH - 1) / cellH
        val density = FloatArray(cols * rows)

        for (cy in 0 until rows) {
            for (cx in 0 until cols) {
                density[cy * cols + cx] = edgeDensity(gray, w, h, cx * cellW, cy * cellH, cellW, cellH)
            }
        }

        val mean = density.average().toFloat()
        val variance = density.fold(0f) { acc, v -> acc + (v - mean) * (v - mean) } / density.size
        val threshold = mean + max(0.015f, kotlin.math.sqrt(variance.toDouble()).toFloat() * 0.8f)
        val active = BooleanArray(cols * rows) { density[it] > threshold }

        val boxes = connectedComponents(active, cols, rows)
        val invScale = 1f / scale
        val results = mutableListOf<Detection>()
        for ((gx0, gy0, gx1, gy1, score) in boxes) {
            val left = (gx0 * cellW * invScale).toInt()
            val top = (gy0 * cellH * invScale).toInt()
            val right = min(frame.width, ((gx1 + 1) * cellW * invScale).toInt())
            val bottom = min(frame.height, ((gy1 + 1) * cellH * invScale).toInt())
            val bw = right - left
            val bh = bottom - top
            if (bw < MIN_PX || bh < MIN_PX) continue
            if (bw > frame.width * MAX_FRACTION || bh > frame.height * MAX_FRACTION) continue
            val aspect = bw.toFloat() / bh
            if (aspect > 4.5f || aspect < 1f / 4.5f) continue // long thin strips are usually text/ammo bars, not single buttons

            val rect = Rect(left, top, right, bottom)
            results += Detection(
                box = rect,
                confidence = min(1f, score),
                likelyControl = looksLikeControlZone(rect, frame.width, frame.height),
            )
        }
        return results.sortedByDescending { it.confidence }.take(MAX_RESULTS)
    }

    /** Average absolute difference between each pixel and its right/below neighbour, normalised to 0..1. */
    private fun edgeDensity(gray: IntArray, w: Int, h: Int, x0: Int, y0: Int, cw: Int, ch: Int): Float {
        var sum = 0L
        var n = 0
        val xEnd = min(w - 1, x0 + cw)
        val yEnd = min(h - 1, y0 + ch)
        var y = y0
        while (y < yEnd) {
            var x = x0
            val rowBase = y * w
            while (x < xEnd) {
                val p = gray[rowBase + x]
                sum += kotlin.math.abs(p - gray[rowBase + x + 1])
                sum += kotlin.math.abs(p - gray[rowBase + w + x])
                n += 2
                x++
            }
            y++
        }
        return if (n == 0) 0f else (sum.toFloat() / n) / 255f
    }

    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 77 + g * 151 + b * 28) shr 8
    }

    private data class Box(val gx0: Int, val gy0: Int, val gx1: Int, val gy1: Int, val score: Float)

    /** 4-connectivity flood fill over the coarse grid; returns one bounding box per connected cluster. */
    private fun connectedComponents(active: BooleanArray, cols: Int, rows: Int): List<Box> {
        val visited = BooleanArray(active.size)
        val out = mutableListOf<Box>()
        val stack = ArrayDeque<Int>()
        for (start in active.indices) {
            if (!active[start] || visited[start]) continue
            stack.clear()
            stack.addLast(start)
            visited[start] = true
            var gx0 = Int.MAX_VALUE; var gy0 = Int.MAX_VALUE; var gx1 = Int.MIN_VALUE; var gy1 = Int.MIN_VALUE
            var count = 0
            while (stack.isNotEmpty()) {
                val idx = stack.removeLast()
                val cx = idx % cols
                val cy = idx / cols
                gx0 = min(gx0, cx); gy0 = min(gy0, cy); gx1 = max(gx1, cx); gy1 = max(gy1, cy)
                count++
                val neighbours = intArrayOf(idx - 1, idx + 1, idx - cols, idx + cols)
                for (nb in neighbours) {
                    if (nb < 0 || nb >= active.size) continue
                    if (nb % cols == 0 && idx % cols == cols - 1) continue // wrap guard (idx+1 at row end)
                    if (idx % cols == 0 && nb % cols == cols - 1) continue // wrap guard (idx-1 at row start)
                    if (active[nb] && !visited[nb]) { visited[nb] = true; stack.addLast(nb) }
                }
            }
            // normalise cluster size to a 0..1-ish score: a handful of cells is a typical single icon.
            out += Box(gx0, gy0, gx1, gy1, min(1f, count / 6f))
        }
        return out
    }

    /** Bottom ~45% of the screen, or the outer ~22% left/right columns — where touch controls live in almost every mobile shooter. */
    private fun looksLikeControlZone(box: Rect, screenW: Int, screenH: Int): Boolean {
        val bottomZone = box.top > screenH * 0.55f
        val edgeZone = box.centerX() < screenW * 0.22f || box.centerX() > screenW * 0.78f
        return bottomZone || (edgeZone && box.top > screenH * 0.25f)
    }

    private const val MIN_PX = 18
    private const val MAX_FRACTION = 0.16f
    private const val MAX_RESULTS = 40
}
