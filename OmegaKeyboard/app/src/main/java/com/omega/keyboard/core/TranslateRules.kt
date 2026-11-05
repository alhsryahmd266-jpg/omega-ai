package com.omega.keyboard.core

/**
 * The decisions of screen translation that need no phone: which language a piece of text is in, and whether a
 * translation can be believed. Pure, so it is unit-tested. A wrong source language or an invented answer is what makes a
 * translation look like nonsense, so both are guarded here.
 */
object TranslateRules {
    enum class Verdict { OK, UNCHANGED, BAD }

    private val CYRILLIC_LANGS = setOf("ru", "uk", "be", "bg", "mk", "sr")

    /** a text at least this long is recognised on its own; a shorter one is too little for a language detector */
    const val OWN_LANGUAGE_MIN = 24

    /** Everyday interface words: short, so a detector cannot tell their language, but they are English wherever they appear. */
    private val COMMON_EN = setOf(
        "ok", "okay", "cancel", "share", "home", "settings", "search", "back", "next", "previous", "menu", "send", "save", "delete", "edit",
        "done", "close", "open", "more", "help", "login", "log in", "sign in", "sign up", "sign out", "log out", "profile", "messages", "message",
        "notifications", "reply", "like", "comment", "follow", "following", "copy", "paste", "cut", "select", "all", "none", "yes", "no", "retry",
        "skip", "continue", "accept", "decline", "allow", "deny", "add", "remove", "clear", "filter", "sort", "view", "download", "upload",
        "update", "refresh", "new", "details", "about", "contact", "privacy", "terms", "account", "password", "email", "name", "phone",
        "photos", "videos", "files", "chats", "calls", "status", "camera", "gallery", "explore", "feed", "inbox", "sent", "drafts", "trash",
        "search...", "type a message", "loading", "loading...", "error", "success", "warning", "online", "offline", "today", "yesterday",
    )

    /**
     * A language code as the translation engine knows it: "fil" is "tl", "iw" is "he"; transliterated text ("ar-Latn",
     * Arabic written with Latin letters) and "und" (undetermined) are not a language any engine can translate: null.
     */
    fun normalizeCode(code: String?): String? {
        val c = code?.trim().orEmpty()
        if (c.isEmpty() || c.contains("-latn", ignoreCase = true)) return null
        return when (val base = c.lowercase().substringBefore('-').substringBefore('_')) {
            "", "und" -> null
            "fil" -> "tl"
            "iw" -> "he"
            "in" -> "id"
            "nb", "nn" -> "no"
            "ji" -> "yi"
            else -> base
        }
    }

    /** Arabic-script text may be Persian or Urdu: the letters only they use tell which. */
    fun arabicScriptLanguage(text: String): String {
        var urdu = 0
        var persian = 0
        for (c in text) {
            when (c) {
                'ٹ', 'ڈ', 'ڑ', 'ں', 'ے', 'ہ', 'ۂ', 'ۃ' -> urdu++
                'پ', 'چ', 'ژ', 'گ', 'ی', 'ک' -> persian++
            }
        }
        return when {
            urdu > 0 -> "ur"
            persian > 0 -> "fa"
            else -> "ar"
        }
    }

    /**
     * The language most of the screen's Latin-script text is in, from the strings long enough to be recognised
     * ([detected] is parallel to [texts]); English when nothing says otherwise.
     */
    fun latinDominant(texts: List<String>, detected: List<String?>): String {
        val votes = HashMap<String, Int>()
        fun vote(minLen: Int) {
            for (i in texts.indices) {
                val t = texts[i].trim()
                val d = normalizeCode(detected.getOrNull(i)) ?: continue
                if (t.length >= minLen && Languages.guessByScript(t) == null) votes[d] = (votes[d] ?: 0) + 1
            }
        }
        vote(OWN_LANGUAGE_MIN)
        if (votes.isEmpty()) vote(12)
        return votes.entries.maxByOrNull { it.value }?.key ?: "en"
    }

    /**
     * The language to translate [text] from. Its script when the script says it (Chinese, Cyrillic, Hebrew...), the
     * recogniser's answer when the text is long enough to trust it, and otherwise the language of the screen
     * ([dominant]) — never a guess from a three-letter word.
     */
    fun sourceLanguage(text: String, detectedCode: String?, dominant: String): String {
        val t = text.trim()
        val detected = normalizeCode(detectedCode)
        val byScript = Languages.guessByScript(t)
        val long = t.length >= OWN_LANGUAGE_MIN
        if (byScript == "ar") {
            val own = arabicScriptLanguage(t)
            return if (own != "ar") own else if (long && (detected == "fa" || detected == "ur")) detected else "ar"
        }
        if (byScript == "ru") {
            return when {
                dominant in CYRILLIC_LANGS -> dominant
                long && detected in CYRILLIC_LANGS -> detected!!
                else -> "ru"
            }
        }
        if (byScript != null) return byScript
        // Latin script (or no letters we can place)
        if (ArabicText.normalize(t) in COMMON_EN) return "en"
        return if (long && detected != null) detected else dominant
    }

    private val LEFTOVER = Regex("""(?im)^\s*(?:translation|translated text|here is the translation|here's the translation|الترجمة)\s*[:：]|^\s*\d{1,4}\s*[|｜]\s*\S""")

    private fun flat(s: String) = s.lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), " ").trim()

    /**
     * Is [output] a believable translation of [source] into [target]? OK: show it. UNCHANGED: the engine gave the text
     * back (a name, a code): nothing to paint. BAD: invented, empty, wrong script, wrong size: never shown.
     */
    fun check(source: String, output: String, target: String): Verdict {
        val s = source.trim()
        val o = output.trim()
        if (o.isEmpty()) return Verdict.BAD
        if (flat(s) == flat(o)) return Verdict.UNCHANGED
        if (LEFTOVER.containsMatchIn(o) && !LEFTOVER.containsMatchIn(s)) return Verdict.BAD
        if (o.length > s.length * 6 + 24) return Verdict.BAD
        if (s.length >= 20 && o.length * 6 < s.length) return Verdict.BAD
        if (o.count { it == '\n' } > s.count { it == '\n' } + 2) return Verdict.BAD
        if (target == "ar") {
            val letters = o.count { it.isLetter() }
            if (letters >= 3 && ArabicText.arabicRatio(o) < 0.3 && ArabicText.arabicRatio(s) < 0.5) return Verdict.BAD
        }
        return Verdict.OK
    }
}
