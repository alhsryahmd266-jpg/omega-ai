package com.omega.keyboard.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.omega.keyboard.AppLog
import com.omega.keyboard.Prefs

/**
 * One speech-recognition request at a time, on top of the phone's own recognisers.
 *
 * The first recogniser that answers "language not supported" (or is broken) does not end the request: the next way of the
 * [VoicePlan] is tried silently (Google's recogniser first, other spellings of the language, the on-device model), and only
 * when all of them failed does [Callback.onError] fire. Must be used from the main thread. After [cancel] no callback is
 * delivered any more.
 */
class VoiceController(context: Context) {

    interface Callback {
        fun onReady()
        fun onPartial(text: String)
        fun onResult(text: String)
        /**
         * [needsPermission]: the microphone permission is missing. [code]: the recogniser's own error code
         * (SpeechRecognizer.ERROR_*), or one of the negative codes below for problems before it even started.
         */
        fun onError(message: String, needsPermission: Boolean, code: Int)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var cb: Callback? = null
    private var guard: Runnable? = null
    private var retry: Runnable? = null

    // the ways still to try for this request
    private var plan = ArrayList<VoicePlan.Attempt>()
    private var planIndex = 0
    private val errors = ArrayList<Int>()
    private var gen = 0                       // bumped for every attempt: callbacks of an older recogniser are ignored
    private var sawSpeech = false             // this attempt already heard words: its errors are the user's, not the recogniser's

    @Volatile var listening = false
        private set

    companion object {
        const val CODE_FAILED = VoicePlan.CODE_FAILED
        const val CODE_NO_PERMISSION = VoicePlan.CODE_NO_PERMISSION
        const val CODE_UNAVAILABLE = VoicePlan.CODE_UNAVAILABLE
        const val CODE_STUCK = VoicePlan.CODE_STUCK

        /** the recogniser must say "ready" within this time, otherwise the next way is tried */
        private const val READY_MS = 8_000L
        /** once listening: how long it may stay silent (no words, no answer) before it counts as stuck */
        private const val SILENT_MS = 30_000L

        /** Silence and "could not make out words" are normal for a microphone that stays open. */
        fun isQuiet(code: Int) = code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || code == SpeechRecognizer.ERROR_NO_MATCH

        fun hasPermission(ctx: Context): Boolean =
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        fun available(ctx: Context): Boolean = try {
            SpeechRecognizer.isRecognitionAvailable(ctx)
        } catch (_: Throwable) {
            false
        }

        /** Every speech recogniser installed on the phone, as "package/Class". */
        @Suppress("DEPRECATION")
        fun recognisers(ctx: Context): List<String> = try {
            ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .mapNotNull { it.serviceInfo }
                .map { ComponentName(it.packageName, it.name).flattenToString() }
        } catch (_: Throwable) {
            emptyList()
        }

        /** The recogniser the system itself would pick, "package/Class" (null when unknown). */
        fun systemRecogniser(ctx: Context): String? = try {
            Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")
                ?.takeIf { it.isNotBlank() }
                ?.let { ComponentName.unflattenFromString(it)?.flattenToString() ?: it }
        } catch (_: Throwable) {
            null
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
            12 -> "مفيش خدمة صوت في التليفون بتفهم العربي. ثبّت أو فعّل تطبيق Google"
            13 -> "لغة الصوت دي محتاجة تتحمّل الأول (إعدادات جوجل ← الصوت ← تعرّف الكلام بدون نت)"
            CODE_UNAVAILABLE -> "التليفون مفيهوش خدمة تعرّف على الكلام (محتاج تطبيق Google)"
            CODE_STUCK -> "خدمة الصوت ما ردتش"
            CODE_FAILED -> "مقدرتش أشغّل خدمة الصوت"
            else -> "حصلت مشكلة في الصوت (كود $code)"
        }
    }

    fun start(callback: Callback, langTag: String?, preferOffline: Boolean) {
        cancel()
        if (!hasPermission(app)) { callback.onError("محتاج إذن الميكروفون", true, CODE_NO_PERMISSION); return }
        val found = recognisers(app)
        if (found.isEmpty() && !available(app)) {
            callback.onError(errorText(CODE_UNAVAILABLE), false, CODE_UNAVAILABLE)
            return
        }
        cb = callback
        plan = ArrayList(VoicePlan.build(found, systemRecogniser(app), langTag, preferOffline, Prefs.get(app).voiceGood))
        planIndex = 0
        errors.clear()
        AppLog.d("voice", "plan: " + plan.joinToString(" | ") { it.label })
        runAttempt()
    }

