package com.omega.keyboard.ai

import android.content.Context
import com.omega.keyboard.AppLog
import com.omega.keyboard.Prefs
import com.omega.keyboard.core.Languages
import com.omega.keyboard.core.LlmPrompts

/**
 * Picks the engine for a translation:
 *  - ML Kit on the phone (59 languages, fast, needs a ~30 MB language pack downloaded once), or
 *  - the local Gemma model (any language, slower).
 * Setting "engine_mode": auto = ML Kit when it supports both languages and falls back to Gemma, mlkit, gemma.
 * Everything blocks; call from a background thread.
 */
object Translator {

    class TranslateException(message: String) : Exception(message)

    data class Out(val text: String, val engine: String, val source: String?)

    class BatchResult(val translated: Int, val skipped: Int, val failed: Int, val notes: List<String>)

    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 900
    }

    private fun cget(k: String): String? = synchronized(cache) { cache[k] }
    private fun cput(k: String, v: String) { synchronized(cache) { cache[k] = v } }
    fun clearCache() { synchronized(cache) { cache.clear() } }

    fun friendly(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        val m = (root.message ?: e.message ?: "").lowercase()
        return when {
            root is java.net.UnknownHostException || "unable to resolve" in m || "network" in m || "internet" in m ||
                ("download" in m && "model" in m) ->
                "محتاج إنترنت أول مرة عشان ينزّل حزمة اللغة (حوالي 30 ميجا). بعد كده بتشتغل من غير نت."
            root is java.util.concurrent.TimeoutException || "timed out" in m ->
                "الوقت خلص قبل ما الترجمة تخلّص. جرّب تاني."
            else -> (root.message ?: e.message ?: root.javaClass.simpleName).take(160)
        }
    }

    private fun detectOne(text: String): String? {
        val byScript = Languages.guessByScript(text)
        if (byScript != null) return byScript
        return try { MlKit.detect(text) } catch (_: Throwable) { null }
    }

    private fun mlKitPossible(src: String?, target: String, mode: String): Boolean =
        mode != "gemma" && src != null && MlKit.supports(src) && MlKit.supports(target)

    // ------------------------------------------------------------------ typed text

    /** Translates text the user typed (may have several lines). [source] "auto" detects the language. */
    fun translateText(ctx: Context, text: String, target: String, source: String = "auto"): Out {
        val mode = Prefs.get(ctx).engineMode
        if (text.isBlank()) return Out(text, "none", null)
        val src = if (source == "auto") detectOne(text.trim()) else source
        if (src != null && src == target) return Out(text, "same", src)

        cget("$target|${text.trim()}")?.let { return Out(it, "cache", src) }

        if (mlKitPossible(src, target, mode)) {
            try {
                MlKit.prepare(src!!, target)
                val sb = StringBuilder()
                for ((i, line) in text.split('\n').withIndex()) {
                    if (i > 0) sb.append('\n')
                    sb.append(if (line.isBlank()) line else MlKit.translate(line.trim(), src, target))
                }
                val r = sb.toString()
                if (r.isNotBlank()) {
                    cput("$target|${text.trim()}", r)
                    return Out(r, "mlkit", src)
                }
            } catch (e: Throwable) {
                AppLog.e("translate", "ML Kit failed ($src>$target)", e)
                if (mode == "mlkit") throw TranslateException(friendly(e))
                if (!Llm.isConfigured(ctx)) throw TranslateException(friendly(e))
            }
        } else if (mode == "mlkit") {
            throw TranslateException("اللغة دي مش في الترجمة السريعة. غيّر وضع الترجمة لـ تلقائي أو النموذج الذكي.")
        }
        val r = viaGemma(ctx, text, src, target)
        cput("$target|${text.trim()}", r)
        return Out(r, "gemma", src)
    }

    private fun viaGemma(ctx: Context, text: String, src: String?, target: String): String {
        if (!Llm.isConfigured(ctx)) {
            throw TranslateException("اللغة دي محتاجة النموذج المحلي. ضيف ملف النموذج من التطبيق (الشاشة الرئيسية).")
        }
        val raw = try {
            Llm.generate(
                ctx,
                LlmPrompts.typedTranslateSystem(Languages.englishName(target), src?.let { Languages.englishName(it) }),
                text,
                maxTokens = (text.length * 2 + 64).coerceIn(96, 1200),
                temperature = 0.2f,
                topK = 20,
                timeoutMs = 240_000,
            )
        } catch (e: Llm.LlmException) {
            throw TranslateException(e.message ?: "النموذج فشل")
        }
        val s = LlmPrompts.cleanTranslation(raw)
        if (s.isBlank()) throw TranslateException("النموذج رجّع ترجمة فاضية")
        return s
    }

    // ------------------------------------------------------------------ many strings (whole screen)

    /**
     * Translates [texts] into [target]. Results are delivered one by one through [onItem] (index into [texts]),
     * so the screen overlay can fill in while the rest is still being worked on.
     */
    fun translateBatch(
        ctx: Context,
        texts: List<String>,
        target: String,
        cancelled: () -> Boolean,
        onItem: (index: Int, translated: String) -> Unit,
        onProgress: (done: Int, total: Int, engine: String) -> Unit,
    ): BatchResult {
        val mode = Prefs.get(ctx).engineMode
        val total = texts.size
        var done = 0
        var skipped = 0
        var failed = 0
        var translated = 0
        val notes = ArrayList<String>()

        // 1) what we already know
        val pending = ArrayList<Int>()
        for ((i, t) in texts.withIndex()) {
            val hit = cget("$target|${t.trim()}")
            if (hit != null) { onItem(i, hit); done++; translated++ } else pending.add(i)
        }
        onProgress(done, total, "cache")
        if (pending.isEmpty() || cancelled()) return BatchResult(translated, skipped, failed, notes)

        // 2) which language is each string in?
        val detected: List<String?> = try {
            if (mode == "gemma") List(pending.size) { null } else MlKit.detectMany(pending.map { texts[it].trim() })
        } catch (_: Throwable) {
            List(pending.size) { null }
        }
        // short Latin strings ("OK", "Home") can't be detected reliably: use the language most other strings are in
        val votes = HashMap<String, Int>()
        for ((k, idx) in pending.withIndex()) {
            val d = detected[k] ?: continue
            if (texts[idx].trim().length >= 12) votes[d] = (votes[d] ?: 0) + 1
        }
        val dominant = votes.entries.maxByOrNull { it.value }?.key ?: "en"

        val srcOf = HashMap<Int, String?>()
        for ((k, idx) in pending.withIndex()) {
            val t = texts[idx].trim()
            srcOf[idx] = Languages.guessByScript(t) ?: detected[k] ?: dominant
        }

        val mlGroups = LinkedHashMap<String, MutableList<Int>>()
        val gemmaList = ArrayList<Int>()
        for (idx in pending) {
            val s = srcOf[idx]
            when {
                s != null && s == target -> { skipped++; done++ }
                mode != "gemma" && s != null && MlKit.supports(s) && MlKit.supports(target) ->
                    mlGroups.getOrPut(s) { ArrayList() }.add(idx)
                else -> gemmaList.add(idx)
            }
        }
        onProgress(done, total, "detect")

        // 3) ML Kit
        for ((s, idxs) in mlGroups) {
            if (cancelled()) break
            try {
                MlKit.prepare(s, target)
            } catch (e: Throwable) {
                AppLog.e("translate", "language pack $s>$target", e)
                notes.add(friendly(e))
                if (mode == "mlkit" || !Llm.isConfigured(ctx)) { failed += idxs.size; done += idxs.size } else gemmaList.addAll(idxs)
                onProgress(done, total, "mlkit")
                continue
            }
            for (idx in idxs) {
                if (cancelled()) break
                try {
                    val r = MlKit.translate(texts[idx].trim(), s, target)
                    if (r.isNotBlank()) {
                        cput("$target|${texts[idx].trim()}", r)
                        onItem(idx, r)
                        translated++
                    } else failed++
                } catch (e: Throwable) {
                    failed++
                    if (notes.size < 3) notes.add(friendly(e))
                }
                done++
                onProgress(done, total, "mlkit")
            }
        }

        // 4) Gemma for everything ML Kit can't do
        if (gemmaList.isNotEmpty() && !cancelled()) {
            when {
                mode == "mlkit" -> {
                    failed += gemmaList.size; done += gemmaList.size
                    notes.add("فيه لغات مش في الترجمة السريعة.")
                }
                !Llm.isConfigured(ctx) -> {
                    failed += gemmaList.size; done += gemmaList.size
                    notes.add("باقي النصوص محتاجة النموذج المحلي. ضيف ملف النموذج.")
                }
                else -> {
                    val items = gemmaList.sorted().map { it to texts[it].trim() }
                    for (b in LlmPrompts.batches(items, { it.second.length })) {
                        if (cancelled()) break
                        try {
                            val raw = Llm.generate(
                                ctx,
                                LlmPrompts.translateSystem(Languages.englishName(target)),
                                LlmPrompts.translateUser(b),
                                maxTokens = (b.sumOf { it.second.length } * 2 + 80).coerceIn(96, 1500),
                                temperature = 0.1f,
                                topK = 10,
                                timeoutMs = 240_000,
                            )
                            val parsed = LlmPrompts.parseTranslations(raw, b.map { it.first })
                            for ((id, tr) in parsed) {
                                val original = texts[id].trim()
                                cput("$target|$original", tr)
                                onItem(id, tr)
                                translated++
                            }
                            failed += b.size - parsed.size
                        } catch (e: Throwable) {
                            failed += b.size
                            if (notes.size < 3) notes.add(e.message ?: "النموذج فشل")
                            AppLog.e("translate", "gemma batch", e)
                            if (cancelled()) break
                        }
                        done += b.size
                        onProgress(done, total, "gemma")
                    }
                }
            }
        }
        return BatchResult(translated, skipped, failed, notes)
    }
}
