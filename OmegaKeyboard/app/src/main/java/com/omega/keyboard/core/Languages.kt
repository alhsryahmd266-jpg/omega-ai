package com.omega.keyboard.core

/**
 * Translation languages. [mlkit] = supported by Google ML Kit on-device translation (fast, small downloads).
 * Every other language is handled by the local Gemma model.
 */
data class Lang(val code: String, val english: String, val arabic: String, val native: String, val mlkit: Boolean)

object Languages {
    val all: List<Lang> = listOf(
        Lang("ar", "Arabic", "العربية", "العربية", true),
        Lang("en", "English", "الإنجليزية", "English", true),
        Lang("fr", "French", "الفرنسية", "Français", true),
        Lang("es", "Spanish", "الإسبانية", "Español", true),
        Lang("de", "German", "الألمانية", "Deutsch", true),
        Lang("it", "Italian", "الإيطالية", "Italiano", true),
        Lang("pt", "Portuguese", "البرتغالية", "Português", true),
        Lang("ru", "Russian", "الروسية", "Русский", true),
        Lang("tr", "Turkish", "التركية", "Türkçe", true),
        Lang("fa", "Persian", "الفارسية", "فارسی", true),
        Lang("ur", "Urdu", "الأردية", "اردو", true),
        Lang("hi", "Hindi", "الهندية", "हिन्दी", true),
        Lang("bn", "Bengali", "البنغالية", "বাংলা", true),
        Lang("zh", "Chinese", "الصينية", "中文", true),
        Lang("ja", "Japanese", "اليابانية", "日本語", true),
        Lang("ko", "Korean", "الكورية", "한국어", true),
        Lang("id", "Indonesian", "الإندونيسية", "Bahasa Indonesia", true),
        Lang("ms", "Malay", "الماليزية", "Bahasa Melayu", true),
        Lang("th", "Thai", "التايلاندية", "ไทย", true),
        Lang("vi", "Vietnamese", "الفيتنامية", "Tiếng Việt", true),
        Lang("nl", "Dutch", "الهولندية", "Nederlands", true),
        Lang("pl", "Polish", "البولندية", "Polski", true),
        Lang("uk", "Ukrainian", "الأوكرانية", "Українська", true),
        Lang("ro", "Romanian", "الرومانية", "Română", true),
        Lang("el", "Greek", "اليونانية", "Ελληνικά", true),
        Lang("he", "Hebrew", "العبرية", "עברית", true),
        Lang("sv", "Swedish", "السويدية", "Svenska", true),
        Lang("no", "Norwegian", "النرويجية", "Norsk", true),
        Lang("da", "Danish", "الدانماركية", "Dansk", true),
        Lang("fi", "Finnish", "الفنلندية", "Suomi", true),
        Lang("cs", "Czech", "التشيكية", "Čeština", true),
        Lang("sk", "Slovak", "السلوفاكية", "Slovenčina", true),
        Lang("hu", "Hungarian", "المجرية", "Magyar", true),
        Lang("bg", "Bulgarian", "البلغارية", "Български", true),
        Lang("hr", "Croatian", "الكرواتية", "Hrvatski", true),
        Lang("sl", "Slovenian", "السلوفينية", "Slovenščina", true),
        Lang("lt", "Lithuanian", "الليتوانية", "Lietuvių", true),
        Lang("lv", "Latvian", "اللاتفية", "Latviešu", true),
        Lang("et", "Estonian", "الإستونية", "Eesti", true),
        Lang("sq", "Albanian", "الألبانية", "Shqip", true),
        Lang("mk", "Macedonian", "المقدونية", "Македонски", true),
        Lang("be", "Belarusian", "البيلاروسية", "Беларуская", true),
        Lang("ka", "Georgian", "الجورجية", "ქართული", true),
        Lang("ta", "Tamil", "التاميلية", "தமிழ்", true),
        Lang("te", "Telugu", "التيلوغوية", "తెలుగు", true),
        Lang("kn", "Kannada", "الكانادية", "ಕನ್ನಡ", true),
        Lang("mr", "Marathi", "الماراثية", "मराठी", true),
        Lang("gu", "Gujarati", "الغوجاراتية", "ગુજરાતી", true),
        Lang("sw", "Swahili", "السواحيلية", "Kiswahili", true),
        Lang("af", "Afrikaans", "الأفريقانية", "Afrikaans", true),
        Lang("ca", "Catalan", "الكتالانية", "Català", true),
        Lang("gl", "Galician", "الجاليكية", "Galego", true),
        Lang("cy", "Welsh", "الويلزية", "Cymraeg", true),
        Lang("ga", "Irish", "الأيرلندية", "Gaeilge", true),
        Lang("is", "Icelandic", "الأيسلندية", "Íslenska", true),
        Lang("mt", "Maltese", "المالطية", "Malti", true),
        Lang("eo", "Esperanto", "الإسبرانتو", "Esperanto", true),
        Lang("ht", "Haitian Creole", "الكريولية الهايتية", "Kreyòl ayisyen", true),
        Lang("tl", "Filipino", "الفلبينية", "Filipino", true),
        // Gemma-only languages
        Lang("sr", "Serbian", "الصربية", "Српски", false),
        Lang("hy", "Armenian", "الأرمينية", "Հայերեն", false),
        Lang("az", "Azerbaijani", "الأذرية", "Azərbaycan", false),
        Lang("kk", "Kazakh", "الكازاخية", "Қазақша", false),
        Lang("uz", "Uzbek", "الأوزبكية", "Oʻzbek", false),
        Lang("ky", "Kyrgyz", "القيرغيزية", "Кыргызча", false),
        Lang("tg", "Tajik", "الطاجيكية", "Тоҷикӣ", false),
        Lang("tk", "Turkmen", "التركمانية", "Türkmen", false),
        Lang("ml", "Malayalam", "المالايالامية", "മലയാളം", false),
        Lang("pa", "Punjabi", "البنجابية", "ਪੰਜਾਬੀ", false),
        Lang("ne", "Nepali", "النيبالية", "नेपाली", false),
        Lang("si", "Sinhala", "السنهالية", "සිංහල", false),
        Lang("my", "Burmese", "البورمية", "မြန်မာ", false),
        Lang("km", "Khmer", "الخميرية", "ខ្មែរ", false),
        Lang("lo", "Lao", "اللاوية", "ລາວ", false),
        Lang("mn", "Mongolian", "المنغولية", "Монгол", false),
        Lang("am", "Amharic", "الأمهرية", "አማርኛ", false),
        Lang("ha", "Hausa", "الهوسا", "Hausa", false),
        Lang("yo", "Yoruba", "اليوروبا", "Yorùbá", false),
        Lang("ig", "Igbo", "الإيغبو", "Igbo", false),
        Lang("zu", "Zulu", "الزولو", "isiZulu", false),
        Lang("xh", "Xhosa", "الخوسا", "isiXhosa", false),
        Lang("so", "Somali", "الصومالية", "Soomaali", false),
        Lang("mg", "Malagasy", "المالاغاشية", "Malagasy", false),
        Lang("eu", "Basque", "الباسكية", "Euskara", false),
        Lang("ku", "Kurdish", "الكردية", "Kurdî", false),
        Lang("ps", "Pashto", "البشتو", "پښتو", false),
        Lang("sd", "Sindhi", "السندية", "سنڌي", false),
        Lang("lb", "Luxembourgish", "اللوكسمبورغية", "Lëtzebuergesch", false),
        Lang("jv", "Javanese", "الجاوية", "Basa Jawa", false),
        Lang("su", "Sundanese", "السوندانية", "Basa Sunda", false),
        Lang("la", "Latin", "اللاتينية", "Latina", false),
        Lang("yi", "Yiddish", "اليديشية", "ייִדיש", false),
        Lang("bs", "Bosnian", "البوسنية", "Bosanski", false),
        Lang("ceb", "Cebuano", "السيبوانو", "Cebuano", false),
        Lang("haw", "Hawaiian", "الهاواوية", "ʻŌlelo Hawaiʻi", false),
        Lang("mi", "Maori", "الماورية", "Māori", false),
        Lang("sm", "Samoan", "الساموية", "Gagana Samoa", false),
    )

