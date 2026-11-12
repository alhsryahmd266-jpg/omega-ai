package com.omega.keyboard.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.omega.keyboard.AppLog
import com.omega.keyboard.Bg
import com.omega.keyboard.core.FPoint
import com.omega.keyboard.core.FRect
import com.omega.keyboard.core.GameProfile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Teaches the autonomous mode where a game keeps its chat. A game draws its own screen, so none of this can be read from it: a
 * layer over the whole screen asks the user to drag a frame around the chat messages, then to touch the spot that opens the typing
 * box and the game's send button. Touches on the layer never reach the game. The answer is kept per game and screen orientation.
 */
object Calibrator {

    /** Shows the three steps and waits for the user. Call it from a background thread. Null = cancelled or nobody answered. */
    fun run(svc: AccessibilityService, start: GameProfile?): GameProfile? {
        val latch = CountDownLatch(1)
        var out: GameProfile? = null
        var layer: Layer? = null
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Bg.ui {
            try {
                val l = Layer(svc, start ?: GameProfile()) { p -> out = p; latch.countDown() }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.START
                wm.addView(l, lp)
                layer = l
            } catch (t: Throwable) {
                AppLog.e("calibrate", "could not show the layer", t)
                latch.countDown()
            }
        }
        latch.await(4, TimeUnit.MINUTES)
        Bg.ui { layer?.let { try { wm.removeView(it) } catch (_: Throwable) {} } }
        return out
    }

    private class Layer(ctx: Context, base: GameProfile, private val onFinish: (GameProfile?) -> Unit) : FrameLayout(ctx) {
        private val d = resources.displayMetrics.density
        private val real = GameEyes.realSize(ctx)
        private val rw = real.first
        private val rh = real.second

