package com.omega.sight.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.omega.sight.core.BrainSpec
import com.omega.sight.core.YoloDecoder
import com.omega.sight.ml.BrainModel
import com.omega.sight.ml.TfliteRunner
import com.omega.sight.ml.VisionModel
import java.io.File

/**
 * Where the owner's model files live. He picks them from inside his phone (the system file picker); they are copied into the app's own
 * private folder and nothing else in the app reads any other file. The app has no internet permission and sends nothing anywhere.
 */
object ModelStore {
    enum class Slot(val file: String) { VISION("vision.tflite"), BRAIN("brain.tflite"), NAMES("vision.names.txt") }

    class Report(val ok: Boolean, val lines: List<String>)

    fun dir(ctx: Context): File = File(ctx.filesDir, "models").also { it.mkdirs() }
    fun file(ctx: Context, slot: Slot): File = File(dir(ctx), slot.file)
    fun has(ctx: Context, slot: Slot): Boolean = file(ctx, slot).let { it.isFile && it.length() > 0 }

    fun sizeText(ctx: Context, slot: Slot): String {
        val n = file(ctx, slot).length()
        return if (n >= 1_000_000) String.format(java.util.Locale.US, "%.1f MB", n / 1e6) else String.format(java.util.Locale.US, "%d KB", n / 1000)
    }

    fun remove(ctx: Context, slot: Slot) {
        file(ctx, slot).delete()
    }

