package com.omega.keyboard.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Picture backgrounds for the keyboard ("scenes": a beach at sunset, the deep sea, liquid colour, a galaxy, a retro sunset). They are
 * painted with plain canvas operations (gradients, paths, circles), so they are sharp at any size and add nothing to the download.
 * A scene is drawn once into a bitmap ([SceneDrawable]) and afterwards only copied, so typing costs nothing extra.
 */
object Scenes {
    val ids = listOf("beach", "deep", "paint", "galaxy", "synth")

    /** paints scene [id] on [c], which is [w] by [h] pixels (an unknown id gets the galaxy) */
    fun draw(id: String, c: Canvas, w: Float, h: Float) {
        when (id) {
            "beach" -> beach(c, w, h)
            "deep" -> deep(c, w, h)
            "paint" -> paint(c, w, h)
            "synth" -> synth(c, w, h)
            else -> galaxy(c, w, h)
        }
    }

    // ------------------------------------------------------------------ small helpers

    /** the same little random generator everywhere, so a scene always looks the same */
    private class Rng(seed: Int) {
        private var s = seed
        fun next(): Float { s = s * 1664525 + 1013904223; return (s ushr 8) / 16777216f }
    }

    /** colour 0xRRGGBB with an opacity of 0..1 */
    private fun col(rgb: Int, a: Float = 1f): Int = ((a * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

    private fun lin(x0: Float, y0: Float, x1: Float, y1: Float, vararg s: Pair<Float, Int>): Shader =
        LinearGradient(x0, y0, x1, y1, IntArray(s.size) { s[it].second }, FloatArray(s.size) { s[it].first }, Shader.TileMode.CLAMP)

    private fun rad(cx: Float, cy: Float, r: Float, vararg s: Pair<Float, Int>): Shader =
        RadialGradient(cx, cy, max(1f, r), IntArray(s.size) { s[it].second }, FloatArray(s.size) { s[it].first }, Shader.TileMode.CLAMP)

    /** a paint with the two things a scene needs: a flat colour or a gradient (a shader is multiplied by the paint's alpha, so it stays opaque) */
    private class Pen(val c: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()

        fun fill(color: Int) { p.shader = null; p.style = Paint.Style.FILL; p.color = color }
        fun fill(s: Shader) { p.style = Paint.Style.FILL; p.color = 0xFF000000.toInt(); p.shader = s }
        fun line(color: Int, width: Float) { p.shader = null; p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = width }
        fun line(s: Shader, width: Float) { p.style = Paint.Style.STROKE; p.color = 0xFF000000.toInt(); p.shader = s; p.strokeWidth = width }

        fun rect(l: Float, t: Float, r: Float, b: Float) = c.drawRect(l, t, r, b, p)
        fun disc(x: Float, y: Float, r: Float) = c.drawCircle(x, y, r, p)

        /** an ellipse; a gradient set before it is drawn in the squeezed space, which is what gives the clouds their soft look */
        fun blob(x: Float, y: Float, rx: Float, ry: Float) {
            c.save(); c.translate(x, y); c.scale(1f, ry / rx); c.drawCircle(0f, 0f, rx, p); c.restore()
        }
    }

    // ------------------------------------------------------------------ beach at sunset

    private fun palm(g: Pen, bx: Float, by: Float, hh: Float, lean: Float, color: Int) {
        val tx = bx + lean
        val ty = by - hh
        val cx = bx + lean * 0.10f
        val cy = by - hh * 0.58f
        val n = 18
        val wb = hh * 0.05f
        val wt = hh * 0.024f
        val lx = FloatArray(n + 1); val ly = FloatArray(n + 1); val rx = FloatArray(n + 1); val ry = FloatArray(n + 1)
        for (i in 0..n) {
            val t = i / n.toFloat()
            val u = 1 - t
            val x = u * u * bx + 2 * u * t * cx + t * t * tx
            val y = u * u * by + 2 * u * t * cy + t * t * ty
            val dx = 2 * u * (cx - bx) + 2 * t * (tx - cx)
            val dy = 2 * u * (cy - by) + 2 * t * (ty - cy)
            val len = hypot(dx, dy).let { if (it == 0f) 1f else it }
            val nx = -dy / len
            val ny = dx / len
            val w = (wb + (wt - wb) * t) / 2
            lx[i] = x + nx * w; ly[i] = y + ny * w
            rx[i] = x - nx * w; ry[i] = y - ny * w
        }
        g.fill(color)
        val pa = g.path
        pa.reset()
        pa.moveTo(lx[0], ly[0])
        for (i in 1..n) pa.lineTo(lx[i], ly[i])
        for (i in n downTo 0) pa.lineTo(rx[i], ry[i])
        pa.close()
        g.c.drawPath(pa, g.p)
        val leaves = 9
        for (i in 0 until leaves) {
            val a = (-PI * (0.04 + 0.92 * i / (leaves - 1))).toFloat()
            val len = hh * (0.50f + 0.10f * sin(i * 1.9f + 0.6f))
            val ca = cos(a)
            val sa = sin(a)
            val ex = tx + ca * len
            val ey = ty + sa * len * 0.50f + hh * 0.26f * abs(ca)
            val c1x = tx + ca * len * 0.52f
            val c1y = ty + sa * len * 0.95f - hh * 0.04f
            val c2x = tx + ca * len * 0.50f
            val c2y = ty + sa * len * 0.55f + hh * 0.05f
            pa.reset()
            pa.moveTo(tx, ty); pa.quadTo(c1x, c1y, ex, ey); pa.quadTo(c2x, c2y, tx, ty); pa.close()
            g.c.drawPath(pa, g.p)
        }
        g.disc(tx, ty + hh * 0.012f, hh * 0.03f)
    }

    private fun beach(c: Canvas, w: Float, h: Float) {
        val g = Pen(c)
        val r = Rng(11)
        val u = min(w, h * 1.35f)
        val hy = h * 0.60f
        g.fill(lin(0f, 0f, 0f, hy, 0f to col(0x120A36), 0.28f to col(0x3F1F78), 0.55f to col(0xA8387C), 0.80f to col(0xF0714B), 1f to col(0xFFC06A)))
        g.rect(0f, 0f, w, hy + 1)
        for (i in 0 until 34) {
            val x = r.next() * w
            val y = r.next() * hy * 0.34f
            val a = 0.25f + r.next() * 0.5f
            g.fill(col(0xFFFFFF, a * 0.55f)); g.rect(x, y, x + 1.6f, y + 1.6f)
        }
        val sx = w * 0.70f
        val sy = hy - h * 0.012f
        val sr = u * 0.085f
        c.save(); c.clipRect(0f, 0f, w, hy + 1)
        g.fill(rad(sx, sy, w * 0.66f, 0f to col(0xFFE3A0, 0.88f), 0.16f to col(0xFFB36B, 0.52f), 0.5f to col(0xFF6E6E, 0.16f), 1f to col(0xFF6E6E, 0f)))
        g.disc(sx, sy, w * 0.66f); c.restore()
        // clouds
        val clouds = arrayOf(
            floatArrayOf(0.22f, 0.20f, 0.34f, 0.016f), floatArrayOf(0.58f, 0.29f, 0.40f, 0.020f), floatArrayOf(0.30f, 0.40f, 0.46f, 0.017f),
            floatArrayOf(0.80f, 0.18f, 0.26f, 0.013f), floatArrayOf(0.62f, 0.47f, 0.38f, 0.012f),
        )
        for (cl in clouds) {
            val cx = cl[0]; val cy = cl[1]; val cw = cl[2]; val ch = cl[3]
            g.fill(lin(0f, h * (cy - ch), 0f, h * (cy + ch), 0f to col(0xFFD2A8, 0.55f), 0.5f to col(0xFF8E8E, 0.45f), 1f to col(0x7A3A9A, 0.30f)))
            g.blob(w * cx, h * cy, w * cw / 2, h * ch)
            g.blob(w * (cx + 0.07f), h * (cy - ch * 0.5f), w * cw * 0.32f, h * ch * 0.8f)
        }
        g.fill(rad(sx, sy, sr, 0f to col(0xFFFBE6), 0.72f to col(0xFFE9AE), 1f to col(0xFFC873)))
        g.disc(sx, sy, sr)
        // sea
        g.fill(lin(0f, hy, 0f, h, 0f to col(0x9A4580), 0.10f to col(0x5A2A70), 0.45f to col(0x27144A), 1f to col(0x0B0824)))
        g.rect(0f, hy, w, h)
        g.fill(lin(0f, hy - h * 0.03f, 0f, hy + h * 0.03f, 0f to col(0xFFD9A0, 0f), 0.5f to col(0xFFD9A0, 0.42f), 1f to col(0xFFD9A0, 0f)))
        g.rect(0f, hy - h * 0.03f, w, hy + h * 0.03f)
        // the sun's glitter on the water
        for (i in 0 until 16) {
            val t = i / 15f
            val y = hy + (h - hy) * (0.015f + 0.80f * t * t)
            val ww = sr * (0.5f + 2.6f * t)
            val th = h * (0.004f + 0.011f * t)
            val x = sx + (r.next() - 0.5f) * w * 0.05f * (0.3f + t)
            g.fill(col(0xFFD98A, 0.72f * (1 - 0.78f * t)))
            g.blob(x, y, ww, th)
        }
        // waves
        for (k in 0 until 6) {
            val y0 = hy + (h - hy) * (0.10f + k * 0.15f)
            val amp = h * 0.004f * (1 + k * 0.7f)
            val len = w * 0.13f * (1 + k * 0.35f)
            g.line(col(0xFFB890, 0.13f + 0.01f * k), 1.2f + k * 0.5f)
            val pa = g.path
            pa.reset(); pa.moveTo(-len, y0)
            var x = -len
            while (x < w + len) {
                pa.quadTo(x + len / 4, y0 - amp * 2, x + len / 2, y0)
                pa.quadTo(x + len * 3 / 4, y0 + amp * 2, x + len, y0)
                x += len
            }
            c.drawPath(pa, g.p)
        }
        // palms
        palm(g, w * 0.075f, h * 1.02f, h * 0.62f, w * 0.045f, col(0x0E0826))
        palm(g, w * 0.955f, h * 1.02f, h * 0.50f, -w * 0.035f, col(0x0E0826))
        // a darker foot, so the keys stay readable
        g.fill(lin(0f, h * 0.70f, 0f, h, 0f to col(0x080418, 0f), 1f to col(0x080418, 0.45f)))
        g.rect(0f, h * 0.70f, w, h)
    }

    // ------------------------------------------------------------------ deep sea

    private fun deep(c: Canvas, w: Float, h: Float) {
        val g = Pen(c)
        val r = Rng(23)
        val u = min(w, h * 1.35f)
        g.fill(lin(0f, 0f, 0f, h, 0f to col(0x0F7EA0), 0.30f to col(0x0A5A7E), 0.65f to col(0x053A58), 1f to col(0x021A2E)))
        g.rect(0f, 0f, w, h)
        // rays of light from the surface
        for (i in 0 until 7) {
            val x0 = w * (0.04f + 0.15f * i + 0.05f * r.next())
            val wd = w * (0.05f + 0.05f * r.next())
            val skew = w * (0.20f + 0.06f * r.next())
            g.fill(lin(0f, 0f, 0f, h * 0.95f, 0f to col(0xD8FAFF, 0.20f), 0.55f to col(0xBEF3FF, 0.06f), 1f to col(0xBEF3FF, 0f)))
            val pa = g.path
            pa.reset(); pa.moveTo(x0, 0f); pa.lineTo(x0 + wd, 0f); pa.lineTo(x0 + wd + skew + wd * 0.8f, h); pa.lineTo(x0 + skew - wd * 0.4f, h); pa.close()
            c.drawPath(pa, g.p)
        }
        g.fill(rad(w * 0.45f, -h * 0.05f, w * 0.7f, 0f to col(0xCFF8FF, 0.38f), 0.5f to col(0x7FE0F5, 0.12f), 1f to col(0x7FE0F5, 0f)))
        g.disc(w * 0.45f, -h * 0.05f, w * 0.7f)
        // kelp at the bottom
        for (k in 0 until 9) {
            val bx = w * (0.03f + 0.115f * k + 0.03f * r.next())
            val hh = h * (0.20f + 0.28f * r.next())
            val sway = w * (0.02f + 0.03f * r.next()) * (if (r.next() < 0.5f) -1f else 1f)
            g.fill(col(if (k % 3 == 0) 0x06495A else 0x043445, 0.80f))
            val pa = g.path
            pa.reset(); pa.moveTo(bx - u * 0.008f, h)
            pa.cubicTo(bx - u * 0.01f + sway, h - hh * 0.4f, bx + sway * 1.8f, h - hh * 0.7f, bx + sway * 0.6f, h - hh)
            pa.cubicTo(bx + sway * 2.4f, h - hh * 0.65f, bx + u * 0.014f + sway * 0.6f, h - hh * 0.35f, bx + u * 0.008f, h)
            pa.close()
            c.drawPath(pa, g.p)
        }
        g.fill(lin(0f, h * 0.78f, 0f, h, 0f to col(0x021520, 0f), 1f to col(0x021520, 0.75f)))
        g.rect(0f, h * 0.78f, w, h)
        // fish
        for (k in 0 until 6) {
            val x = w * (0.12f + 0.75f * r.next())
            val y = h * (0.30f + 0.30f * r.next())
            val s = u * (0.018f + 0.012f * r.next())
            val dir = if (r.next() < 0.5f) -1f else 1f
            g.fill(col(0x052B3F, 0.55f))
            g.blob(x, y, s * 1.6f, s * 0.7f)
            val pa = g.path
            pa.reset(); pa.moveTo(x - dir * s * 1.4f, y); pa.lineTo(x - dir * s * 2.4f, y - s * 0.8f); pa.lineTo(x - dir * s * 2.4f, y + s * 0.8f); pa.close()
            c.drawPath(pa, g.p)
        }
        // bubbles
        for (i in 0 until 34) {
            val x = r.next() * w
            val y = r.next() * h
            val rr = u * (0.004f + 0.013f * r.next() * r.next())
            g.fill(rad(x - rr * 0.3f, y - rr * 0.3f, rr, 0f to col(0xFFFFFF, 0.30f), 0.7f to col(0xBFF3FF, 0.08f), 1f to col(0xBFF3FF, 0.16f)))
            g.disc(x, y, rr)
            g.line(col(0xE6FBFF, 0.38f), 1f); c.drawCircle(x, y, rr, g.p)
            g.fill(col(0xFFFFFF, 0.7f)); g.disc(x - rr * 0.38f, y - rr * 0.38f, max(0.8f, rr * 0.18f))
        }
    }

    // ------------------------------------------------------------------ liquid colour

    private fun paint(c: Canvas, w: Float, h: Float) {
        val g = Pen(c)
        val u = min(w, h * 1.35f)
        g.fill(col(0x150A26)); g.rect(0f, 0f, w, h)
        val big = max(w, h)
        val blobs = arrayOf(
            floatArrayOf(0xFF2E88.toFloat(), 0.12f, 0.18f, 0.62f), floatArrayOf(0xFF9A3C.toFloat(), 0.88f, 0.14f, 0.56f),
            floatArrayOf(0x23D3FF.toFloat(), 0.20f, 0.88f, 0.60f), floatArrayOf(0x7C4DFF.toFloat(), 0.84f, 0.82f, 0.62f),
            floatArrayOf(0xFFD83D.toFloat(), 0.50f, 0.52f, 0.34f),
        )
        for (b in blobs) {
            val k = b[0].toInt()
            g.fill(rad(w * b[1], h * b[2], big * b[3], 0f to col(k, 0.92f), 0.5f to col(k, 0.40f), 1f to col(k, 0f)))
            g.disc(w * b[1], h * b[2], big * b[3])
        }
        // ribbons of colour
        class Ribbon(val k1: Int, val k2: Int, val p: FloatArray, val wd: Float)
        val ribbons = arrayOf(
            Ribbon(0xFF5FA2, 0xFFC24D, floatArrayOf(-0.1f, 0.62f, 0.30f, 0.05f, 0.62f, 1.10f, 1.1f, 0.40f), 0.20f),
            Ribbon(0x38E1FF, 0x9B5CFF, floatArrayOf(-0.1f, 0.20f, 0.35f, 0.95f, 0.70f, -0.10f, 1.1f, 0.78f), 0.15f),
            Ribbon(0xFFE66D, 0xFF4F9A, floatArrayOf(-0.1f, 0.95f, 0.40f, 0.60f, 0.60f, 0.40f, 1.1f, 0.05f), 0.11f),
        )
        g.p.strokeCap = Paint.Cap.ROUND
        for (rb in ribbons) {
            for (pass in 0..1) {
                val a = if (pass == 1) 0.78f else 0.30f
                g.line(lin(0f, 0f, w, h, 0f to col(rb.k1, a), 1f to col(rb.k2, a)), u * rb.wd * (if (pass == 1) 0.55f else 1f))
                val pa = g.path
                pa.reset(); pa.moveTo(w * rb.p[0], h * rb.p[1])
                pa.cubicTo(w * rb.p[2], h * rb.p[3], w * rb.p[4], h * rb.p[5], w * rb.p[6], h * rb.p[7])
                c.drawPath(pa, g.p)
            }
        }
        g.p.strokeCap = Paint.Cap.BUTT
        // soft light and a little shade at the foot
        g.fill(rad(w * 0.30f, h * 0.30f, u * 0.5f, 0f to col(0xFFFFFF, 0.22f), 1f to col(0xFFFFFF, 0f)))
        g.disc(w * 0.30f, h * 0.30f, u * 0.5f)
        g.fill(lin(0f, 0f, 0f, h, 0f to col(0x10061C, 0.20f), 0.5f to col(0x10061C, 0.05f), 1f to col(0x10061C, 0.42f)))
        g.rect(0f, 0f, w, h)
    }

    // ------------------------------------------------------------------ galaxy

    private fun galaxy(c: Canvas, w: Float, h: Float) {
        val g = Pen(c)
        val r = Rng(5)
        val u = min(w, h * 1.35f)
        val big = max(w, h)
        g.fill(rad(w * 0.30f, h * 0.35f, big * 0.95f, 0f to col(0x1C0C3E), 0.55f to col(0x0B0620), 1f to col(0x04020C)))
        g.rect(0f, 0f, w, h)
        val clouds = arrayOf(
            floatArrayOf(0xC2338F.toFloat(), 0.12f, 0.80f), floatArrayOf(0x6A32D6.toFloat(), 0.28f, 0.62f), floatArrayOf(0x2D59E0.toFloat(), 0.46f, 0.50f),
            floatArrayOf(0x19B5C9.toFloat(), 0.64f, 0.36f), floatArrayOf(0x8B3CE0.toFloat(), 0.80f, 0.22f), floatArrayOf(0xE0457B.toFloat(), 0.95f, 0.12f),
        )
        for (cl in clouds) {
            val k = cl[0].toInt()
            for (j in 0 until 3) {
                val x = w * (cl[1] + 0.10f * (r.next() - 0.5f))
                val y = h * (cl[2] + 0.14f * (r.next() - 0.5f))
                val rr = big * (0.20f + 0.14f * r.next())
                g.fill(rad(x, y, rr, 0f to col(k, 0.46f), 0.5f to col(k, 0.16f), 1f to col(k, 0f)))
                g.disc(x, y, rr)
            }
        }
        for (j in 0 until 5) {
            val t = 0.15f + 0.7f * r.next()
            val x = w * t
            val y = h * (0.95f - 0.8f * t + 0.1f * (r.next() - 0.5f))
            val rr = big * (0.10f + 0.08f * r.next())
            g.fill(rad(x, y, rr, 0f to col(0x05030F, 0.52f), 1f to col(0x05030F, 0f)))
            g.disc(x, y, rr)
        }
        for (i in 0 until 280) {
            val x = r.next() * w
            val y = r.next() * h
            val s = 0.6f + 1.6f * r.next() * r.next()
            val a = 0.30f + 0.70f * r.next()
            val tint = r.next()
            g.fill(col(if (tint < 0.25f) 0xBFD6FF else if (tint < 0.5f) 0xFFF1C8 else 0xFFFFFF, a))
            g.disc(x, y, s * (u / 1000f + 0.5f))
        }
        for (i in 0 until 12) {
            val x = r.next() * w
            val y = r.next() * h
            val rr = u * (0.012f + 0.014f * r.next())
            g.fill(rad(x, y, rr * 2.6f, 0f to col(0xFFFFFF, 0.85f), 0.25f to col(0xBFD6FF, 0.35f), 1f to col(0xBFD6FF, 0f)))
            g.disc(x, y, rr * 2.6f)
            g.line(col(0xFFFFFF, 0.75f), 1.2f)
            val pa = g.path
            pa.reset(); pa.moveTo(x - rr * 2.4f, y); pa.lineTo(x + rr * 2.4f, y); pa.moveTo(x, y - rr * 2.4f); pa.lineTo(x, y + rr * 2.4f)
            c.drawPath(pa, g.p)
        }
    }

    // ------------------------------------------------------------------ retro sunset with a neon grid

    private fun synth(c: Canvas, w: Float, h: Float) {
        val g = Pen(c)
        val r = Rng(9)
        val u = min(w, h * 1.35f)
        val hy = h * 0.58f
        val sky = lin(0f, 0f, 0f, hy, 0f to col(0x0A0220), 0.42f to col(0x37106A), 0.80f to col(0xD02F95), 1f to col(0xFF8A4C))
        g.fill(sky); g.rect(0f, 0f, w, hy + 1)
        for (i in 0 until 40) {
            val x = r.next() * w
            val y = r.next() * hy * 0.5f
            g.fill(col(0xFFFFFF, 0.25f + 0.5f * r.next())); g.rect(x, y, x + 1.6f, y + 1.6f)
        }
        val sx = w * 0.5f
        val sr = u * 0.19f
        val sy = hy - sr * 0.30f
        c.save(); c.clipRect(0f, 0f, w, hy + 1)
        g.fill(rad(sx, sy, sr * 2.2f, 0f to col(0xFF4FB0, 0.55f), 0.5f to col(0xFF4FB0, 0.16f), 1f to col(0xFF4FB0, 0f)))
        g.disc(sx, sy, sr * 2.2f); c.restore()
        c.save(); c.clipRect(0f, 0f, w, hy)
        g.fill(lin(0f, sy - sr, 0f, sy + sr, 0f to col(0xFFE94D), 0.5f to col(0xFF9A3D), 1f to col(0xFF2F9E)))
        g.disc(sx, sy, sr)
        g.fill(sky)
        for (i in 0 until 7) {            // stripes cut out of the lower half of the sun
            val t = i / 6f
            val y = sy + sr * (0.02f + 0.92f * t)
            val th = sr * (0.03f + 0.10f * t)
            g.rect(sx - sr - 2, y, sx + sr + 2, y + th)
        }
        c.restore()
        // mountains on the horizon
        val peaks = arrayOf(0.00f to 0.10f, 0.07f to 0.36f, 0.12f to 0.20f, 0.20f to 0.52f, 0.28f to 0.24f, 0.34f to 0.30f, 0.40f to 0.08f)
        val peaks2 = arrayOf(0.62f to 0.10f, 0.70f to 0.30f, 0.76f to 0.18f, 0.84f to 0.58f, 0.91f to 0.26f, 0.96f to 0.40f, 1.00f to 0.12f)
        g.fill(col(0x2A0B52))
        val pa = g.path
        pa.reset(); pa.moveTo(0f, hy)
        for ((x, y) in peaks) pa.lineTo(w * x, hy - h * 0.15f * y)
        pa.lineTo(w * 0.42f, hy); pa.lineTo(w * 0.60f, hy)
        for ((x, y) in peaks2) pa.lineTo(w * x, hy - h * 0.15f * y)
        pa.lineTo(w, hy); pa.close()
        c.drawPath(pa, g.p)
        g.line(col(0x35E8FF, 0.80f), 1.6f); g.p.strokeJoin = Paint.Join.ROUND
        pa.reset(); pa.moveTo(0f, hy - h * 0.015f)
        for ((x, y) in peaks) pa.lineTo(w * x, hy - h * 0.15f * y)
        c.drawPath(pa, g.p)
        pa.reset(); pa.moveTo(w * 0.60f, hy)
        for ((x, y) in peaks2) pa.lineTo(w * x, hy - h * 0.15f * y)
        c.drawPath(pa, g.p)
        // the floor with its perspective grid
        g.fill(lin(0f, hy, 0f, h, 0f to col(0x2A0A55), 0.25f to col(0x14052C), 1f to col(0x070114)))
        g.rect(0f, hy, w, h)
        g.fill(lin(0f, hy - h * 0.04f, 0f, hy + h * 0.06f, 0f to col(0xFF4FB0, 0f), 0.4f to col(0xFF4FB0, 0.55f), 1f to col(0xFF4FB0, 0f)))
        g.rect(0f, hy - h * 0.04f, w, hy + h * 0.06f)
        for (pass in 0..1) {
            g.line(col(if (pass == 1) 0xFF5CC8 else 0xFF2F9E, if (pass == 1) 0.95f else 0.22f), if (pass == 1) 1.4f else 4.5f)
            pa.reset()
            for (i in -16..16) { pa.moveTo(sx + i * w * 0.012f, hy); pa.lineTo(sx + i * w * 0.20f, h) }
            for (k in 1..9) { val t = k / 9f; val y = hy + (h - hy) * t * t; pa.moveTo(0f, y); pa.lineTo(w, y) }
            c.drawPath(pa, g.p)
        }
        g.fill(lin(0f, h * 0.72f, 0f, h, 0f to col(0x07010F, 0f), 1f to col(0x07010F, 0.35f)))
        g.rect(0f, h * 0.72f, w, h)
    }
}

/**
 * A scene (or the user's own photo) as the background behind the whole keyboard. The picture is made once, in the background, at the size
 * it is shown at; until it is ready (a moment) the two plain colours of the theme are shown, and it is stretched when the keyboard's height
 * changes a little (the translation bar opening) instead of being drawn again.
 */
class SceneDrawable(
    private val scene: String,
    private val top: Int,
    private val bottom: Int,
    private val photo: () -> Bitmap? = { null },
    /** how much the picture is darkened (0..255): a photo needs some, so the letters stay readable */
    private val scrim: Int = 0,
) : Drawable() {
    private var bmp: Bitmap? = null
    private var bmpW = 0
    private var bmpH = 0
    private var pending = false
    private var gen = 0
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val flat = Paint()
    private val scrimPaint = Paint().apply { color = (scrim shl 24) }
    private val src = Rect()
    private val ui = Handler(Looper.getMainLooper())

    companion object {
        private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "omega-scene").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

        /** draws [scene] (or the photo) into a new bitmap [w] x [h]; also used by the picker's thumbnails */
        fun render(scene: String, w: Int, h: Int, photo: Bitmap?, top: Int, bottom: Int): Bitmap {
            val b = Bitmap.createBitmap(max(8, w), max(8, h), Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            if (scene == "photo" && photo != null) {
                // cover: the photo fills the area, the part that does not fit is cut off
                val s = max(w / photo.width.toFloat(), h / photo.height.toFloat())
                val dw = photo.width * s
                val dh = photo.height * s
                val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                c.drawBitmap(photo, null, android.graphics.RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), p)
            } else {
                val id = if (scene == "photo") "galaxy" else scene
                Scenes.draw(id, c, b.width.toFloat(), b.height.toFloat())
            }
            return b
        }
    }

    private fun ensure() {
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0) return
        val have = bmp
        // the same picture is kept while the height changes by less than a quarter
        if (have != null && w == bmpW && abs(h - bmpH) * 4 < bmpH) return
        if (pending) return
        pending = true
        val myGen = ++gen
        pool.execute {
            val made = try { render(scene, w, h, photo(), top, bottom) } catch (_: Throwable) { null }
            ui.post {
                pending = false
                if (myGen == gen && made != null) {
                    bmp = made; bmpW = made.width; bmpH = made.height
                    invalidateSelf()
                } else if (myGen != gen) {
                    ensure()
                }
            }
        }
    }

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        ensure()
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val m = bmp
        if (m == null) {
            flat.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            c.drawRect(b, flat)
            ensure()
            return
        }
        src.set(0, 0, m.width, m.height)
        c.drawBitmap(m, src, b, paint)
        if (scrim > 0) c.drawRect(b, scrimPaint)
    }

    /** gives the memory back */
    fun release() { gen++; bmp = null }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
