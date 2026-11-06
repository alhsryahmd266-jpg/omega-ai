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
        if (!Regex("[A-Za-z0-9_.-]+\\.png").matches(name)) return null
        val f = File(NeonRenderer.dir(ctx), name)
        return if (f.isFile) f else null
    }

    override fun getType(uri: Uri): String? = "image/png"

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
