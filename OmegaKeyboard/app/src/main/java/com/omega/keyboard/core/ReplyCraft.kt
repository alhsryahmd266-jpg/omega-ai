package com.omega.keyboard.core

/**
 * What a good reply needs, worked out from the conversation by plain code, and the check that decides whether the reply the small
 * local model wrote is good enough to send. Pure Kotlin, tested on the JVM.
 *
 * A small model fails a chat in a few predictable ways: it clips every answer to a couple of words, answers one of two questions,
 * switches language, talks like a help desk ("how can I help you?"), parrots the message it was given, or says again what it already
 * said. Noticing these needs no smarter model: they are noticed here, the model is told exactly what was wrong, and it writes again.
 */
object ReplyCraft {

    enum class Script { ARABIC, LATIN, OTHER }

    enum class Kind { EMOJI, ACK, GREETING, THANKS, QUESTION, QUESTIONS, REQUEST, LONG, STATEMENT, GAME }

    /**
     * @param minLetters a reply with fewer letters and digits than this is too short for the message it answers
     * @param sentences how long the answer should be, for the model ("2 to 3")
     * @param brief my own style notes ask for short replies: the length rules are relaxed
     * @param note the guidance written for the model about this very message
     * @param checkScript the chat has enough text to be sure of its language, so a reply in another one is rejected
     */
    class Plan(
        val kind: Kind,
        val script: Script,
        val checkScript: Boolean,
        val questions: Int,
        val minLetters: Int,
        val sentences: String,
        val brief: Boolean,
        val note: String,
    )

    /** The conversation as real turns: [history] = (written by me?, text), opening with their turn, and the message to answer. */
    class Convo(val history: List<Pair<Boolean, String>>, val user: String)

    enum class Flaw(val hard: Boolean) {
        EMPTY(true), LEAK(true), ASSISTANT(true), WRONG_SCRIPT(true), ECHO(true), REPEAT(true),

        /** usable when nothing better comes */
        TOO_SHORT(false)
    }

    /** [fix] = what to tell the model so that the next try avoids this flaw (null when the reply is fine). */
    class Verdict(val flaw: Flaw?, val fix: String?) {
        val ok: Boolean get() = flaw == null
    }

    class Candidate(val text: String, val verdict: Verdict)

    // ------------------------------------------------------------------ reading their message

    private val NON_WORD = Regex("[^\\p{L}\\p{N}']+")

    private fun tokens(s: String): List<String> = ArabicText.normalize(s.replace('’', '\'')).split(NON_WORD).filter { it.isNotEmpty() }

    private fun letters(s: String): Int = s.count { it.isLetterOrDigit() }

