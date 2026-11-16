package com.omega.keyboard.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.omega.keyboard.core.ColorMath

/**
 * The typed-text translation bar, in the way Gboard works: a back arrow, the two language chips with a swap between them, and a small
 * text box inside the keyboard. What is typed lands in that box; its translation is written live into the app's own field, and the
 * Enter key (a tick while this bar is open) keeps it there.
 *
 * Portrait: two rows (chips, then the box with the typed text on top and its translation under it). Landscape: one row.
 * The box is drawn here, with its own caret, because the keys are the only input the keyboard has.
 */
class TranslateBar(ctx: Context) : View(ctx) {

    interface Listener {
        fun onTrBack()
        /** [source]: the left chip was tapped (else the right one) */
        fun onTrLanguage(source: Boolean)
        fun onTrSwap()
        fun onTrClear()
        fun onTrMic()
        fun onTrDone()
        fun onTrCaret(pos: Int)
    }

    var listener: Listener? = null
    var theme: KbTheme = Themes.LIGHT
        set(v) { field = v; invalidate() }

    var sourceLabel: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var targetLabel: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }

    /** what the box holds and where its caret is */
    var text: String = ""
        set(v) { if (field != v) { field = v; mainLayout = null; blinkOn = true; invalidate() } }
    var caret: Int = 0
        set(v) { if (field != v) { field = v; blinkOn = true; invalidate() } }
    /** shown in the box while it is empty */
    var hint: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    /** the live translation of the text (second line of the box) */
    var translation: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    /** said under the typed text while there is no translation: progress, "already in that language", what went wrong */
    var note: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var noteWarn: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var busy: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val compact get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spin = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val mainPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val chipPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val icons = HashMap<String, Drawable?>()
    private val hits = ArrayList<Pair<RectF, String>>()
    private var pressedTag: String? = null

    // the box
    private var mainLayout: StaticLayout? = null
    private var scrollOff = 0f
    private var textOx = 0f                 // where the left edge of the typed text was drawn last (taps are turned into a caret from it)
    private var boxLeft = 0f
    private var boxRight = 0f
    private var blinkOn = true
    private val blink = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            invalidate()
            postDelayed(this, 530)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(blink)
        postDelayed(blink, 530)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changed: View, visibility: Int) {
        super.onVisibilityChanged(changed, visibility)
        removeCallbacks(blink)
        if (visibility == VISIBLE && isAttachedToWindow) { blinkOn = true; postDelayed(blink, 530) }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(if (compact) 50f else 108f).toInt())
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

    // ------------------------------------------------------------------ pieces

    /** a shader fill: a Paint's own alpha multiplies the shader, so the colour is made opaque first */
    private fun gradient(sh: Shader) {
        fill.color = 0xFF000000.toInt()
        fill.shader = sh
    }

    private fun solid(color: Int) {
        fill.shader = null
        fill.color = color
    }

    /** the body of a chip or a round button: a lit gradient with a fine edge (luxe look) or a flat key colour */
    private fun body(c: Canvas, r: RectF, radius: Float, pressed: Boolean) {
        val t = theme
        val sa = ColorMath.alpha(t.shadow)
        if (t.fancy && sa > 0) {
            solid(ColorMath.withAlpha(t.shadow, (sa * 0.45f).toInt()))
            c.drawRoundRect(r.left, r.top + dp(1.3f), r.right, r.bottom + dp(1.3f), radius, radius, fill)
        }
        if (t.fancy) {
            gradient(LinearGradient(0f, r.top, 0f, r.bottom, t.capHi, t.capLo, Shader.TileMode.CLAMP))
            c.drawRoundRect(r, radius, radius, fill)
            fill.shader = null
            ring.shader = LinearGradient(
                0f, r.top, 0f, r.bottom,
                intArrayOf(t.rim, ColorMath.withAlpha(t.rim, 0), ColorMath.withAlpha(t.rimDark, 0), t.rimDark), floatArrayOf(0f, 0.45f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
            ring.alpha = 255
            val hw = density * 0.5f
            c.drawRoundRect(r.left + hw, r.top + hw, r.right - hw, r.bottom - hw, radius, radius, ring)
            ring.shader = null
        } else {
            solid(if (pressed) t.keyPressed else t.keyFn)
            c.drawRoundRect(r, radius, radius, fill)
            ring.color = if (t.glass) t.rim else ColorMath.withAlpha(t.divider, 0xC0)
            c.drawRoundRect(r.left + density * 0.5f, r.top + density * 0.5f, r.right - density * 0.5f, r.bottom - density * 0.5f, radius, radius, ring)
        }
        if (pressed) {
            solid(ColorMath.withAlpha(t.accent, 0x30))
            c.drawRoundRect(r, radius, radius, fill)
        }
    }

    private val tmp = RectF()

    private fun roundButton(c: Canvas, cx: Float, cy: Float, r: Float, tag: String, icon: String) {
        val down = pressedTag == tag
        val rr = if (down) r * 0.93f else r
        tmp.set(cx - rr, cy - rr, cx + rr, cy + rr)
        body(c, tmp, rr, down)
        drawIcon(c, icon, cx, cy, rr * 1.25f, theme.onFn)
    }

    private fun chip(c: Canvas, r: RectF, tag: String, label: String) {
        val t = theme
        body(c, r, r.height() / 2f, pressedTag == tag)
        chipPaint.color = t.onFn
        chipPaint.isFakeBoldText = true
        val full = "$label ▾"
        val room = r.width() - dp(14f)
        var size = dp(14f)
        chipPaint.textSize = size
        while (size > dp(10.5f) && chipPaint.measureText(full) > room) { size -= dp(0.5f); chipPaint.textSize = size }
        val shown = TextUtils.ellipsize(full, chipPaint, room, TextUtils.TruncateAt.END).toString()
        val fm = chipPaint.fontMetrics
        c.drawText(shown, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, chipPaint)
        chipPaint.isFakeBoldText = false
    }

    /** the tick: gold metal (luxe look) or the accent colour; a turning arc while a translation is on its way */
    private fun doneButton(c: Canvas, cx: Float, cy: Float, r: Float) {
        val t = theme
        val down = pressedTag == "tr:done"
        val rr = if (down) r * 0.93f else r
        if (t.fancy) {
            val halo = intArrayOf(0x30, 0x1A)
            fill.shader = null
            for (i in halo.indices) {
                fill.color = ColorMath.withAlpha(t.accent, halo[i])
                c.drawCircle(cx, cy, rr + dp(2f) * (i + 1), fill)
            }
            gradient(LinearGradient(cx, cy - rr, cx, cy + rr, t.stops(2), t.stopPos(2), Shader.TileMode.CLAMP))
        } else {
            gradient(LinearGradient(cx, cy - rr, cx, cy + rr, ColorMath.shade(t.accent, 0.12f), ColorMath.shade(t.accent, -0.16f), Shader.TileMode.CLAMP))
        }
        c.drawCircle(cx, cy, rr, fill)
        fill.shader = null
        ring.color = ColorMath.withAlpha(ColorMath.PAPER, 0x55)
        c.drawCircle(cx, cy, rr - density * 0.5f, ring)
        shine.shader = LinearGradient(cx, cy - rr, cx, cy + rr * 0.15f, ColorMath.withAlpha(ColorMath.PAPER, 0x55), 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rr - dp(1f), shine)
        val ink = t.onAccent
        if (busy) {
            spin.color = ink
            spin.strokeWidth = dp(2.2f)
            val a = (SystemClock.uptimeMillis() % 900L) / 900f * 360f
            val s = rr * 0.5f
            tmp.set(cx - s, cy - s, cx + s, cy + s)
            c.drawArc(tmp, a, 250f, false, spin)
            postInvalidateOnAnimation()
        } else {
            drawIcon(c, "check", cx, cy, rr * 1.2f, ink)
        }
    }

    private fun build(s: CharSequence, paint: TextPaint): StaticLayout {
        val w = Math.ceil(Layout.getDesiredWidth(s, paint).toDouble()).toInt().coerceAtLeast(1) + 2
        return StaticLayout.Builder.obtain(s, 0, s.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setMaxLines(1)
            .build()
    }

    private fun isRtl(l: StaticLayout) = l.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT

    /** one short line, on the side its own language starts from, shortened with "…" when it is too long */
    private fun drawLine(c: Canvas, s: CharSequence, paint: TextPaint, left: Float, right: Float, cy: Float) {
        val shown = TextUtils.ellipsize(s, paint, right - left, TextUtils.TruncateAt.END)
        val lay = build(shown, paint)
        val x = if (isRtl(lay)) right - lay.width else left
        c.save()
        c.translate(x, cy - lay.height / 2f)
        lay.draw(c)
        c.restore()
    }

    /** the typed text with its caret; it scrolls to keep the caret in view when it is longer than the box */
    private fun drawTyped(c: Canvas, l: Float, r: Float, top: Float, bottom: Float, cy: Float) {
        val t = theme
        val avail = r - l
        boxLeft = l
        boxRight = r
        mainPaint.textSize = dp(18f)
        if (text.isEmpty()) {
            mainLayout = null
            scrollOff = 0f
            mainPaint.textSize = dp(15f)
            mainPaint.color = t.textSub
            if (hint.isNotEmpty()) drawLine(c, hint, mainPaint, l + dp(3f), r, cy)
            if (blinkOn) {
                val hl = if (hint.isNotEmpty()) build(hint, mainPaint) else null
                val x = if (hl != null && isRtl(hl)) r else l
                solid(t.accent)
                c.drawRect(x - dp(1f), cy - dp(11f), x + dp(1f), cy + dp(11f), fill)
            }
            textOx = l
            return
        }
        val lay = mainLayout ?: build(text, mainPaint).also { mainLayout = it }
        val rtl = isRtl(lay)
        val lw = lay.width.toFloat()
        val base = if (rtl) r - lw else l
        val cx = lay.getPrimaryHorizontal(caret.coerceIn(0, text.length))
        var sx = 0f
        if (lw > avail) {
            sx = scrollOff
            val m = dp(6f)
            val cv = base + sx + cx
            if (cv < l + m) sx += (l + m) - cv
            if (cv > r - m) sx -= cv - (r - m)
            val lo = if (rtl) 0f else -(lw - avail)
            val hi = if (rtl) lw - avail else 0f
            sx = sx.coerceIn(lo, hi)
        }
        scrollOff = sx
        val ox = base + sx
        textOx = ox
        c.save()
        c.clipRect(l - dp(2f), top, r + dp(2f), bottom)
        c.translate(ox, cy - lay.height / 2f)
        mainPaint.color = t.text
        lay.draw(c)
        c.restore()
        if (blinkOn) {
            solid(t.accent)
            val x = ox + cx
            c.drawRect(x - dp(1f), cy - dp(11f), x + dp(1f), cy + dp(11f), fill)
        }
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        if (t.bgStyle == 0) c.drawColor(t.bg)
        hits.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(8f)
        val small = compact
        val rowCy = if (small) h / 2f else dp(26f)

        // ---- the first row: back, the two languages and the swap between them
        val backR = dp(17f)
        val backCx = pad + backR + dp(3f)
        addHit(backCx - dp(24f), rowCy - dp(24f), backCx + dp(24f), rowCy + dp(24f), "tr:back")
        roundButton(c, backCx, rowCy, backR, "tr:back", "arrow_back")

        val swapR = dp(17f)
        val gap = dp(8f)
        val micR = dp(17f)
        val groupLeft = backCx + backR + dp(10f)
        val micRight = if (small) 0f else w - pad - micR - dp(3f) - micR - dp(8f)       // portrait: the mic stands at the right end of the row
        val room = if (small) dp(300f) else micRight - groupLeft
        val chipW = ((room - 2 * swapR - 2 * gap) / 2f).coerceIn(dp(70f), dp(150f))
        val groupW = 2 * chipW + 2 * swapR + 2 * gap
        val gl = if (small) groupLeft else groupLeft + (room - groupW).coerceAtLeast(0f) / 2f
        val chipH = dp(34f)
        val src = RectF(gl, rowCy - chipH / 2f, gl + chipW, rowCy + chipH / 2f)
        addHit(src.left, rowCy - dp(24f), src.right, rowCy + dp(24f), "tr:src")
        chip(c, src, "tr:src", sourceLabel)
        val swapCx = src.right + gap + swapR
        addHit(swapCx - dp(22f), rowCy - dp(24f), swapCx + dp(22f), rowCy + dp(24f), "tr:swap")
        roundButton(c, swapCx, rowCy, swapR, "tr:swap", "swap_horiz")
        val dst = RectF(swapCx + swapR + gap, rowCy - chipH / 2f, swapCx + swapR + gap + chipW, rowCy + chipH / 2f)
        addHit(dst.left, rowCy - dp(24f), dst.right, rowCy + dp(24f), "tr:tgt")
        chip(c, dst, "tr:tgt", targetLabel)
        val micCx = if (small) dst.right + dp(10f) + micR else w - pad - micR - dp(3f)
        addHit(micCx - dp(22f), rowCy - dp(24f), micCx + dp(22f), rowCy + dp(24f), "tr:mic")
        roundButton(c, micCx, rowCy, micR, "tr:mic", "mic")

        // ---- the box
        val box = if (small) RectF(micCx + micR + dp(10f), h / 2f - dp(19f), w - pad, h / 2f + dp(19f))
        else RectF(pad, dp(52f), w - pad, h - dp(6f))
        val br = if (small) box.height() / 2f else dp(18f)
        val fillC = when {
            t.glass -> ColorMath.withAlpha(0xFF000000.toInt(), 0x40)
            t.dark -> ColorMath.shade(t.key, -0.35f)
            else -> ColorMath.shade(t.key, 0.0f)
        }
        solid(fillC)
        c.drawRoundRect(box, br, br, fill)
        c.save()
        c.clipPath(Path().apply { addRoundRect(box, br, br, Path.Direction.CW) })
        // a little shade inside the top edge: the box looks sunk into the keyboard
        gradient(LinearGradient(0f, box.top, 0f, box.top + dp(7f), ColorMath.withAlpha(0xFF000000.toInt(), if (t.dark) 0x70 else 0x26), 0x00000000, Shader.TileMode.CLAMP))
        c.drawRect(box.left, box.top, box.right, box.top + dp(7f), fill)
        fill.shader = null
        c.restore()
        ring.color = if (t.glass) t.rim else ColorMath.withAlpha(t.divider, 0xD0)
        c.drawRoundRect(box.left + density * 0.5f, box.top + density * 0.5f, box.right - density * 0.5f, box.bottom - density * 0.5f, br, br, ring)

        // the tick (and the cross while there is text) at the right end of the box
        val doneR = dp(16f)
        val doneCx = box.right - dp(6f) - doneR
        val doneCy = box.centerY()
        addHit(doneCx - dp(20f), box.top, box.right, box.bottom, "tr:done")
        doneButton(c, doneCx, doneCy, doneR)
        var textRight = doneCx - doneR - dp(8f)
        if (text.isNotEmpty()) {
            val clearCx = textRight - dp(13f)
            addHit(clearCx - dp(16f), box.top, clearCx + dp(16f), box.bottom, "tr:clear")
            if (pressedTag == "tr:clear") {
                solid(ColorMath.withAlpha(t.text, 0x22))
                c.drawCircle(clearCx, doneCy, dp(13f), fill)
            }
            drawIcon(c, "close", clearCx, doneCy, dp(18f), t.textSub)
            textRight = clearCx - dp(13f) - dp(4f)
        }
        val textLeft = box.left + dp(16f)
        val line1Cy = if (small) box.centerY() else box.top + dp(15f)
        val typedHit = addHit(box.left, box.top, textRight, box.bottom, "tr:pill")
        drawTyped(c, textLeft, textRight, typedHit.top, typedHit.bottom, line1Cy)

        // ---- the second line of the box: the translation, or what is going on
        if (!small) {
            val shownText = if (translation.isNotEmpty()) translation else note
            if (shownText.isNotEmpty()) {
                subPaint.textSize = dp(14f)
                subPaint.color = when {
                    translation.isNotEmpty() -> if (ColorMath.contrast(t.accent, fillC) >= 3f) t.accent else t.text
                    noteWarn -> 0xFFE8A200.toInt()
                    else -> t.textSub
                }
                subPaint.isFakeBoldText = translation.isNotEmpty()
                // a hairline between the two lines
                solid(ColorMath.withAlpha(t.divider, 0x70))
                c.drawRect(textLeft, box.top + dp(28.5f), textRight, box.top + dp(28.5f) + density * 0.8f, fill)
                drawLine(c, shownText, subPaint, textLeft, textRight, box.top + dp(39f))
                subPaint.isFakeBoldText = false
            }
        }
    }

    /** Preview builds only: logs the centre of every button, so the emulator test can tap them by name. */
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
                if (tag != null && tag == hitAt(e.x, e.y)) dispatch(tag, e.x)
            }
            MotionEvent.ACTION_CANCEL -> { pressedTag = null; invalidate() }
        }
        return true
    }

    private fun dispatch(tag: String, x: Float) {
        when (tag) {
            "tr:back" -> listener?.onTrBack()
            "tr:src" -> listener?.onTrLanguage(true)
            "tr:tgt" -> listener?.onTrLanguage(false)
            "tr:swap" -> listener?.onTrSwap()
            "tr:clear" -> listener?.onTrClear()
            "tr:mic" -> listener?.onTrMic()
            "tr:done" -> listener?.onTrDone()
            "tr:pill" -> {
                val lay = mainLayout
                if (lay != null && text.isNotEmpty()) listener?.onTrCaret(lay.getOffsetForHorizontal(0, x - textOx))
            }
        }
    }
}
