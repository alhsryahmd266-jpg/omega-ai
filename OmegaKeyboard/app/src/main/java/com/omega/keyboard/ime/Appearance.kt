package com.omega.keyboard.ime

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.omega.keyboard.Prefs
import com.omega.keyboard.core.ColorMath
import com.omega.keyboard.core.Palette
import kotlin.math.abs

private fun Context.dpi(v: Float) = (v * resources.displayMetrics.density + 0.5f).toInt()

// ====================================================================== small custom views

/** A square of colours: the hue runs from left to right, white is at the top and black at the bottom. Dragging picks. */
class ColorField(ctx: Context) : View(ctx) {
    var onPick: ((Int) -> Unit)? = null
    private var hue = 0f
    private var tone = 0.5f
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()
    private var hueShader: Shader? = null
    private var topShader: Shader? = null
    private var bottomShader: Shader? = null

    fun setColor(c: Int) {
        val hsv = ColorMath.toHsv(c)
        if (hsv[1] > 0.05f) hue = hsv[0]          // a grey keeps the hue that was there
        tone = ColorMath.toTone(c)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val pad = dp(4f)
        box.set(pad, pad, w - pad, h - pad)
        val red = 0xFFFF0000.toInt()
        hueShader = LinearGradient(
            box.left, 0f, box.right, 0f,
            intArrayOf(red, 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), red),
            null, Shader.TileMode.CLAMP,
        )
        topShader = LinearGradient(0f, box.top, 0f, box.centerY(), 0xFFFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        bottomShader = LinearGradient(0f, box.centerY(), 0f, box.bottom, 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val r = dp(12f)
        paint.shader = hueShader
        c.drawRoundRect(box, r, r, paint)
        paint.shader = topShader
        c.drawRoundRect(box.left, box.top, box.right, box.centerY(), r, r, paint)
        paint.shader = bottomShader
        c.drawRoundRect(box.left, box.centerY(), box.right, box.bottom, r, r, paint)
        paint.shader = null
        val x = box.left + hue / 360f * box.width()
        val y = box.top + (1f - tone) * box.height()
        paint.color = ColorMath.fromTone(hue, tone)
        c.drawCircle(x, y, dp(10f), paint)
        ring.strokeWidth = dp(3f)
        ring.color = 0xFFFFFFFF.toInt()
        c.drawCircle(x, y, dp(10f), ring)
        ring.strokeWidth = dp(1f)
        ring.color = 0x66000000
        c.drawCircle(x, y, dp(12f), ring)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); pick(e.x, e.y) }
            MotionEvent.ACTION_MOVE -> pick(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun pick(x: Float, y: Float) {
        if (box.width() <= 0f || box.height() <= 0f) return
        hue = ((x - box.left) / box.width()).coerceIn(0f, 1f) * 359.9f
        tone = (1f - (y - box.top) / box.height()).coerceIn(0f, 1f)
        invalidate()
        onPick?.invoke(ColorMath.fromTone(hue, tone))
    }
}

/** A row of round colour buttons. Item kinds: 0 a colour, 1 "none / from the theme", 2 rainbow. */
class SwatchRow(ctx: Context) : View(ctx) {
    class Item(val color: Int, val kind: Int)

    var items: List<Item> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    var selectedIndex = -1
        set(v) { if (field != v) { field = v; invalidate() } }
    var ringColor = 0xFF2979FF.toInt()
        set(v) { field = v; invalidate() }
    var edgeColor = 0x55808080
        set(v) { field = v; invalidate() }
    var onPick: ((Item) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downIndex = -1
    private var downX = 0f
    private var downY = 0f
    private val cell get() = dp(42f)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension((items.size * cell + dp(8f)).toInt(), dp(44f).toInt())
    }

    private fun rtl() = layoutDirection == View.LAYOUT_DIRECTION_RTL

