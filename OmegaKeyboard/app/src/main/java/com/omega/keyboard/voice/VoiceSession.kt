package com.omega.keyboard.voice

import android.Manifest
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.omega.keyboard.AppLog
import com.omega.keyboard.Prefs

/**
 * Dictation through the phone's own recogniser: the keyboard's microphone listens once and types what was said.
 * (There are no spoken orders any more; autonomous mode is a single button.) Main thread only.
 */
object VoiceSession {

    interface Sink {
        /** [active]: the microphone is open. [text]: what to show (hint, partial speech, result or error). */
        fun onVoiceState(active: Boolean, text: String, warn: Boolean)
        fun onDictation(text: String)
    }

    @Volatile var active = false
        private set
    private var controller: VoiceController? = null
    private var sink: Sink? = null
    private var lastPartial = ""
    private var stopping = false
    private val main = Handler(Looper.getMainLooper())
    private val forceStop = Runnable { finishNow() }

    /** Tap on the keyboard's microphone: starts listening, or (when already listening) finishes and uses what was said. */
    fun listenForKeyboard(ctx: Context, sink: Sink, langHint: String) {
        if (active) { stopListening(); return }
        this.sink = sink
        begin(ctx, langHint)
    }

    /**
     * The user pressed "stop". The recogniser is asked to finish so the last words are kept, but we never wait for it: after
     * a short grace period (or on a second press) the microphone is closed for good and what was heard so far is typed.
     */
    fun stopListening() {
        if (!active) return
        if (stopping) { finishNow(); return }
        stopping = true
        controller?.stop()
        main.removeCallbacks(forceStop)
        main.postDelayed(forceStop, 700)
    }

    /** Close the microphone now and type what was heard so far. */
    private fun finishNow() {
        main.removeCallbacks(forceStop)
        if (!active) return
        val heard = lastPartial.trim()
        val c = controller
        reset()
        c?.cancel()
        val s = sink
        s?.onVoiceState(false, "", false)
        if (heard.isNotEmpty()) s?.onDictation(heard)
    }

    private fun reset() {
        active = false
        stopping = false
        lastPartial = ""
        controller = null
    }

    /** Abort without using anything. */
    fun cancel() {
        main.removeCallbacks(forceStop)
        val c = controller
        val wasActive = active
        reset()
        c?.cancel()
        if (wasActive) sink?.onVoiceState(false, "", false)
    }

    fun detach(s: Sink) { if (sink === s) { sink = null } }

    private fun resolveLang(pref: String, hint: String): String = when {
        pref != "auto" && pref.isNotBlank() -> pref
        hint.startsWith("ar") -> "ar-EG"
        hint == "en" -> "en-US"
        else -> hint
    }

    private fun begin(ctx: Context, langHint: String) {
        val app = ctx.applicationContext
        val prefs = Prefs.get(app)
        val c = VoiceController(app)
        controller = c
        active = true
        stopping = false
        lastPartial = ""
        sink?.onVoiceState(true, "بسمعك...", false)
        c.start(object : VoiceController.Callback {
            override fun onReady() { if (active && !stopping) sink?.onVoiceState(true, "اتكلم دلوقتي...", false) }
            override fun onPartial(text: String) {
                if (!active) return
                lastPartial = text
                if (!stopping) sink?.onVoiceState(true, text, false)
            }
            override fun onResult(text: String) {
                if (!active) return
                main.removeCallbacks(forceStop)
                reset()
                AppLog.d("voice", "heard: $text")
                val said = text.trim()
                val s = sink
                // leave the "listening" state first: the strip used to keep its stop button after a successful result
                s?.onVoiceState(false, "", false)
                if (said.isEmpty()) s?.onVoiceState(false, "ما سمعتش كلام", true) else s?.onDictation(said)
            }
            override fun onError(message: String, needsPermission: Boolean, code: Int) {
                if (!active) return
                main.removeCallbacks(forceStop)
                val heard = lastPartial.trim()
                val wasStopping = stopping
                reset()
                val s = sink
                if (wasStopping && heard.isNotEmpty()) {
                    // the user pressed stop and the recogniser had nothing final to say: type what was heard
                    s?.onVoiceState(false, "", false)
                    s?.onDictation(heard)
                    return
                }
                s?.onVoiceState(false, if (wasStopping) "" else message, !wasStopping)
                if (needsPermission) ActionActivity.requestPermissions(app, arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        }, resolveLang(prefs.voiceLang, langHint), prefs.voiceOffline)
    }
}
