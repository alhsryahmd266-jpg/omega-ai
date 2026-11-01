package com.omega.keyboard.core

enum class KeyKind { CHAR, SHIFT, BACKSPACE, SYMBOLS, SYMBOLS2, LETTERS, ENTER, SPACE, LANG, EMOJI, ALT, SPACER }

enum class LayoutKind { LETTERS, SYMBOLS1, SYMBOLS2, ARABIC_ALT, NUMERIC }

/**
 * One key. [label] is what is drawn, [text] is what gets committed (they differ for diacritics and the "لا" key),
 * [hint] is the small character drawn in the corner, [popups] are the long-press alternatives.
 */
data class Key(
    val kind: KeyKind,
    val label: String = "",
    val text: String = label,
    val hint: String = "",
    val popups: List<String> = emptyList(),
    val weight: Float = 1f,
)

data class Row(val keys: List<Key>) {
    val total: Float get() = keys.sumOf { it.weight.toDouble() }.toFloat()
}

data class Layout(val id: String, val lang: String, val kind: LayoutKind, val rows: List<Row>, val rtl: Boolean)

object Layouts {
    private fun c(label: String, hint: String = "", popups: List<String> = emptyList(), w: Float = 1f) =
        Key(KeyKind.CHAR, label, label, hint, popups, w)

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
        val sw = switchKey.copy(weight = if (ar) 1.6f else 1.3f)
        val comma = if (ar) c("،", popups = listOf("،", "؛", "؟", ",", "!", ":", "'", "\""), w = 1.2f)
        else c(",", popups = listOf(",", ";", ":", "!", "?", "'", "\""), w = 0.9f)
        val emoji = Key(KeyKind.EMOJI, "😊", weight = if (ar) 1.2f else 1f)
        val globe = Key(KeyKind.LANG, "🌐", weight = if (ar) 1.2f else 1f)
        val period = c(".", popups = listOf(".", "…", "?", "!", ":", ";", "-", "_", "@", "/"), w = if (ar) 1.2f else 0.9f)
        val enter = Key(KeyKind.ENTER, "↵", weight = if (ar) 1.8f else 1.3f)
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

    fun arabic(numberRow: Boolean, indic: Boolean): Layout {
        val r1 = "ضصثقفغعهخحجد"
        val r2 = "شسيبلاتنمكطذ"
        val r3 = listOf("ئ", "ء", "ؤ", "ر", "لا", "ى", "ة", "و", "ز", "ظ")
        fun letter(ch: String, hint: String = ""): Key {
            val pops = (if (hint.isNotEmpty() && !numberRow) listOf(hint) else emptyList()) + (AR_POPUPS[ch] ?: emptyList())
            return c(ch, if (numberRow) "" else hint, pops)
        }
        val rows = ArrayList<Row>()
        if (numberRow) rows.add(Row((0 until 10).map { c(digit(it, indic), w = 1.2f) }))
        rows.add(Row(r1.mapIndexed { i, ch -> letter(ch.toString(), if (i < 10) digit(i, indic) else "") }))
        rows.add(Row(r2.map { letter(it.toString()) }))
        rows.add(Row(listOf(Key(KeyKind.ALT, "◌ً", weight = 1f)) + r3.map { letter(it) } + Key(KeyKind.BACKSPACE, "⌫", weight = 1f)))
        rows.add(bottomRow("ar", Key(KeyKind.SYMBOLS, "؟123"), 12f))
        return Layout("ar", "ar", LayoutKind.LETTERS, rows, true)
    }

    /** Second Arabic page: diacritics, hamza forms and Persian/Urdu letters. */
    fun arabicAlt(): Layout {
        val marks = listOf("ً", "ٌ", "ٍ", "َ", "ُ", "ِ", "ّ", "ْ", "ٰ", "ـ", "ٓ", "ٔ")
        val r1 = marks.map { Key(KeyKind.CHAR, "◌$it", it, "", emptyList(), 1f) }
        val r2 = listOf("پ", "چ", "ڤ", "گ", "ژ", "أ", "إ", "آ", "ٱ", "ء", "ؤ", "ئ").map { c(it) }
        val r3 = listOf("،", "؛", "؟", "!", ".", ":", "\"", "'", "(", ")").map { c(it) }
        val rows = listOf(
            Row(r1), Row(r2),
            Row(listOf(Key(KeyKind.LETTERS, "أبج")) + r3 + Key(KeyKind.BACKSPACE, "⌫")),
            bottomRow("ar", Key(KeyKind.SYMBOLS, "؟123"), 12f),
        )
        return Layout("ar_alt", "ar", LayoutKind.ARABIC_ALT, rows, true)
    }

    fun symbols1(lang: String, indic: Boolean): Layout {
        val ar = lang == "ar"
        val s = if (ar) 1.2f else 1f
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
        val s = if (ar) 1.2f else 1f
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
