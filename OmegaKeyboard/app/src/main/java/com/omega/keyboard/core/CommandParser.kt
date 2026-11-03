package com.omega.keyboard.core

/** What a spoken sentence means. Anything that is not a recognised command is plain dictation. */
sealed class VoiceCommand {
    /** [name] has a glued preposition ("بـ") removed; [alt] is the untouched text (a name may genuinely start with ب) */
    data class Call(val name: String, val alt: String = name) : VoiceCommand()
    data class OpenApp(val name: String) : VoiceCommand()
    object TranslateScreen : VoiceCommand()
    object StartAutopilot : VoiceCommand()
    object StopAutopilot : VoiceCommand()
    object ClearInstructions : VoiceCommand()
    /** something to do for the autonomous mode ("قول له ...", "تابع المحادثة", "استنى") */
    data class Auto(val order: Order) : VoiceCommand()
    data class Text(val text: String) : VoiceCommand()
}

object CommandParser {
    private fun n(s: String): String = ArabicText.normalize(s).replace(Regex("[\\p{P}\\p{S}]"), " ").replace(Regex("\\s+"), " ").trim()

    // Patterns run on the normalised sentence (alef/ya/ta-marbuta merged, no diacritics, lower-case).
    // "اطلب منه ..." means "ask him ...", not "call him": the verb for calling is never followed by "منه".
    private val CALL = Regex("^(?:من فضلك |لو سمحت |يا |ممكن )?(?:كلمني|كلملي|كلم لي|كلم|كلمي|اتصل بي|اتصل لي|اتصل ب|اتصلي ب|اتصل|اطلب لي|اطلب(?!\\s+(?:منه|منها|منهم|منو)(?:\\s|$))|رن على|رن|call|phone|dial|ring)\\s+(.+)$")
    private val OPEN = Regex("^(?:من فضلك |لو سمحت |يا )?(?:افتح لي|افتحلي|افتح|شغل لي|شغللي|شغل|روح ل|open|launch|start)\\s+(.+)$")
    private val TRANSLATE_SCREEN = Regex("^(?:من فضلك )?(?:ترجم|ترجمه|ترجملي|ترجم لي)\\s*(?:لي )?(?:ال)?(?:شاشه|صفحه|الشاشه|الصفحه)|translate (?:the |this )?(?:screen|page)")
    private val START_AUTO = Regex("(?:شغل|ابدا|ابدأ|فعل|start|enable|turn on)\\s*(?:ال)?(?:وضع الذاتي|وضع ذاتي|الوضع الذاتي|auto ?pilot|auto mode|autonomous)")
    private val STOP_AUTO = Regex("(?:وقف|اوقف|أوقف|ايقاف|إيقاف|اقفل|اطفي|طفي|الغي|stop|disable|turn off)\\s*(?:ال)?(?:وضع الذاتي|وضع ذاتي|الوضع الذاتي|auto ?pilot|auto mode|autonomous)|^(?:وقف|توقف|ايقاف|stop)\$")
    private val CLEAR_INSTR = Regex("^(?:الغي|امسح|انسى|انسي|clear|reset)\\s*(?:ال)?(?:تعليمات|اوامر|الاوامر|instructions)")

    /**
     * @param autopilotRunning while autonomous mode runs, a sentence that is no command is an [Order] for it
     *        ("قول له ...", "تابع المحادثة") instead of text typed into a field.
     * @param always the microphone is open all the time (autonomous mode): other people's talk may be heard, so
     *        only clear orders count, and calls / opening apps / translation need the wake word "أوميجا".
     */
    fun parse(spoken: String, autopilotRunning: Boolean, commandsEnabled: Boolean = true, always: Boolean = false): VoiceCommand {
        val raw0 = spoken.trim()
        if (raw0.isEmpty()) return VoiceCommand.Text("")
        val (raw, addressed) = OrderParser.stripWake(raw0)
        val s = n(raw)
        val general = commandsEnabled && (!always || addressed)          // calls, apps, translation, starting the mode

        // the controls of autonomous mode itself work whenever it runs
        if ((commandsEnabled || autopilotRunning) && STOP_AUTO.containsMatchIn(s)) return VoiceCommand.StopAutopilot
        if (autopilotRunning && CLEAR_INSTR.containsMatchIn(s)) return VoiceCommand.ClearInstructions
        if (general) {
            if (START_AUTO.containsMatchIn(s)) return VoiceCommand.StartAutopilot
            if (TRANSLATE_SCREEN.containsMatchIn(s)) return VoiceCommand.TranslateScreen
            CALL.find(s)?.let { m ->
                val whole = cleanName(m.groupValues[1])
                val verb = s.substringBefore(' ')
                // "اتصل بأحمد": the preposition is glued to the name
                val stripped = if (verb.startsWith("اتصل") && whole.length > 2 && whole.startsWith("ب")) whole.substring(1) else whole
                return VoiceCommand.Call(stripped, whole)
            }
            // while the AI is chatting only "call X" stays a command; other sentences are orders for it
            if (!autopilotRunning) {
                OPEN.find(s)?.let { return VoiceCommand.OpenApp(cleanName(it.groupValues[1])) }
            }
        }
        if (autopilotRunning) return VoiceCommand.Auto(OrderParser.parse(raw.ifBlank { raw0 }, always, addressed))
        return VoiceCommand.Text(raw0)
    }

