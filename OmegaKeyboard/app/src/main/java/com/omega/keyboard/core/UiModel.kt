package com.omega.keyboard.core

/**
 * A snapshot-independent copy of one accessibility node. The Android layer fills these in; everything that
 * decides things (who said what, where the send button is, what to translate) works on this pure model so it can
 * be unit-tested without a phone.
 */
data class UiNode(
    val id: Int,
    val parentId: Int,
    val depth: Int,
    val text: String,
    val desc: String,
    val viewId: String,
    val cls: String,
    val pkg: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val editable: Boolean = false,
    val clickable: Boolean = false,
    /** clickable itself or through one of its ancestors */
    val actionable: Boolean = clickable,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val focused: Boolean = false,
    val password: Boolean = false,
    val showingHint: Boolean = false,
    val childCount: Int = 0,
    /** a scrolling view that can still move towards its end (there is more content below / after what is shown) */
    val scrollDown: Boolean = false,
    /** ... and towards its start */
    val scrollUp: Boolean = false,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val area: Long get() = width.toLong().coerceAtLeast(0) * height.toLong().coerceAtLeast(0)

    /** what a human sees/hears for this node */
    val label: String get() = text.ifBlank { desc }
    val shortId: String get() = viewId.substringAfter('/', viewId).lowercase()

    fun contains(o: UiNode): Boolean = o.left >= left - 2 && o.right <= right + 2 && o.top >= top - 2 && o.bottom <= bottom + 2
}

class Screen(
    val pkg: String,
    val width: Int,
    val height: Int,
    val density: Float,
    val nodes: List<UiNode>,
    val rtl: Boolean = false,
) {
    fun dp(v: Float): Int = (v * density).toInt()
    fun dp(v: Int): Int = (v * density).toInt()
}

/** Decisions about scrolling a conversation to its newest messages. Pure, so it is tested without a phone. */
object ScrollLogic {
    /**
     * The big vertical list (the conversation) that still has content below the visible part, or null when we are
     * already at its end. Small scrollers (a multi-line text box, a chip row) never count.
     */
    fun listWithMoreBelow(screen: Screen): UiNode? {
        val minH = screen.height * 0.28
        val minW = screen.width * 0.45
        return screen.nodes
            .filter { it.scrollDown && it.visible && it.enabled && !it.editable && it.height >= minH && it.width >= minW }
            .maxByOrNull { it.area }
    }

    /** Where to put a finger for a swipe that moves the list towards its newest end: from low to high inside the list. */
    fun swipeUpPath(list: UiNode): IntArray {
        val x = list.centerX
        val y1 = list.bottom - (list.height * 0.18).toInt()
        val y2 = list.top + (list.height * 0.22).toInt()
        return intArrayOf(x, y1, y2)
    }

    /** What the list currently shows, to notice that a scroll did nothing (the same text at the same places). */
    fun signature(screen: Screen, list: UiNode): Int {
        var h = 17
        for (n in screen.nodes) {
            if (!n.visible || n.label.isBlank() || !list.contains(n)) continue
            h = h * 31 + (n.label.hashCode() xor (n.top / 6))
        }
        return h
    }
}

