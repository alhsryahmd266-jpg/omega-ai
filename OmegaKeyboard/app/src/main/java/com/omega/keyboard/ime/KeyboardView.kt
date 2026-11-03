package com.omega.keyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import com.omega.keyboard.core.Key
import com.omega.keyboard.core.KeyKind
import com.omega.keyboard.core.Layout
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The key grid. Everything is drawn on one canvas (like real keyboards do) for speed: rounded keys, corner hints,
 * press feedback, key previews and long-press alternatives (those two are drawn by [PopupOverlay] so they can rise
 * above the first row), multi-touch typing, hold-to-repeat on backspace and swipe-on-space to move the cursor.
 */
class KeyboardView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onText(text: String)
        fun onKey(kind: KeyKind, key: Key)
        fun onBackspaceRepeat()
        fun onSpaceSwipe(steps: Int)
        fun onGlobeLong()
        fun onFeedback(kind: KeyKind)
    }

    var listener: Listener? = null
    var overlay: PopupOverlay? = null
    var theme: KbTheme = Themes.LIGHT
        set(v) { field = v; invalidate() }

    /** 0 = off, 1 = next letter only, 2 = caps lock */
    var shiftState: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    var enterIcon: String = "keyboard_return"
        set(v) { if (field != v) { field = v; invalidate() } }

    var spaceLabel: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }

    var heightScale: Float = 1f
        set(v) { field = v; requestLayout() }

    var showPreview: Boolean = true
    var layout: Layout? = null
        private set

    private class KeyRect(val key: Key, val cell: RectF, val row: Int)

    private class Ptr(val id: Int) {
        var kr: KeyRect? = null
        var downX = 0f
        var lastX = 0f
        var longFired = false
        var swiped = false
        var spaceAccum = 0f
        var altIndex = -1
        var runnable: Runnable? = null
    }

    private val keys = ArrayList<KeyRect>()
    private val pressed = HashSet<KeyRect>()
    private val ptrs = SparseArray<Ptr>()
    private val handler = Handler(Looper.getMainLooper())

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val rf = RectF()
    private val path = Path()
    private val icons = HashMap<String, Drawable?>()

    private val baseRowDp = 52f
    private val padTop get() = dp(6f)
    private val padBottom get() = dp(8f)
    private val rowHeight get() = dp(baseRowDp) * heightScale

    fun setLayout(l: Layout) {
        layout = l
        pressed.clear()
        cancelAll()
        computeKeys()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val rows = layout?.rows?.size ?: 4
        val h = (rows * rowHeight + padTop + padBottom).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        computeKeys()
    }

    private fun computeKeys() {
        keys.clear()
        val lay = layout ?: return
        if (width == 0) return
        val side = dp(2f)
        val usable = width - 2 * side
        val mirror = lay.rtl
        for ((r, row) in lay.rows.withIndex()) {
            val unit = usable / row.total
            var x = if (mirror) side + usable else side
            val top = padTop + r * rowHeight
            for (k in row.keys) {
                val kw = unit * k.weight
                val left = if (mirror) x - kw else x
                if (k.kind != KeyKind.SPACER) keys.add(KeyRect(k, RectF(left, top, left + kw, top + rowHeight), r))
                x = if (mirror) x - kw else x + kw
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    private fun upper(): Boolean = shiftState != 0 && layout?.lang == "en"

    private fun labelOf(k: Key): String = if (k.kind == KeyKind.CHAR && upper()) k.label.uppercase() else k.label

    override fun onDraw(c: Canvas) {
        val t = theme
        c.drawColor(t.bg)
        val insetX = dp(2.5f)
        val insetY = dp(4f)
        val radius = dp(9f)
        for (kr in keys) {
            val k = kr.key
            rf.set(kr.cell.left + insetX, kr.cell.top + insetY, kr.cell.right - insetX, kr.cell.bottom - insetY)
            val isPressed = pressed.contains(kr)
            val base = when (k.kind) {
                KeyKind.CHAR, KeyKind.SPACE -> t.key
                KeyKind.ENTER -> t.accent
                KeyKind.SHIFT -> if (shiftState == 2) t.accent else t.keyFn
                else -> t.keyFn
            }
            // soft shadow under the key
            fill.color = t.shadow
            c.drawRoundRect(rf.left, rf.top + dp(1f), rf.right, rf.bottom + dp(1f), radius, radius, fill)
            fill.color = if (isPressed) blend(base, t.keyPressed, if (k.kind == KeyKind.ENTER) 0.25f else 1f) else base
            c.drawRoundRect(rf, radius, radius, fill)
            drawContent(c, kr, rf, t)
        }
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        val r = Color.red(a) + ((Color.red(b) - Color.red(a)) * f).toInt()
        val g = Color.green(a) + ((Color.green(b) - Color.green(a)) * f).toInt()
        val bl = Color.blue(a) + ((Color.blue(b) - Color.blue(a)) * f).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun drawContent(c: Canvas, kr: KeyRect, r: RectF, t: KbTheme) {
        val k = kr.key
        val cx = r.centerX()
        val cy = r.centerY()
        when (k.kind) {
            KeyKind.CHAR -> {
                textPaint.color = t.text
                textPaint.isFakeBoldText = false
                textPaint.textSize = min(rowHeight * 0.42f, dp(24f))
                val lab = labelOf(k)
                if (lab.length > 2) textPaint.textSize = min(textPaint.textSize, dp(19f))
                drawCentered(c, lab, cx, cy)
                if (k.hint.isNotEmpty()) {
                    hintPaint.color = t.textSub
                    hintPaint.textSize = dp(10f)
                    c.drawText(k.hint, r.right - dp(7f), r.top + dp(13f), hintPaint)
                }
            }
            KeyKind.SPACE -> {
                if (spaceLabel.isNotEmpty()) {
                    textPaint.color = t.textSub
                    textPaint.textSize = dp(13f)
                    drawCentered(c, spaceLabel, cx, cy)
                }
            }
            KeyKind.ENTER -> drawIcon(c, enterIcon, cx, cy, dp(24f), t.onAccent)
            KeyKind.BACKSPACE -> drawIcon(c, "backspace", cx, cy, dp(24f), t.text)
            KeyKind.LANG -> drawIcon(c, "language", cx, cy, dp(22f), t.text)
            KeyKind.EMOJI -> drawIcon(c, "emoji_emotions", cx, cy, dp(22f), t.text)
            KeyKind.SHIFT -> drawShift(c, cx, cy, if (shiftState == 2) t.onAccent else t.text, shiftState)
            KeyKind.SYMBOLS, KeyKind.SYMBOLS2, KeyKind.LETTERS -> {
                textPaint.color = t.text
                textPaint.isFakeBoldText = true
                textPaint.textSize = dp(15f)
                drawCentered(c, k.label, cx, cy)
                textPaint.isFakeBoldText = false
            }
            KeyKind.ALT -> {
                textPaint.color = t.text
                textPaint.textSize = dp(20f)
                drawCentered(c, k.label, cx, cy)
            }
            KeyKind.SPACER -> {}
        }
    }

    private fun drawCentered(c: Canvas, s: String, cx: Float, cy: Float) {
        val fm = textPaint.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    private fun drawIcon(c: Canvas, name: String, cx: Float, cy: Float, size: Float, color: Int) {
        val d = icons.getOrPut(name) { Icons.get(context, name) } ?: return
        d.setTint(color)
        val h = size / 2f
        d.setBounds((cx - h).toInt(), (cy - h).toInt(), (cx + h).toInt(), (cy + h).toInt())
        d.draw(c)
    }

    /** Gboard-style shift arrow: outline when off, filled when on, with a bar for caps lock. */
    private fun drawShift(c: Canvas, cx: Float, cy: Float, color: Int, state: Int) {
        val w = dp(18f)
        val h = dp(18f)
        val dy = if (state == 2) -dp(2f) else 0f
        path.reset()
        path.moveTo(cx, cy - h / 2 + dy)
        path.lineTo(cx + w / 2, cy + dy)
        path.lineTo(cx + w / 4, cy + dy)
        path.lineTo(cx + w / 4, cy + h / 2 + dy)
        path.lineTo(cx - w / 4, cy + h / 2 + dy)
        path.lineTo(cx - w / 4, cy + dy)
        path.lineTo(cx - w / 2, cy + dy)
        path.close()
        if (state == 0) {
            strokePaint.color = color
            strokePaint.strokeWidth = dp(1.8f)
            c.drawPath(path, strokePaint)
        } else {
            fill.color = color
            c.drawPath(path, fill)
        }
        if (state == 2) {
            fill.color = color
            rf.set(cx - w / 4, cy + h / 2 + dp(1.5f), cx + w / 4, cy + h / 2 + dp(3.5f))
            c.drawRoundRect(rf, dp(1f), dp(1f), fill)
        }
    }

    // ------------------------------------------------------------------ touch

    private fun keyAt(x: Float, y: Float): KeyRect? {
        if (keys.isEmpty()) return null
        var best: KeyRect? = null
        var bestD = Float.MAX_VALUE
        for (kr in keys) {
            val cell = kr.cell
            if (x >= cell.left && x < cell.right && y >= cell.top && y < cell.bottom) return kr
            val dx = max(max(cell.left - x, 0f), x - cell.right)
            val dy = max(max(cell.top - y, 0f), y - cell.bottom)
            val d = hypot(dx, dy)
            if (d < bestD) { bestD = d; best = kr }
        }
        return if (bestD < dp(22f)) best else null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                up(e.getPointerId(i))
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        val kr = keyAt(x, y) ?: return
        val p = Ptr(id)
        p.kr = kr; p.downX = x; p.lastX = x
        ptrs.put(id, p)
        pressed.add(kr)
        listener?.onFeedback(kr.key.kind)
        armKey(p, kr)
        invalidate()
    }

    private fun armKey(p: Ptr, kr: KeyRect) {
        cancelRunnable(p)
        when (kr.key.kind) {
            KeyKind.BACKSPACE -> {
                // delete on touch-down (feels instant), then repeat while held; sliding onto this key never lands here
                listener?.onKey(KeyKind.BACKSPACE, kr.key)
                p.longFired = true
                val rep = object : Runnable {
                    var n = 0
                    override fun run() {
                        if (ptrs.get(p.id) !== p) return
                        listener?.onBackspaceRepeat()
                        n++
                        handler.postDelayed(this, if (n > 12) 28L else 52L)
                    }
                }
                p.runnable = rep
                handler.postDelayed(rep, 380)
            }
            KeyKind.CHAR -> {
                if (showPreview) showPreview(kr)
                if (kr.key.popups.isNotEmpty()) {
                    val r = Runnable { openAlt(p, kr) }
                    p.runnable = r
                    handler.postDelayed(r, 340)
                }
            }
            KeyKind.LANG -> {
                val r = Runnable { p.longFired = true; listener?.onGlobeLong() }
                p.runnable = r
                handler.postDelayed(r, 480)
            }
            else -> {}
        }
    }

    private fun move(id: Int, x: Float, y: Float) {
        val p = ptrs.get(id) ?: return
        val kr = p.kr ?: return
        if (p.altIndex >= 0) {
            val a = altSpec ?: return
            val idx = ((x - a.rect.left - a.pad) / a.cellW).toInt().coerceIn(0, a.items.size - 1)
            if (idx != p.altIndex) {
                p.altIndex = idx
                altSpec = a.copy(selected = idx)
                pushOverlay()
            }
            return
        }
        if (kr.key.kind == KeyKind.SPACE) {
            p.spaceAccum += x - p.lastX
            p.lastX = x
            val step = dp(13f)
            if (abs(p.spaceAccum) >= step) {
                val steps = (p.spaceAccum / step).toInt()
                p.spaceAccum -= steps * step
                p.swiped = true
                listener?.onSpaceSwipe(steps)
            }
            return
        }
        val nk = keyAt(x, y)
        if (nk != null && nk !== kr && !p.longFired) {
            pressed.remove(kr)
            cancelRunnable(p)
            p.kr = nk
            pressed.add(nk)
            if (nk.key.kind != KeyKind.BACKSPACE) armKey(p, nk) else hidePopups()
            invalidate()
        }
    }

    private fun up(id: Int) {
        val p = ptrs.get(id) ?: return
        ptrs.remove(id)
        cancelRunnable(p)
        val kr = p.kr
        if (kr != null) pressed.remove(kr)
        val a = altSpec
        hidePopups()
        if (kr != null) {
            if (p.altIndex >= 0 && a != null) {
                listener?.onText(casedAlt(a.items[p.altIndex.coerceIn(0, a.items.size - 1)]))
            } else if (!p.longFired && !p.swiped) {
                fire(kr.key)
            }
        }
        invalidate()
    }

    private fun fire(k: Key) {
        when (k.kind) {
            KeyKind.CHAR -> listener?.onText(if (upper()) k.text.uppercase() else k.text)
            else -> listener?.onKey(k.kind, k)
        }
    }

    private fun casedAlt(s: String) = if (upper() && s.length <= 2) s.uppercase() else s

    fun cancelAll() {
        for (i in 0 until ptrs.size()) cancelRunnable(ptrs.valueAt(i))
        ptrs.clear()
        pressed.clear()
        hidePopups()
        invalidate()
    }

    private fun cancelRunnable(p: Ptr) {
        p.runnable?.let { handler.removeCallbacks(it) }
        p.runnable = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAll()
    }

    // ------------------------------------------------------------------ popups (drawn by the overlay)

    class PreviewSpec(val rect: RectF, val text: String)
    data class AltSpec(val rect: RectF, val items: List<String>, val selected: Int, val cellW: Float, val pad: Float)

    private var previewSpec: PreviewSpec? = null
    private var altSpec: AltSpec? = null

    private fun showPreview(kr: KeyRect) {
        val k = kr.key
        val txt = labelOf(k)
        if (txt.isEmpty()) return
        val w = max(kr.cell.width() * 1.25f, dp(54f))
        val h = dp(62f)
        val cx = kr.cell.centerX().coerceIn(w / 2 + dp(2f), width - w / 2 - dp(2f))
        val bottom = kr.cell.top + dp(8f)
        previewSpec = PreviewSpec(RectF(cx - w / 2, bottom - h, cx + w / 2, bottom), txt)
        pushOverlay()
    }

    private fun openAlt(p: Ptr, kr: KeyRect) {
        if (ptrs.get(p.id) !== p) return
        val items = kr.key.popups
        if (items.isEmpty()) return
        val cellW = dp(44f)
        val pad = dp(6f)
        val total = items.size * cellW + 2 * pad
        val cx = kr.cell.centerX()
        val left = (cx - total / 2).coerceIn(dp(3f), max(dp(3f), width - total - dp(3f)))
        val bottom = kr.cell.top + dp(4f)
        val rect = RectF(left, bottom - dp(56f), left + total, bottom)
        p.longFired = true
        p.altIndex = 0
        previewSpec = null
        altSpec = AltSpec(rect, items.map { casedAlt(it) }, 0, cellW, pad)
        listener?.onFeedback(KeyKind.CHAR)
        pushOverlay()
    }

    private fun hidePopups() {
        previewSpec = null
        altSpec = null
        overlay?.clear()
    }

    private fun pushOverlay() {
        val ov = overlay ?: return
        val a = IntArray(2); val b = IntArray(2)
        getLocationOnScreen(a); ov.getLocationOnScreen(b)
        ov.show(previewSpec, altSpec, (a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat(), theme)
    }
}

/** Transparent layer above the whole keyboard that draws key previews and long-press alternatives. */
class PopupOverlay(ctx: Context) : View(ctx) {
    private var preview: KeyboardView.PreviewSpec? = null
    private var alt: KeyboardView.AltSpec? = null
    private var dx = 0f
    private var dy = 0f
    private var theme: KbTheme = Themes.LIGHT
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    init { isClickable = false; isFocusable = false }

    /** Never asks for room of its own: it only covers whatever its parent already is. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val exact = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), if (exact) MeasureSpec.getSize(heightMeasureSpec) else 0)
    }

    fun show(p: KeyboardView.PreviewSpec?, a: KeyboardView.AltSpec?, dx: Float, dy: Float, t: KbTheme) {
        preview = p; alt = a; this.dx = dx; this.dy = dy; theme = t
        invalidate()
    }

    fun clear() {
        if (preview != null || alt != null) { preview = null; alt = null; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        c.save()
        c.translate(dx, dy)
        val t = theme
        preview?.let { p ->
            fill.color = t.shadow
            c.drawRoundRect(p.rect.left, p.rect.top + dp(2f), p.rect.right, p.rect.bottom + dp(2f), dp(12f), dp(12f), fill)
            fill.color = t.popupBg
            c.drawRoundRect(p.rect, dp(12f), dp(12f), fill)
            line.color = t.divider
            c.drawRoundRect(p.rect, dp(12f), dp(12f), line)
            text.color = t.text
            text.textSize = dp(30f)
            val fm = text.fontMetrics
            c.drawText(p.text, p.rect.centerX(), p.rect.centerY() - (fm.ascent + fm.descent) / 2f, text)
        }
        alt?.let { a ->
            fill.color = t.shadow
            c.drawRoundRect(a.rect.left, a.rect.top + dp(2f), a.rect.right, a.rect.bottom + dp(2f), dp(14f), dp(14f), fill)
            fill.color = t.popupBg
            c.drawRoundRect(a.rect, dp(14f), dp(14f), fill)
            line.color = t.divider
            c.drawRoundRect(a.rect, dp(14f), dp(14f), line)
            for ((i, s) in a.items.withIndex()) {
                val l = a.rect.left + a.pad + i * a.cellW
                if (i == a.selected) {
                    fill.color = t.accent
                    c.drawRoundRect(l + dp(2f), a.rect.top + dp(5f), l + a.cellW - dp(2f), a.rect.bottom - dp(5f), dp(10f), dp(10f), fill)
                    text.color = t.onAccent
                } else text.color = t.text
                text.textSize = dp(if (s.length > 2) 18f else 24f)
                val fm = text.fontMetrics
                c.drawText(s, l + a.cellW / 2, a.rect.centerY() - (fm.ascent + fm.descent) / 2f, text)
            }
        }
        c.restore()
    }
}
