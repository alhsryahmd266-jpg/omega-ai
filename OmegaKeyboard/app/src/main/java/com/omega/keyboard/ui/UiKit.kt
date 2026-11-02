package com.omega.keyboard.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.omega.keyboard.ime.Icons
import com.omega.keyboard.ime.Themes

/** All app screens are built in code with these helpers (no appcompat / Material library needed, works on every Android version). */
class Pal(
    val dark: Boolean,
    val bg: Int, val card: Int, val cardAlt: Int,
    val text: Int, val sub: Int,
    val accent: Int, val onAccent: Int,
    val divider: Int, val ok: Int, val warn: Int, val danger: Int,
)

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

object UiKit {

    fun pal(ctx: Context): Pal {
        val dark = Themes.isNight(ctx)
        var accent = if (dark) 0xFF8AB4F8.toInt() else 0xFF0B57D0.toInt()
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                accent = ctx.resources.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600, ctx.theme)
            } catch (_: Throwable) {
            }
        }
        return if (dark) Pal(
            true, 0xFF121317.toInt(), 0xFF1E2025.toInt(), 0xFF2A2D34.toInt(), 0xFFECEDF1.toInt(), 0xFFA2A6B0.toInt(),
            accent, 0xFF0A1C3A.toInt(), 0xFF34363C.toInt(), 0xFF81C995.toInt(), 0xFFFDD663.toInt(), 0xFFF28B82.toInt()
        ) else Pal(
            false, 0xFFF6F8FC.toInt(), 0xFFFFFFFF.toInt(), 0xFFE8EBF0.toInt(), 0xFF1B1C1F.toInt(), 0xFF6A707C.toInt(),
            accent, 0xFFFFFFFF.toInt(), 0xFFD6DAE1.toInt(), 0xFF1E8E3E.toInt(), 0xFFB06000.toInt(), 0xFFD93025.toInt()
        )
    }

    fun shape(ctx: Context, color: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * ctx.resources.displayMetrics.density
            if (strokeDp > 0f) setStroke(ctx.dp(strokeDp).coerceAtLeast(1), strokeColor)
        }

    private fun ripple(ctx: Context, base: Int, radiusDp: Float, rippleColor: Int): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), shape(ctx, base, radiusDp), shape(ctx, Color.WHITE, radiusDp))

    fun label(ctx: Context, s: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false): TextView = TextView(ctx).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    fun spacer(ctx: Context, heightDp: Float): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(heightDp))
    }

    fun icon(ctx: Context, name: String, sizeDp: Float, tint: Int): ImageView = ImageView(ctx).apply {
        setImageDrawable(Icons.get(ctx, name)?.also { it.setTint(tint) })
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
    }

    fun card(ctx: Context, p: Pal): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(ctx, p.card, 20f, p.divider, 1f)
        setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.setMargins(ctx.dp(14), ctx.dp(6), ctx.dp(14), ctx.dp(6))
        }
    }

    fun section(ctx: Context, p: Pal, title: String): TextView = label(ctx, title, 13f, p.accent, true).apply {
        setPadding(ctx.dp(22), ctx.dp(18), ctx.dp(22), ctx.dp(4))
    }

    /** A pill button. [filled]: accent background; otherwise a soft tonal one. */
    fun button(ctx: Context, p: Pal, text: String, filled: Boolean = true, danger: Boolean = false, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 14.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val bgc = when {
                danger -> p.danger
                filled -> p.accent
                else -> p.cardAlt
            }
            val fg = when {
                danger -> Color.WHITE
                filled -> p.onAccent
                else -> p.text
            }
            setTextColor(fg)
            background = ripple(ctx, bgc, 22f, 0x33000000)
            setPadding(ctx.dp(20), 0, ctx.dp(20), 0)
            minHeight = ctx.dp(44)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(44)).also { it.topMargin = ctx.dp(10) }
        }

    /** icon + title + optional subtitle, with a colored status icon on the start side. */
    fun statusRow(ctx: Context, p: Pal, state: Int, title: String, sub: String?): LinearLayout {
        // state: 1 = done, 0 = todo, -1 = problem
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val (name, tint) = when (state) {
            1 -> "check_circle" to p.ok
            -1 -> "error" to p.danger
            else -> "info" to p.warn
        }
        row.addView(icon(ctx, name, 26f, tint))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(ctx, title, 16f, p.text, true))
        if (!sub.isNullOrBlank()) col.addView(label(ctx, sub, 13f, p.sub).apply { setPadding(0, ctx.dp(2), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = ctx.dp(12) })
        return row
    }

    fun switchRow(ctx: Context, p: Pal, title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(22), ctx.dp(10), ctx.dp(18), ctx.dp(10))
            minimumHeight = ctx.dp(56)
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(ctx, title, 16f, p.text))
        if (!sub.isNullOrBlank()) col.addView(label(ctx, sub, 12.5f, p.sub).apply { setPadding(0, ctx.dp(2), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(ctx).apply {
            isChecked = checked
            val on = p.accent
            val off = if (p.dark) 0xFF8E9099.toInt() else 0xFF7A7F8A.toInt()
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(on, off))
            trackTintList = ColorStateList(states, intArrayOf((on and 0x00FFFFFF) or 0x66000000, (off and 0x00FFFFFF) or 0x55000000))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw)
        row.isClickable = true
        row.setOnClickListener { sw.toggle() }
        return row
    }

    /** A row that shows the current value; tapping it runs [onClick] (usually opens a choice dialog). */
    fun valueRow(ctx: Context, p: Pal, title: String, value: String, sub: String? = null, onClick: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(22), ctx.dp(10), ctx.dp(18), ctx.dp(10))
            minimumHeight = ctx.dp(56)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(ctx, title, 16f, p.text))
        if (!sub.isNullOrBlank()) col.addView(label(ctx, sub, 12.5f, p.sub).apply { setPadding(0, ctx.dp(2), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label(ctx, value, 14f, p.accent, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = ctx.dp(170)
        })
        return row
    }

    /** A labelled slider. [show] turns the current value into text. */
    fun seekRow(ctx: Context, p: Pal, title: String, min: Int, max: Int, value: Int, show: (Int) -> String, onChange: (Int) -> Unit): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(22), ctx.dp(10), ctx.dp(22), ctx.dp(8))
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(ctx, title, 16f, p.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = label(ctx, show(value), 14f, p.accent, true)
        head.addView(v)
        col.addView(head)
        val sb = SeekBar(ctx).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            progressTintList = ColorStateList.valueOf(p.accent)
            thumbTintList = ColorStateList.valueOf(p.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    v.text = show(progress + min)
                    if (fromUser) onChange(progress + min)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        col.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return col
    }

    fun divider(ctx: Context, p: Pal): View = View(ctx).apply {
        setBackgroundColor(p.divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).also {
            it.marginStart = ctx.dp(22)
        }
    }

    /** a rounded group that holds setting rows */
    fun group(ctx: Context, p: Pal, vararg rows: View): LinearLayout {
        val g = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(ctx, p.card, 20f, p.divider, 1f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.setMargins(ctx.dp(14), ctx.dp(4), ctx.dp(14), ctx.dp(4))
            }
            clipToOutline = true
        }
        for ((i, r) in rows.withIndex()) {
            if (i > 0) g.addView(divider(ctx, p))
            g.addView(r)
        }
        return g
    }

    fun screen(ctx: Context, p: Pal, title: String, content: LinearLayout, onBack: (() -> Unit)?): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(8), ctx.dp(6), ctx.dp(16), ctx.dp(6))
        }
        if (onBack != null) {
            val b = FrameLayout(ctx).apply {
                isClickable = true
                setOnClickListener { onBack() }
                background = ripple(ctx, 0x00000000, 24f, 0x33000000)
            }
            b.addView(icon(ctx, "arrow_forward", 24f, p.text).apply {
                layoutParams = FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER)
            })
            bar.addView(b, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
        }
        bar.addView(label(ctx, title, 20f, p.text, true).apply { setPadding(ctx.dp(8), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val sv = ScrollView(ctx).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        content.setPadding(0, 0, 0, ctx.dp(28))
        sv.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
}

/** Base class for every screen: Arabic UI, right-to-left layout regardless of the phone's language. */
open class BaseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }
}
