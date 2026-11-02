package com.omega.keyboard.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.omega.keyboard.AppLog

/**
 * One speech-recognition request at a time, on top of the phone's own recogniser (the Google app).
 * Must be used from the main thread. After [cancel] no callback is delivered any more.
 */
class VoiceController(context: Context) {

    interface Callback {
        fun onReady()
        fun onPartial(text: String)
        fun onResult(text: String)
        /** [needsPermission]: the microphone permission is missing */
        fun onError(message: String, needsPermission: Boolean)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var cb: Callback? = null
    private var guard: Runnable? = null

    @Volatile var listening = false
        private set

    companion object {
        fun hasPermission(ctx: Context): Boolean =
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun available(ctx: Context): Boolean = try {
            SpeechRecognizer.isRecognitionAvailable(ctx)
        } catch (_: Throwable) {
            false
        }

        fun errorText(code: Int): String = when (code) {
            1, 2 -> "مفيش نت كفاية لخدمة الصوت (جرّب تفعّل \"الصوت بدون نت\" من الإعدادات بعد تحميل اللغة)"
            3 -> "مشكلة في تسجيل الصوت من الميكروفون"
            4, 11 -> "خدمة الصوت مش بترد دلوقتي، جرّب تاني"
            5 -> "خدمة الصوت وقفت، جرّب تاني"
            6 -> "ما سمعتش صوت، اتكلم بعد ما أقولك"
            7 -> "ما فهمتش الكلام، جرّب تاني"
            8 -> "الميكروفون مشغول، جرّب تاني بعد ثانية"
            9 -> "محتاج إذن الميكروفون"
            10 -> "طلبات كتير ورا بعض، استنى شوية"
            12 -> "لغة الصوت دي مش مدعومة"
            13 -> "لغة الصوت دي محتاجة تتحمّل الأول (إعدادات جوجل ← الصوت ← تعرّف الكلام بدون نت)"
            else -> "حصلت مشكلة في الصوت (كود $code)"
        }
    }

    fun start(callback: Callback, langTag: String?, preferOffline: Boolean) {
        cancel()
        if (!hasPermission(app)) { callback.onError("محتاج إذن الميكروفون", true); return }
        if (!available(app)) {
            callback.onError("التليفون مفيهوش خدمة تعرّف على الكلام (محتاج تطبيق Google)", false)
            return
        }
        cb = callback
        val r = try {
            SpeechRecognizer.createSpeechRecognizer(app)
        } catch (t: Throwable) {
            AppLog.e("voice", "create failed", t)
            fail("مقدرتش أشغّل خدمة الصوت", false)
            return
        }
        rec = r
        r.setRecognitionListener(listener)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            if (!langTag.isNullOrBlank()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag)
            }
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        listening = true
        try {
            r.startListening(i)
        } catch (t: Throwable) {
            AppLog.e("voice", "start failed", t)
            fail("مقدرتش أبدأ التسجيل", false)
            return
        }
        // if the recogniser never answers (some phones), do not stay "listening" forever
        val g = Runnable { if (listening) { stopInternal(); cb?.let { c -> cb = null; c.onError("خدمة الصوت ما ردتش", false) } } }
        guard = g
        main.postDelayed(g, 30_000)
    }

    /** The user is done talking: deliver what was heard so far. */
    fun stop() {
        try { rec?.stopListening() } catch (_: Throwable) {}
    }

    /** Throw everything away; no callback follows. */
    fun cancel() {
        cb = null
        stopInternal()
    }

    private fun stopInternal() {
        guard?.let { main.removeCallbacks(it) }
        guard = null
        listening = false
        val r = rec
        rec = null
        if (r != null) {
            try { r.setRecognitionListener(null) } catch (_: Throwable) {}
            try { r.cancel() } catch (_: Throwable) {}
            main.post { try { r.destroy() } catch (_: Throwable) {} }
        }
    }

    private fun fail(msg: String, perm: Boolean) {
        val c = cb
        cb = null
        stopInternal()
        c?.onError(msg, perm)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { cb?.onReady() }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) cb?.onPartial(t)
        }

        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
            val c = cb
            cb = null
            stopInternal()
            if (t == null) c?.onError(errorText(7), false) else c?.onResult(t.trim())
        }

        override fun onError(error: Int) {
            AppLog.d("voice", "recognizer error $error")
            fail(errorText(error), error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        }
    }
}
