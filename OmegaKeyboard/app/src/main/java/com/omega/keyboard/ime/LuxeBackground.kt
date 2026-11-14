package com.omega.keyboard.ime

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.omega.keyboard.core.ColorMath
import kotlin.math.max

/**
 * Background of the luxe look: a deep vertical gradient, a soft light that falls from the top edge and a thin line along
 * that edge. It is painted once behind the tool row and the keys and never animates, so it costs no battery.
 */
class LuxeBackground(private val top: Int, private val bottom: Int, private val spot: Int, private val hair: Int) : Drawable() {

    private val base = Paint()
    private val light = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint().apply { color = hair }
    private val lineHeight = max(1f, 0.7f * android.content.res.Resources.getSystem().displayMetrics.density)
    private var radius = 0f

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.isEmpty) return
        base.shader = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        radius = bounds.width() * 0.62f
        if (spot != 0) {
            light.shader = RadialGradient(
                bounds.exactCenterX(), bounds.top.toFloat(), radius,
                intArrayOf(spot, ColorMath.withAlpha(spot, ColorMath.alpha(spot) / 3), ColorMath.withAlpha(spot, 0)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.drawRect(b, base)
        if (spot != 0) canvas.drawCircle(b.exactCenterX(), b.top.toFloat(), radius, light)
        if (hair != 0) canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + lineHeight, line)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
