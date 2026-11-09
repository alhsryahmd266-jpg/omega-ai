package com.omega.keyboard.voice

/**
 * The pure logic behind dictation (no Android classes, so it is unit-tested on a plain JVM).
 *
 * A phone can have several speech recognisers, and the one the system picks by default is not always the one that knows
 * Arabic (many regional / OEM builds ship their own that answers "language not supported"). So dictation does not give up
 * on the first answer: it walks through a list of [Attempt]s (Google's recogniser first, then the rest, each with a few
 * spellings of the language) and only shows an error when every way failed.
 */
object VoicePlan {

    const val GOOGLE_PKG = "com.google.android.googlequicksearchbox"

    // SpeechRecognizer.ERROR_* values, spelled as numbers so this file needs no Android classes
    const val E_NETWORK_TIMEOUT = 1
    const val E_NETWORK = 2
    const val E_AUDIO = 3
    const val E_SERVER = 4
    const val E_CLIENT = 5
    const val E_SPEECH_TIMEOUT = 6
    const val E_NO_MATCH = 7
    const val E_BUSY = 8
    const val E_PERMISSION = 9
    const val E_TOO_MANY = 10
    const val E_SERVER_DISCONNECTED = 11
    const val E_LANG_NOT_SUPPORTED = 12
    const val E_LANG_UNAVAILABLE = 13
    const val E_CANNOT_CHECK = 14

    // our own codes: something went wrong before the recogniser could even answer
    const val CODE_FAILED = -1          // could not create / start the recogniser
    const val CODE_NO_PERMISSION = -2
    const val CODE_UNAVAILABLE = -3     // the phone has no speech service at all
    const val CODE_STUCK = -4           // the recogniser never answered

    /**
     * One way of asking for speech recognition. [service] is the recogniser as "package/Class" (null: the phone's default),
     * [lang] a BCP-47 tag ("" = no language asked for), [offline] asks for the on-device model.
     */
    data class Attempt(val service: String?, val lang: String, val offline: Boolean) {
        val key: String get() = "${service ?: "-"}|$lang|${if (offline) 1 else 0}"

        /** short text for logs: "google ar-EG online" */
        val label: String
            get() {
                val who = when {
                    service == null -> "default"
                    service.substringBefore('/') == GOOGLE_PKG -> "google"
                    else -> service.substringBefore('/')
                }
                return "$who ${lang.ifEmpty { "-" }} ${if (offline) "offline" else "online"}"
            }
    }

    private fun pkgOf(service: String) = service.substringBefore('/')

    /** Google's recogniser first (it is the one that knows Egyptian Arabic), then the phone's default, then the rest. */
    fun rank(services: List<String>, systemDefault: String?): List<String> = services.distinct().sortedWith(
        compareBy<String>(
            {
                val p = pkgOf(it)
                when {
                    p == GOOGLE_PKG -> 0
                    p.startsWith("com.google.") -> 1
                    else -> 2
                }
            },
            { if (it == systemDefault) 0 else 1 },
        )
    )

    /**
     * The spellings of a language worth trying, most specific first. For Arabic any dialect the recogniser knows beats
     * none at all ("ar-EG" → ar-EG, ar-SA, ar). Empty when no language is wanted.
     */
    fun languageChain(tag: String?): List<String> {
        val t = tag?.trim()?.replace('_', '-').orEmpty()
        if (t.isEmpty()) return emptyList()
        val base = t.substringBefore('-').lowercase()
        val out = LinkedHashSet<String>()
        out.add(t)
        if (base == "ar") {
            out.add("ar-SA")
            out.add("ar-EG")
        }
        out.add(base)
        return out.toList()
    }

    /**
     * Every way worth trying, best first. [lastGood] (the [Attempt.key] that worked last time) goes first when it is still part
     * of the list. [max] keeps a phone with many recognisers from spending many seconds on failures.
     */
    fun build(
        services: List<String>,
        systemDefault: String?,
        wanted: String?,
        preferOffline: Boolean,
        lastGood: String? = null,
        max: Int = 8,
    ): List<Attempt> {
        val svcs: List<String?> = rank(services, systemDefault).ifEmpty { listOf<String?>(null) }
        val langs = languageChain(wanted).ifEmpty { listOf("") }
        val all = ArrayList<Attempt>()
        for (s in svcs) for (l in langs) {
            all.add(Attempt(s, l, preferOffline))
            if (preferOffline) all.add(Attempt(s, l, false))
        }
        val list = all.distinct().take(max)
        val good = if (lastGood.isNullOrEmpty()) null else list.firstOrNull { it.key == lastGood }
        return if (good == null) list else listOf(good) + list.filter { it != good }
    }

    /** The failure belongs to the way that was tried, not to the user's speech: another way may still work. */
    fun tryNext(code: Int): Boolean = when (code) {
        E_SERVER, E_CLIENT, E_SERVER_DISCONNECTED, E_LANG_NOT_SUPPORTED, E_LANG_UNAVAILABLE, E_CANNOT_CHECK,
        CODE_FAILED, CODE_STUCK -> true
        else -> false
    }

    /** No network: the same recogniser may still work from its on-device model. */
    fun offlineRetry(failed: Attempt, code: Int): Attempt? =
        if (!failed.offline && (code == E_NETWORK || code == E_NETWORK_TIMEOUT)) failed.copy(offline = true) else null

    /** Every silent way failed: is it worth opening the system's own voice dialog? */
    fun dialogWorthIt(code: Int): Boolean = tryNext(code) || code == CODE_UNAVAILABLE

    /** When several ways failed, the error that tells the user the most (the connection, then the missing language pack). */
    fun bestError(codes: List<Int>): Int {
        if (codes.isEmpty()) return CODE_FAILED
        for (pick in intArrayOf(E_NETWORK, E_NETWORK_TIMEOUT, E_LANG_UNAVAILABLE, E_LANG_NOT_SUPPORTED)) {
            if (pick in codes) return pick
        }
        return codes.last()
    }
}
