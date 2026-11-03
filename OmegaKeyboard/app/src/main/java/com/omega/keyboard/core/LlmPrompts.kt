package com.omega.keyboard.core

/** Prompt builders and output parsers for the local model. Pure, so the parsing rules are unit-tested. */
object LlmPrompts {
    const val NO_REPLY = "NO_REPLY"
    const val DEFAULT_PERSONA = "أسلوبي عفوي ومحترم ومختصر، وبرد بنفس لهجة اللي بيكلمني."

    // ------------------------------------------------------------------ chat replies

    fun replySystem(persona: String, instructions: List<String>): String {
        val sb = StringBuilder()
        sb.append("You are me, answering my own chat messages on my phone. Write the next message I would send.\n")
        sb.append("Rules:\n")
        sb.append("- Reply in the same language and dialect the other person writes in (for example Egyptian Arabic in Arabic script).\n")
        sb.append("- Sound like a real person texting: short (usually one or two sentences), natural, no lists, no markdown, no quotation marks around the message.\n")
        sb.append("- Never say you are an AI, a model or an assistant, and never explain what you are doing.\n")
        sb.append("- Do not invent facts, plans, prices, addresses or promises that are not in the conversation or in my instructions. If you do not know, answer briefly and honestly or ask one short question.\n")
        sb.append("- Output ONLY the message text. If no reply is needed, output exactly $NO_REPLY.\n")
        if (persona.isNotBlank()) sb.append("\nMy style: ").append(persona.trim()).append('\n')
        if (instructions.isNotEmpty()) {
            sb.append("\nInstructions I gave you just now (follow them, the newest matters most):\n")
            instructions.takeLast(6).forEach { sb.append("- ").append(it.trim()).append('\n') }
        }
        return sb.toString()
    }

    /** The conversation as the model reads it: consecutive messages of one side merged into one turn, newest last. */
    private fun conversation(messages: List<ChatMessage>): String {
        val turns = ArrayList<Pair<Side, StringBuilder>>()
        for (m in messages.takeLast(24)) {
            val side = if (m.side == Side.UNKNOWN) Side.THEM else m.side
            val full = m.text.trim()
            val t = if (full.length > 500) full.take(200) + " … " + full.takeLast(300) else full
            if (turns.isNotEmpty() && turns.last().first == side) turns.last().second.append('\n').append(t)
            else turns.add(side to StringBuilder(t))
        }
        val sb = StringBuilder("Conversation so far (oldest first):\n")
        var used = 0
        val kept = ArrayList<String>()
        for (t in turns.asReversed()) {
            val line = (if (t.first == Side.ME) "Me: " else "Them: ") + t.second
            if (used + line.length > 2200 && kept.isNotEmpty()) break
            kept.add(line); used += line.length
        }
        kept.asReversed().forEach { sb.append(it).append('\n') }
        return sb.toString()
    }

    fun replyUser(messages: List<ChatMessage>): String = conversation(messages) + "\nWrite my next message.\n"

    /**
     * An order the model has to word ("اسأله هو جاي ولا لأ"). The order is given as data and the model is told not to
     * repeat it: small models like to echo an instruction back, and [echoes] catches the ones that still do.
     */
    fun composeUser(messages: List<ChatMessage>, instruction: String): String {
        val sb = StringBuilder(conversation(messages))
        sb.append("\nI told you out loud (in Egyptian Arabic): \"").append(instruction.trim()).append("\"\n")
        sb.append("That is an instruction for you, not the text to send. Write the message I should send to them right now, ")
            .append("in the first person, in the language of the chat, short and natural. ")
            .append("Do not repeat my instruction and do not start with a command such as \"tell him\" or \"قول له\". Output only the message.\n")
        return sb.toString()
    }

    private fun flat(s: String) = ArabicText.normalize(s).replace(Regex("[\\p{P}\\p{S}]"), " ").replace(Regex("\\s+"), " ").trim()

    private val COMMAND_WORDS = setOf(
        "قول", "قوله", "قولها", "قولهم", "قل", "اسال", "اساله", "اسالها", "اسالهم", "ابعت", "ابعتله", "اكتب", "اكتبله", "ارسل", "ارسله",
        "tell", "say", "ask", "send", "write",
    )

    /**
     * True when a model "reply" is really just the spoken order handed back (it would be sent as it is, command word
     * and all). A message that merely contains the same words as the order ("هو جاي ولا لأ؟" for "اسأله هو جاي ولا لأ")
     * is exactly what we want and is not an echo.
     */
    fun echoes(reply: String, instruction: String): Boolean {
        val r = flat(reply)
        val i = flat(instruction)
        if (r.isEmpty() || i.isEmpty()) return false
        if (r == i) return true
        val first = r.substringBefore(' ')
        if (r.contains(' ') && (first == i.substringBefore(' ') || first in COMMAND_WORDS)) return true
        return i.length >= 8 && r.contains(i)
    }

