package com.omega.sight.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** The colours of the app, in one place. */
object Pal {
    val BG0 = 0xFF070B14.toInt()
    val CARD_TOP = 0xFF121B31.toInt()
    val CARD_BOT = 0xFF0B1222.toInt()
    val STROKE = 0x30FFFFFF
    val TEXT = 0xFFEAF2FF.toInt()
    val DIM = 0xFF8FA0BC.toInt()
    val CYAN = 0xFF3DE0FF.toInt()
    val VIOLET = 0xFF7A6CFF.toInt()
    val GREEN = 0xFF3DFF9A.toInt()
    val AMBER = 0xFFFFB020.toInt()
    val RED = 0xFFFF5A5A.toInt()
    val MAGENTA = 0xFFFF4DD8.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun label(ctx: Context, text: String, sp: Float = 14f, color: Int = Pal.TEXT, bold: Boolean = false): TextView {
    val t = TextView(ctx)
    t.text = text
    t.textSize = sp
    t.setTextColor(color)
    if (bold) t.typeface = Typeface.DEFAULT_BOLD
    t.gravity = Gravity.START or Gravity.CENTER_VERTICAL
    t.setLineSpacing(0f, 1.15f)
    return t
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, ctx: Context? = null): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(w, h)
    if (ctx != null) p.topMargin = ctx.dp(top)
    return p
}

fun glassBg(ctx: Context, radiusDp: Int = 20, accent: Int = 0): GradientDrawable {
    val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Pal.CARD_TOP, Pal.CARD_BOT))
    g.cornerRadius = ctx.dpf(radiusDp.toFloat())
    g.setStroke(ctx.dp(1), if (accent != 0) (accent and 0x00FFFFFF) or 0x55000000 else Pal.STROKE)
    return g
}

/** a glass card with a title; tapping the title folds the content away */
class Card(ctx: Context, title: String, private val accent: Int = Pal.CYAN, startOpen: Boolean = true) : LinearLayout(ctx) {
    val body = LinearLayout(ctx)
    private val chevron = label(ctx, "▾", 16f, Pal.DIM)
    private var open = startOpen

    init {
        orientation = VERTICAL
        background = glassBg(ctx, 22, accent)
        setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(16))
        val head = LinearLayout(ctx)
        head.orientation = HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val bar = View(ctx)
        val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(accent, Pal.VIOLET))
        g.cornerRadius = ctx.dpf(3f)
        bar.background = g
        head.addView(bar, LayoutParams(ctx.dp(4), ctx.dp(20)))
        val t = label(ctx, title, 16f, Pal.TEXT, true)
        val tl = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        tl.marginStart = ctx.dp(10)
        head.addView(t, tl)
        head.addView(chevron)
        head.setOnClickListener { setOpen(!open) }
        addView(head)
        body.orientation = VERTICAL
        val bl = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        bl.topMargin = ctx.dp(12)
        addView(body, bl)
        setOpen(startOpen)
    }

    fun setOpen(v: Boolean) {
        open = v
        body.visibility = if (v) VISIBLE else GONE
        chevron.text = if (v) "▾" else "◂"
    }

    fun add(v: View, topDp: Int = 10) {
        body.addView(v, lp(top = topDp, ctx = context))
    }
}

/** a button with a soft glow behind it; [ghost] = only an outline */
class GlowButton(ctx: Context, text: String, private val c1: Int = Pal.CYAN, private val c2: Int = Pal.VIOLET, private val ghost: Boolean = false) : TextView(ctx) {
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val pad = ctx.dpf(7f)

