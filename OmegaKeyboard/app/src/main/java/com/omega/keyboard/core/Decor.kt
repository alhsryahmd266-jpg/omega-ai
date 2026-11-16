package com.omega.keyboard.core

/**
 * Text decoration ("زخرفة"). Everything it makes is real text (letters, marks, symbols), so it arrives exactly as written in any
 * app: chats, games, notes. No pictures, no stickers. The result depends only on the text and the style, so the same words always
 * come out the same, and decorating an already decorated text gives the same result again.
 */
object Decor {
    enum class Kind { ARABIC, LATIN, FRAME }

    /** [live]: can be applied letter by letter while typing (a frame needs the whole text) */
    class Style(val id: String, val label: String, val kind: Kind, val live: Boolean, val open: String = "", val close: String = "")

    const val NONE = "none"

    val styles: List<Style> = listOf(
        // Arabic: marks and special letters
        Style("royal", "ملكي", Kind.ARABIC, true),
        Style("double", "حركتين", Kind.ARABIC, true),
        Style("tashkeel", "تشكيل", Kind.ARABIC, true),
        Style("letters", "حروف مزخرفة", Kind.ARABIC, true),
        Style("kashida", "مطّ", Kind.ARABIC, true),
        // frames round the whole text
        Style("brackets", "أقواس", Kind.FRAME, false, "﴿ ", " ﴾"),
        Style("crown", "تاج", Kind.FRAME, false, "꧁༺ ", " ༻꧂"),
        Style("hearts", "قلوب", Kind.FRAME, false, "♥ ", " ♥"),
        Style("wave", "موجة", Kind.FRAME, false, "ﮩ٨ـﮩﮩ٨ـ♡ ", " ♡ـﮩ٨ـﮩﮩ٨ـ"),
        // English letters in fancy fonts
        Style("script", "Script", Kind.LATIN, true),
        Style("gothic", "Gothic", Kind.LATIN, true),
        Style("struck", "Double", Kind.LATIN, true),
        Style("bubble", "Bubble", Kind.LATIN, true),
        Style("wide", "Wide", Kind.LATIN, true),
        Style("smallcaps", "Small caps", Kind.LATIN, true),
    )

    fun byId(id: String): Style? = styles.firstOrNull { it.id == id }

    private const val FATHA = 'َ'
    private const val DAMMA = 'ُ'
    private const val KASRA = 'ِ'
    private const val SUKUN = 'ْ'
    const val TATWEEL = 'ـ'
    private const val SWASH_KAF = 'ڪ'
    private const val YEH_BARREE = 'ﮯ'
    private const val ALEF_MADDA = 'آ'
    private const val ROYAL_END = " ♥✨"

    /** the stacks of marks the royal look puts after a letter, mostly fatha based, picked by the letter and its place in the text */
    private val ROYAL = arrayOf(
        "َِ", "ََ", "ُِ", "َُ", "ِِ", "ُِْ",
        "ٍَ", "ًَ", "ْْ", "َِْ", "ََ", "َِ",
    )
    private val PLAIN_MARKS = charArrayOf(FATHA, KASRA, DAMMA, FATHA, FATHA, KASRA)

    // ------------------------------------------------------------------ letters

    fun isLetter(c: Char): Boolean =
        c in 'ء'..'ي' || c == 'ٱ' || c in 'ٮ'..'ۓ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'ﻼ'

    fun isMark(c: Char): Boolean =
        c in 'ً'..'ٟ' || c == 'ٰ' || c in 'ۖ'..'ۜ' || c in '۟'..'ۤ' ||
            c == 'ۧ' || c == 'ۨ' || c in '۪'..'ۭ'

    /** letters that do not join the letter after them: no stretch bar goes behind them */
    private const val NO_JOIN_NEXT = "ءآأؤإادذرزوةىﮯٱ"

    private fun joinsNext(c: Char): Boolean = isLetter(c) && NO_JOIN_NEXT.indexOf(c) < 0

    /** the special forms of the royal look: alef with madda, swash kaf, the long yeh at the end of "ى" */
    private fun variant(c: Char): Char = when (c) {
        'ا' -> ALEF_MADDA
        'ك' -> SWASH_KAF
        'ى' -> YEH_BARREE
        else -> c
    }

    /** marks never sit on an alef or on the long yeh */
    private fun takesMarks(c: Char): Boolean = c != 'ا' && c != 'آ' && c != 'ٱ' && c != YEH_BARREE

    private fun royalMarks(c: Char, n: Int): String = ROYAL[Math.floorMod(c.code * 7 + n * 5 + n / 3, ROYAL.size)]

    // ------------------------------------------------------------------ English letters in other fonts

    private const val SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ"