    private fun centerX(i: Int): Float {
        val x = dp(4f) + cell * i + cell / 2f
        return if (rtl()) width - x else x
    }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val rad = dp(14f)
        for ((i, it) in items.withIndex()) {
            val cx = centerX(i)
            when (it.kind) {
                1 -> {
                    stroke.strokeWidth = dp(1.5f); stroke.color = edgeColor
                    c.drawCircle(cx, cy, rad, stroke)
                    c.drawLine(cx - rad * 0.65f, cy + rad * 0.65f, cx + rad * 0.65f, cy - rad * 0.65f, stroke)
                }
                2 -> {
                    paint.shader = SweepGradient(cx, cy, intArrayOf(
                        0xFFFF1744.toInt(), 0xFFFFEA00.toInt(), 0xFF39FF14.toInt(), 0xFF00F0FF.toInt(), 0xFF2979FF.toInt(), 0xFFFF2BD6.toInt(), 0xFFFF1744.toInt()), null)
                    c.drawCircle(cx, cy, rad, paint)
                    paint.shader = null
                }
                else -> {
                    paint.color = it.color
                    c.drawCircle(cx, cy, rad, paint)
                    stroke.strokeWidth = dp(1f); stroke.color = edgeColor
                    c.drawCircle(cx, cy, rad, stroke)
                }
            }
            if (i == selectedIndex) {
                stroke.strokeWidth = dp(2.5f); stroke.color = ringColor
                c.drawCircle(cx, cy, rad + dp(5f), stroke)
            }
        }
    }

    private fun indexAt(x: Float): Int {
        for (i in items.indices) if (abs(x - centerX(i)) <= cell / 2f) return i
        return -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downIndex = indexAt(e.x); downX = e.x; downY = e.y; return downIndex >= 0 }
            MotionEvent.ACTION_UP -> {
                val i = indexAt(e.x)
                if (i >= 0 && i == downIndex && abs(e.x - downX) < slop && abs(e.y - downY) < slop) onPick?.invoke(items[i])
            }
        }
        return true
    }
}

/** Shows the chosen colour the way it will look: split in a white and a dark half, like a light and a dark chat box. */
class ColorPreview(ctx: Context) : View(ctx) {
    var mode = 0                 // 0 typed text, 1 a key
    var color = 0
    var rainbow = false
    var highlight = false
    var sample = "أب Aa"
    var ink = 0xFF000000.toInt()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val rf = RectF()

    fun update() = invalidate()

    private fun drawSample(c: Canvas, cx: Float, cy: Float) {
        paint.textSize = dp(19f)
        val fm = paint.fontMetrics
        val total = paint.measureText(sample)
        val base = cy - (fm.ascent + fm.descent) / 2f
        var x = cx - total / 2f
        if (highlight && !rainbow && color != 0) {
            paint.color = color
            c.drawRoundRect(x - dp(3f), cy - dp(13f), x + total + dp(3f), cy + dp(13f), dp(4f), dp(4f), paint)
        }
        var n = 0
        var i = 0
        while (i < sample.length) {
            val cp = sample.codePointAt(i)
            val ch = String(Character.toChars(cp))
            paint.color = when {
                rainbow -> ColorMath.rainbow(n++)
                color == 0 -> ink
                highlight -> ColorMath.onColor(color)
                else -> color
            }
            c.drawText(ch, x, base, paint)
            x += paint.measureText(ch)
            i += Character.charCount(cp)
        }
    }

    override fun onDraw(c: Canvas) {
        rf.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = dp(10f)
        if (mode == 1) {
            paint.color = if (color != 0) color else 0xFF9AA0A6.toInt()
            c.drawRoundRect(rf, r, r, paint)
            paint.color = ColorMath.onColor(paint.color)
            paint.textSize = dp(20f)
            val fm = paint.fontMetrics
            c.drawText("ض  A", width / 2f - paint.measureText("ض  A") / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, paint)
            return
        }
        c.save()
        c.clipRect(0f, 0f, width / 2f, height.toFloat())
        paint.color = 0xFFFFFFFF.toInt()
        c.drawRoundRect(rf, r, r, paint)
        drawSample(c, width / 2f, height / 2f)
        c.restore()
        c.save()
        c.clipRect(width / 2f, 0f, width.toFloat(), height.toFloat())
        paint.color = 0xFF17181C.toInt()
        c.drawRoundRect(rf, r, r, paint)
        drawSample(c, width / 2f, height / 2f)
        c.restore()
    }
}

