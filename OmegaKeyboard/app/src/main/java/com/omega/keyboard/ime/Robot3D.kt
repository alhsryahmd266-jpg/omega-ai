package com.omega.keyboard.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.omega.keyboard.core.ColorMath

/**
 * The robot of the autonomous mode, drawn as a small glossy 3D head: a lit metal shell with a bright top edge and a dark base,
 * a deep glass visor with a soft reflection, two glowing eyes, ear lights, a neck and an antenna with a light on it.
 *
 * It takes about thirty gradient fills, so each size / colour is painted once into a bitmap and the bitmap is what the strip draws.
 * Everything is laid out on a 100 x 100 sheet and scaled to the bitmap.
 */
object Robot3D {

    /** [PEARL]: a white shell for dark surfaces. [GRAPHITE]: a dark shell for light surfaces and for the gold disc. */
    enum class Body { PEARL, GRAPHITE }

    private class Look(
        val top: Int, val mid: Int, val low: Int, val bounce: Int, val edge: Int,
        val ear0: Int, val ear1: Int, val neck0: Int, val neck1: Int, val visor0: Int, val visor1: Int,
        val light: Float, val shade: Float, val rimTop: Float, val rimLow: Float, val bevelLow: Float,
    )

    private fun rgb(v: Int): Int = 0xFF000000.toInt() or v

    private val pearl = Look(
        rgb(0xFFFFFF), rgb(0xE6EAF2), rgb(0xA9B3C6), rgb(0xC8D0DF), rgb(0x5A6478),
        rgb(0xD9DFEA), rgb(0x8B96AB), rgb(0x9AA5BA), rgb(0x59647A), rgb(0x070B14), rgb(0x18223A),
        light = 0.55f, shade = 0.16f, rimTop = 0.95f, rimLow = 0.55f, bevelLow = 0.55f,
    )
    private val graphite = Look(
        rgb(0x6A758C), rgb(0x364055), rgb(0x141926), rgb(0x2A3347), rgb(0x000000),
        rgb(0x7D889E), rgb(0x2D3547), rgb(0x4A546A), rgb(0x1B2130), rgb(0x04060C), rgb(0x121A2C),
        light = 0.30f, shade = 0.30f, rimTop = 0.60f, rimLow = 0.80f, bevelLow = 0.22f,
    )

    private val cache = HashMap<Long, Bitmap>()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** the colour of the eyes and of the antenna light: the theme's accent, lifted when it is too dark to glow on the visor */
    fun glowFor(accent: Int): Int {
        val a = ColorMath.opaque(accent)
        val l = ColorMath.luminance(a)
        return if (l >= 0.22f) a else ColorMath.blend(a, ColorMath.PAPER, ((0.22f - l) * 2.6f).coerceAtMost(0.6f))
    }

    /** draws the robot centred on ([cx], [cy]) in a square of [size] pixels; [lit] is the running look (brighter glow) */
    fun draw(c: Canvas, cx: Float, cy: Float, size: Float, body: Body, accent: Int, lit: Boolean) {
        val px = Math.round(size).coerceIn(8, 320)
        val glow = glowFor(accent)
        val key = px.toLong() or (if (body == Body.PEARL) 1L shl 16 else 0L) or (if (lit) 1L shl 17 else 0L) or ((glow.toLong() and 0xFFFFFFL) shl 24)
        var bmp = cache[key]
        if (bmp == null) {
            if (cache.size >= 24) cache.clear()
            bmp = render(px, if (body == Body.PEARL) pearl else graphite, glow, lit)
            cache[key] = bmp
        }
        c.drawBitmap(bmp, Math.round(cx - px / 2f).toFloat(), Math.round(cy - px / 2f).toFloat(), bitmapPaint)
    }

    private fun a(color: Int, f: Float): Int = ColorMath.withAlpha(color, (f.coerceIn(0f, 1f) * 255f + 0.5f).toInt())

