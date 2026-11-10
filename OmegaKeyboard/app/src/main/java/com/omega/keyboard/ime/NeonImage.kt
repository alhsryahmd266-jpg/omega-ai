package com.omega.keyboard.ime

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import com.omega.keyboard.core.ColorMath
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Some apps (WhatsApp sending, most chat boxes built with modern UI toolkits) throw the colour of typed text away because
 * they only keep plain text. A picture cannot be stripped like that, so this draws the text as a glowing neon sign on a dark
 * card and saves it as a PNG; the keyboard then inserts it as an image, or opens the share sheet.
 */
object NeonRenderer {

    private const val WIDTH = 1080
    private const val PAD = 120
    private const val MAX_CHARS = 500
    private const val KEEP_FILES = 8
    private const val DEFAULT_NEON = 0xFF00F0FF.toInt()

    fun dir(ctx: Context): File = File(ctx.applicationContext.cacheDir, "neon").also { it.mkdirs() }

    /** a sign must glow: very dark or "no colour" choices become a bright neon instead of vanishing into the card */
    private fun signColor(color: Int): Int = when {
        color == 0 -> DEFAULT_NEON
        ColorMath.luminance(color) < 0.12f -> ColorMath.blend(ColorMath.opaque(color), ColorMath.PAPER, 0.62f)
        else -> ColorMath.opaque(color)
    }

    private fun paintFor(size: Float): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun layoutFor(text: CharSequence, p: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.14f)
            .setIncludePad(false)
            .build()

    /** every letter gets the colour [colorAt] gives it (counting letters, not spaces) */
    private fun coloured(text: String, colorAt: (Int) -> Int): SpannableString {
        val sp = SpannableString(text)
        var i = 0
        var n = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            if (!Character.isWhitespace(cp)) {
                sp.setSpan(ForegroundColorSpan(colorAt(n)), i, i + len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                n++
            }
            i += len
        }
        return sp
    }

    fun render(raw: String, color: Int, rainbow: Boolean): Bitmap {
        val text = raw.trim().let { if (it.length > MAX_CHARS) it.take(MAX_CHARS - 1) + "…" else it }
        val tint = if (rainbow) ColorMath.rainbow(7) else signColor(color)
        val letter: (Int) -> Int = { n -> if (rainbow) ColorMath.rainbow(n) else tint }
        val maxW = WIDTH - 2 * PAD

        // the biggest letters that still fit: a short sentence is large, a long one shrinks to a few lines
        var size = 176f
        var probe = layoutFor(text, paintFor(size), maxW)
        while ((probe.lineCount > 7 || probe.height > 1500) && size > 40f) {
            size -= 6f
            probe = layoutFor(text, paintFor(size), maxW)
        }
        val textH = probe.height
        val h = maxOf(textH + 2 * PAD, 560)
        val bmp = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        drawBackdrop(c, WIDTH, h, tint, rainbow)

        // the glow: wide and faint first, then tighter and brighter, then the white-hot tube itself
        val top = (h - textH) / 2f
        fun pass(blurFactor: Float, tone: (Int) -> Int) {
            val p = paintFor(size)
            if (blurFactor > 0f) p.maskFilter = BlurMaskFilter(size * blurFactor, BlurMaskFilter.Blur.NORMAL)
            val l = layoutFor(coloured(text) { tone(letter(it)) }, p, maxW)
            c.save()
            c.translate(PAD.toFloat(), top)
            l.draw(c)
            c.restore()
        }
        pass(0.55f) { ColorMath.withAlphaF(it, 0.55f) }
        pass(0.26f) { ColorMath.withAlphaF(it, 0.85f) }
        pass(0.09f) { it }
        pass(0f) { ColorMath.blend(it, ColorMath.PAPER, 0.62f) }
        return bmp
    }

