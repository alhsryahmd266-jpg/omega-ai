package com.omega.keyboard.ai

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import com.omega.keyboard.AppLog
import com.omega.keyboard.Prefs
import java.io.File

/**
 * Downloads the model file from Hugging Face with the phone's own download manager. That way it keeps going with the screen off
 * or the app closed, picks up again after the network drops, and shows its own progress notification. The file lands in the
 * app's private "Download" folder (no storage permission needed) and becomes the active model as soon as it is complete.
 */
object ModelDownloader {

    const val REPO_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
    const val FILE_NAME = "gemma-4-E2B-it.litertlm"
    const val FILE_URL = "$REPO_URL/resolve/main/$FILE_NAME"

    /** the file is about 2.59 GB; this is only used to check the free space before the real size is known */
    private const val APPROX_BYTES = 2_600_000_000L
    private const val SPARE_BYTES = 200L * 1024 * 1024

    enum class Kind { NONE, WAITING, RUNNING, PAUSED, FAILED, DONE }

    class Status(val kind: Kind, val done: Long = 0L, val total: Long = 0L, val message: String = "") {
        val active: Boolean get() = kind == Kind.WAITING || kind == Kind.RUNNING || kind == Kind.PAUSED
        val percent: Int get() = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
    }

    private fun manager(ctx: Context): DownloadManager? =
        try { ctx.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager } catch (_: Throwable) { null }

