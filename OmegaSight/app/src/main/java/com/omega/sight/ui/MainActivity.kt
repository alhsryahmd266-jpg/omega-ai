package com.omega.sight.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.omega.sight.Prefs
import com.omega.sight.core.BrainSpec
import com.omega.sight.core.Safety
import com.omega.sight.core.Tuning
import com.omega.sight.engine.CaptureService
import com.omega.sight.engine.Hub
import com.omega.sight.engine.StillAnalyzer
import com.omega.sight.input.SightService
import com.omega.sight.ml.TfliteRunner
import com.omega.sight.model.ModelStore
import java.util.Locale

/** The one screen of the app: set-up steps, the owner's files, tests, tuning and the honest limits. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var radar: RadarView
    private lateinit var startBtn: GlowButton
    private lateinit var statusLine: TextView
    private lateinit var stepGame: StepRow
    private lateinit var stepAccess: StepRow
    private lateinit var stepCapture: StepRow
    private lateinit var visionStatus: TextView
    private lateinit var namesStatus: TextView
    private lateinit var brainStatus: TextView
    private lateinit var motionText: TextView
    private lateinit var motionBtn: GlowButton
    private lateinit var liveText: TextView
    private var tuning = Tuning()
    private var ticking = false
    private var askedNotifications = false

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            refresh()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        tuning = prefs.tuning
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val root = FrameLayout(this)
        root.setBackgroundColor(Pal.BG0)
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(26), dp(14), dp(44))
        sv.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(sv, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        hero(col)
        setup(col)
        files(col)
        tests(col)
        tuningCard(col)
        limits(col)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        ticking = true
        ui.post(tick)
    }

    override fun onPause() {
        ticking = false
        ui.removeCallbacks(tick)
        super.onPause()
    }

    // ------------------------------------------------------------------ hero

    private fun hero(col: LinearLayout) {
        radar = RadarView(this)
        col.addView(radar, lp())
        val title = label(this, "Omega Sight", 30f, Pal.TEXT, true)
        title.gravity = Gravity.CENTER
        title.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val w = (r - l).toFloat()
            if (w > 0f) (v as TextView).paint.shader = LinearGradient(0f, 0f, w, 0f, Pal.CYAN, Pal.VIOLET, Shader.TileMode.CLAMP)
        }
        col.addView(title, lp(top = 2, ctx = this))
        val sub = label(this, "رؤية + حركة + وهج أحمر — لعبتك الأوفلاين بس", 13f, Pal.DIM)
        sub.gravity = Gravity.CENTER
        col.addView(sub, lp(top = 4, ctx = this))
        statusLine = label(this, "", 14f, Pal.TEXT, true)
        statusLine.gravity = Gravity.CENTER
        col.addView(statusLine, lp(top = 14, ctx = this))
        startBtn = GlowButton(this, "ابدأ", Pal.CYAN, Pal.VIOLET)
        startBtn.textSize = 17f
        startBtn.setOnClickListener { toggleCapture() }
        val bl = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8, this)
        bl.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(startBtn, bl)
    }

    private fun toggleCapture() {
        if (Hub.stats.running) {
            Hub.testAim = false
            CaptureService.stop(this)
            return
        }
        // Android 13+ asks before a notification may show (the capture notification is how the owner stops it): ask first, then go on
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotifications = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            return
        }
        startActivity(Intent(this, ProjectionActivity::class.java))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // granted or not, the capture goes on: without the permission only the notification is hidden
        if (requestCode == REQ_NOTIFICATIONS && !Hub.stats.running) startActivity(Intent(this, ProjectionActivity::class.java))
    }

    // ------------------------------------------------------------------ set-up

    private fun setup(col: LinearLayout) {
        val card = Card(this, "الإعداد", Pal.CYAN)
        stepGame = StepRow(this, 1, "لعبتك")
        stepAccess = StepRow(this, 2, "خدمة الإيماءات")
        stepCapture = StepRow(this, 3, "التقاط الشاشة")
        card.add(stepGame, 0)
        card.add(stepAccess, 14)
        card.add(stepCapture, 14)
        col.addView(card, lp(top = 22, ctx = this))
    }

    private fun gameLabel(pkg: String): String {
        if (pkg.isEmpty()) return ""
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) {
            pkg
        }
    }

    private fun pickGame() {
        Thread {
            val pm = packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(i, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { Triple(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
            runOnUiThread {
                val list = LinearLayout(this)
                list.orientation = LinearLayout.VERTICAL
                val dlg = sheet(this, "اختار لعبتك", list)
                for ((name, pkg, icon) in apps) {
                    val row = LinearLayout(this)
                    row.orientation = LinearLayout.HORIZONTAL
                    row.gravity = Gravity.CENTER_VERTICAL
                    row.setPadding(0, dp(7), 0, dp(7))
                    val iv = ImageView(this)
                    iv.setImageDrawable(icon)
                    row.addView(iv, LinearLayout.LayoutParams(dp(38), dp(38)))
                    val tx = LinearLayout(this)
                    tx.orientation = LinearLayout.VERTICAL
                    tx.addView(label(this, name, 14.5f, Pal.TEXT, true))
                    tx.addView(label(this, pkg, 10.5f, Pal.DIM))
                    val tl = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    tl.marginStart = dp(12)
                    row.addView(tx, tl)
                    row.setOnClickListener {
                        dlg.dismiss()
                        chooseGame(pkg)
                    }
                    list.addView(row)
                }
                dlg.show()
            }
        }.start()
    }

    private fun chooseGame(pkg: String) {
        if (Safety.isOnlineShooter(pkg)) {
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.addView(label(this, "ده لعبة أونلاين معروفة (${gameLabel(pkg)}). التطبيق مش بيشتغل فيها نهائيًا — هو متصمم للعبتك الأوفلاين بس.", 14f, Pal.TEXT))
            val b = GlowButton(this, "تمام", Pal.AMBER, Pal.RED)
            val dlg = sheet(this, "مش مسموح", c)
            b.setOnClickListener { dlg.dismiss() }
            c.addView(b, lp(top = 14, ctx = this))
            dlg.show()
            return
        }
        prefs.gamePkg = pkg
        prefs.ownConfirmed = false
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.addView(label(this, "${gameLabel(pkg)}\n\nالتطبيق هيشتغل في اللعبة دي بس. أكّد إنها لعبتك الأوفلاين الخاصة (انت اللي عاملها أو بتملكها) ومفيهاش لعب ضد ناس.", 14f, Pal.TEXT))
        val dlg = sheet(this, "تأكيد", c)
        val yes = GlowButton(this, "أؤكد: لعبتي الأوفلاين", Pal.GREEN, Pal.CYAN)
        yes.setOnClickListener {
            prefs.ownConfirmed = true
            dlg.dismiss()
            refresh()
        }
        val no = GlowButton(this, "إلغاء", Pal.DIM, Pal.DIM, true)
        no.setOnClickListener {
            prefs.gamePkg = ""
            dlg.dismiss()
            refresh()
        }
        c.addView(yes, lp(top = 16, ctx = this))
        c.addView(no, lp(top = 4, ctx = this))
        dlg.show()
    }

    // ------------------------------------------------------------------ the owner's files

    private fun files(col: LinearLayout) {
        val card = Card(this, "ملفاتك", Pal.VIOLET)
        visionStatus = label(this, "", 12f, Pal.DIM)
        namesStatus = label(this, "", 12f, Pal.DIM)
        brainStatus = label(this, "", 12f, Pal.DIM)
        card.add(fileBlock("ملف الرؤية (.tflite)", visionStatus, REQ_VISION, ModelStore.Slot.VISION), 0)
        card.add(fileBlock("أسماء الفئات (.txt)", namesStatus, REQ_NAMES, ModelStore.Slot.NAMES), 16)
        card.add(fileBlock("ملف العقل (.tflite)", brainStatus, REQ_BRAIN, ModelStore.Slot.BRAIN), 16)
        val fmt = GlowButton(this, "صيغة الملفات اللي التطبيق بيفهمها", Pal.VIOLET, Pal.MAGENTA, true)
        fmt.setOnClickListener { showFormats() }
        card.add(fmt, 12)
        card.add(label(this, "الملفات بتتنسخ جوه التطبيق بس. مفيش إنترنت: التطبيق مابيحمّلش ولا بيبعت أي حاجة.", 11.5f, Pal.DIM), 8)
        col.addView(card, lp(top = 16, ctx = this))
    }

    private fun fileBlock(title: String, status: TextView, req: Int, slot: ModelStore.Slot): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.addView(label(this, title, 14.5f, Pal.TEXT, true))
        box.addView(status)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val pick = GlowButton(this, "اختار الملف", Pal.CYAN, Pal.VIOLET)
        pick.setOnClickListener { pickFile(req) }
        row.addView(pick)
        if (slot != ModelStore.Slot.NAMES) {
            val test = GlowButton(this, "جرّبه", Pal.GREEN, Pal.CYAN, true)
            test.setOnClickListener { testFile(slot) }
            row.addView(test)
        }
        val del = GlowButton(this, "مسح", Pal.RED, Pal.RED, true)
        del.setOnClickListener {
            ModelStore.remove(this, slot)
            refresh()
        }
        row.addView(del)
        box.addView(row, lp(top = 2, ctx = this))
        return box
    }

    private fun pickFile(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = if (req == REQ_IMAGE) "image/*" else "*/*"
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, req)
        } catch (_: Throwable) {
            toast("مفيش مدير ملفات على الجهاز")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_VISION -> importFile(uri, ModelStore.Slot.VISION)
            REQ_NAMES -> importFile(uri, ModelStore.Slot.NAMES)
            REQ_BRAIN -> importFile(uri, ModelStore.Slot.BRAIN)
            REQ_IMAGE -> analyseImage(uri)
        }
    }

    private fun importFile(uri: Uri, slot: ModelStore.Slot) {
        toast("بنسخ الملف…")
        Thread {
            val err = ModelStore.import(this, uri, slot)
            runOnUiThread {
                if (err.isEmpty()) {
                    toast("اتحمّل ✓ (${ModelStore.displayName(this, uri)})")
                    // a quick look at the file straight away: does it open, which shapes does it have
                    if (slot != ModelStore.Slot.NAMES) testFile(slot)
                } else {
                    toast(err)
                }
                refresh()
            }
        }.start()
    }

    private fun testFile(slot: ModelStore.Slot) {
        if (!ModelStore.has(this, slot)) {
            toast("مفيش ملف")
            return
        }
        toast("بجرّب الملف…")
        Thread {
            val rep = if (slot == ModelStore.Slot.VISION) ModelStore.testVision(this, prefs.gpu) else ModelStore.testBrain(this, prefs.gpu)
            runOnUiThread {
                val c = LinearLayout(this)
                c.orientation = LinearLayout.VERTICAL
                val head = label(this, if (rep.ok) "✓ الملف شغّال" else "✗ في مشكلة", 15f, if (rep.ok) Pal.GREEN else Pal.RED, true)
                c.addView(head)
                for (l in rep.lines) c.addView(label(this, l, 13f, Pal.TEXT), lp(top = 6, ctx = this))
                sheet(this, if (slot == ModelStore.Slot.VISION) "ملف الرؤية" else "ملف العقل", c).show()
            }
        }.start()
    }

    private fun showFormats() {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val mono = { s: String ->
            val t = label(this, s, 12.5f, Pal.TEXT)
            t.typeface = Typeface.DEFAULT
            t
        }
        c.addView(mono(VISION_FORMAT))
        c.addView(label(this, "—", 12f, Pal.DIM), lp(top = 10, ctx = this))
        c.addView(mono(BrainSpec.describe()), lp(top = 6, ctx = this))
        sheet(this, "الصيغ", c).show()
    }

    // ------------------------------------------------------------------ tests

    private fun tests(col: LinearLayout) {
        val card = Card(this, "اختبارات", Pal.GREEN)
        val img = GlowButton(this, "اختبار صورة من جهازك", Pal.CYAN, Pal.VIOLET)
        img.setOnClickListener { pickFile(REQ_IMAGE) }
        card.add(img, 0)
        card.add(label(this, "اختار لقطة شاشة من لعبتك: التطبيق يرسم عليها اللي لقاه (جسم، رأس، وهج أحمر) من غير ما تلعب.", 11.5f, Pal.DIM), 2)

        card.add(label(this, "اختبار الحركة (من غير لمس)", 14.5f, Pal.TEXT, true), 18)
        card.add(label(this, "بيفتح لعبتك وبيحرّك الكاميرا نحو أي عدو يظهر، بشرط إن إصبعك مش على الشاشة. لو لمست الشاشة أندرويد بيلغي لمسات التطبيق، والعدّاد تحت بيورّيك ده.", 11.5f, Pal.DIM), 2)
        motionBtn = GlowButton(this, "ابدأ الاختبار", Pal.GREEN, Pal.CYAN)
        motionBtn.setOnClickListener { toggleMotionTest() }
        card.add(motionBtn, 8)
        motionText = label(this, "", 12.5f, Pal.TEXT)
        card.add(motionText, 6)

        card.add(label(this, "الحالة المباشرة", 14.5f, Pal.TEXT, true), 18)
        liveText = label(this, "", 12f, Pal.TEXT)
        liveText.typeface = Typeface.MONOSPACE
        liveText.gravity = Gravity.START
        card.add(liveText, 6)
        col.addView(card, lp(top = 16, ctx = this))
    }

    private fun toggleMotionTest() {
        if (Hub.testAim) {
            Hub.testAim = false
            refresh()
            return
        }
        val why: String? = when {
            !Hub.stats.running -> "شغّل التقاط الشاشة الأول (زرار ابدأ فوق)"
            SightService.instance == null -> "فعّل خدمة الإيماءات الأول (الخطوة 2)"
            prefs.gamePkg.isEmpty() -> "اختار لعبتك الأول (الخطوة 1)"
            !prefs.ownConfirmed -> "أكّد إن دي لعبتك الأوفلاين"
            Safety.isOnlineShooter(prefs.gamePkg) -> "اللعبة دي أونلاين"
            else -> null
        }
        if (why != null) {
            toast(why)
            return
        }
        Hub.stats.strokesSent = 0
        Hub.stats.strokesDone = 0
        Hub.stats.strokesCancelled = 0
        Hub.stats.strokesFailed = 0
        Hub.testAim = true
        val launch = packageManager.getLaunchIntentForPackage(prefs.gamePkg)
        if (launch != null) startActivity(launch)
        toast("الاختبار شغّال — سيب الشاشة من غير لمس")
        refresh()
    }

    private fun analyseImage(uri: Uri) {
        toast("بحلل الصورة…")
        Thread {
            val r = StillAnalyzer.run(this, uri)
            runOnUiThread {
                val c = LinearLayout(this)
                c.orientation = LinearLayout.VERTICAL
                val iv = ImageView(this)
                iv.adjustViewBounds = true
                iv.setImageBitmap(r.bitmap)
                c.addView(iv, lp())
                for (l in r.lines) c.addView(label(this, l, 13f, Pal.TEXT), lp(top = 6, ctx = this))
                c.addView(label(this, "أزرق = جسم · أرجواني = رأس · أصفر = وهج أحمر", 11.5f, Pal.DIM), lp(top = 8, ctx = this))
                sheet(this, "نتيجة الصورة", c).show()
            }
        }.start()
    }

    // ------------------------------------------------------------------ tuning

    private fun setT(t: Tuning) {
        tuning = t
        prefs.tuning = t
    }

    private fun pct(v: Float) = String.format(Locale.US, "%d%%", Math.round(v * 100f))

    private fun tuningCard(col: LinearLayout) {
        val card = Card(this, "الضبط", Pal.MAGENTA, false)
        card.add(SliderRow(this, "قوة التصويب", "قد إيه من التصحيح يتنفّذ", 0f, 1f, tuning.strength, ::pct) { setT(tuning.copy(strength = it)) }, 0)
        card.add(SliderRow(this, "مجال التصويب", "أبعد هدف عن المؤشر يتصوّب عليه", 0.1f, 0.9f, tuning.fov, ::pct) { setT(tuning.copy(fov = it)) }, 16)
        card.add(SliderRow(this, "نعومة الحركة", "٠ = حاد وسريع ، ١ = ناعم وبطيء", 0f, 1f, tuning.smooth, ::pct) { setT(tuning.copy(smooth = it)) }, 16)
        card.add(SliderRow(this, "أقصى سرعة", "ارتفاعات الشاشة في الثانية", 0.5f, 6f, tuning.maxSpeed, { String.format(Locale.US, "%.1f", it) }) { setT(tuning.copy(maxSpeed = it)) }, 16)
        card.add(SliderRow(this, "الصدر ↔ الرأس", "مكان التصويب جوه الجسم لو مفيش رأس", 0f, 1f, tuning.headBias, ::pct) { setT(tuning.copy(headBias = it)) }, 16)
        card.add(SliderRow(this, "تقدّم على الهدف المتحرك", "٠ = من غير ، ١ = بقد التأخير المقاس", 0f, 2f, tuning.lead, { String.format(Locale.US, "%.1f", it) }) { setT(tuning.copy(lead = it)) }, 16)
        card.add(SliderRow(this, "حساسية الحركة", "١٠٠٪ = أي تغيير صغير يتحسب", 0f, 1f, tuning.motionSensitivity, ::pct) { setT(tuning.copy(motionSensitivity = it)) }, 16)
        card.add(SliderRow(this, "أصغر جسم متحرك", "٠ = حتى نقطة واحدة بعيدة", 0f, 1f, tuning.minBlob, ::pct) { setT(tuning.copy(minBlob = it)) }, 16)
        card.add(SliderRow(this, "ثقة ملف الرؤية", "أقل ثقة لقبول كشف", 0.1f, 0.9f, tuning.modelScore, ::pct) { setT(tuning.copy(modelScore = it)) }, 16)
        card.add(SliderRow(this, "حساسية الوهج الأحمر", "١٠٠٪ = الأحمر الضعيف كمان", 0f, 1f, tuning.glowSens, ::pct) { setT(tuning.copy(glowSens = it)) }, 16)
        card.add(SliderRow(this, "مزج العقل", "٠ = التصويب العادي لوحده", 0f, 1f, prefs.brainMix, ::pct) { prefs.brainMix = it }, 16)

        card.add(SwitchRow(this, "الأجسام المتحركة تتحسب أهداف", "حتى اللي ملف الرؤية مايعرفهاش", tuning.motionTargets) { setT(tuning.copy(motionTargets = it)) }, 18)
        card.add(SwitchRow(this, "وضع البعيد", "نظرة تانية بدقة كاملة لأصغر المتحركات", tuning.farMode) { setT(tuning.copy(farMode = it)) }, 12)
        card.add(SwitchRow(this, "الوهج الأحمر علامة عدو", "عين البوت الحمرا بتتلقط باللون", tuning.glow) { setT(tuning.copy(glow = it)) }, 12)
        card.add(SwitchRow(this, "ملف الرؤية", "شغّله لو متحمّل", prefs.visionOn) { prefs.visionOn = it }, 12)
        card.add(SwitchRow(this, "ملف العقل", "يغيّر نقطة التصويب حسب المزج", prefs.brainOn) { prefs.brainOn = it }, 12)
        card.add(SwitchRow(this, "كرت الشاشة للملفات", "لو ماشتغلش بيرجع للمعالج لوحده", prefs.gpu) { prefs.gpu = it }, 12)
        card.add(SwitchRow(this, "المؤشر الطافي", "نقطة صغيرة فوق اللعبة بتورّي الحالة", prefs.pill) { prefs.pill = it }, 12)

        card.add(label(this, "جودة الالتقاط (بيتطبّق عند التشغيل الجاي)", 14f, Pal.TEXT, true), 18)
        val q = listOf(960, 1280, 1600, 1920)
        card.add(Segmented(this, q.map { it.toString() }, q.indexOf(prefs.captureLong).coerceAtLeast(0)) { prefs.captureLong = q[it] }, 6)
        card.add(label(this, "الأعلى أدق للبعيد لكنه أتقل على الجهاز.", 11.5f, Pal.DIM), 4)

        card.add(label(this, "مكان إصبع التصويب", 14f, Pal.TEXT, true), 16)
        card.add(Segmented(this, listOf("تلقائي", "يمين", "شمال", "وسط"), prefs.zoneMode) { prefs.zoneMode = it }, 6)

        card.add(SliderRow(this, "مكان المؤشر أفقيًا", "نص الشاشة = ٥٠٪", 0.3f, 0.7f, prefs.crossX, ::pct) { prefs.crossX = it }, 16)
        card.add(SliderRow(this, "مكان المؤشر رأسيًا", "نص الشاشة = ٥٠٪", 0.3f, 0.7f, prefs.crossY, ::pct) { prefs.crossY = it }, 16)

        val reset = GlowButton(this, "نسيان معايرة اللعبة", Pal.AMBER, Pal.RED, true)
        reset.setOnClickListener {
            if (prefs.gamePkg.isNotEmpty()) prefs.forgetCalib(prefs.gamePkg)
            toast("اتمسحت — هيتعلمها من جديد")
        }
        card.add(reset, 16)
        val defaults = GlowButton(this, "رجوع للإعدادات الأصلية", Pal.DIM, Pal.DIM, true)
        defaults.setOnClickListener {
            setT(Tuning())
            toast("اتغيّرت — افتح التطبيق تاني عشان الأشرطة تتحدّث")
            recreate()
        }
        card.add(defaults, 4)
        col.addView(card, lp(top = 16, ctx = this))
    }

    // ------------------------------------------------------------------ limits

    private fun limits(col: LinearLayout) {
        val card = Card(this, "حدود التطبيق (بصراحة)", Pal.AMBER, false)
        val lines = listOf(
            "• التقاط الشاشة والتحليل بيشتغلوا على جهازك بس. مفيش إنترنت ولا حاجة بتتبعت أو بتتحفظ.",
            "• بيشتغل في اللعبة اللي اخترتها وأكدت إنها بتاعتك الأوفلاين بس، ومش بيشتغل في ألعاب الأونلاين المعروفة. ومفيهوش أي حقن أو قراءة ذاكرة أو تحايل على أي حماية.",
            "• أندرويد بيلغي لمسات التطبيق لما إصبعك الحقيقي يلمس الشاشة، وممكن يوقف عصا الحركة. عشان كده اختبار الحركة هنا من غير لمس. التصويب وإيدك على الشاشة محتاج خدمة shell (مش موجودة في النسخة دي).",
            "• أبعد مدى وأعلى جرافيك مش مضمونين: نقطة واحدة بعيدة قريبة من حد ضوضاء الصورة، والتطبيق بيقيس ويقول بالأرقام في «الحالة المباشرة».",
            "• الوهج الأحمر علامة مش دليل: أي ضوء أحمر شبهه ممكن يتلقط.",
        )
        for ((i, s) in lines.withIndex()) card.add(label(this, s, 12.5f, Pal.TEXT), if (i == 0) 0 else 8)
        col.addView(card, lp(top = 16, ctx = this))
    }

    // ------------------------------------------------------------------ live

    private fun fmt1(v: Float) = String.format(Locale.US, "%.1f", v)

    private fun refresh() {
        val st = Hub.stats
        val running = st.running
        radar.active = running
        startBtn.text = if (running) "إيقاف" else "ابدأ"
        val verdict = Safety.verdict(prefs.gamePkg, prefs.ownConfirmed, SightService.foreground, packageName)
        statusLine.text = when {
            running && Hub.testAim -> "● اختبار الحركة شغّال — سيب الشاشة"
            running -> "● شغّال · ${st.fps.toInt()} fps · ${st.nTracks} هدف"
            else -> "● واقف"
        }
        statusLine.setTextColor(if (running) Pal.GREEN else Pal.DIM)

        val game = prefs.gamePkg
        stepGame.setDone(
            game.isNotEmpty() && prefs.ownConfirmed,
            if (game.isEmpty()) "اختار اللعبة اللي هتشتغل عليها" else if (!prefs.ownConfirmed) "${gameLabel(game)} — لسه محتاجة تأكيد" else "${gameLabel(game)} ✓ لعبتي الأوفلاين",
            if (game.isEmpty()) "اختار" else "غيّر",
        ) { pickGame() }
        val acc = SightService.instance != null
        stepAccess.setDone(
            acc,
            if (acc) "شغّالة" else "لازم عشان التطبيق يعرف اللعبة اللي قدامك ويبعت لمسة التصويب. إعدادات ← إمكانية الوصول ← Omega Sight",
            if (acc) null else "افتح",
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        stepCapture.setDone(running, if (running) "شغّال ${st.w}×${st.h}" else "بيطلب إذن من النظام", if (running) "وقف" else "ابدأ") { toggleCapture() }

        val vs = if (ModelStore.has(this, ModelStore.Slot.VISION)) "${ModelStore.sizeText(this, ModelStore.Slot.VISION)}" + (if (running && st.visionStatus.isNotEmpty()) " — ${st.visionStatus}" else "") else "مفيش ملف"
        visionStatus.text = vs
        namesStatus.text = if (ModelStore.has(this, ModelStore.Slot.NAMES)) "${ModelStore.names(this).size} اسم" else "مفيش (الفئة 0 = جسم ، 1 = رأس)"
        brainStatus.text = if (ModelStore.has(this, ModelStore.Slot.BRAIN)) "${ModelStore.sizeText(this, ModelStore.Slot.BRAIN)}" + (if (running && st.brainStatus.isNotEmpty()) " — ${st.brainStatus}" else "") else "مفيش ملف"

        motionBtn.text = if (Hub.testAim) "وقف الاختبار" else "ابدأ الاختبار"
        val sb = StringBuilder()
        sb.append(if (Hub.testAim) "شغّال" else "واقف").append(" · ").append(Safety.explain(verdict)).append('\n')
        sb.append("لمسات: اتبعتت ${st.strokesSent} · خلصت ${st.strokesDone} · اتلغت ${st.strokesCancelled} · رفضها النظام ${st.strokesFailed}\n")
        if (st.calibrated) sb.append("✓ الصورة بتتحرك مع اللمسة — تعلّم الكاميرا: x ${fmt1(st.relX)} · y ${fmt1(st.relY)} · تأخير ${fmt1(st.lag)} إطار")
        else sb.append("المعايرة لسه (الثقة ${Math.round(st.confidence * 100)}٪)")
        if (Hub.testAim && st.strokesCancelled > 0 && st.strokesDone == 0) sb.append("\n⚠ كل اللمسات اتلغت: ممكن إصبعك على الشاشة، أو النظام عندك بيمنع لمسات الخدمة")
        motionText.text = sb.toString()

        val lv = StringBuilder()
        if (!running) lv.append("واقف — اضغط ابدأ")
        else {
            lv.append("التقاط ${st.w}×${st.h} · ${fmt1(st.fps)} fps · ${fmt1(st.frameMs)} ms للإطار\n")
            lv.append("حركة ${st.nMotion} (${fmt1(st.motionMs)}ms) · بعيد ${st.nFar} (${fmt1(st.farMs)}ms)\n")
            lv.append("وهج ${st.nGlow} (${fmt1(st.glowMs)}ms) · رؤية ${st.nModel} (${fmt1(st.visionMs)}ms${if (st.visionGpu) " GPU" else ""})\n")
            lv.append("متتبَّع ${st.nTracks} · مختار ${if (st.picked.isEmpty()) "—" else st.picked}\n")
            lv.append("سرعة الكاميرا ${fmt1(st.egoSpeed)} · تعلّم الأزرار ${st.hudPairs}${if (st.hudReady) " ✓" else ""}\n")
            lv.append("رؤية: ${if (st.visionStatus.isEmpty()) "—" else st.visionStatus}\n")
            lv.append("عقل: ${if (st.brainStatus.isEmpty()) "—" else st.brainStatus}")
            if (st.error.isNotEmpty()) lv.append("\n⚠ ${st.error}")
        }
        liveText.text = lv.toString()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val REQ_VISION = 11
        private const val REQ_NAMES = 12
        private const val REQ_BRAIN = 13
        private const val REQ_IMAGE = 14
        private const val REQ_NOTIFICATIONS = 15

        val VISION_FORMAT = """
ملف الرؤية: TensorFlow Lite (.tflite) بمخرج واحد.
الدخل: [1, ارتفاع, عرض, 3] ألوان RGB بين 0 و 1 (NHWC). بيفهم كمان NCHW و uint8 و int8.
الخرج واحد من تلاتة:
 • [1, N, 6] أو [1, 6, N] (N لحد 1000): x1, y1, x2, y2, الثقة, الفئة
 • YOLO خام [1, 4+C, A] أو [1, A, 4+C]: مركز x, مركز y, عرض, ارتفاع ثم ثقة كل فئة
الصندوق بالبكسل أو من 0 لـ 1. الاتنين بيتميّزوا لوحدهم.
ملف الأسماء (.txt): اسم في كل سطر بترتيب الفئات. الفئة اللي اسمها فيه friend أو ally أو team أو civil أو hostage أو ignore عمره ما يتصوّب عليها. الأسماء head أو face أو رأس أو وجه = رأس. من غير ملف أسماء: الفئة 0 جسم والفئة 1 رأس.
""".trim()
    }
}
