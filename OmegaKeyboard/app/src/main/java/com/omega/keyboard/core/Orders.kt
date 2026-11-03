package com.omega.keyboard.core

/** What the user wants the autonomous mode to do, understood from a spoken sentence. */
sealed class Order {
    /** send exactly this message now ("قول له إني جاي بعد ساعة" -> "أنا جاي بعد ساعة") */
    data class Say(val text: String) : Order()

    /** write a message that does what the instruction says (needs the model) and send it now ("اسأله هو جاي ولا لأ") */
    data class Compose(val instruction: String) : Order()

    /** answer the latest message now, even if it was answered already or the last message looks like mine ("رد عليه") */
    object Reply : Order()

    /** keep following the conversation: carry on when it was paused and look at the chat right now ("تابع المحادثة") */
    object Follow : Order()

    /** stop answering for a while but stay on ("استنى") */
    object Pause : Order()

    /** a rule for every later reply ("خليك رسمي"); [replyNow]: and answer the latest message with it ("رد بأسلوب رسمي") */
    data class Style(val rule: String, val replyNow: Boolean = false) : Order()

    /** an order that lacks its content ("قول له" and nothing more) */
    data class Incomplete(val hint: String) : Order()

    /** talk that is not meant for us (a TV, a person next to the phone): it must never be typed or sent */
    data class NotAnOrder(val heard: String) : Order()
}

/**
 * Understands a sentence spoken while autonomous mode runs. The user talks to the app, not through it:
 * "تابع المحادثة" is something to DO, "قول له ..." names the text to send, and nothing is ever sent just because
 * it was said.
 *
 *  - [always] = true: the microphone is always open, so only clear orders count; everything else is ignored
 *    (unless the sentence starts with the wake word "أوميجا").
 *  - [always] = false: the user spoke on purpose, so a sentence that is no known order is kept as a note for the replies.
 */
