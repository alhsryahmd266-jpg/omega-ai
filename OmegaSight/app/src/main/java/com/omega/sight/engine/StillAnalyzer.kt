package com.omega.sight.engine

import com.omega.sight.Bidi
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import com.omega.sight.Prefs
import com.omega.sight.core.Det
import com.omega.sight.core.GlowDetector
import com.omega.sight.core.Kind
import com.omega.sight.ml.TfliteRunner
import com.omega.sight.ml.VisionModel
import com.omega.sight.model.ModelStore
import kotlin.math.max
import kotlin.math.min

/**
 * Tries the detectors on ONE picture the owner picks from his phone (a screenshot of his game): the red glow by colour and the vision file.
 * This is how he checks the vision file against his game without playing. The picture is read, analysed and drawn on in memory; nothing
 * is saved or sent.
 */
object StillAnalyzer {
    class Result(val bitmap: Bitmap, val lines: List<String>, val dets: List<Det>)

    /** run this on a background thread */
    fun run(ctx: Context, uri: Uri): Result {
        val lines = ArrayList<String>()
        val bmp = decode(ctx, uri) ?: return Result(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888), listOf("الصورة ماتفتحتش"), emptyList())
        val w = bmp.width
        val h = bmp.height
        lines.add("الصورة: ${Bidi.dim(w, h)}")
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val rgba = ByteArray(w * h * 4)
        for (i in px.indices) {
            val v = px[i]
            rgba[i * 4] = (v shr 16).toByte()
            rgba[i * 4 + 1] = (v shr 8).toByte()
            rgba[i * 4 + 2] = v.toByte()
            rgba[i * 4 + 3] = -1
        }
        val prefs = Prefs(ctx)
        val t = prefs.tuning
        val dets = ArrayList<Det>()

        if (t.glow) {
            val t0 = System.nanoTime()
            val g = GlowDetector(w, h).detect(rgba, w * 4, null, t.glowSens, 2)
            dets.addAll(g)
            lines.add("الوهج الأحمر: ${g.size} (${Bidi.ltr("${fmt((System.nanoTime() - t0) / 1e6f)} ms")})")
        } else {
            lines.add("الوهج الأحمر: مقفول من الإعدادات")
        }

        if (!ModelStore.has(ctx, ModelStore.Slot.VISION)) {
            lines.add("ملف الرؤية: مفيش — حمّله من «ملفاتك» الأول")
        } else if (TfliteRunner.libraryName() == null) {
            lines.add("ملف الرؤية: مكتبة LiteRT مش موجودة في النسخة دي")
        } else {
            val o = VisionModel.open(ModelStore.file(ctx, ModelStore.Slot.VISION), ModelStore.names(ctx), 4, prefs.gpu)
            val m = o.model
            if (m == null) {
                lines.add("ملف الرؤية: ${o.error}")
            } else {
                try {
                    var found = m.detect(rgba, w, h, w * 4, 0, 0, w, h, t.modelScore)
                    // a second look at full size around the middle: small, far things
                    val extra = if (t.farMode && (w > m.spec.inW || h > m.spec.inH)) {
                        val cw = min(w, m.spec.inW)
                        val ch = min(h, m.spec.inH)
                        m.detect(rgba, w, h, w * 4, (w - cw) / 2, (h - ch) / 2, cw, ch, t.modelScore)
                    } else emptyList()
                    val merged = ArrayList<Det>(found)
                    for (d in extra) if (merged.none { it.kind == d.kind && it.box.iou(d.box) > 0.4f }) merged.add(d)
                    found = merged
                    dets.addAll(found)
                    val bodies = found.count { it.kind == Kind.BODY }
                    val heads = found.count { it.kind == Kind.HEAD }
                    lines.add("ملف الرؤية: $bodies جسم، $heads رأس (حد الثقة ${fmt(t.modelScore)}، ${fmt(m.lastMs)} ms${if (m.usingGpu) "، كرت الشاشة" else ""})")
                    if (m.runner.lastError.isNotEmpty()) lines.add("تحذير: ${m.runner.lastError}")
                } finally {
                    m.close()
                }
            }
        }
        return Result(draw(bmp, dets), lines, dets)
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)

    /** the picture, at most 2200 pixels on the long side */
    private fun decode(ctx: Context, uri: Uri): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (max(o.outWidth, o.outHeight) / sample > 2200) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            o2.inPreferredConfig = Bitmap.Config.ARGB_8888
            val b = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
            if (b.config == Bitmap.Config.ARGB_8888 && b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true)
        } catch (_: Throwable) {
            null
        }
    }

    private fun draw(src: Bitmap, dets: List<Det>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val unit = max(2f, out.width / 640f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeWidth = unit
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        tp.textSize = 5f * unit
        tp.typeface = Typeface.DEFAULT_BOLD
        for (d in dets) {
            val col = when (d.kind) {
                Kind.BODY -> 0xFF3DE0FF.toInt()
                Kind.HEAD -> 0xFFFF4DD8.toInt()
                Kind.GLOW -> 0xFFFFD84D.toInt()
                Kind.MOTION -> 0xFF7CFF8A.toInt()
            }
            p.color = col
            val b = d.box
            if (d.kind == Kind.GLOW) {
                val r = max(b.w, b.h) / 2f + 3f * unit
                c.drawCircle(b.cx, b.cy, r, p)
            } else {
                c.drawRect(b.l, b.t, b.r, b.b, p)
            }
            tp.color = col
            val label = "${d.kind.name} ${fmt(d.score)}"
            c.drawText(label, b.l, max(5f * unit, b.t - 1.5f * unit), tp)
        }
        tp.color = Color.WHITE
        return out
    }
}
