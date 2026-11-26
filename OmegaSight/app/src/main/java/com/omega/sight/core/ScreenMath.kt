package com.omega.sight.core

import kotlin.math.max
import kotlin.math.min

/** Plain arithmetic about the screen's shape, kept apart from Android so that it can be tested on any machine. */
object ScreenMath {
    /**
     * The pixel counts of the screen, turned the way the display is turned now.
     *
     * [naturalLandscape] is the shape of the panel itself (null = not known, then nothing is changed) and [quarterTurn] says that the
     * display is turned by 90 or 270 degrees. The system's own pixel counts are kept, only a reading whose direction disagrees with
     * the rotation (a stale one) is turned over. A square screen has no direction and stays as it is.
     */
    fun oriented(w: Int, h: Int, naturalLandscape: Boolean?, quarterTurn: Boolean): IntArray {
        if (naturalLandscape == null || w == h) return intArrayOf(w, h)
        val wantLandscape = naturalLandscape != quarterTurn
        return if ((w > h) == wantLandscape) intArrayOf(w, h) else intArrayOf(h, w)
    }

    /** the size of the capture picture for a screen of [sw] x [sh] pixels: the long side is at most [long], both sides are even and at least 64 */
    fun captureSize(sw: Int, sh: Int, long: Int): IntArray {
        val longest = max(sw, sh).coerceAtLeast(1)
        val k = min(1f, long / longest.toFloat())
        return intArrayOf(even(sw * k), even(sh * k))
    }

    private fun even(v: Float): Int = max(64, (Math.round(v) / 2) * 2)
}
