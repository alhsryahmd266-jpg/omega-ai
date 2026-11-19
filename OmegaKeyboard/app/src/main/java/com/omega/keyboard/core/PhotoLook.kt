package com.omega.keyboard.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The colours of a theme made from the user's own picture, so that any picture becomes a theme that looks like the picture themes of
 * the phone keyboards that ship them: coloured letters on light see-through keys, an eye-catching enter key and key bubble, a picture
 * that is darkened just enough for the white text of the strip. The input is a small copy of the picture (ARGB ints, row by row).
 * No Android classes in here: the unit tests cover it.
 */
object PhotoLook {
    class Look(
        /** colours of the top and the bottom while the picture is being made and behind the panels: always dark enough for white text */
        val top: Int,
        val bottom: Int,
        /** letters on the see-through keys */
        val ink: Int,
        /** the enter key and the choices that are on, with the colour of what is written on it */
        val accent: Int,
        val onAccent: Int,
        /** the bubble over a pressed key (its letters are white) */
        val popup: Int,
        /** how much the picture is darkened, 0..255 (a bright picture needs more, so the white text of the strip stays readable) */
        val scrim: Int,
    )

    /** a dark version of [c]: dark enough that white text on it has a contrast of at least 7 */
    private fun darkOf(c: Int): Int {
        var d = ColorMath.shade(ColorMath.opaque(c), -0.55f)
        var guard = 0
        while (ColorMath.contrast(d, ColorMath.PAPER) < 7f && guard++ < 8) d = ColorMath.shade(d, -0.25f)
        return d
    }

    private fun average(px: IntArray, w: Int, y0: Int, y1: Int): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        for (y in y0 until y1) for (x in 0 until w) {
            val c = px[y * w + x]
            r += ColorMath.red(c); g += ColorMath.green(c); b += ColorMath.blue(c); n++
        }
        if (n == 0L) return 0xFF202030.toInt()
        return ColorMath.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** what a theme gets when nothing can be read from the picture */
    fun neutral(): Look = make(220f, 0.6f, 0.3f, 0xFF1A1A2E.toInt(), 0xFF0E0E1A.toInt())

    private fun make(hue: Float, sat: Float, light: Float, topAvg: Int, bottomAvg: Int): Look {
        val s = sat.coerceIn(0.6f, 0.95f)
        val accent = ColorMath.fromHsv(hue, s, 1f)
        // the bubble: as vivid as the picture, deep enough for white letters
        var v = 0.95f
        var popup = ColorMath.fromHsv(hue, 0.88f, v)
        while (ColorMath.contrast(popup, ColorMath.PAPER) < 2.4f && v > 0.55f) {
            v -= 0.05f
            popup = ColorMath.fromHsv(hue, 0.88f, v)
        }
        val ink = ColorMath.fromHsv(hue, 0.78f, 0.34f)
        val scrim = (0x30 + light * 0x68).toInt().coerceIn(0x30, 0x98)
        return Look(darkOf(topAvg), darkOf(bottomAvg), ink, accent, ColorMath.onColor(accent), popup, scrim)
    }

    /** works the colours out of a picture of [w] x [h] pixels (a copy of about 24 x 24 is plenty) */
    fun from(px: IntArray, w: Int, h: Int): Look {
        if (w <= 0 || h <= 0 || px.size < w * h) return neutral()
        val third = max(1, h / 3)
        val topAvg = average(px, w, 0, third)
        val bottomAvg = average(px, w, h - third, h)
        var lightSum = 0.0
        var vx = 0.0
        var vy = 0.0
        var weight = 0.0
        var satSum = 0.0
        val n = w * h
        for (i in 0 until n) {
            val c = px[i]
            lightSum += sqrt(ColorMath.luminance(c).toDouble())      // the perceived lightness, not the amount of light
            val hsv = ColorMath.toHsv(c)
            val wgt = (hsv[1] * hsv[1] * hsv[2]).toDouble()          // saturated, bright pixels say what colour the picture is
            val ang = Math.toRadians(hsv[0].toDouble())
            vx += cos(ang) * wgt
            vy += sin(ang) * wgt
            weight += wgt
            satSum += hsv[1] * wgt
        }
        val light = (lightSum / n).toFloat()
        // a picture with no colour in it (grey, black and white) gets a calm blue
        if (weight < 0.02 * n) return make(220f, 0.6f, light, topAvg, bottomAvg)
        val hue = ((Math.toDegrees(atan2(vy, vx)) + 360.0) % 360.0).toFloat()
        return make(hue, (satSum / weight).toFloat(), light, topAvg, bottomAvg)
    }
}
