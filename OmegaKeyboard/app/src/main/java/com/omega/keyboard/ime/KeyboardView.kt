package com.omega.keyboard.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
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
import com.omega.keyboard.core.Decor
import com.omega.keyboard.core.Key
import com.omega.keyboard.core.KeyKind
import com.omega.keyboard.core.KeySize
import com.omega.keyboard.core.Layout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
        /** resize mode: the finger asked for more than the screen's width can give the keys (once per drag) */
        fun onResizeLimit() {}
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

    /** the height of the rows, as a scale of the normal row: used where the keys are not square (landscape), see [KeySize] */
    var heightScale: Float = 1f
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }

    /**
     * Portrait: the keys are squares and the keyboard has ONE size ([widthScale]); the rows are as tall as the keys are wide, and the gaps,
     * letters and icons zoom with them, so a bigger or smaller keyboard is the same picture, never a stretched one. Landscape: the rows
     * have a height of their own ([heightScale]). See [KeySize].
     */
    var squareKeys: Boolean = true
        set(v) { if (field != v) { field = v; computeKeys(); requestLayout(); invalidate() } }

    /** size of the letters on the keys, 1 = normal */
    var fontScale: Float = 1f
        set(v) { if (field != v) { field = v; invalidate() } }

    /** width of the keys area as a share of the view (one-handed typing) and where it sits: 0 centre, 1 left, 2 right */
    var widthScale: Float = 1f
        set(v) { if (field != v) { field = v; computeKeys(); requestLayout(); invalidate() } }

    /** where the keys area sits when it is narrower than the view: 0 = at the left edge, 0.5 = centred, 1 = at the right edge */
    var alignFrac: Float = 0.5f
        set(v) { if (field != v) { field = v; computeKeys(); invalidate() } }

    var fxGlow: Boolean = true
    var fxRipple: Boolean = true

    /** the luxe look's extras: the metal catches the light as the phone tilts, gold sparks leave the enter key, a glint crosses the keys when they appear */
    var fxLuxe: Boolean = true

    var showPreview: Boolean = true
    var layout: Layout? = null
        private set

    private class KeyRect(val key: Key, val cell: RectF, val row: Int) {
        var press = 0f          // 0 = up .. 1 = fully pushed, animated
    }

    private class Ripple(val kr: KeyRect, val x: Float, val y: Float, val t0: Long)

    /** one gold spark: it leaves [x],[y] along [ang], [dist] far, starts after [delay] (0..0.18 of the animation) and turns by [spin] */
    private class Spark(val x: Float, val y: Float, val ang: Float, val dist: Float, val size: Float, val spin: Float, val delay: Float)

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
        private const val SPARK_MS = 720L
        private const val INTRO_MS = 800L

        /** how long a letter is held before its alternatives open (a slow tap must not become "another letter") */
        private const val LONG_PRESS_MS = 400L

        /** square keys: the size of a letter as a share of the key's side (Arabic letters are drawn bigger than English ones) */
        private const val SQUARE_LETTER_AR = 0.62f
        private const val SQUARE_LETTER_EN = 0.54f
        private val SHADOW_FRAC = floatArrayOf(0.55f, 0.32f, 0.16f)

        /** the halo of the enter key in the luxe look: four rings, each a little wider and fainter than the one inside it */
        private val GLOW_FRAC = floatArrayOf(0.34f, 0.24f, 0.15f, 0.08f)

        /** limits of the size the user can give the keyboard (percent of the normal one); how the two sizes go together is in [KeySize] */
        const val MIN_HEIGHT_PCT = KeySize.MIN_HEIGHT_PCT
        const val MAX_HEIGHT_PCT = KeySize.MAX_HEIGHT_PCT
        const val MIN_WIDTH_PCT = KeySize.MIN_WIDTH_PCT

        /** a finger that went down on a key moves to the next one only after leaving the first by this much and travelling this far (dp) */
        private const val KEY_SWITCH_MARGIN_DP = 5f
        private const val KEY_SWITCH_TRAVEL_DP = 11f

        private const val RZ_NONE = 0
        private const val RZ_TOP = 1
        private const val RZ_LEFT = 2
        private const val RZ_RIGHT = 3
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
    private val glowRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density * 1.4f }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimLoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    // the metal of the enter key: a stronger highlight, a diagonal streak of light, a bright upper edge and an amber lower edge
    private val goldSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val goldRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val goldEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
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
    private val bodyShaders = arrayOfNulls<Shader>(3)     // luxe look: gradient of a letter key, a function key and an accent (enter) key
    private var streakShader: Shader? = null
    private var streakW = 0f
    private var streakH = 0f
    private val streakM = Matrix()

    // the soft shadows round the see-through keys of a picture theme (see [drawGlassShadows])
    private var shadowBmp: Bitmap? = null
    private var shadowSig = 0L
    private var shadowRev = 0
    private val shadowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadowCut = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    // luxe extras: tilt of the phone (smoothed towards the sensor's value), sparks, the glint when the keys appear
    private var tiltX = 0f
    private var tiltY = 0f
    private var tiltTX = 0f
    private var tiltTY = 0f
    private val sparks = ArrayList<Spark>()
    private var sparkT0 = 0L
    private var introT0 = 0L
    private val sparkPath = Path()
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dirty = Rect()

    /** preview builds only: freeze the effects at a given point so a screenshot can show them (-1 = off) */
    var debugIntro = -1f
    var debugSpark = -1f
    var debugTilt = false

    // animation state
    private var lastFrame = 0L
    private var glowX = 0f
    private var glowY = 0f
    private var glowAmount = 0f

    private val baseRowDp = KeySize.BASE_ROW_DP
    private val padTop get() = dp(6f)
    private val padBottom get() = dp(8f)

    /**
     * The height of one row, px. Square keys: the side of the squares at the size the keyboard has. Landscape: the height the user asked
     * for, held to the tallest one at which the keys still have the shape of a key at this width (see [KeySize]).
     */
    private val rowHeight: Float get() = rowHeightFor(viewWidthPx())
    private fun rowHeightFor(viewW: Float): Float =
        if (squareKeys) KeySize.squareRowPx(viewW, density, widthScale)
        else dp(baseRowDp) * KeySize.rowScale(heightScale, viewW, density, widthScale)

    /** the rows as a scale of the normal row (what the readout of the resize handle shows is the size, see [sizePct]) */
    private val rowScale: Float get() = rowHeight / dp(baseRowDp)

    private fun viewWidthPx(): Float = if (width > 0) width.toFloat() else resources.displayMetrics.widthPixels.toFloat()

    /** square keys: how much the keyboard is zoomed (1 = all the width of the screen) */
    private val zoom: Float get() = KeySize.zoom(widthScale)

    /**
     * How much letters, hints and icons are scaled with the keyboard. Square keys: exactly as much as the keys are (they are one picture).
     * Landscape: they grow half as fast as the rows do, because the keys stay as wide as they were.
     */
    private val grow: Float get() = if (squareKeys) zoom else 1f + (rowScale - 1f).coerceAtLeast(0f) * 0.55f

    /** the free space round a key: equal on all sides for square keys (and zoomed with them), as it always was for the others */
    private val insetX get() = if (squareKeys) dp(KeySize.SQUARE_GAP_DP) * zoom else dp(2.5f)
    private val insetY get() = if (squareKeys) dp(KeySize.SQUARE_GAP_DP) * zoom else dp(4f)

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
        val w = MeasureSpec.getSize(widthSpec)
        val h = (rows * rowHeightFor(w.toFloat()) + padTop + padBottom).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        computeKeys()
    }

    private fun computeKeys() {
        keys.clear()
        shadowRev++
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

    /** the look English letters are typed in right now (a fancy-letter style): the keys show it, so the user sees what will come out */
    var capStyle: Decor.Style? = null
        set(v) { if (field !== v) { field = v; invalidate() } }

    /** a real font for the letters on the keys (an Arabic calligraphy face, say); null = the phone's own */
    var capFace: Typeface? = null
        set(v) { if (field !== v) { field = v; invalidate() } }

    /** how big that font's letters are drawn compared with the phone's own (some faces run small or tall) */
    var capScale: Float = 1f
        set(v) { if (field != v) { field = v; invalidate() } }

    private fun labelOf(k: Key): String {
        val s = if (k.kind == KeyKind.CHAR && upper()) k.label.uppercase() else k.label
        val st = capStyle
        return if (st != null && st.kind == Decor.Kind.LATIN && k.kind == KeyKind.CHAR && s.length == 1) Decor.typed(st, s, "", 0) else s
    }

    private fun ensureShaders(h: Float, t: KbTheme) {
        if (h == shaderH && t === shaderTheme && sheen != null) return
        shaderH = h
        shaderTheme = t
        if (t.fancy) {
            for (g in 0..2) bodyShaders[g] = LinearGradient(0f, 0f, 0f, h, t.stops(g), t.stopPos(g), Shader.TileMode.CLAMP)
            streakShader = null
            goldSheenPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(ColorMath.withAlpha(ColorMath.PAPER, 0x74), ColorMath.withAlpha(ColorMath.PAPER, 0x10), ColorMath.withAlpha(ColorMath.PAPER, 0)),
                floatArrayOf(0f, 0.42f, 0.55f), Shader.TileMode.CLAMP,
            )
            val bright = ColorMath.withAlpha(0xFFFFF4C0.toInt(), 0xB4)
            goldRimPaint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(bright, ColorMath.withAlpha(bright, 0)), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP)
            val amber = ColorMath.withAlpha(0xFF7A4A00.toInt(), 0xB0)
            goldEdgePaint.shader = LinearGradient(
                0f, 0f, 0f, h, intArrayOf(ColorMath.withAlpha(amber, 0), ColorMath.withAlpha(amber, 0), amber), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            // a short, clean highlight on the upper half; the body's own gradient does the shading
            sheen = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(ColorMath.withAlpha(ColorMath.PAPER, if (t.dark) 0x22 else 0x78), ColorMath.withAlpha(ColorMath.PAPER, 0)),
                floatArrayOf(0f, 0.55f), Shader.TileMode.CLAMP,
            )
            val lo = t.rimDark
            rimLoPaint.shader = LinearGradient(
                0f, 0f, 0f, h, intArrayOf(ColorMath.withAlpha(lo, 0), ColorMath.withAlpha(lo, 0), lo), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
        } else {
            val topA = if (t.glass) 0x52 else if (t.dark) 0x24 else 0x4A
            val botA = if (t.glass) 0x00 else if (t.dark) 0x26 else 0x0A
            sheen = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(ColorMath.withAlpha(ColorMath.PAPER, topA), ColorMath.withAlpha(ColorMath.PAPER, topA / 6), ColorMath.withAlpha(0xFF000000.toInt(), botA)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
        }
        sheenPaint.shader = sheen
        rimShader = LinearGradient(0f, 0f, 0f, h, intArrayOf(t.rim, ColorMath.withAlpha(t.rim, 0)), floatArrayOf(0f, 0.75f), Shader.TileMode.CLAMP)
        rimPaint.shader = rimShader
    }

    /**
     * A see-through key cannot carry a shadow underneath it (the shadow would show through the glass), so the shadow is laid round it:
     * a wide soft one and a tight one just below each key, in one picture of the whole grid that is made once per layout, theme and size
     * (every key's own shape is cut out of it, so the glass stays clear) and drawn under the keys. This is what lifts the keys off the picture.
     */
    private fun drawGlassShadows(c: Canvas, t: KbTheme) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || keys.isEmpty()) return
        val ink = if (t.dark) 0xFF050A1E.toInt() else 0xFF0A2A4A.toInt()
        val strength = if (t.dark) 0x5E else 0x48
        val corner = t.corner.coerceIn(0, Themes.CORNER_DP.size - 1)
        var sig = (w.toLong() * 31 + h) * 31 + shadowRev
        sig = (sig * 31 + corner) * 31 + rowHeight.toInt()
        sig = (sig * 31 + strength) * 31 + ink
        val old = shadowBmp
        if (sig != shadowSig || (old != null && (old.width != w || old.height != h))) {
            shadowSig = sig
            try {
                val bmp = if (old != null && old.width == w && old.height == h) old.also { it.eraseColor(0) }
                else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                shadowBmp = bmp
                val cv = Canvas(bmp)
                val r = RectF()
                // the same shape drawKey gives a key that is not pressed
                fun shape(kr: KeyRect, grow: Float, dy: Float): Float {
                    r.set(kr.cell.left + insetX, kr.cell.top + insetY, kr.cell.right - insetX, kr.cell.bottom - insetY)
                    val rad = min(dp(Themes.CORNER_DP[corner]), min(r.width(), r.height()) / 2f) + grow
                    r.set(r.left - grow, r.top - grow + dy, r.right + grow, r.bottom + grow + dy)
                    return rad
                }
                // wide and soft, then tight and darker: together they fade out the way a real shadow does
                val soft = BlurMaskFilter(dp(3.6f), BlurMaskFilter.Blur.NORMAL)
                val tight = BlurMaskFilter(dp(1.2f), BlurMaskFilter.Blur.NORMAL)
                for (layer in 0..1) {
                    shadowFill.maskFilter = if (layer == 0) soft else tight
                    shadowFill.color = ColorMath.withAlpha(ink, if (layer == 0) strength else strength * 3 / 4)
                    val dy = dp(if (layer == 0) 2.4f else 0.9f)
                    for (kr in keys) {
                        val rad = shape(kr, 0f, dy)
                        cv.drawRoundRect(r, rad, rad, shadowFill)
                    }
                }
                shadowFill.maskFilter = null
                for (kr in keys) {
                    val rad = shape(kr, 0.6f, 0f)
                    cv.drawRoundRect(r, rad, rad, shadowCut)
                }
            } catch (_: Throwable) {
                shadowBmp = null
                shadowFill.maskFilter = null
            }
        }
        shadowBmp?.let { c.drawBitmap(it, 0f, 0f, null) }
    }

    /** soft light round a key: [GLOW_FRAC] rings of [color], strongest next to the key */
    private fun drawGlow(c: Canvas, r: RectF, radius: Float, color: Int, strength: Float) {
        val a = ColorMath.alpha(color) * strength
        for (i in GLOW_FRAC.indices) {
            val spread = dp(1.7f) * (i + 1)
            fill.color = ColorMath.withAlpha(color, (a * GLOW_FRAC[i]).toInt())
            c.drawRoundRect(r.left - spread, r.top - spread, r.right + spread, r.bottom + spread, radius + spread, radius + spread, fill)
        }
    }

    /** the diagonal streak of light across the enter key: a broad band and a thin one, running from the lower left to the upper right */
    private fun streakFor(w: Float, h: Float): Shader {
        val cached = streakShader
        if (cached != null && abs(w - streakW) < 0.5f && abs(h - streakH) < 0.5f) return cached
        streakW = w
        streakH = h
        val gx = 0.9f / w
        val gy = 1f / h
        val n = gx * gx + gy * gy
        fun white(a: Int) = ColorMath.withAlpha(ColorMath.PAPER, a)
        return LinearGradient(
            0f, 0f, gx / n, gy / n,
            intArrayOf(white(0), white(0), white(0x5C), white(0), white(0), white(0x2E), white(0)),
            floatArrayOf(0f, 0.62f, 0.75f, 0.88f, 0.94f, 0.98f, 1f), Shader.TileMode.CLAMP,
        ).also { streakShader = it }
    }

    // ------------------------------------------------------------------ luxe extras: tilt, sparks, glint

    /** the phone's tilt, -1..1 on each axis, from the sensor: the metal of the enter key catches the light as it moves */
    fun setTilt(x: Float, y: Float) {
        if (debugTilt || !fxLuxe || !theme.fancy) return
        if (abs(x - tiltTX) < 0.01f && abs(y - tiltTY) < 0.01f) return
        tiltTX = x
        tiltTY = y
        accentRect()?.let { postInvalidateOnAnimation(it.left, it.top, it.right, it.bottom) }
    }

    /** the glint that crosses the keys once when they appear */
    fun playIntro() {
        if (!fxLuxe || !theme.fancy) return
        introT0 = SystemClock.uptimeMillis()
        invalidate()
    }

    /** preview builds only: shows the effects frozen (tilt, glint progress 0..1, spark age 0..1; -1 = off) */
    fun debugFx(tx: Float?, ty: Float?, intro: Float?, spark: Float?) {
        if (tx != null || ty != null) {
            debugTilt = true
            tiltTX = tx ?: 0f; tiltTY = ty ?: 0f
            tiltX = tiltTX; tiltY = tiltTY
        }
        if (intro != null) debugIntro = intro
        if (spark != null) {
            debugSpark = spark
            if (spark >= 0f) keys.firstOrNull { it.key.kind == KeyKind.ENTER }?.let { spawnSparks(it) } else sparks.clear()
        }
        invalidate()
    }

    /** the area the enter key (and a locked caps key) shines in, glow included */
    private fun accentRect(): Rect? {
        var found = false
        var l = 0f; var t = 0f; var r = 0f; var b = 0f
        for (kr in keys) {
            if (kr.key.kind != KeyKind.ENTER && !(kr.key.kind == KeyKind.SHIFT && shiftState == 2)) continue
            if (!found) { l = kr.cell.left; t = kr.cell.top; r = kr.cell.right; b = kr.cell.bottom; found = true }
            else { l = min(l, kr.cell.left); t = min(t, kr.cell.top); r = max(r, kr.cell.right); b = max(b, kr.cell.bottom) }
        }
        if (!found) return null
        val m = dp(14f)
        dirty.set((l - m).toInt(), (t - m).toInt(), (r + m).toInt() + 1, (b + m).toInt() + 1)
        return dirty
    }

    /** moves the tilt the keys show towards the sensor's value; true while it is still moving */
    private fun stepTilt(dt: Float): Boolean {
        if (debugTilt) return false
        val dx = tiltTX - tiltX
        val dy = tiltTY - tiltY
        if (abs(dx) < 0.004f && abs(dy) < 0.004f) { tiltX = tiltTX; tiltY = tiltTY; return false }
        val k = 1f - exp(-dt / 110f)
        tiltX += dx * k
        tiltY += dy * k
        return true
    }

    private fun spawnSparks(kr: KeyRect) {
        sparks.clear()
        val rnd = kotlin.random.Random(SystemClock.uptimeMillis())
        val n = 14
        for (i in 0 until n) {
            val a = (2.0 * Math.PI * i / n).toFloat() + rnd.nextFloat() * 0.45f
            sparks.add(
                Spark(
                    kr.cell.centerX(), kr.cell.centerY(), a, dp(26f) + rnd.nextFloat() * dp(36f),
                    dp(3f) + rnd.nextFloat() * dp(3.6f), rnd.nextFloat() * 1.6f, rnd.nextFloat() * 0.18f,
                ),
            )
        }
        sparkT0 = SystemClock.uptimeMillis()
    }

    /** a four-pointed star centred on [cx],[cy] with points [r] long, turned by [rot] */
    private fun starPath(cx: Float, cy: Float, r: Float, rot: Float) {
        sparkPath.reset()
        for (i in 0 until 8) {
            val rr = if (i % 2 == 0) r else r * 0.3f
            val a = rot + i * (Math.PI / 4).toFloat()
            val x = cx + cos(a) * rr
            val y = cy + sin(a) * rr
            if (i == 0) sparkPath.moveTo(x, y) else sparkPath.lineTo(x, y)
        }
        sparkPath.close()
    }

    /** true while sparks are flying */
    private fun drawSparks(c: Canvas, t: KbTheme, now: Long): Boolean {
        if (sparks.isEmpty() || !t.fancy) return false
        val age = if (debugSpark >= 0f) debugSpark else (now - sparkT0) / SPARK_MS.toFloat()
        if (age >= 1f) { sparks.clear(); return false }
        for (sp in sparks) {
            val a = ((age - sp.delay) / (1f - sp.delay)).coerceIn(0f, 1f)
            if (a <= 0f) continue
            val e = 1f - (1f - a) * (1f - a)
            val px = sp.x + cos(sp.ang) * sp.dist * e
            val py = sp.y + sin(sp.ang) * sp.dist * e - dp(9f) * a * a
            val alpha = (1f - a) * (1f - a)
            val sz = sp.size * (1f - 0.45f * a)
            fill.shader = null
            fill.color = ColorMath.withAlphaF(t.accent, 0.22f * alpha)
            c.drawCircle(px, py, sz * 1.9f, fill)
            starPath(px, py, sz * 1.5f, sp.spin * a * 1.3f)
            fill.color = ColorMath.withAlphaF(0xFFFFF1B8.toInt(), alpha)
            c.drawPath(sparkPath, fill)
        }
        return debugSpark < 0f
    }

    /** true while the glint crosses the keys */
    private fun drawSweep(c: Canvas, t: KbTheme, now: Long): Boolean {
        val p = when {
            debugIntro >= 0f -> debugIntro
            introT0 == 0L -> return false
            else -> (now - introT0) / INTRO_MS.toFloat()
        }
        if (p >= 1f) { introT0 = 0L; return false }
        val w = width.toFloat()
        val h = height.toFloat()
        val e = p * p * (3f - 2f * p)
        val bandW = w * 0.36f
        val x0 = -bandW + e * (w + 2f * bandW)
        val tint = if (t.dark) 0xFFFFE9B0.toInt() else 0xFFFFC94D.toInt()
        val a = if (t.dark) 0x34 else 0x40
        sweepPaint.shader = LinearGradient(
            x0, 0f, x0 + bandW, h * 0.5f,
            intArrayOf(ColorMath.withAlpha(tint, 0), ColorMath.withAlpha(tint, a), ColorMath.withAlpha(tint, 0)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, h, sweepPaint)
        return debugIntro < 0f
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
            if (t.fancy) {
                // the luxe look: a colourless lift under the finger, and the metal catching the light on the edges of the keys round it.
                // (A gold tint laid over the dark keys turned them olive-brown.)
                val lift = if (t.dark) ColorMath.PAPER else t.accent
                val liftA = if (t.dark) 0x1E else 0x1A
                glowPaint.shader = RadialGradient(
                    glowX, glowY, dp(120f),
                    intArrayOf(ColorMath.withAlpha(lift, (liftA * glowAmount).toInt()), ColorMath.withAlpha(lift, 0)),
                    null, Shader.TileMode.CLAMP,
                )
                val edge = if (t.dark) (t.metal.getOrNull(1) ?: t.accent) else ColorMath.shade(t.accent, -0.08f)
                glowRimPaint.shader = RadialGradient(
                    glowX, glowY, dp(112f),
                    intArrayOf(ColorMath.withAlpha(edge, (0xF0 * glowAmount).toInt()), ColorMath.withAlpha(edge, (0x70 * glowAmount).toInt()), ColorMath.withAlpha(edge, 0)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
                )
            } else {
                glowPaint.shader = RadialGradient(
                    glowX, glowY, dp(125f),
                    intArrayOf(ColorMath.withAlpha(t.accent, (0x55 * glowAmount).toInt()), ColorMath.withAlpha(t.accent, 0)),
                    null, Shader.TileMode.CLAMP,
                )
            }
        }

        ripples.removeAll { now - it.t0 > RIPPLE_MS }
        ensureShaders(rowHeight - 2 * insetY, t)
        val tiltBusy = fxLuxe && t.fancy && stepTilt(dt)
        if (t.glass) drawGlassShadows(c, t)

        for (kr in keys) {
            val target = if (pressed.contains(kr)) 1f else 0f
            kr.press += (target - kr.press) * (1f - exp(-dt / (if (target > kr.press) 38f else 105f)))
            if (abs(target - kr.press) < 0.015f) kr.press = target else busy = true
            drawKey(c, kr, t, now)
        }
        if (ripples.isNotEmpty()) busy = true
        if (fxLuxe && t.fancy) {
            if (drawSparks(c, t, now)) busy = true
            if (drawSweep(c, t, now)) busy = true
        }
        if (resizeMode) drawResize(c, t)
        if (busy) postInvalidateOnAnimation()
        else if (tiltBusy) accentRect()?.let { postInvalidateOnAnimation(it.left, it.top, it.right, it.bottom) }
    }

    private fun drawKey(c: Canvas, kr: KeyRect, t: KbTheme, now: Long) {
        val k = kr.key
        val p = kr.press
        val sink = dp(1.4f) * p
        rf.set(kr.cell.left + insetX + sink, kr.cell.top + insetY + sink, kr.cell.right - insetX - sink, kr.cell.bottom - insetY - sink)
        // one corner for every key (the enter key and the page switchers used to be pills, which made the bottom row look like
        // another keyboard): the rows read as one even grid. How round it is comes from the theme or the user's choice (a soft square
        // by default, like the keys of Gboard); a key never rounds more than its shorter side allows.
        val radius = min(dp(Themes.CORNER_DP[t.corner.coerceIn(0, Themes.CORNER_DP.size - 1)]), min(rf.width(), rf.height()) / 2f)

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

        // the enter key (and caps lock) shine in the luxe look
        val accentKey = k.kind == KeyKind.ENTER || (k.kind == KeyKind.SHIFT && shiftState == 2)
        val fancy = t.fancy
        if (fancy && accentKey) drawGlow(c, rf, radius, ColorMath.withAlpha(t.accent, 0x5A), 1f + 0.7f * p)

        // body
        if (fancy) {
            val grade = if (accentKey) 2 else if (base == t.keyFn) 1 else 0
            c.save()
            c.translate(rf.left, rf.top)
            bodyPaint.shader = if (p > 0.01f) {
                // while a key is pushed its gradient moves towards the pressed colour (built per frame, only for the keys being touched);
                // the metal keeps most of its shine
                val st = t.stops(grade)
                val to = IntArray(st.size) { i ->
                    if (grade == 2) ColorMath.blend(st[i], pressedTo, p * 0.6f)
                    else ColorMath.blend(st[i], if (i == 0) pressedTo else ColorMath.shade(pressedTo, -0.12f), p)
                }
                LinearGradient(0f, 0f, 0f, shaderH, to, t.stopPos(grade), Shader.TileMode.CLAMP)
            } else bodyShaders[grade]?.also {
                // the metal's bright band slides up and down as the phone tips
                if (grade == 2) { streakM.setTranslate(0f, tiltY * shaderH * 0.14f); it.setLocalMatrix(streakM) }
            }
            c.drawRoundRect(0f, 0f, rf.width(), rf.height(), radius, radius, bodyPaint)
            c.restore()
        } else {
            fill.color = ColorMath.blend(base, pressedTo, p)
            c.drawRoundRect(rf, radius, radius, fill)
        }

        // gloss, light under the finger, ripples: all clipped to the key by being drawn as the key's own shape
        c.save()
        c.translate(rf.left, rf.top)
        val hw = density * 0.5f
        if (fancy && accentKey) {
            goldSheenPaint.alpha = (255 * (1f - 0.5f * p)).toInt()
            c.drawRoundRect(0f, 0f, rf.width(), rf.height(), radius, radius, goldSheenPaint)
            val nw = kr.cell.width() - 2 * insetX
            val nh = kr.cell.height() - 2 * insetY
            val streak = streakFor(nw, nh)
            // the streak of light slides across the key as the phone turns
            streakM.setTranslate(tiltX * nw * 0.6f, tiltY * nh * 0.25f)
            streak.setLocalMatrix(streakM)
            streakPaint.shader = streak
            streakPaint.alpha = (255 * (1f - 0.4f * p)).toInt()
            c.drawRoundRect(0f, 0f, rf.width(), rf.height(), radius, radius, streakPaint)
            c.drawRoundRect(hw, hw, rf.width() - hw, rf.height() - hw, radius, radius, goldRimPaint)
            c.drawRoundRect(hw, hw, rf.width() - hw, rf.height() - hw, radius, radius, goldEdgePaint)
        } else {
            sheenPaint.alpha = (255 * (1f - 0.55f * p)).toInt()
            c.drawRoundRect(0f, 0f, rf.width(), rf.height(), radius, radius, sheenPaint)
            c.drawRoundRect(hw, hw, rf.width() - hw, rf.height() - hw, radius, radius, rimPaint)
            if (fancy) c.drawRoundRect(hw, hw, rf.width() - hw, rf.height() - hw, radius, radius, rimLoPaint)
        }
        c.restore()

        if (glowAmount > 0.01f) {
            c.drawRoundRect(rf, radius, radius, glowPaint)
            if (fancy) {
                val gw = glowRimPaint.strokeWidth / 2f
                c.drawRoundRect(rf.left + gw, rf.top + gw, rf.right - gw, rf.bottom - gw, radius, radius, glowRimPaint)
            }
        }

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
                val face = capFace
                textPaint.typeface = face ?: letterFace
                textPaint.isFakeBoldText = false
                // Arabic letters are drawn bigger (as Gboard does): at the English size they are hard to read
                val lab = labelOf(k)
                var size = letterSize(arabic, face)
                if (lab.length > 2) size = min(size, dp(19f) * fontScale)
                size = min(size, rowHeight * (if (squareKeys) 0.86f else 0.62f))
                // every letter of the page is drawn at the one size at which the widest of them still sits inside its key (a wide Arabic
                // letter in a calligraphy face, or a big letter-size setting, used to run over the edge of the key); a long label has its own fit
                size *= if (lab.length <= 2) glyphFit(arabic, face, r.width()) else 1f
                textPaint.textSize = size
                if (lab.length > 2) {
                    val room = r.width() * 0.94f
                    val wide = textPaint.measureText(lab)
                    if (wide > room && wide > 0f) textPaint.textSize = size * room / wide
                }
                if (t.neon != 0) textPaint.setShadowLayer(dp(6f), 0f, 0f, ColorMath.withAlpha(t.neon, 0xE0))
                else if (t.fancy && t.dark) textPaint.setShadowLayer(dp(0.9f), 0f, dp(0.9f), 0x80000000.toInt())
                else if (t.letterShadow != 0) textPaint.setShadowLayer(dp(1.6f), 0f, dp(0.9f), t.letterShadow)
                drawCentered(c, lab, cx, cy)
                textPaint.clearShadowLayer()
                textPaint.typeface = Typeface.DEFAULT
                if (k.hint.isNotEmpty()) {
                    hintPaint.color = t.textSub
                    val hg = min(grow, 1.2f)
                    hintPaint.textSize = dp(10f) * min(1.2f, fontScale) * hg
                    // Gboard puts the number hints of an Arabic key in its top-left corner
                    val hx = if (arabic) r.left + dp(7f) else r.right - dp(7f)
                    c.drawText(k.hint, hx, r.top + dp(13f) * hg, hintPaint)
                }
            }
            KeyKind.SPACE -> {
                if (spaceLabel.isNotEmpty()) {
                    textPaint.color = t.textSub
                    textPaint.textSize = dp(13f) * min(grow, 1.25f)
                    if (t.fancy) {
                        // the language name with a short accent line under it
                        drawCentered(c, spaceLabel, cx, cy - dp(3f))
                        fill.color = ColorMath.withAlpha(t.accent, 0xC0)
                        c.drawRoundRect(cx - dp(13f), cy + dp(9f), cx + dp(13f), cy + dp(10.6f), dp(1f), dp(1f), fill)
                    } else drawCentered(c, spaceLabel, cx, cy)
                }
            }
            KeyKind.ENTER -> drawIcon(c, enterIcon, cx, cy, iconSize(24f, r), t.onAccent)
            KeyKind.BACKSPACE -> drawIcon(c, "backspace", cx, cy, iconSize(24f, r), t.onFn)
            KeyKind.LANG -> drawIcon(c, "language", cx, cy, iconSize(22f, r), t.text)
            KeyKind.EMOJI -> drawIcon(c, "emoji_emotions", cx, cy, iconSize(22f, r), t.onFn)
            KeyKind.SHIFT -> drawShift(c, cx, cy, if (shiftState == 2) t.onAccent else t.onFn, shiftState, min(grow, 1.3f))
            KeyKind.SYMBOLS, KeyKind.SYMBOLS2, KeyKind.LETTERS -> {
                textPaint.color = t.onFn
                textPaint.typeface = letterFace          // a real medium face instead of fake bold
                textPaint.textSize = dp(15f) * min(grow, 1.25f)
                drawCentered(c, k.label, cx, cy)
                textPaint.typeface = Typeface.DEFAULT
            }
            KeyKind.ALT -> {
                textPaint.color = t.onFn
                textPaint.textSize = dp(20f) * min(grow, 1.25f)
                drawCentered(c, k.label, cx, cy)
            }
            KeyKind.SPACER -> {}
        }
    }

    private fun drawCentered(c: Canvas, s: String, cx: Float, cy: Float) {
        val fm = textPaint.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    /** the size a letter would be drawn at before it is fitted to its key: it grows a little as the keyboard is made taller */
    private fun letterSize(arabic: Boolean, face: Typeface?): Float {
        // square keys: a share of the key, so the letters zoom with the keyboard; the others: as before
        val base = if (squareKeys) rowHeight * (if (arabic) SQUARE_LETTER_AR else SQUARE_LETTER_EN)
        else if (arabic) min(rowHeight * 0.50f, dp(27f) * grow) else min(rowHeight * 0.42f, dp(24f) * grow)
        return base * fontScale * (if (face != null) capScale else 1f)
    }

    private var fitSig = 0L
    private var fitValue = 1f

    /**
     * How much smaller than [letterSize] the letters of this page must be drawn so that the widest of them fits inside its key (1 when all
     * of them do). One number for the whole page, so the letters of a row stay the same size; cached until the page, the font, the
     * size or the key width changes.
     */
    private fun glyphFit(arabic: Boolean, face: Typeface?, keyFace: Float): Float {
        val lay = layout ?: return 1f
        val base = letterSize(arabic, face)
        var sig = lay.id.hashCode().toLong()
        sig = sig * 31 + System.identityHashCode(face)
        sig = sig * 31 + System.identityHashCode(capStyle)
        sig = sig * 31 + java.lang.Float.floatToIntBits(base)
        sig = sig * 31 + java.lang.Float.floatToIntBits(keyFace)
        sig = sig * 31 + (if (upper()) 1 else 0)
        if (sig == fitSig) return fitValue
        val room = keyFace * 0.94f
        val tf = textPaint.typeface
        val sz = textPaint.textSize
        textPaint.typeface = face ?: letterFace
        textPaint.textSize = base
        var worst = 0f
        for (row in lay.rows) for (key in row.keys) {
            if (key.kind != KeyKind.CHAR) continue
            val lab = labelOf(key)
            if (lab.isEmpty() || lab.length > 2) continue
            worst = max(worst, textPaint.measureText(lab))
        }
        textPaint.typeface = tf
        textPaint.textSize = sz
        fitValue = if (worst > room && worst > 0f) (room / worst).coerceAtLeast(0.55f) else 1f
        fitSig = sig
        return fitValue
    }

    /** an icon: it grows with the keyboard like the letters do, and never past what its key can hold */
    private fun iconSize(baseDp: Float, r: RectF): Float = min(dp(baseDp) * grow, min(r.width() * 0.92f, r.height() * 0.72f))

    private fun drawIcon(c: Canvas, name: String, cx: Float, cy: Float, size: Float, color: Int) {
        val d = icons.getOrPut(name) { Icons.get(context, name) } ?: return
        d.setTint(color)
        val h = size / 2f
        d.setBounds((cx - h).toInt(), (cy - h).toInt(), (cx + h).toInt(), (cy + h).toInt())
        d.draw(c)
    }

    /** Gboard-style shift arrow: outline when off, filled when on, with a bar for caps lock. */
    private fun drawShift(c: Canvas, cx: Float, cy: Float, color: Int, state: Int, scale: Float = 1f) {
        val w = dp(18f) * scale
        val h = dp(18f) * scale
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
        if (fxLuxe && theme.fancy && kr.key.kind == KeyKind.ENTER) spawnSparks(kr)
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
            val l = areaLeft(); val r = areaRight()
            fun put(name: String, x: Float, y: Float) { sb.append(name).append('=').append((loc[0] + x).toInt()).append(',').append((loc[1] + y).toInt()).append(';') }
            put("RZ_TOP", (l + r) / 2f, dp(10f))
            put("RZ_LEFT", l + dp(10f), height / 2f)
            put("RZ_RIGHT", r - dp(10f), height / 2f)
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
        shadowBmp = null
        shadowSig = 0L
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
    private var rzPart = RZ_NONE
    private var rzRawX = 0f
    private var rzRawY = 0f
    private var rzH0 = 0f
    private var rzL0 = 0f
    private var rzR0 = 0f

    /** the rows' scale and the keys area's width as they were when the drag began: a side handle keeps the keys' shape between them */
    private var rzScale0 = 1f
    private var rzW0 = 1f
    private var rzLimitTold = false

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

    private fun drawResize(c: Canvas, t: KbTheme) {
        val l = areaLeft()
        val r = areaRight()
        val h = height.toFloat()
        val cx = (l + r) / 2f
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
        fun pill(left: Float, top: Float, right: Float, bottom: Float, active: Boolean, line: Boolean = true) {
            rzPaint.style = Paint.Style.FILL
            rzPaint.color = if (active) t.onAccent else t.accent
            rf.set(left, top, right, bottom)
            c.drawRoundRect(rf, dp(7f), dp(7f), rzPaint)
            if (!line) return
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
        // the top handle carries the readout of the size (no line through it: the text would be struck out)
        pill(cx - dp(42f), dp(3f), cx + dp(42f), dp(21f), rzPart == RZ_TOP, line = false)
        pill(l + dp(4f), h / 2f - dp(40f), l + dp(16f), h / 2f + dp(40f), rzPart == RZ_LEFT)
        pill(r - dp(16f), h / 2f - dp(40f), r - dp(4f), h / 2f + dp(40f), rzPart == RZ_RIGHT)
        // what the size is now, in the middle of the top handle's bar (the text is drawn in the colour the bar's line would have)
        textPaint.color = if (rzPart == RZ_TOP) t.accent else t.onAccent
        textPaint.textSize = dp(10f)
        textPaint.isFakeBoldText = true
        // square keys: the size is the share of the screen's width the keyboard takes; landscape: the height of the rows
        val pct = Math.round((if (squareKeys) zoom else rowScale) * 100f)
        // at the biggest the screen allows (all of its width, the tallest rows) the readout says so: the edge stops there
        val top = if (squareKeys) KeySize.fullWidth(widthScale) else KeySize.atMost(rowScale, widthScale, viewWidthPx(), density)
        drawCentered(c, if (top) "$pct% · أقصى" else "$pct%", cx, dp(12f))
        textPaint.isFakeBoldText = false
    }

    private fun rzPartAt(x: Float, y: Float): Int {
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
                rzScale0 = rowScale
                rzW0 = widthScale.coerceIn(MIN_WIDTH_PCT / 100f, 1f)
                rzLimitTold = false
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (rzPart == RZ_TOP || rzPart == RZ_LEFT || rzPart == RZ_RIGHT) dragResize(e.rawX, e.rawY)
            MotionEvent.ACTION_UP -> {
                val part = rzPart
                rzPart = RZ_NONE
                if (part == RZ_TOP || part == RZ_LEFT || part == RZ_RIGHT) listener?.onResized(heightScale, widthScale, alignFrac)
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

    /**
     * The finger's place on the SCREEN decides (the window moves while it grows, so the view's own coordinates would feed back).
     * Square keys: there is one size, so the top handle makes the whole keyboard bigger or smaller (the rows are as tall as the finger
     * leaves them, the width follows, the keys stay square) and a side handle moves an edge (the height follows it); nothing is ever
     * stretched. Landscape: the two sizes move together, see [KeySize].
     */
    private fun dragResize(rawX: Float, rawY: Float) {
        val side = dp(2f)
        val full = width - 2 * side
        val minUsable = full * MIN_WIDTH_PCT / 100f
        val viewW = viewWidthPx()
        when (rzPart) {
            RZ_TOP -> {
                val rows = (layout?.rows?.size ?: 4).coerceAtLeast(1)
                if (squareKeys) {
                    // the rows the finger leaves (the keyboard's top edge follows it) decide the size; the area grows round the place it has
                    val wantRow = (rzH0 + (rzRawY - rawY) - padTop - padBottom) / rows
                    val w = KeySize.dragTopSquare(wantRow, viewW, density)
                    if (w != widthScale) widthScale = w
                    // the finger went well past the whole width of the screen: say why it stopped (once per drag)
                    if (!rzLimitTold && KeySize.beyondWidth(wantRow / KeySize.squareRowAtFull(viewW, density))) {
                        rzLimitTold = true
                        listener?.onResizeLimit()
                    }
                } else {
                    val maxPx = resources.displayMetrics.heightPixels * 0.5f
                    val screenCap = (maxPx - padTop - padBottom) / (rows * dp(baseRowDp))
                    val want = ((rzH0 + (rzRawY - rawY) - padTop - padBottom) / (rows * dp(baseRowDp))).coerceAtMost(max(screenCap, MIN_HEIGHT_PCT / 100f))
                    val s = KeySize.dragTop(want, rzScale0, rzW0, viewW, density)
                    // the area grows around the place it has (left, middle or right), so it grows to the side where there is room
                    if (s.w != widthScale) widthScale = s.w
                    heightScale = s.h
                    // the finger went well past the biggest size the screen allows: say why it stopped (once per drag)
                    if (!rzLimitTold && want > s.h + 0.04f && KeySize.atMost(s.h, s.w, viewW, density)) {
                        rzLimitTold = true
                        listener?.onResizeLimit()
                    }
                }
            }
            RZ_LEFT -> {
                setArea((rzL0 + (rawX - rzRawX)).coerceIn(side, rzR0 - minUsable), rzR0)
                if (!squareKeys) heightScale = KeySize.dragSide(widthScale, rzScale0, rzW0, viewW, density).h
            }
            RZ_RIGHT -> {
                setArea(rzL0, (rzR0 + (rawX - rzRawX)).coerceIn(rzL0 + minUsable, width - side))
                if (!squareKeys) heightScale = KeySize.dragSide(widthScale, rzScale0, rzW0, viewW, density).h
            }
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

    class PreviewSpec(val rect: RectF, val text: String, val t0: Long = SystemClock.uptimeMillis(), val face: Typeface? = null, val scale: Float = 1f)
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
        previewSpec = PreviewSpec(RectF(cx - w / 2, bottom - h, cx + w / 2, bottom), txt, face = capFace, scale = capScale)
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
    private val plainFace: Typeface = try { Typeface.create("sans-serif-medium", Typeface.NORMAL) } catch (_: Throwable) { Typeface.DEFAULT }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = plainFace
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
        line.color = if (t.neon != 0) ColorMath.withAlpha(t.neon, 0xE0) else if (t.onPopup != 0) ColorMath.withAlpha(t.onPopup, 0x66) else ColorMath.withAlpha(t.accent, 0x70)
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
            text.color = if (t.onPopup != 0) t.onPopup else t.text
            text.textSize = dp(31f) * (if (p.face != null) p.scale else 1f)
            if (p.face != null) text.typeface = p.face
            if (t.neon != 0) text.setShadowLayer(dp(7f), 0f, 0f, ColorMath.withAlpha(t.neon, 0xF0))
            val fm = text.fontMetrics
            c.drawText(p.text, p.rect.centerX(), p.rect.centerY() - (fm.ascent + fm.descent) / 2f, text)
            text.clearShadowLayer()
            text.typeface = plainFace
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
                } else text.color = if (t.onPopup != 0) t.onPopup else t.text
                text.textSize = dp(if (s.length > 2) 18f else 24f)
                val fm = text.fontMetrics
                c.drawText(s, l + a.cellW / 2, a.rect.centerY() - (fm.ascent + fm.descent) / 2f, text)
            }
        }
        c.restore()
        if (animating) postInvalidateOnAnimation()
    }
}