    private fun drawBackdrop(c: Canvas, w: Int, h: Int, tint: Int, rainbow: Boolean) {
        val wf = w.toFloat()
        val hf = h.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, hf, 0xFF07070D.toInt(), 0xFF140F22.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, wf, hf, p)
        // a soft pool of the sign's own colour behind the letters, like neon light on a wall
        p.shader = RadialGradient(
            wf / 2f, hf / 2f, wf * 0.66f,
            intArrayOf(ColorMath.withAlphaF(tint, 0.26f), ColorMath.withAlphaF(tint, 0.09f), 0),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, wf, hf, p)

        // the tube frame around the card
        val frame = RectF(34f, 34f, wf - 34f, hf - 34f)
        fun tube(strokeW: Float, blur: Float, alpha: Float, hot: Boolean) {
            val fp = Paint(Paint.ANTI_ALIAS_FLAG)
            fp.style = Paint.Style.STROKE
            fp.strokeWidth = strokeW
            if (blur > 0f) fp.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            if (rainbow) {
                val cs = IntArray(8) { ColorMath.withAlphaF(ColorMath.rainbow(it * 8 + 1), alpha) }
                cs[7] = cs[0]
                fp.shader = SweepGradient(wf / 2f, hf / 2f, cs, null)
            } else {
                fp.color = ColorMath.withAlphaF(if (hot) ColorMath.blend(tint, ColorMath.PAPER, 0.55f) else tint, alpha)
            }
            c.drawRoundRect(frame, 58f, 58f, fp)
        }
        tube(14f, 26f, 0.75f, false)
        tube(5f, 0f, 0.95f, true)
    }

    /** Draws the sign and saves it; old pictures are cleaned up. @return the PNG file */
    fun renderToFile(ctx: Context, text: String, color: Int, rainbow: Boolean): File {
        val d = dir(ctx)
        val f = File(d, "neon_${System.currentTimeMillis()}.png")
        val bmp = render(text, color, rainbow)
        try {
            FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        } finally {
            bmp.recycle()
        }
        d.listFiles { x -> x.isFile && x.name.endsWith(".png") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_FILES)
            ?.forEach { it.delete() }
        return f
    }
}

/**
 * The plain version of the coloured picture: the text exactly as it was typed (same colours, same highlighter), big and bold, on a
 * card that keeps it readable whatever the chat background is. Light colours get a dark card, dark colours a white one, the
 * highlighter pen turns the card itself into the marker colour.
 */
object ColorPicRenderer {

    private const val MAX_W = 1080
    private const val MIN_W = 380
    private const val PAD = 72
    private const val MAX_CHARS = 600
    private const val DEFAULT_INK = 0xFF2979FF.toInt()
    private const val DARK_CARD = 0xFF16161F.toInt()
    private const val LIGHT_CARD = 0xFFFFFFFF.toInt()

    private fun paintFor(size: Float): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun layoutFor(text: CharSequence, p: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.18f)
            .setIncludePad(false)
            .build()

    /** every letter gets the colour [colorAt] gives it (counting letters, not spaces) */
    private fun coloured(text: String, colorAt: (Int) -> Int): SpannableString {
        val sp = SpannableString(text)
        var i = 0
        var n = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            if (!Character.isWhitespace(cp)) {
                sp.setSpan(ForegroundColorSpan(colorAt(n)), i, i + len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                n++
            }
            i += len
        }
        return sp
    }

    /** the card colour for a text colour (pure: also used by the tests) */
    fun cardFor(ink: Int, rainbow: Boolean, marker: Boolean): Int = when {
        marker -> ColorMath.opaque(ink)
        rainbow || ColorMath.luminance(ink) >= 0.22f -> DARK_CARD
        else -> LIGHT_CARD
    }