        private var step = 0                        // 0 the chat frame, 1 the typing spot, 2 the send button
        private var chat: FRect? = base.chat
        private var input: FPoint? = base.input
        private var send: FPoint? = base.send
        private var dragFrom: PointF? = null
        private var dragTo: PointF? = null
        private var finished = false

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x3300E676 }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * d
            color = 0xFF00E676.toInt()
            pathEffect = DashPathEffect(floatArrayOf(18 * d, 10 * d), 0f)
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = Color.WHITE }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        private val loc = IntArray(2)

        private val card = LinearLayout(ctx)
        private val title = TextView(ctx)
        private val hint = TextView(ctx)
        private val row = LinearLayout(ctx)

        init {
            setWillNotDraw(false)
            card.orientation = LinearLayout.VERTICAL
            card.background = GradientDrawable().apply {
                setColor(0xF2202124.toInt())
                cornerRadius = 22 * d
                setStroke((1 * d).toInt().coerceAtLeast(1), 0x40FFFFFF)
            }
            card.setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            title.apply { setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
            hint.apply { setTextColor(0xFFC6D4FF.toInt()); textSize = 13f; setPadding(0, (4 * d).toInt(), 0, (10 * d).toInt()) }
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER
            card.addView(title)
            card.addView(hint)
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val w = minOf(resources.displayMetrics.widthPixels - (24 * d).toInt(), (430 * d).toInt())
            val lp = LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.topMargin = (36 * d).toInt()
            lp.bottomMargin = (36 * d).toInt()
            addView(card, lp)
            render()
        }

        private fun pill(text: String, fill: Int, onClick: () -> Unit): TextView = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply { setColor(fill); cornerRadius = 18 * d }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                it.leftMargin = (4 * d).toInt(); it.rightMargin = (4 * d).toInt()
            }
            setOnClickListener { onClick() }
        }

        private fun render() {
            row.removeAllViews()
            val grey = 0xFF5F6368.toInt()
            val red = 0xFFD93025.toInt()
            val blue = 0xFF1A73E8.toInt()
            when (step) {
                0 -> {
                    title.text = "١/٣  شات اللعبة"
                    hint.text = "اسحب بإصبعك مربع حوالين رسايل الشات في اللعبة (الكلام اللي بيكتبه اللاعبين). كل ما المربع يبقى أضيق على الرسايل، القراية أسرع وأدق."
                    if (chat != null) row.addView(pill("التالي", blue) { step = 1; render() })
                    row.addView(pill("إلغاء", red) { finish(null) })
                }
                1 -> {
                    title.text = "٢/٣  خانة الكتابة"
                    hint.text = "دوس على المكان اللي بتدوس عليه في اللعبة عشان تبدأ تكتب رسالة."
                    row.addView(pill("رجوع", grey) { step = 0; render() })
                    row.addView(pill("تخطي", grey) { input = null; step = 2; render() })
                    row.addView(pill("إلغاء", red) { finish(null) })
                }
                else -> {
                    title.text = "٣/٣  زرار الإرسال"
                    hint.text = "دوس على زرار الإرسال بتاع اللعبة. لو بتبعت بزرار الإدخال في الكيبورد دوس «مفيش زرار»."
                    row.addView(pill("رجوع", grey) { step = 1; render() })
                    row.addView(pill("مفيش زرار", blue) { send = null; finish(GameProfile(chat, input, null)) })
                    row.addView(pill("إلغاء", red) { finish(null) })
                }
            }
            invalidate()
        }

        private fun finish(p: GameProfile?) {
            if (finished) return
            finished = true
            onFinish(p)
        }

        /** the card stays out of the way of the finger: at the bottom while something is drawn in the top half, else at the top */
        private fun placeCard(atBottom: Boolean) {
            val lp = card.layoutParams as LayoutParams
            val g = (if (atBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
            if (lp.gravity != g) {
                lp.gravity = g
                card.layoutParams = lp
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (step == 0) {
                        dragFrom = PointF(e.rawX, e.rawY)
                        dragTo = PointF(e.rawX, e.rawY)
                        placeCard(e.rawY < rh / 2f)
                    }
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (step == 0 && dragFrom != null) { dragTo = PointF(e.rawX, e.rawY); invalidate() }
                }
                MotionEvent.ACTION_UP -> {
                    if (step == 0) endDrag(e.rawX, e.rawY) else tapped(e.rawX, e.rawY)
                }
                MotionEvent.ACTION_CANCEL -> { dragFrom = null; dragTo = null; invalidate() }
            }
            return true
        }

        private fun endDrag(x: Float, y: Float) {
            val a = dragFrom
            dragFrom = null
            dragTo = null
            if (a == null) return
            val r = FRect.fromPx(a.x, a.y, x, y, rw, rh)
            if (r.width < 0.08f || r.height < 0.05f) {
                hint.text = "المربع صغير قوي. اسحب مربع أكبر حوالين الرسايل."
            } else {
                chat = r
                render()
            }
            invalidate()
        }

        private fun tapped(x: Float, y: Float) {
            val p = FPoint.fromPx(x, y, rw, rh)
            if (step == 1) {
                input = p
                step = 2
                render()
            } else {
                send = p
                invalidate()
                // a moment to see where the spot landed, then done
                postDelayed({ finish(GameProfile(chat, input, send)) }, 450)
            }
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            getLocationOnScreen(loc)
            val ox = loc[0].toFloat()
            val oy = loc[1].toFloat()
            c.drawColor(0x66000000)
            val a = dragFrom
            val b = dragTo
            val rect: RectF? = when {
                a != null && b != null -> RectF(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
                else -> chat?.px(rw, rh)?.let { RectF(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat()) }
            }
            if (rect != null) {
                rect.offset(-ox, -oy)
                c.drawRect(rect, fill)
                c.drawRect(rect, frame)
            }
            input?.let { mark(c, it.pxX(rw) - ox, it.pxY(rh) - oy, 0xFF1A73E8.toInt(), "كتابة") }
            send?.let { mark(c, it.pxX(rw) - ox, it.pxY(rh) - oy, 0xFFFF6D00.toInt(), "إرسال") }
        }

        private fun mark(c: Canvas, x: Float, y: Float, colour: Int, name: String) {
            dot.color = colour
            c.drawCircle(x, y, 15 * d, dot)
            c.drawCircle(x, y, 15 * d, ring)
            c.drawText(name, x, y - 22 * d, label)
        }
    }
}