    init {
        setText(text)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        gravity = Gravity.CENTER
        textSize = 14.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (ghost) c1 else 0xFF031018.toInt())
        val h = ctx.dp(15)
        val v = ctx.dp(11)
        setPadding(h + pad.toInt(), v + pad.toInt(), h + pad.toInt(), v + pad.toInt())
        isClickable = true
        glow.maskFilter = BlurMaskFilter(ctx.dpf(9f), BlurMaskFilter.Blur.NORMAL)
        glow.color = c1
        glow.alpha = if (ghost) 40 else 120
        line.style = Paint.Style.STROKE
        line.strokeWidth = ctx.dpf(1.4f)
        line.color = c1
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fill.shader = LinearGradient(pad, 0f, w - pad, 0f, c1, c2, Shader.TileMode.CLAMP)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val s = if (isPressed) 0.96f else 1f
        scaleX = s
        scaleY = s
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(pad, pad, width - pad, height - pad)
        val r = rect.height() / 2f
        canvas.drawRoundRect(rect, r, r, glow)
        if (ghost) canvas.drawRoundRect(rect, r, r, line) else canvas.drawRoundRect(rect, r, r, fill)
        super.onDraw(canvas)
    }
}

/** a titled slider that shows its value */
class SliderRow(
    ctx: Context,
    title: String,
    hint: String,
    private val min: Float,
    private val max: Float,
    value: Float,
    private val fmt: (Float) -> String,
    private val onChange: (Float) -> Unit,
) : LinearLayout(ctx) {
    private val valueView = label(ctx, "", 13f, Pal.CYAN, true)
    private val seek = SeekBar(ctx)

    init {
        orientation = VERTICAL
        val top = LinearLayout(ctx)
        top.orientation = HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(label(ctx, title, 14f, Pal.TEXT, true), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(valueView)
        addView(top)

        val track = GradientDrawable()
        track.cornerRadius = ctx.dpf(4f)
        track.setColor(0x26FFFFFF)
        track.setSize(0, ctx.dp(6))
        val fillG = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.CYAN, Pal.VIOLET))
        fillG.cornerRadius = ctx.dpf(4f)
        fillG.setSize(0, ctx.dp(6))
        val clip = ClipDrawable(fillG, Gravity.START, ClipDrawable.HORIZONTAL)
        val layers = LayerDrawable(arrayOf(track, clip))
        layers.setId(0, android.R.id.background)
        layers.setId(1, android.R.id.progress)
        seek.progressDrawable = layers
        val thumb = GradientDrawable()
        thumb.shape = GradientDrawable.OVAL
        thumb.setColor(0xFFFFFFFF.toInt())
        thumb.setStroke(ctx.dp(3), Pal.CYAN)
        thumb.setSize(ctx.dp(22), ctx.dp(22))
        seek.thumb = thumb
        seek.thumbOffset = 0
        seek.splitTrack = false
        seek.max = STEPS
        seek.progress = toStep(value)
        seek.setPadding(ctx.dp(11), ctx.dp(8), ctx.dp(11), ctx.dp(8))
        valueView.text = fmt(value)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = fromStep(p)
                valueView.text = fmt(v)
                if (fromUser) onChange(v)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        addView(seek, lp(top = 2, ctx = ctx))
        if (hint.isNotEmpty()) addView(label(ctx, hint, 11.5f, Pal.DIM))
    }

    private fun toStep(v: Float): Int = (((v - min) / (max - min)) * STEPS).toInt().coerceIn(0, STEPS)
    private fun fromStep(p: Int): Float = min + (max - min) * p / STEPS.toFloat()

    companion object {
        private const val STEPS = 1000
    }
}

/** a titled switch */
class SwitchRow(ctx: Context, title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) : LinearLayout(ctx) {
    private val sw = Switch(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val col = LinearLayout(ctx)
        col.orientation = VERTICAL
        col.addView(label(ctx, title, 14f, Pal.TEXT, true))
        if (hint.isNotEmpty()) col.addView(label(ctx, hint, 11.5f, Pal.DIM))
        addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        sw.isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(Pal.CYAN, 0xFFB7C2D6.toInt()))
        sw.trackTintList = ColorStateList(states, intArrayOf(0x663DE0FF, 0x44FFFFFF))
        sw.setOnCheckedChangeListener { _: CompoundButton, v: Boolean -> onChange(v) }
        addView(sw)
        setOnClickListener { sw.toggle() }
    }

    fun set(v: Boolean) {
        sw.isChecked = v
    }
}