/** One theme of the picker: a tiny keyboard drawn in that theme's colours with its name below. */
class ThemeCard(ctx: Context, val id: String, private val name: String, private val t: KbTheme) : View(ctx) {
    var selected = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var ringColor = 0xFF2979FF.toInt()
    var labelColor = 0xFF000000.toInt()
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val box = RectF()
    private val key = RectF()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(dp(80f).toInt(), dp(112f).toInt())
    }

    override fun onDraw(c: Canvas) {
        box.set(dp(4f), dp(4f), width - dp(4f), dp(86f))
        val rad = dp(12f)
        if (t.bgStyle >= 1) paint.shader = LinearGradient(0f, box.top, 0f, box.bottom, t.bg, t.bg2, Shader.TileMode.CLAMP) else paint.color = t.bg
        c.drawRoundRect(box, rad, rad, paint)
        paint.shader = null
        if (t.bgStyle == 2) {
            for ((i, col) in t.aurora.withIndex()) {
                val cx = box.left + box.width() * (0.2f + 0.3f * i)
                val cy = box.top + box.height() * (0.3f + 0.3f * (i % 2))
                paint.shader = RadialGradient(cx, cy, box.width() * 0.75f, ColorMath.withAlpha(col, 0xA0), ColorMath.withAlpha(col, 0), Shader.TileMode.CLAMP)
                c.drawRoundRect(box, rad, rad, paint)
            }
            paint.shader = null
        }
        // three rows of tiny keys, the last one with a space bar and the accent-coloured enter key
        val rows = intArrayOf(6, 5, 0)
        val padX = dp(7f)
        val padY = dp(9f)
        val gap = dp(2.6f)
        val kh = (box.height() - 2 * padY - 2 * gap) / 3f
        for (r in 0 until 3) {
            val top = box.top + padY + r * (kh + gap)
            if (r < 2) {
                val n = rows[r]
                val kw = (box.width() - 2 * padX - (n - 1) * gap) / n
                for (i in 0 until n) {
                    val l = box.left + padX + i * (kw + gap)
                    drawKey(c, l, top, l + kw, top + kh, t.key, true)
                }
            } else {
                val total = box.width() - 2 * padX
                val enterW = total * 0.26f
                val spaceW = total - enterW - gap
                drawKey(c, box.left + padX, top, box.left + padX + spaceW, top + kh, t.key, false)
                drawKey(c, box.left + padX + spaceW + gap, top, box.right - padX, top + kh, t.accent, false)
            }
        }
        if (selected) {
            stroke.strokeWidth = dp(2.5f); stroke.color = ringColor
            c.drawRoundRect(box.left - dp(2f), box.top - dp(2f), box.right + dp(2f), box.bottom + dp(2f), rad + dp(2f), rad + dp(2f), stroke)
        } else {
            stroke.strokeWidth = dp(1f); stroke.color = 0x33808080
            c.drawRoundRect(box, rad, rad, stroke)
        }
        text.color = labelColor
        text.textSize = dp(12f)
        c.drawText(name, width / 2f, dp(104f), text)
    }

    private fun drawKey(c: Canvas, l: Float, top: Float, r: Float, b: Float, color: Int, mark: Boolean) {
        key.set(l, top, r, b)
        val rd = dp(3.2f)
        paint.color = color
        c.drawRoundRect(key, rd, rd, paint)
        if (t.neon != 0) {
            stroke.strokeWidth = dp(0.9f); stroke.color = ColorMath.withAlpha(t.neon, 0xD0)
            c.drawRoundRect(key, rd, rd, stroke)
        }
        if (mark) {
            paint.color = ColorMath.withAlpha(t.text, 0xD8)
            val cx = key.centerX()
            val cy = key.centerY()
            c.drawRoundRect(cx - dp(2.2f), cy - dp(1f), cx + dp(2.2f), cy + dp(1f), dp(1f), dp(1f), paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

// ====================================================================== the sheet

/**
 * The appearance sheet that opens under the keyboard (the keys stay visible above it, so every change is seen at once):
 *  - colours: the colour of the text you type, of the keys, the letters, the background, the function keys, the accent
 *  - themes: twelve looks
 *  - size: height, letter size, width (one-handed), where a narrow keyboard sits, and the finger effects.
 * Everything is written to [Prefs] as it changes and reported to the [Host], which redraws the keyboard.
 */
class AppearanceSheet(ctx: Context, private val prefs: Prefs, private val host: Host) : LinearLayout(ctx) {

    interface Host {
        /** colours, theme or effects changed */
        fun onLook()
        /** height, letter size, width or alignment changed */
        fun onSize()
        fun onClose()
    }

    private enum class Target(val label: String) {
        TEXT("الكتابة"), KEY("الأزرار"), LETTER("الحروف"), BG("الخلفية"), FN("الوظائف"), ACCENT("التمييز")
    }

    var theme: KbTheme = Themes.LIGHT
        set(v) { field = v; restyle() }

    private val restylers = ArrayList<() -> Unit>()
    private val tabs = ArrayList<TextView>()
    private val pages = ArrayList<View>()
    private var tab = 0
    private var target = Target.TEXT

    // colours page
    private val swatches = SwatchRow(ctx)
    private val field = ColorField(ctx)
    private val preview = ColorPreview(ctx)
    private val toggles = LinearLayout(ctx)
    private var swatchScroll: HorizontalScrollView? = null
    private var swatchTarget: Target? = null

    // themes page
    private val cards = ArrayList<ThemeCard>()

    init {
        orientation = VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        isClickable = true
        build()
        showTab(0)
    }

    // ------------------------------------------------------------------ building blocks

    private fun round(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dpi(radiusDp).toFloat()
    }

    private fun styleChip(v: TextView, on: Boolean) {
        v.background = round(if (on) theme.accent else ColorMath.blend(sheetColor(), theme.onBg, 0.14f), 16f)
        v.setTextColor(if (on) theme.onAccent else theme.onBg)
    }

    private fun chip(label: String, selected: () -> Boolean, onClick: () -> Unit): TextView {
        val v = TextView(context).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(context.dpi(13f), 0, context.dpi(13f), 0)
            isClickable = true
            setOnClickListener { onClick() }
        }
        v.layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dpi(30f)).also {
            it.marginEnd = context.dpi(6f)
        }
        restylers.add { styleChip(v, selected()) }
        return v
    }

    private fun hScroll(): Pair<HorizontalScrollView, LinearLayout> {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sv = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        return sv to row
    }

    private fun sheetColor(): Int = ColorMath.blend(if (theme.bgStyle >= 1) theme.bg2 else theme.bg, theme.onBg, 0.07f)

    // ------------------------------------------------------------------ structure

    private fun build() {
        val ctx = context
        // header: three tabs and a close button
        val header = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(10f), ctx.dpi(6f), ctx.dpi(8f), ctx.dpi(2f))
        }
        val names = listOf("الألوان", "الثيمات", "الحجم والتأثيرات")
        for ((i, n) in names.withIndex()) {
            val tv = TextView(ctx).apply {
                text = n
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { showTab(i) }
            }
            tabs.add(tv)
            header.addView(tv, LayoutParams(0, ctx.dpi(34f), 1f).also { it.marginEnd = ctx.dpi(6f) })
        }
        val close = ImageView(ctx).apply {
            setImageDrawable(Icons.get(ctx, "close"))
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            setOnClickListener { host.onClose() }
        }
        header.addView(close, LayoutParams(ctx.dpi(38f), ctx.dpi(34f)))
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(42f)))
        restylers.add {
            for ((i, tv) in tabs.withIndex()) styleChip(tv, i == tab)
            close.drawable?.mutate()?.setTint(theme.onBg)
            close.background = round(ColorMath.blend(sheetColor(), theme.onBg, 0.14f), 17f)
        }

        val body = FrameLayout(ctx)
        pages.add(page(buildColorsPage()))
        pages.add(page(buildThemesPage()))
        pages.add(page(buildSizePage()))
        for (p in pages) body.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun page(content: View): View {
        val sv = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false }
        sv.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return sv
    }

    private fun showTab(i: Int) {
        tab = i
        for ((k, p) in pages.withIndex()) p.visibility = if (k == i) View.VISIBLE else View.GONE
        if (i == 0) refreshColors()
        if (i == 1) refreshCards()
        restyle()
    }

    private fun restyle() {
        background = GradientDrawable().apply {
            setColor(sheetColor())
            val r = context.dpi(20f).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        for (f in restylers) f()
        swatches.ringColor = theme.accent
        swatches.edgeColor = ColorMath.withAlpha(theme.onBg, 0x55)
        for (c in cards) { c.ringColor = theme.accent; c.labelColor = theme.onBg }
        invalidate()
    }

    // ------------------------------------------------------------------ colours page

    private fun current(tg: Target): Int = when (tg) {
        Target.TEXT -> prefs.textColor
        Target.KEY -> prefs.keyColor
        Target.LETTER -> prefs.letterColor
        Target.BG -> prefs.bgColor
        Target.FN -> prefs.fnColor
        Target.ACCENT -> prefs.accentColor
    }

    private fun store(tg: Target, c: Int) {
        when (tg) {
            Target.TEXT -> prefs.textColor = c
            Target.KEY -> prefs.keyColor = c
            Target.LETTER -> prefs.letterColor = c
            Target.BG -> prefs.bgColor = c
            Target.FN -> prefs.fnColor = c
            Target.ACCENT -> prefs.accentColor = c
        }
    }

    /** the colour the keyboard shows for [tg] right now (what the theme says when the user has not chosen one) */
    private fun effective(tg: Target): Int = when (tg) {
        Target.TEXT -> if (prefs.textColor != 0) prefs.textColor else 0xFF2979FF.toInt()
        Target.KEY -> ColorMath.opaque(if (theme.glass) theme.popupBg else theme.key)
        Target.LETTER -> theme.text
        Target.BG -> theme.bg
        Target.FN -> ColorMath.opaque(theme.keyFn)
        Target.ACCENT -> theme.accent
    }

    private fun buildColorsPage(): View {
        val ctx = context
        val col = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(ctx.dpi(10f), ctx.dpi(4f), ctx.dpi(10f), ctx.dpi(8f))
        }

        // what to colour
        val (targetScroll, targetRow) = hScroll()
        for (tg in Target.values()) targetRow.addView(chip(tg.label, { target == tg }) { target = tg; refreshColors() })
        targetRow.addView(chip("↺ الأصلي", { false }) { resetTarget() })
        col.addView(targetScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(36f)))

        // quick colours
        val (sw, swRow) = hScroll()
        swatchScroll = sw
        swRow.addView(swatches, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        swatches.onPick = { item -> onSwatch(item) }
        col.addView(sw, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(46f)))

        // any colour + preview and switches
        val low = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        field.onPick = { c -> onField(c) }
        low.addView(field, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val side = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(ctx.dpi(8f), 0, 0, 0) }
        side.addView(preview, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(40f)))
        toggles.orientation = VERTICAL
        side.addView(toggles, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).also { it.topMargin = ctx.dpi(6f) })
        low.addView(side, LayoutParams(ctx.dpi(118f), ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(low, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(100f)).also { it.topMargin = ctx.dpi(4f) })
        return col
    }

    private fun itemsFor(tg: Target): List<SwatchRow.Item> {
        val out = ArrayList<SwatchRow.Item>()
        out.add(SwatchRow.Item(0, 1))
        for (s in if (tg == Target.TEXT) Palette.typed else Palette.general) out.add(SwatchRow.Item(s.color, 0))
        if (tg == Target.TEXT) out.add(SwatchRow.Item(0, 2))
        return out
    }

    private fun refreshColors(fromField: Boolean = false) {
        val items = itemsFor(target)
        if (swatchTarget != target) {
            swatches.items = items
            swatchTarget = target
            swatchScroll?.scrollTo(0, 0)
        }
        val cur = current(target)
        swatches.selectedIndex = when {
            target == Target.TEXT && prefs.textRainbow -> items.indexOfFirst { it.kind == 2 }
            cur == 0 -> 0
            else -> items.indexOfFirst { it.kind == 0 && ColorMath.opaque(it.color) == ColorMath.opaque(cur) }
        }
        if (!fromField) field.setColor(effective(target))

        if (target == Target.TEXT) {
            preview.mode = 0
            preview.color = prefs.textColor
            preview.rainbow = prefs.textRainbow
            preview.highlight = prefs.textHighlight
        } else {
            preview.mode = 1
            preview.color = effective(target)
        }
        preview.update()
        refreshToggles()
        restyle()
    }

    private fun refreshToggles() {
        toggles.removeAllViews()
        fun add(label: String, on: Boolean, click: () -> Unit) {
            val v = TextView(context).apply {
                text = label
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { click() }
            }
            styleChip(v, on)
            toggles.addView(v, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dpi(30f)).also { it.bottomMargin = context.dpi(4f) })
        }
        when (target) {
            Target.TEXT -> add("تظليل فسفوري", prefs.textHighlight) { prefs.textHighlight = !prefs.textHighlight; refreshColors() }
            Target.KEY -> add("زجاجي شفاف", theme.glass) { prefs.glassKeys = if (theme.glass) 0 else 1; host.onLook(); refreshColors() }
            Target.LETTER -> add("توهج نيون", theme.neon != 0) { prefs.neonLetters = if (theme.neon != 0) 0 else 1; host.onLook(); refreshColors() }
            Target.BG -> {
                add("تدرج", theme.bgStyle == 1) { prefs.bgStyle = if (theme.bgStyle == 1) 0 else 1; host.onLook(); refreshColors() }
                add("أورورا متحركة", theme.bgStyle == 2) { prefs.bgStyle = if (theme.bgStyle == 2) 0 else 2; host.onLook(); refreshColors() }
            }
            else -> {}
        }
    }

    private fun onSwatch(item: SwatchRow.Item) {
        when (item.kind) {
            1 -> {
                store(target, 0)
                if (target == Target.TEXT) prefs.textRainbow = false
            }
            2 -> prefs.textRainbow = true
            else -> {
                store(target, item.color)
                if (target == Target.TEXT) prefs.textRainbow = false
            }
        }
        if (target != Target.TEXT) host.onLook()
        refreshColors()
    }

    private fun onField(c: Int) {
        store(target, c)
        if (target == Target.TEXT) prefs.textRainbow = false
        if (target != Target.TEXT) host.onLook()
        refreshColors(fromField = true)
    }

    private fun resetTarget() {
        store(target, 0)
        if (target == Target.TEXT) { prefs.textRainbow = false; prefs.textHighlight = false }
        if (target == Target.KEY) prefs.glassKeys = -1
        if (target == Target.LETTER) prefs.neonLetters = -1
        if (target == Target.BG) prefs.bgStyle = -1
        host.onLook()
        refreshColors()
    }

    // ------------------------------------------------------------------ themes page

    private fun buildThemesPage(): View {
        val ctx = context
        val col = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(ctx.dpi(8f), ctx.dpi(6f), ctx.dpi(8f), ctx.dpi(6f)) }
        val (sv, row) = hScroll()
        for ((id, _) in Themes.choices) {
            val card = ThemeCard(ctx, id, Themes.cardNames[id] ?: id, Themes.base(ctx, id))
            card.setOnClickListener { pickTheme(id) }
            cards.add(card)
            row.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginEnd = ctx.dpi(6f) })
        }
        col.addView(sv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(118f)))
        val hint = TextView(ctx).apply { text = "اختيار ثيم بيرجّع ألوان الأزرار والخلفية ليه. لون الكتابة بيفضل زي ما هو." ; textSize = 11.5f }
        restylers.add { hint.setTextColor(theme.onBgSub) }
        col.addView(hint, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = ctx.dpi(4f) })
        return col
    }

    private fun refreshCards() {
        for (c in cards) c.selected = c.id == prefs.theme
    }

    private fun pickTheme(id: String) {
        prefs.theme = id
        Themes.resetCustom(prefs)
        host.onLook()
        refreshCards()
    }

    // ------------------------------------------------------------------ size and effects page

    private fun buildSizePage(): View {
        val ctx = context
        val col = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(ctx.dpi(12f), ctx.dpi(4f), ctx.dpi(12f), ctx.dpi(8f)) }
        col.addView(seekRow("الارتفاع", 70, 150, { prefs.heightPct }, { "$it%" }, liveApply = false) { prefs.heightPct = it; host.onSize() })
        col.addView(seekRow("حجم الحروف", 80, 140, { prefs.fontPct }, { "$it%" }, liveApply = true) { prefs.fontPct = it; host.onSize() })
        col.addView(seekRow("العرض", 70, 100, { prefs.widthPct }, { "$it%" }, liveApply = true) { prefs.widthPct = it; host.onSize() })
        val (sv, row) = hScroll()
        row.addView(chip("وسط", { prefs.kbAlign == 0 }) { prefs.kbAlign = 0; host.onSize(); restyle() })
        row.addView(chip("يمين", { prefs.kbAlign == 2 }) { prefs.kbAlign = 2; host.onSize(); restyle() })
        row.addView(chip("شمال", { prefs.kbAlign == 1 }) { prefs.kbAlign = 1; host.onSize(); restyle() })
        row.addView(chip("ضوء الإصبع", { prefs.fxGlow }) { prefs.fxGlow = !prefs.fxGlow; host.onLook(); restyle() })
        row.addView(chip("موجات الضغط", { prefs.fxRipple }) { prefs.fxRipple = !prefs.fxRipple; host.onLook(); restyle() })
        col.addView(sv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(36f)).also { it.topMargin = ctx.dpi(4f) })
        return col
    }

    private fun seekRow(label: String, min: Int, max: Int, get: () -> Int, fmt: (Int) -> String, liveApply: Boolean, set: (Int) -> Unit): View {
        val ctx = context
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val name = TextView(ctx).apply { text = label; textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
        val value = TextView(ctx).apply { textSize = 12.5f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; text = fmt(get()) }
        val sb = SeekBar(ctx).apply {
            this.max = max - min
            progress = (get() - min).coerceIn(0, max - min)
            layoutDirection = View.LAYOUT_DIRECTION_LTR           // small on the left, big on the right
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    value.text = fmt(progress + min)
                    if (fromUser && liveApply) set(progress + min)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    if (s != null) set(s.progress + min)
                }
            })
        }
        row.addView(name, LayoutParams(ctx.dpi(92f), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(sb, LayoutParams(0, ctx.dpi(34f), 1f))
        row.addView(value, LayoutParams(ctx.dpi(46f), ViewGroup.LayoutParams.WRAP_CONTENT))
        restylers.add {
            name.setTextColor(theme.onBg)
            value.setTextColor(theme.accent)
            sb.progressTintList = ColorStateList.valueOf(theme.accent)
            sb.thumbTintList = ColorStateList.valueOf(theme.accent)
        }
        row.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(36f))
        return row
    }

    /** the keyboard asks the sheet to forget a stale position after it was shown again */
    fun reopen() {
        showTab(tab)
    }
}
