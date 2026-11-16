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
import com.omega.keyboard.core.Decor
import com.omega.keyboard.core.Languages
import com.omega.keyboard.core.TypedLang

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

    /** [withAuto]: the first row is "work the language out yourself" (the source of the translate bar) */
    fun show(title: String, selected: String, withAuto: Boolean = false) {
        setTitle(title)
        list.removeAllViews()
        if (withAuto) list.addView(row("تلقائي  ·  يعرّف اللغة لوحده", selected = selected == TypedLang.AUTO) { onPick?.invoke(TypedLang.AUTO) })
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

/**
 * Text decoration: tap a look and the whole text of the box takes it (real text, so it arrives as written in any app). While
 * "أثناء الكتابة" is on, the letters are decorated as they are typed.
 */
class DecorPanel(ctx: Context) : ListPanel(ctx) {
    var onPick: ((String) -> Unit)? = null
    var onLive: ((Boolean) -> Unit)? = null
    var onUndo: (() -> Unit)? = null

    private val controls = LinearLayout(ctx)
    private val liveBtn = TextView(ctx)
    private val undoBtn = TextView(ctx)
    private val noneBtn = TextView(ctx)
    private val rows = ArrayList<Pair<String, View>>()
    private var live = false
    private var canUndo = false
    private var noneSelected = false
    private var ready = false

    init {
        controls.orientation = HORIZONTAL
        controls.gravity = Gravity.CENTER_VERTICAL
        controls.setPadding(ctx.dpi(6f), ctx.dpi(0f), ctx.dpi(6f), ctx.dpi(6f))
        fun button(v: TextView, tag: String, weight: Float, onTap: () -> Unit) {
            v.textSize = 13.5f
            v.typeface = Typeface.DEFAULT_BOLD
            v.gravity = Gravity.CENTER
            v.maxLines = 1
            v.setPadding(ctx.dpi(8f), 0, ctx.dpi(8f), 0)
            v.isClickable = true
            v.setOnClickListener { onTap() }
            val lp = LayoutParams(0, ctx.dpi(38f), weight)
            lp.setMargins(ctx.dpi(3f), 0, ctx.dpi(3f), 0)
            controls.addView(v, lp)
            rows.add(tag to v)
        }
        button(liveBtn, "live", 1.9f) { onLive?.invoke(!live) }
        button(undoBtn, "undo", 1f) { if (canUndo) onUndo?.invoke() }
        button(noneBtn, "none", 1f) { onPick?.invoke(Decor.NONE) }
        addView(controls, 1, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        ready = true
        restyle()
    }

    override fun restyle() {
        super.restyle()
        if (!ready) return
        styleControls()
    }

    private fun styleControls() {
        val t = theme
        liveBtn.text = if (live) "أثناء الكتابة: شغّال ✓" else "أثناء الكتابة: مقفول"
        liveBtn.setTextColor(if (live) t.onAccent else t.onFn)
        liveBtn.background = pill(if (live) t.accent else t.keyFn, 19f, context)
        undoBtn.text = "↩ تراجع"
        undoBtn.setTextColor(t.onFn)
        undoBtn.alpha = if (canUndo) 1f else 0.4f
        undoBtn.background = pill(t.keyFn, 19f, context)
        noneBtn.text = "✕ بدون"
        noneBtn.setTextColor(if (noneSelected) t.onAccent else t.onFn)
        noneBtn.background = pill(if (noneSelected) t.accent else t.keyFn, 19f, context)
    }

    /** [field]: what is written in the box (the previews use it), [selected]: the look the box has now, "" = none */
    fun show(field: String, selected: String, live: Boolean, canUndo: Boolean) {
        setTitle("زخرفة النص")
        this.live = live
        this.canUndo = canUndo
        noneSelected = false
        styleControls()
        list.removeAllViews()
        rows.removeAll { it.first !in CONTROLS }
        fun section(s: String) = list.addView(TextView(context).apply {
            text = s
            textSize = 12f
            setTextColor(theme.onBgSub)
            setPadding(context.dpi(16f), context.dpi(8f), context.dpi(16f), context.dpi(2f))
        })
        var last: Decor.Kind? = null
        for (st in Decor.styles) {
            if (st.kind != last) {
                last = st.kind
                section(when (st.kind) {
                    Decor.Kind.ARABIC -> "عربي: حركات وحروف مزخرفة"
                    Decor.Kind.FRAME -> "إطار حوالين الكلام"
                    Decor.Kind.LATIN -> "إنجليزي: خطوط مزخرفة"
                })
            }
            val shown = Decor.apply(st, Decor.sampleFor(st, field))
            val v = row(shown, st.label + if (st.live) "" else "  ·  على الكلام كله", selected = st.id == selected) { onPick?.invoke(st.id) }
            ((v as LinearLayout).getChildAt(0) as TextView).apply { textSize = 18f; setLineSpacing(0f, 1.2f) }
            list.addView(v)
            rows.add(st.id to v)
        }
        list.addView(TextView(context).apply {
            text = "الزخرفة نص حقيقي (مش صورة): بتوصل زي ما هي في واتساب وتليجرام والألعاب وأي مكان."
            textSize = 12f
            setTextColor(theme.onBgSub)
            setPadding(context.dpi(16f), context.dpi(10f), context.dpi(16f), context.dpi(12f))
        })
    }

    /** Preview builds only: where the buttons and rows are on the screen, so the emulator test can tap them by name */
    fun rowsLine(): String {
        val sb = StringBuilder()
        val r = android.graphics.Rect()
        for ((tag, v) in rows) {
            if (!v.isShown || !v.getGlobalVisibleRect(r)) continue
            sb.append("p:").append(tag).append('=').append(r.centerX()).append(',').append(r.centerY()).append(';')
        }
        return sb.toString()
    }

    private companion object {
        val CONTROLS = setOf("live", "undo", "none")
    }
}
