package com.omega.keyboard.core

enum class KeyKind { CHAR, SHIFT, BACKSPACE, SYMBOLS, SYMBOLS2, LETTERS, ENTER, SPACE, LANG, EMOJI, ALT, SPACER }

enum class LayoutKind { LETTERS, SYMBOLS1, SYMBOLS2, ARABIC_ALT, NUMERIC }

/**
 * One key. [label] is what is drawn, [text] is what gets committed (they differ for diacritics and the "لا" key),
 * [hint] is the small character drawn in the corner, [popups] are the long-press alternatives, [fn] draws the key in the
 * function-key colour (comma and period, like Gboard).
 */
data class Key(
    val kind: KeyKind,
    val label: String = "",
    val text: String = label,
    val hint: String = "",
    val popups: List<String> = emptyList(),
    val weight: Float = 1f,
    val fn: Boolean = false,
)

data class Row(val keys: List<Key>) {
    val total: Float get() = keys.sumOf { it.weight.toDouble() }.toFloat()
}

/**
 * [rtl] is kept for completeness only: no layout is mirrored any more. The keys keep their physical places exactly like on
 * Gboard (the Arabic rows read from the left, ض is the first key of the top row), and so do the function keys.
 */
data class Layout(val id: String, val lang: String, val kind: LayoutKind, val rows: List<Row>, val rtl: Boolean)

object Layouts {
    /** Every Arabic page is 11 key-widths wide (Gboard has 11 keys in its first two rows) so the bottom row never moves. */
    private const val AR_UNITS = 11f

    private fun c(label: String, hint: String = "", popups: List<String> = emptyList(), w: Float = 1f, fn: Boolean = false) =
        Key(KeyKind.CHAR, label, label, hint, popups, w, fn)

    private fun spacer(w: Float) = Key(KeyKind.SPACER, weight = w)

    private fun digit(i: Int, indic: Boolean): String {
        val d = ((i + 1) % 10).toString()
        return if (indic) ArabicText.toArabicIndicDigits(d) else d
    }

    private val EN_ACCENTS = mapOf(
        "a" to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"),
        "e" to listOf("è", "é", "ê", "ë", "ē", "ė", "ę"),
        "i" to listOf("î", "ï", "í", "ī", "į", "ì"),
        "o" to listOf("ô", "ö", "ò", "ó", "œ", "ø", "ō", "õ"),
        "u" to listOf("û", "ü", "ù", "ú", "ū"),
        "c" to listOf("ç", "ć", "č"),
        "n" to listOf("ñ", "ń"),
        "s" to listOf("ß", "ś", "š"),
        "y" to listOf("ÿ", "ý"),
        "z" to listOf("ž", "ź", "ż"),
        "l" to listOf("ł"),
        "d" to listOf("ð"),
        "t" to listOf("þ"),
    )

    private val AR_POPUPS = mapOf(
        "ا" to listOf("أ", "إ", "آ", "ء", "ٱ"),
        "ء" to listOf("ئ"),
        "و" to listOf("ؤ"),
        "ي" to listOf("ئ", "ى"),
        "ه" to listOf("ة"),
        "ب" to listOf("پ"),
        "ج" to listOf("چ"),
        "ف" to listOf("ڤ"),
        "ك" to listOf("گ"),
        "ز" to listOf("ژ"),
        "ل" to listOf("لا", "لأ", "لإ", "لآ"),
    )

    private fun bottomRow(lang: String, switchKey: Key, total: Float): Row {
        val ar = lang == "ar"
        val sw = switchKey.copy(weight = if (ar) 1.5f else 1.3f)
        val comma = if (ar) c("،", popups = listOf("،", "؛", "؟", ",", "!", ":", "'", "\""), w = 1f, fn = true)
        else c(",", popups = listOf(",", ";", ":", "!", "?", "'", "\""), w = 0.9f, fn = true)
        val emoji = Key(KeyKind.EMOJI, "😊", weight = 1f)
        val globe = Key(KeyKind.LANG, "🌐", weight = 1f)
        val period = c(".", popups = listOf(".", "…", "?", "!", ":", ";", "-", "_", "@", "/"), w = if (ar) 1f else 0.9f, fn = true)
        val enter = Key(KeyKind.ENTER, "↵", weight = if (ar) 1.5f else 1.3f)
        val fixed = sw.weight + comma.weight + emoji.weight + globe.weight + period.weight + enter.weight
        val space = Key(KeyKind.SPACE, " ", " ", weight = total - fixed)
        return Row(listOf(sw, comma, emoji, globe, space, period, enter))
    }

