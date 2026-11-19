package com.omega.keyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.omega.keyboard.core.ColorMath
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The bar of looks above the keys while a look is on (like the font bar of phone keyboards that decorate text): a box showing the last
 * thing typed in the look, then a row of chips that slides sideways, each drawn in its own look, the chosen one in yellow, and "Aa" to open
 * the full list. Tapping a chip changes the look at once.
 */
class FontBar(ctx: Context) : View(ctx) {
    interface Listener {
        fun onChip(id: String)
        fun onMore()
    }

    /** [group]: chips of the same group stand together; a wider gap separates two groups */
    class Chip(val id: String, val text: String, val face: Typeface? = null, val scale: Float = 1f, val group: Int = 0)

    var listener: Listener? = null

    var theme: KbTheme = Themes.LIGHT
        set(v) { field = v; invalidate() }

    var chips: List<Chip> = emptyList()
        set(v) { field = v; laidOut = false; invalidate() }

    /** a chip that stays on the left while the others slide (the one that switches the look off), drawn like the others */
    var pinned: Chip? = null
        set(v) { field = v; laidOut = false; invalidate() }

    /** id of the chosen chip (it is drawn in yellow and slid into view) */
    var selected: String = ""
        set(v) { if (field != v) { field = v; scrollToSelected = true; invalidate() } }

    /** what the box on the left shows, in [previewFace] */
    var preview: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var previewFace: Typeface? = null
        set(v) { if (field !== v) { field = v; invalidate() } }
    var previewScale: Float = 1f

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val box = RectF()

    private var laidOut = false
    private var xs = FloatArray(0)          // left edge of every chip inside the scrolling row
    private var ws = FloatArray(0)
    private var rowWidth = 0f
    private var pinW = 0f                   // room the pinned chip takes, with the gap after it (0 without one)
    private var scroll = 0f
    private var scrollToSelected = false
    private var downX = 0f
    private var downScroll = 0f
    private var moved = false

