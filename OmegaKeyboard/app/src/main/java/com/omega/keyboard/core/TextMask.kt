package com.omega.keyboard.core

import kotlin.math.max
import kotlin.math.min

/**
 * Makes letters of any colour readable on any background, for the picture reader: every pixel is compared with the average of its
 * neighbourhood, and what stands out from it (brighter on a dark chat panel, darker on a light one) is drawn black on white. The colour
 * of the letters stops mattering, and so does a busy background (it is smooth compared with letters).
 *
 * Nothing here knows Android: pixels are plain ARGB ints, so it can be tested on any machine.
 */
object TextMask {

    /** the value channel of a pixel: a pure blue or red letter is as bright as a white one here (luma would make it dark) */
    private fun value(p: Int): Int = max((p shr 16) and 0xFF, max((p shr 8) and 0xFF, p and 0xFF))

    /**
     * [argb]: [w] x [h] pixels. Returns a new array of opaque gray pixels, white where nothing stands out and darker the more a pixel
     * stands out from its surroundings. [lightText]: letters are brighter than their background (null = decided from the whole picture).
     */
    fun make(argb: IntArray, w: Int, h: Int, lightText: Boolean? = null): IntArray {
        require(w > 0 && h > 0 && argb.size >= w * h) { "bad picture size" }
        val n = w * h
        val v = IntArray(n) { value(argb[it]) }
        var total = 0L
        for (x in v) total += x
        val light = lightText ?: ((total / n) < 120L)

        // sums of the value channel over any rectangle, in a few operations each (an integral image)
        val iw = w + 1
        val integral = IntArray(iw * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                row += v[y * w + x]
                integral[(y + 1) * iw + (x + 1)] = integral[y * iw + (x + 1)] + row
            }
        }
        val r = max(6, min(w, h) / 36)
        val out = IntArray(n)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h - 1, y + r)
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                val area = (x1 - x0 + 1) * (y1 - y0 + 1)
                val sum = integral[(y1 + 1) * iw + (x1 + 1)] - integral[y0 * iw + (x1 + 1)] - integral[(y1 + 1) * iw + x0] + integral[y0 * iw + x0]
                val avg = sum / area
                val d = if (light) v[y * w + x] - avg else avg - v[y * w + x]
                val ink = ((d - 8) * 7).coerceIn(0, 255)
                val g = 255 - ink
                out[y * w + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        return out
    }
}
