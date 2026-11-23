package com.ahmedxg.gamebot

import android.graphics.Bitmap

/**
 * "I got hit" detector: many games flash the screen edges red when you take damage. It compares the
 * average redness of the four screen borders with a slow baseline. Tune [sensitivity] for your game.
 */
class HitDetector(private val sensitivity: Int) {
    private var base = 0f
    private var primed = false
    private var hitUntil = 0L

    private fun red(b: Bitmap, x: Int, y: Int): Float {
        val p = b.getPixel(x.coerceIn(0, b.width - 1), y.coerceIn(0, b.height - 1))
        return (((p shr 16) and 255) - (((p shr 8) and 255) + (p and 255)) / 2).toFloat()
    }

    fun update(bmp: Bitmap, w: Int, h: Int, now: Long): Boolean {
        val ix = (w * 0.03f).toInt().coerceAtLeast(2)
        val iy = (h * 0.04f).toInt().coerceAtLeast(2)
        var sum = 0f
        var n = 0
        for (i in 1..10) {
            val x = w * i / 11
            val y = h * i / 11
            sum += red(bmp, x, iy) + red(bmp, x, h - 1 - iy) + red(bmp, ix, y) + red(bmp, w - 1 - ix, y)
            n += 4
        }
        val m = sum / n
        if (!primed) {
            base = m
            primed = true
        }
        if (m - base > sensitivity) {
            hitUntil = now + 600L
        } else {
            base = base * 0.97f + m * 0.03f
        }
        return now < hitUntil
    }
}
