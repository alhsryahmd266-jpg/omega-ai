package com.omega.sight

import com.omega.sight.core.ScreenMath
import kotlin.math.abs

/** The screen's shape: which way round the pixel counts are after the phone turned, and the size of the capture picture. */
object ScreenTests {
    private fun same(a: IntArray, w: Int, h: Int) = a[0] == w && a[1] == h

    fun run() {
        // a phone whose panel is portrait
        T.check("portrait panel, not turned", same(ScreenMath.oriented(1080, 2400, false, false), 1080, 2400))
        T.check("portrait panel, turned, fresh reading", same(ScreenMath.oriented(2400, 1080, false, true), 2400, 1080))
        T.check("portrait panel, turned, stale reading is turned over", same(ScreenMath.oriented(1080, 2400, false, true), 2400, 1080))
        T.check("portrait panel, turned back, stale landscape reading", same(ScreenMath.oriented(2400, 1080, false, false), 1080, 2400))
        // a tablet whose panel is landscape
        T.check("landscape panel, not turned", same(ScreenMath.oriented(2560, 1600, true, false), 2560, 1600))
        T.check("landscape panel, turned, stale reading", same(ScreenMath.oriented(2560, 1600, true, true), 1600, 2560))
        T.check("landscape panel, turned, fresh reading", same(ScreenMath.oriented(1600, 2560, true, true), 1600, 2560))
        // nothing to go by
        T.check("shape of the panel unknown: unchanged", same(ScreenMath.oriented(1080, 2400, null, true), 1080, 2400))
        T.check("square screen: unchanged", same(ScreenMath.oriented(1000, 1000, false, true), 1000, 1000))
        // the pixel count never changes, only the direction
        for ((w, h) in listOf(1080 to 2400, 2400 to 1080, 720 to 1600, 1600 to 720, 1440 to 3120)) {
            for (nat in listOf(false, true)) {
                for (turn in listOf(false, true)) {
                    val o = ScreenMath.oriented(w, h, nat, turn)
                    T.check("same pixels $w x $h nat=$nat turn=$turn", o[0] * o[1] == w * h && (o[0] > o[1]) == (nat != turn))
                }
            }
        }

        // the capture picture
        T.check("capture of a portrait phone", same(ScreenMath.captureSize(1080, 2400, 1280), 576, 1280))
        T.check("capture of the same phone turned", same(ScreenMath.captureSize(2400, 1080, 1280), 1280, 576))
        T.check("capture of a smaller screen is not enlarged", same(ScreenMath.captureSize(540, 960, 1280), 540, 960))
        T.check("capture at the largest setting", same(ScreenMath.captureSize(1080, 2400, 1920), 864, 1920))
        for ((w, h) in listOf(1080 to 2400, 2400 to 1080, 720 to 1600, 1440 to 3200, 1200 to 2670, 2560 to 1600, 1080 to 1920)) {
            for (long in listOf(640, 960, 1280, 1600, 1920)) {
                val c = ScreenMath.captureSize(w, h, long)
                val a0 = w.toFloat() / h
                val a1 = c[0].toFloat() / c[1]
                T.check("capture $w x $h at $long: even sides", c[0] % 2 == 0 && c[1] % 2 == 0, { "${c[0]}x${c[1]}" })
                T.check("capture $w x $h at $long: long side", maxOf(c[0], c[1]) <= maxOf(long, 64) + 1, { "${c[0]}x${c[1]}" })
                T.check("capture $w x $h at $long: same shape as the screen", abs(a1 - a0) / a0 < 0.01f, { "${c[0]}x${c[1]} vs $w x $h" })
                T.check("capture $w x $h at $long: direction", (c[0] >= c[1]) == (w >= h))
            }
        }
    }
}
