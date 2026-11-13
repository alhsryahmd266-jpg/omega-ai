package com.omega.keyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.omega.keyboard.core.ColorMath

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
        /** the round "send in my colour" button */
        fun onColourSend()
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

    // the round "send in my colour" button: shown while a text colour is chosen; it wears that colour
    var colourSend: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var swatch: Int = 0xFF2979FF.toInt()
        set(v) { if (field != v) { field = v; invalidate() } }
    var swatchRainbow: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    var tools: List<Tool> = emptyList()
        set(v) { field = v; invalidate() }

    // typed-text translation bar
    var targetName: String = ""
        set(v) { field = v; invalidate() }
    var busy: Boolean = false
        set(v) { field = v; invalidate() }
    var canUndo: Boolean = false
        set(v) { field = v; invalidate() }
    /** the live translation of what is typed (a tap puts it into the box), or the note shown while there is none */
    var preview: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var previewNote: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }

    // status bar
    var statusText: String = ""
        set(v) { field = v; invalidate() }
    var statusActionId: String = ""
    var statusActionLabel: String = ""
    var statusIcon: String = "robot"
    var statusWarn: Boolean = false
    /** the message is not a warning and its button is not a "stop": draw the button in the accent colour */
    var statusCalm: Boolean = false

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

    // ------------------------------------------------------------------ the round buttons

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)

    /** both ends of a row of round buttons keep this margin from the edge of the keyboard */
    private val railEdge get() = dp(23f)

    /** the centre of button [i] of [n]: the first and the last sit [railEdge] from the edges and every gap in between is the same */
    private fun railX(i: Int, n: Int, w: Float): Float =
        if (n <= 1) w / 2f else railEdge + i * (w - 2 * railEdge) / (n - 1)

    /** one radius for every button of the row, as big as the gaps allow, so the row never looks stuck together here and loose there */
    private fun railRadius(n: Int, w: Float): Float {
        val step = if (n <= 1) dp(40f) else (w - 2 * railEdge) / (n - 1)
        return minOf(dp(17f), step / 2f - dp(2.5f)).coerceAtLeast(dp(11f))
    }

    private enum class Chip { PLAIN, ACTIVE }

    /**
     * One round button: the same size and the same look wherever it stands. A plain one is a little glass pearl (soft shadow, light
     * top, hairline edge), a running tool is filled with the accent colour and glows.
     */
    private fun drawChip(c: Canvas, cx: Float, cy: Float, r: Float, kind: Chip, pressed: Boolean) {
        val t = theme
        val rr = if (pressed) r * 0.93f else r
        fill.shader = null
        val sa = ColorMath.alpha(t.shadow)
        if (!t.glass && sa > 0) {
            fill.color = ColorMath.withAlpha(t.shadow, (sa * 0.5f).toInt())
            c.drawCircle(cx, cy + dp(1.3f), rr, fill)
        }
        when (kind) {
            Chip.ACTIVE -> {
                fill.color = ColorMath.withAlpha(t.accent, 0x3A)
                c.drawCircle(cx, cy, rr + dp(3f), fill)
                fill.shader = LinearGradient(cx, cy - rr, cx, cy + rr, ColorMath.shade(t.accent, 0.12f), ColorMath.shade(t.accent, -0.16f), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, rr, fill)
                fill.shader = null
                ring.color = ColorMath.withAlpha(ColorMath.PAPER, 0x55)
            }
            Chip.PLAIN -> {
                val top = if (pressed) t.keyPressed else t.key
                val bottom = if (pressed) t.keyPressed else ColorMath.blend(t.key, t.keyFn, 0.55f)
                fill.shader = LinearGradient(cx, cy - rr, cx, cy + rr, top, bottom, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, rr, fill)
                fill.shader = null
                ring.color = if (t.glass) t.rim else ColorMath.withAlpha(t.divider, 0xC0)
            }
        }
        c.drawCircle(cx, cy, rr - density * 0.5f, ring)
        // the light on the top half
        shine.shader = LinearGradient(
            cx, cy - rr, cx, cy + rr * 0.15f,
            ColorMath.withAlpha(ColorMath.PAPER, if (kind == Chip.ACTIVE) 0x55 else if (t.dark) 0x26 else 0x70), 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, rr - dp(1f), shine)
    }

    /** the round colour button: a disc in the typing colour (or the rainbow) with a "send" arrow in a readable ink */
    private fun drawSwatch(c: Canvas, cx: Float, cy: Float, radius: Float, pressed: Boolean) {
        val prev = fill.shader
        val rr = if (pressed) radius * 0.93f else radius
        val sa = ColorMath.alpha(theme.shadow)
        if (!theme.glass && sa > 0) {
            fill.shader = null
            fill.color = ColorMath.withAlpha(theme.shadow, (sa * 0.5f).toInt())
            c.drawCircle(cx, cy + dp(1.3f), rr, fill)
        }
        if (swatchRainbow) {
            val cs = IntArray(7) { com.omega.keyboard.core.ColorMath.rainbow(it * 8 + 1) }
            cs[6] = cs[0]
            fill.shader = SweepGradient(cx, cy, cs, null)
        } else {
            fill.shader = null
            fill.color = swatch
        }
        c.drawCircle(cx, cy, rr, fill)
        fill.shader = prev
        ring.color = ColorMath.withAlpha(ColorMath.PAPER, 0x66)
        c.drawCircle(cx, cy, rr - density * 0.5f, ring)
        shine.shader = LinearGradient(cx, cy - rr, cx, cy + rr * 0.15f, ColorMath.withAlpha(ColorMath.PAPER, 0x55), 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rr - dp(1f), shine)
        val ink = if (swatchRainbow) 0xFFFFFFFF.toInt() else com.omega.keyboard.core.ColorMath.onColor(swatch)
        drawIcon(c, "send", cx, cy, rr * 1.15f, ink)
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
                val rr = dp(16f)
                val tx = railEdge
                addHit(tx - btn / 2, cy - btn / 2, tx + btn / 2, cy + btn / 2, "toggle")
                drawChip(c, tx, cy, rr, Chip.PLAIN, pressedTag == "toggle")
                drawIcon(c, "chevron_right", tx, cy, rr * 1.25f, t.text)
                val mx = w - railEdge
                addHit(mx - btn / 2, cy - btn / 2, mx + btn / 2, cy + btn / 2, "mic")
                drawChip(c, mx, cy, rr, Chip.PLAIN, pressedTag == "mic")
                drawIcon(c, "mic", mx, cy, rr * 1.25f, t.text)
                var left = tx + btn / 2 + dp(2f)
                if (colourSend) {
                    val sx = left + btn / 2
                    addHit(sx - btn / 2, cy - btn / 2, sx + btn / 2, cy + btn / 2, "csend")
                    drawSwatch(c, sx, cy, rr, pressedTag == "csend")
                    left = sx + btn / 2 + dp(2f)
                }
                val right = mx - btn / 2 - dp(2f)
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
                // the collapse button and every tool stand on one rail: same size, same look, equal gaps
                val n = tools.size + 1
                val rr = railRadius(n, w)
                val step = if (n <= 1) w else (w - 2 * railEdge) / (n - 1)
                val tx = railX(0, n, w)
                addHit(tx - step / 2, 0f, tx + step / 2, h, "toggle")
                drawChip(c, tx, cy, rr, Chip.PLAIN, pressedTag == "toggle")
                drawIcon(c, "chevron_left", tx, cy, rr * 1.25f, t.text)
                for ((i, tool) in tools.withIndex()) {
                    val x = railX(i + 1, n, w)
                    val tag = "t:${tool.id}"
                    addHit(x - step / 2, 0f, x + step / 2, h, tag)
                    val down = pressedTag == tag
                    if (tool.id == "csend") {
                        drawSwatch(c, x, cy, rr, down)
                        continue
                    }
                    drawChip(c, x, cy, rr, if (tool.active) Chip.ACTIVE else Chip.PLAIN, down)
                    drawIcon(c, tool.icon, x, cy, rr * 1.25f, if (tool.active) t.onAccent else t.text)
                }
            }
            Mode.TRANSLATE -> {
                val r = addHit(dp(4f), cy - btn / 2, dp(4f) + btn, cy + btn / 2, "close")
                pressBg(c, r, "close", btn / 2)
                drawIcon(c, "close", r.centerX(), cy, dp(22f), t.onBgSub)
                // the button at the right puts the live translation in, or translates now when there is none yet
                val goW = dp(72f)
                val go = addHit(w - dp(6f) - goW, cy - dp(17f), w - dp(6f), cy + dp(17f), "go")
                fill.color = if (pressedTag == "go") t.keyPressed else t.accent
                c.drawRoundRect(go, dp(17f), dp(17f), fill)
                tp.color = t.onAccent
                tp.textSize = dp(14f)
                tp.isFakeBoldText = true
                drawText(c, if (busy) "..." else if (preview.isNotEmpty()) "استخدم" else "ترجم", go.centerX(), cy, goW - dp(10f))
                tp.isFakeBoldText = false
                var right = go.left - dp(6f)
                if (canUndo) {
                    val u = addHit(right - btn, cy - btn / 2, right, cy + btn / 2, "undo")
                    pressBg(c, u, "undo", btn / 2)
                    drawIcon(c, "undo", u.centerX(), cy, dp(20f), t.onBg)
                    right = u.left - dp(2f)
                }
                // target language chip
                tp.textSize = dp(14f)
                val chipText = "$targetName ▾"
                val chipW = minOf(dp(112f), maxOf(dp(72f), tp.measureText(chipText) + dp(26f)))
                val chip = addHit(dp(46f), cy - dp(17f), dp(46f) + chipW, cy + dp(17f), "lang")
                fill.color = if (pressedTag == "lang") t.keyPressed else t.keyFn
                c.drawRoundRect(chip, dp(17f), dp(17f), fill)
                tp.color = t.onFn
                drawText(c, chipText, chip.centerX(), cy, chip.width() - dp(12f))
                // the live translation between the chip and the buttons: a tap puts it into the box
                val pl = chip.right + dp(6f)
                if (right - pl > dp(44f)) {
                    if (preview.isNotEmpty()) {
                        val box = addHit(pl, cy - dp(17f), right, cy + dp(17f), "go")
                        fill.color = if (pressedTag == "go") t.keyPressed else t.key
                        c.drawRoundRect(box, dp(17f), dp(17f), fill)
                        tp.color = t.text
                        tp.textSize = dp(15f)
                        drawText(c, preview, box.centerX(), cy, box.width() - dp(16f))
                    } else if (previewNote.isNotEmpty()) {
                        tp.color = t.onBgSub
                        tp.textSize = dp(13f)
                        drawText(c, previewNote, (pl + right) / 2f, cy, right - pl - dp(6f))
                    }
                }
            }
            Mode.STATUS -> {
                val ic = RectF(dp(8f), cy - dp(14f), dp(36f), cy + dp(14f))
                drawIcon(c, statusIcon, ic.centerX(), cy, dp(22f), if (statusWarn) 0xFFE8A200.toInt() else t.accent)
                var right = w - dp(8f)
                if (statusActionLabel.isNotEmpty()) {
                    val pw = dp(86f)
                    val p = addHit(right - pw, cy - dp(16f), right, cy + dp(16f), "status")
                    val accentBtn = statusWarn || statusCalm
                    fill.color = if (pressedTag == "status") t.keyPressed else (if (accentBtn) t.accent else 0xFFD93025.toInt())
                    c.drawRoundRect(p, dp(16f), dp(16f), fill)
                    tp.color = if (accentBtn) t.onAccent else 0xFFFFFFFF.toInt()
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
            tag == "csend" -> listener?.onColourSend()
            tag.startsWith("s") && tag.length == 2 && tag[1].isDigit() -> suggestions.getOrNull(tag[1] - '0')?.let { listener?.onSuggestion(it) }
            tag.startsWith("t:") -> listener?.onTool(tag.substring(2))
            tag == "status" -> listener?.onStatusAction(statusActionId)
            tag == "close" || tag == "go" || tag == "lang" || tag == "undo" -> listener?.onTranslateAction(tag)
        }
    }
}
