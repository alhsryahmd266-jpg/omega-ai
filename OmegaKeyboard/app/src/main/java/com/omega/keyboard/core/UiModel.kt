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
    private val ONLY_PUNCT_DIGITS = Regex("""^[\p{N}\p{P}\p{S}\s]*$""")
    private val URL = Regex("""^\s*(https?://|www\.)\S+\s*$""", RegexOption.IGNORE_CASE)

    fun isTime(s: String) = TIME.matches(s)
    fun isDateOrDay(s: String) = DATE_NUM.matches(s) || DATE_WORDS.matches(s) || DATE_LONG.matches(s)
    fun isStatusWord(s: String) = STATUS.matches(s)
    fun isPresence(s: String) = PRESENCE.containsMatchIn(s) && s.length < 80
    fun isSystemNotice(s: String) = SYSTEM_NOTICE.containsMatchIn(s)
    fun hasNoLetters(s: String) = ONLY_PUNCT_DIGITS.matches(s)
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
