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
 * The one background that is drawn by code: a galaxy, used when a picture theme has no picture to show (the "my picture" looks before a
 * picture is chosen). The pictures of the picture themes themselves are real images (assets/themes, drawn by tools/themes) that
 * [SceneDrawable] shows. A scene is drawn once into a bitmap and afterwards only copied, so typing costs nothing extra.
 */
object Scenes {
    /** paints the galaxy on [c], which is [w] by [h] pixels */
    fun draw(@Suppress("UNUSED_PARAMETER") id: String, c: Canvas, w: Float, h: Float) {
        galaxy(c, w, h)
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

}

/**
 * A picture as the background behind the whole keyboard: one of the keyboard's own backgrounds, or a picture of the user's own (the galaxy
 * of [Scenes] when there is none). The picture is made once, in the background, at the size it is shown at; until it is ready (a moment)
 * the two plain colours of the theme are shown, and it is stretched when the keyboard's height changes a little (the translation bar
 * opening) instead of being drawn again. [scrim] darkens all of it and [scrimTop] its upper part, where the suggestion strip is, so the
 * white text there can always be read.
 */
class SceneDrawable(
    private val scene: String,
    private val top: Int,
    private val bottom: Int,
    /** the picture for an area of the given size, read no bigger than that (called in the background); null when there is none */
    private val photo: (Int, Int) -> Bitmap? = { _, _ -> null },
    private val scrim: Int = 0,
    private val scrimTop: Int = 0,
) : Drawable() {
    private var bmp: Bitmap? = null
    private var bmpW = 0
    private var bmpH = 0
    private var pending = false
    private var gen = 0
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val flat = Paint()
    private val scrimPaint = Paint().apply { color = (scrim.coerceIn(0, 255) shl 24) }
    private val topPaint = Paint()
    private var fadeBottom = 0f
    private val src = Rect()
    private val ui = Handler(Looper.getMainLooper())

    companion object {
        private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "omega-scene").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }

        /** how far down the extra darkening of the top reaches, as a part of the height */
        private const val TOP_FADE = 0.45f

        /** draws the picture (without one: the galaxy) into a new bitmap [w] x [h]; also used by the thumbnails of the picker */
        fun render(w: Int, h: Int, photo: Bitmap?): Bitmap {
            val b = Bitmap.createBitmap(max(8, w), max(8, h), Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            if (photo != null && photo.width > 0 && photo.height > 0) {
                // cover: the picture fills the area, the part that does not fit is cut off
                val s = max(b.width / photo.width.toFloat(), b.height / photo.height.toFloat())
                val dw = photo.width * s
                val dh = photo.height * s
                val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                c.drawBitmap(photo, null, android.graphics.RectF((b.width - dw) / 2, (b.height - dh) / 2, (b.width + dw) / 2, (b.height + dh) / 2), p)
            } else {
                Scenes.draw("galaxy", c, b.width.toFloat(), b.height.toFloat())
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
            val made = try {
                val source = photo(w, h)
                val r = render(w, h, source)
                source?.recycle()
                r
            } catch (_: Throwable) {
                null
            }
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
        if (scrimTop > 0 && b.height() > 0) {
            fadeBottom = b.top + b.height() * TOP_FADE
            topPaint.shader = LinearGradient(0f, b.top.toFloat(), 0f, fadeBottom, (scrimTop.coerceIn(0, 255) shl 24), 0, Shader.TileMode.CLAMP)
        }
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
        if (scrimTop > 0 && topPaint.shader != null) c.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), fadeBottom, topPaint)
    }

    /** gives the memory back */
    fun release() { gen++; bmp = null }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
