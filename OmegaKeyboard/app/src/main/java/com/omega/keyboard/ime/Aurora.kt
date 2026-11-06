package com.omega.keyboard.ime

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator
import com.omega.keyboard.core.ColorMath
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Background of the "aurora" look: soft coloured lights drifting over a deep gradient.
 * The lights stand still while you are not typing and every key press nudges them along ([kick]), so the keyboard
 * feels alive without drawing a single frame when it is idle (no battery cost).
 */
class AuroraDrawable(private val top: Int, private val bottom: Int, colors: List<Int>) : Drawable() {

    private class Blob(val color: Int, val ax: Float, val ay: Float, val speed: Float, val offset: Float, val size: Float) {
        var shader: Shader? = null
        var radius = 0f
    }

    private val blobs: List<Blob>
    private val basePaint = Paint()
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var anim: ValueAnimator? = null

    init {
        val cs = if (colors.isEmpty()) listOf(0xFF5CF2D6.toInt()) else colors
        val ax = floatArrayOf(0.42f, 0.36f, 0.40f)
        val ay = floatArrayOf(0.30f, 0.40f, 0.28f)
        val speed = floatArrayOf(1.0f, 0.7f, 1.3f)
        val offset = floatArrayOf(0f, 2.1f, 4.2f)
        val size = floatArrayOf(0.62f, 0.56f, 0.50f)
        blobs = cs.mapIndexed { i, col -> Blob(col, ax[i % 3], ay[i % 3], speed[i % 3], offset[i % 3] + 0.9f * (i / 3), size[i % 3]) }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.isEmpty) return
        basePaint.shader = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        val big = max(bounds.width(), bounds.height()).toFloat()
        for (b in blobs) {
            b.radius = b.size * big
            b.shader = RadialGradient(
                0f, 0f, b.radius,
                intArrayOf(ColorMath.withAlpha(b.color, 0x78), ColorMath.withAlpha(b.color, 0x2A), ColorMath.withAlpha(b.color, 0)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.drawRect(b, basePaint)
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        for (blob in blobs) {
            val sh = blob.shader ?: continue
            val a = phase * blob.speed + blob.offset
            val cx = b.left + w * (0.5f + blob.ax * cos(a))
            val cy = b.top + h * (0.5f + blob.ay * sin(a * 0.9f + blob.offset))
            canvas.save()
            canvas.translate(cx, cy)
            lightPaint.shader = sh
            canvas.drawCircle(0f, 0f, blob.radius, lightPaint)
            canvas.restore()
        }
    }

    /** one key press: the lights glide a little further along their paths */
    fun kick() {
        val from = phase
        anim?.cancel()
        val a = ValueAnimator.ofFloat(from, from + 1.1f)
        a.duration = 900
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            phase = it.animatedValue as Float
            invalidateSelf()
        }
        anim = a
        a.start()
    }

    fun stop() {
        anim?.cancel()
        anim = null
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
