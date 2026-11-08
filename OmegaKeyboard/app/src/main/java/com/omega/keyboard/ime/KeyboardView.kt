package com.omega.keyboard.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import com.omega.keyboard.core.ColorMath
import com.omega.keyboard.core.Key
import com.omega.keyboard.core.KeyKind
import com.omega.keyboard.core.Layout
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The key grid. Everything is drawn on one canvas (like real keyboards do) for speed: rounded keys with layered shadows,
 * a glossy sheen and a light rim, corner hints, press feedback, key previews and long-press alternatives (those two are
 * drawn by [PopupOverlay] so they can rise above the first row), multi-touch typing, hold-to-repeat on backspace and
 * swipe-on-space to move the cursor.
 *
 * Extras that react to the finger: keys sink and light up when pressed, a soft light follows the finger across the keys
 * ([fxGlow]) and a ring spreads from every pressed key ([fxRipple]). Nothing is drawn while the keyboard is idle.
 */
class KeyboardView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onText(text: String)
        fun onKey(kind: KeyKind, key: Key)
        fun onBackspaceRepeat()
        fun onSpaceSwipe(steps: Int)
        fun onGlobeLong()
        fun onFeedback(kind: KeyKind)
        /** resize mode: a drag of an edge ended; these are the scales the view shows now (not percentages) */
        fun onResized(heightScale: Float, widthScale: Float, alignFrac: Float)
        /** resize mode: "back to the original size" was pressed */
        fun onResizeReset()
        /** resize mode: "done" was pressed */
        fun onResizeDone()
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
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }

    /** size of the letters on the keys, 1 = normal */
    var fontScale: Float = 1f
        set(v) { if (field != v) { field = v; invalidate() } }

    /** width of the keys area as a share of the view (one-handed typing) and where it sits: 0 centre, 1 left, 2 right */
    var widthScale: Float = 1f
        set(v) { if (field != v) { field = v; computeKeys(); invalidate() } }

    /** where the keys area sits when it is narrower than the view: 0 = at the left edge, 0.5 = centred, 1 = at the right edge */
    var alignFrac: Float = 0.5f
        set(v) { if (field != v) { field = v; computeKeys(); invalidate() } }

    var fxGlow: Boolean = true
    var fxRipple: Boolean = true

    var showPreview: Boolean = true
    var layout: Layout? = null
        private set

    private class KeyRect(val key: Key, val cell: RectF, val row: Int) {
        var press = 0f          // 0 = up .. 1 = fully pushed, animated
    }

    private class Ripple(val kr: KeyRect, val x: Float, val y: Float, val t0: Long)

    private class Ptr(val id: Int) {
        var kr: KeyRect? = null
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var longFired = false
        var swiped = false
        var spaceAccum = 0f
        var altIndex = -1
        var runnable: Runnable? = null
    }

    companion object {
        private const val RIPPLE_MS = 460L

        /** how long a letter is held before its alternatives open (a slow tap must not become "another letter") */
        private const val LONG_PRESS_MS = 400L
        private val SHADOW_FRAC = floatArrayOf(0.55f, 0.32f, 0.16f)

        /** limits of the size the user can give the keyboard (percent of the normal one) */
        const val MIN_HEIGHT_PCT = 60
        const val MAX_HEIGHT_PCT = 170
        const val MIN_WIDTH_PCT = 60

        /** a finger that went down on a key moves to the next one only after leaving the first by this much and travelling this far (dp) */
        private const val KEY_SWITCH_MARGIN_DP = 5f
        private const val KEY_SWITCH_TRAVEL_DP = 11f

        private const val RZ_NONE = 0
        private const val RZ_TOP = 1
        private const val RZ_LEFT = 2
        private const val RZ_RIGHT = 3
        private const val RZ_DONE = 4
        private const val RZ_RESET = 5
    }

    private val keys = ArrayList<KeyRect>()
    private val pressed = HashSet<KeyRect>()
    private val ptrs = SparseArray<Ptr>()
    private val ripples = ArrayList<Ripple>()
    private val handler = Handler(Looper.getMainLooper())

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letterFace: Typeface = try { Typeface.create("sans-serif-medium", Typeface.NORMAL) } catch (_: Throwable) { Typeface.DEFAULT }
    private val rf = RectF()
    private val path = Path()
    private val icons = HashMap<String, Drawable?>()

    // shaders that depend on the key height / theme only, built once and shared by every key
    private var sheen: Shader? = null
    private var rimShader: Shader? = null
    private var shaderH = -1f
    private var shaderTheme: KbTheme? = null

    // animation state
    private var lastFrame = 0L
    private var glowX = 0f
    private var glowY = 0f
    private var glowAmount = 0f

    private val baseRowDp = 52f
    private val padTop get() = dp(6f)
    private val padBottom get() = dp(8f)
    private val rowHeight get() = dp(baseRowDp) * heightScale
    private val insetX get() = dp(2.5f)
    private val insetY get() = dp(4f)

    fun setLayout(l: Layout) {
        layout = l
        pressed.clear()
        ripples.clear()
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
        val full = width - 2 * side
        val usable = full * widthScale.coerceIn(MIN_WIDTH_PCT / 100f, 1f)
        val start = side + (full - usable) * alignFrac.coerceIn(0f, 1f)
        for ((r, row) in lay.rows.withIndex()) {
            val unit = usable / row.total
            var x = start
            val top = padTop + r * rowHeight
            for (k in row.keys) {
                val kw = unit * k.weight
                if (k.kind != KeyKind.SPACER) keys.add(KeyRect(k, RectF(x, top, x + kw, top + rowHeight), r))
                x += kw
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    private fun upper(): Boolean = shiftState != 0 && layout?.lang == "en"

    private fun labelOf(k: Key): String = if (k.kind == KeyKind.CHAR && upper()) k.label.uppercase() else k.label

    private fun ensureShaders(h: Float, t: KbTheme) {
        if (h == shaderH && t === shaderTheme && sheen != null) return
        shaderH = h
        shaderTheme = t
        val topA = if (t.glass) 0x52 else if (t.dark) 0x22 else 0x66
        val botA = if (t.glass) 0x00 else if (t.dark) 0x2A else 0x14
        sheen = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(ColorMath.withAlpha(ColorMath.PAPER, topA), ColorMath.withAlpha(ColorMath.PAPER, topA / 6), ColorMath.withAlpha(0xFF000000.toInt(), botA)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        sheenPaint.shader = sheen
        rimShader = LinearGradient(0f, 0f, 0f, h, intArrayOf(t.rim, ColorMath.withAlpha(t.rim, 0)), floatArrayOf(0f, 0.75f), Shader.TileMode.CLAMP)
        rimPaint.shader = rimShader
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16f else (now - lastFrame).coerceIn(1L, 48L).toFloat()
        lastFrame = now
        if (t.bgStyle == 0) c.drawColor(t.bg)

        // the light that follows the finger fades in under it and out again when it lifts
        val glowTarget = if (fxGlow && ptrs.size() > 0) 1f else 0f
        glowAmount += (glowTarget - glowAmount) * (1f - exp(-dt / (if (glowTarget > glowAmount) 70f else 170f)))
        if (abs(glowTarget - glowAmount) < 0.01f) glowAmount = glowTarget
        var busy = glowAmount != glowTarget
        if (glowAmount > 0.01f) {
            glowPaint.shader = RadialGradient(
                glowX, glowY, dp(125f),
                intArrayOf(ColorMath.withAlpha(t.accent, (0x55 * glowAmount).toInt()), ColorMath.withAlpha(t.accent, 0)),
                null, Shader.TileMode.CLAMP,
            )
        }

        ripples.removeAll { now - it.t0 > RIPPLE_MS }
        ensureShaders(rowHeight - 2 * insetY, t)

        for (kr in keys) {
            val target = if (pressed.contains(kr)) 1f else 0f
            kr.press += (target - kr.press) * (1f - exp(-dt / (if (target > kr.press) 38f else 105f)))
            if (abs(target - kr.press) < 0.015f) kr.press = target else busy = true
            drawKey(c, kr, t, now)
        }
        if (ripples.isNotEmpty()) busy = true
        if (resizeMode) drawResize(c, t)
        if (busy) postInvalidateOnAnimation()
    }

    private fun drawKey(c: Canvas, kr: KeyRect, t: KbTheme, now: Long) {
        val k = kr.key
        val p = kr.press
        val sink = dp(1.4f) * p
        rf.set(kr.cell.left + insetX + sink, kr.cell.top + insetY + sink, kr.cell.right - insetX - sink, kr.cell.bottom - insetY - sink)
        // Gboard look: the enter key and the page switchers are pills, every other key is a rounded square
        val pill = k.kind == KeyKind.ENTER || k.kind == KeyKind.SYMBOLS || k.kind == KeyKind.SYMBOLS2 || k.kind == KeyKind.LETTERS
        val radius = if (pill) rf.height() / 2f else dp(11f)

        val base = when (k.kind) {
            KeyKind.CHAR -> if (k.fn) t.keyFn else t.key
            KeyKind.SPACE, KeyKind.LANG -> t.key
            KeyKind.ENTER -> t.accent
            KeyKind.SHIFT -> if (shiftState == 2) t.accent else t.keyFn
            else -> t.keyFn
        }
        val pressedTo = when {
            k.kind == KeyKind.ENTER || (k.kind == KeyKind.SHIFT && shiftState == 2) -> ColorMath.shade(t.accent, -0.16f)
            t.glass -> ColorMath.withAlpha(t.accent, 0xA0)
            else -> t.keyPressed
        }

        // layered shadow: it shrinks as the key is pushed down
        val sa = ColorMath.alpha(t.shadow)
        if (!t.glass && sa > 0) {
            val lift = 1f - 0.65f * p
            for (i in 0 until 3) {
                val spread = dp(0.7f) * i
                val dy = dp(0.9f + 1.0f * i) * lift
                fill.color = ColorMath.withAlpha(t.shadow, (sa * SHADOW_FRAC[i] * lift).toInt())
                c.drawRoundRect(rf.left - spread, rf.top + dy - spread, rf.right + spread, rf.bottom + dy + spread, radius + spread, radius + spread, fill)
            }
        }

        // body
        fill.color = ColorMath.blend(base, pressedTo, p)
        c.drawRoundRect(rf, radius, radius, fill)

        // gloss, light under the finger, ripples: all clipped to the key by being drawn as the key's own shape
        c.save()
        c.translate(rf.left, rf.top)
        sheenPaint.alpha = (255 * (1f - 0.55f * p)).toInt()
        c.drawRoundRect(0f, 0f, rf.width(), rf.height(), radius, radius, sheenPaint)
        val hw = density * 0.5f
        c.drawRoundRect(hw, hw, rf.width() - hw, rf.height() - hw, radius, radius, rimPaint)
        c.restore()

        if (glowAmount > 0.01f) c.drawRoundRect(rf, radius, radius, glowPaint)

        for (rp in ripples) {
            if (rp.kr !== kr) continue
            val age = ((now - rp.t0) / RIPPLE_MS.toFloat()).coerceIn(0f, 1f)
            val e = 1f - (1f - age) * (1f - age)
            val rad = dp(12f) + e * max(rf.width(), rf.height()) * 0.95f
            val a = (1f - age) * 0.36f
            val col = if (t.dark || t.glass) ColorMath.blend(t.accent, ColorMath.PAPER, 0.45f) else t.accent
            ripplePaint.shader = RadialGradient(
                rp.x, rp.y, max(1f, rad),
                intArrayOf(ColorMath.withAlphaF(col, a), ColorMath.withAlphaF(col, a * 0.55f), ColorMath.withAlphaF(col, 0f)),
                floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawRoundRect(rf, radius, radius, ripplePaint)
        }

        // tube-light edge of the neon theme: a few strokes of falling strength make the halo
        if (t.neon != 0) {
            val a = 0.5f + 0.5f * p
            strokePaint.shader = null
            strokePaint.strokeWidth = dp(5f); strokePaint.color = ColorMath.withAlphaF(t.neon, 0.08f * a)
            c.drawRoundRect(rf, radius, radius, strokePaint)
            strokePaint.strokeWidth = dp(3f); strokePaint.color = ColorMath.withAlphaF(t.neon, 0.16f * a)
            c.drawRoundRect(rf, radius, radius, strokePaint)
            strokePaint.strokeWidth = dp(1.2f); strokePaint.color = ColorMath.withAlphaF(t.neon, 0.85f * a)
            c.drawRoundRect(rf, radius, radius, strokePaint)
        }

        drawContent(c, kr, rf, t)
    }

    private fun drawContent(c: Canvas, kr: KeyRect, r: RectF, t: KbTheme) {
        val k = kr.key
        val cx = r.centerX()
        val cy = r.centerY() + dp(0.6f) * kr.press
        when (k.kind) {
            KeyKind.CHAR -> {
                val arabic = layout?.lang == "ar"
                textPaint.color = if (k.fn) t.onFn else t.text
                textPaint.typeface = letterFace
                textPaint.isFakeBoldText = false
                // Arabic letters are drawn bigger (as Gboard does): at the English size they are hard to read
                var size = (if (arabic) min(rowHeight * 0.50f, dp(27f)) else min(rowHeight * 0.42f, dp(24f))) * fontScale
                val lab = labelOf(k)
                if (lab.length > 2) size = min(size, dp(19f) * fontScale)
                size = min(size, min(kr.cell.width() * 0.92f, rowHeight * 0.62f))
                textPaint.textSize = size
                if (t.neon != 0) textPaint.setShadowLayer(dp(6f), 0f, 0f, ColorMath.withAlpha(t.neon, 0xE0))
                drawCentered(c, lab, cx, cy)
                textPaint.clearShadowLayer()
                textPaint.typeface = Typeface.DEFAULT
                if (k.hint.isNotEmpty()) {
                    hintPaint.color = t.textSub
                    hintPaint.textSize = dp(10f) * min(1.2f, fontScale)
                    // Gboard puts the number hints of an Arabic key in its top-left corner
                    val hx = if (arabic) r.left + dp(7f) else r.right - dp(7f)
                    c.drawText(k.hint, hx, r.top + dp(13f), hintPaint)
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
            KeyKind.BACKSPACE -> drawIcon(c, "backspace", cx, cy, dp(24f), t.onFn)
            KeyKind.LANG -> drawIcon(c, "language", cx, cy, dp(22f), t.text)
            KeyKind.EMOJI -> drawIcon(c, "emoji_emotions", cx, cy, dp(22f), t.onFn)
            KeyKind.SHIFT -> drawShift(c, cx, cy, if (shiftState == 2) t.onAccent else t.onFn, shiftState)
            KeyKind.SYMBOLS, KeyKind.SYMBOLS2, KeyKind.LETTERS -> {
                textPaint.color = t.onFn
                textPaint.typeface = letterFace          // a real medium face instead of fake bold
                textPaint.textSize = dp(15f)
                drawCentered(c, k.label, cx, cy)
                textPaint.typeface = Typeface.DEFAULT
            }
            KeyKind.ALT -> {
                textPaint.color = t.onFn
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
            strokePaint.shader = null
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
        if (resizeMode) return resizeTouch(e)
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
        p.kr = kr; p.downX = x; p.downY = y; p.lastX = x
        ptrs.put(id, p)
        pressed.add(kr)
        glowX = x; glowY = y
        if (fxRipple) {
            ripples.add(Ripple(kr, x, y, SystemClock.uptimeMillis()))
            if (ripples.size > 12) ripples.removeAt(0)
        }
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
                    handler.postDelayed(r, LONG_PRESS_MS)
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
        glowX = x; glowY = y
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
            invalidate()
            return
        }
        val nk = keyAt(x, y)
        // Like the Android keyboard: a rolling fingertip must not type the neighbour. The finger has to leave the key it
        // went down on by a clear margin (and really travel) before the other key takes over; a deliberate slide still does.
        if (nk != null && nk !== kr && !p.longFired && leftKeyClearly(kr, p, x, y)) {
            pressed.remove(kr)
            cancelRunnable(p)
            p.kr = nk
            pressed.add(nk)
            if (nk.key.kind != KeyKind.BACKSPACE) armKey(p, nk) else hidePopups()
        }
        invalidate()
    }

    /** True when (x, y) is a clear step away from [kr]: outside its edge by the margin AND far enough from where the finger went down. */
    private fun leftKeyClearly(kr: KeyRect, p: Ptr, x: Float, y: Float): Boolean {
        val c = kr.cell
        val outside = hypot(max(max(c.left - x, 0f), x - c.right), max(max(c.top - y, 0f), y - c.bottom))
        if (outside < dp(KEY_SWITCH_MARGIN_DP)) return false
        return hypot(x - p.downX, y - p.downY) >= dp(KEY_SWITCH_TRAVEL_DP)
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

    /** Preview builds only: logs where every key is on the screen, so the emulator test can tap them by name. */
    fun dumpKeys() {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val sb = StringBuilder()
        for (kr in keys) {
            val id = if (kr.key.kind == KeyKind.CHAR) kr.key.label else kr.key.kind.name
            sb.append(id).append('=').append((loc[0] + kr.cell.centerX()).toInt()).append(',').append((loc[1] + kr.cell.centerY()).toInt()).append(';')
        }
        if (resizeMode) {
            layoutResizeButtons()
            val l = areaLeft(); val r = areaRight()
            fun put(name: String, x: Float, y: Float) { sb.append(name).append('=').append((loc[0] + x).toInt()).append(',').append((loc[1] + y).toInt()).append(';') }
            put("RZ_TOP", (l + r) / 2f, dp(10f))
            put("RZ_LEFT", l + dp(10f), height / 2f)
            put("RZ_RIGHT", r - dp(10f), height / 2f)
            put("RZ_DONE", rzBtnDone.centerX(), rzBtnDone.centerY())
            put("RZ_RESET", rzBtnReset.centerX(), rzBtnReset.centerY())
        }
        Log.d("OmegaKeys", sb.toString())
    }

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

    // ------------------------------------------------------------------ resize by hand

    /** True while the user drags the edges to change the size of the keyboard. Nothing is typed in this mode. */
    var resizeMode = false
        set(v) {
            if (field == v) return
            field = v
            rzPart = RZ_NONE
            if (v) cancelAll()
            requestLayout()
            invalidate()
        }

    private val rzPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rzBtnDone = RectF()
    private val rzBtnReset = RectF()
    private var rzPart = RZ_NONE
    private var rzRawX = 0f
    private var rzRawY = 0f
    private var rzH0 = 0f
    private var rzL0 = 0f
    private var rzR0 = 0f

    private fun areaLeft(): Float {
        val side = dp(2f)
        val full = width - 2 * side
        return side + (full - full * widthScale.coerceIn(MIN_WIDTH_PCT / 100f, 1f)) * alignFrac.coerceIn(0f, 1f)
    }

    private fun areaRight(): Float {
        val side = dp(2f)
        val full = width - 2 * side
        return areaLeft() + full * widthScale.coerceIn(MIN_WIDTH_PCT / 100f, 1f)
    }

    private fun layoutResizeButtons() {
        val l = areaLeft()
        val r = areaRight()
        val cx = (l + r) / 2f
        val cy = height / 2f
        val bw = min(dp(98f), (r - l - dp(104f)) / 2f).coerceAtLeast(dp(58f))
        val bh = dp(38f)
        val gap = dp(10f)
        val top = cy - dp(2f)
        rzBtnDone.set(cx + gap / 2f, top, cx + gap / 2f + bw, top + bh)
        rzBtnReset.set(cx - gap / 2f - bw, top, cx - gap / 2f, top + bh)
    }

    private fun drawResize(c: Canvas, t: KbTheme) {
        val l = areaLeft()
        val r = areaRight()
        val h = height.toFloat()
        val cx = (l + r) / 2f
        layoutResizeButtons()
        // a light veil and a frame around the keys area: this is what the handles resize
        rzPaint.style = Paint.Style.FILL
        rzPaint.color = ColorMath.withAlpha(t.accent, 0x2A)
        c.drawRect(l, 0f, r, h, rzPaint)
        rzPaint.style = Paint.Style.STROKE
        rzPaint.strokeWidth = dp(2.5f)
        rzPaint.color = t.accent
        rf.set(l + dp(1.5f), dp(1.5f), r - dp(1.5f), h - dp(1.5f))
        c.drawRoundRect(rf, dp(10f), dp(10f), rzPaint)
        // the three handles: top (height), left and right (width)
        fun pill(left: Float, top: Float, right: Float, bottom: Float, active: Boolean) {
            rzPaint.style = Paint.Style.FILL
            rzPaint.color = if (active) t.onAccent else t.accent
            rf.set(left, top, right, bottom)
            c.drawRoundRect(rf, dp(6f), dp(6f), rzPaint)
            rzPaint.color = if (active) t.accent else t.onAccent
            rzPaint.style = Paint.Style.STROKE
            rzPaint.strokeWidth = dp(1.6f)
            rzPaint.strokeCap = Paint.Cap.ROUND
            if (right - left > bottom - top) {
                val y = (top + bottom) / 2f
                c.drawLine(left + dp(16f), y, right - dp(16f), y, rzPaint)
            } else {
                val x = (left + right) / 2f
                c.drawLine(x, top + dp(16f), x, bottom - dp(16f), rzPaint)
            }
        }
        pill(cx - dp(40f), dp(4f), cx + dp(40f), dp(16f), rzPart == RZ_TOP)
        pill(l + dp(4f), h / 2f - dp(40f), l + dp(16f), h / 2f + dp(40f), rzPart == RZ_LEFT)
        pill(r - dp(16f), h / 2f - dp(40f), r - dp(4f), h / 2f + dp(40f), rzPart == RZ_RIGHT)
        // a plate with the hint and the two buttons, readable over any keys
        val plateW = min(r - l - dp(76f), dp(264f))
        val plateH = dp(100f)
        val py = h / 2f - plateH / 2f - dp(2f)
        rf.set(cx - plateW / 2f, py, cx + plateW / 2f, py + plateH)
        rzPaint.style = Paint.Style.FILL
        rzPaint.color = ColorMath.withAlpha(if (t.dark) 0xFF101114.toInt() else 0xFFFFFFFF.toInt(), 0xEE)
        c.drawRoundRect(rf, dp(16f), dp(16f), rzPaint)
        textPaint.color = t.onBg
        textPaint.textSize = dp(13.5f)
        textPaint.isFakeBoldText = true
        val hint = android.text.TextUtils.ellipsize("اسحب المقابض لتكبير الكيبورد أو تصغيره", android.text.TextPaint(textPaint), plateW - dp(16f), android.text.TextUtils.TruncateAt.END).toString()
        drawCentered(c, hint, cx, py + dp(22f))
        fun button(rect: RectF, label: String, main: Boolean, active: Boolean) {
            rzPaint.style = Paint.Style.FILL
            rzPaint.color = if (main) (if (active) t.keyPressed else t.accent) else (if (active) t.keyPressed else t.keyFn)
            c.drawRoundRect(rect, dp(19f), dp(19f), rzPaint)
            textPaint.color = if (main) t.onAccent else t.onFn
            textPaint.textSize = dp(14f)
            drawCentered(c, label, rect.centerX(), rect.centerY())
        }
        button(rzBtnReset, "الأصلي", false, rzPart == RZ_RESET)
        button(rzBtnDone, "تم", true, rzPart == RZ_DONE)
        textPaint.isFakeBoldText = false
    }

    private fun rzPartAt(x: Float, y: Float): Int {
        layoutResizeButtons()
        if (rzBtnDone.contains(x, y)) return RZ_DONE
        if (rzBtnReset.contains(x, y)) return RZ_RESET
        val l = areaLeft()
        val r = areaRight()
        val zone = dp(44f)
        if (y < zone && x >= l - dp(20f) && x <= r + dp(20f)) return RZ_TOP
        if (x >= l - dp(28f) && x <= l + zone) return RZ_LEFT
        if (x >= r - zone && x <= r + dp(28f)) return RZ_RIGHT
        return RZ_NONE
    }

    private fun resizeTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rzPart = rzPartAt(e.x, e.y)
                rzRawX = e.rawX
                rzRawY = e.rawY
                rzH0 = height.toFloat()
                rzL0 = areaLeft()
                rzR0 = areaRight()
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (rzPart == RZ_TOP || rzPart == RZ_LEFT || rzPart == RZ_RIGHT) dragResize(e.rawX, e.rawY)
            MotionEvent.ACTION_UP -> {
                val part = rzPart
                rzPart = RZ_NONE
                layoutResizeButtons()
                when (part) {
                    RZ_DONE -> if (rzBtnDone.contains(e.x, e.y)) listener?.onResizeDone()
                    RZ_RESET -> if (rzBtnReset.contains(e.x, e.y)) listener?.onResizeReset()
                    RZ_TOP, RZ_LEFT, RZ_RIGHT -> listener?.onResized(heightScale, widthScale, alignFrac)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                val part = rzPart
                rzPart = RZ_NONE
                if (part == RZ_TOP || part == RZ_LEFT || part == RZ_RIGHT) listener?.onResized(heightScale, widthScale, alignFrac)
                invalidate()
            }
        }
        return true
    }

    /** The finger's place on the SCREEN decides (the window moves while it grows, so the view's own coordinates would feed back). */
    private fun dragResize(rawX: Float, rawY: Float) {
        val side = dp(2f)
        val full = width - 2 * side
        val minUsable = full * MIN_WIDTH_PCT / 100f
        when (rzPart) {
            RZ_TOP -> {
                val rows = (layout?.rows?.size ?: 4).coerceAtLeast(1)
                val maxPx = resources.displayMetrics.heightPixels * 0.5f
                val screenCap = (maxPx - padTop - padBottom) / (rows * dp(baseRowDp))
                val want = (rzH0 + (rzRawY - rawY) - padTop - padBottom) / (rows * dp(baseRowDp))
                heightScale = want.coerceIn(MIN_HEIGHT_PCT / 100f, min(MAX_HEIGHT_PCT / 100f, max(screenCap, MIN_HEIGHT_PCT / 100f)))
            }
            RZ_LEFT -> setArea((rzL0 + (rawX - rzRawX)).coerceIn(side, rzR0 - minUsable), rzR0)
            RZ_RIGHT -> setArea(rzL0, (rzR0 + (rawX - rzRawX)).coerceIn(rzL0 + minUsable, width - side))
        }
    }

    private fun setArea(l: Float, r: Float) {
        val side = dp(2f)
        val full = width - 2 * side
        val usable = (r - l).coerceIn(full * MIN_WIDTH_PCT / 100f, full)
        widthScale = usable / full
        alignFrac = if (full - usable < 1f) 0.5f else ((l - side) / (full - usable)).coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------ popups (drawn by the overlay)

    class PreviewSpec(val rect: RectF, val text: String, val t0: Long = SystemClock.uptimeMillis())
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
        val pad = dp(6f)
        // a long list is squeezed to fit the screen instead of running off its edge
        val cellW = min(dp(44f), (width - 2 * dp(3f) - 2 * pad) / items.size)
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
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = try { Typeface.create("sans-serif-medium", Typeface.NORMAL) } catch (_: Throwable) { Typeface.DEFAULT }
    }

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

    /** a floating bubble: soft shadow, a gradient body, a light rim, and a neon halo on the neon theme */
    private fun bubble(c: Canvas, r: RectF, radius: Float, t: KbTheme) {
        val sa = max(0x30, ColorMath.alpha(t.shadow))
        for (i in 0 until 3) {
            val spread = dp(1f) * i
            fill.shader = null
            fill.color = ColorMath.withAlpha(0xFF000000.toInt(), (sa * (0.5f - 0.14f * i)).toInt())
            c.drawRoundRect(r.left - spread, r.top + dp(2f) + dp(1f) * i - spread, r.right + spread, r.bottom + dp(2f) + dp(1f) * i + spread, radius + spread, radius + spread, fill)
        }
        // a shader is multiplied by the paint's own alpha: the faint shadow colour above must not stay on the paint, or the body turns see-through
        fill.color = 0xFF000000.toInt()
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, ColorMath.shade(ColorMath.opaque(t.popupBg), if (t.dark) 0.12f else 0.0f), ColorMath.shade(ColorMath.opaque(t.popupBg), if (t.dark) -0.04f else -0.06f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, radius, radius, fill)
        fill.shader = null
        line.shader = null
        line.strokeWidth = density
        line.color = if (t.neon != 0) ColorMath.withAlpha(t.neon, 0xE0) else ColorMath.withAlpha(t.accent, 0x70)
        c.drawRoundRect(r, radius, radius, line)
        if (t.neon != 0) {
            line.strokeWidth = dp(4f)
            line.color = ColorMath.withAlpha(t.neon, 0x22)
            c.drawRoundRect(r, radius, radius, line)
            line.strokeWidth = density
        }
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        var animating = false
        c.save()
        c.translate(dx, dy)
        val t = theme
        preview?.let { p ->
            val k = ((now - p.t0) / 110f).coerceIn(0f, 1f)
            val e = 1f - (1f - k) * (1f - k)
            if (k < 1f) animating = true
            val sc = 0.80f + 0.20f * e
            c.save()
            c.scale(sc, sc, p.rect.centerX(), p.rect.bottom)      // grows out of the key
            bubble(c, p.rect, dp(14f), t)
            text.color = t.text
            text.textSize = dp(31f)
            if (t.neon != 0) text.setShadowLayer(dp(7f), 0f, 0f, ColorMath.withAlpha(t.neon, 0xF0))
            val fm = text.fontMetrics
            c.drawText(p.text, p.rect.centerX(), p.rect.centerY() - (fm.ascent + fm.descent) / 2f, text)
            text.clearShadowLayer()
            c.restore()
        }
        alt?.let { a ->
            bubble(c, a.rect, dp(16f), t)
            for ((i, s) in a.items.withIndex()) {
                val l = a.rect.left + a.pad + i * a.cellW
                if (i == a.selected) {
                    fill.color = 0xFF000000.toInt()
                    fill.shader = LinearGradient(0f, a.rect.top, 0f, a.rect.bottom, ColorMath.shade(t.accent, 0.18f), t.accent, Shader.TileMode.CLAMP)
                    c.drawRoundRect(l + dp(2f), a.rect.top + dp(5f), l + a.cellW - dp(2f), a.rect.bottom - dp(5f), dp(11f), dp(11f), fill)
                    fill.shader = null
                    text.color = t.onAccent
                } else text.color = t.text
                text.textSize = dp(if (s.length > 2) 18f else 24f)
                val fm = text.fontMetrics
                c.drawText(s, l + a.cellW / 2, a.rect.centerY() - (fm.ascent + fm.descent) / 2f, text)
            }
        }
        c.restore()
        if (animating) postInvalidateOnAnimation()
    }
}