    private fun latin(id: String, cp: Int): Int {
        val up = cp in 'A'.code..'Z'.code
        val lo = cp in 'a'.code..'z'.code
        val dg = cp in '0'.code..'9'.code
        if (!up && !lo && !dg) return cp
        val u = cp - 'A'.code
        val l = cp - 'a'.code
        val d = cp - '0'.code
        return when (id) {
            "script" -> if (up) 0x1D4D0 + u else if (lo) 0x1D4EA + l else cp
            "gothic" -> if (up) 0x1D56C + u else if (lo) 0x1D586 + l else cp
            "struck" -> when {
                up -> when (cp.toChar()) {
                    'C' -> 0x2102; 'H' -> 0x210D; 'N' -> 0x2115; 'P' -> 0x2119; 'Q' -> 0x211A; 'R' -> 0x211D; 'Z' -> 0x2124
                    else -> 0x1D538 + u
                }
                lo -> 0x1D552 + l
                else -> 0x1D7D8 + d
            }
            "bubble" -> if (up) 0x24B6 + u else if (lo) 0x24D0 + l else if (d == 0) 0x24EA else 0x2460 + d - 1
            "wide" -> if (up) 0xFF21 + u else if (lo) 0xFF41 + l else 0xFF10 + d
            "smallcaps" -> if (up) SMALL_CAPS[u].code else if (lo) SMALL_CAPS[l].code else cp
            else -> cp
        }
    }

    /** every fancy English letter back to the plain one */
    private val latinBack: Map<Int, Int> by lazy {
        val m = HashMap<Int, Int>()
        for (s in styles) if (s.kind == Kind.LATIN) {
            for (cp in ('0'.code..'9'.code) + ('A'.code..'Z'.code) + ('a'.code..'z'.code)) {
                val x = latin(s.id, cp)
                if (x != cp) m[x] = cp
            }
        }
        // small caps have one form for both cases: the lower case wins
        for (cp in 'a'.code..'z'.code) {
            val x = SMALL_CAPS[cp - 'a'.code].code
            if (x != cp) m[x] = cp
        }
        m.remove('x'.code)           // the small cap x is the plain x
        m
    }

    // ------------------------------------------------------------------ the whole text

    /** the text without any frame or closing ornament of ours */
    private fun unwrap(text: String): String {
        var core = text.trim()
        if (core.isEmpty()) return text
        if (core.endsWith(ROYAL_END.trim())) core = core.removeSuffix(ROYAL_END.trim()).trimEnd()
        for (s in styles) {
            if (s.kind != Kind.FRAME) continue
            val o = s.open.trim()
            val c = s.close.trim()
            if (core.length > o.length + c.length && core.startsWith(o) && core.endsWith(c)) {
                core = core.substring(o.length, core.length - c.length).trim()
                break
            }
        }
        return core
    }

    /** Arabic without marks, stretch bars and special letters (a word that carried marks gets its plain alef back) */
    private fun unArabic(text: String): String {
        val sb = StringBuilder(text.length)
        var wordStart = 0
        var marked = false
        fun endWord() {
            if (marked) for (i in wordStart until sb.length) if (sb[i] == ALEF_MADDA) sb.setCharAt(i, 'ا')
            marked = false
        }
        for (ch in text) {
            if (ch.isWhitespace()) { endWord(); sb.append(ch); wordStart = sb.length; continue }
            when {
                isMark(ch) -> marked = true
                ch == TATWEEL -> {}
                ch == SWASH_KAF -> sb.append('ك')
                ch == YEH_BARREE -> sb.append('ى')
                else -> sb.append(ch)
            }
        }
        endWord()
        return sb.toString()
    }