    fun english(numberRow: Boolean): Layout {
        val top = "qwertyuiop"
        val mid = "asdfghjkl"
        val low = "zxcvbnm"
        fun letter(ch: String, hint: String = ""): Key {
            val acc = EN_ACCENTS[ch] ?: emptyList()
            val pops = if (hint.isNotEmpty() && !numberRow) listOf(hint) + acc else acc
            return c(ch, if (numberRow) "" else hint, pops)
        }
        val rows = ArrayList<Row>()
        if (numberRow) rows.add(Row((0 until 10).map { c(digit(it, false)) }))
        rows.add(Row(top.mapIndexed { i, ch -> letter(ch.toString(), digit(i, false)) }))
        rows.add(Row(listOf(spacer(0.5f)) + mid.map { letter(it.toString()) } + spacer(0.5f)))
        rows.add(Row(listOf(Key(KeyKind.SHIFT, "⇧", weight = 1.5f)) + low.map { letter(it.toString()) } + Key(KeyKind.BACKSPACE, "⌫", weight = 1.5f)))
        rows.add(bottomRow("en", Key(KeyKind.SYMBOLS, "?123"), 10f))
        return Layout("en", "en", LayoutKind.LETTERS, rows, false)
    }

    /**
     * The Arabic letters sit exactly where Gboard puts them (the standard Arabic keyboard, read from the left):
     *   ض ص ث ق ف غ ع ه خ ح ج
     *   ش س ي ب ل ا ت ن م ك ط
     *   ذ ء ؤ ر ى ة و ز ظ د
     * ئ and لا are one long-press away (on ي / ء and on ل). Nothing is mirrored, and the symbols key stays at the left of
     * the bottom row while enter and backspace stay at the right, as on every other keyboard.
     */
    fun arabic(numberRow: Boolean, indic: Boolean): Layout {
        val r1 = "ضصثقفغعهخحج"
        val r2 = "شسيبلاتنمكط"
        val r3 = "ذءؤرىةوزظد"
        fun letter(ch: String, hint: String = "", w: Float = 1f): Key {
            val pops = (if (hint.isNotEmpty() && !numberRow) listOf(hint) else emptyList()) + (AR_POPUPS[ch] ?: emptyList())
            return c(ch, if (numberRow) "" else hint, pops, w)
        }
        val rows = ArrayList<Row>()
        if (numberRow) rows.add(Row((0 until 10).map { c(digit(it, indic), w = 1.1f) }))
        rows.add(Row(r1.mapIndexed { i, ch -> letter(ch.toString(), if (i < 10) digit(i, indic) else "") }))
        rows.add(Row(r2.map { letter(it.toString()) }))
        // the third row also holds the key that opens the page with the vowel marks and the extra letters
        rows.add(
            Row(
                listOf(Key(KeyKind.ALT, "◌ً", weight = 0.85f)) +
                    r3.map { letter(it.toString(), w = 0.915f) } +
                    Key(KeyKind.BACKSPACE, "⌫", weight = 1f)
            )
        )
        rows.add(bottomRow("ar", Key(KeyKind.SYMBOLS, "؟123"), AR_UNITS))
        return Layout("ar", "ar", LayoutKind.LETTERS, rows, false)
    }

    /** Second Arabic page: diacritics, hamza forms and Persian/Urdu letters. */
    fun arabicAlt(): Layout {
        val w = AR_UNITS / 12f   // 12 keys per row, same total width as the letters page
        val marks = listOf("ً", "ٌ", "ٍ", "َ", "ُ", "ِ", "ّ", "ْ", "ٰ", "ـ", "ٓ", "ٔ")
        val r1 = marks.map { Key(KeyKind.CHAR, "◌$it", it, "", emptyList(), w) }
        val r2 = listOf("پ", "چ", "ڤ", "گ", "ژ", "أ", "إ", "آ", "ٱ", "ء", "ؤ", "ئ").map { c(it, w = w) }
        val r3 = listOf("،", "؛", "؟", "!", ".", ":", "\"", "'", "(", ")").map { c(it, w = w) }
        val rows = listOf(
            Row(r1), Row(r2),
            Row(listOf(Key(KeyKind.LETTERS, "أبج", weight = w)) + r3 + Key(KeyKind.BACKSPACE, "⌫", weight = w)),
            bottomRow("ar", Key(KeyKind.SYMBOLS, "؟123"), AR_UNITS),
        )
        return Layout("ar_alt", "ar", LayoutKind.ARABIC_ALT, rows, false)
    }

