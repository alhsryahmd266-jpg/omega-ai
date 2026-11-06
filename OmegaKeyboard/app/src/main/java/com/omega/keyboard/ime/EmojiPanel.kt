package com.omega.keyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.omega.keyboard.Prefs
import com.omega.keyboard.core.ColorMath
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object EmojiData {
    class Section(val name: String, val icon: String, val items: List<String>)

    private val names = listOf("الوجوه", "الأشخاص", "الحيوانات والطبيعة", "الطعام", "السفر والأماكن", "الأنشطة", "الأشياء", "الرموز", "الأعلام")
    private val icons = listOf("emoji_emotions", "emoji_people", "pets", "restaurant", "directions_car", "sports_soccer", "lightbulb", "emoji_symbols", "flag")

    @Volatile private var cache: List<Section>? = null

    /** Parses assets/emoji.txt and drops every emoji this phone's font cannot draw (new Unicode releases). */
    fun load(ctx: Context): List<Section> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val paint = Paint()
            val out = ArrayList<Section>()
            try {
                ctx.assets.open("emoji.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        val bar = line.indexOf('|')
                        if (bar < 0) continue
                        val idx = line.substring(0, bar).toIntOrNull() ?: continue
                        val items = line.substring(bar + 1).split(' ').filter { it.isNotEmpty() && paint.hasGlyph(it) }
                        if (idx in names.indices && items.isNotEmpty()) out.add(Section(names[idx], icons[idx], items))
                    }
                }
            } catch (_: Throwable) {
            }
            cache = out
            return out
        }
    }
}

/** Scrollable emoji grid with a category tab bar, like Gboard's emoji keyboard. */
class EmojiPanel(ctx: Context) : View(ctx) {
    interface Listener {
        fun onEmoji(e: String)
        fun onBackToLetters()
        fun onBackspace()
        fun onBackspaceRepeat()
    }

    var listener: Listener? = null
    var theme: KbTheme = Themes.LIGHT
        set(v) { field = v; invalidate() }

    private val prefs = Prefs.get(ctx)
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val handler = Handler(Looper.getMainLooper())

    private class Sec(val name: String, val icon: String, val items: List<String>, var top: Float = 0f, var height: Float = 0f)

    private var sections: List<Sec> = emptyList()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val icons = HashMap<String, Drawable?>()

    private val scroller = OverScroller(ctx)
    private var scrollY = 0f
    private var maxScroll = 0f
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var cols = 8
    private var cell = 0f
    private var repeatRunnable: Runnable? = null

    private val barH get() = dp(46f)
    private val titleH get() = dp(30f)

    fun reload() {
        val recent = prefs.recentEmoji.split(' ').filter { it.isNotEmpty() }
        val list = ArrayList<Sec>()
        if (recent.isNotEmpty()) list.add(Sec("الأخيرة", "schedule", recent))
        for (s in EmojiData.load(context)) list.add(Sec(s.name, s.icon, s.items))
        sections = list
        relayout()
        scrollY = 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        relayout()
    }

    private fun relayout() {
        if (width == 0) return
        cols = max(6, (width / dp(46f)).toInt())
        cell = width.toFloat() / cols
        var y = 0f
        for (s in sections) {
            s.top = y
            val rows = (s.items.size + cols - 1) / cols
            s.height = titleH + rows * cell
            y += s.height
        }
        maxScroll = max(0f, y - (height - barH))
        scrollY = scrollY.coerceIn(0f, maxScroll)
    }

    private fun icon(name: String): Drawable? = icons.getOrPut(name) { Icons.get(context, name) }

    private fun drawIcon(c: Canvas, name: String, cx: Float, cy: Float, size: Float, color: Int) {
        val d = icon(name) ?: return
        d.setTint(color)
        val h = size / 2f
        d.setBounds((cx - h).toInt(), (cy - h).toInt(), (cx + h).toInt(), (cy + h).toInt())
        d.draw(c)
    }

