package com.omega.keyboard

import com.omega.keyboard.core.*
import com.omega.keyboard.ime.ColorPicRenderer
import com.omega.keyboard.voice.VoicePlan

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
        prompts(); screenFilter(); scrolling(); noise(); translation(); autopilotRules(); colors(); voicePlan(); colourSend()
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
        check("arabic first row has 11 keys like Gboard", ar.rows[0].keys.size == 11)
        fun letters(r: Int) = ar.rows[r].keys.filter { it.kind == KeyKind.CHAR }.joinToString("") { it.text }
        check("arabic row 1 reads from the left like Gboard", letters(0) == "ضصثقفغعهخحج") { letters(0) }
        check("arabic row 2 reads from the left like Gboard", letters(1) == "شسيبلاتنمكط") { letters(1) }
        check("arabic row 3 reads from the left like Gboard", letters(2) == "ذءؤرىةوزظد") { letters(2) }
        check("arabic keys are not mirrored", !ar.rtl && !Layouts.arabicAlt().rtl)
        check("function keys keep their usual sides", ar.rows[3].keys.first().kind == KeyKind.SYMBOLS &&
            ar.rows[3].keys.last().kind == KeyKind.ENTER && ar.rows[2].keys.last().kind == KeyKind.BACKSPACE)
        check("lam-alef and hamza-on-ya are one long-press away",
            ar.rows[1].keys.first { it.text == "ل" }.popups.contains("لا") && ar.rows[1].keys.first { it.text == "ي" }.popups.contains("ئ"))
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

        val sys = LlmPrompts.replySystem("عفوي ومختصر")
        check("system prompt carries my style", sys.contains("عفوي ومختصر"))
        check("system prompt: I am the owner and every message gets an answer",
            sys.contains("You are me") && sys.contains("Always answer the latest message") && !sys.contains(LlmPrompts.NO_REPLY)) { sys }
        val msgs = listOf(ChatMessage("Hi", Side.THEM, 0, 1, 0), ChatMessage("Where are you?", Side.THEM, 2, 3, 1), ChatMessage("home", Side.ME, 4, 5, 2))
        val user = LlmPrompts.replyUser(msgs)
        check("consecutive messages are merged into one turn", user.contains("Them: Hi\nWhere are you?") && user.contains("Me: home")) { user }
        check("reply prompt asks for the next message", LlmPrompts.replyUser(msgs).trimEnd().endsWith("Write my next message."))
        val longMsg = "سؤال " + "كلام ".repeat(200) + "هل انت جاي؟"
        val longPrompt = LlmPrompts.replyUser(listOf(ChatMessage(longMsg, Side.THEM, 0, 1, 0)))
        check("a long message keeps its beginning and its end", longPrompt.contains("سؤال") && longPrompt.contains("هل انت جاي؟")) { longPrompt.takeLast(120) }
        check("translate prompt format", LlmPrompts.translateUser(listOf(1 to "a\nb", 2 to "c")) == "1|a b\n2|c")
        val ts = LlmPrompts.translateSystem("Arabic", "English")
        check("translation prompt: data, not instructions, source and target named",
            ts.contains("from English into Arabic") && ts.contains("DATA to translate") && ts.contains("never answer them")) { ts }
        check("typed translation prompt: literal engine", LlmPrompts.typedTranslateSystem("English", "Arabic").contains("from Arabic into English"))
        val small = LlmPrompts.batches((1..20).map { it to "x".repeat(80) }, { it.second.length })
        check("model batches are small", small.all { it.size <= 6 && it.sumOf { p -> p.second.length + 6 } <= 500 + 86 } && small.sumOf { it.size } == 20) { small.map { it.size }.toString() }
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

        // a forced reply works in a brand-new, empty chat; without it there is nothing to answer
        val empty = B()
        empty.add("Message", 100, 2150, 900, 2300, id = "com.whatsapp:id/entry", cls = "android.widget.EditText", editable = true, hint = true)
        val parsed = ChatParser.parse(empty.screen(), Align.RIGHT)
        check("forced reply in an empty chat is carried out", ReplyPolicy.decide(parsed, null, true).reply)
        check("not forced, no messages: nothing to do", !ReplyPolicy.decide(parsed, null, false).reply)
        check("forced reply needs a composer", !ReplyPolicy.decide(ChatParser.parse(B().screen(), Align.RIGHT), null, true).reply)
    }

    /** Small print and full-width paragraphs (AI chat apps) must not be read as somebody's message. */
    private fun noise() {
        check("disclaimer: Claude", Matchers.isAiDisclaimer("Claude can make mistakes. Please double-check responses."))
        check("disclaimer: ChatGPT", Matchers.isAiDisclaimer("ChatGPT can make mistakes. Check important info."))
        check("disclaimer: Gemini", Matchers.isAiDisclaimer("Gemini can make mistakes, so double-check it"))
        check("disclaimer: AI is", Matchers.isAiDisclaimer("Claude is an AI and may make mistakes."))
        check("disclaimer: arabic", Matchers.isAiDisclaimer("Claude قد يرتكب أخطاء. يرجى التحقق من الردود."))
        check("disclaimer: second line alone", Matchers.isAiDisclaimer("Please double-check responses."))
        check("a friend's words are still messages", !Matchers.isAiDisclaimer("I can make mistakes too lol") && !Matchers.isAiDisclaimer("ممكن يغلط") &&
            !Matchers.isAiDisclaimer("ChatGPT wrote it for me but it can make mistakes") && !Matchers.isAiDisclaimer("الذكاء الاصطناعي ممكن يغلط"))
        check("disclaimer is chat noise", Matchers.isChatNoise("Claude can make mistakes. Please double-check responses."))

        // an AI chat screen: my bubble on the right, the answer as a full-width paragraph, the small print under it
        val c = B(pkg = "com.anthropic.claude")
        c.add("what is 2+2", 380, 430, 1020, 500)
        c.add("2+2 equals 4. Let me know if you want more examples of simple arithmetic.", 48, 560, 1032, 700)
        c.add("Claude can make mistakes. Please double-check responses.", 140, 760, 1000, 820)
        c.add("Reply to Claude", 120, 2190, 900, 2300, id = "x:id/input", cls = "android.widget.EditText", editable = true, hint = true)
        val r = ChatParser.parse(c.screen())
        check("small print is dropped", r.messages.map { it.text } == listOf("what is 2+2", "2+2 equals 4. Let me know if you want more examples of simple arithmetic.")) { "${r.messages.map { it.text }}" }
        check("my bubble is mine, the full-width answer is nobody's yet", r.messages.map { it.side } == listOf(Side.ME, Side.UNKNOWN)) { "${r.messages.map { it.side }}" }
        val d = ReplyPolicy.decide(r, null, force = false, unknownAsThem = true)
        check("the answer gets a reply", d.reply && d.reason == "new_incoming") { d.toString() }
        check("without the 'unknown is theirs' rule it would stay silent", !ReplyPolicy.decide(r, null, force = false).reply)

        // the prompt of an empty message box is not a draft the user wrote
        check("hint is not a draft", Matchers.isComposerHint("") && Matchers.isComposerHint("Reply to Claude") && Matchers.isComposerHint("Message") &&
            Matchers.isComposerHint("اكتب رسالة") && Matchers.isComposerHint("Type a message") && Matchers.isComposerHint("anything", "anything"))
        check("a real draft is a draft", !Matchers.isComposerHint("ok see you at 5") && !Matchers.isComposerHint("Message me when you arrive, ok?") &&
            !Matchers.isComposerHint("تمام هجيلك بعد ساعة") && !Matchers.isComposerHint("Reply to Claude was slow today because of the network"))

        // geometry: a bubble hugs one edge; a paragraph that spans the width belongs to nobody
        fun sideOf(l: Int, rr: Int): Side {
            val b = B(); b.add("some words in a message", l, 500, rr, 560)
            b.add("", 120, 2190, 900, 2300, cls = "android.widget.EditText", editable = true, id = "x:id/entry")
            return ChatParser.parse(b.screen()).messages.single().side
        }
        check("right bubble is mine", sideOf(400, 1020) == Side.ME) { "${sideOf(400, 1020)}" }
        check("left bubble is theirs", sideOf(60, 700) == Side.THEM) { "${sideOf(60, 700)}" }
        check("full-width paragraph is nobody's", sideOf(48, 1032) == Side.UNKNOWN) { "${sideOf(48, 1032)}" }
        check("centred text is nobody's", sideOf(300, 780) == Side.UNKNOWN) { "${sideOf(300, 780)}" }
        check("a wide left bubble is still theirs", sideOf(40, 880) == Side.THEM) { "${sideOf(40, 880)}" }
        check("a message about mistakes is kept", ChatParser.parse(B().also { it.add("ممكن يغلط", 60, 430, 400, 500); it.add("", 120, 2190, 900, 2300, cls = "android.widget.EditText", editable = true, id = "x:id/entry") }.screen()).messages.size == 1)
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

        // a container that shows merged text and also holds the texts: the children are translated, the container is not
        val c = B(pkg = "com.example.app")
        c.add("Title Subtitle 3 min", 60, 300, 1000, 500, depth = 2, childCount = 2)
        c.add("Title", 70, 310, 500, 360, depth = 3)
        c.add("Subtitle", 70, 400, 500, 450, depth = 3)
        val ct = ScreenFilter.translatable(c.screen(), "ar").map { it.text }
        check("a container's merged text is dropped in favour of its children", ct == listOf("Title", "Subtitle")) { "$ct" }

        // the content description only counts for a small plain label: never a big container or a picture
        val d = B(pkg = "com.example.app")
        d.add("", 60, 300, 1000, 1500, desc = "Conversation with John", childCount = 5)
        d.add("", 60, 1600, 300, 1680, desc = "Profile photo", cls = "android.widget.ImageView")
        d.add("", 60, 1700, 500, 1760, desc = "Add to cart")
        val dt = ScreenFilter.translatable(d.screen(), "ar").map { it.text }
        check("only a small plain label is translated through its description", dt == listOf("Add to cart")) { "$dt" }
    }

    /** Which language a text is in, and whether an answer can be believed: the two things that decide if a translation looks right. */
    private fun translation() {
        fun src(t: String, d: String?, dom: String = "en") = TranslateRules.sourceLanguage(t, d, dom)
        check("script: chinese / japanese / korean / hebrew / arabic",
            src("你好，今天怎么样", null) == "zh" && src("こんにちは、元気ですか", null) == "ja" && src("안녕하세요", null) == "ko" &&
                src("שלום מה שלומך", null) == "he" && src("مرحبا كيف حالك", null) == "ar")
        check("arabic script: persian and urdu letters are told apart", src("چطور هستید؟ گفتگو", null) == "fa" && src("آپ کیسے ہیں", null) == "ur") {
            "${src("چطور هستید؟ گفتگو", null)} ${src("آپ کیسے ہیں", null)}"
        }
        check("cyrillic: ukrainian only when it is long enough to be sure", src("Привіт, як справи в тебе сьогодні?", "uk") == "uk" && src("Привет", "uk") == "ru")
        check("a short interface word is English wherever it is", src("OK", "fr", "es") == "en" && src("Settings", "de", "fr") == "en")
        check("a short unknown word takes the language of the screen", src("Hola", null, "es") == "es" && src("Hola", "und", "es") == "es")
        check("a long text is recognised on its own", src("Gracias por todo, nos vemos mañana por la tarde", "es", "en") == "es")
        check("a short text is never trusted to the recogniser", src("Ver más", "pt", "es") == "es")
        check("transliterated text is not a language any engine knows", TranslateRules.normalizeCode("ar-Latn") == null && src("ezayak ya basha", "ar-Latn", "en") == "en")
        check("language codes are normalised", TranslateRules.normalizeCode("fil") == "tl" && TranslateRules.normalizeCode("iw") == "he" &&
            TranslateRules.normalizeCode("zh-CN") == "zh" && TranslateRules.normalizeCode("pt-BR") == "pt" && TranslateRules.normalizeCode("und") == null &&
            TranslateRules.normalizeCode(null) == null)

        val texts = listOf("Bonjour tout le monde, comment ça va aujourd'hui", "Merci beaucoup pour votre aide", "OK", "Menu")
        check("the screen's language is what its long texts say", TranslateRules.latinDominant(texts, listOf("fr", "fr", null, null)) == "fr")
        check("nothing to go on: English", TranslateRules.latinDominant(listOf("OK", "Menu"), listOf(null, null)) == "en")

        fun v(s: String, o: String, t: String = "ar") = TranslateRules.check(s, o, t)
        val ok = TranslateRules.Verdict.OK
        val bad = TranslateRules.Verdict.BAD
        val same = TranslateRules.Verdict.UNCHANGED
        check("a real translation passes", v("Hello world, how are you today?", "مرحبا بالعالم، كيف حالك اليوم؟") == ok && v("Home", "الرئيسية") == ok && v("John", "جون") == ok)
        check("an unchanged text is nothing to paint", v("iPhone 15", "iPhone 15") == same && v("Hello!", "hello") == same)
        check("an empty answer is rejected", v("Hello", "  ") == bad)
        check("prompt leftovers are rejected", v("Good morning", "Translation: صباح الخير") == bad && v("Good morning", "1|صباح الخير") == bad)
        check("an answer in the wrong script is rejected", v("Good morning my friend", "Buenos dias mi amigo") == bad)
        check("an invented, much longer answer is rejected", v("Hi", "مرحبا ".repeat(40)) == bad)
        check("a one-word answer to a long text is rejected", v("This is a fairly long sentence that needs a long translation", "نعم") == bad)
        check("only Arabic targets need Arabic letters", v("Bonjour", "Hello", "en") == ok)
        check("mixed text that is mostly Arabic is left alone", v("اهلا Hello", "اهلا Hello") == same)
    }

    /** The decisions behind "press once, it replies and sends": the box's prompt is not a draft, the send button is found, the prompt is "me". */
    private fun autopilotRules() {
        check("WhatsApp's prompt is not a draft", !Matchers.isDraft("مراسلة", false) && !Matchers.isDraft("\u200Fمراسلة", false, hint = "مراسلة") &&
            !Matchers.isDraft("Message", false, hint = "Message") && !Matchers.isDraft("", false))
        check("a real draft is a draft", Matchers.isDraft("هجيلك بعد شوية", false) && Matchers.isDraft("ok see you", false) && Matchers.isDraft("العميل محتاج مراسلة ضرورية جدا", false))
        check("while the prompt is showing nothing is a draft", !Matchers.isDraft("anything", true))

        // first press, WhatsApp with its prompt handed over as text: it must reply at once
        val w = B()
        w.add("Hi how are you?", 60, 430, 520, 500)
        w.nodes.add(UiNode(w.nodes.size, -1, 2, "مراسلة", "", "com.whatsapp:id/entry", "android.widget.EditText", "com.whatsapp", 150, 2190, 880, 2300,
            editable = true, clickable = true, actionable = true, hint = "مراسلة"))
        val wp = ChatParser.parse(w.screen())
        val wd = ReplyPolicy.decide(wp, null, force = false, unknownAsThem = true)
        val inp = wp.input!!
        check("the first press replies although the box shows its prompt as text", wd.reply && !Matchers.isDraft(inp.text, inp.showingHint, inp.desc, inp.hint)) { wd.toString() }

        // a button that changes once there is text in the box is the send button, whatever it is called
        val before = B(pkg = "com.example.chat")
        before.add("", 120, 2190, 880, 2300, cls = "android.widget.EditText", editable = true, hint = true)
        before.add("", 930, 2200, 1040, 2290, desc = "Voice message", clickable = true)
        val after = B(pkg = "com.example.chat")
        after.add("see you", 120, 2190, 880, 2300, cls = "android.widget.EditText", editable = true)
        after.add("", 930, 2200, 1040, 2290, desc = "Ok", clickable = true)
        val aIn = ChatParser.findInput(after.screen())!!
        check("without the before-picture an odd button name is not recognised", SendFinder.candidates(after.screen(), aIn).isEmpty())
        val ch = SendFinder.candidates(after.screen(), aIn, before.screen())
        check("the button that changed is the send button", ch.firstOrNull()?.node?.desc == "Ok" && ch.first().why.contains("changed")) { ch.toString() }

        // a button that was there before and did not change is not "changed" (the attach button stays what it was)
        val keep = B(pkg = "com.example.chat")
        keep.add("", 120, 2190, 880, 2300, cls = "android.widget.EditText", editable = true, hint = true)
        keep.add("", 930, 2200, 1040, 2290, desc = "Ok", clickable = true)
        check("an unchanged button is not promoted", SendFinder.candidates(after.screen(), aIn, keep.screen()).isEmpty())
    }

    // ------------------------------------------------------------------ colours

    private fun near(a: Int, b: Int, tol: Int = 1): Boolean =
        Math.abs(ColorMath.red(a) - ColorMath.red(b)) <= tol && Math.abs(ColorMath.green(a) - ColorMath.green(b)) <= tol &&
            Math.abs(ColorMath.blue(a) - ColorMath.blue(b)) <= tol

    private fun colors() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF2979FF.toInt()

        check("white is the brightest", Math.abs(ColorMath.luminance(white) - 1f) < 0.001f)
        check("black has no luminance", ColorMath.luminance(black) < 0.001f)
        check("black on white has the top contrast", Math.abs(ColorMath.contrast(black, white) - 21f) < 0.05f) { ColorMath.contrast(black, white).toString() }
        check("contrast does not depend on the order", ColorMath.contrast(red, white) == ColorMath.contrast(white, red))

        check("dark letters on a white key", ColorMath.onColor(white) == ColorMath.INK && ColorMath.isLight(white))
        check("white letters on a black key", ColorMath.onColor(black) == ColorMath.PAPER && !ColorMath.isLight(black))
        check("white letters on blue", ColorMath.onColor(blue) == ColorMath.PAPER)
        val yellow = 0xFFFFEA00.toInt()
        check("dark letters on neon yellow", ColorMath.onColor(yellow) == ColorMath.INK)
        // whatever the key colour, the chosen letter colour reads at least as well as the other one
        var worst = 21f
        for (i in 0 until 360 step 15) for (s in listOf(0.2f, 0.6f, 1f)) for (v in listOf(0.2f, 0.5f, 0.8f, 1f)) {
            val c = ColorMath.fromHsv(i.toFloat(), s, v)
            worst = minOf(worst, ColorMath.contrast(c, ColorMath.onColor(c)))
        }
        check("letters are readable on every key colour (worst contrast $worst)", worst >= 3.9f) { worst.toString() }

        check("blend at 0 is the first colour", ColorMath.blend(red, blue, 0f) == red)
        check("blend at 1 is the second colour", ColorMath.blend(red, blue, 1f) == blue)
        check("blend halfway", near(ColorMath.blend(black, white, 0.5f), 0xFF808080.toInt()))
        check("alpha is blended too", ColorMath.alpha(ColorMath.blend(0x00FFFFFF, white, 0.5f)) in 127..128)
        check("withAlpha keeps the colour", ColorMath.withAlpha(red, 0x40) == 0x40FF0000)
        check("withAlphaF 1.0 is opaque", ColorMath.alpha(ColorMath.withAlphaF(red, 1f)) == 255 && ColorMath.alpha(ColorMath.withAlphaF(red, 0f)) == 0)

        check("shade to black", ColorMath.shade(blue, -1f) == black)
        check("shade to white", ColorMath.shade(blue, 1f) == white)
        check("shade 0 keeps the colour", ColorMath.shade(blue, 0f) == blue)
        check("shade keeps alpha", ColorMath.alpha(ColorMath.shade(0x80FF0000.toInt(), 0.3f)) == 0x80)
        check("lighter is lighter", ColorMath.luminance(ColorMath.shade(blue, 0.3f)) > ColorMath.luminance(blue))
        check("darker is darker", ColorMath.luminance(ColorMath.shade(blue, -0.3f)) < ColorMath.luminance(blue))

        // HSV round trip over the whole wheel
        var roundTripOk = true
        var bad = ""
        for (i in 0 until 360 step 7) for (s in listOf(0.15f, 0.5f, 1f)) for (v in listOf(0.2f, 0.6f, 1f)) {
            val c = ColorMath.fromHsv(i.toFloat(), s, v)
            val hsv = ColorMath.toHsv(c)
            val back = ColorMath.fromHsv(hsv[0], hsv[1], hsv[2])
            if (!near(c, back)) { roundTripOk = false; bad = "h=$i s=$s v=$v ${Integer.toHexString(c)} -> ${Integer.toHexString(back)}" }
        }
        check("HSV round trip", roundTripOk) { bad }
        check("pure red is hue 0", ColorMath.toHsv(red)[0] == 0f && ColorMath.toHsv(red)[1] == 1f && ColorMath.toHsv(red)[2] == 1f)
        check("pure green is hue 120", Math.abs(ColorMath.toHsv(0xFF00FF00.toInt())[0] - 120f) < 0.01f)
        check("pure blue is hue 240", Math.abs(ColorMath.toHsv(0xFF0000FF.toInt())[0] - 240f) < 0.01f)
        check("hue wraps around", ColorMath.fromHsv(360f, 1f, 1f) == red && ColorMath.fromHsv(-120f, 1f, 1f) == 0xFF0000FF.toInt())
        check("grey has no saturation", ColorMath.toHsv(0xFF808080.toInt())[1] == 0f)

        // the tone bar: black → pure hue → white
        check("tone 0 is black", ColorMath.fromTone(200f, 0f) == black)
        check("tone 1 is white", ColorMath.fromTone(200f, 1f) == white)
        check("tone 0.5 is the pure hue", ColorMath.fromTone(0f, 0.5f) == red)
        var toneOk = true
        for (i in 0..20) {
            val t = i / 20f
            val back = ColorMath.toTone(ColorMath.fromTone(30f, t))
            if (Math.abs(back - t) > 0.02f) toneOk = false
        }
        check("tone round trip", toneOk)
        var mono = true
        var prev = -1f
        for (i in 0..20) { val l = ColorMath.luminance(ColorMath.fromTone(210f, i / 20f)); if (l + 0.0005f < prev) mono = false; prev = l }
        check("moving right on the tone bar never gets darker", mono)

        // gradients
        for (c in listOf(blue, red, white, black, 0xFF14213D.toInt(), 0xFFFFEA00.toInt())) {
            val g = ColorMath.gradientPartner(c)
            check("gradient partner of ${Integer.toHexString(c)} differs", g != c)
            check("gradient partner of ${Integer.toHexString(c)} is opaque", ColorMath.alpha(g) == 255)
        }

        // rainbow
        val first = (0 until 6).map { ColorMath.rainbow(it) }
        check("rainbow letters are different colours", first.toSet().size == 6)
        check("rainbow colours are opaque and bright", first.all { ColorMath.alpha(it) == 255 && ColorMath.toHsv(it)[2] > 0.95f })

        // palettes
        check("typed palette has red and blue first", Palette.typed[0].color == 0xFF2979FF.toInt() && Palette.typed[1].color == 0xFFFF1744.toInt())
        check("typed palette has fluorescent colours", Palette.typed.count { it.name.contains("فسفوري") } >= 5)
        check("palette names are unique", Palette.typed.map { it.name }.toSet().size == Palette.typed.size && Palette.general.map { it.name }.toSet().size == Palette.general.size)
        check("palette colours are opaque and unique", (Palette.typed + Palette.general).all { ColorMath.alpha(it.color) == 255 } &&
            Palette.typed.map { it.color }.toSet().size == Palette.typed.size && Palette.general.map { it.color }.toSet().size == Palette.general.size)
    }

    // ------------------------------------------------------------------ dictation: which recogniser / language is tried, in which order

    private fun voicePlan() {
        val google = "com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
        val oem = "com.vendor.speech/com.vendor.speech.Service"
        val other = "org.other.asr/org.other.asr.Service"

        check("Google's recogniser is ranked first", VoicePlan.rank(listOf(oem, google, other), oem).first() == google)
        check("without Google the phone's default comes first", VoicePlan.rank(listOf(other, oem), oem).first() == oem)
        check("rank drops duplicates", VoicePlan.rank(listOf(oem, oem, google), null).size == 2)

        check("ar-EG chain", VoicePlan.languageChain("ar-EG") == listOf("ar-EG", "ar-SA", "ar"))
        check("ar chain", VoicePlan.languageChain("ar") == listOf("ar", "ar-SA", "ar-EG"))
        check("en-US chain", VoicePlan.languageChain("en-US") == listOf("en-US", "en"))
        check("underscore is accepted", VoicePlan.languageChain("en_GB") == listOf("en-GB", "en"))
        check("no language wanted: no chain", VoicePlan.languageChain("").isEmpty() && VoicePlan.languageChain(null).isEmpty())

        val plan = VoicePlan.build(listOf(oem, google), oem, "ar-EG", false)
        check("first attempt is Google with the wanted language", plan.first() == VoicePlan.Attempt(google, "ar-EG", false)) { plan.toString() }
        check("every recogniser gets every spelling", plan.size == 6 && plan.count { it.service == google } == 3) { plan.toString() }
        check("online unless offline was asked for", plan.none { it.offline })

        val off = VoicePlan.build(listOf(google), null, "ar-EG", true)
        check("offline first, then the same online", off[0] == VoicePlan.Attempt(google, "ar-EG", true) && off[1] == VoicePlan.Attempt(google, "ar-EG", false)) { off.toString() }
        check("plan is capped", VoicePlan.build(listOf(google, oem, other), null, "ar-EG", true).size == 8)

        val bare = VoicePlan.build(emptyList(), null, "ar-EG", false)
        check("no recogniser found: the phone's default is still tried", bare.isNotEmpty() && bare.all { it.service == null })
        check("no language wanted: one attempt without language", VoicePlan.build(listOf(google), null, "", false).single().lang == "")

        val good = VoicePlan.Attempt(oem, "ar-SA", false)
        val promoted = VoicePlan.build(listOf(oem, google), oem, "ar-EG", false, good.key)
        check("what worked last time goes first", promoted.first() == good && promoted.size == plan.size && promoted.toSet() == plan.toSet()) { promoted.toString() }
        val unknown = VoicePlan.build(listOf(oem, google), oem, "ar-EG", false, "nothing|x|0")
        check("an unknown remembered attempt is ignored", unknown == plan)

        check("language-not-supported tries the next way", VoicePlan.tryNext(12) && VoicePlan.tryNext(13))
        check("a broken recogniser tries the next way", VoicePlan.tryNext(5) && VoicePlan.tryNext(11) && VoicePlan.tryNext(VoicePlan.CODE_STUCK) && VoicePlan.tryNext(VoicePlan.CODE_FAILED))
        check("silence / no match / busy / permission are the user's own outcome", listOf(6, 7, 8, 9, 3).none { VoicePlan.tryNext(it) })
        check("network trouble retries offline once", VoicePlan.offlineRetry(plan.first(), 2) == plan.first().copy(offline = true) && VoicePlan.offlineRetry(plan.first().copy(offline = true), 2) == null)
        check("a language error does not trigger an offline retry", VoicePlan.offlineRetry(plan.first(), 12) == null)
        check("the system dialog is the last way for service failures", VoicePlan.dialogWorthIt(12) && VoicePlan.dialogWorthIt(VoicePlan.CODE_UNAVAILABLE) && !VoicePlan.dialogWorthIt(7) && !VoicePlan.dialogWorthIt(2))

        check("the connection error wins", VoicePlan.bestError(listOf(12, 2, 12)) == 2)
        check("a missing language pack beats not supported", VoicePlan.bestError(listOf(12, 13)) == 13)
        check("not supported alone", VoicePlan.bestError(listOf(12, 12)) == 12)
        check("something else: the last one", VoicePlan.bestError(listOf(5, 4)) == 4)
        check("no error at all", VoicePlan.bestError(emptyList()) == VoicePlan.CODE_FAILED)
        check("attempt labels are readable", plan.first().label == "google ar-EG online" && plan[3].label == "com.vendor.speech ar-EG online") { plan.first().label + " / " + plan[3].label }
    }

    // ------------------------------------------------------------------ coloured send: colour codes, the preview's send button, the picture's card

    private fun colourSend() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF2979FF.toInt()
        check("a format is found by its id", ColorCodes.Format.of("ff") == ColorCodes.Format.FREEFIRE && ColorCodes.Format.of("discord") == ColorCodes.Format.DISCORD && ColorCodes.Format.of("???") == ColorCodes.Format.NONE && ColorCodes.Format.of(null) == ColorCodes.Format.NONE)
        check("no format leaves the text alone", ColorCodes.wrap("hello", ColorCodes.Format.NONE) { red } == "hello")
        check("Free Fire code goes before the text", ColorCodes.wrap("hello", ColorCodes.Format.FREEFIRE) { red } == "[FF0000]hello")
        check("one colour over several words is one code", ColorCodes.wrap("hello world", ColorCodes.Format.FREEFIRE) { red } == "[FF0000]hello world")
        check("Arabic text gets the same treatment", ColorCodes.wrap("مرحبا بيك", ColorCodes.Format.FREEFIRE) { blue } == "[2979FF]مرحبا بيك")
        check("Unity tags", ColorCodes.wrap("hi", ColorCodes.Format.UNITY) { red } == "<color=#FF0000>hi</color>")
        check("BBCode tags", ColorCodes.wrap("hi", ColorCodes.Format.BBCODE) { blue } == "[color=#2979FF]hi[/color]")
        check("HTML tags", ColorCodes.wrap("hi", ColorCodes.Format.HTML) { red } == "<font color=\"#FF0000\">hi</font>")
        check("alpha is ignored", ColorCodes.wrap("a", ColorCodes.Format.FREEFIRE) { 0x80FF0000.toInt() } == "[FF0000]a")
        val rb = ColorCodes.wrap("abc", ColorCodes.Format.FREEFIRE) { n -> listOf(0xFF0000, 0x00FF00, 0x0000FF)[n % 3] }
        check("the rainbow colours every letter", rb == "[FF0000]a[00FF00]b[0000FF]c") { rb }
        check("spaces never start a code", ColorCodes.wrap("a b", ColorCodes.Format.UNITY) { n -> if (n == 0) 0xFF0000 else 0x00FF00 } == "<color=#FF0000>a </color><color=#00FF00>b</color>")
        check("leading blanks stay outside", ColorCodes.wrap("  hi", ColorCodes.Format.FREEFIRE) { red } == "  [FF0000]hi")
        check("only blanks: nothing to colour", ColorCodes.wrap("   ", ColorCodes.Format.FREEFIRE) { red } == "   ")
        check("emoji are not split", ColorCodes.wrap("😀a", ColorCodes.Format.FREEFIRE) { n -> if (n == 0) 0xFF0000 else 0x00FF00 } == "[FF0000]😀[00FF00]a")
        check("Minecraft red", ColorCodes.minecraftCode(0xFF1744) == 'c' && ColorCodes.minecraftCode(0x000000) == '0' && ColorCodes.minecraftCode(0xFFFFFF) == 'f')
        check("Minecraft picks the nearest colour", ColorCodes.minecraftCode(0x2979FF) == '9')
        check("Minecraft text", ColorCodes.wrap("hi", ColorCodes.Format.MINECRAFT) { 0xFFFF1744.toInt() } == "&chi")
        check("Discord red and blue", ColorCodes.discordCode(0xFF0000) == 31 && ColorCodes.discordCode(0x2979FF) == 34)
        val dc = ColorCodes.wrap("hi", ColorCodes.Format.DISCORD) { red }
        check("Discord ansi block", dc == "```ansi\n\u001B[1;31mhi\u001B[0m\n```") { dc.replace("\u001B", "ESC") }

        // the picture's card keeps the text readable
        check("light colours get a dark card", ColorPicRenderer.cardFor(0xFFFFEA00.toInt(), false, false) == 0xFF16161F.toInt())
        check("dark colours get a white card", ColorPicRenderer.cardFor(0xFF1A237E.toInt(), false, false) == 0xFFFFFFFF.toInt())
        check("the rainbow gets a dark card", ColorPicRenderer.cardFor(blue, true, false) == 0xFF16161F.toInt())
        check("the highlighter pen colours the card", ColorPicRenderer.cardFor(red, false, true) == red)

        // the send button of a picture preview
        val dm = 3f
        fun node(id: Int, label: String, viewId: String, l: Int, t: Int, r: Int, b: Int, act: Boolean = true, desc: String = "") =
            UiNode(id, -1, 0, label, desc, viewId, "android.widget.ImageButton", "com.whatsapp", l, t, r, b, clickable = act, actionable = act)
        fun scr(vararg n: UiNode) = Screen("com.whatsapp", 1080, 2400, dm, n.toList())
        val send = node(1, "", "com.whatsapp:id/send", 900, 2150, 1030, 2280, desc = "إرسال")
        val caption = UiNode(2, -1, 0, "", "", "com.whatsapp:id/caption", "android.widget.EditText", "com.whatsapp", 100, 2150, 880, 2280, editable = true)
        val close = node(3, "", "com.whatsapp:id/cancel", 20, 100, 150, 230, desc = "إغلاق")
        val crop = node(4, "", "com.whatsapp:id/crop", 800, 100, 930, 230, desc = "قص")
        check("the preview's send button is found by id and label", PreviewSendFinder.find(scr(close, crop, caption, send))?.id == 1)
        val byLabel = node(5, "", "x:id/btn", 900, 2150, 1030, 2280, desc = "Send")
        check("or by label alone", PreviewSendFinder.find(scr(close, byLabel))?.id == 5)
        check("no send button: nothing is pressed", PreviewSendFinder.find(scr(close, crop, caption)) == null)
        check("the microphone is never taken for send", PreviewSendFinder.find(scr(node(6, "", "x:id/voice_note_btn", 900, 2150, 1030, 2280, desc = "Send voice message"))) == null)
        check("a disabled button is not pressed", PreviewSendFinder.find(Screen("p", 1080, 2400, dm, listOf(send.copy(enabled = false)))) == null)
        val chatSend = node(7, "", "com.whatsapp:id/send", 900, 1500, 1030, 1630, desc = "إرسال")
        check("with two matches the lower one (the preview's own) wins", PreviewSendFinder.find(scr(chatSend, send))?.id == 1)
    }
}
