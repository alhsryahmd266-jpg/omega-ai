package com.omega.keyboard.core

/** Text helpers that know about Arabic. Pure Kotlin (no Android) so it can be unit-tested on the JVM. */
object ArabicText {
    private val DIACRITICS = Regex("[ً-ٰٟـ]")
    private val SPACES = Regex("\\s+")

    fun isArabicChar(c: Char): Boolean =
        c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ࢠ'..'ࣿ' ||
            c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿'

    fun stripDiacritics(s: String): String = DIACRITICS.replace(s, "")

    /** Matching key: no diacritics, alef/ya/ta-marbuta variants merged, Arabic-Indic digits -> western, lower-case. */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in stripDiacritics(s)) {
            sb.append(
                when (ch) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ى', 'ئ', 'ی' -> 'ي'
                    'ة', 'ہ', 'ھ' -> 'ه'
                    'ؤ' -> 'و'
                    'ک' -> 'ك'
                    in '٠'..'٩' -> ('0' + (ch - '٠'))
                    in '۰'..'۹' -> ('0' + (ch - '۰'))
                    else -> ch.lowercaseChar()
                }
            )
        }
        return SPACES.replace(sb.toString().trim(), " ")
    }

    /** Share of Arabic-script letters among all letters (0.0 when there are no letters). */
    fun arabicRatio(s: String): Double {
        var letters = 0
        var arabic = 0
        for (c in s) {
            if (c.isLetter()) {
                letters++
                if (isArabicChar(c)) arabic++
            }
        }
        return if (letters == 0) 0.0 else arabic.toDouble() / letters
    }

    fun isMostlyArabic(s: String): Boolean = arabicRatio(s) >= 0.5

    fun hasLetters(s: String): Boolean = s.any { it.isLetter() }

    fun toArabicIndicDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '0'..'9') ('٠' + (c - '0')) else c)
        return sb.toString()
    }

    fun toWesternDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(
            when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                else -> c
            }
        )
        return sb.toString()
    }

    /** Levenshtein distance with an early-exit bound; returns bound+1 when the distance exceeds [max]. */
    fun editDistance(a: String, b: String, max: Int = 3): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