    private fun currentSection(): Int {
        var idx = 0
        for ((i, s) in sections.withIndex()) if (s.top <= scrollY + 2f) idx = i
        return idx
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        c.drawColor(if (t.bgStyle >= 1) t.bg2 else t.bg)
        val gridH = height - barH
        c.save()
        c.clipRect(0f, 0f, width.toFloat(), gridH)
        emojiPaint.textSize = cell * 0.56f
        titlePaint.textSize = dp(13f)
        titlePaint.color = t.onBgSub
        for (s in sections) {
            val top = s.top - scrollY
            if (top > gridH || top + s.height < 0) continue
            c.drawText(s.name, dp(14f), top + titleH * 0.7f, titlePaint)
            val fm = emojiPaint.fontMetrics
            for ((i, e) in s.items.withIndex()) {
                val r = i / cols
                val col = i % cols
                val cy = top + titleH + r * cell + cell / 2
                if (cy + cell / 2 < 0 || cy - cell / 2 > gridH) continue
                c.drawText(e, col * cell + cell / 2, cy - (fm.ascent + fm.descent) / 2f, emojiPaint)
            }
        }
        c.restore()
        // bottom bar
        fill.color = t.keyFn
        c.drawRect(0f, gridH, width.toFloat(), height.toFloat(), fill)
        val cy = gridH + barH / 2
        // ABC
        val abc = RectF(dp(6f), gridH + dp(6f), dp(58f), height - dp(6f))
        fill.color = t.key
        c.drawRoundRect(abc, dp(10f), dp(10f), fill)
        titlePaint.color = t.text
        titlePaint.textSize = dp(14f)
        titlePaint.textAlign = Paint.Align.CENTER
        c.drawText("أبج", abc.centerX(), cy + dp(5f), titlePaint)
        titlePaint.textAlign = Paint.Align.LEFT
        // tabs
        val tabsLeft = dp(64f)
        val tabsRight = width - dp(58f)
        val n = max(1, sections.size)
        val slot = (tabsRight - tabsLeft) / n
        val cur = currentSection()
        for ((i, s) in sections.withIndex()) {
            val cx = tabsLeft + slot * i + slot / 2
            if (i == cur) {
                fill.color = t.key
                c.drawRoundRect(cx - slot / 2 + dp(1f), gridH + dp(6f), cx + slot / 2 - dp(1f), height - dp(6f), dp(10f), dp(10f), fill)
            }
            drawIcon(c, s.icon, cx, cy, dp(20f), if (i == cur) t.accent else ColorMath.withAlpha(t.onFn, 0xB4))
        }
        drawIcon(c, "backspace", width - dp(30f), cy, dp(22f), t.onFn)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            invalidate()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val gridH = height - barH
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity?.recycle(); velocity = VelocityTracker.obtain()
                velocity?.addMovement(e)
                downX = e.x; downY = e.y; lastY = e.y
                dragging = false
                if (e.y >= gridH && e.x > width - dp(58f)) startRepeat()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                if (e.y < gridH && (dragging || abs(e.y - downY) > slop)) {
                    dragging = true
                    scrollY = (scrollY - (e.y - lastY)).coerceIn(0f, maxScroll)
                    invalidate()
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                stopRepeat()
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    scroller.fling(0, scrollY.toInt(), 0, (-vy).toInt(), 0, 0, 0, maxScroll.toInt())
                    invalidate()
                } else onTap(e.x, e.y)
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> { stopRepeat(); velocity?.recycle(); velocity = null }
        }
        return true
    }

    private fun startRepeat() {
        listener?.onBackspace()
        val r = object : Runnable {
            var n = 0
            override fun run() { listener?.onBackspaceRepeat(); n++; handler.postDelayed(this, if (n > 10) 30L else 55L) }
        }
        repeatRunnable = r
        handler.postDelayed(r, 380)
    }

    private fun stopRepeat() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun onTap(x: Float, y: Float) {
        val gridH = height - barH
        if (y >= gridH) {
            when {
                x < dp(62f) -> listener?.onBackToLetters()
                x > width - dp(58f) -> {}   // backspace already handled on touch-down
                else -> {
                    val tabsLeft = dp(64f)
                    val slot = (width - dp(58f) - tabsLeft) / max(1, sections.size)
                    val i = ((x - tabsLeft) / slot).toInt().coerceIn(0, sections.size - 1)
                    sections.getOrNull(i)?.let { scroller.startScroll(0, scrollY.toInt(), 0, (it.top.coerceIn(0f, maxScroll) - scrollY).toInt(), 250); invalidate() }
                }
            }
            return
        }
        val yy = y + scrollY
        for (s in sections) {
            if (yy >= s.top + titleH && yy < s.top + s.height) {
                val r = ((yy - s.top - titleH) / cell).toInt()
                val col = (x / cell).toInt().coerceIn(0, cols - 1)
                val idx = r * cols + col
                s.items.getOrNull(idx)?.let { pick(it) }
                return
            }
        }
    }

    private fun pick(e: String) {
        val list = prefs.recentEmoji.split(' ').filter { it.isNotEmpty() && it != e }.toMutableList()
        list.add(0, e)
        prefs.recentEmoji = list.take(32).joinToString(" ")
        listener?.onEmoji(e)
    }
}
