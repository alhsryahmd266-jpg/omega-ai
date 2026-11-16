package com.omega.keyboard.core

/**
 * The two language choices of the translate bar (what is typed → what comes out) and the rules that keep them sensible.
 * [AUTO] as a source means "work out the language of what is typed".
 */
object TypedLang {
    const val AUTO = "auto"

    /** the language to translate into when nothing else is known: Arabic for those who type English and English for the rest */
    fun otherThan(code: String): String = if (code == "ar") "en" else "ar"

    /**
     * ⇄: the languages trade places. With an automatic source the language that was just found in the text becomes the target
     * (the other way round: what you wrote is what you read now); without one, Arabic and English are the natural pair.
     */
    fun swap(source: String, target: String, detected: String?): Pair<String, String> {
        if (source != AUTO) return target to source
        val newTarget = detected?.takeIf { it != target } ?: otherThan(target)
        return target to newTarget
    }

    /** the user picked [picked] as the target; it can never equal an explicit source (they trade places then) */
    fun pickTarget(source: String, target: String, picked: String): Pair<String, String> =
        if (source != AUTO && picked == source) target to picked else source to picked

    /** the user picked [picked] as the source ([AUTO] included); it can never equal the target (they trade places then) */
    fun pickSource(source: String, target: String, picked: String): Pair<String, String> =
        if (picked != AUTO && picked == target) picked to source.let { if (it == AUTO) otherThan(picked) else it } else picked to target
}
