package com.omega.keyboard.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.omega.keyboard.AppLog
import com.omega.keyboard.Bg
import com.omega.keyboard.ime.Icons
import kotlin.math.max
import kotlin.math.min

/** Full-screen see-through layer that paints the translated text on top of the original text. Never takes touches. */
class TranslationLayer(ctx: Context) : View(ctx) {
    class Box(val rect: Rect, val bg: Int, val fg: Int) {
        @Volatile var text: String? = null
        var layout: StaticLayout? = null
    }

    private val boxes = ArrayList<Box>()
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loc = IntArray(2)
    private val density = resources.displayMetrics.density

    fun setBoxes(list: List<Box>) {
        boxes.clear()
        boxes.addAll(list)
        invalidate()
    }

    fun clear() {
        boxes.clear()
        invalidate()
    }

    fun put(index: Int, text: String) {
        val b = boxes.getOrNull(index) ?: return
        b.text = text
        b.layout = null
        invalidate()
    }

    private fun build(b: Box, text: String, w: Int): StaticLayout {
        var size = min(b.rect.height() * 0.62f, 20f * density)
        val minSize = 10f * density
        paint.color = b.fg
        paint.typeface = Typeface.DEFAULT
        var lay: StaticLayout
        while (true) {
            paint.textSize = size
            lay = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(false)
                .build()
            if (lay.height <= b.rect.height() * 1.1f || size <= minSize) break
            size -= density
        }
        return lay
    }

    override fun onDraw(c: Canvas) {
        getLocationOnScreen(loc)
        c.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val pad = 3f * density
        for (b in boxes) {
            val t = b.text ?: continue
            val w = max(b.rect.width(), (48 * density).toInt())
            val lay = b.layout ?: build(b, t, w).also { b.layout = it }
            val h = max(b.rect.height().toFloat(), lay.height.toFloat())
            val r = RectF(b.rect.left - pad, b.rect.top - pad, b.rect.left + w + pad, b.rect.top + h + pad)
            fill.color = b.bg
            c.drawRoundRect(r, 4f * density, 4f * density, fill)
            c.save()
            c.translate(b.rect.left.toFloat(), b.rect.top + (h - lay.height) / 2f)
            lay.draw(c)
            c.restore()
        }
    }
}

/**
 * The small floating control strip. Two looks:
 *  - icon buttons next to the text (screen translation: language / refresh / close)
 *  - big labelled buttons under the text (autonomous mode: voice on/off / wait / stop)
 * Draggable by its text.
 */
class ControlBar(ctx: Context) : LinearLayout(ctx) {
    class Action(val id: String, val icon: String, val label: String = "", val color: Int = 0x33FFFFFF)

