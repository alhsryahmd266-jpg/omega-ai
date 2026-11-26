package com.omega.sight.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.omega.sight.Prefs
import com.omega.sight.core.Safety
import com.omega.sight.engine.Hub

/**
 * A small status capsule over the game (an accessibility overlay, so no extra permission): a coloured dot, the frame rate and the number
 * of targets. A tap switches the hands-off aiming test on or off. The area it covers is kept out of the analysis (Hub.ignoreRect).
 */
class Pill(private val svc: AccessibilityService, private val main: Handler) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(svc)
    private var view: TextView? = null
    private var running = false
    private var shown = false

    private fun dp(v: Int): Int = (v * svc.resources.displayMetrics.density + 0.5f).toInt()

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            update()
            main.postDelayed(this, 500)
        }
    }

    fun start() {
        if (running) return
        running = true
        main.post(tick)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        hide()
    }

    private fun show() {
        if (shown) return
        val tv = TextView(svc)
        tv.setTextColor(Color.WHITE)
        tv.textSize = 11f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(10), dp(3), dp(12), dp(3))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(14).toFloat()
        bg.setColor(0xD9101420.toInt())
        bg.setStroke(dp(1), 0x6650E6FF)
        tv.background = bg
        tv.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                Hub.testAim = !Hub.testAim && Hub.stats.running
                update()
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = dp(2)
        try {
            wm.addView(tv, lp)
            view = tv
            shown = true
            tv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> publishRect(v) }
        } catch (_: Throwable) {
            view = null
            shown = false
        }
    }

    private fun hide() {
        val v = view
        if (v != null) {
            try {
                wm.removeView(v)
            } catch (_: Throwable) {
            }
        }
        view = null
        shown = false
        Hub.ignoreRect = null
    }

    private fun publishRect(v: View) {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val m = dp(4)
        Hub.ignoreRect = intArrayOf(loc[0] - m, loc[1] - m, loc[0] + v.width + m, loc[1] + v.height + m)
    }

    private fun update() {
        val st = Hub.stats
        if (!prefs.pill || !st.running) {
            hide()
            return
        }
        show()
        val v = view ?: return
        val dot: Int
        val label: String
        if (Hub.testAim) {
            val touching = com.omega.sight.input.SightService.instance?.userTouching() == true
            if (st.verdict != Safety.Verdict.OK || !st.inGame) {
                dot = 0xFFFFB020.toInt()
                label = "متوقف"
            } else if (touching) {
                dot = 0xFFFF5A5A.toInt()
                label = "لمس"
            } else if (st.nTracks > 0) {
                dot = 0xFF3DFF9A.toInt()
                label = "يصوّب ${st.nTracks}"
            } else {
                dot = 0xFF50E6FF.toInt()
                label = "يراقب"
            }
        } else {
            dot = 0xFF8A93A6.toInt()
            label = "${st.fps.toInt()} · ${st.nTracks}"
        }
        val sp = SpannableString("● $label")
        sp.setSpan(ForegroundColorSpan(dot), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        v.text = sp
    }
}