    private fun unLatin(text: String): String {
        var any = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (latinBack.containsKey(cp)) { any = true; break }
            i += Character.charCount(cp)
        }
        if (!any) return text
        val sb = StringBuilder(text.length)
        i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            sb.appendCodePoint(latinBack[cp] ?: cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** the text as it was before any decoration of ours (best effort: a text that was never decorated comes back unchanged) */
    fun plain(text: String): String {
        if (text.isBlank()) return text
        val lead = text.takeWhile { it.isWhitespace() }
        val tail = text.takeLastWhile { it.isWhitespace() }
        return lead + unLatin(unArabic(unwrap(text))) + tail
    }

    /** decorates the whole text; [NONE] (or an unknown id) takes the decoration off */
    fun apply(styleId: String, text: String): String {
        val s = byId(styleId) ?: return plain(text)
        return apply(s, text)
    }

    fun apply(style: Style, text: String): String {
        if (text.isBlank()) return text
        val lead = text.takeWhile { it.isWhitespace() }
        val tail = text.takeLastWhile { it.isWhitespace() }
        val base = unwrap(text)
        val body = when (style.kind) {
            Kind.ARABIC -> arabic(style.id, unArabic(base))
            Kind.LATIN -> {
                val src = unLatin(base)
                val sb = StringBuilder(src.length * 2)
                var i = 0
                while (i < src.length) {
                    val cp = src.codePointAt(i)
                    sb.appendCodePoint(latin(style.id, cp))
                    i += Character.charCount(cp)
                }
                sb.toString()
            }
            Kind.FRAME -> style.open + base + style.close
        }
        return lead + body + tail
    }

    private fun arabic(id: String, base: String): String {
        val sb = StringBuilder(base.length * 3)
        var n = 0
        for (i in base.indices) {
            val c = base[i]
            if (!isLetter(c)) { sb.append(c); continue }
            val next = base.getOrNull(i + 1)
            val atEnd = next == null || !isLetter(next)
            when (id) {
                "royal" -> {
                    val v = variant(c)
                    sb.append(v)
                    if (takesMarks(v)) sb.append(royalMarks(c, n++))
                }
                "double" -> {
                    sb.append(c)
                    if (takesMarks(c)) { val m = if (n++ % 5 == 4) KASRA else FATHA; sb.append(m).append(m) }
                }
                "tashkeel" -> {
                    sb.append(c)
                    if (takesMarks(c)) sb.append(if (atEnd) SUKUN else PLAIN_MARKS[Math.floorMod(c.code + n++, PLAIN_MARKS.size)])
                }
                "letters" -> sb.append(variant(c))
                "kashida" -> {
                    sb.append(c)
                    if (!atEnd && joinsNext(c) && next != 'ء') sb.append(TATWEEL).append(TATWEEL)
                }
                else -> sb.append(c)
            }
        }
        if (id == "royal" && base.isNotBlank()) sb.append(ROYAL_END)
        return sb.toString()
    }

    // ------------------------------------------------------------------ while typing

    /**
     * What to put in the box for the letters [text] just typed, with [before] (the text in front of the cursor) and [n] (how many
     * letters were decorated so far, so the marks vary from letter to letter). Frames cannot be typed letter by letter.
     */
    fun typed(style: Style, text: String, before: CharSequence, n: Int): String {
        if (!style.live || text.isEmpty()) return text
        val sb = StringBuilder(text.length * 3)
        var count = n
        var prev = before.lastOrNull() ?: ' '
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            if (style.kind == Kind.LATIN) {
                sb.appendCodePoint(latin(style.id, cp))
            } else {
                val c = text[i]
                if (len == 1 && isLetter(c)) {
                    when (style.id) {
                        "royal" -> { val v = variant(c); sb.append(v); if (takesMarks(v)) sb.append(royalMarks(c, count++)) }
                        "double" -> { sb.append(c); if (takesMarks(c)) { val m = if (count++ % 5 == 4) KASRA else FATHA; sb.append(m).append(m) } }
                        "tashkeel" -> { sb.append(c); if (takesMarks(c)) sb.append(PLAIN_MARKS[Math.floorMod(c.code + count++, PLAIN_MARKS.size)]) }
                        "letters" -> sb.append(variant(c))
                        "kashida" -> { if (joinsNext(prev) && c != 'ء') sb.append(TATWEEL).append(TATWEEL); sb.append(c) }
                        else -> sb.append(c)
                    }
                } else sb.appendCodePoint(cp)
            }
            prev = text[i + len - 1]
            i += len
        }
        return sb.toString()
    }

    /** how many letters of a typed text carry decoration (so the next one gets a different stack of marks) */
    fun letterCount(text: String): Int = text.count { isLetter(it) }

    // ------------------------------------------------------------------ deleting

    /** a text with marks or stretch bars on it (backspace takes a letter together with them) */
    fun hasDecoration(s: CharSequence): Boolean = s.any { isMark(it) || it == TATWEEL }

    /**
     * How many characters one press of backspace removes from the end of [before]: a decorated letter goes together with its marks
     * (and the two stretch bars of the stretched style), a plain letter or a symbol goes alone.
     */
    fun unitBefore(before: CharSequence): Int {
        val n = before.length
        if (n == 0) return 0
        var i = n
        if (before[i - 1] == TATWEEL) {
            while (i > 0 && before[i - 1] == TATWEEL) i--
            return n - i
        }
        while (i > 0 && isMark(before[i - 1])) i--
        if (i > 0) {
            i--
            if (i > 0 && Character.isLowSurrogate(before[i]) && Character.isHighSurrogate(before[i - 1])) i--
        }
        var j = i
        while (j > 0 && before[j - 1] == TATWEEL) j--
        if (i - j >= 2) i = j
        return n - i
    }

    // ------------------------------------------------------------------ the panel

    /** the words the previews of the panel are shown with: the user's own text when it fits the style, else an example */
    fun sampleFor(style: Style?, field: String): String {
        val t = field.trim().replace('\n', ' ').take(36)
        val arabic = t.any { isLetter(it) }
        val latinText = t.any { it in 'A'..'Z' || it in 'a'..'z' }
        return when (style?.kind) {
            Kind.LATIN -> if (latinText) t else "Omega Keyboard"
            Kind.ARABIC -> if (arabic) t else "لا يسيء الظن بك"
            else -> if (t.isNotEmpty()) t else "مرحبا بك"
        }
    }
}