    var onAction: ((String) -> Unit)? = null
    var onDrag: ((dx: Int, dy: Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val titleView = TextView(ctx)
    private val statusView = TextView(ctx)
    private val texts = LinearLayout(ctx)
    private val inline = LinearLayout(ctx)   // round icon buttons beside the text
    private val row = LinearLayout(ctx)      // labelled buttons under the text
    private val textsLp = LayoutParams((190 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    private val labeledTextWidth = min((330 * d).toInt(), resources.displayMetrics.widthPixels - (32 * d).toInt()) - (24 * d).toInt()

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(0xF2202124.toInt())
            cornerRadius = 26 * d
            setStroke((1 * d).toInt().coerceAtLeast(1), 0x40FFFFFF)
        }
        elevation = 8 * d
        setPadding((14 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())

        texts.orientation = VERTICAL
        titleView.apply {
            setTextColor(0xFF8AB4F8.toInt()); textSize = 11f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusView.apply {
            setTextColor(Color.WHITE); textSize = 13f; maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        texts.addView(titleView)
        texts.addView(statusView)

        val top = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(texts, textsLp)
        inline.orientation = HORIZONTAL
        top.addView(inline, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(top, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER
        row.visibility = View.GONE
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = (8 * d).toInt() })

        // dragging by the text area
        var lx = 0f
        var ly = 0f
        texts.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.rawX; ly = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    onDrag?.invoke((e.rawX - lx).toInt(), (e.rawY - ly).toInt())
                    lx = e.rawX; ly = e.rawY
                    true
                }
                else -> true
            }
        }
    }

    fun setTitle(s: String) { titleView.text = s }

    fun setStatus(s: String, warn: Boolean = false) {
        statusView.text = s
        statusView.setTextColor(if (warn) 0xFFFFD54F.toInt() else Color.WHITE)
    }

    private fun tinted(a: Action) = Icons.get(context, a.icon)?.also { it.setTint(Color.WHITE) }

    private fun roundButton(a: Action): View = ImageView(context).apply {
        setImageDrawable(tinted(a))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding((9 * d).toInt(), (9 * d).toInt(), (9 * d).toInt(), (9 * d).toInt())
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(a.color) }
        layoutParams = LayoutParams((38 * d).toInt(), (38 * d).toInt()).also { it.leftMargin = (6 * d).toInt() }
    }

    private fun pillButton(a: Action): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(a.color); cornerRadius = 22 * d }
        addView(ImageView(context).apply { setImageDrawable(tinted(a)) }, LayoutParams((22 * d).toInt(), (22 * d).toInt()))
        addView(TextView(context).apply {
            text = a.label; setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding((6 * d).toInt(), 0, 0, 0)
        })
        layoutParams = LayoutParams(0, (44 * d).toInt(), if (a.id == "voice") 1.4f else 1f).also {
            it.leftMargin = (4 * d).toInt(); it.rightMargin = (4 * d).toInt()
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.alpha = 0.7f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
            }
            false
        }
    }

    fun setActions(list: List<Action>) {
        inline.removeAllViews()
        row.removeAllViews()
        val labeled = list.any { it.label.isNotEmpty() }
        row.visibility = if (labeled) View.VISIBLE else View.GONE
        textsLp.width = if (labeled) labeledTextWidth else (190 * d).toInt()
        texts.layoutParams = textsLp
        for (a in list) {
            val btn = if (a.label.isEmpty()) roundButton(a) else pillButton(a)
            btn.isClickable = true
            btn.contentDescription = a.label.ifEmpty { a.id }
            btn.setOnClickListener { onAction?.invoke(a.id) }
            if (a.label.isEmpty()) inline.addView(btn) else row.addView(btn)
        }
    }
}

/** Adds / removes the two overlay windows. Safe to call from any thread. */
object Overlays {
    private var layer: TranslationLayer? = null
    private var bar: ControlBar? = null
    private var barLp: WindowManager.LayoutParams? = null
    private var layerOwner: Context? = null
    private var barOwner: Context? = null

    private fun wm(ctx: Context) = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** Runs [block] on the main thread with the translation layer (created if needed). */
    fun withLayer(svc: AccessibilityService, block: (TranslationLayer) -> Unit) {
        Bg.ui {
            try {
                var l = layer
                if (l == null) {
                    l = TranslationLayer(svc)
                    val lp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        PixelFormat.TRANSLUCENT
                    )
                    if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    lp.gravity = Gravity.TOP or Gravity.START
                    wm(svc).addView(l, lp)
                    layer = l
                    layerOwner = svc
                }
                block(l)
            } catch (t: Throwable) {
                AppLog.e("overlay", "layer failed", t)
            }
        }
    }

    fun hideLayer() {
        Bg.ui {
            val l = layer ?: return@ui
            try { wm(layerOwner ?: l.context).removeView(l) } catch (_: Throwable) {}
            layer = null
        }
    }

    /** Runs [block] on the main thread with the control bar (created if needed). */
    fun withBar(svc: AccessibilityService, keepScreenOn: Boolean = false, block: (ControlBar) -> Unit) {
        Bg.ui {
            try {
                var b = bar
                if (b == null) {
                    b = ControlBar(svc)
                    var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    if (keepScreenOn) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    val lp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
                    )
                    lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    lp.y = (56 * svc.resources.displayMetrics.density).toInt()
                    val wmgr = wm(svc)
                    b.onDrag = { dx, dy ->
                        lp.x += dx
                        lp.y += dy
                        try { wmgr.updateViewLayout(b, lp) } catch (_: Throwable) {}
                    }
                    wmgr.addView(b, lp)
                    bar = b
                    barLp = lp
                    barOwner = svc
                }
                block(b)
            } catch (t: Throwable) {
                AppLog.e("overlay", "bar failed", t)
            }
        }
    }

    fun hideBar() {
        Bg.ui {
            val b = bar ?: return@ui
            try { wm(barOwner ?: b.context).removeView(b) } catch (_: Throwable) {}
            bar = null
            barLp = null
        }
    }

    fun hideAll() {
        hideLayer()
        hideBar()
    }

    val barVisible: Boolean get() = bar != null
}
