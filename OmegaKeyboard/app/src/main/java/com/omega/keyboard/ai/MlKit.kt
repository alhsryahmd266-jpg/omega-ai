package com.omega.keyboard.ai

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit

/**
 * Google ML Kit on-device translation (59 languages) and language detection: the fast path.
 * Everything here blocks; call from a background thread.
 */
object MlKit {
    private val translators = HashMap<String, Translator>()
    private var identifier: LanguageIdentifier? = null

    /** Does ML Kit know this language code? */
    fun supports(lang: String): Boolean = TranslateLanguage.fromLanguageTag(lang) != null

    /** BCP-47 code of the text's language, or null if it can't tell. */
    @Synchronized
    fun detect(text: String): String? {
        val t = text.trim()
        if (t.length < 2) return null
        val id = identifierLocked()
        val code: String? = Tasks.await(id.identifyLanguage(t.take(400)), 10, TimeUnit.SECONDS)
        return if (code.isNullOrBlank() || code == "und") null else code
    }

    /** Detects many texts at once (all requests are started first, so this is much faster than one by one). */
    fun detectMany(texts: List<String>): List<String?> {
        val id = identifierLocked()
        val tasks: List<Task<String>?> = texts.map { t ->
            val s = t.trim()
            if (s.length < 2) null else id.identifyLanguage(s.take(300))
        }
        return tasks.map { t ->
            try {
                if (t == null) null else {
                    val c: String? = Tasks.await(t, 12, TimeUnit.SECONDS)
                    if (c.isNullOrBlank() || c == "und") null else c
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    @Synchronized
    private fun identifierLocked(): LanguageIdentifier = identifier ?: LanguageIdentification
        .getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build())
        .also { identifier = it }

    @Synchronized
    private fun client(src: String, dst: String): Translator = translators.getOrPut("$src>$dst") {
        Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build()
        )
    }

    /** Downloads the language packs for this pair if they are not on the phone yet (needs internet once). */
    fun prepare(src: String, dst: String, wifiOnly: Boolean = false, timeoutSec: Long = 240) {
        val s = TranslateLanguage.fromLanguageTag(src) ?: throw IllegalArgumentException("لغة مش مدعومة: $src")
        val d = TranslateLanguage.fromLanguageTag(dst) ?: throw IllegalArgumentException("لغة مش مدعومة: $dst")
        val cond = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        Tasks.await(client(s, d).downloadModelIfNeeded(cond), timeoutSec, TimeUnit.SECONDS)
    }

    fun translate(text: String, src: String, dst: String, timeoutSec: Long = 30): String {
        val s = TranslateLanguage.fromLanguageTag(src) ?: throw IllegalArgumentException("لغة مش مدعومة: $src")
        val d = TranslateLanguage.fromLanguageTag(dst) ?: throw IllegalArgumentException("لغة مش مدعومة: $dst")
        if (s == d) return text
        val out: String? = Tasks.await(client(s, d).translate(text), timeoutSec, TimeUnit.SECONDS)
        return out ?: ""
    }

    /** Language packs that are already on the phone. */
    fun downloaded(): Set<String> = try {
        val models: Set<TranslateRemoteModel> =
            Tasks.await(RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java), 15, TimeUnit.SECONDS)
        models.map { it.language }.toSet()
    } catch (_: Throwable) {
        emptySet()
    }

    @Synchronized
    fun release() {
        for (t in translators.values) try { t.close() } catch (_: Throwable) {}
        translators.clear()
        try { identifier?.close() } catch (_: Throwable) {}
        identifier = null
    }
}
