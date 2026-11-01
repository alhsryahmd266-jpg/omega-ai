package com.omega.keyboard.core

import java.util.PriorityQueue

/**
 * Prefix dictionary for the suggestion strip (one instance per language).
 * Words are indexed by a normalised key (Arabic: alef/ya/ta-marbuta merged, no diacritics) so that typing
 * "احمد" suggests the correctly spelled "أحمد".
 */
class Dictionary(private val lang: String) {
    private var keys: Array<String> = emptyArray()
    private var surfaces: Array<String> = emptyArray()
    private var scores: IntArray = IntArray(0)
    private val byKey = HashMap<String, Int>()

    /** words the user committed; boosted above the static list */
    private val user = HashMap<String, Int>()

    val size: Int get() = keys.size
    val isLoaded: Boolean get() = keys.isNotEmpty()

    fun key(s: String): String = if (lang == "ar") ArabicText.normalize(s) else s.lowercase()

    /** Lines are "word<TAB>score". */
    fun load(lines: Sequence<String>) {
        data class E(val key: String, val surface: String, var score: Long)
        val merged = LinkedHashMap<String, E>()
        for (line in lines) {
            val t = line.indexOf('\t')
            if (t <= 0) continue
            val w = line.substring(0, t)
            val sc = line.substring(t + 1).trim().toLongOrNull() ?: continue
            val k = key(w)
            if (k.isEmpty()) continue
            val e = merged[k]
            if (e == null) merged[k] = E(k, w, sc) else e.score += sc
        }
        val sorted = merged.values.sortedBy { it.key }
        keys = Array(sorted.size) { sorted[it].key }
        surfaces = Array(sorted.size) { sorted[it].surface }
        scores = IntArray(sorted.size) { sorted[it].score.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
        byKey.clear()
        for (i in keys.indices) byKey[keys[i]] = i
    }

    private fun lowerBound(prefix: String): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun contains(word: String): Boolean = byKey.containsKey(key(word)) || user.containsKey(word)

    /** Completions for [prefix], most frequent first. */
    fun suggest(prefix: String, limit: Int = 3): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val pk = key(prefix)
        val heap = PriorityQueue<Pair<Long, String>>(limit + 1, compareBy { it.first })
        var i = lowerBound(pk)
        var scanned = 0
        while (i < keys.size && keys[i].startsWith(pk) && scanned < 20000) {
            offer(heap, limit, scores[i].toLong(), surfaces[i])
            i++; scanned++
        }
        for ((w, c) in user) {
            if (key(w).startsWith(pk)) offer(heap, limit, 5_000_000_000L + c * 1_000_000L, w)
        }
        val out = ArrayList<Pair<Long, String>>(heap)
        out.sortByDescending { it.first }
        return out.map { it.second }.distinct()
    }

    private fun offer(heap: PriorityQueue<Pair<Long, String>>, limit: Int, score: Long, w: String) {
        if (heap.size < limit) heap.add(score to w)
        else if (score > heap.peek()!!.first) { heap.poll(); heap.add(score to w) }
    }

    /** Spelling candidates at edit distance 1 for a word that is not in the dictionary. */
    fun corrections(word: String, limit: Int = 2): List<String> {
        if (word.length < 3 || contains(word)) return emptyList()
        val w = key(word)
        val alphabet = if (lang == "ar") AR_ALPHABET else EN_ALPHABET
        val found = HashMap<Int, Boolean>()
        fun probe(c: String) {
            val idx = byKey[c]
            if (idx != null) found[idx] = true
        }
        for (i in 0..w.length) {
            if (i < w.length) probe(w.removeRange(i, i + 1))                                // deletion
            if (i < w.length - 1) probe(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2)) // transposition
            for (c in alphabet) {
                if (i < w.length) probe(w.substring(0, i) + c + w.substring(i + 1))         // substitution
                probe(w.substring(0, i) + c + w.substring(i))                               // insertion
            }
        }
        return found.keys.sortedByDescending { scores[it] }.take(limit).map { surfaces[it] }
    }

    fun learn(word: String) {
        if (word.length < 2) return
        user[word] = (user[word] ?: 0) + 1
        if (user.size > 4000) {
            val drop = user.entries.sortedBy { it.value }.take(500).map { it.key }
            for (d in drop) user.remove(d)
        }
    }

    fun exportUser(): String = user.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    fun importUser(text: String) {
        user.clear()
        for (line in text.lineSequence()) {
            val t = line.indexOf('\t')
            if (t > 0) user[line.substring(0, t)] = line.substring(t + 1).toIntOrNull() ?: 1
        }
    }

    companion object {
        private const val AR_ALPHABET = "ابتثجحخدذرزسشصضطظعغفقكلمنهويء"
        private const val EN_ALPHABET = "abcdefghijklmnopqrstuvwxyz'"
    }
}