    private val barH get() = dp(46f)
    private val chipH get() = dp(32f)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), barH.toInt())
    }

    private fun previewW(): Float = min(width * 0.27f, dp(120f))
    private fun moreW(): Float = dp(44f)
    private fun pinL(): Float = dp(8f) + previewW() + dp(6f)
    private fun areaL(): Float = pinL() + pinW
    private fun areaR(): Float = width - dp(8f) - moreW() - dp(4f)

    private fun sizeFor(ch: Chip): Float = dp(15f) * ch.scale

    private fun layoutChips() {
        if (laidOut) return
        laidOut = true
        pinW = pinned?.let {
            ink.typeface = it.face ?: Typeface.DEFAULT
            ink.textSize = sizeFor(it)
            max(dp(46f), ink.measureText(it.text) + dp(22f)) + dp(6f)
        } ?: 0f
        xs = FloatArray(chips.size)
        ws = FloatArray(chips.size)
        var x = 0f
        var lastGroup = chips.firstOrNull()?.group ?: 0
        for ((i, ch) in chips.withIndex()) {
            if (ch.group != lastGroup) { x += dp(10f); lastGroup = ch.group }
            ink.typeface = ch.face ?: Typeface.DEFAULT
            ink.textSize = sizeFor(ch)
            val w = max(dp(46f), ink.measureText(ch.text) + dp(22f))
            xs[i] = x
            ws[i] = w
            x += w + dp(6f)
        }
        rowWidth = max(0f, x - dp(6f))
    }

    private fun maxScroll(): Float = max(0f, rowWidth - (areaR() - areaL()))

    private fun ensureSelectedVisible() {
        val i = chips.indexOfFirst { it.id == selected }
        if (i < 0) return
        val view = areaR() - areaL()
        val mid = xs[i] + ws[i] / 2
        scroll = (mid - view / 2).coerceIn(0f, maxScroll())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        scrollToSelected = true
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        if (t.bgStyle == 0) c.drawColor(t.bg)
        layoutChips()
        if (scrollToSelected) { scrollToSelected = false; ensureSelectedVisible() }
        val cy = height / 2f
        val r = dp(9f)
        // the box with the last thing typed
        box.set(dp(8f), cy - chipH / 2, dp(8f) + previewW(), cy + chipH / 2)
        fill.shader = null
        fill.color = ColorMath.withAlpha(t.key, max(0x99, ColorMath.alpha(t.key)))
        c.drawRoundRect(box, r, r, fill)
        if (preview.isNotEmpty()) {
            ink.typeface = previewFace ?: Typeface.DEFAULT
            ink.textSize = dp(16f) * (if (previewFace != null) previewScale else 1f)
            ink.color = t.text
            ink.textAlign = Paint.Align.CENTER
            var shown = preview
            val room = box.width() - dp(14f)
            // the end of the text is what was typed last: keep that part when it does not fit
            // (by whole characters: a decorated letter is two chars, and cutting between them would show a "?" box)
            while (shown.codePointCount(0, shown.length) > 1 && ink.measureText(shown) > room) shown = shown.substring(Character.charCount(shown.codePointAt(0)))
            val fm = ink.fontMetrics
            c.drawText(shown, box.centerX(), cy - (fm.ascent + fm.descent) / 2f, ink)
        }
        // the pinned chip
        pinned?.let { p ->
            box.set(pinL(), cy - chipH / 2, pinL() + pinW - dp(6f), cy + chipH / 2)
            fill.color = ColorMath.withAlpha(t.keyFn, max(0xAA, ColorMath.alpha(t.keyFn)))
            c.drawRoundRect(box, r, r, fill)
            ink.typeface = p.face ?: Typeface.DEFAULT_BOLD
            ink.textSize = sizeFor(p)
            ink.color = t.onFn
            val pm = ink.fontMetrics
            c.drawText(p.text, box.centerX(), cy - (pm.ascent + pm.descent) / 2f, ink)
        }
        // the row of chips
        c.save()
        c.clipRect(areaL(), 0f, areaR(), height.toFloat())
        c.translate(areaL() - scroll, 0f)
        for ((i, ch) in chips.withIndex()) {
            val l = xs[i]
            val on = ch.id == selected
            box.set(l, cy - chipH / 2, l + ws[i], cy + chipH / 2)
            if (box.right - scroll < 0f || box.left - scroll > areaR() - areaL()) continue
            fill.color = if (on) YELLOW else ColorMath.withAlpha(t.key, max(0x99, ColorMath.alpha(t.key)))
            c.drawRoundRect(box, r, r, fill)
            ink.typeface = ch.face ?: Typeface.DEFAULT
            ink.textSize = sizeFor(ch)
            ink.color = if (on) ON_YELLOW else t.text
            ink.textAlign = Paint.Align.CENTER
            val fm = ink.fontMetrics
            c.drawText(ch.text, box.centerX(), cy - (fm.ascent + fm.descent) / 2f, ink)
        }
        c.restore()
        // "Aa": the full list
        box.set(width - dp(8f) - moreW(), cy - chipH / 2, width - dp(8f), cy + chipH / 2)
        fill.color = ColorMath.withAlpha(t.keyFn, max(0xAA, ColorMath.alpha(t.keyFn)))
        c.drawRoundRect(box, r, r, fill)
        ink.typeface = Typeface.DEFAULT_BOLD
        ink.textSize = dp(15f)
        ink.color = t.onFn
        val fm = ink.fontMetrics
        c.drawText("Aa", box.centerX(), cy - (fm.ascent + fm.descent) / 2f, ink)
        ink.typeface = Typeface.DEFAULT
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downScroll = scroll; moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                if (abs(dx) > slop) moved = true
                if (moved) { scroll = (downScroll - dx).coerceIn(0f, maxScroll()); invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) tap(e.x)
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun tap(x: Float) {
        if (x >= width - dp(8f) - moreW() - dp(2f)) { listener?.onMore(); return }
        pinned?.let { p -> if (x >= pinL() && x <= pinL() + pinW - dp(6f)) { listener?.onChip(p.id); return } }
        if (x < areaL() || x > areaR()) return
        val px = x - areaL() + scroll
        for (i in chips.indices) if (px >= xs[i] && px <= xs[i] + ws[i]) { listener?.onChip(chips[i].id); return }
    }

    /** Preview builds only: where the chips are on the screen (id=x,y), so the emulator test can tap them by name */
    fun chipsLine(): String {
        layoutChips()
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val sb = StringBuilder()
        pinned?.let { sb.append("p:").append(it.id).append('=').append((loc[0] + pinL() + (pinW - dp(6f)) / 2).toInt()).append(',').append((loc[1] + height / 2).toInt()).append(';') }
        for ((i, ch) in chips.withIndex()) {
            val x = areaL() - scroll + xs[i] + ws[i] / 2
            if (x < areaL() || x > areaR()) continue
            sb.append("p:").append(ch.id).append('=').append((loc[0] + x).toInt()).append(',').append((loc[1] + height / 2).toInt()).append(';')
        }
        return sb.toString()
    }

    private companion object {
        const val YELLOW = 0xFFFFC928.toInt()
        const val ON_YELLOW = 0xFF2B2100.toInt()
    }
}
