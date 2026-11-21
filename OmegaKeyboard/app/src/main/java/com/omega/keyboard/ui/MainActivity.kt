package com.omega.keyboard.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import com.omega.keyboard.ai.Llm
import com.omega.keyboard.ai.ModelDownloader
import com.omega.keyboard.ai.ModelStore
import com.omega.keyboard.agent.ScreenAgentService
import com.omega.keyboard.agent.ShortcutHider

/** First screen: switches everything on step by step and shows, live, what is still missing. */
class MainActivity : BaseActivity() {

    private lateinit var p: Pal
    private lateinit var steps: LinearLayout
    private lateinit var hero: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var lastKey = ""

    /** while the model downloads, the step below shows the progress */
    private val tick = object : Runnable {
        override fun run() {
            renderSteps(force = false)
            if (ModelDownloader.status(this@MainActivity).active) handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = UiKit.pal(this)
        val col = UiKit.column(this)

        hero = UiKit.card(this, p)
        col.addView(hero)
        steps = UiKit.column(this)
        col.addView(steps)

        // typing test
        col.addView(UiKit.section(this, p, "جرّب الكيبورد"))
        val test = UiKit.card(this, p)
        val field = android.widget.EditText(this).apply {
            hint = "اكتب هنا..."
            setHintTextColor(p.sub)
            setTextColor(p.text)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            gravity = Gravity.START or Gravity.TOP
            background = UiKit.shape(this@MainActivity, p.cardAlt, 14f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        test.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(test)

        // how to use
        col.addView(UiKit.section(this, p, "إزاي تستخدمه"))
        val how = UiKit.card(this, p)
        fun tip(title: String, body: String) {
            how.addView(UiKit.label(this, title, 15f, p.text, true).apply { setPadding(0, dp(8), 0, 0) })
            how.addView(UiKit.label(this, body, 13.5f, p.sub).apply { setPadding(0, dp(2), 0, 0) })
        }
        tip("الوضع الذاتي", "افتح محادثة (واتساب مثلًا) واضغط \"ذاتي\" في شريط الأدوات فوق الكيبورد مرة واحدة. الكيبورد بيختفي، والنموذج المحلي بيقرا المحادثة، يكتب الرد باسمك ويبعته، ويفضل يرد على كل رسالة جديدة طول اليوم من غير حد أقصى ومن غير ما يوقف لوحده، لحد ما تضغط \"إيقاف\". (الشاشة بتفضل صاحية وهو شغال.)")
        tip("ترجمة الشاشة", "زر \"الشاشة\" بيخفي الكيبورد ويترجم كل نص على الشاشة من أي لغة للعربي (أو أي لغة تختارها من الإعدادات). يدوي: دوس مرة وهيترجم اللي ظاهر، ولو مرّرت لحاجة جديدة دوس \"ترجم\" على الشريط الصغير تاني.")
        tip("ترجمة اللي بتكتبه", "زر \"ترجم\" بيفتح خانة كتابة صغيرة جوه الكيبورد. اكتب فيها بأي لغة (الكيبورد بيعرّف اللغة لوحده) والترجمة بتظهر لحظيًا في خانة التطبيق نفسه. دوس ✓ (زرار الإدخال) عشان تثبّتها وتكتب جملة تانية، و ⇄ بيقلب اللغتين، وسهم الرجوع بيقفل الشريط. لو الخانة فاضية، ✓ بتشتغل كزرار الإدخال العادي (إرسال).")
        tip("الصوت", "زر الميكروفون على الكيبورد بيكتب اللي بتقوله (إملاء). مفيش أوامر صوتية.")
        tip("زخرفة الكتابة", "زر \"زخرفة\" بيحوّل الكلام لخط مزخرف: تشكيل وحروف مزخرفة ومطّ وإطارات وقلوب وخطوط إنجليزي مزخرفة. كلها نص حقيقي بيظهر في أي تطبيق، مش صور. اختار الشكل فيتطبق على اللي مكتوب، أو شغّل \"زخرفة وأنا بكتب\" عشان الحروف تتزخرف وإنت بتكتب. فيه زر تراجع.")
        tip("الكتابة الملونة", "اختار لون من زرار \"المظهر\" واكتب: اللون بيتحط على الكلام كنص حقيقي. بعض الخانات بتحتفظ بالألوان وأغلب تطبيقات الشات بتمسحها وده مش في إيدي. الألعاب اللي بتفهم أكواد الألوان: من الإعدادات اختار صيغة اللعبة وفعّل زرار الإرسال الملوّن أو \"الإدخال بيبعت بلون\"، والكلام بيتبعت نص عادي بكود اللون.")
        tip("الوضع الذاتي في الألعاب", "لو الشات في لعبة (مفيهاش نص يتقري)، الوضع الذاتي بياخد صورة للشاشة والنموذج بيقرا الشات منها. أول مرة بيطلب منك تحدد مكان الشات وخانة الكتابة وزرار الإرسال على الشاشة (٣ خطوات). تقدر تعيد الضبط من زرار \"ضبط\" في الشريط الصغير. اكتب اسمك في اللعبة من الإعدادات عشان ميردّش على رسايلك. الكيبورد لازم يكون كيبورد Omega، وشغّل خدمة إمكانية الوصول.")
        tip("الألعاب اللي فيها حماية", "بعض الألعاب بتمنع تصوير الشاشة، وبعضها بيعتبر أي تطبيق بيقرا الشاشة غش. الكيبورد مش بيحاول يتخطى أي حماية ولا يخبّي نفسه: لو اللعبة منعت القراءة هيقولك ده بوضوح. واستخدامه جوه لعبة على مسؤوليتك، لأن اللعبة ممكن توقف الحساب لو شافته مخالفة.")
        col.addView(how)

        col.addView(UiKit.group(
            this, p,
            UiKit.valueRow(this, p, "الإعدادات", "فتح") { startActivity(Intent(this, SettingsActivity::class.java)) },
            UiKit.valueRow(this, p, "النموذج المحلي", Llm.statusText(this).take(24)) { startActivity(Intent(this, ModelActivity::class.java)) },
        ).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14) })

        setContentView(UiKit.screen(this, p, "Omega Keyboard", col, null))
        handlePage(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePage(intent)
    }

    private fun handlePage(i: Intent?) {
        if (i?.getStringExtra("page") == "model") {
            i.removeExtra("page")
            startActivity(Intent(this, ModelActivity::class.java))
        }
        if (i?.getBooleanExtra("hide_icon", false) == true) {
            i.removeExtra("hide_icon")
            hideFloatingButton()
        }
    }

    override fun onResume() {
        super.onResume()
        renderSteps()
        handler.postDelayed(tick, 1500)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    // ------------------------------------------------------------------ live status

    private fun imeEnabled(): Boolean = try {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.any { it.packageName == packageName }
    } catch (_: Throwable) {
        false
    }

    private fun imeSelected(): Boolean = try {
        (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "").startsWith("$packageName/")
    } catch (_: Throwable) {
        false
    }

    private fun a11yEnabled(): Boolean {
        if (ScreenAgentService.isRunning) return true
        val s = try { Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) } catch (_: Throwable) { null } ?: return false
        return s.split(':').any { it.startsWith("$packageName/") && it.endsWith("ScreenAgentService") }
    }

    /** the settings page of this keyboard's accessibility service (its shortcut, the floating button, is switched off there); the general page where a phone has no such page */
    private fun openAccessibilityPage() { ShortcutHider.openPage(this) }

    /** "اقفله لي": opens that page and presses the shortcut's switch there by itself (the service does the pressing) */
    private fun hideFloatingButton() {
        when (ShortcutHider.start(this)) {
            ShortcutHider.Start.NEED_SERVICE -> Toast.makeText(this, "فعّل خدمة إمكانية الوصول الأول (الخطوة ٣ فوق)", Toast.LENGTH_LONG).show()
            ShortcutHider.Start.BUSY -> Toast.makeText(this, "شغّال على قفله... استنى لحظة", Toast.LENGTH_SHORT).show()
            ShortcutHider.Start.OK -> Toast.makeText(this, "هفتح الإعدادات وأقفل الاختصار بنفسي — متلمسش الشاشة لحظات", Toast.LENGTH_LONG).show()
        }
    }

    /** 1: the shortcut (the floating button) is on, 0: it is off, -1: this phone does not let an app read it */
    private fun shortcutState(): Int = try {
        val targets = Settings.Secure.getString(contentResolver, "accessibility_button_targets")
        val mode = Settings.Secure.getString(contentResolver, "accessibility_button_mode")
        when {
            targets != null -> if (targets.split(':').any { it.startsWith("$packageName/") }) 1 else 0
            mode != null -> 0
            else -> -1
        }
    } catch (_: Throwable) {
        -1
    }

    private val wanted = arrayOf(Manifest.permission.RECORD_AUDIO)

    private fun permsGranted(): Boolean = wanted.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun renderSteps(force: Boolean = true) {
        ModelDownloader.adoptIfDone(this)
        val imeOn = imeEnabled()
        val imeSel = imeSelected()
        val a11y = a11yEnabled()
        val perms = permsGranted()
        val model = ModelStore.path(this) != null
        val dl = ModelDownloader.status(this)
        val shortcut = shortcutState()
        val key = "$imeOn $imeSel $a11y $perms $model ${dl.kind} ${dl.percent} ${dl.message} $shortcut ${ShortcutHider.result}"
        if (!force && key == lastKey) return
        lastKey = key

        // header
        hero.removeAllViews()
        val ready = imeOn && imeSel
        hero.addView(UiKit.label(this, if (ready) "الكيبورد شغال ✓" else "خلّينا نجهّز الكيبورد", 22f, p.text, true))
        hero.addView(UiKit.label(
            this,
            if (ready && a11y && model) "كل حاجة جاهزة: الوضع الذاتي وترجمة الشاشة والكتابة بالصوت."
            else if (ready) "الكتابة جاهزة. كمّل الخطوات اللي تحت عشان الوضع الذاتي وترجمة الشاشة."
            else "٣ خطوات بسيطة وتبدأ تكتب. الباقي اختياري.", 14f, p.sub
        ).apply { setPadding(0, dp(6), 0, 0) })

        steps.removeAllViews()

        // 1
        steps.addView(UiKit.section(this, p, "الأساسيات"))
        steps.addView(step(
            if (imeOn) 1 else 0, "١) فعّل الكيبورد", if (imeOn) "متفعّل" else "هتفتحلك قايمة لوحات المفاتيح: شغّل \"Omega Keyboard\".",
            if (imeOn) null else "تفعيل" to { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        ))
        steps.addView(step(
            if (imeSel) 1 else 0, "٢) اختاره كلوحة المفاتيح", if (imeSel) "هو اللي شغال دلوقتي" else "اختار \"Omega Keyboard\" من القايمة اللي هتظهر.",
            if (imeSel || !imeOn) null else "اختيار" to {
                try { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() } catch (_: Throwable) {}
            }
        ))

        // 3
        steps.addView(UiKit.section(this, p, "الميزات الذكية"))
        val restrictedNote = if (Build.VERSION.SDK_INT >= 33 && !a11y)
            "\nلو الخيار رمادي (أندرويد ١٣+): افتح معلومات التطبيق ← القايمة (⋮) ← \"السماح بالإعدادات المقيّدة\" وبعدين ارجع هنا." else ""
        steps.addView(step(
            if (a11y) 1 else 0, "٣) إمكانية الوصول (قراءة الشاشة)",
            if (a11y) "شغّالة. الوضع الذاتي وترجمة الشاشة جاهزين."
            else "لازم عشان الكيبورد يقرا الشاشة ويبعت الردود. دوس على \"Omega Keyboard\" وشغّله. كل ده بيحصل على تليفونك وبس.$restrictedNote",
            if (a11y) null else "تفعيل" to { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            if (!a11y && Build.VERSION.SDK_INT >= 33) "معلومات التطبيق" to {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } else null
        ))
        if (a11y) {
            val failed = ShortcutHider.result == "fail"
            if (shortcut == 0 || ShortcutHider.result == "ok" || ShortcutHider.result == "already") steps.addView(step(
                1, "الأيقونة العايمة مقفولة ✓",
                "الاختصار مقفول، فالزرار العايم مش هيظهر. لو لسه شايفه اقفل الشاشة وافتحها. الصلاحية والميزات شغّالة زي ما هي.",
                null,
            )) else steps.addView(step(
                0, "زرار بنفسجي عايم على الشاشة؟",
                (if (failed) "مقدرتش أقفله لوحدي على تليفونك. اقفله بإيدك: إمكانية الوصول ← Omega Keyboard ← «الاختصار» (Shortcut) ← اقفله.\n\n" else "") +
                    "ده مش من الكيبورد: أندرويد نفسه بيحطّه لما بتفعّل إمكانية الوصول، وأي تطبيق مبيقدرش يشيله من جوه. دوس «اقفله لي» وهفتح الإعدادات وأقفل «الاختصار» بنفسي (هتشوف الصفحة بتتحرك لوحدها). الصلاحية والميزات بتفضل شغّالة.",
                "اقفله لي" to { hideFloatingButton() },
                "افتح الصفحة بإيدي" to { openAccessibilityPage() },
            ))
        }
        steps.addView(step(
            if (perms) 1 else 0, "٤) إذن الميكروفون (للكتابة بالصوت)",
            if (perms) "تمام" else "الميكروفون بس، عشان تكتب بصوتك. اختياري.",
            if (perms) null else "السماح" to { requestPermissions(wanted, 11) }
        ))
        val openModel = { startActivity(Intent(this, ModelActivity::class.java)) }
        val pickFile = { startActivity(Intent(this, ModelActivity::class.java).putExtra("autopick", true)) }
        val downloadNow = { startActivity(Intent(this, ModelActivity::class.java).putExtra("autodownload", true)) }
        steps.addView(step(
            if (model) 1 else 0, "٥) النموذج المحلي (Gemma)",
            when {
                model -> "${ModelStore.sizeMb(this)} MB — ${Llm.statusText(this)}"
                dl.kind == ModelDownloader.Kind.PAUSED -> "التنزيل واقف: ${dl.message}"
                dl.active -> "بينزّل دلوقتي... ${dl.percent}% (بيكمل في الخلفية وبيتفعّل لوحده)"
                dl.kind == ModelDownloader.Kind.FAILED -> "التنزيل ما اتمّش: ${dl.message}"
                else -> "محتاجه للوضع الذاتي. اختار ملف النموذج اللي على تليفونك (الأساسي)، أو نزّله من هنا مباشرةً (حوالي ٢.٦ جيجا، يفضّل واي فاي). مش بيتبعت لأي حد."
            },
            when {
                model -> "إدارة" to openModel
                dl.active -> "المتابعة" to openModel
                dl.kind == ModelDownloader.Kind.FAILED -> "التفاصيل" to openModel
                else -> "اختيار ملف" to pickFile            // the file already on the phone is the main way in; one tap opens the picker
            },
            if (!model && !dl.active && dl.kind != ModelDownloader.Kind.FAILED) "تنزيل النموذج" to downloadNow else null
        ))
        steps.addView(UiKit.label(
            this, "ترجمة الشاشة والكتابة بتشتغل بدون النموذج لأغلب اللغات (محرك سريع على التليفون؛ أول مرة بتنزّل حزمة اللغة حوالي ٣٠ ميجا). النموذج مطلوب للوضع الذاتي ولباقي اللغات.",
            12.5f, p.sub
        ).apply { setPadding(dp(24), dp(8), dp(24), dp(4)) })
    }

    /** state: 1 = done, 0 = to do */
    private fun step(
        state: Int, title: String, sub: String,
        action: Pair<String, () -> Unit>?, second: Pair<String, () -> Unit>? = null,
    ): LinearLayout {
        val c = UiKit.card(this, p)
        c.addView(UiKit.statusRow(this, p, state, title, sub))
        if (action != null || second != null) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (action != null) row.addView(UiKit.button(this, p, action.first, filled = true) { action.second() })
            if (second != null) row.addView(UiKit.button(this, p, second.first, filled = false) { second.second() }.also {
                (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            })
            c.addView(row)
        }
        return c
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderSteps()
    }
}