    /** the app's own Download folder on shared storage; null when the storage is not available right now */
    fun dir(ctx: Context): File? =
        try { ctx.applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) } catch (_: Throwable) { null }

    private fun freeBytes(dir: File): Long = try { StatFs(dir.path).availableBytes } catch (_: Throwable) { Long.MAX_VALUE }

    /** a file name that does not clash with the model that is in use right now */
    private fun pickName(prefs: Prefs, dir: File): String {
        val names = listOf(FILE_NAME, "gemma-4-E2B-it-2.litertlm")
        for (n in names) if (!File(dir, n).exists()) return n
        // both are taken: the one that is not the active model is an old leftover
        val victim = names.firstOrNull { File(dir, it).absolutePath != prefs.modelPath } ?: names[1]
        File(dir, victim).delete()
        return victim
    }

    /** @return an error text, or null when the download has been queued */
    fun start(ctx: Context, wifiOnly: Boolean): String? {
        val app = ctx.applicationContext
        val prefs = Prefs.get(app)
        val m = manager(app) ?: return "مدير التنزيلات مش شغال على التليفون ده. افتح الصفحة ونزّل الملف من المتصفح."
        val cur = status(app)
        if (cur.active) return "فيه تنزيل شغال دلوقتي"
        if (cur.kind == Kind.DONE) {
            adoptIfDone(app)
            return null
        }
        val dir = dir(app) ?: return "مساحة التخزين مش متاحة دلوقتي"
        if (freeBytes(dir) < APPROX_BYTES + SPARE_BYTES) {
            return "المساحة مش كفاية. محتاج حوالي ٣ جيجا فاضيين عشان النموذج يتنزّل. امسح حاجات من التليفون وجرّب تاني."
        }
        // a failed attempt is cleaned up first (this also deletes its half-written file)
        if (prefs.dlId >= 0) {
            try { m.remove(prefs.dlId) } catch (_: Throwable) {}
            prefs.dlId = -1L
        }
        val name = pickName(prefs, dir)
        return try {
            val req = DownloadManager.Request(Uri.parse(FILE_URL))
                .setTitle("نموذج Omega (Gemma)")
                .setDescription("تنزيل نموذج الذكاء الاصطناعي للكيبورد")
                .setMimeType("application/octet-stream")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, name)
            if (wifiOnly) req.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            val id = m.enqueue(req)
            prefs.dlId = id
            prefs.dlName = name
            AppLog.d("model", "download queued id=$id file=$name wifiOnly=$wifiOnly")
            null
        } catch (t: Throwable) {
            AppLog.e("model", "download could not start", t)
            "مقدرتش أبدأ التنزيل (${t.message ?: t.javaClass.simpleName}). افتح الصفحة ونزّل الملف من المتصفح."
        }
    }

    fun cancel(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = Prefs.get(app)
        val id = prefs.dlId
        if (id >= 0) {
            try { manager(app)?.remove(id) } catch (_: Throwable) {}
        }
        prefs.dlId = -1L
        val dir = dir(app)
        if (dir != null && prefs.dlName.isNotBlank()) {
            val f = File(dir, prefs.dlName)
            if (f.exists() && f.absolutePath != prefs.modelPath) f.delete()
        }
        AppLog.d("model", "download cancelled")
    }

    fun status(ctx: Context): Status {
        val app = ctx.applicationContext
        val prefs = Prefs.get(app)
        val id = prefs.dlId
        if (id < 0) return Status(Kind.NONE)
        val m = manager(app) ?: return Status(Kind.NONE)
        val cursor = try { m.query(DownloadManager.Query().setFilterById(id)) } catch (_: Throwable) { null }
        if (cursor == null) return Status(Kind.NONE)
        var gone = false
        val result = cursor.use { c ->
            if (!c.moveToFirst()) {
                gone = true
                Status(Kind.NONE)
            } else {
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0L)
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(0L)
                when (st) {
                    DownloadManager.STATUS_PENDING -> Status(Kind.WAITING, done, total)
                    DownloadManager.STATUS_RUNNING -> Status(Kind.RUNNING, done, total)
                    DownloadManager.STATUS_PAUSED -> Status(Kind.PAUSED, done, total, pauseText(reason))
                    DownloadManager.STATUS_FAILED -> Status(Kind.FAILED, done, total, failText(reason))
                    DownloadManager.STATUS_SUCCESSFUL -> checkFinished(app, prefs, done, total)
                    else -> Status(Kind.WAITING, done, total)
                }
            }
        }
        if (gone) prefs.dlId = -1L      // the user removed it from the downloads app
        return result
    }

    /** the manager says "done": make sure the file is really there and complete */
    private fun checkFinished(app: Context, prefs: Prefs, done: Long, total: Long): Status {
        val dir = dir(app) ?: return Status(Kind.FAILED, done, total, "مساحة التخزين مش متاحة")
        val f = File(dir, prefs.dlName)
        return when {
            !f.isFile -> Status(Kind.FAILED, done, total, "الملف اتمسح بعد ما نزل. نزّله تاني.")
            f.length() < ModelStore.MIN_BYTES || (total > 0 && f.length() != total) ->
                Status(Kind.FAILED, f.length(), total, "الملف اللي نزل مش كامل. جرّب تنزّله تاني.")
            else -> Status(Kind.DONE, f.length(), f.length())
        }
    }

    /** If the download has finished, makes the file the active model. @return true when the model was switched just now */
    @Synchronized
    fun adoptIfDone(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val prefs = Prefs.get(app)
        if (status(app).kind != Kind.DONE) return false
        val dir = dir(app) ?: return false
        val f = File(dir, prefs.dlName)
        val err = ModelImporter.useInPlace(app, f)
        if (err != null) {
            AppLog.d("model", "downloaded file is not usable: $err")
            return false
        }
        // older copies are not needed any more: earlier downloads in this folder and files that were copied in
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".litertlm", true) && it.absolutePath != f.absolutePath }?.forEach { it.delete() }
        if (!ModelImporter.state.running) ModelImporter.modelsDir(app).listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        prefs.dlId = -1L
        AppLog.d("model", "downloaded model is active: ${f.name}")
        return true
    }

    private fun pauseText(reason: Int): String = when (reason) {
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "مستني واي فاي (اخترت واي فاي بس). وصّل بالواي فاي وهيكمّل لوحده."
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "مستني اتصال بالنت. هيكمّل لوحده أول ما النت يرجع."
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "حصلت مشكلة مؤقتة في الاتصال. هيحاول تاني لوحده."
        else -> "واقف مؤقتًا. هيكمّل لوحده."
    }

    private fun failText(reason: Int): String = when (reason) {
        401, 403 -> "الموقع رفض التنزيل (محتاج موافقة أو تسجيل دخول). افتح صفحة النموذج ووافق على الشروط، أو نزّله من المتصفح واختاره بزر «اختيار ملف النموذج»."
        404 -> "الملف مش موجود على الرابط ده (ممكن يكون اتنقل). افتح صفحة النموذج ونزّله من هناك."
        429, 500, 502, 503, 504 -> "السيرفر مشغول دلوقتي. جرّب كمان شوية."
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "المساحة خلصت أثناء التنزيل. امسح حاجات من التليفون وجرّب تاني."
        DownloadManager.ERROR_FILE_ERROR, DownloadManager.ERROR_DEVICE_NOT_FOUND -> "مقدرتش أكتب الملف على التخزين."
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_UNHANDLED_HTTP_CODE ->
            "الاتصال قطع أو رد السيرفر مش مظبوط. جرّب تاني."
        DownloadManager.ERROR_CANNOT_RESUME -> "مقدرتش أكمّل من حيث وقف. ابدأ التنزيل من جديد."
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "فيه ملف بنفس الاسم. جرّب تاني."
        else -> "التنزيل فشل (كود $reason). جرّب تاني أو نزّله من المتصفح."
    }
}

/** The download manager says a download is finished: switch to the new model even when the app is closed. */
class DownloadDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val app = context.applicationContext
        if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != Prefs.get(app).dlId) return
        val pending = goAsync()
        val t = Thread({
            try {
                ModelDownloader.adoptIfDone(app)
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }, "omega-dl-done")
        t.isDaemon = true
        t.start()
    }
}
