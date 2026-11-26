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

    /**
     * How far from every edge of the screen a touch of the aim stays, in pixels. The system's own edge gestures live there (back from the
     * sides, home and recent apps from the bottom, the notification shade from the top): a touch that starts or runs in that band is taken
     * by the system and sends the game to the background. 56 dp, but never more than a sixth of the short side.
     */
    fun safeMargin(dpi: Int, w: Int, h: Int): Int {
        val px = Math.round(56f * dpi.coerceAtLeast(120) / 160f)
        val cap = min(w, h) / 6
        return px.coerceIn(24, max(24, cap))
    }

    private fun even(v: Float): Int = max(64, (Math.round(v) / 2) * 2)
}