    private val byCode = all.associateBy { it.code }

    fun find(code: String): Lang? = byCode[code.lowercase().substringBefore('-').substringBefore('_')]

    fun englishName(code: String): String = find(code)?.english ?: code
    fun arabicName(code: String): String = find(code)?.arabic ?: code
    fun isMlKit(code: String): Boolean = find(code)?.mlkit == true

    /** Quick script-based guess used before (and instead of) ML Kit language-id for obvious scripts. */
    fun guessByScript(text: String): String? {
        var cjk = 0; var kana = 0; var hangul = 0; var cyr = 0; var arabic = 0; var latin = 0
        var devanagari = 0; var thai = 0; var hebrew = 0; var greek = 0; var letters = 0
        for (c in text) {
            if (!c.isLetter()) continue
            letters++
            when (c.code) {
                in 0x4E00..0x9FFF -> cjk++
                in 0x3040..0x30FF -> kana++
                in 0xAC00..0xD7AF, in 0x1100..0x11FF -> hangul++
                in 0x0400..0x04FF -> cyr++
                in 0x0600..0x06FF, in 0x0750..0x077F -> arabic++
                in 0x0900..0x097F -> devanagari++
                in 0x0E00..0x0E7F -> thai++
                in 0x0590..0x05FF -> hebrew++
                in 0x0370..0x03FF -> greek++
                in 0x41..0x24F -> latin++
            }
        }
        if (letters == 0) return null
        fun big(n: Int) = n * 10 >= letters * 6
        return when {
            big(kana) || (kana > 0 && kana + cjk >= letters / 2) -> "ja"
            big(hangul) -> "ko"
            big(cjk) -> "zh"
            big(arabic) -> "ar"
            big(devanagari) -> "hi"
            big(thai) -> "th"
            big(hebrew) -> "he"
            big(greek) -> "el"
            big(cyr) -> "ru"
            else -> null
        }
    }
}
