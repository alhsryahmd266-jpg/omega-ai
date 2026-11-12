package com.omega.keyboard.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.omega.keyboard.AppLog
import com.omega.keyboard.Prefs
import com.omega.keyboard.ai.Llm
import com.omega.keyboard.ai.Translator
import com.omega.keyboard.core.ColorCodes
import com.omega.keyboard.core.LlmPrompts
import com.omega.keyboard.core.Languages
import com.omega.keyboard.core.Palette
import com.omega.keyboard.ime.Themes

/** Every setting, grouped. Changes apply immediately (the keyboard reads them live). */
class SettingsActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var p: Pal
    private lateinit var holder: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        p = UiKit.pal(this)
        holder = UiKit.column(this)
        setContentView(UiKit.screen(this, p, "الإعدادات", holder) { finish() })
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::holder.isInitialized) render()
    }

    private fun render() {
        // keep the scroll position when a value changes
        val sv = holder.parent as? ScrollView
        val y = sv?.scrollY ?: 0
        holder.removeAllViews()
        build()
        sv?.post { sv.scrollTo(0, y) }
    }

    private fun choose(title: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
        val labels = options.map { it.second }.toTypedArray()
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels, options.indexOfFirst { it.first == current }) { d, i ->
                onPick(options[i].first)
                d.dismiss()
                render()
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun chooseLanguage(title: String, current: String, onPick: (String) -> Unit) {
        choose(title, Languages.all.map { it.code to "${it.arabic}  ·  ${it.native}" }, current, onPick)
    }

    private fun askText(title: String, current: String, hint: String, onDone: (String) -> Unit) {
        val et = EditText(this).apply {
            setText(current)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("حفظ") { _, _ -> onDone(et.text.toString().trim()); render() }
            .setNeutralButton("الافتراضي") { _, _ -> onDone(LlmPrompts.DEFAULT_PERSONA); render() }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun askName(title: String, current: String, hint: String, onDone: (String) -> Unit) {
        val et = EditText(this).apply {
            setText(current)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("حفظ") { _, _ -> onDone(et.text.toString().trim()); render() }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun sw(title: String, sub: String?, value: Boolean, set: (Boolean) -> Unit) = UiKit.switchRow(this, p, title, sub, value, set)

    private fun build() {
        val h = holder

        // ---------------------------------------------------------------- look
        h.addView(UiKit.section(this, p, "الشكل"))
        // the colour of the text you type: none, one of the named colours, or the rainbow (the full colour picker is in the keyboard)
        val textChoices = listOf("none" to "بدون (لون الخانة)") + Palette.typed.map { it.color.toString() to it.name } + ("rainbow" to "قوس قزح")
        val textKey = when {
            prefs.textRainbow -> "rainbow"
            prefs.textColor == 0 -> "none"
            else -> prefs.textColor.toString()
        }
        h.addView(UiKit.group(
            this, p,
            UiKit.valueRow(this, p, "المظهر", Themes.choices.firstOrNull { it.first == prefs.theme }?.second ?: "تلقائي") {
                choose("المظهر", Themes.choices, prefs.theme) { prefs.theme = it; Themes.resetCustom(prefs) }
            },
            UiKit.valueRow(this, p, "لون الكتابة", textChoices.firstOrNull { it.first == textKey }?.second ?: "لون مخصص") {
                choose("لون الكتابة", textChoices, textKey) { k ->
                    when (k) {
                        "none" -> { prefs.textColor = 0; prefs.textRainbow = false }
                        "rainbow" -> prefs.textRainbow = true
                        else -> { prefs.textColor = k.toInt(); prefs.textRainbow = false }
                    }
                }
            },
            UiKit.seekRow(this, p, "ارتفاع الكيبورد", 60, 170, prefs.heightPct, { "$it%" }) { prefs.heightPct = it },
            UiKit.seekRow(this, p, "حجم الحروف", 80, 140, prefs.fontPct, { "$it%" }) { prefs.fontPct = it },
            UiKit.seekRow(this, p, "عرض الكيبورد", 60, 100, prefs.widthPct, { "$it%" }) { prefs.widthPct = it },
            sw("صف الأرقام", "صف أرقام فوق الحروف", prefs.numberRow) { prefs.numberRow = it },
            sw("أرقام هندية في العربي", "٠١٢٣ بدل 0123", prefs.indicDigits) { prefs.indicDigits = it },
            sw("معاينة الحرف", "تكبير الحرف فوق الزرار", prefs.keyPreview) { prefs.keyPreview = it },
            sw("اهتزاز", null, prefs.haptic) { prefs.haptic = it },
            sw("صوت الضغط", null, prefs.sound) { prefs.sound = it },
        ))

        // ---------------------------------------------------------------- typing
        h.addView(UiKit.section(this, p, "الكتابة"))
        val langChoices = listOf("ar,en" to "العربية ثم English", "en,ar" to "English ثم العربية", "ar" to "العربية فقط", "en" to "English فقط")
        h.addView(UiKit.group(
            this, p,
            UiKit.valueRow(this, p, "لغات الكيبورد", langChoices.firstOrNull { it.first == prefs.langs }?.second ?: "العربية ثم English") {
                choose("لغات الكيبورد", langChoices, prefs.langs) { prefs.langs = it }
            },
            sw("حرف كبير تلقائي", "أول الجملة في الإنجليزي", prefs.autoCap) { prefs.autoCap = it },
            sw("الاقتراحات", "كلمات مقترحة فوق الكيبورد", prefs.suggestions) { prefs.suggestions = it },
            sw("التصحيح التلقائي", "في الإنجليزي بس، وبيسيب العامية والحروف اللاتينية للعربي زي ما هي. اضغط مسح فورًا بعد التصحيح عشان ترجّع كلمتك.", prefs.autoCorrect) { prefs.autoCorrect = it },
            sw("نقطة بالمسافتين", "مسافتين ورا بعض = نقطة ومسافة", prefs.doubleSpacePeriod) { prefs.doubleSpacePeriod = it },
        ))

        // ---------------------------------------------------------------- coloured sending
        h.addView(UiKit.section(this, p, "إرسال الكتابة الملونة"))
        val picStyles = listOf("sticker" to "ملصق شفاف (بدون إطار)", "clean" to "كارت عادي (كلامك بلونه)", "neon" to "نيون مضيء")
        val codeChoices = ColorCodes.Format.values().map { it.id to it.label }
        h.addView(UiKit.group(
            this, p,
            sw("زرار الإرسال يبعت ملوّن", "في خانات الدردشة اللي بتقبل صور (واتساب، تليجرام، ماسنجر): الكلام يتحوّل لصورة بلونك ويتبعت. الدايرة الملونة فوق الكيبورد بتعمل نفس الشيء.", prefs.colourOnSend) { prefs.colourOnSend = it },
            sw("إرسال تلقائي", "يدوس \"إرسال\" لوحده في معاينة الصورة (محتاج تفعيل خدمة إمكانية الوصول للكيبورد)", prefs.picAutoSend) { prefs.picAutoSend = it },
            UiKit.valueRow(
                this, p, "شكل الصورة", picStyles.firstOrNull { it.first == prefs.picStyle }?.second ?: "ملصق شفاف (بدون إطار)",
                "في واتساب الكلام بيتبعت ملصق شفاف من غير إطار، وده أقرب حاجة لنص ملوّن. باقي التطبيقات بتاخد الكارت"
            ) {
                choose("شكل الصورة الملونة", picStyles, prefs.picStyle) { prefs.picStyle = it }
            },
            UiKit.valueRow(
                this, p, "ألوان الألعاب", codeChoices.firstOrNull { it.first == prefs.colorCodes }?.second ?: "بدون كود",
                "للألعاب والتطبيقات اللي مبتقبلش صور: الكلام بيتبعت بكود ألوان تفهمه اللعبة. اختار الصيغة بتاعتها (Free Fire مثلًا)"
            ) {
                choose("كود ألوان الألعاب", codeChoices, prefs.colorCodes) { prefs.colorCodes = it }
            },
        ))

        // ---------------------------------------------------------------- translation
        h.addView(UiKit.section(this, p, "الترجمة"))
        val engines = listOf(
            "auto" to "تلقائي (سريع ثم النموذج)",
            "mlkit" to "سريع فقط (على التليفون)",
            "gemma" to "النموذج المحلي فقط",
        )
        h.addView(UiKit.group(
            this, p,
            UiKit.valueRow(this, p, "ترجمة الشاشة إلى", Languages.arabicName(prefs.screenTarget)) {
                chooseLanguage("ترجمة الشاشة إلى", prefs.screenTarget) { prefs.screenTarget = it; Translator.clearCache() }
            },
            UiKit.valueRow(this, p, "ترجمة الكتابة إلى", Languages.arabicName(prefs.typedTarget)) {
                chooseLanguage("ترجمة اللي بتكتبه إلى", prefs.typedTarget) { prefs.typedTarget = it }
            },
            UiKit.valueRow(this, p, "محرك الترجمة", engines.firstOrNull { it.first == prefs.engineMode }?.second ?: "تلقائي", "السريع بيحتاج حزمة لغة صغيرة تتنزّل مرة واحدة") {
                choose("محرك الترجمة", engines, prefs.engineMode) { prefs.engineMode = it; Translator.clearCache() }
            },
            sw("ترجمة عند الإرسال", "لما شريط \"ترجم\" يكون مفتوح، زرار الإدخال بيترجم الأول وبعدين يبعت", prefs.translateOnSend) { prefs.translateOnSend = it },
        ))

        // ---------------------------------------------------------------- autonomous mode
        h.addView(UiKit.section(this, p, "الوضع الذاتي"))
        val sides = listOf("auto" to "تلقائي (بيتعلّم لوحده)", "right" to "اليمين", "left" to "الشمال")
        h.addView(UiKit.group(
            this, p,
            UiKit.valueRow(this, p, "أسلوبي في الرد", prefs.apPersona.take(22) + if (prefs.apPersona.length > 22) "…" else "", "بيتبعه النموذج وهو بيكتب بدالك") {
                askText("أسلوبي في الرد", prefs.apPersona, "مثلًا: عفوي وبهزّر مع صحابي، وبرد على كل سؤال بشكل كامل") { prefs.apPersona = it.ifBlank { LlmPrompts.DEFAULT_PERSONA } }
            },
            UiKit.seekRow(this, p, "أقل وقت قبل الرد", 0, 30, prefs.apDelayMinSec, { "$it ث" }) {
                prefs.apDelayMinSec = it
                if (prefs.apDelayMaxSec < it) prefs.apDelayMaxSec = it
            },
            UiKit.seekRow(this, p, "أكتر وقت قبل الرد", 0, 60, prefs.apDelayMaxSec, { "$it ث" }) {
                prefs.apDelayMaxSec = it
                if (prefs.apDelayMinSec > it) prefs.apDelayMinSec = it
            },
            sw("إرسال تلقائي", "لو مقفول: النموذج بيكتب الرد في الخانة وإنت اللي تبعته", prefs.apAutoSend) { prefs.apAutoSend = it },
            sw("تفكير قبل الرد (تجريبي)", "النموذج بيفكّر الأول وبعدين يكتب: ردود أذكى لكن أبطأ بكتير. لو الردود بقت بتفشل قفّله", prefs.apThink) { prefs.apThink = it },
            UiKit.valueRow(this, p, "رسايلي بتظهر على", sides.firstOrNull { it.first == prefs.apSide }?.second ?: "تلقائي",
                "سيبها تلقائي. غيّرها بس لو بيرد على رسايلك إنت أو مش بيرد خالص") {
                choose("رسايلي بتظهر على", sides, prefs.apSide) { prefs.apSide = it }
            },
            sw("الردود بلون كتابتك", "لو اخترت لون كتابة: الرد يتبعت ملوّن (ملصق شفاف في واتساب، وكود ألوان في الألعاب لو اخترت صيغتها)", prefs.apColour) { prefs.apColour = it },
        ))

        // ---------------------------------------------------------------- games
        h.addView(UiKit.section(this, p, "الألعاب"))
        val visions = listOf("auto" to "تلقائي (يقرا الشاشة بالنموذج)", "off" to "مقفول")
        h.addView(UiKit.group(
            this, p,
            UiKit.valueRow(this, p, "قراية شات الألعاب", visions.firstOrNull { it.first == prefs.apVision }?.second ?: "تلقائي",
                "الألعاب بترسم شاشتها بنفسها فمفيش نص يتقري. الوضع الذاتي بياخد صورة لشات اللعبة والنموذج بيقراها (أبطأ من التطبيقات العادية). محتاج أندرويد ١١ أو أحدث") {
                choose("قراية شات الألعاب", visions, prefs.apVision) { prefs.apVision = it }
            },
            UiKit.valueRow(this, p, "اسمك في اللعبة", prefs.apGameName.ifBlank { "غير محدد" },
                "عشان الوضع الذاتي ميردّش على رسايلك إنت") {
                askName("اسمك في اللعبة", prefs.apGameName, "الاسم زي ما بيظهر في الشات") { prefs.apGameName = it }
            },
            UiKit.valueRow(this, p, "الألعاب اللي اتعلّمتها", prefs.gameProfileCount().toString(),
                "مكان الشات وخانة الكتابة لكل لعبة. دوس هنا لمسحها كلها (الضبط بيتعمل من زرار «ضبط» في شريط الوضع الذاتي)") {
                AlertDialog.Builder(this).setTitle("مسح الألعاب المتعلّمة؟")
                    .setPositiveButton("امسح") { _, _ -> prefs.clearGameProfiles(); render() }
                    .setNegativeButton("إلغاء", null).show()
            },
        ))

        // ---------------------------------------------------------------- voice
        h.addView(UiKit.section(this, p, "الصوت"))
        val voiceLangs = listOf(
            "auto" to "تلقائي (حسب لغة الكيبورد)", "ar-EG" to "العربية (مصر)", "ar-SA" to "العربية (السعودية)", "ar-AE" to "العربية (الإمارات)",
            "en-US" to "English (US)", "en-GB" to "English (UK)", "fr-FR" to "Français", "es-ES" to "Español", "de-DE" to "Deutsch",
            "tr-TR" to "Türkçe", "ru-RU" to "Русский",
        )
        h.addView(UiKit.group(
            this, p,
            UiKit.valueRow(this, p, "لغة الكلام", voiceLangs.firstOrNull { it.first == prefs.voiceLang }?.second ?: prefs.voiceLang) {
                choose("لغة الكلام", voiceLangs, prefs.voiceLang) { prefs.voiceLang = it }
            },
            sw("تعرّف على الكلام بدون نت", "محتاج تحمّل لغة الكلام من إعدادات جوجل", prefs.voiceOffline) { prefs.voiceOffline = it },
        ))

        // ---------------------------------------------------------------- model
        h.addView(UiKit.section(this, p, "النموذج المحلي"))
        val specs = listOf("auto" to "تلقائي", "on" to "شغّال", "off" to "مقفول")
        val idle = listOf("2" to "بعد ٢ دقيقة", "5" to "بعد ٥ دقايق", "10" to "بعد ١٠ دقايق", "30" to "بعد نص ساعة", "0" to "أبدًا (يفضل في الذاكرة)")
        h.addView(UiKit.group(
            this, p,
            UiKit.valueRow(this, p, "إدارة النموذج", Llm.statusText(this).take(20)) { startActivity(Intent(this, ModelActivity::class.java)) },
            UiKit.valueRow(this, p, "تفريغه من الذاكرة", idle.firstOrNull { it.first == prefs.idleMinutes.toString() }?.second ?: "بعد ١٠ دقايق", "لما ما يكونش بيستعمل") {
                choose("تفريغ النموذج من الذاكرة", idle, prefs.idleMinutes.toString()) { prefs.idleMinutes = it.toInt() }
            },
            UiKit.valueRow(this, p, "تسريع الإجابة", specs.firstOrNull { it.first == prefs.specDecode }?.second ?: "تلقائي",
                "بيخلّي النموذج يكتب أسرع بنفس الكلام ونفس الذكاء. لو حصلت مشكلة في التحميل اقفله") {
                choose("تسريع الإجابة", specs, prefs.specDecode) { prefs.specDecode = it }
            },
        ))

        // ---------------------------------------------------------------- about
        h.addView(UiKit.section(this, p, "عن التطبيق"))
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }
        h.addView(UiKit.group(
            this, p,
            UiKit.valueRow(this, p, "النسخة", version) {},
            UiKit.valueRow(this, p, "خطوات التفعيل", "فتح") { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)) },
            UiKit.valueRow(this, p, "سجل الأخطاء", "عرض", "لو حاجة مش شغالة ابعتلي الصفحة دي") { showLog() },
        ))
    }

    private fun showLog() {
        val text = AppLog.dump(200).ifBlank { "السجل فاضي." }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11.5f
            setTextIsSelectable(true)
            setTextColor(p.text)
            textDirection = android.view.View.TEXT_DIRECTION_LTR
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val sv = ScrollView(this).apply {
            addView(tv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this).setTitle("سجل الأخطاء").setView(sv)
            .setPositiveButton("نسخ") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("omega-log", text))
                Toast.makeText(this, "اتنسخ", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("مسح") { _, _ -> AppLog.clear() }
            .setNegativeButton("إغلاق", null).show()
    }
}
