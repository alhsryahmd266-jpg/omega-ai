package com.omega.keyboard.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Colour helpers on plain 0xAARRGGBB ints. They use no Android classes, so everything in here is covered by the
 * unit tests on an ordinary JVM. The keyboard uses them to keep letters readable on any colour the user picks.
 */
object ColorMath {

    fun alpha(c: Int): Int = (c ushr 24) and 0xFF
    fun red(c: Int): Int = (c shr 16) and 0xFF
    fun green(c: Int): Int = (c shr 8) and 0xFF
    fun blue(c: Int): Int = c and 0xFF

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun rgb(r: Int, g: Int, b: Int): Int = argb(255, r, g, b)

    fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** [f] is 0..1 of full opacity */
    fun withAlphaF(c: Int, f: Float): Int = withAlpha(c, (f.coerceIn(0f, 1f) * 255f + 0.5f).toInt())

    fun opaque(c: Int): Int = withAlpha(c, 255)

    /** linear mix of every channel (alpha included): 0 = [a], 1 = [b] */
    fun blend(a: Int, b: Int, f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        fun mix(x: Int, y: Int): Int = (x + (y - x) * t + 0.5f).toInt()
        return argb(mix(alpha(a), alpha(b)), mix(red(a), red(b)), mix(green(a), green(b)), mix(blue(a), blue(b)))
    }

    private fun lin(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG relative luminance, 0 (black) .. 1 (white) */
    fun luminance(c: Int): Float = (0.2126 * lin(red(c)) + 0.7152 * lin(green(c)) + 0.0722 * lin(blue(c))).toFloat()

    /** WCAG contrast ratio, 1 (none) .. 21 (black on white) */
    fun contrast(a: Int, b: Int): Float {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    const val INK: Int = 0xFF14161A.toInt()
    const val PAPER: Int = 0xFFFFFFFF.toInt()

    /**
     * The text colour (near-black or white) that reads best on [bg]. White gets a small head start: the contrast formula
     * alone would put dark letters on a mid blue such as 2979FF, where every UI design uses white.
     */
    fun onColor(bg: Int): Int = if (contrast(bg, INK) >= contrast(bg, PAPER) * 1.15f) INK else PAPER

    /** true when dark text is the better choice on [c] */
    fun isLight(c: Int): Boolean = onColor(c) == INK

    /** -1 = black, 0 = unchanged, +1 = white (alpha is kept) */
    fun shade(c: Int, f: Float): Int {
        val a = alpha(c)
        val m = if (f < 0f) blend(c, 0xFF000000.toInt(), -f) else blend(c, PAPER, f)
        return withAlpha(m, a)
    }

    /** hue 0..360, saturation 0..1, value 0..1 */
    fun toHsv(c: Int): FloatArray {
        val r = red(c) / 255f
        val g = green(c) / 255f
        val b = blue(c) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        var h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        if (h < 0f) h += 360f
        val s = if (mx == 0f) 0f else d / mx
        return floatArrayOf(h, s, mx)
    }

    fun fromHsv(h: Float, s: Float, v: Float, a: Int = 255): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val ss = s.coerceIn(0f, 1f)
        val vv = v.coerceIn(0f, 1f)
        val c = vv * ss
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = vv - c
        val r: Float
        val g: Float
        val b: Float
        when ((hh / 60f).toInt()) {
            0 -> { r = c; g = x; b = 0f }
            1 -> { r = x; g = c; b = 0f }
            2 -> { r = 0f; g = c; b = x }
            3 -> { r = 0f; g = x; b = c }
            4 -> { r = x; g = 0f; b = c }
            else -> { r = c; g = 0f; b = x }
        }
        return argb(a, ((r + m) * 255f + 0.5f).toInt(), ((g + m) * 255f + 0.5f).toInt(), ((b + m) * 255f + 0.5f).toInt())
    }

    /** the colour at [tone] on the black → pure hue → white bar: 0 = black, 0.5 = the pure hue, 1 = white */
    fun fromTone(h: Float, tone: Float): Int {
        val t = tone.coerceIn(0f, 1f)
        return if (t <= 0.5f) fromHsv(h, 1f, t * 2f) else fromHsv(h, 1f - (t - 0.5f) * 2f, 1f)
    }

    /** where [c] sits on that bar (colours that are not on it land on the nearest side) */
    fun toTone(c: Int): Float {
        val hsv = toHsv(c)
        val s = hsv[1]
        val v = hsv[2]
        return when {
            s >= 0.98f -> v / 2f
            v >= 0.98f -> 0.5f + (1f - s) / 2f
            else -> v / 2f
        }
    }

    /** a second colour that sits well next to [c] in a gradient or in the blobs of an aurora */
    fun gradientPartner(c: Int): Int {
        val hsv = toHsv(c)
        val a = alpha(c)
        if (hsv[1] < 0.08f) return shade(c, if (isLight(c)) -0.08f else 0.10f)
        val v = if (hsv[2] > 0.6f) hsv[2] * 0.80f else min(1f, hsv[2] * 1.3f + 0.08f)
        return fromHsv(hsv[0] + 28f, min(1f, hsv[1] * 0.95f + 0.05f), v, a)
    }

    /** a rainbow colour for the n-th character */
    fun rainbow(n: Int): Int = fromHsv((n * 47f) % 360f, 0.92f, 1f)
}

/** The colours offered in the appearance sheet. */
object Palette {
    class Swatch(val name: String, val color: Int)

    private fun s(name: String, argb: Long) = Swatch(name, argb.toInt())

    /** for the text you type: the usual red and blue first, then the fluorescent ones */
    val typed: List<Swatch> = listOf(
        s("أزرق", 0xFF2979FF), s("أحمر", 0xFFFF1744), s("أخضر فسفوري", 0xFF39FF14), s("أصفر فسفوري", 0xFFFFEA00),
        s("وردي فسفوري", 0xFFFF2BD6), s("سماوي فسفوري", 0xFF00F0FF), s("برتقالي فسفوري", 0xFFFF7A00), s("بنفسجي", 0xFFB026FF),
        s("أبيض", 0xFFFFFFFF), s("أسود", 0xFF111111), s("ذهبي", 0xFFFFC107), s("أخضر", 0xFF00C853),
    )

    /** for keys, letters, background... */
    val general: List<Swatch> = listOf(
        s("أبيض", 0xFFFFFFFF), s("رمادي فاتح", 0xFFE8EBF0), s("رمادي", 0xFF9AA0A6), s("رمادي غامق", 0xFF3C4043), s("أسود", 0xFF000000),
        s("أزرق", 0xFF1A73E8), s("سماوي", 0xFF00BCD4), s("أخضر", 0xFF0F9D58), s("ليموني", 0xFFC6FF00), s("أصفر", 0xFFFFD600),
        s("برتقالي", 0xFFFF6D00), s("أحمر", 0xFFE53935), s("وردي", 0xFFEC407A), s("بنفسجي", 0xFF7C4DFF), s("كحلي", 0xFF0D1B3E), s("بني", 0xFF5D4037),
    )
}