/** a row of choices, one selected */
class Segmented(ctx: Context, private val items: List<String>, selected: Int, private val onPick: (Int) -> Unit) : LinearLayout(ctx) {
    private val views = ArrayList<TextView>()
    private var sel = selected

    init {
        orientation = HORIZONTAL
        val bg = GradientDrawable()
        bg.cornerRadius = ctx.dpf(14f)
        bg.setColor(0x1AFFFFFF)
        background = bg
        setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
        for ((i, s) in items.withIndex()) {
            val t = label(ctx, s, 13f, Pal.DIM, true)
            t.gravity = Gravity.CENTER
            t.setPadding(ctx.dp(6), ctx.dp(9), ctx.dp(6), ctx.dp(9))
            t.setOnClickListener {
                select(i)
                onPick(i)
            }
            views.add(t)
            addView(t, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        paint()
    }

    fun select(i: Int) {
        sel = i
        paint()
    }

    private fun paint() {
        for ((i, t) in views.withIndex()) {
            if (i == sel) {
                val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Pal.CYAN, Pal.VIOLET))
                g.cornerRadius = context.dpf(11f)
                t.background = g
                t.setTextColor(0xFF031018.toInt())
            } else {
                t.background = null
                t.setTextColor(Pal.DIM)
            }
        }
    }
}

/** one step of the set-up list: a round mark (a number, or a tick when done), a title, a line of help and an optional button */
class StepRow(ctx: Context, private val number: Int, title: String) : LinearLayout(ctx) {
    private val mark = label(ctx, "$number", 14f, Pal.DIM, true)
    private val sub = label(ctx, "", 12f, Pal.DIM)
    private val action = GlowButton(ctx, "", Pal.CYAN, Pal.VIOLET, true)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        mark.gravity = Gravity.CENTER
        addView(mark, LayoutParams(ctx.dp(30), ctx.dp(30)))
        val col = LinearLayout(ctx)
        col.orientation = VERTICAL
        col.addView(label(ctx, title, 14.5f, Pal.TEXT, true))
        col.addView(sub)
        val cl = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        cl.marginStart = ctx.dp(10)
        cl.marginEnd = ctx.dp(6)
        addView(col, cl)
        addView(action)
        setDone(false, "", null, null)
    }

    /** [btnText] null = no button */
    fun setDone(done: Boolean, help: String, btnText: String?, onClick: (() -> Unit)?) {
        val c = if (done) Pal.GREEN else Pal.DIM
        mark.text = if (done) "✓" else "$number"
        mark.setTextColor(if (done) 0xFF031018.toInt() else Pal.DIM)
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        if (done) g.setColor(c) else {
            g.setColor(0x14FFFFFF)
            g.setStroke(context.dp(1), 0x40FFFFFF)
        }
        mark.background = g
        sub.text = help
        sub.visibility = if (help.isEmpty()) GONE else VISIBLE
        if (btnText == null) {
            action.visibility = GONE
        } else {
            action.visibility = VISIBLE
            action.text = btnText
            action.setOnClickListener { onClick?.invoke() }
        }
    }
}

/** a floating sheet in the app's style */
fun sheet(activity: Activity, title: String, content: View): Dialog {
    val d = Dialog(activity)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val card = LinearLayout(activity)
    card.orientation = LinearLayout.VERTICAL
    card.background = glassBg(activity, 24, Pal.CYAN)
    card.setPadding(activity.dp(18), activity.dp(16), activity.dp(18), activity.dp(16))
    card.layoutDirection = View.LAYOUT_DIRECTION_RTL
    card.addView(label(activity, title, 17f, Pal.TEXT, true))
    val sv = ScrollView(activity)
    sv.addView(content)
    card.addView(sv, lp(top = 10, ctx = activity))
    d.setContentView(card)
    d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    d.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.93f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    d.setCanceledOnTouchOutside(true)
    return d
}