    fun render(raw: String, color: Int, rainbow: Boolean, highlight: Boolean): Bitmap {
        val text = raw.trim().let { if (it.length > MAX_CHARS) it.take(MAX_CHARS - 1) + "…" else it }
        val ink = if (color == 0) DEFAULT_INK else ColorMath.opaque(color)
        val marker = highlight && !rainbow && color != 0
        val card = cardFor(ink, rainbow, marker)
        val letter: (Int) -> Int = when {
            marker -> { _ -> ColorMath.onColor(ink) }
            rainbow -> { n -> ColorMath.rainbow(n) }
            else -> { _ -> ink }
        }
        val styled = coloured(text, letter)

        // the biggest letters that still fit: a short sentence is large, a long one shrinks to a few lines
        val maxText = MAX_W - 2 * PAD
        var size = 150f
        var probe = layoutFor(styled, paintFor(size), maxText)
        while ((probe.lineCount > 9 || probe.height > 1600) && size > 40f) {
            size -= 6f
            probe = layoutFor(styled, paintFor(size), maxText)
        }
        var textW = 0
        for (i in 0 until probe.lineCount) textW = maxOf(textW, Math.ceil(probe.getLineWidth(i).toDouble()).toInt())
        val w = (textW + 2 * PAD).coerceIn(MIN_W, MAX_W)
        val inner = w - 2 * PAD
        val layout = layoutFor(styled, paintFor(size), inner)
        val h = layout.height + 2 * PAD
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = card }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 64f, 64f, bg)
        c.save()
        c.translate(PAD.toFloat(), PAD.toFloat())
        layout.draw(c)
        c.restore()
        return bmp
    }

    /** Draws the picture and saves it next to the neon ones (old pictures are cleaned up). @return the PNG file */
    fun renderToFile(ctx: Context, text: String, color: Int, rainbow: Boolean, highlight: Boolean): File {
        val d = NeonRenderer.dir(ctx)
        val f = File(d, "pic_${System.currentTimeMillis()}.png")
        val bmp = render(text, color, rainbow, highlight)
        try {
            FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        } finally {
            bmp.recycle()
        }
        d.listFiles { x -> x.isFile && x.name.endsWith(".png") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(12)
            ?.forEach { it.delete() }
        return f
    }
}

/**
 * The "sticker" version for chats that take stickers (WhatsApp): the words in their colour on a transparent background, no card,
 * so in the conversation they stand there like coloured writing instead of a picture. A thin outline in a contrasting colour keeps
 * them readable on both a light and a dark chat background. 512 x 512 WebP with transparency, which is what WhatsApp asks of a
 * sticker (and well under its 100 KB limit).
 */
object StickerRenderer {

    /** WhatsApp's own sticker type: a box that lists it takes the picture as a sticker */
    const val MIME = "image/webp.wasticker"

    const val SIDE = 512
    private const val PAD = 26
    private const val MAX_CHARS = 220
    private const val LIMIT_BYTES = 100 * 1024

    /** the outline colour that stands out from the letters: dark round light ink, white round dark ink */
    fun outlineFor(ink: Int): Int = if (ColorMath.luminance(ink) >= 0.45f) 0xFF15151D.toInt() else 0xFFFFFFFF.toInt()

    private fun paintFor(size: Float): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun layoutFor(text: CharSequence, p: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.12f)
            .setIncludePad(false)
            .build()

