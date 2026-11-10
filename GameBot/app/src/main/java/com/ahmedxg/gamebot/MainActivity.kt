package com.ahmedxg.gamebot

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : Activity() {
    companion object {
        const val REQ_CAPTURE = 11
        const val REQ_MODEL = 12
        const val REQ_LABELS = 13
        const val REQ_SHIZUKU = 14
    }

    private var cfg = Config()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var statusTv: TextView
    private lateinit var gameTv: TextView
    private lateinit var modelTv: TextView
    private lateinit var logTv: TextView
    private lateinit var spMode: Spinner
    private lateinit var spSkill: Spinner
    private lateinit var spBackend: Spinner
    private lateinit var spStyle: Spinner
    private lateinit var wizardBtn: Button
    private lateinit var cbSneak: CheckBox
    private lateinit var cbAmbush: CheckBox
    private lateinit var cbDodge: CheckBox
    private lateinit var cbJump: CheckBox
    private lateinit var cbCrouch: CheckBox
    private lateinit var etHitSens: EditText
    private lateinit var cbHealth: CheckBox
    private lateinit var etHealthColor: EditText
    private lateinit var etHealthTol: EditText
    private lateinit var cbCalib: CheckBox
    private lateinit var cbCalibEvery: CheckBox
    private lateinit var cbAllyMap: CheckBox
    private lateinit var etAllyColor: EditText
    private lateinit var etAllyTol: EditText
    private lateinit var etAllyColor2: EditText
    private lateinit var cbEnemyMap: CheckBox
    private lateinit var etEnemyColor: EditText
    private lateinit var etEnemyTol: EditText
    private lateinit var cbSquad: CheckBox
    private lateinit var etFov: EditText
    private lateinit var etLabels: EditText
    private lateinit var etConf: EditText
    private lateinit var etThreads: EditText
    private lateinit var etFps: EditText
    private lateinit var etCap: EditText
    private lateinit var etDelay: EditText
    private lateinit var etMax: EditText
    private lateinit var etAim: EditText
    private lateinit var cbGpu: CheckBox
    private lateinit var cbGuard: CheckBox
    private lateinit var cbRecord: CheckBox
    private lateinit var cbAuto: CheckBox
    private lateinit var cbSoak: CheckBox
    private lateinit var cbRelaunch: CheckBox
    private lateinit var cbZone: CheckBox
    private lateinit var cbHeading: CheckBox
    private lateinit var etFrozen: EditText
    private lateinit var etMacroN: EditText
    private lateinit var etMacroDelay: EditText
    private lateinit var etZoneColor: EditText
    private lateinit var etZoneTol: EditText

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { refresh() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun title(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 16f
        v.setTextColor(Color.rgb(0, 230, 118))
        v.setPadding(0, dp(16), 0, dp(4))
        return v
    }

    private fun note(t: String): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 12f
        v.setTextColor(Color.rgb(176, 176, 176))
        return v
    }

    private fun btn(t: String, f: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.setOnClickListener { f() }
        return b
    }

    private fun field(hint: String, value: String, numeric: Boolean): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.setTextColor(Color.WHITE)
        e.setHintTextColor(Color.GRAY)
        if (numeric) e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        return e
    }

    private fun spinner(items: List<String>, selected: Int): Spinner {
        val s = Spinner(this)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        s.setSelection(selected)
        return s
    }

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logger.init(this)
        cfg = Config.load(this)
        AssetModel.ensure(this, cfg)
        try { Shizuku.addRequestPermissionResultListener(shizukuListener) } catch (t: Throwable) { }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 99)
        }
        build()
    }

    private fun build() {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(14), dp(14), dp(28))
        sv.addView(col)
        setContentView(sv)

        val h = TextView(this)
        h.text = "GameBot"
        h.textSize = 26f
        h.setTextColor(Color.WHITE)
        col.addView(h)
        col.addView(note("بوت بيقرا الشاشة ويلعب بدالك على نفس الهاتف، كله محلي (مفيش إنترنت). مخصص لاختبار لعبتك إنت. الوضع الذكي محتاج نموذج كشف مدرّب على لعبتك (شوف training/README_AR.md)."))

        col.addView(title("الحالة"))
        statusTv = TextView(this)
        statusTv.setTextColor(Color.WHITE)
        statusTv.textSize = 13f
        col.addView(statusTv)
        wizardBtn = btn("...") { nextStep().act() }
        wizardBtn.textSize = 16f
        col.addView(wizardBtn)

        col.addView(title("تشغيل"))
        val run = LinearLayout(this)
        run.orientation = LinearLayout.HORIZONTAL
        val start = btn("▶ تشغيل") { startBot() }
        start.textSize = 20f
        val stop = btn("■ إيقاف") { stopBot() }
        stop.textSize = 20f
        run.addView(start, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        run.addView(stop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(run)
        col.addView(note("بعد الموافقة على تسجيل الشاشة هيفتح اللعبة، ادخل الماتش واضغط ▶ START على الـ HUD اللي فوق الشاشة. ⏸ PAUSE بيوقّف مؤقتًا و■ STOP بينهي ويطلّع تقرير."))
        col.addView(btn("عرض آخر تقرير") { showReport() })
        col.addView(btn("تشخيص المشاكل (ابعتلي النتيجة)") { showDiagnostics() })

        col.addView(title("1) الصلاحيات"))
        col.addView(btn("Shizuku (التحكم باللمس - الأفضل)") { askShizuku() })
        col.addView(btn("Accessibility (بديل للمس + حماية)") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        col.addView(btn("الظهور فوق التطبيقات (HUD + زرار STOP)") { openOverlay() })
        col.addView(btn("Usage access (حماية: يتأكد إن اللعبة قدامك)") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
        col.addView(btn("استثناء من توفير البطارية") { askBattery() })

        col.addView(title("2) اللعبة"))
        gameTv = TextView(this)
        gameTv.setTextColor(Color.WHITE)
        col.addView(gameTv)
        col.addView(btn("اختيار اللعبة") { pickGame() })
        col.addView(btn("تشغيل اللعبة") { launchGame() })

        col.addView(title("3) الوضع والمستوى"))
        spMode = spinner(listOf("Monkey (حركة عشوائية للاختبار، من غير نموذج)", "AI (كشف + تكتيك، بيرجع Monkey لو مفيش نموذج)"), cfg.mode.ordinal)
        col.addView(spMode)
        spSkill = spinner(Skill.values().map { it.label }, cfg.skill.ordinal)
        col.addView(spSkill)
        spStyle = spinner(Style.values().map { it.label }, cfg.style.ordinal)
        col.addView(spStyle)
        col.addView(note("الخطة: Balanced متوازن، Aggressive هجومي، Stealth تسلل وكمين، Survivor بيهرب من الخناقات البعيدة وبيعالج بدري."))

        col.addView(title("4) نموذج الكشف (TFLite)"))
        modelTv = TextView(this)
        modelTv.setTextColor(Color.WHITE)
        col.addView(modelTv)
        col.addView(btn("استيراد نموذج .tflite") { pickFile(REQ_MODEL) })
        col.addView(btn("استيراد ملف labels.txt (اختياري)") { pickFile(REQ_LABELS) })
        col.addView(btn("قياس سرعة النموذج على جهازك") { benchmark() })
        col.addView(btn("تصنيف اللقطات على الهاتف (لتدريب نموذجك)") { startActivity(Intent(this, LabelActivity::class.java)) })
        etLabels = field("أسماء الكلاسات مفصولة بفاصلة", cfg.labels, false)
        col.addView(etLabels)
        etConf = field("حدّ الثقة (0.35)", cfg.conf.toString(), true)
        col.addView(etConf)
        etThreads = field("عدد الـ threads (4)", cfg.threads.toString(), true)
        col.addView(etThreads)
        cbGpu = CheckBox(this)
        cbGpu.text = "استخدم GPU (جرّب وقارن بالـ benchmark)"
        cbGpu.setTextColor(Color.WHITE)
        cbGpu.isChecked = cfg.useGpu
        col.addView(cbGpu)

        col.addView(title("5) التحكم"))
        spBackend = spinner(listOf("AUTO (Shell مدمج ثم Shizuku ثم Accessibility)", "Shizuku", "Accessibility", "Shell مدمج"), cfg.backend.ordinal)
        col.addView(spBackend)
        col.addView(btn("تعديل أماكن أزرار اللعبة (فوق اللعبة)") { editLayout() })
        col.addView(btn("اختبار حقن اللمس (لازم ينجح قبل التشغيل)") { startActivity(Intent(this, TouchTestActivity::class.java)) })

        col.addView(title("6) إعدادات التشغيل"))
        etFps = field("أقصى عدد قرارات في الثانية (30)", cfg.targetFps.toString(), true)
        col.addView(etFps)
        etCap = field("دقة الالتقاط (الضلع الأقصر، 480)", cfg.captureShortSide.toString(), true)
        col.addView(etCap)
        etDelay = field("تأخير البداية بالثواني (5)", cfg.startDelaySec.toString(), true)
        col.addView(etDelay)
        etMax = field("أقصى مدة تشغيل بالدقايق (0 = لحد ما أوقّفه)", cfg.maxRunMinutes.toString(), true)
        col.addView(etMax)
        etAim = field("حساسية التصويب (1.0)", cfg.aimScale.toString(), true)
        col.addView(etAim)
        cbGuard = CheckBox(this)
        cbGuard.text = "الحماية: ما يلمسش غير لما اللعبة تبقى قدامك (مفضّل)"
        cbGuard.setTextColor(Color.WHITE)
        cbGuard.isChecked = cfg.guardEnabled
        col.addView(cbGuard)
        cbRecord = CheckBox(this)
        cbRecord.text = "سجّل لقطات للتدريب (frames) أثناء التشغيل"
        cbRecord.setTextColor(Color.WHITE)
        cbRecord.isChecked = cfg.recordFrames
        col.addView(cbRecord)

        col.addView(title("7) التكتيك (محتاج نموذج كشف)"))
        cbSneak = CheckBox(this)
        cbSneak.text = "هجوم مباغت: يتسلل ويلف على العدو قبل ما يضرب"
        cbSneak.setTextColor(Color.WHITE)
        cbSneak.isChecked = cfg.tacticSneak
        col.addView(cbSneak)
        cbAmbush = CheckBox(this)
        cbAmbush.text = "كمين: يثبت ويراقب لما يفقد عدو قريب"
        cbAmbush.setTextColor(Color.WHITE)
        cbAmbush.isChecked = cfg.tacticAmbush
        col.addView(cbAmbush)
        cbDodge = CheckBox(this)
        cbDodge.text = "مراوغة: ينط ويتحرك جانبي لما يتضرب (بيقرا الحواف الحمرا)"
        cbDodge.setTextColor(Color.WHITE)
        cbDodge.isChecked = cfg.tacticDodge
        col.addView(cbDodge)
        cbJump = CheckBox(this)
        cbJump.text = "قفز أثناء القتال"
        cbJump.setTextColor(Color.WHITE)
        cbJump.isChecked = cfg.tacticJump
        col.addView(cbJump)
        cbCrouch = CheckBox(this)
        cbCrouch.text = "استخدم زرار الانحناء (لو لعبتك بتشتغل toggle) أثناء التسلل والكمين"
        cbCrouch.setTextColor(Color.WHITE)
        cbCrouch.isChecked = cfg.tacticCrouch
        col.addView(cbCrouch)
        etHitSens = field("حساسية كشف الضرب (25، قلّلها لو مابيحسش)", cfg.hitSensitivity.toString(), true)
        col.addView(etHitSens)
        cbHealth = CheckBox(this)
        cbHealth.text = "اقرا شريط الصحة من الـ HUD (ينسحب ويعالج لما الصحة تقل)"
        cbHealth.setTextColor(Color.WHITE)
        cbHealth.isChecked = cfg.healthEnabled
        col.addView(cbHealth)
        etHealthColor = field("لون شريط الصحة RRGGBB (00FF00)", cfg.healthColor, false)
        col.addView(etHealthColor)
        etHealthTol = field("تسامح لون الصحة (50)", cfg.healthTol.toString(), true)
        col.addView(etHealthTol)
        col.addView(note("حدّد شريط الصحة بالعلامتين الخضرا hpTL/hpBR، وزرار العلاج heal، من 'تعديل أماكن الأزرار'. الصديق مقابل العدو: درّب كلاس ally في النموذج، والبوت مابيضربش لو صديق قدام التصويب."))

        col.addView(title("8) الاختبار المكثف (Soak)"))
        cbAuto = CheckBox(this)
        cbAuto.text = "ابدأ لوحده بعد التأخير (من غير ما أضغط START على الـ HUD)"
        cbAuto.setTextColor(Color.WHITE)
        cbAuto.isChecked = cfg.autoStart
        col.addView(cbAuto)
        cbSoak = CheckBox(this)
        cbSoak.text = "مراقب الاختبار: يكتشف الشاشة المجمّدة/الثابتة ويشغّل ماكرو إعادة الماتش"
        cbSoak.setTextColor(Color.WHITE)
        cbSoak.isChecked = cfg.soakEnabled
        col.addView(cbSoak)
        cbRelaunch = CheckBox(this)
        cbRelaunch.text = "افتح اللعبة تاني لو وقعت أو اتقفلت (وعدّ مرات الوقوع)"
        cbRelaunch.setTextColor(Color.WHITE)
        cbRelaunch.isChecked = cfg.relaunchOnExit
        col.addView(cbRelaunch)
        etFrozen = field("اعتبر الشاشة ثابتة بعد كام ثانية (15)", cfg.frozenSeconds.toString(), true)
        col.addView(etFrozen)
        etMacroN = field("عدد ضغطات الماكرو m1..m4 (3)", cfg.macroCount.toString(), true)
        col.addView(etMacroN)
        etMacroDelay = field("الفاصل بين ضغطات الماكرو بالملي ثانية (1500)", cfg.macroDelayMs.toString(), true)
        col.addView(etMacroDelay)
        col.addView(note("أماكن m1..m4 (برتقالي) بتتظبط من 'تعديل أماكن الأزرار': حطها على أزرار (متابعة / العب تاني / ابدأ ماتش)."))

        col.addView(title("9) توجيه الزون من الخريطة المصغرة"))
        cbZone = CheckBox(this)
        cbZone.text = "فعّل توجيه الزون (لو برّه الدايرة يجري ناحية مركزها)"
        cbZone.setTextColor(Color.WHITE)
        cbZone.isChecked = cfg.zoneEnabled
        col.addView(cbZone)
        etZoneColor = field("لون حلقة الزون RRGGBB (FFFFFF)", cfg.zoneColor, false)
        col.addView(etZoneColor)
        etZoneTol = field("تسامح اللون (40)", cfg.zoneTol.toString(), true)
        col.addView(etZoneTol)
        cbHeading = CheckBox(this)
        cbHeading.text = "الخريطة بتلف مع اللاعب (أيقونة اللاعب ثابتة في النص ووشها لفوق)"
        cbHeading.setTextColor(Color.WHITE)
        cbHeading.isChecked = cfg.zoneHeadingUp
        col.addView(cbHeading)
        col.addView(note("حدّد مستطيل الخريطة بالعلامتين الصفرا (mapTL و mapBR) في 'تعديل أماكن الأزرار'. الـ HUD بيكتب 'zone ?' لو ما قراش الحلقة: غيّر اللون أو التسامح."))

        col.addView(title("10) اكتشاف الأزرار والخريطة"))
        cbCalib = CheckBox(this)
        cbCalib.text = "اكتشف العصا وزرار النار تلقائي (البوت بيحرّك الكاميرا 10 ثواني أول ما يبدأ)"
        cbCalib.setTextColor(Color.WHITE)
        cbCalib.isChecked = cfg.autoCalibrate
        col.addView(cbCalib)
        cbCalibEvery = CheckBox(this)
        cbCalibEvery.text = "كرّر الاكتشاف في كل تشغيل (لو أماكن الأزرار بتتغير)"
        cbCalibEvery.setTextColor(Color.WHITE)
        cbCalibEvery.isChecked = cfg.calibrateEveryRun
        col.addView(cbCalibEvery)
        cbAllyMap = CheckBox(this)
        cbAllyMap.text = "الأصدقاء من الخريطة (نقط خضرا، أو دواير مرقمة بلون خلفيتها)"
        cbAllyMap.setTextColor(Color.WHITE)
        cbAllyMap.isChecked = cfg.allyMapEnabled
        col.addView(cbAllyMap)
        etAllyColor = field("لون النقطة الخضرا للصديق RRGGBB (00FF00)", cfg.allyColor, false)
        col.addView(etAllyColor)
        etAllyColor2 = field("لون خلفية العلامة المرقمة (اختياري) RRGGBB", cfg.allyColor2, false)
        col.addView(etAllyColor2)
        etAllyTol = field("تسامح لون الصديق (70)", cfg.allyTol.toString(), true)
        col.addView(etAllyTol)
        cbEnemyMap = CheckBox(this)
        cbEnemyMap.text = "الأعداء من الخريطة (نقط حمرا، أو توهج أحمر وقت الاشتباك)"
        cbEnemyMap.setTextColor(Color.WHITE)
        cbEnemyMap.isChecked = cfg.enemyMapEnabled
        col.addView(cbEnemyMap)
        etEnemyColor = field("لون نقطة العدو RRGGBB (FF2D2D)", cfg.enemyMapColor, false)
        col.addView(etEnemyColor)
        etEnemyTol = field("تسامح لون العدو (60)", cfg.enemyMapTol.toString(), true)
        col.addView(etEnemyTol)
        cbSquad = CheckBox(this)
        cbSquad.text = "ارجع للفريق لو بعيد ومفيش عدو قدامه"
        cbSquad.setTextColor(Color.WHITE)
        cbSquad.isChecked = cfg.squadFollow
        col.addView(cbSquad)
        etFov = field("زاوية الرؤية الأفقية بالدرجات (80)", cfg.fovDeg.toString(), true)
        col.addView(etFov)
        col.addView(note("مستطيل الخريطة = العلامتين الصفرا mapTL/mapBR في 'تعديل أماكن الأزرار' (الافتراضي أعلى اليمين). الرقم جوّه علامة الصديق مابيتقراش؛ بيتعرف عليها من لونها ومكانها، فاكتب لون خلفية العلامة."))

        col.addView(title("11) تفعيل الشل المدمج (اختياري: أسرع، مرة واحدة)"))
        col.addView(note("من غير Shizuku ومن غير كمبيوتر. 1) Developer options ← Wireless debugging (شغّله، لازم Wi-Fi) ← Pair device with pairing code. 2) اضغط 'ابدأ الاقتران' وسيب شاشة الاقتران مفتوحة، واكتب الكود في إشعار GameBot. بعدها التطبيق بيتصل لوحده."))
        col.addView(btn("ابدأ الاقتران (مرة واحدة)") { startPairing() })
        col.addView(btn("اختبار الشل") { testShell() })

        col.addView(title("السجل"))
        logTv = TextView(this)
        logTv.textSize = 10f
        logTv.setTextColor(Color.rgb(160, 160, 160))
        col.addView(logTv)
        col.addView(btn("تصدير السجل (مشاركة)") { shareLog() })
    }

    /** the bot (auto-calibration) and the layout editor save the layout from other components: pick it up */
    private fun reloadSharedState() {
        val fresh = Config.load(this)
        cfg.layout.load(fresh.layout.toJson())
        cfg.layoutCalibrated = fresh.layoutCalibrated
        if (fresh.modelFile.isNotEmpty()) cfg.modelFile = fresh.modelFile
        if (fresh.labelsFile.isNotEmpty()) cfg.labelsFile = fresh.labelsFile
    }

    private fun showDiagnostics() {
        toast("بيفحص كل حاجة (ممكن ياخد 15 ثانية)...")
        Thread {
            val text = try { Diagnostics.collect(this) } catch (t: Throwable) { "diagnostics failed: " + t.javaClass.simpleName + " " + t.message }
            runOnUiThread {
                val tv = TextView(this)
                tv.text = text
                tv.textSize = 11f
                tv.setPadding(dp(12), dp(8), dp(12), dp(8))
                val sv = ScrollView(this)
                sv.addView(tv)
                AlertDialog.Builder(this)
                    .setTitle("تشخيص GameBot")
                    .setView(sv)
                    .setPositiveButton("مشاركة") { _, _ ->
                        val i = Intent(Intent.ACTION_SEND)
                        i.type = "text/plain"
                        i.putExtra(Intent.EXTRA_TEXT, text)
                        startActivity(Intent.createChooser(i, "GameBot diagnostics"))
                    }
                    .setNegativeButton("إغلاق", null)
                    .show()
            }
        }.start()
    }

    private fun startPairing() {
        try {
            val i = Intent(this, PairingService::class.java)
            i.action = PairingService.ACTION_START
            startForegroundService(i)
            toast("افتح Wireless debugging ← Pair device with pairing code، واكتب الكود في إشعار GameBot")
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (t: Throwable) {
            toast("افتح خيارات المطوّر يدويًا: ${t.javaClass.simpleName}")
        }
    }

    private fun testShell() {
        toast("بيجرّب الشل...")
        Thread {
            val s = AdbShellInput(this)
            val e = s.prepare()
            s.close()
            Logger.i("shell test: ${e ?: "OK"}")
            runOnUiThread { toast(if (e == null) "الشل شغّال ✔" else e) }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        reloadSharedState()
        refresh()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        collect()
        cfg.save(this)
        super.onPause()
    }

    override fun onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(shizukuListener) } catch (t: Throwable) { }
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            logTv.text = Logger.tail(40)
            statusTv.text = statusText()
            wizardBtn.text = nextStep().text
            ui.postDelayed(this, 1000L)
        }
    }

    private fun mark(ok: Boolean) = if (ok) "✔" else "✖"

    private fun hasUsage(): Boolean {
        return try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            false
        }
    }

    private fun shizukuState(): String {
        return try {
            if (!Shizuku.pingBinder()) "✖ مش شغّال"
            else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) "⚠ شغّال بس الصلاحية مش ممنوحة"
            else "✔ جاهز"
        } catch (t: Throwable) {
            "✖ غير متاح"
        }
    }

    private fun statusText(): String {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val model = if (cfg.modelFile.isNotEmpty() && File(cfg.modelFile).exists()) File(cfg.modelFile).name else "—"
        return "Shizuku: ${shizukuState()}\n" +
            "Shell مدمج: ${if (ShellState.paired(this)) "✔ مقترن" else "✖ مش مفعّل (اختياري)"}\n" +
            "Accessibility: ${mark(BotAccessibilityService.instance != null)}\n" +
            "الظهور فوق التطبيقات: ${mark(Settings.canDrawOverlays(this))}\n" +
            "Usage access: ${mark(hasUsage())}\n" +
            "البطارية: ${mark(pm.isIgnoringBatteryOptimizations(packageName))}\n" +
            "النموذج: $model\n" +
            "البوت: ${if (BotService.running) "شغّال" else "واقف"} (${BotService.status})"
    }

    private class Step(val text: String, val act: () -> Unit)

    /** One button that always does the next thing still missing (no external apps needed). */
    private fun nextStep(): Step {
        val shizukuOk = shizukuState().startsWith("✔")
        val a11y = BotAccessibilityService.instance != null
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!shizukuOk && !a11y) {
            return Step("الخطوة 1: فعّل خدمة GameBot Input (Accessibility) ←") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        if (!Settings.canDrawOverlays(this)) {
            return Step("الخطوة 2: اسمح بالظهور فوق التطبيقات ←") { openOverlay() }
        }
        if (cfg.guardEnabled && !a11y && !hasUsage()) {
            return Step("الخطوة 3: فعّل Usage access ←") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        if (cfg.gamePackage.isEmpty()) {
            return Step("الخطوة 4: اختار لعبتك ←") { pickGame() }
        }
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            return Step("الخطوة 5: استثناء توفير البطارية ←") { askBattery() }
        }
        return Step("✔ كله جاهز: افتح لعبتك واضغط هنا للتشغيل ▶") { startBot() }
    }

    private fun refresh() {
        wizardBtn.text = nextStep().text
        gameTv.text = if (cfg.gamePackage.isEmpty()) "اللعبة: لسه ما اتحددتش" else "اللعبة: ${cfg.gamePackage}"
        modelTv.text = if (cfg.modelFile.isEmpty()) "مفيش نموذج (وضع Monkey بس)" else "النموذج: ${File(cfg.modelFile).name}"
        statusTv.text = statusText()
        logTv.text = Logger.tail(40)
    }

    private fun collect() {
        try {
            cfg.mode = Mode.values()[spMode.selectedItemPosition.coerceIn(0, Mode.values().size - 1)]
            cfg.skill = Skill.values()[spSkill.selectedItemPosition.coerceIn(0, Skill.values().size - 1)]
            cfg.backend = Backend.values()[spBackend.selectedItemPosition.coerceIn(0, Backend.values().size - 1)]
            cfg.labels = etLabels.text.toString().trim()
            cfg.conf = etConf.text.toString().trim().toFloatOrNull()?.coerceIn(0.05f, 0.95f) ?: 0.35f
            cfg.threads = etThreads.text.toString().trim().toIntOrNull()?.coerceIn(1, 8) ?: 4
            cfg.useGpu = cbGpu.isChecked
            cfg.targetFps = etFps.text.toString().trim().toIntOrNull()?.coerceIn(5, 60) ?: 30
            cfg.captureShortSide = etCap.text.toString().trim().toIntOrNull()?.coerceIn(160, 1080) ?: 480
            cfg.startDelaySec = etDelay.text.toString().trim().toIntOrNull()?.coerceIn(0, 60) ?: 5
            cfg.maxRunMinutes = etMax.text.toString().trim().toIntOrNull()?.coerceIn(0, 1440) ?: 0
            cfg.aimScale = etAim.text.toString().trim().toFloatOrNull()?.coerceIn(0.1f, 3f) ?: 1f
            cfg.guardEnabled = cbGuard.isChecked
            cfg.recordFrames = cbRecord.isChecked
            cfg.autoStart = cbAuto.isChecked
            cfg.soakEnabled = cbSoak.isChecked
            cfg.relaunchOnExit = cbRelaunch.isChecked
            cfg.frozenSeconds = etFrozen.text.toString().trim().toIntOrNull()?.coerceIn(5, 300) ?: 15
            cfg.macroCount = etMacroN.text.toString().trim().toIntOrNull()?.coerceIn(0, 4) ?: 3
            cfg.macroDelayMs = etMacroDelay.text.toString().trim().toIntOrNull()?.coerceIn(300, 10000) ?: 1500
            cfg.zoneEnabled = cbZone.isChecked
            cfg.zoneColor = etZoneColor.text.toString().trim().removePrefix("#").ifEmpty { "FFFFFF" }
            cfg.zoneTol = etZoneTol.text.toString().trim().toIntOrNull()?.coerceIn(5, 160) ?: 40
            cfg.zoneHeadingUp = cbHeading.isChecked
            cfg.style = Style.values()[spStyle.selectedItemPosition.coerceIn(0, Style.values().size - 1)]
            cfg.tacticSneak = cbSneak.isChecked
            cfg.tacticAmbush = cbAmbush.isChecked
            cfg.tacticDodge = cbDodge.isChecked
            cfg.tacticJump = cbJump.isChecked
            cfg.tacticCrouch = cbCrouch.isChecked
            cfg.hitSensitivity = etHitSens.text.toString().trim().toIntOrNull()?.coerceIn(5, 120) ?: 25
            cfg.healthEnabled = cbHealth.isChecked
            cfg.healthColor = etHealthColor.text.toString().trim().removePrefix("#").ifEmpty { "00FF00" }
            cfg.healthTol = etHealthTol.text.toString().trim().toIntOrNull()?.coerceIn(5, 160) ?: 50
            cfg.autoCalibrate = cbCalib.isChecked
            cfg.calibrateEveryRun = cbCalibEvery.isChecked
            cfg.allyMapEnabled = cbAllyMap.isChecked
            cfg.allyColor = etAllyColor.text.toString().trim().removePrefix("#").ifEmpty { "00FF00" }
            cfg.allyColor2 = etAllyColor2.text.toString().trim().removePrefix("#")
            cfg.allyTol = etAllyTol.text.toString().trim().toIntOrNull()?.coerceIn(5, 160) ?: 70
            cfg.enemyMapEnabled = cbEnemyMap.isChecked
            cfg.enemyMapColor = etEnemyColor.text.toString().trim().removePrefix("#").ifEmpty { "FF2D2D" }
            cfg.enemyMapTol = etEnemyTol.text.toString().trim().toIntOrNull()?.coerceIn(5, 160) ?: 60
            cfg.squadFollow = cbSquad.isChecked
            cfg.fovDeg = etFov.text.toString().trim().toIntOrNull()?.coerceIn(30, 150) ?: 80
        } catch (t: Throwable) {
            Logger.i("collect failed: ${t.message}")
        }
    }

    // ---- permissions -------------------------------------------------------------------------

    private fun askShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                toast("Shizuku مش شغّال. نزّله وشغّله الأول (الخطوات في README)")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                toast("Shizuku جاهز")
                return
            }
            Shizuku.requestPermission(REQ_SHIZUKU)
        } catch (t: Throwable) {
            toast("Shizuku: ${t.javaClass.simpleName}")
        }
    }

    private fun openOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (t: Throwable) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---- game / model ------------------------------------------------------------------------

    private fun pickGame() {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN)
        i.addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(i, 0)
            .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.second != packageName }
            .sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) {
            toast("مفيش تطبيقات ظهرت")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("اختار لعبتك")
            .setItems(apps.map { it.first + "\n" + it.second }.toTypedArray()) { _, which ->
                if (Safety.isBlocked(apps[which].second)) {
                    toast(Safety.MESSAGE)
                } else {
                    cfg.gamePackage = apps[which].second
                    cfg.save(this)
                    refresh()
                }
            }
            .show()
    }

    private fun launchGame() {
        val i = packageManager.getLaunchIntentForPackage(cfg.gamePackage)
        if (i == null) {
            toast("مش لاقي اللعبة، اختارها الأول")
            return
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun pickFile(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, req)
    }

    private fun importUri(uri: Uri, dest: String): String {
        val dir = File(filesDir, "models")
        dir.mkdirs()
        val f = File(dir, dest)
        val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot open file")
        input.use { ins -> f.outputStream().use { out -> ins.copyTo(out) } }
        return f.absolutePath
    }

    private fun benchmark() {
        collect()
        cfg.save(this)
        if (cfg.modelFile.isEmpty()) {
            toast("استورد نموذج الأول")
            return
        }
        toast("بيقيس السرعة...")
        Thread {
            val d = Detector(cfg)
            val err = d.load()
            val res = if (err != null) err else d.benchmark(40)
            d.close()
            Logger.i("benchmark: $res")
            runOnUiThread {
                toast(res)
                refresh()
            }
        }.start()
    }

    // ---- run ---------------------------------------------------------------------------------

    private fun editLayout() {
        if (!Settings.canDrawOverlays(this)) {
            toast("فعّل الظهور فوق التطبيقات الأول")
            openOverlay()
            return
        }
        startForegroundService(Intent(this, EditorService::class.java))
        toast("ادخل ماتش، واضغط ⚙ Layout اللي ظهر فوق، واسحب الدواير على أزرارك")
        launchGame()
    }

    private fun startBot() {
        collect()
        cfg.save(this)
        if (BotService.running) {
            toast("البوت شغّال بالفعل")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("فعّل الظهور فوق التطبيقات (للـ HUD وزرار STOP)")
            openOverlay()
            return
        }
        if (Safety.isBlocked(cfg.gamePackage)) {
            toast(Safety.MESSAGE)
            return
        }
        if (cfg.guardEnabled && cfg.gamePackage.isEmpty()) {
            toast("اختار اللعبة الأول")
            return
        }
        if (cfg.guardEnabled && !hasUsage() && BotAccessibilityService.instance == null) {
            toast("الحماية محتاجة Usage access أو Accessibility (أو عطّل الحماية)")
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    private fun stopBot() {
        val i = Intent(this, BotService::class.java)
        i.action = BotService.ACTION_STOP
        startService(i)
    }

    private fun showReport() {
        val f = File(filesDir, "last_report.txt")
        val text = if (f.exists()) f.readText() else "مفيش تقرير لسه. التقرير بيتكتب لما توقّف البوت."
        AlertDialog.Builder(this).setTitle("آخر تقرير").setMessage(text).setPositiveButton("تمام", null).show()
    }

    private fun shareLog() {
        try {
            val f = File(filesDir, "gamebot.log")
            val text = if (f.exists()) f.readText() else Logger.tail(150)
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, text.takeLast(60000))
            startActivity(Intent.createChooser(i, "GameBot log"))
        } catch (t: Throwable) {
            toast("فشل التصدير: ${t.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    val i = Intent(this, BotService::class.java)
                    i.action = BotService.ACTION_START
                    i.putExtra(BotService.EXTRA_CODE, resultCode)
                    i.putExtra(BotService.EXTRA_DATA, data)
                    startForegroundService(i)
                    ui.postDelayed({ if (cfg.gamePackage.isNotEmpty()) launchGame() }, 700L)
                } else {
                    toast("لازم توافق على تسجيل الشاشة")
                }
            }
            REQ_MODEL, REQ_LABELS -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    try {
                        if (requestCode == REQ_MODEL) {
                            cfg.modelFile = importUri(uri, "detector.tflite")
                        } else {
                            cfg.labelsFile = importUri(uri, "labels.txt")
                        }
                        cfg.save(this)
                        refresh()
                        toast("تم الاستيراد")
                    } catch (t: Throwable) {
                        toast("فشل الاستيراد: ${t.message}")
                    }
                }
            }
        }
    }
}
