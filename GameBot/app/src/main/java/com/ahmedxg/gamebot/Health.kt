package com.ahmedxg.gamebot

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * Reads the HUD health bar (classical CV, no model): counts the columns of the bar rectangle that contain
 * the bar color. Assumes the bar empties from right to left. Set the rectangle with the green hpTL / hpBR
 * markers in the layout editor and the bar color (hex) in the settings.
 * @return 0..1, or -1 when the bar cannot be seen.
 */
class HealthReader(private val cfg: Config) {
    private var buf = IntArray(0)
    private var smooth = -1f

    fun read(bmp: Bitmap, w: Int, h: Int): Float {
        val x0 = (cfg.layout.x("hpTL") * w).toInt().coerceIn(0, w - 2)
        val y0 = (cfg.layout.y("hpTL") * h).toInt().coerceIn(0, h - 2)
        val x1 = (cfg.layout.x("hpBR") * w).toInt().coerceIn(x0 + 1, w - 1)
        val y1 = (cfg.layout.y("hpBR") * h).toInt().coerceIn(y0 + 1, h - 1)
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw < 12 || rh < 2 || x0 + rw > bmp.width || y0 + rh > bmp.height) return -1f
        if (buf.size < rw * rh) buf = IntArray(rw * rh)
        bmp.getPixels(buf, 0, rw, x0, y0, rw, rh)
        val tc = try {
            Color.parseColor("#" + cfg.healthColor.trim().removePrefix("#"))
        } catch (e: Exception) {
            Color.GREEN
        }
        val tr = Color.red(tc)
        val tg = Color.green(tc)
        val tb = Color.blue(tc)
        val tol = cfg.healthTol.coerceIn(5, 160)
        var last = -1
        var total = 0
        val need = maxOf(1, rh / 4)
        for (i in 0 until rw) {
            var c = 0
            for (j in 0 until rh) {
                val p = buf[j * rw + i]
                if (abs(((p shr 16) and 255) - tr) <= tol && abs(((p shr 8) and 255) - tg) <= tol && abs((p and 255) - tb) <= tol) c++
            }
            if (c >= need) {
                last = i
                total++
            }
        }
        if (total < 2) return -1f
        val v = ((last + 1).toFloat() / rw).coerceIn(0f, 1f)
        smooth = if (smooth < 0f) v else smooth * 0.6f + v * 0.4f
        return smooth
    }
}
