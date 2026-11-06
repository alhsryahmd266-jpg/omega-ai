package com.omega.keyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.MotionEvent
import android.view.View

/**
 * The bar above the keys: word suggestions, the tool row (autonomous mode, translation, voice, clipboard...),
 * the typed-text translation controls and short status messages.
 */
class StripView(ctx: Context) : View(ctx) {

    enum class Mode { SUGGEST, TOOLBAR, TRANSLATE, STATUS }

    data class Tool(val id: String, val icon: String, val label: String, val active: Boolean = false)

    interface Listener {
        fun onSuggestion(text: String)
        fun onTool(id: String)
        fun onToggleToolbar()
        fun onMic()
        fun onTranslateAction(id: String)
        fun onStatusAction(id: String)
    }

    var listener: Listener? = null
    var theme: KbTheme = Themes.LIGHT
        set(v) { field = v; invalidate() }

    var mode: Mode = Mode.SUGGEST
        set(v) { if (field != v) { field = v; invalidate() } }

    var suggestions: List<String> = emptyList()
        set(v) { field = v; invalidate() }

    var tools: List<Tool> = emptyList()
        set(v) { field = v; invalidate() }

    // typed-text translation bar
    var targetName: String = ""
        set(v) { field = v; invalidate() }
    var busy: Boolean = false
        set(v) { field = v; invalidate() }
    var canUndo: Boolean = false
        set(v) { field = v; invalidate() }