    private fun runAttempt() {
        val a = plan.getOrNull(planIndex)
        if (a == null) { giveUp(); return }
        val g = ++gen
        sawSpeech = false
        AppLog.d("voice", "try ${planIndex + 1}/${plan.size}: ${a.label}")
        val r = try {
            val cn = a.service?.let { ComponentName.unflattenFromString(it) }
            if (cn != null) SpeechRecognizer.createSpeechRecognizer(app, cn) else SpeechRecognizer.createSpeechRecognizer(app)
        } catch (t: Throwable) {
            AppLog.e("voice", "create failed", t)
            attemptFailed(g, CODE_FAILED)
            return
        }
        rec = r
        r.setRecognitionListener(Listener(g))
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            if (a.lang.isNotBlank()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, a.lang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, a.lang)
            }
            if (a.offline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        listening = true
        try {
            r.startListening(i)
        } catch (t: Throwable) {
            AppLog.e("voice", "start failed", t)
            attemptFailed(g, CODE_FAILED)
            return
        }
        armGuard(g, READY_MS)
    }

    /** If the recogniser stays silent (some phones never answer) the request must not stay "listening" forever. */
    private fun armGuard(g: Int, ms: Long) {
        guard?.let { main.removeCallbacks(it) }
        val r = Runnable { if (g == gen && listening) attemptFailed(g, CODE_STUCK) }
        guard = r
        main.postDelayed(r, ms)
    }

    /** This way did not work. Goes on with the next one, or reports the error when none is left. */
    private fun attemptFailed(g: Int, code: Int) {
        if (g != gen || cb == null) return
        val a = plan.getOrNull(planIndex)
        AppLog.d("voice", "failed: ${a?.label} code=$code")
        errors.add(code)
        closeRecogniser()
        var again = !sawSpeech && VoicePlan.tryNext(code)
        if (!sawSpeech && a != null) {
            VoicePlan.offlineRetry(a, code)?.let { extra ->
                if (plan.none { it == extra }) {
                    plan.add(planIndex + 1, extra)
                    again = true
                }
            }
        }
        if (again && planIndex + 1 < plan.size) {
            planIndex++
            val r = Runnable { if (g == gen && cb != null) runAttempt() }
            retry = r
            // a short pause: the previous recogniser has to let go of the microphone first
            main.postDelayed(r, 150)
            return
        }
        giveUp()
    }

    private fun giveUp() {
        val code = VoicePlan.bestError(errors)
        fail(errorText(code), code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, code)
    }

    /** The user is done talking: deliver what was heard so far. */
    fun stop() {
        try { rec?.stopListening() } catch (_: Throwable) {}
    }

    /** Throw everything away; no callback follows. */
    fun cancel() {
        cb = null
        gen++
        retry?.let { main.removeCallbacks(it) }
        retry = null
        closeRecogniser()
    }

    private fun closeRecogniser() {
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

    private fun fail(msg: String, perm: Boolean, code: Int) {
        val c = cb
        cb = null
        gen++
        retry?.let { main.removeCallbacks(it) }
        retry = null
        closeRecogniser()
        c?.onError(msg, perm, code)
    }

    private fun remember(g: Int) {
        val a = plan.getOrNull(planIndex) ?: return
        if (g != gen) return
        try {
            val p = Prefs.get(app)
            if (p.voiceGood != a.key) p.voiceGood = a.key
        } catch (_: Throwable) {
        }
    }

    private inner class Listener(val g: Int) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (g != gen) return
            armGuard(g, SILENT_MS)
            cb?.onReady()
        }

        override fun onBeginningOfSpeech() { if (g == gen) sawSpeech = true }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (g != gen) return
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) {
                sawSpeech = true
                armGuard(g, SILENT_MS)
                cb?.onPartial(t)
            }
        }

        override fun onResults(results: Bundle?) {
            if (g != gen) return
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
            if (t != null) remember(g)
            val c = cb
            cb = null
            gen++
            closeRecogniser()
            if (t == null) c?.onError(errorText(SpeechRecognizer.ERROR_NO_MATCH), false, SpeechRecognizer.ERROR_NO_MATCH) else c?.onResult(t.trim())
        }

        override fun onError(error: Int) {
            if (g != gen) return
            AppLog.d("voice", "recognizer error $error")
            val a = plan.getOrNull(planIndex)
            val another = !sawSpeech && (VoicePlan.tryNext(error) || (a != null && VoicePlan.offlineRetry(a, error) != null))
            if (another) {
                attemptFailed(g, error)
            } else {
                // the user's own outcome (silence, no match, busy, no permission ...): report it as it is
                errors.add(error)
                fail(errorText(error), error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, error)
            }
        }
    }
}