    private val LABEL_PREFIX = Regex("^\\s*(?:me|reply|my reply|message|أنا|انا|الرد|رد)\\s*[:：]\\s*", RegexOption.IGNORE_CASE)
    private val STOP_AT = Regex("(?m)^\\s*(?:them|me|أنا|انا|هو|هي)\\s*[:：]", RegexOption.IGNORE_CASE)
    private val QUOTES = charArrayOf('"', '“', '”', '«', '»', '\'', '‘', '’', '`')

    /** @return the message to send, or null when the model decided not to answer / produced nothing usable */
    fun cleanReply(raw: String, maxChars: Int = 700): String? {
        var s = raw.replace("<end_of_turn>", "").replace("<start_of_turn>", "").replace("<eos>", "").trim()
        s = s.removePrefix("```").removeSuffix("```").trim()
        if (s.isEmpty()) return null
        if (s.contains(NO_REPLY, ignoreCase = true) && s.length < NO_REPLY.length + 12) return null
        s = LABEL_PREFIX.replace(s, "")
        val cut = STOP_AT.find(s)
        if (cut != null && cut.range.first > 0) s = s.substring(0, cut.range.first)
        s = s.trim().trim(*QUOTES).trim()
        if (s.isEmpty()) return null
        if (s.length > maxChars) {
            val head = s.take(maxChars)
            val end = maxOf(head.lastIndexOf('.'), head.lastIndexOf('؟'), head.lastIndexOf('?'), head.lastIndexOf('!'), head.lastIndexOf('\n'))
            s = if (end > maxChars / 2) head.substring(0, end + 1) else head
        }
        if (Regex("(as an ai|language model|كنموذج لغوي|بصفتي ذكاء اصطناعي)", RegexOption.IGNORE_CASE).containsMatchIn(s)) return null
        return s.trim()
    }

    // ------------------------------------------------------------------ translation

    fun translateSystem(targetEnglish: String): String =
        "You are a precise translation engine. Translate every numbered item into $targetEnglish. " +
            "Keep the numbering. Keep names, numbers, URLs, hashtags and emoji unchanged. " +
            "Do not add explanations, notes or quotation marks. " +
            "Output exactly one line per item in the form N|translation (replace any line break inside an item with a space)."

    fun translateUser(items: List<Pair<Int, String>>): String =
        items.joinToString("\n") { "${it.first}|${it.second.replace('\n', ' ').trim()}" }

    private val NUMBERED = Regex("^\\s*(\\d{1,4})\\s*[|｜:：.)\\-–]\\s*(.*)$")

    /** Reads "N|text" lines; unnumbered lines continue the previous item. Unknown ids are ignored. */
    fun parseTranslations(output: String, ids: Collection<Int>): Map<Int, String> {
        val allowed = ids.toSet()
        val res = LinkedHashMap<Int, String>()
        var cur = -1
        for (line in output.replace("<end_of_turn>", "").lineSequence()) {
            val m = NUMBERED.find(line)
            if (m != null && m.groupValues[1].toInt() in allowed) {
                cur = m.groupValues[1].toInt()
                res[cur] = m.groupValues[2].trim()
            } else if (cur >= 0 && line.isNotBlank() && NUMBERED.find(line) == null) {
                res[cur] = (res[cur] + " " + line.trim()).trim()
            }
        }
        return res.filterValues { it.isNotBlank() }
    }

    fun <T> batches(items: List<T>, size: (T) -> Int, maxChars: Int = 800, maxItems: Int = 10): List<List<T>> {
        val out = ArrayList<List<T>>()
        var cur = ArrayList<T>()
        var chars = 0
        for (it in items) {
            val sz = size(it) + 6
            if (cur.isNotEmpty() && (chars + sz > maxChars || cur.size >= maxItems)) { out.add(cur); cur = ArrayList(); chars = 0 }
            cur.add(it); chars += sz
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    fun typedTranslateSystem(targetEnglish: String, sourceEnglish: String? = null): String =
        "Translate the user's text" + (if (sourceEnglish != null) " from $sourceEnglish" else "") +
            " into $targetEnglish. Output only the translation: no quotation marks, no notes, no explanations. " +
            "Keep line breaks, emoji, names, numbers and links unchanged."

    fun cleanTranslation(raw: String): String {
        var s = raw.replace("<end_of_turn>", "").trim()
        s = s.removePrefix("```").removeSuffix("```").trim()
        if (s.length >= 2 && s.first() in QUOTES && s.last() in QUOTES) s = s.substring(1, s.length - 1).trim()
        return s
    }

    // ------------------------------------------------------------------ keyboard "AI actions"

    fun rewriteSystem(mode: String): String = when (mode) {
        "fix" -> "Fix the spelling, grammar and punctuation of the user's text. Keep the language, meaning and tone. Output only the corrected text."
        "formal" -> "Rewrite the user's text in a polite, formal tone. Keep the language and meaning. Output only the rewritten text."
        "casual" -> "Rewrite the user's text in a friendly, casual tone. Keep the language and meaning. Output only the rewritten text."
        "short" -> "Make the user's text shorter and clearer. Keep the language and meaning. Output only the shortened text."
        else -> "Improve the user's text. Keep the language and meaning. Output only the improved text."
    }
}