    fun symbols1(lang: String, indic: Boolean): Layout {
        val ar = lang == "ar"
        val s = if (ar) 1.1f else 1f
        val digitPops = mapOf(
            0 to listOf("¹", "½", "⅓", "¼", "⅛"), 1 to listOf("²", "⅔"), 2 to listOf("³", "¾", "⅜"),
            3 to listOf("⁴"), 4 to listOf("⅝"), 6 to listOf("⅞"), 9 to listOf("ⁿ", "∅", "°"),
        )
        val r1 = (0 until 10).map { c(digit(it, ar && indic), popups = digitPops[it] ?: emptyList(), w = s) }
        val r2 = listOf(
            c("@"), c("#"), c("$", popups = listOf("£", "€", "¥", "₹", "¢", "₩")), c("_"),
            c("&", popups = listOf("§")), c("-", popups = listOf("–", "—", "·")), c("+", popups = listOf("±")),
            c("(", popups = listOf("[", "{", "<")), c(")", popups = listOf("]", "}", ">")), c("/", popups = listOf("\\")),
        ).map { it.copy(weight = s) }
        val r3k = listOf(
            c("*", popups = listOf("†", "‡", "★")), c("\"", popups = listOf("«", "»", "“", "”", "„")),
            c("'", popups = listOf("‘", "’", "‚", "‹", "›")), c(":"),
            if (ar) c("؛", popups = listOf(";")) else c(";"),
            c("!", popups = listOf("¡")),
            if (ar) c("؟", popups = listOf("?", "¿")) else c("?", popups = listOf("¿")),
        ).map { it.copy(weight = s) }
        val total = 10f * s
        val rows = listOf(
            Row(r1), Row(r2),
            Row(listOf(Key(KeyKind.SYMBOLS2, "=\\<", weight = 1.5f * s)) + r3k + Key(KeyKind.BACKSPACE, "⌫", weight = 1.5f * s)),
            bottomRow(lang, Key(KeyKind.LETTERS, if (ar) "أبج" else "ABC"), total),
        )
        return Layout("sym1_$lang", lang, LayoutKind.SYMBOLS1, rows, false)
    }

    fun symbols2(lang: String): Layout {
        val ar = lang == "ar"
        val s = if (ar) 1.1f else 1f
        fun row(chars: List<String>) = Row(chars.map { c(it, w = s) })
        val rows = listOf(
            row(listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆")),
            row(listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\")),
            Row(
                listOf(Key(KeyKind.SYMBOLS, if (ar) "؟123" else "?123", weight = 1.5f * s)) +
                    listOf("%", "©", "®", "™", "✓", "[", "]").map { c(it, w = s) } +
                    Key(KeyKind.BACKSPACE, "⌫", weight = 1.5f * s)
            ),
            bottomRow(lang, Key(KeyKind.LETTERS, if (ar) "أبج" else "ABC"), 10f * s),
        )
        return Layout("sym2_$lang", lang, LayoutKind.SYMBOLS2, rows, false)
    }

    fun numeric(phone: Boolean): Layout {
        fun d(s: String) = c(s, w = 1f)
        val rows = listOf(
            Row(listOf(d("1"), d("2"), d("3"), Key(KeyKind.BACKSPACE, "⌫"))),
            Row(listOf(d("4"), d("5"), d("6"), if (phone) d("+") else d("-"))),
            Row(listOf(d("7"), d("8"), d("9"), if (phone) d("*") else d("."))),
            Row(listOf(if (phone) d("#") else d(","), c("0", popups = listOf("+")), Key(KeyKind.SPACE, " ", " "), Key(KeyKind.ENTER, "↵"))),
        )
        return Layout("numeric", "en", LayoutKind.NUMERIC, rows, false)
    }
}
