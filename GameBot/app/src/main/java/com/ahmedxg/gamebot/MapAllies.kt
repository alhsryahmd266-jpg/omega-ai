package com.ahmedxg.gamebot

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max

class MapMarks(val allies: List<MapMark>, val enemies: List<MapMark>)

/**
 * Finds teammate and enemy markers on the minimap by COLOR and shape (no OCR: the digit inside a numbered
 * teammate marker is not read, only where the marker is). Friends = numbered circles or green dots (set the
 * marker's background color), enemies = red dots. Uses the same minimap rectangle as the zone reader.
 */
class AllyMapReader(private val cfg: Config) {
    private var buf = IntArray(0)

    fun read(bmp: Bitmap, w: Int, h: Int): MapMarks {
        val x0 = (cfg.layout.x("mapTL") * w).toInt().coerceIn(0, w - 2)
        val y0 = (cfg.layout.y("mapTL") * h).toInt().coerceIn(0, h - 2)
        val x1 = (cfg.layout.x("mapBR") * w).toInt().coerceIn(x0 + 1, w - 1)
        val y1 = (cfg.layout.y("mapBR") * h).toInt().coerceIn(y0 + 1, h - 1)
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw < 24 || rh < 24 || x0 + rw > bmp.width || y0 + rh > bmp.height) return MapMarks(emptyList(), emptyList())
        if (buf.size < rw * rh) buf = IntArray(rw * rh)
        bmp.getPixels(buf, 0, rw, x0, y0, rw, rh)
        val allies = if (cfg.allyMapEnabled) detect(rw, rh, cfg.allyColor, cfg.allyTol, Color.CYAN) else emptyList()
        val enemies = if (cfg.enemyMapEnabled) detect(rw, rh, cfg.enemyMapColor, cfg.enemyMapTol, Color.RED) else emptyList()
        return MapMarks(allies, enemies)
    }

    private fun detect(rw: Int, rh: Int, hex: String, tolIn: Int, fallback: Int): List<MapMark> {
        val tc = try {
            Color.parseColor("#" + hex.trim().removePrefix("#"))
        } catch (e: Exception) {
            fallback
        }
        val tr = Color.red(tc)
        val tg = Color.green(tc)
        val tb = Color.blue(tc)
        val tol = tolIn.coerceIn(5, 160)
        val mask = BooleanArray(rw * rh)
        for (i in 0 until rw * rh) {
            val p = buf[i]
            mask[i] = abs(((p shr 16) and 255) - tr) <= tol && abs(((p shr 8) and 255) - tg) <= tol && abs((p and 255) - tb) <= tol
        }
        val minArea = max(6, rw * rh / 2500)
        val maxArea = rw * rh / 12
        return MapMath.marks(MapMath.blobs(mask, rw, rh, minArea, maxArea), rw, rh)
    }
}
