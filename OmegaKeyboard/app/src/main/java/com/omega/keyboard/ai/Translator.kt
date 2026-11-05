package com.omega.keyboard.ai

import android.content.Context
import com.omega.keyboard.AppLog
import com.omega.keyboard.Prefs
import com.omega.keyboard.core.Languages
import com.omega.keyboard.core.LlmPrompts
import com.omega.keyboard.core.TranslateRules
import com.omega.keyboard.core.TranslateRules.Verdict

/**
 * Picks the engine for a translation:
 *  - ML Kit on the phone (59 languages, fast, needs a ~30 MB language pack downloaded once), or
 *  - the local Gemma model (any language, slower) for languages ML Kit does not know, or when a pack cannot be downloaded.
 * Setting "engine_mode": auto = ML Kit when it supports both languages, the model otherwise; mlkit; gemma.
 *
 * Nothing is believed blindly: the language of every text is decided by [TranslateRules] (never guessed from a three-letter
 * word) and every answer goes through [TranslateRules.check] before it is cached or shown, so an invented answer is dropped
 * instead of being painted on the screen.
 * Everything blocks; call from a background thread.
 */
object Translator {

    class TranslateException(message: String) : Exception(message)

    data class Out(val text: String, val engine: String, val source: String?)

    class BatchResult(val translated: Int, val skipped: Int, val failed: Int, val notes: List<String>)

    /** The model is slow: one screen press hands it at most this many texts (the press after continues with the rest). */
    private const val MODEL_SCREEN_CAP = 30