    private fun render(px: Int, look: Look, glow: Int, lit: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(px / 100f, px / 100f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = RectF()
        val white = ColorMath.PAPER
        val black = 0xFF000000.toInt()
        val opaque = black

        // a Paint's own alpha multiplies a shader: every shader fill starts from an opaque colour
        fun solid(col: Int) { p.shader = null; p.style = Paint.Style.FILL; p.color = col }
        fun shader(sh: Shader) { p.style = Paint.Style.FILL; p.color = opaque; p.shader = sh }
        fun stroke(sh: Shader?, w: Float, col: Int = opaque) {
            p.style = Paint.Style.STROKE; p.strokeWidth = w; p.shader = sh; p.color = if (sh == null) col else opaque
        }
        fun vert(y0: Float, y1: Float, vararg s: Pair<Float, Int>): Shader =
            LinearGradient(0f, y0, 0f, y1, IntArray(s.size) { s[it].second }, FloatArray(s.size) { s[it].first }, Shader.TileMode.CLAMP)
        fun horiz(x0: Float, x1: Float, vararg s: Pair<Float, Int>): Shader =
            LinearGradient(x0, 0f, x1, 0f, IntArray(s.size) { s[it].second }, FloatArray(s.size) { s[it].first }, Shader.TileMode.CLAMP)
        fun radial(cx: Float, cy: Float, rad: Float, vararg s: Pair<Float, Int>): Shader =
            RadialGradient(cx, cy, rad, IntArray(s.size) { s[it].second }, FloatArray(s.size) { s[it].first }, Shader.TileMode.CLAMP)
        fun round(x: Float, y: Float, w: Float, h: Float, rad: Float) { r.set(x, y, x + w, y + h); c.drawRoundRect(r, rad, rad, p) }

        val gTop = ColorMath.blend(glow, white, 0.82f)
        val gMid = ColorMath.blend(glow, white, 0.25f)
        val gLow = ColorMath.blend(glow, black, 0.15f)

        // a soft contact shadow under the neck
        for (i in 0 until 3) {
            solid(a(black, 0.07f - 0.02f * i))
            val rx = 24f + 3f * i
            val ry = 3.2f + 1.1f * i
            r.set(50f - rx, 92.5f - ry, 50f + rx, 92.5f + ry)
            c.drawOval(r, p)
        }

        // neck (behind the head)
        shader(vert(74f, 88.5f, 0f to look.neck0, 1f to look.neck1))
        round(31f, 74f, 38f, 14.5f, 6f)
        stroke(vert(74f, 88.5f, 0f to a(white, 0.35f), 0.5f to a(white, 0f), 1f to a(black, 0.25f)), 1f)
        round(31.5f, 74.5f, 37f, 13.5f, 5.6f)

        // antenna stem
        shader(horiz(48.5f, 51.5f, 0f to look.ear0, 1f to look.ear1))
        round(48.5f, 15f, 3f, 13f, 1.5f)

        // ears with a light each
        for (x in floatArrayOf(4.2f, 87.8f)) {
            shader(horiz(x, x + 8f, 0f to look.ear1, 0.5f to look.ear0, 1f to look.ear1))
            round(x, 40f, 8f, 22f, 4f)
            stroke(null, 0.8f, a(black, 0.28f))
            round(x, 40f, 8f, 22f, 4f)
            solid(a(glow, if (lit) 1f else 0.75f))
            round(x + 3.1f, 45f, 1.8f, 12f, 0.9f)
        }

        // head: a soft drop shadow, then the shell
        val hx = 12f; val hy = 25f; val hw = 76f; val hh = 52f; val hr = 21f
        val sh = floatArrayOf(0.16f, 0.09f, 0.05f)
        for (i in 0 until 3) {
            solid(a(black, sh[i]))
            round(hx - i * 0.6f, hy + 1.6f + i * 1.1f, hw + i * 1.2f, hh, hr + i)
        }
        shader(vert(hy, hy + hh, 0f to look.top, 0.38f to look.mid, 0.86f to look.low, 1f to look.bounce))
        round(hx, hy, hw, hh, hr)

        // roundness: light from the top left, shade at the right edge
        c.save()
        val shell = Path().apply { addRoundRect(RectF(hx, hy, hx + hw, hy + hh), hr, hr, Path.Direction.CW) }
        c.clipPath(shell)
        shader(radial(34f, 32f, 52f, 0f to a(white, look.light), 1f to a(white, 0f)))
        c.drawRect(hx, hy, hx + hw, hy + hh, p)
        shader(horiz(hx + hw * 0.55f, hx + hw, 0f to a(black, 0f), 1f to a(black, look.shade)))
        c.drawRect(hx, hy, hx + hw, hy + hh, p)
        c.restore()

        // lit top edge, dark base edge
        stroke(
            vert(hy, hy + hh, 0f to a(white, look.rimTop), 0.45f to a(white, 0f), 0.6f to a(look.edge, 0f), 1f to a(look.edge, look.rimLow)), 1.3f,
        )
        round(hx + 0.6f, hy + 0.6f, hw - 1.2f, hh - 1.2f, hr - 0.6f)

        // visor
        val vx = 21f; val vy = 33f; val vw = 58f; val vh = 36f; val vr = 15f
        shader(vert(vy, vy + vh, 0f to look.visor0, 1f to look.visor1))
        round(vx, vy, vw, vh, vr)
        stroke(vert(vy, vy + vh, 0f to a(black, 0.5f), 0.5f to a(black, 0.1f), 1f to a(white, look.bevelLow)), 1.1f)
        round(vx - 0.5f, vy - 0.5f, vw + 1f, vh + 1f, vr + 0.5f)

        // eyes, their glow and the reflection of the glass: all inside the visor
        c.save()
        val glass = Path().apply { addRoundRect(RectF(vx, vy, vx + vw, vy + vh), vr, vr, Path.Direction.CW) }
        c.clipPath(glass)
        for (ex in floatArrayOf(38f, 62f)) {
            shader(radial(ex, 51f, 17f, 0f to a(glow, if (lit) 0.62f else 0.45f), 1f to a(glow, 0f)))
            c.drawRect(vx, vy, vx + vw, vy + vh, p)
        }
        for (ex in floatArrayOf(38f, 62f)) {
            shader(vert(42.5f, 59.5f, 0f to gTop, 0.45f to gMid, 1f to gLow))
            round(ex - 4.6f, 42.5f, 9.2f, 17f, 4.6f)
        }
        shader(vert(vy, vy + vh * 0.6f, 0f to a(white, 0.26f), 1f to a(white, 0f)))
        val refl = Path().apply {
            moveTo(vx, vy)
            lineTo(vx + vw, vy)
            quadTo(vx + vw * 0.55f, vy + vh * 0.8f, vx, vy + vh * 0.52f)
            close()
        }
        c.drawPath(refl, p)
        c.restore()

        // the antenna light
        shader(radial(50f, 11f, 14f, 0f to a(glow, if (lit) 0.7f else 0.5f), 1f to a(glow, 0f)))
        c.drawCircle(50f, 11f, 14f, p)
        shader(radial(48.2f, 9.2f, 7f, 0f to white, 0.35f to gMid, 1f to gLow))
        c.drawCircle(50f, 11f, 5.6f, p)
        return bmp
    }
}
