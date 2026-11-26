package com.omega.sight.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The hero picture of the main screen: a radar with a sweeping beam. Targets light up when the beam passes them; one of them is a glowing
 * red light, the mark the app looks for. When the capture is running the colours turn green and the beam is faster.
 */
class RadarView(ctx: Context) : View(ctx) {
    var active = false

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beam = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private var angle = 0f
    private var last = SystemClock.uptimeMillis()

    // angle (degrees), distance (0..1), kind (0 body, 1 head, 2 red glow)
    private val blips = arrayOf(
        floatArrayOf(28f, 0.55f, 0f),
        floatArrayOf(112f, 0.78f, 2f),
        floatArrayOf(205f, 0.40f, 0f),
        floatArrayOf(250f, 0.86f, 1f),
        floatArrayOf(318f, 0.63f, 0f),
    )

    init {
        ring.style = Paint.Style.STROKE
        ring.strokeWidth = ctx.dpf(1f)
        line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND
        line.strokeWidth = ctx.dpf(2.2f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, min(w, context.dp(230)))
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = (now - last).coerceIn(0L, 60L)
        last = now
        angle = (angle + dt * (if (active) 0.16f else 0.08f)) % 360f
        val accent = if (active) Pal.GREEN else Pal.CYAN
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.92f

        bg.shader = RadialGradient(cx, cy, r * 1.25f, intArrayOf((accent and 0x00FFFFFF) or 0x40000000, (Pal.VIOLET and 0x00FFFFFF) or 0x14000000, 0), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 1.25f, bg)

        ring.color = (accent and 0x00FFFFFF) or 0x40000000
        for (i in 1..4) canvas.drawCircle(cx, cy, r * i / 4f, ring)
        ring.color = (accent and 0x00FFFFFF) or 0x22000000
        canvas.drawLine(cx - r, cy, cx + r, cy, ring)
        canvas.drawLine(cx, cy - r, cx, cy + r, ring)
        for (k in 0 until 36) {
            val a = k * 10.0 * PI / 180.0
            val len = if (k % 3 == 0) 0.07f else 0.035f
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            ring.color = (accent and 0x00FFFFFF) or 0x55000000
            canvas.drawLine(cx + c * r, cy + s * r, cx + c * r * (1f - len), cy + s * r * (1f - len), ring)
        }

        // the beam
        canvas.save()
        canvas.rotate(angle, cx, cy)
        beam.shader = SweepGradient(
            cx, cy,
            intArrayOf(0, 0, (accent and 0x00FFFFFF) or 0x20000000, (accent and 0x00FFFFFF) or 0xA0000000.toInt()),
            floatArrayOf(0f, 0.72f, 0.95f, 1f),
        )
        canvas.drawCircle(cx, cy, r, beam)
        line.color = (accent and 0x00FFFFFF) or 0xE0000000.toInt()
        canvas.drawLine(cx, cy, cx + r, cy, line)
        canvas.restore()

        // the targets
        for (b in blips) {
            val a = b[0] * PI / 180.0
            val bx = cx + cos(a).toFloat() * r * b[1]
            val by = cy + sin(a).toFloat() * r * b[1]
            val d = (angle - b[0] + 360f) % 360f
            val glowA = max(0f, 1f - d / 230f)
            if (glowA <= 0.02f) continue
            val col = when (b[2].toInt()) {
                1 -> Pal.MAGENTA
                2 -> Pal.RED
                else -> Pal.CYAN
            }
            halo.shader = RadialGradient(bx, by, context.dpf(15f), (col and 0x00FFFFFF) or ((glowA * 150).toInt() shl 24), 0, Shader.TileMode.CLAMP)
            canvas.drawCircle(bx, by, context.dpf(15f), halo)
            dot.color = (col and 0x00FFFFFF) or ((glowA * 255).toInt() shl 24)
            canvas.drawCircle(bx, by, context.dpf(if (b[2] == 2f) 3.2f else 2.6f), dot)
            if (b[2] == 2f) {
                dot.color = (0xFFFFFFFF.toInt() and 0x00FFFFFF) or ((glowA * 255).toInt() shl 24)
                canvas.drawCircle(bx, by, context.dpf(1.2f), dot)
            }
        }

        // the sight in the middle
        line.strokeWidth = context.dpf(1.6f)
        line.color = (Pal.TEXT and 0x00FFFFFF) or 0xCC000000.toInt()
        canvas.drawCircle(cx, cy, r * 0.07f, line)
        dot.color = accent
        canvas.drawCircle(cx, cy, context.dpf(2.4f), dot)
        line.strokeWidth = context.dpf(2.2f)

        postInvalidateOnAnimation()
    }
}