    fun render(raw: String, color: Int, rainbow: Boolean, highlight: Boolean): Bitmap {
        val text = raw.trim().let { if (it.length > MAX_CHARS) it.take(MAX_CHARS - 1) + "…" else it }
        val ink = if (color == 0) 0xFF2979FF.toInt() else ColorMath.opaque(color)
        val marker = highlight && !rainbow && color != 0
        val letter: (Int) -> Int = when {
            marker -> { _ -> ColorMath.onColor(ink) }
            rainbow -> { n -> ColorMath.rainbow(n) }
            else -> { _ -> ink }
        }
        val styled = SpannableString(text)
        var i = 0
        var n = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            if (!Character.isWhitespace(cp)) {
                styled.setSpan(ForegroundColorSpan(letter(n)), i, i + len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                n++
            }
            i += len
        }

        // the biggest letters that still fit the square
        val inner = SIDE - 2 * PAD - if (marker) 40 else 0
        var size = 210f
        var layout = layoutFor(styled, paintFor(size), inner)
        while (layout.height > inner && size > 24f) {
            size -= 6f
            layout = layoutFor(styled, paintFor(size), inner)
        }
        var textW = 0
        for (l in 0 until layout.lineCount) textW = maxOf(textW, Math.ceil(layout.getLineWidth(l).toDouble()).toInt())

        val bmp = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)      // starts fully transparent
        val c = Canvas(bmp)
        val left = (SIDE - inner) / 2f
        val top = (SIDE - layout.height) / 2f
        if (marker) {
            // the highlighter pen: a rounded band of the colour behind the lines
            val bandW = textW + 44f
            val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = ink }
            c.drawRoundRect(RectF((SIDE - bandW) / 2f, top - 18f, (SIDE + bandW) / 2f, top + layout.height + 18f), 30f, 30f, band)
        } else {
            // outline first (the same lines, drawn as a thick stroke in the contrasting colour), the letters on top
            val plain = layoutFor(text, paintFor(size).apply {
                style = Paint.Style.STROKE
                strokeWidth = (size * 0.13f).coerceIn(5f, 16f)
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                this.color = outlineFor(if (rainbow) 0xFF808080.toInt() else ink)
            }, inner)
            c.save()
            c.translate(left, top)
            plain.draw(c)
            c.restore()
        }
        c.save()
        c.translate(left, top)
        layout.draw(c)
        c.restore()
        return bmp
    }

    /** Writes the WebP (lossless where the phone can, smaller and lossy when it must be). @return false when it stays above the limit */
    @Suppress("DEPRECATION")
    private fun save(bmp: Bitmap, f: File): Boolean {
        val tries: List<Pair<Bitmap.CompressFormat, Int>> =
            if (Build.VERSION.SDK_INT >= 30) listOf(
                Bitmap.CompressFormat.WEBP_LOSSLESS to 100, Bitmap.CompressFormat.WEBP_LOSSY to 90, Bitmap.CompressFormat.WEBP_LOSSY to 70
            ) else listOf(
                Bitmap.CompressFormat.WEBP to 92, Bitmap.CompressFormat.WEBP to 75, Bitmap.CompressFormat.WEBP to 55
            )
        for ((fmt, q) in tries) {
            FileOutputStream(f).use { out -> bmp.compress(fmt, q, out) }
            if (f.length() in 1..LIMIT_BYTES.toLong()) return true
        }
        return false
    }

    /** Draws the sticker and saves it next to the other pictures (old ones are cleaned up). @return the WebP file */
    fun renderToFile(ctx: Context, text: String, color: Int, rainbow: Boolean, highlight: Boolean): File {
        val d = NeonRenderer.dir(ctx)
        val f = File(d, "stk_${System.currentTimeMillis()}.webp")
        val bmp = render(text, color, rainbow, highlight)
        try {
            if (!save(bmp, f)) throw java.io.IOException("the sticker is bigger than ${LIMIT_BYTES / 1024} KB")
        } finally {
            bmp.recycle()
        }
        d.listFiles { x -> x.isFile && x.name.endsWith(".webp") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(12)
            ?.forEach { it.delete() }
        return f
    }
}

/**
 * Hands the neon pictures to other apps. The app that receives the picture gets a temporary read grant for that one file
 * (the provider is not exported and shares nothing else).
 */
class NeonProvider : ContentProvider() {

    companion object {
        fun authority(ctx: Context): String = ctx.packageName + ".neon"

        fun uriFor(ctx: Context, f: File): Uri =
            Uri.Builder().scheme("content").authority(authority(ctx)).appendPath(f.name).build()
    }

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (!Regex("[A-Za-z0-9_.-]+\\.(png|webp)").matches(name)) return null
        val f = File(NeonRenderer.dir(ctx), name)
        return if (f.isFile) f else null
    }

    override fun getType(uri: Uri): String? = if (uri.lastPathSegment?.endsWith(".webp") == true) "image/webp" else "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val row = arrayOfNulls<Any>(cols.size)
        for ((i, col) in cols.withIndex()) {
            row[i] = when (col) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
