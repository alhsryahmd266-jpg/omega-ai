package com.omega.keyboard.ai

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns
import com.omega.keyboard.AppLog
import com.omega.keyboard.Prefs
import java.io.File
import java.io.FileOutputStream

/**
 * Brings the model file (.litertlm) into the app: either copies it from wherever the user picked it, or points the app at a
 * file that is already on the phone (Downloads) when "all files access" is allowed. The copy runs on its own thread and
 * survives the screen being closed; the screen just polls [state].
 */
object ModelImporter {

    class State(val running: Boolean, val copied: Long, val total: Long, val error: String?, val done: Boolean, val name: String = "")

    @Volatile var state = State(false, 0, 0, null, false)
        private set

    @Volatile private var cancelFlag = false

    private class Cancelled : Exception()

    fun modelsDir(ctx: Context): File = File(ctx.filesDir, "models").also { it.mkdirs() }

    fun mb(bytes: Long): String = if (bytes >= 1L shl 30) String.format("%.2f GB", bytes / 1073741824.0) else "${bytes / (1024 * 1024)} MB"

    private fun queryName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Throwable) {
        null
    }

    private fun querySize(ctx: Context, uri: Uri): Long = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } ?: -1L
    } catch (_: Throwable) {
        -1L
    }

    /** @return an error message, or null when the copy has started */
    fun start(ctx: Context, uri: Uri): String? {
        if (state.running) return "فيه نسخ شغال دلوقتي"
        val app = ctx.applicationContext
        val name = (queryName(app, uri) ?: "model.litertlm").replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "model.litertlm" }
        val total = querySize(app, uri)
        val dir = modelsDir(app)
        val free = try { StatFs(dir.path).availableBytes } catch (_: Throwable) { Long.MAX_VALUE }
        if (total in 1 until ModelStore.MIN_BYTES) return "الملف ده صغير أوي (${mb(total)}) ومش شكله ملف نموذج"
        // the models that are already here stay (the user switches between them): the new one needs room of its own
        if (total > 0 && free < total + 64L * 1024 * 1024) {
            return "المساحة مش كفاية. محتاج ${mb(total)} فاضيين، والمتاح ${mb(free)}. احذف نموذج من قايمة النماذج، أو امسح حاجات من التليفون، أو استخدم الملف من التنزيلات بدون نسخ."
        }
        cancelFlag = false
        state = State(true, 0, total, null, false, name)
        val t = Thread({ copy(app, uri, name, total) }, "omega-model-copy")
        t.isDaemon = true
        t.start()
        return null
    }

    fun cancel() { cancelFlag = true }

    private fun copy(app: Context, uri: Uri, name: String, total: Long) {
        val dir = modelsDir(app)
        val dest = File(dir, name)
        val part = File(dir, "$name.part")
        try {
            val input = app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("مقدرتش أفتح الملف")
            var done = 0L
            input.use { ins ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(1 shl 20)
                    var lastPublish = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (cancelFlag) throw Cancelled()
                        val now = System.nanoTime()
                        if (now - lastPublish > 150_000_000L) {
                            lastPublish = now
                            state = State(true, done, total, null, false, name)
                        }
                    }
                    out.flush()
                    try { out.fd.sync() } catch (_: Throwable) {}
                }
            }
            if (done < ModelStore.MIN_BYTES) throw java.io.IOException("الملف صغير أوي (${mb(done)}). اختار ملف النموذج الصح.")
            if (total > 0 && done != total) throw java.io.IOException("النسخ ما اكتملش (${mb(done)} من ${mb(total)})")
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw java.io.IOException("مقدرتش أنقل الملف لمكانه النهائي")
            Prefs.get(app).modelPath = dest.absolutePath
            Llm.unload()      // the next use loads the new file
            AppLog.d("model", "imported ${dest.name} (${mb(done)})")
            state = State(false, done, done, null, true, name)
        } catch (c: Cancelled) {
            part.delete()
            state = State(false, 0, total, "اتلغى النسخ", false, name)
        } catch (t: Throwable) {
            AppLog.e("model", "import failed", t)
            part.delete()
            state = State(false, 0, total, t.message ?: t.javaClass.simpleName, false, name)
        }
    }

    // ------------------------------------------------------------------ use a file where it already is

    /** .litertlm files directly inside the Downloads folder (needs all-files access on Android 11+, or storage permission before). */
    fun findInDownloads(): List<File> = try {
        @Suppress("DEPRECATION")
        val d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        d.listFiles { f -> f.isFile && f.name.endsWith(".litertlm", ignoreCase = true) && f.length() > ModelStore.MIN_BYTES }?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /** Uses [f] where it is, without copying. */
    fun useInPlace(ctx: Context, f: File): String? {
        if (!f.isFile || !f.canRead()) return "مقدرتش أقرا الملف ده"
        if (f.length() < ModelStore.MIN_BYTES) return "الملف صغير أوي ومش شكله نموذج"
        val app = ctx.applicationContext
        Prefs.get(app).modelPath = f.absolutePath
        Thread({ try { Llm.unload() } catch (_: Throwable) {} }, "omega-unload").also { it.isDaemon = true }.start()
        return null
    }
}
