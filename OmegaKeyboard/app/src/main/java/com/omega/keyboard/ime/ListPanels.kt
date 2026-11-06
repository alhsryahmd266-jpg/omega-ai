package com.omega.keyboard.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.omega.keyboard.core.Languages

private fun Context.dpi(v: Float) = (v * resources.displayMetrics.density).toInt()

private fun pill(color: Int, radiusDp: Float, ctx: Context) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusDp * ctx.resources.displayMetrics.density
}

/** Common frame: title row with a back/close button and a scrolling list below. */
abstract class ListPanel(ctx: Context) : LinearLayout(ctx) {
    protected val list = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val title = TextView(ctx)
    private val closeBtn = TextView(ctx)
    var onClose: (() -> Unit)? = null
    var theme: KbTheme = Themes.LIGHT
        set(v) { field = v; restyle() }

    init {
        orientation = VERTICAL
        val head = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(8f), ctx.dpi(2f), ctx.dpi(8f), ctx.dpi(2f))
        }
        closeBtn.apply {
            text = "أبج"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(ctx.dpi(14f), ctx.dpi(8f), ctx.dpi(14f), ctx.dpi(8f))
            setOnClickListener { onClose?.invoke() }
        }
        title.apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setPadding(ctx.dpi(8f), 0, 0, 0) }
        head.addView(closeBtn, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dpi(40f)))
        head.addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(head, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(46f)))
        val sv = ScrollView(ctx).apply { isFillViewport = false; overScrollMode = OVER_SCROLL_NEVER }
        sv.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(sv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        restyle()
    }

    protected fun setTitle(s: String) { title.text = s }

    protected open fun restyle() {
        setBackgroundColor(if (theme.bgStyle >= 1) theme.bg2 else theme.bg)
        title.setTextColor(theme.onBg)
        closeBtn.setTextColor(theme.onFn)
        closeBtn.background = pill(theme.keyFn, 12f, context)
    }

    protected fun row(text: String, sub: String? = null, selected: Boolean = false, onBackground: Boolean = false, onTap: () -> Unit): View {
        val ctx = context
        val box = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(ctx.dpi(14f), ctx.dpi(10f), ctx.dpi(14f), ctx.dpi(10f))
            background = pill(if (onBackground) Color.TRANSPARENT else if (selected) theme.accent else if (theme.glass) theme.popupBg else theme.key, 12f, ctx)
            isClickable = true
            setOnClickListener { onTap() }
        }
        val ink = if (selected) theme.onAccent else if (onBackground) theme.onBg else theme.text
        val inkSub = if (selected) theme.onAccent else if (onBackground) theme.onBgSub else theme.textSub
        box.addView(TextView(ctx).apply {
            this.text = text
            textSize = 15f
            setTextColor(ink)
            maxLines = 3
        })
        if (sub != null) box.addView(TextView(ctx).apply {
            this.text = sub
            textSize = 11.5f
            setTextColor(inkSub)
        })
        val lp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(ctx.dpi(8f), ctx.dpi(3f), ctx.dpi(8f), ctx.dpi(3f))
        box.layoutParams = lp
        return box
    }
}

/** Clipboard history: tap an entry to paste it. */
class ClipboardPanel(ctx: Context) : ListPanel(ctx) {
    var onPick: ((String) -> Unit)? = null
    var onClear: (() -> Unit)? = null

    fun show(items: List<String>) {
        setTitle("الحافظة")
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "الحافظة فاضية. انسخ أي نص وهيظهر هنا."
                textSize = 14f
                setTextColor(theme.onBgSub)
                gravity = Gravity.CENTER
                setPadding(context.dpi(16f), context.dpi(28f), context.dpi(16f), context.dpi(28f))
            })
            return
        }
        for (s in items) list.addView(row(s.take(300)) { onPick?.invoke(s) })
        list.addView(row("مسح الكل", onBackground = true) { onClear?.invoke() })
    }
}

/** Searchable-by-scrolling list of every translation language. */
class LangPanel(ctx: Context) : ListPanel(ctx) {
    var onPick: ((String) -> Unit)? = null

    fun show(title: String, selected: String) {
        setTitle(title)
        list.removeAllViews()
        val fast = Languages.all.filter { it.mlkit }
        val slow = Languages.all.filter { !it.mlkit }
        list.addView(sectionLabel("ترجمة سريعة (بدون النموذج)"))
        for (l in fast) list.addView(row("${l.arabic}  ·  ${l.native}", selected = l.code == selected) { onPick?.invoke(l.code) })
        list.addView(sectionLabel("بالنموذج الذكي المحلي (أبطأ)"))
        for (l in slow) list.addView(row("${l.arabic}  ·  ${l.native}", selected = l.code == selected) { onPick?.invoke(l.code) })
    }

    private fun sectionLabel(s: String) = TextView(context).apply {
        text = s
        textSize = 12f
        setTextColor(theme.onBgSub)
        setPadding(context.dpi(16f), context.dpi(10f), context.dpi(16f), context.dpi(4f))
    }
}

/** Writing helpers that use the local model: fix, formal, casual, shorter. */
class AiPanel(ctx: Context) : ListPanel(ctx) {
    var onPick: ((String) -> Unit)? = null

    fun show() {
        setTitle("تحسين الكتابة بالذكاء المحلي")
        list.removeAllViews()
        list.addView(row("تصحيح الإملاء والنحو", "يصلّح الأخطاء من غير ما يغيّر المعنى") { onPick?.invoke("fix") })
        list.addView(row("أسلوب رسمي", "مناسب للشغل والرسايل المهمة") { onPick?.invoke("formal") })
        list.addView(row("أسلوب ودّي", "عفوي وقريب") { onPick?.invoke("casual") })
        list.addView(row("اختصار", "نفس الفكرة بكلام أقل") { onPick?.invoke("short") })
        list.addView(TextView(context).apply {
            text = "بتشتغل على النص اللي إنت محدده، أو على الخانة كلها لو مفيش تحديد."
            textSize = 12f
            setTextColor(theme.onBgSub)
            setPadding(context.dpi(16f), context.dpi(12f), context.dpi(16f), context.dpi(12f))
        })
    }
}
