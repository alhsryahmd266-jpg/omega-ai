package com.ahmedxg.gamebot

import kotlin.math.max
import kotlin.math.min

/**
 * Second detection pass on magnified parts of the frame. A far enemy is only a few pixels in a full-frame
 * letterbox, so while nothing confident is on screen the bot also looks at a 50%-wide window (about 2x more
 * pixels per object): the crosshair window first, then the left and right windows, one per frame.
 * Pure Kotlin (no Android types) so it can be unit-tested on the JVM.
 */
object ZoomDetect {
    class Roi(val x: Int, val y: Int, val w: Int, val h: Int)

    /** A window of [fracW] x [fracH] of the frame centered on (cx, cy) in 0..1 screen coordinates, kept inside the frame. */
    fun window(frameW: Int, frameH: Int, cx: Float, cy: Float, fracW: Float = 0.5f, fracH: Float = 0.6f): Roi {
        val w = max(32, (frameW * fracW).toInt())
        val h = max(32, (frameH * fracH).toInt())
        val x = (cx * frameW - w / 2f).toInt().coerceIn(0, max(0, frameW - w))
        val y = (cy * frameH - h / 2f).toInt().coerceIn(0, max(0, frameH - h))
        return Roi(x, y, min(w, frameW), min(h, frameH))
    }

    /** The three windows scanned in rotation: crosshair, left half, right half (same height band as the crosshair). */
    fun windows(frameW: Int, frameH: Int, crossX: Float, crossY: Float): List<Roi> = listOf(
        window(frameW, frameH, crossX, crossY),
        window(frameW, frameH, 0.25f, crossY),
        window(frameW, frameH, 0.75f, crossY)
    )

    /** Run the zoom pass only when the full-frame pass found nothing clearly confident. */
    fun needZoom(first: List<Det>, conf: Float): Boolean = first.none { it.score >= conf + 0.15f }

    /** Boxes detected inside [r] (normalized to the window) -> normalized to the whole frame. */
    fun toFull(dets: List<Det>, r: Roi, frameW: Int, frameH: Int): List<Det> = dets.map {
        Det(it.cls, it.label, it.score,
            (r.x + it.cx * r.w) / frameW, (r.y + it.cy * r.h) / frameH,
            it.w * r.w / frameW, it.h * r.h / frameH)
    }

    private fun iou(a: Det, b: Det): Float {
        val iw = max(0f, min(a.cx + a.w / 2, b.cx + b.w / 2) - max(a.cx - a.w / 2, b.cx - b.w / 2))
        val ih = max(0f, min(a.cy + a.h / 2, b.cy + b.h / 2) - max(a.cy - a.h / 2, b.cy - b.h / 2))
        val inter = iw * ih
        val u = a.w * a.h + b.w * b.h - inter
        return if (u <= 0f) 0f else inter / u
    }

    /** Same enemy found by both passes -> keep the more confident box. */
    fun merge(a: List<Det>, b: List<Det>, iouThr: Float = 0.4f): List<Det> {
        val all = (a + b).sortedByDescending { it.score }
        val keep = ArrayList<Det>()
        for (d in all) {
            if (keep.none { it.cls == d.cls && iou(it, d) > iouThr }) keep.add(d)
            if (keep.size >= 30) break
        }
        return keep
    }

    /** Rotates through the windows, one per frame. */
    class Scheduler {
        private var i = 0
        fun next(wins: List<Roi>): Roi { val r = wins[i % wins.size]; i++; return r }
        fun reset() { i = 0 }
    }
}
