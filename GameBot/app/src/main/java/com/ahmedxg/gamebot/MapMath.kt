package com.ahmedxg.gamebot

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/** A teammate marker on the minimap. angleDeg is clockwise from "up"; dist is a fraction of the half-size of the minimap. */
class MapMark(val angleDeg: Float, val dist: Float)

/** Pure helpers (no Android types) so they can be tested on a plain JVM. */
object MapMath {
    class Blob(val cx: Float, val cy: Float, val area: Int)

    fun blobs(mask: BooleanArray, rw: Int, rh: Int, minArea: Int, maxArea: Int): List<Blob> {
        val seen = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val out = ArrayList<Blob>()
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var sx = 0L
            var sy = 0L
            var n = 0
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % rw
                val y = p / rw
                sx += x
                sy += y
                n++
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue
                        val q = ny * rw + nx
                        if (mask[q] && !seen[q]) {
                            seen[q] = true
                            stack[sp++] = q
                        }
                    }
                }
            }
            if (n in minArea..maxArea) out.add(Blob(sx.toFloat() / n, sy.toFloat() / n, n))
        }
        return out
    }

    fun marks(blobs: List<Blob>, rw: Int, rh: Int): List<MapMark> {
        val cx = (rw - 1) / 2f
        val cy = (rh - 1) / 2f
        val half = max(rw, rh) / 2f
        val list = ArrayList<MapMark>()
        for (b in blobs) {
            val dx = b.cx - cx
            val dy = b.cy - cy
            val d = hypot(dx, dy) / half
            if (d < 0.08f) continue                      // the player's own icon in the middle
            list.add(MapMark(Math.toDegrees(atan2(dx, -dy).toDouble()).toFloat(), d))
        }
        return list
    }

    fun angleDiff(a: Float, b: Float): Float {
        var d = (a - b) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
}