    // only translations that passed the check are ever stored
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
                ("download" in m && "model" in m) || "not downloaded" in m ->
                "محتاج إنترنت أول مرة عشان ينزّل حزمة اللغة (حوالي 30 ميجا). بعد كده بتشتغل من غير نت."
            root is java.util.concurrent.TimeoutException || "timed out" in m ->
                "الوقت خلص قبل ما الترجمة تخلّص. جرّب تاني."
            else -> (root.message ?: e.message ?: root.javaClass.simpleName).take(160)
        }
    }

    private fun detectOne(text: String): String? = try { MlKit.detect(text) } catch (_: Throwable) { null }

    private fun fastEngineKnows(src: String?, target: String): Boolean = src != null && MlKit.supports(src) && MlKit.supports(target)

    /** ML Kit, line by line so the line breaks of the text survive. */
    private fun viaMlKit(text: String, src: String, target: String): String {
        if (!text.contains('\n')) return MlKit.translate(text.trim(), src, target)
        val sb = StringBuilder()
        for ((i, line) in text.split('\n').withIndex()) {
            if (i > 0) sb.append('\n')
            sb.append(if (line.isBlank()) line else MlKit.translate(line.trim(), src, target))
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ typed text

    /** Translates text the user typed (may have several lines). [source] "auto" detects the language. */
    fun translateText(ctx: Context, text: String, target: String, source: String = "auto"): Out {
        val mode = Prefs.get(ctx).engineMode
        if (text.isBlank()) return Out(text, "none", null)
        val trimmed = text.trim()
        val src = if (source == "auto") TranslateRules.sourceLanguage(trimmed, detectOne(trimmed), dominant = "en") else source
        if (src == target) return Out(text, "same", src)

        cget("$target|$trimmed")?.let { return Out(it, "cache", src) }

        var fastFailure: Throwable? = null
        if (mode != "gemma" && fastEngineKnows(src, target)) {
            try {
                MlKit.prepare(src, target)
                val r = viaMlKit(text, src, target)
                when (TranslateRules.check(text, r, target)) {
                    Verdict.OK -> { cput("$target|$trimmed", r); return Out(r, "mlkit", src) }
                    Verdict.UNCHANGED -> return Out(text, "same", src)
                    Verdict.BAD -> AppLog.d("translate", "ML Kit answer rejected ($src>$target): ${r.take(60)}")
                }
            } catch (e: Throwable) {
                AppLog.e("translate", "ML Kit failed ($src>$target)", e)
                fastFailure = e
            }
        } else if (mode == "mlkit") {
            throw TranslateException("اللغة دي مش في الترجمة السريعة. غيّر وضع الترجمة لـ تلقائي أو النموذج الذكي.")
        }
        if (mode == "mlkit") {
            throw TranslateException(fastFailure?.let { friendly(it) } ?: "الترجمة السريعة رجّعت نتيجة مش مظبوطة. جرّب تاني.")
        }
        if (!Llm.isConfigured(ctx)) {
            throw TranslateException(fastFailure?.let { friendly(it) } ?: "اللغة دي محتاجة النموذج المحلي. ضيف ملف النموذج من التطبيق (الشاشة الرئيسية).")
        }
        val r = viaModel(ctx, text, src, target)
        cput("$target|$trimmed", r)
        return Out(r, "gemma", src)
    }

    /** The local model, one text at a time; the answer must pass the check (a second try is allowed). */
    private fun viaModel(ctx: Context, text: String, src: String?, target: String): String {
        for (attempt in 1..2) {
            val raw = try {
                Llm.generate(
                    ctx,
                    LlmPrompts.typedTranslateSystem(Languages.englishName(target), src?.let { Languages.englishName(it) }),
                    text,
                    maxTokens = (text.length * 2 + 64).coerceIn(96, 1200),
                    temperature = if (attempt == 1) 0.2f else 0.5f,
                    topK = 20,
                    timeoutMs = 240_000,
                )
            } catch (e: Llm.LlmException) {
                throw TranslateException(e.message ?: "النموذج فشل")
            }
            val s = LlmPrompts.cleanTranslation(raw)
            if (TranslateRules.check(text, s, target) == Verdict.OK) return s
            AppLog.d("translate", "model answer rejected (attempt $attempt): ${s.take(60)}")
        }
        throw TranslateException("النموذج ما قدرش يترجم النص ده كويس. جرّب تاني.")
    }

    // ------------------------------------------------------------------ many strings (whole screen)

    /**
     * Translates [texts] into [target]. Results are delivered one by one through [onItem] (index into [texts]), so the
     * screen overlay fills in while the rest is still being worked on. [onStage] says in words what is going on
     * (language pack download, model...). A text whose answer fails the check is simply not delivered.
     */
    fun translateBatch(
        ctx: Context,
        texts: List<String>,
        target: String,
        cancelled: () -> Boolean,
        onItem: (index: Int, translated: String) -> Unit,
        onProgress: (done: Int, total: Int, engine: String) -> Unit,
        onStage: (String) -> Unit = {},
    ): BatchResult {
        val mode = Prefs.get(ctx).engineMode
        val clean = texts.map { it.trim() }
        val total = clean.size
        var done = 0
        var skipped = 0
        var failed = 0
        var translated = 0
        val notes = ArrayList<String>()
        fun note(s: String) { if (s.isNotBlank() && s !in notes && notes.size < 4) notes.add(s) }

        // 1) what we already know
        val pending = ArrayList<Int>()
        for ((i, t) in clean.withIndex()) {
            val hit = cget("$target|$t")
            if (hit != null) { onItem(i, hit); done++; translated++ } else pending.add(i)
        }
        onProgress(done, total, "cache")
        if (pending.isEmpty() || cancelled()) return BatchResult(translated, skipped, failed, notes)

        // 2) which language is each text in? (script first, then the recogniser for long texts, then the language of the screen)
        onStage("بحدد لغة النصوص...")
        val detected: List<String?> = try {
            if (mode == "gemma") List(pending.size) { null } else MlKit.detectMany(pending.map { clean[it] })
        } catch (_: Throwable) {
            List(pending.size) { null }
        }
        val dominant = TranslateRules.latinDominant(pending.map { clean[it] }, detected)
        val srcOf = HashMap<Int, String>()
        for ((k, idx) in pending.withIndex()) srcOf[idx] = TranslateRules.sourceLanguage(clean[idx], detected[k], dominant)
        AppLog.d("translate", "screen language: dominant=$dominant, " + srcOf.values.groupingBy { it }.eachCount())

        // 3) who translates what
        val fast = LinkedHashMap<String, MutableList<Int>>()
        val model = ArrayList<Int>()
        for (idx in pending) {
            val s = srcOf.getValue(idx)
            when {
                s == target -> { skipped++; done++ }
                mode != "gemma" && fastEngineKnows(s, target) -> fast.getOrPut(s) { ArrayList() }.add(idx)
                else -> model.add(idx)
            }
        }
        onProgress(done, total, "detect")

        // 4) ML Kit
        for ((s, idxs) in fast) {
            if (cancelled()) break
            val missing = try { MlKit.missingPacks(s, target) } catch (_: Throwable) { emptyList() }
            onStage(
                if (missing.isEmpty()) "بترجم من ${Languages.arabicName(s)}..."
                else "بنزّل حزمة الترجمة (${missing.joinToString("، ") { Languages.arabicName(it) }}) مرة واحدة بس. محتاج إنترنت..."
            )
            try {
                MlKit.prepare(s, target)
            } catch (e: Throwable) {
                AppLog.e("translate", "language pack $s>$target", e)
                note(friendly(e))
                if (mode == "mlkit") { failed += idxs.size; done += idxs.size } else model.addAll(idxs)   // the model may take over; a guess never does
                onProgress(done, total, "mlkit")
                continue
            }
            onStage("بترجم من ${Languages.arabicName(s)}...")
            for (idx in idxs) {
                if (cancelled()) break
                val original = clean[idx]
                try {
                    val r = viaMlKit(original, s, target)
                    when (TranslateRules.check(original, r, target)) {
                        Verdict.OK -> { cput("$target|$original", r); onItem(idx, r); translated++ }
                        Verdict.UNCHANGED -> skipped++
                        Verdict.BAD -> { failed++; AppLog.d("translate", "rejected ($s>$target): '${original.take(40)}' -> '${r.take(40)}'") }
                    }
                } catch (e: Throwable) {
                    failed++
                    note(friendly(e))
                }
                done++
                onProgress(done, total, "mlkit")
            }
        }

        // 5) the local model: languages the fast engine does not know, or packs that could not be downloaded
        if (model.isNotEmpty() && !cancelled()) {
            when {
                mode == "mlkit" -> {
                    failed += model.size; done += model.size
                    note("فيه لغات مش في الترجمة السريعة.")
                }
                !Llm.isConfigured(ctx) -> {
                    failed += model.size; done += model.size
                    note("باقي النصوص محتاجة النموذج المحلي. ضيف ملف النموذج.")
                }
                else -> {
                    val order = model.sorted()
                    val use = order.take(MODEL_SCREEN_CAP)
                    if (order.size > use.size) {
                        failed += order.size - use.size; done += order.size - use.size
                        note("النموذج ترجم أول ${use.size} نص بس (بطيء). دوس ترجم تاني للباقي.")
                    }
                    onStage("بترجم بالنموذج المحلي (أبطأ شوية)...")
                    val res = viaModelBatch(ctx, use, clean, srcOf, target, cancelled, { i, tr -> onItem(i, tr) }, ::note) { d ->
                        done += d
                        onProgress(done, total, "gemma")
                    }
                    translated += res.first
                    skipped += res.second
                    failed += res.third
                }
            }
        }
        return BatchResult(translated, skipped, failed, notes)
    }

    /** Returns (translated, skipped, failed). Small numbered batches; whatever is missing or doubtful is asked again, alone. */
    private fun viaModelBatch(
        ctx: Context,
        use: List<Int>,
        clean: List<String>,
        srcOf: Map<Int, String>,
        target: String,
        cancelled: () -> Boolean,
        onItem: (Int, String) -> Unit,
        note: (String) -> Unit,
        progress: (Int) -> Unit,
    ): Triple<Int, Int, Int> {
        var translated = 0
        var skipped = 0
        var failed = 0
        val items = use.map { it to clean[it] }
        for (b in LlmPrompts.batches(items, { it.second.length })) {
            if (cancelled()) break
            // numbered 1..n inside the batch: a small model keeps count far better than with screen-wide ids
            val local = b.mapIndexed { k, p -> (k + 1) to p }
            val srcCodes = b.map { srcOf[it.first] }.toSet()
            val srcName = srcCodes.singleOrNull()?.let { Languages.englishName(it) }
            val parsed: Map<Int, String> = try {
                val raw = Llm.generate(
                    ctx,
                    LlmPrompts.translateSystem(Languages.englishName(target), srcName),
                    LlmPrompts.translateUser(local.map { it.first to it.second.second }),
                    maxTokens = (b.sumOf { it.second.length } * 2 + 80).coerceIn(96, 1500),
                    temperature = 0.1f,
                    topK = 10,
                    timeoutMs = 240_000,
                )
                LlmPrompts.parseTranslations(raw, local.map { it.first })
            } catch (e: Throwable) {
                if (cancelled()) break
                AppLog.e("translate", "model batch", e)
                note(e.message ?: "النموذج فشل")
                emptyMap()
            }
            for ((k, p) in local) {
                if (cancelled()) break
                val idx = p.first
                val original = p.second
                var answer = parsed[k]
                var verdict = answer?.let { TranslateRules.check(original, it, target) }
                if (verdict != Verdict.OK) {
                    // missing or doubtful in the batch: ask for this one alone, once
                    answer = try { viaModel(ctx, original, srcOf[idx], target) } catch (e: Throwable) { null }
                    verdict = answer?.let { TranslateRules.check(original, it, target) }
                }
                when {
                    verdict == Verdict.OK && answer != null -> { cput("$target|$original", answer); onItem(idx, answer); translated++ }
                    verdict == Verdict.UNCHANGED -> skipped++
                    else -> failed++
                }
            }
            progress(b.size)
        }
        return Triple(translated, skipped, failed)
    }
}