    /** the name of the picked file, for the screen */
    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: ""
            }
        } catch (_: Throwable) {
        }
        return uri.lastPathSegment ?: ""
    }

    /** copies the picked file into place; "" = done, else the reason (Arabic) */
    fun import(ctx: Context, uri: Uri, slot: Slot): String {
        val dst = file(ctx, slot)
        val tmp = File(dst.parentFile, slot.file + ".tmp")
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return "الملف مش بيتفتح"
            var total = 0L
            input.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) {
                            tmp.delete()
                            return "الملف كبير جدًا (أكتر من 300 MB)"
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            if (total == 0L) {
                tmp.delete()
                return "الملف فاضي"
            }
            if (slot != Slot.NAMES && !looksLikeTflite(tmp)) {
                tmp.delete()
                return "ده مش ملف TensorFlow Lite (.tflite)"
            }
            if (slot == Slot.NAMES && total > 262144) {
                tmp.delete()
                return "ملف الأسماء كبير على غير العادة — المطلوب ملف نصي صغير"
            }
            dst.delete()
            if (!tmp.renameTo(dst)) {
                tmp.delete()
                return "ماقدرتش أحفظ الملف"
            }
            return ""
        } catch (t: Throwable) {
            tmp.delete()
            return "فشل النسخ: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    /** a TensorFlow Lite file has the identifier "TFL3" at bytes 4..7 */
    private fun looksLikeTflite(f: File): Boolean {
        val h = ByteArray(8)
        f.inputStream().use { if (it.read(h) < 8) return false }
        return h[4] == 'T'.code.toByte() && h[5] == 'F'.code.toByte() && h[6] == 'L'.code.toByte() && h[7] == '3'.code.toByte()
    }

    /** the class names next to the vision file; empty = none given */
    fun names(ctx: Context): List<String> {
        val f = file(ctx, Slot.NAMES)
        if (!f.isFile) return emptyList()
        return try {
            parseNames(f.readText(Charsets.UTF_8))
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** one name per line; "0: bot" and "- bot" and the Ultralytics line "names: ['bot', 'head']" are understood too */
    fun parseNames(text: String): List<String> {
        val t = text.replace("\r", "")
        val br = Regex("names\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL).find(t)
        if (br != null) return br.groupValues[1].split(',').map { it.trim().trim('\'', '"') }.filter { it.isNotEmpty() }
        val numbered = Regex("^\\d+\\s*[:=]\\s*(.+)$")
        val out = ArrayList<String>()
        for (raw in t.split('\n')) {
            var l = raw.trim()
            if (l.isEmpty() || l.startsWith("#") || l.lowercase().startsWith("names:")) continue
            if (l.startsWith("- ")) l = l.substring(2).trim()
            val m = numbered.find(l)
            if (m != null) l = m.groupValues[1].trim()
            l = l.trim('\'', '"').trim()
            if (l.isNotEmpty()) out.add(l)
        }
        return out
    }

    // ---- self tests (run them on a background thread: they open the model, run it and time it) ----

    fun testVision(ctx: Context, tryGpu: Boolean): Report {
        val lines = ArrayList<String>()
        val f = file(ctx, Slot.VISION)
        if (!f.isFile) return Report(false, listOf("مفيش ملف رؤية متحمّل"))
        val lib = TfliteRunner.libraryName() ?: return Report(false, listOf("مكتبة LiteRT مش موجودة في النسخة دي"))
        lines.add("المكتبة: ${lib.substringAfterLast('.')}")
        val names = names(ctx)
        val cpu = VisionModel.open(f, names, 4, false)
        val m = cpu.model ?: return Report(false, lines + cpu.error)
        val rgba = grey(1280, 576)
        var ok = true
        try {
            val s = m.spec
            lines.add("الدخل: ${s.inW}×${s.inH} ${if (s.nchw) "NCHW" else "NHWC"} (${m.runner.inputs[0].type})")
            lines.add("الخرج: ${s.outShape.joinToString("×")} (${m.runner.outputs[0].type}) ← ${YoloDecoder.layoutOf(s.outShape)}")
            val n0 = m.detect(rgba, 1280, 576, 1280 * 4, 0, 0, 1280, 576, 0.35f).size
            if (!m.runner.lastError.isEmpty()) {
                ok = false
                lines.add("فشل التشغيل: ${m.runner.lastError}")
            } else {
                var t = 0f
                for (i in 0 until 3) {
                    m.detect(rgba, 1280, 576, 1280 * 4, 0, 0, 1280, 576, 0.35f)
                    t += m.lastMs
                }
                lines.add("تشغيل على المعالج: ${fmt(t / 3f)} ms للصورة (على صورة رمادية فاضية لقى $n0 — طبيعي)")
            }
        } finally {
            m.close()
        }
        if (ok && tryGpu) {
            val g = VisionModel.open(f, names, 2, true)
            val gm = g.model
            if (gm == null) {
                lines.add("كرت الشاشة: ${g.error}")
            } else {
                try {
                    if (!gm.usingGpu) {
                        lines.add("كرت الشاشة مش متاح على الجهاز ده — هيشتغل على المعالج")
                    } else {
                        gm.detect(rgba, 1280, 576, 1280 * 4, 0, 0, 1280, 576, 0.35f)
                        if (gm.runner.lastError.isNotEmpty()) {
                            lines.add("كرت الشاشة فشل: ${gm.runner.lastError} — هيشتغل على المعالج")
                        } else {
                            var t = 0f
                            for (i in 0 until 3) {
                                gm.detect(rgba, 1280, 576, 1280 * 4, 0, 0, 1280, 576, 0.35f)
                                t += gm.lastMs
                            }
                            lines.add("تشغيل على كرت الشاشة: ${fmt(t / 3f)} ms للصورة")
                        }
                    }
                } finally {
                    gm.close()
                }
            }
        }
        if (names.isEmpty()) lines.add("مفيش ملف أسماء: الفئة 0 = جسم، والفئة 1 (لو موجودة) = رأس")
        else lines.add("أسماء الفئات (${names.size}): ${names.take(8).joinToString("، ")}")
        return Report(ok, lines)
    }

    fun testBrain(ctx: Context, tryGpu: Boolean): Report {
        val f = file(ctx, Slot.BRAIN)
        if (!f.isFile) return Report(false, listOf("مفيش ملف عقل متحمّل"))
        val lines = ArrayList<String>()
        val o = BrainModel.open(f, 2, tryGpu)
        val b = o.model ?: return Report(false, listOf(o.error))
        try {
            lines.add("الدخل: ${b.runner.inputs[0].describe()}  الخرج: ${b.runner.outputs[0].describe()}")
            val x = FloatArray(BrainSpec.IN)
            x[0] = 1f
            x[1] = 0.1f
            x[2] = -0.05f
            x[3] = 0.1f
            x[4] = 0.25f
            x[BrainSpec.K * BrainSpec.PER + 7] = 1f
            val y = b.infer(x)
            if (y == null) {
                lines.add("فشل التشغيل: ${b.runner.lastError}")
                return Report(false, lines)
            }
            lines.add("جرّبته على حالة فيها هدف واحد: الخرج = ${y.joinToString("، ") { fmt(it) }}")
            lines.add("السرعة: ${fmt(b.lastMs)} ms")
            if (y.any { it.isNaN() || it.isInfinite() }) {
                lines.add("الخرج فيه قيم مش رقمية!")
                return Report(false, lines)
            }
        } finally {
            b.close()
        }
        return Report(true, lines)
    }

    private fun grey(w: Int, h: Int): ByteArray {
        val a = ByteArray(w * h * 4)
        var i = 0
        while (i < a.size) {
            a[i] = 114
            a[i + 1] = 114
            a[i + 2] = 114
            a[i + 3] = -1
            i += 4
        }
        return a
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)

    private const val MAX_BYTES = 300L * 1024 * 1024
}
