package com.ahmedxg.gamebot

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

/**
 * Self-test of the touch injection: injects two simultaneous moving pointers into this very screen and
 * reports how many pointers the app really received. Run it before trusting the bot.
 */
class TouchTestActivity : Activity() {
    private lateinit var canvasView: TestView
    private lateinit var info: TextView
    private var maxPointers = 0
    private var moveEvents = 0
    private val live = HashMap<Int, FloatArray>()

    inner class TestView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.pointerCount > maxPointers) maxPointers = e.pointerCount
            if (e.actionMasked == MotionEvent.ACTION_MOVE) moveEvents++
            live.clear()
            if (e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL) {
                for (i in 0 until e.pointerCount) live[e.getPointerId(i)] = floatArrayOf(e.getX(i), e.getY(i))
            }
            invalidate()
            return true
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(Color.rgb(20, 20, 20))
            p.color = Color.GREEN
            for ((id, v) in live) {
                c.drawCircle(v[0], v[1], 40f, p)
                p.color = Color.WHITE
                p.textSize = 36f
                c.drawText("#$id", v[0] + 50f, v[1], p)
                p.color = Color.GREEN
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(this)
        val root = FrameLayout(this)
        canvasView = TestView(this)
        root.addView(canvasView)
        info = TextView(this)
        info.setTextColor(Color.WHITE)
        info.text = "اختبار حقن اللمس: اضغط الزرار وهتشوف نقطتين بتتحركوا لوحدهم"
        root.addView(info, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        val b = Button(this)
        b.text = "ابدأ الاختبار"
        b.setOnClickListener { runTest() }
        root.addView(b, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
    }

    private fun runTest() {
        val cfg = Config.load(this)
        val pair = Backends.create(this, cfg.backend)
        val b = pair.first
        if (b == null) {
            info.text = "مفيش وسيلة تحكم: " + pair.second
            return
        }
        maxPointers = 0
        moveEvents = 0
        info.text = "بيجرّب عن طريق ${pair.second}..."
        val loc = IntArray(2)
        canvasView.getLocationOnScreen(loc)
        val w = canvasView.width.toFloat()
        val h = canvasView.height.toFloat()
        Thread {
            try {
                val x0 = loc[0] + w * 0.25f
                val x1 = loc[0] + w * 0.75f
                val y0 = loc[1] + h * 0.5f
                b.touchDown(0, x0, y0)
                b.flush()
                Thread.sleep(120)
                b.touchDown(1, x1, y0)
                b.flush()
                for (i in 0 until 45) {
                    val a = i / 45f * 6.2832f
                    b.touchMove(0, x0 + 90f * cos(a), y0 + 90f * sin(a))
                    b.touchMove(1, x1 + 90f * sin(a), y0 + 90f * cos(a))
                    b.flush()
                    Thread.sleep(33)
                }
                b.touchUp(1)
                b.touchUp(0)
                b.flush()
                Thread.sleep(300)
            } catch (t: Throwable) {
                Logger.i("test error: ${t.message}")
            } finally {
                b.releaseAll()
                b.close()
            }
            runOnUiThread {
                val ok = maxPointers >= 2
                info.text = (if (ok) "نجح ✔" else "فشل ✖") +
                    " — وصل $maxPointers لمسات في نفس الوقت و$moveEvents حركة (${pair.second})"
                Logger.i("touch test via ${pair.second}: pointers=$maxPointers moves=$moveEvents")
            }
        }.start()
    }
}
