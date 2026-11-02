package com.omega.keyboard.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.omega.keyboard.AppLog
import com.omega.keyboard.Bg
import com.omega.keyboard.Prefs
import com.omega.keyboard.ai.Llm
import com.omega.keyboard.ai.ModelImporter
import com.omega.keyboard.ai.ModelStore
import java.io.File

/** Where the local model file is chosen, copied in, tested and tuned. */
class ModelActivity : BaseActivity() {

    private companion object {
        const val REQ_PICK = 51
        const val REQ_STORAGE = 52
    }

    private lateinit var prefs: Prefs
    private lateinit var p: Pal
    private lateinit var holder: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var testText: String? = null
    private var testing = false
    private var progressBar: ProgressBar? = null
    private var progressLabel: TextView? = null
    private var lastRunning = false

    private val ticker = object : Runnable {
        override fun run() {
            val s = ModelImporter.state
            if (s.running) {
                progressBar?.progress = if (s.total > 0) (s.copied * 1000 / s.total).toInt() else 0
                progressLabel?.text = progressText()
            }
            if (s.running != lastRunning) {          // started or finished: redraw everything
                lastRunning = s.running
                render()
                if (!s.running && s.done) Toast.makeText(this@ModelActivity, "النموذج اتضاف وجاهز", Toast.LENGTH_LONG).show()
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        p = UiKit.pal(this)
        holder = UiKit.column(this)
        setContentView(UiKit.screen(this, p, "النموذج المحلي", holder) { finish() })
        lastRunning = ModelImporter.state.running
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.post(ticker)
        if (ModelImporter.state.running) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun progressText(): String {
        val s = ModelImporter.state
        val pct = if (s.total > 0) (s.copied * 100 / s.total).toInt() else 0
        return if (s.total > 0) "$pct%  —  ${ModelImporter.mb(s.copied)} من ${ModelImporter.mb(s.total)}" else ModelImporter.mb(s.copied)
    }

    private fun render() {
        val sv = holder.parent as? ScrollView
        val y = sv?.scrollY ?: 0
        holder.removeAllViews()
        progressBar = null
        progressLabel = null
        build()
        sv?.post { sv.scrollTo(0, y) }
    }

    private fun build() {
        val h = holder
        val path = ModelStore.path(this)
        val copy = ModelImporter.state

        // ---- status
        val status = UiKit.card(this, p)
        status.addView(UiKit.statusRow(
            this, p, if (path != null) 1 else 0,
            if (path != null) File(path).name else "لسه مفيش نموذج",
            if (path != null) "${ModelStore.sizeMb(this)} MB — ${Llm.statusText(this)}"
            else "الوضع الذاتي والترجمة لباقي اللغات محتاجين ملف النموذج (.litertlm) على التليفون."
        ))
        h.addView(status)

        // ---- copy in progress
        if (copy.running) {
            val c = UiKit.card(this, p)
            c.addView(UiKit.label(this, "بنسخ النموذج جوه التطبيق... سيب الشاشة مفتوحة", 15f, p.text, true))
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = if (copy.total > 0) (copy.copied * 1000 / copy.total).toInt() else 0
                progressTintList = android.content.res.ColorStateList.valueOf(p.accent)
            }
            c.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).also { it.topMargin = dp(12) })
            val lab = UiKit.label(this, progressText(), 13f, p.sub).apply { setPadding(0, dp(6), 0, 0) }
            c.addView(lab)
            c.addView(UiKit.button(this, p, "إلغاء", filled = false) { ModelImporter.cancel() })
            progressBar = pb
            progressLabel = lab
            h.addView(c)
        } else if (copy.error != null) {
            val c = UiKit.card(this, p)
            c.addView(UiKit.statusRow(this, p, -1, "ما اتمّش", copy.error))
            h.addView(c)
        }

        // ---- choose
        h.addView(UiKit.section(this, p, "إضافة النموذج"))
        val choose = UiKit.card(this, p)
        choose.addView(UiKit.label(this, "اختار الملف اللي نزّلته (مثلًا gemma-4-E2B-it.litertlm). بيتنسخ جوه التطبيق (بيحتاج مساحة فاضية بقد حجمه) ومبيتبعتش لأي مكان.", 13.5f, p.sub))
        choose.addView(UiKit.button(this, p, if (path != null) "تغيير ملف النموذج" else "اختيار ملف النموذج") { pick() })
        choose.addView(UiKit.spacer(this, 10f))
        choose.addView(UiKit.label(this, "تليفونك مساحته ضيقة؟ استخدم الملف من مجلد التنزيلات زي ما هو من غير نسخ (بيحتاج إذن الوصول لكل الملفات).", 13.5f, p.sub))
        choose.addView(UiKit.button(this, p, if (hasAllFiles()) "استخدم ملف من التنزيلات" else "السماح بالوصول للملفات", filled = false) { useDownloads() })
        h.addView(choose)

        // ---- test
        h.addView(UiKit.section(this, p, "تجربة"))
        val test = UiKit.card(this, p)
        test.addView(UiKit.label(this, "بيحمّل النموذج ويخليه يرد على جملة بسيطة. أول مرة بتاخد وقت (١٠ ثواني أو أكتر).", 13.5f, p.sub))
        test.addView(UiKit.button(this, p, if (testing) "بجرّب..." else "جرّب النموذج") { if (!testing && path != null) runTest() })
        val tt = testText
        if (tt != null) {
            test.addView(UiKit.label(this, tt, 13.5f, p.text).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = UiKit.shape(this@ModelActivity, p.cardAlt, 12f)
                setTextIsSelectable(true)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10) })
        }
        h.addView(test)

        // ---- tuning
        h.addView(UiKit.section(this, p, "ضبط"))
        val backends = listOf("cpu" to "المعالج (الأكثر ثباتًا)", "gpu" to "كرت الشاشة (أسرع، تجريبي)")
        h.addView(UiKit.group(
            this, p,
            UiKit.valueRow(this, p, "التشغيل على", (backends.firstOrNull { it.first == prefs.backend } ?: backends[0]).second,
                "لو الكرت فشل بيرجع للمعالج لوحده") {
                val labels = backends.map { it.second }.toTypedArray()
                AlertDialog.Builder(this).setTitle("التشغيل على")
                    .setSingleChoiceItems(labels, backends.indexOfFirst { it.first == prefs.backend }.coerceAtLeast(0)) { d, i ->
                        prefs.backend = backends[i].first
                        d.dismiss()
                        Bg.io { Llm.unload() }
                        render()
                    }.setNegativeButton("إلغاء", null).show()
            },
            UiKit.switchRow(this, p, "توفير المساحة", "من غير كاش على التخزين (بيوفّر جيجات بس التحميل أبطأ)", prefs.llmNoCache) {
                prefs.llmNoCache = it
                Bg.io { Llm.unload() }
            },
            UiKit.valueRow(this, p, "تفريغ من الذاكرة الآن", "تفريغ", "بيحرّر رامات التليفون") {
                Bg.io { Llm.unload(); Bg.ui { render() } }
                Toast.makeText(this, "جاري التفريغ", Toast.LENGTH_SHORT).show()
            },
            UiKit.valueRow(this, p, "سجل الأخطاء", "نسخ", "لو النموذج مش بيشتغل ابعت السجل") {
                val text = AppLog.dump(200).ifBlank { "السجل فاضي." }
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("omega-log", text))
                Toast.makeText(this, "اتنسخ السجل", Toast.LENGTH_SHORT).show()
            },
        ))
        if (path != null) {
            h.addView(UiKit.label(this, "المسار: $path", 11.5f, p.sub).apply {
                setPadding(dp(24), dp(12), dp(24), 0)
                textDirection = android.view.View.TEXT_DIRECTION_LTR
                setTextIsSelectable(true)
            })
        }
    }

    // ------------------------------------------------------------------ choosing the file

    private fun pick() {
        if (ModelImporter.state.running) return
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            startActivityForResult(i, REQ_PICK)
        } catch (t: Throwable) {
            Toast.makeText(this, "مقدرتش أفتح اختيار الملفات", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
        val err = ModelImporter.start(this, uri)
        if (err != null) {
            AlertDialog.Builder(this).setTitle("مش هينفع").setMessage(err).setPositiveButton("تمام", null).show()
        } else {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        lastRunning = false      // the ticker redraws as soon as it sees the copy running
    }

    private fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun useDownloads() {
        if (!hasAllFiles()) {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE)
                }
            } catch (_: Throwable) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (_: Throwable) {}
            }
            Toast.makeText(this, "فعّل \"السماح بالوصول لكل الملفات\" وارجع هنا", Toast.LENGTH_LONG).show()
            return
        }
        val files = ModelImporter.findInDownloads()
        when {
            files.isEmpty() -> AlertDialog.Builder(this).setTitle("مفيش ملف")
                .setMessage("مفيش ملف بامتداد .litertlm في مجلد التنزيلات. استخدم \"اختيار ملف النموذج\" لو الملف في مكان تاني.")
                .setPositiveButton("تمام", null).show()
            files.size == 1 -> applyInPlace(files[0])
            else -> AlertDialog.Builder(this).setTitle("اختار الملف")
                .setItems(files.map { "${it.name}  (${ModelImporter.mb(it.length())})" }.toTypedArray()) { _, i -> applyInPlace(files[i]) }
                .setNegativeButton("إلغاء", null).show()
        }
    }

    private fun applyInPlace(f: File) {
        val err = ModelImporter.useInPlace(this, f)
        if (err != null) {
            AlertDialog.Builder(this).setTitle("مش هينفع").setMessage(err).setPositiveButton("تمام", null).show()
        } else {
            Toast.makeText(this, "تمام، هستخدم ${f.name} من مكانه", Toast.LENGTH_LONG).show()
            render()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) render()
    }

    // ------------------------------------------------------------------ test run

    private fun runTest() {
        testing = true
        testText = "بحمّل النموذج وبجرّب... (ممكن ياخد من ١٠ ثواني لدقيقة في أول مرة)"
        render()
        val app = applicationContext
        Bg.io {
            val msg = try {
                val t0 = SystemClock.elapsedRealtime()
                val backend = Llm.ensureLoaded(app)
                val t1 = SystemClock.elapsedRealtime()
                val out = Llm.generate(
                    app, "You are a friendly assistant. Answer in Egyptian Arabic, in one short sentence.",
                    "عرّف نفسك في جملة واحدة.", maxTokens = 60, temperature = 0.7f
                )
                val t2 = SystemClock.elapsedRealtime()
                "اشتغل ✓ (على ${if (backend == "gpu") "كرت الشاشة" else "المعالج"})\nالتحميل: ${(t1 - t0) / 1000.0} ث، الرد: ${(t2 - t1) / 1000.0} ث\n\n${out.trim()}"
            } catch (e: Exception) {
                AppLog.e("model", "test failed", e)
                "فشل: ${e.message}\n\nلو الرسالة مش واضحة: انسخ \"سجل الأخطاء\" وابعته."
            }
            Bg.ui {
                testing = false
                testText = msg
                render()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }
}
