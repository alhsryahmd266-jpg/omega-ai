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
import com.omega.keyboard.core.ColorMath
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
    protected val closeBtn = TextView(ctx)
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
        setBackgroundColor(panelColor())
        title.setTextColor(theme.onBg)
        closeBtn.setTextColor(theme.onFn)
        closeBtn.background = pill(theme.keyFn, 12f, context)
    }

    /** the colour of the panel: a picture theme lets a little of its picture show through (frosted), the others are solid */
    protected fun panelColor(): Int = when {
        theme.bgStyle == 3 -> ColorMath.withAlpha(theme.bg2, 0xE4)
        theme.bgStyle >= 1 -> theme.bg2
        else -> theme.bg
    }

    protected fun row(text: String, sub: String? = null, selected: Boolean = false, onBackground: Boolean = false, onTap: () -> Unit): View {
        val ctx = context
        // see-through (glass) keys are no good as a fill for a row of text: the row is a lighter tone of the panel, with the panel's text colour
        val calm = theme.glass && !selected && !onBackground
        val box = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(ctx.dpi(14f), ctx.dpi(10f), ctx.dpi(14f), ctx.dpi(10f))
            background = pill(
                if (onBackground) Color.TRANSPARENT else if (selected) theme.accent
                else if (theme.glass) ColorMath.blend(ColorMath.opaque(if (theme.bgStyle >= 1) theme.bg2 else theme.bg), theme.onBg, 0.14f) else theme.key,
                12f, ctx,
            )
            isClickable = true
            setOnClickListener { onTap() }
        }
        val ink = if (selected) theme.onAccent else if (onBackground || calm) theme.onBg else theme.text
        val inkSub = if (selected) theme.onAccent else if (onBackground || calm) theme.onBgSub else theme.textSub
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
    var onFont: ((String) -> Unit)? = null
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
    fun show(field: String, selected: String, live: Boolean, canUndo: Boolean, font: String = "") {
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
        var fontsDone = false
        // the real Arabic fonts come after the Arabic decorations, before the frames
        fun fonts() {
            fontsDone = true
            section("خطوط عربية حقيقية: رقعة ونسخ وكوفي ونستعليق… (بتظهر على الكيبورد بس)")
            val arabicSample = Decor.sampleFor(Decor.byId("royal"), field).let { if (it.any { c -> Decor.isLetter(c) }) it else "لا يسيء الظن بك" }
            for (f in FontLib.fonts) {
                val v = row(arabicSample, f.label + "  ·  " + f.about, selected = f.id == font) { onFont?.invoke(f.id) }
                ((v as LinearLayout).getChildAt(0) as TextView).apply {
                    textSize = 20f
                    setLineSpacing(0f, 1.15f)
                    FontLib.face(context, f.id)?.let { typeface = it }
                }
                list.addView(v)
                rows.add("f:" + f.id to v)
            }
        }
        for (st in Decor.styles) {
            if (st.kind != last) {
                if (st.kind != Decor.Kind.ARABIC && !fontsDone) fonts()
                last = st.kind
                section(when (st.kind) {
                    Decor.Kind.ARABIC -> "عربي: حركات وحروف مزخرفة (بتوصل نص حقيقي)"
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
            text = "الزخرفة نص حقيقي (مش صورة): بتوصل زي ما هي في أغلب التطبيقات، بس شكل بعض الرموز بيعتمد على خط تليفون اللي بيستقبل.\n" +
                "الخطوط العربية الحقيقية بتظهر على الكيبورد وفي معاينة الكتابة بس: التطبيق اللي بيستلم الرسالة بيعرضها بخطه هو."
            textSize = 12f
            setTextColor(theme.onBgSub)
            setPadding(context.dpi(16f), context.dpi(10f), context.dpi(16f), context.dpi(12f))
        })
    }

    /** Preview builds only: where the buttons and rows are on the screen, so the emulator test can tap them by name */
    fun rowsLine(): String {
        val sb = StringBuilder()
        val r = android.graphics.Rect()
        val inWindow = IntArray(2)
        val onScreen = IntArray(2)
        for ((tag, v) in rows) {
            if (!v.isShown || !v.getGlobalVisibleRect(r)) continue
            // the visible part is measured inside the keyboard's window; a tap needs the place on the screen
            v.getLocationInWindow(inWindow)
            v.getLocationOnScreen(onScreen)
            val x = r.centerX() + onScreen[0] - inWindow[0]
            val y = r.centerY() + onScreen[1] - inWindow[1]
            sb.append("p:").append(tag).append('=').append(x).append(',').append(y).append(';')
        }
        if (closeBtn.isShown) {
            closeBtn.getLocationOnScreen(onScreen)
            sb.append("p:close=").append(onScreen[0] + closeBtn.width / 2).append(',').append(onScreen[1] + closeBtn.height / 2).append(';')
        }
        return sb.toString()
    }

    private companion object {
        val CONTROLS = setOf("live", "undo", "none")
    }
}