    private fun cleanName(s: String): String =
        s.replace(Regex("^(?:ال)?(?:اسم|رقم|صاحبي|صديقي|الاستاذ|استاذ|دكتور|الدكتور|م/|ال)\\s+"), "")
            .replace(Regex("\\s+(?:دلوقتي|حالا|الان|من فضلك|لو سمحت|please|now)$"), "")
            .trim()
}

/** Fuzzy name matching for contacts and installed apps (Arabic and Latin). */
object NameMatcher {
    private val ALIASES = mapOf(
        "واتس" to "whatsapp", "واتساب" to "whatsapp", "واتس اب" to "whatsapp", "وتساب" to "whatsapp",
        "تيليجرام" to "telegram", "تليجرام" to "telegram", "تلجرام" to "telegram", "تيليغرام" to "telegram",
        "فيسبوك" to "facebook", "فيس بوك" to "facebook", "فيس" to "facebook",
        "ماسنجر" to "messenger", "ميسنجر" to "messenger",
        "انستجرام" to "instagram", "انستا" to "instagram", "انستغرام" to "instagram",
        "يوتيوب" to "youtube", "جوجل" to "google", "كروم" to "chrome", "جيميل" to "gmail", "خرائط" to "maps",
        "كاميرا" to "camera", "الكاميرا" to "camera", "الاعدادات" to "settings", "اعدادات" to "settings", "الضبط" to "settings",
        "الساعه" to "clock", "الساعة" to "clock", "الحاسبه" to "calculator", "الالة الحاسبة" to "calculator",
        "تيك توك" to "tiktok", "تويتر" to "twitter", "سناب" to "snapchat", "سناب شات" to "snapchat", "سيجنال" to "signal",
        "الهاتف" to "phone", "الرسائل" to "messages", "المعرض" to "gallery", "الصور" to "photos",
    )

    fun canonical(query: String): String {
        val q = ArabicText.normalize(query)
        return ALIASES[q] ?: ALIASES.entries.firstOrNull { q == ArabicText.normalize(it.key) }?.value ?: q
    }

    /** 0..100, higher is better. */
    fun score(query: String, candidate: String): Int {
        val q = canonical(query)
        val c = ArabicText.normalize(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0
        if (q == c) return 100
        if (c.startsWith(q) || c.split(' ').any { it == q }) return 90
        val qt = q.split(' ').filter { it.isNotEmpty() }
        val ct = c.split(' ').filter { it.isNotEmpty() }
        if (qt.isNotEmpty() && qt.all { t -> ct.any { it.startsWith(t) } }) return 80
        if (c.contains(q)) return 70
        val d = ArabicText.editDistance(q, c, 3)
        if (d <= 1) return 65
        if (d <= 2 && q.length >= 5) return 55
        // best single-token match
        var best = 0
        for (t in qt) for (u in ct) {
            val dd = ArabicText.editDistance(t, u, 2)
            if (dd <= 1 && t.length >= 3) best = maxOf(best, 60)
        }
        return best
    }

    fun <T> best(query: String, items: List<T>, label: (T) -> String, min: Int = 55): T? {
        var bestItem: T? = null
        var bestScore = 0
        for (it in items) {
            val s = score(query, label(it))
            if (s > bestScore) { bestScore = s; bestItem = it }
        }
        return if (bestScore >= min) bestItem else null
    }
}