object Matchers {
    private val TIME = Regex("""^\s*\d{1,2}[:.٫]\d{2}(\s?([AaPp]\.?[Mm]\.?|ص|م|صباحا|مساء))?\s*$""")
    private val DATE_NUM = Regex("""^\s*\d{1,4}[/.\-]\d{1,2}[/.\-]\d{1,4}\s*$""")
    private val DATE_WORDS = Regex(
        """^\s*(today|yesterday|اليوم|أمس|امس|الأمس|gestern|heute|hier|aujourd'hui|ayer|hoy|сегодня|вчера|""" +
            """(mon|tues?|wed(nes)?|thu(rs)?|fri|sat(ur)?|sun)(day)?|""" +
            """الاثنين|الإثنين|الثلاثاء|الأربعاء|الاربعاء|الخميس|الجمعة|السبت|الأحد|الاحد)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val DATE_LONG = Regex(
        """^\s*(\d{1,2}\s+\p{L}+\.?,?\s+\d{2,4}|\p{L}+\.?\s+\d{1,2},?\s+\d{2,4})\s*$"""
    )
    private val STATUS = Regex(
        """^\s*(read|delivered|sent|pending|seen|failed|edited|starred|تمت القراءة|تم التسليم|تم الإرسال|تم الارسال|مقروءة|""" +
            """مقروء|تم الاستلام|قيد الإرسال|معدلة|تم التعديل|مُعدّلة)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val PRESENCE = Regex(
        """(typing|يكتب|يكتب\.\.\.|recording|يسجل|online|متصل|last seen|آخر ظهور|اخر ظهور|""" +
            """tap here for contact info|اضغط هنا لمعلومات|click here for contact info)""",
        RegexOption.IGNORE_CASE
    )
    private val SYSTEM_NOTICE = Regex(
        """(end-to-end|مشفرة|مشفّرة|encrypted|unread messages?|رسائل غير مقروءة|رسالة غير مقروءة|""" +
            """changed their phone number|you blocked|you're now|messages disappear|الرسائل المؤقتة)""",
        RegexOption.IGNORE_CASE
    )
    /**
     * The small print AI chat apps show under the conversation ("Claude can make mistakes. Please double-check responses."):
     * never a message. Anchored on the product name at the start of the line, so a friend writing "I can make mistakes"
     * or "ممكن يغلط" is still read as a message.
     */
    private const val AI_NAMES = """(?:claude|chat ?gpt|gpt-?\d[\w.]*|gemini|bard|copilot|grok|perplexity|deepseek|meta ai|mistral|le chat|كلود|شات ?جي ?بي ?تي|جيميني|جيمني)"""
    private const val AI_MISTAKES = """(?:can make mistakes|may make mistakes|can be wrong|can be inaccurate|may be inaccurate|may display inaccurate|may produce inaccurate|can sometimes be wrong|قد يرتكب أخطاء|يمكن أن يرتكب أخطاء|ممكن يرتكب أخطاء|قد يخطئ|يمكن أن يخطئ|قد يعرض معلومات غير دقيقة|قد يقدم معلومات غير دقيقة)"""
    private val AI_DISCLAIMER = Regex(
        """^\s*(?:$AI_NAMES\s.{0,16}?$AI_MISTAKES|messages are generated by ai|(?:please |kindly )?double-?check (?:the )?(?:responses?|answers?)\.?\s*$|check important info\.?\s*$|(?:يرجى |الرجاء |برجاء )?(?:التحقق|تحقق) من (?:الردود|الاجابات|الإجابات|المعلومات المهمة)\.?\s*$)""",
        RegexOption.IGNORE_CASE
    )
    /** the grey prompt of an empty message box ("Reply to Claude", "اكتب رسالة"): some apps hand it over as if it were typed text */
    private val COMPOSER_HINT = Regex(
        """^\s*(?:reply|message|ask|type|write|send|say|talk|chat|enter|start typing|اكتب|اكتبي|رسالة|رسالتك|اسأل|رد|ارسل|أرسل|تحدث|قل|راسل)(?:\s|$|[.…:])""",
        RegexOption.IGNORE_CASE
    )
    private val ONLY_PUNCT_DIGITS = Regex("""^[\p{N}\p{P}\p{S}\s]*$""")
    private val URL = Regex("""^\s*(https?://|www\.)\S+\s*$""", RegexOption.IGNORE_CASE)

    fun isTime(s: String) = TIME.matches(s)
    fun isDateOrDay(s: String) = DATE_NUM.matches(s) || DATE_WORDS.matches(s) || DATE_LONG.matches(s)
    fun isStatusWord(s: String) = STATUS.matches(s)
    fun isPresence(s: String) = PRESENCE.containsMatchIn(s) && s.length < 80
    fun isSystemNotice(s: String) = SYSTEM_NOTICE.containsMatchIn(s) || isAiDisclaimer(s)
    fun isAiDisclaimer(s: String) = s.length < 160 && AI_DISCLAIMER.containsMatchIn(s)
    fun hasNoLetters(s: String) = ONLY_PUNCT_DIGITS.matches(s)

    /** True when what a message box reports is only its prompt (or nothing), not something the user wrote. */
    fun isComposerHint(text: String, desc: String = ""): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (desc.isNotBlank() && t.equals(desc.trim(), ignoreCase = true)) return true
        val words = t.split(Regex("\\s+")).size
        return words <= 6 && t.none { it in ".!?،؟" } && COMPOSER_HINT.containsMatchIn(t)
    }
    fun isUrlOnly(s: String) = URL.matches(s)

    /** things that are never a chat message: clock times, date separators, ticks, presence, notices */
    fun isChatNoise(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return true
        return isTime(t) || isDateOrDay(t) || isStatusWord(t) || isPresence(t) || isSystemNotice(t) || hasNoLetters(t) && t.length < 4
    }

    fun containsAny(haystack: String, needles: List<String>): Boolean {
        val h = haystack.lowercase()
        return needles.any { h.contains(it.lowercase()) }
    }
}
