package com.omega.keyboard.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.omega.keyboard.AppLog
import com.omega.keyboard.Bg
import com.omega.keyboard.core.FPoint
import com.omega.keyboard.core.GameProfile
import java.util.concurrent.atomic.AtomicReference

/**
 * Shows for a few seconds what the autonomous mode found on a game's screen by itself (the chat, the buttons), drawn over the game, so
 * the user can see at once whether it found the right places. It never takes a touch: the game underneath keeps working.
 */
object GameMarks {

    /**
     * Draws what [profile] holds over the screen for [ms] and takes it away again before it returns, so that the next picture taken of
     * the screen does not show the marks (the model would read them as part of the game). Blocking: call it from a background thread.
     */
    fun flash(svc: AccessibilityService, profile: GameProfile, ms: Long = 3500L, stop: () -> Boolean = { false }) {
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val shown = AtomicReference<View?>(null)
        Bg.ui {
            try {
                val v = MarksView(svc, profile)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.START
                wm.addView(v, lp)
                shown.set(v)
            } catch (t: Throwable) {
                AppLog.e("gamemarks", "could not show what was found", t)
            }
        }
        val end = System.currentTimeMillis() + ms
        try {
            while (System.currentTimeMillis() < end && !stop()) Thread.sleep(100)
        } catch (_: InterruptedException) {
        }
        Bg.ui { shown.get()?.let { try { wm.removeView(it) } catch (_: Throwable) {} } }
        try { Thread.sleep(250) } catch (_: InterruptedException) {}
    }

    private class MarksView(ctx: Context, private val p: GameProfile) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 13 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        private fun tag(c: Canvas, x: Float, y: Float, label: String, colour: Int) {
            val w = text.measureText(label) + 18 * d
            val h = 24 * d
            val cx = x.coerceIn(w / 2f + 4 * d, width - w / 2f - 4 * d)
            val cy = y.coerceIn(h / 2f + 4 * d, height - h / 2f - 4 * d)
            back.color = colour
            c.drawRoundRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, h / 2f, h / 2f, back)
            c.drawText(label, cx, cy + 4.5f * d, text)
        }

        private fun spot(c: Canvas, pt: FPoint, colour: Int, label: String) {
            val x = pt.x * width
            val y = pt.y * height
            fill.color = (colour and 0x00FFFFFF) or 0x44000000
            c.drawCircle(x, y, 24 * d, fill)
            line.color = colour
            line.pathEffect = null
            c.drawCircle(x, y, 24 * d, line)
            tag(c, x, y - 40 * d, label, colour)
        }

        override fun onDraw(c: Canvas) {
            p.chat?.let {
                val l = it.l * width; val t = it.t * height; val r = it.r * width; val b = it.b * height
                fill.color = 0x2800E676
                c.drawRect(l, t, r, b, fill)
                line.color = 0xFF00E676.toInt()
                line.pathEffect = DashPathEffect(floatArrayOf(16 * d, 9 * d), 0f)
                c.drawRect(l, t, r, b, line)
                tag(c, (l + r) / 2f, t - 16 * d, "الشات", 0xFF00A152.toInt())
            }
            p.open?.let { spot(c, it, 0xFF2979FF.toInt(), "فتح الشات") }
            p.input?.let { spot(c, it, 0xFF00B8D4.toInt(), "خانة الكتابة") }
            p.send?.let { spot(c, it, 0xFFFF9100.toInt(), "إرسال") }
        }
    }
}
