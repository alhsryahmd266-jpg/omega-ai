package com.omega.keyboard

import com.omega.keyboard.core.*

/**
 * Plain-Kotlin test suite (no JUnit dependency in the logic itself) so it can be run with kotlinc on any machine
 * and from Gradle through [CoreSuiteTest].
 */
object CoreTests {
    private val failures = ArrayList<String>()
    private var checks = 0

    private fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
        checks++
        if (!cond) failures.add("FAIL  $name  ${detail()}")
    }

    fun runAll(): List<String> {
        failures.clear(); checks = 0
        layouts(); arabic(); dictionary(); languages(); chatParsing(); replyPolicy(); sendFinding(); sendVerifying()
        commands(); matching(); prompts(); screenFilter(); scrolling()
        println("checks run: $checks, failures: ${failures.size}")
        return failures.toList()
    }

    // ------------------------------------------------------------------ fixtures

    private class B(
        val pkg: String = "com.whatsapp",
        val w: Int = 1080,
        val h: Int = 2400,
        val rtl: Boolean = false,
    ) {
        val nodes = ArrayList<UiNode>()
        fun add(
            text: String = "", l: Int, t: Int, r: Int, b: Int, desc: String = "", id: String = "",
            cls: String = "android.widget.TextView", editable: Boolean = false, clickable: Boolean = false,
            hint: Boolean = false, depth: Int = 2, p: String = pkg, childCount: Int = 0,
            scrollDown: Boolean = false, scrollUp: Boolean = false, visible: Boolean = true,
        ): Int {
            val i = nodes.size
            nodes.add(
                UiNode(i, -1, depth, text, desc, id, cls, p, l, t, r, b, editable = editable, clickable = clickable,
                    actionable = clickable, showingHint = hint, childCount = childCount,
                    scrollDown = scrollDown, scrollUp = scrollUp, visible = visible)
            )
            return i
        }
        fun screen() = Screen(pkg, w, h, 2.75f, nodes.toList(), rtl)
    }

    /** A WhatsApp-like chat: them / me / them(last), composer at the bottom. */
    private fun whatsapp(typed: String? = null, rtl: Boolean = false, ticks: Boolean = true): Screen {
        val b = B(rtl = rtl)
        b.add("أحمد", 150, 90, 600, 150, id = "com.whatsapp:id/conversation_contact_name")
        b.add("online", 150, 150, 400, 190, id = "com.whatsapp:id/conversation_contact_status")
        b.add("اليوم", 470, 330, 610, 380)
        b.add("Hi how are you?", 60, 430, 520, 500)
        b.add("10:30 AM", 430, 505, 515, 540)
        b.add("Fine thanks, and you?", 640, 600, 1010, 670)
        b.add("10:31 AM", 880, 675, 960, 710)
        if (ticks) b.add("", 975, 675, 1020, 710, desc = "Read", id = "com.whatsapp:id/status")
        b.add("Are you coming tonight?", 60, 760, 640, 830)
        b.add("10:35 AM", 540, 835, 625, 870)
        // composer
        b.add(typed ?: "Message", 120, 2190, 900, 2300, id = "com.whatsapp:id/entry", cls = "android.widget.EditText",
            editable = true, hint = typed == null)
        b.add("", 50, 2210, 120, 2280, desc = "Emoji", id = "com.whatsapp:id/emoji_picker_btn", clickable = true)
        b.add("", 760, 2210, 830, 2280, desc = "Attach", id = "com.whatsapp:id/input_attach_button", clickable = true)
        b.add("", 830, 2210, 900, 2280, desc = "Camera", id = "com.whatsapp:id/camera_btn", clickable = true)
        if (typed != null) b.add("", 940, 2190, 1040, 2300, desc = "Send", id = "com.whatsapp:id/send", cls = "android.widget.ImageButton", clickable = true)
        else b.add("", 940, 2190, 1040, 2300, desc = "Voice message", id = "com.whatsapp:id/voice_note_btn", cls = "android.widget.ImageButton", clickable = true)
        return b.screen()
    }

    // ------------------------------------------------------------------ suites

    private fun layouts() {
        val all = listOf(
            Layouts.english(false), Layouts.english(true), Layouts.arabic(false, false), Layouts.arabic(true, true),
            Layouts.arabicAlt(), Layouts.symbols1("en", false), Layouts.symbols2("en"), Layouts.symbols1("ar", true),
            Layouts.symbols2("ar"), Layouts.numeric(false), Layouts.numeric(true),
        )
        for (l in all) {
            val totals = l.rows.map { it.total }
            check("layout ${l.id} rows have equal width", totals.all { kotlin.math.abs(it - totals[0]) < 0.01f }) { "$totals" }
            check("layout ${l.id} keys have a label", l.rows.all { r -> r.keys.all { it.kind == KeyKind.SPACER || it.label.isNotEmpty() } })
            check("layout ${l.id} has an enter and a backspace", l.rows.any { r -> r.keys.any { it.kind == KeyKind.ENTER } } &&
                l.rows.any { r -> r.keys.any { it.kind == KeyKind.BACKSPACE } })
        }
        val ar = Layouts.arabic(false, false)
        check("arabic row1 starts with ض", ar.rows[0].keys.first().text == "ض")
        check("arabic has 4 rows", ar.rows.size == 4)
        check("arabic first row has 12 keys", ar.rows[0].keys.size == 12)
        check("arabic alef has hamza popups", ar.rows[1].keys.first { it.text == "ا" }.popups.containsAll(listOf("أ", "إ", "آ")))
        check("number row adds a row", Layouts.english(true).rows.size == 5 && Layouts.english(false).rows.size == 4)
        check("indic digits on arabic number row", Layouts.arabic(true, true).rows[0].keys[0].text == "١")
        check("english e has digit 3 then accents", Layouts.english(false).rows[0].keys[2].popups.first() == "3")
        val diacritic = Layouts.arabicAlt().rows[0].keys.first()
        check("diacritic key commits the bare mark", diacritic.text.length == 1 && diacritic.label.length == 2)
    }

    private fun arabic() {
        check("normalize strips tashkeel/hamza", ArabicText.normalize("أَحْمَد") == "احمد")
        check("normalize ta marbuta + ya", ArabicText.normalize("مدرسة") == "مدرسه" && ArabicText.normalize("على") == "علي")
        check("normalize digits", ArabicText.normalize("٠١٢") == "012")
        check("arabic ratio", ArabicText.arabicRatio("مرحبا") == 1.0 && ArabicText.arabicRatio("hello") == 0.0)
        check("mostly arabic mixed", ArabicText.isMostlyArabic("مرحبا يا صاحبي WhatsApp"))
        check("mostly latin mixed", !ArabicText.isMostlyArabic("مرحبا يا WhatsApp Messenger"))
        check("edit distance", ArabicText.editDistance("kitten", "sitting", 5) == 3 && ArabicText.editDistance("abc", "abc") == 0)
        check("edit distance bound", ArabicText.editDistance("abcdef", "uvwxyz", 2) == 3)
        check("indic round trip", ArabicText.toWesternDigits(ArabicText.toArabicIndicDigits("2024")) == "2024")
    }

    private fun dictionary() {
        val ar = Dictionary("ar")
        ar.load(listOf("أحمد\t500", "احمد\t100", "محمد\t900", "مرحبا\t800", "مرحبتين\t10", "شكرا\t700").asSequence())
        check("ar suggest prefix", ar.suggest("مر", 3) == listOf("مرحبا", "مرحبتين")) { ar.suggest("مر", 3).toString() }
        check("ar suggest normalises alef", ar.suggest("احم", 3) == listOf("أحمد")) { ar.suggest("احم", 3).toString() }
        check("ar merged duplicate keys", ar.size == 5) { "${ar.size}" }
        check("ar contains normalised", ar.contains("احمد") && ar.contains("أحمد"))
        val en = Dictionary("en")
        en.load(listOf("the\t1000", "them\t500", "there\t400", "thanks\t300", "hello\t200", "help\t150").asSequence())
        check("en suggest order", en.suggest("th", 3) == listOf("the", "them", "there")) { en.suggest("th", 3).toString() }
        check("en correction insert", en.corrections("thnks").contains("thanks")) { en.corrections("thnks").toString() }
        check("en correction substitute", en.corrections("hallo").contains("hello"))
        check("no correction for known word", en.corrections("hello").isEmpty())
        en.learn("zorblax"); en.learn("zorblax")
        check("learned word is suggested first", en.suggest("zo", 2) == listOf("zorblax"))
        val dump = en.exportUser()
        val en2 = Dictionary("en"); en2.load(sequenceOf("a\t1")); en2.importUser(dump)
        check("user words survive export/import", en2.suggest("zorb", 1) == listOf("zorblax"))
    }

    private fun languages() {
        check("script guess zh", Languages.guessByScript("你好，世界") == "zh")
        check("script guess ja", Languages.guessByScript("こんにちは世界") == "ja")
        check("script guess ko", Languages.guessByScript("안녕하세요") == "ko")
        check("script guess ru", Languages.guessByScript("Привет мир") == "ru")
        check("script guess ar", Languages.guessByScript("مرحبا بالعالم") == "ar")
        check("latin is undecided", Languages.guessByScript("hello world") == null)
        check("lang lookup tolerant", Languages.find("en-US")?.code == "en" && Languages.find("zh_CN")?.code == "zh")
        check("gemma-only language flagged", Languages.find("am")?.mlkit == false && Languages.find("fr")?.mlkit == true)
        check("language codes unique", Languages.all.map { it.code }.toSet().size == Languages.all.size)
    }

    private fun chatParsing() {
        val s = whatsapp()
        val r = ChatParser.parse(s)
        val texts = r.messages.map { it.text }
        check("input found", r.input?.shortId == "entry")
        check("chat messages in order", texts == listOf("Hi how are you?", "Fine thanks, and you?", "Are you coming tonight?")) { "$texts" }
        check("chat sides", r.messages.map { it.side } == listOf(Side.THEM, Side.ME, Side.THEM)) { "${r.messages.map { it.side }}" }
        check("header/time/date are not messages", texts.none { it == "أحمد" || it.contains("AM") || it == "اليوم" })

        // long incoming message that almost spans the screen is still "theirs"
        val b = B(); b.add("a long paragraph of text from the other person here", 40, 500, 880, 700)
        b.add("", 120, 2190, 900, 2300, cls = "android.widget.EditText", editable = true, id = "x:id/entry")
        val long = ChatParser.parse(b.screen())
        check("wide bubble still themed by margins", long.messages.single().side == Side.THEM) { "${long.messages}" }

        // apps that put MY bubbles on the left (learned alignment); no delivery ticks on screen here
        val plain = whatsapp(ticks = false)
        check("no ticks: default alignment", ChatParser.parse(plain).messages.map { it.side } == listOf(Side.THEM, Side.ME, Side.THEM))
        val mirrored = ChatParser.parse(plain, Align.LEFT)
        check("alignment flip swaps sides", mirrored.messages.map { it.side } == listOf(Side.ME, Side.THEM, Side.ME)) { "${mirrored.messages.map { it.side }}" }
        check("ticks override alignment", ChatParser.parse(s, Align.LEFT).messages[1].side == Side.ME)
        // delivery ticks also TEACH the parser which edge is mine (apps in right-to-left layouts mirror the bubbles)
        val taught = ChatParser.parse(s, Align.LEFT)
        check("ticks teach the edge", taught.learnedAlign == Align.RIGHT) { "${taught.learnedAlign}" }
        check("ticks win over a wrong saved alignment", taught.messages.map { it.side } == listOf(Side.THEM, Side.ME, Side.THEM)) { "${taught.messages.map { it.side }}" }
        val mir = B(rtl = true)
        mir.add("Hello there", 640, 430, 1010, 500)                 // theirs: right edge
        mir.add("Hi, how can I help?", 60, 600, 520, 670)           // mine: left edge, with ticks
        mir.add("", 70, 675, 115, 710, desc = "Read", id = "com.whatsapp:id/status")
        mir.add("Need a price please", 600, 760, 1010, 830)         // theirs again (last)
        mir.add("", 120, 2190, 900, 2300, cls = "android.widget.EditText", editable = true, id = "x:id/entry")
        val m = ChatParser.parse(mir.screen())                       // default alignment says RIGHT, the ticks say LEFT
        check("mirrored chat learned from ticks", m.learnedAlign == Align.LEFT && m.messages.map { it.side } == listOf(Side.THEM, Side.ME, Side.THEM)) { "${m.learnedAlign} ${m.messages.map { it.side }}" }
        check("no ticks: nothing learned", ChatParser.parse(plain).learnedAlign == null)
        check("mirrored chat: last message is theirs, so we reply", ReplyPolicy.decide(m, null, force = false).reply)

        // search box at the top must not be taken as the composer
        val c = B(pkg = "x.y"); c.add("search", 100, 150, 900, 250, cls = "android.widget.EditText", editable = true)
        c.add("", 100, 2200, 900, 2300, cls = "android.widget.EditText", editable = true)
        check("lowest editable is the composer", ChatParser.findInput(c.screen())?.top == 2200)
    }

    private fun replyPolicy() {
        val r = ChatParser.parse(whatsapp())
        val d = ReplyPolicy.decide(r, null, false)
        check("reply to new incoming", d.reply && d.reason == "new_incoming") { d.toString() }
        check("same situation is not answered twice", !ReplyPolicy.decide(r, d.key, false).reply)

        val b = B(); b.add("hello", 60, 500, 500, 560); b.add("hi, how are you", 600, 700, 1020, 760)
        b.add("", 120, 2190, 900, 2300, cls = "android.widget.EditText", editable = true)
        val mine = ChatParser.parse(b.screen())
        check("no reply when my message is last", !ReplyPolicy.decide(mine, null, false).reply)
        check("directive forces a reply", ReplyPolicy.decide(mine, null, true).reply)

        val noInput = ChatParser.parse(B().also { it.add("hello", 60, 500, 500, 560) }.screen())
        check("no input field means no reply", !ReplyPolicy.decide(noInput, null, true).reply)

        // the "same text again" case: them:"ok", me:"ok", them:"ok" must be answered again
        val m1 = listOf(ChatMessage("ok", Side.THEM, 0, 1, 0))
        val m2 = listOf(ChatMessage("ok", Side.THEM, 0, 1, 0), ChatMessage("ok", Side.ME, 2, 3, 1), ChatMessage("ok", Side.THEM, 4, 5, 2))
        check("repeat text gets a new key", ReplyPolicy.key(m1) != ReplyPolicy.key(m2))
    }

    private fun sendFinding() {
        // 1) WhatsApp with text typed: the send button, never emoji/attach/camera/mic
        val s1 = whatsapp("Great, see you later")
        val in1 = ChatParser.findInput(s1)!!
        val c1 = SendFinder.candidates(s1, in1)
        check("whatsapp send found", c1.isNotEmpty() && c1.first().node.shortId == "send") { c1.toString() }
        check("only the send button is offered", c1.size == 1) { c1.map { it.node.shortId }.toString() }

        // 2) nothing typed: WhatsApp shows the microphone -> must NOT be offered
        val s2 = whatsapp(null)
        val c2 = SendFinder.candidates(s2, ChatParser.findInput(s2)!!)
        check("microphone is never a send candidate", c2.isEmpty()) { c2.toString() }

        // 3) Telegram-like: no ids at all, label only
        val t = B(pkg = "org.telegram.messenger")
        t.add("hello", 60, 500, 500, 560)
        t.add("Message", 150, 2190, 880, 2300, cls = "android.widget.EditText", editable = true, hint = false)
        t.add("", 30, 2200, 130, 2290, desc = "Attach", clickable = true)
        t.add("", 940, 2200, 1040, 2290, desc = "Send", clickable = true)
        val c3 = SendFinder.candidates(t.screen(), ChatParser.findInput(t.screen())!!)
        check("label-only send found", c3.firstOrNull()?.node?.desc == "Send") { c3.toString() }

        // 4) Arabic UI label
        val a = B(pkg = "com.whatsapp")
        a.add("نص", 150, 2190, 880, 2300, cls = "android.widget.EditText", editable = true)
        a.add("", 940, 2200, 1040, 2290, desc = "إرسال", clickable = true)
        val c4 = SendFinder.candidates(a.screen(), ChatParser.findInput(a.screen())!!)
        check("arabic label send found", c4.firstOrNull()?.node?.desc == "إرسال") { c4.toString() }

        // 5) RTL layout mirrored: send button on the LEFT
        val rt = B(pkg = "com.whatsapp", rtl = true)
        rt.add("نص", 140, 2190, 940, 2300, cls = "android.widget.EditText", editable = true)
        rt.add("", 20, 2200, 120, 2290, desc = "إرسال", id = "com.whatsapp:id/send", clickable = true)
        val c5 = SendFinder.candidates(rt.screen(), ChatParser.findInput(rt.screen())!!)
        check("mirrored send found", c5.firstOrNull()?.node?.shortId == "send") { c5.toString() }

        // 6) text button (Instagram style)
        val ig = B(pkg = "com.instagram.android")
        ig.add("hi", 40, 2190, 800, 2300, cls = "android.widget.EditText", editable = true)
        ig.add("Send", 820, 2210, 1000, 2280, clickable = true)
        val c6 = SendFinder.candidates(ig.screen(), ChatParser.findInput(ig.screen())!!)
        check("text 'Send' button found", c6.firstOrNull()?.node?.text == "Send") { c6.toString() }

        // 7) icon-only unlabeled button at the end of the row is a last resort, the one at the start is ignored
        val u = B(pkg = "com.example.chat")
        u.add("hello", 120, 2190, 880, 2300, cls = "android.widget.EditText", editable = true)
        u.add("", 10, 2200, 100, 2290, clickable = true)
        u.add("", 930, 2200, 1040, 2290, clickable = true)
        val c7 = SendFinder.candidates(u.screen(), ChatParser.findInput(u.screen())!!)
        check("unlabeled end button is the fallback", c7.size == 1 && c7[0].node.left == 930 && c7[0].why == "unlabeled-end") { c7.toString() }

        // 8) a disabled or hidden button is never returned
        val d = B(pkg = "com.example.chat")
        d.add("hello", 120, 2190, 880, 2300, cls = "android.widget.EditText", editable = true)
        d.nodes.add(UiNode(5, -1, 2, "", "Send", "", "android.widget.ImageButton", "com.example.chat", 940, 2200, 1040, 2290, clickable = true, actionable = true, enabled = false))
        check("disabled send is skipped", SendFinder.candidates(d.screen(), ChatParser.findInput(d.screen())!!).isEmpty())
    }

    private fun sendVerifying() {
        val typed = "Great, see you later"
        val before = whatsapp(typed)
        check("text still in field -> not sent", !SendVerifier.isSent(before, typed))
        check("field state still", SendVerifier.fieldState(before, typed) == SendState.STILL_IN_FIELD)
        val after = whatsapp(null)   // composer back to the hint
        check("hint showing -> sent", SendVerifier.isSent(after, typed))
        val noField = B().also { it.add("Great, see you later", 640, 900, 1000, 960) }.screen()
        check("no composer but the message is visible -> sent", SendVerifier.isSent(noField, typed))
        val nothing = B().also { it.add("unrelated", 640, 900, 1000, 960) }.screen()
        check("no composer and no message -> not sent", !SendVerifier.isSent(nothing, typed))
    }

    private fun commands() {
        fun p(s: String, auto: Boolean = false) = CommandParser.parse(s, auto)
        check("call colloquial", p("كلملي أحمد") == VoiceCommand.Call("احمد")) { "${p("كلملي أحمد")}" }
        check("call with 'كلم'", p("كلم ماما") == VoiceCommand.Call("ماما"))
        check("call with preposition glued", (p("اتصل بمحمد علي") as VoiceCommand.Call).name == "محمد علي") { "${p("اتصل بمحمد علي")}" }
        check("call keeps the untouched alternative", (p("اتصل بباسل") as VoiceCommand.Call).alt == "بباسل" && (p("اتصل بباسل") as VoiceCommand.Call).name == "باسل")
        check("call in english", p("call John Smith") == VoiceCommand.Call("john smith"))
        check("open app", p("افتح واتساب") == VoiceCommand.OpenApp("واتساب"))
        check("translate screen", p("ترجم الشاشة") == VoiceCommand.TranslateScreen)
        check("translate screen 2", p("ترجملي الشاشة دي") == VoiceCommand.TranslateScreen)
        check("start autopilot", p("شغل الوضع الذاتي") == VoiceCommand.StartAutopilot)
        check("stop autopilot", p("وقف الوضع الذاتي") == VoiceCommand.StopAutopilot)
        check("plain stop while chatting", p("وقف", true) == VoiceCommand.StopAutopilot)
        check("dictation stays dictation", p("أنا رايح المدرسة دلوقتي") == VoiceCommand.Text("أنا رايح المدرسة دلوقتي"))
        check("free speech is an instruction while the AI chats", p("قوله إني جاي في الطريق", true) == VoiceCommand.Instruction("قوله إني جاي في الطريق"))
        check("clear instructions", p("امسح التعليمات", true) == VoiceCommand.ClearInstructions)
        check("commands can be disabled", CommandParser.parse("كلم أحمد", false, commandsEnabled = false) == VoiceCommand.Text("كلم أحمد"))
    }

    private fun matching() {
        check("name match with hamza", NameMatcher.score("احمد", "أحمد علي") >= 80)
        check("name match tolerant", NameMatcher.score("محمد", "Mohamed") < 55 && NameMatcher.score("محمد", "محمد حسن") >= 80)
        check("app alias", NameMatcher.best("واتساب", listOf("Telegram", "WhatsApp", "Chrome"), { it }) == "WhatsApp")
        check("app alias 2", NameMatcher.best("كروم", listOf("Telegram", "WhatsApp", "Chrome"), { it }) == "Chrome")
        check("no match below threshold", NameMatcher.best("زززز", listOf("Telegram", "WhatsApp"), { it }) == null)
        val contacts = listOf("ماما", "أحمد السيد", "أحمد علي", "بابا")
        check("contact best", NameMatcher.best("احمد علي", contacts, { it }) == "أحمد علي")
    }

    private fun prompts() {
        check("clean: label prefix", LlmPrompts.cleanReply("Me: تمام يا صاحبي") == "تمام يا صاحبي")
        check("clean: quotes and hallucinated next turn", LlmPrompts.cleanReply("\"أيوه جاي\"\nThem: طيب") == "أيوه جاي") { "${LlmPrompts.cleanReply("\"أيوه جاي\"\nThem: طيب")}" }
        check("clean: NO_REPLY", LlmPrompts.cleanReply("NO_REPLY") == null)
        check("clean: refuses AI disclaimers", LlmPrompts.cleanReply("As an AI language model, I cannot do that") == null)
        check("clean: chat tokens", LlmPrompts.cleanReply("ماشي<end_of_turn>") == "ماشي")
        check("clean: empty", LlmPrompts.cleanReply("   ") == null)
        check("clean: long text is cut", (LlmPrompts.cleanReply("جملة. ".repeat(300), 100) ?: "").length <= 100)

        val out = "1|مرحبا\n2|كيف حالك\nمتابعة السطر\n3|وداعا\n9|مجهول"
        val m = LlmPrompts.parseTranslations(out, listOf(1, 2, 3))
        check("parse translations", m == mapOf(1 to "مرحبا", 2 to "كيف حالك متابعة السطر", 3 to "وداعا")) { "$m" }
        check("parse tolerant separators", LlmPrompts.parseTranslations("1: hello\n2) world", listOf(1, 2)) == mapOf(1 to "hello", 2 to "world"))

        val batches = LlmPrompts.batches((1..25).map { it to "x".repeat(100) }, { it.second.length }, maxChars = 800, maxItems = 10)
        check("batches respect limits", batches.all { it.size <= 8 } && batches.sumOf { it.size } == 25) { batches.map { it.size }.toString() }

        val sys = LlmPrompts.replySystem("", listOf("اعتذر عن الموعد"))
        check("system prompt carries instructions", sys.contains("اعتذر عن الموعد") && sys.contains(LlmPrompts.NO_REPLY))
        val msgs = listOf(ChatMessage("Hi", Side.THEM, 0, 1, 0), ChatMessage("Where are you?", Side.THEM, 2, 3, 1), ChatMessage("home", Side.ME, 4, 5, 2))
        val user = LlmPrompts.replyUser(msgs)
        check("consecutive messages are merged into one turn", user.contains("Them: Hi\nWhere are you?") && user.contains("Me: home")) { user }
        check("directive is requested", LlmPrompts.replyUser(msgs, "say I will be late").contains("say I will be late"))
        check("translate prompt format", LlmPrompts.translateUser(listOf(1 to "a\nb", 2 to "c")) == "1|a b\n2|c")
        check("clean translation unquotes", LlmPrompts.cleanTranslation("\"Hello\"") == "Hello")
    }

    /** A conversation that opened above its newest messages, and the rules for getting down to them. */
    private fun scrolling() {
        fun chat(down: Boolean, up: Boolean = true, listBottom: Int = 2050): Screen {
            val b = B()
            b.add("أحمد", 150, 90, 600, 150, id = "com.whatsapp:id/conversation_contact_name")
            b.add("", 0, 200, 1080, listBottom, id = "android:id/list", cls = "android.widget.ListView", scrollDown = down, scrollUp = up, depth = 1)
            b.add("Hi", 60, 430, 520, 500)
            b.add("Message", 100, 2150, 900, 2300, id = "com.whatsapp:id/entry", cls = "android.widget.EditText", editable = true, hint = true)
            return b.screen()
        }
        val open = chat(down = true)
        val list = ScrollLogic.listWithMoreBelow(open)
        check("finds the list that has more below", list != null && list.shortId == "list") { "$list" }
        check("at the end: nothing to scroll", ScrollLogic.listWithMoreBelow(chat(down = false)) == null)

        val tiny = B()
        tiny.add("", 100, 2150, 900, 2300, cls = "android.widget.EditText", editable = true, scrollDown = true)   // multi-line box
        check("a text box that scrolls is not the conversation", ScrollLogic.listWithMoreBelow(tiny.screen()) == null)
        val narrow = B()
        narrow.add("", 0, 200, 300, 2000, scrollDown = true)
        check("a narrow strip is not the conversation", ScrollLogic.listWithMoreBelow(narrow.screen()) == null)
        val short = B()
        short.add("", 0, 200, 1080, 600, scrollDown = true)
        check("a short strip (chips, carousel) is not the conversation", ScrollLogic.listWithMoreBelow(short.screen()) == null)
        val hidden = B()
        hidden.add("", 0, 200, 1080, 2000, scrollDown = true, visible = false)
        check("an invisible list is ignored", ScrollLogic.listWithMoreBelow(hidden.screen()) == null)
        val two = B()
        two.add("", 0, 200, 1080, 1000, id = "small", scrollDown = true)
        two.add("", 0, 200, 1080, 2000, id = "big", scrollDown = true)
        check("the biggest list wins", ScrollLogic.listWithMoreBelow(two.screen())?.shortId == "big")

        val p = ScrollLogic.swipeUpPath(list!!)
        check("swipe goes upwards inside the list", p[0] == list.centerX && p[1] > p[2] && p[2] > list.top && p[1] < list.bottom) { p.joinToString() }

        val sigA = ScrollLogic.signature(open, list)
        check("signature is stable", sigA == ScrollLogic.signature(chat(down = true), list))
        val moved = B()
        moved.add("", 0, 200, 1080, 2050, id = "android:id/list", scrollDown = true)
        moved.add("Hi", 60, 330, 520, 400)                                          // same text, other place
        check("signature notices a scroll", ScrollLogic.signature(moved.screen(), list) != sigA)

        // an order works in a brand-new, empty chat; without one there is nothing to answer
        val empty = B()
        empty.add("Message", 100, 2150, 900, 2300, id = "com.whatsapp:id/entry", cls = "android.widget.EditText", editable = true, hint = true)
        val parsed = ChatParser.parse(empty.screen(), Align.RIGHT)
        check("order in an empty chat is carried out", ReplyPolicy.decide(parsed, null, true).reply)
        check("no order, no messages: nothing to do", !ReplyPolicy.decide(parsed, null, false).reply)
        check("order needs a composer", !ReplyPolicy.decide(ChatParser.parse(B().screen(), Align.RIGHT), null, true).reply)

        // spoken orders
        check("'قول له' is an order", CommandParser.isOrder("قول له إني جاي بعد ساعة"))
        check("'قوله' is an order", CommandParser.isOrder("قوله اني جاي"))
        check("'tell him' is an order", CommandParser.isOrder("tell him I'm coming"))
        check("'ابعتله' is an order", CommandParser.isOrder("ابعتله مرحبا"))
        check("a style rule is not an order", !CommandParser.isOrder("من دلوقتي كلمه بأسلوب رسمي"))
        check("while chatting, 'قول له' is an instruction",
            CommandParser.parse("قول له إني جاي بعد ساعة", true) is VoiceCommand.Instruction)
        check("the order text reaches the model", LlmPrompts.replyUser(emptyList(), "قول له إني جاي بعد ساعة").contains("قول له إني جاي بعد ساعة"))
    }

    private fun screenFilter() {
        val b = B(pkg = "com.example.app")
        b.add("Settings", 60, 300, 400, 360)
        b.add("إعدادات", 60, 400, 400, 460)                       // already Arabic -> skipped for target ar
        b.add("10:45", 60, 500, 200, 540)                        // time -> skipped
        b.add("https://example.com/x", 60, 600, 700, 640)        // url -> skipped
        b.add("typed by me", 60, 700, 700, 760, editable = true) // editable -> skipped
        b.add("Battery", 60, 800, 400, 860, p = "com.android.systemui")
        val parent = b.add("Notifications", 60, 900, 600, 980, depth = 2)
        b.add("Notifications", 70, 910, 590, 970, depth = 3)     // same string deeper -> only the deepest stays
        b.add("2", 60, 1000, 100, 1040)                          // no letters
        val items = ScreenFilter.translatable(b.screen(), "ar")
        val texts = items.map { it.text }
        check("filter keeps only translatable text", texts == listOf("Settings", "Notifications")) { "$texts" }
        check("deepest duplicate wins", items.last().node.depth == 3 && parent == 6)
        val toEn = ScreenFilter.translatable(b.screen(), "en").map { it.text }
        check("arabic is translatable when the target is English", toEn.contains("إعدادات"))
    }
}