    private val ARABIZI = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:[a-z]*[2356789][a-z]+|ezayak|ezzayak|ezayyak|3amel|keda|kda|mesh|msh|inshallah|habibi|yasta|ya3ni|lesa|ahlan)(?![\\p{L}\\p{N}])"
    )
    private val ORDINAL = Regex("(?i)\\b\\d+(?:st|nd|rd|th)\\b")

    /** The writing system their message is in; mixed text, or text with no letters, is OTHER (nothing to check against). */
    fun scriptOf(text: String): Script {
        val l = text.count { it.isLetter() }
        if (l == 0) return Script.OTHER
        val ratio = ArabicText.arabicRatio(text)
        return when {
            ratio >= 0.6 -> Script.ARABIC
            ratio <= 0.15 -> if (ARABIZI.containsMatchIn(ORDINAL.replace(text, ""))) Script.OTHER else Script.LATIN
            else -> Script.OTHER
        }
    }

    private val GREETINGS = listOf(
        "السلام عليكم", "سلام عليكم", "وعليكم السلام", "ورحمه الله", "وبركاته", "صباح الخير", "صباح النور", "صباح الفل", "صباح الورد",
        "مساء الخير", "مساء النور", "مساء الفل", "تصبح علي خير", "تصبحي علي خير", "اهلا وسهلا", "اهلا بيك", "اهلا", "اهلين", "مرحبا", "هلا", "هاي",
        "ازيك", "ازيكم", "ازيك عامل ايه", "عامل ايه", "عاملين ايه", "عامله ايه", "اخبارك ايه", "اخبارك", "اخباركم", "ايه الاخبار", "ايه اخبارك",
        "كيف حالك", "كيفك", "شلونك", "يا صباح الفل", "نورت", "ايه يا صاحبي",
        "ezayak", "ezzayak", "ezayek", "ezayyak", "azayak", "3amel eh", "a3mel eh", "3amla eh", "eh el akhbar", "eh el 2akhbar", "salam alaikom",
        "salam aleikum", "sabah el kheir", "sabah el 5eir", "masa2 el kheir", "ahlan", "ahla",
        "hi", "hello", "hey", "hiya", "yo", "good morning", "good evening", "good afternoon", "good night", "how are you doing",
        "how are you", "how r u", "what's up", "whats up", "sup", "wassup", "how's it going", "hows it going", "how are things"
    )
    private val GREETING_RE = Regex(
        "(?<![\\p{L}\\p{N}])(?:" + GREETINGS.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}])"
    )

    private fun isGreeting(text: String): Boolean {
        val n = ArabicText.normalize(text.replace('’', '\''))
        if (!GREETING_RE.containsMatchIn(n)) return false
        val rest = GREETING_RE.replace(n, " ")
        val restLetters = letters(rest)
        val restHasMark = rest.contains('?') || rest.contains('؟')
        return restLetters <= 10 && !(restLetters > 0 && restHasMark)
    }

    private val THANKS = listOf(
        "شكرا", "متشكر", "مشكور", "تسلم", "ميرسي", "الله يخليك", "يعطيك العافيه", "جزاك الله", "thanks", "thank you", "thx", "much appreciated",
        "appreciate it", "cheers"
    )
    private val THANKS_RE = Regex(
        "(?<![\\p{L}\\p{N}])(?:" + THANKS.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}])"
    )

    private fun isThanks(text: String, questions: Int): Boolean =
        questions == 0 && letters(text) <= 70 && THANKS_RE.containsMatchIn(ArabicText.normalize(text))

    private val REQUEST_RE = Regex(
        "(?<![\\p{L}\\p{N}])(?:ممكن|ابعتلي|ابعت|قولي|قول لي|عايز|عاوز|محتاج|تقدر|كلمني|اتصل|لو سمحت|من فضلك|ارجوك|please|pls|plz|can you|could you|would you|" +
            "send me|tell me|let me know|i need|i want|call me|text me)(?![\\p{L}\\p{N}])"
    )

    private fun isRequest(text: String): Boolean = REQUEST_RE.containsMatchIn(ArabicText.normalize(text))

    private val Q_WORDS = setOf(
        "ايه", "ليه", "ليش", "لماذا", "فين", "وين", "متي", "امتي", "مين", "ازاي", "كيف", "كام", "كم", "هل", "بكام", "بكم", "ينفع", "ايش", "شو",
        "what", "why", "where", "when", "who", "whom", "whose", "which", "how"
    )
    private val SENTENCE = Regex("[^.!?؟\\n]+[?؟]*")

    /** How many questions the message holds: a sentence ending in a question mark, or one that opens with a question word. */
    fun countQuestions(text: String): Int {
        var n = 0
        for (m in SENTENCE.findAll(text)) {
            val s = m.value.trim()
            if (letters(s) < 2) continue
            val marked = s.endsWith("?") || s.endsWith("؟")
            val first = tokens(s).firstOrNull().orEmpty()
            val bare = if (first.length > 2 && (first[0] == 'و' || first[0] == 'ف')) first.substring(1) else first
            if (marked || first in Q_WORDS || bare in Q_WORDS) n++
        }
        return n
    }

    private val BRIEF = Regex("(?i)(مختصر|قصير|باختصار|short|brief|concise|terse)")
    private val NOT_BRIEF = Regex("(?i)(مش مختصر|مش قصير|مفصل|بالتفصيل|طويل|not short|not brief|detailed|long)")

    /** My own style notes ask for short replies (then they are allowed to be short). */
    fun wantsBrief(persona: String): Boolean = BRIEF.containsMatchIn(persona) && !NOT_BRIEF.containsMatchIn(persona)

    // ------------------------------------------------------------------ the plan

    /** The writing system of the chat as it stands: the newest messages, until there is enough text to tell. */
    private fun contextScript(messages: List<ChatMessage>, game: Boolean): Pair<Script, Boolean> {
        if (game) return Script.OTHER to false
        val sb = StringBuilder()
        var n = 0
        for (t in LlmPrompts.foldTurns(messages).asReversed()) {
            sb.append(t.second).append('\n')
            n++
            if (letters(sb.toString()) >= 40 || n >= 6) break
        }
        val text = sb.toString()
        return scriptOf(text) to (letters(text) >= 15)
    }

    /** The message I am answering: the newest turn of the other side (in a game: its newest line, without the player's name). */
    private fun theirNewest(messages: List<ChatMessage>, game: Boolean): String {
        val turns = LlmPrompts.foldTurns(messages)
        val theirs = turns.lastOrNull { it.first != Side.ME }?.second.orEmpty()
        if (!game) return theirs
        val line = theirs.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
        return GameChat.splitName(line)?.second ?: line
    }

    fun plan(messages: List<ChatMessage>, game: Boolean = false, persona: String = ""): Plan {
        val theirs = theirNewest(messages, game)
        val brief = wantsBrief(persona)
        val (script, sure) = contextScript(messages, game)
        val l = letters(theirs)
        val lines = theirs.lines().count { it.isNotBlank() }
        val toks = tokens(theirs)
        val questions = countQuestions(theirs)
        val long = l >= 140 || (lines >= 3 && l >= 60)
        val short = l <= 12 && toks.size <= 3
        val kind = when {
            game -> Kind.GAME
            l == 0 -> Kind.EMOJI
            isGreeting(theirs) -> Kind.GREETING
            isThanks(theirs, questions) -> Kind.THANKS
            questions >= 2 -> Kind.QUESTIONS
            questions == 1 -> Kind.QUESTION
            short -> Kind.ACK
            isRequest(theirs) -> Kind.REQUEST
            long -> Kind.LONG
            else -> Kind.STATEMENT
        }
        val ar = script != Script.LATIN             // only a chat that is surely in a Latin language needs the longer minimum
        var min = when (kind) {
            Kind.EMOJI -> 0
            Kind.ACK, Kind.GAME -> 2
            Kind.THANKS -> 3
            Kind.GREETING -> 6
            Kind.QUESTION -> when {
                short -> 10
                long -> if (ar) 24 else 32
                else -> if (ar) 14 else 18
            }
            Kind.QUESTIONS -> if (ar) 30 else 40
            Kind.REQUEST -> if (ar) 14 else 18
            Kind.LONG -> if (ar) 30 else 40
            Kind.STATEMENT -> if (ar) 10 else 14
        }
        var sentences = when (kind) {
            Kind.EMOJI, Kind.ACK, Kind.GREETING, Kind.THANKS -> "1"
            Kind.GAME -> "1 or 2 short"
            Kind.QUESTION -> if (long) "2 to 4" else "2 to 3"
            Kind.QUESTIONS -> "3 to 5"
            Kind.REQUEST -> "2 to 3"
            Kind.LONG -> "2 to 4"
            Kind.STATEMENT -> "1 to 3"
        }
        if (brief) {
            min /= 2
            if (kind != Kind.GAME && sentences != "1") sentences = "1 to 2"
        }
        return Plan(kind, script, sure, questions, min, sentences, brief, noteFor(kind, questions, sentences, script, brief))
    }

    private fun noteFor(kind: Kind, questions: Int, sentences: String, script: Script, brief: Boolean): String {
        val sb = StringBuilder()
        sb.append(
            when (kind) {
                Kind.EMOJI -> "They sent only an emoji or a symbol. React to it in one very short line."
                Kind.ACK -> "They only said something very short. Answer in one short, natural line."
                Kind.GREETING -> "They are greeting me. Greet back warmly in one short line and ask how they are."
                Kind.THANKS -> "They are thanking me. Answer warmly in one short line."
                Kind.QUESTION -> "They asked me a question. My first sentence must answer it directly, then add the one detail that helps ($sentences sentences). " +
                    "If I cannot know the answer, say so plainly and ask one specific question about it."
                Kind.QUESTIONS -> "They asked $questions questions. Answer every one of them, in the order they asked, each with its own sentence " +
                    "($sentences sentences in all). If I cannot know one of the answers, say so for that one."
                Kind.REQUEST -> "They asked me for something. Say clearly whether I will do it and what happens next ($sentences sentences)."
                Kind.LONG -> "They wrote a long message. Show that I read all of it: answer its specific points (names, numbers, what happened) in $sentences sentences."
                Kind.STATEMENT -> "They told me something. React to exactly what they said (the news, the feeling or the plan) in $sentences sentences, " +
                    "and ask a natural follow-up question only if it fits."
                Kind.GAME -> "This is a quick chat inside a game. Answer the newest message in $sentences sentence(s), a few words each, and answer it for real " +
                    "(a question gets an answer, not just \"lol\" or \"ok\")."
            }
        )
        if (brief && kind != Kind.GAME) sb.append(" Keep it short, as my style notes say.")
        sb.append(' ')
        sb.append(
            when (script) {
                Script.ARABIC -> "Write in Arabic script, in the dialect they use (Egyptian Arabic if you cannot tell), without English words."
                Script.LATIN -> "Write in the language they used (not Arabic)."
                Script.OTHER -> "Write in the language they used."
            }
        )
        return sb.toString()
    }

    // ------------------------------------------------------------------ what the model is given

    /** Examples that show the length and the tone wanted; the topics are made up. */
    fun examples(script: Script): String {
        val sb = StringBuilder("\nExamples of the length and tone I want (made-up topics: copy the manner, never the words or the facts):\n")
        if (script == Script.ARABIC) {
            sb.append("Them: صباح الخير، عامل إيه؟\nMe: صباح النور، الحمد لله تمام. إنت أخبارك إيه؟\n\n")
            sb.append("Them: هتيجي النهارده؟ ولو جاي هتيجي الساعة كام؟\nMe: إن شاء الله جاي. بس لسه مش متأكد من الميعاد بالظبط، أول ما أتحرك هبعتلك أقولك.\n\n")
            sb.append("Them: الشغل النهارده كان تقيل أوي والمدير زعقلي قدام الكل\nMe: ده شيء وحش فعلًا وأكيد ضايقك. بس متشيلش هم، قولّي إيه اللي حصل بالظبط ونشوف نتصرف إزاي.\n\n")
            sb.append("Them: شكرا أوي على مساعدتك\nMe: العفو، ده واجب. أي وقت.\n")
        } else {
            sb.append("Them: Hey, how's it going?\nMe: Hey! Pretty good, thanks. How about you?\n\n")
            sb.append("Them: Are you coming tonight? And what time will you get there?\nMe: Yes, I'm planning to. I'm not sure about the exact time yet, but I'll message you as soon as I leave.\n\n")
            sb.append("Them: Work was awful today and my manager shouted at me in front of everyone.\nMe: That's really rough, I'm sorry. Try not to let it get to you. What happened exactly? Maybe we can figure out how to handle it.\n\n")
            sb.append("Them: Thanks so much for your help!\nMe: Anytime, glad I could help.\n")
        }
        return sb.toString()
    }

    /**
     * The chat as real turns (theirs = the user's turn, mine = the model's turn), ending with the message to answer and the guidance
     * for it. A chat model follows a conversation it is "in" far better than a transcript it is asked to read.
     */
    fun conversation(messages: List<ChatMessage>, plan: Plan, fix: String? = null): Convo {
        val turns = ArrayList(LlmPrompts.foldTurns(messages))
        val answering = turns.isNotEmpty() && turns.last().first != Side.ME
        val body = if (answering) turns.removeAt(turns.lastIndex).second else null
        val hist = ArrayList<Pair<Boolean, String>>()
        for (t in turns) hist.add((t.first == Side.ME) to t.second)
        var total = hist.sumOf { it.second.length }
        fun trimStart() {
            // the history has to open with their turn
            while (hist.isNotEmpty() && hist.first().first) total -= hist.removeAt(0).second.length
        }
        trimStart()
        while (hist.size > 1 && total > 3200) {
            total -= hist.removeAt(0).second.length
            trimStart()
        }
        val sb = StringBuilder()
        if (body != null) {
            sb.append(LlmPrompts.shorten(body, 600)).append("\n\n[Reply guidance, not part of the chat: ")
        } else {
            sb.append("[Write my next message to keep the conversation going. ")
        }
        sb.append(plan.note)
        if (!fix.isNullOrBlank()) sb.append(' ').append(fix)
        sb.append(']')
        return Convo(hist, sb.toString())
    }

    /** The guidance as one line to hang on the old transcript format (a game's chat, or when real turns are not available). */
    fun noteWithFix(plan: Plan, fix: String?): String = if (fix.isNullOrBlank()) plan.note else plan.note + " " + fix

    // ------------------------------------------------------------------ is the reply good enough

    private val LEAK = Regex("(?i)reply guidance|not part of the (?:chat|message)|\\[\\s*(?:write|reply|guidance)|<\\|?[a-z_]{2,20}\\|?>")

    private val ASSISTANT = Regex(
        "how can i (?:help|assist)|how may i (?:help|assist)|i(?:'m| am) here to (?:help|assist)|is there anything else|" +
            "anything else i can (?:help|do)|as an? (?:ai|assistant|language model)|i(?:'m| am) an? (?:ai|assistant)|" +
            "كيف (?:يمكنني|اقدر|ممكن) (?:ان )?(?:اساعد|اخدم)|كيف يمكنني مساعدتك|هل (?:تحتاج|تريد|تود) (?:الي )?(?:اي )?(?:مساعده|شيء اخر|شي اخر)|" +
            "انا هنا (?:ل|عشان |علشان )?(?:مساعدتك|اساعدك)|هل هناك (?:شيء|شي) اخر|هل من (?:شيء|شي) اخر|انا (?:مساعد|ذكاء) (?:ذكي|افتراضي|اصطناعي|الي)|كنموذج لغوي|بصفتي (?:ذكاء|مساعد|نموذج)"
    )

    private fun similar(a: String, b: String, threshold: Double): Boolean {
        val x = tokens(a)
        val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        if (x.size < 3 || y.size < 3) return false
        val sx = x.toSet()
        val sy = y.toSet()
        val inter = sx.count { it in sy }
        val union = sx.size + sy.size - inter
        return union > 0 && inter.toDouble() / union >= threshold
    }

    private fun quote(s: String): String = s.replace('\n', ' ').let { if (it.length > 80) it.take(80) + "…" else it }

    fun judge(reply: String?, messages: List<ChatMessage>, plan: Plan): Verdict {
        val r = reply?.trim().orEmpty()
        if (r.isEmpty()) return Verdict(Flaw.EMPTY, "Write the message itself.")
        if (LEAK.containsMatchIn(r)) {
            return Verdict(Flaw.LEAK, "Output only the message text itself: no notes, brackets, labels, names or instructions.")
        }
        if (ASSISTANT.containsMatchIn(ArabicText.normalize(r.replace('’', '\'')))) {
            return Verdict(
                Flaw.ASSISTANT,
                "Your last draft (\"${quote(r)}\") sounded like a customer-support assistant. Write like me, a normal person chatting: " +
                    "no offers of help, just my natural answer to what they said."
            )
        }
        val game = plan.kind == Kind.GAME
        if (!game && plan.checkScript && plan.script != Script.OTHER && letters(r) >= 5) {
            val ar = ArabicText.arabicRatio(r)
            if ((plan.script == Script.ARABIC && ar < 0.5) || (plan.script == Script.LATIN && ar > 0.5)) {
                return Verdict(
                    Flaw.WRONG_SCRIPT,
                    "Your last draft was in the wrong language. " + (if (plan.script == Script.ARABIC) "Write it in Arabic script, in their dialect." else "Write it in the language they used, not in Arabic.")
                )
            }
        }
        val theirs = theirNewest(messages, game)
        val echoable = plan.kind == Kind.QUESTION || plan.kind == Kind.QUESTIONS || plan.kind == Kind.REQUEST ||
            plan.kind == Kind.LONG || plan.kind == Kind.STATEMENT
        if (echoable && similar(r, theirs, 0.8)) {
            return Verdict(Flaw.ECHO, "Your last draft just repeated their message. Write my own answer to it instead.")
        }
        if (tokens(r).size >= 4 && messages.filter { it.side == Side.ME }.takeLast(8).any { similar(r, it.text, 0.85) }) {
            return Verdict(Flaw.REPEAT, "Your last draft repeats something I already wrote earlier in this chat. Say something new that moves the conversation on.")
        }
        val light = plan.kind == Kind.EMOJI || plan.kind == Kind.ACK || plan.kind == Kind.THANKS || plan.kind == Kind.GREETING
        val emojiOnly = letters(r) == 0 && light              // a thumbs-up is a fine answer to "ok"
        if (letters(r) < plan.minLetters && !emojiOnly) {
            return Verdict(
                Flaw.TOO_SHORT,
                "Your last draft (\"${quote(r)}\") was too short. Write a complete reply of ${plan.sentences} sentences that deals with everything they wrote, in the same language."
            )
        }
        return Verdict(null, null)
    }

    /** Of replies that were not good, the one worth sending anyway: only a merely short one qualifies, the longest of them. */
    fun pickBest(rejected: List<Candidate>): String? =
        rejected.filter { it.verdict.flaw?.hard == false }.maxByOrNull { letters(it.text) }?.text
}