    // status bar
    var statusText: String = ""
        set(v) { field = v; invalidate() }
    var statusActionId: String = ""
    var statusActionLabel: String = ""
    var statusIcon: String = "smart_toy"
    var statusWarn: Boolean = false

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density }
    private val icons = HashMap<String, Drawable?>()
    private val hits = ArrayList<Pair<RectF, String>>()
    private var pressedTag: String? = null

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(46f).toInt())
    }

    private fun icon(name: String): Drawable? = icons.getOrPut(name) { Icons.get(context, name) }

    private fun drawIcon(c: Canvas, name: String, cx: Float, cy: Float, size: Float, color: Int) {
        val d = icon(name) ?: return
        d.setTint(color)
        val h = size / 2f
        d.setBounds((cx - h).toInt(), (cy - h).toInt(), (cx + h).toInt(), (cy + h).toInt())
        d.draw(c)
    }

    private fun addHit(l: Float, t: Float, r: Float, b: Float, tag: String): RectF {
        val rect = RectF(l, t, r, b)
        hits.add(rect to tag)
        return rect
    }

    private fun pressBg(c: Canvas, r: RectF, tag: String, radius: Float) {
        if (pressedTag == tag) {
            fill.color = theme.keyFn
            c.drawRoundRect(r, radius, radius, fill)
        }
    }

    private fun drawText(c: Canvas, s: String, cx: Float, cy: Float, maxW: Float) {
        val shown = TextUtils.ellipsize(s, tp, maxW, TextUtils.TruncateAt.END).toString()
        val fm = tp.fontMetrics
        c.drawText(shown, cx, cy - (fm.ascent + fm.descent) / 2f, tp)
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        if (t.bgStyle == 0) c.drawColor(t.bg)       // gradient and aurora looks are painted behind the whole keyboard
        hits.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h / 2f
        val btn = dp(40f)
        when (mode) {
            Mode.SUGGEST -> {
                val r = addHit(dp(4f), cy - btn / 2, dp(4f) + btn, cy + btn / 2, "toggle")
                pressBg(c, r, "toggle", btn / 2)
                drawIcon(c, "chevron_right", r.centerX(), cy, dp(24f), t.onBgSub)
                val mr = addHit(w - dp(4f) - btn, cy - btn / 2, w - dp(4f), cy + btn / 2, "mic")
                pressBg(c, mr, "mic", btn / 2)
                drawIcon(c, "mic", mr.centerX(), cy, dp(22f), t.onBgSub)
                val left = dp(48f)
                val right = w - dp(48f)
                val slot = (right - left) / 3f
                // order on screen: second | best | third
                val order = listOf(1, 0, 2)
                for ((pos, idx) in order.withIndex()) {
                    val s = suggestions.getOrNull(idx) ?: continue
                    val l = left + pos * slot
                    val rect = addHit(l, 0f, l + slot, h, "s$idx")
                    pressBg(c, RectF(rect.left + dp(3f), dp(5f), rect.right - dp(3f), h - dp(5f)), "s$idx", dp(10f))
                    tp.color = t.onBg
                    tp.textSize = dp(17f)
                    tp.isFakeBoldText = idx == 0
                    drawText(c, s, rect.centerX(), cy, slot - dp(12f))
                    tp.isFakeBoldText = false
                    if (pos > 0) { line.color = t.divider; c.drawLine(l, dp(11f), l, h - dp(11f), line) }
                }
            }
            Mode.TOOLBAR -> {
                val r = addHit(dp(4f), cy - btn / 2, dp(4f) + btn, cy + btn / 2, "toggle")
                pressBg(c, r, "toggle", btn / 2)
                drawIcon(c, "chevron_left", r.centerX(), cy, dp(24f), t.onBgSub)
                val left = dp(48f)
                val slot = (w - left - dp(4f)) / maxOf(1, tools.size)
                val pw = minOf(dp(44f), slot - dp(2f))
                val ph = dp(34f)
                for ((i, tool) in tools.withIndex()) {
                    val l = left + i * slot
                    val rect = addHit(l, 0f, l + slot, h, "t:${tool.id}")
                    val pill = RectF(rect.centerX() - pw / 2, cy - ph / 2, rect.centerX() + pw / 2, cy + ph / 2)
                    // Gboard look: icons only. The microphone sits in a round bubble, a running tool in a filled pill.
                    when {
                        tool.active -> { fill.color = t.accent; c.drawRoundRect(pill, ph / 2, ph / 2, fill) }
                        pressedTag == "t:${tool.id}" -> { fill.color = t.keyFn; c.drawRoundRect(pill, ph / 2, ph / 2, fill) }
                        tool.id == "voice" -> { fill.color = t.key; c.drawCircle(pill.centerX(), cy, ph / 2, fill) }
                    }
                    val ink = when {
                        tool.active -> t.onAccent
                        pressedTag == "t:${tool.id}" -> t.onFn
                        tool.id == "voice" -> t.text
                        else -> t.onBg
                    }
                    drawIcon(c, tool.icon, rect.centerX(), cy, dp(22f), ink)
                }
            }
            Mode.TRANSLATE -> {
                val r = addHit(dp(4f), cy - btn / 2, dp(4f) + btn, cy + btn / 2, "close")
                pressBg(c, r, "close", btn / 2)
                drawIcon(c, "close", r.centerX(), cy, dp(22f), t.onBgSub)
                // go button (right)
                val goW = dp(84f)
                val go = addHit(w - dp(6f) - goW, cy - dp(17f), w - dp(6f), cy + dp(17f), "go")
                fill.color = if (pressedTag == "go") t.keyPressed else t.accent
                c.drawRoundRect(go, dp(17f), dp(17f), fill)
                tp.color = t.onAccent
                tp.textSize = dp(14f)
                tp.isFakeBoldText = true
                drawText(c, if (busy) "..." else "ترجم", go.centerX(), cy, goW - dp(10f))
                tp.isFakeBoldText = false
                var chipRight = go.left - dp(6f)
                if (canUndo) {
                    val u = addHit(chipRight - btn, cy - btn / 2, chipRight, cy + btn / 2, "undo")
                    pressBg(c, u, "undo", btn / 2)
                    drawIcon(c, "undo", u.centerX(), cy, dp(20f), t.onBg)
                    chipRight = u.left - dp(2f)
                }
                // target language chip
                val chip = addHit(dp(50f), cy - dp(17f), chipRight, cy + dp(17f), "lang")
                fill.color = if (pressedTag == "lang") t.keyPressed else t.keyFn
                c.drawRoundRect(chip, dp(17f), dp(17f), fill)
                tp.color = t.onFn
                tp.textSize = dp(14f)
                drawText(c, "إلى: $targetName  ▾", chip.centerX(), cy, chip.width() - dp(14f))
            }
            Mode.STATUS -> {
                val ic = RectF(dp(8f), cy - dp(14f), dp(36f), cy + dp(14f))
                drawIcon(c, statusIcon, ic.centerX(), cy, dp(22f), if (statusWarn) 0xFFE8A200.toInt() else t.accent)
                var right = w - dp(8f)
                if (statusActionLabel.isNotEmpty()) {
                    val pw = dp(86f)
                    val p = addHit(right - pw, cy - dp(16f), right, cy + dp(16f), "status")
                    fill.color = if (pressedTag == "status") t.keyPressed else (if (statusWarn) t.accent else 0xFFD93025.toInt())
                    c.drawRoundRect(p, dp(16f), dp(16f), fill)
                    tp.color = if (statusWarn) t.onAccent else 0xFFFFFFFF.toInt()
                    tp.textSize = dp(14f)
                    tp.isFakeBoldText = true
                    drawText(c, statusActionLabel, p.centerX(), cy, pw - dp(8f))
                    tp.isFakeBoldText = false
                    right = p.left - dp(8f)
                }
                tp.color = t.onBg
                tp.textSize = dp(14f)
                val left = dp(42f)
                drawText(c, statusText, (left + right) / 2f, cy, right - left)
            }
        }
    }

    /** Preview builds only: logs the centre of every button of the strip, so the emulator test can tap them by name. */
    fun dumpHits() {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val sb = StringBuilder()
        for ((r, tag) in hits) sb.append(tag).append('=').append((loc[0] + r.centerX()).toInt()).append(',').append((loc[1] + r.centerY()).toInt()).append(';')
        Log.d("OmegaStrip", sb.toString())
    }

    private fun hitAt(x: Float, y: Float): String? {
        for (i in hits.indices.reversed()) if (hits[i].first.contains(x, y)) return hits[i].second
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressedTag = hitAt(e.x, e.y); invalidate(); return pressedTag != null }
            MotionEvent.ACTION_MOVE -> {
                val cur = hitAt(e.x, e.y)
                if (cur != pressedTag) { pressedTag = null; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                val tag = pressedTag
                pressedTag = null
                invalidate()
                if (tag != null && tag == hitAt(e.x, e.y)) dispatch(tag)
            }
            MotionEvent.ACTION_CANCEL -> { pressedTag = null; invalidate() }
        }
        return true
    }

    private fun dispatch(tag: String) {
        when {
            tag == "toggle" -> listener?.onToggleToolbar()
            tag == "mic" -> listener?.onMic()
            tag.startsWith("s") && tag.length == 2 && tag[1].isDigit() -> suggestions.getOrNull(tag[1] - '0')?.let { listener?.onSuggestion(it) }
            tag.startsWith("t:") -> listener?.onTool(tag.substring(2))
            tag == "status" -> listener?.onStatusAction(statusActionId)
            tag == "close" || tag == "go" || tag == "lang" || tag == "undo" -> listener?.onTranslateAction(tag)
        }
    }
}