object OrderParser {
    private fun n(s: String): String =
        ArabicText.normalize(s).replace(Regex("[\\p{P}\\p{S}]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun words(vararg w: String): Set<String> = w.map { n(it) }.toSet()

    private val WAKE_WORDS = words("اوميجا", "اوميغا", "اوميقا", "اومجا", "اوميجه", "omega")
    private val FILLER = words("طيب", "يلا", "ممكن", "فضلك", "بقي", "بقى", "الان", "يا", "باشا", "عم", "دلوقتي", "hey", "ok", "okay", "please")

    private val PAUSE_HEAD = words("استنى", "استنوا", "اصبر", "اسكت", "pause", "wait", "hold")
    private val PAUSE_PHRASE = Regex("^(?:وقف|وقفي|ايقاف|اوقف)\\s+(?:ال)?(?:رد|ردود|ردي|رسايل|رسائل|متابعه|مؤقتا|شويه)")
    private val FOLLOW_HEAD = words("تابع", "تابعي", "كمل", "كملي", "اكمل", "استمر", "واصل", "follow", "continue", "resume")
    private val FOLLOW_PHRASE = Regex("^(?:(?:ارجع|رجع|شغل|فعل|كمل)\\s+(?:ال)?(?:رد|ردود|ترد|متابعه)|(?:خليك|فضل|ابقي|استمر)\\s+(?:معاه|معاها|معاهم|في الرد|في المحادثه|علي الرد))")
    private val CHAT_WORDS = words(
        "المحادثه", "محادثه", "الشات", "شات", "الرسايل", "الرسائل", "الكلام", "معاه", "معاها", "معاهم", "الرد", "الردود", "رد", "ردود",
        "للرد", "الحوار", "الدردشه", "conversation", "chat",
    )

    private val REPLY_HEAD = words("رد", "ردي", "ارد", "جاوب", "جاوبه", "جاوبها", "جاوبي", "اجب", "reply", "respond", "answer")
    private val REPLY_FILLER = words(
        "عليه", "عليها", "عليهم", "علي", "ع", "الرساله", "الرسايل", "رساله", "الشات", "المحادثه", "الكلام", "اللي", "كتبه", "كتبها",
        "بعته", "قاله", "دلوقتي", "تاني", "كمان", "برضو", "بسرعه", "الان", "to", "him", "her", "them", "it", "the", "message", "chat", "now", "again",
    )

    private val SAY_ATTACHED = words(
        "قوله", "قولها", "قولهم", "قوللو", "قوللها", "قوللهم", "قللو", "قللها", "قللهم", "قله", "قلها", "قلهم", "قولو", "قولوا", "ابعتله", "ابعتلها", "ابعتلهم", "ابعتلو",
        "اكتبله", "اكتبلها", "اكتبلهم", "اكتبلو", "ارسله", "ارسلها", "ارسلهم", "بلغه", "بلغها", "بلغهم", "ابلغه", "ابلغها", "ابلغهم",
        "خبره", "خبرها", "خبرهم", "عرفه", "عرفها", "عرفهم", "فهمه", "فهمها", "فهمهم",
    )
    private val SAY_BASE = words("قول", "قل", "ابعت", "اكتب", "ارسل", "بلغ", "ابلغ", "خبر", "عرف", "فهم")
    private val SAY_BARE_OK = words("قول", "قل", "ابعت", "اكتب", "ارسل")          // "قول اهلا" works without "له"
    private val PRONOUN = words("له", "لها", "لهم", "لو", "ليه", "ليها", "ليهم", "لهن")
    private val TO_ME = words("لي", "لنا", "يا")
    private val ENGLISH_SAY = words("tell", "say", "send", "write", "text")
    private val ENGLISH_TO = words("him", "her", "them", "he", "she")

    private val COMPOSE_ATTACHED = words(
        "اسأله", "اسألها", "اسألهم", "اسالو", "اشكره", "اشكرها", "اشكرهم", "هنيه", "هنيها", "هنيهم", "عزيه", "عزيها", "ذكره", "ذكرها",
        "وافقه", "ارفضله", "ارفضلها", "اقنعه", "اقنعها", "طمنه", "طمنها", "بشره", "كلمه", "ناقشه", "ناقشها", "اعتذرله", "اعتذرلها",
    )
    private val COMPOSE_BARE = words("اسأل", "اسال", "اعتذر", "اعتذري", "رحب", "سلم", "ارفض", "ask", "thank", "apologize", "apologise", "greet", "remind", "congratulate")

    private val STYLE_STRONG_START = Regex("^(?:من دلوقتي|من الان|من هنا ورايح|بعد كده|دايما|علطول|لحد ما اقولك|from now on|always|keep )")
    private val STYLE_HEAD = words("خليك", "خليكي", "خلي", "كن", "كوني", "ابقي", "be")
    private val BAN_START = Regex(
        "^(?:بلاش|متقولش|ماتقولش|متكتبش|ماتكتبش|متذكرش|ماتذكرش|متجاوبش|ماتجاوبش|ممنوع|اوعي|لا تقل|لا تكتب|لا تذكر|don t|dont|never|do not)(?:\\s|$)"
    )
    private val STYLE_WEAK = Regex(
        "(?:باسلوب|بطريقه|بشكل|بلهجه|باللهجه|بالانجليزي|بالانجليزيه|بالعربي|بالفصحي|بالمصري|بالعاميه|رسمي|مختصر|مؤدب|ودود|بسرعه|بهدوء|بالتفصيل|قصير|طويل|formal|casual|short|polite|friendly)"
    )

    /** the words that name a style outright ("بأسلوب رسمي"): clear enough for an open microphone, unlike "بسرعه" */
    private val STYLE_EXPLICIT = Regex("(?:باسلوب|بطريقه|بلهجه|باللهجه|بشكل)")

    /** "إني" -> "أنا": the message goes to the other person, so what I said about me is told in the first person */
    private val ADDRESSED_PRON = mapOf(
        "اني" to "أنا", "انني" to "أنا", "باني" to "أنا", "بانني" to "أنا",
        "انك" to "إنت", "بانك" to "إنت", "انكم" to "إنتو", "بانكم" to "إنتو",
        "انه" to "هو", "بانه" to "هو", "انو" to "هو", "بانو" to "هو",
        "انها" to "هي", "بانها" to "هي",
        "اننا" to "إحنا", "باننا" to "إحنا",
        "انهم" to "هم", "بانهم" to "هم",
    )
    private val COMPLEMENTIZER = words("ان", "بان", "that")
    private val MESSAGE_NOUN = words("رساله", "مسج", "ماسج", "ميسج", "مسدج", "message", "text")
    private val SAYING = words("بتقول", "بيقول", "تقول", "فيها", "مضمونها", "بنص", "بالنص", "saying")
    private val PAYLOAD_FILLER = words("بقي", "بقى")

    private class Tok(val raw: String, val norm: String)

    private fun tokens(s: String): List<Tok> =
        s.trim().split(Regex("\\s+")).mapNotNull { r -> n(r).takeIf { it.isNotEmpty() }?.let { Tok(r, it) } }

    private fun joinRaw(t: List<Tok>) = t.joinToString(" ") { it.raw }.trim().trim('،', ',', ':', '.', '!', '؟', '?').trim()

    /** Leading polite words and the wake word are dropped. */
    private fun lead(all: List<Tok>, wakeIn: Boolean): Pair<List<Tok>, Boolean> {
        var t = all
        var wake = wakeIn
        var guard = 0
        while (t.size > 1 && guard++ < 8) {
            val h = t[0].norm
            when {
                h in WAKE_WORDS -> { wake = true; t = t.drop(1) }
                h in FILLER -> t = t.drop(1)
                (h == "من" && t[1].norm == "فضلك") || (h == "لو" && t[1].norm == "سمحت") -> t = t.drop(2)
                else -> break
            }
        }
        if (t.size == 1 && t[0].norm in WAKE_WORDS) return emptyList<Tok>() to true
        return t to wake
    }

    /** The sentence without a leading wake word ("أوميجا ..."), and whether it had one. */
    fun stripWake(spoken: String): Pair<String, Boolean> {
        val ws = spoken.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var i = 0
        while (i < ws.size && n(ws[i]) in FILLER) i++
        if (i < ws.size && n(ws[i]) in WAKE_WORDS) return ws.drop(i + 1).joinToString(" ") to true
        return spoken.trim() to false
    }

    /** Words after a "tell him" verb, turned into the message to send ("إني جاي" -> "أنا جاي"). Empty when nothing is left. */
    private fun payload(rest: List<Tok>): String {
        var t = rest
        var guard = 0
        while (t.isNotEmpty() && guard++ < 3 && (t[0].norm in MESSAGE_NOUN || t[0].norm in SAYING || t[0].norm in PAYLOAD_FILLER)) t = t.drop(1)
        if (t.isEmpty()) return ""
        if (t[0].norm in COMPLEMENTIZER && t.size > 1 && t[1].norm != "شاء") t = t.drop(1)     // "إن شاء الله" keeps its "إن"
        val fixed = t.map { tok ->
            val own = ADDRESSED_PRON[tok.norm]
            val joined = if (tok.norm.startsWith("و")) ADDRESSED_PRON[tok.norm.drop(1)] else null
            when {
                own != null -> Tok(own, own)
                joined != null -> Tok("و$joined", "و$joined")
                else -> tok
            }
        }
        return joinRaw(fixed)
    }

    /** "قول له X" and friends -> X ("" when X is missing); null when the sentence is not of that kind. */
    private fun sayPayload(t: List<Tok>, allowBare: Boolean): String? {
        val head = t[0].norm
        val rest = t.drop(1)
        if (head in SAY_ATTACHED) return payload(rest)
        if (head in SAY_BASE) {
            if (rest.isNotEmpty() && rest[0].norm in PRONOUN) return payload(rest.drop(1))
            if (rest.isNotEmpty() && rest[0].norm in TO_ME) return null          // "قول لي" = tell ME
            if (allowBare && head in SAY_BARE_OK) return payload(rest)
            return null
        }
        if (head in ENGLISH_SAY) {
            if (rest.isNotEmpty() && rest[0].norm in ENGLISH_TO) return payload(rest.drop(1))
            if (allowBare && head == "say") return payload(rest)
            return null
        }
        return null
    }

    fun parse(spoken: String, always: Boolean = false, addressed: Boolean = false): Order {
        val (clean, hadWake) = stripWake(spoken)
        val raw = clean.ifBlank { spoken.trim() }                  // what is kept / sent never carries the wake word
        val (t, wake) = lead(tokens(raw), addressed || hadWake)
        if (t.isEmpty()) return Order.NotAnOrder(raw)
        val head = t[0].norm
        val rest = t.drop(1)
        val sentence = t.joinToString(" ") { it.norm }
        val strict = always && !wake          // the microphone may hear other people: only clear orders count

        // ---- stop answering for a while
        if (head in PAUSE_HEAD && (!strict || t.size <= 3)) return Order.Pause
        if (PAUSE_PHRASE.containsMatchIn(sentence) && (!strict || t.size <= 4)) return Order.Pause
        if (Regex("^(?:بلاش|ما|م)\\s*(?:ترد|تردي|تردش)(?:\\s|$)").containsMatchIn(sentence)) {
            val aboutSomething = Regex("(?:ترد|تردش)\\s+(?:علي|ع)\\s+\\S").containsMatchIn(sentence)
            if (!aboutSomething && (!strict || t.size <= 4)) return Order.Pause
        }

        // ---- carry on / follow the conversation
        if (head in FOLLOW_HEAD && t.size <= 6 && (!strict || t.size == 1 || head == n("تابع") || rest.any { it.norm in CHAT_WORDS })) return Order.Follow
        if (FOLLOW_PHRASE.containsMatchIn(sentence) && t.size <= 6) return Order.Follow

        // ---- answer now: "رد عليه", "رد بإني مشغول", "رد بأسلوب رسمي"
        if (head in REPLY_HEAD) {
            val left = rest.filter { it.norm !in REPLY_FILLER }
            if (left.isEmpty()) return Order.Reply
            val next = left[0].norm
            if (next in ADDRESSED_PRON.keys || next in COMPLEMENTIZER) {
                val p = payload(left)
                return if (p.isBlank()) Order.Reply else Order.Say(p)
            }
            if (STYLE_WEAK.containsMatchIn(sentence) && (!strict || STYLE_EXPLICIT.containsMatchIn(sentence))) return Order.Style(raw, replyNow = true)
            return if (strict) Order.NotAnOrder(raw) else Order.Compose(raw)
        }
        // "اتكلم بالمصري", "اتكلم معاه بهدوء"
        if ((head == n("اتكلم") || head == n("اتكلمي")) && (rest.isEmpty() || rest.any { it.norm.startsWith("ب") } || STYLE_WEAK.containsMatchIn(sentence))) {
            return Order.Style(raw)
        }

        // ---- say this: "قول له X", "قوله X", "ابعتله X", "tell him X"
        sayPayload(t, allowBare = !strict)?.let { p ->
            return if (p.isBlank()) Order.Incomplete("قول لي أقوله إيه. مثال: قول له إني جاي بعد ساعة") else Order.Say(p)
        }
        if (sentence.startsWith("let him know") || sentence.startsWith("let her know") || sentence.startsWith("let them know")) {
            val p = payload(t.drop(3))
            return if (p.isBlank()) Order.Incomplete("قول لي أقوله إيه") else Order.Say(p)
        }

        // ---- ask / thank / apologise ...: the model words it
        val composeStrong = head in COMPOSE_ATTACHED || (head in COMPOSE_BARE && rest.firstOrNull()?.norm in PRONOUN)
        if (composeStrong || (!strict && head in COMPOSE_BARE)) return Order.Compose(raw)
        if (head == n("اطلب") && rest.isNotEmpty() && rest[0].norm in words("منه", "منها", "منهم", "منو")) return Order.Compose(raw)

        // ---- standing rules
        if (STYLE_STRONG_START.containsMatchIn(sentence)) return Order.Style(raw)
        if (head in STYLE_HEAD && rest.isNotEmpty() && !sentence.startsWith("خلي بالك")) return Order.Style(raw)
        if (BAN_START.containsMatchIn(sentence)) return Order.Style(raw)

        // anything else is a note for the replies when the user clearly spoke to us
        return if (!strict) Order.Style(raw) else Order.NotAnOrder(raw)
    }

    /**
     * A message for the orders the model is asked to word ("اسأله هو جاي ولا لأ"), used when the model only repeats the
     * order back. Null when there is no sensible plain wording.
     */
    fun composeFallback(spoken: String): String? {
        val (t, _) = lead(tokens(spoken), false)
        if (t.isEmpty()) return null
        val head = t[0].norm
        var rest = t.drop(1)
        if (rest.firstOrNull()?.norm in PRONOUN) rest = rest.drop(1)
        val body = payload(rest)
        val onSomething = body.isNotBlank() && n(body.substringBefore(' ')) in words("على", "علي")
        return when {
            head in words("اسأله", "اسألها", "اسألهم", "اسالو", "اسأل", "ask") ->
                when {
                    body.isBlank() -> null
                    n(body.substringBefore(' ')) == "عن" -> "ممكن تقولي $body؟"
                    else -> body.trimEnd('؟', '?') + "؟"
                }
            head in words("اشكره", "اشكرها", "اشكرهم", "thank") -> if (onSomething) "شكرًا $body" else "شكرًا ليك"
            head in words("اعتذر", "اعتذري", "اعتذرله", "اعتذرلها", "apologize", "apologise") -> if (onSomething) "أنا آسف $body" else "أنا آسف"
            head in words("رحب", "رحبي", "greet") -> "أهلاً وسهلاً"
            head in words("هنيه", "هنيها", "هنيهم", "congratulate") -> if (onSomething) "مبروك $body" else "مبروك"
            head in words("سلم") -> "سلام"
            else -> null
        }
    }
}
