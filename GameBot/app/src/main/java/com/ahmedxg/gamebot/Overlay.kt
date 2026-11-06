package com.ahmedxg.gamebot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.min

/** Small always-on-top status panel with a STOP button (the kill switch while the bot runs). */
class Hud(private val ctx: Context, private val onStop: () -> Unit) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var tv: TextView? = null

    fun show() {
        main.post {
            if (root != null) return@post
            val dp = ctx.resources.displayMetrics.density
            val ll = LinearLayout(ctx)
            ll.orientation = LinearLayout.VERTICAL
            ll.setBackgroundColor(0x99000000.toInt())
            ll.setPadding((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
            val t = TextView(ctx)
            t.setTextColor(Color.GREEN)
            t.textSize = 9f
            t.typeface = Typeface.MONOSPACE
            t.text = "GameBot..."
            val b = Button(ctx)
            b.text = "STOP"
            b.textSize = 10f
            b.setOnClickListener { onStop() }
            ll.addView(t)
            ll.addView(b)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = 4
            lp.y = 4
            lp.alpha = 0.7f
            try {
                wm.addView(ll, lp)
                root = ll
                tv = t
            } catch (e: Throwable) {
                Logger.i("HUD failed: ${e.message}")
            }
        }
    }

    fun update(s: String) {
        main.post { tv?.text = s }
    }

    fun hide() {
        main.post {
            val r = root
            if (r != null) {
                try { wm.removeView(r) } catch (e: Throwable) { }
            }
            root = null
            tv = null
        }
    }
}

/** Full-screen layout editor: drag each marker onto the matching on-screen control of your game. */
class LayoutEditorView(
    ctx: Context,
    private val layout: ControlLayout,
    private val dispW: Int,
    private val dispH: Int
) : View(ctx) {
    private val dp = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loc = IntArray(2)
    private var dragKey: String? = null

    init {
        line.style = Paint.Style.STROKE
        line.strokeWidth = 3 * dp
        text.color = Color.WHITE
        text.textSize = 13 * dp
        text.textAlign = Paint.Align.CENTER
    }

    private fun colorOf(k: String): Int = when (k) {
        "stick" -> Color.rgb(33, 150, 243)
        "look" -> Color.rgb(76, 175, 80)
        "fire" -> Color.rgb(244, 67, 54)
        "jump" -> Color.rgb(255, 193, 7)
        "crouch" -> Color.rgb(156, 39, 176)
        "reload" -> Color.rgb(0, 188, 212)
        else -> Color.rgb(255, 152, 0)
    }

    override fun onDraw(c: Canvas) {
        getLocationOnScreen(loc)
        val short = min(dispW, dispH).toFloat()
        for ((k, p) in layout.points) {
            val x = p[0] * dispW - loc[0]
            val y = p[1] * dispH - loc[1]
            val r = when (k) {
                "stick" -> layout.stickRadius * short
                "look" -> layout.lookRadius * short
                else -> 26 * dp
            }
            fill.color = colorOf(k)
            fill.alpha = 90
            c.drawCircle(x, y, r, fill)
            line.color = colorOf(k)
            c.drawCircle(x, y, r, line)
            c.drawText(k, x, y + 5 * dp, text)
        }
    }

    private fun nearest(rx: Float, ry: Float): String? {
        var best: String? = null
        var bd = Float.MAX_VALUE
        for ((k, p) in layout.points) {
            val d = hypot(p[0] * dispW - rx, p[1] * dispH - ry)
            if (d < bd) { bd = d; best = k }
        }
        return if (bd < 90 * dp) best else null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> dragKey = nearest(e.rawX, e.rawY)
            MotionEvent.ACTION_MOVE -> {
                val k = dragKey
                val p = if (k != null) layout.points[k] else null
                if (p != null) {
                    p[0] = (e.rawX / dispW).coerceIn(0.02f, 0.98f)
                    p[1] = (e.rawY / dispH).coerceIn(0.02f, 0.98f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragKey = null
        }
        return true
    }
}
