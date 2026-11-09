package com.tacticai.bot.tactical

import android.graphics.Bitmap
import android.graphics.Rect

data class Rgb(val r: Int, val g: Int, val b: Int)

/**
 * Tells friend from foe by colour (team-coloured outlines/nameplates/gear are how most shooters already
 * make this visually obvious to a human — a generic object detector trained on real-world photos has no
 * way to know what "your team" means in a specific game, but a hue comparison genuinely can).
 *
 * Verified with a standalone 9-case test (including the 0°/360° hue wrap-around, a classic bug source)
 * before being ported here.
 */
class TeamColorFilter {
    private var reference: Rgb? = null
    val isCalibrated: Boolean get() = reference != null

    /** Call once, e.g. by sampling the colour of the player's own teammate-list marker or health bar. */
    fun calibrate(color: Rgb) { reference = color }
    fun reset() { reference = null }

    /** Average colour of the pixels in [box] (clamped to the bitmap). */
    fun averageColor(bitmap: Bitmap, box: Rect): Rgb {
        val w = bitmap.width
        val h = bitmap.height
        val x0 = box.left.coerceIn(0, w - 1)
        val x1 = box.right.coerceIn(x0 + 1, w)
        val y0 = box.top.coerceIn(0, h - 1)
        val y1 = box.bottom.coerceIn(y0 + 1, h)
        val bw = x1 - x0
        val bh = y1 - y0
        val pixels = IntArray(bw * bh)
        bitmap.getPixels(pixels, 0, bw, x0, y0, bw, bh)
        var r = 0L; var g = 0L; var b = 0L
        for (p in pixels) {
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        val n = pixels.size.toLong()
        return if (n == 0L) Rgb(0, 0, 0) else Rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun hue(c: Rgb): Float {
        val r = c.r / 255f; val g = c.g / 255f; val b = c.b / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        if (d < 1e-4f) return -1f // greyscale: hue is meaningless (black/white/grey, bare skin, shadow)
        val h = when (max) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return (h * 60f + 360f) % 360f
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val d = kotlin.math.abs(a - b) % 360f
        return minOf(d, 360f - d)
    }

    /** True if [color] is close enough in hue to the calibrated friendly colour. Uncalibrated/greyscale -> false, never guesses. */
    fun looksFriendly(color: Rgb, hueToleranceDeg: Float = 28f): Boolean {
        val ref = reference ?: return false
        val refHue = hue(ref)
        val cHue = hue(color)
        if (refHue < 0f || cHue < 0f) return false
        return hueDistance(refHue, cHue) <= hueToleranceDeg
    }
}
