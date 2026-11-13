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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
import com.omega.keyboard.ai.ModelDownloader
import com.omega.keyboard.ai.ModelImporter
import com.omega.keyboard.ai.ModelStore
import com.omega.keyboard.core.GameChat
import java.io.File
import java.io.FileOutputStream

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
    private var dlBar: ProgressBar? = null
    private var dlLabel: TextView? = null
    private var lastDl = ModelDownloader.Kind.NONE
    private var dlTick = 0

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
            if (++dlTick % 4 == 0) pollDownload()
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
        if (intent?.getBooleanExtra("autodownload", false) == true) {
            intent.removeExtra("autodownload")
            handler.post { if (!ModelDownloader.status(this).active) confirmDownload() }
        }
        // "اختيار ملف" on the first screen: go straight to the system file picker (only on a fresh start, never after a rotation)
        if (savedInstanceState == null && intent?.getBooleanExtra("autopick", false) == true) {
            intent.removeExtra("autopick")
            handler.post { pick() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (ModelDownloader.adoptIfDone(this)) Toast.makeText(this, "النموذج اتنزّل واتفعّل وجاهز", Toast.LENGTH_LONG).show()
        lastDl = ModelDownloader.status(this).kind
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
        dlBar = null
        dlLabel = null
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
            else "الوضع الذاتي والترجمة لباقي اللغات محتاجين ملف النموذج (‎.litertlm) على التليفون."
        ))
        h.addView(status)

        // ---- the models on the phone: switch between them or delete one
        val models = ModelStore.installed(this)
        if (models.size >= 2) {
            h.addView(UiKit.section(this, p, "النماذج اللي على تليفونك"))
            h.addView(UiKit.group(this, p, *models.map { f ->
                val active = f.absolutePath == prefs.modelPath
                UiKit.valueRow(this, p, ModelStore.title(f), if (active) "شغّال ✓" else "استخدمه", "${ModelImporter.mb(f.length())} — ${f.name}") { modelActions(f) }
            }.toTypedArray()))
            h.addView(UiKit.label(this, "E2B أسرع وبياخد ذاكرة أقل. E4B أذكى وبيفهم السياق أحسن بس أبطأ شوية. جرّب الاتنين واختار اللي يريحك.", 12.5f, p.sub).apply {
                setPadding(dp(24), dp(8), dp(24), 0)
            })
        }

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

        // ---- download: the file he already has is the main way in, so the download card goes below it; on top only while something is going on
        val dl = ModelDownloader.status(this)
        val dlOnTop = dl.active || dl.kind == ModelDownloader.Kind.FAILED
        if (dlOnTop) buildDownload(h, path != null, dl)

        // ---- choose
        h.addView(UiKit.section(this, p, "إضافة النموذج"))
        val choose = UiKit.card(this, p)
        choose.addView(UiKit.label(this, "اختار الملف اللي نزّلته (مثلًا gemma-4-E2B-it.litertlm). بيتنسخ جوه التطبيق (بيحتاج مساحة فاضية بقد حجمه) ومبيتبعتش لأي مكان.", 13.5f, p.sub))
        choose.addView(UiKit.button(this, p, if (path != null) "تغيير ملف النموذج" else "اختيار ملف النموذج") { pick() })
        choose.addView(UiKit.spacer(this, 10f))
        choose.addView(UiKit.label(this, "تليفونك مساحته ضيقة؟ استخدم الملف من مجلد التنزيلات زي ما هو من غير نسخ (بيحتاج إذن الوصول لكل الملفات).", 13.5f, p.sub))
        choose.addView(UiKit.button(this, p, if (hasAllFiles()) "استخدم ملف من التنزيلات" else "السماح بالوصول للملفات", filled = false) { useDownloads() })
        h.addView(choose)
        if (!dlOnTop) buildDownload(h, path != null, dl)

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

    // ------------------------------------------------------------------ download from the internet

    private fun dlText(st: ModelDownloader.Status): String =
        if (st.total > 0) "${st.percent}%  —  ${ModelImporter.mb(st.done)} من ${ModelImporter.mb(st.total)}" else ModelImporter.mb(st.done)

    private fun pollDownload() {
        val adopted = ModelDownloader.adoptIfDone(this)
        val st = ModelDownloader.status(this)
        if (adopted) Toast.makeText(this, "النموذج اتنزّل واتفعّل وجاهز", Toast.LENGTH_LONG).show()
        if (adopted || st.kind != lastDl) {
            lastDl = st.kind
            render()
        } else if (st.active) {
            dlBar?.progress = if (st.total > 0) (st.done * 1000 / st.total).toInt() else 0
            dlLabel?.text = dlText(st)
        }
    }

    /** a row of equally wide buttons */
    private fun buttonRow(primaryFirst: Boolean, vararg items: Pair<String, () -> Unit>): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, item) in items.withIndex()) {
            val action = item.second
            val b = UiKit.button(this, p, item.first, filled = primaryFirst && i == 0) { action() }
            b.setPadding(dp(8), 0, dp(8), 0)
            b.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).also { lp ->
                lp.topMargin = dp(10)
                if (i > 0) lp.marginStart = dp(8)
            }
            r.addView(b)
        }
        return r
    }

    private fun buildDownload(h: LinearLayout, hasModel: Boolean, st: ModelDownloader.Status) {
        val failed = st.kind == ModelDownloader.Kind.FAILED
        h.addView(UiKit.section(this, p, if (hasModel && !st.active && !failed) "تنزيل نسخة تانية من الإنترنت" else "تنزيل النموذج من الإنترنت"))
        val c = UiKit.card(this, p)
        when {
            st.active -> {
                val paused = st.kind == ModelDownloader.Kind.PAUSED
                c.addView(UiKit.label(this, if (paused) "التنزيل واقف دلوقتي" else "بنزّل النموذج...", 15f, p.text, true))
                if (paused) c.addView(UiKit.label(this, st.message, 13f, p.sub).apply { setPadding(0, dp(4), 0, 0) })
                val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = if (st.total > 0) (st.done * 1000 / st.total).toInt() else 0
                    progressTintList = android.content.res.ColorStateList.valueOf(p.accent)
                }
                c.addView(pb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).also { it.topMargin = dp(12) })
                val lab = UiKit.label(this, dlText(st), 13f, p.sub).apply { setPadding(0, dp(6), 0, 0) }
                c.addView(lab)
                c.addView(UiKit.label(
                    this, "تقدر تقفل الشاشة أو التطبيق: التنزيل بيكمل في الخلفية (فيه إشعار بالتقدم) وبيتفعّل لوحده لما يخلص.", 12.5f, p.sub
                ).apply { setPadding(0, dp(8), 0, 0) })
                c.addView(UiKit.button(this, p, "إلغاء التنزيل", filled = false) {
                    ModelDownloader.cancel(this)
                    lastDl = ModelDownloader.Kind.NONE
                    render()
                })
                dlBar = pb
                dlLabel = lab
            }
            failed -> {
                c.addView(UiKit.statusRow(this, p, -1, "التنزيل ما اتمّش", st.message))
                c.addView(buttonRow(true, "حاول تاني" to { confirmDownload(ModelDownloader.entryFor(prefs.dlName)) }, "إلغاء" to {
                    ModelDownloader.cancel(this)
                    lastDl = ModelDownloader.Kind.NONE
                    render()
                }))
            }
            else -> {
                c.addView(UiKit.label(
                    this, "نزّل النموذج جوه التطبيق مباشرةً من غير ما تدوّر على ملفات. يفضّل واي فاي. اللي نزّلته قبل كده بيفضل موجود وتبدّل بينهم.", 13.5f, p.sub
                ))
                val have = ModelStore.installed(this).map { it.name.lowercase() }
                for ((i, e) in ModelDownloader.CATALOG.withIndex()) {
                    c.addView(UiKit.spacer(this, 8f))
                    val label = "${e.title} — ${e.note} (${e.sizeText})" + if (have.contains(e.file.lowercase())) "  ✓ موجود" else ""
                    c.addView(UiKit.button(this, p, label, filled = !hasModel && i == 0) { confirmDownload(e) })
                }
            }
        }
        // the link: to send to somebody else, or to download in a browser
        c.addView(UiKit.spacer(this, 14f))
        c.addView(UiKit.label(this, "رابط النموذج (ابعته لأي حد بيستخدم الكيبورد):", 12.5f, p.sub))
        c.addView(UiKit.label(this, ModelDownloader.FILE_URL, 11.5f, p.text).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = UiKit.shape(this@ModelActivity, p.cardAlt, 10f)
            textDirection = android.view.View.TEXT_DIRECTION_LTR
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(6) })
        c.addView(buttonRow(false, "فتح الصفحة" to { openLink(ModelDownloader.REPO_URL) }, "نسخ الرابط" to { copyLink() }, "مشاركة" to { shareLink() }))
        h.addView(c)
    }

    private fun confirmDownload(entry: ModelDownloader.Entry = ModelDownloader.E2B) {
        AlertDialog.Builder(this)
            .setTitle("تنزيل ${entry.title}")
            .setMessage("الملف حوالي ${entry.sizeText} (${entry.title} من Hugging Face). بينزل في الخلفية ومعاه إشعار، وبيتفعّل لوحده لما يخلص. يفضّل واي فاي عشان باقة النت.")
            .setPositiveButton("واي فاي بس") { _, _ -> startDownload(true, entry) }
            .setNeutralButton("أي شبكة") { _, _ -> startDownload(false, entry) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun startDownload(wifiOnly: Boolean, entry: ModelDownloader.Entry = ModelDownloader.E2B) {
        val err = ModelDownloader.start(this, wifiOnly, entry)
        if (err != null) {
            AlertDialog.Builder(this).setTitle("مش هينفع").setMessage(err)
                .setPositiveButton("تمام", null)
                .setNeutralButton("فتح الصفحة") { _, _ -> openLink(entry.repoUrl) }
                .show()
        } else {
            Toast.makeText(this, "بدأ التنزيل", Toast.LENGTH_SHORT).show()
        }
        lastDl = ModelDownloader.status(this).kind
        render()
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
            Toast.makeText(this, "مفيش متصفح يفتح الرابط", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyLink() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("model-link", ModelDownloader.FILE_URL))
        Toast.makeText(this, "اتنسخ رابط النموذج", Toast.LENGTH_SHORT).show()
    }

    private fun shareLink() {
        val text = "نموذج Gemma لكيبورد Omega (حوالي ٢.٦ جيجا):\n${ModelDownloader.FILE_URL}\n\nصفحة النموذج: ${ModelDownloader.REPO_URL}"
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        try {
            startActivity(Intent.createChooser(i, "مشاركة رابط النموذج"))
        } catch (_: Throwable) {
            Toast.makeText(this, "مقدرتش أفتح المشاركة", Toast.LENGTH_LONG).show()
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
                .setMessage("مفيش ملف بامتداد ‎.litertlm‎ في مجلد التنزيلات. استخدم \"اختيار ملف النموذج\" لو الملف في مكان تاني.")
                .setPositiveButton("تمام", null).show()
            files.size == 1 -> applyInPlace(files[0])
            else -> AlertDialog.Builder(this).setTitle("اختار الملف")
                .setItems(files.map { "${it.name}  (${ModelImporter.mb(it.length())})" }.toTypedArray()) { _, i -> applyInPlace(files[i]) }
                .setNegativeButton("إلغاء", null).show()
        }
    }

    /** tapped a model of the list: switch to it, or delete it (only a file the app keeps itself: the user's own files stay) */
    private fun modelActions(f: File) {
        val active = f.absolutePath == prefs.modelPath
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (!active) items.add("استخدم النموذج ده" to { applyInPlace(f) })
        if (ModelStore.isOurs(this, f)) items.add("احذفه من التليفون" to { confirmDelete(f) })
        if (items.isEmpty()) return
        AlertDialog.Builder(this).setTitle(ModelStore.title(f))
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this).setTitle("حذف النموذج؟")
            .setMessage("${f.name} (${ModelImporter.mb(f.length())}) هيتمسح من التليفون وتقدر تضيفه أو تنزّله تاني.")
            .setPositiveButton("احذف") { _, _ -> deleteModel(f) }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun deleteModel(f: File) {
        val wasActive = f.absolutePath == prefs.modelPath
        val gone = try { f.delete() } catch (_: Throwable) { false }
        if (!gone) {
            Toast.makeText(this, "مقدرتش أمسح الملف", Toast.LENGTH_LONG).show()
            return
        }
        if (wasActive) {
            // another model takes over when there is one
            prefs.modelPath = ModelStore.installed(this).firstOrNull()?.absolutePath ?: ""
            Bg.io { Llm.unload() }
        }
        render()
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
                // loaded once with the picture reader: the games need it, and a second load would cost the same time again
                val backend = Llm.ensureLoaded(app, vision = true, retryVision = true)
                val t1 = SystemClock.elapsedRealtime()
                val out = Llm.generate(
                    app, "You are a friendly assistant. Answer in Egyptian Arabic, in one short sentence.",
                    "عرّف نفسك في جملة واحدة.", maxTokens = 60, temperature = 0.7f
                )
                val t2 = SystemClock.elapsedRealtime()
                "اشتغل ✓ (على ${if (backend == "gpu") "كرت الشاشة" else "المعالج"})\nالتحميل: ${(t1 - t0) / 1000.0} ث، الرد: ${(t2 - t1) / 1000.0} ث\n\n${out.trim()}\n\n${visionCheck(app)}"
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

    /** A picture that looks like a game's chat (coloured lines on a dark scene), made here so the test needs nothing from outside. */
    private fun testPicture(app: Context): File {
        val bmp = Bitmap.createBitmap(900, 420, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(24, 32, 44))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 40f; typeface = Typeface.DEFAULT_BOLD }
        fun line(y: Float, name: String, nameColour: Int, text: String, colour: Int) {
            paint.color = nameColour
            c.drawText(name, 28f, y, paint)
            paint.color = colour
            c.drawText(text, 28f + paint.measureText(name) + 14f, y, paint)
        }
        line(90f, "[VIP] Ahmed:", Color.rgb(255, 160, 0), "who has the flag?", Color.rgb(255, 235, 59))
        line(190f, "Sara:", Color.rgb(120, 200, 255), "I do, covering left", Color.rgb(255, 105, 180))
        line(290f, "Omar:", Color.rgb(255, 120, 120), "gg wp 482", Color.rgb(105, 240, 120))
        val f = File(app.cacheDir, "vision_test.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }

    /** The same way the autonomous mode reads a game's chat, on a picture whose lines are known. */
    private fun visionCheck(app: Context): String {
        if (Llm.visionOk != true) {
            return "قراية الصور (للألعاب): مش شغالة ✗\n" + (Llm.visionWhy.takeIf { it.isNotBlank() } ?: "الجهاز مادّاش سبب") +
                "\n\nلو دي الرسالة، ابعتها لي زي ما هي."
        }
        return try {
            val f = testPicture(app)
            val t0 = SystemClock.elapsedRealtime()
            val raw = Llm.generate(
                app, GameChat.visionSystem(""), GameChat.VISION_USER, maxTokens = 200, temperature = 0.1f, topK = 1, topP = 1f,
                timeoutMs = 240_000, imagePath = f.absolutePath, visualTokens = 560
            )
            val sec = (SystemClock.elapsedRealtime() - t0) / 1000.0
            val lines = GameChat.parseTranscript(raw, "", emptyList())
            if (lines.isEmpty()) "قراية الصور: اشتغلت بس مقرتش رسايل ✗ ($sec ث)\nالرد: ${raw.trim().take(140)}"
            else "قراية الصور (للألعاب): شغالة ✓ في $sec ث\nقرت ${lines.size} رسايل (المفروض ٣):\n" + lines.joinToString("\n") { "• " + it.text }
        } catch (e: Exception) {
            AppLog.e("model", "the picture test failed", e)
            "قراية الصور: فشلت ✗\n${e.message}"
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }
}
