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
        Style("longkash", "مطّ طويل", Kind.ARABIC, true),
        Style("quran", "علامات المصحف", Kind.ARABIC, true),
        Style("supalef", "ألف صغيرة", Kind.ARABIC, true),
        Style("shadda", "شدّة", Kind.ARABIC, true),
        Style("tanween", "تنوين", Kind.ARABIC, true),
        // frames round the whole text
        Style("brackets", "أقواس", Kind.FRAME, false, "﴿ ", " ﴾"),
        Style("crown", "تاج", Kind.FRAME, false, "꧁༺ ", " ༻꧂"),
        Style("hearts", "قلوب", Kind.FRAME, false, "♥ ", " ♥"),
        Style("wave", "موجة", Kind.FRAME, false, "ﮩ٨ـﮩﮩ٨ـ♡ ", " ♡ـﮩ٨ـﮩﮩ٨ـ"),
        Style("stars", "نجوم", Kind.FRAME, false, "✦ ", " ✦"),
        Style("flowers", "زهور", Kind.FRAME, false, "✿ ", " ✿"),
        Style("sparkle", "لمعة", Kind.FRAME, false, "✨ ", " ✨"),
        Style("quote", "اقتباس", Kind.FRAME, false, "❝ ", " ❞"),
        Style("lens", "أقواس مربعة", Kind.FRAME, false, "【 ", " 】"),
        Style("moon", "هلال", Kind.FRAME, false, "☪ ", " ☪"),
        Style("arrows", "أسهم", Kind.FRAME, false, "«« ", " »»"),
        Style("lines", "خطوط", Kind.FRAME, false, "━━ ", " ━━"),
        Style("ornament", "زينة", Kind.FRAME, false, "•°¯`•• ", " ••´¯°•"),
        // English letters in fancy fonts
        Style("script", "Script", Kind.LATIN, true),
        Style("gothic", "Gothic", Kind.LATIN, true),
        Style("struck", "Double", Kind.LATIN, true),
        Style("bubble", "Bubble", Kind.LATIN, true),
        Style("wide", "Wide", Kind.LATIN, true),
        Style("smallcaps", "Small caps", Kind.LATIN, true),
        Style("bold", "Bold", Kind.LATIN, true),
        Style("italic", "Italic", Kind.LATIN, true),
        Style("bolditalic", "Bold Italic", Kind.LATIN, true),
        Style("cursive", "Cursive", Kind.LATIN, true),
        Style("fraktur", "Fraktur", Kind.LATIN, true),
        Style("sans", "Sans", Kind.LATIN, true),
        Style("sansbold", "Sans Bold", Kind.LATIN, true),
        Style("sansitalic", "Sans Italic", Kind.LATIN, true),
        Style("sansbolditalic", "Sans Bold Italic", Kind.LATIN, true),
        Style("mono", "Mono", Kind.LATIN, true),
        Style("squares", "Squares", Kind.LATIN, true),
        Style("blacksquares", "Black Squares", Kind.LATIN, true),
        Style("blackcircles", "Black Circles", Kind.LATIN, true),
        Style("parens", "Parentheses", Kind.LATIN, true),
        Style("superscript", "Superscript", Kind.LATIN, true),
        Style("strike", "Strike", Kind.LATIN, true),
        Style("underline", "Underline", Kind.LATIN, true),
        Style("dunder", "Double Underline", Kind.LATIN, true),
        Style("slash", "Slash", Kind.LATIN, true),
        Style("tilde", "Tilde", Kind.LATIN, true),
    )

    /** looks that keep the plain letter and put a combining mark after it (strike, underline...) */
    private val COMBINING: Map<String, Char> = mapOf(
        "strike" to '\u0336', "underline" to '\u0332', "dunder" to '\u0333', "slash" to '\u0338', "tilde" to '\u0334',
    )

    private fun isAlnum(cp: Int): Boolean = cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code || cp in '0'.code..'9'.code

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
            c == 'ۧ' || c == 'ۨ' || c in '۪'..'ۭ' || c in '\u0300'..'\u036F'

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

    /** the small signs of the Qur'an's text (they sit above a letter) and the three tanween */
    private const val QURAN = "\u06D6\u06D7\u06D8\u06D9\u06DA\u06DB\u06DC"
    private const val TANWEEN = "\u064B\u064C\u064D"

    /** the marks the newer Arabic looks put after a letter (null for a look that has none) */
    private fun extraMarks(id: String, c: Char, n: Int): String? = when (id) {
        "quran" -> QURAN[Math.floorMod(c.code + n * 3, QURAN.length)].toString()
        "supalef" -> "\u0670"
        "shadda" -> "\u0651" + FATHA
        "tanween" -> TANWEEN[Math.floorMod(c.code + n, TANWEEN.length)].toString()
        else -> null
    }

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
            "bold" -> if (up) 0x1D400 + u else if (lo) 0x1D41A + l else 0x1D7CE + d
            "italic" -> if (up) 0x1D434 + u else if (lo) (if (cp == 'h'.code) 0x210E else 0x1D44E + l) else cp
            "bolditalic" -> if (up) 0x1D468 + u else if (lo) 0x1D482 + l else 0x1D7CE + d
            "cursive" -> when {
                up -> when (cp.toChar()) {
                    'B' -> 0x212C; 'E' -> 0x2130; 'F' -> 0x2131; 'H' -> 0x210B; 'I' -> 0x2110; 'L' -> 0x2112; 'M' -> 0x2133; 'R' -> 0x211B
                    else -> 0x1D49C + u
                }
                lo -> when (cp.toChar()) {
                    'e' -> 0x212F; 'g' -> 0x210A; 'o' -> 0x2134
                    else -> 0x1D4B6 + l
                }
                else -> cp
            }
            "fraktur" -> when {
                up -> when (cp.toChar()) {
                    'C' -> 0x212D; 'H' -> 0x210C; 'I' -> 0x2111; 'R' -> 0x211C; 'Z' -> 0x2128
                    else -> 0x1D504 + u
                }
                lo -> 0x1D51E + l
                else -> cp
            }
            "sans" -> if (up) 0x1D5A0 + u else if (lo) 0x1D5BA + l else 0x1D7E2 + d
            "sansbold" -> if (up) 0x1D5D4 + u else if (lo) 0x1D5EE + l else 0x1D7EC + d
            "sansitalic" -> if (up) 0x1D608 + u else if (lo) 0x1D622 + l else cp
            "sansbolditalic" -> if (up) 0x1D63C + u else if (lo) 0x1D656 + l else 0x1D7EC + d
            "mono" -> if (up) 0x1D670 + u else if (lo) 0x1D68A + l else 0x1D7F6 + d
            "squares" -> if (up) 0x1F130 + u else if (lo) 0x1F130 + l else cp
            "blacksquares" -> if (up) 0x1F170 + u else if (lo) 0x1F170 + l else cp
            "blackcircles" -> if (up) 0x1F150 + u else if (lo) 0x1F150 + l else if (d == 0) 0x24FF else 0x2775 + d
            "parens" -> if (up) 0x1F110 + u else if (lo) 0x249C + l else if (d == 0) cp else 0x2473 + d
            "superscript" -> if (up) SUPER_UP[u].code else if (lo) SUPER_LO[l].code else SUPER_DIGITS[d].code
            else -> cp
        }
    }

    private const val SUPER_LO = "ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖqʳˢᵗᵘᵛʷˣʸᶻ"
    private const val SUPER_UP = "ᴬᴮᶜᴰᴱᶠᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾQᴿˢᵀᵁⱽᵂˣʸᶻ"
    private const val SUPER_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹"

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
        // superscript: the few capitals that have no raised form of their own share the lower case one
        m.remove('Q'.code); m.remove('q'.code)
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

    /** the text without the combining marks of the strike / underline looks */
    private fun stripCombining(text: String): String =
        if (text.none { it in '\u0300'..'\u036F' }) text else text.filter { it !in '\u0300'..'\u036F' }

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
                val src = unLatin(stripCombining(base))
                val sb = StringBuilder(src.length * 2)
                val mark = COMBINING[style.id]
                var i = 0
                while (i < src.length) {
                    val cp = src.codePointAt(i)
                    sb.appendCodePoint(latin(style.id, cp))
                    if (mark != null && isAlnum(cp)) sb.append(mark)
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
                "kashida", "longkash" -> {
                    sb.append(c)
                    if (!atEnd && joinsNext(c) && next != 'ء') repeat(if (id == "longkash") 4 else 2) { sb.append(TATWEEL) }
                }
                else -> {
                    sb.append(c)
                    if (takesMarks(c)) extraMarks(id, c, n)?.let { sb.append(it); n++ }
                }
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
                val mark = COMBINING[style.id]
                if (mark != null && isAlnum(cp)) sb.append(mark)
            } else {
                val c = text[i]
                if (len == 1 && isLetter(c)) {
                    when (style.id) {
                        "royal" -> { val v = variant(c); sb.append(v); if (takesMarks(v)) sb.append(royalMarks(c, count++)) }
                        "double" -> { sb.append(c); if (takesMarks(c)) { val m = if (count++ % 5 == 4) KASRA else FATHA; sb.append(m).append(m) } }
                        "tashkeel" -> { sb.append(c); if (takesMarks(c)) sb.append(PLAIN_MARKS[Math.floorMod(c.code + count++, PLAIN_MARKS.size)]) }
                        "letters" -> sb.append(variant(c))
                        "kashida", "longkash" -> { if (joinsNext(prev) && c != 'ء') repeat(if (style.id == "longkash") 4 else 2) { sb.append(TATWEEL) }; sb.append(c) }
                        else -> {
                            sb.append(c)
                            if (takesMarks(c)) extraMarks(style.id, c, count)?.let { sb.append(it); count++ }
                        }
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
